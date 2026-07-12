package com.dragonmeow.mctranslator.legacy.mixin;

import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class ScreenRenderScopeMixin {
    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
                    shift = At.Shift.BEFORE),
            require = 1)
    private void mctranslator$beforeScreenRender(float tickDelta, long startTime, boolean tick,
                                                 CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        LegacyTranslatorMod.beginScreenRender(minecraft == null ? null : minecraft.screen);
    }

    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void mctranslator$afterScreenRender(float tickDelta, long startTime, boolean tick,
                                                CallbackInfo ci) {
        LegacyTranslatorMod.endScreenRender();
    }
}
