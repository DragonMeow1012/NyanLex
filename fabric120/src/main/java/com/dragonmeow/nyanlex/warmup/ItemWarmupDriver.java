package com.dragonmeow.nyanlex.warmup;

import com.dragonmeow.nyanlex.cache.BatchBudget;
import com.dragonmeow.nyanlex.config.TranslatorConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Tick-driven all-item warm-up. The loader calls {@link #tick()} once per client tick.
 *
 * <p><b>Batches.</b> Items are packed into a request until the shared request character
 * budget is full ({@link BatchBudget#WINDOWED_CHARS}, the very budget of a screen-capture
 * request, and the same packing rule). An item is atomic: one that does not fit starts the
 * next batch, and an item larger than the whole budget travels alone. {@value
 * #MAX_ITEMS_PER_BATCH} items is only a safety net.</p>
 *
 * <p><b>Pacing.</b> Warm-up has its own pace, independent of the interactive request
 * cooldown: at least {@code itemWarmupChunkDelayMs} between two dispatches and an adaptive
 * number of requests in flight: it starts at 1, grows by one after {@value #RAMP_SUCCESSES}
 * consecutive successful requests up to {@value #MAX_CONCURRENCY}, and drops back to 1 the
 * moment the shared 429 gate closes. ChatGPT (Codex) sign-in is fixed at 1.</p>
 *
 * <p><b>Yielding.</b> While an interactive translation (chat, tooltip, key press) is queued
 * or in flight nothing new is dispatched; a guard lets one request through after
 * {@value #YIELD_CAP_MS} ms of uninterrupted yielding so a busy surface cannot starve the
 * run forever.</p>
 *
 * <p><b>Only the player starts it.</b> A run begins when the player presses Start or Continue
 * ({@link #start}) and never by itself: not at launch, not when a world loads, not after the
 * previous run ended. Before every dispatch the run re-checks the master switch, the engine, the
 * source and the shared 429 gate; any of them failing pauses it. Only a 429 pause lifts by itself
 * when the gate reopens (the same run the player started); every other pause, the player's own
 * included, waits for the player to press Continue ({@link #resume}).
 * Progress is "already cached": a later Start or Continue skips what is done without any cursor.
 * Where the last run stopped is kept in the config ({@code itemWarmupLast*}) so the card can offer
 * "Continue".</p>
 */
public final class ItemWarmupDriver {
    /** Safety net only: the character budget decides the batch size. */
    public static final int MAX_ITEMS_PER_BATCH = 200;
    public static final int MAX_CONCURRENCY = 3;
    public static final int RAMP_SUCCESSES = 2;
    public static final long PENDING_WAIT_MS = 120_000L;
    public static final long YIELD_CAP_MS = 90_000L;
    public static final int PROBE_BUDGET_PER_TICK = 40;
    /** Items asked of the source per probe call (the rest of the tick budget loops). */
    private static final int PROBE_STEP = 10;
    /** Span of the throughput window. */
    private static final long RATE_WINDOW_MS = 180_000L;
    private static final long RATE_MIN_SPAN_MS = 10_000L;

    public enum State { IDLE, RUNNING, PAUSED, DONE, STOPPED }

    public enum PauseReason { NONE, USER, NO_WORLD, RATE_LIMITED, REQUESTS_OFF, ENGINE }

    public interface Listener {
        void onChanged(ItemWarmupDriver driver);
    }

    /**
     * @param scanned          items looked at (cached, native, failed and sent alike)
     * @param submittedItems   items sent to the AI so far
     * @param translatedItems  sent items whose request has finished with every unit stored
     * @param failedItems      sent items whose request finished without a stored result
     *                         (tried again by the next run)
     * @param skippedCached    items already stored from an earlier run
     * @param skippedNative    items that need no translation (already in the target language)
     * @param skippedFailed    items whose tooltip could not be built
     * @param sessionLimit     per-launch cap, {@code 0} = unlimited
     * @param inflightRequests requests currently sent and unanswered
     * @param concurrency      requests the driver is allowed to keep in flight right now
     * @param yielding         dispatch is held back for an interactive translation
     * @param itemsPerMinute   recent throughput of finished items, {@code 0} while unknown
     * @param etaMinutes       estimated minutes left, {@code -1} while unknown
     * @param lastScanned      where the last run stopped (items looked at), from the config
     * @param lastTotal        items in the registry at that time
     * @param resumable        no run is active and the last one did not finish: the card offers Continue
     * @param needsWorldItems  items skipped only because the run had no world (translate them by
     *                         pressing Start again inside a world), {@code 0} when none
     */
    public record Progress(State state, PauseReason pauseReason, int scanned, int totalItems,
                           int skippedCached, int submittedItems, int sessionLimit,
                           boolean limitReached, int skippedFailed,
                           int translatedItems, int failedItems, int skippedNative,
                           int inflightRequests, int concurrency, boolean yielding,
                           int itemsPerMinute, int etaMinutes,
                           int lastScanned, int lastTotal, boolean resumable, int needsWorldItems) {
    }

    /** One request in flight. */
    private static final class Batch {
        final List<String> units;
        final List<List<String>> itemUnits;
        final long sentAt;

        Batch(List<String> units, List<List<String>> itemUnits, long sentAt) {
            this.units = units;
            this.itemUnits = itemUnits;
            this.sentAt = sentAt;
        }
    }

    private final ItemWarmupSource source;
    private final ItemWarmupBackend backend;
    private final Supplier<TranslatorConfig> config;
    private final LongSupplier clock;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Saves the config (the loader's own save); called at most every {@value #SAVE_EVERY_MS} ms while running. */
    private volatile Runnable progressSaver = () -> { };
    private static final long SAVE_EVERY_MS = 15_000L;
    private long lastSavedAt = Long.MIN_VALUE;
    private boolean startedInWorld = true;

    private State state = State.IDLE;
    private PauseReason pauseReason = PauseReason.NONE;
    private long nextDispatchAt;
    private final List<Batch> inflight = new ArrayList<>();
    private int concurrency = 1;
    private int successStreak;
    private int scanned;
    private int skippedCached;
    private int skippedNative;
    private int skippedFailed;
    private int submitted;
    private int translated;
    private int failedItems;
    private int submittedThisSession;
    private boolean limitReached;
    private long yieldSince = -1L;
    private boolean yielding;

    // Batch under construction (survives across ticks until it is full or the run ends).
    private final ArrayDeque<ItemWarmupTarget> backlog = new ArrayDeque<>();
    private final Set<String> staged = new LinkedHashSet<>();
    private final List<List<String>> stagedItemUnits = new ArrayList<>();
    private BatchBudget budget = BatchBudget.windowed();
    private boolean batchFull;

    // Throughput window: (finish time, items) of recent requests.
    private final ArrayDeque<long[]> finished = new ArrayDeque<>();
    private long rateSince;

    public ItemWarmupDriver(ItemWarmupSource source, ItemWarmupBackend backend,
                            Supplier<TranslatorConfig> config, LongSupplier clock) {
        this.source = source;
        this.backend = backend;
        this.config = config;
        this.clock = clock;
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** The loader's config save, used to keep where the run stopped across launches. */
    public void setProgressSaver(Runnable saver) {
        progressSaver = saver == null ? () -> { } : saver;
    }

    /** Whether {@link #start()} would be accepted right now. */
    public synchronized boolean canStart() {
        TranslatorConfig cfg = config.get();
        return cfg != null && backend.isAiEngine() && state != State.RUNNING && state != State.PAUSED;
    }

    /** Start (or restart) a run the player asked for; see {@link #start(boolean)}. */
    public synchronized boolean start() {
        return start(true);
    }

    /**
     * Start (or restart) a run. This is only ever called by a player action (the Start / Continue
     * button, after the consent box when online translation is off): nothing in the core or the
     * loaders starts a run by itself. A per-launch item budget, if configured, is shared across runs.
     *
     * @param inWorld whether a world is loaded: items whose tooltip cannot be built without one are
     *                skipped and counted, and the card then tells the player to press Start again
     *                inside a world
     */
    public synchronized boolean start(boolean inWorld) {
        if (!canStart()) return false;
        startedInWorld = inWorld;
        source.reset();
        state = State.RUNNING;
        pauseReason = PauseReason.NONE;
        nextDispatchAt = 0L;
        inflight.clear();
        resetStaging();
        backlog.clear();
        concurrency = 1;
        successStreak = 0;
        scanned = 0;
        skippedCached = 0;
        skippedNative = 0;
        skippedFailed = 0;
        submitted = 0;
        translated = 0;
        failedItems = 0;
        limitReached = false;
        yieldSince = -1L;
        yielding = false;
        finished.clear();
        rateSince = clock.getAsLong();
        notifyChanged();
        return true;
    }

    public synchronized void pause() {
        if (state != State.RUNNING) return;
        state = State.PAUSED;
        pauseReason = PauseReason.USER;
        notifyChanged();
    }

    public synchronized void resume() {
        if (state != State.PAUSED) return;
        state = State.RUNNING;
        pauseReason = PauseReason.NONE;
        restartRateWindow();
        notifyChanged();
    }

    public synchronized void stop() {
        if (state != State.RUNNING && state != State.PAUSED) return;
        state = State.STOPPED;
        pauseReason = PauseReason.NONE;
        inflight.clear();
        yielding = false;
        notifyChanged();
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Progress progress() {
        TranslatorConfig cfg = config.get();
        int limit = cfg == null ? 0 : Math.max(0, cfg.itemWarmupMaxItemsPerSession);
        int rate = itemsPerMinute();
        boolean active = state == State.RUNNING || state == State.PAUSED;
        int lastScanned = cfg == null ? 0 : cfg.itemWarmupLastScanned;
        int lastTotal = cfg == null ? 0 : cfg.itemWarmupLastTotal;
        boolean resumable = !active && cfg != null && cfg.itemWarmupLastTotal > 0
                && !cfg.itemWarmupLastFinished;
        int needsWorld = active ? (startedInWorld ? 0 : skippedFailed)
                : cfg != null && cfg.itemWarmupLastNeedsWorld ? cfg.itemWarmupLastSkipped : 0;
        return new Progress(state, pauseReason, scanned, source.totalItemCount(), skippedCached,
                submitted, limit, limitReached, skippedFailed, translated, failedItems,
                skippedNative, inflight.size(), effectiveConcurrency(), yielding, rate,
                etaMinutes(rate), lastScanned, lastTotal, resumable, needsWorld);
    }

    /** One client tick. Cheap when nothing is due. */
    public synchronized void tick() {
        if (state != State.RUNNING && state != State.PAUSED) return;
        TranslatorConfig cfg = config.get();
        if (cfg == null) {
            stop();
            return;
        }
        long now = clock.getAsLong();
        settleFinished(now);
        // A pause lifts by itself only for a 429 (the gate reopening: the same run the player
        // started). The player's own pause, and a pause for any other reason, wait for Continue.
        if (state == State.PAUSED && pauseReason != PauseReason.RATE_LIMITED) return;
        PauseReason blocker = blocker();
        if (blocker != PauseReason.NONE) {
            if (blocker == PauseReason.RATE_LIMITED) {
                // A 429 means the quota is the limit: back to one request at a time.
                concurrency = 1;
                successStreak = 0;
            }
            if (state != State.PAUSED || pauseReason != blocker) {
                state = State.PAUSED;
                pauseReason = blocker;
                notifyChanged();
            }
            return;
        }
        if (state == State.PAUSED) {
            state = State.RUNNING;
            pauseReason = PauseReason.NONE;
            restartRateWindow();
            notifyChanged();
        }
        if (isFinishedRun()) {
            state = State.DONE;
            pauseReason = PauseReason.NONE;
            yielding = false;
            notifyChanged();
            return;
        }
        if (inflight.size() >= effectiveConcurrency()) return;
        if (now < nextDispatchAt) return;
        if (yieldToInteractive(now)) return;
        stageAndDispatch(cfg, now);
    }

    private boolean isFinishedRun() {
        return source.isExhausted() && backlog.isEmpty() && stagedItemUnits.isEmpty()
                && inflight.isEmpty();
    }

    private int effectiveConcurrency() {
        return Math.max(1, Math.min(concurrency, maxConcurrency()));
    }

    private int maxConcurrency() {
        return backend.usesCodex() ? 1 : MAX_CONCURRENCY;
    }

    /** True while an interactive translation holds the run back. */
    private boolean yieldToInteractive(long now) {
        boolean busy = backend.interactiveBusy();
        if (!busy) {
            yieldSince = -1L;
            setYielding(false);
            return false;
        }
        if (yieldSince < 0) yieldSince = now;
        if (now - yieldSince >= YIELD_CAP_MS && inflight.isEmpty()) {
            // Starvation guard: one request goes out, then the wait starts over.
            yieldSince = now;
            setYielding(false);
            return false;
        }
        setYielding(true);
        return true;
    }

    private void setYielding(boolean value) {
        if (yielding != value) {
            yielding = value;
            notifyChanged();
        }
    }

    private PauseReason blocker() {
        if (!backend.requestsEnabled()) return PauseReason.REQUESTS_OFF;
        if (!backend.isAiEngine()) return PauseReason.ENGINE;
        if (!source.isAvailable()) return PauseReason.NO_WORLD;
        if (backend.isRateLimited()) return PauseReason.RATE_LIMITED;
        return PauseReason.NONE;
    }

    /** Close every request that has been answered (or has waited too long) and learn from it. */
    private void settleFinished(long now) {
        if (inflight.isEmpty()) return;
        boolean changed = false;
        for (Iterator<Batch> it = inflight.iterator(); it.hasNext(); ) {
            Batch batch = it.next();
            boolean pending = false;
            for (String unit : batch.units) {
                if (backend.isPending(unit)) {
                    pending = true;
                    break;
                }
            }
            if (pending && now - batch.sentAt < PENDING_WAIT_MS) continue;
            it.remove();
            changed = true;
            int ok = 0;
            for (List<String> unitsOfItem : batch.itemUnits) {
                if (itemStored(unitsOfItem)) ok++;
            }
            translated += ok;
            failedItems += batch.itemUnits.size() - ok;
            finished.addLast(new long[] {now, batch.itemUnits.size()});
            boolean success = ok * 2 >= batch.itemUnits.size() && !backend.isRateLimited();
            if (success) {
                if (++successStreak >= RAMP_SUCCESSES && concurrency < maxConcurrency()) {
                    concurrency++;
                    successStreak = 0;
                }
            } else {
                successStreak = 0;
            }
        }
        if (changed) notifyChanged();
    }

    private boolean itemStored(List<String> units) {
        for (String unit : units) {
            if (!backend.isReady(unit)) return false;
        }
        return true;
    }

    private void stageAndDispatch(TranslatorConfig cfg, long now) {
        int sessionLimit = Math.max(0, cfg.itemWarmupMaxItemsPerSession);
        boolean limited = sessionLimit > 0;
        int probedThisTick = 0;
        // Cached items are skipped cheaply, but tooltips are built on the client thread:
        // never probe more than PROBE_BUDGET_PER_TICK items in one tick. The batch under
        // construction survives across ticks until it is full (or the run ends).
        while (!batchFull) {
            if (limited && submittedThisSession + stagedItemUnits.size() >= sessionLimit) break;
            ItemWarmupTarget target = backlog.pollFirst();
            if (target == null) {
                if (source.isExhausted() || probedThisTick >= PROBE_BUDGET_PER_TICK) break;
                List<ItemWarmupTarget> batch = source.probeNext(
                        Math.min(PROBE_STEP, PROBE_BUDGET_PER_TICK - probedThisTick));
                if (batch.isEmpty()) break;
                probedThisTick += batch.size();
                backlog.addAll(batch);
                continue;
            }
            if (!considerItem(target)) backlog.addFirst(target);
        }
        boolean limitHit = limited && submittedThisSession + stagedItemUnits.size() >= sessionLimit;
        boolean exhausted = source.isExhausted() && backlog.isEmpty();
        boolean ending = exhausted || limitHit;
        boolean dispatched = false;
        if (!stagedItemUnits.isEmpty() && (batchFull || ending)) {
            List<String> sources = new ArrayList<>(staged);
            List<List<String>> items = new ArrayList<>(stagedItemUnits);
            backend.warm(sources);
            inflight.add(new Batch(sources, items, now));
            nextDispatchAt = now + Math.max(0, cfg.itemWarmupChunkDelayMs);
            submitted += items.size();
            submittedThisSession += items.size();
            resetStaging();
            dispatched = true;
        }
        if (ending && stagedItemUnits.isEmpty() && limitHit && !exhausted) {
            limitReached = true;
            if (inflight.isEmpty()) {
                state = State.DONE;
                pauseReason = PauseReason.NONE;
            }
        }
        if (dispatched || state == State.DONE) notifyChanged();
    }

    /**
     * Account for one probed item and stage it when it needs the AI.
     *
     * @return {@code false} when the item does not fit the batch under construction and must
     *         be offered again after that batch left
     */
    private boolean considerItem(ItemWarmupTarget target) {
        if (target.failed()) {
            scanned++;
            skippedFailed++;
            return true;
        }
        List<String> needed = new ArrayList<>();
        boolean anyUnit = false;
        boolean allNative = true;
        for (String unit : target.sources()) {
            if (unit == null || unit.isBlank()) continue;
            anyUnit = true;
            if (!backend.needsNoTranslation(unit)) allNative = false;
            if (!backend.isReady(unit)) needed.add(unit);
        }
        if (needed.isEmpty()) {
            scanned++;
            if (anyUnit && allNative) skippedNative++;
            else skippedCached++;
            return true;
        }
        // A unit shared with an item staged earlier is sent once and costs nothing again.
        List<String> fresh = new ArrayList<>();
        int cost = 0;
        for (String unit : needed) {
            if (!staged.contains(unit)) {
                fresh.add(unit);
                cost += BatchBudget.unitChars(unit);
            }
        }
        boolean room = stagedItemUnits.size() < MAX_ITEMS_PER_BATCH;
        if (!stagedItemUnits.isEmpty() && (!room || !budget.fits(cost))) {
            batchFull = true;
            return false;
        }
        scanned++;
        // The whole item rides along (ready units included) as AI context.
        for (String unit : target.sources()) {
            if (unit != null && !unit.isBlank()) staged.add(unit);
        }
        for (String unit : fresh) budget.add(BatchBudget.unitChars(unit));
        stagedItemUnits.add(List.copyOf(needed));
        if (budget.full() || stagedItemUnits.size() >= MAX_ITEMS_PER_BATCH) batchFull = true;
        return true;
    }

    private void resetStaging() {
        staged.clear();
        stagedItemUnits.clear();
        budget = BatchBudget.windowed();
        batchFull = false;
    }

    private void restartRateWindow() {
        finished.clear();
        rateSince = clock.getAsLong();
    }

    private int itemsPerMinute() {
        if (state != State.RUNNING && state != State.PAUSED) return 0;
        long now = clock.getAsLong();
        while (!finished.isEmpty() && now - finished.peekFirst()[0] > RATE_WINDOW_MS) {
            finished.pollFirst();
        }
        long items = 0;
        for (long[] entry : finished) items += entry[1];
        long span = Math.min(RATE_WINDOW_MS, now - rateSince);
        if (items <= 0 || span < RATE_MIN_SPAN_MS) return 0;
        return (int) Math.round(items * 60_000.0 / span);
    }

    /** Minutes left, from the share of probed items that needed the AI. */
    private int etaMinutes(int rate) {
        if (rate <= 0 || (state != State.RUNNING && state != State.PAUSED)) return -1;
        int probed = Math.max(0, scanned - skippedFailed);
        double needShare = probed == 0 ? 1.0 : (submitted + stagedItemUnits.size()) / (double) probed;
        double unseen = Math.max(0, source.totalItemCount() - scanned);
        double inflightItems = 0;
        for (Batch batch : inflight) inflightItems += batch.itemUnits.size();
        double remaining = unseen * needShare + stagedItemUnits.size() + inflightItems;
        return (int) Math.ceil(remaining / rate);
    }

    private void notifyChanged() {
        rememberProgress();
        for (Listener l : listeners) l.onChanged(this);
    }

    /** Keeps where this run stands in the config so a later launch can offer Continue. */
    private void rememberProgress() {
        TranslatorConfig cfg = config.get();
        if (cfg == null) return;
        boolean finished = state == State.DONE && !limitReached;
        int total = source.totalItemCount();
        boolean needsWorld = !startedInWorld && skippedFailed > 0;
        boolean changed = cfg.itemWarmupLastScanned != scanned || cfg.itemWarmupLastTotal != total
                || cfg.itemWarmupLastFinished != finished || cfg.itemWarmupLastSkipped != skippedFailed
                || cfg.itemWarmupLastNeedsWorld != needsWorld;
        if (!changed) return;
        cfg.itemWarmupLastScanned = scanned;
        cfg.itemWarmupLastTotal = total;
        cfg.itemWarmupLastFinished = finished;
        cfg.itemWarmupLastSkipped = skippedFailed;
        cfg.itemWarmupLastNeedsWorld = needsWorld;
        long now = clock.getAsLong();
        boolean terminal = state != State.RUNNING;
        if (terminal || lastSavedAt == Long.MIN_VALUE || now - lastSavedAt >= SAVE_EVERY_MS) {
            lastSavedAt = now;
            try {
                progressSaver.run();
            } catch (RuntimeException ignored) {
                // a loader save failure must never break the client tick
            }
        }
    }
}
