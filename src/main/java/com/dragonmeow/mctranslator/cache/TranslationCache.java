package com.dragonmeow.mctranslator.cache;

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

    // ---- lookup ----

    public String getCached(String source) {
        if (source == null) return null;
        String value = getStored(source);
        if (value != null) return value;
        String normalized = norm(source);
        if (!normalized.equals(source)) {
            value = getStored(normalized);
            if (value != null) return value;
        }
        TemplateText.Prepared prepared = TemplateText.prepare(normalized);
        if (prepared.changed()) {
            String templated = getStored(prepared.text());
            if (templated != null) {
                String restored = prepared.restore(templated);
                if (isUsableTranslation(restored)) {
                    putStored(source, restored); // materialise so the render path hits on first get
                    return restored;
                }
            }
        }
        TranslationCache fb = fallback;
        if (fb != null) {
            String fromSibling = fb.getCached(source);
            if (fromSibling != null) {
                cache.put(source, fromSibling); // memory only: keep the sibling's disk file authoritative
                return fromSibling;
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
        if (!isUsableTranslation(translated)) return;
        cache.put(key, translated);
        if (store != null) {
            store.put(key, translated);
        }
    }

    /** Store a translation under the normalised/templated key AND the raw source. */
    private void store(String source, String translated) {
        String normalized = norm(source);
        TemplateText.Prepared prepared = TemplateText.prepare(normalized);
        if (prepared.changed()) {
            putStored(prepared.text(), translated);
            String restored = prepared.restore(translated);
            if (isUsableTranslation(restored)) putStored(source, restored);
        } else {
            putStored(normalized, translated);
            if (!normalized.equals(source)) cache.put(source, translated); // memory alias only
        }
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
                store(source, out);
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
                        store(source, out);
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
        if (key.isEmpty() || isBackingOff(key)) {
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
        return warmBatch(sources, Set.of());
    }

    /**
     * Synchronously translate every not-yet-cached source in one batched request.
     * {@code allowPendingKeys} lets {@link #flushBatch} include keys it has itself
     * just registered as in-flight singles.
     */
    private boolean warmBatch(List<String> sources, Set<String> allowPendingKeys) {
        // Group raw variants by normalised request key: one key = one line in the request.
        LinkedHashMap<String, List<String>> rawByKey = new LinkedHashMap<>();
        for (String s : sources) {
            if (s == null || getCached(s) != null) continue;
            String key = requestKey(s);
            if (key.isEmpty() || isBackingOff(key)) continue;
            if (!allowPendingKeys.contains(key) && pendingSingles.containsKey(key)) continue;
            rawByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(s);
        }
        List<String> todo = new ArrayList<>(rawByKey.keySet());
        if (todo.isEmpty()) return true;
        try {
            List<TranslationResult> results = translator.translateBatch(todo, targetLang);
            if (results.size() != todo.size()) {
                for (String s : todo) recordFailure(s);
                return false;
            }
            Map<String, String> toPersist = new java.util.HashMap<>();
            for (int i = 0; i < todo.size(); i++) {
                String key = todo.get(i);
                String out = results.get(i).translatedText();
                if (isUsableTranslation(key, out)) {
                    cache.put(key, out);
                    failedUntil.remove(key);
                    toPersist.put(key, out);
                    for (String raw : rawByKey.get(key)) {
                        String restored = TemplateText.prepare(norm(raw)).restore(out);
                        if (isUsableTranslation(restored)) {
                            cache.put(raw, restored);
                            toPersist.put(raw, restored);
                        }
                    }
                } else {
                    recordFailure(key);
                }
            }
            if (store != null && !toPersist.isEmpty()) {
                store.putBatch(toPersist);
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
        List<String> todo = new ArrayList<>();
        List<String> pendingKeys = new ArrayList<>();
        for (String s : sources) {
            if (s == null || getCached(s) != null) continue;
            String key = requestKey(s);
            if (key.isEmpty() || isBackingOff(key) || pendingSingles.containsKey(key)) continue;
            if (pending.add(key)) {
                todo.add(s);
                pendingKeys.add(key);
            }
        }
        if (todo.isEmpty()) return;
        executor.execute(() -> {
            try {
                warmBatch(todo);
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
        if (getCached(source) != null) return;
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

    private static String norm(String source) {
        return source == null ? "" : source.strip();
    }

    private boolean isUsableTranslation(String translated) {
        return translated != null && !translated.isEmpty() && !TextFilter.isLikelyMojibake(translated);
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
        if (store != null) store.clear();
    }

    public void invalidate(String source) {
        if (source == null) return;
        for (String key : new String[]{source, norm(source), requestKey(source)}) {
            cache.remove(key);
            failedUntil.remove(key);
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
