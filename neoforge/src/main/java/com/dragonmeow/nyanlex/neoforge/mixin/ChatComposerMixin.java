package com.dragonmeow.nyanlex.neoforge.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge.NyanLexNeoForge;
import com.dragonmeow.nyanlex.translate.ChatComposerPanel;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only fills vanilla's draft. Vanilla alone owns the final Enter/send action. */
@Mixin(ChatScreen.class)
public abstract class ChatComposerMixin extends Screen {
    @Shadow protected EditBox input;
    @Unique private EditBox nyanlex$draft;
    @Unique private EditBox nyanlex$search;
    @Unique private boolean nyanlex$enterHeld;
    @Unique private ChatComposerPanel nyanlex$composer;
    protected ChatComposerMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"), require = 1)
    private void nyanlex$initComposer(CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.initComposer")) return;
        try {
            if (NyanLexNeoForge.config() == null || !NyanLexNeoForge.config().chatComposerEnabled
                    || input.getValue().startsWith("/")) return;
            String draft = nyanlex$draft == null ? ChatComposerPanel.savedDraft() : nyanlex$draft.getValue();
            if (nyanlex$composer != null) nyanlex$composer.close();
            final String[][] languages = nyanlex$languages();
            nyanlex$composer = new ChatComposerPanel(new ChatComposerPanel.Host() {
                public String text(String key) { return Component.translatable(key).getString(); }
                public int textWidth(String text) { return font.width(text); }
                public String draft() { return nyanlex$draft.getValue(); }
                public String chat() { return input.getValue(); }
                public String language() { return NyanLexNeoForge.config().chatComposerLanguage; }
                public void language(String tag) { NyanLexNeoForge.config().chatComposerLanguage = tag; NyanLexNeoForge.saveConfig(); }
                public String[][] languages() { return languages; }
                public double positionX() { return NyanLexNeoForge.config().chatComposerX; }
                public double positionY() { return NyanLexNeoForge.config().chatComposerY; }
                public void position(double x, double y) {
                    NyanLexNeoForge.config().chatComposerX = x; NyanLexNeoForge.config().chatComposerY = y; NyanLexNeoForge.saveConfig();
                }
                public void fill(String text) {
                    input.setValue(text);
                    nyanlex$draft.setFocused(false);
                    input.setFocused(true);
                    setFocused(input);
                }
                public boolean current() { return minecraft.screen == (Object) ChatComposerMixin.this; }
                public void execute(Runnable action) { minecraft.execute(action); }
                public void translate(String text, String target, java.util.function.BiConsumer<String, String> callback) {
                    NyanLexNeoForge.outgoingChat.translate(text, target, callback);
                }
            });
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
    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void nyanlex$renderComposer(GuiGraphics g, int mx, int my, float delta, CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.renderComposer")) return;
        try {
            if (nyanlex$composer == null) return;
            ChatComposerPanel.Canvas canvas = new ChatComposerPanel.Canvas() {
                public void fill(int x, int y, int w, int h, int color) { g.fill(x, y, x + w, y + h, color); }
                public void text(String text, int x, int y, int color) { g.drawString(font, text, x, y, color, false); }
            };
            nyanlex$composer.render(canvas);
            nyanlex$draft.setX(nyanlex$composer.inputX()); nyanlex$draft.setY(nyanlex$composer.inputY());
            nyanlex$draft.render(g, mx, my, delta);
            nyanlex$composer.renderChoices(canvas);
            if (nyanlex$composer.choosing()) {
            nyanlex$search.setX(nyanlex$composer.searchX()); nyanlex$search.setY(nyanlex$composer.searchY());
            nyanlex$search.render(g, mx, my, delta);
            }
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.renderComposer", guardError);
        }
    }
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
    private void nyanlex$clickComposer(double mx, double my, int button, CallbackInfoReturnable<Boolean> ci) {
        if (!HookGuard.enter("ChatComposer.clickComposer")) return;
        try {
            if (nyanlex$composer == null) return;
            if (nyanlex$composer.choosing()) {
                if (my >= nyanlex$composer.searchY() && my < nyanlex$composer.searchY() + 20
                        && mx >= nyanlex$composer.searchX() && mx < nyanlex$composer.searchX() + nyanlex$composer.inputWidth())
                    nyanlex$search.mouseClicked(mx, my, button);
                else nyanlex$composer.click(mx, my, button);
                nyanlex$focusSearch(); ci.setReturnValue(true); return;
            }
            boolean inside = nyanlex$composer.contains(mx, my);
            if (inside) {
                if (my >= nyanlex$composer.inputY() && my < nyanlex$composer.inputY() + 20) {
                    nyanlex$focusDraft(); nyanlex$draft.mouseClicked(mx, my, button);
                } else nyanlex$composer.click(mx, my, button);
                if (nyanlex$composer.choosing()) nyanlex$focusSearch();
                ci.setReturnValue(true);
            } else { nyanlex$draft.setFocused(false); input.setFocused(true); setFocused(input); }
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.clickComposer", guardError);
        }
    }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (nyanlex$composer != null && nyanlex$composer.drag(mx, my)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }
    @Override public boolean mouseReleased(double mx, double my, int button) {
        if (nyanlex$composer != null && nyanlex$composer.release()) return true;
        return super.mouseReleased(mx, my, button);
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
    private void nyanlex$keyComposer(int code, int scan, int modifiers, CallbackInfoReturnable<Boolean> ci) {
        if (!HookGuard.enter("ChatComposer.keyComposer")) return;
        try {
            int key = code;
            if (nyanlex$enterHeld && (key == 257 || key == 335)) { ci.setReturnValue(true); return; }
            if (nyanlex$composer != null && nyanlex$composer.choosing()) {
                if (key == 256 || key == 258) nyanlex$composer.closeChoices();
                else if (key == 257 || key == 335) { nyanlex$enterHeld = true; nyanlex$composer.chooseFirst(); }
                else nyanlex$search.keyPressed(code, scan, modifiers);
                nyanlex$focusSearch(); ci.setReturnValue(true); return;
            }
            if (nyanlex$composer == null || !nyanlex$draft.isFocused()) return;
            if (key == 256) return;
            if (key == 257 || key == 335) { nyanlex$enterHeld = true; nyanlex$composer.submit(); }
            else if (key == 258) { nyanlex$draft.setFocused(false); input.setFocused(true); setFocused(input); }
            else nyanlex$draft.keyPressed(code, scan, modifiers);
            ci.setReturnValue(true);
        } catch (Throwable guardError) {
            HookGuard.fail("ChatComposer.keyComposer", guardError);
        }
    }
    @Override public boolean keyReleased(int code, int scan, int modifiers) {
        int key = code;
        if (key == 257 || key == 335) nyanlex$enterHeld = false;
        return super.keyReleased(code, scan, modifiers);
    }
}
