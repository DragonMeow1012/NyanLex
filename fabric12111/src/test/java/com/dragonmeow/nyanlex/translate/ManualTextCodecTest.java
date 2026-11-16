package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.translate.ManualTextCodec.Diff;
import com.dragonmeow.nyanlex.translate.ManualTextCodec.Kind;
import com.dragonmeow.nyanlex.translate.ManualTextCodec.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The marker aliases, the marker comparison and the marker-skipping search of the translation manager. */
class ManualTextCodecTest {

    private static final String MT0 = "⟦MT0⟧";
    private static final String MT1 = "⟦MT1⟧";
    private static final String NAME0 = "⟦0⟧";
    private static final String WS0 = "⟦WS0⟧";
    private static final String PB0 = "⟦PB0⟧";
    private static final String PB1 = "⟦PB1⟧";
    private static final String CS0 = "⟦CS0⟧";
    private static final String CS0_END = "⟦/CS0⟧";
    private static final String CS1 = "⟦CS1⟧";
    private static final String CS1_END = "⟦/CS1⟧";

    @Test
    void everyKindOfMarkerHasItsAliasAndComesBack() {
        String raw = "a " + MT0 + " b " + NAME0 + " c " + WS0 + " d " + PB0 + " e " + CS0 + "f" + CS0_END;
        String display = "a {V0} b {N0} c {S0} d {B0} e {C0}f{/C0}";
        assertEquals(display, ManualTextCodec.toDisplay(raw));
        assertEquals(raw, ManualTextCodec.fromDisplay(display));
        assertEquals("{V12}", ManualTextCodec.toDisplay("⟦MT12⟧"));
        assertEquals("⟦/CS3⟧", ManualTextCodec.fromDisplay("{/C3}"));
        assertEquals("", ManualTextCodec.toDisplay(null));
        assertEquals("", ManualTextCodec.fromDisplay(null));
        assertEquals("plain text", ManualTextCodec.toDisplay("plain text"));
    }

    @Test
    void tagsHaveBothFormsAndKnowTheirKind() {
        Tag value = new Tag(Kind.VALUE, 4);
        assertEquals("⟦MT4⟧", value.raw());
        assertEquals("{V4}", value.display());
        assertEquals("⟦4⟧", new Tag(Kind.NAME, 4).raw());
        assertEquals("{N4}", new Tag(Kind.NAME, 4).display());
        assertEquals("{/C2}", new Tag(Kind.COLOR_CLOSE, 2).display());
        assertEquals("⟦/CS2⟧", new Tag(Kind.COLOR_CLOSE, 2).raw());
        assertTrue(Kind.COLOR_OPEN.isColor() && Kind.COLOR_CLOSE.isColor());
        assertFalse(Kind.VALUE.isColor());
    }

    @Test
    void onlyTheExactMarkerShapesAreMarkers() {
        // blanks, lower case, a leading zero, too many digits, an unknown name, an unclosed bracket
        String[] notMarkers = {"⟦ MT0⟧", "⟦mt0⟧", "⟦MT00⟧", "⟦MT1234567890⟧",
                "⟦XX0⟧", "⟦MT0", "⟦⟧", "⟦007⟧", "⟦CS⟧", "⟦/MT0⟧"};
        for (String s : notMarkers) {
            assertEquals(s, ManualTextCodec.toDisplay(s), s);
            assertEquals(List.of(), ManualTextCodec.tags(s), s);
        }
        // the same on the typed side
        String[] notAliases = {"{v0}", "{V}", "{V01}", "{ V0}", "{V0", "{X0}", "{/V0}", "{/C}", "{V1234567890}"};
        for (String s : notAliases) {
            assertEquals(s, ManualTextCodec.fromDisplay(s), s);
            assertEquals(List.of(), ManualTextCodec.displayTags(s), s);
        }
        assertEquals(List.of(new Tag(Kind.NAME, 0)), ManualTextCodec.tags("⟦0⟧"));
        assertEquals(List.of(new Tag(Kind.VALUE, 123456789)), ManualTextCodec.tags("⟦MT123456789⟧"));
    }

