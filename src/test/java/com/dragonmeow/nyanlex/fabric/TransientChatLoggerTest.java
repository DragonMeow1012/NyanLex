package com.dragonmeow.nyanlex.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

/** The chat line itself, built from real components; nothing here touches a running game. */
class TransientChatLoggerTest {

    @Test
    void theLineIsADimPrefixThenTheTextWithItsOwnColours() {
        Style aqua = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        MutableComponent body = Component.empty()
                .append(Component.literal("平凡的話"))
                .append(Component.literal("藍色片語").setStyle(aqua));
        MutableComponent line = TransientChatLogger.compose(Component.literal("[米拉]"), body);
        assertEquals("[米拉] 平凡的話藍色片語", line.getString());
        Style prefix = line.getSiblings().get(0).getStyle();
        assertNotNull(prefix.getColor());
        assertEquals(ChatFormatting.GRAY.getColor(), prefix.getColor().getValue());
        // the translation keeps what the segments carried
        MutableComponent text = (MutableComponent) line.getSiblings().get(2);
        assertEquals(0x55FFFF, text.getSiblings().get(1).getStyle().getColor().getValue());
    }

    @Test
    void withoutAPrefixTheLineIsJustTheText() {
        assertEquals("只有文字。", TransientChatLogger.compose(null, Component.literal("只有文字。")).getString());
    }
}
