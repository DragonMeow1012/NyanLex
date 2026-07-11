package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.fabric.MctranslatorFabric;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 1.21.11 name tags use render-state submission rather than Font.drawInBatch. */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagMixin {
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
    private Component mctranslator$name(Component component) {
        return MctranslatorFabric.nameTag(component);
    }
}
