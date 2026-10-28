package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.fabric26.WorldSignRenderAdapter;
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

/** 26.x submit renderer profile; all drawing remains owned by vanilla. */
@Mixin(AbstractSignRenderer.class)
public abstract class SignRendererLayoutMixin {
    @Shadow @Final private Font font;

    @Redirect(
            method = "submitSignText(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/world/level/block/entity/SignText;)V",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/block/entity/SignText;"
                            + "getRenderMessages(ZLjava/util/function/Function;)"
                            + "[Lnet/minecraft/util/FormattedCharSequence;"),
            require = 1)
    private FormattedCharSequence[] nyanlex$fitTranslatedRows(
            SignText sign, boolean filtered, Function<Component, FormattedCharSequence> layout,
            SignRenderState state, PoseStack pose, SubmitNodeCollector collector,
            SignText renderedText) {
        return HookGuard.call("SignRendererLayout.fitTranslatedRows",
                () -> WorldSignRenderAdapter.fit(state.maxTextLineWidth,
                        pose, this.font, () -> sign.getRenderMessages(filtered, layout)),
                () -> sign.getRenderMessages(filtered, layout));
    }
}
