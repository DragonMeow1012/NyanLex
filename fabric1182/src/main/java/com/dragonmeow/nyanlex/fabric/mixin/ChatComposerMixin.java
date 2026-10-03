package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import com.dragonmeow.nyanlex.translate.ChatComposerPanel;
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
public abstract class ChatComposerMixin extends Screen {
    @Shadow protected EditBox input;
    @Unique private EditBox nyanlex$draft;
    @Unique private boolean nyanlex$enterHeld;
    @Unique private ChatComposerPanel nyanlex$composer;
    protected ChatComposerMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"), require = 1)
    private void nyanlex$initComposer(CallbackInfo ci) {
        if (!HookGuard.enter("ChatComposer.initComposer")) return;
        try {
            if (NyanLexFabric.config() == null || !NyanLexFabric.config().chatComposerEnabled
                    || input.getValue().startsWith("/")) return;
            String draft = nyanlex$draft == null ? "" : nyanlex$draft.getValue();
            if (nyanlex$composer != null) nyanlex$composer.close();
            final String[][] languages = nyanlex$languages();
            nyanlex$composer = new ChatComposerPanel(new ChatComposerPanel.Host() {
                public String text(String key) { return new TranslatableComponent(key).getString(); }
                public int textWidth(String text) { return font.width(text); }
                public String draft() { return nyanlex$draft.getValue(); }
                public String chat() { return input.getValue(); }
                public String language() { return NyanLexFabric.config().chatComposerLanguage; }
                public void language(String tag) { NyanLexFabric.config().chatComposerLanguage = tag; NyanLexFabric.saveConfig(); }
                public String[][] languages() { return languages; }
                public double positionX() { return NyanLexFabric.config().chatComposerX; }
                public double positionY() { return NyanLexFabric.config().chatComposerY; }
                public void position(double x, double y) {
                    NyanLexFabric.config().chatComposerX = x; NyanLexFabric.config().chatComposerY = y; NyanLexFabric.saveConfig();
                }
                public void fill(String text) {
                    input.setValue(text);
                    nyanlex$draft.setFocus(false);
                    input.setFocus(true);
                    setFocused(input);
                }
                public boolean current() { return minecraft.screen == (Object) ChatComposerMixin.this; }
                public void execute(Runnable action) { minecraft.execute(action); }
                public void translate(String text, String target, java.util.function.BiConsumer<String, String> callback) {
                    NyanLexFabric.outgoingChat.translate(text, target, callback);
                }
            });
            nyanlex$composer.resize(width, height);
            nyanlex$draft = new EditBox(font, nyanlex$composer.inputX(), nyanlex$composer.inputY(),
                    nyanlex$composer.inputWidth(), 20, new TranslatableComponent("nyanlex.composer.title"));
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
        for (net.minecraft.client.resources.language.LanguageInfo info : minecraft.getLanguageManager().getLanguages()) {
            rows.add(new String[]{com.dragonmeow.nyanlex.config.TranslationLanguages.fromMinecraftCode(info.getCode()), info.toString()});
        }
        return rows.toArray(new String[0][]);
    }
    @Unique private void nyanlex$focusDraft() {
        if (nyanlex$composer == null) return;
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
            ChatComposerPanel.Canvas canvas = new ChatComposerPanel.Canvas() {
                public void fill(int x, int y, int w, int h, int color) { net.minecraft.client.gui.GuiComponent.fill(g, x, y, x + w, y + h, color); }
                public void text(String text, int x, int y, int color) { font.draw(g, text, x, y, color); }
            };
            nyanlex$composer.render(canvas);
            nyanlex$draft.x = nyanlex$composer.inputX(); nyanlex$draft.y = nyanlex$composer.inputY();
            nyanlex$draft.render(g, mx, my, delta);
            nyanlex$composer.renderChoices(canvas);
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
                nyanlex$composer.click(mx, my, button); ci.setReturnValue(true); return;
            }
            boolean inside = nyanlex$composer.contains(mx, my);
            if (inside) {
                if (my >= nyanlex$composer.inputY() && my < nyanlex$composer.inputY() + 20) {
                    nyanlex$focusDraft(); nyanlex$draft.mouseClicked(mx, my, button);
                } else nyanlex$composer.click(mx, my, button);
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
