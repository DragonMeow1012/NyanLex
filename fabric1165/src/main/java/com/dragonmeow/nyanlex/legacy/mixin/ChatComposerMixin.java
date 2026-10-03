package com.dragonmeow.nyanlex.legacy.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.legacy.LegacyTranslatorMod;
import com.dragonmeow.nyanlex.translate.ChatComposerPanel;
import com.dragonmeow.nyanlex.legacy.GuiCanvas;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.TranslatableComponent;

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
            if (LegacyTranslatorMod.config() == null || !LegacyTranslatorMod.config().chatComposerEnabled
                    || input.getValue().startsWith("/")) return;
            String draft = nyanlex$draft == null ? ChatComposerPanel.savedDraft() : nyanlex$draft.getValue();
            if (nyanlex$composer != null) nyanlex$composer.close();
            nyanlex$languageRows = nyanlex$languages();
            nyanlex$composer = new ChatComposerPanel(this);
            nyanlex$composer.resize(width, height);
            nyanlex$draft = new EditBox(font, nyanlex$composer.inputX(), nyanlex$composer.inputY(),
                    nyanlex$composer.inputWidth(), 20, new TranslatableComponent("nyanlex.composer.title"));
            nyanlex$search = new EditBox(font, nyanlex$composer.searchX(), nyanlex$composer.searchY(),
                    nyanlex$composer.inputWidth(), 20, new TranslatableComponent("screen.nyanlex.language.search"));
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
    // The mixed-in screen is the host; no generated Mixin inner classes are needed.
    @Unique @Override public String text(String key) { return new TranslatableComponent(key).getString(); }
    @Unique @Override public int textWidth(String text) { return font.width(text); }
    @Unique @Override public String draft() { return nyanlex$draft.getValue(); }
    @Unique @Override public String chat() { return input.getValue(); }
    @Unique @Override public String language() { return LegacyTranslatorMod.config().chatComposerLanguage; }
    @Unique @Override public void language(String tag) { LegacyTranslatorMod.config().chatComposerLanguage = tag; LegacyTranslatorMod.saveConfig(); }
    @Unique @Override public String[][] languages() { return nyanlex$languageRows; }
    @Unique @Override public double positionX() { return LegacyTranslatorMod.config().chatComposerX; }
    @Unique @Override public double positionY() { return LegacyTranslatorMod.config().chatComposerY; }
    @Unique @Override public void position(double x, double y) {
        LegacyTranslatorMod.config().chatComposerX = x; LegacyTranslatorMod.config().chatComposerY = y; LegacyTranslatorMod.saveConfig();
    }
    @Unique @Override public void fill(String text) {
        input.setValue(text);
        nyanlex$draft.setFocus(false);
        input.setFocus(true);
        setFocused(input);
    }
    @Unique @Override public boolean current() { return minecraft.screen == this; }
    @Unique @Override public void execute(Runnable action) { minecraft.execute(action); }
    @Unique @Override public void translate(String text, String target, java.util.function.BiConsumer<String, String> callback) {
        LegacyTranslatorMod.translateDraft(text, target, callback);
    }
    @Unique private String[][] nyanlex$languages() {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[]{"en", "English"});
        for (net.minecraft.client.resources.language.LanguageInfo info : minecraft.getLanguageManager().getLanguages()) {
            rows.add(new String[]{info.getCode().replace("_", "-"), info.toString()});
        }
        return rows.toArray(new String[0][]);
    }
    @Unique private void nyanlex$focusSearch() {
        if (nyanlex$composer.choosing()) {
            input.setFocus(false);
            nyanlex$draft.setFocus(false);
            setFocused(nyanlex$search);
            nyanlex$search.setFocus(true);
        } else nyanlex$focusDraft();
    }
    @Unique private void nyanlex$focusDraft() {
        if (nyanlex$composer == null) return;
        nyanlex$search.setFocus(false);
        input.setFocus(false);
        setFocused(nyanlex$draft);
        nyanlex$draft.setFocus(true);
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
    private void nyanlex$renderComposer(PoseStack g, int mx, int my, float delta, CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.renderComposer")) return;
        try {
            if (nyanlex$composer == null) return;
            ChatComposerPanel.Canvas canvas = new GuiCanvas(g, font);
            nyanlex$composer.render(canvas);
            nyanlex$draft.x = nyanlex$composer.inputX(); nyanlex$draft.y = nyanlex$composer.inputY();
            nyanlex$draft.render(g, mx, my, delta);
            nyanlex$composer.renderChoices(canvas);
            if (nyanlex$composer.choosing()) {
            nyanlex$search.x = nyanlex$composer.searchX(); nyanlex$search.y = nyanlex$composer.searchY();
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
            } else { nyanlex$draft.setFocus(false); input.setFocus(true); setFocused(input); }
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
    private void nyanlex$scrollComposer(double mx, double my, double amount, CallbackInfoReturnable<Boolean> ci) {
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
            else if (key == 258) { nyanlex$draft.setFocus(false); input.setFocus(true); setFocused(input); }
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
