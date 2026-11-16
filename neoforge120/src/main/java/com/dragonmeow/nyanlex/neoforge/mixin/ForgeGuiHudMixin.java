package com.dragonmeow.nyanlex.neoforge.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge.NeoTextStyle;
import com.dragonmeow.nyanlex.neoforge.NyanLexNeoForge;
import com.dragonmeow.nyanlex.neoforge.OverlayDialogueView;
import com.dragonmeow.nyanlex.neoforge.TransientChatLogger;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.InternalRenderGuard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Action bar and title on Forge. {@code ForgeGui.render} replaces the whole of {@code Gui.render}
 * and draws these two surfaces itself, from {@code renderRecordOverlay} and {@code renderTitle},
 * with {@code drawString(Font, FormattedCharSequence, ...)}; the inline draw calls that
 * {@link GuiScoreboardMixin} redirects in {@code Gui.render} are never reached on this loader.
 * The redirects here do for the Forge layout what that mixin does for the inline one: the overlay
 * dialogue box is drawn as a translated view (same total width as the original, so the backdrop
 * and the centring that Forge already computed stay right), and title / subtitle are drawn
 * translated and reported to the chat log.
 */
@Mixin(ForgeGui.class)
public abstract class ForgeGuiHudMixin extends Gui {

    // Extends the target's superclass only to read its protected overlayMessageString, title and
    // subtitle; the mixin framework drops this constructor.
    protected ForgeGuiHudMixin(Minecraft minecraft, ItemRenderer itemRenderer) {
        super(minecraft, itemRenderer);
    }

    @Redirect(
            method = "renderRecordOverlay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"),
            require = 0)
    private int nyanlex$recordOverlay(GuiGraphics g, Font font, FormattedCharSequence text,
                                      int x, int y, int color) {
        if (!HookGuard.enter("ForgeGuiHud.recordOverlay")) return g.drawString(font, text, x, y, color);
        try {
            Component message = this.overlayMessageString;
            if (message != null && !NyanLexNeoForge.isOwnFeedback(message)) {
                // An overlay dialogue box is translated block by block and drawn in place; it never
                // goes down the one-line action bar path (its HUD numbers and typed text change every
                // frame). The rebuilt component has the original's total advance.
                Component dialogue = OverlayDialogueView.view(message, font);
                if (dialogue != null) {
                    return InternalRenderGuard.call(
                            () -> g.drawString(font, dialogue.getVisualOrderText(), x, y, color));
                }
            }
            return g.drawString(font, text, x, y, color);
        } catch (Throwable guardError) {
            HookGuard.fail("ForgeGuiHud.recordOverlay", guardError);
            return g.drawString(font, text, x, y, color);
        }
    }

    @Redirect(
            method = "renderTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
                    ordinal = 0),
            require = 0)
    private int nyanlex$title(GuiGraphics g, Font font, FormattedCharSequence text,
                              int x, int y, int color, boolean shadow) {
        if (!HookGuard.enter("ForgeGuiHud.title")) return g.drawString(font, text, x, y, color, shadow);
        try {
            return nyanlex$drawTitleLine(false, this.title, g, font, text, x, y, color, shadow);
        } catch (Throwable guardError) {
            HookGuard.fail("ForgeGuiHud.title", guardError);
            return g.drawString(font, text, x, y, color, shadow);
        }
    }

    @Redirect(
            method = "renderTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
                    ordinal = 1),
            require = 0)
    private int nyanlex$subtitle(GuiGraphics g, Font font, FormattedCharSequence text,
                                 int x, int y, int color, boolean shadow) {
        if (!HookGuard.enter("ForgeGuiHud.subtitle")) return g.drawString(font, text, x, y, color, shadow);
        try {
            return nyanlex$drawTitleLine(true, this.subtitle, g, font, text, x, y, color, shadow);
        } catch (Throwable guardError) {
            HookGuard.fail("ForgeGuiHud.subtitle", guardError);
            return g.drawString(font, text, x, y, color, shadow);
        }
    }

    /** One title or subtitle line: translated when a translation is ready, otherwise drawn as the game would. */
    private static int nyanlex$drawTitleLine(boolean subtitleLine, Component source, GuiGraphics g, Font font,
                                             FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        TranslationService service = NyanLexNeoForge.service();
        if (service == null || source == null || NyanLexNeoForge.isOwnFeedback(source)) {
            return InternalRenderGuard.call(() -> g.drawString(font, text, x, y, color, shadow));
        }
        Component shown = NeoTextStyle.renderTranslated("title", source, service::translateTitle);
        if (TransientChatLogger.on()) {
            TransientChatLogger.title(subtitleLine, source, shown != null);
        }
        if (shown == null || NyanLexNeoForge.projectionSuppressed()) {
            return InternalRenderGuard.call(() -> g.drawString(font, text, x, y, color, shadow));
        }
        int center = x + font.width(source) / 2;
        List<Component> lines = NeoTextStyle.splitLines(shown);
        int ret = 0;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            int ly = y - (lines.size() - 1 - i) * NeoTextStyle.STACK_LINE_GAP;
            ret = InternalRenderGuard.call(() -> g.drawString(font, line.getVisualOrderText(),
                    center - font.width(line) / 2, ly, color, shadow));
        }
        return ret;
    }
}
