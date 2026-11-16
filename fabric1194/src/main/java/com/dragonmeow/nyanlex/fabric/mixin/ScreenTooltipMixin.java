package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric.NyanLexFabric;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * Applies cached translations at the point where a tooltip made of {@link Component} lines is
 * handed to the screen for drawing, so tooltips that no item event produced (hover text of other
 * mods' screens, widgets that draw their own tooltips) are covered as well. Gated by the tooltip
 * display mode and only active while a screen is being drawn (see
 * {@link NyanLexFabric#visibleTooltip(List)}).
 *
 * <p>Two entry points are hooked. Tooltips that arrive as already-ordered text
 * ({@code renderTooltip(PoseStack, List<FormattedCharSequence>, ...)}), as a single component, or as the
 * wrapped result of {@code Font.split} (button tooltips, hover events) never pass through either one, so
 * nothing is translated twice; {@code renderComponentTooltip} forwards ordered text, not this list, so the
 * two hooks never run for the same tooltip.</p>
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    @ModifyVariable(
            method = "renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> nyanlex$visibleTooltip(List<Component> lines) {
        if (!HookGuard.enter("ScreenTooltip.visibleTooltip")) return lines;
        try {
            return NyanLexFabric.visibleTooltip(lines);
        } catch (Throwable guardError) {
            HookGuard.fail("ScreenTooltip.visibleTooltip", guardError);
            return lines;
        }
    }

    @ModifyVariable(
            method = "renderComponentTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;II)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> nyanlex$visibleComponentTooltip(List<Component> lines) {
        if (!HookGuard.enter("ScreenTooltip.visibleComponentTooltip")) return lines;
        try {
            return NyanLexFabric.visibleTooltip(lines);
        } catch (Throwable guardError) {
            HookGuard.fail("ScreenTooltip.visibleComponentTooltip", guardError);
            return lines;
        }
    }
}
