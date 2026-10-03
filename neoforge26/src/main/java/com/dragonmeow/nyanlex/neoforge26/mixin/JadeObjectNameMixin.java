package com.dragonmeow.nyanlex.neoforge26.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.neoforge26.NyanLexNeoForge26;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Optional Jade/WAILA object-title integration. Jade 11 keeps both providers in the
 * outer class, Jade 15 splits them, and Jade 26 delegates through a shared helper.
 * In every generation the first Component stored by appendTooltip is the block or
 * entity title; mod-name, health and numeric rows are produced by other providers.
 */
@Pseudo
@Mixin(targets = {
        "snownee.jade.addon.core.ObjectNameProvider",
        "snownee.jade.addon.core.ObjectNameProvider$ForBlock",
        "snownee.jade.addon.core.ObjectNameProvider$ForEntity"
})
public abstract class JadeObjectNameMixin {
    @ModifyVariable(method = "appendTooltip", at = @At("STORE"), ordinal = 0, require = 0)
    private Component nyanlex$translateObjectName(Component component) {
        if (!HookGuard.enter("Jade.objectName")) return component;
        try {
            return NyanLexNeoForge26.jadeObjectName(component);
        } catch (Throwable guardError) {
            HookGuard.fail("Jade.objectName", guardError);
            return component;
        }
    }
}
