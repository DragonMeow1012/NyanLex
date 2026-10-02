package com.dragonmeow.nyanlex.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HubPackCleanerTest {

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("nyanlex-pack-clear-test");
    }

    private static HubFile fileOf(String language, Map<String, String> rows) {
        Map<String, String> hashed = new LinkedHashMap<>();
        rows.forEach((key, value) -> hashed.put(HubKeyHash.of(key), value));
        return new HubFile(language, hashed);
    }

    @Test
    void clearRemovesDownloadedRowsAndForgetsTheDownloadLedger() throws IOException {
        Path dir = tempDir();
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubSource server = HubSource.server("example.net");
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Diamond Sword", "鑽石劍");
        rows.put("Ender Pearl", "終界珍珠");
        cache.mergeFromFile(fileOf("zh-TW", rows), server);
        state.record(server, "zh-TW", "sha-1");
        state.record(server, "en-US", "sha-en");

        assertEquals(2, HubPackCleaner.downloadedCount(cache));
        assertEquals(2, HubPackCleaner.clear(cache, state));

        assertEquals(0, cache.size());
        assertNull(cache.get("Diamond Sword"));
        assertNull(state.sha256(server, "zh-TW"), "the next detect must see the files as downloadable again");
        assertEquals("sha-en", state.sha256(server, "en-US"), "another language is untouched");
    }

    @Test
    void clearIsHarmlessWhenNothingWasDownloaded() throws IOException {
        Path dir = tempDir();
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        assertEquals(0, HubPackCleaner.downloadedCount(cache));
        assertEquals(0, HubPackCleaner.clear(cache, new HubDownloadState(dir.resolve("state.json"))));
        assertEquals(0, HubPackCleaner.clear(null, null));
        assertEquals(0, HubPackCleaner.downloadedCount(null));
    }
}
