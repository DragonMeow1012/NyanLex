package com.dragonmeow.mctranslator.cache;

import com.dragonmeow.mctranslator.translate.ChurnGuard;
import com.dragonmeow.mctranslator.translate.TemplateText;
import com.dragonmeow.mctranslator.translate.TextFilter;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.TranslationTemplate;
import com.dragonmeow.mctranslator.translate.PriorityTranslationExecutor;
import com.dragonmeow.mctranslator.translate.Translator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Translation repository and request coordinator.
 *
 * <p>Every operation is based on one immutable {@link TranslationTemplate.Snapshot}.
 * The snapshot is created before enqueueing and is carried unchanged through
 * deduplication, HTTP dispatch, placeholder restore, and persistence. No request
 * ever recomputes its placeholder layout while an HTTP call is in flight.</p>
 *
 * <p>There is one ordered settle queue for render and callback traffic, and one
 * single-flight table for every active key. Numeric variants, callback variants,
 * and concurrent surfaces all converge at those two structures.</p>
 */
public final class TranslationCache {
    private static final int MAX_BATCH = 64;
    private static final int MAX_SETTLE_TICKS = 3;
    private static final int CONTENT_FAILURE_LIMIT = 3;
    private static final int MAX_FINAL_WAITER_FAMILIES = 512;
    /** Durable negative-cache value. It is never shown; reads return the original key. */
    private static final String KEEP_ORIGINAL = "\u0000MT_KEEP_ORIGINAL2";
    /** Pre-1.0.3 sentinel. Old builds also learned it from genuine provider failures
     *  (empty/mojibake/damaged-marker responses), permanently poisoning those lines.
     *  Every read path treats it as a miss and deletes it on sight, so legacy poison
     *  unlocks exactly once; a legitimate echo then relearns the v2 value. */
    private static final String LEGACY_KEEP_ORIGINAL = "\u0000MT_KEEP_ORIGINAL";

    /** Failure-ledger value: the provider really answered with the original text.
     *  Permanent; hits refill the original and never issue another request. */
    private static final String FAILURE_ECHO = "echo";
    /** Failure-ledger value prefix: {@code temporary:<attempt>:<untilMs>}. A damaged,
     *  empty or rate-limited response that must heal; retried once the backoff expires. */
    private static final String FAILURE_TEMPORARY_PREFIX = "temporary:";
    /** Pending identity confirmation: {@code identity:<count>:<untilMs>}. */
    private static final String FAILURE_IDENTITY_PREFIX = "identity:";
    /** Presentation-only retry key. It can never enable GT or poison semantic identity. */
    private static final String STYLE_FAILURE_PREFIX = "\u0000MT_STYLE_FAILURE:";
    /** Ceiling for exponential retry backoff. 429/5xx storms usually clear quickly;
     *  a user hovering a tooltip must never wait unbounded multiples of the base. */
    private static final long MAX_FAILURE_BACKOFF_MS = 5 * 60_000L;

    private static final Pattern CS_MARKER =
            Pattern.compile("\\u27E6\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7");
    private static final Pattern CS_TOKEN =
            Pattern.compile("\\u27E6\\s*(/?)\\s*CS\\s*(\\d+)\\s*\\u27E7");
    private static final Pattern CS_RESIDUE =
            Pattern.compile("\\u27E6?\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7?");
    private static final Pattern MT_TOKEN =
            Pattern.compile("\\u27E6\\s*MT\\s*(\\d+)\\s*\\u27E7");
    private static final Pattern PARAGRAPH_BREAK_TOKEN =
            Pattern.compile("\\u27E6\\s*PB\\s*(\\d+)\\s*\\u27E7");

    private final Translator translator;
    private volatile String targetLang;
    private final Executor executor;
    private final LongSupplier clock;
    private final long failureBackoffMs;
    private final PersistentStore store;
    /** Sibling GT file: provisional (fallback-produced) rows are persisted here so the
     *  primary store carries only final primary-engine wording. */
    private volatile PersistentStore provisionalStore;
    /** Durable failure ledger (third file): permanent echo marks and temporary retry
     *  marks carrying their attempt count and backoff expiry across restarts. */
    private volatile PersistentStore failureStore;
    private final TranslationTemplate templates = new TranslationTemplate();
    private final Map<String, String> memory;

    private volatile TranslationCache fallback;
    private volatile boolean fallbackHitsProvisional;
    private volatile BooleanSupplier fallbackEnabled = () -> true;
    private volatile ChurnGuard churnGuard = new ChurnGuard();

    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    /** Consecutive identity echoes for this semantic template; only these may become
     *  a durable keep-original decision. Transport failures are intentionally
     *  excluded: an outage must not poison text. */
    private final Map<String, Integer> contentFailures = new ConcurrentHashMap<>();
    /** Consecutive damaged/empty (non-echo) responses per semantic family. These are
     *  provider bugs that must heal, so they only grow an exponential retry backoff
     *  and can never write the durable keep-original sentinel. */
    private final Map<String, Integer> contentRetryAttempts = new ConcurrentHashMap<>();
    /** Failed requests remain scheduled (with backoff) until success or confirmed identity. */
    private final Map<String, TranslationTemplate.Snapshot> retrySnapshots = new ConcurrentHashMap<>();
    private final Map<String, Flight> flights = new ConcurrentHashMap<>();

    private final Object queueLock = new Object();
    private final LinkedHashMap<String, Queued> queue = new LinkedHashMap<>();
    private boolean queueGrew;
    private int settleTicks;

    private final Set<String> provisional = ConcurrentHashMap.newKeySet();
    private final Set<String> provisionalRetrying = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> provisionalRetryAttempts = new ConcurrentHashMap<>();
    /** Callbacks used by UI mutations that must not consume provisional GT results. */
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<FinalWaiter>> finalWaiters =
            new ConcurrentHashMap<>();
    private volatile BooleanSupplier provisionalRetryGate;

    /** Invalidates work that completes after clear()/language changes. */
    private final AtomicLong generation = new AtomicLong();
    /** Per-request-key revisions make individual retranslation a hard invalidation:
     *  a response started before the key was deleted can never refill the old value. */
    private final AtomicLong revisionSequence = new AtomicLong();
    private final Map<String, Long> keyRevisions = new ConcurrentHashMap<>();
    private volatile TranslationDebugLog debugLog;
    private volatile String debugEngine = "translator";

    public TranslationCache(Translator translator, String targetLang, Executor executor, int maxSize) {
        this(translator, targetLang, executor, maxSize, 10_000L, System::currentTimeMillis, null);
    }

    public TranslationCache(Translator translator, String targetLang, Executor executor,
                            int maxSize, long failureBackoffMs, LongSupplier clock) {
        this(translator, targetLang, executor, maxSize, failureBackoffMs, clock, null);
    }