    @Test
    void literalBracesThatLookLikeAliasesAreDoubledAndComeBack() {
        String[] tricky = {"{V0}", "{{V0}", "{", "{{", "{}", "{x}", "{" + MT0, "{{" + MT0, MT0 + "{V0}", "{V0}" + MT0,
                "{/C0}", "a{b}c", MT0 + "{", "{" + CS0 + "x" + CS0_END + "}", "{V", "V0}"};
        for (String raw : tricky) {
            String display = ManualTextCodec.toDisplay(raw);
            assertEquals(raw, ManualTextCodec.fromDisplay(display), raw + " -> " + display);
            assertEquals(ManualTextCodec.tags(raw), ManualTextCodec.displayTags(display),
                    "the aliases of " + display + " are exactly the markers of " + raw);
        }
        assertEquals("{{V0}", ManualTextCodec.toDisplay("{V0}"), "a literal {V0} is shown doubled");
        assertEquals("{V0}", ManualTextCodec.fromDisplay("{{V0}"));
        assertEquals("a{b}c", ManualTextCodec.toDisplay("a{b}c"), "an ordinary brace is left alone");
        assertEquals(List.of(new Tag(Kind.VALUE, 0)), ManualTextCodec.displayTags("{{{V0}"), "a doubled brace then a real alias");
    }

    @Test
    void displayAndRawAreInverseForAnyStringAndKeepTheMarkersExactly() {
        String[] atoms = {"{", "}", "{{", "V", "N", "S", "B", "C", "/", "0", "1", "12", "01", "x", " ", "\n", "你",
                MT0, MT1, NAME0, WS0, PB0, PB1, CS0, CS0_END, CS1, CS1_END, "⟦", "⟧", "⟦MT", "MT0⟧",
                "{V0}", "{N1}", "{/C0}", "{C2}", "{B0}", "{S0}"};
        Random random = new Random(20261116);
        for (int run = 0; run < 30000; run++) {
            StringBuilder sb = new StringBuilder();
            int n = random.nextInt(13);
            for (int i = 0; i < n; i++) sb.append(atoms[random.nextInt(atoms.length)]);
            String raw = sb.toString();
            String display = ManualTextCodec.toDisplay(raw);
            assertEquals(raw, ManualTextCodec.fromDisplay(display), "round trip of [" + raw + "] shown as [" + display + "]");
            assertEquals(ManualTextCodec.tags(raw), ManualTextCodec.displayTags(display), "markers of [" + raw + "]");
        }
    }

    @Test
    void tagsAreListedInReadingOrderAndDistinctOnesByFirstAppearance() {
        String raw = MT1 + " a " + MT0 + " " + MT1 + " " + NAME0 + CS0 + "x" + CS0_END + PB0;
        assertEquals(List.of(new Tag(Kind.VALUE, 1), new Tag(Kind.VALUE, 0), new Tag(Kind.VALUE, 1), new Tag(Kind.NAME, 0),
                new Tag(Kind.COLOR_OPEN, 0), new Tag(Kind.COLOR_CLOSE, 0), new Tag(Kind.BREAK, 0)), ManualTextCodec.tags(raw));
        assertEquals(List.of(new Tag(Kind.VALUE, 1), new Tag(Kind.VALUE, 0), new Tag(Kind.NAME, 0),
                new Tag(Kind.COLOR_OPEN, 0), new Tag(Kind.COLOR_CLOSE, 0), new Tag(Kind.BREAK, 0)),
                ManualTextCodec.distinctTags(raw), "the buttons for inserting a marker");
        assertEquals(List.of(), ManualTextCodec.tags(null));
        assertEquals(List.of(), ManualTextCodec.distinctTags("no markers"));
        assertEquals(List.of("{V1}", "{V0}", "{V1}"), displays(ManualTextCodec.tags(MT1 + MT0 + MT1)));
        // offsets
        List<ManualTextCodec.Span> spans = ManualTextCodec.rawSpans("ab" + MT0 + "c");
        assertEquals(1, spans.size());
        assertEquals(2, spans.get(0).start());
        assertEquals(2 + MT0.length(), spans.get(0).end());
        assertEquals(1, ManualTextCodec.displaySpans("ab{V0}c").size());
        assertEquals(2, ManualTextCodec.displaySpans("ab{V0}c").get(0).start());
        assertEquals(6, ManualTextCodec.displaySpans("ab{V0}c").get(0).end());
    }

