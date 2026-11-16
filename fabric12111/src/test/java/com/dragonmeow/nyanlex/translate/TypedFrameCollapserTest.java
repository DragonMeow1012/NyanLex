package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedFrameCollapserTest {

    private final TypedFrameCollapser<String> collapser = new TypedFrameCollapser<>();
    private final List<String> written = new ArrayList<>();

    private void offer(String frame, String typed, long now) {
        written.addAll(collapser.offer(frame, typed, now));
    }

    @Test
    void aSentenceTypedOutIsOneFrameAndItIsTheLastOne() {
        String text = "";
        for (char c : "Hello there".toCharArray()) {
            text += c;
            offer("frame:" + text, text, text.length() * 20L);
        }
        assertEquals(List.of(), written);
        // the blinking icon after the text is complete changes the frame but not the words
        offer("frame:blink1", "Hello there", 1_000);
        offer("frame:blink2", "Hello there", 2_000);
        assertEquals(List.of(), written);
        // the next page
        offer("frame:next", "N", 3_000);
        assertEquals(List.of("frame:Hello there"), written);
    }

    @Test
    void aFrameThatIsNotATextIsWrittenAtOnceAfterTheHeldOne() {
        offer("frame:one", "One", 0);
        offer("hud", null, 10);
        assertEquals(List.of("frame:one", "hud"), written);
    }

    @Test
    void aTextThatChangesInAnyOtherWayReleasesTheHeldFrame() {
        offer("a", "Hello", 0);
        offer("b", "Help", 10);
        offer("c", "Help!", 20);
        assertEquals(List.of("a"), written);
        written.addAll(collapser.idle(10_000, 800));
        assertEquals(List.of("a", "c"), written);
    }

    @Test
    void aHeldFrameIsWrittenOnceNothingNewerCame() {
        offer("a", "Hello", 100);
        assertEquals(List.of(), collapser.idle(500, 800));
        assertEquals(List.of("a"), collapser.idle(900, 800));
        assertEquals(List.of(), collapser.idle(5_000, 800));
    }

    @Test
    void anEmptyTextIsATextToo() {
        offer("fade1", "", 0);
        offer("fade2", "", 10);
        offer("page", "Hi", 20);
        assertEquals(List.of("fade1"), written);
    }
}
