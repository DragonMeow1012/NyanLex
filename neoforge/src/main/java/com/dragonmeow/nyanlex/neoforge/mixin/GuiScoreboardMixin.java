package com.dragonmeow.nyanlex.neoforge.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge.NyanLexNeoForge;
import com.dragonmeow.nyanlex.neoforge.NeoTextStyle;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    @Shadow @Final
    private static Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER;

    @Shadow
    public abstract Font getFont();

    /** Full render-order scoreboard state; the lambda redirect alone sees only one row. */
    private final ArrayDeque<Component> nyanlex$scoreboardSources = new ArrayDeque<>();
    private final ArrayDeque<Component> nyanlex$scoreboardRendered = new ArrayDeque<>();

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), require = 0)
    private void nyanlex$prepareScoreboard(GuiGraphics graphics, Objective objective,
                                                CallbackInfo ci) {
        HookGuard.enterSticky("GuiScoreboard.prepareScoreboard");
        try {
            nyanlex$scoreboardSources.clear();
            nyanlex$scoreboardRendered.clear();
            TranslationService service = NyanLexNeoForge.service();
            if (service == null || objective == null) return;

            Component title = objective.getDisplayName();
            Scoreboard scoreboard = objective.getScoreboard();
            NumberFormat numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
            List<PlayerScoreEntry> entries = scoreboard.listPlayerScores(objective).stream()
                    .filter(entry -> !entry.isHidden())
                    .sorted(SCORE_DISPLAY_ORDER)
                    .limit(15L)
                    .toList();
            List<Component> rows = entries.stream()
                    .map(entry -> (Component) PlayerTeam.formatNameForTeam(
                            scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()))
                    .toList();
            List<Component> scores = entries.stream()
                    .map(entry -> (Component) entry.formatValue(numberFormat))
                    .toList();
            service.warmScoreboardBatch(nyanlex$scoreboardRequests(title, rows));

            Component translatedTitle = NeoTextStyle.renderTranslated(
                    "scoreboard", title, service::translateScoreboardLine);
            nyanlex$enqueueScoreboardRow(
                    title, translatedTitle == null ? title : translatedTitle);
            List<Component> renderedRows = new ArrayList<>(rows);

            for (int i = 0; i < rows.size(); i++) {
                Component row = rows.get(i);
                Component translated = NeoTextStyle.renderTranslated(
                        "scoreboard", row, service::translateScoreboardLine);
                if (translated != null) renderedRows.set(i, translated);
            }

            for (int i = 0; i < entries.size(); i++) {
                nyanlex$enqueueScoreboardRow(rows.get(i), renderedRows.get(i));
                nyanlex$enqueueScoreboardRow(scores.get(i), scores.get(i));
            }
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.prepareScoreboard", guardError);
        }
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("RETURN"), require = 0)
    private void nyanlex$clearScoreboard(GuiGraphics graphics, Objective objective,
                                              CallbackInfo ci) {
        HookGuard.enterSticky("GuiScoreboard.clearScoreboard");
        try {
            nyanlex$scoreboardSources.clear();
            nyanlex$scoreboardRendered.clear();
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.clearScoreboard", guardError);
        }
    }

    private void nyanlex$enqueueScoreboardRow(Component source, Component rendered) {
        nyanlex$scoreboardSources.addLast(source);
        nyanlex$scoreboardRendered.addLast(rendered);
    }

    private static List<String> nyanlex$scoreboardRequests(
            Component title, List<Component> rows) {
        List<String> requests = new ArrayList<>();
        requests.add(NeoTextStyle.paragraphRequestText(List.of(title)));
        for (Component row : rows) {
            requests.add(row == null || row.getString().isBlank() ? ""
                    : NeoTextStyle.paragraphRequestText(List.of(row)));
        }
        return requests;
    }

    private Component nyanlex$takeScoreboardRow(Component source) {
        Component next = nyanlex$scoreboardSources.peekFirst();
        if (next == null || !next.equals(source)) return null;
        nyanlex$scoreboardSources.removeFirst();
        return nyanlex$scoreboardRendered.removeFirst();
    }

    @Redirect(
            method = "lambda$displayScoreboardSidebar$*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0)
    private int nyanlex$scoreboard(GuiGraphics g, Font font, Component text,
                                        int x, int y, int color, boolean shadow) {
        if (!HookGuard.enter("GuiScoreboard.scoreboard")) return g.drawString(font, text, x, y, color, shadow);
        try {
            Component translated = text == null ? null : nyanlex$takeScoreboardRow(text);
            Component toDraw = translated == null ? text : translated;
            Component rendered = toDraw;
            return com.dragonmeow.nyanlex.translate.InternalRenderGuard.call(
                    () -> g.drawString(font, rendered, x, y, color, shadow));
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.scoreboard", guardError);
            return g.drawString(font, text, x, y, color, shadow);
        }
    }

    @Redirect(
            method = "renderSelectedItemName",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int nyanlex$heldName(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        if (!HookGuard.enter("GuiScoreboard.heldName")) return g.drawStringWithBackdrop(font, text, x, y, width, color);
        try {
            TranslationService s = NyanLexNeoForge.service();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                NyanLexNeoForge.registerItemEntity(mc.player.getMainHandItem());
            }
            return nyanlex$backdrop("held", g, font, text, x, y, width, color, s == null ? null : s::translateHeld);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.heldName", guardError);
            return g.drawStringWithBackdrop(font, text, x, y, width, color);
        }
    }

    @Redirect(
            method = "renderTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int nyanlex$title(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        if (!HookGuard.enter("GuiScoreboard.title")) return g.drawStringWithBackdrop(font, text, x, y, width, color);
        try {
            TranslationService s = NyanLexNeoForge.service();
            return nyanlex$backdrop("title", g, font, text, x, y, width, color, s == null ? null : s::translateTitle);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.title", guardError);
            return g.drawStringWithBackdrop(font, text, x, y, width, color);
        }
    }

    @Redirect(
            method = "renderOverlayMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int nyanlex$actionBar(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        if (!HookGuard.enter("GuiScoreboard.actionBar")) return g.drawStringWithBackdrop(font, text, x, y, width, color);
        try {
            TranslationService s = NyanLexNeoForge.service();
            return nyanlex$backdrop("actionBar", g, font, text, x, y, width, color, s == null ? null : s::translateActionBar);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.actionBar", guardError);
            return g.drawStringWithBackdrop(font, text, x, y, width, color);
        }
    }

    /** Centred backdrop draw shared by held name / title / subtitle / action bar; re-centres the
     *  translation, and stacks 原文／譯文 upward when the surface produced a 2-line (\n) component. */
    private static int nyanlex$backdrop(String surfaceId, GuiGraphics g, Font font, Component text,
                                             int x, int y, int width, int color,
                                             Function<String, TranslationDecision> fn) {
        if (fn != null && text != null) {
            Component translated = NeoTextStyle.renderTranslated(surfaceId, text, fn);
            if (translated != null) {
                int center = x + font.width(text) / 2; // keep the original's centre
                java.util.List<Component> lines = NeoTextStyle.splitLines(translated);
                if (lines.size() <= 1) {
                    int w = font.width(translated);
                    return com.dragonmeow.nyanlex.translate.InternalRenderGuard.call(
                            () -> g.drawStringWithBackdrop(font, translated, center - w / 2, y, w, color));
                }
                // 原文＋翻譯: stack lines upward (原文 on top, 譯文 at the baseline).
                int n = lines.size();
                int ret = 0;
                for (int k = 0; k < n; k++) {
                    Component line = lines.get(k);
                    int w = font.width(line);
                    int ly = y - (n - 1 - k) * NeoTextStyle.STACK_LINE_GAP;
                    ret = com.dragonmeow.nyanlex.translate.InternalRenderGuard.call(
                            () -> g.drawStringWithBackdrop(font, line, center - w / 2, ly, w, color));
                }
                return ret;
            }
        }
        return com.dragonmeow.nyanlex.translate.InternalRenderGuard.call(
                () -> g.drawStringWithBackdrop(font, text, x, y, width, color));
    }
}
