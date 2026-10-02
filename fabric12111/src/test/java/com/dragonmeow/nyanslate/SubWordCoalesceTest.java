package com.dragonmeow.nyanslate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Family-A regression: a per-character-colour name (MVP++/rank gradient — each letter its own
 * {@link net.minecraft.network.chat.Style}) must NOT be shredded into one ⟦CS#⟧ marker pair per
 * letter, because markers walled INSIDE a word make the backend unable to recognise the word.
 *
 * <p>The real {@code FabricTextStyle.markChatContent(Component,int)} and its
 * {@code coalesceSubWordRuns/mergeDominant/first/lastCodePoint/isWordChar} helpers operate over
 * {@code net.minecraft} {@code Component}/{@code Style}, which are deliberately NOT on this
 * module's test classpath (see build.gradle: "Minecraft-free unit tests ... without Loom/Minecraft
 * on the classpath"). So this test re-implements the ported algorithm VERBATIM over a colour-keyed
 * {@code Seg} fake (an int colour standing in for {@code Style}, since run distinctness is decided
 * by {@code Style.equals}) and asserts the coalescing collapses per-letter runs so every marker
 * boundary lands at a whitespace/punctuation gap — i.e. the whole word reaches the translator
 * inside a SINGLE marker pair. The {@code coalesce=false} path reproduces the pre-fix shredding to
 * show the assertion actually depends on the fix.</p>
 */
class SubWordCoalesceTest {

    private static final int MAX_MARKED_SEGMENTS = 16;

    /** Fake run: an int colour key stands in for net.minecraft Style (distinctness = key equality). */
    private record Seg(String text, int color) {}

    // ---- helpers copied VERBATIM from the fix (Style -> int color key) ----

