package com.dragonmeow.nyanlex.fabric;

import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;

/** Version-native history exposed by the chat Mixin for original-position updates. */
public interface ChatComponentAccess {
    List<GuiMessage<Component>> nyanlex$getAllMessages();
}
