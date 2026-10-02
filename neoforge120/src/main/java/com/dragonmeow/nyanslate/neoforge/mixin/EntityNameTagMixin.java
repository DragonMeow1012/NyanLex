package com.dragonmeow.nyanslate.neoforge.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.neoforge.NeoTextStyle;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Lets a name tag / hologram render its 原文＋翻譯 as TWO stacked lines (原文 above, 譯文 below)
 * instead of one inline line. The name-tag {@code Component} is produced by
 * {@link com.dragonmeow.nyanslate.neoforge.NyanslateNeoForge#onRenderNameTag} as
 * {@code 原文\n譯文}; here we redirect the {@code Font.drawInBatch} call inside
 * {@code EntityRenderer.renderNameTag} to split that on {@code '\n'} and draw each line,
 * each re-centred, stacked upward from the original baseline. A single-line name (no
 * {@code '\n'}) draws exactly as vanilla. Multi-line holograms (each line is a separate
 * entity) thus read 原文/譯文/原文/譯文 down the stack.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagMixin {

    @Redirect(
            method = "renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;"
                            + "FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;"
                            + "Lnet/minecraft/client/gui/Font$DisplayMode;II)I"),
            require = 0)
    private int nyanslate$stackedNameTag(Font font, Component text, float x, float y, int color,
                                            boolean dropShadow, Matrix4f matrix, MultiBufferSource buffers,
                                            Font.DisplayMode mode, int bgColor, int light) {
        if (!HookGuard.enter("EntityNameTag.stackedNameTag")) return font.drawInBatch(text, x, y, color, dropShadow, matrix, buffers, mode, bgColor, light);
        try {
            List<Component> lines = NeoTextStyle.splitLines(text);
            if (lines.size() <= 1) {
                return font.drawInBatch(text, x, y, color, dropShadow, matrix, buffers, mode, bgColor, light);
            }
            int n = lines.size();
            int ret = 0;
            for (int k = 0; k < n; k++) {
                Component line = lines.get(k);
                float lx = -font.width(line) / 2f;                         // re-centre each line
                float ly = y - (n - 1 - k) * (float) NeoTextStyle.STACK_LINE_GAP; // stack upward (原文 top, 譯文 baseline)
                ret = font.drawInBatch(line, lx, ly, color, dropShadow, matrix, buffers, mode, bgColor, light);
            }
            return ret;
        } catch (Throwable guardError) {
            HookGuard.fail("EntityNameTag.stackedNameTag", guardError);
            return font.drawInBatch(text, x, y, color, dropShadow, matrix, buffers, mode, bgColor, light);
        }
    }
}
