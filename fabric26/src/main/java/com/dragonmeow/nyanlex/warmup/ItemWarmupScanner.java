package com.dragonmeow.nyanlex.warmup;

import com.dragonmeow.nyanlex.translate.SessionTokenUsage;

/**
 * Tick-driven dry run: walks the whole registry (client thread, a few items per tick)
 * and feeds an {@link ItemWarmupEstimator}. Sends nothing.
 */
public final class ItemWarmupScanner {
    public static final int DEFAULT_ITEMS_PER_TICK = 40;

    private final ItemWarmupSource source;
    private final ItemWarmupEstimator estimator;
    private int scanned;
    private int failed;
    private boolean started;

    public ItemWarmupScanner(ItemWarmupSource source, ItemWarmupBackend backend) {
        this.source = source;
        this.estimator = new ItemWarmupEstimator(backend);
    }

    /** Advance by one tick budget. Returns {@code true} once the scan is complete. */
    public boolean tick(int budget) {
        if (!started) {
            source.reset();
            started = true;
        }
        if (source.isExhausted()) return true;
        if (!source.isAvailable()) return false;
        for (ItemWarmupTarget target : source.probeNext(Math.max(1, budget))) {
            scanned++;
            if (target.failed()) {
                failed++;
                continue;
            }
            estimator.add(target);
        }
        return source.isExhausted();
    }

    public boolean isDone() {
        return started && source.isExhausted();
    }

    public int scanned() {
        return scanned;
    }

    /** Items whose tooltip could not be built (skipped). */
    public int failed() {
        return failed;
    }

    public int total() {
        return source.totalItemCount();
    }

    public ItemWarmupPlan plan(int maxItemsPerSession, SessionTokenUsage.Snapshot usage) {
        return estimator.build(maxItemsPerSession, usage);
    }
}
