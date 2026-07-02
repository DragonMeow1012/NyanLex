package com.dragonmeow.mctranslator.neoforge26.mixin;

import com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

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

    @ModifyArg(
            method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag"),
            index = 3, require = 0)
    private Component mctranslator$name(Component component) {
        return MctranslatorNeoForge26.nameTag(component);
    }
}
