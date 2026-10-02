package com.dragonmeow.nyanslate.fabric26.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.fabric26.Fabric26TextStyle;
import com.dragonmeow.nyanslate.fabric26.NyanslateFabric26;
import com.dragonmeow.nyanslate.service.TranslationDecision;
import com.dragonmeow.nyanslate.service.TranslationService;

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

    private final ArrayDeque<Component> nyanslate$scoreboardSources = new ArrayDeque<>();
    private final ArrayDeque<Component> nyanslate$scoreboardRendered = new ArrayDeque<>();

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), require = 0)
    private void nyanslate$prepareScoreboard(GuiGraphicsExtractor graphics,
                                                Objective objective, CallbackInfo ci) {
        HookGuard.enterSticky("Hud.prepareScoreboard");
        try {
            nyanslate$scoreboardSources.clear();
            nyanslate$scoreboardRendered.clear();
            TranslationService service = NyanslateFabric26.service();
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
            service.warmScoreboardBatch(nyanslate$scoreboardRequests(title, rows));

            Component translatedTitle = Fabric26TextStyle.renderTranslated(
                    "scoreboard", title, service::translateScoreboardLine);
            nyanslate$enqueueScoreboardRow(
                    title, translatedTitle == null ? title : translatedTitle);
            List<Component> renderedRows = new ArrayList<>(rows);

            for (int i = 0; i < rows.size(); i++) {
                Component row = rows.get(i);
                Component translated = Fabric26TextStyle.renderTranslated(
                        "scoreboard", row, service::translateScoreboardLine);
                if (translated != null) renderedRows.set(i, translated);
            }

            for (int i = 0; i < entries.size(); i++) {
                nyanslate$enqueueScoreboardRow(rows.get(i), renderedRows.get(i));
                nyanslate$enqueueScoreboardRow(scores.get(i), scores.get(i));
            }
        } catch (Throwable guardError) {
            HookGuard.fail("Hud.prepareScoreboard", guardError);
        }
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("RETURN"), require = 0)
    private void nyanslate$clearScoreboard(GuiGraphicsExtractor graphics,
                                              Objective objective, CallbackInfo ci) {
        HookGuard.enterSticky("Hud.clearScoreboard");
        try {
            nyanslate$scoreboardSources.clear();
            nyanslate$scoreboardRendered.clear();
        } catch (Throwable guardError) {
            HookGuard.fail("Hud.clearScoreboard", guardError);
        }
    }

    private void nyanslate$enqueueScoreboardRow(Component source, Component rendered) {
        nyanslate$scoreboardSources.addLast(source);
        nyanslate$scoreboardRendered.addLast(rendered);
    }

    private static List<String> nyanslate$scoreboardRequests(
            Component title, List<Component> rows) {
        List<String> requests = new ArrayList<>();
        requests.add(Fabric26TextStyle.paragraphRequestText(List.of(title)));
        for (Component row : rows) {
            requests.add(row == null || row.getString().isBlank() ? ""
                    : Fabric26TextStyle.paragraphRequestText(List.of(row)));
        }
        return requests;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void nyanslate$debugRequests(GuiGraphicsExtractor graphics,
                                            DeltaTracker deltaTracker,
                                            CallbackInfo ci) {
        if (!HookGuard.enter("Hud.debugRequests")) return;
        try {
            var log = NyanslateFabric26.debugLog();
            var config = NyanslateFabric26.config();
            Minecraft minecraft = Minecraft.getInstance();
            if (log == null || config == null || !config.debugTranslationOverlay
                    || minecraft == null || minecraft.font == null) return;

            NyanslateFabric26.beginInternalOverlay();
            try {
                List<com.dragonmeow.nyanslate.translate.TranslationDebugLog.Entry> entries = log.snapshot(5);
                Font font = minecraft.font;
                int availableWidth = Math.max(160, graphics.guiWidth() - 16);
                int maxWidth = Math.min(520,
                        Math.min(availableWidth, Math.max(220, graphics.guiWidth() / 3)));
                int lineHeight = 9;
                int x = 6;
                int y = 6;
                int height = 25 + entries.size() * lineHeight;
                graphics.fill(x - 3, y - 3, x + maxWidth + 3, y + height, 0xB0101010);

                long waiting = entries.stream().filter(e -> e.status()
                        == com.dragonmeow.nyanslate.translate.TranslationDebugLog.Status.IN_FLIGHT).count();
                long failed = entries.stream().filter(e -> e.status()
                        == com.dragonmeow.nyanslate.translate.TranslationDebugLog.Status.FAILED).count();
                long rateLimited = entries.stream().filter(e -> e.status()
                        == com.dragonmeow.nyanslate.translate.TranslationDebugLog.Status.RATE_LIMITED).count();
                String header = "MT DEBUG  最近 " + entries.size() + " 項  …" + waiting
                        + "  429×" + rateLimited + "  ✕" + failed;
                graphics.text(font, Component.literal(header), x, y, 0xFFFFD060, false);

                var tokens = NyanslateFabric26.tokenUsageSnapshot();
                String tokenLine = "TOKENS total " + tokens.totalTokens()
                        + " | in " + tokens.inputTokens() + " (cached " + tokens.cachedInputTokens() + ")"
                        + " | out " + tokens.outputTokens() + " (reason " + tokens.reasoningOutputTokens() + ")"
                        + " | req " + tokens.requests() + " | " + com.dragonmeow.nyanslate.translate.HookHealth.shortSummary();
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
                    String sourceText = com.dragonmeow.nyanslate.translate.TranslationDebugLog
                            .compactText(entry.text());
                    String translatedText = com.dragonmeow.nyanslate.translate.TranslationDebugLog
                            .compactText(translated);
                    int bodyBudget = Math.max(40, maxWidth - font.width(prefix + "原:  → 譯: "));
                    int sourceBudget = bodyBudget / 2;
                    int translatedBudget = bodyBudget - sourceBudget;
                    String body = "原: " + nyanslate$ellipsize(font, sourceText, sourceBudget)
                            + " → 譯: " + nyanslate$ellipsize(font, translatedText, translatedBudget);
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
                NyanslateFabric26.endInternalOverlay();
            }
        } catch (Throwable guardError) {
            HookGuard.fail("Hud.debugRequests", guardError);
        }
    }

    private static String nyanslate$ellipsize(Font font, String text, int width) {
        if (text == null || text.isEmpty() || font.width(text) <= width) return text == null ? "" : text;
        return font.plainSubstrByWidth(text, Math.max(4, width - font.width("…"))) + "…";
    }

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            require = 0)
    private void nyanslate$scoreboard(GuiGraphicsExtractor g, Font font, Component text,
                                         int x, int y, int color, boolean shadow) {
        if (!HookGuard.enter("Hud.scoreboard")) {
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.text(font, text, x, y, color, shadow));
            return;
        }
        try {
            Component next = nyanslate$scoreboardSources.peekFirst();
            Component toDraw = text;
            if (next != null && next.equals(text)) {
                nyanslate$scoreboardSources.removeFirst();
                toDraw = nyanslate$scoreboardRendered.removeFirst();
            }
            Component rendered = toDraw;
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(
                    () -> g.text(font, rendered, x, y, color, shadow));
        } catch (Throwable guardError) {
            HookGuard.fail("Hud.scoreboard", guardError);
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.text(font, text, x, y, color, shadow));
        }
    }

    @Redirect(
            method = "extractSelectedItemName",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            require = 0)
    private void nyanslate$heldName(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
        if (!HookGuard.enter("Hud.heldName")) {
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.textWithBackdrop(font, text, x, y, width, color));
            return;
        }
        try {
            TranslationService s = NyanslateFabric26.service();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                NyanslateFabric26.registerItemEntity(mc.player.getMainHandItem());
            }
            nyanslate$backdrop("held", g, font, text, x, y, width, color, s == null ? null : s::translateHeld);
        } catch (Throwable guardError) {
            HookGuard.fail("Hud.heldName", guardError);
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.textWithBackdrop(font, text, x, y, width, color));
        }
    }

    @Redirect(
            method = "extractTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            require = 0)
    private void nyanslate$title(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
        if (!HookGuard.enter("Hud.title")) {
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.textWithBackdrop(font, text, x, y, width, color));
            return;
        }
        try {
            TranslationService s = NyanslateFabric26.service();
            nyanslate$backdrop("title", g, font, text, x, y, width, color, s == null ? null : s::translateTitle);
        } catch (Throwable guardError) {
            HookGuard.fail("Hud.title", guardError);
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.textWithBackdrop(font, text, x, y, width, color));
        }
    }

    @Redirect(
            method = "extractOverlayMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            require = 0)
    private void nyanslate$actionBar(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
        if (!HookGuard.enter("Hud.actionBar")) {
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.textWithBackdrop(font, text, x, y, width, color));
            return;
        }
        try {
            TranslationService s = NyanslateFabric26.service();
            nyanslate$backdrop("actionBar", g, font, text, x, y, width, color, s == null ? null : s::translateActionBar);
        } catch (Throwable guardError) {
            HookGuard.fail("Hud.actionBar", guardError);
            com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(() -> g.textWithBackdrop(font, text, x, y, width, color));
        }
    }

    /** Centred backdrop draw shared by held name / title / subtitle / action bar; re-centres the
     *  translation, and stacks 原文／譯文 upward when the surface produced a 2-line (\n) component. */
    private static void nyanslate$backdrop(String surfaceId, GuiGraphicsExtractor g, Font font, Component text,
                                              int x, int y, int width, int color,
                                              Function<String, TranslationDecision> fn) {
        if (fn != null && text != null) {
            Component translated = Fabric26TextStyle.renderTranslated(surfaceId, text, fn);
            if (translated != null) {
                int center = x + font.width(text) / 2; // keep the original's centre
                List<Component> lines = Fabric26TextStyle.splitLines(translated);
                if (lines.size() <= 1) {
                    int w = font.width(translated);
                    com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(
                            () -> g.textWithBackdrop(font, translated, center - w / 2, y, w, color));
                    return;
                }
                // 原文＋翻譯: stack lines upward (原文 on top, 譯文 at the baseline).
                int n = lines.size();
                for (int k = 0; k < n; k++) {
                    Component line = lines.get(k);
                    int w = font.width(line);
                    int ly = y - (n - 1 - k) * Fabric26TextStyle.STACK_LINE_GAP;
                    com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(
                            () -> g.textWithBackdrop(font, line, center - w / 2, ly, w, color));
                }
                return;
            }
        }
        com.dragonmeow.nyanslate.translate.InternalRenderGuard.run(
                () -> g.textWithBackdrop(font, text, x, y, width, color));
    }
}
