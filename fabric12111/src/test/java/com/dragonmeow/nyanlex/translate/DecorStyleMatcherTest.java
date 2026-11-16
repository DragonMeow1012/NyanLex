package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DecorStyleMatcherTest {

    private static final String ICON = new String(Character.toChars(0xE001));
    private static final String OTHER = new String(Character.toChars(0xE002));
    private static final String MOVE = new String(Character.toChars(0xD0002));

    private static DecorStyleMatcher.Piece piece(String text, int style) {
        return new DecorStyleMatcher.Piece(text, style);
    }

    @Test
    void decorMeansIconsMovesAndSymbolsNeverLetters() {
        assertTrue(DecorStyleMatcher.isDecor(0xE001));
        assertTrue(DecorStyleMatcher.isDecor(0xD0002));
        assertTrue(DecorStyleMatcher.isDecor(0xC0001));
        assertTrue(DecorStyleMatcher.isDecor(0x2694)); // crossed swords
        assertTrue(DecorStyleMatcher.isDecor(0x2500)); // box drawing
        assertFalse(DecorStyleMatcher.isDecor('A'));
        assertFalse(DecorStyleMatcher.isDecor('5'));
        assertFalse(DecorStyleMatcher.isDecor(' '));
        assertFalse(DecorStyleMatcher.isDecor('-'));
        assertFalse(DecorStyleMatcher.isDecor('中'));
        assertFalse(DecorStyleMatcher.isDecor(0xA7));
        assertFalse(DecorStyleMatcher.isDecor(0x27E6));
        assertFalse(DecorStyleMatcher.isDecor(0xFFFD));
    }

    @Test
    void aRunIsPairedWithTheSameCharactersOfTheTranslation() {
        int[] map = DecorStyleMatcher.match(List.of(piece(ICON, 1), piece(" Helmet", 2)), ICON + "頭盔");
        assertArrayEquals(new int[] {1, -1, -1}, map);
    }

    @Test
    void equalRunsAreTakenInOrderOfAppearance() {
        int[] map = DecorStyleMatcher.match(
                List.of(piece(ICON, 1), piece(" a ", 0), piece(ICON, 2)), "一" + ICON + "二" + ICON);
        assertArrayEquals(new int[] {-1, 1, -1, 2}, map);
    }

    @Test
    void aLongerRunIsFoundBeforeItsParts() {
        int[] map = DecorStyleMatcher.match(
                List.of(piece(ICON + OTHER, 5), piece(" x ", 0), piece(ICON, 6)),
                ICON + "字" + ICON + OTHER);
        // the two-icon run claims the last two characters; the single icon takes the first
        assertArrayEquals(new int[] {6, -1, 5, 5}, map);
    }

    @Test
    void touchingPiecesOfOneStyleAreOneRunButDifferentStylesAreNot() {
        int[] same = DecorStyleMatcher.match(List.of(piece(ICON, 3), piece(OTHER, 3)), ICON + OTHER);
        assertArrayEquals(new int[] {3, 3}, same);
        int[] different = DecorStyleMatcher.match(List.of(piece(ICON, 3), piece(OTHER, 4)), OTHER + ICON);
        assertArrayEquals(new int[] {4, 3}, different);
    }

    @Test
    void aMoveBetweenWordsIsPairedByItsCharacters() {
        int[] map = DecorStyleMatcher.match(List.of(piece("1-5", 0), piece(MOVE, 1), piece("Level", 1)),
                "1-5" + MOVE + "等級");
        assertEquals(1, map[3]);
        assertEquals(1, map[4]);
        assertEquals(-1, map[5]);
        assertEquals(-1, map[0]);
    }

    @Test
    void whatTheTranslationDroppedOrChangedStaysUnpaired() {
        assertNull(DecorStyleMatcher.match(List.of(piece(ICON, 1), piece("Helmet", 2)), "頭盔"));
        assertNull(DecorStyleMatcher.match(List.of(piece("plain", 1)), ICON + "頭盔"));
        assertNull(DecorStyleMatcher.match(List.of(), ICON));
        // only one of two icons survived: it takes the first of the two styles
        assertArrayEquals(new int[] {1}, DecorStyleMatcher.match(List.of(piece(ICON, 1), piece(ICON, 2)), ICON));
    }

    @Test
    void aRowOfNothingButGraphicsIsAGraphicRowAndAHeadingIsNot() {
        assertTrue(ParagraphModel.isGraphicOnly(ICON + MOVE + OTHER + "  "));
        assertTrue(ParagraphModel.isGraphicOnly("───"));
        assertFalse(ParagraphModel.isGraphicOnly(ICON + " Unidentified Helmet"));
        assertFalse(ParagraphModel.isGraphicOnly("1-5"));
        assertFalse(ParagraphModel.isGraphicOnly("   "));
        assertFalse(ParagraphModel.isGraphicOnly(null));
    }

    @Test
    void aGraphicRowIsNeverJoinedIntoTheParagraphsAroundIt() {
        List<ParagraphModel.Range> ranges = ParagraphModel.ranges(List.of(
                ICON + " Unidentified Helmet", ICON + OTHER, "1-5 Level Range",
                MOVE + ICON + MOVE, "This item's power has been sealed,", "an Item Identifier can unlock it."));
        assertEquals(List.of(new ParagraphModel.Range(0, 0), new ParagraphModel.Range(1, 1),
                new ParagraphModel.Range(2, 2), new ParagraphModel.Range(3, 3),
                new ParagraphModel.Range(4, 5)), ranges);
    }

    @Test
    void layoutMovesAreDecorativeAndSoNeverTheReasonToTranslateALine() {
        assertFalse(TextFilter.shouldTranslate(MOVE + ICON + MOVE, "zh-TW"));
        assertFalse(TextFilter.shouldTranslate(ICON + MOVE + OTHER, "zh-TW"));
        assertTrue(TextFilter.shouldTranslate(MOVE + ICON + " Unidentified Helmet", "zh-TW"));
    }

    @Test
    void layoutMovesRideInTheSameSlotAsTheIconNextToThem() {
        TemplateText.Prepared prepared = TemplateText.prepare(ICON + MOVE + " Unidentified Helmet");
        assertFalse(prepared.text().contains(MOVE), prepared.text());
        assertFalse(prepared.text().contains(ICON), prepared.text());
        assertEquals(ICON + MOVE + " 未鑑定的頭盔", prepared.restore(prepared.text().replace("Unidentified Helmet", "未鑑定的頭盔")));
    }
}
