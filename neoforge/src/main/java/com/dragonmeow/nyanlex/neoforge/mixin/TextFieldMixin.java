package com.dragonmeow.nyanlex.neoforge.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge.NyanLexNeoForge;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Optional quest-book text widget support ({@link Pseudo}, so it is a safe no-op when the
 * target class is absent). The widget takes the WHOLE description {@link Component} in
 * {@code setText}, then wraps it itself and draws each wrapped piece separately, so render-level
 * hooks only see fragments (choppy, and inline styled runs get dropped).
 *
 * <p>The whole Component is translated at {@code setText} <em>before</em> it is wrapped, so the
 * description is one coherent translation that the widget then wraps normally (styling intact).
 * Gated by {@code screenTextMode} via {@link NyanLexNeoForge#screenText(Component)}.</p>
 */
@Pseudo
@Mixin(targets = {
        "dev.ftb.mods.ftblibrary.ui.TextField",
        "dev.ftb.mods.ftblibrary.client.gui.widget.TextField"
})
public abstract class TextFieldMixin {
    @ModifyVariable(
            method = "setText(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/ui/TextField;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanlex$translateWholeLegacy(Component component) {
        if (!HookGuard.enter("TextField.translateWholeLegacy")) return component;
        try {
            return nyanlex$translateWhole(component);
        } catch (Throwable guardError) {
            HookGuard.fail("TextField.translateWholeLegacy", guardError);
            return component;
        }
    }

    @ModifyVariable(
            method = "setText(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/client/gui/widget/TextField;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanlex$translateWholeModern(Component component) {
        if (!HookGuard.enter("TextField.translateWholeModern")) return component;
        try {
            return nyanlex$translateWhole(component);
        } catch (Throwable guardError) {
            HookGuard.fail("TextField.translateWholeModern", guardError);
            return component;
        }
    }

    private Component nyanlex$translateWhole(Component component) {
        return NyanLexNeoForge.questWidgetText(this, component);
    }
}
