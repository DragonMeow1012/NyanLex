package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.fabric.WorldSignRenderAdapter;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/** 1.19 renderer profile: the block entity supplies the available row width. */
@Mixin(SignRenderer.class)
public abstract class SignRendererLayoutMixin {
    @Shadow @Final private Font font;

    @Redirect(
            method = "renderSignText(Lnet/minecraft/world/level/block/entity/SignBlockEntity;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/block/entity/SignBlockEntity;"
                            + "getRenderMessages(ZLjava/util/function/Function;)"
                            + "[Lnet/minecraft/util/FormattedCharSequence;"),
            require = 1)
    private FormattedCharSequence[] nyanlex$fitTranslatedRows(
            SignBlockEntity sign, boolean filtered,
            Function<Component, FormattedCharSequence> layout,
            SignBlockEntity renderedSign, PoseStack pose, MultiBufferSource buffers,
            int packedLight, float renderScale) {
        return HookGuard.call("SignRendererLayout.fitTranslatedRows",
                () -> WorldSignRenderAdapter.fit(renderedSign.getMaxTextLineWidth(),
                        pose, this.font, () -> sign.getRenderMessages(filtered, layout)),
                () -> sign.getRenderMessages(filtered, layout));
    }
}
