package com.dragonmeow.nyanslate.legacy.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod;
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
    @Unique private Entity nyanslate$currentEntity;
    @Unique private boolean nyanslate$previousGuard;

    @Inject(method = "renderNameTag", at = @At("HEAD"), require = 0)
    private void nyanslate$begin(Entity entity, String name, double x, double y, double z,
                                    int maxDistance, CallbackInfo ci) {
        HookGuard.enterSticky("EntityNameTag.begin");
        try {
            nyanslate$currentEntity = entity;
            nyanslate$previousGuard = LegacyTranslatorMod.beginInternalRender();
        } catch (Throwable guardError) {
            HookGuard.fail("EntityNameTag.begin", guardError);
        }
    }

    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String nyanslate$name(String name) {
        if (!HookGuard.enter("EntityNameTag.name")) return name;
        try {
            return LegacyTranslatorMod.nameTag(nyanslate$currentEntity, name);
        } catch (Throwable guardError) {
            HookGuard.fail("EntityNameTag.name", guardError);
            return name;
        }
    }

    @Inject(method = "renderNameTag", at = @At("RETURN"), require = 0)
    private void nyanslate$end(Entity entity, String name, double x, double y, double z,
                                  int maxDistance, CallbackInfo ci) {
        HookGuard.enterSticky("EntityNameTag.end");
        try {
            LegacyTranslatorMod.endInternalRender(nyanslate$previousGuard);
            nyanslate$currentEntity = null;
        } catch (Throwable guardError) {
            HookGuard.fail("EntityNameTag.end", guardError);
        }
    }
}