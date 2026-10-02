package com.dragonmeow.nyanslate.fabric26.mixin;

import com.dragonmeow.nyanslate.fabric26.Fabric26ConfigScreen;
import com.dragonmeow.nyanslate.fabric26.NyanslateFabric26;
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
   private void nyanslate$addButton(CallbackInfo ci) {
      if (NyanslateFabric26.service() != null) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("screen.nyanslate.options"), b -> this.minecraft.setScreenAndShow(new Fabric26ConfigScreen((OptionsScreen)(Object)this)))
               .bounds(6, 6, 110, 20)
               .build()
         );
      }
   }
}
