package com.dragonmeow.nyanslate.warmup;

import com.dragonmeow.nyanslate.config.TranslatorConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Tick-driven all-item warm-up. The loader calls {@link #tick()} once per client tick;
 * the driver submits at most one chunk of {@value #CHUNK_ITEMS} not-yet-cached items per
 * {@code itemWarmupChunkDelayMs}, at background priority, and waits for the previous
 * chunk to leave the pending state first (bounded by {@value #PENDING_WAIT_MS} ms).
 *
 * <p>Before every chunk it re-checks the master switch, the engine, the world and the
 * shared 429 gate; any of them failing pauses the run (auto-resumes when it clears).
 * Progress is "already cached": a restart skips what is done without any cursor.</p>
 */
public final class ItemWarmupDriver {
    public static final int CHUNK_ITEMS = 8;
    public static final long PENDING_WAIT_MS = 120_000L;
    public static final int PROBE_BUDGET_PER_TICK = 40;

    public enum State { IDLE, RUNNING, PAUSED, DONE, STOPPED }

    public enum PauseReason { NONE, USER, NO_WORLD, RATE_LIMITED, REQUESTS_OFF, ENGINE }

    public interface Listener {
        void onChanged(ItemWarmupDriver driver);
    }

    public record Progress(State state, PauseReason pauseReason, int scanned, int totalItems,
                           int skippedCached, int submittedItems, int sessionLimit,
                           boolean limitReached) {
    }

    private final ItemWarmupSource source;
    private final ItemWarmupBackend backend;
    private final Supplier<TranslatorConfig> config;
    private final LongSupplier clock;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private State state = State.IDLE;
    private PauseReason pauseReason = PauseReason.NONE;
    private long nextChunkAt;
    private long lastSubmitAt;
    private List<String> inflight = List.of();
    private int scanned;
    private int skippedCached;
    private int submitted;
    private int submittedThisSession;
    private boolean limitReached;
    private final Set<String> staged = new LinkedHashSet<>();
    private int stagedItems;

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

    /** Whether {@link #start()} would be accepted right now. */
    public synchronized boolean canStart() {
        TranslatorConfig cfg = config.get();
        return cfg != null && cfg.itemWarmupEnabled && backend.isAiEngine()
                && state != State.RUNNING && state != State.PAUSED;
    }

    /** Start (or restart) a run. The per-launch item budget is shared across runs. */
    public synchronized boolean start() {
        if (!canStart()) return false;
        source.reset();
        state = State.RUNNING;
        pauseReason = PauseReason.NONE;
        nextChunkAt = 0L;
        inflight = List.of();
        staged.clear();
        stagedItems = 0;
        scanned = 0;
        skippedCached = 0;
        submitted = 0;
        limitReached = false;
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
        notifyChanged();
    }

    public synchronized void stop() {
        if (state != State.RUNNING && state != State.PAUSED) return;
        state = State.STOPPED;
        pauseReason = PauseReason.NONE;
        inflight = List.of();
        notifyChanged();
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Progress progress() {
        TranslatorConfig cfg = config.get();
        int limit = cfg == null ? 0 : cfg.itemWarmupMaxItemsPerSession;
        return new Progress(state, pauseReason, scanned, source.totalItemCount(), skippedCached,
                submitted, limit, limitReached);
    }

    /** One client tick. Cheap when nothing is due. */
    public synchronized void tick() {
        if (state != State.RUNNING && state != State.PAUSED) return;
        TranslatorConfig cfg = config.get();
        if (cfg == null || !cfg.itemWarmupEnabled) {
            stop();
            return;
        }
        if (state == State.PAUSED && pauseReason == PauseReason.USER) return;
        PauseReason blocker = blocker();
        if (blocker != PauseReason.NONE) {
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
            notifyChanged();
        }
        long now = clock.getAsLong();
        if (now < nextChunkAt) return;
        if (!inflight.isEmpty()) {
            boolean stillPending = false;
            for (String unit : inflight) {
                if (backend.isPending(unit)) {
                    stillPending = true;
                    break;
                }
            }
            if (stillPending && now - lastSubmitAt < PENDING_WAIT_MS) return;
            inflight = List.of();
        }
        runChunk(cfg, now);
    }

    private PauseReason blocker() {
        if (!backend.requestsEnabled()) return PauseReason.REQUESTS_OFF;
        if (!backend.isAiEngine()) return PauseReason.ENGINE;
        if (!source.isAvailable()) return PauseReason.NO_WORLD;
        if (backend.isRateLimited()) return PauseReason.RATE_LIMITED;
        return PauseReason.NONE;
    }

    private void runChunk(TranslatorConfig cfg, long now) {
        int sessionLimit = Math.max(0, cfg.itemWarmupMaxItemsPerSession);
        int probedThisTick = 0;
        // Cached items are skipped cheaply, but tooltips are built on the client thread:
        // never probe more than PROBE_BUDGET_PER_TICK items in one tick. The chunk under
        // construction survives across ticks until it is full (or the run ends).
        while (stagedItems < CHUNK_ITEMS && !source.isExhausted()
                && submittedThisSession + stagedItems < sessionLimit
                && probedThisTick < PROBE_BUDGET_PER_TICK) {
            int room = Math.min(Math.min(CHUNK_ITEMS - stagedItems,
                            sessionLimit - submittedThisSession - stagedItems),
                    PROBE_BUDGET_PER_TICK - probedThisTick);
            List<ItemWarmupTarget> batch = source.probeNext(room);
            if (batch.isEmpty()) break;
            probedThisTick += batch.size();
            for (ItemWarmupTarget target : batch) {
                scanned++;
                boolean missing = false;
                for (String unit : target.sources()) {
                    if (unit != null && !unit.isBlank() && !backend.isReady(unit)) {
                        missing = true;
                        break;
                    }
                }
                if (!missing) {
                    skippedCached++;
                    continue;
                }
                // The whole item rides along (ready units included) as AI context.
                for (String unit : target.sources()) {
                    if (unit != null && !unit.isBlank()) staged.add(unit);
                }
                stagedItems++;
            }
        }
        boolean exhausted = source.isExhausted();
        boolean limitHit = submittedThisSession + stagedItems >= sessionLimit;
        boolean ending = exhausted || limitHit;
        if (stagedItems > 0 && (stagedItems >= CHUNK_ITEMS || ending)) {
            List<String> sources = new ArrayList<>(staged);
            backend.warm(sources);
            inflight = sources;
            lastSubmitAt = now;
            nextChunkAt = now + Math.max(0, cfg.itemWarmupChunkDelayMs);
            submitted += stagedItems;
            submittedThisSession += stagedItems;
            staged.clear();
            stagedItems = 0;
        }
        if (ending && stagedItems == 0) {
            limitReached = !exhausted;
            state = State.DONE;
            pauseReason = PauseReason.NONE;
        }
        notifyChanged();
    }

    private void notifyChanged() {
        for (Listener l : listeners) l.onChanged(this);
    }
}
