package com.dragonmeow.nyanslate.fabric.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.fabric.NyanslateFabric;

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
 * open (see {@link NyanslateFabric#screenText}).
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
    private List<Component> nyanslate$visibleTooltip(List<Component> lines) {
        if (!HookGuard.enter("GuiGraphicsText.visibleTooltip")) return lines;
        try {
            return NyanslateFabric.visibleTooltip(lines);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.visibleTooltip", guardError);
            return lines;
        }
    }

    @ModifyVariable(method = "renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> nyanslate$visibleComponentTooltip(List<Component> lines) {
        if (!HookGuard.enter("GuiGraphicsText.visibleComponentTooltip")) return lines;
        try {
            return NyanslateFabric.visibleTooltip(lines);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.visibleComponentTooltip", guardError);
            return lines;
        }
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String nyanslate$screenTextString(String text) {
        if (!HookGuard.enter("GuiGraphicsText.screenTextString")) return text;
        try {
            return NyanslateFabric.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.screenTextString", guardError);
            return text;
        }
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanslate$screenTextComponent(Component text) {
        if (!HookGuard.enter("GuiGraphicsText.screenTextComponent")) return text;
        try {
            return NyanslateFabric.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.screenTextComponent", guardError);
            return text;
        }
    }

    @ModifyVariable(
            method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanslate$screenTextCentered(Component text) {
        if (!HookGuard.enter("GuiGraphicsText.screenTextCentered")) return text;
        try {
            return NyanslateFabric.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.screenTextCentered", guardError);
            return text;
        }
    }

    /**
     * Pre-laid-out ordered text (FormattedCharSequence) — the path FTB Quests and other
     * mod GUIs use for multi-line descriptions. Component-originated text reaches here
     * already translated (Chinese) and is skipped by the text filter.
     */
    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedCharSequence nyanslate$screenTextOrdered(FormattedCharSequence text) {
        if (!HookGuard.enter("GuiGraphicsText.screenTextOrdered")) return text;
        try {
            return NyanslateFabric.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.screenTextOrdered", guardError);
            return text;
        }
    }
}
