package com.dragonmeow.nyanlex.fabric.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the 1.19.4 chat history so an asynchronous translation can replace its source row. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements com.dragonmeow.nyanlex.fabric.ChatComponentAccess {
    @Accessor("allMessages")
    public abstract java.util.List<GuiMessage> nyanlex$getAllMessages();
}
