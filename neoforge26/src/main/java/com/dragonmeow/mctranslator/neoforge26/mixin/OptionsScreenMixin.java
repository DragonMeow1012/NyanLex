package com.dragonmeow.mctranslator.neoforge26.mixin;

import com.dragonmeow.mctranslator.neoforge26.Neo26ConfigScreen;
import com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26;
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
   private void mctranslator$addButton(CallbackInfo ci) {
      if (MctranslatorNeoForge26.service() != null) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.mctranslator.options"), b -> this.minecraft.setScreenAndShow(new Neo26ConfigScreen((OptionsScreen)(Object)this)))
               .bounds(6, 6, 110, 20)
               .build()
         );
      }
   }
}
