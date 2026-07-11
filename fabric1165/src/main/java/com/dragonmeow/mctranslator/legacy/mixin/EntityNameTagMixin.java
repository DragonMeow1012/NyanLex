package com.dragonmeow.mctranslator.legacy.mixin;

import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(EntityRenderer.class)
public abstract class EntityNameTagMixin {
    @ModifyVariable(method = "renderNameTag", at = @At("HEAD"), argsOnly = true, require = 0)
    private Component mctranslator$name(Component name) { return LegacyTranslatorMod.translateVisible(name); }
}
