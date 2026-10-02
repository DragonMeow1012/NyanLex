package com.dragonmeow.nyanslate.hub.tool;

import com.dragonmeow.nyanslate.cache.FileStore;
import com.dragonmeow.nyanslate.hub.HubIndex;
import com.dragonmeow.nyanslate.translate.TranslationFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every cache file here is a fresh {@code @TempDir} fixture this test writes itself with
 *  a plain {@link FileStore}; nothing touches a real player's config directory. */
class HubExportToolTest {

    private static PrintStream nullOut() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static void seedCache(Path cacheDir, String tag, Map<String, String> finalRows,
            Map<String, String> provisionalRows) {
        Path file = cacheDir.resolve("nyanslate-ai-cache-" + tag + ".json");
        FileStore store = new FileStore(file, true);
        if (finalRows != null) store.putBatch(finalRows, false);
        if (provisionalRows != null) store.putBatch(provisionalRows, true);
    }

    // ------------------------------------------------------------------ classify()

    @Test
    void classifyAcceptsValidRowsAndRejectsUntranslatedEcho(@TempDir Path cacheDir) throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Diamond Sword", "鑽石劍");
        rows.put("Ender Pearl", "Ender Pearl"); // untranslated echo: rejected
        seedCache(cacheDir, "zh-tw", rows, null);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        assertEquals(2, result.size());
        Map<String, HubExportTool.Disposition> bySource = new LinkedHashMap<>();
        for (HubExportTool.ClassifiedRow row : result) bySource.put(row.source(), row.disposition());
        assertEquals(HubExportTool.Disposition.KEPT, bySource.get("Diamond Sword"));
        assertEquals(HubExportTool.Disposition.REJECTED_VALIDATION, bySource.get("Ender Pearl"));
    }

    @Test
    void classifySkipsProvisionalRows(@TempDir Path cacheDir) throws IOException {
        Map<String, String> finalRows = new LinkedHashMap<>();
        finalRows.put("Diamond Sword", "鑽石劍");
        Map<String, String> provisional = new LinkedHashMap<>();
        provisional.put("Ender Pearl", "終界珍珠(暫定)");
        seedCache(cacheDir, "zh-tw", finalRows, provisional);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        assertEquals(1, result.size());
        assertEquals("Diamond Sword", result.get(0).source());
    }

    @Test
    void classifySkipsNulPrefixedSentinelRows(@TempDir Path cacheDir) throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Diamond Sword", "鑽石劍");
        rows.put("Weird Phrase", "\u0000MT_KEEP_ORIGINAL2"); // TranslationCache's KEEP_ORIGINAL sentinel
        seedCache(cacheDir, "zh-tw", rows, null);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        assertEquals(1, result.size());
        assertEquals("Diamond Sword", result.get(0).source());
    }

    @Test
    void classifyConvertsRawUnmaskedPlayerNameWhenItSurvivesInTheValue(@TempDir Path cacheDir) throws IOException {
        // Real-data finding: a legacy Auction House tooltip row cached before the
        // "ah.field" PlayerNamePatterns frame existed can carry a RAW (unmasked) seller
        // name. 2026-10-01 user decision: convert it in place (mask the name as ⟦n⟧ in
        // both key and value) rather than discard the whole row, since the AI usually
        // left the proper noun untranslated.
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Seller: AceRay51", "賣家：AceRay51");
        seedCache(cacheDir, "zh-tw", rows, null);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);
        assertEquals(1, result.size());
        HubExportTool.ClassifiedRow row = result.get(0);
        assertEquals(HubExportTool.Disposition.KEPT, row.disposition());
        assertTrue(row.nameConverted());
        assertEquals("Seller: ⟦0⟧", row.source());
        assertEquals("賣家：⟦0⟧", row.translated());
        // The backstop never trips on a correctly converted row: no raw name survives.
        assertTrue(com.dragonmeow.nyanslate.translate.PlayerNamePatterns.nameSpans(row.source()).isEmpty());
    }

    @Test
    void classifyRejectsUnmaskedNameWhenTheExactTextIsMissingFromTheValue(@TempDir Path cacheDir)
            throws IOException {
        // The AI reshaped/translated the proper noun differently than it appears in the
        // key (simulated here by a value that simply never mentions the seller at all):
        // conversion cannot safely locate it, so the row must still be rejected.
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Seller: AceRay51", "賣家：某人");
        seedCache(cacheDir, "zh-tw", rows, null);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        assertEquals(1, result.size());
        assertEquals(HubExportTool.Disposition.REJECTED_UNMASKED_NAME, result.get(0).disposition());
        assertEquals("name-not-found-in-value", result.get(0).reason());
        assertFalse(result.get(0).nameConverted());
    }

    @Test
    void classifyConversionRenumbersAnExistingSlotThatNowFallsAfterTheNewName(@TempDir Path cacheDir)
            throws IOException {
        // The raw, frame-recognised seller name comes FIRST in the row; a pre-existing
        // bare ⟦0⟧ (e.g. a TAB-masked buyer) comes after it on the next "line" (a real
        // newline, same role a ⟦PBn⟧ paragraph-break token plays in a real tooltip).
        // Converting must renumber by POSITION: the new name claims index 0 (it appears
        // first) and the pre-existing slot shifts from 0 to 1 — in BOTH key and value.
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Seller: AceRay51\nBuyer: ⟦0⟧", "賣家：AceRay51\n買家：⟦0⟧");
        seedCache(cacheDir, "zh-tw", rows, null);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        assertEquals(1, result.size());
        HubExportTool.ClassifiedRow row = result.get(0);
        assertEquals(HubExportTool.Disposition.KEPT, row.disposition());
        assertEquals("Seller: ⟦0⟧\nBuyer: ⟦1⟧", row.source());
        assertEquals("賣家：⟦0⟧\n買家：⟦1⟧", row.translated());
    }

    @Test
    void classifyRejectsForeignUrlSeparatelyFromValidation(@TempDir Path cacheDir) throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Visit our site", "請上 http://scam.example.com 領取獎勵");
        seedCache(cacheDir, "zh-tw", rows, null);

        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);

        assertEquals(1, result.size());
        assertEquals(HubExportTool.Disposition.REJECTED_FOREIGN_URL, result.get(0).disposition());
    }

    @Test
    void classifyWithDropChatDropsChatShapedRowsOnly(@TempDir Path cacheDir) throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Strength: +10", "力量：+10");
        rows.put("[VIP] hello everyone", "[VIP] 大家好");
        seedCache(cacheDir, "zh-tw", rows, null);

        List<HubExportTool.ClassifiedRow> withoutDrop = HubExportTool.classify(cacheDir, "zh-TW", false);
        assertTrue(withoutDrop.stream().allMatch(r -> r.disposition() == HubExportTool.Disposition.KEPT));

        List<HubExportTool.ClassifiedRow> withDrop = HubExportTool.classify(cacheDir, "zh-TW", true);
        Map<String, HubExportTool.Disposition> bySource = new LinkedHashMap<>();
        for (HubExportTool.ClassifiedRow row : withDrop) bySource.put(row.source(), row.disposition());
        assertEquals(HubExportTool.Disposition.KEPT, bySource.get("Strength: +10"));
        assertEquals(HubExportTool.Disposition.DROPPED_CHAT, bySource.get("[VIP] hello everyone"));
    }

    @Test
    void classifyReturnsEmptyWhenCacheFileMissing(@TempDir Path cacheDir) throws IOException {
        List<HubExportTool.ClassifiedRow> result = HubExportTool.classify(cacheDir, "zh-TW", false);
        assertTrue(result.isEmpty());
    }

    @Test
    void classifyNeverMutatesTheOriginalCacheFile(@TempDir Path cacheDir) throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Diamond Sword", "鑽石劍");
        seedCache(cacheDir, "zh-tw", rows, null);
        Path original = cacheDir.resolve("nyanslate-ai-cache-zh-tw.json");
        byte[] before = Files.readAllBytes(original);

        HubExportTool.classify(cacheDir, "zh-TW", false);

        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(original)),
                "the original cache file must be byte-for-byte unchanged after classify()");
    }

    // ------------------------------------------------------------------ run() end to end

    @Test
    void runWritesRepositoryFileAndMergesIndex(@TempDir Path cacheDir, @TempDir Path out) throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Diamond Sword", "鑽石劍");
        rows.put("Ender Pearl", "終界珍珠");
        seedCache(cacheDir, "zh-tw", rows, null);

        HubExportTool.Result result = HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "mc.hypixel.net",
                "--out", out.toString(), "--merge-index"
        }, nullOut());

        assertEquals(0, result.exitCode());
        assertEquals(2, result.stats().exportedRows());
        Path written = out.resolve("servers").resolve("hypixel.net").resolve("zh-tw.json");
        assertEquals(written, result.writtenFile());
        assertTrue(Files.isRegularFile(written));

        TranslationFile file = TranslationFile.read(written);
        assertEquals("鑽石劍", file.ai.get("Diamond Sword"));
        assertEquals("終界珍珠", file.ai.get("Ender Pearl"));
        assertEquals("zh-tw", file.language);

        Path indexFile = out.resolve("index.json");
        assertTrue(Files.isRegularFile(indexFile));
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            HubIndex index = HubIndex.read(reader);
            HubIndex.LanguageStats stats = index.server("hypixel.net", "zh-TW");
            assertEquals(2, stats.rows());
            assertEquals(result.stats().sha256(), stats.sha256());
        }
    }

    @Test
    void runNormalizesServerHostToRegistrableDomain(@TempDir Path cacheDir, @TempDir Path out) throws Exception {
        seedCache(cacheDir, "zh-tw", Map.of("Diamond Sword", "鑽石劍"), null);

        HubExportTool.Result result = HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "mc.hypixel.net:25565",
                "--out", out.toString()
        }, nullOut());

        assertEquals(0, result.exitCode());
        assertTrue(Files.isRegularFile(out.resolve("servers").resolve("hypixel.net").resolve("zh-tw.json")));
    }

    @Test
    void runRejectsPrivateHostAsUsageError(@TempDir Path cacheDir, @TempDir Path out) {
        assertThrows(HubExportTool.UsageException.class, () -> HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "localhost",
                "--out", out.toString()
        }, nullOut()));
    }

    @Test
    void runRequiresExactlyOneSourceFlag(@TempDir Path cacheDir, @TempDir Path out) {
        assertThrows(HubExportTool.UsageException.class, () -> HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--out", out.toString()
        }, nullOut()));
        assertThrows(HubExportTool.UsageException.class, () -> HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW",
                "--server", "hypixel.net", "--modpack", "some-pack", "--out", out.toString()
        }, nullOut()));
    }

    @Test
    void runPreservesModIdUnderscoresInsteadOfSlugifying(@TempDir Path cacheDir, @TempDir Path out)
            throws Exception {
        seedCache(cacheDir, "zh-tw", Map.of("Some Item", "某物品"), null);

        HubExportTool.Result result = HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--mod", "placeholder_api",
                "--out", out.toString()
        }, nullOut());

        assertEquals(0, result.exitCode());
        assertTrue(Files.isRegularFile(out.resolve("mods").resolve("placeholder_api").resolve("zh-tw.json")),
                "a mod id's underscore must survive exactly: it is matched verbatim against loadedModIds");
    }

    @Test
    void runReportsNothingToExportWithoutWritingAFile(@TempDir Path cacheDir, @TempDir Path out)
            throws Exception {
        HubExportTool.Result result = HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "hypixel.net",
                "--out", out.toString()
        }, nullOut());

        assertEquals(0, result.exitCode());
        assertNull(result.writtenFile());
        assertFalse(Files.exists(out.resolve("servers")));
    }

    @Test
    void runMaxRowsDeterministicallyTruncatesAndReportsTheOmittedCount(
            @TempDir Path cacheDir, @TempDir Path out) throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Row C", "C列");
        rows.put("Row A", "A列");
        rows.put("Row B", "B列");
        seedCache(cacheDir, "zh-tw", rows, null);

        HubExportTool.Result result = HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "hypixel.net",
                "--out", out.toString(), "--max-rows", "2"
        }, nullOut());

        assertEquals(0, result.exitCode());
        assertEquals(2, result.stats().exportedRows());
        assertEquals(1, result.stats().truncated());
        TranslationFile file = TranslationFile.read(result.writtenFile());
        // Sorted by key: "Row A" and "Row B" survive, "Row C" is the one left out.
        assertTrue(file.ai.containsKey("Row A"));
        assertTrue(file.ai.containsKey("Row B"));
        assertFalse(file.ai.containsKey("Row C"));
    }

    @Test
    void runRejectsMaxRowsAboveTheHardCap(@TempDir Path cacheDir, @TempDir Path out) {
        assertThrows(HubExportTool.UsageException.class, () -> HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "hypixel.net",
                "--out", out.toString(), "--max-rows", String.valueOf(HubExportTool.MAX_ROWS + 1)
        }, nullOut()));
    }

    @Test
    void runRejectsFileExceedingTheByteCapAndLeavesNoPartialFile(@TempDir Path cacheDir, @TempDir Path out)
            throws Exception {
        // Short keys, ~16000-CJK-char values (~48 KB each in UTF-8): ~750 rows clears the
        // raised 32 MiB per-file cap comfortably without needing hundreds of thousands of
        // rows (2026-10-01: cap raised from 3 MiB/40,000 rows to 16 MiB/100,000 rows, then
        // same day to 32 MiB/100,000 rows so the hypixel.net seed's 54,281 kept rows fit).
        Map<String, String> rows = new LinkedHashMap<>();
        String bigTranslation = "測".repeat(16_000);
        for (int i = 0; i < 750; i++) {
            rows.put(String.format("Row %04d", i), bigTranslation);
        }
        seedCache(cacheDir, "zh-tw", rows, null);

        HubExportTool.Result result = HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "hypixel.net",
                "--out", out.toString()
        }, nullOut());

        assertEquals(1, result.exitCode());
        assertFalse(Files.exists(out.resolve("servers").resolve("hypixel.net").resolve("zh-tw.json")),
                "a file that fails the byte cap must never be left behind");
    }

    @Test
    void runDedupesRowsThatCollapseOntoTheSameKeyAfterNameConversion(@TempDir Path cacheDir, @TempDir Path out)
            throws Exception {
        // Two distinct Auction House listings whose only difference was the seller's raw
        // name collapse onto the identical converted key. The most common translated
        // value must win (2026-10-01 user instruction: "取最常見或最新").
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Seller: AceRay51", "賣家：AceRay51 常見翻譯");
        rows.put("Seller: BobTheBuilder", "賣家：BobTheBuilder 常見翻譯");
        rows.put("Seller: CarlJones1", "賣家：CarlJones1 罕見翻譯");
        seedCache(cacheDir, "zh-tw", rows, null);

        HubExportTool.Result result = HubExportTool.run(new String[] {
                "--cache-dir", cacheDir.toString(), "--lang", "zh-TW", "--server", "hypixel.net",
                "--out", out.toString()
        }, nullOut());

        assertEquals(0, result.exitCode());
        assertEquals(1, result.stats().exportedRows());
        assertEquals(1, result.stats().duplicateKeyGroups());
        assertEquals(2, result.stats().duplicateRowsDropped());
        assertEquals(3, result.stats().nameConversionSucceeded());
        TranslationFile file = TranslationFile.read(result.writtenFile());
        assertEquals("賣家：⟦0⟧ 常見翻譯", file.ai.get("Seller: ⟦0⟧"));
    }
}
