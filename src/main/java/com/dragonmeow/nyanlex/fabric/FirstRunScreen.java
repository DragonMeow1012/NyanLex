package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.DialogContent;
import com.dragonmeow.nyanlex.config.DialogPanel;
import com.dragonmeow.nyanlex.config.SettingsCatalog;
import com.dragonmeow.nyanlex.config.SettingsCategory;
import com.dragonmeow.nyanlex.config.SettingsPanel;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubPlan;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The first-start card on the title screen (shown once, see {@link DialogContent#firstRunDue}).
 * Three answers: start with machine translation, set up AI first, or not now; a repository
 * line appears when the hub has packs for the installed mods. Nothing is focused by default,
 * Enter does nothing, Escape means "not now".
 */
public final class FirstRunScreen extends Screen {
    private static final DialogContent.Lang LANG = (key, args) -> Component.translatable(key, args).getString();

    private final Screen parent;
    private final DialogPanel panel;
    private HubPlan shownPlan;
    private boolean awaitingAi;

    public FirstRunScreen(Screen parent) {
        super(Component.translatable("nyanlex.ui.first.title"));
        this.parent = parent;
        this.panel = new DialogPanel(text -> this.font == null ? text.length() * 6 : this.font.width(text));
    }

    @Override
    protected void init() {
        panel.resize(this.width, this.height);
        rebuild();
    }

    private void rebuild() {
        HubPlan plan = NyanLexFabric.firstRunHubPlan();
        shownPlan = plan;
        int count = plan == null ? 0 : plan.downloadable().size();
        String size = plan == null ? "" : HubDownloadConfirmScreen.formatBytes(plan.totalDownloadBytes());
        panel.set(DialogContent.firstRun(NyanLexFabric.config(), count, size, LANG));
    }

    @Override
    public void tick() {
        if (NyanLexFabric.firstRunHubPlan() != shownPlan) rebuild();
        if (awaitingAi && NyanLexFabric.aiConfigured()) {
            awaitingAi = false;
            TranslatorConfig cfg = NyanLexFabric.config();
            SettingsCatalog.setAllEngines(cfg, true);
            finish(true);
        }
    }

    private void finish(boolean enable) {
        TranslatorConfig cfg = NyanLexFabric.config();
        if (enable) ConsentOverlay.gate().enable();
        else cfg.firstRunDone = true;
        NyanLexFabric.saveConfig();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    private void open(SettingsCategory category) {
        SettingsPanel.rememberCategory(category);
        if (this.minecraft != null) this.minecraft.setScreen(new TranslationConfigScreen(this));
    }

    private void handle(int id) {
        switch (id) {
            case DialogContent.FIRST_MACHINE -> finish(true);
            case DialogContent.FIRST_LATER -> finish(false);
            case DialogContent.FIRST_AI -> {
                awaitingAi = true;
                open(SettingsCategory.AI);
            }
            case DialogContent.FIRST_CHANGE, DialogContent.FIRST_PRIVACY -> open(SettingsCategory.GENERAL);
            case DialogContent.FIRST_HUB -> {
                if (shownPlan != null && this.minecraft != null) {
                    this.minecraft.setScreen(new HubDownloadConfirmScreen(this, shownPlan, null, null,
                            NyanLexFabric.firstRunModCount()));
                }
            }
            default -> { }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        handle(panel.mouseClicked((int) mouseX, (int) mouseY, button));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int id = panel.keyPressed(keyCode);
        if (id != DialogPanel.NONE) handle(id);
        return true; // Enter, Tab and everything else do nothing: no button is ever focused
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }
}
