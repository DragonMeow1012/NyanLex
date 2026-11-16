package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverlayDialogueTest {

    private static final String ROW0 = "Ah. Does this mean we have to walk all the";
    private static final String ROW1 = "way to Harbor from here?";
    private static final String SENTENCE = ROW0 + " " + ROW1;

    private static final OverlayDialogue.Wrapper WRAP = (font, style, text, limit) ->
            OverlayLineWrap.wrap(text, limit,
                    cp -> OverlayFixture.MEASURE.width(font, style, new String(Character.toChars(cp))));

    private static List<OverlayDialogue.Run> twoRowFrame() {
        return OverlayFixture.frame("Mira", "\ue000", ROW0, ROW1);
    }

    private static List<OverlayDialogue.Run> rewrite(List<OverlayDialogue.Run> in, Map<String, String> tr) {
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(OverlayDialogue.analyze(in), tr,
                OverlayFixture.MEASURE, OverlayDialogueSession.LINE_LIMIT_PX, WRAP);
        assertNotNull(out, "expected a rewrite");
        return OverlayFixture.toRuns(out, in);
    }

    /** Characters that are not move characters, drawn in {@code font}. */
    private static String inkOf(List<OverlayDialogue.Run> runs, String font) {
        StringBuilder sb = new StringBuilder();
        for (OverlayDialogue.Run run : runs) {
            if (!run.font().equals(font)) {
                continue;
            }
            for (int i = 0; i < run.text().length(); ) {
                int cp = run.text().codePointAt(i);
                i += Character.charCount(cp);
                if (!OverlaySpace.isMove(cp)) {
                    sb.appendCodePoint(cp);
                }
            }
        }
        return sb.toString();
    }

    /** Cursor position (px from the origin) at the first non-move character drawn in {@code font}. */
    private static int startOf(List<OverlayDialogue.Run> runs, String font) {
        int x = 0;
        for (int r = 0; r < runs.size(); r++) {
            OverlayDialogue.Run run = runs.get(r);
            for (int i = 0; i < run.text().length(); ) {
                int cp = run.text().codePointAt(i);
                i += Character.charCount(cp);
                if (!OverlaySpace.isMove(cp) && run.font().equals(font)) {
                    return x;
                }
                x += OverlayFixture.MEASURE.width(run.font(), r, new String(Character.toChars(cp)));
            }
        }
        throw new AssertionError("no ink in " + font);
    }

    @Test
    void analyzeReadsEveryBlockAsPlainText() {
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(twoRowFrame());
        assertTrue(a.dialogue());
        assertEquals(List.of("body", "nameplate", "control"), new ArrayList<>(a.sources().keySet()));
        assertEquals(SENTENCE, a.sources().get("body"));
        assertEquals("Mira", a.sources().get("nameplate"));
        assertEquals("to continue", a.sources().get("control"));
    }

    @Test
    void theKeyIgnoresHowTheRowsWereBroken() {
        List<OverlayDialogue.Run> oneRow = OverlayFixture.frame("Mira", "\ue000", SENTENCE);
        List<OverlayDialogue.Run> threeRows = OverlayFixture.frame("Mira", "\ue001",
                "Ah. Does this mean we", "have to walk all the way", "to Harbor from here?");
        assertEquals(SENTENCE, OverlayDialogue.analyze(oneRow).sources().get("body"));
        assertEquals(SENTENCE, OverlayDialogue.analyze(threeRows).sources().get("body"));
        // the blinking key icon is not part of the hint key
        assertEquals("to continue", OverlayDialogue.analyze(threeRows).sources().get("control"));
    }

    @Test
    void hudAloneIsNotADialogue() {
        List<OverlayDialogue.Run> hud = List.of(OverlayFixture.run(OverlayFixture.HUD, "\ue00a"),
                OverlayFixture.run("minecraft:default", "§6395/1,422"));
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(hud);
        assertFalse(a.dialogue());
        assertTrue(a.sources().isEmpty());
        assertNull(OverlayDialogue.rewrite(a, Map.of("body", "x"), OverlayFixture.MEASURE, 200, WRAP));
    }

    @Test
    void artworkOnlyBoxHasNothingToTranslate() {
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(OverlayFixture.frame(null, null));
        assertTrue(a.dialogue());
        assertTrue(a.sources().isEmpty());
    }

    @Test
    void hudChangesDoNotTouchTheKeys() {
        List<OverlayDialogue.Run> a = new ArrayList<>(twoRowFrame());
        List<OverlayDialogue.Run> b = new ArrayList<>(twoRowFrame());
        b.set(0, OverlayFixture.run(OverlayFixture.HUD, "§a296 " + OverlayFixture.move(-100) + "\ue008"));
        b.add(OverlayFixture.run("minecraft:hud/gameplay/default/top_right", "6,395/1,422"));
        assertEquals(OverlayDialogue.analyze(a).sources(), OverlayDialogue.analyze(b).sources());
    }

    @Test
    void bodyRewriteKeepsRowStartAndTotalWidth() {
        List<OverlayDialogue.Run> in = twoRowFrame();
        String translated = "啊。這是不是表示我們得從這裡一路走到那座港口去？";
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("body", translated));

        // 23 characters = 207 px > 200: two rows, and the closing mark does not start row two
        assertEquals("啊。這是不是表示我們得從這裡一路走到那座港口", inkOf(out, OverlayFixture.BODY + 0));
        assertEquals("去？", inkOf(out, OverlayFixture.BODY + 1));
        assertEquals(startOf(in, OverlayFixture.BODY + 0), startOf(out, OverlayFixture.BODY + 0));
        assertEquals(startOf(in, OverlayFixture.BODY + 0), startOf(out, OverlayFixture.BODY + 1));
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
        assertEquals(0, OverlayFixture.total(out));
        // everything that is not the text block is byte-for-byte the same
        assertEquals(inkOf(in, OverlayFixture.NAME), inkOf(out, OverlayFixture.NAME));
        assertEquals(inkOf(in, OverlayFixture.BOX), inkOf(out, OverlayFixture.BOX));
        assertEquals(inkOf(in, OverlayFixture.PORTRAIT), inkOf(out, OverlayFixture.PORTRAIT));
        assertEquals(inkOf(in, OverlayFixture.HUD), inkOf(out, OverlayFixture.HUD));
        assertEquals(inkOf(in, OverlayFixture.CONTROL), inkOf(out, OverlayFixture.CONTROL));
    }

    @Test
    void moreRowsThanTheOriginalUseTheNextRowFonts() {
        List<OverlayDialogue.Run> in = twoRowFrame();
        String translated = "測試文字".repeat(13);
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("body", translated));
        assertEquals(22, inkOf(out, OverlayFixture.BODY + 0).length());
        assertEquals(22, inkOf(out, OverlayFixture.BODY + 1).length());
        assertEquals(8, inkOf(out, OverlayFixture.BODY + 2).length());
        for (int row = 1; row <= 2; row++) {
            assertEquals(startOf(in, OverlayFixture.BODY + 0), startOf(out, OverlayFixture.BODY + row));
        }
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
    }

    @Test
    void fewerRowsThanTheOriginalLeaveTheRestEmpty() {
        List<OverlayDialogue.Run> in = twoRowFrame();
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("body", "好的。"));
        assertEquals("好的。", inkOf(out, OverlayFixture.BODY + 0));
        assertEquals("", inkOf(out, OverlayFixture.BODY + 1));
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
    }

    @Test
    void aSingleOriginalRowCanGrowToSeveral() {
        List<OverlayDialogue.Run> in = OverlayFixture.frame("Mira", "\ue000", "Ah. Does");
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("body", "測試文字".repeat(10)));
        assertEquals(22, inkOf(out, OverlayFixture.BODY + 0).length());
        assertEquals(18, inkOf(out, OverlayFixture.BODY + 1).length());
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
    }

    @Test
    void aPageThatDoesNotFitFiveRowsStaysOriginal() {
        List<OverlayDialogue.Run> in = twoRowFrame();
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        // 6 rows of 22 characters
        String huge = "測".repeat(22 * 5 + 1);
        assertNull(OverlayDialogue.rewrite(a, Map.of("body", huge), OverlayFixture.MEASURE, 200, WRAP));
        // five rows exactly still fit
        assertNotNull(OverlayDialogue.rewrite(a, Map.of("body", "測".repeat(22 * 5)),
                OverlayFixture.MEASURE, 200, WRAP));
    }

    @Test
    void anOverflowingBodyDoesNotBlockTheOtherBlocks() {
        List<OverlayDialogue.Run> in = twoRowFrame();
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("body", "測".repeat(200), "nameplate", "米拉"));
        assertEquals(ROW0, inkOf(out, OverlayFixture.BODY + 0));
        assertEquals("米拉", inkOf(out, OverlayFixture.NAME));
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
    }

    @Test
    void theLimitGrowsToTheWidestOriginalRow() {
        // the original row is 225 px wide (a layout without the portrait frame)
        String wide = "a".repeat(45);
        List<OverlayDialogue.Run> in = OverlayFixture.frame(null, null, wide);
        int original = OverlayFixture.MEASURE.width(OverlayFixture.BODY + 0, 0, wide);
        assertTrue(original > 200, "fixture should be wider than the base limit: " + original);
        // 25 CJK characters = 225 px: one row only because the limit grew with the original
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("body", "測".repeat(25)));
        assertEquals(25, inkOf(out, OverlayFixture.BODY + 0).length());
        assertEquals("", inkOf(out, OverlayFixture.BODY + 1));
        // 26 would be 234 px, over the grown limit: two rows
        List<OverlayDialogue.Run> two = rewrite(in, Map.of("body", "測".repeat(26)));
        assertEquals("測", inkOf(two, OverlayFixture.BODY + 1));
    }

    @Test
    void centredBlocksStayCentredAndKeepTheTotal() {
        List<OverlayDialogue.Run> in = OverlayFixture.frame("Road Warden", "\ue000", ROW0);
        int oldStart = startOf(in, OverlayFixture.NAME);
        int oldCentre = oldStart + OverlayFixture.MEASURE.width(OverlayFixture.NAME, 0, "Road Warden") / 2;
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("nameplate", "路政督察"));
        assertEquals("路政督察", inkOf(out, OverlayFixture.NAME));
        int newStart = startOf(out, OverlayFixture.NAME);
        assertEquals(oldCentre, newStart + 4 * 9 / 2);
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
        assertEquals(inkOf(in, OverlayFixture.BODY + 0), inkOf(out, OverlayFixture.BODY + 0));
    }

    @Test
    void theHintKeepsItsIconAndOnlyTheWordsChange() {
        for (String icon : new String[] {"\ue000", "\ue001"}) {
            List<OverlayDialogue.Run> in = OverlayFixture.frame("Mira", icon, ROW0);
            List<OverlayDialogue.Run> out = rewrite(in, Map.of("control", "繼續"));
            assertEquals(icon + " 繼續", inkOf(out, OverlayFixture.CONTROL));
            assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
            // icon moved right by half of the saved width (53 - 22 = 31 -> 15), the centre is kept
            assertEquals(startOf(in, OverlayFixture.CONTROL) + 15, startOf(out, OverlayFixture.CONTROL));
        }
    }

    @Test
    void aLongerTranslationShiftsTheOtherWay() {
        List<OverlayDialogue.Run> in = OverlayFixture.frame("Mira", null, ROW0);
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("nameplate", "米拉米拉米拉"));
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(out));
        assertTrue(startOf(out, OverlayFixture.NAME) < startOf(in, OverlayFixture.NAME));
    }

    @Test
    void untranslatedBlocksAreLeftAlone() {
        List<OverlayDialogue.Run> in = twoRowFrame();
        List<OverlayDialogue.Run> out = rewrite(in, Map.of("nameplate", "米拉"));
        assertEquals(ROW0, inkOf(out, OverlayFixture.BODY + 0));
        assertEquals(ROW1, inkOf(out, OverlayFixture.BODY + 1));
        assertEquals(inkOf(in, OverlayFixture.CONTROL), inkOf(out, OverlayFixture.CONTROL));
    }

    @Test
    void noTranslationNoRewrite() {
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(twoRowFrame());
        assertNull(OverlayDialogue.rewrite(a, Map.of(), OverlayFixture.MEASURE, 200, WRAP));
        assertNull(OverlayDialogue.rewrite(a, Map.of("choice2", "x"), OverlayFixture.MEASURE, 200, WRAP));
        assertNull(OverlayDialogue.rewrite(a, Map.of("body", "  "), OverlayFixture.MEASURE, 200, WRAP));
    }

    @Test
    void bodyInterleavedWithOtherElementsIsNotTouched() {
        List<OverlayDialogue.Run> in = new ArrayList<>();
        in.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(-92) + "Hello"));
        in.add(OverlayFixture.run(OverlayFixture.BOX, "\ue003"));
        in.add(OverlayFixture.run(OverlayFixture.BODY + 1, "world"));
        assertFalse(OverlayDialogue.analyze(in).sources().containsKey("body"));
    }

    @Test
    void mixedStylesOrSectionSignsOrUnknownMovesAreNotTouched() {
        List<OverlayDialogue.Run> mixed = List.of(
                OverlayFixture.run(OverlayFixture.BODY + 0, "Hello"),
                OverlayFixture.run("minecraft:hud/dialogue/text/other/body_1", "world"));
        assertFalse(OverlayDialogue.analyze(mixed).sources().containsKey("body"));

        List<OverlayDialogue.Run> sign = List.of(OverlayFixture.run(OverlayFixture.BODY + 0, "§cHello"));
        assertFalse(OverlayDialogue.analyze(sign).sources().containsKey("body"));

        List<OverlayDialogue.Run> unknown = List.of(
                OverlayFixture.run(OverlayFixture.BODY + 0, "Hello" + new String(Character.toChars(0xC5000)) + "x"));
        assertFalse(OverlayDialogue.analyze(unknown).sources().containsKey("body"));
    }

    @Test
    void aRowSplitIntoStyledRunsIsJoinedAndTakesTheFirstStyle() {
        List<OverlayDialogue.Run> in = List.of(
                OverlayFixture.run(OverlayFixture.BODY + 0, "Hello "),
                OverlayFixture.run(OverlayFixture.BODY + 0, "brave world"));
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        assertEquals("Hello brave world", a.sources().get("body"));
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(a, Map.of("body", "你好勇敢的世界"),
                OverlayFixture.MEASURE, 200, WRAP);
        assertNotNull(out);
        assertEquals(0, out.get(0).styleSource());
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(OverlayFixture.toRuns(out, in)));
    }

    @Test
    void answersAreSeparateSingleRowBlocks() {
        String choice0 = "minecraft:hud/dialogue/text/standard/choice_0";
        String choice1 = "minecraft:hud/dialogue/text/standard/choice_1";
        List<OverlayDialogue.Run> in = List.of(
                OverlayFixture.run(OverlayFixture.BOX, "\ue003"),
                OverlayFixture.run(choice0, OverlayFixture.move(-60) + "Yes, please." + OverlayFixture.move(-6)),
                OverlayFixture.run(choice1, OverlayFixture.move(66) + "No." + OverlayFixture.move(-60)));
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        assertEquals("Yes, please.", a.sources().get("choice0"));
        assertEquals("No.", a.sources().get("choice1"));
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(a, Map.of("choice0", "好的，請。"),
                OverlayFixture.MEASURE, 200, WRAP);
        assertNotNull(out);
        List<OverlayDialogue.Run> runs = OverlayFixture.toRuns(out, in);
        assertEquals("好的，請。", inkOf(runs, choice0));
        assertEquals("No.", inkOf(runs, choice1));
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(runs));
    }

    // ---------------------------------------------------------------- coloured phrases

    /** Row 0: plain words and a coloured phrase; row 1: more plain words. */
    private static List<OverlayDialogue.Run> colouredFrame() {
        return OverlayFixture.frameRows("Mira", "\ue000", new String[][] {
                {"Plain words ", "BLUE WORDS"}, {" go on, plain."}});
    }

    private static int runIndex(List<OverlayDialogue.Run> runs, String text) {
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).text().equals(text)) {
                return i;
            }
        }
        throw new AssertionError("no run " + text);
    }

    /** (text, style) pieces the output draws in {@code font}, move characters removed. */
    private static List<String> piecesIn(List<OverlayDialogue.OutRun> out, List<OverlayDialogue.Run> in,
                                         String font) {
        List<String> pieces = new ArrayList<>();
        for (OverlayDialogue.OutRun run : out) {
            String runFont = run.font() != null ? run.font() : in.get(run.styleSource()).font();
            if (!runFont.equals(font)) {
                continue;
            }
            StringBuilder ink = new StringBuilder();
            for (int i = 0; i < run.text().length(); ) {
                int cp = run.text().codePointAt(i);
                i += Character.charCount(cp);
                if (!OverlaySpace.isMove(cp)) {
                    ink.appendCodePoint(cp);
                }
            }
            if (ink.length() > 0) {
                pieces.add(ink + "@" + run.styleSource());
            }
        }
        return pieces;
    }

    @Test
    void theSpokenTextKeepsTheRunOfEveryPiece() {
        List<OverlayDialogue.Run> in = colouredFrame();
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        assertEquals("Plain words BLUE WORDS go on, plain.", a.sources().get("body"));
        int plain = runIndex(in, "Plain words ");
        int blue = runIndex(in, "BLUE WORDS");
        int tail = runIndex(in, " go on, plain.");
        assertEquals(List.of(
                new OverlayDialogue.BodyPiece("Plain words ", plain),
                new OverlayDialogue.BodyPiece("BLUE WORDS", blue),
                new OverlayDialogue.BodyPiece(" ", -1),
                new OverlayDialogue.BodyPiece("go on, plain.", tail)), a.bodyPieces());
        StringBuilder joined = new StringBuilder();
        for (OverlayDialogue.BodyPiece piece : a.bodyPieces()) {
            joined.append(piece.text());
        }
        assertEquals(a.sources().get("body"), joined.toString());
    }

    @Test
    void aColouredPhraseColoursTheMatchingCharactersOnly() {
        List<OverlayDialogue.Run> in = colouredFrame();
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        int plain = runIndex(in, "Plain words ");
        int blue = runIndex(in, "BLUE WORDS");
        List<OverlayDialogue.Segment> segments = List.of(
                new OverlayDialogue.Segment("一二三四", plain),
                new OverlayDialogue.Segment("藍藍藍", blue),
                new OverlayDialogue.Segment("五六七八。", plain));
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(a, Map.of(), segments,
                OverlayFixture.MEASURE, OverlayDialogueSession.LINE_LIMIT_PX, WRAP);
        assertNotNull(out);
        assertEquals(List.of("一二三四@" + plain, "藍藍藍@" + blue, "五六七八。@" + plain),
                piecesIn(out, in, OverlayFixture.BODY + 0));
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(OverlayFixture.toRuns(out, in)));
    }

    @Test
    void aColouredPhraseThatCrossesARowKeepsItsColourOnBothRows() {
        List<OverlayDialogue.Run> in = colouredFrame();
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        int plain = runIndex(in, "Plain words ");
        int blue = runIndex(in, "BLUE WORDS");
        // 15 + 10 + 5 characters; a row holds 22, so the coloured ten are split 7 + 3
        List<OverlayDialogue.Segment> segments = List.of(
                new OverlayDialogue.Segment("一".repeat(15), plain),
                new OverlayDialogue.Segment("藍".repeat(10), blue),
                new OverlayDialogue.Segment("二".repeat(5), plain));
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(a, Map.of(), segments,
                OverlayFixture.MEASURE, OverlayDialogueSession.LINE_LIMIT_PX, WRAP);
        assertNotNull(out);
        assertEquals(List.of("一".repeat(15) + "@" + plain, "藍".repeat(7) + "@" + blue),
                piecesIn(out, in, OverlayFixture.BODY + 0));
        assertEquals(List.of("藍".repeat(3) + "@" + blue, "二".repeat(5) + "@" + plain),
                piecesIn(out, in, OverlayFixture.BODY + 1));
        List<OverlayDialogue.Run> runs = OverlayFixture.toRuns(out, in);
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(runs));
        assertEquals(startOf(in, OverlayFixture.BODY + 0), startOf(runs, OverlayFixture.BODY + 1));
    }

    @Test
    void aSingleStyleTextIsDrawnExactlyAsThePlainStringIs() {
        List<OverlayDialogue.Run> in = twoRowFrame();
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        String translated = "啊。這是不是表示我們得從這裡一路走到那座港口去？";
        List<OverlayDialogue.OutRun> viaString = OverlayDialogue.rewrite(a, Map.of("body", translated),
                OverlayFixture.MEASURE, OverlayDialogueSession.LINE_LIMIT_PX, WRAP);
        List<OverlayDialogue.OutRun> viaSegments = OverlayDialogue.rewrite(a, Map.of(),
                List.of(new OverlayDialogue.Segment(translated, a.bodyPieces().get(0).styleSource())),
                OverlayFixture.MEASURE, OverlayDialogueSession.LINE_LIMIT_PX, WRAP);
        assertNotNull(viaString);
        assertEquals(OverlayFixture.toRuns(viaString, in), OverlayFixture.toRuns(viaSegments, in));
    }

    @Test
    void aStyleThatStretchesMovesNeverCarriesTheMoveBack() {
        // style 999 is "bold": every character, move characters included, is one pixel wider
        OverlayDialogue.Measure bold = (font, style, text) ->
                OverlayFixture.MEASURE.width(font, style, text) + (style == 999 ? text.codePointCount(0, text.length()) : 0);
        List<OverlayDialogue.Run> in = colouredFrame();
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        OverlayDialogue.Wrapper wrap = (font, style, text, limit) ->
                OverlayLineWrap.wrap(text, limit, cp -> bold.width(font, style, new String(Character.toChars(cp))));
        List<OverlayDialogue.Segment> segments = List.of(
                new OverlayDialogue.Segment("一二三四", 0),
                new OverlayDialogue.Segment("粗體結尾", 999));
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(a, Map.of(), segments, bold,
                OverlayDialogueSession.LINE_LIMIT_PX, wrap);
        assertNotNull(out);
        int before = 0;
        for (int i = 0; i < in.size(); i++) {
            before += bold.width(in.get(i).font(), i, in.get(i).text());
        }
        int after = 0;
        for (OverlayDialogue.OutRun run : out) {
            String font = run.font() != null ? run.font() : in.get(run.styleSource()).font();
            after += bold.width(font, run.styleSource(), run.text());
        }
        assertEquals(before, after);
    }

    // ---------------------------------------------------------------- inline icons

    private static final String ICON_FONT = "minecraft:custom/icons";
    private static final String ICON = "";

    /**
     * Two rows; row 0 has an icon in the middle, a small move before it (in the row font) and
     * one after it (in the icon font). Every element nets to zero, like the real layout.
     */
    private static List<OverlayDialogue.Run> iconFrame() {
        String before = "Go to the ";
        String after = " shop now";
        int row0 = OverlayFixture.MEASURE.width(OverlayFixture.BODY + 0, 0, before) + 2 + 10 + 2
                + OverlayFixture.MEASURE.width(OverlayFixture.BODY + 0, 0, after);
        String second = "Then come back to me.";
        int row1 = OverlayFixture.MEASURE.width(OverlayFixture.BODY + 1, 0, second);
        List<OverlayDialogue.Run> r = new ArrayList<>();
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(-92)));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, before));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(2)));
        r.add(OverlayFixture.run(ICON_FONT, ICON));
        r.add(OverlayFixture.run(ICON_FONT, OverlayFixture.move(2)));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, after));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 1, OverlayFixture.move(-row0)));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 1, second));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(-row1 + 92)));
        return r;
    }

    private static OverlayDialogue.Wrapper iconWrapper() {
        return new OverlayDialogue.Wrapper() {
            @Override
            public List<String> wrap(String font, int style, String text, int limit) {
                return wrap(font, style, text, limit, Map.of());
            }

            @Override
            public List<String> wrap(String font, int style, String text, int limit,
                                     Map<Integer, Integer> fixed) {
                return OverlayLineWrap.wrap(text, limit, cp -> fixed.containsKey(cp) ? fixed.get(cp)
                        : OverlayFixture.MEASURE.width(font, style, new String(Character.toChars(cp))));
            }
        };
    }

    private static List<OverlayDialogue.OutRun> rewriteIcons(List<OverlayDialogue.Run> in, String translated) {
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        return OverlayDialogue.rewrite(a, Map.of(), List.of(new OverlayDialogue.Segment(translated, -1)),
                OverlayFixture.MEASURE, OverlayDialogueSession.LINE_LIMIT_PX, iconWrapper());
    }

    @Test
    void anIconInTheMiddleOfARowIsPartOfTheSpokenText() {
        List<OverlayDialogue.Run> in = iconFrame();
        OverlayDialogue.Analysis a = OverlayDialogue.analyze(in);
        assertEquals("Go to the " + ICON + " shop now Then come back to me.",
                a.sources().get("body"));
        List<OverlayDialogue.BodyPiece> pieces = a.bodyPieces();
        OverlayDialogue.BodyPiece icon = pieces.stream().filter(OverlayDialogue.BodyPiece::icon).findFirst().orElseThrow();
        assertEquals(ICON, icon.text());
        assertEquals(runIndex(in, ICON), icon.styleSource());
    }

    @Test
    void theIconStaysWhereItStoodWithItsFontAndItsMoves() {
        List<OverlayDialogue.Run> in = iconFrame();
        List<OverlayDialogue.OutRun> out = rewriteIcons(in, "請到" + ICON + "商店，然後回來找我。");
        assertNotNull(out);
        List<OverlayDialogue.Run> runs = OverlayFixture.toRuns(out, in);
        assertEquals(OverlayFixture.total(in), OverlayFixture.total(runs));
        assertEquals(0, OverlayFixture.total(runs));
        // the icon is drawn once, still in its own font
        long icons = runs.stream().filter(r -> r.font().equals(ICON_FONT) && r.text().contains(ICON)).count();
        assertEquals(1, icons);
        // two CJK characters (18 px) after the row start, then the move before the icon (2 px)
        assertEquals(-92 + 18 + 2, startOf(runs, ICON_FONT));
    }

    @Test
    void anIconIsNeverSplitFromItsMovesAndMovesToTheNextRowAsOne() {
        List<OverlayDialogue.Run> in = iconFrame();
        // 21 characters = 189 px; the icon with its moves (14 px) does not fit the same row
        String twentyOne = "一二三四五六七八九十一二三四五六七八九十一";
        List<OverlayDialogue.OutRun> out = rewriteIcons(in, twentyOne + ICON + "後面的文字");
        assertNotNull(out);
        List<OverlayDialogue.Run> runs = OverlayFixture.toRuns(out, in);
        assertEquals(0, OverlayFixture.total(runs));
        assertEquals(twentyOne, inkOf(runs, OverlayFixture.BODY + 0).replace(ICON, ""));
        // the icon starts the second row, at the row start plus its leading move
        assertEquals(-92 + 2, startOf(runs, ICON_FONT));
        // its leading and trailing moves are still the original runs next to it
        int at = -1;
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).font().equals(ICON_FONT) && runs.get(i).text().equals(ICON)) at = i;
        }
        assertEquals(OverlayFixture.move(2), runs.get(at - 1).text());
        assertEquals(OverlayFixture.move(2), runs.get(at + 1).text());
    }

    @Test
    void aTranslationThatLostTheIconLeavesThePageOriginal() {
        assertNull(rewriteIcons(iconFrame(), "請到商店，然後回來找我。"));
    }

    @Test
    void anIconBetweenTwoRowsOrWithLettersMeansTheSpokenTextIsNotTouched() {
        List<OverlayDialogue.Run> atRowEnd = new ArrayList<>();
        atRowEnd.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(-92)));
        atRowEnd.add(OverlayFixture.run(OverlayFixture.BODY + 0, "First row"));
        atRowEnd.add(OverlayFixture.run(ICON_FONT, ICON));
        atRowEnd.add(OverlayFixture.run(OverlayFixture.BODY + 1, OverlayFixture.move(-60)));
        atRowEnd.add(OverlayFixture.run(OverlayFixture.BODY + 1, "Second row"));
        assertFalse(OverlayDialogue.analyze(atRowEnd).sources().containsKey("body"));

        List<OverlayDialogue.Run> withLetters = new ArrayList<>();
        withLetters.add(OverlayFixture.run(OverlayFixture.BODY + 0, "First "));
        withLetters.add(OverlayFixture.run(ICON_FONT, "Ab"));
        withLetters.add(OverlayFixture.run(OverlayFixture.BODY + 0, " row"));
        assertFalse(OverlayDialogue.analyze(withLetters).sources().containsKey("body"));

        List<OverlayDialogue.Run> unknownMove = new ArrayList<>();
        unknownMove.add(OverlayFixture.run(OverlayFixture.BODY + 0, "First "));
        unknownMove.add(OverlayFixture.run(ICON_FONT, ICON + new String(Character.toChars(0xC5000))));
        unknownMove.add(OverlayFixture.run(OverlayFixture.BODY + 0, " row"));
        assertFalse(OverlayDialogue.analyze(unknownMove).sources().containsKey("body"));
    }

    @Test
    void aMoveOnlyGapBetweenWordsOfTheSameRowIsStillJustSpacing() {
        List<OverlayDialogue.Run> in = new ArrayList<>();
        in.add(OverlayFixture.run(OverlayFixture.BODY + 0, "Hello"));
        in.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(3)));
        in.add(OverlayFixture.run(OverlayFixture.BODY + 0, "world"));
        assertEquals("Helloworld", OverlayDialogue.analyze(in).sources().get("body"));
    }
}
