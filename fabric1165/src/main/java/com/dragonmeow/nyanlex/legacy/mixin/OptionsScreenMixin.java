package com.dragonmeow.nyanlex.legacy.mixin;

import com.dragonmeow.nyanlex.legacy.LegacyTranslatorMod;
import com.dragonmeow.nyanlex.translate.HookGuard;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the "Translation settings..." button to the vanilla Options screen. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void nyanlex$addButton(CallbackInfo ci) {
        if (!HookGuard.enter("OptionsScreen.addButton")) return;
        try {
            this.addButton(new Button(6, 6, 110, 20, new TranslatableComponent("screen.nyanlex.options"),
                    button -> LegacyTranslatorMod.openSettings(this)));
        } catch (Throwable guardError) {
            HookGuard.fail("OptionsScreen.addButton", guardError);
        }
    }
}
