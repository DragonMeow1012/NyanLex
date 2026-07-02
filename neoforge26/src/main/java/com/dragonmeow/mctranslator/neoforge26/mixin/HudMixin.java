package com.dragonmeow.mctranslator.neoforge26.mixin;

import com.dragonmeow.mctranslator.neoforge26.Neo26TextStyle;
import com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Function;

/**
 * Translates the on-screen HUD text surfaces drawn by 26.2's {@code Hud}:
 * scoreboard sidebar ({@code displayScoreboardSidebar}), held-item name
 * ({@code extractSelectedItemName}), title / subtitle ({@code extractTitle}) and the
 * action-bar / overlay message ({@code extractOverlayMessage}). Each redirects the
 * {@code GuiGraphicsExtractor.text}/{@code textWithBackdrop} call so the per-surface mode + engine
 * gating applies. Non-blocking + memoised inside the service. The generic
 * {@link GuiGraphicsTextMixin} is gated to screen-open only, so it never double-handles the HUD.
 */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            require = 0)
    private void mctranslator$scoreboard(GuiGraphicsExtractor g, Font font, Component text,
                                         int x, int y, int color, boolean shadow) {
        Component toDraw = text;
        TranslationService service = MctranslatorNeoForge26.service();
        if (service != null && text != null) {
            Component t = Neo26TextStyle.renderTranslated("scoreboard", text, service::translateScoreboardLine);
            if (t != null) toDraw = t;
        }
        g.text(font, toDraw, x, y, color, shadow);
    }

    @Redirect(
            method = "extractSelectedItemName",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            require = 0)
    private void mctranslator$heldName(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorNeoForge26.service();
        mctranslator$backdrop("held", g, font, text, x, y, width, color, s == null ? null : s::translateHeld);
    }

    @Redirect(
            method = "extractTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            require = 0)
    private void mctranslator$title(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorNeoForge26.service();
        mctranslator$backdrop("title", g, font, text, x, y, width, color, s == null ? null : s::translateTitle);
    }

    @Redirect(
            method = "extractOverlayMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            require = 0)
    private void mctranslator$actionBar(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorNeoForge26.service();
        mctranslator$backdrop("actionBar", g, font, text, x, y, width, color, s == null ? null : s::translateActionBar);
    }

    /** Centred backdrop draw shared by held name / title / subtitle / action bar; re-centres the
     *  translation, and stacks 原文／譯文 upward when the surface produced a 2-line (\n) component. */
    private static void mctranslator$backdrop(String surfaceId, GuiGraphicsExtractor g, Font font, Component text,
                                              int x, int y, int width, int color,
                                              Function<String, TranslationDecision> fn) {
        if (fn != null && text != null) {
            Component translated = Neo26TextStyle.renderTranslated(surfaceId, text, fn);
            if (translated != null) {
                int center = x + font.width(text) / 2; // keep the original's centre
                List<Component> lines = Neo26TextStyle.splitLines(translated);
                if (lines.size() <= 1) {
                    int w = font.width(translated);
                    g.textWithBackdrop(font, translated, center - w / 2, y, w, color);
                    return;
                }
                // 原文＋翻譯: stack lines upward (原文 on top, 譯文 at the baseline).
                int n = lines.size();
                for (int k = 0; k < n; k++) {
                    Component line = lines.get(k);
                    int w = font.width(line);
                    int ly = y - (n - 1 - k) * Neo26TextStyle.STACK_LINE_GAP;
                    g.textWithBackdrop(font, line, center - w / 2, ly, w, color);
                }
                return;
            }
        }
        g.textWithBackdrop(font, text, x, y, width, color);
    }
}
