package com.dragonmeow.mctranslator.neoforge.mixin;

import com.dragonmeow.mctranslator.neoforge.NeoTextStyle;
import com.dragonmeow.mctranslator.neoforge.MctranslatorNeoForge;
import com.dragonmeow.mctranslator.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MC 1.20.1 HUD text translation. 1.20.1 has no {@code drawStringWithBackdrop}, and renders
 * title / subtitle / action-bar INLINE in {@code render()} (the dedicated renderTitle /
 * renderOverlayMessage methods were split out only in 1.20.2), so this covers the two surfaces
 * that 1.20.1 draws in their own methods via {@code drawString}: the scoreboard sidebar
 * ({@code displayScoreboardSidebar}) and the held-item name ({@code renderSelectedItemName}).
 * Title / action-bar are not translated on 1.20.1.
 */
@Mixin(Gui.class)
public abstract class GuiScoreboardMixin {

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"),
            require = 0)
    private int mctranslator$scoreboard(GuiGraphics g, Font font, Component text,
                                        int x, int y, int color, boolean shadow) {
        TranslationService service = MctranslatorNeoForge.service();
        if (service != null && text != null) {
            Component t = NeoTextStyle.renderTranslated("scoreboard", text, service::translateScoreboardLine);
            if (t != null) return g.drawString(font, t, x, y, color, shadow);
        }
        return g.drawString(font, text, x, y, color, shadow);
    }

    @Redirect(
            method = "renderSelectedItemName",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString"
                            + "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"),
            require = 0)
    private int mctranslator$heldName(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        TranslationService service = MctranslatorNeoForge.service();
        if (service != null && text != null) {
            Component t = NeoTextStyle.renderTranslated("held", text, service::translateHeld);
            if (t != null) return g.drawString(font, t, x, y, color);
        }
        return g.drawString(font, text, x, y, color);
    }
}
