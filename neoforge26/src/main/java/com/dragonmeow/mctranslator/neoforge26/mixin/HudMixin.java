package com.dragonmeow.mctranslator.neoforge26.mixin;

import com.dragonmeow.mctranslator.neoforge26.Neo26TextStyle;
import com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
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

    @Shadow @Final
    private static Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER;

    @Shadow
    public abstract Font getFont();

    private final ArrayDeque<Component> mctranslator$scoreboardSources = new ArrayDeque<>();
    private final ArrayDeque<Component> mctranslator$scoreboardRendered = new ArrayDeque<>();

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), require = 0)
    private void mctranslator$prepareScoreboard(GuiGraphicsExtractor graphics,
                                                Objective objective, CallbackInfo ci) {
        mctranslator$scoreboardSources.clear();
        mctranslator$scoreboardRendered.clear();
        TranslationService service = MctranslatorNeoForge26.service();
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
        service.warmScoreboardBatch(mctranslator$scoreboardRequests(title, rows));

        Component translatedTitle = Neo26TextStyle.renderTranslated(
                "scoreboard", title, service::translateScoreboardLine);
        mctranslator$enqueueScoreboardRow(
                title, translatedTitle == null ? title : translatedTitle);
        List<Component> renderedRows = new ArrayList<>(rows);

        for (int i = 0; i < rows.size(); i++) {
            Component row = rows.get(i);
            Component translated = Neo26TextStyle.renderTranslated(
                    "scoreboard", row, service::translateScoreboardLine);
            if (translated != null) renderedRows.set(i, translated);
        }

        for (int i = 0; i < entries.size(); i++) {
            mctranslator$enqueueScoreboardRow(rows.get(i), renderedRows.get(i));
            mctranslator$enqueueScoreboardRow(scores.get(i), scores.get(i));
        }
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("RETURN"), require = 0)
    private void mctranslator$clearScoreboard(GuiGraphicsExtractor graphics,
                                              Objective objective, CallbackInfo ci) {
        mctranslator$scoreboardSources.clear();
        mctranslator$scoreboardRendered.clear();
    }

    private void mctranslator$enqueueScoreboardRow(Component source, Component rendered) {
        mctranslator$scoreboardSources.addLast(source);
        mctranslator$scoreboardRendered.addLast(rendered);
    }

    private static List<String> mctranslator$scoreboardRequests(
            Component title, List<Component> rows) {
        List<String> requests = new ArrayList<>();
        requests.add(Neo26TextStyle.paragraphRequestText(List.of(title)));
        for (Component row : rows) {
            requests.add(row == null || row.getString().isBlank() ? ""
                    : Neo26TextStyle.paragraphRequestText(List.of(row)));
        }
        return requests;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void mctranslator$debugRequests(GuiGraphicsExtractor graphics,
                                            DeltaTracker deltaTracker,
                                            CallbackInfo ci) {
        var log = MctranslatorNeoForge26.debugLog();
        var config = MctranslatorNeoForge26.config();
        Minecraft minecraft = Minecraft.getInstance();
        if (log == null || config == null || !config.debugTranslationOverlay
                || minecraft == null || minecraft.font == null) return;

        com.dragonmeow.mctranslator.translate.InternalRenderGuard.enter();
        try {
            List<com.dragonmeow.mctranslator.translate.TranslationDebugLog.Entry> entries = log.snapshot(5);
            Font font = minecraft.font;
            int availableWidth = Math.max(160, graphics.guiWidth() - 16);
            int maxWidth = Math.min(440,
                    Math.min(availableWidth, Math.max(220, graphics.guiWidth() / 3)));
            int lineHeight = 9;
            int x = 6;
            int y = 6;
            int height = 25 + entries.size() * lineHeight;
            graphics.fill(x - 3, y - 3, x + maxWidth + 3, y + height, 0xB0101010);

            long waiting = entries.stream().filter(e -> e.status()
                    == com.dragonmeow.mctranslator.translate.TranslationDebugLog.Status.IN_FLIGHT).count();
            long failed = entries.stream().filter(e -> e.status()
                    == com.dragonmeow.mctranslator.translate.TranslationDebugLog.Status.FAILED).count();
            long rateLimited = entries.stream().filter(e -> e.status()
                    == com.dragonmeow.mctranslator.translate.TranslationDebugLog.Status.RATE_LIMITED).count();
            String header = "MT DEBUG  最近 " + entries.size() + " 項  …" + waiting
                    + "  429×" + rateLimited + "  ✕" + failed;
            graphics.text(font, Component.literal(header), x, y, 0xFFFFD060, false);

            var tokens = MctranslatorNeoForge26.tokenUsageSnapshot();
            String tokenLine = "TOKENS total " + tokens.totalTokens()
                    + " | in " + tokens.inputTokens() + " (cached " + tokens.cachedInputTokens() + ")"
                    + " | out " + tokens.outputTokens() + " (reason " + tokens.reasoningOutputTokens() + ")"
                    + " | req " + tokens.requests();
            graphics.text(font, Component.literal(tokenLine), x, y + 11, 0xFF80D8FF, false);


            int row = y + 22;
            for (var entry : entries) {
                String state = switch (entry.status()) {
                    case IN_FLIGHT -> "…";
                    case SUCCESS -> "✓";
                    case FALLBACK -> "↪";
                    case KEEP_ORIGINAL -> "•";
                    case RATE_LIMITED -> "429";
                    case FAILED -> "✕";
                };
                String provider = "AI".equalsIgnoreCase(entry.engine()) ? "AI" : "GT";
                String prefix = "[" + provider + " #" + entry.requestId() + " " + state + "] ";
                String failureReason = entry.failureReason();
                if (failureReason == null || failureReason.isBlank()) failureReason = "unknown";
                String translated = switch (entry.status()) {
                    case IN_FLIGHT -> "等待中";
                    case RATE_LIMITED, FAILED -> "failed (" + failureReason + ")";
                    case KEEP_ORIGINAL -> "略過";
                    case SUCCESS, FALLBACK -> entry.translation() == null
                            ? "無結果" : entry.translation();
                };
                String sourceText = com.dragonmeow.mctranslator.translate.TranslationDebugLog
                        .compactText(entry.text());
                String translatedText = com.dragonmeow.mctranslator.translate.TranslationDebugLog
                        .compactText(translated);
                int bodyBudget = Math.max(40, maxWidth - font.width(prefix + "原:  → 譯: "));
                int sourceBudget = bodyBudget / 2;
                int translatedBudget = bodyBudget - sourceBudget;
                String body = "原: " + mctranslator$ellipsize(font, sourceText, sourceBudget)
                        + " → 譯: " + mctranslator$ellipsize(font, translatedText, translatedBudget);
                int color = switch (entry.status()) {
                    case IN_FLIGHT -> 0xFFFFD080;
                    case SUCCESS -> 0xFF80FF80;
                    case FALLBACK -> 0xFF80C0FF;
                    case KEEP_ORIGINAL -> 0xFFC0C0C0;
                    case RATE_LIMITED -> 0xFFFF40FF;
                    case FAILED -> 0xFFFF8080;
                };
                graphics.text(font, Component.literal(prefix + body), x, row, color, false);
                row += lineHeight;
            }
        } finally {
            com.dragonmeow.mctranslator.translate.InternalRenderGuard.exit();
        }
    }

    private static String mctranslator$ellipsize(Font font, String text, int width) {
        if (text == null || text.isEmpty() || font.width(text) <= width) return text == null ? "" : text;
        return font.plainSubstrByWidth(text, Math.max(4, width - font.width("…"))) + "…";
    }

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            require = 0)
    private void mctranslator$scoreboard(GuiGraphicsExtractor g, Font font, Component text,
                                         int x, int y, int color, boolean shadow) {
        Component next = mctranslator$scoreboardSources.peekFirst();
        Component toDraw = text;
        if (next != null && next.equals(text)) {
            mctranslator$scoreboardSources.removeFirst();
            toDraw = mctranslator$scoreboardRendered.removeFirst();
        }
        Component rendered = toDraw;
        com.dragonmeow.mctranslator.translate.InternalRenderGuard.run(
                () -> g.text(font, rendered, x, y, color, shadow));
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
                    com.dragonmeow.mctranslator.translate.InternalRenderGuard.run(
                            () -> g.textWithBackdrop(font, translated, center - w / 2, y, w, color));
                    return;
                }
                // 原文＋翻譯: stack lines upward (原文 on top, 譯文 at the baseline).
                int n = lines.size();
                for (int k = 0; k < n; k++) {
                    Component line = lines.get(k);
                    int w = font.width(line);
                    int ly = y - (n - 1 - k) * Neo26TextStyle.STACK_LINE_GAP;
                    com.dragonmeow.mctranslator.translate.InternalRenderGuard.run(
                            () -> g.textWithBackdrop(font, line, center - w / 2, ly, w, color));
                }
                return;
            }
        }
        com.dragonmeow.mctranslator.translate.InternalRenderGuard.run(
                () -> g.textWithBackdrop(font, text, x, y, width, color));
    }
}
