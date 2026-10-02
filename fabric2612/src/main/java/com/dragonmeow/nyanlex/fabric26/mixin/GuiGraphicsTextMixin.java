package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric26.NyanLexFabric26;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Translates arbitrary GUI text drawn through 26.2's {@link GuiGraphicsExtractor} — the general
 * hook that reaches custom mod screens (e.g. Iris shader-pack settings) that render labels directly
 * instead of via vanilla widgets. Gated by {@code screenTextMode} (default OFF) and only active
 * while a screen is open (see {@link NyanLexFabric26#screenText}).
 *
 * <p>In 26.2 every text draw funnels through {@code text(Font, FormattedCharSequence, …, boolean)};
 * the {@code String}/{@code Component} {@code (…,boolean)} overloads flatten into it, and the
 * 5-arg / {@code textWithBackdrop} / {@code centeredText} variants all delegate into these. So we
 * modify the text argument on the {@code String} and {@code Component} {@code (…,boolean)} overloads
 * — each label is translated exactly once, and pre-laid-out {@code FormattedCharSequence} (book /
 * wrapped descriptions) is handled upstream by {@code FontSplitMixin}.</p>
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsTextMixin {

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String nyanlex$screenTextString(String text) {
        if (!HookGuard.enter("GuiGraphicsText.screenTextString")) return text;
        try {
            return NyanLexFabric26.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.screenTextString", guardError);
            return text;
        }
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanlex$screenTextComponent(Component text) {
        if (!HookGuard.enter("GuiGraphicsText.screenTextComponent")) return text;
        try {
            return NyanLexFabric26.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("GuiGraphicsText.screenTextComponent", guardError);
            return text;
        }
    }
}
