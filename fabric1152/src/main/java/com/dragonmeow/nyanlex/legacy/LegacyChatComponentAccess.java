package com.dragonmeow.nyanlex.legacy;

import java.util.List;
import net.minecraft.client.GuiMessage;

/** The same history boundary used by modern chat's original-position updates. */
public interface LegacyChatComponentAccess {
    List<GuiMessage> nyanlex$getAllMessages();
}
