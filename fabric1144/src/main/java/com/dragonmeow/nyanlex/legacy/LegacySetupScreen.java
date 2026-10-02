package com.dragonmeow.nyanlex.legacy;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.List;

/**
 * Quick setup: welcome, how to translate (the consent page), done. Nothing is applied, and
 * nothing is sent, until Finish; Esc and "Set up later" apply nothing.
 */
final class LegacySetupScreen extends LegacyFormScreen {
    private final Screen parent;
    private final LegacyUiModel.Setup setup;
    private boolean waitingForAi;

    LegacySetupScreen(Screen parent, boolean fromSettings) {
        super(new TranslatableComponent("screen.nyanlex.setup.welcome.title"));
        this.parent = parent;
        this.setup = new LegacyUiModel.Setup(fromSettings ? LegacyTranslatorMod.config() : null);
    }

    @Override protected boolean hasDone() { return false; }

    @Override protected String heading() { return setup.title(TEXT); }

    @Override protected List<LegacyUiModel.Row> rows() {
        return setup.rows(TEXT, LegacyTranslatorMod.keyLabel(LegacyTranslatorMod.KEY_ITEM),
                LegacyTranslatorMod.keyLabel(LegacyTranslatorMod.KEY_SCREEN),
                LegacyTranslatorMod.keyLabel(LegacyTranslatorMod.KEY_SETTINGS));
    }

    @Override protected void init() {
        if (waitingForAi) {
            // Back from the AI settings screen: continue only when an AI service is actually usable.
            waitingForAi = false;
            if (LegacyTranslatorMod.config().aiConfigured()) {
                setup.aiMissing = false;
                setup.page = LegacyUiModel.PAGE_DONE;
            } else {
                setup.choice = LegacyUiModel.CHOICE_NONE;
                setup.aiMissing = true;
            }
        }
        super.init();
    }

    @Override protected void onAction(int action) {
        if (action >= LegacyUiModel.S_CHOICE && action < LegacyUiModel.S_CHOICE + 3) {
            setup.choice = action - LegacyUiModel.S_CHOICE;
            setup.aiMissing = false;
            refresh();
            return;
        }
        switch (action) {
            case LegacyUiModel.S_LATER: onClose(); break;
            case LegacyUiModel.S_START: setup.page = LegacyUiModel.PAGE_METHOD; refresh(); break;
            case LegacyUiModel.S_BACK:
                setup.page = Math.max(LegacyUiModel.PAGE_WELCOME, setup.page - 1);
                refresh();
                break;
            case LegacyUiModel.S_NEXT:
                if (setup.choice == LegacyUiModel.CHOICE_AI && !LegacyTranslatorMod.config().aiConfigured()) {
                    waitingForAi = true;
                    minecraft.setScreen(new LegacyAiConfigScreen(this));
                } else {
                    setup.page = LegacyUiModel.PAGE_DONE;
                    refresh();
                }
                break;
            case LegacyUiModel.S_KEYS:
                minecraft.setScreen(new net.minecraft.client.gui.screens.controls.ControlsScreen(this, minecraft.options));
                break;
            case LegacyUiModel.S_FINISH:
                setup.apply(LegacyTranslatorMod.config());
                LegacyTranslatorMod.saveConfig();
                minecraft.setScreen(parent);
                break;
            default: break;
        }
    }

    /** Esc: "set up later" - no choice is applied, and the first-run prompt does not come back. */
    @Override public void onClose() {
        LegacyTranslatorMod.config().firstRunDone = true;
        LegacyTranslatorMod.saveConfig();
        minecraft.setScreen(parent);
    }
}
