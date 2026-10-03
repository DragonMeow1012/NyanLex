package com.dragonmeow.nyanlex.legacy.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.legacy.LegacyTranslatorMod;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Optional Jade/WAILA object-title integration; absent Jade classes are a safe no-op. */
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
            return LegacyTranslatorMod.translateVisible(component);
        } catch (Throwable guardError) {
            HookGuard.fail("Jade.objectName", guardError);
            return component;
        }
    }
}
