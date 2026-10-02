package com.dragonmeow.nyanlex.legacy;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Translation settings: an index of six categories, each opening a short page of settings. The
 * rows come from {@link LegacyUiModel}; this class only reacts to what is clicked.
 */
final class LegacySettingsScreen extends LegacyFormScreen {
    private final Screen parent;
    private int category = -1;

    LegacySettingsScreen(Screen parent) {
        super(new TranslatableComponent("screen.nyanlex.config.title"));
        this.parent = parent;
    }

    @Override protected List<LegacyUiModel.Row> rows() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        if (category < 0) return LegacyUiModel.indexRows(TEXT);
        return LegacyUiModel.categoryRows(category, cfg, TEXT, languageLabel(cfg),
                LegacyTranslatorMod.version());
    }

    @Override protected String heading() {
        return category < 0 ? title.getString() : TEXT.get(LegacyUiModel.CATEGORY_KEYS[category]);
    }

    @Override protected void init() {
        super.init();
        addButton(new Button(6, 4, 70, 14, new TextComponent("§e" + TEXT.get("config.nyanlex.help.open")),
                button -> minecraft.setScreen(new LegacyHelpScreen(this))));
    }

    private String languageLabel(LegacyConfig cfg) {
        return TEXT.get("config.nyanlex.language", cfg.followGameLanguage
                ? TEXT.get("config.nyanlex.language.follow", LegacyTranslatorMod.currentTarget(minecraft))
                : cfg.targetLang);
    }

    @Override protected void onAction(int action) {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        if (action >= LegacyUiModel.A_CATEGORY && action < LegacyUiModel.A_CATEGORY + LegacyUiModel.CATEGORY_COUNT) {
            category = action - LegacyUiModel.A_CATEGORY;
            refresh();
            return;
        }
        if (LegacyUiModel.perform(action, cfg)) {
            if (action == LegacyUiModel.A_DEBUG && !cfg.debugTranslationOverlay)
                LegacyTranslatorMod.TRANSLATOR.clearDebug();
            LegacyTranslatorMod.saveConfig();
            refresh();
            return;
        }
        switch (action) {
            case LegacyUiModel.A_DONE: onClose(); break;
            case LegacyUiModel.A_QUICK_SETUP: minecraft.setScreen(new LegacySetupScreen(this, true)); break;
            case LegacyUiModel.A_HELP: minecraft.setScreen(new LegacyHelpScreen(this)); break;
            case LegacyUiModel.A_LANGUAGE: minecraft.setScreen(new LegacyLanguageScreen(this)); break;
            case LegacyUiModel.A_KEYS:
                minecraft.setScreen(new net.minecraft.client.gui.screens.controls.ControlsScreen(this, minecraft.options));
                break;
            case LegacyUiModel.A_TERMS: minecraft.setScreen(new LegacyRequestsScreen(this)); break;
            case LegacyUiModel.A_AI: minecraft.setScreen(new LegacyAiConfigScreen(this)); break;
            case LegacyUiModel.A_COOLDOWN: minecraft.setScreen(new LegacyCooldownScreen(this)); break;
            case LegacyUiModel.A_EXPORT: LegacyTranslatorMod.translationFile(false); break;
            case LegacyUiModel.A_IMPORT: LegacyTranslatorMod.translationFile(true); break;
            case LegacyUiModel.A_GITHUB: LegacyTranslatorMod.openLink(this, LegacyUiModel.GITHUB_URL); break;
            default: break;
        }
    }

    static int nextCooldown(int current) {
        int[] values = {0, 1000, 2000, 4000, 6000, 8000, 10000};
        for (int value : values) if (value > current) return value;
        return 0;
    }

    static int nextBatchWindow(int current) {
        int[] values = {0, 1000, 2000, 3000, 5000, 8000, 10000};
        for (int value : values) if (value > current) return value;
        return 0;
    }

    static Component cooldownLabel(LegacyConfig cfg) {
        Component state = cfg.requestCooldownMs <= 0
                ? new TranslatableComponent("config.nyanlex.request_cooldown.off")
                : new TextComponent(cfg.requestCooldownMs + " ms");
        return new TranslatableComponent("config.nyanlex.request_cooldown", state);
    }

    static Component batchWindowLabel(LegacyConfig cfg) {
        Component state = cfg.batchWindowMs <= 0
                ? new TranslatableComponent("config.nyanlex.batch_window.off")
                : new TextComponent(cfg.batchWindowMs / 1000F + " s");
        return new TranslatableComponent("config.nyanlex.batch_window", state);
    }

    @Override public void onClose() {
        LegacyTranslatorMod.saveConfig();
        if (category >= 0) {
            category = -1;
            refresh();
        } else {
            minecraft.setScreen(parent);
        }
    }
}
