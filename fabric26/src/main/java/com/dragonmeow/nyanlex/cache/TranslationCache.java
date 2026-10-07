package com.dragonmeow.nyanlex.cache;

import com.dragonmeow.nyanlex.translate.ChurnGuard;
import com.dragonmeow.nyanlex.translate.DebugErrorLog;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.RequestGate;
import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.RequestsPausedException;
import com.dragonmeow.nyanlex.translate.TemplateText;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.TranslationTemplate;
import com.dragonmeow.nyanlex.translate.PriorityTranslationExecutor;
import com.dragonmeow.nyanlex.translate.Translator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
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
    /** The character budget is the real provider safety limit. Keep the count ceiling
     * only as a final guard, so short complete entries are not split at an arbitrary 64. */
    private static final int MAX_BATCH = 512;
    /** Hard protection against render hooks discovering unbounded dynamic/background text. */
    public static final int MAX_QUEUED_ENTRIES = 512;
    private static final int MAX_SETTLE_TICKS = 3;
    /** Approximate input budget for one high-level batch. Entries are atomic: the
     * collector cuts only between entries, never through an item name or paragraph. */
    /** Kept below GoogleFreeTranslator's 1600-char URL budget (including anchors),
     * so a normal token-free collected batch remains one physical GT HTTP request. */
    static final int MAX_BATCH_CHARS = 1400;
    /** Per-request item budget of a window-collected AI batch ({@link #setWindowedBatching}).
     * One AI request carries a fixed system prompt, so a collected screen is packed into as
     * few requests as possible; only the overflow beyond this budget becomes a next request. */
    static final int MAX_WINDOWED_BATCH_CHARS = BatchBudget.WINDOWED_CHARS;
    /** A windowed batch still outstanding after this long no longer holds the next one back,
     * so a hung transport can never stall the engine's collector for good. */
    private static final long WINDOWED_BATCH_STALE_MS = 120_000L;
    /** perf ②: ledger retries that fell due together (typically all of them after the master
     * switch was off for a while) enter the collector at most this many per tick. */
    static final int MAX_DUE_RETRIES_PER_TICK = 16;
    private static final int CONTENT_FAILURE_LIMIT = 3;
    private static final int MAX_FINAL_WAITER_FAMILIES = 512;
    /** Explicit/chat requests may keep healing during this session. Passive item
     * warmups are deliberately excluded and retry only while re-observed. */
    private static final int MAX_SESSION_RETRY_DEMANDS = 512;
    /** Session-only failure bookkeeping is lazy and bounded; the durable store remains
     * authoritative for older entries and is rehydrated only when text is observed. */
    private static final int MAX_TRACKED_RETRY_STATES = MAX_SESSION_RETRY_DEMANDS;
    private static final int MAX_TRACKED_KEY_REVISIONS = 4_096;
    private static final int MAX_CONTEXT_LINES = 64;
    private static final int MAX_CONTEXT_CHARS = 8_192;
    private static final int MAX_CALLBACKS_PER_KEY = 64;
    /** Bound of the projection-to-semantic rebuild bookkeeping (pending and failed keys). */
    private static final int MAX_SEMANTIC_REBUILDS = 512;
    /** Rebuilds handed to one worker task per tick; the rest follow on later ticks. */
    private static final int MAX_SEMANTIC_REBUILDS_PER_TICK = 64;
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
    private static final Pattern NON_CS_SLOT =
            Pattern.compile("\\u27E6\\s*(?:MT|WS|PB)\\s*\\d+\\s*\\u27E7");
    /** A protected-term placeholder (masked player name / do-not-translate term), e.g. ⟦0⟧. */
    private static final Pattern PROTECTED_PLACEHOLDER =
            Pattern.compile("\\u27E6\\s*(\\d+)\\s*\\u27E7");

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
    /** Serializes terminal decisions with durable writes; never held across a backend call. */
    private final Object terminalWriteLock = new Object();

    private volatile TranslationCache fallback;
    private volatile boolean fallbackHitsProvisional;
    private volatile BooleanSupplier fallbackEnabled = () -> true;
    /** Live "send new translation requests" switch. Closed = cache-only: hits are still
     *  served, but no new work is queued or sent and nothing is recorded as a failure. */
    private volatile BooleanSupplier requestGate = () -> true;
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
    private final Object retryDemandLock = new Object();
    private final java.util.LinkedHashSet<String> sessionRetryDemand =
            new java.util.LinkedHashSet<>();

    private final Object queueLock = new Object();
    private final LinkedHashMap<String, Queued> queue = new LinkedHashMap<>();
    /** One permit per queued entry or active flight. Queue-to-flight transfer keeps
     * the same permit, so a slow backend cannot free and refill the collector forever. */
    private final AtomicInteger pendingEntries = new AtomicInteger();
    private boolean queueGrew;
    private int settleTicks;
    private long queueStartedAtMs = -1L;
    private int queuedChars;
    /** Null preserves the legacy short settle window for standalone embedders/tests. */
    private volatile IntSupplier batchWindowMs;
    /** AI engines: collect for the whole window, send only when the engine may send, and
     *  pack everything (hovered entries first) into one request. See {@link #setWindowedBatching}. */
    private volatile boolean windowedBatching;
    /** Ticket of the windowed batch that has been handed out and not finished yet (0 = none). */
    private final AtomicLong windowedBatchTicket = new AtomicLong();
    private final AtomicLong windowedTicketSequence = new AtomicLong();
    /** When the outstanding windowed batch was handed out (tick thread writes and reads). */
    private volatile long windowedBatchStartedAtMs;

    /** CS-marked keys whose final colour projection a render lookup found while their
     *  semantic row was missing (it is written first, so write-order eviction drops it
     *  first). flushBatch hands them to a worker, which rebuilds the semantic row from the
     *  projection locally; the render thread never writes. Bounded; sends nothing. */
    private final Map<String, TranslationTemplate.Snapshot> pendingSemanticRebuilds =
            new ConcurrentHashMap<>();
    /** Keys whose semantic row could not be rebuilt from their projection this session (for
     *  example its plain copy fails validation, or its plain form is not translatable at all).
     *  Only a memo so a render lookup does not note them again every frame: not a failure
     *  record, never persisted, never retried by a request (the projection keeps being
     *  displayed). Cleared by invalidate/reset; bounded. */
    private final Set<String> unrebuildableProjections = ConcurrentHashMap.newKeySet();
    /** Semantic rows that exist only as a session copy rebuilt from a colour projection. They
     *  serve every lookup, but are never final wording for a write, an import or an export, so
     *  a genuine answer or an imported row still replaces and persists them; a GT stand-in does
     *  not. The request side does not count them as cached. Changed only together with the row
     *  itself, under the memory lock. */
    private final Set<String> derivedSemanticRows = ConcurrentHashMap.newKeySet();
    /** At most one rebuild task is queued or running per cache. The executor is the bounded
     *  translation pool: while its workers are stalled (AI pacing), a task per tick would fill
     *  it and translation batches would then be rejected. */
    private final AtomicBoolean rebuildTaskOutstanding = new AtomicBoolean();

    private final Set<String> provisional = ConcurrentHashMap.newKeySet();
    private final Set<String> provisionalRetrying = ConcurrentHashMap.newKeySet();
    /** Only live, newly accepted wrap-loss results get one review, never cache hits. */
    private final Set<String> paragraphReviews = ConcurrentHashMap.newKeySet();
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
    /** Plain-text diagnostic sink (wired to the loader's own logger by the glue, e.g.
     *  {@code LOGGER::warn}); defaults to a no-op so a standalone/test cache never
     *  requires one. Used by {@link #refuseUnsendable}/{@link #globalSendFuseAllows}
     *  to record the two 2026-10 safety nets below without depending on any
     *  loader-specific logging API from this loader-independent package. */
    private volatile Consumer<String> infoLog = message -> { };

    /** 2026-10 hard safety net, independent of the ordinary exponential {@link
     *  #failTemporarily} backoff: refuses to actually dispatch the SAME exact request
     *  key more than {@link #GLOBAL_SEND_FUSE_MAX_SENDS} times within any trailing
     *  {@link #GLOBAL_SEND_FUSE_WINDOW_MS} window, however the ordinary backoff math is
     *  configured (including a misconfigured {@code failureBackoffMs == 0}, or any
     *  future bug that re-introduces an unthrottled resend loop) — the live bug: a
     *  broken unit resent roughly every ~2s for as long as it stayed warmed/hovered,
     *  which at that cadence would still trip this fuse within seconds. {@code
     *  GLOBAL_SEND_FUSE_MAX_SENDS} is deliberately ABOVE 2 (not the smallest number that
     *  would still call this "a fuse"): this cache's own pre-existing, deliberately
     *  unbounded retry philosophy — "a provider bug... can never poison the line
     *  permanently" (see {@link #handleUnusableContent}'s class doc) — legitimately
     *  sends the SAME key several times in quick succession during normal operation (the
     *  three-consecutive-identity-echo keep-original rule; an exponential ramp's first
     *  few, still-short intervals; a provisional-fallback supplement's own ramp) — up to
     *  5 in existing, intentional, already-tested call patterns. Set low enough to still
     *  catch a genuine high-frequency runaway (a steady ~2s cadence reaches this count in
     *  well under a minute, nowhere near the 10-minute window) while never tripping on
     *  any of this cache's own normal, backoff-respecting retry behaviour. See {@link
     *  #globalSendFuseAllows}. */
    private final Map<String, long[]> globalSendFuse = new ConcurrentHashMap<>();
    private static final int GLOBAL_SEND_FUSE_MAX_SENDS = 10;
    private static final long GLOBAL_SEND_FUSE_WINDOW_MS = 10 * 60_000L;

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
                boolean evict = size() > capacity;
                if (evict) {
                    // Session provisional state describes the resident value. If the bit
                    // outlives that value, every historical fallback key stays retained
                    // and a later final disk row can be misclassified.
                    provisional.remove(eldest.getKey());
                    derivedSemanticRows.remove(eldest.getKey());
                }
                return evict;
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

    /**
     * Live master switch for NEW translation requests. While it is closed, cached rows
     * are still served, but nothing is queued or sent (already queued work is dropped
     * without counting as a failure), so reopening it simply resumes normal requests.
     */
    public void setRequestGate(BooleanSupplier gate) {
        this.requestGate = gate == null ? () -> true : gate;
    }

    /** Whether new translation requests may currently be sent. */
    public boolean requestsAllowed() {
        try {
            return requestGate.getAsBoolean();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** Recheck a terminal decision even while the provider is waiting for a send slot.
     * A mixed batch stays open while it still contains work for another semantic family. */
    private BooleanSupplier requestGateFor(List<TranslationTemplate.Snapshot> snapshots) {
        return () -> {
            if (!requestsAllowed()) return false;
            for (TranslationTemplate.Snapshot snapshot : snapshots) {
                if (!keepsOriginalFamily(snapshot)) return true;
            }
            return false;
        };
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

    /** Wire a plain-text diagnostic sink (e.g. the loader's own logger). See {@link #infoLog}. */
    public void setInfoLog(Consumer<String> sink) {
        this.infoLog = sink == null ? message -> { } : sink;
    }

    /** Install a live batching-window setting. Zero means send on the next tick. */
    public void setBatchWindowMs(IntSupplier supplier) {
        this.batchWindowMs = supplier;
    }

    /**
     * Window batching for an AI engine (it takes effect while a batching window is
     * installed). Every AI request pays a fixed system prompt, so instead of flushing a
     * hovered tooltip on its own the collector keeps everything the screen produces and
     * sends when BOTH the window of the oldest entry has passed AND the engine may send: its
     * previous windowed batch has finished and its pacer ({@link Translator#nextRequestDelayMs})
     * has a free slot. All collected entries then travel in one request — hovered entries
     * first, each with its own surface context — up to {@link #MAX_WINDOWED_BATCH_CHARS};
     * only the overflow waits for the next request. Machine-translation engines keep the
     * immediate hover flush and their smaller URL-bound budget.
     */
    public void setWindowedBatching(boolean enabled) {
        this.windowedBatching = enabled;
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

    /** True only when this engine failed and has no final semantic wording of its own.
     *  While new requests are switched off this engine can never fail or recover, so any
     *  lower-tier wording that is already cached may stand in (unless fallback is off). */
    public boolean mayUseFallback(String source) {
        if (!isFallbackEnabled()) return false;
        if (requestsAllowed() && !hasFailureState(source)) return false;
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
        // A marker-free line whose entire semantic payload is deterministic (for
        // example x100 or §ax100) is already its own final value. Returning the
        // source here makes both lookup and callback paths settle immediately without
        // allowing the literal x to create a backend request.
        if (!snapshot.hasTranslatableContent() && !hasCsMarkers(snapshot.key())) return source;
        TranslationCache sibling = isFallbackEnabled() ? fallback : null;

        // Style is not meaning, but CS markers carry the alignment needed to apply
        // the CURRENT component's styles to the translated words. A marked request
        // therefore needs both a canonical semantic row and a marker-only projection.
        // The projection is accepted only while its plain wording equals the canonical
        // row, preventing two surfaces from reviving conflicting translations — or while
        // no canonical row exists at all, until a worker rebuilds it from the projection.
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
                if (styleHit != null && plainHit != null) {
                    boolean provisionalStyle = provisional(styleProjectionKey(snapshot));
                    if (!allowStyleFallback && !provisionalStyle) {
                        // A one-shot rich consumer asked specifically for this verified
                        // topology. Its final wording may legitimately differ from the
                        // older canonical plain row; keep both instead of deleting the
                        // only exact colour projection.
                        return styleHit;
                    }
                    if (!allowStyleFallback && provisionalStyle) {
                        removeStored(styleProjectionKey(snapshot));
                        return null;
                    }
                    // The final semantic wording is already usable. A live HUD may mint a
                    // different CS topology every frame, so generic render lookups must not
                    // buy cosmetic topology supplements in the background.
                    return TextFilter.markStyleFallback(plainHit);
                }
                if (styleHit != null) {
                    if (!provisional(styleProjectionKey(snapshot))) {
                        // No semantic row (write-order eviction drops it first, or its plain
                        // copy never validated), but this final colour projection is a complete
                        // translation of exactly this line: show it on every surface, as chat
                        // always did, instead of leaving the line original while the request
                        // side counts it as cached. A worker rebuilds the semantic row from it
                        // on the next tick; this lookup only notes that (O(1)) — it never
                        // writes, requests or records anything.
                        noteSemanticRebuild(snapshot);
                        return styleHit;
                    }
                    removeStored(styleProjectionKey(snapshot));
                }
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

    /** Set while {@link #peekFinal} runs on this thread: lookups must start no request. */
    private static final ThreadLocal<Boolean> PEEKING = new ThreadLocal<>();

    /**
     * Strictly read-only twin of {@link #getCachedFinal}: only this engine's own final
     * wording (never a provisional stand-in, never a lower-tier read-through, never a
     * kept-original/failure mark -- those read as {@code null}), and unlike an ordinary
     * lookup it can never start a request: a provisional row it passes over is not
     * scheduled for an upgrade attempt. A machine-translation surface uses it to prefer
     * wording this (AI) cache already has without waking the AI engine.
     *
     * @param exactStyle {@code true} accepts only a wording that carries this exact
     *                   colour topology (chat); {@code false} also accepts the
     *                   colour-independent semantic row, marked as a style fallback.
     */
    public String peekFinal(String source, boolean exactStyle) {
        if (source == null) return null;
        Boolean outer = PEEKING.get();
        PEEKING.set(Boolean.TRUE);
        try {
            String hit = getCachedFinal(source, exactStyle);
            if (hit == null || hit.isEmpty()) return null;
            String semantic = TextFilter.stripStyleFallback(hit);
            if (semantic.equals(source) || semantic.strip().equals(source.strip())) return null;
            return hit;
        } finally {
            if (outer == null) PEEKING.remove();
            else PEEKING.set(outer);
        }
    }

    private String getCachedFinal(String source, boolean exactStyle) {
        // Final means this engine's own value, never a result merely read through from
        // its lower-priority sibling.
        String hit = getCached(source, !exactStyle, false);
        if (hit == null) return null;
        String semanticKey = provisionalSemanticKey(source);
        // A late fallback may leave a provisional style row after KEEP. Original
        // text is already final for this family, so final-only callbacks must settle.
        if (keepsOriginal(semanticKey)) return source;
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
        if (!usable(restored) || !matchingCsShape(snapshot.source(), restored)) {
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

    /**
     * Read-through into rows that are keyed like this cache's own rows (the shared repository):
     * the very same {@link TranslationTemplate} snapshot the own lookup uses is prepared, then the
     * exact text, its trimmed form and finally the number/gap-normalized key are asked of
     * {@code rows}; a normalized hit is restored with THIS snapshot's own values, so the numbers
     * shown are the ones on screen. Returns null on a miss or an unusable restored value.
     */
    public String lookupExternal(String source, java.util.function.Function<String, String> rows) {
        if (source == null || rows == null) return null;
        String hit = rows.apply(source);
        if (hit != null) return hit;
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        if (!snapshot.normalized().equals(source)) {
            hit = rows.apply(snapshot.normalized());
            if (hit != null) return hit;
        }
        if (!snapshot.changed()) return null;
        String stored = rows.apply(snapshot.key());
        if (stored == null) return null;
        String restored = snapshot.restore(stored);
        return usable(restored) ? restored : null;
    }

    /** Lookup only the immutable forms captured by this request snapshot. */
    private String lookupSnapshot(TranslationTemplate.Snapshot snapshot, TranslationCache owner) {
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
            // Memory admits only validated immutable rows. Re-running every token,
            // style and language regex on each rendered frame creates avoidable GC load.
            return value;
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
        // The unsendable-shape gate is NOT bypassed though: unlike backoff timing, it
        // is not a rate limit, it is "this exact key can never pass validation no
        // matter what comes back" -- sending it would still be pure waste even for an
        // explicit one-off action (see #refuseUnsendable).
        if (!snapshot.hasTranslatableContent()) return null;
        if (refuseUnsendable(snapshot)) return null;
        if (!requestsAllowed()) return null;
        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(snapshot.key());
        if (keepsOriginalFamily(snapshot)) return snapshot.source();
        long debugId = debugSubmitted(List.of(snapshot.key()));
        try {
            TranslationResult result;
            BooleanSupplier previousGate = RequestGate.bind(requestGateFor(List.of(snapshot)));
            try {
                result = translator.translate(snapshot.key(), targetLang);
            } finally {
                RequestGate.restore(previousGate);
            }
            if (current(snapshot.key(), expectedGeneration, expectedRevision)
                    && keepsOriginalFamily(snapshot)) {
                debugCompleted(debugId, snapshot.key(), TranslationDebugLog.Status.KEEP_ORIGINAL);
                return snapshot.source();
            }
            if (!usable(snapshot.key(), result.translatedText())) {
                boolean kept = current(snapshot.key(), expectedGeneration, expectedRevision)
                        && handleUnusableContent(snapshot, result);
                debugCompleted(debugId, result.translatedText(), kept
                        ? TranslationDebugLog.Status.KEEP_ORIGINAL
                        : TranslationDebugLog.Status.FAILED,
                        kept ? null : failureReasonFor(snapshot.key(), result));
                return kept ? snapshot.source() : null;
            }
            if (current(snapshot.key(), expectedGeneration, expectedRevision)) {
                store(snapshot, result.translatedText(), result.fromFallback());
                failedUntil.remove(snapshot.key());
                reviewParagraphOnce(snapshot, result, null, expectedGeneration, expectedRevision);
            }
            debugCompleted(debugId, result.translatedText(), result.fromFallback()
                    ? TranslationDebugLog.Status.FALLBACK : TranslationDebugLog.Status.SUCCESS);
            return snapshot.restore(result.translatedText());
        } catch (RequestsPausedException paused) {
            // Switched off or learned KEEP while waiting for a send slot: no failure.
            debugDiscarded(debugId);
            return keepsOriginalFamily(snapshot) ? snapshot.source() : null;
        } catch (TranslationException | RuntimeException e) {
            if (current(snapshot.key(), expectedGeneration, expectedRevision)) fail(snapshot.key());
            debugCompleted(debugId, TranslationDebugLog.failureFor(e));
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
        if (!requestsAllowed()) {
            deliver(new Callback(null, callback, always, false), null);
            return;
        }

        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        markSessionRetryDemand(snapshot);
        Callback cb = new Callback(snapshot, callback, always, false);
        if (!snapshot.hasTranslatableContent() || backingOff(snapshot.key())
                || suppressed(snapshot.key()) || refuseUnsendable(snapshot)) {
            deliver(cb, null);
            return;
        }
        submitSingle(snapshot, cb);
    }

    private void submitSingle(TranslationTemplate.Snapshot snapshot, Callback callback) {
        String key = snapshot.key();
        Flight ours = new Flight();
        ours.add(callback);
        Flight active = flights.get(key);
        if (active != null) {
            if (active.add(callback) != Flight.AddResult.ADDED) {
                deliver(callback, lookupSnapshot(snapshot, this));
            }
            return;
        }
        if (!tryAcquirePendingEntry()) {
            deliver(callback, null);
            return;
        }
        Flight existing = flights.putIfAbsent(key, ours);
        if (existing != null) {
            releasePendingEntry();
            if (existing.add(callback) != Flight.AddResult.ADDED) {
                deliver(callback, lookupSnapshot(snapshot, this));
            }
            return;
        }

        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(key);
        Runnable task = () -> {
            long debugId = 0L;
            try {
                if (!current(key, expectedGeneration, expectedRevision)) return;
                if (keepsOriginalFamily(snapshot)) return;
                if (lookupSnapshot(snapshot, this) == null) {
                    // Queued before new requests were switched off: give up unsent.
                    if (!requestsAllowed()) return;
                    // Re-checked here (not just at requestImmediate's own entry gate)
                    // because this task runs later, on the executor -- the same
                    // discipline translateBatch's todo-loop re-applies right before its
                    // own HTTP call. See refuseUnsendable/globalSendFuseAllows.
                    if (backingOff(key) || refuseUnsendable(snapshot)
                            || !globalSendFuseAllows(key)) {
                        return;
                    }
                    debugId = debugSubmitted(List.of(key));
                    TranslationResult result;
                    BooleanSupplier previousGate = RequestGate.bind(requestGateFor(List.of(snapshot)));
                    try {
                        result = translator.translate(key, targetLang);
                    } finally {
                        RequestGate.restore(previousGate);
                    }
                    boolean usableResult = usable(key, result.translatedText());
                    boolean kept = current(key, expectedGeneration, expectedRevision)
                            && (keepsOriginalFamily(snapshot) || !usableResult
                            && handleUnusableContent(snapshot, result));
                    if (usableResult && !kept && current(key, expectedGeneration, expectedRevision)) {
                        store(snapshot, result.translatedText(), result.fromFallback());
                        failedUntil.remove(key);
                        reviewParagraphOnce(snapshot, result, null, expectedGeneration, expectedRevision);
                    }
                    debugCompleted(debugId, kept ? snapshot.key() : usableResult ? result.translatedText() : null,
                            kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                                    : !usableResult ? TranslationDebugLog.Status.FAILED
                                    : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                                    : TranslationDebugLog.Status.SUCCESS,
                            !usableResult && !kept ? failureReasonFor(key, result) : null);
                }
            } catch (RequestsPausedException paused) {
                debugDiscarded(debugId);
            } catch (TranslationException | RuntimeException e) {
                if (current(key, expectedGeneration, expectedRevision)) fail(key);
                debugCompleted(debugId, TranslationDebugLog.failureFor(e));
            } finally {
                finishFlight(key, ours);
            }
        };
        if (!executeHigh(task)) finishFlight(key, ours);
    }

    // -------------------------------------------------------------------------
    // Shared settle queue
    // -------------------------------------------------------------------------

    public void requestBatched(String source) {
        requestBatched(source, true);
    }

    /** Passive visible-item request. It joins the same collector, but a later failure
     * is retried only if the loader observes and submits the item again. */
    public void requestBatchedPassive(String source) {
        requestBatched(source, false);
    }

    private void requestBatched(String source, boolean keepRetryingThisSession) {
        if (source == null || getCached(source) != null) return;
        // Cache-only mode: no retry demand, no churn record, no queue entry.
        if (!requestsAllowed()) return;
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        if (keepRetryingThisSession) markSessionRetryDemand(snapshot);
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
        if (!requestsAllowed()) {
            // Cache-only mode: an always-callback (chat) completes now. The exact colour
            // projection cannot be bought, so a cached semantic row is delivered as the
            // style fallback (null = show the original) instead of waiting for it.
            deliver(new Callback(null, callback, always, exactStyle),
                    exactStyle ? getCached(source) : null);
            return;
        }

        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        // Requiring an exact style projection has no effect on plain text.
        exactStyle &= hasCsMarkers(snapshot.key());
        Callback cb = new Callback(snapshot, callback, always, exactStyle);
        if (exactStyle && backingOffState(styleFailureKey(snapshot))) {
            // The semantic wording is already usable; only this colour topology failed.
            // Do not let repeated chat messages bypass the style-debt cooldown. The
            // service will read and display the semantic style fallback after this null
            // completion, while a later occurrence (or manual invalidation) may try the
            // exact projection again.
            deliver(cb, null);
            return;
        }
        markSessionRetryDemand(snapshot);
        if (!eligible(snapshot)) {
            deliver(cb, null);
            return;
        }

        while (true) {
            Flight flight = flights.get(snapshot.key());
            if (flight == null) break;
            Flight.AddResult added = flight.add(cb);
            if (added == Flight.AddResult.ADDED) return;
            cached = exactStyle ? getCachedExactStyle(source) : lookupSnapshot(snapshot, this);
            if (cached != null) {
                deliver(cb, cached);
                return;
            }
            if (added == Flight.AddResult.FULL) {
                // This caller owns no pending-entry permit. Reject it with the same
                // callback/null contract as a saturated collector instead of spinning
                // forever on an otherwise healthy, still-open flight.
                deliver(cb, null);
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
        // Cache-only mode: no new request may produce the final value, so do not
        // register a waiter (or start the supplement it would drive).
        if (!requestsAllowed()) return;

        String family = provisionalSemanticKey(source);
        FinalWaiter waiter = new FinalWaiter(source, callback, exactStyle);
        if (!finalWaiters.containsKey(family)
                && finalWaiters.size() >= MAX_FINAL_WAITER_FAMILIES) {
            java.util.Iterator<String> oldest = finalWaiters.keySet().iterator();
            if (oldest.hasNext()) finalWaiters.remove(oldest.next());
        }
        java.util.concurrent.CopyOnWriteArrayList<FinalWaiter> familyWaiters =
                finalWaiters.computeIfAbsent(family,
                        ignored -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (familyWaiters.size() < MAX_CALLBACKS_PER_KEY) familyWaiters.add(waiter);

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
                && !keepsOriginalFamily(snapshot)
                && !backingOff(snapshot.key())
                && !suppressed(snapshot.key())
                && !refuseUnsendable(snapshot);
    }

    /**
     * 2026-10 send-gate: {@code true} (and refuses) when {@code snapshot}'s own key is
     * structurally guaranteed to fail validation no matter what a translator returns --
     * right now this means its ⟦CSn⟧ colour markers are not balanced (an orphaned
     * opener/closer somewhere, the live bug: a {@link TooltipSegmentPlanner}-carved
     * TRADE/STATS/PROSE/ABILITY segment whose boundary split a colour run that spanned a
     * ⟦PBn⟧ row break -- see {@code TradeLineComposer#trimmedSpan}'s 2026-10 fix, which
     * stops this at the source for that path; this gate is the backstop for any other
     * path that might still produce one). {@link #matchingCsShape} always rejects such a
     * key's response (its OWN {@link #csShape} is {@code null}), so sending it can only
     * ever end in a deterministic {@code FAILED(format/token lost)} -- retried forever at
     * the provider's expense with no way to EVER succeed, since the key string itself
     * never changes. This refuses the send before it happens instead: the caller keeps
     * showing the original/previous value, the attempt is logged once (quiet afterwards:
     * the very next line pushes the key into the ordinary failure-backoff ledger, so a
     * render loop re-offering the same broken key every frame does not spam the log or
     * retry tighter than any other capped, never-fully-poisoned temporary failure in
     * this cache -- see {@link #failTemporarily}'s own class-level discipline). */
    private boolean refuseUnsendable(TranslationTemplate.Snapshot snapshot) {
        String key = snapshot == null ? null : snapshot.key();
        if (key == null || !hasOrphanCsMarker(key)) return false;
        if (!backingOff(key)) {
            infoLog.accept("[nyanlex] refusing to send unit with unbalanced CS markers "
                    + "(would always fail format validation), backing off: " + previewForLog(key));
        }
        int attempt = contentRetryAttempts.merge(key, 1, Integer::sum);
        trimRetryStateMaps();
        failTemporarily(key, attempt, "unsendable shape (unbalanced CS markers)");
        return true;
    }

    /**
     * {@code true} only when {@code text} has a genuinely unmatched ⟦CSn⟧ opener or
     * closer — pure open/close pairing, nothing else. Deliberately NARROWER than {@link
     * #csShape}/{@link #matchingCsShape} (which additionally require every non-CS
     * character to sit INSIDE some CS pair — the right rule for validating a live
     * RESPONSE against its request, but not for this pre-send gate: ordinary, legitimate
     * text can freely mix CS-marked and unmarked runs — see {@code
     * ItemEntityTranslationTest#unregisteredShortAndColourSplitNamesAreNeverSubstituted},
     * a real, intentional "⟦CS0⟧Withered⟦/CS0⟧ ⟦CS1⟧Hyperion⟦/CS1⟧ is great"-shaped line
     * that must still reach the translator even though {@code csShape} alone would call
     * it invalid). A truly unmatched opener/closer, by contrast, can NEVER be resolved
     * by anything in {@code text} itself — that is the one shape this pre-send gate
     * exists to catch (see {@link #refuseUnsendable}'s own javadoc for the full
     * mechanism/motivation). Mirrors {@code OpenAiTranslator#hasBalancedCsMarkers}
     * exactly (same stack-based pairing), kept as a separate copy since the two classes
     * do not share a token-protocol utility class today.
     */
    private static boolean hasOrphanCsMarker(String text) {
        if (text == null || text.indexOf('\u27E6') < 0) return false;
        java.util.regex.Matcher matcher = CS_TOKEN.matcher(text);
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String index = matcher.group(2);
            if (!closing) {
                open.push(index);
            } else if (open.isEmpty() || !open.pop().equals(index)) {
                return true; // a close with nothing (or the wrong thing) open: orphaned
            }
        }
        return !open.isEmpty(); // any opener never closed: also orphaned
    }

    /** Short, single-line preview for a log line -- never the whole (possibly very long)
     *  key, and never leaks past one line even if the key somehow contains one. */
    private static String previewForLog(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "..." : oneLine;
    }

    private void markSessionRetryDemand(TranslationTemplate.Snapshot snapshot) {
        if (snapshot == null) return;
        synchronized (retryDemandLock) {
            addSessionRetryDemand(snapshot.key());
            addSessionRetryDemand(provisionalSemanticKey(snapshot.key()));
        }
    }

    private void addSessionRetryDemand(String key) {
        if (key == null || key.isBlank()) return;
        sessionRetryDemand.remove(key);
        sessionRetryDemand.add(key);
        while (sessionRetryDemand.size() > MAX_SESSION_RETRY_DEMANDS) {
            var oldest = sessionRetryDemand.iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    private boolean sessionRetryDemanded(String stateKey,
                                          TranslationTemplate.Snapshot snapshot) {
        synchronized (retryDemandLock) {
            return sessionRetryDemand.contains(stateKey)
                    || (snapshot != null && (sessionRetryDemand.contains(snapshot.key())
                    || sessionRetryDemand.contains(provisionalSemanticKey(snapshot.key()))));
        }
    }

    private void enqueue(TranslationTemplate.Snapshot snapshot, Callback callback) {
        enqueue(snapshot, callback, null, false);
    }

    private void enqueue(TranslationTemplate.Snapshot snapshot, Callback callback,
                         List<String> surfaceContext) {
        enqueue(snapshot, callback, surfaceContext, false);
    }

    private void enqueue(TranslationTemplate.Snapshot snapshot, Callback callback,
                         List<String> surfaceContext, boolean highPriority) {
        if (keepsOriginalFamily(snapshot)) {
            deliver(callback, cachedFor(callback));
            return;
        }
        boolean rejected = false;
        boolean callbackRejected = false;
        synchronized (queueLock) {
            Queued item = queue.get(snapshot.key());
            if (item == null) {
                if (!tryAcquirePendingEntry()) {
                    rejected = true;
                } else {
                    long now = clock.getAsLong();
                    item = new Queued(snapshot, now);
                    item.background = RequestPacer.isUnpacedThread();
                    queue.put(snapshot.key(), item);
                    queueGrew = true;
                    queuedChars += batchChars(snapshot.key());
                    if (queueStartedAtMs < 0L) queueStartedAtMs = now;
                }
            }
            if (!rejected) {
                if (!RequestPacer.isUnpacedThread()) item.background = false;
                item.highPriority |= highPriority;
                item.mergeContext(surfaceContext);
                if (callback != null) {
                    if (item.callbacks.size() < MAX_CALLBACKS_PER_KEY) {
                        item.callbacks.add(callback);
                    } else {
                        callbackRejected = true;
                    }
                }
            }
        }
        if (rejected || callbackRejected) deliver(callback, null);
    }

    private static int batchChars(String text) {
        return BatchBudget.unitChars(text);
    }

    private int configuredBatchWindowMs() {
        IntSupplier supplier = batchWindowMs;
        if (supplier == null) return -1;
        try {
            return Math.max(0, Math.min(60_000, supplier.getAsInt()));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private boolean queued(String key) {
        synchronized (queueLock) {
            return queue.containsKey(key);
        }
    }

    /** Called once per client tick. */
    public void flushBatch() {
        // Local cache maintenance, deliberately before the request switch: rebuilding a
        // semantic row from a cached projection sends nothing, so it also runs while new
        // requests are switched off.
        dispatchSemanticRebuilds();
        if (!requestsAllowed()) {
            // Cache-only mode: drop whatever is still collected (it is not a failure) and
            // start neither ledger retries nor provisional supplements.
            discardQueuedWork();
            return;
        }
        // A closed rate-limit gate (Google 429) holds everything: the queue and the retry
        // ledger stay as they are and resume by themselves once the gate reopens.
        if (engineSendBlocked()) return;
        // Durable failures are passive. They retry only when their text is observed
        // again through a live surface; loading a world must not resurrect vanished
        // tooltips from the failure ledger as background requests.
        enqueueDueRetries();
        // A provisional result may have arrived while the AI's 429 gate was closed.
        // Final-only widget callbacks remain registered, so re-check their bounded
        // semantic families each tick and start the supplement once the gate reopens.
        for (String family : finalWaiters.keySet()) retryProvisional(family);
        List<Queued> drained;
        boolean windowed;
        synchronized (queueLock) {
            if (queue.isEmpty()) {
                settleTicks = 0;
                queueGrew = false;
                queueStartedAtMs = -1L;
                queuedChars = 0;
                return;
            }
            int windowMs = configuredBatchWindowMs();
            windowed = windowedBatching && windowMs >= 0;
            if (windowed) {
                queueGrew = false;
                settleTicks = 0;
                if (!windowedBatchDue(windowMs)) return;
            } else if (windowMs < 0) {
                boolean grew = queueGrew;
                queueGrew = false;
                if (grew && settleTicks < MAX_SETTLE_TICKS) {
                    settleTicks++;
                    return;
                }
            } else {
                queueGrew = false;
                long age = Math.max(0L, clock.getAsLong() - queueStartedAtMs);
                boolean urgent = queue.values().stream().anyMatch(item -> item.highPriority);
                boolean full = queue.size() >= MAX_BATCH || queuedChars >= MAX_BATCH_CHARS;
                if (!urgent && !full && windowMs > 0 && age < windowMs) return;
            }
            settleTicks = 0;
            drained = drainQueued(windowed ? MAX_WINDOWED_BATCH_CHARS : MAX_BATCH_CHARS);
        }

        List<TranslationTemplate.Snapshot> send = new ArrayList<>();
        Map<String, Flight> owned = new LinkedHashMap<>();
        for (Queued item : drained) {
            String key = item.snapshot.key();
            if (cachedForRequest(item.snapshot)) {
                // For a CS-marked line this hit may be the colour projection alone. That is
                // cached here exactly as for the render lookup (which displays it), so no
                // request is bought; a worker rebuilds the missing semantic row from it.
                if (hasCsMarkers(key)) noteSemanticRebuild(item.snapshot);
                item.callbacks.forEach(cb -> deliver(cb, cachedFor(cb)));
                releasePendingEntry();
                continue;
            }
            if (!item.snapshot.hasTranslatableContent() || backingOff(key)) {
                item.callbacks.forEach(cb -> deliver(cb, null));
                releasePendingEntry();
                continue;
            }

            Flight ours = new Flight();
            ours.warm = item.background;
            for (Callback callback : item.callbacks) ours.add(callback);
            Flight existing = flights.putIfAbsent(key, ours);
            if (existing != null) {
                for (Callback cb : item.callbacks) {
                    if (existing.add(cb) != Flight.AddResult.ADDED) {
                        deliver(cb, cachedFor(cb));
                    }
                }
                releasePendingEntry();
            } else {
                owned.put(key, ours);
                send.add(item.snapshot);
            }
        }
        if (send.isEmpty()) return;

        long expectedGeneration = generation.get();
        Map<String, Long> expectedRevisions = revisions(send);
        // A windowed AI batch mixes surfaces, so every entry keeps its own context; the
        // machine-translation collector keeps its single shared (or dropped) context.
        List<String> requestContext = windowed ? null : commonContext(drained);
        Map<String, List<String>> itemContexts = windowed ? itemContexts(drained, owned) : null;
        long ticket = windowed ? beginWindowedBatch() : 0L;
        Runnable task = () -> {
            try {
                if (generation.get() == expectedGeneration) {
                    translateBatch(send, requestContext, itemContexts,
                            expectedGeneration, expectedRevisions);
                }
            } finally {
                try {
                    owned.forEach(this::finishFlight);
                } finally {
                    endWindowedBatch(ticket);
                }
            }
        };
        boolean accepted = false;
        try {
            accepted = drained.stream().anyMatch(item -> item.highPriority)
                    ? executeHigh(task) : executeLow(task);
        } finally {
            if (!accepted) {
                try {
                    owned.forEach(this::finishFlight);
                } finally {
                    endWindowedBatch(ticket);
                }
            }
        }
    }

    /**
     * Collector, under {@code queueLock}: take hovered entries first, then the rest in
     * arrival order, up to {@code budget} characters. Entries are atomic: a single oversized
     * entry is sent whole, and the first entry that no longer fits stays queued (with its
     * original arrival time) for the next request rather than being sliced to fit.
     */
    private List<Queued> drainQueued(int budget) {
        List<Queued> drained = new ArrayList<>(Math.min(MAX_BATCH, queue.size()));
        BatchBudget packer = new BatchBudget(budget);
        boolean budgetFull = false;
        // Hovered entries are first, but share this request with as many already
        // collected normal entries as the safety budget permits.
        for (int pass = 0; pass < 2 && !budgetFull; pass++) {
            boolean highPass = pass == 0;
            var iterator = queue.entrySet().iterator();
            while (iterator.hasNext() && drained.size() < MAX_BATCH) {
                Queued next = iterator.next().getValue();
                if (next.highPriority != highPass) continue;
                int nextChars = batchChars(next.snapshot.key());
                if (!packer.fits(nextChars)) {
                    budgetFull = true;
                    break;
                }
                drained.add(next);
                packer.add(nextChars);
                iterator.remove();
                if (packer.full()) {
                    budgetFull = true;
                    break;
                }
            }
        }
        queuedChars = Math.max(0, queuedChars - packer.chars());
        queueStartedAtMs = queue.isEmpty() ? -1L : clock.getAsLong();
        return drained;
    }

    /**
     * Windowed collector, under {@code queueLock}: whether a request should be composed now.
     * The oldest entry decides the window (entries left over from a full request keep their
     * arrival time, so they never wait a second window), a request that is already full does
     * not wait for it, and in every case the engine must be able to send right away.
     */
    private boolean windowedBatchDue(int windowMs) {
        long now = clock.getAsLong();
        if (windowedBatchTicket.get() != 0L
                && now - windowedBatchStartedAtMs < WINDOWED_BATCH_STALE_MS) {
            // The previous batch has not finished (it may still be waiting for its pacer
            // slot). Composing now would only park a second request behind it while later
            // text keeps arriving outside both, so keep collecting.
            return false;
        }
        boolean full = queue.size() >= MAX_BATCH || queuedChars >= MAX_WINDOWED_BATCH_CHARS;
        if (!full && !hoveredAndEngineIdle()) {
            Queued oldest = queue.values().iterator().next();
            if (now - oldest.enqueuedAtMs < windowMs) return false;
        }
        return engineMaySendNow();
    }

    /**
     * Whether a hovered (high-priority) entry is waiting AND the engine's own pacer is
     * already open right now. When both hold, there is nothing to gain from waiting out
     * the rest of the window: the engine was idle (no request pending, no cooldown
     * running), so nothing else was going to be forced to wait for THIS request's pacer
     * slot anyway, and every extra millisecond is pure added latency for a tooltip the
     * player is looking at right now. This restores hover's pre-1.0.7 "flush now" for the
     * one case that costs nothing (a busy/cooling-down engine still fills the window
     * exactly as before, so bursts of hovers still consolidate into one request the same
     * way -- see the simulation in investigate-slowness.md).
     */
    private boolean hoveredAndEngineIdle() {
        if (!engineMaySendNow()) return false;
        for (Queued item : queue.values()) {
            if (item.highPriority) return true;
        }
        return false;
    }

    private boolean engineSendBlocked() {
        try {
            return translator.sendBlocked();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Whether this engine's own pacing would let a request go out right now. */
    private boolean engineMaySendNow() {
        try {
            return translator.nextRequestDelayMs() <= 0L;
        } catch (RuntimeException ignored) {
            return true; // a misbehaving peek must never stall the collector
        }
    }

    private long beginWindowedBatch() {
        long ticket = windowedTicketSequence.incrementAndGet();
        windowedBatchStartedAtMs = clock.getAsLong();
        windowedBatchTicket.set(ticket);
        return ticket;
    }

    /** Only the batch that holds the current ticket releases it (a stale one may still end). */
    private void endWindowedBatch(long ticket) {
        if (ticket != 0L) windowedBatchTicket.compareAndSet(ticket, 0L);
    }

    /** Surface context of every entry this batch actually sends (entries without one are absent). */
    private static Map<String, List<String>> itemContexts(List<Queued> drained,
                                                          Map<String, Flight> owned) {
        Map<String, List<String>> contexts = new java.util.HashMap<>();
        for (Queued item : drained) {
            String key = item.snapshot.key();
            if (item.surfaceContext != null && owned.containsKey(key)) {
                contexts.put(key, item.surfaceContext);
            }
        }
        return contexts;
    }

    /**
     * Drop every collected (not yet dispatched) entry after new requests were switched
     * off: permits are returned and always-callbacks complete with {@code null}, exactly
     * like {@link #reset}, but generation, memory and every failure/retry table are left
     * untouched, because nothing failed. Reopening the switch lets the next observation
     * request the same text again.
     */
    private void discardQueuedWork() {
        List<Callback> cancelled = new ArrayList<>();
        synchronized (queueLock) {
            for (Queued queued : queue.values()) {
                releasePendingEntry();
                cancelled.addAll(queued.callbacks);
            }
            queue.clear();
            queueGrew = false;
            settleTicks = 0;
            queueStartedAtMs = -1L;
            queuedChars = 0;
        }
        cancelled.forEach(callback -> deliver(callback, null));
    }

    // -------------------------------------------------------------------------
    // Projection-only rows: display the projection, rebuild the semantic row locally
    // -------------------------------------------------------------------------
    // A CS-marked line whose final colour projection is cached but whose semantic row is
    // missing is served from the projection by every lookup (render, chat, flush, batch),
    // so no surface stays original and no request is ever bought for it. The semantic row
    // (used by the plain container/hotbar key and by differently coloured variants) is
    // rebuilt from the projection on a worker, into session memory only; a projection whose
    // plain copy cannot be rebuilt simply stays displayed. Zero requests, zero tokens, no
    // store write (so no eviction at the store's row cap), independent of the request
    // switch, and nothing is recorded as a failure. The rebuilt copy is marked as derived:
    // it never counts as final wording for a write, an import or an export, so it can only
    // fill the gap until a genuine semantic row arrives, never block one.

    private enum Rebuild { REBUILT, DEFERRED, FAILED }

    /**
     * Render/tick side, O(1): remember a CS-marked line whose final colour projection is
     * cached while its semantic row may be missing, so that the next tick hands it to a
     * worker. Nothing is scanned, requested or written here.
     */
    private void noteSemanticRebuild(TranslationTemplate.Snapshot snapshot) {
        String key = snapshot.key();
        if (unrebuildableProjections.contains(key) || pendingSemanticRebuilds.containsKey(key)
                || pendingSemanticRebuilds.size() >= MAX_SEMANTIC_REBUILDS) {
            return;
        }
        pendingSemanticRebuilds.putIfAbsent(key, snapshot);
    }

    /**
     * Tick side: hand the noted lines to one low-priority worker task (bounded per tick), so
     * the rebuild — reads that may touch the disk tier, and the validation work — never runs
     * on the client (render/tick) thread. A rebuild is local cache maintenance that sends
     * nothing, so it runs regardless of the request switch. A line that cannot be rebuilt
     * keeps displaying its projection and is not noted again this session. While a rebuild
     * task is still queued or running no other is handed out: the noted lines simply wait, so
     * a stalled pool never holds more than one rebuild task per cache.
     */
    private void dispatchSemanticRebuilds() {
        if (pendingSemanticRebuilds.isEmpty()
                || !rebuildTaskOutstanding.compareAndSet(false, true)) {
            return;
        }
        List<TranslationTemplate.Snapshot> batch = new ArrayList<>();
        for (Map.Entry<String, TranslationTemplate.Snapshot> pending
                : pendingSemanticRebuilds.entrySet()) {
            if (batch.size() >= MAX_SEMANTIC_REBUILDS_PER_TICK) break;
            if (pendingSemanticRebuilds.remove(pending.getKey(), pending.getValue())) {
                batch.add(pending.getValue());
            }
        }
        if (batch.isEmpty()) {
            rebuildTaskOutstanding.set(false);
            return;
        }
        long expectedGeneration = generation.get();
        Map<String, Long> expectedRevisions = revisions(batch);
        Runnable task = () -> {
            try {
                for (TranslationTemplate.Snapshot snapshot : batch) {
                    long expectedRevision = expectedRevisions.getOrDefault(snapshot.key(), 0L);
                    if (!current(snapshot.key(), expectedGeneration, expectedRevision)) continue;
                    if (projectionWithoutSemantic(snapshot)) {
                        rebuildOrRemember(snapshot, expectedGeneration, expectedRevision);
                    }
                }
            } finally {
                rebuildTaskOutstanding.set(false);
            }
        };
        boolean accepted = false;
        try {
            accepted = executeLow(task);
        } finally {
            // Not handed over (pool saturated or shut down, or the executor failed): this batch
            // is simply dropped, and the next render lookup notes it again.
            if (!accepted) rebuildTaskOutstanding.set(false);
        }
    }

    /**
     * Request-side "already cached", matching what a render lookup displays. A CS-marked
     * line is also served by a final projection stored under its §-free projection key,
     * which a plain {@link #lookupSnapshot} of a §-carrying styled key does not read. This
     * is only a check: a projection this line's live values cannot restore is left for the
     * render lookup to discard, never deleted here on the tick thread. A session copy derived
     * from a projection does not count: a request for that row is only in hand when it was
     * collected while the row was missing (a copy appearing later makes the rebuild defer), so
     * it is sent exactly as without the copy, and its answer replaces the copy.
     */
    private boolean cachedForRequest(TranslationTemplate.Snapshot snapshot) {
        if (keepsOriginalFamily(snapshot)) return true;
        if (lookupSnapshot(snapshot, this) != null && !derivedRow(snapshot)) return true;
        if (!hasCsMarkers(snapshot.key())) return false;
        // A §-free projection key equal to the styled key was already read just above.
        return !styleProjectionKey(snapshot).equals(snapshot.key()) && ownFinalProjection(snapshot) != null;
    }

    /** KEEP belongs to the same semantic key used by the echo counter, including
     * the original snapshot's live slot layout. A normal plain translation is not KEEP. */
    private boolean keepsOriginalFamily(TranslationTemplate.Snapshot snapshot) {
        return snapshot != null && keepsOriginal(contentFailureKey(snapshot));
    }

    /** This CS-marked line's own final colour projection restored with its live values, or
     *  {@code null}: read-only, the same acceptance a render lookup applies (minus deletion). */
    private String ownFinalProjection(TranslationTemplate.Snapshot snapshot) {
        String projectionKey = styleProjectionKey(snapshot);
        if (provisional(projectionKey)) return null;
        String stored = read(projectionKey);
        if (stored == null || stored.equals(projectionKey)) return null;
        String restored = snapshot.restore(stored);
        return usable(restored) && matchingCsShape(snapshot.source(), restored) ? restored : null;
    }

    /** Whether the row this snapshot's lookup reads is a session copy derived from a projection. */
    private boolean derivedRow(TranslationTemplate.Snapshot snapshot) {
        return !derivedSemanticRows.isEmpty() && derivedSemanticRows.contains(
                snapshot.changed() ? snapshot.key() : snapshot.normalized());
    }

    /** CS-marked, not keep-original, and no semantic row. A plain form that is not
     *  translatable at all can never get one; its rebuild fails once and is memoised. */
    private boolean projectionWithoutSemantic(TranslationTemplate.Snapshot snapshot) {
        if (snapshot == null || !hasCsMarkers(snapshot.key())) return false;
        TranslationTemplate.Snapshot plain = templates.prepare(stripStyle(snapshot.source()));
        if (keepsOriginal(plain.key())) return false;
        return lookupSnapshot(plain, this) == null;
    }

    private void rebuildOrRemember(TranslationTemplate.Snapshot snapshot,
                                   long expectedGeneration, long expectedRevision) {
        if (rebuildSemanticFromProjection(snapshot, expectedGeneration, expectedRevision)
                != Rebuild.FAILED) {
            return;
        }
        // A line retranslated meanwhile failed only because its rows were just deleted; its
        // new projection must still be rebuilt, so the memo is written for current work only.
        if (!current(snapshot.key(), expectedGeneration, expectedRevision)) return;
        if (unrebuildableProjections.size() >= MAX_SEMANTIC_REBUILDS) unrebuildableProjections.clear();
        unrebuildableProjections.add(snapshot.key());
    }

    /**
     * Worker side: rebuild the missing semantic row of a CS-marked line from its cached
     * FINAL colour projection, with this line's CURRENT live values (see
     * {@link #semanticFromProjection}), into the session memory tier only. The projection
     * stays the durable source and already serves every lookup of this line, so the row
     * only has to serve the plain key (container/hotbar names) and other colour topologies.
     * Persisting it would write the journal and, with the store at its row cap, evict an
     * older row that might later have to be bought again. Local maintenance: it never sends
     * a request, writes the store or records a failure (a failed rebuild is only memoised).
     * The copy is marked as derived, so any genuine semantic row (another topology's answer,
     * an import, a retranslation) still replaces and persists it.
     */
    private Rebuild rebuildSemanticFromProjection(TranslationTemplate.Snapshot snapshot,
                                                  long expectedGeneration, long expectedRevision) {
        TranslationTemplate.Snapshot plain = templates.prepare(stripStyle(snapshot.source()));
        long plainRevision = keyRevision(plain.key());
        if (lookupSnapshot(plain, this) != null) return Rebuild.REBUILT;
        // A request for the semantic row is already under way (for example an explicit
        // retranslate): its answer, not an older projection, must become the row.
        if (flights.containsKey(plain.key()) || queued(plain.key())) return Rebuild.DEFERRED;
        String projectionKey = styleProjectionKey(snapshot);
        String projected = read(projectionKey);
        if (projected == null || projected.equals(projectionKey) || provisional(projectionKey)) {
            return Rebuild.FAILED;
        }
        String semantic = semanticFromProjection(snapshot, plain, projected);
        if (semantic == null) return Rebuild.FAILED;
        String rowKey = plain.changed() ? plain.key() : plain.normalized();
        boolean written = false;
        synchronized (memory) {
            // Re-checked at the write itself: a language switch, a retranslate of either line
            // or a genuine row that landed while this copy was being derived outranks it.
            if (!current(snapshot.key(), expectedGeneration, expectedRevision)
                    || keyRevision(plain.key()) != plainRevision) {
                return Rebuild.DEFERRED;
            }
            if (memory.get(rowKey) == null || derivedSemanticRows.contains(rowKey)) {
                derivedSemanticRows.add(rowKey);
                memory.put(rowKey, semantic);
                provisional.remove(rowKey);
                written = true;
            }
        }
        if (written) notifyFinalWaiters(plain.source());
        return lookupSnapshot(plain, this) != null ? Rebuild.REBUILT : Rebuild.FAILED;
    }

    /**
     * The plain key's semantic template derived from a styled line's projection, or
     * {@code null} when it cannot be derived and validated. The provider's de-styled template
     * is taken directly only when both keys slot the same live values under the same indices;
     * otherwise the projection is restored with the line's own values and templated again. A
     * colour run can split a sign from its number: the styled key slots "10" after a literal
     * "+" while the plain key slots "+10", so the de-styled "+⟦MT0⟧" would render "++10".
     */
    private String semanticFromProjection(TranslationTemplate.Snapshot snapshot,
                                          TranslationTemplate.Snapshot plain, String projected) {
        String plainSource = plain.source();
        if (plainSource.equals(snapshot.normalized()) || !plain.hasTranslatableContent()) return null;
        String plainValue = stripStyle(projected);
        if (CS_RESIDUE.matcher(plainValue).find()) return null;
        // A projection that lost a protected name or term is never displayed (the service
        // reverts it to the original). Deriving a copy from it would also hide the plain row's
        // own miss, which buys that row exactly as before 1.0.7, so no copy is made.
        if (!tokenMultiset(plain.key(), PROTECTED_PLACEHOLDER)
                .equals(tokenMultiset(plainValue, PROTECTED_PLACEHOLDER))) {
            return null;
        }
        if (sameSlotValues(snapshot, plain) && usable(plain.key(), plainValue)) return plainValue;
        String restored = stripStyle(snapshot.restore(plainValue));
        if (restored.equals(plainSource)) return null; // an echo is not a translation
        String retokenized = plain.retokenize(restored);
        return retokenized != null && usable(plain.key(), retokenized) ? retokenized : null;
    }

    // -------------------------------------------------------------------------
    // Item warm-up lane
    // -------------------------------------------------------------------------

    /** Concurrent requests the dedicated warm-up pool can run (the driver chooses how many it uses). */
    public static final int WARM_LANE_THREADS = 3;

    private static final ThreadLocal<WarmCollector> WARM_COLLECTOR = new ThreadLocal<>();

    private volatile java.util.concurrent.ExecutorService warmPool;

    /** One collected request unit of the warm-up lane with its own surface context. */
    private record WarmEntry(TranslationTemplate.Snapshot snapshot, List<String> context) {
    }

    /** Units gathered on the calling thread while {@link #collectWarmLane} runs, per cache. */
    private static final class WarmCollector {
        private final Map<TranslationCache, Map<String, WarmEntry>> pending = new LinkedHashMap<>();

        void add(TranslationCache cache, List<TranslationTemplate.Snapshot> snapshots,
                 List<String> context) {
            Map<String, WarmEntry> entries =
                    pending.computeIfAbsent(cache, ignored -> new LinkedHashMap<>());
            for (TranslationTemplate.Snapshot snapshot : snapshots) {
                WarmEntry existing = entries.get(snapshot.key());
                if (existing == null || (existing.context() == null && context != null)) {
                    entries.put(snapshot.key(), new WarmEntry(snapshot, context));
                }
            }
        }
    }

    /**
     * Run {@code body} (which warms background items through the usual paths), but instead
     * of feeding the shared collector with the units it discovers, gather them and send
     * everything missing, per cache, as ONE request on the dedicated warm-up pool. That
     * request skips the interactive cooldown (the warm-up driver paces itself) and never
     * shows up as interactive work. Returns how many requests were dispatched.
     */
    public static int collectWarmLane(Runnable body) {
        WarmCollector previous = WARM_COLLECTOR.get();
        WarmCollector collector = new WarmCollector();
        WARM_COLLECTOR.set(collector);
        try {
            body.run();
        } finally {
            if (previous == null) WARM_COLLECTOR.remove();
            else WARM_COLLECTOR.set(previous);
        }
        int dispatched = 0;
        for (Map.Entry<TranslationCache, Map<String, WarmEntry>> entry
                : collector.pending.entrySet()) {
            dispatched += entry.getKey().dispatchWarmLane(entry.getValue().values());
        }
        return dispatched;
    }

    /** @return how many requests were handed over */
    private int dispatchWarmLane(Collection<WarmEntry> entries) {
        if (entries.isEmpty() || !requestsAllowed()) return 0;
        List<TranslationTemplate.Snapshot> send = new ArrayList<>();
        Map<String, Flight> owned = new LinkedHashMap<>();
        Map<String, List<String>> contexts = new java.util.HashMap<>();
        for (WarmEntry entry : entries) {
            String key = entry.snapshot().key();
            Flight ours = new Flight();
            ours.warm = true;
            // The driver bounds what it hands over, so the lane does not compete for the
            // interactive pending-entry permits; it still holds (and returns) one each.
            pendingEntries.incrementAndGet();
            if (flights.putIfAbsent(key, ours) == null) {
                owned.put(key, ours);
                send.add(entry.snapshot());
                if (entry.context() != null) contexts.put(key, entry.context());
            } else {
                releasePendingEntry();
            }
        }
        if (send.isEmpty()) return 0;
        long expectedGeneration = generation.get();
        Map<String, Long> expectedRevisions = revisions(send);
        // Masking and segment decomposition can change unit sizes after the driver packed
        // its items, so the lane re-applies the shared request budget: a part that does not
        // fit leaves as its own request (a single oversized unit goes alone).
        int handedOver = 0;
        int from = 0;
        while (from < send.size()) {
            BatchBudget packer = BatchBudget.windowed();
            int to = from;
            while (to < send.size() && packer.fits(batchChars(send.get(to).key()))) {
                packer.add(batchChars(send.get(to).key()));
                to++;
            }
            List<TranslationTemplate.Snapshot> part = new ArrayList<>(send.subList(from, to));
            Map<String, Flight> partOwned = new LinkedHashMap<>();
            Map<String, List<String>> partContexts = new java.util.HashMap<>();
            for (TranslationTemplate.Snapshot snapshot : part) {
                partOwned.put(snapshot.key(), owned.get(snapshot.key()));
                List<String> context = contexts.get(snapshot.key());
                if (context != null) partContexts.put(snapshot.key(), context);
            }
            if (submitWarmPart(part, partOwned, partContexts, expectedGeneration,
                    expectedRevisions)) {
                handedOver++;
            }
            from = to;
        }
        return handedOver;
    }

    private boolean submitWarmPart(List<TranslationTemplate.Snapshot> part,
                                   Map<String, Flight> owned,
                                   Map<String, List<String>> contexts,
                                   long expectedGeneration,
                                   Map<String, Long> expectedRevisions) {
        Runnable task = () -> {
            Boolean previous = RequestPacer.bindUnpaced();
            try {
                if (generation.get() == expectedGeneration) {
                    translateBatch(part, null, contexts.isEmpty() ? null : contexts,
                            expectedGeneration, expectedRevisions);
                }
            } finally {
                RequestPacer.restoreUnpaced(previous);
                owned.forEach(this::finishFlight);
            }
        };
        boolean accepted;
        try {
            warmExecutor().execute(task);
            accepted = true;
        } catch (RejectedExecutionException e) {
            accepted = false;
        }
        if (!accepted) owned.forEach(this::finishFlight);
        return accepted;
    }

    /** The dedicated pool when the cache runs on the priority executor, else that executor. */
    private Executor warmExecutor() {
        if (!(executor instanceof PriorityTranslationExecutor)) return executor;
        java.util.concurrent.ExecutorService pool = warmPool;
        if (pool == null) {
            synchronized (this) {
                pool = warmPool;
                if (pool == null) {
                    AtomicInteger counter = new AtomicInteger();
                    java.util.concurrent.ThreadPoolExecutor created =
                            new java.util.concurrent.ThreadPoolExecutor(WARM_LANE_THREADS,
                                    WARM_LANE_THREADS, 30L, java.util.concurrent.TimeUnit.SECONDS,
                                    new java.util.concurrent.LinkedBlockingQueue<>(16), runnable -> {
                                Thread thread = new Thread(runnable,
                                        "nyanlex-warm-" + counter.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            });
                    created.allowCoreThreadTimeOut(true);
                    warmPool = pool = created;
                }
            }
        }
        return pool;
    }

    /**
     * Whether anything other than the item warm-up is collected or in flight on this cache
     * (chat, tooltip, key-triggered translation). The warm-up yields while this is true.
     */
    public boolean hasInteractiveWork() {
        synchronized (queueLock) {
            for (Queued queued : queue.values()) {
                if (!queued.background) return true;
            }
        }
        for (Flight flight : flights.values()) {
            if (!flight.warm) return true;
        }
        return false;
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
        warmBatchAsync(sources, surfaceLines, false);
    }

    /** Visible/hovered work: promote these complete entries inside the timed collector
     * and flush it next tick on the priority executor. This avoids buying a separate
     * HTTP request beside an already-pending normal batch. */
    public void warmBatchAsyncHigh(List<String> sources, List<String> surfaceLines) {
        warmBatchAsync(sources, surfaceLines, true);
    }

    private void warmBatchAsync(List<String> sources, List<String> surfaceLines,
                                boolean highPriority) {
        List<TranslationTemplate.Snapshot> candidates = prepareMissing(sources, true);
        if (candidates.isEmpty()) return;
        List<String> requestContext = context(surfaceLines);
        WarmCollector lane = highPriority ? null : WARM_COLLECTOR.get();
        if (lane != null) {
            lane.add(this, candidates, requestContext);
            return;
        }
        if (batchWindowMs != null) {
            for (TranslationTemplate.Snapshot snapshot : candidates) {
                enqueue(snapshot, null, requestContext, highPriority);
            }
            return;
        }
        List<TranslationTemplate.Snapshot> send = new ArrayList<>();
        Map<String, Flight> owned = new LinkedHashMap<>();
        for (TranslationTemplate.Snapshot snapshot : candidates) {
            if (!tryAcquirePendingEntry()) break;
            Flight ours = new Flight();
            if (flights.putIfAbsent(snapshot.key(), ours) == null) {
                owned.put(snapshot.key(), ours);
                send.add(snapshot);
            } else {
                releasePendingEntry();
            }
        }
        if (send.isEmpty()) return;

        long expectedGeneration = generation.get();
        Map<String, Long> expectedRevisions = revisions(send);
        Runnable task = () -> {
            try {
                if (generation.get() == expectedGeneration) {
                    translateBatch(send, requestContext, expectedGeneration, expectedRevisions);
                }
            } finally {
                owned.forEach(this::finishFlight);
            }
        };
        boolean accepted = highPriority ? executeHigh(task) : executeLow(task);
        if (!accepted) owned.forEach(this::finishFlight);
    }

    public void translateAllAsync(List<String> sources, Consumer<List<String>> onResults) {
        long expectedGeneration = generation.get();
        Runnable task = () -> {
            if (generation.get() != expectedGeneration) {
                deliverResults(onResults, sources);
                return;
            }
            warmBatch(sources);
            List<String> results = new ArrayList<>(sources.size());
            for (String source : sources) {
                String hit = getCached(source);
                results.add(hit == null ? source : hit);
            }
            onResults.accept(results);
        };
        if (!executeLow(task)) deliverResults(onResults, sources);
    }

    private static void deliverResults(Consumer<List<String>> callback, List<String> sources) {
        if (callback == null) return;
        try {
            callback.accept(sources == null ? List.of() : new ArrayList<>(sources));
        } catch (RuntimeException ignored) {
            // A caller callback cannot break render-thread request coordination.
        }
    }

    private void rememberRetrySnapshot(String stateKey,
                                       TranslationTemplate.Snapshot snapshot) {
        if (stateKey == null || snapshot == null
                || !sessionRetryDemanded(stateKey, snapshot)
                || keepsOriginalFamily(snapshot)) return;
        retrySnapshots.put(stateKey, snapshot);
        trimMap(retrySnapshots, MAX_TRACKED_RETRY_STATES);
    }

    private void trimRetryStateMaps() {
        trimMap(failedUntil, MAX_TRACKED_RETRY_STATES);
        trimMap(contentFailures, MAX_TRACKED_RETRY_STATES);
        trimMap(contentRetryAttempts, MAX_TRACKED_RETRY_STATES);
        trimMap(provisionalRetryAttempts, MAX_TRACKED_RETRY_STATES);
        trimMap(retrySnapshots, MAX_TRACKED_RETRY_STATES);
    }

    private static <K, V> void trimMap(Map<K, V> values, int limit) {
        int excess = values.size() - limit;
        if (excess <= 0) return;
        for (Map.Entry<K, V> entry : values.entrySet()) {
            if (excess <= 0) break;
            if (values.remove(entry.getKey(), entry.getValue())) excess--;
        }
    }

    private List<TranslationTemplate.Snapshot> prepareMissing(List<String> sources,
                                                               boolean skipActiveFlights) {
        LinkedHashMap<String, TranslationTemplate.Snapshot> unique = new LinkedHashMap<>();
        if (sources == null) return List.of();
        // Cache-only mode: every warm/retranslate/fallback batch has nothing to send.
        if (!requestsAllowed()) return List.of();
        for (String source : sources) {
            if (source == null || getCached(source) != null) continue;
            TranslationTemplate.Snapshot snapshot = semanticRequestFor(templates.prepare(source));
            if (!eligible(snapshot)) continue;
            if (skipActiveFlights && flights.containsKey(snapshot.key())) continue;
            unique.putIfAbsent(snapshot.key(), snapshot);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Most colour pairs one request unit may carry. Live runs: units with 6-8 pairs failed
     * ~8% of the time, 20+ pairs about 6 times out of 7 -- the model merges, renumbers or
     * drops adjacent pairs, deterministically (same unit, same failure on every retry).
     */
    static final int MAX_CS_PAIRS_PER_REQUEST = 8;

    private static int csPairCount(String key) {
        int pairs = 0;
        java.util.regex.Matcher matcher = CS_TOKEN.matcher(key);
        while (matcher.find()) {
            if (matcher.group(1).isEmpty()) pairs++;
        }
        return pairs;
    }

    /**
     * What to actually ask the provider for when a CS-marked line is missing. Normally the
     * line itself; but a fragmented line (more than {@link #MAX_CS_PAIRS_PER_REQUEST} colour
     * pairs) -- or one whose request already failed validation once (the failure ledger is
     * shared by the whole colour-free family) -- is requested as its COLOUR-INSENSITIVE
     * semantic row instead. That row is validated and stored like any plain line; every
     * lookup of the coloured line then finds it as a style-fallback hit and re-applies the
     * line's own colours on the display side, so the sentence is translated reliably and
     * colour only degrades to the conservative fallback, never to "stays English forever".
     * A line that is not CS-marked, or whose plain form is untranslatable, is returned as is.
     */
    private TranslationTemplate.Snapshot semanticRequestFor(TranslationTemplate.Snapshot snapshot) {
        if (snapshot == null || !hasCsMarkers(snapshot.key())) return snapshot;
        TranslationTemplate.Snapshot plain = templates.prepare(stripStyle(snapshot.source()));
        if (plain.key().isEmpty() || !plain.hasTranslatableContent()) return snapshot;
        boolean heavy = csPairCount(snapshot.key()) > MAX_CS_PAIRS_PER_REQUEST
                || contentRetryAttempts.getOrDefault(plain.key(), 0) > 0;
        return heavy ? plain : snapshot;
    }

    private boolean translateBatch(List<TranslationTemplate.Snapshot> snapshots,
                                   List<String> surfaceContext,
                                   long expectedGeneration,
                                   Map<String, Long> expectedRevisions) {
        return translateBatch(snapshots, surfaceContext, null, expectedGeneration, expectedRevisions);
    }

    /**
     * @param itemContexts per-entry surface contexts of a windowed batch (by request key), or
     *                     {@code null} to send {@code surfaceContext} as the one shared context
     */
    private boolean translateBatch(List<TranslationTemplate.Snapshot> snapshots,
                                   List<String> surfaceContext,
                                   Map<String, List<String>> itemContexts,
                                   long expectedGeneration,
                                   Map<String, Long> expectedRevisions) {
        if (snapshots == null || snapshots.isEmpty()) return true;
        // Dispatched before new requests were switched off: give up unsent. Not a
        // failure, so nothing is recorded; the owning flights still complete (null).
        if (!requestsAllowed()) return true;

        List<TranslationTemplate.Snapshot> todo = new ArrayList<>();
        for (TranslationTemplate.Snapshot snapshot : snapshots) {
            long expectedRevision = expectedRevisions.getOrDefault(snapshot.key(), 0L);
            if (!current(snapshot.key(), expectedGeneration, expectedRevision)) continue;
            if (cachedForRequest(snapshot)) {
                // Cached, possibly as a CS colour projection alone: rebuild the semantic
                // row from it here on the worker. Either way nothing is requested.
                if (projectionWithoutSemantic(snapshot)) {
                    rebuildOrRemember(snapshot, expectedGeneration, expectedRevision);
                }
                continue;
            }
            if (!snapshot.hasTranslatableContent() || backingOff(snapshot.key())) continue;
            // Both gates run here too (not just at eligible()/requestImmediate's own
            // entry points) because this is the SINGLE chokepoint every path -- the
            // immediate batch send above, the windowed-batch flush, and every per-key
            // immediate request -- funnels through right before the real HTTP call;
            // see refuseUnsendable/globalSendFuseAllows for what each one guards.
            if (refuseUnsendable(snapshot)) continue;
            if (!globalSendFuseAllows(snapshot.key())) continue;
            todo.add(snapshot);
        }
        if (todo.isEmpty()) return true;

        List<String> keys = todo.stream().map(TranslationTemplate.Snapshot::key).toList();
        long debugId = debugSubmitted(keys);
        boolean allSucceeded = true;
        try {
            List<TranslationResult> results;
            BooleanSupplier previousGate = RequestGate.bind(requestGateFor(todo));
            try {
                if (itemContexts != null) {
                    List<List<String>> contexts = new ArrayList<>(todo.size());
                    for (TranslationTemplate.Snapshot snapshot : todo) {
                        contexts.add(itemContexts.get(snapshot.key()));
                    }
                    results = translator.translateBatchWithContexts(keys, targetLang, contexts);
                } else {
                    results = translator.translateBatch(keys, targetLang, surfaceContext);
                }
            } finally {
                RequestGate.restore(previousGate);
            }
            if (results.size() != todo.size()) {
                for (TranslationTemplate.Snapshot snapshot : todo) {
                    if (current(snapshot.key(), expectedGeneration,
                            expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                        fail(snapshot.key());
                    }
                }
                debugCompleted(debugId, new TranslationDebugLog.Failure(
                        TranslationDebugLog.Status.FAILED, "anchor/order damaged"));
                return false;
            }
            List<Boolean> keptOriginal = new ArrayList<>(todo.size());
            for (int i = 0; i < todo.size(); i++) {
                TranslationTemplate.Snapshot snapshot = todo.get(i);
                TranslationResult result = results.get(i);
                boolean usableResult = usable(snapshot.key(), result.translatedText());
                boolean kept = current(snapshot.key(), expectedGeneration,
                        expectedRevisions.getOrDefault(snapshot.key(), 0L))
                        && (keepsOriginalFamily(snapshot) || !usableResult
                        && handleUnusableContent(snapshot, result));
                keptOriginal.add(kept);
                if (usableResult && !kept) {
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
                    if (!keptOriginal.get(i) && !keepsOriginalFamily(snapshot)
                            && usable(snapshot.key(), result.translatedText())
                            && current(snapshot.key(), expectedGeneration,
                            expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                        store(snapshot, result.translatedText(), result.fromFallback(), writes);
                    }
                }
                writes.flush();
                // Publish/persist the readable batch before scheduling any review.
                for (int i = 0; i < todo.size(); i++) {
                    TranslationTemplate.Snapshot snapshot = todo.get(i);
                    reviewParagraphOnce(snapshot, results.get(i), itemContexts == null
                                    ? surfaceContext : itemContexts.get(snapshot.key()),
                            expectedGeneration, expectedRevisions.getOrDefault(snapshot.key(), 0L));
                }
            }
            List<String> debugTranslations = new ArrayList<>(results.size());
            List<TranslationDebugLog.Status> debugStatuses = new ArrayList<>(results.size());
            List<String> debugFailureReasons = new ArrayList<>(results.size());
            for (int i = 0; i < results.size(); i++) {
                TranslationResult result = results.get(i);
                boolean usableResult = usable(todo.get(i).key(), result.translatedText());
                boolean kept = keptOriginal.get(i);
                debugTranslations.add(kept ? todo.get(i).key() : usableResult ? result.translatedText() : null);
                debugStatuses.add(kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                        : !usableResult ? TranslationDebugLog.Status.FAILED
                        : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                        : TranslationDebugLog.Status.SUCCESS);
                debugFailureReasons.add(!usableResult && !kept
                        ? failureReasonFor(todo.get(i).key(), result) : null);
            }
            debugCompleted(debugId, debugTranslations, debugStatuses, debugFailureReasons);
            return allSucceeded;
        } catch (RequestsPausedException paused) {
            // Switched off while waiting for a send slot: unsent, and not a failure.
            debugDiscarded(debugId);
            return true;
        } catch (TranslationException | RuntimeException e) {
            for (TranslationTemplate.Snapshot snapshot : todo) {
                if (current(snapshot.key(), expectedGeneration,
                        expectedRevisions.getOrDefault(snapshot.key(), 0L))) {
                    fail(snapshot.key());
                }
            }
            debugCompleted(debugId, TranslationDebugLog.failureFor(e));
            return false;
        }
    }

    private List<String> context(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        List<String> keys = new ArrayList<>(Math.min(lines.size(), MAX_CONTEXT_LINES));
        int chars = 0;
        for (String line : lines) {
            if (line == null) continue;
            if (keys.size() >= MAX_CONTEXT_LINES) break;
            // Context is advisory. Never template or retain one pathological tooltip row;
            // the actual request text still follows its own atomic batch rules.
            if (line.length() > MAX_CONTEXT_CHARS) continue;
            String key = templates.prepare(line).key();
            if (key.length() > MAX_CONTEXT_CHARS) continue;
            int nextChars = chars + key.length();
            if (nextChars > MAX_CONTEXT_CHARS) break;
            // Empty tooltip rows are semantic section boundaries (stats / enchants /
            // ability / market).  Keep them so OpenAiTranslator can emit [SECTION]
            // instead of flattening the whole item into an undifferentiated word list.
            keys.add(key);
            chars = nextChars;
            if (chars >= MAX_CONTEXT_CHARS) break;
        }
        return keys.isEmpty() ? null : keys;
    }

    private long keyRevision(String key) {
        return keyRevisions.getOrDefault(key, 0L);
    }

    private void recordKeyRevision(String key) {
        if (key == null) return;
        long revision = revisionSequence.incrementAndGet();
        keyRevisions.put(key, revision);
        if (keyRevisions.size() <= MAX_TRACKED_KEY_REVISIONS) return;

        for (Map.Entry<String, Long> entry : keyRevisions.entrySet()) {
            if (keyRevisions.size() <= MAX_TRACKED_KEY_REVISIONS) break;
            String candidate = entry.getKey();
            if (!flights.containsKey(candidate) && !queued(candidate)) {
                keyRevisions.remove(candidate, entry.getValue());
            }
        }
        if (keyRevisions.size() > MAX_TRACKED_KEY_REVISIONS) {
            // All remaining revisions protect live work. A generation bump safely
            // invalidates it as one group rather than retaining an unbounded table.
            generation.incrementAndGet();
            keyRevisions.clear();
            keyRevisions.put(key, revision);
        }
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
        // An older in-flight response cannot reopen a terminal semantic family.
        if (keepsOriginalFamily(snapshot)) return;
        clearFailureState(snapshot);
        // Styled variants all converge on one durable semantic row. Keep a raw styled
        // row only when the backend damaged its markers so a safe plain projection is
        // impossible; this prevents colour permutations from disagreeing forever.
        boolean plainWritten = writePlainCopy(snapshot, translated, isProvisional, writes);
        if (hasCsMarkers(snapshot.key())) {
            boolean projected = writeStyleProjection(snapshot, translated, isProvisional, writes);
            // A semantic success whose projection could not be written (markers eaten,
            // residue) leaves a passive style-ledger debt. Chat can immediately render
            // the semantic fallback; buying the exact colours again requires another
            // observation after backoff (or an explicit manual retry).
            if (!projected && !isProvisional && read(styleProjectionKey(snapshot)) == null) {
                failStyleProjection(snapshot);
            }
        }
        if (!plainWritten) {
            if (snapshot.changed()) {
                write(snapshot.key(), translated, isProvisional, writes);
            } else {
                write(snapshot.normalized(), translated, isProvisional, writes);
                if (!snapshot.normalized().equals(snapshot.source())
                        && usable(snapshot.source(), translated)) {
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
        synchronized (terminalWriteLock) {
            if (keepsOriginal(key)) return;
            // First final semantic wording wins until explicit invalidation. A response for a
            // new CS presentation topology may translate the same term differently; it may add
            // its own style row, but can never rewrite an existing final semantic row. A final
            // primary answer still replaces a provisional fallback because provisional rows do
            // not satisfy hasFinalValue(); neither do session copies derived from a projection.
            // A complete paragraph may also upgrade readable wording whose wraps were lost.
            if (hasFinalValue(key, writes)
                    && (isProvisional || !upgradesParagraphLayout(key, value))) return;
            synchronized (memory) {
                // A copy derived from a final AI projection ranks between a GT stand-in and a
                // genuine final row: the stand-in never replaces it, a final answer or import does.
                if (isProvisional && derivedSemanticRows.contains(key)) return;
                derivedSemanticRows.remove(key);
                memory.put(key, value);
            }
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
    }

    private boolean hasFinalValue(String key, WriteBatch writes) {
        if (writes != null && writes.values.containsKey(key)) {
            return !writes.provisionalKeys.contains(key);
        }
        String existing = memory.get(key);
        if (existing != null && usable(existing) && !provisional(key)
                && !derivedSemanticRows.contains(key)) {
            return true;
        }
        if (store == null) return false;
        existing = store.get(key);
        return existing != null && usable(existing) && !store.isProvisional(key);
    }

    /** A verified review may replace only wording that still has missing wraps. */
    private boolean upgradesParagraphLayout(String key, String value) {
        String existing = memory.get(key);
        if (existing == null && store != null) existing = store.get(key);
        return ParagraphModel.canReflowBreakLoss(key, existing)
                && matchingParagraphBreakShape(key, value)
                && matchingParagraphSlotShape(key, value);
    }

    private void reviewParagraphOnce(TranslationTemplate.Snapshot snapshot, TranslationResult first,
                                     List<String> surfaceContext, long expectedGeneration,
                                     long expectedRevision) {
        String key = snapshot.key();
        if (keepsOriginalFamily(snapshot) || first.fromFallback() || !usable(key, first.translatedText())
                || !ParagraphModel.canReflowBreakLoss(key, first.translatedText())
                || !current(key, expectedGeneration, expectedRevision)
                || !paragraphReviews.add(key)) return;
        String language = targetLang;
        Runnable task = () -> {
            long debugId = 0L;
            try {
                if (!current(key, expectedGeneration, expectedRevision) || !requestsAllowed()
                        || keepsOriginalFamily(snapshot)) return;
                debugId = debugSubmitted(List.of(key));
                TranslationResult reviewed;
                BooleanSupplier previousGate = RequestGate.bind(requestGateFor(List.of(snapshot)));
                try {
                    List<TranslationResult> results = translator.translateBatch(
                            List.of(key), language, surfaceContext);
                    if (results.size() != 1) {
                        debugDiscarded(debugId);
                        return;
                    }
                    reviewed = results.get(0);
                } finally {
                    RequestGate.restore(previousGate);
                }
                if (current(key, expectedGeneration, expectedRevision) && keepsOriginalFamily(snapshot)) {
                    debugCompleted(debugId, snapshot.key(), TranslationDebugLog.Status.KEEP_ORIGINAL);
                    return;
                }
                boolean accepted = !reviewed.fromFallback() && reviewed.failureReason() == null
                        && usable(key, reviewed.translatedText())
                        && matchingParagraphBreakShape(key, reviewed.translatedText())
                        && matchingParagraphSlotShape(key, reviewed.translatedText());
                if (accepted && current(key, expectedGeneration, expectedRevision)) {
                    store(snapshot, reviewed.translatedText(), false);
                }
                if (accepted) debugCompleted(debugId, reviewed.translatedText(), TranslationDebugLog.Status.SUCCESS);
                else debugDiscarded(debugId);
            } catch (RequestsPausedException paused) {
                debugDiscarded(debugId);
            } catch (TranslationException | RuntimeException ignored) {
                // The readable first answer is already durable. A failed review must
                // never enter the ordinary failure ledger or schedule another request.
                debugDiscarded(debugId);
            } finally {
                paragraphReviews.remove(key);
            }
        };
        if (!executeLow(task)) paragraphReviews.remove(key);
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
        // stream directly whenever it matches the plain semantic key AND the styled and
        // plain snapshots slot the SAME live values under the SAME indices. A colour run
        // can split a sign from its number ("⟦CS0⟧Berserk: +⟦/CS0⟧⟦CS1⟧10❁ Strength⟦/CS1⟧"
        // slots only "10" in the styled key, while the plain key slots "+10"): trusting the
        // provider's MT0/MT1 stream as-is would then restore the plain row's OWN "+10" value
        // on top of the literal "+" still sitting in the translated text, doubling the sign
        // into "++10" (impl-R F7). When the slot values differ, fall through to the
        // restore-then-retokenize path below, which rebuilds the semantic row from the
        // PLAIN snapshot's own values instead of reusing the styled stream verbatim — the
        // same guard {@link #semanticFromProjection} already applies to the F5 backfill path.
        if (sameSlotValues(original, plain) && usable(plain.key(), plainValue)) {
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

    /** Whether two snapshots slot the same live values under the same MT indices. */
    private static boolean sameSlotValues(TranslationTemplate.Snapshot styled,
                                          TranslationTemplate.Snapshot plain) {
        return styled.base().values().equals(plain.base().values())
                && styled.base().slotIndices().equals(plain.base().slotIndices());
    }

    /** @return whether a projection row write was actually attempted (valid content). */
    private boolean writeStyleProjection(TranslationTemplate.Snapshot snapshot, String translated,
                                         boolean isProvisional, WriteBatch writes) {
        if (translated == null || !hasCsMarkers(translated)) return false;
        String value = TextFilter.stripSectionCodes(translated);
        if (CS_RESIDUE.matcher(value).find() && !CS_MARKER.matcher(value).find()) return false;
        if (!matchingCsShape(snapshot.source(), value)) return false;
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
            synchronized (terminalWriteLock) {
                // A different worker may have learned KEEP since these writes were
                // collected. Recheck under the same lock used by the terminal commit.
                values.keySet().removeIf(TranslationCache.this::keepsOriginal);
                provisionalKeys.retainAll(values.keySet());
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
    }

    private static String stripCsMarkers(String text) {
        if (text == null) return null;
        return text.indexOf('\u27E6') < 0 ? text : CS_MARKER.matcher(text).replaceAll("");
    }

    private static boolean hasCsMarkers(String text) {
        return text != null && text.indexOf('\u27E6') >= 0 && CS_MARKER.matcher(text).find();
    }

    private static String styleProjectionKey(TranslationTemplate.Snapshot snapshot) {
        return TextFilter.stripSectionCodes(snapshot.key());
    }

    /**
     * Whether a style projection and the colour-independent semantic row say the SAME
     * thing. {@link TemplateText#tightenCjkSpacing} runs again AFTER marker stripping (not
     * just once inside each row's own {@code Prepared.restore()}) because a ⟦/CSn⟧ tag
     * sitting between a full-width label colon and its trailing space shields that space
     * from collapsing on the STYLED side while the PLAIN side (markers already stripped
     * before its own restore ran) collapses it — see {@code TemplateText#tightenCjkSpacing}'s
     * javadoc for the full mechanism. Re-tightening here is idempotent for every other
     * input (already-tight text has nothing left to collapse), so this only repairs that
     * one false disagreement; it does not change what either row stores or renders.
     */
    private static boolean sameSemanticText(String first, String second) {
        return TemplateText.tightenCjkSpacing(stripStyle(first))
                .equals(TemplateText.tightenCjkSpacing(stripStyle(second)));
    }

    // Pure, and the lookup/request/warm paths each call it on the same lines every frame.
    private static final com.dragonmeow.nyanlex.translate.LruMemo<String> STRIPPED_STYLE =
            new com.dragonmeow.nyanlex.translate.LruMemo<>(4096);

    private static String stripStyle(String text) {
        return STRIPPED_STYLE.get(text == null ? "" : text, TranslationCache::stripStyleUncached);
    }

    private static String stripStyleUncached(String text) {
        String withoutMarkers = stripCsMarkers(text);
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

    /** Starts the AI supplement of a provisional family when it is due; {@code true} when a
     *  supplement task was handed to a worker by this call. */
    private boolean retryProvisional(String candidate) {
        // Cache-only mode: a provisional hit is simply displayed; no supplement starts.
        if (!requestsAllowed()) return false;
        if (PEEKING.get() != null) return false; // peekFinal: read-only, never wakes the engine
        String semanticKey = provisionalSemanticKey(candidate);
        if (keepsOriginal(semanticKey)) return false;
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
            return false;
        }
        provisionalRetryAttempts.merge(semanticKey, 1, Integer::sum);
        trimMap(provisionalRetryAttempts, MAX_TRACKED_RETRY_STATES);

        TranslationTemplate.Snapshot snapshot = templates.prepare(semanticKey);
        long expectedGeneration = generation.get();
        long expectedRevision = keyRevision(snapshot.key());
        Runnable task = () -> {
            long debugId = 0L;
            try {
                if (!current(snapshot.key(), expectedGeneration, expectedRevision)) return;
                if (!requestsAllowed() || keepsOriginalFamily(snapshot)) return;
                debugId = debugSubmitted(List.of(snapshot.key()));
                TranslationResult result;
                BooleanSupplier previousGate = RequestGate.bind(requestGateFor(List.of(snapshot)));
                try {
                    result = translator.translate(snapshot.key(), targetLang);
                } finally {
                    RequestGate.restore(previousGate);
                }
                boolean usableResult = usable(snapshot.key(), result.translatedText());
                boolean isCurrent = current(snapshot.key(), expectedGeneration, expectedRevision);
                boolean identity = !usableResult
                        && isIdentityEcho(snapshot.key(), result.translatedText());
                boolean kept = isCurrent && (keepsOriginalFamily(snapshot) || identity
                        && learnKeepOriginal(snapshot, result.translatedText()));
                if (!kept && !result.fromFallback() && usableResult) {
                    if (isCurrent) {
                        store(snapshot, result.translatedText(), false);
                        failedUntil.remove(semanticKey);
                        failedUntil.remove(snapshot.key());
                        provisionalRetryAttempts.remove(semanticKey);
                        provisionalRetryAttempts.remove(snapshot.key());
                        reviewParagraphOnce(snapshot, result, null, expectedGeneration, expectedRevision);
                    }
                } else if (isCurrent && !kept && !identity) {
                    fail(snapshot.key());
                }
                debugCompleted(debugId, kept ? snapshot.key() : usableResult ? result.translatedText() : null,
                        kept ? TranslationDebugLog.Status.KEEP_ORIGINAL
                                : !usableResult ? TranslationDebugLog.Status.FAILED
                                : result.fromFallback() ? TranslationDebugLog.Status.FALLBACK
                                : TranslationDebugLog.Status.SUCCESS,
                        !usableResult && !kept
                                ? failureReasonFor(snapshot.key(), result) : null);
            } catch (RequestsPausedException paused) {
                debugDiscarded(debugId);
            } catch (TranslationException | RuntimeException e) {
                if (current(snapshot.key(), expectedGeneration, expectedRevision)) fail(snapshot.key());
                debugCompleted(debugId, TranslationDebugLog.failureFor(e));
            } finally {
                provisionalRetrying.remove(semanticKey);
            }
        };
        if (executeHigh(task)) return true;
        provisionalRetrying.remove(semanticKey);
        return false;
    }

    private void fail(String key) {
        if (key == null) return;
        TranslationTemplate.Snapshot snapshot = templates.prepare(key);
        if (keepsOriginalFamily(snapshot)) return;
        if (hasFinalSemantic(snapshot)) {
            if (hasCsMarkers(snapshot.key())) failStyleProjection(snapshot);
            return;
        }
        String stateKey = provisionalSemanticKey(snapshot.key());
        // Identity confirmation means consecutive successful echoes. Any transport or
        // malformed-content failure breaks that streak.
        contentFailures.remove(stateKey);
        int attempt = contentRetryAttempts.merge(stateKey, 1, Integer::sum);
        trimRetryStateMaps();
        failTemporarily(stateKey, attempt);
        rememberRetrySnapshot(stateKey, snapshot);
        requestFallback(snapshot);
    }

    private boolean executeHigh(Runnable task) {
        if (executor instanceof PriorityTranslationExecutor priority) {
            return priority.tryExecuteHigh(task);
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private boolean executeLow(Runnable task) {
        if (executor instanceof PriorityTranslationExecutor priority) {
            return priority.tryExecuteLow(task);
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void failStyleProjection(TranslationTemplate.Snapshot snapshot) {
        failStyleProjection(snapshot, null);
    }

    private void failStyleProjection(TranslationTemplate.Snapshot snapshot, String reason) {
        synchronized (terminalWriteLock) {
            if (keepsOriginalFamily(snapshot)) return;
            String stateKey = styleFailureKey(snapshot);
            contentFailures.remove(stateKey);
            int attempt = contentRetryAttempts.merge(stateKey, 1, Integer::sum);
            trimRetryStateMaps();
            failTemporarilyState(stateKey, attempt, reason);
        }
        // Presentation-only debt is deliberately passive. Automatically retaining this
        // snapshot made flushBatch() rebuy the same CS topology forever when a provider
        // consistently dropped markers, even though the translated wording was cached.
    }

    /**
     * Exponential retry backoff for temporary failures (damaged shapes, empty answers,
     * fallback-only supplements, transport errors on retry paths). The delay doubles
     * per attempt but is clamped to {@link #MAX_FAILURE_BACKOFF_MS}: 429/5xx windows
     * usually clear quickly, so a few hovers later the line must translate. When a
     * failure ledger is configured the mark is persisted with its attempt and expiry.
     */
    private void failTemporarily(String key, int attempt) {
        failTemporarily(key, attempt, null);
    }

    private void failTemporarily(String key, int attempt, String reason) {
        failTemporarilyState(provisionalSemanticKey(key), attempt, reason);
    }

    private void failTemporarilyState(String stateKey, int attempt) {
        failTemporarilyState(stateKey, attempt, null);
    }

    /**
     * P1.1: the persisted mark optionally carries {@link #failureReasonFor}'s
     * classification after a THIRD colon — {@code temporary:<attempt>:<until>:<reason>}.
     * This rides after {@code until} rather than before it, and {@link
     * #restoreTemporaryFailure} splits with a bounded limit, so an older two-part row
     * ({@code temporary:<attempt>:<until>}, with no reason) still parses unchanged: this
     * is purely additive, never a format break.
     */
    private void failTemporarilyState(String stateKey, int attempt, String reason) {
        synchronized (terminalWriteLock) {
            if (keepsOriginal(stateKey)) return;
            writeTemporaryFailureState(stateKey, attempt, reason);
        }
    }

    /** Caller serializes this ledger update with terminal decisions. */
    private void writeTemporaryFailureState(String stateKey, int attempt, String reason) {
        long now = clock.getAsLong();
        String suffix = failureReasonSuffix(reason);
        if (failureBackoffMs <= 0L) {
            failedUntil.remove(stateKey);
            PersistentStore failures = failureStore;
            if (failures != null) {
                failures.put(stateKey, FAILURE_TEMPORARY_PREFIX + attempt + ":" + now + suffix);
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
        trimMap(failedUntil, MAX_TRACKED_RETRY_STATES);
        PersistentStore failures = failureStore;
        if (failures != null) {
            failures.put(stateKey, FAILURE_TEMPORARY_PREFIX + attempt + ":" + until + suffix);
        }
    }

    /** Empty when there is no reason to record; otherwise a leading colon plus a
     *  single-line, colon-free, length-capped reason so the ledger row's own
     *  {@code attempt:until} fields are never ambiguous to parse. */
    private static String failureReasonSuffix(String reason) {
        if (reason == null) return "";
        String cleaned = reason.strip().replace(':', ';').replace('\n', ' ').replace('\r', ' ');
        if (cleaned.isEmpty()) return "";
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return ":" + cleaned;
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
    private boolean handleUnusableContent(TranslationTemplate.Snapshot snapshot,
                                          TranslationResult result) {
        if (keepsOriginalFamily(snapshot)) return true;
        String translated = result == null ? null : result.translatedText();
        // The semantic AI wording may already be final while a presentation-only CS
        // projection keeps losing its markers. Such an identity can never mean the
        // sentence itself should be kept original; retry the projection as temporary.
        if (hasFinalSemantic(snapshot)) {
            if (hasCsMarkers(snapshot.key())) {
                failStyleProjection(snapshot, failureReasonFor(snapshot.key(), result));
            }
            return false;
        }
        if (isIdentityEcho(snapshot.key(), translated)) {
            if (learnKeepOriginal(snapshot, translated)) return true;
            return false;
        }
        String stateKey = contentFailureKey(snapshot);
        contentFailures.remove(stateKey);
        int attempt = contentRetryAttempts.merge(stateKey, 1, Integer::sum);
        trimRetryStateMaps();
        failTemporarily(stateKey, attempt, failureReasonFor(snapshot.key(), result));
        rememberRetrySnapshot(stateKey, snapshot);
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
            Set<String> cleared = new java.util.LinkedHashSet<>();
            cleared.add(snapshot.key());
            cleared.add(failureKey);
            if (styleFailure != null) cleared.add(styleFailure);
            failures.removeBatch(cleared);
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
        boolean kept;
        synchronized (terminalWriteLock) {
            kept = recordIdentityEcho(snapshot, failureKey);
        }
        // Either action may invoke user callbacks or schedule another backend. Keep
        // both outside the lock that protects short ledger/persistence operations.
        if (kept) notifyFinalWaiters(failureKey);
        else requestFallback(snapshot);
        return kept;
    }

    private boolean recordIdentityEcho(TranslationTemplate.Snapshot snapshot, String failureKey) {
        if (keepsOriginal(failureKey)) return true;
        // A valid provider response breaks the malformed/transport-failure streak even
        // when its identity still needs two more confirmations.
        contentRetryAttempts.remove(failureKey);
        int count = contentFailures.merge(failureKey, 1, Integer::sum);
        trimRetryStateMaps();
        if (count < CONTENT_FAILURE_LIMIT) {
            long delay = failureBackoffMs <= 0L ? 0L : Math.min(
                    MAX_FAILURE_BACKOFF_MS, failureBackoffMs * (1L << Math.min(6, count - 1)));
            long now = clock.getAsLong();
            long until = delay >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
            if (delay > 0L) {
                failedUntil.put(failureKey, until);
                trimMap(failedUntil, MAX_TRACKED_RETRY_STATES);
            }
            PersistentStore failures = failureStore;
            if (failures != null) {
                failures.put(failureKey, FAILURE_IDENTITY_PREFIX + count + ":" + until);
            }
            rememberRetrySnapshot(failureKey, snapshot);
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
        return true;
    }

    private boolean backingOff(String key) {
        return backingOffState(provisionalSemanticKey(key));
    }

    /**
     * 2026-10 hard safety net: {@code true} as long as sending {@code key} now would be
     * at most the {@link #GLOBAL_SEND_FUSE_MAX_SENDS}-th actual dispatch of this EXACT
     * key within the trailing {@link #GLOBAL_SEND_FUSE_WINDOW_MS}; records this send and
     * returns {@code true} when it allows it, leaves the ledger untouched and returns
     * {@code false} (logging once) when it does not. This is deliberately independent of
     * {@link #failTemporarily}'s own exponential backoff -- it is the backstop for that
     * mechanism itself (a misconfigured {@code failureBackoffMs == 0}, a future bug that
     * clears {@link #failedUntil} too eagerly, a key that keeps getting offered under
     * slightly different request-key spellings, …): however the ordinary backoff is
     * configured or behaves, this cache can never dispatch the same exact text a third
     * time in ten minutes. On a trip, the key is also pushed into the ordinary backoff
     * ledger (capped attempt) so it is not re-evaluated every frame while the fuse window
     * is still open.
     */
    private boolean globalSendFuseAllows(String key) {
        if (key == null) return true;
        long now = clock.getAsLong();
        long[] record = globalSendFuse.computeIfAbsent(key,
                ignored -> emptySendFuseRecord());
        synchronized (record) {
            int live = 0;
            for (long ts : record) {
                if (ts != EMPTY_SEND_SLOT && now - ts < GLOBAL_SEND_FUSE_WINDOW_MS) live++;
            }
            if (live >= GLOBAL_SEND_FUSE_MAX_SENDS) {
                infoLog.accept("[nyanlex] global send fuse tripped: refusing to send \""
                        + previewForLog(key) + "\" a " + (live + 1)
                        + "th time within " + (GLOBAL_SEND_FUSE_WINDOW_MS / 60_000L)
                        + " minutes; backing off");
                int attempt = contentRetryAttempts.merge(key, 1, Integer::sum);
                trimRetryStateMaps();
                failTemporarily(key, attempt, "global send fuse tripped");
                return false;
            }
            System.arraycopy(record, 1, record, 0, record.length - 1);
            record[record.length - 1] = now;
            return true;
        }
    }

    /** Sentinel for "this slot has never recorded a send" — NOT {@code 0L}: a test (or,
     *  in principle, an injected clock starting at the epoch) can legitimately send at
     *  {@code now == 0}, which must not be silently mistaken for an empty slot. */
    private static final long EMPTY_SEND_SLOT = Long.MIN_VALUE;

    private static long[] emptySendFuseRecord() {
        long[] record = new long[GLOBAL_SEND_FUSE_MAX_SENDS];
        java.util.Arrays.fill(record, EMPTY_SEND_SLOT);
        return record;
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
        // Limit 3, not 2: an older row has exactly "attempt:until" and parts[1] is still
        // the whole (correct) "until" field either way; a newer temporary row may carry
        // ":<reason>" after it (failTemporarilyState), which must not be swallowed into
        // the Long.parseLong(parts[1]) call below.
        String[] parts = row.substring(prefix.length()).split(":", 3);
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
            trimRetryStateMaps();
            if (!key.startsWith(STYLE_FAILURE_PREFIX)) {
                TranslationTemplate.Snapshot restored = templates.prepare(key);
                if (sessionRetryDemanded(key, restored) && !keepsOriginalFamily(restored)) {
                    // Hydration may run again immediately after a backoff expires. Do
                    // not replace this session's styled retry with a second plain one.
                    retrySnapshots.putIfAbsent(key, restored);
                    trimMap(retrySnapshots, MAX_TRACKED_RETRY_STATES);
                }
            }
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
        boolean activeRetry = sessionRetryDemanded(snapshot.key(), snapshot);
        if (!activeRetry) {
            // Item/tooltip warmups finish their already-observed fallback once, but a
            // failed lower tier remains passive after the surface disappears.
            lower.warmBatchAsync(List.of(snapshot.source()));
        } else if (hasCsMarkers(snapshot.key())) {
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

    /** Re-enqueue only semantic/content failures explicitly demanded during this
     * process. Presentation-only CS debts are passive: their semantic fallback is
     * already displayable, so only another observation/manual retry may rebuy them. */
    private void enqueueDueRetries() {
        // retrySnapshots is bounded and populated only by session-demanded failures.
        // Walking it makes the common no-failure tick O(1), rather than copying and
        // scanning every successful key observed earlier in the session.
        // perf ②: while the master switch is off nothing is retried, so every backoff runs
        // out together; reopening it must not pour up to 512 retries into one tick. At most
        // MAX_DUE_RETRIES_PER_TICK are started per tick; an entry already collected or in
        // flight is skipped without counting, so the following ticks reach the rest.
        int started = 0;
        for (Map.Entry<String, TranslationTemplate.Snapshot> retry
                : retrySnapshots.entrySet()) {
            if (started >= MAX_DUE_RETRIES_PER_TICK) return;
            String stateKey = retry.getKey();
            TranslationTemplate.Snapshot snapshot = retry.getValue();
            // Demand is LRU-bounded independently. A dormant snapshot remains available
            // for a later observation but must not heal after its surface disappeared.
            if (!sessionRetryDemanded(stateKey, snapshot)) continue;
            if (stateKey.startsWith(STYLE_FAILURE_PREFIX)) {
                // Defensive cleanup for a snapshot created before style debts became
                // passive; retain its ledger/backoff row for the next real observation.
                retrySnapshots.remove(stateKey, snapshot);
                continue;
            }
            if (snapshot == null || keepsOriginal(stateKey)) {
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
                if (retryProvisional(stateKey)) started++;
                continue;
            }
            if (backingOffState(stateKey) || flights.containsKey(snapshot.key())
                    || provisionalRetrying.contains(stateKey)) continue;
            synchronized (queueLock) {
                if (!queue.containsKey(snapshot.key())) {
                    enqueue(snapshot, null);
                    started++;
                }
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

    /** A submitted request that was abandoned unsent: neither failed nor still waiting. */
    private void debugDiscarded(long requestId) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.discard(requestId);
    }

    private void debugCompleted(long requestId, boolean success) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, success);
    }

    private void debugCompleted(long requestId, TranslationDebugLog.Status status) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, status);
    }

    private void debugCompleted(long requestId, TranslationDebugLog.Failure failure) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, failure);
    }

    private void debugCompleted(long requestId, String translation,
                                TranslationDebugLog.Status status) {
        debugCompleted(requestId, translation, status, null);
    }

    private void debugCompleted(long requestId, String translation,
                                TranslationDebugLog.Status status, String failureReason) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId,
                translation == null ? List.of() : List.of(translation), List.of(status),
                failureReason == null ? List.of() : List.of(failureReason));
    }

    private void debugCompleted(long requestId, List<String> translations,
                                List<TranslationDebugLog.Status> statuses) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, translations, statuses);
    }

    private void debugCompleted(long requestId, List<String> translations,
                                List<TranslationDebugLog.Status> statuses,
                                List<String> failureReasons) {
        TranslationDebugLog log = debugLog;
        if (log != null) log.completed(requestId, translations, statuses, failureReasons);
    }

    // -------------------------------------------------------------------------
    // Flight completion and callbacks
    // -------------------------------------------------------------------------

    private boolean tryAcquirePendingEntry() {
        while (true) {
            int current = pendingEntries.get();
            if (current >= MAX_QUEUED_ENTRIES) return false;
            if (pendingEntries.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releasePendingEntry() {
        pendingEntries.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    private void finishFlight(String key, Flight flight) {
        if (!flights.remove(key, flight)) return;
        releasePendingEntry();
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
        private enum AddResult { ADDED, CLOSED, FULL }

        private final List<Callback> callbacks = new ArrayList<>();
        private boolean closed;
        /** Started by the item warm-up lane (or its fallout); never counts as interactive work. */
        volatile boolean warm;

        synchronized AddResult add(Callback callback) {
            if (closed) return AddResult.CLOSED;
            if (callback != null && callback.consumer != null) {
                if (callbacks.size() >= MAX_CALLBACKS_PER_KEY) return AddResult.FULL;
                callbacks.add(callback);
            }
            return AddResult.ADDED;
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
        /** First collection time; a windowed collector measures its window from the oldest. */
        final long enqueuedAtMs;
        final List<Callback> callbacks = new ArrayList<>();
        List<String> surfaceContext;
        boolean conflictingContext;
        boolean highPriority;
        /** Queued from the item warm-up lane's worker thread (fallout of a warm request). */
        boolean background;

        Queued(TranslationTemplate.Snapshot snapshot, long enqueuedAtMs) {
            this.snapshot = snapshot;
            this.enqueuedAtMs = enqueuedAtMs;
        }

        void mergeContext(List<String> context) {
            if (context == null || conflictingContext) return;
            if (surfaceContext == null) surfaceContext = List.copyOf(context);
            else if (!surfaceContext.equals(context)) {
                surfaceContext = null;
                conflictingContext = true;
            }
        }
    }

    private static List<String> commonContext(List<Queued> items) {
        List<String> common = null;
        for (Queued item : items) {
            if (item.surfaceContext == null) return null;
            if (common == null) common = item.surfaceContext;
            else if (!common.equals(item.surfaceContext)) return null;
        }
        return common;
    }

    // -------------------------------------------------------------------------
    // Validation and administration
    // -------------------------------------------------------------------------

    /** Final, validated rows from the active language/provider only. */
    public Map<String, String> exportTranslations() {
        Map<String, String> rows = new LinkedHashMap<>();
        if (store != null) rows.putAll(store.entries());
        synchronized (memory) {
            for (Map.Entry<String, String> row : memory.entrySet()) {
                // A session copy derived from a projection is not exported; its projection is.
                if (!derivedSemanticRows.contains(row.getKey())) rows.put(row.getKey(), row.getValue());
            }
        }
        rows.entrySet().removeIf(e -> provisional(e.getKey())
                || KEEP_ORIGINAL.equals(e.getValue()) || LEGACY_KEEP_ORIGINAL.equals(e.getValue())
                || !usableForBulkTransfer(e.getKey(), e.getValue()));
        return rows;
    }

    /** Merge into the active cache without network requests or overwriting final wording. */
    public int importTranslations(Map<String, String> rows) {
        WriteBatch writes = new WriteBatch();
        int imported = 0;
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String key = row.getKey(), value = row.getValue();
            if (!usableForBulkTransfer(key, value) || keepsOriginal(key) || hasFinalValue(key, writes)) continue;
            clearFailureState(templates.prepare(key));
            write(key, value, false, writes);
            imported++;
        }
        writes.flush();
        return imported;
    }

    /** Same contract as {@link #importTranslations}, dispatched onto this cache's own
     *  background executor instead of running on the calling thread. For a caller that
     *  must never perform the write itself — the lazy legacy-tooltip-segment-cache
     *  conversion in {@code TranslationService} runs its (cheap, CPU-only) row alignment
     *  synchronously on whatever thread reaches it (which may be a render thread), but the
     *  actual {@link PersistentStore} write always goes through here instead. Best-effort:
     *  silently drops the batch if the executor is saturated/rejecting, exactly like every
     *  other low-priority background task this cache schedules (see {@link #executeLow}) —
     *  acceptable because every row this writes is itself only an opportunistic, bounded
     *  speed-up over requesting the unit fresh, never the only copy of anything. */
    public void importTranslationsAsync(Map<String, String> rows) {
        if (rows == null || rows.isEmpty()) return;
        Map<String, String> copy = Map.copyOf(rows);
        executeLow(() -> importTranslations(copy));
    }

    private static boolean usable(String translated) {
        return translated != null && !translated.isEmpty()
                && !TextFilter.isLikelyMojibake(TextFilter.stripDecorativeSymbols(translated));
    }

    private static boolean usable(String source, String translated) {
        return usable(translated)
                && !translated.equals(source)
                && !translated.trim().equals(source == null ? "" : source.trim())
                && !TextFilter.hasReshapedProtocolToken(translated)
                && newlineCount(source) == newlineCount(translated)
                && matchingCsShape(source, translated)
                && matchingMtShape(source, translated)
                && ((matchingParagraphBreakShape(source, translated)
                        && matchingParagraphSlotShape(source, translated))
                    || (ParagraphModel.canReflowBreakLoss(source, translated)
                        && matchingProtectedPlaceholderShape(source, translated)))
                && TranslationTemplate.layoutSkeletonMatches(source, translated)
                && TranslationTemplate.styleSlotShapeMatches(source, translated)
                && !TextFilter.isPartialTransliteration(source, translated)
                && !TextFilter.hasUntranslatedAnchoredField(source, translated);
    }

    /** B8: a masked player name / do-not-translate term ({@code ⟦n⟧}) must survive in
     * exactly the same multiplicity, per index. Unlike an MT value slot, a protected
     * placeholder can never legitimately be dropped, duplicated or merged by a
     * translator.
     *
     * <p>Deliberately NOT folded into the general {@link #usable(String, String)} used
     * by every read/write path: {@code usable()} also gates (a) a freshly-received
     * live translator response (submitSingle/flushBatch/translateBlocking, via {@link
     * #handleUnusableContent}) and (b) a disk-read hydration of an existing CS-styled
     * "projection" row ({@link #lookupStyleProjection}, which already re-validates a
     * projection more leniently on its own on purpose — see its own comment). Both of
     * those already have their OWN established, tested handling of a name/term that a
     * translator dropped: a live response is caught downstream by {@code
     * TranslationService.decide()}'s R17 check ({@code protectedTermsSurvive} /
     * {@code invalidateMangledOnce}), which deletes it and lets the very next
     * observation retry un-throttled — exactly once per distinct original text. Adding
     * this check to the shared {@code usable()} would instead make {@link
     * #handleUnusableContent} classify that same response as a genuine content failure
     * and route it through the exponential backoff ledger, silencing that existing
     * self-heal for up to the configured backoff window (confirmed against
     * DoNotTranslateTermsTest's and TranslationServiceTest's "self-heals once" tests,
     * and ProjectionSemanticRebuildTest's "projection displayable since 1.0.7" tests).
     * This check is instead applied explicitly at the two batch paths the B8 finding
     * actually names — {@link #importTranslations} and {@link #exportTranslations} —
     * neither of which goes through a live translator response or the projection
     * display fallback. */
    private static boolean matchingProtectedPlaceholderShape(String source, String translated) {
        return tokenMultiset(source, PROTECTED_PLACEHOLDER)
                .equals(tokenMultiset(translated, PROTECTED_PLACEHOLDER));
    }

    /** {@link #usable(String, String)} plus the B8 {@code ⟦n⟧} multiset check (see
     *  {@link #matchingProtectedPlaceholderShape}) and its {@code styleSlotShape}
     *  region-tracking counterpart (S3). Used only by {@link #importTranslations} and
     *  {@link #exportTranslations}: a bulk write/read that never passed through a fresh
     *  translator response or the projection-tolerant display path, so there is no
     *  other safety net if it silently drops a masked name into a shared template. */
    public static boolean usableForBulkTransfer(String source, String translated) {
        return usable(source, translated)
                && matchingParagraphBreakShape(source, translated)
                && matchingParagraphSlotShape(source, translated)
                && matchingProtectedPlaceholderShape(source, translated)
                && TranslationTemplate.styleSlotShapeMatches(source, translated, true);
    }

    /** Prefer the backend's precise parser diagnosis, then derive the broad protocol
     * category from the same shape checks that rejected the cache value. */
    private static String failureReasonFor(String source, TranslationResult result) {
        if (result != null && result.failureReason() != null
                && !result.failureReason().isBlank()) {
            return result.failureReason().strip();
        }
        String translated = result == null ? null : result.translatedText();
        if (translated == null || translated.isBlank()) return "empty response";
        if (newlineCount(source) != newlineCount(translated)
                || !matchingParagraphBreakShape(source, translated)
                || !matchingParagraphSlotShape(source, translated)) {
            return "paragraph lost";
        }
        if (!matchingCsShape(source, translated)
                || !matchingMtShape(source, translated)
                || !TranslationTemplate.layoutSkeletonMatches(source, translated)
                || !TranslationTemplate.styleSlotShapeMatches(source, translated)
                || TextFilter.isLikelyMojibake(TextFilter.stripDecorativeSymbols(translated))
                || TextFilter.hasReshapedProtocolToken(translated)) {
            return "format/token lost";
        }
        if (translated.equals(source) || translated.trim().equals(source == null ? "" : source.trim())) {
            return DebugErrorLog.UNCHANGED_ECHO_REASON;
        }
        if (TextFilter.isPartialTransliteration(source, translated)
                || TextFilter.hasUntranslatedAnchoredField(source, translated)) {
            return "mixed-script (Latin residue in Chinese)";
        }
        return "unknown";
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
            String between = text.substring(cursor, matcher.start());
            if (CS_RESIDUE.matcher(between).find()) return null;
            if (open == null && !outsideCsIsLayoutOnly(between)) return null;
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
        String tail = text.substring(cursor);
        if (CS_RESIDUE.matcher(tail).find()) return null;
        if (open == null && !outsideCsIsLayoutOnly(tail)) return null;
        if (open != null) return null;
        return pairs.toString();
    }

    /** A valid rich projection keeps every semantic character inside a CS pair. */
    private static boolean outsideCsIsLayoutOnly(String text) {
        if (text == null || text.isEmpty()) return true;
        String withoutSlots = NON_CS_SLOT.matcher(TextFilter.stripSectionCodes(text)).replaceAll("");
        return TextFilter.isLayoutOrPunctuationOnly(withoutSlots);
    }

    private void removeStored(String key) {
        if (key == null) return;
        synchronized (memory) {
            memory.remove(key);
            derivedSemanticRows.remove(key);
        }
        provisional.remove(key);
        if (store != null) store.remove(key);
        // provisionalStore is the independently owned GT backup, not scratch space.
        // TranslationService.invalidateBoth asks the GT cache owner to remove it when
        // the user explicitly retranslates; an AI cleanup must never delete valid GT data.
    }

    private void removeStoredBatch(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        synchronized (memory) {
            for (String key : keys) {
                if (key == null) continue;
                memory.remove(key);
                derivedSemanticRows.remove(key);
                provisional.remove(key);
            }
        }
        if (store != null) store.removeBatch(keys);
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

    /** Switch a live backend/store partition without deleting any provider cache file. */
    public void reloadProviderPartition() {
        reset(false);
        migrateProvisionalRows();
        hydrateFailureStore();
    }

    /** Drop only session state; optionally delete the active language's disk file. */
    private void reset(boolean clearStore) {
        generation.incrementAndGet();
        keyRevisions.clear();
        memory.clear();
        failedUntil.clear();
        globalSendFuse.clear();
        contentFailures.clear();
        contentRetryAttempts.clear();
        provisional.clear();
        provisionalRetrying.clear();
        provisionalRetryAttempts.clear();
        retrySnapshots.clear();
        finalWaiters.clear();
        pendingSemanticRebuilds.clear();
        unrebuildableProjections.clear();
        derivedSemanticRows.clear();
        synchronized (retryDemandLock) {
            sessionRetryDemand.clear();
        }
        List<Callback> cancelled = new ArrayList<>();
        for (Map.Entry<String, Flight> entry : flights.entrySet()) {
            if (flights.remove(entry.getKey(), entry.getValue())) {
                releasePendingEntry();
                cancelled.addAll(entry.getValue().close());
            }
        }
        synchronized (queueLock) {
            for (Queued queued : queue.values()) {
                releasePendingEntry();
                cancelled.addAll(queued.callbacks);
            }
            queue.clear();
            queueGrew = false;
            settleTicks = 0;
            queueStartedAtMs = -1L;
            queuedChars = 0;
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
            recordKeyRevision(key);
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
            globalSendFuse.remove(key);
            pendingSemanticRebuilds.remove(key);
            unrebuildableProjections.remove(key);
            Flight flight = flights.remove(key);
            if (flight != null) {
                releasePendingEntry();
                cancelled.addAll(flight.close());
            }
        }
        if (failures != null) failures.removeBatch(keys);
        removeStoredBatch(keys);
        synchronized (retryDemandLock) {
            sessionRetryDemand.removeAll(keys);
        }
        synchronized (queueLock) {
            for (String key : keys) {
                Queued removed = queue.remove(key);
                if (removed != null) {
                    releasePendingEntry();
                    cancelled.addAll(removed.callbacks);
                }
            }
            queueGrew = !queue.isEmpty();
            queuedChars = 0;
            for (Queued queued : queue.values()) queuedChars += batchChars(queued.snapshot.key());
            if (queue.isEmpty()) {
                settleTicks = 0;
                queueStartedAtMs = -1L;
            }
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

    /**
     * Whether the wording this cache holds for {@code source} comes only from a colour
     * projection: its semantic row is the session copy rebuilt from a projection, or, for a
     * colour-marked line, it has no semantic row but its own final projection (such a line is
     * displayed from that projection alone). Before 1.0.7 such wording was never displayed or
     * compared, so consumers that would buy follow-up requests from it (item-name
     * reconciliation, the mangled-name self-heal) leave it alone. Wording from anywhere else,
     * a lower-tier read-through included, is not projection-only.
     */
    public boolean hasProjectionOnlyWording(String source) {
        if (source == null) return false;
        TranslationTemplate.Snapshot plain = templates.prepare(stripStyle(source));
        if (derivedRow(plain)) return true;
        TranslationTemplate.Snapshot snapshot = templates.prepare(source);
        return hasCsMarkers(snapshot.key()) && lookupSnapshot(plain, this) == null
                && ownFinalProjection(snapshot) != null;
    }

    public int pendingCount() {
        return pendingEntries.get();
    }

    public int size() {
        // The in-memory map is deliberately an LRU and may contain only the most
        // recent few thousand rows. UI totals describe the active durable cache,
        // while max preserves newly accepted rows for non-persistent test/embed stores.
        return store == null ? memory.size() : Math.max(memory.size(), store.size());
    }
}
