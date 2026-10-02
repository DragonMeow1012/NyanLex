package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.UiCanvas;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** {@link UiCanvas} over a {@link GuiGraphicsExtractor}: how the core dialogs are drawn on any screen. */
final class GuiCanvas implements UiCanvas {
    private final GuiGraphicsExtractor g;
    private final Font font;

    GuiCanvas(GuiGraphicsExtractor g, Font font) {
        this.g = g;
        this.font = font;
    }

    @Override
    public void fill(int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + h, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        g.text(font, text, x, y, argb, false);
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
