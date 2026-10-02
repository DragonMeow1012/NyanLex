package com.dragonmeow.nyanslate.fabric.mixin;

import com.dragonmeow.nyanslate.fabric.NyanslateFabric;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * FTB Library / FTB Quests support (optional — {@link Pseudo} so it is a safe no-op when FTB
 * is absent). FTB's {@code TextField} (used by the quest-description panel) takes the WHOLE
 * description {@link Component} in {@code setText}, then splits it itself
 * ({@code Theme.listFormattedStringToWidth}) and draws each wrapped piece separately — so our
 * render-level hooks only ever see fragments (choppy, and inline styled runs get dropped).
 *
 * <p>Here we translate the whole Component at {@code setText} <em>before</em> FTB wraps it, so
 * the description is one coherent translation that FTB then wraps normally (styling intact).
 * Gated by {@code screenTextMode} via {@link NyanslateFabric#screenText(Component)}.</p>
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.TextField")
public abstract class TextFieldMixin {

    @ModifyVariable(
            method = "setText(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/ui/TextField;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component nyanslate$translateWhole(Component component) {
        return NyanslateFabric.ftbText(this, component);
    }
}
