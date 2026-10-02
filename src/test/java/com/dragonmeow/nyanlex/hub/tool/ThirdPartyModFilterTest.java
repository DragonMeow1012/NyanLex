package com.dragonmeow.nyanlex.hub.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyModFilterTest {

    @Test
    void rowsNamingAnotherModAreRejectedFromTheExport() {
        HubExportTool.ClassifiedRow byValue = HubExportTool.classifyOneRow(
                "⟦CS0⟧[⟦/CS0⟧Skyblocker⟦CS1⟧]⟦/CS1⟧ Your line is safe", "⟦CS0⟧[⟦/CS0⟧Skyblocker⟦CS1⟧]⟦/CS1⟧ 安全", false);
        assertEquals(HubExportTool.Disposition.REJECTED_THIRD_PARTY, byValue.disposition());
        HubExportTool.ClassifiedRow byKey = HubExportTool.classifyOneRow(
                "[SkyHanni] Loaded contest data", "已載入", false);
        assertEquals(HubExportTool.Disposition.REJECTED_THIRD_PARTY, byKey.disposition());
    }

    @Test
    void ordinaryVocabularyIsNotMatched() {
        assertFalse(ThirdPartyModFilter.mentionsMod("Feather Falling IV"));
        assertFalse(ThirdPartyModFilter.mentionsMod("Patchwork Quilt"));
        assertTrue(ThirdPartyModFilter.mentionsMod("[skytils] hello"));
    }
}
