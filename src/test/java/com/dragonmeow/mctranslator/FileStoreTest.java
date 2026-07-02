package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.FileStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

        // A reopen replays the append log; the last write must win.
        FileStore reopened = new FileStore(file, false);
        assertEquals("v2", reopened.get("k"));
    }
}
