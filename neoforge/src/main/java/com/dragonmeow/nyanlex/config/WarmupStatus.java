package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.warmup.ItemWarmupDriver;

/** Snapshot of the item warm-up for the settings card and the HUD readout. */
public record WarmupStatus(boolean available, ItemWarmupDriver.State state,
                           ItemWarmupDriver.PauseReason reason, int scanned, int total,
                           int submitted, boolean limitReached) {

    public static final WarmupStatus UNAVAILABLE = new WarmupStatus(false,
            ItemWarmupDriver.State.IDLE, ItemWarmupDriver.PauseReason.NONE, 0, 0, 0, false);

    /** From the driver's progress; {@code available} is whether a run could start now. */
    public static WarmupStatus of(boolean available, ItemWarmupDriver.Progress p) {
        return new WarmupStatus(available, p.state(), p.pauseReason(), p.scanned(),
                p.totalItems(), p.submittedItems(), p.limitReached());
    }

    public boolean active() {
        return state == ItemWarmupDriver.State.RUNNING || state == ItemWarmupDriver.State.PAUSED;
    }

    public boolean userPaused() {
        return state == ItemWarmupDriver.State.PAUSED && reason == ItemWarmupDriver.PauseReason.USER;
    }

    /** Scan fraction 0..1 (1 when finished without hitting the per-launch limit). */
    public float fraction() {
        if (state == ItemWarmupDriver.State.DONE && !limitReached) return 1f;
        return total > 0 ? Math.min(1f, scanned / (float) total) : 0f;
    }

    public int percent() { return Math.round(fraction() * 100f); }
}
