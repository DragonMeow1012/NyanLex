package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.cache.CacheLogMerger;
import com.dragonmeow.nyanlex.cache.FileStore;
import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.translate.DebugErrorLog;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache half of {@link LegacyDataMigration}: copy when the current file is missing, merge
 * (current rows win) when both exist, do each only once, and never overwrite on a failure.
 * Everything runs on generated data in a temporary folder.
 */
class LegacyCacheMigrationTest {
    private static final Gson GSON = new Gson();
    private static final int BIG = 100_000;

    @BeforeEach
    @AfterEach
    void forgetEarlyErrorReports() {
        // Migration problems are queued for the error log; never let them leak between tests.
        DebugErrorLog.install(null);
    }

    // ------------------------------------------------------------------ helpers

    private static String upsert(String key, String value) {
        return upsert(key, value, false);
    }

    private static String upsert(String key, String value, boolean provisional) {
        JsonObject row = new JsonObject();
        row.addProperty("key", key);
        row.addProperty("translation", value);
        row.addProperty("provisional", provisional);
        return GSON.toJson(row);
    }

    private static String tombstone(String key) {
        JsonObject row = new JsonObject();
        row.addProperty("key", key);
        row.addProperty("deleted", true);
        return GSON.toJson(row);
    }

    private static Path journal(Path file, int schema, String... rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("{\"schema\":" + schema + "}");
        lines.addAll(Arrays.asList(rows));
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    private static Map<String, String> read(Path file) {
        return new FileStore(file, false, 1_000_000).entries();
    }

    private static String bigKey(int i) {
        return "Teleports you to the hidden island number " + i + " when you right click this item, "
                + "then grants a short movement speed and night vision bonus";
    }

    private static String bigValue(int i) {
        String sentence = "右鍵點擊此物品時，會把你傳送到第 " + i + " 號隱藏島嶼，並且短暫獲得移動速度加成與夜視效果，持續時間會隨等級提升而增加。";
        // About 600 bytes per row, so 100 000 rows come to the 60 MB of a real long-played cache.
        return sentence + sentence + "傳送冷卻時間為 30 秒。";
    }

    private static void writeBigJournal(Path file, int rows, int firstRow) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("{\"schema\":4}");
            writer.newLine();
            for (int i = firstRow; i < firstRow + rows; i++) {
                writer.write(upsert(bigKey(i), bigValue(i)));
                writer.newLine();
            }
        }
    }

    private static JsonObject marker(Path dir) throws IOException {
        return JsonParser.parseString(Files.readString(dir.resolve(LegacyDataMigration.MARKER_FILE),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("done");
    }

    private static void assertNoLeftovers(Path dir) throws IOException {
        try (var names = Files.list(dir)) {
            names.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".tmp") || n.endsWith(".merge.tmp") || n.endsWith(".migrating.tmp"))
                    .findAny()
                    .ifPresent(n -> org.junit.jupiter.api.Assertions.fail("temporary file left behind: " + n));
        }
    }

    // ------------------------------------------------------------------ scenario A: nothing under the new name

    @Test
    void copiesTheAiCacheWhenTheCurrentFileIsMissingAndRecordsIt(@TempDir Path dir) throws IOException {
        Path legacy = journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4,
                upsert("a", "甲"), upsert("b", "乙"));
        byte[] before = Files.readAllBytes(legacy);

        List<String> log = new ArrayList<>();
        assertEquals(1, LegacyDataMigration.migrate(dir, log::add));

        Map<String, String> entries = read(dir.resolve("nyanlex-ai-cache-zh-tw.json"));
        assertEquals(Map.of("a", "甲", "b", "乙"), entries);
        assertArrayEquals(before, Files.readAllBytes(legacy), "the legacy file is never changed");
        assertEquals("copied", marker(dir).getAsJsonObject("nyanslate-ai-cache-zh-tw.json").get("result").getAsString());
        assertNoLeftovers(dir);
    }

    // ------------------------------------------------------------------ scenario B: a small current file already there

    @Test
    void mergesIntoASmallCurrentFileWithCurrentRowsWinning(@TempDir Path dir) throws IOException {
        Path legacy = journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4,
                upsert("old-1", "舊一"), upsert("shared", "舊版共用"), upsert("old-2", "舊二"),
                upsert("provisional-in-both", "舊暫代", true));
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4,
                upsert("new-1", "新一"), upsert("shared", "新版共用"),
                upsert("provisional-in-both", "新定稿", false));
        byte[] legacyBefore = Files.readAllBytes(legacy);
        byte[] currentBefore = Files.readAllBytes(current);

        List<String> log = new ArrayList<>();
        assertEquals(1, LegacyDataMigration.migrate(dir, log::add));

        FileStore merged = new FileStore(current, false, 1_000_000);
        Map<String, String> entries = merged.entries();
        assertEquals("新版共用", entries.get("shared"), "a key the current file holds keeps its value");
        assertEquals("新定稿", entries.get("provisional-in-both"));
        assertFalse(merged.isProvisional("provisional-in-both"), "and its provisional flag");
        assertEquals("舊一", entries.get("old-1"));
        assertEquals("舊二", entries.get("old-2"));
        assertEquals("新一", entries.get("new-1"));
        assertEquals(5, entries.size());
        // Rows taken from the legacy file go first (oldest, evicted first), the live rows last.
        assertEquals(List.of("old-1", "old-2", "new-1", "shared", "provisional-in-both"),
                new ArrayList<>(entries.keySet()));
        assertArrayEquals(legacyBefore, Files.readAllBytes(legacy), "the legacy file is kept as it was");
        assertArrayEquals(currentBefore,
                Files.readAllBytes(dir.resolve("nyanlex-ai-cache-zh-tw.json" + CacheLogMerger.BACKUP_SUFFIX)),
                "the replaced current file is kept verbatim");
        assertTrue(log.stream().anyMatch(line -> line.contains("merged legacy cache") && line.contains("+2 rows")), log.toString());
        assertEquals("merged", marker(dir).getAsJsonObject("nyanslate-ai-cache-zh-tw.json").get("result").getAsString());
        assertNoLeftovers(dir);
    }

    @Test
    void theMergeIsDoneOnlyOnce(@TempDir Path dir) throws IOException {
        Path legacy = journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4, upsert("old", "舊"));
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("new", "新"));
        assertEquals(1, LegacyDataMigration.migrate(dir, null));
        byte[] afterFirst = Files.readAllBytes(current);

        // The legacy file grows afterwards (an old jar played again); the marker keeps it out.
        Files.writeString(legacy, upsert("later", "後來") + System.lineSeparator(),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        List<String> log = new ArrayList<>();
        assertEquals(0, LegacyDataMigration.migrate(dir, log::add));
        assertArrayEquals(afterFirst, Files.readAllBytes(current));
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void aClearedCacheIsNotBroughtBackFromTheLegacyName(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4, upsert("a", "甲"));
        journal(dir.resolve("nyanslate-cache-zh-tw.json"), 4, upsert("g", "機翻"));
        journal(dir.resolve("nyanslate-failures-zh-tw.json"), 4, upsert("f", "失敗"));
        Files.writeString(dir.resolve("nyanslate.json"), "{\"targetLang\":\"zh-TW\"}", StandardCharsets.UTF_8);
        assertEquals(4, LegacyDataMigration.migrate(dir, null));

        // "Clear saved translations" deletes the active language's files through the stores.
        new LanguageFileStore(dir, "nyanlex-ai-cache", "zh-TW").clear();
        new LanguageFileStore(dir, "nyanlex-cache", "zh-TW").clear();
        new LanguageFileStore(dir, "nyanlex-failures", "zh-TW").clear();
        Files.delete(dir.resolve("nyanlex.json"));
        assertFalse(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json")));

        List<String> log = new ArrayList<>();
        assertEquals(0, LegacyDataMigration.migrate(dir, log::add), "nothing comes back");
        assertFalse(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json")));
        assertFalse(Files.exists(dir.resolve("nyanlex-cache-zh-tw.json")));
        assertFalse(Files.exists(dir.resolve("nyanlex-failures-zh-tw.json")));
        assertFalse(Files.exists(dir.resolve("nyanlex.json")));
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void aStartThatKeptAnExistingSettingsFileAlsoCountsAsHandled(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("nyanslate.json"), "{\"old\":true}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanlex.json"), "{\"new\":true}", StandardCharsets.UTF_8);
        assertEquals(0, LegacyDataMigration.migrate(dir, null));
        Files.delete(dir.resolve("nyanlex.json"));
        assertEquals(0, LegacyDataMigration.migrate(dir, null));
        assertFalse(Files.exists(dir.resolve("nyanlex.json")), "old settings do not replace deleted new ones");
    }

    // ------------------------------------------------------------------ row-level behaviour

    @Test
    void legacyTombstonesAndDuplicatesAreReplayedLastWins(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4,
                upsert("kept", "v1"), upsert("removed", "gone"), upsert("kept", "v2"),
                tombstone("removed"), upsert("revived", "first"), tombstone("revived"), upsert("revived", "second"));
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("x", "y"));

        assertEquals(1, LegacyDataMigration.migrate(dir, null));

        Map<String, String> entries = read(current);
        assertEquals(Map.of("kept", "v2", "revived", "second", "x", "y"), entries);
        assertEquals(List.of("kept", "revived", "x"), new ArrayList<>(entries.keySet()));
    }

    @Test
    void aKeyDeletedFromTheCurrentFileIsFilledFromTheLegacyFile(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4, upsert("k", "legacy"));
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4,
                upsert("k", "current"), tombstone("k"), upsert("z", "z"));
        LegacyDataMigration.migrate(dir, null);
        assertEquals(Map.of("k", "legacy", "z", "z"), read(current));
    }

    @Test
    void olderSnapshotSchemasOfEitherFileAreUnderstood(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 3, upsert("s3", "三"), upsert("both", "舊"));
        JsonObject schema2 = new JsonObject();
        schema2.addProperty("schema", 2);
        var entries = new com.google.gson.JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("key", "s2");
        entry.addProperty("translation", "二");
        entries.add(entry);
        schema2.add("entries", entries);
        Files.writeString(dir.resolve("mctranslator-ai-cache-zh-tw.json"), GSON.toJson(schema2) + "\n",
                StandardCharsets.UTF_8);
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 3, upsert("both", "新"));

        LegacyDataMigration.migrate(dir, null);

        assertEquals(Map.of("s3", "三", "s2", "二", "both", "新"), read(current));
        assertEquals("{\"schema\":4}",
                Files.readAllLines(current, StandardCharsets.UTF_8).get(0), "the result is a schema 4 journal");
    }

    @Test
    void damagedRowsAndATornTailAreSkippedNotFatal(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("nyanslate-ai-cache-zh-tw.json");
        byte[] good1 = (upsert("one", "一") + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] garbage = "{this is not json}\n".getBytes(StandardCharsets.UTF_8);
        byte[] noKey = "{\"translation\":\"x\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] good2 = (upsert("two", "二") + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] torn = "{\"key\":\"three\",\"translation\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] tornChar = new byte[] {(byte) 0xE4, (byte) 0xBD}; // first two bytes of a three-byte character
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.write("{\"schema\":4}\n".getBytes(StandardCharsets.UTF_8));
        for (byte[] part : new byte[][] {good1, garbage, noKey, good2, torn, tornChar}) bytes.write(part);
        Files.write(legacy, bytes.toByteArray());
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("cur", "現"));

        List<String> log = new ArrayList<>();
        LegacyDataMigration.migrate(dir, log::add);

        assertEquals(Map.of("one", "一", "two", "二", "cur", "現"), read(current));
        assertTrue(log.stream().anyMatch(line -> line.contains("3 damaged rows skipped")), log.toString());
    }

    @Test
    void anEmptyCurrentFileTakesTheLegacyRows(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4, upsert("a", "甲"));
        Path current = dir.resolve("nyanlex-ai-cache-zh-tw.json");
        Files.write(current, new byte[0]);

        assertEquals(1, LegacyDataMigration.migrate(dir, null));

        assertEquals(Map.of("a", "甲"), read(current));
        assertFalse(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json" + CacheLogMerger.BACKUP_SUFFIX)),
                "nothing worth backing up in an empty file");
    }

    @Test
    void aLegacyFileThatAddsNothingLeavesTheCurrentFileUntouched(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4, upsert("a", "舊"));
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("a", "新"), upsert("b", "乙"));
        byte[] before = Files.readAllBytes(current);

        assertEquals(0, LegacyDataMigration.migrate(dir, null));

        assertArrayEquals(before, Files.readAllBytes(current));
        assertFalse(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json" + CacheLogMerger.BACKUP_SUFFIX)));
        assertEquals("nothing-to-add",
                marker(dir).getAsJsonObject("nyanslate-ai-cache-zh-tw.json").get("result").getAsString());
    }

    @Test
    void nyanslateRowsOutrankMctranslatorRowsAndBothFillTheCurrentFile(@TempDir Path dir) throws IOException {
        journal(dir.resolve("mctranslator-ai-cache-zh-tw.json"), 4,
                upsert("oldest-only", "最舊"), upsert("both-legacy", "最舊版"), upsert("all", "最舊版"));
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4,
                upsert("newer-only", "較新"), upsert("both-legacy", "較新版"), upsert("all", "較新版"));
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("all", "現行"));

        assertEquals(2, LegacyDataMigration.migrate(dir, null));

        assertEquals(Map.of("oldest-only", "最舊", "newer-only", "較新", "both-legacy", "較新版", "all", "現行"),
                read(current));
    }

    // ------------------------------------------------------------------ every kind of cache file takes the same rules

    @Test
    void machineCacheFailureLedgerAndProviderCachesFollowTheSameRules(@TempDir Path dir) throws IOException {
        String[] names = {"nyanlex-cache-zh-tw.json", "nyanlex-cache-deepl-zh-tw.json", "nyanlex-failures-zh-tw.json",
                "nyanlex-ai-cache-en-us.json"};
        for (String name : names) {
            journal(dir.resolve(name.replace("nyanlex", "nyanslate")), 4, upsert("old", "舊"), upsert("both", "舊"));
            journal(dir.resolve(name), 4, upsert("new", "新"), upsert("both", "新"));
        }

        assertEquals(names.length, LegacyDataMigration.migrate(dir, null));

        for (String name : names) {
            assertEquals(Map.of("old", "舊", "new", "新", "both", "新"), read(dir.resolve(name)), name);
        }
    }

    @Test
    void hubCacheRowsAreMergedWithCurrentRowsWinning(@TempDir Path dir) throws IOException {
        String h1 = HubKeyHash.of("one");
        String h2 = HubKeyHash.of("two");
        String h3 = HubKeyHash.of("three");
        Files.writeString(dir.resolve("nyanslate-hub-cache-zh-tw.json"),
                "{\"schema\":2,\"language\":\"zh-tw\",\"rows\":{\"" + h1 + "\":{\"v\":\"舊一\",\"s\":\"mod:a\"},\""
                        + h2 + "\":{\"v\":\"舊二\"},\"not-a-hash\":{\"v\":\"x\"}}}", StandardCharsets.UTF_8);
        Path current = dir.resolve("nyanlex-hub-cache-zh-tw.json");
        Files.writeString(current,
                "{\"schema\":2,\"language\":\"zh-tw\",\"rows\":{\"" + h2 + "\":{\"v\":\"新二\",\"s\":\"mod:b\"},\""
                        + h3 + "\":{\"v\":\"新三\"}}}", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(current);

        assertEquals(1, LegacyDataMigration.migrate(dir, null));

        JsonObject rows = JsonParser.parseString(Files.readString(current, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("rows");
        assertEquals(3, rows.size());
        assertEquals("舊一", rows.getAsJsonObject(h1).get("v").getAsString());
        assertEquals("mod:a", rows.getAsJsonObject(h1).get("s").getAsString());
        assertEquals("新二", rows.getAsJsonObject(h2).get("v").getAsString());
        assertEquals("mod:b", rows.getAsJsonObject(h2).get("s").getAsString());
        assertEquals("新三", rows.getAsJsonObject(h3).get("v").getAsString());
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("nyanlex-hub-cache-zh-tw.json" + CacheLogMerger.BACKUP_SUFFIX)));
    }

    @Test
    void hubDownloadLedgerIsMerged(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator-hub-state.json"),
                "{\"schema\":1,\"sha\":{\"mod:a\\u0000zh-tw\":\"aaa\",\"mod:b\\u0000zh-tw\":\"old-b\"}}", StandardCharsets.UTF_8);
        Path current = dir.resolve("nyanlex-hub-state.json");
        Files.writeString(current, "{\"schema\":1,\"sha\":{\"mod:b\\u0000zh-tw\":\"new-b\"}}", StandardCharsets.UTF_8);

        assertEquals(1, LegacyDataMigration.migrate(dir, null));

        JsonObject sha = JsonParser.parseString(Files.readString(current, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("sha");
        assertEquals("aaa", sha.get("mod:a\u0000zh-tw").getAsString());
        assertEquals("new-b", sha.get("mod:b\u0000zh-tw").getAsString());
    }

    // ------------------------------------------------------------------ failures never overwrite

    @Test
    void anUnreadableLegacyFileChangesNothingAndIsNotRetried(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("nyanslate-ai-cache-zh-tw.json"), "this is not a cache\nat all\n", StandardCharsets.UTF_8);
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("new", "新"));
        byte[] before = Files.readAllBytes(current);

        List<String> log = new ArrayList<>();
        assertEquals(0, LegacyDataMigration.migrate(dir, log::add));

        assertArrayEquals(before, Files.readAllBytes(current));
        assertFalse(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json" + CacheLogMerger.BACKUP_SUFFIX)));
        assertTrue(log.stream().anyMatch(line -> line.contains("merge skipped, nothing was changed")), log.toString());
        assertEquals("unreadable",
                marker(dir).getAsJsonObject("nyanslate-ai-cache-zh-tw.json").get("result").getAsString());
        assertNoLeftovers(dir);

        log.clear();
        assertEquals(0, LegacyDataMigration.migrate(dir, log::add));
        assertTrue(log.isEmpty(), "a file found unreadable is not tried again every start: " + log);
    }

    @Test
    void anUnreadableCurrentFileIsNotOverwritten(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4, upsert("old", "舊"));
        Path current = dir.resolve("nyanlex-ai-cache-zh-tw.json");
        Files.writeString(current, "garbage, not a journal", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(current);

        List<String> log = new ArrayList<>();
        LegacyDataMigration.migrate(dir, log::add);

        assertArrayEquals(before, Files.readAllBytes(current));
        assertTrue(log.stream().anyMatch(line -> line.contains("nothing was changed")), log.toString());
        assertNoLeftovers(dir);
    }

    @Test
    void aFileOfAFutureSchemaIsLeftAlone(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 9, upsert("old", "舊"));
        Path current = journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("new", "新"));
        byte[] before = Files.readAllBytes(current);

        LegacyDataMigration.migrate(dir, null);

        assertArrayEquals(before, Files.readAllBytes(current));
    }

    @Test
    void unreadableHubFilesAreLeftAlone(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("nyanslate-hub-cache-zh-tw.json"), "[1,2,3]", StandardCharsets.UTF_8);
        Path current = dir.resolve("nyanlex-hub-cache-zh-tw.json");
        Files.writeString(current, "{\"schema\":2,\"language\":\"zh-tw\",\"rows\":{}}", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(current);

        LegacyDataMigration.migrate(dir, null);

        assertArrayEquals(before, Files.readAllBytes(current));
    }

    @Test
    void anIoProblemIsNotRecordedSoTheNextStartTriesAgain(@TempDir Path dir) throws IOException {
        journal(dir.resolve("nyanslate-ai-cache-zh-tw.json"), 4, upsert("old", "舊"));
        // A directory where the current cache should be: reading it fails with an I/O error.
        Path current = dir.resolve("nyanlex-ai-cache-zh-tw.json");
        Files.createDirectory(current);

        List<String> log = new ArrayList<>();
        assertEquals(0, LegacyDataMigration.migrate(dir, log::add));
        assertTrue(log.stream().anyMatch(line -> line.contains("merge failed, nothing was changed")), log.toString());

        Files.delete(current);
        journal(current, 4, upsert("new", "新"));
        assertEquals(1, LegacyDataMigration.migrate(dir, null));
        assertEquals(Map.of("old", "舊", "new", "新"), read(current));
    }

    @Test
    void failuresAreWrittenToTheErrorLogOnceItIsInstalled(@TempDir Path dir) throws IOException, InterruptedException {
        Files.writeString(dir.resolve("nyanslate-ai-cache-zh-tw.json"), "not a cache", StandardCharsets.UTF_8);
        journal(dir.resolve("nyanlex-ai-cache-zh-tw.json"), 4, upsert("new", "新"));
        LegacyDataMigration.migrate(dir, null);

        Path debugFile = dir.resolve("debug.jsonl");
        var debugLog = new DebugErrorLog(debugFile, () -> true, 100, java.util.List::of);
        try {
            DebugErrorLog.install(debugLog);
            debugLog.awaitIdleForTest();
            String text = Files.readString(debugFile, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"type\":\"migration\""), text);
            assertTrue(text.contains("nyanslate-ai-cache-zh-tw.json"), text);
        } finally {
            DebugErrorLog.install(null);
        }
    }

    // ------------------------------------------------------------------ a real-sized cache

    @Test
    void aHundredThousandRowCacheIsCopiedAndLoadsInFull(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("nyanslate-ai-cache-zh-tw.json");
        writeBigJournal(legacy, BIG, 0);
        long legacyBytes = Files.size(legacy);

        long started = System.nanoTime();
        assertEquals(1, LegacyDataMigration.migrate(dir, null));
        long copyMillis = (System.nanoTime() - started) / 1_000_000L;

        Path current = dir.resolve("nyanlex-ai-cache-zh-tw.json");
        assertEquals(legacyBytes, Files.size(current));
        long loadStarted = System.nanoTime();
        FileStore store = new FileStore(current, false, 250_000);
        long loadMillis = (System.nanoTime() - loadStarted) / 1_000_000L;
        assertEquals(BIG, store.size());
        assertEquals(bigValue(77_777), store.get(bigKey(77_777)));
        System.out.println("[migration] copy " + BIG + " rows (" + legacyBytes / 1_000_000 + " MB): "
                + copyMillis + " ms; FileStore load: " + loadMillis + " ms");
        assertTrue(copyMillis < 20_000, "copy took " + copyMillis + " ms");
    }

    @Test
    void aHundredThousandRowLegacyCacheMergesIntoASmallCurrentCacheQuickly(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("nyanslate-ai-cache-zh-tw.json");
        writeBigJournal(legacy, BIG, 0);
        long legacyBytes = Files.size(legacy);
        // 3 000 rows the player's new build collected: 500 of them also exist in the legacy file
        // (with a different wording), 2 500 are new.
        Path current = dir.resolve("nyanlex-ai-cache-zh-tw.json");
        try (BufferedWriter writer = Files.newBufferedWriter(current, StandardCharsets.UTF_8)) {
            writer.write("{\"schema\":4}");
            writer.newLine();
            for (int i = 0; i < 500; i++) {
                writer.write(upsert(bigKey(i * 7), "新版 " + i));
                writer.newLine();
            }
            for (int i = 0; i < 2_500; i++) {
                writer.write(upsert("brand new row " + i, "全新的一列 " + i));
                writer.newLine();
            }
        }
        byte[] currentBefore = Files.readAllBytes(current);

        long started = System.nanoTime();
        assertEquals(1, LegacyDataMigration.migrate(dir, null));
        long mergeMillis = (System.nanoTime() - started) / 1_000_000L;

        long loadStarted = System.nanoTime();
        FileStore merged = new FileStore(current, false, 250_000);
        long loadMillis = (System.nanoTime() - loadStarted) / 1_000_000L;
        assertEquals(BIG + 2_500, merged.size());
        assertEquals("新版 3", merged.get(bigKey(21)), "current wording wins");
        assertEquals(bigValue(22), merged.get(bigKey(22)), "legacy fills the rest");
        assertEquals("全新的一列 2499", merged.get("brand new row 2499"));
        assertArrayEquals(currentBefore, Files.readAllBytes(dir.resolve(current.getFileName() + CacheLogMerger.BACKUP_SUFFIX)));
        assertEquals(legacyBytes, Files.size(legacy));
        System.out.println("[migration] merge " + BIG + " legacy rows (" + legacyBytes / 1_000_000 + " MB) into 3000 current rows: "
                + mergeMillis + " ms; FileStore load of the result: " + loadMillis + " ms");
        assertTrue(mergeMillis < 30_000, "merge took " + mergeMillis + " ms");

        // And the default cap still trims only the oldest (legacy-supplied) rows, never the live ones.
        FileStore capped = new FileStore(current, false, FileStore.DEFAULT_MAX_ENTRIES);
        assertEquals(FileStore.DEFAULT_MAX_ENTRIES, capped.size());
        assertEquals("全新的一列 0", capped.get("brand new row 0"));
        assertEquals("新版 3", capped.get(bigKey(21)));
        assertNull(capped.get(bigKey(1)), "the oldest legacy rows are the ones evicted");
    }

    @Test
    void mergeResultsAreReportedByTheMergerItself(@TempDir Path dir) throws IOException {
        Path legacy = journal(dir.resolve("legacy.json"), 4, upsert("a", "1"), upsert("b", "2"));
        Path current = journal(dir.resolve("current.json"), 4, upsert("b", "x"), upsert("c", "3"));

        CacheLogMerger.Result result = CacheLogMerger.merge(legacy, current);

        assertEquals(CacheLogMerger.Status.MERGED, result.status());
        assertEquals(1, result.added());
        assertEquals(2, result.kept());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a", "1");
        expected.put("b", "x");
        expected.put("c", "3");
        assertEquals(expected, read(current));
    }
}
