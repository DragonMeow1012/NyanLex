package com.dragonmeow.nyanlex.neoforge.mixin;

import com.dragonmeow.nyanlex.neoforge.WorldSignRenderAdapter;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/** 1.20–1.21.3 renderer profile; all drawing remains owned by vanilla. */
@Mixin(SignRenderer.class)
public abstract class SignRendererLayoutMixin {
    @Shadow @Final private Font font;

    @Redirect(
            method = "renderSignText(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/entity/SignText;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;IIIZ)V",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/block/entity/SignText;"
                            + "getRenderMessages(ZLjava/util/function/Function;)"
                            + "[Lnet/minecraft/util/FormattedCharSequence;"),
            require = 1)
    private FormattedCharSequence[] nyanlex$fitTranslatedRows(
            SignText sign, boolean filtered, Function<Component, FormattedCharSequence> layout,
            BlockPos pos, SignText renderedText, PoseStack pose, MultiBufferSource buffers,
            int packedLight, int lineHeight, int maxTextLineWidth, boolean front) {
        return HookGuard.call("SignRendererLayout.fitTranslatedRows",
                () -> WorldSignRenderAdapter.fit(maxTextLineWidth,
                        pose, this.font, () -> sign.getRenderMessages(filtered, layout)),
                () -> sign.getRenderMessages(filtered, layout));
    }
}
