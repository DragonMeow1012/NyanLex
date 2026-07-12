package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.fabric.MctranslatorFabric;
import com.dragonmeow.mctranslator.translate.InternalRenderGuard;
import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class DebugHudMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void mctranslator$debug(PoseStack pose, float partialTick, CallbackInfo ci) {
        var cfg = MctranslatorFabric.config();
        var log = MctranslatorFabric.debugLog();
        Minecraft mc = Minecraft.getInstance();
        if (cfg == null || !cfg.debugTranslationOverlay || log == null || mc.font == null) return;
        InternalRenderGuard.run(() -> {
            Font font = mc.font;
            var entries = log.snapshot(8);
            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int width = Math.min(520, Math.max(260, screenWidth / 2));
            int x = 6, y = 6, row = y + 11;
            GuiComponent.fill(pose, x - 3, y - 3, x + width + 3, y + 14 + entries.size() * 10, 0xB0101010);
            font.draw(pose, "MT DEBUG · " + entries.size() + " requests", x, y, 0xFFFFD060);
            for (TranslationDebugLog.Entry entry : entries) {
                String state = switch (entry.status()) { case IN_FLIGHT -> "…"; case SUCCESS -> "✓"; case FALLBACK -> "↪"; case KEEP_ORIGINAL -> "="; case RATE_LIMITED -> "429"; case FAILED -> "✗"; };
                String failureReason = entry.failureReason();
                if (failureReason == null || failureReason.isBlank()) failureReason = "unknown";
                String result = entry.status() == TranslationDebugLog.Status.IN_FLIGHT ? "waiting" : entry.status() == TranslationDebugLog.Status.RATE_LIMITED || entry.status() == TranslationDebugLog.Status.FAILED ? "failed (" + failureReason + ")" : entry.translation() == null ? "" : entry.translation();
                String text = "[" + entry.engine() + " #" + entry.requestId() + " " + state + "] " + TranslationDebugLog.compactText(entry.text()) + " -> " + TranslationDebugLog.compactText(result);
                if (font.width(text) > width) text = font.plainSubstrByWidth(text, Math.max(8, width - font.width("…"))) + "…";
                int color = entry.status() == TranslationDebugLog.Status.RATE_LIMITED ? 0xFFFF40FF : 0xFFFFFFFF;
                font.draw(pose, text, x, row, color);
                row += 10;
            }
        });
    }
}
