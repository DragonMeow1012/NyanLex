package com.dragonmeow.nyanlex.neoforge26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge26.NyanLexNeoForge26;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Translates a WHOLE block of GUI text at the moment it is wrapped into lines via
 * {@code Font.split(FormattedText, width)} — so descriptions / multi-line tooltips
 * (e.g. quest descriptions) are translated as one coherent unit and Minecraft re-wraps the
 * translation, instead of each already-wrapped line being translated separately
 * (which reads choppy / disjointed).
 *
 * <p>Gated by {@code screenTextMode} and only while a screen is open (see
 * {@link NyanLexNeoForge26#screenText(FormattedText)}). Component-originated text
 * that was already translated upstream arrives here as Chinese and is skipped.</p>
 */
@Mixin(Font.class)
public abstract class FontSplitMixin {

    @ModifyVariable(
            method = "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedText nyanlex$translateBeforeWrap(FormattedText text) {
        if (!HookGuard.enter("FontSplit.translateBeforeWrap")) return text;
        try {
            return NyanLexNeoForge26.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontSplit.translateBeforeWrap", guardError);
            return text;
        }
    }

    /**
     * Interface labels that GUI code cuts to a width (and ends with an ellipsis) before drawing.
     * The draw hooks would only see the cut fragment, so the WHOLE string goes through the
     * interface-text pipeline first and the cut is made on the translation when one exists
     * (same policy, gates and request rules as {@code translateBeforeWrap}; text inputs excluded).
     */
    @ModifyVariable(
            method = "plainSubstrByWidth(Ljava/lang/String;I)Ljava/lang/String;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String nyanlex$translateBeforeTrim(String text) {
        if (!HookGuard.enter("FontSplit.translateBeforeTrim")) return text;
        try {
            return NyanLexNeoForge26.screenTextBeforeTrim(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontSplit.translateBeforeTrim", guardError);
            return text;
        }
    }
}
