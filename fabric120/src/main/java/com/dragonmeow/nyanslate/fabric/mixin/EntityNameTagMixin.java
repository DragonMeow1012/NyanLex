package com.dragonmeow.nyanslate.fabric.mixin;

import com.dragonmeow.nyanslate.fabric.FabricTextStyle;
import com.dragonmeow.nyanslate.fabric.NyanslateFabric;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Name tag / hologram translation + 原文＋翻譯 stacking. Fabric has no name-tag event, so this
 * mixin does BOTH: (1) {@link #nyanslate$translateNameTag} translates the name {@code Component}
 * at HEAD of {@code EntityRenderer.renderNameTag} (producing {@code 原文\n譯文} in BOTH mode), and
 * (2) {@link #nyanslate$stackedNameTag} redirects the {@code Font.drawInBatch} call to split that
 * on {@code '\n'} and draw each line re-centred, stacked upward from the baseline. A single line
 * (no {@code '\n'}) draws exactly as vanilla. Multi-line holograms (each line a separate entity)
 * thus read 原文/譯文/原文/譯文 down the stack.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagMixin {

    @ModifyVariable(
            method = "renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanslate$translateNameTag(Component name, Entity entity, Component original,
                                                    PoseStack poseStack, MultiBufferSource buffers,
                                                    int light) {
        // Target-arg capture (appended after the modified variable) hands us the ENTITY, so
        // the glue can skip real TAB-listed players' name tags (player IDs never translate).
        // 1.20.1 renderNameTag has NO trailing float partialTick (added in 1.21) — descriptor
        // matches the 1.20.1 five-arg method.
        return NyanslateFabric.nameTag(entity, name);
    }

    @Redirect(
            method = "renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;"
                            + "FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;"
                            + "Lnet/minecraft/client/gui/Font$DisplayMode;II)I"),
            require = 0)
    private int nyanslate$stackedNameTag(Font font, Component text, float x, float y, int color,
                                            boolean dropShadow, Matrix4f matrix, MultiBufferSource buffers,
                                            Font.DisplayMode mode, int bgColor, int light) {
        List<Component> lines = FabricTextStyle.splitLines(text);
        if (lines.size() <= 1) {
            return font.drawInBatch(text, x, y, color, dropShadow, matrix, buffers, mode, bgColor, light);
        }
        int n = lines.size();
        int ret = 0;
        for (int k = 0; k < n; k++) {
            Component line = lines.get(k);
            float lx = -font.width(line) / 2f;                         // re-centre each line
            float ly = y - (n - 1 - k) * (float) FabricTextStyle.STACK_LINE_GAP; // stack upward (原文 top, 譯文 baseline)
            ret = font.drawInBatch(line, lx, ly, color, dropShadow, matrix, buffers, mode, bgColor, light);
        }
        return ret;
    }
}
