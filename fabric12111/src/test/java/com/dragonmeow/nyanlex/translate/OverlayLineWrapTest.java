package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OverlayLineWrapTest {

    /** Latin 5 px (space 4), CJK 9 px. */
    private static final OverlayLineWrap.CharWidth W =
            cp -> cp == ' ' ? 4 : OverlayLineWrap.isWide(cp) ? 9 : 5;

    @Test
    void blankTextHasNoLines() {
        assertTrue(OverlayLineWrap.wrap("", 100, W).isEmpty());
        assertTrue(OverlayLineWrap.wrap("  \n ", 100, W).isEmpty());
        assertTrue(OverlayLineWrap.wrap(null, 100, W).isEmpty());
    }

    @Test
    void latinWordsAreNotCutAndSpacesVanishAtBreaks() {
        // each letter 5 px: "aaaa" = 20, space 4
        List<String> lines = OverlayLineWrap.wrap("aaaa bbbb cccc dddd", 45, W);
        assertEquals(List.of("aaaa bbbb", "cccc dddd"), lines);
    }

    @Test
    void cjkBreaksBetweenAnyTwoCharacters() {
        assertEquals(List.of("一二三四五", "六七八九十", "一"),
                OverlayLineWrap.wrap("一二三四五六七八九十一", 45, W));
    }

    @Test
    void mixedTextKeepsLatinWordsWhole() {
        // 4 CJK = 36, then "Harbor" = 30 does not fit in 50 -> next line
        assertEquals(List.of("前往一二", "Harbor去吧"),
                OverlayLineWrap.wrap("前往一二Harbor去吧", 50, W));
    }

    @Test
    void closingPunctuationNeverStartsALine() {
        // 5 chars fill 45 px exactly; the full stop would start line two, so one char goes with it
        List<String> lines = OverlayLineWrap.wrap("一二三四五。六七", 45, W);
        assertEquals(List.of("一二三四", "五。六七"), lines);
    }

    @Test
    void openingBracketNeverEndsALine() {
        List<String> lines = OverlayLineWrap.wrap("一二三四「五六七", 45, W);
        assertEquals(List.of("一二三四", "「五六七"), lines);
    }

    @Test
    void punctuationChainsMoveTogether() {
        // six characters fit (54 px); the full stop that follows would start a line, and so would
        // the bracket before it, so two characters move down
        List<String> lines = OverlayLineWrap.wrap("一二三四五」。六", 54, W);
        assertEquals(List.of("一二三四", "五」。六"), lines);
    }

    @Test
    void aWordWiderThanTheLineIsSplitByCharacter() {
        List<String> lines = OverlayLineWrap.wrap("abcdefghij", 20, W);
        assertEquals(List.of("abcd", "efgh", "ij"), lines);
    }

    @Test
    void lineBreaksInTheTextActAsSpaces() {
        assertEquals(List.of("aa bb"), OverlayLineWrap.wrap("aa\nbb", 100, W));
    }

    @Test
    void everyLineFitsTheLimit() {
        String text = "啊。這是不是表示我們得從這裡一路走到拉格尼去？ Ah. Does this mean we have to walk";
        for (int limit : new int[] {30, 60, 100, 200}) {
            for (String line : OverlayLineWrap.wrap(text, limit, W)) {
                int w = 0;
                for (int i = 0; i < line.length(); ) {
                    int cp = line.codePointAt(i);
                    i += Character.charCount(cp);
                    w += W.of(cp);
                }
                assertTrue(w <= limit, line + " is " + w + " > " + limit);
            }
        }
    }
}