    public TranslationCache(Translator translator, String targetLang, Executor executor,
                            int maxSize, long failureBackoffMs, LongSupplier clock,
                            PersistentStore store) {
        this.translator = translator;
        this.targetLang = targetLang;
        this.executor = executor;
        this.clock = clock;
        this.failureBackoffMs = Math.max(0L, failureBackoffMs);
        this.store = store;
        int capacity = Math.max(1, maxSize);
        this.memory = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > capacity;
            }
        });
    }

    public void setFallback(TranslationCache fallback) {
        setFallback(fallback, false);
    }

    /**
     * Configure a one-way lower-priority cache. When {@code asProvisional} is true,
     * the sibling is a failure-only fallback: a cold primary miss never consults it.
     * Only after this cache records an actual provider/content failure may a sibling
     * hit be displayed provisionally while the primary keeps retrying.
     */
    public void setFallback(TranslationCache fallback, boolean asProvisional) {
        this.fallback = fallback == this ? null : fallback;
        this.fallbackHitsProvisional = fallback != null && fallback != this && asProvisional;
        resumeFailureFallbacks();
    }

    /** Live policy switch used by strict-AI mode; existing cache wiring need not be rebuilt. */
    public void setFallbackEnabled(BooleanSupplier enabled) {
        this.fallbackEnabled = enabled == null ? () -> true : enabled;
    }

    private boolean isFallbackEnabled() {
        try {
            return fallbackEnabled.getAsBoolean();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    public void setChurnGuard(ChurnGuard churnGuard) {
        this.churnGuard = churnGuard;
    }

    public void setProvisionalRetryGate(BooleanSupplier gate) {
        this.provisionalRetryGate = gate;
    }

    /**
     * Route provisional (fallback-produced GT) rows into the sibling GT file so the
     * primary store keeps only final primary-engine wording. Legacy provisional rows
     * that older builds mixed into the primary file are moved over once on wiring
     * (and again after a language switch opens another language's file).
     */
    public void setProvisionalStore(PersistentStore provisionalStore) {
        this.provisionalStore = provisionalStore == store ? null : provisionalStore;
        migrateProvisionalRows();
    }

    /**
     * Durable failure ledger (third file). Pending identity confirmations and temporary
     * failures carry retry state across restarts. A confirmed identity is moved into this
     * engine's own translation store, so AI and GT can never suppress each other.
     */
    public void setFailureStore(PersistentStore failureStore) {
        this.failureStore = failureStore == store ? null : failureStore;
        hydrateFailureStore();
    }

    private void hydrateFailureStore() {
        PersistentStore failures = this.failureStore;
        if (failures == null) return;
        for (Map.Entry<String, String> entry : failures.entries().entrySet()) {
            if (FAILURE_ECHO.equals(entry.getValue())) {
                keepsOriginal(entry.getKey()); // migrate the old terminal row into this engine cache
            } else {
                restoreTemporaryFailure(entry.getKey());
            }
        }
        resumeFailureFallbacks();
    }

    private void resumeFailureFallbacks() {
        if (!isFallbackEnabled() || !fallbackHitsProvisional || fallback == null) return;
        for (Map.Entry<String, TranslationTemplate.Snapshot> entry : retrySnapshots.entrySet()) {
            if (!entry.getKey().startsWith(STYLE_FAILURE_PREFIX)) {
                requestFallback(entry.getValue());
            }
        }
    }

    private void migrateProvisionalRows() {
        PersistentStore target = provisionalStore;
        if (store == null || target == null) return;
        Map<String, String> rows = store.provisionalEntries();
        if (rows.isEmpty()) return;
        target.putBatch(rows, java.util.Set.of());
        store.removeBatch(rows.keySet());
    }

    public void setDebugLog(String engine, TranslationDebugLog log) {
        this.debugEngine = engine == null || engine.isBlank() ? "translator" : engine;
        this.debugLog = log;
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    public String getCached(String source) {
        return getCached(source, true);
    }

    /** Whether this engine has actually failed for the semantic family. UI policy uses
     * this to start the lower-priority engine only after a real primary failure. */
    public boolean hasFailureState(String source) {
        if (source == null) return false;
        String key = provisionalSemanticKey(source);
        if (retrySnapshots.containsKey(key) || failedUntil.containsKey(key)
                || contentFailures.containsKey(key) || contentRetryAttempts.containsKey(key)
                || provisional(key)) {
            return true;
        }
        PersistentStore failures = failureStore;
        if (failures == null) return false;
        String row = failures.get(key);
        return startsWithTemporary(row) || row != null && row.startsWith(FAILURE_IDENTITY_PREFIX);
    }

    /** True only when this engine failed and has no final semantic wording of its own. */
    public boolean mayUseFallback(String source) {
        if (!isFallbackEnabled()) return false;
        if (!hasFailureState(source)) return false;
        TranslationTemplate.Snapshot plain = templates.prepare(stripStyle(source));
        String ownSemantic = lookupSnapshot(plain, this);
        return ownSemantic == null || provisional(plain.key());
    }

    /**
     * Lookup used by one-shot rich surfaces such as chat.  A semantic (marker-free)
     * translation is useful to a widget that will render again next frame, but it is not
     * sufficient for a chat component that is inserted only once: without the CS projection
     * there is no reliable source-to-target style alignment.
     */
    private String getCachedExactStyle(String source) {
        return getCached(source, false);
    }

    private String getCached(String source, boolean allowStyleFallback) {
        return getCached(source, allowStyleFallback, true);
    }

    private String getCached(String source, boolean allowStyleFallback,
                             boolean includeLowerFallback) {
        if (source == null) return null;
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        TranslationCache sibling = isFallbackEnabled() ? fallback : null;

        // Style is not meaning, but CS markers carry the alignment needed to apply
        // the CURRENT component's styles to the translated words. A marked request
        // therefore needs both a canonical semantic row and a marker-only projection.
        // The projection is accepted only while its plain wording equals the canonical
        // row, preventing two surfaces from reviving conflicting translations.
        boolean marked = hasCsMarkers(snapshot.key());
        String stripped = stripStyle(source);
        TranslationTemplate.Snapshot plainSnapshot = templates.prepare(stripped);
        if (keepsOriginal(plainSnapshot.key())) return source;
        if (marked && keepsOriginal(styleProjectionKey(snapshot))) {
            // 1.0.2 pre-release builds briefly stored negative decisions per colour
            // topology. Discard that legacy sentinel: current decisions are semantic so
            // an individual retranslate of the plain line can always unlock the family.
            removeStored(styleProjectionKey(snapshot));
        }
        String styleHit = marked ? lookupStyleProjection(snapshot, this) : null;
        String plainHit = null;
        if (!stripped.equals(snapshot.normalized())) {
            plainHit = lookupSnapshot(plainSnapshot, this);
            if (marked) {
                if (styleHit != null && plainHit != null && sameSemanticText(styleHit, plainHit)) {
                    return styleHit;
                }
                if (styleHit != null && plainHit != null
                        && provisional(styleProjectionKey(snapshot))) {
                    if (!allowStyleFallback) {
                        removeStored(styleProjectionKey(snapshot));
                        return null;
                    }
                    // The final semantic wording is already usable. A live HUD may mint a
                    // different CS topology every frame, so generic render lookups must not
                    // buy cosmetic topology supplements in the background.
                    return TextFilter.markStyleFallback(plainHit);
                }
                if (styleHit != null) removeStored(styleProjectionKey(snapshot));
                // Meaning must not wait for presentation.  The renderer can safely project
                // a semantic hit onto the current component (keeping verbatim numeric/value
                // anchors in their exact colours) while an exact CS topology is unavailable.
                // Do not launch a background request here. Animated scoreboards can change
                // their CS indices continuously; treating every presentation topology as
                // AI work caused an unbounded request stream and let its varying wording
                // overwrite the stable semantic cache. One-shot consumers that truly need
                // exact marker alignment explicitly use requestCoalescedExactStyle().
                if (plainHit != null) {
                    if (!allowStyleFallback) return null;
                    return TextFilter.markStyleFallback(plainHit);
                }
            } else if (plainHit != null) {
                return plainHit;
            }
        }

        String hit = marked ? null : lookupSnapshot(snapshot, this);
        if (hit != null) return hit;

        if (includeLowerFallback && sibling != null && fallbackAllowed(source)) {
            hit = allowStyleFallback ? sibling.getCached(source)
                    : sibling.getCachedExactStyle(source);
            if (hit != null) {
                return acceptFallbackHit(source, hit, allowStyleFallback);
            }
        }

        return null;
    }

    /**
     * Return only a final primary-engine result. AI-enabled structured surfaces use
     * this to keep displaying the original while a provisional Google fallback is
     * being retried, instead of flashing lower-context wording into a whole paragraph.
     * Calling this still triggers the normal provisional retry path in {@link #getCached}.
     */
    public String getCachedFinal(String source) {
        return getCachedFinal(source, false);
    }

    private String getCachedFinal(String source, boolean exactStyle) {
        // Final means this engine's own value, never a result merely read through from
        // its lower-priority sibling.
        String hit = getCached(source, !exactStyle, false);
        if (hit == null) return null;
        String semanticKey = provisionalSemanticKey(source);
        if (provisional(semanticKey)) return null;
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        if (provisional(snapshot.key())) return null;
        return hit;
    }

    private String lookupStyleProjection(TranslationTemplate.Snapshot snapshot,
                                         TranslationCache owner) {
        String key = styleProjectionKey(snapshot);
        String stored = owner.read(key);
        if (stored == null) return null;
        String restored = snapshot.restore(stored);
        if (!usable(restored)) {
            owner.removeStored(key);
            return null;
        }
        // A colour topology is presentation, not an independent AI task. Only the
        // style-free semantic row may schedule an AI supplement; otherwise one visible
        // line with a provisional GT result launches two retries every cooldown.
        return restored;
    }

    private String acceptFallbackHit(String source, String hit, boolean allowStyleFallback) {
        // Never copy GT wording into AI memory: a late read-through must be physically
        // incapable of overwriting a concurrently landed AI final. Recheck the own tier
        // at the linearisation point; if AI completed first, it wins this very lookup.
        String own = getCached(source, allowStyleFallback, false);
        if (own != null) return own;
        if (fallbackHitsProvisional && !mayUseFallback(source)) return null;
        return hit;
    }

    /** Lookup only the immutable forms captured by this request snapshot. */
    private String lookupSnapshot(TranslationTemplate.Snapshot snapshot, TranslationCache owner) {
        if (TemplateText.isLeadingPlayerEvent(snapshot.normalized())) {
            // Builds before 1.0.2 persisted one raw row for every player event. Those
            // rows contain the player ID and would otherwise win before the shared
            // template lookup. Remove an encountered legacy row once, then use only
            // the rank/name-slotted template from this build onward.
            owner.discardLegacyPlayerEvent(snapshot.source());
            if (!snapshot.normalized().equals(snapshot.source())) {
                owner.discardLegacyPlayerEvent(snapshot.normalized());
            }
            return lookupTemplates(snapshot, owner);
        }
        // Once a stable template exists, it is the canonical identity. Legacy builds
        // wrote one exact raw row per number/server/gap variant; consulting those first
        // would prevent the shared template from ever being learned.
        if (snapshot.changed()) {
            String templated = lookupTemplates(snapshot, owner);
            if (templated != null) return templated;

            // Migrate a usable exact row written by an older build into the stable key
            // without paying for another translation. If its old value collapsed layout
            // and cannot be safely retokenized, serve it only for this exact source; a
            // future variant will seed the correct shared template once.
            String rawKey = snapshot.source();
            String exact = owner.read(rawKey);
            if (exact == null && !snapshot.normalized().equals(rawKey)) {
                rawKey = snapshot.normalized();
                exact = owner.read(rawKey);
            }
            if (exact != null) {
                String retokenized = snapshot.retokenize(exact);
                if (retokenized != null && usable(snapshot.key(), retokenized)) {
                    owner.store(snapshot, retokenized,
                            owner.provisional(rawKey) || owner.provisional(snapshot.key()));
                    String migrated = lookupTemplates(snapshot, owner);
                    if (migrated != null) return migrated;
                }
                return exact;
            }
            return null;
        }
        String hit = owner.read(snapshot.source());
        if (hit != null) {
            owner.retryProvisional(snapshot.source());
            return hit;
        }
        if (!snapshot.normalized().equals(snapshot.source())) {
            hit = owner.read(snapshot.normalized());
            if (hit != null) {
                owner.retryProvisional(snapshot.normalized());
                return hit;
            }
        }
        return lookupTemplates(snapshot, owner);
    }

    private void discardLegacyPlayerEvent(String key) {
        if (key != null && read(key) != null) removeStored(key);
    }

    /** Restore deterministic values and the current HUD/layout gaps from one snapshot. */
    private String lookupTemplates(TranslationTemplate.Snapshot snapshot, TranslationCache owner) {
        if (snapshot == null || !snapshot.changed()) return null;
        String stored = owner.read(snapshot.key());
        if (stored == null) return null;
        String restored = snapshot.restore(stored);
        if (!usable(restored)) return null;
        owner.retryProvisional(snapshot.key());
        return restored;
    }

    private String read(String key) {
        if (key == null) return null;
        String value = memory.get(key);
        if (value != null) {
            if (KEEP_ORIGINAL.equals(value)) return key;
            if (LEGACY_KEEP_ORIGINAL.equals(value)) {
                // v1 sentinels also encoded genuine failures. Miss + delete unlocks
                // the line once; a legitimate echo relearns the v2 decision later.
                removeStored(key);
                return null;
            }
            if (usable(key, value)) return value;
            removeStored(key);
        }
        if (store == null) return null;
        value = store.get(key);
        if (value == null) return null;
        if (KEEP_ORIGINAL.equals(value)) {
            memory.put(key, value);
            return key;
        }
        if (LEGACY_KEEP_ORIGINAL.equals(value)) {
            removeStored(key);
            return null;
        }
        if (!usable(key, value)) {
            removeStored(key);
            return null;
        }
        memory.put(key, value);
        return value;
    }

    private boolean keepsOriginal(String key) {
        if (key == null) return false;
        String value = memory.get(key);
        if (KEEP_ORIGINAL.equals(value)) return true;
        if (LEGACY_KEEP_ORIGINAL.equals(value)) {
            removeStored(key);
            return false;
        }
        PersistentStore failures = failureStore;
        if (failures != null && FAILURE_ECHO.equals(failures.get(key))) {
            // Legacy policy stored confirmed identity in the failure ledger. Move it
            // into this engine's own cache; namespaced production stores ensure the
            // verdict can never suppress the other engine.
            failures.remove(key);
            if (store != null) store.put(key, KEEP_ORIGINAL, false);
            memory.put(key, KEEP_ORIGINAL);
            return true;
        }
        if (store == null) return false;
        value = store.get(key);
        if (KEEP_ORIGINAL.equals(value)) {
            memory.put(key, KEEP_ORIGINAL);
            return true;
        }
        if (LEGACY_KEEP_ORIGINAL.equals(value)) removeStored(key);
        return false;
    }

    // -------------------------------------------------------------------------
    // Immediate and blocking requests
    // -------------------------------------------------------------------------

    public String translateBlocking(String source) {
        String cached = getCached(source);
        if (cached != null) return cached;

        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        // Explicit blocking warm-ups are user/workflow initiated and intentionally
        // bypass retry backoff; the backoff protects repeated render-path requests.
        if (!snapshot.hasTranslatableContent()) return null;
        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(snapshot.key());
        long debugId = debugSubmitted(List.of(snapshot.key()));
        try {
            TranslationResult result = translator.translate(snapshot.key(), targetLang);
            if (!usable(snapshot.key(), result.translatedText())) {
                boolean kept = current(snapshot.key(), expectedGeneration, expectedRevision)
                        && handleUnusableContent(snapshot, result.translatedText());
                debugCompleted(debugId, result.translatedText(), kept
                        ? TranslationDebugLog.Status.KEEP_ORIGINAL
                        : TranslationDebugLog.Status.FAILED);
                return kept ? snapshot.source() : null;
            }
            if (current(snapshot.key(), expectedGeneration, expectedRevision)) {
                store(snapshot, result.translatedText(), result.fromFallback());
                failedUntil.remove(snapshot.key());
            }
            debugCompleted(debugId, result.translatedText(), result.fromFallback()
                    ? TranslationDebugLog.Status.FALLBACK : TranslationDebugLog.Status.SUCCESS);
            return snapshot.restore(result.translatedText());
        } catch (TranslationException | RuntimeException e) {
            if (current(snapshot.key(), expectedGeneration, expectedRevision)) fail(snapshot.key());
            debugCompleted(debugId, false);
            return null;
        }
    }

    public void requestAsync(String source) {
        requestAsync(source, null);
    }

    public void requestAsync(String source, Consumer<String> onSuccess) {
        requestImmediate(source, onSuccess, false);
    }

    public void translateAsyncAlways(String source, Consumer<String> onResult) {
        requestImmediate(source, onResult, true);
    }

    private void requestImmediate(String source, Consumer<String> callback, boolean always) {
        String cached = getCached(source);
        if (cached != null) {
            deliver(new Callback(null, callback, always, false), cached);
            return;
        }

        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        Callback cb = new Callback(snapshot, callback, always, false);
        if (!snapshot.hasTranslatableContent() || backingOff(snapshot.key())
                || suppressed(snapshot.key())) {
            deliver(cb, null);
            return;
        }
        submitSingle(snapshot, cb);
    }

    private void submitSingle(TranslationTemplate.Snapshot snapshot, Callback callback) {
        String key = snapshot.key();
        Flight ours = new Flight();
        ours.add(callback);
        Flight existing = flights.putIfAbsent(key, ours);
        if (existing != null) {
            if (!existing.add(callback)) deliver(callback, lookupSnapshot(snapshot, this));
            return;
        }

        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(key);
        executeHigh(() -> {
            long debugId = 0L;
            try {
                if (lookupSnapshot(snapshot, this) == null) {
                    debugId = debugSubmitted(List.of(key));
                    TranslationResult result = translator.translate(key, targetLang);
                    boolean usableResult = usable(key, result.translatedText());
                    boolean kept = !usableResult
                            && current(key, expectedGeneration, expectedRevision)
                            && handleUnusableContent(snapshot, result.translatedText());
                    if (usableResult && current(key, expectedGeneration, expectedRevision)) {
                        store(snapshot, result.translatedText(), result.fromFallback());
                        failedUntil.remove(key);
                    }
                    debugCompleted(debugId, usableResult || kept ? result.translatedText() : null,
                            kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                                    : !usableResult ? TranslationDebugLog.Status.FAILED
                                    : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                                    : TranslationDebugLog.Status.SUCCESS);
                }
            } catch (TranslationException | RuntimeException e) {
                if (current(key, expectedGeneration, expectedRevision)) fail(key);
                debugCompleted(debugId, false);
            } finally {
                finishFlight(key, ours);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Shared settle queue
    // -------------------------------------------------------------------------

    public void requestBatched(String source) {
        if (source == null || getCached(source) != null) return;
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        if (!eligible(snapshot)) return;
        enqueue(snapshot, null);
    }

    public void requestCoalesced(String source, Consumer<String> callback, boolean always) {
        requestCoalesced(source, callback, always, false);
    }

    /**
     * Coalesced request that treats a marker-free semantic cache hit as a miss when the
     * source carries CS style markers.  This is for one-shot chat insertion: it waits for
     * the marked translation instead of permanently rendering an approximate colour guess.
     * Marker-free input behaves exactly like {@link #requestCoalesced}.
     */
    public void requestCoalescedExactStyle(String source, Consumer<String> callback,
                                           boolean always) {
        requestCoalesced(source, callback, always, true);
    }

    private void requestCoalesced(String source, Consumer<String> callback, boolean always,
                                  boolean exactStyle) {
        String cached = exactStyle ? getCachedExactStyle(source) : getCached(source);
        if (cached != null) {
            deliver(new Callback(null, callback, always, exactStyle), cached);
            return;
        }

        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        // Requiring an exact style projection has no effect on plain text.
        exactStyle &= hasCsMarkers(snapshot.key());
        Callback cb = new Callback(snapshot, callback, always, exactStyle);
        if (!eligible(snapshot)) {
            deliver(cb, null);
            return;
        }

        while (true) {
            Flight flight = flights.get(snapshot.key());
            if (flight == null) break;
            if (flight.add(cb)) return;
            cached = exactStyle ? getCachedExactStyle(source) : lookupSnapshot(snapshot, this);
            if (cached != null) {
                deliver(cb, cached);
                return;
            }
        }
        enqueue(snapshot, cb);
    }

    /**
     * Coalesced request whose callback fires only after a final primary-engine value is
     * available. A provisional fallback still starts the normal AI supplement, but is
     * never delivered to a widget that would otherwise permanently replace its source.
     */
    public void requestCoalescedFinal(String source, Consumer<String> callback) {
        requestCoalescedFinal(source, callback, false);
    }

    /** Final-primary waiter that additionally requires the exact CS span projection. */
    public void requestCoalescedExactStyleFinal(String source, Consumer<String> callback) {
        requestCoalescedFinal(source, callback, true);
    }

    private void requestCoalescedFinal(String source, Consumer<String> callback,
                                       boolean exactStyle) {
        if (source == null || callback == null) return;
        String ready = getCachedFinal(source, exactStyle);
        if (ready != null) {
            callback.accept(ready);
            return;
        }

        String family = provisionalSemanticKey(source);
        FinalWaiter waiter = new FinalWaiter(source, callback, exactStyle);
        if (!finalWaiters.containsKey(family)
                && finalWaiters.size() >= MAX_FINAL_WAITER_FAMILIES) {
            java.util.Iterator<String> oldest = finalWaiters.keySet().iterator();
            if (oldest.hasNext()) finalWaiters.remove(oldest.next());
        }
        finalWaiters.computeIfAbsent(family,
                ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(waiter);

        // Close the registration race with a final store on another worker.
        ready = getCachedFinal(source, exactStyle);
        if (ready != null) {
            notifyFinalWaiters(family);
            return;
        }

        Consumer<String> completed = ignored -> {
            if (getCachedFinal(source, exactStyle) != null) notifyFinalWaiters(family);
            else retryProvisional(family);
        };
        if (exactStyle) requestCoalescedExactStyle(source, completed, true);
        else requestCoalesced(source, completed, true);
    }

    private boolean eligible(TranslationTemplate.Snapshot snapshot) {
        return snapshot.hasTranslatableContent()
                && !backingOff(snapshot.key())
                && !suppressed(snapshot.key());
    }

    private void enqueue(TranslationTemplate.Snapshot snapshot, Callback callback) {
        synchronized (queueLock) {
            Queued item = queue.get(snapshot.key());
            if (item == null) {
                item = new Queued(snapshot);
                queue.put(snapshot.key(), item);
                queueGrew = true;
            }
            if (callback != null) item.callbacks.add(callback);
        }
    }

    private boolean queued(String key) {
        synchronized (queueLock) {
            return queue.containsKey(key);
        }
    }

    /** Called once per client tick. */
    public void flushBatch() {
        enqueueDueRetries();
        // A provisional result may have arrived while the AI's 429 gate was closed.
        // Final-only widget callbacks remain registered, so re-check their bounded
        // semantic families each tick and start the supplement once the gate reopens.
        for (String family : finalWaiters.keySet()) retryProvisional(family);
        List<Queued> drained;
        synchronized (queueLock) {
            if (queue.isEmpty()) {
                settleTicks = 0;
                queueGrew = false;
                return;
            }
            boolean grew = queueGrew;
            queueGrew = false;
            if (grew && settleTicks < MAX_SETTLE_TICKS) {
                settleTicks++;
                return;
            }
            settleTicks = 0;
            drained = new ArrayList<>(Math.min(MAX_BATCH, queue.size()));
            var iterator = queue.entrySet().iterator();
            while (iterator.hasNext() && drained.size() < MAX_BATCH) {
                drained.add(iterator.next().getValue());
                iterator.remove();
            }
        }

        List<TranslationTemplate.Snapshot> send = new ArrayList<>();
        Map<String, Flight> owned = new LinkedHashMap<>();
        for (Queued item : drained) {
            String key = item.snapshot.key();
            String cached = lookupSnapshot(item.snapshot, this);
            if (cached != null) {
                item.callbacks.forEach(cb -> deliver(cb, cachedFor(cb)));
                continue;
            }
            if (!item.snapshot.hasTranslatableContent() || backingOff(key)) {
                item.callbacks.forEach(cb -> deliver(cb, null));
                continue;
            }

            Flight ours = new Flight();
            item.callbacks.forEach(ours::add);
            Flight existing = flights.putIfAbsent(key, ours);
            if (existing != null) {
                for (Callback cb : item.callbacks) {
                    if (!existing.add(cb)) deliver(cb, cachedFor(cb));
                }
            } else {
                owned.put(key, ours);
                send.add(item.snapshot);
            }
        }
        if (send.isEmpty()) return;

        long expectedGeneration = generation.get();
        Map<String, Long> expectedRevisions = revisions(send);
        executeHigh(() -> {
            try {
                translateBatch(send, null, expectedGeneration, expectedRevisions);
            } finally {
                owned.forEach(this::finishFlight);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Explicit batch APIs
    // -------------------------------------------------------------------------

    public boolean warmBatch(List<String> sources) {
        return warmBatch(sources, null);
    }

    public boolean warmBatch(List<String> sources, List<String> surfaceLines) {
        List<TranslationTemplate.Snapshot> snapshots = prepareMissing(sources, false);
        return translateBatch(snapshots, context(surfaceLines), generation.get(), revisions(snapshots));
    }

    public void warmBatchAsync(List<String> sources) {
        warmBatchAsync(sources, null);
    }

    public void warmBatchAsync(List<String> sources, List<String> surfaceLines) {
        List<TranslationTemplate.Snapshot> candidates = prepareMissing(sources, true);
        List<TranslationTemplate.Snapshot> send = new ArrayList<>();
        Map<String, Flight> owned = new LinkedHashMap<>();
        for (TranslationTemplate.Snapshot snapshot : candidates) {
            Flight ours = new Flight();
            if (flights.putIfAbsent(snapshot.key(), ours) == null) {
                owned.put(snapshot.key(), ours);
                send.add(snapshot);
            }
        }
        if (send.isEmpty()) return;

        List<String> requestContext = context(surfaceLines);
        long expectedGeneration = generation.get();
        Map<String, Long> expectedRevisions = revisions(send);
        executeLow(() -> {
            try {
                translateBatch(send, requestContext, expectedGeneration, expectedRevisions);
            } finally {
                owned.forEach(this::finishFlight);
            }
        });
    }

    public void translateAllAsync(List<String> sources, Consumer<List<String>> onResults) {
        executeLow(() -> {
            warmBatch(sources);
            List<String> results = new ArrayList<>(sources.size());
            for (String source : sources) {
                String hit = getCached(source);
                results.add(hit == null ? source : hit);
            }
            onResults.accept(results);
        });
    }

    private List<TranslationTemplate.Snapshot> prepareMissing(List<String> sources,
                                                               boolean skipActiveFlights) {
        LinkedHashMap<String, TranslationTemplate.Snapshot> unique = new LinkedHashMap<>();
        if (sources == null) return List.of();
        for (String source : sources) {
            if (source == null || getCached(source) != null) continue;
            TranslationTemplate.Snapshot snapshot = templates.prepare(source);
            if (!eligible(snapshot)) continue;
            if (skipActiveFlights && flights.containsKey(snapshot.key())) continue;
            unique.putIfAbsent(snapshot.key(), snapshot);
        }
        return new ArrayList<>(unique.values());
    }

    private boolean translateBatch(List<TranslationTemplate.Snapshot> snapshots,
                                   List<String> surfaceContext,
                                   long expectedGeneration,
                                   Map<String, Long> expectedRevisions) {
        if (snapshots == null || snapshots.isEmpty()) return true;

        List<TranslationTemplate.Snapshot> todo = new ArrayList<>();
        for (TranslationTemplate.Snapshot snapshot : snapshots) {
            if (lookupSnapshot(snapshot, this) == null
                    && snapshot.hasTranslatableContent()
                    && !backingOff(snapshot.key())) {
                todo.add(snapshot);
            }
        }
        if (todo.isEmpty()) return true;

        List<String> keys = todo.stream().map(TranslationTemplate.Snapshot::key).toList();
        long debugId = debugSubmitted(keys);
        boolean allSucceeded = true;
        try {
            List<TranslationResult> results =
                    translator.translateBatch(keys, targetLang, surfaceContext);
            if (results.size() != todo.size()) {
                for (TranslationTemplate.Snapshot snapshot : todo) {
                    if (current(snapshot.key(), expectedGeneration,
                            expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                        fail(snapshot.key());
                    }
                }
                debugCompleted(debugId, false);
                return false;
            }
            List<Boolean> keptOriginal = new ArrayList<>(todo.size());
            for (int i = 0; i < todo.size(); i++) {
                TranslationTemplate.Snapshot snapshot = todo.get(i);
                TranslationResult result = results.get(i);
                boolean usableResult = usable(snapshot.key(), result.translatedText());
                boolean kept = !usableResult
                        && current(snapshot.key(), expectedGeneration,
                        expectedRevisions.getOrDefault(snapshot.key(), 0L))
                        && handleUnusableContent(snapshot, result.translatedText());
                keptOriginal.add(kept);
                if (usableResult) {
                    if (current(snapshot.key(), expectedGeneration,
                            expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                        // Batched requests collect every canonical/style-independent
                        // entry and persist them in one atomic store update below.
                        failedUntil.remove(snapshot.key());
                    }
                } else if (!kept) {
                    allSucceeded = false;
                }
            }
            WriteBatch writes = new WriteBatch();
            if (generation.get() == expectedGeneration) {
                for (int i = 0; i < todo.size(); i++) {
                    TranslationResult result = results.get(i);
                    TranslationTemplate.Snapshot snapshot = todo.get(i);
                    if (usable(snapshot.key(), result.translatedText())
                            && current(snapshot.key(), expectedGeneration,
                            expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                        store(snapshot, result.translatedText(), result.fromFallback(), writes);
                    }
                }
                writes.flush();
            }
            List<String> debugTranslations = new ArrayList<>(results.size());
            List<TranslationDebugLog.Status> debugStatuses = new ArrayList<>(results.size());
            for (int i = 0; i < results.size(); i++) {
                TranslationResult result = results.get(i);
                boolean usableResult = usable(todo.get(i).key(), result.translatedText());
                boolean kept = keptOriginal.get(i);
                debugTranslations.add(usableResult || kept ? result.translatedText() : null);
                debugStatuses.add(kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                        : !usableResult ? TranslationDebugLog.Status.FAILED
                        : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                        : TranslationDebugLog.Status.SUCCESS);
            }
            debugCompleted(debugId, debugTranslations, debugStatuses);
            return true;
        } catch (TranslationException | RuntimeException e) {
            for (TranslationTemplate.Snapshot snapshot : todo) {
                if (current(snapshot.key(), expectedGeneration,
                        expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                    fail(snapshot.key());
                }
            }
            debugCompleted(debugId, false);
            return false;
        }
    }

    private List<String> context(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        List<String> keys = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) continue;
            String key = templates.prepare(line).key();
            // Empty tooltip rows are semantic section boundaries (stats / enchants /
            // ability / market).  Keep them so OpenAiTranslator can emit [SECTION]
            // instead of flattening the whole item into an undifferentiated word list.
            keys.add(key);
        }
        return keys.isEmpty() ? null : keys;
    }

    private long keyRevision(String key) {
        return keyRevisions.getOrDefault(key, 0L);
    }

    private Map<String, Long> revisions(List<TranslationTemplate.Snapshot> snapshots) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (snapshots != null) {
            for (TranslationTemplate.Snapshot snapshot : snapshots) {
                out.put(snapshot.key(), keyRevision(snapshot.key()));
            }
        }
        return out;
    }

    private boolean current(String key, long expectedGeneration, long expectedRevision) {
        return generation.get() == expectedGeneration && keyRevision(key) == expectedRevision;
    }

    // -------------------------------------------------------------------------
    // Storage and style-independent tier
    // -------------------------------------------------------------------------

    private void store(TranslationTemplate.Snapshot snapshot, String translated,
                       boolean isProvisional) {
        store(snapshot, translated, isProvisional, null);
    }

    private void store(TranslationTemplate.Snapshot snapshot, String translated,
                       boolean isProvisional, WriteBatch writes) {
        clearFailureState(snapshot);
        // Styled variants all converge on one durable semantic row. Keep a raw styled
        // row only when the backend damaged its markers so a safe plain projection is
        // impossible; this prevents colour permutations from disagreeing forever.
        boolean plainWritten = writePlainCopy(snapshot, translated, isProvisional, writes);
        if (hasCsMarkers(snapshot.key())) {
            boolean projected = writeStyleProjection(snapshot, translated, isProvisional, writes);
            // A semantic success whose projection could not be written (markers eaten,
            // residue) must leave a style-ledger debt, or the missing projection would
            // never be retried and exact-style chat would starve forever.
            if (!projected && !isProvisional && read(styleProjectionKey(snapshot)) == null) {
                failStyleProjection(snapshot);
            }
        }
        if (!plainWritten) {
            if (snapshot.changed()) {
                write(snapshot.key(), translated, isProvisional, writes);
            } else {
                write(snapshot.normalized(), translated, isProvisional, writes);
                if (!snapshot.normalized().equals(snapshot.source())) {
                    memory.put(snapshot.source(), translated); // whitespace alias, session only
                    markProvisional(snapshot.source(), isProvisional);
                }
            }
        }
        if (!isProvisional) notifyFinalWaiters(snapshot.source());
    }

    private void notifyFinalWaiters(String candidate) {
        String family = provisionalSemanticKey(candidate);
        java.util.concurrent.CopyOnWriteArrayList<FinalWaiter> waiters = finalWaiters.remove(family);
        if (waiters == null || waiters.isEmpty()) return;
        for (FinalWaiter waiter : waiters) {
            String ready = getCachedFinal(waiter.source(), waiter.exactStyle());
            if (ready != null) {
                try {
                    waiter.callback().accept(ready);
                } catch (RuntimeException ignored) {
                    // A client widget callback cannot break cache coordination.
                }
            } else {
                finalWaiters.computeIfAbsent(family,
                        ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(waiter);
            }
        }
    }

    private record FinalWaiter(String source, Consumer<String> callback, boolean exactStyle) {
    }

    private void write(String key, String value, boolean isProvisional) {
        write(key, value, isProvisional, null);
    }

    private void write(String key, String value, boolean isProvisional, WriteBatch writes) {
        if (!usable(key, value)) return;
        // First final semantic wording wins until explicit invalidation. A response for a
        // new CS presentation topology may translate the same term differently; it may add
        // its own style row, but can never rewrite an existing final semantic row. A final
        // primary answer still replaces a provisional fallback because provisional rows do
        // not satisfy hasFinalValue().
        if (hasFinalValue(key, writes)) return;
        memory.put(key, value);
        markProvisional(key, isProvisional);
        if (writes != null) writes.add(key, value, isProvisional);
        else if (isProvisional && provisionalStore != null) {
            // In the GT file a stand-in is simply a final GT translation; the
            // "awaiting AI" state lives in this cache's session set, and after a
            // restart in the fact that only the GT file carries the row.
            provisionalStore.put(key, value, false);
        } else if (store != null) {
            store.put(key, value, isProvisional);
        }
    }

    private boolean hasFinalValue(String key, WriteBatch writes) {
        if (writes != null && writes.values.containsKey(key)) {
            return !writes.provisionalKeys.contains(key);
        }
        String existing = memory.get(key);
        if (existing != null && usable(existing) && !provisional(key)) return true;
        if (store == null) return false;
        existing = store.get(key);
        return existing != null && usable(existing) && !store.isProvisional(key);
    }

    private boolean writePlainCopy(TranslationTemplate.Snapshot original, String translated,
                                   boolean isProvisional, WriteBatch writes) {
        if (translated == null) return false;
        String plainSource = stripStyle(original.source());
        if (plainSource.equals(original.normalized())) return false;

        // A semantic cache row must never retain the colour codes of whichever
        // render surface happened to translate first. Otherwise the same text can
        // leak one tooltip/HUD colour layout into every other surface.
        String plainValue = stripStyle(translated);
        if (CS_RESIDUE.matcher(plainValue).find()) return false;

        TranslationTemplate.Snapshot plain = templates.prepare(plainSource);
        if (!plain.hasTranslatableContent()) return false;

        // The provider result already contains canonical MT tokens. Store that verified
        // stream directly whenever it matches the plain semantic key. Target grammar may
        // reorder live values; restoring them and searching in source order would fail and
        // make the same styled line buy another translation every frame.
        if (usable(plain.key(), plainValue)) {
            store(plain, plainValue, isProvisional, writes);
            return true;
        }

        // Restoring a dynamic value may reinsert CS markers carried inside a player/name
        // slot. Strip once more before building the semantic row; otherwise the supposed
        // plain copy fails its own CS-shape validation and every new player misses it.
        String restored = stripStyle(original.restore(plainValue));
        if (restored.equals(plainSource)) return false; // untranslated backend echo

        String retokenized = plain.retokenize(restored);
        if (retokenized == null || !usable(plain.key(), retokenized)) return false;
        store(plain, retokenized, isProvisional, writes);
        return true;
    }

    /** @return whether a projection row write was actually attempted (valid content). */
    private boolean writeStyleProjection(TranslationTemplate.Snapshot snapshot, String translated,
                                         boolean isProvisional, WriteBatch writes) {
        if (translated == null || !hasCsMarkers(translated)) return false;
        String value = TextFilter.stripSectionCodes(translated);
        if (CS_RESIDUE.matcher(value).find() && !CS_MARKER.matcher(value).find()) return false;
        write(styleProjectionKey(snapshot), value, isProvisional, writes);
        return true;
    }

    private final class WriteBatch {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Set<String> provisionalKeys = new java.util.HashSet<>();

        void add(String key, String value, boolean isProvisional) {
            values.put(key, value);
            if (isProvisional) provisionalKeys.add(key);
            else provisionalKeys.remove(key);
        }

        void flush() {
            if (values.isEmpty()) return;
            PersistentStore gtStore = provisionalStore;
            if (gtStore != null && !provisionalKeys.isEmpty()) {
                Map<String, String> standIns = new LinkedHashMap<>();
                for (String key : provisionalKeys) {
                    String value = values.remove(key);
                    if (value != null) standIns.put(key, value);
                }
                if (!standIns.isEmpty()) gtStore.putBatch(standIns, Set.of());
                if (store != null && !values.isEmpty()) store.putBatch(values, Set.of());
                return;
            }
            if (store != null) store.putBatch(values, provisionalKeys);
        }
    }

    private static String stripCsMarkers(String text) {
        return text == null ? null : CS_MARKER.matcher(text).replaceAll("");
    }

    private static boolean hasCsMarkers(String text) {
        return text != null && CS_MARKER.matcher(text).find();
    }

    private static String styleProjectionKey(TranslationTemplate.Snapshot snapshot) {
        return TextFilter.stripSectionCodes(snapshot.key());
    }

    private static boolean sameSemanticText(String first, String second) {
        return stripStyle(first).equals(stripStyle(second));
    }

    private static String stripStyle(String text) {
        String withoutMarkers = stripCsMarkers(text == null ? "" : text);
        String withoutCodes = TextFilter.stripSectionCodes(withoutMarkers);
        return withoutCodes.strip();
    }

    // -------------------------------------------------------------------------
    // Provisional entries and failure control
    // -------------------------------------------------------------------------

    private void markProvisional(String key, boolean value) {
        if (value) provisional.add(key);
        else provisional.remove(key);
    }

    private boolean provisional(String key) {
        if (provisional.contains(key)) return true;
        if (store != null && store.isProvisional(key)) {
            provisional.add(key);
            return true;
        }
        return false;
    }

    private String provisionalSemanticKey(String candidate) {
        return templates.prepare(stripStyle(candidate == null ? "" : candidate)).key();
    }

    private void retryProvisional(String candidate) {
        String semanticKey = provisionalSemanticKey(candidate);
        // Migrate any session/disk provisional bit written by an older pre-release raw
        // alias, then use only the canonical key for single-flight, attempts and backoff.
        boolean pending = provisional(semanticKey);
        if (!pending && candidate != null && !semanticKey.equals(candidate)
                && provisional(candidate)) {
            markProvisional(semanticKey, true);
            pending = true;
        }
        BooleanSupplier gate = provisionalRetryGate;
        // There is deliberately no attempt ceiling: the exponential (capped) backoff
        // throttles supplements, and a provisional row always keeps its chance to be
        // upgraded to a final primary-engine translation.
        if (gate == null || !pending || !gate.getAsBoolean()
                || backingOff(semanticKey)
                || flights.containsKey(semanticKey)
                || queued(semanticKey)
                || !provisionalRetrying.add(semanticKey)) {
            return;
        }
        provisionalRetryAttempts.merge(semanticKey, 1, Integer::sum);

        TranslationTemplate.Snapshot snapshot = templates.prepare(semanticKey);
        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(snapshot.key());
        executeHigh(() -> {
            long debugId = debugSubmitted(List.of(snapshot.key()));
            try {
                TranslationResult result = translator.translate(snapshot.key(), targetLang);
                boolean usableResult = usable(snapshot.key(), result.translatedText());
                boolean isCurrent = current(snapshot.key(), expectedGeneration, expectedRevision);
                boolean identity = !usableResult
                        && isIdentityEcho(snapshot.key(), result.translatedText());
                boolean kept = isCurrent && identity
                        && learnKeepOriginal(snapshot, result.translatedText());
                if (!result.fromFallback() && usableResult) {
                    if (isCurrent) {
                        store(snapshot, result.translatedText(), false);
                        failedUntil.remove(semanticKey);
                        failedUntil.remove(snapshot.key());
                        provisionalRetryAttempts.remove(semanticKey);
                        provisionalRetryAttempts.remove(snapshot.key());
                    }
                } else if (isCurrent && !kept && !identity) {
                    fail(snapshot.key());
                }
                debugCompleted(debugId, usableResult || kept ? result.translatedText() : null,
                        kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                                : !usableResult ? TranslationDebugLog.Status.FAILED
                                : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                                : TranslationDebugLog.Status.SUCCESS);
            } catch (TranslationException | RuntimeException e) {
                if (current(snapshot.key(), expectedGeneration, expectedRevision)) fail(snapshot.key());
                debugCompleted(debugId, false);
            } finally {
                provisionalRetrying.remove(semanticKey);
            }
        });
    }

    private void fail(String key) {
        if (key == null) return;
        TranslationTemplate.Snapshot snapshot = templates.prepare(key);
        if (hasFinalSemantic(snapshot)) {
            if (hasCsMarkers(snapshot.key())) failStyleProjection(snapshot);
            return;
        }
        String stateKey = provisionalSemanticKey(snapshot.key());
        // Identity confirmation means consecutive successful echoes. Any transport or
        // malformed-content failure breaks that streak.
        contentFailures.remove(stateKey);
        int attempt = contentRetryAttempts.merge(stateKey, 1, Integer::sum);
        failTemporarily(stateKey, attempt);
        retrySnapshots.put(stateKey, snapshot);
        requestFallback(snapshot);
    }

    private void executeHigh(Runnable task) {
        if (executor instanceof PriorityTranslationExecutor priority) priority.executeHigh(task);
        else executor.execute(task);
    }

    private void executeLow(Runnable task) {
        if (executor instanceof PriorityTranslationExecutor priority) priority.executeLow(task);
        else executor.execute(task);
    }

    private void failStyleProjection(TranslationTemplate.Snapshot snapshot) {
        String stateKey = styleFailureKey(snapshot);
        contentFailures.remove(stateKey);
        int attempt = contentRetryAttempts.merge(stateKey, 1, Integer::sum);
        failTemporarilyState(stateKey, attempt);
        retrySnapshots.put(stateKey, snapshot);
    }

    /**
     * Exponential retry backoff for temporary failures (damaged shapes, empty answers,
     * fallback-only supplements, transport errors on retry paths). The delay doubles
     * per attempt but is clamped to {@link #MAX_FAILURE_BACKOFF_MS}: 429/5xx windows
     * usually clear quickly, so a few hovers later the line must translate. When a
     * failure ledger is configured the mark is persisted with its attempt and expiry.
     */
    private void failTemporarily(String key, int attempt) {
        failTemporarilyState(provisionalSemanticKey(key), attempt);
    }

    private void failTemporarilyState(String stateKey, int attempt) {
        long now = clock.getAsLong();
        if (failureBackoffMs <= 0L) {
            failedUntil.remove(stateKey);
            PersistentStore failures = failureStore;
            if (failures != null) {
                failures.put(stateKey, FAILURE_TEMPORARY_PREFIX + attempt + ":" + now);
            }
            return;
        }
        long multiplier = 1L << Math.min(6, Math.max(0, attempt - 1));
        long delay;
        try {
            delay = Math.multiplyExact(failureBackoffMs, multiplier);
        } catch (ArithmeticException overflow) {
            delay = Long.MAX_VALUE;
        }
        delay = Math.min(delay, Math.max(failureBackoffMs, MAX_FAILURE_BACKOFF_MS));
        long until = delay >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
        failedUntil.put(stateKey, until);
        PersistentStore failures = failureStore;
        if (failures != null) {
            failures.put(stateKey, FAILURE_TEMPORARY_PREFIX + attempt + ":" + until);
        }
    }

    /**
     * Split failure ledger. Only an identity echo (the provider answered, unchanged:
     * a proper noun that legitimately needs no translation) may count toward a durable
     * keep-original decision. Every other unusable response — empty, mojibake, damaged
     * CS/MT/PB markers, layout or transliteration mismatches — is a provider bug that
     * must heal, so it earns only an exponentially growing, capped retry backoff and
     * can never poison the line permanently.
     *
     * @return whether a durable keep-original decision was learned
     */
    private boolean handleUnusableContent(TranslationTemplate.Snapshot snapshot, String translated) {
        // The semantic AI wording may already be final while a presentation-only CS
        // projection keeps losing its markers. Such an identity can never mean the
        // sentence itself should be kept original; retry the projection as temporary.
        if (hasFinalSemantic(snapshot)) {
            if (hasCsMarkers(snapshot.key())) failStyleProjection(snapshot);
            return false;
        }
        if (isIdentityEcho(snapshot.key(), translated)) {
            if (learnKeepOriginal(snapshot, translated)) return true;
            return false;
        }
        String stateKey = contentFailureKey(snapshot);
        contentFailures.remove(stateKey);
        int attempt = contentRetryAttempts.merge(stateKey, 1, Integer::sum);
        failTemporarily(stateKey, attempt);
        retrySnapshots.put(stateKey, snapshot);
        requestFallback(snapshot);
        return false;
    }

    /** A legitimate untranslatable echo: a real answer whose text equals the request. */
    private static boolean isIdentityEcho(String source, String translated) {
        return usable(translated)
                && translated.trim().equals(source == null ? "" : source.trim());
    }

    /** Failure decisions are shared by the whole de-styled semantic family. */
    private String contentFailureKey(TranslationTemplate.Snapshot snapshot) {
        String plainSource = stripStyle(snapshot.source());
        TranslationTemplate.Snapshot plain = templates.prepare(plainSource);
        return plain.key().isEmpty() ? snapshot.key() : plain.key();
    }

    /** A stored translation is proof the provider works for this text again. */
    private void clearFailureState(TranslationTemplate.Snapshot snapshot) {
        String failureKey = contentFailureKey(snapshot);
        String styleFailure = hasCsMarkers(snapshot.key()) ? styleFailureKey(snapshot) : null;
        contentFailures.remove(snapshot.key());
        contentFailures.remove(failureKey);
        contentRetryAttempts.remove(snapshot.key());
        contentRetryAttempts.remove(failureKey);
        provisionalRetryAttempts.remove(failureKey);
        failedUntil.remove(snapshot.key());
        failedUntil.remove(failureKey);
        TranslationTemplate.Snapshot pendingRetry = retrySnapshots.remove(failureKey);
        retrySnapshots.remove(snapshot.key());
        // A plain semantic success must not uproot a pending CS-marked retry whose
        // presentation projection is still missing: move that debt to the style
        // ledger (never GT-eligible) instead of silently dropping it forever.
        if (pendingRetry != null && hasCsMarkers(pendingRetry.key())
                && !pendingRetry.key().equals(snapshot.key())
                && read(styleProjectionKey(pendingRetry)) == null) {
            failStyleProjection(pendingRetry);
        }
        if (styleFailure != null) {
            contentFailures.remove(styleFailure);
            contentRetryAttempts.remove(styleFailure);
            failedUntil.remove(styleFailure);
            retrySnapshots.remove(styleFailure);
        }
        PersistentStore failures = failureStore;
        if (failures != null) {
            failures.remove(snapshot.key());
            failures.remove(failureKey);
            if (styleFailure != null) failures.remove(styleFailure);
        }
    }

    /**
     * Three consecutive identity echoes become a durable keep-original decision:
     * the provider is answering, and its answer is that this text needs no
     * translation. Genuine failures never reach this method (see
     * {@link #handleUnusableContent}), and transport exceptions never call it either,
     * so a temporary 429/outage cannot poison the negative cache. Individual/global
     * retranslation removes the decision through invalidate/clear.
     */
    private boolean learnKeepOriginal(TranslationTemplate.Snapshot snapshot, String translated) {
        if (snapshot == null) return false;
        String failureKey = contentFailureKey(snapshot);
        // A valid provider response breaks the malformed/transport-failure streak even
        // when its identity still needs two more confirmations.
        contentRetryAttempts.remove(failureKey);
        int count = contentFailures.merge(failureKey, 1, Integer::sum);
        if (count < CONTENT_FAILURE_LIMIT) {
            long delay = failureBackoffMs <= 0L ? 0L : Math.min(
                    MAX_FAILURE_BACKOFF_MS, failureBackoffMs * (1L << Math.min(6, count - 1)));
            long now = clock.getAsLong();
            long until = delay >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
            if (delay > 0L) failedUntil.put(failureKey, until);
            PersistentStore failures = failureStore;
            if (failures != null) {
                failures.put(failureKey, FAILURE_IDENTITY_PREFIX + count + ":" + until);
            }
            retrySnapshots.put(failureKey, snapshot);
            requestFallback(snapshot);
            return false;
        }

        contentFailures.remove(failureKey);
        contentRetryAttempts.remove(snapshot.key());
        contentRetryAttempts.remove(failureKey);
        failedUntil.remove(snapshot.key());
        provisional.remove(snapshot.key());
        removeStored(snapshot.key());
        removeStored(styleProjectionKey(snapshot));
        failedUntil.remove(failureKey);
        provisional.remove(failureKey);
        removeStored(failureKey);
        memory.put(failureKey, KEEP_ORIGINAL);
        PersistentStore failures = failureStore;
        if (store != null) store.put(failureKey, KEEP_ORIGINAL, false);
        if (failures != null) failures.remove(failureKey);
        retrySnapshots.remove(failureKey);
        notifyFinalWaiters(failureKey);
        return true;
    }

    private boolean backingOff(String key) {
        return backingOffState(provisionalSemanticKey(key));
    }

    private boolean backingOffState(String stateKey) {
        Long until = failedUntil.get(stateKey);
        if (until == null) {
            until = restoreTemporaryFailure(stateKey);
            if (until == null) return false;
        }
        if (clock.getAsLong() < until) return true;
        failedUntil.remove(stateKey, until);
        // Keep the durable row until the retry commits a success/new failure. If the
        // process exits between expiry and the HTTP result, the attempt is not forgotten.
        return false;
    }

    /**
     * Rehydrate a persisted temporary-failure mark into the session maps, so retry
     * spacing and attempt escalation survive a restart. Damaged rows are deleted.
     */
    private Long restoreTemporaryFailure(String key) {
        PersistentStore failures = failureStore;
        if (failures == null || key == null) return null;
        String row = failures.get(key);
        boolean identity = row != null && row.startsWith(FAILURE_IDENTITY_PREFIX);
        if (!identity && !startsWithTemporary(row)) return null;
        String prefix = identity ? FAILURE_IDENTITY_PREFIX : FAILURE_TEMPORARY_PREFIX;
        String[] parts = row.substring(prefix.length()).split(":", 2);
        try {
            int attempt = Integer.parseInt(parts[0]);
            long until = Long.parseLong(parts[1]);
            if (identity) {
                contentFailures.putIfAbsent(key, attempt);
            } else {
                contentRetryAttempts.putIfAbsent(key, attempt);
                provisionalRetryAttempts.putIfAbsent(key, attempt);
            }
            failedUntil.putIfAbsent(key, until);
            String retrySource = key.startsWith(STYLE_FAILURE_PREFIX)
                    ? key.substring(STYLE_FAILURE_PREFIX.length()) : key;
            retrySnapshots.putIfAbsent(key, templates.prepare(retrySource));
            return until;
        } catch (RuntimeException damaged) {
            failures.remove(key);
            return null;
        }
    }

    private static boolean startsWithTemporary(String row) {
        return row != null && row.startsWith(FAILURE_TEMPORARY_PREFIX);
    }

    /**
     * A provisional fallback is deliberately invisible on a cold primary miss. It
     * becomes eligible only after this engine has failed for the same semantic family,
     * including a failure restored from the shared, engine-namespaced ledger.
     */
    private boolean fallbackAllowed(String source) {
        if (!isFallbackEnabled()) return false;
        if (!fallbackHitsProvisional) return true;
        return mayUseFallback(source);
    }

    /** Start the lower-priority engine only after this engine actually failed. */
    private void requestFallback(TranslationTemplate.Snapshot snapshot) {
        if (!isFallbackEnabled()) return;
        TranslationCache lower = fallback;
        if (!fallbackHitsProvisional || lower == null || snapshot == null) return;
        // A CS projection is presentation only. If the AI semantic wording already
        // succeeded, a marker-topology failure must retry AI but must not buy GT.
        if (hasFinalSemantic(snapshot)) return;
        if (hasCsMarkers(snapshot.key())) {
            lower.requestCoalescedExactStyle(snapshot.source(), ignored -> { }, false);
        } else {
            lower.requestBatched(snapshot.source());
        }
    }

    private boolean hasFinalSemantic(TranslationTemplate.Snapshot snapshot) {
        if (snapshot == null) return false;
        TranslationTemplate.Snapshot plain = templates.prepare(stripStyle(snapshot.source()));
        String ownSemantic = lookupSnapshot(plain, this);
        return ownSemantic != null && !provisional(plain.key());
    }

    private static String styleFailureKey(TranslationTemplate.Snapshot snapshot) {
        return STYLE_FAILURE_PREFIX + (snapshot == null ? "" : snapshot.key());
    }

    /** Re-enqueue durable failures once their backoff expires, even if the surface vanished. */
    private void enqueueDueRetries() {
        for (Map.Entry<String, TranslationTemplate.Snapshot> entry : retrySnapshots.entrySet()) {
            String stateKey = entry.getKey();
            TranslationTemplate.Snapshot snapshot = entry.getValue();
            if (snapshot == null || keepsOriginal(stateKey)) {
                discardRetryState(stateKey, snapshot);
                continue;
            }
            // A style retry is done once the projection row exists. Its snapshot key may
            // carry §-codes, so projectionKey != snapshot.key() and lookupSnapshot below
            // cannot see the projection row — without this check the retry would rebuy
            // the same marked key forever.
            if (stateKey.startsWith(STYLE_FAILURE_PREFIX)
                    && read(styleProjectionKey(snapshot)) != null) {
                discardRetryState(stateKey, snapshot);
                continue;
            }
            String hit = lookupSnapshot(snapshot, this);
            boolean pendingPrimary = provisional(stateKey) || provisional(snapshot.key());
            if (hit != null && !pendingPrimary) {
                discardRetryState(stateKey, snapshot);
                continue;
            }
            if (pendingPrimary) {
                retryProvisional(stateKey);
                continue;
            }
            if (backingOffState(stateKey) || flights.containsKey(snapshot.key())
                    || provisionalRetrying.contains(stateKey)) continue;
            synchronized (queueLock) {
                if (!queue.containsKey(snapshot.key())) enqueue(snapshot, null);
            }
        }
    }

    private void discardRetryState(String stateKey, TranslationTemplate.Snapshot snapshot) {
        retrySnapshots.remove(stateKey, snapshot);
        contentFailures.remove(stateKey);
        contentRetryAttempts.remove(stateKey);
        failedUntil.remove(stateKey);
        PersistentStore failures = failureStore;
        if (failures != null) failures.remove(stateKey);
    }

    private boolean suppressed(String key) {
        ChurnGuard guard = churnGuard;
        return guard != null && guard.shouldSuppress(key);
    }

    private long debugSubmitted(List<String> keys) {
        TranslationDebugLog log = debugLog;
        return log == null ? 0L : log.submitted(debugEngine, keys);
    }

    private void debugCompleted(long requestId, boolean success) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, success);
    }

    private void debugCompleted(long requestId, TranslationDebugLog.Status status) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, status);
    }

    private void debugCompleted(long requestId, String translation,
                                TranslationDebugLog.Status status) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId,
                translation == null ? List.of() : List.of(translation), List.of(status));
    }

    private void debugCompleted(long requestId, List<String> translations,
                                List<TranslationDebugLog.Status> statuses) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, translations, statuses);
    }

    // -------------------------------------------------------------------------
    // Flight completion and callbacks
    // -------------------------------------------------------------------------

    private void finishFlight(String key, Flight flight) {
        flights.remove(key, flight);
        for (Callback callback : flight.close()) {
            deliver(callback, cachedFor(callback));
        }
    }

    private String cachedFor(Callback callback) {
        if (callback == null || callback.snapshot == null) return null;
        String hit = callback.exactStyle
                ? getCachedExactStyle(callback.snapshot.source())
                : getCached(callback.snapshot.source());
        if (callback.exactStyle) return hit;
        return hit != null ? hit : lookupSnapshot(callback.snapshot, this);
    }

    private static void deliver(Callback callback, String value) {
        if (callback == null || callback.consumer == null) return;
        if (!callback.always && value == null) return;
        try {
            callback.consumer.accept(value);
        } catch (RuntimeException ignored) {
            // A client callback cannot break request coordination.
        }
    }

    private record Callback(TranslationTemplate.Snapshot snapshot,
                            Consumer<String> consumer,
                            boolean always,
                            boolean exactStyle) {
    }

    private static final class Flight {
        private final List<Callback> callbacks = new ArrayList<>();
        private boolean closed;

        synchronized boolean add(Callback callback) {
            if (closed) return false;
            if (callback != null && callback.consumer != null) callbacks.add(callback);
            return true;
        }

        synchronized List<Callback> close() {
            closed = true;
            List<Callback> out = new ArrayList<>(callbacks);
            callbacks.clear();
            return out;
        }
    }

    private static final class Queued {
        final TranslationTemplate.Snapshot snapshot;
        final List<Callback> callbacks = new ArrayList<>();

        Queued(TranslationTemplate.Snapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    // -------------------------------------------------------------------------
    // Validation and administration
    // -------------------------------------------------------------------------

    private static boolean usable(String translated) {
        return translated != null && !translated.isEmpty()
                && !TextFilter.isLikelyMojibake(TextFilter.stripDecorativeSymbols(translated));
    }

    private static boolean usable(String source, String translated) {
        return usable(translated)
                && !translated.equals(source)
                && !translated.trim().equals(source == null ? "" : source.trim())
                && newlineCount(source) == newlineCount(translated)
                && matchingCsShape(source, translated)
                && matchingMtShape(source, translated)
                && matchingParagraphBreakShape(source, translated)
                && matchingParagraphSlotShape(source, translated)
                && TranslationTemplate.layoutSkeletonMatches(source, translated)
                && TranslationTemplate.styleSlotShapeMatches(source, translated)
                && !TextFilter.isPartialTransliteration(source, translated)
                && !TextFilter.hasUntranslatedAnchoredField(source, translated);
    }

    private static long newlineCount(String text) {
        if (text == null || text.isEmpty()) return 0L;
        return text.chars().filter(ch -> ch == '\n').count();
    }

    /** Dynamic-value slots may move for grammar, but none may disappear or multiply. */
    private static boolean matchingMtShape(String source, String translated) {
        return tokenMultiset(source, MT_TOKEN).equals(tokenMultiset(translated, MT_TOKEN));
    }

    /** Paragraph breaks are layout AND semantic context boundaries. Unlike movable value
     * slots, their order is fixed so a model can never fill line 2 into line 1. */
    private static boolean matchingParagraphBreakShape(String source, String translated) {
        return tokenSequence(source, PARAGRAPH_BREAK_TOKEN)
                .equals(tokenSequence(translated, PARAGRAPH_BREAK_TOKEN));
    }

    /** Dynamic values may reorder within a row for target-language grammar, but a
     * wallet amount or stat value must never cross a protected PB row boundary. */
    private static boolean matchingParagraphSlotShape(String source, String translated) {
        if (source == null || !PARAGRAPH_BREAK_TOKEN.matcher(source).find()) return true;
        if (translated == null) return false;
        String[] sourceRows = PARAGRAPH_BREAK_TOKEN.split(source, -1);
        String[] translatedRows = PARAGRAPH_BREAK_TOKEN.split(translated, -1);
        if (sourceRows.length != translatedRows.length) return false;
        for (int i = 0; i < sourceRows.length; i++) {
            if (!tokenMultiset(sourceRows[i], MT_TOKEN)
                    .equals(tokenMultiset(translatedRows[i], MT_TOKEN))) return false;
        }
        return true;
    }

    private static List<String> tokenSequence(String text, Pattern pattern) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    private static Map<String, Integer> tokenMultiset(String text, Pattern pattern) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        if (text == null) return out;
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) out.merge(matcher.group(1), 1, Integer::sum);
        return out;
    }

    /**
     * Reject both newly returned and legacy on-disk values whose style-marker topology
     * no longer matches the request.  Comparing balanced marker-pair counts catches a
     * missing opener/closer, an extra trailing token, re-numbering, and malformed residue,
     * while still allowing a translator to move a complete styled phrase for grammar.
     */
    private static boolean matchingCsShape(String source, String translated) {
        // CS is a legacy renderer-local protocol. Plain Minecraft text may legitimately
        // contain names such as "CS2" or "CS50"; only validate topology when the source
        // actually contains a complete marker pair.
        if (!hasCsMarkers(source)) {
            if (translated == null) return true;
            boolean hasProtocolBrackets = translated.indexOf('\u27E6') >= 0
                    || translated.indexOf('\u27E7') >= 0;
            return !hasCsMarkers(translated)
                    && !(hasProtocolBrackets && CS_RESIDUE.matcher(translated).find());
        }
        String sourceShape = csShape(source);
        String translatedShape = csShape(translated);
        return sourceShape != null && sourceShape.equals(translatedShape);
    }

    private static String csShape(String text) {
        if (text == null) return "";
        java.util.regex.Matcher matcher = CS_TOKEN.matcher(text);
        Map<String, Integer> pairs = new java.util.TreeMap<>();
        String open = null;
        int cursor = 0;
        while (matcher.find()) {
            if (CS_RESIDUE.matcher(text.substring(cursor, matcher.start())).find()) return null;
            boolean closing = !matcher.group(1).isEmpty();
            String index = matcher.group(2);
            if (!closing) {
                if (open != null) return null;
                open = index;
            } else {
                if (open == null || !open.equals(index)) return null;
                pairs.merge(index, 1, Integer::sum);
                open = null;
            }
            cursor = matcher.end();
        }
        if (CS_RESIDUE.matcher(text.substring(cursor)).find()) return null;
        if (open != null) return null;
        return pairs.toString();
    }

    private void removeStored(String key) {
        if (key == null) return;
        memory.remove(key);
        provisional.remove(key);
        if (store != null) store.remove(key);
        // provisionalStore is the independently owned GT backup, not scratch space.
        // TranslationService.invalidateBoth asks the GT cache owner to remove it when
        // the user explicitly retranslates; an AI cleanup must never delete valid GT data.
    }

    public void setTargetLang(String targetLang) {
        String next = targetLang == null || targetLang.isBlank() ? "zh-TW" : targetLang;
        if (next.equals(this.targetLang)) return;
        beginTargetLangChange();
        completeTargetLangChange(next);
    }

    /** Phase one used by TranslationService for two caches sharing one failure file. */
    public void beginTargetLangChange() {
        reset(false);
    }

    /** Phase two: every participating cache must finish phase one before any calls this. */
    public void completeTargetLangChange(String targetLang) {
        String next = targetLang == null || targetLang.isBlank() ? "zh-TW" : targetLang;
        if (store != null) store.setLanguage(next);
        if (provisionalStore != null) provisionalStore.setLanguage(next);
        if (failureStore != null) failureStore.setLanguage(next);
        this.targetLang = next;
        reset(false);
        // The newly opened language file may still mix in legacy provisional rows.
        migrateProvisionalRows();
        hydrateFailureStore();
    }

    public void clear() {
        reset(true);
    }

    /** Drop only session state; optionally delete the active language's disk file. */
    private void reset(boolean clearStore) {
        generation.incrementAndGet();
        keyRevisions.clear();
        memory.clear();
        failedUntil.clear();
        contentFailures.clear();
        contentRetryAttempts.clear();
        provisional.clear();
        provisionalRetrying.clear();
        provisionalRetryAttempts.clear();
        retrySnapshots.clear();
        finalWaiters.clear();
        List<Callback> cancelled = new ArrayList<>();
        for (Flight flight : flights.values()) cancelled.addAll(flight.close());
        flights.clear();
        synchronized (queueLock) {
            for (Queued queued : queue.values()) cancelled.addAll(queued.callbacks);
            queue.clear();
            queueGrew = false;
            settleTicks = 0;
        }
        cancelled.forEach(callback -> deliver(callback, null));
        if (clearStore) {
            if (store != null) store.clear();
            // A full clear is an explicit retranslate-everything request, so learned
            // failure decisions go too. The GT file belongs to the sibling cache and
            // is cleared by its own owner.
            PersistentStore failures = failureStore;
            if (failures != null) failures.clear();
        }
    }

    public void invalidate(String source) {
        if (source == null) return;
        finalWaiters.remove(provisionalSemanticKey(source));
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        String stripped = stripStyle(source);
        TranslationTemplate.Snapshot plain = templates.prepare(stripped);
        Set<String> keys = new java.util.LinkedHashSet<>();
        Collections.addAll(keys, snapshot.source(), snapshot.normalized(),
                snapshot.key(), stripped,
                plain.normalized(), plain.key());
        if (hasCsMarkers(snapshot.key())) {
            keys.add(styleProjectionKey(snapshot));
            keys.add(styleFailureKey(snapshot));
        }

        // Bump request revisions before detaching work. Any old HTTP response that
        // races this deletion is rejected even if it completes after the new request.
        for (String key : keys) {
            if (key != null) keyRevisions.put(key, revisionSequence.incrementAndGet());
        }

        List<Callback> cancelled = new ArrayList<>();
        PersistentStore failures = failureStore;
        for (String key : keys) {
            if (key == null) continue;
            contentFailures.remove(key);
            contentRetryAttempts.remove(key);
            provisionalRetryAttempts.remove(key);
            retrySnapshots.remove(key);
            // Manual retranslation is the designated unlock for every learned failure:
            // echo decisions, temporary marks and their in-session backoff all go.
            failedUntil.remove(key);
            if (failures != null) failures.remove(key);
            Flight flight = flights.remove(key);
            if (flight != null) cancelled.addAll(flight.close());
            removeStored(key);
        }
        synchronized (queueLock) {
            for (String key : keys) {
                Queued removed = queue.remove(key);
                if (removed != null) cancelled.addAll(removed.callbacks);
            }
            queueGrew = !queue.isEmpty();
            if (queue.isEmpty()) settleTicks = 0;
        }
        cancelled.forEach(callback -> deliver(callback, null));
    }

    /**
     * Replace a non-templated key with a translation derived from a larger, already
     * validated contextual translation. Used to make an isolated item name share the
     * authoritative wording of its full tooltip title. Dynamic keys are rejected: a
     * caller cannot accidentally store restored live values into a template slot.
     */
    public boolean replaceFinal(String source, String translated) {
        if (source == null || translated == null) return false;
        TranslationTemplate.Snapshot before = templates.prepare(source);
        if (before.changed() || !usable(before.key(), translated)) return false;

        invalidate(source);
        TranslationTemplate.Snapshot fresh = templates.prepare(source);
        if (fresh.changed() || !usable(fresh.key(), translated)) return false;
        store(fresh, translated, false);
        failedUntil.remove(fresh.key());
        return translated.equals(getCached(source));
    }

    public boolean isPending(String source) {
        if (source == null) return false;
        String key = templates.prepare(source).key();
        if (flights.containsKey(key)) return true;
        synchronized (queueLock) {
            return queue.containsKey(key);
        }
    }

    public int pendingCount() {
        synchronized (queueLock) {
            return flights.size() + queue.size();
        }
    }

    public int size() {
        return memory.size();
    }
}
