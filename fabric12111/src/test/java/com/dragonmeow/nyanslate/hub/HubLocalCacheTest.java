package com.dragonmeow.nyanslate.hub;

import com.dragonmeow.nyanslate.translate.TranslationFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubLocalCacheTest {

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("nyanslate-hub-test");
    }

    private static TranslationFile fileOf(String language, Map<String, String> ai) {
        return new TranslationFile("modern-template-v1", language, "", Map.of(), ai);
    }

    @Test
    void mergeAddsNewRowsAndReportsCounts() throws IOException {
        HubLocalCache cache = new HubLocalCache(tempDir(), "zh-TW");
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Diamond Sword", "鑽石劍");
        rows.put("Ender Pearl", "終界珍珠");

        HubLocalCache.MergeResult result = cache.mergeFromFile(
                fileOf("zh-TW", rows), HubSource.server("hypixel.net"));

        assertEquals(2, result.added());
        assertEquals(0, result.rejectedValidation());
        assertEquals(0, result.alreadyPresent());
        assertEquals("鑽石劍", cache.get("Diamond Sword"));
        assertEquals("終界珍珠", cache.get("Ender Pearl"));
    }

    @Test
    void existingLocalKeyIsNeverOverwritten() throws IOException {
        HubLocalCache cache = new HubLocalCache(tempDir(), "zh-TW");
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Diamond Sword", "鑽石劍")),
                HubSource.server("hypixel.net"));

        HubLocalCache.MergeResult second = cache.mergeFromFile(
                fileOf("zh-TW", Map.of("Diamond Sword", "另一個翻譯")),
                HubSource.modpack("some-pack"));

        assertEquals(0, second.added());
        assertEquals(1, second.alreadyPresent());
        assertEquals("鑽石劍", cache.get("Diamond Sword"), "the first-written value must survive");
    }

    @Test
    void mergingServerThenModpackThenModRealizesThePriorityOrder() throws IOException {
        // "本機已有的 key 永不被覆蓋" + merging in server -> modpack -> mod order together
        // implement the documented server > modpack > mod precedence for a CONFLICTING key.
        HubLocalCache cache = new HubLocalCache(tempDir(), "zh-TW");
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Shared Key", "server wins")),
                HubSource.server("hypixel.net"));
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Shared Key", "modpack loses")),
                HubSource.modpack("some-pack"));
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Shared Key", "mod loses")),
                HubSource.mod("somemod"));

        assertEquals("server wins", cache.get("Shared Key"));
    }

    @Test
    void rowsFailingValidationAreRejected() throws IOException {
        HubLocalCache cache = new HubLocalCache(tempDir(), "zh-TW");

        HubLocalCache.MergeResult result = cache.mergeFromFile(
                fileOf("zh-TW", Map.of("Diamond Sword", "Diamond Sword")), // untranslated echo
                HubSource.server("hypixel.net"));

        assertEquals(0, result.added());
        assertEquals(1, result.rejectedValidation());
        assertNull(cache.get("Diamond Sword"));
    }

    @Test
    void languageMismatchRejectsTheWholeFile() throws IOException {
        HubLocalCache cache = new HubLocalCache(tempDir(), "zh-TW");
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Diamond Sword", "鑽石劍");
        rows.put("Ender Pearl", "終界珍珠");

        HubLocalCache.MergeResult result = cache.mergeFromFile(fileOf("en-us", rows),
                HubSource.server("hypixel.net"));

        assertTrue(result.languageRejected());
        assertEquals(0, result.added());
        assertEquals(0, cache.size());
    }

    @Test
    void persistsAcrossReopen() throws IOException {
        Path dir = tempDir();
        HubLocalCache first = new HubLocalCache(dir, "zh-TW");
        first.mergeFromFile(fileOf("zh-TW", Map.of("Diamond Sword", "鑽石劍")),
                HubSource.server("hypixel.net"));

        HubLocalCache reopened = new HubLocalCache(dir, "zh-TW");

        assertEquals("鑽石劍", reopened.get("Diamond Sword"));
        assertEquals(1, reopened.size());
    }

    @Test
    void clearSourceRemovesOnlyThatSourcesRows() throws IOException {
        HubLocalCache cache = new HubLocalCache(tempDir(), "zh-TW");
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Diamond Sword", "鑽石劍")),
                HubSource.server("hypixel.net"));
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Ender Pearl", "終界珍珠")),
                HubSource.mod("somemod"));

        int removed = cache.clearSource(HubSource.mod("somemod"));

        assertEquals(1, removed);
        assertEquals("鑽石劍", cache.get("Diamond Sword"));
        assertNull(cache.get("Ender Pearl"));
    }

    @Test
    void clearAllRemovesEverything() throws IOException {
        HubLocalCache cache = new HubLocalCache(tempDir(), "zh-TW");
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Diamond Sword", "鑽石劍")),
                HubSource.server("hypixel.net"));

        cache.clearAll();

        assertEquals(0, cache.size());
        assertNull(cache.get("Diamond Sword"));
    }

    @Test
    void switchingLanguageSwapsTheActiveFile() throws IOException {
        Path dir = tempDir();
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        cache.mergeFromFile(fileOf("zh-TW", Map.of("Diamond Sword", "鑽石劍")),
                HubSource.server("hypixel.net"));

        cache.setLanguage("en-US");
        assertNull(cache.get("Diamond Sword"), "a different language file starts empty");

        cache.setLanguage("zh-TW");
        assertEquals("鑽石劍", cache.get("Diamond Sword"), "switching back reloads the saved file");
    }
}
