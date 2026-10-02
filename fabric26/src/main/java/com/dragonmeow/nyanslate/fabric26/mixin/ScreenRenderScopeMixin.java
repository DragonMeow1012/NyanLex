package com.dragonmeow.nyanslate.fabric26.mixin;

import com.dragonmeow.nyanslate.fabric26.NyanslateFabric26;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Thread-local scope around the only 26.2 path that extracts a visible screen and its tooltip. */
@Mixin(Screen.class)
public abstract class ScreenRenderScopeMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), require = 1)
    private void nyanslate$beginVisibleScreen(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        NyanslateFabric26.beginScreenRender((Screen) (Object) this);
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"), require = 1)
    private void nyanslate$endVisibleScreen(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        NyanslateFabric26.endScreenRender((Screen) (Object) this);
    }
}
