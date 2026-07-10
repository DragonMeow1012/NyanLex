package com.dragonmeow.mctranslator.cache;

import com.dragonmeow.mctranslator.translate.ChurnGuard;
import com.dragonmeow.mctranslator.translate.TemplateText;
import com.dragonmeow.mctranslator.translate.TextFilter;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.TranslationTemplate;
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
    private static final int MAX_PROVISIONAL_RETRIES = 3;
    private static final int MAX_FINAL_WAITER_FAMILIES = 512;
    /** Durable negative-cache value. It is never shown; reads return the original key. */
    private static final String KEEP_ORIGINAL = "\u0000MT_KEEP_ORIGINAL";

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
    private final TranslationTemplate templates = new TranslationTemplate();
    private final Map<String, String> memory;

    private volatile TranslationCache fallback;
    private volatile boolean fallbackHitsProvisional;
    private volatile ChurnGuard churnGuard = new ChurnGuard();

    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    /** Consecutive provider responses that are unusable for this semantic template.
     *  Transport failures are intentionally excluded: an outage must not poison text. */
    private final Map<String, Integer> contentFailures = new ConcurrentHashMap<>();
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
     * a sibling hit is displayed immediately but marked only in this cache as awaiting
     * replacement; the provisional retry gate then schedules one primary translation.
     */
    public void setFallback(TranslationCache fallback, boolean asProvisional) {
        this.fallback = fallback == this ? null : fallback;
        this.fallbackHitsProvisional = fallback != null && fallback != this && asProvisional;
    }

    public void setChurnGuard(ChurnGuard churnGuard) {
        this.churnGuard = churnGuard;
    }

    public void setProvisionalRetryGate(BooleanSupplier gate) {
        this.provisionalRetryGate = gate;
    }

    public void setDebugLog(String engine, TranslationDebugLog log) {
        this.debugEngine = engine == null || engine.isBlank() ? "translator" : engine;
        this.debugLog = log;
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    public String getCached(String source) {
        if (source == null) return null;
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        TranslationCache sibling = fallback;

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
                    // The final semantic AI wording is already usable.  Do not keep showing
                    // an older GT style projection (or the source text) while a cosmetic CS
                    // topology supplement runs in the background.
                    if (!provisional(plainSnapshot.key())) retryStyleProjection(snapshot);
                    return TextFilter.markStyleFallback(plainHit);
                }
                if (styleHit != null) removeStored(styleProjectionKey(snapshot));
                // Meaning must not wait for presentation.  The renderer can safely project
                // a semantic hit onto the current component (keeping verbatim numeric/value
                // anchors in their exact colours) while an exact CS topology is unavailable.
                // This also prevents the same visible tooltip line from spawning one request
                // for its words and another request merely for its colour boundaries.
                if (plainHit != null) return TextFilter.markStyleFallback(plainHit);
            } else if (plainHit != null) {
                return plainHit;
            }
        }

        String hit = marked ? null : lookupSnapshot(snapshot, this);
        if (hit != null) return hit;

        if (sibling != null) {
            hit = sibling.getCached(source);
            if (hit != null) {
                return acceptFallbackHit(source, hit);
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
        String hit = getCached(source);
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

    private String acceptFallbackHit(String source, String hit) {
        // Read-through aliases stay session-only; the lower-priority cache owns its
        // durable entry. In AI mode the alias is provisional and immediately queues
        // an AI supplement without delaying the GT text already on screen. Retry state
        // belongs to the de-styled semantic template, never to each player/number/style
        // variant, or ten lobby joins would each start a fresh three-attempt family.
        memory.put(source, hit);
        if (fallbackHitsProvisional) {
            String semanticKey = provisionalSemanticKey(source);
            // Migration consults this canonical family bit as well as an exact alias,
            // so volatile raw number/time variants never need their own provisional marks.
            markProvisional(semanticKey, true);
            retryProvisional(semanticKey);
        }
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
        if (store == null) return false;
        value = store.get(key);
        if (!KEEP_ORIGINAL.equals(value)) return false;
        memory.put(key, value);
        return true;
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
                boolean kept = learnKeepOriginal(snapshot, result.translatedText());
                if (!kept) fail(snapshot.key());
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
            fail(snapshot.key());
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
            deliver(new Callback(null, callback, always), cached);
            return;
        }

        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        Callback cb = new Callback(snapshot, callback, always);
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
        executor.execute(() -> {
            long debugId = 0L;
            try {
                if (lookupSnapshot(snapshot, this) == null) {
                    debugId = debugSubmitted(List.of(key));
                    TranslationResult result = translator.translate(key, targetLang);
                    boolean usableResult = usable(key, result.translatedText());
                    boolean kept = !usableResult && learnKeepOriginal(snapshot, result.translatedText());
                    if (usableResult) {
                        if (current(key, expectedGeneration, expectedRevision)) {
                            store(snapshot, result.translatedText(), result.fromFallback());
                            failedUntil.remove(key);
                        }
                    } else if (!kept) {
                        fail(key);
                    }
                    debugCompleted(debugId, usableResult || kept ? result.translatedText() : null,
                            kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                                    : !usableResult ? TranslationDebugLog.Status.FAILED
                                    : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                                    : TranslationDebugLog.Status.SUCCESS);
                }
            } catch (TranslationException | RuntimeException e) {
                fail(key);
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
        String cached = getCached(source);
        if (cached != null) {
            deliver(new Callback(null, callback, always), cached);
            return;
        }

        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        Callback cb = new Callback(snapshot, callback, always);
        if (!eligible(snapshot)) {
            deliver(cb, null);
            return;
        }

        while (true) {
            Flight flight = flights.get(snapshot.key());
            if (flight == null) break;
            if (flight.add(cb)) return;
            cached = lookupSnapshot(snapshot, this);
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
        if (source == null || callback == null) return;
        String ready = getCachedFinal(source);
        if (ready != null) {
            callback.accept(ready);
            return;
        }

        String family = provisionalSemanticKey(source);
        FinalWaiter waiter = new FinalWaiter(source, callback);
        if (!finalWaiters.containsKey(family)
                && finalWaiters.size() >= MAX_FINAL_WAITER_FAMILIES) {
            java.util.Iterator<String> oldest = finalWaiters.keySet().iterator();
            if (oldest.hasNext()) finalWaiters.remove(oldest.next());
        }
        finalWaiters.computeIfAbsent(family,
                ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(waiter);

        // Close the registration race with a final store on another worker.
        ready = getCachedFinal(source);
        if (ready != null) {
            notifyFinalWaiters(family);
            return;
        }

        requestCoalesced(source, ignored -> {
            if (getCachedFinal(source) != null) notifyFinalWaiters(family);
            else retryProvisional(family);
        }, true);
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

    /** Called once per client tick. */
    public void flushBatch() {
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
        executor.execute(() -> {
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
        executor.execute(() -> {
            try {
                translateBatch(send, requestContext, expectedGeneration, expectedRevisions);
            } finally {
                owned.forEach(this::finishFlight);
            }
        });
    }

    public void translateAllAsync(List<String> sources, Consumer<List<String>> onResults) {
        executor.execute(() -> {
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
                keys.forEach(this::fail);
                debugCompleted(debugId, false);
                return false;
            }
            List<Boolean> keptOriginal = new ArrayList<>(todo.size());
            for (int i = 0; i < todo.size(); i++) {
                TranslationTemplate.Snapshot snapshot = todo.get(i);
                TranslationResult result = results.get(i);
                boolean usableResult = usable(snapshot.key(), result.translatedText());
                boolean kept = !usableResult && learnKeepOriginal(snapshot, result.translatedText());
                keptOriginal.add(kept);
                if (usableResult) {
                    if (current(snapshot.key(), expectedGeneration,
                            expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                        // Batched requests collect every canonical/style-independent
                        // entry and persist them in one atomic store update below.
                        failedUntil.remove(snapshot.key());
                    }
                } else if (!kept) {
                    fail(snapshot.key());
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
            keys.forEach(this::fail);
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
        contentFailures.remove(snapshot.key());
        // Styled variants all converge on one durable semantic row. Keep a raw styled
        // row only when the backend damaged its markers so a safe plain projection is
        // impossible; this prevents colour permutations from disagreeing forever.
        boolean plainWritten = writePlainCopy(snapshot, translated, isProvisional, writes);
        if (hasCsMarkers(snapshot.key())) {
            writeStyleProjection(snapshot, translated, isProvisional, writes);
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
            String ready = getCachedFinal(waiter.source());
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

    private record FinalWaiter(String source, Consumer<String> callback) {
    }

    private void write(String key, String value, boolean isProvisional) {
        write(key, value, isProvisional, null);
    }

    private void write(String key, String value, boolean isProvisional, WriteBatch writes) {
        if (!usable(key, value)) return;
        // A lower-priority GT/fallback result may add a missing style topology, but it
        // must never downgrade an existing final AI semantic row (or final style row).
        if (isProvisional && hasFinalValue(key, writes)) return;
        memory.put(key, value);
        markProvisional(key, isProvisional);
        if (writes != null) writes.add(key, value, isProvisional);
        else if (store != null) store.put(key, value, isProvisional);
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

    private void writeStyleProjection(TranslationTemplate.Snapshot snapshot, String translated,
                                      boolean isProvisional, WriteBatch writes) {
        if (translated == null || !hasCsMarkers(translated)) return;
        String value = TextFilter.stripSectionCodes(translated);
        if (CS_RESIDUE.matcher(value).find() && !CS_MARKER.matcher(value).find()) return;
        write(styleProjectionKey(snapshot), value, isProvisional, writes);
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
            if (store != null && !values.isEmpty()) store.putBatch(values, provisionalKeys);
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
        if (gate == null || !pending || !gate.getAsBoolean()
                || backingOff(semanticKey)
                || provisionalRetryAttempts.getOrDefault(semanticKey, 0) >= MAX_PROVISIONAL_RETRIES
                || !provisionalRetrying.add(semanticKey)) {
            return;
        }
        int attempt = provisionalRetryAttempts.merge(semanticKey, 1, Integer::sum);

        TranslationTemplate.Snapshot snapshot = templates.prepare(semanticKey);
        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(snapshot.key());
        executor.execute(() -> {
            long debugId = debugSubmitted(List.of(snapshot.key()));
            try {
                TranslationResult result = translator.translate(snapshot.key(), targetLang);
                boolean usableResult = usable(snapshot.key(), result.translatedText());
                boolean kept = !usableResult && learnKeepOriginal(snapshot, result.translatedText());
                if (!result.fromFallback() && usableResult) {
                    if (current(snapshot.key(), expectedGeneration, expectedRevision)) {
                        store(snapshot, result.translatedText(), false);
                        failedUntil.remove(semanticKey);
                        failedUntil.remove(snapshot.key());
                        provisionalRetryAttempts.remove(semanticKey);
                        provisionalRetryAttempts.remove(snapshot.key());
                    }
                } else if (!kept) {
                    failProvisional(semanticKey, attempt);
                }
                debugCompleted(debugId, usableResult || kept ? result.translatedText() : null,
                        kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                                : !usableResult ? TranslationDebugLog.Status.FAILED
                                : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                                : TranslationDebugLog.Status.SUCCESS);
            } catch (TranslationException | RuntimeException e) {
                failProvisional(semanticKey, attempt);
                debugCompleted(debugId, false);
            } finally {
                provisionalRetrying.remove(semanticKey);
            }
        });
    }

    /** Retry a provisional CS topology while a final semantic AI row already exists. */
    private void retryStyleProjection(TranslationTemplate.Snapshot snapshot) {
        if (snapshot == null || !hasCsMarkers(snapshot.key())) return;
        String projectionKey = styleProjectionKey(snapshot);
        String semanticKey = provisionalSemanticKey(snapshot.key());
        BooleanSupplier gate = provisionalRetryGate;
        if (gate == null || !provisional(projectionKey) || !gate.getAsBoolean()
                || backingOff(semanticKey)
                || provisionalRetryAttempts.getOrDefault(semanticKey, 0) >= MAX_PROVISIONAL_RETRIES
                || !provisionalRetrying.add(semanticKey)) {
            return;
        }
        int attempt = provisionalRetryAttempts.merge(semanticKey, 1, Integer::sum);
        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(semanticKey);
        executor.execute(() -> {
            long debugId = debugSubmitted(List.of(snapshot.key()));
            try {
                TranslationResult result = translator.translate(snapshot.key(), targetLang);
                boolean usableResult = usable(snapshot.key(), result.translatedText());
                if (!result.fromFallback() && usableResult
                        && current(semanticKey, expectedGeneration, expectedRevision)) {
                    store(snapshot, result.translatedText(), false);
                    failedUntil.remove(semanticKey);
                    provisionalRetryAttempts.remove(semanticKey);
                } else {
                    failProvisional(semanticKey, attempt);
                }
                debugCompleted(debugId, usableResult ? result.translatedText() : null,
                        !usableResult ? TranslationDebugLog.Status.FAILED
                                : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                                : TranslationDebugLog.Status.SUCCESS);
            } catch (TranslationException | RuntimeException error) {
                failProvisional(semanticKey, attempt);
                debugCompleted(debugId, false);
            } finally {
                provisionalRetrying.remove(semanticKey);
            }
        });
    }

    private void fail(String key) {
        if (failureBackoffMs > 0L) failedUntil.put(key, clock.getAsLong() + failureBackoffMs);
    }

    private void failProvisional(String key, int attempt) {
        if (failureBackoffMs <= 0L) return;
        long multiplier = 1L << Math.min(6, Math.max(0, attempt - 1));
        long delay;
        try {
            delay = Math.multiplyExact(failureBackoffMs, multiplier);
        } catch (ArithmeticException overflow) {
            delay = Long.MAX_VALUE;
        }
        long now = clock.getAsLong();
        failedUntil.put(key, delay >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay);
    }

    /**
     * Three consecutive content responses that cannot be used become a durable
     * keep-original decision. This covers identity echoes, empty model answers and
     * damaged placeholder shapes without retrying forever. Transport exceptions never
     * call this method, so a temporary 429/outage cannot poison the negative cache.
     * Individual/global retranslation removes the sentinel through invalidate/clear.
     */
    private boolean learnKeepOriginal(TranslationTemplate.Snapshot snapshot, String translated) {
        if (snapshot == null) return false;
        String plainSource = stripStyle(snapshot.source());
        TranslationTemplate.Snapshot plain = templates.prepare(plainSource);
        String failureKey = plain.key().isEmpty() ? snapshot.key() : plain.key();
        int count = contentFailures.merge(failureKey, 1, Integer::sum);
        if (count < CONTENT_FAILURE_LIMIT) return false;

        contentFailures.remove(failureKey);
        failedUntil.remove(snapshot.key());
        provisional.remove(snapshot.key());
        removeStored(snapshot.key());
        removeStored(styleProjectionKey(snapshot));
        failedUntil.remove(failureKey);
        provisional.remove(failureKey);
        removeStored(failureKey);
        memory.put(failureKey, KEEP_ORIGINAL);
        if (store != null) store.put(failureKey, KEEP_ORIGINAL, false);
        return true;
    }

    private boolean backingOff(String key) {
        Long until = failedUntil.get(key);
        if (until == null) return false;
        if (clock.getAsLong() < until) return true;
        failedUntil.remove(key, until);
        return false;
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
        String hit = getCached(callback.snapshot.source());
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
                            boolean always) {
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
    }

    public void setTargetLang(String targetLang) {
        String next = targetLang == null || targetLang.isBlank() ? "zh-TW" : targetLang;
        if (next.equals(this.targetLang)) return;
        // First boundary invalidates work belonging to the old language. The second
        // invalidates any request that raced into the tiny store/target swap window,
        // so an HTTP response can never land in a different language's active file.
        reset(false);
        if (store instanceof LanguageFileStore languageStore) {
            languageStore.setLanguage(next);
        }
        this.targetLang = next;
        reset(false);
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
        provisional.clear();
        provisionalRetrying.clear();
        provisionalRetryAttempts.clear();
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
        if (clearStore && store != null) store.clear();
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
        if (hasCsMarkers(snapshot.key())) keys.add(styleProjectionKey(snapshot));

        // Bump request revisions before detaching work. Any old HTTP response that
        // races this deletion is rejected even if it completes after the new request.
        for (String key : keys) {
            if (key != null) keyRevisions.put(key, revisionSequence.incrementAndGet());
        }

        List<Callback> cancelled = new ArrayList<>();
        for (String key : keys) {
            if (key == null) continue;
            contentFailures.remove(key);
            provisionalRetryAttempts.remove(key);
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
