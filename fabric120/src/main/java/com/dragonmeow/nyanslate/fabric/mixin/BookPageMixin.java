package com.dragonmeow.nyanslate.fabric.mixin;

import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.fabric.NyanslateFabric;
import com.dragonmeow.nyanslate.fabric.FabricTextStyle;
import com.dragonmeow.nyanslate.service.TranslationService;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
 * Translates opened-book and lectern page text (gated by {@code bookMode}). One mixin
 * covers both cases because {@code LecternScreen extends BookViewScreen}.
 *
 * <p>{@code BookViewScreen} lays the current page out via
 * {@code this.font.split(this.bookAccess.getPage(currentPage), 114)} and caches the
 * result, only re-splitting when {@code cachedPage != currentPage}. So we:</p>
 * <ol>
 *   <li>{@link #nyanslate$forceResplit} forces a re-split every frame while a book
 *       surface is on, so an async translation that arrives a frame or two later is
 *       shown without needing a page flip; and</li>
 *   <li>{@link #nyanslate$translateBookPage} redirects the {@code Font.split} call to
 *       translate the page {@code FormattedText} first (keeps Minecraft's line-wrapping
 *       and the original styling).</li>
 * </ol>
 *
 * <p>Non-blocking + memoised (via {@link FabricTextStyle#renderTranslated}); on a cache miss
 * the original page is shown and the translation pops in once warmed.</p>
 */
@Mixin(BookViewScreen.class)
public abstract class BookPageMixin {

    @Shadow
    protected int cachedPage;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"))
    private void nyanslate$forceResplit(GuiGraphics g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        TranslationService service = NyanslateFabric.service();
        if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY) {
            this.cachedPage = -1; // re-split each frame so a late translation appears in place
        }
    }

    @Redirect(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;split"
                            + "(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            require = 0)
    private List<FormattedCharSequence> nyanslate$translateBookPage(Font font, FormattedText text, int width) {
        TranslationService service = NyanslateFabric.service();
        if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY && text != null) {
            Component src = preserveStyles(text);
            service.warmBookBatch(FabricTextStyle.paragraphRequests(src));
            Component translated = FabricTextStyle.renderTranslatedParagraphPage(
                    src, service::translateBook, font);
            if (translated != null) {
                Component shown = service.bookMode() == DisplayMode.BOTH
                        ? src.copy().append(Component.literal("\n")).append(translated)
                        : translated;
                return font.split(shown, width);
            }
        }
        return font.split(text, width);
    }

    private static Component preserveStyles(FormattedText text) {
        if (text instanceof Component component) return FabricTextStyle.resolveLegacyCodes(component);
        MutableComponent out = Component.empty();
        text.visit((style, value) -> {
            out.append(Component.literal(value).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return FabricTextStyle.resolveLegacyCodes(out);
    }
}
