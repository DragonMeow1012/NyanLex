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
    void legacyLinesWithoutTheProvisionalFieldLoadAsFinal() throws IOException {
        Path file = tempFile();
        // A pre-R9 cache file: plain {"k","v"} lines, no "g" field anywhere.
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"k\":\"Old\",\"v\":\"舊譯文\"}\n{\"k\":\"Two\",\"v\":\"二\"}\n");

        FileStore store = new FileStore(file, false);
        assertEquals("舊譯文", store.get("Old"));
        assertEquals("二", store.get("Two"));
        assertFalse(store.isProvisional("Old"), "a legacy line loads as FINAL");
        assertFalse(store.isProvisional("Two"));
    }
}
