package com.dragonmeow.mctranslator.neoforge.mixin;

import com.dragonmeow.mctranslator.neoforge.MctranslatorNeoForge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * Translates arbitrary GUI text drawn through {@link GuiGraphics} — the only general hook
 * that reaches custom mod screens (e.g. Iris/Oculus shader-pack settings) that render their
 * labels directly via {@code drawString}/{@code drawCenteredString} instead of using vanilla
 * widgets. Gated by {@code screenTextMode} (default OFF) and only active while a screen is
 * open (see {@link MctranslatorNeoForge#screenText}).
 *
 * <p>We modify the text argument on the int-coordinate {@code (…,boolean)} overloads, which
 * every other String/Component draw and {@code drawCenteredString} delegate into, so each
 * piece of text is translated exactly once (no double-translation). Text drawn via
 * {@code Font.drawInBatch} directly, or as a pre-built {@code FormattedCharSequence}, is not
 * covered.</p>
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsTextMixin {

    @ModifyVariable(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> mctranslator$visibleTooltip(List<Component> lines) {
        return MctranslatorNeoForge.visibleTooltip(lines);
    }

    @ModifyVariable(method = "renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> mctranslator$visibleComponentTooltip(List<Component> lines) {
        return MctranslatorNeoForge.visibleTooltip(lines);
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String mctranslator$screenTextString(String text) {
        return MctranslatorNeoForge.screenText(text);
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component mctranslator$screenTextComponent(Component text) {
        return MctranslatorNeoForge.screenText(text);
    }

    @ModifyVariable(
            method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component mctranslator$screenTextCentered(Component text) {
        return MctranslatorNeoForge.screenText(text);
    }

    /**
     * Pre-laid-out ordered text (FormattedCharSequence) — the path FTB Quests and other
     * mod GUIs use for multi-line descriptions. Component-originated text reaches here
     * already translated (Chinese) and is skipped by the text filter.
     */
    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedCharSequence mctranslator$screenTextOrdered(FormattedCharSequence text) {
        return MctranslatorNeoForge.screenText(text);
    }
}
