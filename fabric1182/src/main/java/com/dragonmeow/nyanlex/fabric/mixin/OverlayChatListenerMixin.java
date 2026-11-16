package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import com.dragonmeow.nyanlex.translate.HookGuard;

import net.minecraft.client.gui.chat.OverlayChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * This Minecraft version has no client message event with an overlay flag. A server's action bar
 * message ({@code ChatType.GAME_INFO}) reaches the screen through this listener, which is what the
 * later versions' {@code ALLOW_GAME} callback with {@code overlay == true} stands for: the glue asks
 * for the translation right away and, when one is already known, shows it instead (see
 * {@link NyanLexFabric#interceptOverlayMessage(Component)}). Overlay text set by other means
 * ({@code /title actionbar}, mount hints) is translated when it is drawn.
 */
@Mixin(OverlayChatListener.class)
public abstract class OverlayChatListenerMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private void nyanlex$translateOverlay(ChatType type, Component message, UUID sender, CallbackInfo ci) {
        if (!HookGuard.enter("OverlayChatListener.translateOverlay")) return;
        try {
            if (NyanLexFabric.interceptOverlayMessage(message)) ci.cancel();
        } catch (Throwable guardError) {
            HookGuard.fail("OverlayChatListener.translateOverlay", guardError);
        }
    }
}
