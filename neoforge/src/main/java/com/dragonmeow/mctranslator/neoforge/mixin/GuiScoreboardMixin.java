package com.dragonmeow.mctranslator.neoforge.mixin;

import com.dragonmeow.mctranslator.neoforge.MctranslatorNeoForge;
import com.dragonmeow.mctranslator.neoforge.NeoTextStyle;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/**
 * Translates the on-screen text surfaces drawn by {@code Gui}:
 * <ul>
 *   <li>scoreboard sidebar — the {@code drawString} calls live inside the
 *       {@code drawManaged(Runnable)} lambda of {@code displayScoreboardSidebar},
 *       which javac compiles to a synthetic {@code lambda$displayScoreboardSidebar$N}
 *       method, so the redirect must target the lambda, not the outer method;</li>
 *   <li>held-item name — {@code renderSelectedItemName} (drawStringWithBackdrop);</li>
 *   <li>title / subtitle — {@code renderTitle} (drawStringWithBackdrop, both calls);</li>
 *   <li>action-bar / overlay message — {@code renderOverlayMessage} (drawStringWithBackdrop).</li>
 * </ul>
 * Non-blocking + memoised per surface; mode/engine gating happens inside the service.
 */
@Mixin(Gui.class)
public abstract class GuiScoreboardMixin {

    @Redirect(
            method = "lambda$displayScoreboardSidebar$*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0)
    private int mctranslator$scoreboard(GuiGraphics g, Font font, Component text,
                                        int x, int y, int color, boolean shadow) {
        Component toDraw = text;
        TranslationService service = MctranslatorNeoForge.service();
        if (service != null && text != null) {
            Component t = NeoTextStyle.renderTranslated("scoreboard", text, service::translateScoreboardLine);
            if (t != null) toDraw = t;
        }
        return g.drawString(font, toDraw, x, y, color, shadow);
    }

    @Redirect(
            method = "renderSelectedItemName",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int mctranslator$heldName(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorNeoForge.service();
        return mctranslator$backdrop("held", g, font, text, x, y, width, color, s == null ? null : s::translateHeld);
    }

    @Redirect(
            method = "renderTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int mctranslator$title(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorNeoForge.service();
        return mctranslator$backdrop("title", g, font, text, x, y, width, color, s == null ? null : s::translateTitle);
    }

    @Redirect(
            method = "renderOverlayMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int mctranslator$actionBar(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorNeoForge.service();
        return mctranslator$backdrop("actionBar", g, font, text, x, y, width, color, s == null ? null : s::translateActionBar);
    }

    /** Centred backdrop draw shared by held name / title / subtitle / action bar; re-centres the
     *  translation, and stacks 原文／譯文 upward when the surface produced a 2-line (\n) component. */
    private static int mctranslator$backdrop(String surfaceId, GuiGraphics g, Font font, Component text,
                                             int x, int y, int width, int color,
                                             Function<String, TranslationDecision> fn) {
        if (fn != null && text != null) {
            Component translated = NeoTextStyle.renderTranslated(surfaceId, text, fn);
            if (translated != null) {
                int center = x + font.width(text) / 2; // keep the original's centre
                java.util.List<Component> lines = NeoTextStyle.splitLines(translated);
                if (lines.size() <= 1) {
                    int w = font.width(translated);
                    return g.drawStringWithBackdrop(font, translated, center - w / 2, y, w, color);
                }
                // 原文＋翻譯: stack lines upward (原文 on top, 譯文 at the baseline).
                int n = lines.size();
                int ret = 0;
                for (int k = 0; k < n; k++) {
                    Component line = lines.get(k);
                    int w = font.width(line);
                    int ly = y - (n - 1 - k) * NeoTextStyle.STACK_LINE_GAP;
                    ret = g.drawStringWithBackdrop(font, line, center - w / 2, ly, w, color);
                }
                return ret;
            }
        }
        return g.drawStringWithBackdrop(font, text, x, y, width, color);
    }
}
