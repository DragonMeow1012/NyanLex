package com.dragonmeow.nyanslate.neoforge26.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code AbstractContainerScreen.hoveredSlot} (protected) so the
 * "re-translate pointed item" hotkey can read the slot under the mouse while a
 * container screen is open.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("hoveredSlot")
    Slot nyanslate$hoveredSlot();
}
