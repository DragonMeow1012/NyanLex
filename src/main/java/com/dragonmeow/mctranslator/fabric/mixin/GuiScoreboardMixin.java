package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.fabric.MctranslatorFabric;
import com.dragonmeow.mctranslator.fabric.FabricTextStyle;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;
import com.dragonmeow.mctranslator.translate.ParagraphModel;

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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
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

    /**
     * Translations prepared for the scoreboard currently being rendered.  Vanilla's
     * actual draw calls live in a synthetic lambda and expose only one row at a time;
     * preparing the complete ordered entry list at the outer method is what lets blank
     * rows remain hard paragraph boundaries and prevents accidental per-line requests.
     *
     * <p>The deque mirrors Vanilla's exact title/name/score draw order, so duplicate
     * text (including a numeric fake-player row equal to a score value) cannot consume
     * another row's translation. It is render-thread state and is cleared at both
     * method boundaries.</p>
     */
    private final ArrayDeque<Component> mctranslator$scoreboardSources = new ArrayDeque<>();
    private final ArrayDeque<Component> mctranslator$scoreboardRendered = new ArrayDeque<>();

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), require = 0)
    private void mctranslator$prepareScoreboard(GuiGraphics graphics, Objective objective,
                                                CallbackInfo ci) {
        mctranslator$scoreboardSources.clear();
        mctranslator$scoreboardRendered.clear();
        TranslationService service = MctranslatorFabric.service();
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
        List<String> rowStrings = rows.stream().map(Component::getString).toList();
        List<ParagraphModel.Range> rowRanges = ParagraphModel.ranges(rowStrings);
        service.warmScoreboardBatch(
                mctranslator$scoreboardRequests(title, rows, rowStrings, rowRanges));

        Component translatedTitle = FabricTextStyle.renderTranslated(
                "scoreboard", title, service::translateScoreboardLine);
        mctranslator$enqueueScoreboardRow(
                title, translatedTitle == null ? title : translatedTitle);
        List<Component> renderedRows = new ArrayList<>(rows);

        Font font = getFont();
        for (ParagraphModel.Range range : rowRanges) {
            int start = range.start();
            if (ParagraphModel.isBlank(rowStrings.get(start))) {
                // A blank scoreboard entry is layout, not a translation unit.
                continue;
            }
            int end = range.end() + 1;

            List<Component> paragraph = new ArrayList<>(rows.subList(start, end));
            List<Component> translated = FabricTextStyle.renderTranslatedParagraph(
                    paragraph, service::translateScoreboardLine, font);
            // PB markers are required to round-trip one-for-one.  Never partially fill
            // a paragraph if a backend damaged a boundary or wrapping changed row count.
            if (translated != null && translated.size() == paragraph.size()) {
                for (int i = 0; i < paragraph.size(); i++) {
                    Component rendered = translated.get(i);
                    if (service.scoreboardMode() == DisplayMode.BOTH) {
                        rendered = paragraph.get(i).copy()
                                .append(Component.literal("\u3000"))
                                .append(rendered);
                    }
                    renderedRows.set(start + i, rendered);
                }
            }
        }

        for (int i = 0; i < entries.size(); i++) {
            mctranslator$enqueueScoreboardRow(rows.get(i), renderedRows.get(i));
            // Score values are dynamic layout fields, never an independent translation
            // unit. Keeping them in the draw sequence prevents equal strings colliding.
            mctranslator$enqueueScoreboardRow(scores.get(i), scores.get(i));
        }
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("RETURN"), require = 0)
    private void mctranslator$clearScoreboard(GuiGraphics graphics, Objective objective,
                                              CallbackInfo ci) {
        mctranslator$scoreboardSources.clear();
        mctranslator$scoreboardRendered.clear();
    }

    private void mctranslator$enqueueScoreboardRow(Component source, Component rendered) {
        mctranslator$scoreboardSources.addLast(source);
        mctranslator$scoreboardRendered.addLast(rendered);
    }

    private static List<String> mctranslator$scoreboardRequests(
            Component title, List<Component> rows, List<String> rowStrings,
            List<ParagraphModel.Range> rowRanges) {
        List<String> requests = new ArrayList<>();
        requests.add(FabricTextStyle.paragraphRequestText(List.of(title)));
        for (ParagraphModel.Range range : rowRanges) {
            int start = range.start();
            if (ParagraphModel.isBlank(rowStrings.get(start))) {
                requests.add("");
                continue;
            }
            requests.add(FabricTextStyle.paragraphRequestText(
                    rows.subList(start, range.end() + 1)));
        }
        return requests;
    }

    private Component mctranslator$takeScoreboardRow(Component source) {
        Component next = mctranslator$scoreboardSources.peekFirst();
        if (next == null || !next.equals(source)) return null;
        mctranslator$scoreboardSources.removeFirst();
        return mctranslator$scoreboardRendered.removeFirst();
    }

    @Redirect(
            // 1.21.1's drawManaged lambda body keeps Yarn's stable intermediary
            // name in the layered Mojang mapping; targeting it directly avoids a
            // wildcard selector that the annotation processor cannot verify.
            method = "method_55440",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0)
    private int mctranslator$scoreboard(GuiGraphics g, Font font, Component text,
                                        int x, int y, int color, boolean shadow) {
        // Do not fall back to translating this isolated row: the outer hook already
        // queued its complete blank-line-delimited paragraph (or intentionally kept it).
        Component translated = text == null ? null : mctranslator$takeScoreboardRow(text);
        Component toDraw = translated == null ? text : translated;
        Component rendered = toDraw;
        return com.dragonmeow.mctranslator.translate.InternalRenderGuard.call(
                () -> g.drawString(font, rendered, x, y, color, shadow));
    }

    @Redirect(
            method = "renderSelectedItemName",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int mctranslator$heldName(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorFabric.service();
        return mctranslator$backdrop("held", g, font, text, x, y, width, color, s == null ? null : s::translateHeld);
    }

    @Redirect(
            method = "renderTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int mctranslator$title(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorFabric.service();
        return mctranslator$backdrop("title", g, font, text, x, y, width, color, s == null ? null : s::translateTitle);
    }

    @Redirect(
            method = "renderOverlayMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"),
            require = 0)
    private int mctranslator$actionBar(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        TranslationService s = MctranslatorFabric.service();
        return mctranslator$backdrop("actionBar", g, font, text, x, y, width, color, s == null ? null : s::translateActionBar);
    }

    /** Centred backdrop draw shared by held name / title / subtitle / action bar; re-centres the
     *  translation, and stacks 原文／譯文 upward when the surface produced a 2-line (\n) component. */
    private static int mctranslator$backdrop(String surfaceId, GuiGraphics g, Font font, Component text,
                                             int x, int y, int width, int color,
                                             Function<String, TranslationDecision> fn) {
        if (fn != null && text != null) {
            Component translated = FabricTextStyle.renderTranslated(surfaceId, text, fn);
            if (translated != null) {
                int center = x + font.width(text) / 2; // keep the original's centre
                java.util.List<Component> lines = FabricTextStyle.splitLines(translated);
                if (lines.size() <= 1) {
                    int w = font.width(translated);
                    return com.dragonmeow.mctranslator.translate.InternalRenderGuard.call(
                            () -> g.drawStringWithBackdrop(font, translated, center - w / 2, y, w, color));
                }
                // 原文＋翻譯: stack lines upward (原文 on top, 譯文 at the baseline).
                int n = lines.size();
                int ret = 0;
                for (int k = 0; k < n; k++) {
                    Component line = lines.get(k);
                    int w = font.width(line);
                    int ly = y - (n - 1 - k) * FabricTextStyle.STACK_LINE_GAP;
                    ret = com.dragonmeow.mctranslator.translate.InternalRenderGuard.call(
                            () -> g.drawStringWithBackdrop(font, line, center - w / 2, ly, w, color));
                }
                return ret;
            }
        }
        return com.dragonmeow.mctranslator.translate.InternalRenderGuard.call(
                () -> g.drawStringWithBackdrop(font, text, x, y, width, color));
    }
}
