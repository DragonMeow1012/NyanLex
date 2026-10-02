package com.dragonmeow.nyanlex.hub.tool;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyModFilterTest {
    private static final Set<String> NAMES = Set.of(
            ThirdPartyModFilter.hashName("Zorbleplus"),
            ThirdPartyModFilter.hashName("Quixmod Tools"),
            ThirdPartyModFilter.hashName("Wibble Bobble Pack"),
            ThirdPartyModFilter.hashName("Frumpers Tools"));
    private static final Set<String> BRACKETS = Set.of(ThirdPartyModFilter.hashName("QZX"));

    private static boolean hit(String text) {
        return ThirdPartyModFilter.mentionsMod(text, NAMES, BRACKETS);
    }

    @Test
    void rowsNamingAnotherModAreRejectedFromTheExport() {
        // The project's own name is on the real list, so the integrated gate can be exercised with it.
        HubExportTool.ClassifiedRow byValue = HubExportTool.classifyOneRow(
                "⟦CS0⟧[⟦/CS0⟧Nyanlex⟦CS1⟧]⟦/CS1⟧ Your line is safe", "⟦CS0⟧[⟦/CS0⟧Nyanlex⟦CS1⟧]⟦/CS1⟧ 安全", false);
        assertEquals(HubExportTool.Disposition.REJECTED_THIRD_PARTY, byValue.disposition());
        HubExportTool.ClassifiedRow byKey = HubExportTool.classifyOneRow(
                "[nyanlex] Loaded contest data", "已載入", false);
        assertEquals(HubExportTool.Disposition.REJECTED_THIRD_PARTY, byKey.disposition());
    }

    @Test
    void matchesSingleAndMultiWordNamesCaseInsensitively() {
        assertTrue(hit("[zorbleplus] hello"));
        assertTrue(hit("ZORBLEPLUS: ready"));
        assertTrue(hit("Quixmod Tools loaded"));
        assertTrue(hit("quixmodtools loaded"));
        assertTrue(hit("QuixmodTools loaded"));
        assertTrue(hit("a Wibble Bobble Pack b"));
        assertTrue(hit("x_Zorbleplus_y"));
    }

    @Test
    void possessiveSuffixIsIgnored() {
        assertTrue(hit("Zorbleplus's settings"));
        assertTrue(hit("Wibble's Bobble Pack"));
        assertTrue(hit("Frumpers Tools"));
    }

    @Test
    void bracketedCodeOnlyMatchesInsideBrackets() {
        assertTrue(hit("[QZX] started"));
        assertTrue(hit("[qzx] started"));
        assertFalse(hit("qzx started"));
        assertFalse(hit("[QZX Tools] started"));
    }

    @Test
    void ordinaryVocabularyIsNotMatched() {
        assertFalse(hit("Feather Falling IV"));
        assertFalse(hit("Zorbleplusa"));
        assertFalse(hit("Quixmod"));
        assertFalse(hit("Tools Quixmod"));
        assertFalse(hit(null));
        assertFalse(hit(""));
        assertFalse(ThirdPartyModFilter.mentionsMod("Patchwork Quilt"));
    }

    @Test
    void realHashListIsUnchanged() {
        Set<String> names = Set.of(ThirdPartyModFilter.NAME_HASHES);
        assertEquals(22, ThirdPartyModFilter.NAME_HASHES.length);
        assertEquals(22, names.size());
        assertEquals(ThirdPartyModFilter.NAME_HASHES_CHECKSUM, ThirdPartyModFilter.checksum(names));
        assertEquals("e49114694cad0b0266aec0204dd3b076d8fe134eb9147c5ada368dafe374053e",
                ThirdPartyModFilter.checksum(names));
        assertEquals(1, ThirdPartyModFilter.BRACKET_HASHES.length);
    }
}
