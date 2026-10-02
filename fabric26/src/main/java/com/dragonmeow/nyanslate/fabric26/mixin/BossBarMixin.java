package com.dragonmeow.nyanslate.fabric26.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.fabric26.Fabric26TextStyle;
import com.dragonmeow.nyanslate.fabric26.NyanslateFabric26;
import com.dragonmeow.nyanslate.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Translates boss-bar names by intercepting the {@code text(Font, Component, int, int, int)} call
 * inside 26.2's {@code BossHealthOverlay#extractRenderState}. The name is centred over the bar, so
 * the translation is re-centred. Non-blocking + memoised; gated by {@code bossBarMode}.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossBarMixin {

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
            require = 0)
    private void nyanslate$bossBar(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        if (!HookGuard.enter("BossBar.bossBar")) {
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.text(font, text, x, y, color));
            return;
        }
        try {
            TranslationService service = NyanslateFabric26.service();
            if (service != null && text != null) {
                Component translated = Fabric26TextStyle.renderTranslated("bossBar", text, service::translateBossBar);
                if (translated != null) {
                    int center = x + font.width(text) / 2; // original name's centre
                    List<Component> lines = Fabric26TextStyle.splitLines(translated);
                    if (lines.size() <= 1) {
                        com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(
                                () -> g.text(font, translated, center - font.width(translated) / 2, y, color));
                        return;
                    }
                    // 原文＋翻譯: stack the lines upward above the bar (原文 on top, 譯文 at the baseline).
                    int n = lines.size();
                    for (int k = 0; k < n; k++) {
                        Component line = lines.get(k);
                        int ly = y - (n - 1 - k) * Fabric26TextStyle.STACK_LINE_GAP;
                        com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(
                                () -> g.text(font, line, center - font.width(line) / 2, ly, color));
                    }
                    return;
                }
            }
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(
                    () -> g.text(font, text, x, y, color));
        } catch (Throwable guardError) {
            HookGuard.fail("BossBar.bossBar", guardError);
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.text(font, text, x, y, color));
        }
    }
}
