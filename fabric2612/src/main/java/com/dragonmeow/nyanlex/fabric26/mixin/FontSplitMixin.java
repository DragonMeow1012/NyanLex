package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric26.NyanLexFabric26;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Translates a WHOLE block of GUI text at the moment it is wrapped into lines via
 * {@code Font.split(FormattedText, width)} — so descriptions / multi-line tooltips
 * (e.g. FTB Quests) are translated as one coherent unit and Minecraft re-wraps the
 * translation, instead of each already-wrapped line being translated separately
 * (which reads choppy / disjointed).
 *
 * <p>Gated by {@code screenTextMode} and only while a screen is open (see
 * {@link NyanLexFabric26#screenText(FormattedText)}). Component-originated text
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
            return NyanLexFabric26.screenText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FontSplit.translateBeforeWrap", guardError);
            return text;
        }
    }
}
