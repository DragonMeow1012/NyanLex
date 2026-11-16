package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.11 name tags use render-state submission rather than Font.drawInBatch. */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagMixin {

    /** Captured at the enclosing render method boundary. Keeping it on the renderer
     *  instance lets the ModifyArg handler retain the one-argument signature required
     *  by Mixin 0.8.7 while still identifying AvatarRenderState player name tags. */
    @Unique
    private EntityRenderState nyanlex$currentState;

    @Inject(
            method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At("HEAD"), require = 0)
    private void nyanlex$captureState(EntityRenderState state, PoseStack poseStack,
                                      SubmitNodeCollector collector, CameraRenderState camera,
                                      CallbackInfo ci) {
        if (!HookGuard.enter("EntityNameTag.captureState")) return;
        try {
            nyanlex$currentState = state;
        } catch (Throwable guardError) {
            HookGuard.fail("EntityNameTag.captureState", guardError);
        }
    }

    @ModifyArg(
            method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag("
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;I"
                            + "Lnet/minecraft/network/chat/Component;ZID"
                            + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V"),
            index = 3, require = 0)
    private Component nyanlex$name(Component component) {
        if (!HookGuard.enter("EntityNameTag.name")) return component;
        try {
            return NyanLexFabric.nameTag(nyanlex$currentState, component);
        } catch (Throwable guardError) {
            HookGuard.fail("EntityNameTag.name", guardError);
            return component;
        }
    }
}
