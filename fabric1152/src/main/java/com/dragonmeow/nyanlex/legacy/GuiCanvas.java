package com.dragonmeow.nyanlex.legacy;

import com.dragonmeow.nyanlex.translate.ChatComposerPanel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

/** Platform drawing boundary shared by the chat composer and its choices. */
public final class GuiCanvas implements ChatComposerPanel.Canvas {
    private final Font font;

    public GuiCanvas(Font font) {
        this.font = font;
    }

    @Override
    public void fill(int x, int y, int width, int height, int color) {
        GuiComponent.fill(x, y, x + width, y + height, color);
    }

    @Override
    public void text(String text, int x, int y, int color) {
        font.draw(text, x, y, color);
    }
}
