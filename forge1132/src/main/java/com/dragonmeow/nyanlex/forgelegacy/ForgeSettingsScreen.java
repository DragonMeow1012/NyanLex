package com.dragonmeow.nyanlex.forgelegacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.util.List;

/**
 * Translation settings: an index of six categories, each opening a short page of settings. The
 * rows come from {@link LegacyUiModel}; this class only reacts to what is clicked.
 */
final class ForgeSettingsScreen extends ForgeFormScreen {
    private static final int HELP = 900;
    private final GuiScreen parent;
    private int category = -1;

    ForgeSettingsScreen(GuiScreen p) {
        parent = p;
    }

    @Override protected List<LegacyUiModel.Row> rows() {
        LegacyConfig cfg = NyanLexForge.config();
        if (category < 0) return LegacyUiModel.indexRows(TEXT);
        return LegacyUiModel.categoryRows(category, cfg, TEXT, languageLabel(cfg), NyanLexForge.version());
    }

    @Override protected String heading() {
        return category < 0 ? I18n.format("screen.nyanlex.config.title")
                : TEXT.get(LegacyUiModel.CATEGORY_KEYS[category]);
    }

    @Override protected void initGui() {
        super.initGui();
        addButton(new ForgeButton(HELP, 6, 4, 70, 14, "§e" + TEXT.get("config.nyanlex.help.open"), this));
    }

    private static String languageLabel(LegacyConfig c) {
        return TEXT.get("config.nyanlex.language", c.followGameLanguage
                ? TEXT.get("config.nyanlex.language.follow", NyanLexForge.currentTarget())
                : c.targetLang);
    }

    @Override protected void onAction(int action) {
        LegacyConfig c = NyanLexForge.config();
        if (action == HELP) {
            mc.displayGuiScreen(new ForgeHelpScreen(this));
            return;
        }
        if (action >= LegacyUiModel.A_CATEGORY && action < LegacyUiModel.A_CATEGORY + LegacyUiModel.CATEGORY_COUNT) {
            category = action - LegacyUiModel.A_CATEGORY;
            refresh();
            return;
        }
        if (LegacyUiModel.perform(action, c)) {
            if (action == LegacyUiModel.A_DEBUG && !c.debugTranslationOverlay) NyanLexForge.TRANSLATOR.clearDebug();
            NyanLexForge.save();
            refresh();
            return;
        }
        switch (action) {
            case LegacyUiModel.A_DONE: onEscape(); break;
            case LegacyUiModel.A_QUICK_SETUP: mc.displayGuiScreen(new ForgeSetupScreen(this, true)); break;
            case LegacyUiModel.A_HELP: mc.displayGuiScreen(new ForgeHelpScreen(this)); break;
            case LegacyUiModel.A_LANGUAGE:
                if (c.followGameLanguage) {
                    c.followGameLanguage = false;
                    c.targetLang = "zh-TW";
                } else if ("zh-TW".equals(c.targetLang)) c.targetLang = "en";
                else c.followGameLanguage = true;
                NyanLexForge.save();
                refresh();
                break;
            case LegacyUiModel.A_KEYS:
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiControls(this, mc.gameSettings));
                break;
            case LegacyUiModel.A_TERMS: mc.displayGuiScreen(new ForgeRequestsScreen(this)); break;
            case LegacyUiModel.A_AI: mc.displayGuiScreen(new ForgeAiConfigScreen(this)); break;
            case LegacyUiModel.A_COOLDOWN: mc.displayGuiScreen(new ForgeCooldownScreen(this)); break;
            case LegacyUiModel.A_EXPORT: NyanLexForge.translationFile(false); break;
            case LegacyUiModel.A_IMPORT: NyanLexForge.translationFile(true); break;
            case LegacyUiModel.A_GITHUB: NyanLexForge.openLink(this, LegacyUiModel.GITHUB_URL); break;
            default: break;
        }
    }

    static int next(int c, int[] a) {
        for (int v : a) if (v > c) return v;
        return 0;
    }

    @Override protected void onEscape() {
        NyanLexForge.save();
        if (category >= 0) {
            category = -1;
            refresh();
        } else {
            mc.displayGuiScreen(parent);
        }
    }

    @Override public void onGuiClosed() {
        NyanLexForge.save();
    }
}
