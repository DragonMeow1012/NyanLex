package com.dragonmeow.nyanslate.neoforge.mixin;

import com.dragonmeow.nyanslate.neoforge.NyanslateNeoForge;
import com.dragonmeow.nyanslate.neoforge.NeoTextStyle;
import com.dragonmeow.nyanslate.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Translates boss-bar names by intercepting the {@code drawString(Font, Component, int, int, int)}
 * call inside {@code BossHealthOverlay#render}. The name is centred over the bar, so the
 * translation is re-centred. Non-blocking + memoised; gated by {@code bossBarMode}.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossBarMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 0)
    private int nyanslate$bossBar(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        TranslationService service = NyanslateNeoForge.service();
        if (service != null && text != null) {
            Component translated = NeoTextStyle.renderTranslated("bossBar", text, service::translateBossBar);
            if (translated != null) {
                int center = x + font.width(text) / 2; // original name's centre
                java.util.List<Component> lines = NeoTextStyle.splitLines(translated);
                if (lines.size() <= 1) {
                    return com.dragonmeow.nyanslate.translate.InternalRenderGuard.call(
                            () -> g.drawString(font, translated, center - font.width(translated) / 2, y, color));
                }
                // 原文＋翻譯: stack the lines upward above the bar (原文 on top, 譯文 at the baseline).
                int n = lines.size();
                int ret = 0;
                for (int k = 0; k < n; k++) {
                    Component line = lines.get(k);
                    int ly = y - (n - 1 - k) * NeoTextStyle.STACK_LINE_GAP;
                    ret = com.dragonmeow.nyanslate.translate.InternalRenderGuard.call(
                            () -> g.drawString(font, line, center - font.width(line) / 2, ly, color));
                }
                return ret;
            }
        }
        return com.dragonmeow.nyanslate.translate.InternalRenderGuard.call(
                () -> g.drawString(font, text, x, y, color));
    }
}
