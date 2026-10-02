package com.dragonmeow.nyanslate.legacy.mixin;

import com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Protect real/TAB-listed player tags while retaining ArmorStand hologram translation. */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagMixin {
    @Unique private Entity nyanslate$currentEntity;
    @Unique private boolean nyanslate$previousGuard;

    @Inject(method = "renderNameTag", at = @At("HEAD"), require = 0)
    private void nyanslate$begin(Entity entity, Component name, PoseStack pose,
                                    MultiBufferSource buffers, int light, CallbackInfo ci) {
        nyanslate$currentEntity = entity;
        nyanslate$previousGuard = LegacyTranslatorMod.beginInternalRender();
    }

    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanslate$name(Component name) {
        return LegacyTranslatorMod.nameTag(nyanslate$currentEntity, name);
    }

    @Inject(method = "renderNameTag", at = @At("RETURN"), require = 0)
    private void nyanslate$end(Entity entity, Component name, PoseStack pose,
                                  MultiBufferSource buffers, int light, CallbackInfo ci) {
        LegacyTranslatorMod.endInternalRender(nyanslate$previousGuard);
        nyanslate$currentEntity = null;
    }
}