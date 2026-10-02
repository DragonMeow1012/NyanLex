package com.dragonmeow.nyanlex.forgelegacy;

import net.minecraft.client.gui.GuiScreen;

import java.util.List;

/**
 * Quick setup: welcome, how to translate (the consent page), done. Nothing is applied, and
 * nothing is sent, until Finish; Esc and "Set up later" apply nothing.
 */
final class ForgeSetupScreen extends ForgeFormScreen {
    private final GuiScreen parent;
    private final LegacyUiModel.Setup setup;
    private boolean waitingForAi;

    ForgeSetupScreen(GuiScreen parent, boolean fromSettings) {
        this.parent = parent;
        this.setup = new LegacyUiModel.Setup(fromSettings ? NyanLexForge.config() : null);
    }

    @Override protected boolean hasDone() { return false; }

    @Override protected String heading() { return setup.title(TEXT); }

    @Override protected List<LegacyUiModel.Row> rows() {
        return setup.rows(TEXT, NyanLexForge.keyLabel(NyanLexForge.KEY_ITEM),
                NyanLexForge.keyLabel(NyanLexForge.KEY_SCREEN),
                NyanLexForge.keyLabel(NyanLexForge.KEY_SETTINGS));
    }

    @Override public void initGui() {
        if (waitingForAi) {
            // Back from the AI settings screen: continue only when an AI service is actually usable.
            waitingForAi = false;
            if (NyanLexForge.config().aiConfigured()) {
                setup.aiMissing = false;
                setup.page = LegacyUiModel.PAGE_DONE;
            } else {
                setup.choice = LegacyUiModel.CHOICE_NONE;
                setup.aiMissing = true;
            }
        }
        super.initGui();
    }

    @Override protected void onAction(int action) {
        if (action >= LegacyUiModel.S_CHOICE && action < LegacyUiModel.S_CHOICE + 3) {
            setup.choice = action - LegacyUiModel.S_CHOICE;
            setup.aiMissing = false;
            refresh();
            return;
        }
        switch (action) {
            case LegacyUiModel.S_LATER: onEscape(); break;
            case LegacyUiModel.S_START: setup.page = LegacyUiModel.PAGE_METHOD; refresh(); break;
            case LegacyUiModel.S_BACK:
                setup.page = Math.max(LegacyUiModel.PAGE_WELCOME, setup.page - 1);
                refresh();
                break;
            case LegacyUiModel.S_NEXT:
                if (setup.choice == LegacyUiModel.CHOICE_AI && !NyanLexForge.config().aiConfigured()) {
                    waitingForAi = true;
                    mc.displayGuiScreen(new ForgeAiConfigScreen(this));
                } else {
                    setup.page = LegacyUiModel.PAGE_DONE;
                    refresh();
                }
                break;
            case LegacyUiModel.S_KEYS:
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiControls(this, mc.gameSettings));
                break;
            case LegacyUiModel.S_FINISH:
                setup.apply(NyanLexForge.config());
                NyanLexForge.save();
                mc.displayGuiScreen(parent);
                break;
            default: break;
        }
    }

    /** Esc: "set up later" - no choice is applied, and the first-run prompt does not come back. */
    @Override protected void onEscape() {
        NyanLexForge.config().firstRunDone = true;
        NyanLexForge.save();
        mc.displayGuiScreen(parent);
    }

    @Override public void onGuiClosed() { }
}
