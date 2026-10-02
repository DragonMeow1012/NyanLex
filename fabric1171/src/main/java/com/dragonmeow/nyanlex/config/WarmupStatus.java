package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.warmup.ItemWarmupDriver;

/**
 * Snapshot of the item warm-up for the settings card and the HUD readout.
 *
 * <p>{@code scanned} (items looked at, cached or not) and {@code submitted} / {@code translated}
 * (items actually sent / stored by the AI) are different numbers and never mixed.</p>
 */
public record WarmupStatus(boolean available, ItemWarmupDriver.State state,
                           ItemWarmupDriver.PauseReason reason, int scanned, int total,
                           int submitted, boolean limitReached, int translated,
                           int itemsPerMinute, int etaMinutes, boolean yielding) {

    public static final WarmupStatus UNAVAILABLE = new WarmupStatus(false,
            ItemWarmupDriver.State.IDLE, ItemWarmupDriver.PauseReason.NONE, 0, 0, 0, false);

    /** Without speed figures (nothing measured yet). */
    public WarmupStatus(boolean available, ItemWarmupDriver.State state,
                        ItemWarmupDriver.PauseReason reason, int scanned, int total,
                        int submitted, boolean limitReached) {
        this(available, state, reason, scanned, total, submitted, limitReached, 0, 0, -1, false);
    }

    /** From the driver's progress; {@code available} is whether a run could start now. */
    public static WarmupStatus of(boolean available, ItemWarmupDriver.Progress p) {
        return new WarmupStatus(available, p.state(), p.pauseReason(), p.scanned(),
                p.totalItems(), p.submittedItems(), p.limitReached(), p.translatedItems(),
                p.itemsPerMinute(), p.etaMinutes(), p.yielding());
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

    /** A throughput figure exists (shown as "about N per minute"). */
    public boolean hasSpeed() {
        return state == ItemWarmupDriver.State.RUNNING && itemsPerMinute > 0;
    }
}
