package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.DialogContent;
import com.dragonmeow.nyanlex.config.DialogPanel;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * A yes/no question on the shared card: [取消] at the left, the action at the right, nothing focused
 * when it opens, Tab and Shift+Tab move the frame, Enter or Space press the framed button, Escape cancels.
 */
public final class ConfirmDialogScreen extends Screen {
    private static final int CANCEL = 1;
    private static final int CONFIRM = 2;

    private final Screen parent;
    private final String message;
    private final String confirmLabel;
    private final Runnable onConfirm;
    private final DialogPanel panel;

    public ConfirmDialogScreen(Screen parent, Component title, Component message, Component confirmLabel,
                               Runnable onConfirm) {
        super(title);
        this.parent = parent;
        this.message = message.getString();
        this.confirmLabel = confirmLabel.getString();
        this.onConfirm = onConfirm;
        this.panel = new DialogPanel(text -> this.font == null ? text.length() * 6 : this.font.width(text));
    }

    @Override
    protected void init() {
        panel.setNarration(DialogContent.narration((key, args) -> new net.minecraft.network.chat.TranslatableComponent(key, args).getString()));
        panel.set(new DialogPanel.Content(this.title.getString(),
                List.of(new DialogPanel.Text(message, 0)),
                DialogPanel.Footer.of(new DialogPanel.Btn(CANCEL, new net.minecraft.network.chat.TranslatableComponent("gui.cancel").getString()),
                        new DialogPanel.Btn(CONFIRM, confirmLabel, true)),
                CANCEL));
        panel.resize(this.width, this.height);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void handle(int id) {
        if (id == CONFIRM) {
            onConfirm.run();
            close();
        } else if (id == CANCEL) {
            close();
        }
    }

    private void close() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        handle(panel.mouseClicked((int) mouseX, (int) mouseY, button));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        handle(panel.keyPressed(keyCode, (modifiers & GLFW.GLFW_MOD_SHIFT) != 0));
        if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return true;
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, new net.minecraft.network.chat.TextComponent(panel.narration()));
    }

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        close();
    }
}
