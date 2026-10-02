package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.translate.RarityLineComposer;
import com.dragonmeow.nyanlex.translate.TermTableDefaults;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-02 colour-format-only sharing: a Hypixel rarity line such as "MYTHIC DUNGEON
 * BOW" sometimes renders as one solid colour and sometimes as a per-character rainbow
 * (the SAME English text at a different animation frame). Before this fix, the rainbow
 * frame produced a DIFFERENT marker shape than the solid frame (one ⟦CS#⟧ pair per
 * colour-distinct run instead of none), so {@code RarityLineComposer} — which refuses to
 * splice across an internal colour-run boundary — fell through to the ordinary AI/cache
 * path and the line was translated by network request instead of the local term table.
 *
 * <p>These tests exercise the REAL {@code net.minecraft} {@code Component}/{@code Style}
 * pipeline ({@code FabricTextStyle.markChatContent}/{@code rebuildRich}), not a
 * reimplementation, and the REAL {@code RarityLineComposer} term-table composition.</p>
 */
class GradientRarityLineTest {

    private static final BiFunction<RarityLineComposer.Kind, String, String> DEFAULTS_ONLY =
            (kind, word) -> kind == RarityLineComposer.Kind.RARITY
                    ? TermTableDefaults.RARITY.get(word)
                    : TermTableDefaults.TYPE.get(word);

    private static Style flat(int rgb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
    }

    private static final String RAINBOW_TEXT = "MYTHIC DUNGEON BOW";
    private static final int RAINBOW_FIRST_COLOR = 0xFF0000;
    private static final int RAINBOW_STEP = 0x001133;

    /** "MYTHIC DUNGEON BOW" with every character its own colour (a true per-letter
     *  rainbow spanning all three words, including the two spaces between them). */
    private static Component rainbowMythicDungeonBow() {
        MutableComponent out = Component.empty();
        int color = RAINBOW_FIRST_COLOR;
        for (int i = 0; i < RAINBOW_TEXT.length(); i++) {
            out.append(Component.literal(String.valueOf(RAINBOW_TEXT.charAt(i))).setStyle(flat(color)));
            color = (color + RAINBOW_STEP) & 0xFFFFFF;
        }
        return out;
    }

    private static Component solidMythicDungeonBow() {
        return Component.literal("MYTHIC DUNGEON BOW").setStyle(flat(0xFFAA00));
    }

    @Test
    void solidRarityLineHasNoMarkersAtAll() {
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(solidMythicDungeonBow(), 0);
        assertTrue(!marked.marked(), "a single solid run must not be wrapped in any marker");
        assertEquals("MYTHIC DUNGEON BOW", marked.text());
    }

    @Test
    void rainbowRarityLineCoalescesIntoOneMarkerAcrossAllThreeWords() {
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(rainbowMythicDungeonBow(), 0);
        assertTrue(marked.marked(), "the rainbow run must still produce a marker (18 raw style runs)");
        assertEquals(1, marked.styles().size(),
                "the whole 3-word rainbow sweep must coalesce into ONE run, matching the solid "
                        + "line's shape — not one run per word/letter: " + marked.text());
        assertEquals("⟦CS0⟧MYTHIC DUNGEON BOW⟦/CS0⟧", marked.text());
    }

    @Test
    void rainbowRarityLineStillMatchesAndComposesLocallyZeroRequests() {
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(rainbowMythicDungeonBow(), 0);

        // Local term-table resolution exactly as RarityLineComposer already supports for a
        // SINGLE colour-wrapped phrase (see RarityLineComposerTest#
        // confusionLettersWrapRarityDungeonType_withCsTags) — no AI/cache request involved.
        RarityLineComposer.Match match = RarityLineComposer.match(marked.text());
        assertNotNull(match, "the coalesced single-marker rainbow phrase must still match: " + marked.text());
        String composed = match.compose(marked.text(), DEFAULTS_ONLY);
        assertEquals("⟦CS0⟧神話地城弓⟦/CS0⟧", composed);

        // The solid line resolves to the exact same Chinese text via the exact same table.
        RarityLineComposer.Match solidMatch = RarityLineComposer.match("MYTHIC DUNGEON BOW");
        assertNotNull(solidMatch);
        assertEquals("神話地城弓", solidMatch.compose("MYTHIC DUNGEON BOW", DEFAULTS_ONLY));
    }

