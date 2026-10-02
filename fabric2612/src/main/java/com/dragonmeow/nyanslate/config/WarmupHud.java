package com.dragonmeow.nyanslate.config;

import com.dragonmeow.nyanslate.warmup.ItemWarmupDriver;

import java.util.function.BiFunction;

/** What the small corner readout of the item warm-up shows (pure, so every glue draws the same). */
public final class WarmupHud {

    /** How long "done" stays on screen after a run finishes. */
    public static final long DONE_SHOW_MS = 6_000L;

    public record View(String text, float fraction, int color) {}

    private WarmupHud() {}

    /**
     * @param hudEnabled   {@link TranslatorConfig#itemWarmupHud}
     * @param msSinceDone  milliseconds since the run reached DONE (ignored for other states)
     * @param lang         lang lookup: key and arguments to text
     * @return the readout, or null when nothing should be drawn
     */
    public static View view(WarmupStatus st, boolean hudEnabled, long msSinceDone,
                            BiFunction<String, Object[], String> lang) {
        if (!hudEnabled || st == null) return null;
        if (st.state() == ItemWarmupDriver.State.RUNNING) {
            return new View(lang.apply(SettingsModel.KEY_WARMUP_HUD_RUNNING,
                    new Object[] {st.scanned(), st.total()}), st.fraction(), 0xFF4C9AFF);
        }
        if (st.state() == ItemWarmupDriver.State.PAUSED) {
            return new View(lang.apply(SettingsModel.KEY_WARMUP_HUD_PAUSED,
                    new Object[] {st.scanned(), st.total()}), st.fraction(), 0xFFFFD75E);
        }
        if (st.state() == ItemWarmupDriver.State.DONE && msSinceDone >= 0 && msSinceDone < DONE_SHOW_MS) {
            return new View(lang.apply(SettingsModel.KEY_WARMUP_HUD_DONE, new Object[0]), 1f, 0xFF7FE08F);
        }
        return null;
    }
}
