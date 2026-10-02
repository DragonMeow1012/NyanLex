package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;

/** The texts and buttons of the first-start card and of the consent box (no Minecraft types). */
public final class DialogContent {

    /** Localized text, {@code %s} filled from {@code args}. */
    public interface Lang {
        String get(String key, Object... args);
    }

    public static final int CONSENT_CANCEL = 0;
    public static final int CONSENT_START = 1;

    public static final int FIRST_LATER = 10;
    public static final int FIRST_MACHINE = 11;
    public static final int FIRST_AI = 12;
    public static final int FIRST_CHANGE = 13;
    public static final int FIRST_HUB = 14;
    public static final int FIRST_PRIVACY = 15;

    private static final int C_WARN = 0xFFFFD75E;
    private static final int C_MUTED = 0xFFA4A9B8;
    private static final int C_GOOD = 0xFF7FE08F;

    private DialogContent() {}

    /** Every lang key the dialogs use (for the lang-file test). */
    public static List<String> allLangKeys() {
        return List.of("nyanlex.ui.consent.title", "nyanlex.ui.consent.item", "nyanlex.ui.consent.screen",
                "nyanlex.ui.consent.warmup", "nyanlex.ui.consent.unofficial", "nyanlex.ui.consent.stop",
                "nyanlex.ui.consent.start", "nyanlex.ui.consent.cancel", "nyanlex.ui.engine.ai",
                "nyanlex.ui.first.title", "nyanlex.ui.first.change", "nyanlex.ui.first.consent",
                "nyanlex.ui.first.machine", "nyanlex.ui.first.ai", "nyanlex.ui.first.later",
                "nyanlex.ui.first.hub", "nyanlex.ui.first.hub_btn", "nyanlex.ui.first.privacy",
                "nyanlex.ui.privacy.status.on", "nyanlex.ui.privacy.status.off",
                "message.nyanlex.tooltip_hint_start", "screen.nyanlex.provider.google",
                "screen.nyanlex.provider.youdao", "screen.nyanlex.provider.deepl",
                "screen.nyanlex.provider.microsoft", "config.nyanlex.language.follow",
                "nyanlex.settings.language");
    }

    /** "Google（非官方端點）" / "AI（model）": the service one surface sends to. */
    public static String engineName(TranslatorConfig cfg, boolean ai, Lang lang) {
        if (ai) return lang.get("nyanlex.ui.engine.ai", cfg.aiModel == null ? "" : cfg.aiModel);
        MachineTranslationProvider provider = MachineTranslationProvider.fromId(cfg.machineTranslationProvider);
        String name = lang.get("screen.nyanlex.provider." + provider.id());
        return provider == MachineTranslationProvider.GOOGLE
                ? name + lang.get("nyanlex.ui.consent.unofficial") : name;
    }

    /** Engine summary of all surfaces for the status row: one name, or AI and machine together. */
    public static String engineSummary(TranslatorConfig cfg, Lang lang) {
        boolean any = cfg.aiChat || cfg.aiTooltip || cfg.aiScoreboard || cfg.aiName || cfg.aiBossBar
                || cfg.aiTitle || cfg.aiActionBar || cfg.aiBook || cfg.aiScreenText;
        boolean all = cfg.aiChat && cfg.aiTooltip && cfg.aiScoreboard && cfg.aiName && cfg.aiBossBar
                && cfg.aiTitle && cfg.aiActionBar && cfg.aiBook && cfg.aiScreenText;
        if (!any) return engineName(cfg, false, lang);
        if (all) return engineName(cfg, true, lang);
        return engineName(cfg, true, lang) + " / " + engineName(cfg, false, lang);
    }

    /** "線上翻譯：開（Google）" or "線上翻譯：關，不會送出任何文字". */
    public static String onlineStatus(TranslatorConfig cfg, Lang lang) {
        return cfg.translationRequestsEnabled
                ? lang.get("nyanlex.ui.privacy.status.on", engineSummary(cfg, lang))
                : lang.get("nyanlex.ui.privacy.status.off");
    }

    /** The on-the-spot box for a manual action while 線上翻譯 is off. */
    public static DialogPanel.Content consent(ConsentGate.Kind kind, TranslatorConfig cfg, Lang lang) {
        String body;
        switch (kind) {
            case ITEM -> body = lang.get("nyanlex.ui.consent.item", engineName(cfg, cfg.aiTooltip, lang));
            case SCREEN -> body = lang.get("nyanlex.ui.consent.screen", engineName(cfg, cfg.aiScreenText, lang));
            default -> body = lang.get("nyanlex.ui.consent.warmup", engineName(cfg, true, lang));
        }
        List<DialogPanel.Block> blocks = new ArrayList<>();
        blocks.add(new DialogPanel.Text(body, 0));
        blocks.add(new DialogPanel.Text(lang.get("nyanlex.ui.consent.stop"), C_MUTED));
        blocks.add(new DialogPanel.Row(List.of(
                new DialogPanel.Btn(CONSENT_START, lang.get("nyanlex.ui.consent.start"), false),
                new DialogPanel.Btn(CONSENT_CANCEL, lang.get("nyanlex.ui.consent.cancel"), false))));
        return new DialogPanel.Content(lang.get("nyanlex.ui.consent.title"), blocks, CONSENT_CANCEL);
    }

    /**
     * The first-start card. {@code hubCount} &lt;= 0 means no repository translations were found
     * (or the check has not finished); {@code hubSize} is the preformatted total size.
     */
    public static DialogPanel.Content firstRun(TranslatorConfig cfg, int hubCount, String hubSize, Lang lang) {
        String langState = cfg.followGameLanguage
                ? lang.get("config.nyanlex.language.follow", cfg.targetLang) : cfg.targetLang;
        List<DialogPanel.Block> blocks = new ArrayList<>();
        blocks.add(new DialogPanel.Text(lang.get("nyanlex.settings.language", langState), 0));
        blocks.add(new DialogPanel.Row(List.of(
                new DialogPanel.Btn(FIRST_CHANGE, lang.get("nyanlex.ui.first.change"), false))));
        blocks.add(new DialogPanel.Text(lang.get("nyanlex.ui.first.consent"), C_WARN));
        blocks.add(new DialogPanel.Row(List.of(
                new DialogPanel.Btn(FIRST_MACHINE, lang.get("nyanlex.ui.first.machine"), true),
                new DialogPanel.Btn(FIRST_AI, lang.get("nyanlex.ui.first.ai"), false),
                new DialogPanel.Btn(FIRST_LATER, lang.get("nyanlex.ui.first.later"), false))));
        if (hubCount > 0) {
            blocks.add(new DialogPanel.Text(lang.get("nyanlex.ui.first.hub", hubCount, hubSize), C_GOOD));
            blocks.add(new DialogPanel.Row(List.of(
                    new DialogPanel.Btn(FIRST_HUB, lang.get("nyanlex.ui.first.hub_btn"), false))));
        }
        blocks.add(new DialogPanel.Row(List.of(
                new DialogPanel.Btn(FIRST_PRIVACY, lang.get("nyanlex.ui.first.privacy"), false))));
        return new DialogPanel.Content(lang.get("nyanlex.ui.first.title"), blocks, FIRST_LATER);
    }

    /** True while the first-start card is due: never answered and 線上翻譯 still off. */
    public static boolean firstRunDue(TranslatorConfig cfg) {
        return cfg != null && !cfg.firstRunDone && !cfg.translationRequestsEnabled;
    }
}