    @Test
    void rainbowRarityLineRedistributesGradientOntoTheComposedChineseText() {
        Component source = rainbowMythicDungeonBow();
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        RarityLineComposer.Match match = RarityLineComposer.match(marked.text());
        assertNotNull(match);
        String composed = match.compose(marked.text(), DEFAULTS_ONLY);

        Component rebuilt = FabricTextStyle.rebuildRich(source, composed, marked);
        assertEquals("神話地城弓", rebuilt.getString());

        List<FabricTextStyle.Seg> segments = FabricTextStyle.segments(rebuilt);
        assertTrue(segments.size() > 1,
                "a redistributed gradient must produce more than one colour run, not a flat "
                        + "dominant colour: " + segments);
        // The first rendered character keeps (approximately) the gradient's first colour and
        // the last character the gradient's last colour — proportional redistribution, not a
        // guessed/reordered mapping.
        assertEquals(RAINBOW_FIRST_COLOR, segments.get(0).style().getColor().getValue(),
                "first translated character should anchor the gradient's first colour");
        assertEquals((RAINBOW_FIRST_COLOR + (RAINBOW_TEXT.length() - 1) * RAINBOW_STEP) & 0xFFFFFF,
                segments.get(segments.size() - 1).style().getColor().getValue(),
                "last translated character should anchor the gradient's last colour");
    }

    @Test
    void perLetterGradientNameWithNoSpacesStillUsesTheExistingSubWordPath() {
        // Pure mid-word gradient (no whitespace at all) must be unaffected by the NEW
        // cross-word pass: it still collapses via the pre-existing coalesceSubWordRuns
        // mechanism into one CS0 run with a single dominant flat colour (Family A, 2026-07
        // gradient-name-shredding fix) — no per-character redistribution here.
        MutableComponent source = Component.empty();
        String name = "Steve";
        int color = 0xFF0000;
        for (int i = 0; i < name.length(); i++) {
            source.append(Component.literal(String.valueOf(name.charAt(i))).setStyle(flat(color)));
            color += 0x001133;
        }
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertTrue(marked.marked());
        assertEquals(1, marked.styles().size());
        assertEquals("⟦CS0⟧Steve⟦/CS0⟧", marked.text());

        Component rebuilt = FabricTextStyle.rebuildRich(source, "⟦CS0⟧史蒂夫⟦/CS0⟧", marked);
        List<FabricTextStyle.Seg> segments = FabricTextStyle.segments(rebuilt);
        assertEquals(1, segments.size(),
                "a pure mid-word gradient keeps today's flat-dominant-colour behaviour");
    }

    @Test
    void perWordColouredRarityLineComposesLocallyAndEachChineseWordKeepsItsOwnColour() {
        MutableComponent source = Component.empty()
                .append(Component.literal("MYTHIC").setStyle(flat(0xFF55FF)))
                .append(Component.literal(" "))
                .append(Component.literal("DUNGEON").setStyle(flat(0x55FFFF)))
                .append(Component.literal(" "))
                .append(Component.literal("BOW").setStyle(flat(0xFFFF55)));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        RarityLineComposer.Match match = RarityLineComposer.matchLenient(marked.text());
        assertNotNull(match, marked.text());
        Component rebuilt = FabricTextStyle.rebuildRich(source,
                match.compose(marked.text(), DEFAULTS_ONLY), marked);
        assertEquals("神話地城弓", rebuilt.getString());
        var segs = FabricTextStyle.segments(rebuilt);
        assertEquals(0xFF55FF, segs.stream().filter(x -> x.text().equals("神話")).findFirst()
                .orElseThrow().style().getColor().getValue());
        assertEquals(0x55FFFF, segs.stream().filter(x -> x.text().equals("地城")).findFirst()
                .orElseThrow().style().getColor().getValue());
        assertEquals(0xFFFF55, segs.stream().filter(x -> x.text().equals("弓")).findFirst()
                .orElseThrow().style().getColor().getValue());
    }

