package com.dragonmeow.mctranslator.fabric26.mixin;

import com.dragonmeow.mctranslator.fabric26.Fabric26ConfigScreen;
import com.dragonmeow.mctranslator.fabric26.MctranslatorFabric26;
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
      if (MctranslatorFabric26.service() != null) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.mctranslator.options"), b -> this.minecraft.setScreenAndShow(new Fabric26ConfigScreen((OptionsScreen)(Object)this)))
               .bounds(6, 6, 110, 20)
               .build()
         );
      }
   }
}
