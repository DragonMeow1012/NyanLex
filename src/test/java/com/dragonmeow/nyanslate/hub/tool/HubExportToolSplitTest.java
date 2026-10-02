package com.dragonmeow.nyanslate.hub.tool;

import com.dragonmeow.nyanslate.cache.FileStore;
import com.dragonmeow.nyanslate.translate.ParagraphModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HubExportTool#splitLegacyWholeRows}: splitting a pre-segment-cache
 * whole-paragraph row into its independent TRADE/STATS segment rows BEFORE the rest of
 * the export pipeline runs (design-segment-cache.md §3/§4, "同上規則" as {@code
 * TranslationService#convertLegacyWholeCache}). No file or network is read for the pure
 * {@code splitLegacyWholeRows} tests; the end-to-end {@code classify()} tests use a
 * {@code @TempDir} fixture the test writes itself, same convention as
 * {@code HubExportToolTest}.
 */
class HubExportToolSplitTest {

    private static PrintStream nullOut() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static void seedCache(Path cacheDir, String tag, Map<String, String> rows) {
        Path file = cacheDir.resolve("nyanslate-ai-cache-" + tag + ".json");
        FileStore store = new FileStore(file, true);
        store.putBatch(rows, false);
    }

    private static String hyperionKey(String seller) {
        return ParagraphModel.join(List.of(
                "LEGENDARY", "● Implosion", "● Wither Shield", "Seller: " + seller));
    }

    private static String hyperionValue(String sellerValue) {
        return ParagraphModel.join(List.of(
                "傳奇", "● 內爆", "● 凋零盾",
                "賣家：" + sellerValue));
    }

    @Test
    void splitExtractsTheTradeRowAndKeepsTheOriginalWholeRow() {
        String key = hyperionKey("⟦0⟧");
        String value = hyperionValue("⟦0⟧");
        Map<String, String> raw = Map.of(key, value);

        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);

        assertEquals(1, result.splittableRows());
        assertEquals(1, result.segmentsProduced());
        assertEquals(1, result.distinctSegmentKeys());
        assertTrue(result.rows().containsKey(key), "original whole row kept -- 舊列保留不刪");
        assertEquals(value, result.rows().get(key));
        assertTrue(result.rows().containsKey("Seller: ⟦0⟧"), "the Seller row was split out");
        assertEquals("賣家：⟦0⟧", result.rows().get("Seller: ⟦0⟧"));
        assertEquals(2, result.rows().size(), "exactly the whole row plus the one segment row");
    }

    @Test
    void rowWithNoTradeOrStatsSegmentIsLeftWhollyUnsplit() {
        // Rarity + a 2-name scroll run only -- nothing TRADE/STATS to extract.
        String key = ParagraphModel.join(List.of("LEGENDARY", "● Implosion", "● Wither Shield"));
        String value = ParagraphModel.join(List.of("傳奇", "● 內爆", "● 凋零盾"));
        Map<String, String> raw = Map.of(key, value);

        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);

        assertEquals(0, result.splittableRows());
        assertEquals(0, result.segmentsProduced());
        assertEquals(Map.of(key, value), result.rows());
    }

    @Test
    void structurallyBrokenPbSequenceIsLeftWhollyUnsplit() {
        String key = hyperionKey("⟦0⟧");
        String correctValue = hyperionValue("⟦0⟧");
        // Swap PB0/PB1 so the translated row's own break sequence no longer matches the
        // key's -- row-index correspondence can no longer be trusted for ANY row.
        String broken = correctValue
                .replace("⟦PB0⟧", "⟦PBTMP⟧")
                .replace("⟦PB1⟧", "⟦PB0⟧")
                .replace("⟦PBTMP⟧", "⟦PB1⟧");
        Map<String, String> raw = Map.of(key, broken);

        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);

        assertEquals(0, result.splittableRows());
        assertEquals(Map.of(key, broken), result.rows());
    }

    @Test
    void singleRowParagraphIsNeverPlanEligible() {
        Map<String, String> raw = Map.of("Diamond Sword", "鋻石劍");
        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);
        assertEquals(0, result.splittableRows());
        assertEquals(raw, result.rows());
    }

    @Test
    void twoWholeRowsSharingTheSameSegmentKeyDedupeToOneWithFirstOccurrenceWinning() {
        String keyA = hyperionKey("⟦0⟧"); // same seller row text in both
        String valueA = hyperionValue("⟦0⟧");
        String keyB = ParagraphModel.join(List.of(
                "EPIC", "● Shadow Warp", "● Wither Impact", "Seller: ⟦0⟧"));
        String valueB = ParagraphModel.join(List.of(
                "史詩", "● 暗影躍動", "● 凋零衝擊",
                "賣家：⟦0⟧"));
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(keyA, valueA);
        raw.put(keyB, valueB);

        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);

        assertEquals(2, result.splittableRows());
        assertEquals(2, result.segmentsProduced(), "both rows produced a segment candidate");
        assertEquals(1, result.distinctSegmentKeys(), "both segments collapsed onto the same key");
        // Both whole rows survive untouched regardless.
        assertTrue(result.rows().containsKey(keyA));
        assertTrue(result.rows().containsKey(keyB));
        assertEquals("賣家：⟦0⟧", result.rows().get("Seller: ⟦0⟧"));
    }

    @Test
    void mtSlotAtANonZeroGlobalIndexIsRenumberedBeforeBeingAccepted() {
        // Two TRADE rows, each with its own literal number: the second price is NOT the
        // row's own locally-first ⟦MTn⟧ slot inside the whole key, so a naive
        // (non-renumbering) extraction would never validate -- same mechanism as
        // TranslationService#convertLegacyWholeCache, reused here via LocalTokenRenumberer.
        // This is exactly what TranslationTemplate.prepare would have produced for the
        // masked whole text "Starting bid: 100,000 coins ⟦PB0⟧ Buy it now: 500,000 coins".
        String templatedKey = "Starting bid: ⟦MT0⟧ coins ⟦PB0⟧ Buy it now: ⟦MT1⟧ coins";
        String templatedValue = "起標價：⟦MT0⟧ 金幣 ⟦PB0⟧ "
                + "一口價：⟦MT1⟧ 金幣";
        Map<String, String> raw = Map.of(templatedKey, templatedValue);

        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);

        assertEquals(1, result.splittableRows());
        assertEquals(2, result.segmentsProduced(), "both TRADE rows split out");
        assertTrue(result.rows().containsKey("Starting bid: ⟦MT0⟧ coins"),
                "the FIRST row was already at local index 0, no renumbering needed");
        assertTrue(result.rows().containsKey("Buy it now: ⟦MT0⟧ coins"),
                "the SECOND row was renumbered to the LOCAL index a standalone request for "
                        + "it alone would use (global ⟦MT1⟧ -> local ⟦MT0⟧)");
        assertEquals("一口價：⟦MT0⟧ 金幣",
                result.rows().get("Buy it now: ⟦MT0⟧ coins"));
    }

    @Test
    void aStatsShapedRowWithATemplatedValueIsNowExtractedAlongsideItsSiblingTradeRow() {
        // 2026-10-01 real-cache calibration: StatsLineComposer now ALSO recognises an
        // already-templated ⟦MTn⟧ value slot, not just a literal digit (both the live
        // render path's PRE-template "Strength: +10" and an on-disk cache KEY's POST-
        // template "Strength: ⟦MTn⟧" are the SAME composer now — see its own javadoc). This
        // used to be a documented export-side limitation: a STATS row anywhere in a legacy
        // paragraph blocked splitting ANY of its rows, including an otherwise perfectly
        // extractable TRADE row glued to it (real-cache measurement: ~6% of abandoned
        // multi-row rows were exactly this). Today both rows split out independently.
        String templatedKey = "Strength: ⟦MT0⟧ ⟦PB0⟧ Buy it now: ⟦MT1⟧ coins";
        String templatedValue = "力量：⟦MT0⟧ ⟦PB0⟧ "
                + "一口價：⟦MT1⟧ 金幣";
        Map<String, String> raw = Map.of(templatedKey, templatedValue);

        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);

        assertEquals(1, result.splittableRows());
        assertEquals(2, result.segmentsProduced());
        assertTrue(result.rows().containsKey(templatedKey), "original whole row kept -- 舊列保留不刪");
        assertEquals(templatedValue, result.rows().get(templatedKey));
        assertTrue(result.rows().containsKey("Strength: ⟦MT0⟧"), "the STATS row was split out");
        assertEquals("力量：⟦MT0⟧", result.rows().get("Strength: ⟦MT0⟧"));
        // Locally renumbered: ⟦MT1⟧ is this row's own FIRST (and only) MT token once
        // extracted standalone -- see LocalTokenRenumberer.
        assertTrue(result.rows().containsKey("Buy it now: ⟦MT0⟧ coins"), "the TRADE row was split out");
        assertEquals("一口價：⟦MT0⟧ 金幣", result.rows().get("Buy it now: ⟦MT0⟧ coins"));
        assertEquals(3, result.rows().size(), "the whole row plus its two segment rows");
    }

    @Test
    void aGenuineProseRowGluedToATradeRowNoLongerBlocksSplittingTheTradeRow() {
        // THE core 2026-10-01 real-cache fix: pre-fix, TooltipSegmentPlanner's all-or-
        // nothing design meant ANY row none of the five composers recognised (ordinary
        // lore/description prose, confirmed 93.3% of abandoned real-cache rows) declined
        // the WHOLE plan -- so a TRADE row glued right next to one never split either, even
        // though it was perfectly extractable on its own. Today the prose row becomes its
        // own PROSE segment (not extracted here -- see splitLegacyWholeRows' own javadoc,
        // same conservative scope as ENCHANT/SCROLL) while the TRADE row still splits out.
        String key = ParagraphModel.join(List.of(
                "LEGENDARY", "Right-click to use this item", "Seller: ⟦0⟧"));
        String value = ParagraphModel.join(List.of(
                "傳奇", "右鍵點擊使用此物品", "賣家：⟦0⟧"));
        Map<String, String> raw = Map.of(key, value);

        HubExportTool.SplitResult result = HubExportTool.splitLegacyWholeRows(raw);

        assertEquals(1, result.splittableRows());
        assertEquals(1, result.segmentsProduced(), "only the TRADE row -- PROSE is never extracted here");
        assertTrue(result.rows().containsKey(key), "original whole row kept -- 舊列保留不刪");
        assertTrue(result.rows().containsKey("Seller: ⟦0⟧"), "the Seller row was split out");
        assertEquals("賣家：⟦0⟧", result.rows().get("Seller: ⟦0⟧"));
        assertEquals(2, result.rows().size());
    }

    // ------------------------------------------------------------------ end-to-end classify()

    @Test
    void classifyExpandsALegacyWholeRowIntoBothItselfAndItsSegmentAsIndependentKeptRows(
            @TempDir Path cacheDir) throws IOException {
        String key = hyperionKey("⟦0⟧");
        String value = hyperionValue("⟦0⟧");
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(key, value);
        seedCache(cacheDir, "zh-tw", rows);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        Map<String, HubExportTool.ClassifiedRow> bySource = new LinkedHashMap<>();
        for (HubExportTool.ClassifiedRow row : result) bySource.put(row.source(), row);
        assertEquals(2, result.size(), "the whole row plus its one split-out segment row");
        assertEquals(HubExportTool.Disposition.KEPT, bySource.get(key).disposition());
        HubExportTool.ClassifiedRow segment = bySource.get("Seller: ⟦0⟧");
        assertEquals(HubExportTool.Disposition.KEPT, segment.disposition());
        assertEquals("賣家：⟦0⟧", segment.translated());
        assertFalse(segment.nameConverted(), "the slot was already masked -- nothing to convert here");
    }

    @Test
    void classifyStillAppliesUnmaskedNameConversionToASplitOutSegmentRowIndependently(
            @TempDir Path cacheDir) throws IOException {
        // Splitting runs on the RAW rows BEFORE name conversion -- the extracted Seller
        // segment still carries the player's real account name, so it must go through the
        // SAME UnmaskedNameConverter backstop as any other row, independently of whatever
        // happens to the whole row it came from.
        String key = hyperionKey("AceRay51");
        String value = hyperionValue("AceRay51");
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(key, value);
        seedCache(cacheDir, "zh-tw", rows);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        Map<String, HubExportTool.ClassifiedRow> bySource = new LinkedHashMap<>();
        for (HubExportTool.ClassifiedRow row : result) bySource.put(row.source(), row);
        HubExportTool.ClassifiedRow segment = bySource.get("Seller: ⟦0⟧");
        assertEquals(HubExportTool.Disposition.KEPT, segment.disposition());
        assertTrue(segment.nameConverted());
        assertEquals("賣家：⟦0⟧", segment.translated());
    }
}
