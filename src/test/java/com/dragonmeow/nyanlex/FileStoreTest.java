package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.FileStore;
import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStoreTest {

    private static Path tempFile() throws IOException {
        Path dir = Files.createTempDirectory("nyanlex-test");
        return dir.resolve("cache.json");
    }

    @Test
    void putThenGetRoundTrips() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("Hello", "你好");
        store.put("Diamond Sword", "鑽石劍");

        assertEquals("你好", store.get("Hello"));
        assertEquals("鑽石劍", store.get("Diamond Sword"));
        assertNull(store.get("missing"));
        assertTrue(Files.exists(file), "entries should have been written to disk");
    }

    @Test
    void clearOnStartWipesPreviousSession() throws IOException {
        Path file = tempFile();
        FileStore first = new FileStore(file, true);
        first.put("Hello", "你好");
        assertTrue(Files.exists(file));

        // "Restart": a new FileStore with clearOnStart=true must start empty.
        FileStore restarted = new FileStore(file, true);
        assertNull(restarted.get("Hello"));
        assertEquals(0, restarted.size());
        assertFalse(Files.exists(file), "clearOnStart should delete the previous cache file");
    }

    @Test
    void loadsPreviousWhenNotClearing() throws IOException {
        Path file = tempFile();
        FileStore first = new FileStore(file, true);
        first.put("Hello", "你好");
        first.put("World", "世界");

        // Open again WITHOUT clearing: previous entries must be loaded from disk.
        FileStore reopened = new FileStore(file, false);
        assertEquals("你好", reopened.get("Hello"));
        assertEquals("世界", reopened.get("World"));
        assertEquals(2, reopened.size());
    }

    @Test
    void clearEmptiesAndDeletesFile() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("Hello", "你好");
        store.clear();
        assertNull(store.get("Hello"));
        assertEquals(0, store.size());
        assertFalse(Files.exists(file));
    }

    @Test
    void overwriteUpdatesValue() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("k", "v1");
        store.put("k", "v2");
        assertEquals("v2", store.get("k"));

        // A reopen replays the journal; the last value wins.
        FileStore reopened = new FileStore(file, false);
        assertEquals("v2", reopened.get("k"));
    }

    @Test
    void oneUpdateToLargeStoreAppendsOnlyOneSmallOperation() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true, 10_000);
        Map<String, String> seed = new LinkedHashMap<>();
        for (int i = 0; i < 5_000; i++) {
            seed.put("key-" + i, "translation-" + i);
        }
        store.putBatch(seed, false);
        long before = Files.size(file);

        store.put("key-2500", "updated");

        long appended = Files.size(file) - before;
        assertTrue(appended > 0L && appended < 512L,
                "a single update must append one small journal row, not rewrite the store");
        assertEquals("updated", new FileStore(file, false, 10_000).get("key-2500"));
    }

    @Test
    void appendFailureFailClosesWithoutRetryingOrRewriting() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("first", "one");

        Path saved = file.resolveSibling("saved-cache.json");
        Files.move(file, saved);
        Files.createDirectory(file);
        Files.writeString(file.resolve("block-replacement"), "keep directory non-empty");
        store.put("during-failure", "two");

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, "sentinel");
        for (int i = 0; i < 20; i++) {
            store.put("while-read-only-" + i, "value-" + i);
        }
        assertEquals("sentinel", Files.readString(temporary),
                "later mutations must not retry a full temporary-file rewrite");

        Files.delete(file.resolve("block-replacement"));
        Files.delete(file);
        Files.move(saved, file);
        long durableSize = Files.size(file);
        store.put("after-recovery", "three");
        assertEquals(durableSize, Files.size(file),
                "the instance remains read-only even after the filesystem recovers");

        FileStore reopened = new FileStore(file, false);
        assertEquals("one", reopened.get("first"));
        assertNull(reopened.get("during-failure"));
        assertNull(reopened.get("after-recovery"));
    }

    @Test
    void removePersistsATombstoneAcrossRestart() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("keep", "value");
        store.put("remove", "old value");
        store.remove("remove");

        List<String> journal = Files.readAllLines(file);
        JsonObject last = JsonParser.parseString(journal.get(journal.size() - 1)).getAsJsonObject();
        assertEquals("remove", last.get("key").getAsString());
        assertTrue(last.get("deleted").getAsBoolean());

        FileStore reopened = new FileStore(file, false);
        assertEquals("value", reopened.get("keep"));
        assertNull(reopened.get("remove"));
        assertEquals(1, reopened.size());
    }

    @Test
    void persistentEntryCapEvictsOldestWriteAndLogsDeletion() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true, 3);
        store.put("a", "A");
        store.put("b", "B");
        store.put("c", "C");
        store.put("d", "D");

        assertEquals(3, store.size());
        assertNull(store.get("a"));
        assertTrue(Files.readAllLines(file).stream()
                .map(JsonParser::parseString)
                .filter(element -> element.isJsonObject())
                .map(element -> element.getAsJsonObject())
                .anyMatch(row -> row.has("deleted")
                        && row.get("deleted").getAsBoolean()
                        && "a".equals(row.get("key").getAsString())));

        FileStore reopened = new FileStore(file, false, 3);
        assertNull(reopened.get("a"));
        assertEquals(Map.of("b", "B", "c", "C", "d", "D"), reopened.entries());
    }

    @Test
    void schema4AppliesCapAfterCompleteLastWinsReplay() throws IOException {
        Path file = tempFile();
        Files.writeString(file, "{\"schema\":4}\n"
                + "{\"key\":\"a\",\"translation\":\"A\"}\n"
                + "{\"key\":\"b\",\"translation\":\"B\"}\n"
                + "{\"key\":\"c\",\"translation\":\"C\"}\n"
                + "{\"key\":\"d\",\"translation\":\"D\"}\n"
                + "{\"key\":\"d\",\"deleted\":true}\n");

        FileStore store = new FileStore(file, false, 3);

        assertEquals(Map.of("a", "A", "b", "B", "c", "C"), store.entries());
        assertNull(store.get("d"));
    }

    @Test
    void largeBatchStaysCappedAndReplaysItsFinalRows() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true, 3);
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 10_000; i++) {
            entries.put("key-" + i, "value-" + i);
        }

        store.putBatch(entries, false);

        assertEquals(3, store.size());
        FileStore reopened = new FileStore(file, false, 3);
        assertEquals(Map.of(
                "key-9997", "value-9997",
                "key-9998", "value-9998",
                "key-9999", "value-9999"), reopened.entries());
    }

    @Test
    void operationBudgetFailClosesWithoutRuntimeCompaction()
            throws IOException, ReflectiveOperationException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("durable", "value");
        long durableSize = Files.size(file);

        Field operations = FileStore.class.getDeclaredField("journalOperations");
        operations.setAccessible(true);
        Field operationBudget = FileStore.class.getDeclaredField("MAX_JOURNAL_OPERATIONS");
        operationBudget.setAccessible(true);
        operations.setLong(store, operationBudget.getLong(null) - 1L);
        store.put("over-budget", "memory only");
        assertEquals(durableSize, Files.size(file));

        // Even if the accounting field changes, fail-closed is permanent for
        // this instance and cannot turn a later mutation into a rewrite.
        operations.setLong(store, 1L);
        store.put("later", "also memory only");
        assertEquals(durableSize, Files.size(file));
        FileStore reopened = new FileStore(file, false);
        assertEquals("value", reopened.get("durable"));
        assertNull(reopened.get("over-budget"));
        assertNull(reopened.get("later"));
    }

    @Test
    void schema3SnapshotIsBackedUpAndMigrated() throws IOException {
        Path file = tempFile();
        String original = "{\"schema\":3}\n"
                + "{\"key\":\"old\",\"translation\":\"first\",\"provisional\":true}\n"
                + "{\"key\":\"old\",\"translation\":\"last\",\"provisional\":false}\n";
        Files.writeString(file, original);

        FileStore store = new FileStore(file, false);

        assertEquals("last", store.get("old"));
        assertFalse(store.isProvisional("old"));
        assertEquals(original, Files.readString(file.resolveSibling(file.getFileName() + ".schema3.bak")));
        assertEquals(4, JsonParser.parseString(Files.readAllLines(file).get(0))
                .getAsJsonObject().get("schema").getAsInt());
    }

    @Test
    void writesHeaderThenExactlyOneTranslationPerPhysicalLine() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("Diamond Sword", "鑽石劍");
        store.put("Mana\nCost", "魔力\n消耗", true);

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), "one header plus one physical line per translation");
        assertEquals(4, JsonParser.parseString(lines.get(0)).getAsJsonObject()
                .get("schema").getAsInt());

        JsonObject first = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        JsonObject second = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertTrue(first.has("key") && first.has("translation"));
        assertTrue(second.has("key") && second.has("translation"));
        assertTrue(first.get("key").getAsString().contains("\n")
                        || second.get("key").getAsString().contains("\n"),
                "embedded newlines must be JSON-escaped instead of splitting an entry");
    }

    @Test
    void provisionalFlagRoundTripsAndFinalOverwriteClearsIt() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("Hello", "GT:你好", true);       // GT stand-in
        assertTrue(store.isProvisional("Hello"));

        // The "g":1 field survives a reopen.
        FileStore reopened = new FileStore(file, false);
        assertEquals("GT:你好", reopened.get("Hello"));
        assertTrue(reopened.isProvisional("Hello"), "the provisional mark must persist");

        // The AI redo overwrites as FINAL — even an identical value must persist the flip.
        reopened.put("Hello", "GT:你好", false);
        assertFalse(reopened.isProvisional("Hello"));
        FileStore again = new FileStore(file, false);
        assertEquals("GT:你好", again.get("Hello"));
        assertFalse(again.isProvisional("Hello"), "the provisional→final flip must persist");
    }

    @Test
    void schema2CacheIsBackedUpAndMigratedWithoutLosingRows() throws IOException {
        Path file = tempFile();
        // Schema 2 was one giant JSON line. Upgrades must preserve its valid rows.
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\":2,\"entries\":["
                + "{\"key\":\"Old\",\"translation\":\"舊譯文\"}]}\n");
        String original = Files.readString(file);

        FileStore store = new FileStore(file, false);
        assertTrue(store.get("Old") != null);
        assertNull(store.get("Two"));
        assertEquals(1, store.size());
        assertTrue(Files.exists(file));
        Path backup = file.resolveSibling(file.getFileName() + ".schema2.bak");
        assertTrue(Files.exists(backup));
        assertEquals(original, Files.readString(backup),
                "migration is authorized only by a byte-for-byte backup of schema 2");
        assertEquals(4, JsonParser.parseString(Files.readAllLines(file).get(0))
                .getAsJsonObject().get("schema").getAsInt());
    }

    @Test
    void mismatchedSchema2BackupBlocksAutomaticMigration() throws IOException {
        Path file = tempFile();
        String original = "{\"schema\":2,\"entries\":["
                + "{\"key\":\"Old\",\"translation\":\"legacy\"}]}\n";
        Files.writeString(file, original);
        Path backup = file.resolveSibling(file.getFileName() + ".schema2.bak");
        Files.writeString(backup, "backup from a different cache\n");

        FileStore store = new FileStore(file, false);
        assertEquals("legacy", store.get("Old"), "valid legacy rows remain usable in memory");
        assertEquals(original, Files.readString(file),
                "a stale backup must not authorize replacing the schema-2 source");

        store.put("New", "translation");
        assertEquals(original, Files.readString(file),
                "later writes remain disk-blocked while no matching backup exists");
        assertEquals("backup from a different cache\n", Files.readString(backup));
    }

    @Test
    void unknownSchemaIsBackedUpAndFailClosed() throws IOException {
        Path file = tempFile();
        Files.createDirectories(file.getParent());
        String original = "{\"schema\":99,\"entries\":[{\"key\":\"Future\",\"translation\":\"keep\"}]}\n";
        Files.writeString(file, original);

        FileStore store = new FileStore(file, false);
        assertEquals(0, store.size());
        assertTrue(Files.exists(file));
        assertEquals(original, Files.readString(file));
        assertEquals(original, Files.readString(
                file.resolveSibling(file.getFileName() + ".unreadable.bak")));

        store.put("New", "translation");
        assertEquals(original, Files.readString(
                file.resolveSibling(file.getFileName() + ".unreadable.bak")));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void partialSchema4JournalIsBackedUpAndRepairedOnceOnNextLoad() throws IOException {
        Path file = tempFile();
        FileStore first = new FileStore(file, true);
        first.put("first", "one");
        Files.writeString(file, "{\"key\":\"partial", StandardOpenOption.APPEND);
        String damaged = Files.readString(file);

        FileStore repaired = new FileStore(file, false);

        assertEquals("one", repaired.get("first"));
        assertEquals(damaged, Files.readString(
                file.resolveSibling(file.getFileName() + ".unreadable.bak")));
        List<String> compacted = Files.readAllLines(file);
        assertEquals(2, compacted.size(), "startup repair keeps one header and one valid row");
        assertEquals(4, JsonParser.parseString(compacted.get(0)).getAsJsonObject()
                .get("schema").getAsInt());

        repaired.put("second", "two");
        FileStore reopened = new FileStore(file, false);
        assertEquals("one", reopened.get("first"));
        assertEquals("two", reopened.get("second"));
    }

    @Test
    void laterDamagedJournalUsesRotatingBackupInsteadOfStaleMismatch() throws IOException {
        Path file = tempFile();
        FileStore first = new FileStore(file, true);
        first.put("stable", "value");
        Files.writeString(file, "{first partial", StandardOpenOption.APPEND);
        String firstIncident = Files.readString(file);
        FileStore firstRepair = new FileStore(file, false);
        assertEquals("value", firstRepair.get("stable"));
        assertEquals(firstIncident, Files.readString(
                file.resolveSibling(file.getFileName() + ".unreadable.bak")));

        Files.writeString(file, "{second partial", StandardOpenOption.APPEND);
        String secondIncident = Files.readString(file);
        FileStore secondRepair = new FileStore(file, false);

        assertEquals("value", secondRepair.get("stable"));
        assertEquals(secondIncident, Files.readString(
                file.resolveSibling(file.getFileName() + ".unreadable.bak.1")));
        secondRepair.put("after-second-repair", "persisted");
        assertEquals("persisted", new FileStore(file, false).get("after-second-repair"));
    }

    @Test
    void failedStartupRepairFailClosesWithoutMutationRetry() throws IOException {
        Path file = tempFile();
        String damaged = "{\"schema\":4}\n"
                + "{\"key\":\"valid\",\"translation\":\"value\",\"provisional\":false}\n"
                + "{\"key\":\"partial";
        Files.writeString(file, damaged);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectory(temporary);
        Files.writeString(temporary.resolve("block-write"), "non-empty");

        FileStore store = new FileStore(file, false);
        assertEquals("value", store.get("valid"));
        assertEquals(damaged, Files.readString(file));
        assertEquals(damaged, Files.readString(
                file.resolveSibling(file.getFileName() + ".unreadable.bak")));

        Files.delete(temporary.resolve("block-write"));
        Files.delete(temporary);
        store.put("after-recovery", "must stay memory-only");
        assertEquals(damaged, Files.readString(file),
                "a failed startup compact must not retry from a normal mutation");
        assertFalse(Files.exists(temporary));
    }

    @Test
    void mismatchedUnreadableBackupBlocksLaterPersistence() throws IOException {
        Path file = tempFile();
        String original = "{\"schema\":99,\"entries\":[]}\n";
        Files.writeString(file, original);
        Path backup = file.resolveSibling(file.getFileName() + ".unreadable.bak");
        Files.writeString(backup, "older unreadable cache\n");

        FileStore store = new FileStore(file, false);
        store.put("New", "translation");

        assertEquals(original, Files.readString(file));
        assertEquals("older unreadable cache\n", Files.readString(backup));
    }

    @Test
    void failedUnreadableBackupBlocksLaterPersistence() throws IOException {
        Path file = tempFile();
        String original = "not a JSON header\n";
        Files.writeString(file, original);
        Path backup = file.resolveSibling(file.getFileName() + ".unreadable.bak");
        Files.createDirectory(backup);
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".unreadable.bak.1"));
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".unreadable.bak.2"));

        FileStore store = new FileStore(file, false);
        store.put("New", "translation");

        assertEquals(original, Files.readString(file),
                "the source must survive when the safety-copy destination cannot be written");
        assertTrue(Files.isDirectory(backup));
    }

    @Test
    void oneCorruptJsonLineDoesNotEraseOtherPermanentTranslations() throws IOException {
        Path file = tempFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\":3}\n"
                + "{\"key\":\"Forest\",\"translation\":\"森林\"}\n"
                + "{truncated garbage\n"
                + "{\"key\":\"Village\",\"translation\":\"村莊\"}\n");
        String original = Files.readString(file);

        FileStore store = new FileStore(file, false);
        assertEquals("森林", store.get("Forest"));
        assertEquals("村莊", store.get("Village"));
        assertEquals(2, store.size());
        assertTrue(Files.exists(file));
        Path backup = file.resolveSibling(file.getFileName() + ".unreadable.bak");
        assertEquals(original, Files.readString(backup),
                "damaged JSONL must be preserved before a later canonical rewrite");

        store.put("New", "translation");
        assertEquals(original, Files.readString(backup));
        FileStore reopened = new FileStore(file, false);
        assertEquals("森林", reopened.get("Forest"));
        assertEquals("村莊", reopened.get("Village"));
        assertEquals("translation", reopened.get("New"));
    }

    @Test
    void languageFilesSurviveSwitchesAndClearOnlyAffectsTheActiveLanguage() throws IOException {
        Path dir = Files.createTempDirectory("nyanlex-languages");
        LanguageFileStore store = new LanguageFileStore(dir, "nyanlex-ai-cache", "zh-TW");
        store.put("Forest", "森林");

        store.setLanguage("zh-CN");
        assertNull(store.get("Forest"), "Simplified Chinese must not read Traditional Chinese");
        store.put("Forest", "森林-简体");

        store.setLanguage("ja-JP");
        store.put("Forest", "森");
        store.clear();
        assertNull(store.get("Forest"), "clear removes only the current language");

        store.setLanguage("zh-TW");
        assertEquals("森林", store.get("Forest"), "switching back reuses the permanent file");
        store.setLanguage("zh-CN");
        assertEquals("森林-简体", store.get("Forest"));

        assertTrue(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanlex-ai-cache-zh-cn.json")));
        assertFalse(Files.exists(dir.resolve("nyanlex-ai-cache-ja-jp.json")));
    }

    @Test
    void switchingManyLanguagesRetainsOnlyTheActiveStoreInMemory() throws IOException {
        Path dir = Files.createTempDirectory("nyanlex-bounded-languages");
        LanguageFileStore store = new LanguageFileStore(dir, "nyanlex-ai-cache", "lang-0");
        for (int i = 0; i < 64; i++) {
            store.setLanguage("lang-" + i);
            store.put("key", "value-" + i);
            assertEquals(1, store.retainedStoreCount());
        }

        store.setLanguage("lang-0");
        assertEquals("value-0", store.get("key"), "released partitions must reload from disk");
        assertEquals(1, store.retainedStoreCount());
    }

    @Test
    void translationCacheSwitchesFilesWithoutDeletingPreviousLanguage() throws IOException {
        Path dir = Files.createTempDirectory("nyanlex-cache-switch");
        LanguageFileStore store = new LanguageFileStore(dir, "nyanlex-ai-cache", "zh-TW");
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache((text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(target + ":" + text, "en");
        }, "zh-TW", Runnable::run, 10, 10_000L, () -> 0L, store);

        assertEquals("zh-TW:Forest", cache.translateBlocking("Forest"));
        cache.setTargetLang("zh-CN");
        assertNull(cache.getCached("Forest"));
        assertEquals("zh-CN:Forest", cache.translateBlocking("Forest"));
        assertEquals(2, calls.get());

        cache.setTargetLang("zh-TW");
        assertEquals("zh-TW:Forest", cache.getCached("Forest"));
        assertEquals(2, calls.get(), "switching back loads the old language file without rebuying");

        cache.clear();
        assertNull(cache.getCached("Forest"));
        cache.setTargetLang("zh-CN");
        assertEquals("zh-CN:Forest", cache.getCached("Forest"),
                "global clear affects only the language that was active when pressed");
    }

    @Test
    void firstLanguageFileCopiesTheLegacyCacheWithoutDeletingIt() throws IOException {
        Path dir = Files.createTempDirectory("nyanlex-cache-migration");
        Path legacy = dir.resolve("nyanlex-ai-cache.json");
        FileStore old = new FileStore(legacy, false);
        old.put("Forest", "森林");

        LanguageFileStore partitioned =
                new LanguageFileStore(dir, "nyanlex-ai-cache", "zh-TW");
        assertEquals("森林", partitioned.get("Forest"));
        assertTrue(Files.exists(legacy), "legacy file is retained as a safety backup");
        assertTrue(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json")));
    }

    @Test
    void legacyCacheIsClaimedOnlyByTheInitialLanguage() throws IOException {
        Path dir = Files.createTempDirectory("nyanlex-cache-migration-once");
        Path legacy = dir.resolve("nyanlex-ai-cache.json");
        FileStore old = new FileStore(legacy, false);
        old.put("Forest", "森林");

        LanguageFileStore partitioned =
                new LanguageFileStore(dir, "nyanlex-ai-cache", "zh-TW");
        assertEquals("森林", partitioned.get("Forest"));

        partitioned.setLanguage("zh-CN");
        assertNull(partitioned.get("Forest"),
                "a new language must not inherit the initial language's legacy rows");
        assertFalse(Files.exists(dir.resolve("nyanlex-ai-cache-zh-cn.json")),
                "an untouched language stays empty until its first translation is written");
    }
}
