package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.InternalRenderGuard;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
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

@Mixin(Gui.class)
public abstract class GuiScoreboardMixin {
    @Shadow public abstract Font getFont();
    @Shadow private Component overlayMessageString;
    @Shadow private Component title;
    @Shadow private Component subtitle;

    private final ArrayDeque<Component> nyanlex$sources = new ArrayDeque<>();
    private final ArrayDeque<Component> nyanlex$rendered = new ArrayDeque<>();

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), require = 0)
    private void nyanlex$prepare(PoseStack pose, Objective objective, CallbackInfo ci) {
        HookGuard.enterSticky("GuiScoreboard.prepare");
        try {
            nyanlex$sources.clear();
            nyanlex$rendered.clear();
            TranslationService service = NyanLexFabric.service();
            if (service == null || objective == null) return;
            Scoreboard board = objective.getScoreboard();
            Collection<Score> all = board.getPlayerScores(objective);
            List<Score> visible = all.stream().filter(s -> s.getOwner() != null && !s.getOwner().startsWith("#")).toList();
            List<Score> shown = visible.size() > 15 ? visible.stream().skip(visible.size() - 15L).toList() : visible;
            List<Component> rows = shown.stream().map(s -> (Component) PlayerTeam.formatNameForTeam(
                    board.getPlayersTeam(s.getOwner()), new net.minecraft.network.chat.TextComponent(s.getOwner()))).toList();
            Component heading = objective.getDisplayName();
            List<String> requests = new ArrayList<>();
            requests.add(FabricTextStyle.paragraphRequestText(List.of(heading)));
            for (Component row : rows) requests.add(FabricTextStyle.paragraphRequestText(List.of(row)));
            service.warmScoreboardBatch(requests);
            for (Component row : rows) enqueue(row, translated("scoreboard", row, service::translateScoreboardLine));
            enqueue(heading, translated("scoreboard", heading, service::translateScoreboardLine));
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.prepare", guardError);
        }
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("RETURN"), require = 0)
    private void nyanlex$clear(PoseStack pose, Objective objective, CallbackInfo ci) {
        HookGuard.enterSticky("GuiScoreboard.clear");
        try {
            nyanlex$sources.clear(); nyanlex$rendered.clear();
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.clear", guardError);
        }
    }

    private static Component translated(String id, Component source, Function<String, TranslationDecision> fn) {
        Component value = FabricTextStyle.renderTranslated(id, source, fn);
        return value == null ? source : value;
    }

    private void enqueue(Component source, Component value) { nyanlex$sources.addLast(source); nyanlex$rendered.addLast(value); }
    private Component take(Component source) {
        Component expected = nyanlex$sources.peekFirst();
        if (expected == null || !expected.equals(source)) return source;
        nyanlex$sources.removeFirst();
        return nyanlex$rendered.removeFirst();
    }

    @Redirect(method = "displayScoreboardSidebar", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"), require = 0)
    private int nyanlex$score(Font font, PoseStack pose, Component text, float x, float y, int color) {
        if (!HookGuard.enter("GuiScoreboard.score")) return font.draw(pose, text, x, y, color);
        try {
            Component value = take(text);
            return InternalRenderGuard.call(() -> font.draw(pose, value, x, y, color));
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.score", guardError);
            return font.draw(pose, text, x, y, color);
        }
    }

    @Redirect(method = "renderSelectedItemName", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"), require = 0)
    private int nyanlex$held(Font font, PoseStack pose, Component text, float x, float y, int color) {
        if (!HookGuard.enter("GuiScoreboard.held")) return font.drawShadow(pose, text, x, y, color);
        try {
            TranslationService service = NyanLexFabric.service();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                NyanLexFabric.registerItemEntity(mc.player.getMainHandItem());
            }
            return drawTranslated("held", service == null ? null : service::translateHeld, font, pose, text, x, y, color);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.held", guardError);
            return font.drawShadow(pose, text, x, y, color);
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"), require = 0)
    private int nyanlex$hud(Font font, PoseStack pose, Component text, float x, float y, int color) {
        if (!HookGuard.enter("GuiScoreboard.hud")) return font.drawShadow(pose, text, x, y, color);
        try {
            TranslationService service = NyanLexFabric.service();
            if (service != null && text == overlayMessageString)
                return drawTranslated("actionBar", service::translateActionBar, font, pose, text, x, y, color);
            if (service != null && (text == title || text == subtitle))
                return drawTranslated("title", service::translateTitle, font, pose, text, x, y, color);
            return InternalRenderGuard.call(() -> font.drawShadow(pose, text, x, y, color));
        } catch (Throwable guardError) {
            HookGuard.fail("GuiScoreboard.hud", guardError);
            return font.drawShadow(pose, text, x, y, color);
        }
    }

    private static int drawTranslated(String id, Function<String, TranslationDecision> fn, Font font,
                                      PoseStack pose, Component text, float x, float y, int color) {
        Component value = fn == null || text == null ? null : FabricTextStyle.renderTranslated(id, text, fn);
        if (value == null) value = text;
        final Component shown = value;
        float center = x + font.width(text) / 2f;
        List<Component> lines = FabricTextStyle.splitLines(shown);
        int ret = 0;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            float ly = y - (lines.size() - 1 - i) * FabricTextStyle.STACK_LINE_GAP;
            ret = InternalRenderGuard.call(() -> font.drawShadow(pose, line, center - font.width(line) / 2f, ly, color));
        }
        return ret;
    }
}
