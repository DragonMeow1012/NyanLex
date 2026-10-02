package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.translate.TextFilter;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-02 follow-up to colour-format-only sharing: (1) a rainbow whole-line must be
 * redistributed over the translation on the marker-less / style-fallback (tooltip) path
 * too; (2) only a per-CHARACTER gradient (colour switching inside words) counts as a
 * gradient - a line coloured one short WORD at a time must not.
 */
class GradientDetectionTest {

    private static Style flat(int rgb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
    }

    private static Component perChar(String text, int first, int step) {
        MutableComponent out = Component.empty();
        int color = first;
        for (int i = 0; i < text.length(); i++) {
            out.append(Component.literal(String.valueOf(text.charAt(i))).setStyle(flat(color)));
            color = (color + step) & 0xFFFFFF;
        }
        return out;
    }

    /** Each word its own colour; the spaces take the colour of the word before them. */
    private static Component perWord(String... wordsAndColours) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < wordsAndColours.length; i += 2) {
            String word = wordsAndColours[i] + (i + 2 < wordsAndColours.length ? " " : "");
            out.append(Component.literal(word)
                    .setStyle(flat(Integer.parseInt(wordsAndColours[i + 1], 16))));
        }
        return out;
    }

    private static Set<Integer> colours(Component c) {
        Set<Integer> out = new HashSet<>();
        for (FabricTextStyle.Seg seg : FabricTextStyle.segments(c)) {
            if (seg.style().getColor() != null) out.add(seg.style().getColor().getValue());
        }
        return out;
    }

    private static void add(MutableComponent c, String t, int rgb) {
        c.append(Component.literal(t).setStyle(flat(rgb)));
    }

    // ---- problem 2: word-level colour is not a gradient ----

    @Test
    void shortWordsEachOwnColourAreNotAGradient() {
        Component source = perWord("Go", "FF5555", "on", "55FF55", "up", "5555FF",
                "to", "FFFF55", "it", "FF55FF", "now", "55FFFF");
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertNull(marked.gradients(), "word-level colouring must not be flagged gradient");
        assertEquals(6, marked.styles().size(), marked.text());
    }

    @Test
    void shortWordsWithUncolouredSpacesAreNotAGradient() {
        MutableComponent source = Component.empty();
        String[] words = {"Go", "on", "up", "to", "it", "now"};
        int[] cols = {0xFF5555, 0x55FF55, 0x5555FF, 0xFFFF55, 0xFF55FF, 0x55FFFF};
        for (int i = 0; i < words.length; i++) {
            if (i > 0) add(source, " ", 0xAAAAAA);
            add(source, words[i], cols[i]);
        }
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertNull(marked.gradients(), marked.text());
    }

    @Test
    void tooltipPathOfWordColouredLineKeepsOnlyOriginalColoursNoProportionalSplit() {
        Component source = perWord("Go", "FF5555", "on", "55FF55", "up", "5555FF",
                "to", "FFFF55", "it", "FF55FF", "now", "55FFFF");
        Component rendered = FabricTextStyle.styledAnchored(source, 0, "繼續向上吧現在");
        Set<Integer> orig = Set.of(0xFF5555, 0x55FF55, 0x5555FF, 0xFFFF55, 0xFF55FF, 0x55FFFF);
        assertTrue(orig.containsAll(colours(rendered)), colours(rendered).toString());
        assertTrue(FabricTextStyle.segments(rendered).size() < "繼續向上吧現在".length());
    }

    @Test
    void realRainbowNamesStillCountAsGradients() {
        for (String text : new String[]{"MYTHIC DUNGEON BOW", "LEGENDARY SWORD", "Hello World Today"}) {
            FabricTextStyle.MarkedChat marked =
                    FabricTextStyle.markChatContent(perChar(text, 0xFF0000, 0x001133), 0);
            assertTrue(marked.gradients() != null, text);
            assertEquals(1, marked.styles().size(), text);
        }
    }

    @Test
    void twoLetterStepGradientIsStillAGradient() {
        MutableComponent source = Component.empty();
        String[] chunks = {"MY", "TH", "IC", " ", "DU", "NG", "EO", "N", " ", "BO", "W"};
        int c = 0xFF0000;
        for (String chunk : chunks) {
            add(source, chunk, c);
            c += 0x103311;
        }
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertTrue(marked.gradients() != null, marked.text());
    }

    @Test
    void mvpPlusPlusBadgeStillIsolatedAndGradientNameStillCoalescesFamilyA() {
        MutableComponent badge = Component.empty();
        add(badge, "Seller: ", 0xAAAAAA);
        add(badge, "[MVP", 0x55FFFF);
        add(badge, "++", 0xFFAA00);
        add(badge, "] ", 0x55FFFF);
        add(badge, "Bob", 0x55FFFF);
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(badge, 0);
        assertTrue(marked.text().contains("[MVP++]"), marked.text());
        FabricTextStyle.MarkedChat name =
                FabricTextStyle.markChatContent(perChar("Steve", 0xFF0000, 0x001133), 0);
        assertEquals("⟦CS0⟧Steve⟦/CS0⟧", name.text());
        assertNull(name.gradients());
    }

    // ---- problem 1: tooltip / style-fallback path redistributes the gradient ----

    @Test
    void tooltipPathRedistributesRainbowOntoChineseCharacters() {
        Component source = perChar("MYTHIC DUNGEON BOW", 0xFF0000, 0x001133);
        Component rendered = FabricTextStyle.styledAnchored(source, 0, "神話地城弓");
        assertEquals("神話地城弓", rendered.getString());
        List<FabricTextStyle.Seg> segs = FabricTextStyle.segments(rendered);
        assertEquals(5, segs.size(), "one colour per Chinese character");
        assertEquals(5, colours(rendered).size());
        assertEquals(0xFF0000, segs.get(0).style().getColor().getValue());
        assertEquals((0xFF0000 + 17 * 0x001133) & 0xFFFFFF,
                segs.get(4).style().getColor().getValue());
    }

    @Test
    void tooltipPathLongerTranslationRepeatsNeighbouringColoursButKeepsEndpoints() {
        Component source = perChar("MYTHIC DUNGEON BOW", 0xFF0000, 0x001133);
        String zh = "非常稀有的神話級地城長弓武器裝備";
        Component rendered = FabricTextStyle.styledAnchored(source, 0, zh);
        assertEquals(zh, rendered.getString());
        List<FabricTextStyle.Seg> segs = FabricTextStyle.segments(rendered);
        assertEquals(0xFF0000, segs.get(0).style().getColor().getValue());
        assertEquals((0xFF0000 + 17 * 0x001133) & 0xFFFFFF,
                segs.get(segs.size() - 1).style().getColor().getValue());
        assertTrue(colours(rendered).size() > 5);
    }

    @Test
    void styleFallbackTranslationOfRainbowLineIsRedistributedViaRebuildRich() {
        Component source = perChar("MYTHIC DUNGEON BOW", 0xFF0000, 0x001133);
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        Component rebuilt = FabricTextStyle.rebuildRich(source,
                TextFilter.markStyleFallback("神話地城弓"), marked);
        assertEquals("神話地城弓", rebuilt.getString());
        assertEquals(5, colours(rebuilt).size(), "gradient, not one dominant colour");
    }

    @Test
    void nonGradientMultiColourLineDoesNotGetProportionalColouring() {
        MutableComponent source = Component.empty();
        add(source, "EPIC", 0xFF5555);
        add(source, " DUNGEON GLOVES", 0x5555FF);
        Component rendered = FabricTextStyle.styledAnchored(source, 0, "史詩地城手套");
        assertTrue(Set.of(0xFF5555, 0x5555FF).containsAll(colours(rendered)),
                colours(rendered).toString());
    }

    @Test
    void gradientNeverSplitsSupplementaryCharactersIntoLoneSurrogates() {
        Component source = perChar("MYTHIC DUNGEON BOW", 0xFF0000, 0x001133);
        String zh = "神\uD83D\uDE00話\uD842\uDFB7城弓\uD83D\uDE00";
        Component rendered = FabricTextStyle.styledAnchored(source, 0, zh);
        assertEquals(zh, rendered.getString());
        for (FabricTextStyle.Seg seg : FabricTextStyle.segments(rendered)) {
            String t = seg.text();
            for (int k = 0; k < t.length(); k++) {
                char ch = t.charAt(k);
                if (Character.isHighSurrogate(ch)) {
                    assertTrue(k + 1 < t.length() && Character.isLowSurrogate(t.charAt(k + 1)), t);
                    k++;
                } else {
                    assertTrue(!Character.isLowSurrogate(ch), "lone low surrogate in segment");
                }
            }
        }
    }
}