    private static List<String> displays(List<Tag> tags) {
        List<String> out = new ArrayList<>();
        for (Tag t : tags) out.add(t.display());
        return out;
    }

    @Test
    void compareListsWhatIsMissingAndWhatIsExtraAsMultisets() {
        String source = "You got " + MT0 + " coins from " + NAME0 + ", " + MT0;
        Diff same = ManualTextCodec.compare(source, NAME0 + " " + MT0 + " " + MT0);
        assertTrue(same.isEmpty(), "order and wording do not matter here");

        Diff lost = ManualTextCodec.compare(source, "x " + MT0 + " y");
        assertEquals(List.of("{V0}", "{N0}"), lost.missingDisplay());
        assertEquals("{V0} {N0}", lost.missingText(), "listed by kind: values first, then names");
        assertEquals(1, lost.missing().stream().filter(e -> e.tag().kind() == Kind.VALUE).findFirst().orElseThrow().count());
        assertTrue(lost.extra().isEmpty());

        Diff doubled = ManualTextCodec.compare(MT0, MT0 + MT0 + MT0);
        assertTrue(doubled.missing().isEmpty());
        assertEquals("{V0}×2", doubled.extraText(), "two extra copies of the same tag");

        Diff other = ManualTextCodec.compare(MT0, MT1);
        assertEquals("{V0}", other.missingText());
        assertEquals("{V1}", other.extraText());

        Diff none = ManualTextCodec.compare("plain", "純文字");
        assertTrue(none.isEmpty());
        assertTrue(ManualTextCodec.compare(null, null).isEmpty());
        assertEquals("{V0}", ManualTextCodec.compare(MT0, "").missingText());
    }

    @Test
    void colourTagsMustFormCleanPairs() {
        assertEquals(List.of(), ManualTextCodec.unpairedColorTags(CS0 + "a" + CS0_END + " " + CS1 + "b" + CS1_END));
        assertEquals(List.of(), ManualTextCodec.unpairedColorTags("no colours " + MT0));
        assertEquals(List.of(new Tag(Kind.COLOR_OPEN, 0)), ManualTextCodec.unpairedColorTags(CS0 + "a"), "never closed");
        assertEquals(List.of(new Tag(Kind.COLOR_CLOSE, 0)), ManualTextCodec.unpairedColorTags("a" + CS0_END), "never opened");
        assertEquals(List.of(new Tag(Kind.COLOR_CLOSE, 0), new Tag(Kind.COLOR_OPEN, 0)),
                ManualTextCodec.unpairedColorTags(CS0_END + "a" + CS0), "closed before it opens");
        assertEquals(List.of(new Tag(Kind.COLOR_OPEN, 0)),
                ManualTextCodec.unpairedColorTags(CS0 + CS1 + "a" + CS1_END), "a run opened inside another one: the outer one is lost");
        assertEquals(List.of(new Tag(Kind.COLOR_OPEN, 0), new Tag(Kind.COLOR_CLOSE, 1)),
                ManualTextCodec.unpairedColorTags(CS0 + "a" + CS1_END), "a closer of another index");
        assertEquals(List.of(), ManualTextCodec.unpairedColorTags(CS0 + CS0_END + CS0 + CS0_END), "the same index twice in a row is fine");
    }

    @Test
    void paragraphBreaksKeepTheirOrderAndValuesStayInTheirRow() {
        String source = MT0 + " one" + PB0 + MT1 + " two" + PB1 + "three";
        assertTrue(ManualTextCodec.breakOrderMatches(source, "一 " + MT0 + PB0 + "二 " + MT1 + PB1 + "三"));
        assertFalse(ManualTextCodec.breakOrderMatches(source, "一 " + MT0 + PB1 + "二 " + MT1 + PB0 + "三"),
                "the breaks swapped");
        assertFalse(ManualTextCodec.breakOrderMatches(source, "一 " + MT1 + PB0 + "二 " + MT0 + PB1 + "三"),
                "a value moved into another row");
        assertTrue(ManualTextCodec.breakOrderMatches(MT0 + " " + MT1, MT1 + " " + MT0), "no break: values may swap places");
        assertTrue(ManualTextCodec.breakOrderMatches("plain", "純"));
    }

