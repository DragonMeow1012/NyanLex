package com.dragonmeow.nyanslate.neoforge.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.neoforge.NyanslateNeoForge;
import com.dragonmeow.nyanslate.translate.InternalRenderGuard;
import com.dragonmeow.nyanslate.translate.TranslationDebugLog;
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
    private void nyanslate$debug(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        if (!HookGuard.enter("DebugHud.debug")) return;
        try {
            var cfg = NyanslateNeoForge.config();
            var log = NyanslateNeoForge.debugLog();
            Minecraft mc = Minecraft.getInstance();
            if (cfg == null || !cfg.debugTranslationOverlay || log == null || mc.font == null) return;
            InternalRenderGuard.run(() -> {
                Font font = mc.font;
                var entries = log.snapshot(8);
                int width = Math.min(520, Math.max(260, graphics.guiWidth() / 2));
                int x = 6, y = 6, row = y + 22;
                graphics.fill(x - 3, y - 3, x + width + 3, y + 25 + entries.size() * 10, 0xB0101010);
                graphics.drawString(font, Component.literal("MT DEBUG · " + entries.size() + " requests"), x, y, 0xFFFFD060, false);

                var tokens = NyanslateNeoForge.tokenUsageSnapshot();
                String tokenLine = "TOKENS total " + tokens.totalTokens()
                        + " | in " + tokens.inputTokens() + " (cached " + tokens.cachedInputTokens() + ")"
                        + " | out " + tokens.outputTokens() + " (reason " + tokens.reasoningOutputTokens() + ")"
                        + " | req " + tokens.requests() + " | " + com.dragonmeow.nyanslate.translate.HookHealth.shortSummary();
                graphics.drawString(font, Component.literal(tokenLine), x, y + 11, 0xFF80D8FF, false);

                for (TranslationDebugLog.Entry entry : entries) {
                    String state = switch (entry.status()) { case IN_FLIGHT -> "…"; case SUCCESS -> "✓"; case FALLBACK -> "↪"; case KEEP_ORIGINAL -> "="; case RATE_LIMITED -> "429"; case FAILED -> "✗"; };
                    String failureReason = entry.failureReason();
                    if (failureReason == null || failureReason.isBlank()) failureReason = "unknown";
                    String result = entry.status() == TranslationDebugLog.Status.IN_FLIGHT ? "waiting" : entry.status() == TranslationDebugLog.Status.RATE_LIMITED || entry.status() == TranslationDebugLog.Status.FAILED ? "failed (" + failureReason + ")" : entry.translation() == null ? "" : entry.translation();
                    String text = "[" + entry.engine() + " #" + entry.requestId() + " " + state + "] " + TranslationDebugLog.compactText(entry.text()) + " -> " + TranslationDebugLog.compactText(result);
                    if (font.width(text) > width) text = font.plainSubstrByWidth(text, width - font.width("…")) + "…";
                    int color = entry.status() == TranslationDebugLog.Status.RATE_LIMITED ? 0xFFFF40FF : 0xFFFFFFFF;
                    graphics.drawString(font, Component.literal(text), x, row, color, false);
                    row += 10;
                }
            });
        } catch (Throwable guardError) {
            HookGuard.fail("DebugHud.debug", guardError);
        }
    }
}
