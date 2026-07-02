package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.style.ColorProfile;
import com.dragonmeow.mctranslator.style.StyleMapper;
import com.dragonmeow.mctranslator.style.StyledRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleMapperTest {

    private static final int RED = 0xFF0000;
    private static final int GREEN = 0x00FF00;
    private static final int BLUE = 0x0000FF;

    @Test
    void noStyleProducesSingleUncolouredRun() {
        List<StyledRun> runs = StyleMapper.toRuns("你好世界", ColorProfile.empty());
        assertEquals(1, runs.size());
        StyledRun run = runs.get(0);
        assertEquals("你好世界", run.text());
        assertFalse(run.hasColor());
        assertFalse(run.bold());
    }

    @Test
    void emptyTranslationProducesNoRuns() {
        ColorProfile p = new ColorProfile(new int[]{RED}, false, false, false, false, false);
        assertTrue(StyleMapper.toRuns("", p).isEmpty());
        assertTrue(StyleMapper.toRuns(null, p).isEmpty());
    }

    @Test
    void solidColourAppliesToWholeTranslation() {
        // Original "Hi" both red -> translation "你好嗎" all red, merged into one run.
        ColorProfile p = new ColorProfile(new int[]{RED, RED}, false, false, false, false, false);
        List<StyledRun> runs = StyleMapper.toRuns("你好嗎", p);
        assertEquals(1, runs.size());
        assertEquals("你好嗎", runs.get(0).text());
        assertEquals(RED, runs.get(0).color());
    }

    @Test
    void rainbowIsRememberedAndSweptAcrossTranslation() {
        // Original 4 chars: red, red, green, green. Translation 2 chars ("你好").
        // colorAt(0,2) -> colors[0]=red ; colorAt(1,2) -> colors[2]=green
        ColorProfile p = new ColorProfile(new int[]{RED, RED, GREEN, GREEN}, false, false, false, false, false);
        List<StyledRun> runs = StyleMapper.toRuns("你好", p);
        assertEquals(2, runs.size());
        assertEquals("你", runs.get(0).text());
        assertEquals(RED, runs.get(0).color());
        assertEquals("好", runs.get(1).text());
        assertEquals(GREEN, runs.get(1).color());
    }

    @Test
    void gradientStretchesAcrossLongerTranslation() {
        // 3-colour original spread across a 6-char translation -> 3 runs of 2 chars.
        ColorProfile p = new ColorProfile(new int[]{RED, GREEN, BLUE}, false, false, false, false, false);
        List<StyledRun> runs = StyleMapper.toRuns("一二三四五六", p);
        assertEquals(3, runs.size());
        assertEquals("一二", runs.get(0).text());
        assertEquals(RED, runs.get(0).color());
        assertEquals("三四", runs.get(1).text());
        assertEquals(GREEN, runs.get(1).color());
        assertEquals("五六", runs.get(2).text());
        assertEquals(BLUE, runs.get(2).color());
    }

    @Test
    void doesNotSplitSurrogatePairs() {
        // "A" + U+20000 (a CJK Extension-B supplementary char = surrogate pair).
        String translated = "A𠀀"; // 3 UTF-16 units, 2 code points
        // 3 distinct colours so the proportional mapping wants a run boundary that
        // would otherwise land between the high and low surrogate.
        ColorProfile p = new ColorProfile(new int[]{RED, GREEN, BLUE}, false, false, false, false, false);

        List<StyledRun> runs = StyleMapper.toRuns(translated, p);

        // No run may contain a lone (unpaired) surrogate.
        for (StyledRun run : runs) {
            String t = run.text();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < t.length() && Character.isLowSurrogate(t.charAt(i + 1)),
                            "high surrogate must be followed by its low surrogate in the same run");
                }
                if (Character.isLowSurrogate(c)) {
                    assertTrue(i > 0 && Character.isHighSurrogate(t.charAt(i - 1)),
                            "low surrogate must be preceded by its high surrogate in the same run");
                }
            }
        }
        // The supplementary char stays whole as a single code point.
        StyledRun last = runs.get(runs.size() - 1);
        assertTrue(last.text().endsWith("𠀀"));
        assertEquals(1, "𠀀".codePointCount(0, 2));
    }

    @Test
    void formattingFlagsPropagateToRuns() {
        ColorProfile p = new ColorProfile(new int[]{RED, RED}, true, true, false, false, false);
        List<StyledRun> runs = StyleMapper.toRuns("粗體", p);
        assertEquals(1, runs.size());
        assertTrue(runs.get(0).bold());
        assertTrue(runs.get(0).italic());
        assertFalse(runs.get(0).underline());
    }

    @Test
    void boldOnlyOriginalStillStylesUncolouredTranslation() {
        // No colours, but bold across the whole original -> bold (uncoloured) run.
        ColorProfile p = new ColorProfile(new int[]{ColorProfile.NO_COLOR, ColorProfile.NO_COLOR},
                true, false, false, false, false);
        List<StyledRun> runs = StyleMapper.toRuns("你好", p);
        assertEquals(1, runs.size());
        assertTrue(runs.get(0).bold());
        assertFalse(runs.get(0).hasColor());
    }
}
