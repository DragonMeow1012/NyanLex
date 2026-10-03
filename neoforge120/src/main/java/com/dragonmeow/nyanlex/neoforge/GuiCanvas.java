package com.dragonmeow.nyanlex.neoforge;

import com.dragonmeow.nyanlex.config.UiCanvas;
import com.dragonmeow.nyanlex.translate.ChatComposerPanel;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** {@link UiCanvas} over a {@link GuiGraphics}: how the core dialogs are drawn on any screen. */
public final class GuiCanvas implements UiCanvas, ChatComposerPanel.Canvas {
    private final GuiGraphics g;
    private final Font font;

    public GuiCanvas(GuiGraphics g, Font font) {
        this.g = g;
        this.font = font;
    }

    @Override
    public void fill(int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + h, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        g.drawString(font, text, x, y, argb, false);
    }

    @Override
    public void pushClip(int x, int y, int w, int h) {
        g.enableScissor(x, y, x + w, y + h);
    }

    @Override
    public void popClip() {
        g.disableScissor();
    }
}
