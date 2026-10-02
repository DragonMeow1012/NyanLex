package com.dragonmeow.nyanslate.legacy.mixin;

import com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class DebugHudMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void nyanslate$debug(float delta, CallbackInfo ci) {
        if (!LegacyTranslatorMod.debugEnabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) return;
        boolean previous = LegacyTranslatorMod.beginInternalRender();
        try {
            int y = 6;
            minecraft.font.drawShadow(LegacyTranslatorMod.tokenUsageLine(), 6, y, 0x80D8FF);
            y += 11;
            for (String line : LegacyTranslatorMod.debugLines()) {
                int color = line.contains("failed (429 rate limit)") ? 0xFFFF40FF
                        : line.contains("failed (") ? 0xFFFF8080 : 0x80FF80;
                minecraft.font.drawShadow(line, 6, y, color);
                y += 10;
            }
        } finally { LegacyTranslatorMod.endInternalRender(previous); }
    }
}