    private static int semanticWeight(String text) {
        int weight = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) weight++;
        }
        return weight;
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    private static int firstCodePoint(String s) {
        return (s == null || s.isEmpty()) ? -1 : s.codePointAt(0);
    }

    private static int lastCodePoint(String s) {
        return (s == null || s.isEmpty()) ? -1 : s.codePointBefore(s.length());
    }

    private static List<Seg> mergeSegments(List<Seg> input) {
        List<Seg> out = new ArrayList<>();
        for (Seg seg : input) {
            if (seg == null || seg.text() == null || seg.text().isEmpty()) continue;
            if (!out.isEmpty()) {
                Seg last = out.get(out.size() - 1);
                if (last.color() == seg.color()) {
                    out.set(out.size() - 1, new Seg(last.text() + seg.text(), last.color()));
                    continue;
                }
            }
            out.add(seg);
        }
        return out;
    }

    private static List<Seg> coalesceSubWordRuns(List<Seg> segs) {
        List<Seg> out = new ArrayList<>();
        List<Seg> group = new ArrayList<>();
        for (Seg seg : segs) {
            if (!group.isEmpty()) {
                Seg prev = group.get(group.size() - 1);
                boolean midWord = isWordChar(lastCodePoint(prev.text()))
                        && isWordChar(firstCodePoint(seg.text()));
                if (!midWord) {
                    out.add(mergeDominant(group));
                    group = new ArrayList<>();
                }
            }
            group.add(seg);
        }
        if (!group.isEmpty()) out.add(mergeDominant(group));
        return out;
    }

    private static Seg mergeDominant(List<Seg> group) {
        if (group.size() == 1) return group.get(0);
        StringBuilder text = new StringBuilder();
        Map<Integer, Integer> weightByStyle = new LinkedHashMap<>();
        for (Seg seg : group) {
            text.append(seg.text());
            weightByStyle.merge(seg.color(), Math.max(1, semanticWeight(seg.text())), Integer::sum);
        }
        int dominant = group.get(0).color();
        int best = -1;
        for (Map.Entry<Integer, Integer> e : weightByStyle.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                dominant = e.getKey();
            }
        }
        return new Seg(text.toString(), dominant);
    }

    // ---- markChatContent's marker-wrap loop, coalesce toggled to contrast the fix ----

    private static String mark(List<Seg> raw, boolean coalesce) {
        List<Seg> segs = coalesce
                ? coalesceSubWordRuns(mergeSegments(raw))
                : mergeSegments(raw);
        if (segs.size() <= 1 || segs.size() > MAX_MARKED_SEGMENTS) {
            StringBuilder plain = new StringBuilder();
            for (Seg seg : segs) plain.append(seg.text());
            return plain.toString();
        }
        StringBuilder text = new StringBuilder();
        int idx = 0;
        for (Seg seg : segs) {
            String s = seg.text();
            if (s == null || s.isEmpty()) continue;
            if (s.isBlank()) {
                text.append(s);
                continue;
            }
            text.append("⟦CS").append(idx).append('⟧')
                .append(s)
                .append("⟦/CS").append(idx).append('⟧');
            idx++;
        }
        return text.toString();
    }

    /** Build "hi <word> gg" where every letter of {@code word} carries a distinct colour. */
    private static List<Seg> sentenceWithGradientWord(String word) {
        List<Seg> raw = new ArrayList<>();
        raw.add(new Seg("hi ", 0xAAAAAA));
        int c = 0xFF0000;
        for (int i = 0; i < word.length(); i++) {
            raw.add(new Seg(String.valueOf(word.charAt(i)), c));
            c += 0x001133; // every char a different colour
        }
        raw.add(new Seg(" gg", 0xAAAAAA));
        return raw;
    }

    @Test
    void perLetterGradientNameStaysInsideOneMarkerPair() {
        String marked = mark(sentenceWithGradientWord("Steve"), true);
        // Whole word inside a SINGLE ⟦CS#⟧…⟦/CS#⟧ pair, not shredded per letter.
        assertTrue(marked.contains("⟦CS1⟧Steve⟦/CS1⟧"),
                "gradient name must be one marker region: " + marked);
        assertFalse(marked.contains("⟦CS1⟧S⟦/CS1⟧"),
                "no per-letter markers should remain: " + marked);
        // A marker must never sit between two letters of the word.
        assertFalse(marked.matches(".*[A-Za-z]⟦.*⟧[A-Za-z].*"),
                "no marker may land mid-word: " + marked);
    }

    @Test
    void withoutCoalesceThePreFixPathShredsTheName() {
        String shredded = mark(sentenceWithGradientWord("Steve"), false);
        // Demonstrates the assertion depends on the fix: pre-fix each letter is its own region.
        assertTrue(shredded.contains("⟦CS1⟧S⟦/CS1⟧"),
                "pre-fix path should shred per letter: " + shredded);
        assertFalse(shredded.contains("⟦CS1⟧Steve⟦/CS1⟧"),
                "pre-fix path cannot keep the word whole: " + shredded);
    }

    @Test
    void underscoreNameCoalescesWhole() {
        // Minecraft usernames use '_' as an intra-word char (NameMasker.isNameChar).
        String marked = mark(sentenceWithGradientWord("xX_Player_Xx"), true);
        assertTrue(marked.contains("⟦CS1⟧xX_Player_Xx⟦/CS1⟧"),
                "underscore username must coalesce whole: " + marked);
    }

    @Test
    void wholeWordColouredLinePassesThroughUnchanged() {
        // Two words, each ONE colour (no mid-word colour boundary) -> untouched by coalescing.
        List<Seg> raw = new ArrayList<>();
        raw.add(new Seg("Red", 0xFF0000));
        raw.add(new Seg(" ", 0xAAAAAA));
        raw.add(new Seg("Green", 0x00FF00));
        String marked = mark(raw, true);
        assertTrue(marked.contains("⟦CS0⟧Red⟦/CS0⟧"), marked);
        assertTrue(marked.contains("⟦CS1⟧Green⟦/CS1⟧"), marked);
    }
}
