package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.UiCanvas;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

import java.util.ArrayDeque;

/**
 * {@link UiCanvas} over a {@link PoseStack}: how the core dialogs are drawn on any screen.
 * This Minecraft version has no GUI-space scissor helper, so the clip rectangles are kept in a
 * small stack here and converted to window pixels when applied.
 */
final class GuiCanvas implements UiCanvas {
    private static final ArrayDeque<int[]> CLIPS = new ArrayDeque<>();

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
        pushScissor(x, y, w, h);
    }

    @Override
    public void popClip() {
        popScissor();
    }

    /** Restricts drawing to a GUI-space rectangle (intersected with any active one). */
    static void pushScissor(int x, int y, int w, int h) {
        int[] r = {x, y, x + w, y + h};
        int[] top = CLIPS.peek();
        if (top != null) {
            r[0] = Math.max(r[0], top[0]);
            r[1] = Math.max(r[1], top[1]);
            r[2] = Math.min(r[2], top[2]);
            r[3] = Math.min(r[3], top[3]);
        }
        if (r[2] < r[0]) r[2] = r[0];
        if (r[3] < r[1]) r[3] = r[1];
        CLIPS.push(r);
        apply(r);
    }

    static void popScissor() {
        if (!CLIPS.isEmpty()) CLIPS.pop();
        int[] top = CLIPS.peek();
        if (top == null) RenderSystem.disableScissor();
        else apply(top);
    }

    private static void apply(int[] r) {
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        int px = (int) Math.round(r[0] * scale);
        int pw = (int) Math.round((r[2] - r[0]) * scale);
        int ph = (int) Math.round((r[3] - r[1]) * scale);
        int py = (int) Math.round(window.getHeight() - r[3] * scale);
        RenderSystem.enableScissor(px, py, Math.max(0, pw), Math.max(0, ph));
    }
}
