package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.DialogContent;
import com.dragonmeow.nyanlex.config.DialogPanel;
import com.dragonmeow.nyanlex.config.SettingsCatalog;
import com.dragonmeow.nyanlex.config.SettingsCategory;
import com.dragonmeow.nyanlex.config.SettingsPanel;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubPlan;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
        HubPlan plan = NyanLexFabric26.firstRunHubPlan();
        shownPlan = plan;
        int count = plan == null ? 0 : plan.downloadable().size();
        String size = plan == null ? "" : HubDownloadConfirmScreen.formatBytes(plan.totalDownloadBytes());
        panel.set(DialogContent.firstRun(NyanLexFabric26.config(), count, size, LANG));
    }

    @Override
    public void tick() {
        if (NyanLexFabric26.firstRunHubPlan() != shownPlan) rebuild();
        if (awaitingAi && NyanLexFabric26.aiConfigured()) {
            awaitingAi = false;
            SettingsCatalog.setAllEngines(NyanLexFabric26.config(), true);
            finish(true);
        }
    }

    private void finish(boolean enable) {
        TranslatorConfig cfg = NyanLexFabric26.config();
        if (enable) ConsentOverlay.gate().enable();
        else cfg.firstRunDone = true;
        NyanLexFabric26.saveConfig();
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }

    private void open(SettingsCategory category) {
        SettingsPanel.rememberCategory(category);
        if (this.minecraft != null) this.minecraft.setScreenAndShow(new Fabric26ConfigScreen(this));
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
                    this.minecraft.setScreenAndShow(new HubDownloadConfirmScreen(this, shownPlan, null, null,
                            NyanLexFabric26.firstRunModCount()));
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        handle(panel.mouseClicked((int) event.x(), (int) event.y(), event.button()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int id = panel.keyPressed(event.key());
        if (id != DialogPanel.NONE) handle(id);
        return true; // Enter, Tab and everything else do nothing: no button is ever focused
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }
}
