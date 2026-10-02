package com.dragonmeow.nyanlex.neoforge.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge.NyanLexNeoForge;
import com.dragonmeow.nyanlex.neoforge.TranslationConfigScreen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "翻譯設定..." button to the vanilla Options screen that opens the per-surface
 * {@link TranslationConfigScreen}. A Mixin adds the button.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void nyanlex$addToggle(CallbackInfo ci) {
        if (!HookGuard.enter("OptionsScreen.addToggle")) return;
        try {
            if (NyanLexNeoForge.service() == null) return;
            Button button = Button.builder(
                    Component.translatable("screen.nyanlex.options"),
                    b -> this.minecraft.setScreen(new TranslationConfigScreen((OptionsScreen) (Object) this))
            ).bounds(6, 6, 110, 20).build();
            this.addRenderableWidget(button);
        } catch (Throwable guardError) {
            HookGuard.fail("OptionsScreen.addToggle", guardError);
        }
    }
}
