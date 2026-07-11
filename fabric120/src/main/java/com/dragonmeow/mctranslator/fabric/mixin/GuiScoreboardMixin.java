package com.dragonmeow.mctranslator.fabric.mixin;

import com.dragonmeow.mctranslator.fabric.FabricTextStyle;
import com.dragonmeow.mctranslator.fabric.MctranslatorFabric;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * MC 1.20.1 HUD text translation. 1.20.1 has no {@code drawStringWithBackdrop}, and renders
 * title / subtitle / action-bar INLINE in {@code render()} (the dedicated renderTitle /
 * renderOverlayMessage methods were split out only in 1.20.2), so this covers the two surfaces
 * that 1.20.1 draws in their own methods via {@code drawString}: the scoreboard sidebar
 * ({@code displayScoreboardSidebar}) and the held-item name ({@code renderSelectedItemName}).
 * The shared {@code render} redirect additionally identifies the title, subtitle and
 * overlay-message fields so those inline call sites receive the correct surface policy.
 */
@Mixin(Gui.class)
public abstract class GuiScoreboardMixin {

    @Shadow
    public abstract Font getFont();
    @Shadow private Component overlayMessageString;
    @Shadow private Component title;
    @Shadow private Component subtitle;

    private final ArrayDeque<Component> mctranslator$scoreboardSources = new ArrayDeque<>();
    private final ArrayDeque<Component> mctranslator$scoreboardRendered = new ArrayDeque<>();

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), require = 0)
    private void mctranslator$prepareScoreboard(GuiGraphics graphics, Objective objective,
                                                CallbackInfo ci) {
        mctranslator$scoreboardSources.clear();
        mctranslator$scoreboardRendered.clear();
        TranslationService service = MctranslatorFabric.service();
        if (service == null || objective == null) return;

        Scoreboard scoreboard = objective.getScoreboard();
        Collection<Score> all = scoreboard.getPlayerScores(objective);
        List<Score> visible = all.stream()
                .filter(score -> score.getOwner() != null && !score.getOwner().startsWith("#"))
                .toList();
        List<Score> shown = visible.size() > 15
                ? visible.stream().skip(Math.max(0, visible.size() - 15L)).toList()
                : visible;
        List<Component> rows = shown.stream()
                .map(score -> (Component) PlayerTeam.formatNameForTeam(
                        scoreboard.getPlayersTeam(score.getOwner()),
                        Component.literal(score.getOwner())))
                .toList();
        Component title = objective.getDisplayName();
        service.warmScoreboardBatch(mctranslator$scoreboardRequests(title, rows));
        List<Component> renderedRows = new ArrayList<>(rows);

        for (int i = 0; i < rows.size(); i++) {
            Component row = rows.get(i);
            Component translated = FabricTextStyle.renderTranslated(
                    "scoreboard", row, service::translateScoreboardLine);
            if (translated != null) renderedRows.set(i, translated);
        }

        for (int i = 0; i < rows.size(); i++) {
            mctranslator$enqueueScoreboardRow(rows.get(i), renderedRows.get(i));
        }
        if (!rows.isEmpty()) {
            Component translatedTitle = FabricTextStyle.renderTranslated(
                    "scoreboard", title, service::translateScoreboardLine);
            mctranslator$enqueueScoreboardRow(
                    title, translatedTitle == null ? title : translatedTitle);
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
            Component title, List<Component> rows) {
        List<String> requests = new ArrayList<>();
        requests.add(FabricTextStyle.paragraphRequestText(List.of(title)));
        for (Component row : rows) {
            requests.add(row == null || row.getString().isBlank() ? ""
                    : FabricTextStyle.paragraphRequestText(List.of(row)));
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
            method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0)
    private int mctranslator$scoreboard(GuiGraphics g, Font font, Component text,
                                        int x, int y, int color, boolean shadow) {
        Component translated = text == null ? null : mctranslator$takeScoreboardRow(text);
        Component rendered = translated == null ? text : translated;
        return com.dragonmeow.mctranslator.translate.InternalRenderGuard.call(
                () -> g.drawString(font, rendered, x, y, color, shadow));
    }

    @Redirect(
            method = "renderSelectedItemName",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 0)
    private int mctranslator$heldName(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        TranslationService service = MctranslatorFabric.service();
        return mctranslator$drawTranslated("held", service == null ? null : service::translateHeld,
                g, font, text, x, y, color);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"), require = 0)
    private int mctranslator$inlineHud(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        TranslationService service = MctranslatorFabric.service();
        if (service != null && text == overlayMessageString) {
            return mctranslator$drawTranslated("actionBar", service::translateActionBar, g, font, text, x, y, color);
        }
        if (service != null && (text == title || text == subtitle)) {
            return mctranslator$drawTranslated("title", service::translateTitle, g, font, text, x, y, color);
        }
        return com.dragonmeow.mctranslator.translate.InternalRenderGuard.call(
                () -> g.drawString(font, text, x, y, color));
    }

    private static int mctranslator$drawTranslated(String id, Function<String, TranslationDecision> fn,
                                                    GuiGraphics g, Font font, Component text,
                                                    int x, int y, int color) {
        Component shown = fn == null || text == null ? null : FabricTextStyle.renderTranslated(id, text, fn);
        if (shown == null) shown = text;
        int center = x + font.width(text) / 2;
        List<Component> lines = FabricTextStyle.splitLines(shown);
        int ret = 0;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            int ly = y - (lines.size() - 1 - i) * FabricTextStyle.STACK_LINE_GAP;
            ret = com.dragonmeow.mctranslator.translate.InternalRenderGuard.call(
                    () -> g.drawString(font, line, center - font.width(line) / 2, ly, color));
        }
        return ret;
    }
}
