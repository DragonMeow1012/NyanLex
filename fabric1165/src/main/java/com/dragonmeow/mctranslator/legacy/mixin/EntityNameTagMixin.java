package com.dragonmeow.mctranslator.legacy.mixin;

import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
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
    @Unique private Entity mctranslator$currentEntity;
    @Unique private boolean mctranslator$previousGuard;

    @Inject(method = "renderNameTag", at = @At("HEAD"), require = 0)
    private void mctranslator$begin(Entity entity, Component name, PoseStack pose,
                                    MultiBufferSource buffers, int light, CallbackInfo ci) {
        mctranslator$currentEntity = entity;
        mctranslator$previousGuard = LegacyTranslatorMod.beginInternalRender();
    }

    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, require = 0)
    private Component mctranslator$name(Component name) {
        return LegacyTranslatorMod.nameTag(mctranslator$currentEntity, name);
    }

    @Inject(method = "renderNameTag", at = @At("RETURN"), require = 0)
    private void mctranslator$end(Entity entity, Component name, PoseStack pose,
                                  MultiBufferSource buffers, int light, CallbackInfo ci) {
        LegacyTranslatorMod.endInternalRender(mctranslator$previousGuard);
        mctranslator$currentEntity = null;
    }
}