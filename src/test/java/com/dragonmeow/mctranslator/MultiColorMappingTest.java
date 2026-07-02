package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.style.ColorProfile;
import com.dragonmeow.mctranslator.style.StyleMapper;
import com.dragonmeow.mctranslator.style.StyledRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-segment colour preservation for multi-coloured originals (request: keep fixed colours). */
class MultiColorMappingTest {

    private static final int RED = 0xFF5555;
    private static final int GREEN = 0x55FF55;
    private static final int N = ColorProfile.NO_COLOR;

    @Test
    void distinctColorCountCountsExplicitColoursOnly() {
        assertEquals(0, new ColorProfile(new int[]{N, N}, false, false, false, false, false).distinctColorCount());
        assertEquals(1, new ColorProfile(new int[]{RED, RED, N}, false, false, false, false, false).distinctColorCount());
        assertEquals(2, new ColorProfile(new int[]{RED, GREEN, N}, false, false, false, false, false).distinctColorCount());
    }

    @Test
    void twoColourOriginalMapsToTwoColouredRunsOnTheTranslation() {
        // Original: first half RED, second half GREEN. Translation: 4 chars.
        ColorProfile profile = new ColorProfile(new int[]{RED, RED, GREEN, GREEN},
                false, false, false, false, false);
        List<StyledRun> runs = StyleMapper.toRuns("甲乙丙丁", profile);

        // Both fixed colours survive, in order, and cover the whole translation.
        assertTrue(runs.size() >= 2, "multi-colour original should yield multiple runs");
        assertEquals(RED, runs.get(0).color());
        assertEquals(GREEN, runs.get(runs.size() - 1).color());
        StringBuilder rebuilt = new StringBuilder();
        for (StyledRun r : runs) rebuilt.append(r.text());
        assertEquals("甲乙丙丁", rebuilt.toString(), "runs must reconstruct the full translation");
    }

    @Test
    void singleColourStaysOneRun() {
        ColorProfile profile = new ColorProfile(new int[]{RED, RED, RED},
                false, false, false, false, false);
        List<StyledRun> runs = StyleMapper.toRuns("鑽石劍", profile);
        assertEquals(1, runs.size());
        assertEquals(RED, runs.get(0).color());
    }
}
