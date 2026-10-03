package com.dragonmeow.nyanlex.legacy;

import com.dragonmeow.nyanlex.translate.ChatComposerPanel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

/** Platform drawing boundary shared by the chat composer and its choices. */
public final class GuiCanvas implements ChatComposerPanel.Canvas {
    private final PoseStack pose;
    private final Font font;

    public GuiCanvas(PoseStack pose, Font font) {
        this.pose = pose;
        this.font = font;
    }

    @Override
    public void fill(int x, int y, int width, int height, int color) {
        GuiComponent.fill(pose, x, y, x + width, y + height, color);
    }

    @Override
    public void text(String text, int x, int y, int color) {
        font.draw(pose, text, x, y, color);
    }
}
