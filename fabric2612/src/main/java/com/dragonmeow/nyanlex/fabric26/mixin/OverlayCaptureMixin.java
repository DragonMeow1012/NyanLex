package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric26.ComponentCapture;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 偵錯模式 overlay component capture, second entry point: the HUD's single overlay setter.
 * The chat event only sees system-chat overlays; the dedicated action bar packet goes straight
 * to this setter. Does nothing unless 偵錯模式 is on (see {@link ComponentCapture}).
 */
@Mixin(Gui.class)
public abstract class OverlayCaptureMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), require = 0)
    private void nyanlex$captureOverlay(Component message, boolean animate, CallbackInfo ci) {
        if (!HookGuard.enter("OverlayCapture.captureOverlay")) return;
        try {
            ComponentCapture.captureOverlayFromGui(message);
        } catch (Throwable guardError) {
            HookGuard.fail("OverlayCapture.captureOverlay", guardError);
        }
    }
}
