package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric26.NyanLexFabric26;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Thread-local scope around the 26.1.2 path that extracts a visible screen and tooltip. */
@Mixin(Screen.class)
public abstract class ScreenRenderScopeMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), require = 1)
    private void nyanlex$beginVisibleScreen(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        HookGuard.enterSticky("ScreenRenderScope.beginVisibleScreen");
        try {
            NyanLexFabric26.beginScreenRender((Screen) (Object) this);
        } catch (Throwable guardError) {
            HookGuard.fail("ScreenRenderScope.beginVisibleScreen", guardError);
        }
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"), require = 1)
    private void nyanlex$endVisibleScreen(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        HookGuard.enterSticky("ScreenRenderScope.endVisibleScreen");
        try {
            NyanLexFabric26.endScreenRender((Screen) (Object) this);
        } catch (Throwable guardError) {
            HookGuard.fail("ScreenRenderScope.endVisibleScreen", guardError);
        }
    }
}
