package com.dragonmeow.nyanlex.config;

import java.util.function.Supplier;

/**
 * The only way a manual translation action (R, P with a screen open, P in the world, start warm-up) gets past a switched-off
 * 線上翻譯: it is parked until the player presses "開始翻譯" in the on-the-spot box, then
 * runs exactly once; "取消" drops it and nothing is sent. Pure logic, the box is drawn by the glue.
 */
public final class ConsentGate {

    /** What the player asked for (decides the wording of the box). */
    public enum Kind { ITEM, SCREEN, HUD, WARMUP }

    private final Supplier<TranslatorConfig> config;
    private final Runnable save;
    private Kind pendingKind;
    private Runnable pending;

    public ConsentGate(Supplier<TranslatorConfig> config, Runnable save) {
        this.config = config;
        this.save = save;
    }

    public boolean online() {
        TranslatorConfig cfg = config.get();
        return cfg != null && cfg.translationRequestsEnabled;
    }

    /**
     * Runs {@code action} now when 線上翻譯 is on and returns {@code true}; otherwise parks it
     * (replacing any earlier parked action) and returns {@code false}: the glue must show the box.
     */
    public boolean request(Kind kind, Runnable action) {
        if (online()) {
            action.run();
            return true;
        }
        pendingKind = kind;
        pending = action;
        return false;
    }

    public boolean hasPending() { return pending != null; }

    public Kind pendingKind() { return pendingKind; }

    /** 開始翻譯: turns 線上翻譯 on, saves, and completes the parked action once. */
    public void confirm() {
        Runnable action = pending;
        pending = null;
        pendingKind = null;
        enable();
        if (action != null) action.run();
    }

    /** 取消: nothing is sent and the switch stays off. */
    public void cancel() {
        pending = null;
        pendingKind = null;
    }

    /** Turns 線上翻譯 on (and marks the first-start card as answered). */
    public void enable() {
        TranslatorConfig cfg = config.get();
        if (cfg == null) return;
        cfg.translationRequestsEnabled = true;
        cfg.firstRunDone = true;
        save.run();
    }
}
