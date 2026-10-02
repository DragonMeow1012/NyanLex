package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric.NyanLexFabric;

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
 * Gated by {@code screenTextMode} via {@link NyanLexFabric#screenText(Component)}.</p>
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.TextField")
public abstract class TextFieldMixin {
    @ModifyVariable(
            method = "setText(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/ui/TextField;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanlex$translateWhole(Component component) {
        if (!HookGuard.enter("TextField.translateWhole")) return component;
        try {
            return NyanLexFabric.questWidgetText(this, component);
        } catch (Throwable guardError) {
            HookGuard.fail("TextField.translateWhole", guardError);
            return component;
        }
    }
}