    @Test
    void geniunelyDiscreteTwoColourRarityLineStillFallsThroughToProse() {
        // "EPIC" and " DUNGEON GLOVES" each a SINGLE solid colour (4 and 16 characters):
        // this is NOT a gradient (each run is far longer than the tiny-run threshold), so
        // it must still be rejected exactly like RarityLineComposerTest's existing
        // coreSplitAcrossTwoColourRunsIsRejected — no regression from the new pass.
        MutableComponent source = Component.empty()
                .append(Component.literal("EPIC").setStyle(flat(0xFF5555)))
                .append(Component.literal(" DUNGEON GLOVES").setStyle(flat(0x5555FF)));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertTrue(marked.marked());
        assertEquals(2, marked.styles().size(), "two genuinely discrete colour blocks stay separate");
        assertNull(RarityLineComposer.match(marked.text()),
                "a real two-block colour split must still be rejected, not misread as a gradient");
    }

    // ---- Hypixel rank badge slotting (Seller:/Buyer:/Bidder: ...) ----

    private static Component sellerSplitRank(String rankLetters, String plusses, String name) {
        MutableComponent out = Component.empty();
        out.append(Component.literal("Seller:").setStyle(flat(0xAAAAAA)));
        if (plusses.isEmpty()) {
            out.append(Component.literal(" [" + rankLetters + "]").setStyle(flat(0x55FF55)));
            out.append(Component.literal(" " + name).setStyle(flat(0x55FF55)));
        } else {
            out.append(Component.literal(" [" + rankLetters).setStyle(flat(0x55FFFF)));
            out.append(Component.literal(plusses).setStyle(flat(0xFF5555)));
            out.append(Component.literal("] " + name).setStyle(flat(0x55FFFF)));
        }
        return out;
    }

    @Test
    void sellerLinesWithDifferentRanksAndColourRunCountsShareOneMaskedKey() {
        String keyVip = com.dragonmeow.nyanlex.translate.NameMasker.mask(
                FabricTextStyle.markChatContent(sellerSplitRank("VIP", "", "Alice_1"), 0).text(),
                List.of(), com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher.EMPTY).text();
        String keyMvpPlus = com.dragonmeow.nyanlex.translate.NameMasker.mask(
                FabricTextStyle.markChatContent(sellerSplitRank("MVP", "+", "Bob_22"), 0).text(),
                List.of(), com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher.EMPTY).text();
        String keyMvpPlusPlus = com.dragonmeow.nyanlex.translate.NameMasker.mask(
                FabricTextStyle.markChatContent(sellerSplitRank("MVP", "++", "Cat_333"), 0).text(),
                List.of(), com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher.EMPTY).text();
        assertEquals(keyVip, keyMvpPlus);
        assertEquals(keyVip, keyMvpPlusPlus);
        assertTrue(!keyVip.contains("MVP") && !keyVip.contains("VIP"), keyVip);
    }

    @Test
    void rankBadgeKeepsItsOwnPerLetterColoursWhenRestoredVerbatim() {
        Component source = sellerSplitRank("MVP", "+", "Bob_22");
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        var masked = com.dragonmeow.nyanlex.translate.NameMasker.mask(marked.text(), List.of(),
                com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher.EMPTY);
        String translated = com.dragonmeow.nyanlex.translate.NameMasker.unmask(
                masked.text().replace("Seller:", "賣家："), masked.names());
        Component rebuilt = FabricTextStyle.rebuildRich(source, translated, marked);
        assertEquals("賣家： [MVP+] Bob_22", rebuilt.getString());
        var plus = FabricTextStyle.segments(rebuilt).stream()
                .filter(sg -> sg.text().equals("+")).findFirst().orElseThrow();
        assertEquals(0xFF5555, plus.style().getColor().getValue(), "the '+' keeps its own colour");
        var bracket = FabricTextStyle.segments(rebuilt).stream()
                .filter(sg -> sg.text().equals("[")).findFirst().orElseThrow();
        assertEquals(0x55FFFF, bracket.style().getColor().getValue());
    }

    @Test
    void textMergedFromColourRunsOfNonBadgeBracketsIsNotTouched() {
        // "[Bazaar]" is not an all-caps rank-shaped tag: left exactly as before.
        MutableComponent source = Component.empty()
                .append(Component.literal(" [Bazaar]").setStyle(flat(0xFFAA00)))
                .append(Component.literal(" sold").setStyle(flat(0xAAAAAA)));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertEquals(2, marked.styles().size());
    }
}
