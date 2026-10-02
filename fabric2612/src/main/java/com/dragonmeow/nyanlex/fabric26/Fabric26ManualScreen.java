package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.ManualPanel;
import com.dragonmeow.nyanlex.config.SettingsModel;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 說明書: the two-column manual (contents on the left, a reading column on the right, the 快速設定
 * button at the top, 返回 at the bottom; Escape also returns). All layout and input logic lives in
 * core's {@link ManualPanel}. It always returns to the screen that opened it, which keeps its own
 * category and scroll position.
 */
public final class Fabric26ManualScreen extends Screen {
    private final Screen parent;
    private final int chapter;
    private ManualPanel panel;

    public Fabric26ManualScreen(Screen parent) {
        this(parent, -1);
    }

    /** Opens on the given chapter (0-based), e.g. the privacy chapter for the 隱私說明 button. */
    public Fabric26ManualScreen(Screen parent, int chapter) {
        super(Component.translatable(SettingsModel.KEY_MANUAL_TITLE));
        this.parent = parent;
        this.chapter = chapter;
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }

    private static String keyName(KeyMapping key) {
        return key == null || key.isUnbound() ? "" : key.getTranslatedKeyMessage().getString();
    }

    private Map<String, String> keyNames() {
        Map<String, String> names = new HashMap<>();
        names.put("R", keyName(NyanLexFabric26.retranslateKeyMapping()));
        names.put("P", keyName(NyanLexFabric26.screenScanKeyMapping()));
        names.put("G", keyName(NyanLexFabric26.toggleKeyMapping()));
        names.put("S", keyName(NyanLexFabric26.modeKeyMapping()));
        return names;
    }

    @Override
    protected void init() {
        if (panel == null) {
            List<ManualPanel.Section> sections = new ArrayList<>();
            for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
                sections.add(new ManualPanel.Section(text(SettingsModel.manualTitleKey(i)),
                        text(SettingsModel.manualBodyKey(i))));
            }
            panel = new ManualPanel(text -> this.font.width(text), text(SettingsModel.KEY_MANUAL_TITLE),
                    text(SettingsModel.KEY_MANUAL_BACK), sections)
                    .withTopButton(text(SettingsModel.KEY_MANUAL_SETUP_BTN))
                    .withChaptersLabel(text(SettingsModel.KEY_MANUAL_CHAPTERS))
                    .withNarration(text(SettingsModel.KEY_NARRATE_BUTTON), text(SettingsModel.KEY_NARRATE_CHAPTER));
            if (chapter >= 0) panel.openAt(chapter);
        }
        // key caps follow the bindings, which may have changed since the screen was last shown
        panel.withKeys(keyNames(), text(SettingsModel.KEY_KEY_UNSET));
        panel.resize(this.width, this.height);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void handle(int result) {
        if (result == ManualPanel.BACK) {
            onClose();
        } else if (result == ManualPanel.OPEN_QUICK_SETUP && this.minecraft != null) {
            this.minecraft.setScreenAndShow(new QuickSetupScreen(this));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        handle(panel.mouseClicked((int) event.x(), (int) event.y(), event.button()));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        panel.mouseDragged((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.mouseScrolled(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        handle(panel.keyPressed(event.key(), (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0));
        if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
        return true;
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
