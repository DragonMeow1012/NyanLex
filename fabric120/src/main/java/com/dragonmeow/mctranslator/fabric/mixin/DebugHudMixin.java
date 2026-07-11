package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.fabric.MctranslatorFabric;
import com.dragonmeow.mctranslator.translate.InternalRenderGuard;
import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class DebugHudMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void mctranslator$debug(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        var cfg = MctranslatorFabric.config();
        var log = MctranslatorFabric.debugLog();
        Minecraft mc = Minecraft.getInstance();
        if (cfg == null || !cfg.debugTranslationOverlay || log == null || mc.font == null) return;
        InternalRenderGuard.run(() -> {
            Font font = mc.font;
            var entries = log.snapshot(8);
            int width = Math.min(520, Math.max(260, graphics.guiWidth() / 2));
            int x = 6, y = 6, row = y + 11;
            graphics.fill(x - 3, y - 3, x + width + 3, y + 14 + entries.size() * 10, 0xB0101010);
            graphics.drawString(font, Component.literal("MT DEBUG · " + entries.size() + " requests"), x, y, 0xFFFFD060, false);
            for (TranslationDebugLog.Entry entry : entries) {
                String state = switch (entry.status()) { case IN_FLIGHT -> "…"; case SUCCESS -> "✓"; case FALLBACK -> "↪"; case KEEP_ORIGINAL -> "="; case FAILED -> "✗"; };
                String result = entry.status() == TranslationDebugLog.Status.IN_FLIGHT ? "waiting" : entry.status() == TranslationDebugLog.Status.FAILED ? "failed" : entry.translation() == null ? "" : entry.translation();
                String text = "[" + entry.engine() + " #" + entry.requestId() + " " + state + "] " + TranslationDebugLog.compactText(entry.text()) + " -> " + TranslationDebugLog.compactText(result);
                if (font.width(text) > width) text = font.plainSubstrByWidth(text, width - font.width("…")) + "…";
                graphics.drawString(font, Component.literal(text), x, row, 0xFFFFFFFF, false);
                row += 10;
            }
        });
    }
}
