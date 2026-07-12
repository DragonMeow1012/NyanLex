package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.fabric.MctranslatorFabric;
import com.dragonmeow.mctranslator.translate.InternalRenderGuard;
import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Request diagnostics drawn after the vanilla HUD; guarded from generic UI translation. */
@Mixin(Gui.class)
public abstract class DebugHudMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void mctranslator$debug(GuiGraphics graphics, DeltaTracker tracker, CallbackInfo ci) {
        var config = MctranslatorFabric.config();
        var log = MctranslatorFabric.debugLog();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || !config.debugTranslationOverlay || log == null || mc.font == null) return;
        InternalRenderGuard.run(() -> draw(graphics, mc.font, log.snapshot(8)));
    }

    private static void draw(GuiGraphics graphics, Font font, List<TranslationDebugLog.Entry> entries) {
        int width = Math.min(520, Math.max(260, graphics.guiWidth() / 2));
        int x = 6, y = 6, line = 10;
        graphics.fill(x - 3, y - 3, x + width + 3, y + 14 + entries.size() * line, 0xB0101010);
        long batches = entries.stream().map(TranslationDebugLog.Entry::requestId).distinct().count();
        graphics.drawString(font, Component.literal(
                "MT DEBUG · HTTP " + batches + " 批 / " + entries.size() + " 項"),
                x, y, 0xFFFFD060, false);
        int row = y + 11;
        for (TranslationDebugLog.Entry entry : entries) {
            String state = switch (entry.status()) {
                case IN_FLIGHT -> "…";
                case SUCCESS -> "✓";
                case FALLBACK -> "↪";
                case KEEP_ORIGINAL -> "=";
                case FAILED -> "✗";
            };
            String result = entry.status() == TranslationDebugLog.Status.IN_FLIGHT ? "waiting"
                    : entry.status() == TranslationDebugLog.Status.FAILED ? "failed"
                    : entry.translation() == null ? "" : entry.translation();
            String text = "[" + entry.engine() + " #" + entry.requestId() + " " + state + "] "
                    + TranslationDebugLog.compactText(entry.text()) + " -> "
                    + TranslationDebugLog.compactText(result);
            text = ellipsize(font, text, width);
            int color = switch (entry.status()) {
                case IN_FLIGHT -> 0xFFFFD080;
                case SUCCESS -> 0xFF80FF80;
                case FALLBACK -> 0xFF80C0FF;
                case KEEP_ORIGINAL -> 0xFFC0C0C0;
                case FAILED -> 0xFFFF8080;
            };
            graphics.drawString(font, Component.literal(text), x, row, color, false);
            row += line;
        }
    }

    private static String ellipsize(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(8, width - font.width("…"))) + "…";
    }
}
