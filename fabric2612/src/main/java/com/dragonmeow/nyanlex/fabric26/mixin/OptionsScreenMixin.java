package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric26.Fabric26ConfigScreen;
import com.dragonmeow.nyanlex.fabric26.NyanLexFabric26;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
   protected OptionsScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "init", at = @At("TAIL"), require = 0)
   private void nyanlex$addButton(CallbackInfo ci) {
        if (!HookGuard.enter("OptionsScreen.addButton")) return;
        try {
          if (NyanLexFabric26.service() != null) {
             this.addRenderableWidget(
                Button.builder(Component.translatable("screen.nyanlex.options"), b -> this.minecraft.setScreenAndShow(new Fabric26ConfigScreen((OptionsScreen)(Object)this)))
                   .bounds(6, 6, 110, 20)
                   .build()
             );
          }
        } catch (Throwable guardError) {
            HookGuard.fail("OptionsScreen.addButton", guardError);
        }
    }
}
