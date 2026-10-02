package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The texts and buttons of the on-the-spot consent box and the names of the services text goes to (no Minecraft types). */
public final class DialogContent {

    /** Localized text, {@code %s} filled from {@code args}. */
    public interface Lang {
        String get(String key, Object... args);
    }

    public static final int CONSENT_CANCEL = 0;
    public static final int CONSENT_START = 1;

    private DialogContent() {}

    /** Every lang key the dialogs use (for the lang-file test). */
    public static List<String> allLangKeys() {
        return List.of("nyanlex.ui.consent.title", "nyanlex.ui.consent.item", "nyanlex.ui.consent.screen",
                "nyanlex.ui.consent.warmup", "nyanlex.ui.consent.stop",
                "nyanlex.ui.consent.start", "nyanlex.ui.consent.cancel",
                "nyanlex.ui.engine.ai", "nyanlex.ui.engine.codex", "nyanlex.ui.engine.custom",
                "nyanlex.ui.privacy.status.on", "nyanlex.ui.privacy.status.off",
                "message.nyanlex.tooltip_hint_start", "screen.nyanlex.provider.google",
                "config.nyanlex.language.follow",
                "nyanlex.settings.language",
                "nyanlex.narrate.choice", "nyanlex.narrate.chosen", "nyanlex.narrate.disabled");
    }

    /** The narrator phrases of every card-style dialog, from the lang files. */
    public static DialogPanel.Narration narration(Lang lang) {
        return new DialogPanel.Narration(lang.get("nyanlex.narrate.button", "%s"), lang.get("nyanlex.narrate.choice", "%s"),
                lang.get("nyanlex.narrate.chosen", "%s"), lang.get("nyanlex.narrate.button", "%s"),
                lang.get("nyanlex.narrate.disabled", "%s"));
    }

    /** "Google 翻譯（非官方端點）", "Gemini" ... or "ChatGPT（使用你的 Codex 額度）": the service one surface sends to. */
    public static String engineName(TranslatorConfig cfg, boolean ai, Lang lang) {
        if (ai) {
            if (cfg.aiUseCodex) return lang.get("nyanlex.ui.engine.codex");
            return lang.get("nyanlex.ui.engine.ai", aiProviderName(cfg, lang));
        }
        MachineTranslationProvider provider = MachineTranslationProvider.fromId(cfg.machineTranslationProvider);
        // The Google label itself says "unofficial endpoint"; official APIs say "official API".
        return lang.get("screen.nyanlex.provider." + provider.id());
    }

    /** The AI service by its endpoint: Gemini, OpenAI, DeepSeek, or the host of a custom endpoint. */
    public static String aiProviderName(TranslatorConfig cfg, Lang lang) {
        String url = cfg.aiBaseUrl == null ? "" : cfg.aiBaseUrl.toLowerCase(Locale.ROOT);
        if (url.contains("generativelanguage.googleapis.com")) return "Gemini";
        if (url.contains("api.openai.com")) return "OpenAI";
        if (url.contains("api.deepseek.com")) return "DeepSeek";
        String host = url.replaceFirst("^[a-z]+://", "");
        int cut = host.indexOf('/');
        if (cut >= 0) host = host.substring(0, cut);
        return host.isBlank() ? lang.get("nyanlex.ui.engine.custom") : host;
    }

    /** Service summary of all surfaces for the status row: one name, or AI and machine together. */
    public static String engineSummary(TranslatorConfig cfg, Lang lang) {
        boolean any = cfg.aiChat || cfg.aiTooltip || cfg.aiScoreboard || cfg.aiName || cfg.aiBossBar
                || cfg.aiTitle || cfg.aiActionBar || cfg.aiBook || cfg.aiScreenText;
        boolean all = cfg.aiChat && cfg.aiTooltip && cfg.aiScoreboard && cfg.aiName && cfg.aiBossBar
                && cfg.aiTitle && cfg.aiActionBar && cfg.aiBook && cfg.aiScreenText;
        if (!any) return engineName(cfg, false, lang);
        if (all) return engineName(cfg, true, lang);
        return engineName(cfg, true, lang) + " / " + engineName(cfg, false, lang);
    }

    /** "開：送往 Google 翻譯（非官方端點）" or "關：不會送出任何文字". */
    public static String onlineStatus(TranslatorConfig cfg, Lang lang) {
        return cfg.translationRequestsEnabled
                ? lang.get("nyanlex.ui.privacy.status.on", engineSummary(cfg, lang))
                : lang.get("nyanlex.ui.privacy.status.off");
    }

    /** The on-the-spot box for a manual action while 線上翻譯 is off: [取消] left, [開始翻譯] right, nothing focused. */
    public static DialogPanel.Content consent(ConsentGate.Kind kind, TranslatorConfig cfg, Lang lang) {
        String body;
        switch (kind) {
            case ITEM -> body = lang.get("nyanlex.ui.consent.item", engineName(cfg, cfg.aiTooltip, lang));
            case SCREEN -> body = lang.get("nyanlex.ui.consent.screen", engineName(cfg, cfg.aiScreenText, lang));
            default -> body = lang.get("nyanlex.ui.consent.warmup", engineName(cfg, true, lang));
        }
        List<DialogPanel.Block> blocks = new ArrayList<>();
        blocks.add(new DialogPanel.Text(body, 0));
        blocks.add(new DialogPanel.Text(lang.get("nyanlex.ui.consent.stop"), 0xFFA4A9B8));
        return new DialogPanel.Content(lang.get("nyanlex.ui.consent.title"), blocks,
                DialogPanel.Footer.of(new DialogPanel.Btn(CONSENT_CANCEL, lang.get("nyanlex.ui.consent.cancel")),
                        new DialogPanel.Btn(CONSENT_START, lang.get("nyanlex.ui.consent.start"), true)),
                CONSENT_CANCEL);
    }
}
