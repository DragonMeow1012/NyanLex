package com.dragonmeow.nyanslate.legacy.mixin;
import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardHandler.class)
public abstract class ScreenKeyMixin {
    @Inject(method="keyPress", at=@At("HEAD"), cancellable=true, require=1)
    private void nyanslate$screenKey(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (!HookGuard.enter("ScreenKey.screenKey")) return;
        try {
            if (action == 1 && LegacyTranslatorMod.handleScreenKey(key, scanCode)) ci.cancel();
        } catch (Throwable guardError) {
            HookGuard.fail("ScreenKey.screenKey", guardError);
        }
    }
}
