package com.dragonmeow.nyanlex.fabric;

import net.minecraft.client.GuiMessage;
import java.util.List;

/** Runtime-safe duck interface implemented on ChatComponent by the mixin. */
public interface ChatComponentAccess {
    List<GuiMessage> nyanlex$getAllMessages();
}
