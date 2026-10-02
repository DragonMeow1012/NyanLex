package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.UiCanvas;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

/** {@link UiCanvas} over a {@link PoseStack}: how the core dialogs are drawn on any screen. */
final class GuiCanvas implements UiCanvas {
    private final PoseStack pose;
    private final Font font;

    GuiCanvas(PoseStack pose, Font font) {
        this.pose = pose;
        this.font = font;
    }

    @Override
    public void fill(int x, int y, int w, int h, int argb) {
        GuiComponent.fill(pose, x, y, x + w, y + h, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        font.draw(pose, text, (float) x, (float) y, argb);
    }

    @Override
    public void pushClip(int x, int y, int w, int h) {
        GuiComponent.enableScissor(x, y, x + w, y + h);
    }

    @Override
    public void popClip() {
        GuiComponent.disableScissor();
    }
}
