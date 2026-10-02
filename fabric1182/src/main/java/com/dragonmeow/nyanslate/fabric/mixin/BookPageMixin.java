package com.dragonmeow.nyanslate.fabric.mixin;

import com.dragonmeow.nyanslate.translate.HookGuard;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.fabric.FabricTextStyle;
import com.dragonmeow.nyanslate.fabric.NyanslateFabric;
import com.dragonmeow.nyanslate.service.TranslationService;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
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

@Mixin(BookViewScreen.class)
public abstract class BookPageMixin {
    @Shadow private int cachedPage;

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("HEAD"), require = 0)
    private void nyanslate$resplit(PoseStack pose, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        if (!HookGuard.enter("BookPage.resplit")) return;
        try {
            TranslationService service = NyanslateFabric.service();
            if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY) cachedPage = -1;
        } catch (Throwable guardError) {
            HookGuard.fail("BookPage.resplit", guardError);
        }
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"), require = 0)
    private List<FormattedCharSequence> nyanslate$page(Font font, FormattedText text, int width) {
        if (!HookGuard.enter("BookPage.page")) return font.split(text, width);
        try {
            TranslationService service = NyanslateFabric.service();
            if (service != null && service.bookMode() != DisplayMode.ORIGINAL_ONLY && text != null) {
                Component source = preserveStyles(text);
                service.warmBookBatch(FabricTextStyle.paragraphRequests(source));
                Component translated = FabricTextStyle.renderTranslatedParagraphPage(source, service::translateBook, font);
                if (translated != null) {
                    Component shown = service.bookMode() == DisplayMode.BOTH
                            ? source.copy().append(new net.minecraft.network.chat.TextComponent("\n")).append(translated) : translated;
                    return font.split(shown, width);
                }
            }
            return font.split(text, width);
        } catch (Throwable guardError) {
            HookGuard.fail("BookPage.page", guardError);
            return font.split(text, width);
        }
    }

    private static Component preserveStyles(FormattedText text) {
        if (text instanceof Component component) return FabricTextStyle.resolveLegacyCodes(component);
        MutableComponent out = new net.minecraft.network.chat.TextComponent("");
        text.visit((style, value) -> { out.append(new net.minecraft.network.chat.TextComponent(value).setStyle(style)); return Optional.empty(); }, Style.EMPTY);
        return FabricTextStyle.resolveLegacyCodes(out);
    }
}