    @Test
    void colourTagsCanBeStrippedToTheirPlainSentence() {
        String raw = CS0 + "Sharp" + CS0_END + "ness " + MT0 + CS1 + "!" + CS1_END;
        assertTrue(ManualTextCodec.hasColorTags(raw));
        assertEquals("Sharpness " + MT0 + "!", ManualTextCodec.stripColorTags(raw));
        assertFalse(ManualTextCodec.hasColorTags("Sharpness " + MT0));
        assertEquals("Sharpness " + MT0, ManualTextCodec.stripColorTags("Sharpness " + MT0));
        assertEquals("", ManualTextCodec.stripColorTags(null));
        assertEquals(2, ManualTextCodec.newlineCount("a\nb\nc"));
        assertEquals(0, ManualTextCodec.newlineCount(null));
    }

    @Test
    void searchIgnoresCaseAndNeverLooksInsideAMarker() {
        String raw = "You got " + MT0 + " coins" + CS1 + "!" + CS1_END;
        assertTrue(ManualTextCodec.contains(raw, "GOT"));
        assertTrue(ManualTextCodec.contains(raw, "coins"));
        assertFalse(ManualTextCodec.contains(raw, "s!"), "a match is a plain run: the tag between the two characters breaks it");
        assertFalse(ManualTextCodec.contains(raw, "mt"), "the name of the marker is not text");
        assertFalse(ManualTextCodec.contains(raw, "cs"));
        assertFalse(ManualTextCodec.contains(raw, "0"), "nor is its index");
        assertFalse(ManualTextCodec.contains(raw, "ws"));
        assertTrue(ManualTextCodec.contains(raw, ""), "an empty word is everywhere");
        assertFalse(ManualTextCodec.contains(null, "a"));
        assertFalse(ManualTextCodec.contains("a", null));
        assertFalse(ManualTextCodec.contains("ab", "abc"));
        // an unclosed bracket is ordinary text
        assertTrue(ManualTextCodec.contains("a ⟦mt b", "mt"));
        // the player may look for a marker explicitly; it is then plain text
        assertTrue(ManualTextCodec.contains(raw, MT0));
        assertTrue(ManualTextCodec.contains(raw, "⟦mt0⟧"), "still ignoring case");
        assertFalse(ManualTextCodec.contains(raw, MT1));
        assertTrue(ManualTextCodec.contains("你獲得 Sharpness", "SHARP"));
    }

    @Test
    void everyWordMustBeInTheOriginalOrTheTranslationAndAliasesFindTheirMarkers() {
        String original = "You got " + MT0 + " coins";
        String translation = "你獲得 " + MT0 + " 枚金幣";
        String[] terms = ManualTextCodec.searchTerms("  got   金幣 ");
        assertEquals(2, terms.length);
        assertTrue(ManualTextCodec.matchesAll(original, translation, terms), "one word per side is enough");
        assertFalse(ManualTextCodec.matchesAll(original, translation, ManualTextCodec.searchTerms("got silver")));
        assertTrue(ManualTextCodec.matchesAll(original, translation, new String[0]), "no words: everything");
        assertEquals(0, ManualTextCodec.searchTerms("   ").length);
        assertEquals(0, ManualTextCodec.searchTerms(null).length);
        // {V0} as typed in the list is looked for as the real marker
        assertTrue(ManualTextCodec.matchesAll(original, translation, ManualTextCodec.searchTerms("{V0}")));
        assertFalse(ManualTextCodec.matchesAll("no value here", "沒有", ManualTextCodec.searchTerms("{V0}")));
        assertFalse(ManualTextCodec.matchesAll(original, translation, ManualTextCodec.searchTerms("{N0}")));
        assertEquals(MT0, ManualTextCodec.searchTerms("{V0}")[0]);
    }
}
