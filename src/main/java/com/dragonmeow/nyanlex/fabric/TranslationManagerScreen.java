package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslationManagerPanel;
import com.dragonmeow.nyanlex.service.TranslationManagerModel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import org.lwjgl.glfw.GLFW;

/**
 * 譯文管理: search the stored translations and edit, revert or delete them. All layout, painting and
 * input logic lives in core's {@link TranslationManagerPanel}; this class forwards input, supplies the
 * canvas and the host, and returns to the screen that opened it (which keeps its own category and
 * scroll position) when the panel says so.
 */
public final class TranslationManagerScreen extends Screen {
    private final Screen parent;
    private final TranslationManagerModel model;
    private final Host host = new Host();
    private TranslationManagerPanel panel;

    public TranslationManagerScreen(Screen parent, TranslationManagerModel model) {
        super(Component.translatable(TranslationManagerPanel.KEY_TITLE));
        this.parent = parent;
        this.model = model;
    }

    /**
     * This screen is a text-entry screen: the mod's letter hotkeys (show original, retranslate,
     * scan) must not fire here, whether or not a text box happens to have the focus.
     */
    public boolean isTyping() {
        return true;
    }

    private final class Host implements TranslationManagerPanel.Host {
        @Override
        public String text(String key, Object... args) {
            return Component.translatable(key, args).getString();
        }

        @Override public int textWidth(String text) { return font.width(text); }

        @Override
        public String clipboard() {
            return minecraft == null ? "" : minecraft.keyboardHandler.getClipboard();
        }

        @Override
        public void copy(String text) {
            if (minecraft != null) minecraft.keyboardHandler.setClipboard(text);
        }

        @Override
        public void playClick() {
            if (minecraft != null) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
        }

        /** A manual translation changed: what is already on screen must be decided again. */
        @Override public void onChanged() { FabricTextStyle.clearRenderMemo(); }
    }

    @Override
    protected void init() {
        if (panel == null) panel = new TranslationManagerPanel(host, model);
        panel.resize(this.width, this.height);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void handle(int result) {
        if (result == TranslationManagerPanel.BACK) onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        handle(panel.mouseClicked((int) mouseX, (int) mouseY, button));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        panel.mouseDragged((int) mouseX, (int) mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        panel.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.mouseScrolled(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        handle(panel.keyPressed(keyCode, ctrl, shift));
        if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return panel.charTyped(codePoint) || super.charTyped(codePoint, modifiers);
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // dims the world behind the panel
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
