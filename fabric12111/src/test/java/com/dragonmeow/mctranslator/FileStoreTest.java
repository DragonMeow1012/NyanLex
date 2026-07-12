package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.FileStore;
import com.dragonmeow.mctranslator.cache.LanguageFileStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStoreTest {

    private static Path tempFile() throws IOException {
        Path dir = Files.createTempDirectory("mctranslator-test");
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

        // A reopen reads the compact canonical snapshot; the last value wins.
        FileStore reopened = new FileStore(file, false);
        assertEquals("v2", reopened.get("k"));
    }

    @Test
    void writesHeaderThenExactlyOneTranslationPerPhysicalLine() throws IOException {
        Path file = tempFile();
        FileStore store = new FileStore(file, true);
        store.put("Diamond Sword", "鑽石劍");
        store.put("Mana\nCost", "魔力\n消耗", true);

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), "one header plus one physical line per translation");
        assertEquals(3, JsonParser.parseString(lines.get(0)).getAsJsonObject()
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
        assertEquals("舊譯文", store.get("Old"));
        assertNull(store.get("Two"));
        assertEquals(1, store.size());
        assertTrue(Files.exists(file));
        Path backup = file.resolveSibling(file.getFileName() + ".schema2.bak");
        assertTrue(Files.exists(backup));
        assertEquals(original, Files.readString(backup),
                "migration is authorized only by a byte-for-byte backup of schema 2");
        assertEquals(3, JsonParser.parseString(Files.readAllLines(file).get(0))
                .getAsJsonObject().get("schema").getAsInt());
    }

    @Test
    void oneCorruptJsonLineDoesNotEraseOtherPermanentTranslations() throws IOException {
        Path file = tempFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\":3}\n"
                + "{\"key\":\"Forest\",\"translation\":\"森林\"}\n"
                + "{truncated garbage\n"
                + "{\"key\":\"Village\",\"translation\":\"村莊\"}\n");

        FileStore store = new FileStore(file, false);
        assertEquals("森林", store.get("Forest"));
        assertEquals("村莊", store.get("Village"));
        assertEquals(2, store.size());
        assertTrue(Files.exists(file));
    }

    @Test
    void languageFilesSurviveSwitchesAndClearOnlyAffectsTheActiveLanguage() throws IOException {
        Path dir = Files.createTempDirectory("mctranslator-languages");
        LanguageFileStore store = new LanguageFileStore(dir, "mctranslator-ai-cache", "zh-TW");
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

        assertTrue(Files.exists(dir.resolve("mctranslator-ai-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("mctranslator-ai-cache-zh-cn.json")));
        assertFalse(Files.exists(dir.resolve("mctranslator-ai-cache-ja-jp.json")));
    }

    @Test
    void translationCacheSwitchesFilesWithoutDeletingPreviousLanguage() throws IOException {
        Path dir = Files.createTempDirectory("mctranslator-cache-switch");
        LanguageFileStore store = new LanguageFileStore(dir, "mctranslator-ai-cache", "zh-TW");
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
        Path dir = Files.createTempDirectory("mctranslator-cache-migration");
        Path legacy = dir.resolve("mctranslator-ai-cache.json");
        FileStore old = new FileStore(legacy, false);
        old.put("Forest", "森林");

        LanguageFileStore partitioned =
                new LanguageFileStore(dir, "mctranslator-ai-cache", "zh-TW");
        assertEquals("森林", partitioned.get("Forest"));
        assertTrue(Files.exists(legacy), "legacy file is retained as a safety backup");
        assertTrue(Files.exists(dir.resolve("mctranslator-ai-cache-zh-tw.json")));
    }

    @Test
    void legacyCacheIsClaimedOnlyByTheInitialLanguage() throws IOException {
        Path dir = Files.createTempDirectory("mctranslator-cache-migration-once");
        Path legacy = dir.resolve("mctranslator-ai-cache.json");
        FileStore old = new FileStore(legacy, false);
        old.put("Forest", "森林");

        LanguageFileStore partitioned =
                new LanguageFileStore(dir, "mctranslator-ai-cache", "zh-TW");
        assertEquals("森林", partitioned.get("Forest"));

        partitioned.setLanguage("zh-CN");
        assertNull(partitioned.get("Forest"),
                "a new language must not inherit the initial language's legacy rows");
        assertFalse(Files.exists(dir.resolve("mctranslator-ai-cache-zh-cn.json")),
                "an untouched language stays empty until its first translation is written");
    }
}
