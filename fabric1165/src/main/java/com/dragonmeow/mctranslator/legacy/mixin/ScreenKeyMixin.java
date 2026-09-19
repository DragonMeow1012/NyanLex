package com.dragonmeow.mctranslator.legacy.mixin;
import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardHandler.class)
public abstract class ScreenKeyMixin {
    @Inject(method="keyPress", at=@At("HEAD"), cancellable=true, require=1)
    private void mctranslator$screenKey(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (action == 1 && LegacyTranslatorMod.handleScreenKey(key, scanCode)) ci.cancel();
    }
}
