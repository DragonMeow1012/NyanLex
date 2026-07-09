package com.dragonmeow.mctranslator.cache;

import com.dragonmeow.mctranslator.translate.ChurnGuard;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.TemplateText;
import com.dragonmeow.mctranslator.translate.TextFilter;
import com.dragonmeow.mctranslator.translate.Translator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Per-engine translation cache and request scheduler.
 *
 * <p>Request-count design (the expensive resource is HTTP round-trips):</p>
 * <ul>
 *   <li><b>Keys are normalised</b>: outer whitespace is stripped and volatile
 *       fragments (numbers, player names, URLs…) are replaced by {@link TemplateText}
 *       placeholders, so cosmetic variants share one request and one cache entry.
 *       The service layer re-applies the original outer whitespace on render.</li>
 *   <li><b>Per-tick coalescing with a settle window</b>: render-path misses
 *       ({@link #requestBatched}) and chat/callback requests ({@link #requestCoalesced})
 *       accumulate in buffers. {@link #flushBatch} (called once per client tick) waits
 *       until the buffers stop growing — up to {@link #MAX_FLUSH_WAIT_TICKS} — then sends
 *       ONE batched request for everything, instead of a burst of small requests while
 *       a screen is still populating.</li>
 *   <li><b>Single-flight everywhere</b>: in-flight keys are tracked ({@link #pending},
 *       {@link #pendingSingles}) so duplicates attach callbacks instead of re-requesting;
 *       failures back off for {@link #failureBackoffMs} before any retry.</li>
 *   <li><b>Two storage tiers + read-through fallback</b>: bounded in-memory LRU over an
 *       optional {@link PersistentStore}; an optional {@link #setFallback fallback cache}
 *       (the AI cache, for the Google cache) is consulted on miss so a string already
 *       fine-translated by AI is never re-bought from Google.</li>
 * </ul>
 */
public final class TranslationCache {

    private final Translator translator;
    private volatile String targetLang;
    private final Executor executor;
    private final LongSupplier clock;
    private final long failureBackoffMs;

    private final PersistentStore store;

    private final Map<String, String> cache;

    /** Read-only sibling cache consulted on miss (AI results reused by the Google engine). */
    private volatile TranslationCache fallback;

    /** Keys currently in flight via a fire-and-forget batch. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /** Keys currently in flight with callbacks attached (single or coalesced). */
    private final Map<String, PendingSingle> pendingSingles = new ConcurrentHashMap<>();

    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();

    /** Animated/flashing-text detector consulted at every request-enqueue throat; a key
     *  on cooldown is silently dropped (the surface keeps showing the original text).
     *  Enabled with built-in defaults; replaceable via {@link #setChurnGuard} (null = off). */
    private volatile ChurnGuard churnGuard = new ChurnGuard();

    /** A callback plus the exact raw text it was registered for (needed to restore
     *  that raw text's own placeholder values on delivery). */
    private static final class PendingCallback {
        final String source;
        final Consumer<String> callback;
        final boolean always;

        PendingCallback(String source, Consumer<String> callback, boolean always) {
            this.source = source;
            this.callback = callback;
            this.always = always;
        }
    }

    /** Callback collection that can be atomically closed, so late attachers know the
     *  request already completed (and must deliver from cache instead). */
    private static final class CallbackList {
        private final List<PendingCallback> list = new ArrayList<>();
        private boolean closed;

        synchronized boolean add(PendingCallback pc) {
            if (closed) return false;
            if (pc != null) list.add(pc);
            return true;
        }

        synchronized List<PendingCallback> close() {
            closed = true;
            return new ArrayList<>(list);
        }
    }

    private static final class PendingSingle {
        final CallbackList callbacks = new CallbackList();
    }

    /** A queued (not yet flushed) callback request. */
    private static final class QueuedRequest {
        final String source;
        final CallbackList callbacks = new CallbackList();

        QueuedRequest(String source) {
            this.source = source;
        }
    }

    public TranslationCache(Translator translator, String targetLang, Executor executor, int maxSize) {
        this(translator, targetLang, executor, maxSize, 10_000L, System::currentTimeMillis, null);
    }

    public TranslationCache(Translator translator, String targetLang, Executor executor,
                            int maxSize, long failureBackoffMs, LongSupplier clock) {
        this(translator, targetLang, executor, maxSize, failureBackoffMs, clock, null);
    }

    public TranslationCache(Translator translator, String targetLang, Executor executor,
                            int maxSize, long failureBackoffMs, LongSupplier clock, PersistentStore store) {
        this.translator = translator;
        this.targetLang = targetLang;
        this.executor = executor;
        this.failureBackoffMs = Math.max(0L, failureBackoffMs);
        this.clock = clock;
        this.store = store;

        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxSize;
            }
        });
    }

    /** Sibling cache consulted read-only on miss. Never set both directions. */
    public void setFallback(TranslationCache fallback) {
        this.fallback = (fallback == this) ? null : fallback;
    }

    /** Optional override of the churn guard (custom thresholds / fake clock in tests);
     *  {@code null} disables churn detection entirely. */
    public void setChurnGuard(ChurnGuard churnGuard) {
        this.churnGuard = churnGuard;
    }

    // ---- provisional (GT 暫代) entries: fallback results awaiting an AI redo ----

    /** Keys whose stored value came from the dispatcher's FALLBACK backend (GT standing in
     *  for the AI engine while its 429 gate is closed). Shown normally, but re-asked of the
     *  AI on a later hit once the retry gate opens. Only the AI cache's translator ever
     *  produces fallback-marked results, so the Google cache never populates this. */
    private final Set<String> provisional = ConcurrentHashMap.newKeySet();

    /** Single-flight guard for provisional retries. */
    private final Set<String> provisionalRetrying = ConcurrentHashMap.newKeySet();

    /** Non-null only on the AI cache: true when the AI is worth re-asking (keys configured
     *  AND its global 429 gate open). {@code null} = provisional retries disabled. */
    private volatile java.util.function.BooleanSupplier provisionalRetryGate;

    public void setProvisionalRetryGate(java.util.function.BooleanSupplier gate) {
        this.provisionalRetryGate = gate;
    }

    private void markProvisional(String key, boolean prov) {
        if (prov) provisional.add(key);
        else provisional.remove(key);
    }

    /** Whether {@code key}'s stored value is a GT stand-in (memory mark or disk "g":1). */
    private boolean isProvisionalKey(String key) {
        if (provisional.contains(key)) return true;
        if (store != null && store.isProvisional(key)) {
            provisional.add(key); // promote alongside the disk-tier value
            return true;
        }
        return false;
    }

    /**
     * On a cache hit of a provisional entry: schedule ONE async AI redo when the retry gate
     * is open and the key is neither backing off nor already in flight. No HTTP ever runs
     * on the caller's (render) thread — the work goes to the existing executor. Success
     * overwrites the value, clears the mark and rebuilds the de-styled copy; failure (or a
     * result that is itself another fallback) records {@link #failedUntil} so later hits
     * stay quiet until the backoff expires. No timers, no polling: the next HIT retries.
     */
    private void maybeRetryProvisional(String key) {
        java.util.function.BooleanSupplier gate = provisionalRetryGate;
        if (gate == null || !isProvisionalKey(key)) return;
        if (!gate.getAsBoolean() || isBackingOff(key)) return;
        if (!provisionalRetrying.add(key)) return; // single-flight per key
        executor.execute(() -> {
            try {
                TemplateText.Prepared prepared = TemplateText.prepare(norm(key));
                TranslationResult r = translator.translate(prepared.text(), targetLang);
                String out = r.translatedText();
                if (!r.fromFallback() && isUsableTranslation(prepared.text(), out)) {
                    store(key, out, false); // the AI answered: overwrite + unmark + rebuild copies
                    failedUntil.remove(prepared.text());
                    failedUntil.remove(key);
                } else {
                    recordFailure(key); // still a GT stand-in: keep it, back this key off
                }
            } catch (TranslationException | RuntimeException e) {
                recordFailure(key);
            } finally {
                provisionalRetrying.remove(key);
            }
        });
    }

    /** True when the churn guard says this key is animated/flashing text on cooldown:
     *  the request is dropped, behaving exactly like a not-yet-translated line. */
    private boolean churnSuppressed(String key) {
        ChurnGuard guard = churnGuard;
        return guard != null && guard.shouldSuppress(key);
    }

    // ---- de-styled second tier (⟦CS#⟧ markers, literal § codes, centring padding) ----

    /** A well-formed ⟦CS#⟧ / ⟦/CS#⟧ colour-run marker as injected by the render glue's
     *  markChatContent (spaces tolerated, matching the glue's own MARKER pattern). Kept as a
     *  private local so {@link TemplateText}'s placeholder grammar stays untouched. */
    private static final java.util.regex.Pattern CS_MARKER =
            java.util.regex.Pattern.compile("\\u27E6\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7");

    /** Marker residue: a "CS#" body whose ⟦/⟧ bracket(s) a translator ate (both optional).
     *  Mirrors the glue's stripMarkerResidue detection — run AFTER removing well-formed
     *  markers, any remaining match means the value is poisoned. */
    private static final java.util.regex.Pattern CS_RESIDUE =
            java.util.regex.Pattern.compile("\\u27E6?\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7?");

    /** A ⟦MT#⟧ placeholder token (removed before the "any letters left?" skeleton check —
     *  its 'M'/'T' letters must not make a pure-number line look like real text). */
    private static final java.util.regex.Pattern MT_TOKEN =
            java.util.regex.Pattern.compile("\\u27E6MT\\d+\\u27E7");

    /** A run of ASCII whitespace (Java's default non-Unicode \s) — full-width spaces are
     *  real content and stay. Collapsed to ONE space in tier-2 keys so centring padding
     *  ("§f                 §aHypixel…") stops minting new keys. */
    private static final java.util.regex.Pattern WS_RUN =
            java.util.regex.Pattern.compile("\\s+");

    private static String stripCsMarkers(String text) {
        return CS_MARKER.matcher(text).replaceAll("");
    }

    /** Canonical de-styled form of a line, used as the tier-2 key: ⟦CS#⟧ markers out →
     *  literal § sequences out ({@link TextFilter#stripSectionCodes}, applied AFTER CS
     *  stripping so a marker bracket is never eaten) → ASCII whitespace runs collapsed to
     *  one space → outer whitespace stripped. Idempotent, so the copy recursion terminates. */
    private static String styleStripped(String text) {
        String out = TextFilter.stripSectionCodes(stripCsMarkers(text));
        return WS_RUN.matcher(out).replaceAll(" ").strip();
    }

    /**
     * Re-cut a translation's number slots against the DE-STYLED source's values. The value's
     * original ⟦MT#⟧ slots were made by templating the §-laden source, where a colour code
     * fuses with ("§65" → one slot "65") or blocks ("§b5" → no slot) the real number — so
     * the original slots cannot be reused as-is. {@code restored} must carry the REAL
     * numbers (original values substituted back); each de-styled value is located in order
     * via the §-code-skipping projection of the text and replaced by its ⟦MT#⟧ token,
     * leaving every § sequence in place ("§65" → "§6⟦MT0⟧": colour kept, number slotted).
     * Returns {@code null} when alignment fails (reordered translation, number split by a
     * § code) — the caller then silently skips the copy instead of force-fitting.
     */
    private static String retokenize(String restored, List<String> values) {
        if (values.isEmpty()) return restored;
        StringBuilder proj = new StringBuilder(restored.length());
        int[] rawAt = new int[restored.length()];
        for (int i = 0; i < restored.length(); i++) {
            char c = restored.charAt(i);
            if (c == '§' && i + 1 < restored.length()) {
                i++; // skip the § pair: it is style, not text
                continue;
            }
            rawAt[proj.length()] = i;
            proj.append(c);
        }
        String projection = proj.toString();
        StringBuilder out = new StringBuilder(restored);
        int searchFrom = 0;
        int shift = 0;
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            int at = projection.indexOf(value, searchFrom);
            if (at < 0) return null;
            int rawStart = rawAt[at];
            int rawEnd = rawAt[at + value.length() - 1] + 1;
            if (rawEnd - rawStart != value.length()) return null; // a § code splits the run
            String token = "⟦MT" + i + "⟧";
            out.replace(rawStart + shift, rawEnd + shift, token);
            shift += token.length() - value.length();
            searchFrom = at + value.length();
        }
        return out.toString();
    }

    // ---- lookup ----

    public String getCached(String source) {
        if (source == null) return null;
        String value = getStored(source);
        if (value != null) {
            maybeRetryProvisional(source);
            return value;
        }
        String normalized = norm(source);
        if (!normalized.equals(source)) {
            value = getStored(normalized);
            if (value != null) {
                maybeRetryProvisional(normalized);
                return value;
            }
        }
        TemplateText.Prepared prepared = TemplateText.prepare(normalized);
        if (prepared.changed()) {
            String templated = getStored(prepared.text());
            if (templated != null) {
                String restored = prepared.restore(templated);
                if (isUsableTranslation(restored)) {
                    // Symmetric write gate: only materialise what the READ side (the 2-arg
                    // check in getStored) would accept for this exact key — otherwise the
                    // read path discards the entry and this path re-appends it on the next
                    // frame, an unbounded disk-append loop (the 30,892-line tab-header
                    // incident). A value failing the stricter source-aware check is still
                    // RETURNED — the service-level decide() re-judges it — just never
                    // written back to memory or disk.
                    if (isUsableTranslation(source, restored)) {
                        // The materialised alias inherits the templated key's provisional
                        // state, so a later raw-key hit still knows to re-ask the AI.
                        putStored(source, restored, isProvisionalKey(prepared.text()));
                    }
                    maybeRetryProvisional(prepared.text());
                    return restored;
                }
            }
        }
        TranslationCache fb = fallback;
        if (fb != null) {
            String fromSibling = fb.getCached(source); // sibling schedules its own retry
            if (fromSibling != null) {
                cache.put(source, fromSibling); // memory only: keep the sibling's disk file authoritative
                return fromSibling;
            }
        }
        String stripped = styleStripped(source);
        if (!stripped.equals(normalized)) {
            // Second tier: ⟦CS#⟧ markers, literal § colour codes and centring padding are
            // STYLE, not text — servers bake them into the string, so every recolour /
            // gradient shift / padding change mints a brand-new key for the SAME words and
            // re-buys the translation. Look up the de-styled, templated text instead. The
            // value keeps the FIRST translation's literal § codes (deliberate trade-off:
            // quota over per-variant colours; CS-marked chat comes back plain and is
            // re-coloured by the glue's marked-mode fallback). Deliberately no
            // materialisation: one entry per style permutation would bloat the memory LRU
            // and the disk store.
            TemplateText.Prepared prepared2 = TemplateText.prepare(stripped);
            String value2 = getStored(prepared2.text());
            if (value2 != null) {
                maybeRetryProvisional(prepared2.text());
            } else if (fb != null) {
                value2 = fb.getStored(prepared2.text());
                if (value2 != null) fb.maybeRetryProvisional(prepared2.text());
            }
            if (value2 != null) {
                String restored = prepared2.restore(value2);
                if (isUsableTranslation(restored)) return restored;
            }
        }
        return null;
    }

    private String getStored(String key) {
        String value = cache.get(key);
        if (value != null) {
            if (isUsableTranslation(key, value)) return value;
            discardStored(key);
        }
        if (store != null) {
            String fromDisk = store.get(key);
            if (fromDisk != null) {
                if (!isUsableTranslation(key, fromDisk)) {
                    discardStored(key);
                    return null;
                }
                cache.put(key, fromDisk);
                return fromDisk;
            }
        }
        return null;
    }

    private void putStored(String key, String translated) {
        putStored(key, translated, false);
    }

    private void putStored(String key, String translated, boolean prov) {
        if (!isUsableTranslation(translated)) return;
        cache.put(key, translated);
        markProvisional(key, prov);
        if (store != null) {
            store.put(key, translated, prov);
        }
    }

    /** Store a translation under the normalised/templated key AND the raw source. */
    private void store(String source, String translated) {
        store(source, translated, false);
    }

    /** {@link #store(String, String)} with the value's provisional (GT stand-in) state —
     *  every key written for this value (templated, raw alias, de-styled copy) carries it. */
    private void store(String source, String translated, boolean prov) {
        String normalized = norm(source);
        TemplateText.Prepared prepared = TemplateText.prepare(normalized);
        if (prepared.changed()) {
            putStored(prepared.text(), translated, prov);
            String restored = prepared.restore(translated);
            if (isUsableTranslation(restored)) putStored(source, restored, prov);
        } else {
            putStored(normalized, translated, prov);
            if (!normalized.equals(source)) {
                cache.put(source, translated); // memory alias only
                markProvisional(source, prov);
            }
        }
        storeStyleStrippedCopy(source, translated, prov);
    }

    /**
     * After a styled line (⟦CS#⟧ markers, literal § codes, or padding runs) is stored, also
     * store its DE-STYLED text so every restyled variant of the same words hits the
     * second-tier lookup in {@link #getCached} instead of re-buying the translation. The
     * copy's value keeps the literal § codes (variant hits show the first translation's
     * colours — quota over colour fidelity) but drops the CS markers; its ⟦MT#⟧ slots are
     * re-cut against the de-styled key via {@link #retokenize}. Silently skipped when the
     * value carries bare "CS#" residue (a translator ate the ⟦⟧ brackets — poison), when
     * the de-styled source has no letters left after templating (a pure number/symbol
     * skeleton is a useless key), when the value is an untranslated identity ECHO of the
     * source (Google's preservesTokens fallback), or when the slots cannot be aligned.
     * The recursive {@code store} call cannot recurse again: {@link #styleStripped} is
     * idempotent.
     */
    private void storeStyleStrippedCopy(String source, String translated, boolean prov) {
        if (translated == null) return;
        String strippedSource = styleStripped(source);
        if (strippedSource.equals(norm(source))) return; // nothing de-styled: no copy needed
        String strippedValue = stripCsMarkers(translated);
        if (CS_RESIDUE.matcher(strippedValue).find()) return;
        TemplateText.Prepared prepSrc = TemplateText.prepare(strippedSource);
        String skeleton = MT_TOKEN.matcher(prepSrc.text()).replaceAll("");
        if (skeleton.codePoints().noneMatch(Character::isLetter)) return;
        TemplateText.Prepared prepOrig = TemplateText.prepare(norm(source));
        String restoredValue = prepOrig.changed() ? prepOrig.restore(strippedValue) : strippedValue;
        // Identity-echo guard: the Google backend's preservesTokens gate returns its INPUT
        // on failure — an echo with pristine markers. De-styled, that echo is clean source
        // text, so CS_RESIDUE cannot catch it, and a copy would pin "value = untranslated
        // source" under the de-styled key: every later styled variant would hit it, never
        // re-request, and the fake entry would spread across engines via the fallback
        // chain. If the value equals the source once both are de-styled, nothing was
        // translated — build no copy. (The marked key's own store is untouched: pre-existing
        // d141b32 behaviour, out of scope here.)
        if (styleStripped(restoredValue).equals(strippedSource)) return;
        String copyValue = retokenize(restoredValue, prepSrc.values());
        if (copyValue == null) return;
        store(strippedSource, copyValue, prov);
    }

    // ---- blocking (pre-translate warm-up only) ----

    public String translateBlocking(String source) {
        String cached = getCached(source);
        if (cached != null) return cached;
        TemplateText.Prepared prepared = TemplateText.prepare(norm(source));
        try {
            TranslationResult r = translator.translate(prepared.text(), targetLang);
            String out = r.translatedText();
            if (isUsableTranslation(prepared.text(), out)) {
                store(source, out, r.fromFallback());
                failedUntil.remove(prepared.text());
                return prepared.restore(out);
            }
            recordFailure(prepared.text());
            return null;
        } catch (TranslationException e) {
            recordFailure(prepared.text());
            return null;
        }
    }

    // ---- immediate async singles (screen-scan hotkey, tests) ----

    public void requestAsync(String source) {
        requestAsync(source, null);
    }

    /** Fire-and-callback single request, dispatched immediately (no tick coalescing). */
    public void requestAsync(String source, Consumer<String> onSuccess) {
        String cached = getCached(source);
        if (cached != null) {
            if (onSuccess != null) onSuccess.accept(cached);
            return;
        }
        if (isBackingOff(requestKey(source))) return;
        requestSingle(source, onSuccess, false);
    }

    /** Like {@link #requestAsync} but the callback always fires (null on failure). */
    public void translateAsyncAlways(String source, Consumer<String> onResult) {
        String cached = getCached(source);
        if (cached != null) {
            onResult.accept(cached);
            return;
        }
        if (isBackingOff(requestKey(source))) {
            onResult.accept(null);
            return;
        }
        requestSingle(source, onResult, true);
    }

    private void requestSingle(String source, Consumer<String> callback, boolean always) {
        String key = requestKey(source);
        PendingCallback pc = callback == null ? null : new PendingCallback(source, callback, always);
        PendingSingle ours = new PendingSingle();
        ours.callbacks.add(pc);
        PendingSingle existing = pendingSingles.putIfAbsent(key, ours);
        if (existing != null) {
            if (!existing.callbacks.add(pc)) deliver(pc, getCached(source)); // raced completion
            return;
        }
        executor.execute(() -> {
            try {
                if (getCached(source) == null) {
                    TranslationResult r = translator.translate(key, targetLang);
                    String out = r.translatedText();
                    if (isUsableTranslation(key, out)) {
                        store(source, out, r.fromFallback());
                        failedUntil.remove(key);
                    } else {
                        recordFailure(key);
                    }
                }
            } catch (TranslationException ignored) {
                recordFailure(key);
            } catch (RuntimeException ignored) {
                // deliver nulls below
            } finally {
                pendingSingles.remove(key, ours);
                for (PendingCallback cb : ours.callbacks.close()) {
                    deliver(cb, getCached(cb.source));
                }
            }
        });
    }

    // ---- coalesced singles (chat & screen-scan: join the per-tick batch) ----

    /**
     * Callback request that joins the next per-tick batch instead of firing its own
     * HTTP request — chat floods (MOTD, plugin spam) collapse into one round-trip.
     * Requires {@link #flushBatch} to be pumped every tick. When {@code always} is
     * true the callback also fires with {@code null} on failure/untranslatable.
     */
    public void requestCoalesced(String source, Consumer<String> callback, boolean always) {
        String cached = getCached(source);
        if (cached != null) {
            if (callback != null) callback.accept(cached);
            return;
        }
        String key = requestKey(source);
        if (key.isEmpty() || isBackingOff(key) || churnSuppressed(key)) {
            if (callback != null && always) callback.accept(null);
            return;
        }
        PendingCallback pc = callback == null ? null : new PendingCallback(source, callback, always);
        while (true) {
            PendingSingle inflight = pendingSingles.get(key);
            if (inflight != null) {
                if (inflight.callbacks.add(pc)) return;   // attached to the in-flight request
                deliver(pc, getCached(source));           // it just completed
                return;
            }
            QueuedRequest queued = queuedSingles.computeIfAbsent(key, k -> new QueuedRequest(source));
            if (queued.callbacks.add(pc)) {
                batchGrew = true;
                return;
            }
            // That queue entry was drained by a concurrent flush; re-check in-flight state.
        }
    }

    // ---- fire-and-forget batch requests (render surfaces) ----

    public boolean warmBatch(List<String> sources) {
        return warmBatch(sources, Set.of(), null);
    }

    /** Like {@link #warmBatch(List)} but forwards the surface's FULL line list (nullable) to
     *  the translator as shared context — see {@link Translator#translateBatch(List, String, List)}. */
    public boolean warmBatch(List<String> sources, List<String> surfaceLines) {
        return warmBatch(sources, Set.of(), surfaceLines);
    }

    private boolean warmBatch(List<String> sources, Set<String> allowPendingKeys) {
        return warmBatch(sources, allowPendingKeys, null);
    }

    /**
     * Synchronously translate every not-yet-cached source in one batched request.
     * {@code allowPendingKeys} lets {@link #flushBatch} include keys it has itself
     * just registered as in-flight singles. {@code surfaceLines} (nullable) is the
     * complete surface (e.g. whole tooltip) the sources came from; it is normalised
     * like the request lines and passed to the translator as context, so lines that
     * are already cached (e.g. the tooltip title) still steer the translation.
     */
    private boolean warmBatch(List<String> sources, Set<String> allowPendingKeys, List<String> surfaceLines) {
        // Group raw variants by normalised request key: one key = one line in the request.
        LinkedHashMap<String, List<String>> rawByKey = new LinkedHashMap<>();
        for (String s : sources) {
            if (s == null || getCached(s) != null) continue;
            String key = requestKey(s);
            if (key.isEmpty() || isBackingOff(key) || churnSuppressed(key)) continue;
            if (!allowPendingKeys.contains(key) && pendingSingles.containsKey(key)) continue;
            rawByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(s);
        }
        List<String> todo = new ArrayList<>(rawByKey.keySet());
        if (todo.isEmpty()) return true;
        try {
            List<TranslationResult> results = translator.translateBatch(todo, targetLang, contextKeys(surfaceLines));
            if (results.size() != todo.size()) {
                for (String s : todo) recordFailure(s);
                return false;
            }
            Map<String, String> toPersist = new java.util.HashMap<>();
            Set<String> provKeys = new java.util.HashSet<>();
            for (int i = 0; i < todo.size(); i++) {
                String key = todo.get(i);
                String out = results.get(i).translatedText();
                boolean prov = results.get(i).fromFallback();
                if (isUsableTranslation(key, out)) {
                    cache.put(key, out);
                    markProvisional(key, prov);
                    if (prov) provKeys.add(key);
                    failedUntil.remove(key);
                    toPersist.put(key, out);
                    for (String raw : rawByKey.get(key)) {
                        String restored = TemplateText.prepare(norm(raw)).restore(out);
                        if (isUsableTranslation(restored)) {
                            cache.put(raw, restored);
                            markProvisional(raw, prov);
                            if (prov) provKeys.add(raw);
                            toPersist.put(raw, restored);
                        }
                    }
                    // Batched requests bypass store(), so build the de-styled copy here too —
                    // coalesced chat / tooltip-group lines are exactly the styled callers.
                    // Pass a RAW variant (not the templated key: its slots already replaced
                    // the numbers, so the § fusion repair in retokenize needs the raw form).
                    storeStyleStrippedCopy(rawByKey.get(key).get(0), out, prov);
                } else {
                    recordFailure(key);
                }
            }
            if (store != null && !toPersist.isEmpty()) {
                store.putBatch(toPersist, provKeys);
            }
            return true;
        } catch (TranslationException e) {
            for (String s : todo) recordFailure(s);
            return false;
        }
    }

    /** Async warm of a whole surface (e.g. one tooltip) in a single request, immediately —
     *  the user is hovering NOW, so this does not wait for the tick coalescer. */
    public void warmBatchAsync(List<String> sources) {
        warmBatchAsync(sources, null);
    }

    /** Like {@link #warmBatchAsync(List)} but forwards {@code surfaceLines} (nullable) —
     *  the surface's COMPLETE line list, cached lines included — as translator context. */
    public void warmBatchAsync(List<String> sources, List<String> surfaceLines) {
        List<String> todo = new ArrayList<>();
        List<String> pendingKeys = new ArrayList<>();
        for (String s : sources) {
            if (s == null || getCached(s) != null) continue;
            String key = requestKey(s);
            if (key.isEmpty() || isBackingOff(key) || pendingSingles.containsKey(key)
                    || churnSuppressed(key)) continue;
            if (pending.add(key)) {
                todo.add(s);
                pendingKeys.add(key);
            }
        }
        if (todo.isEmpty()) return;
        executor.execute(() -> {
            try {
                warmBatch(todo, Set.of(), surfaceLines);
            } catch (RuntimeException ignored) {
                // failures already recorded per key
            } finally {
                pendingKeys.forEach(pending::remove);
            }
        });
    }

    /** Translate all sources in one batch and deliver the full aligned list (source
     *  text is echoed back for items that failed or were skipped). */
    public void translateAllAsync(List<String> sources, Consumer<List<String>> onResults) {
        executor.execute(() -> {
            List<String> todo = new ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            for (String s : sources) {
                if (s == null || getCached(s) != null) continue;
                String key = requestKey(s);
                if (key.isEmpty() || isBackingOff(key) || pendingSingles.containsKey(key)) continue;
                if (seen.add(key)) todo.add(s);
            }
            if (!todo.isEmpty()) {
                try {
                    warmBatch(todo);
                } catch (RuntimeException ignored) {
                    // fall through: cached lookups below return the source text
                }
            }
            List<String> out = new ArrayList<>(sources.size());
            for (String s : sources) {
                String c = getCached(s);
                out.add(c != null ? c : s);
            }
            onResults.accept(out);
        });
    }

    // ---- per-tick coalescer ----

    private final Set<String> batchBuffer = ConcurrentHashMap.newKeySet();
    private final Map<String, QueuedRequest> queuedSingles = new ConcurrentHashMap<>();

    private static final int MAX_BATCH = 64;
    /** How many ticks flushBatch may hold a still-growing buffer before forcing a send. */
    private static final int MAX_FLUSH_WAIT_TICKS = 3;

    private volatile boolean batchGrew;
    private int flushWaitTicks; // touched only on the tick thread

    /** Render-path miss: queue for the next per-tick batch (non-blocking, deduped). */
    public void requestBatched(String source) {
        if (source == null) return;
        String key = requestKey(source);
        if (key.isEmpty() || pending.contains(key) || pendingSingles.containsKey(key)
                || queuedSingles.containsKey(key) || isBackingOff(key)) return;
        if (getCached(source) != null) return; // cached churn keeps rendering: guard is enqueue-only
        if (churnSuppressed(key)) return;
        if (batchBuffer.add(source)) batchGrew = true;
    }

    /**
     * Pump the coalescer; call once per client tick. Holds off while the buffers are
     * still growing (a screen populating over several frames) so the whole screen goes
     * out as ONE request, but never waits more than {@link #MAX_FLUSH_WAIT_TICKS} ticks.
     */
    public void flushBatch() {
        boolean grew = batchGrew;
        batchGrew = false;
        if (batchBuffer.isEmpty() && queuedSingles.isEmpty()) {
            flushWaitTicks = 0;
            return;
        }
        if (grew && flushWaitTicks < MAX_FLUSH_WAIT_TICKS) {
            flushWaitTicks++;
            return;
        }
        flushWaitTicks = 0;

        List<String> todo = new ArrayList<>();
        List<String> batchKeys = new ArrayList<>();
        Map<String, PendingSingle> singles = new LinkedHashMap<>();

        // Callback requests first: they represent visible chat waiting on an answer.
        java.util.Iterator<Map.Entry<String, QueuedRequest>> qit = queuedSingles.entrySet().iterator();
        while (qit.hasNext() && todo.size() < MAX_BATCH) {
            Map.Entry<String, QueuedRequest> e = qit.next();
            qit.remove();
            String key = e.getKey();
            QueuedRequest queued = e.getValue();
            String cached = getCached(queued.source);
            if (cached != null) {
                for (PendingCallback pc : queued.callbacks.close()) deliver(pc, getCached(pc.source));
                continue;
            }
            if (isBackingOff(key)) {
                for (PendingCallback pc : queued.callbacks.close()) deliver(pc, null);
                continue;
            }
            PendingSingle ours = new PendingSingle();
            PendingSingle existing = pendingSingles.putIfAbsent(key, ours);
            if (existing != null) {
                // Already in flight from another path: hand the callbacks over.
                for (PendingCallback pc : queued.callbacks.close()) {
                    if (!existing.callbacks.add(pc)) deliver(pc, getCached(pc.source));
                }
                continue;
            }
            for (PendingCallback pc : queued.callbacks.close()) ours.callbacks.add(pc);
            singles.put(key, ours);
            todo.add(queued.source);
        }

        java.util.Iterator<String> it = batchBuffer.iterator();
        while (it.hasNext() && todo.size() < MAX_BATCH) {
            String s = it.next();
            it.remove();
            if (s == null || getCached(s) != null) continue;
            String key = requestKey(s);
            if (singles.containsKey(key) || isBackingOff(key) || pendingSingles.containsKey(key)) continue;
            if (pending.add(key)) {
                todo.add(s);
                batchKeys.add(key);
            }
        }

        if (todo.isEmpty()) return;
        Set<String> allowKeys = Set.copyOf(singles.keySet());
        executor.execute(() -> {
            try {
                warmBatch(todo, allowKeys);
            } catch (RuntimeException ignored) {
                // failures already recorded per key
            } finally {
                batchKeys.forEach(pending::remove);
                for (Map.Entry<String, PendingSingle> e : singles.entrySet()) {
                    pendingSingles.remove(e.getKey(), e.getValue());
                    for (PendingCallback pc : e.getValue().callbacks.close()) {
                        deliver(pc, getCached(pc.source));
                    }
                }
            }
        });
    }

    private static void deliver(PendingCallback pc, String result) {
        if (pc == null) return;
        if (pc.always || result != null) {
            try {
                pc.callback.accept(result);
            } catch (RuntimeException ignored) {
                // a bad callback must not poison the dispatch loop
            }
        }
    }

    // ---- failure backoff ----

    private void recordFailure(String key) {
        if (failureBackoffMs > 0) {
            failedUntil.put(key, clock.getAsLong() + failureBackoffMs);
        }
    }

    private boolean isBackingOff(String key) {
        Long until = failedUntil.get(key);
        if (until == null) return false;
        if (clock.getAsLong() >= until) {
            failedUntil.remove(key);
            return false;
        }
        return true;
    }

    // ---- keys ----

    /** Canonical request/cache key: outer whitespace stripped, volatile parts templated. */
    private String requestKey(String source) {
        return TemplateText.prepare(norm(source)).text();
    }

    /** Normalise surface-context lines exactly like request keys (order kept, empties
     *  skipped). Null/empty in → null out, so no-context callers behave as before. */
    private List<String> contextKeys(List<String> surfaceLines) {
        if (surfaceLines == null || surfaceLines.isEmpty()) return null;
        List<String> out = new ArrayList<>(surfaceLines.size());
        for (String s : surfaceLines) {
            String key = requestKey(s);
            if (!key.isEmpty()) out.add(key);
        }
        return out.isEmpty() ? null : out;
    }

    private static String norm(String source) {
        return source == null ? "" : source.strip();
    }

    private boolean isUsableTranslation(String translated) {
        // Mojibake is judged on the DECORATIVE-STRIPPED text: server resource-pack icon
        // glyphs live in the Unicode private-use area, which the raw mojibake heuristic
        // flags as corruption — so every PUA-icon-prefixed line ("⚔ Heroic Spirit Sceptre
        // ✪✪✪✪✪" on SkyBlock) failed the restore gates, never stored its raw alias, never
        // displayed, and was re-bought on every encounter. Icons are STYLE (R6): they can
        // never poison a translation. U+FFFD stays unstripped (excluded from the decorative
        // class) and halfwidth kana are letters — the real mojibake signals still fire.
        // Every read/write/restore gate funnels through THIS one function, so the lookup
        // side and the store side can never disagree again.
        return translated != null && !translated.isEmpty()
                && !TextFilter.isLikelyMojibake(TextFilter.stripDecorativeSymbols(translated));
    }

    /**
     * Source-aware usability gate. Adds one rule the source-less overload cannot check: a
     * half-transliterated single word (e.g. source "jacob" translated to "傑cob") is poison —
     * it must never be cached, never read back, and never leak into the AI→Google fallback.
     */
    private boolean isUsableTranslation(String source, String translated) {
        return isUsableTranslation(translated) && !TextFilter.isPartialTransliteration(source, translated);
    }

    private void discardStored(String key) {
        if (key == null) return;
        cache.remove(key);
        provisional.remove(key);
        if (store != null) store.remove(key);
    }

    // ---- admin ----

    public String testTranslate(String source) throws TranslationException {
        return translator.translate(source, targetLang).translatedText();
    }

    public void setTargetLang(String targetLang) {
        if (targetLang != null) this.targetLang = targetLang;
    }

    public void clear() {
        cache.clear();
        failedUntil.clear();
        provisional.clear();
        if (store != null) store.clear();
    }

    /**
     * Evict every stored form of {@code source} (the retranslate hotkey). Besides the raw /
     * normalised / templated keys this must ALSO evict the DE-STYLED tier-2 copy keys —
     * otherwise {@link #getCached} keeps serving the old value through the second tier and
     * the follow-up re-warm sees "already cached" and never re-buys: the hotkey feels like
     * a no-op. Provisional marks go with their entries.
     */
    public void invalidate(String source) {
        if (source == null) return;
        String stripped = styleStripped(source);
        for (String key : new String[]{source, norm(source), requestKey(source),
                stripped, TemplateText.prepare(stripped).text()}) {
            cache.remove(key);
            failedUntil.remove(key);
            provisional.remove(key);
            if (store != null) store.remove(key);
        }
    }

    public boolean isPending(String source) {
        String key = requestKey(source);
        return pending.contains(key) || pendingSingles.containsKey(key) || queuedSingles.containsKey(key);
    }

    public int pendingCount() {
        return pending.size() + pendingSingles.size() + queuedSingles.size() + batchBuffer.size();
    }

    public int size() {
        return cache.size();
    }
}
