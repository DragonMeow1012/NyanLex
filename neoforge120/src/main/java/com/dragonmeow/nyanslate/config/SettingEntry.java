package com.dragonmeow.nyanslate.config;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One button of the settings screen: what it is called, how it explains itself, what
 * state it shows and what pressing it does. Pure data/logic over {@link TranslatorConfig};
 * the per-version glue only renders it.
 */
public final class SettingEntry {

    public enum Type {
        /** Two-state switch; {@link #press} flips a config field. */
        TOGGLE,
        /** Cycles through several values; {@link #press} advances to the next one. */
        CYCLE,
        /** Opens another screen ({@link #action()}). */
        SUBSCREEN,
        /** Runs a one-shot operation ({@link #action()}). */
        ACTION
    }

    /** Extra work the glue must do after a press, beyond saving the config. */
    public enum SideEffect {
        NONE,
        /** Master switch changed: drop pending requests so nothing stale is shown. */
        CLEAR_PENDING,
        /** Debug overlay: clear the debug log when it was just turned off. */
        CLEAR_DEBUG_LOG_WHEN_OFF
    }

    private final String id;
    private final SettingsPage page;
    private final Type type;
    private final String labelKey;
    private final String tipKey;
    private final Function<TranslatorConfig, StateText> state;
    private final Consumer<TranslatorConfig> press;
    private final SettingAction action;
    private final SideEffect sideEffect;
    private final boolean compactInPair;

    SettingEntry(String id, SettingsPage page, Type type, String labelKey, String tipKey,
                 Function<TranslatorConfig, StateText> state, Consumer<TranslatorConfig> press,
                 SettingAction action, SideEffect sideEffect, boolean compactInPair) {
        this.id = id;
        this.page = page;
        this.type = type;
        this.labelKey = labelKey;
        this.tipKey = tipKey;
        this.state = state;
        this.press = press;
        this.action = action;
        this.sideEffect = sideEffect;
        this.compactInPair = compactInPair;
    }

    public String id() { return id; }
    public SettingsPage page() { return page; }
    public Type type() { return type; }
    /** Lang key of the button label; contains one {@code %s} when {@link #hasState()}. */
    public String labelKey() { return labelKey; }
    /** Lang key of the hover / focus explanation. */
    public String tipKey() { return tipKey; }
    /** Non-null for {@link Type#SUBSCREEN} and {@link Type#ACTION}. */
    public SettingAction action() { return action; }
    public SideEffect sideEffect() { return sideEffect; }

    /**
     * When {@code true} and the entry sits in the right-hand cell of a two-column row,
     * the button shows only its state (e.g. "AI") instead of the full label.
     */
    public boolean compactInPair() { return compactInPair; }

    public boolean hasState() { return state != null; }

    public StateText state(TranslatorConfig cfg) {
        return state == null ? null : state.apply(cfg);
    }

    /** True for entries whose press must be confirmed by the glue with an entry count. */
    public boolean destructive() {
        return action == SettingAction.CLEAR_CACHE || action == SettingAction.HUB_CLEAR;
    }

    /** Applies a TOGGLE / CYCLE press to {@code cfg}. No-op for other types. */
    public void press(TranslatorConfig cfg) {
        if (press != null) press.accept(cfg);
    }
}
