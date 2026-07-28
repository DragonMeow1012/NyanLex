package com.dragonmeow.mctranslator.legacy.mixin;

import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
import net.minecraft.client.renderer.entity.EntityRenderer;
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
    private void mctranslator$begin(Entity entity, String name, double x, double y, double z,
                                    int maxDistance, CallbackInfo ci) {
        mctranslator$currentEntity = entity;
        mctranslator$previousGuard = LegacyTranslatorMod.beginInternalRender();
    }

    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String mctranslator$name(String name) {
        return LegacyTranslatorMod.nameTag(mctranslator$currentEntity, name);
    }

    @Inject(method = "renderNameTag", at = @At("RETURN"), require = 0)
    private void mctranslator$end(Entity entity, String name, double x, double y, double z,
                                  int maxDistance, CallbackInfo ci) {
        LegacyTranslatorMod.endInternalRender(mctranslator$previousGuard);
        mctranslator$currentEntity = null;
    }
}