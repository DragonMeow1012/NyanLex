package com.dragonmeow.nyanlex.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;

/** Normalizes the version-specific sign message container for the shared 26.x renderer. */
public final class SignTextAccess {
    private SignTextAccess() {}

    public static Component[] messages(SignText sign, boolean filtered) {
        return sign.getMessages(filtered);
    }
}
