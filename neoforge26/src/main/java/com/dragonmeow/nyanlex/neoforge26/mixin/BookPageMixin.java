package com.dragonmeow.nyanlex.neoforge26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.neoforge26.Neo26TextStyle;
import com.dragonmeow.nyanlex.neoforge26.NyanLexNeoForge26;
import com.dragonmeow.nyanlex.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/**
 * Translates opened-book and lectern page text (gated by {@code bookMode}). One mixin covers both
 * cases because {@code LecternScreen extends BookViewScreen}.
 *
 * <p>{@code BookViewScreen.visitText} lays the current page out via
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
    private void nyanlex$forceResplit(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        if (!HookGuard.enter("BookPage.forceResplit")) return;
        try {
            TranslationService service = NyanLexNeoForge26.service();
            if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY) {
                this.cachedPage = -1; // re-split each frame so a late translation appears in place
            }
        } catch (Throwable guardError) {
            HookGuard.fail("BookPage.forceResplit", guardError);
        }
    }

    @Redirect(
            method = "visitText",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;split"
                            + "(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            require = 1)
    private List<FormattedCharSequence> nyanlex$translateBookPage(Font font, FormattedText text, int width) {
        if (!HookGuard.enter("BookPage.translateBookPage")) return font.split(text, width);
        try {
            TranslationService service = NyanLexNeoForge26.service();
            if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY && text != null) {
                Component src = preserveStyles(text);
                service.warmBookBatch(Neo26TextStyle.paragraphRequests(src));
                Component translated = Neo26TextStyle.renderTranslatedParagraphPage(
                        src, service::translateBook, font);
                if (translated != null) {
                    Component shown = service.bookMode() == DisplayMode.BOTH
                            ? src.copy().append(Component.literal("\n")).append(translated)
                            : translated;
                    return font.split(shown, width);
                }
            }
            return font.split(text, width);
        } catch (Throwable guardError) {
            HookGuard.fail("BookPage.translateBookPage", guardError);
            return font.split(text, width);
        }
    }

    private static Component preserveStyles(FormattedText text) {
        if (text instanceof Component component) return Neo26TextStyle.resolveLegacyCodes(component);
        MutableComponent out = Component.empty();
        text.visit((style, value) -> {
            out.append(Component.literal(value).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return Neo26TextStyle.resolveLegacyCodes(out);
    }
}
