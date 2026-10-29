package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.DialogContent;
import com.dragonmeow.nyanlex.config.DialogPanel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
    private static final int REMEMBER = 3;

    private final Screen parent;
    private final String message;
    private final String confirmLabel;
    private final Runnable onConfirm;
    private java.util.function.Consumer<Boolean> onRemember;
    private boolean remember;
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

    /** The preference is committed only with the affirmative action. */
    public ConfirmDialogScreen rememberChoice(boolean initial, java.util.function.Consumer<Boolean> onRemember) {
        this.remember = initial;
        this.onRemember = onRemember;
        return this;
    }

    private DialogPanel.Content content() {
        java.util.ArrayList<DialogPanel.Block> blocks = new java.util.ArrayList<>();
        blocks.add(new DialogPanel.Text(message, 0));
        if (onRemember != null) blocks.add(new DialogPanel.Check(REMEMBER,
                Component.translatable("screen.nyanlex.notice.dont_show_again").getString(), remember));
        return new DialogPanel.Content(this.title.getString(), blocks,
                DialogPanel.Footer.of(new DialogPanel.Btn(CANCEL, Component.translatable("gui.cancel").getString()),
                        new DialogPanel.Btn(CONFIRM, confirmLabel, true)), CANCEL);
    }

    @Override
    protected void init() {
        panel.setNarration(DialogContent.narration((key, args) -> Component.translatable(key, args).getString()));
        panel.set(content());
        panel.resize(this.width, this.height);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.mouseScrolled((int) mouseX, (int) mouseY, scrollY);
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void handle(int id) {
        if (id == REMEMBER) {
            remember = !remember;
            panel.update(content());
        } else if (id == CONFIRM) {
            if (onRemember != null) onRemember.accept(remember);
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        handle(panel.mouseClicked((int) event.x(), (int) event.y(), event.button()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        handle(panel.keyPressed(event.key(), (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0));
        if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return true;
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        close();
    }
}
