package com.dragonmeow.mctranslator.legacy.mixin;

import com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class DebugHudMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void mctranslator$debug(PoseStack pose, float delta, CallbackInfo ci) {
        if (!LegacyTranslatorMod.debugEnabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) return;
        boolean previous = LegacyTranslatorMod.beginInternalRender();
        try {
            java.util.List<String> lines = LegacyTranslatorMod.debugLines();
            int y = 6;
            for (String line : lines) {
                minecraft.font.drawShadow(pose, line, 6, y, 0x80FF80);
                y += 10;
            }
        } finally { LegacyTranslatorMod.endInternalRender(previous); }
    }
}
