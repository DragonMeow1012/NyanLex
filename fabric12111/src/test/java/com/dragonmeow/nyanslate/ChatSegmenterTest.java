package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.ChatSegmenter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatSegmenterTest {

    @Test
    void splitsAfterGuillemetSeparator() {
        String s = "[Dangerous][Champion] Dgmroxxx \u00BB ./playtime rewards";
        int i = ChatSegmenter.contentStart(s);
        assertEquals("./playtime rewards", s.substring(i));
    }

    @Test
    void splitsAfterDoubleAngle() {
        String s = "[Master] BigSoftieBoi >> what do you do?";
        int i = ChatSegmenter.contentStart(s);
        assertEquals("what do you do?", s.substring(i));
    }

    @Test
    void splitsBracketedVanillaChat() {
        String s = "<Alice> hello there";
        int i = ChatSegmenter.contentStart(s);
        assertEquals("hello there", s.substring(i));
    }

    @Test
    void splitsColonChatFromDifferentPlayersToSameContent() {
        String a = "[VIP] Alice: hello there";
        String b = "[VIP] Bob: hello there";
        assertEquals("hello there", a.substring(ChatSegmenter.contentStart(a)));
        assertEquals("hello there", b.substring(ChatSegmenter.contentStart(b)));
    }

    @Test
    void noSeparatorReturnsMinusOne() {
        assertEquals(-1, ChatSegmenter.contentStart("Welcome to the server!"));
        assertEquals(-1, ChatSegmenter.contentStart("Server restarting in 5 minutes"));
    }

    @Test
    void nullAndEmptySafe() {
        assertEquals(-1, ChatSegmenter.contentStart(null));
        assertEquals(-1, ChatSegmenter.contentStart(""));
    }
}
