package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.translate.WorldSignRenderScope;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Client-only render view for the four rows of a world sign. Translated rows use a cache
 * separate from vanilla's original-message cache, so G can fall straight through to the
 * untouched original view and async translations invalidate only their projected layout.
 * The paired renderer adapter owns fit scaling; outside that explicit scope this mixin
 * deliberately falls through to vanilla.
 */
@Mixin(SignText.class)
public abstract class SignTextMixin {

    @Unique
    private Component[][] nyanlex$projectedLines;
    @Unique
    private FormattedCharSequence[][] nyanlex$preparedLines;

    @Inject(
            method = "getRenderMessages(ZLjava/util/function/Function;)"
                    + "[Lnet/minecraft/util/FormattedCharSequence;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void nyanlex$translateRenderMessages(boolean filtered,
            Function<Component, FormattedCharSequence> layout,
            CallbackInfoReturnable<FormattedCharSequence[]> cir) {
        if (!WorldSignRenderScope.active()) return;
        if (!HookGuard.enter("WorldSign.renderMessages")) return;
        try {
            SignText sign = (SignText) (Object) this;
            Component[] source = sign.getMessages(filtered);
            Component[] projected = NyanLexFabric.worldSignLines(source);
            if (projected == source || Arrays.equals(projected, source)) return;

            int slot = filtered ? 1 : 0;
            if (this.nyanlex$projectedLines == null) {
                this.nyanlex$projectedLines = new Component[2][];
                this.nyanlex$preparedLines = new FormattedCharSequence[2][];
            }
            if (!Arrays.equals(this.nyanlex$projectedLines[slot], projected)) {
                FormattedCharSequence[] prepared = new FormattedCharSequence[projected.length];
                for (int i = 0; i < projected.length; i++) {
                    prepared[i] = projected[i].getVisualOrderText();
                }
                this.nyanlex$projectedLines[slot] = projected.clone();
                this.nyanlex$preparedLines[slot] = prepared;
            }
            cir.setReturnValue(this.nyanlex$preparedLines[slot]);
        } catch (Throwable guardError) {
            HookGuard.fail("WorldSign.renderMessages", guardError);
        }
    }

}
