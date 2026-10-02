package com.dragonmeow.nyanslate.legacy.mixin;

import com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod;
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
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(IIF)V",
                    shift = At.Shift.BEFORE),
            require = 1)
    private void nyanslate$beforeScreenRender(float tickDelta, long startTime, boolean tick,
                                                 CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        LegacyTranslatorMod.beginScreenRender(minecraft == null ? null : minecraft.screen);
    }

    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(IIF)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void nyanslate$afterScreenRender(float tickDelta, long startTime, boolean tick,
                                                CallbackInfo ci) {
        LegacyTranslatorMod.endScreenRender();
    }
}
