package com.dragonmeow.nyanlex.legacy.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.legacy.LegacyTranslatorMod;
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
    private void nyanlex$beforeScreenRender(float tickDelta, long startTime, boolean tick,
                                                 CallbackInfo ci) {
        HookGuard.enterSticky("ScreenRenderScope.beforeScreenRender");
        try {
            Minecraft minecraft = Minecraft.getInstance();
            LegacyTranslatorMod.beginScreenRender(minecraft == null ? null : minecraft.screen);
        } catch (Throwable guardError) {
            HookGuard.fail("ScreenRenderScope.beforeScreenRender", guardError);
        }
    }

    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(IIF)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void nyanlex$afterScreenRender(float tickDelta, long startTime, boolean tick,
                                                CallbackInfo ci) {
        HookGuard.enterSticky("ScreenRenderScope.afterScreenRender");
        try {
            LegacyTranslatorMod.endScreenRender();
        } catch (Throwable guardError) {
            HookGuard.fail("ScreenRenderScope.afterScreenRender", guardError);
        }
    }
}
