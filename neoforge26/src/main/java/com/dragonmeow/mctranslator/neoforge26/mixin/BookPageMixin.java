package com.dragonmeow.mctranslator.neoforge26.mixin;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.neoforge26.Neo26TextStyle;
import com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26;
import com.dragonmeow.mctranslator.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Translates opened-book and lectern page text (gated by {@code bookMode}). One mixin covers both
 * cases because {@code LecternScreen extends BookViewScreen}.
 *
 * <p>26.2's {@code BookViewScreen.extractRenderState} lays the current page out via
 * {@code this.font.split(page, 114)} and caches it, only re-splitting when
 * {@code cachedPage != currentPage}. So we (1) force a re-split each frame while a book surface is
 * on, so a late async translation appears without a page flip, and (2) redirect the {@code Font.split}
 * call to translate the page {@code FormattedText} first (keeping Minecraft's wrapping + styling).</p>
 */
@Mixin(BookViewScreen.class)
public abstract class BookPageMixin {

    @Shadow
    protected int cachedPage;

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"))
    private void mctranslator$forceResplit(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        TranslationService service = MctranslatorNeoForge26.service();
        if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY) {
            this.cachedPage = -1; // re-split each frame so a late translation appears in place
        }
    }

    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;split"
                            + "(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            require = 0)
    private List<FormattedCharSequence> mctranslator$translateBookPage(Font font, FormattedText text, int width) {
        TranslationService service = MctranslatorNeoForge26.service();
        if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY && text != null) {
            Component src = (text instanceof Component c) ? c : Component.literal(text.getString());
            Component translated = Neo26TextStyle.renderTranslated("book", src, service::translateBook);
            if (translated != null) {
                return font.split(translated, width);
            }
        }
        return font.split(text, width);
    }
}
