package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.fabric.WorldSignRenderAdapter;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/** 1.21.9–1.21.11 submit renderer profile; all drawing remains owned by vanilla. */
@Mixin(AbstractSignRenderer.class)
public abstract class SignRendererLayoutMixin {
    @Shadow @Final private Font font;

    @Redirect(
            method = "submitSignText(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/block/entity/SignText;"
                            + "getRenderMessages(ZLjava/util/function/Function;)"
                            + "[Lnet/minecraft/util/FormattedCharSequence;"),
            require = 1)
    private FormattedCharSequence[] nyanlex$fitTranslatedRows(
            SignText sign, boolean filtered, Function<Component, FormattedCharSequence> layout,
            SignRenderState state, PoseStack pose, SubmitNodeCollector collector, boolean front) {
        return HookGuard.call("SignRendererLayout.fitTranslatedRows",
                () -> WorldSignRenderAdapter.fit(state.maxTextLineWidth,
                        pose, this.font, () -> sign.getRenderMessages(filtered, layout)),
                () -> sign.getRenderMessages(filtered, layout));
    }
}
