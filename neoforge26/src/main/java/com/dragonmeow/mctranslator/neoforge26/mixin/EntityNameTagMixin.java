package com.dragonmeow.mctranslator.neoforge26.mixin;

import com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Name tag / hologram translation. In 26.2 name tags are no longer drawn via
 * {@code Font.drawInBatch}; instead {@code EntityRenderer.submitNameDisplay} submits the name
 * {@code Component} to {@code SubmitNodeCollector.submitNameTag(pose, attachment, offset, component,
 * …)}. We modify that {@code component} argument (index 3) — translating the name (and, in BOTH
 * mode, producing 原文＋譯文). Because the submit API renders the tag itself, multi-line upward
 * stacking isn't available here as it was on 1.21.1, so BOTH mode reads inline.
 *
 * <p>Both {@code submitNameTag} call sites (name tag + below-name score text) share this; gated by
 * {@code nameMode} inside {@link MctranslatorNeoForge26#nameTag}.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagMixin {

    @Unique
    private EntityRenderState mctranslator$currentState;

    @Inject(
            method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
            at = @At("HEAD"), require = 0)
    private void mctranslator$captureState(EntityRenderState state, PoseStack poseStack,
                                            SubmitNodeCollector collector,
                                            CameraRenderState camera, int offset,
                                            CallbackInfo ci) {
        mctranslator$currentState = state;
    }

    @ModifyArg(
            method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag("
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;I"
                            + "Lnet/minecraft/network/chat/Component;ZI"
                            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"),
            index = 3, require = 0)
    private Component mctranslator$name(Component component) {
        return MctranslatorNeoForge26.nameTag(mctranslator$currentState, component);
    }
}
