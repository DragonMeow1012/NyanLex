package com.dragonmeow.nyanlex.fabric26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric26.NyanLexFabric26;
import com.dragonmeow.nyanlex.fabric26.GuiCanvas;
import com.dragonmeow.nyanlex.translate.ChatComposerPanel;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only fills vanilla's draft. Vanilla alone owns the final Enter/send action. */
@Mixin(ChatScreen.class)
public abstract class ChatComposerMixin extends Screen implements ChatComposerPanel.Host {
    @Shadow protected EditBox input;
    @Unique private EditBox nyanlex$draft;
    @Unique private EditBox nyanlex$search;
    @Unique private boolean nyanlex$enterHeld;
    @Unique private ChatComposerPanel nyanlex$composer;
    @Unique private String[][] nyanlex$languageRows;
    protected ChatComposerMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"), require = 1)
    private void nyanlex$initComposer(CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.initComposer")) return;
        try {
            if (NyanLexFabric26.config() == null || !NyanLexFabric26.config().chatComposerEnabled
                    || input.getValue().startsWith("/")) return;
            String draft = nyanlex$draft == null ? ChatComposerPanel.savedDraft() : nyanlex$draft.getValue();
            if (nyanlex$composer != null) nyanlex$composer.close();
            nyanlex$languageRows = nyanlex$languages();
            nyanlex$composer = new ChatComposerPanel(this);
            nyanlex$composer.resize(width, height);
            nyanlex$draft = new EditBox(font, nyanlex$composer.inputX(), nyanlex$composer.inputY(),
                    nyanlex$composer.inputWidth(), 20, Component.translatable("nyanlex.composer.title"));
            nyanlex$search = new EditBox(font, nyanlex$composer.searchX(), nyanlex$composer.searchY(),
                    nyanlex$composer.inputWidth(), 20, Component.translatable("screen.nyanlex.language.search"));
            nyanlex$search.setMaxLength(64);
            nyanlex$search.setResponder(text -> nyanlex$composer.search(text));
            nyanlex$draft.setMaxLength(256);
            nyanlex$draft.setValue(draft);
            nyanlex$draft.setResponder(text -> nyanlex$composer.observe());
            nyanlex$focusDraft();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.initComposer", guardError);
        }
    }
    // The mixed-in screen is the host. Keeping this boundary direct avoids anonymous
    // Mixin inner classes that can break transforming-loader frame computation.
    @Unique @Override public String text(String key) { return Component.translatable(key).getString(); }
    @Unique @Override public int textWidth(String text) { return font.width(text); }
    @Unique @Override public String draft() { return nyanlex$draft.getValue(); }
    @Unique @Override public String chat() { return input.getValue(); }
    @Unique @Override public String language() { return NyanLexFabric26.config().chatComposerLanguage; }
    @Unique @Override public void language(String tag) {
        NyanLexFabric26.config().chatComposerLanguage = tag;
        NyanLexFabric26.saveConfig();
    }
    @Unique @Override public String[][] languages() { return nyanlex$languageRows; }
    @Unique @Override public double positionX() { return NyanLexFabric26.config().chatComposerX; }
    @Unique @Override public double positionY() { return NyanLexFabric26.config().chatComposerY; }
    @Unique @Override public void position(double x, double y) {
        NyanLexFabric26.config().chatComposerX = x;
        NyanLexFabric26.config().chatComposerY = y;
        NyanLexFabric26.saveConfig();
    }
    @Unique @Override public void fill(String text) {
        input.setValue(text);
        nyanlex$draft.setFocused(false);
        input.setFocused(true);
        setFocused(input);
    }
    @Unique @Override public boolean current() { return minecraft.screen == this; }
    @Unique @Override public void execute(Runnable action) { minecraft.execute(action); }
    @Unique @Override public void translate(String text, String target, java.util.function.BiConsumer<String, String> callback) {
        NyanLexFabric26.outgoingChat.translate(text, target, callback);
    }
    @Unique private String[][] nyanlex$languages() {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[]{"en", "English"});
        for (java.util.Map.Entry<String, net.minecraft.client.resources.language.LanguageInfo> entry
                : minecraft.getLanguageManager().getLanguages().entrySet()) {
            rows.add(new String[]{com.dragonmeow.nyanlex.config.TranslationLanguages.fromMinecraftCode(entry.getKey()), entry.getValue().toComponent().getString()});
        }
        return rows.toArray(new String[0][]);
    }
    @Unique private void nyanlex$focusSearch() {
        if (nyanlex$composer.choosing()) {
            input.setFocused(false);
            nyanlex$draft.setFocused(false);
            setFocused(nyanlex$search);
            nyanlex$search.setFocused(true);
        } else nyanlex$focusDraft();
    }
    @Unique private void nyanlex$focusDraft() {
        if (nyanlex$composer == null) return;
        nyanlex$search.setFocused(false);
        input.setFocused(false);
        setFocused(nyanlex$draft);
        nyanlex$draft.setFocused(true);
    }
    @Inject(method = "setInitialFocus()V", at = @At("TAIL"), require = 1)
    private void nyanlex$initialFocus(CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.initialFocus")) return;
        try {
     nyanlex$focusDraft();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.initialFocus", guardError);
        }
    }
    @Inject(method = "onEdited", at = @At("TAIL"), require = 1)
    private void nyanlex$chatEdited(String value, CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.chatEdited")) return;
        try {
            if (nyanlex$composer != null && nyanlex$draft != null) nyanlex$composer.observe();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.chatEdited", guardError);
        }
    }
    @Inject(method = "removed", at = @At("HEAD"), require = 1)
    private void nyanlex$closeComposer(CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.closeComposer")) return;
        try {
            if (nyanlex$composer != null) nyanlex$composer.close();
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.closeComposer", guardError);
        }
    }
    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 1)
    private void nyanlex$renderComposer(GuiGraphicsExtractor g, int mx, int my, float delta, CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.renderComposer")) return;
        try {
            if (nyanlex$composer == null) return;
            ChatComposerPanel.Canvas canvas = new GuiCanvas(g, font);
            nyanlex$composer.render(canvas);
            nyanlex$draft.setX(nyanlex$composer.inputX()); nyanlex$draft.setY(nyanlex$composer.inputY());
            nyanlex$draft.extractRenderState(g, mx, my, delta);
            nyanlex$composer.renderChoices(canvas);
            if (nyanlex$composer.choosing()) {
            nyanlex$search.setX(nyanlex$composer.searchX()); nyanlex$search.setY(nyanlex$composer.searchY());
            nyanlex$search.extractRenderState(g, mx, my, delta);
            }
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.renderComposer", guardError);
        }
    }
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
    private void nyanlex$clickComposer(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> ci) {
        if (!HookGuard.enter("ChatComposer.clickComposer")) return;
        try {
            if (nyanlex$composer == null) return;
            if (nyanlex$composer.choosing()) {
                if (event.y() >= nyanlex$composer.searchY() && event.y() < nyanlex$composer.searchY() + 20
                        && event.x() >= nyanlex$composer.searchX() && event.x() < nyanlex$composer.searchX() + nyanlex$composer.inputWidth())
                    nyanlex$search.mouseClicked(event, doubleClick);
                else nyanlex$composer.click(event.x(), event.y(), event.button());
                nyanlex$focusSearch(); ci.setReturnValue(true); return;
            }
            boolean inside = nyanlex$composer.contains(event.x(), event.y());
            if (inside) {
                if (event.y() >= nyanlex$composer.inputY() && event.y() < nyanlex$composer.inputY() + 20) {
                    nyanlex$focusDraft(); nyanlex$draft.mouseClicked(event, doubleClick);
                } else nyanlex$composer.click(event.x(), event.y(), event.button());
                if (nyanlex$composer.choosing()) nyanlex$focusSearch();
                ci.setReturnValue(true);
            } else { nyanlex$draft.setFocused(false); input.setFocused(true); setFocused(input); }
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.clickComposer", guardError);
        }
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (nyanlex$composer != null && nyanlex$composer.drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (nyanlex$composer != null && nyanlex$composer.release()) return true;
        return super.mouseReleased(event);
    }
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, require = 1)
    private void nyanlex$scrollComposer(double mx, double my, double horizontal, double amount, CallbackInfoReturnable<Boolean> ci) {
        if (!HookGuard.enter("ChatComposer.scrollComposer")) return;
        try {
            if (nyanlex$composer != null && nyanlex$composer.scroll(amount)) ci.setReturnValue(true);
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.scrollComposer", guardError);
        }
    }
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 1)
    private void nyanlex$keyComposer(KeyEvent event, CallbackInfoReturnable<Boolean> ci) {
        if (!HookGuard.enter("ChatComposer.keyComposer")) return;
        try {
            int key = event.key();
            if (nyanlex$enterHeld && (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER)) { ci.setReturnValue(true); return; }
            if (nyanlex$composer != null && nyanlex$composer.choosing()) {
                if (key == 256 || key == 258) nyanlex$composer.closeChoices();
                else if (key == 257 || key == 335) { nyanlex$enterHeld = true; nyanlex$composer.chooseFirst(); }
                else nyanlex$search.keyPressed(event);
                nyanlex$focusSearch(); ci.setReturnValue(true); return;
            }
            if (nyanlex$composer == null || !nyanlex$draft.isFocused()) return;
            if (key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) return;
            if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) { nyanlex$enterHeld = true; nyanlex$composer.submit(); }
            else if (key == com.mojang.blaze3d.platform.InputConstants.KEY_TAB) { nyanlex$draft.setFocused(false); input.setFocused(true); setFocused(input); }
            else nyanlex$draft.keyPressed(event);
            ci.setReturnValue(true);
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.keyComposer", guardError);
        }
    }
    @Override public boolean keyReleased(KeyEvent event) {
        int key = event.key();
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) nyanlex$enterHeld = false;
        return super.keyReleased(event);
    }
}
