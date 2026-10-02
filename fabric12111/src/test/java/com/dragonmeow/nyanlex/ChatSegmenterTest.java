package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.ChatSegmenter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static String content(String s) {
        int i = ChatSegmenter.contentStart(s);
        return i < 0 ? null : s.substring(i);
    }

    @Test
    void rankedAndChannelSpeakersAreSplit() {
        assertEquals("hi", content("[MVP+] Name: hi"));
        assertEquals("hi", content("[VIP] Name: hi"));
        assertEquals("hi", content("Guild > [VIP] Name: hi"));
        assertEquals("hi", content("Party > Alice: hi"));
        assertEquals("hi", content("From [MVP+] Name: hi"));
        assertEquals("hi", content("To Alice: hi"));
        assertEquals("hi", content("<Steve> hi"));
        assertEquals("hi", content("Steve: hi"));
        assertEquals("hi", content("§a[VIP] §bName§r: hi"));
        assertEquals("hi", content("[123✫] [MVP+] Name [Admin]: hi"));
    }

    @Test
    void sentencesAndLabelsAreNotSplit() {
        assertNull(content("Your quest 'X' is complete. Reward: 250 coins!"));
        assertNull(content("Reward: 250 coins"));
        assertNull(content("Tip: use /help"));
        assertNull(content("You have 5 coins left: spend them wisely"));
        assertNull(content("Cost: 100 coins"));
        assertNull(content("Click here to open the shop: https://example.com"));
    }

    @Test
    void serverSystemPrefixIsTreatedAsSpeaker() {
        // Documented trade-off: a single bare word before ": " is indistinguishable from a name.
        assertEquals("restarting in 5 min", content("Server: restarting in 5 min"));
    }

    @Test
    void sameContentFromDifferentRankedSpeakersSharesContent() {
        assertEquals(content("[MVP+] Alice: gg"), content("Guild > [VIP] Bob: gg"));
    }
}
