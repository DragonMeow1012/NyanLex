package com.dragonmeow.nyanlex.neoforge26.mixin;

import com.dragonmeow.nyanlex.neoforge26.Neo26TextStyle;
import com.dragonmeow.nyanlex.neoforge26.NyanLexNeoForge26;
import com.dragonmeow.nyanlex.translate.HookGuard;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * Translates the text of Text Display entities (name plates drawn by a server as displays).
 * The entity renderer asks the entity for its split lines through {@code cacheDisplay}, which
 * caches the result until the entity data changes. This mixin (1) re-checks, at most twice a
 * second, whether a translation became available and drops the cached lines when it did, and
 * (2) hands the translated component to the line splitter instead of the original.
 */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayMixin {

    private static final long RECHECK_MS = 500L;

    @Shadow
    private Display.TextDisplay.CachedInfo clientDisplayCache;

    @Shadow
    private Display.TextDisplay.TextRenderState textRenderState;

    /** The entity text the cached decision belongs to. */
    @Unique
    private Component nyanlex$source;
    /** The component the splitter should receive; {@code null} = the original text. */
    @Unique
    private Component nyanlex$wanted;
    /** The component the cached lines were last built from; {@code null} = the original text. */
    @Unique
    private Component nyanlex$built;
    @Unique
    private boolean nyanlex$settled;
    /** {@link Neo26TextStyle#renderEpoch()} when the decision above was made; a change makes it stale. */
    @Unique
    private int nyanlex$epoch;
    @Unique
    private long nyanlex$nextCheck;

    @Inject(method = "cacheDisplay", at = @At("HEAD"), require = 0)
    private void nyanlex$refresh(Display.TextDisplay.LineSplitter splitter,
                                 CallbackInfoReturnable<Display.TextDisplay.CachedInfo> cir) {
        if (!HookGuard.enter("TextDisplay.refresh")) return;
        try {
            Display.TextDisplay.TextRenderState state = this.textRenderState;
            if (state == null) return;
            Component source = state.text();
            if (source == null) return;
            long now = System.nanoTime() / 1_000_000L;
            boolean same = source == nyanlex$source || source.equals(nyanlex$source);
            // A settled (or recently checked) decision holds only while nothing that decides what is
            // shown has changed (show-original key, retranslate, cleared cache, language, settings).
            int epoch = Neo26TextStyle.renderEpoch();
            if (same && epoch == nyanlex$epoch && (nyanlex$settled || now < nyanlex$nextCheck)) return;
            boolean stale = epoch != nyanlex$epoch;
            Component result = NyanLexNeoForge26.displayText(source);
            nyanlex$epoch = epoch;
            nyanlex$source = source;
            nyanlex$wanted = result == source ? null : result;
            // untranslatable, or translated: decided. Otherwise a translation may still arrive.
            nyanlex$settled = result == null || result != source;
            nyanlex$nextCheck = now + RECHECK_MS;
            // Also drop the cached lines when the epoch moved: a translatable text resolves against the
            // game's language table, which the show-original key swaps, so the lines may be stale even
            // when the translated/original choice itself is unchanged.
            if (this.clientDisplayCache != null && (stale || !Objects.equals(nyanlex$wanted, nyanlex$built))) {
                this.clientDisplayCache = null;
            }
        } catch (Throwable guardError) {
            HookGuard.fail("TextDisplay.refresh", guardError);
        }
    }

    @ModifyArg(
            method = "cacheDisplay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;split("
                            + "Lnet/minecraft/network/chat/Component;I)"
                            + "Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"),
            index = 0, require = 0)
    private Component nyanlex$text(Component original) {
        if (!HookGuard.enter("TextDisplay.text")) return original;
        try {
            Component wanted = nyanlex$wanted;
            nyanlex$built = wanted;
            return wanted != null && Objects.equals(original, nyanlex$source) ? wanted : original;
        } catch (Throwable guardError) {
            HookGuard.fail("TextDisplay.text", guardError);
            return original;
        }
    }
}
