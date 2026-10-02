package com.dragonmeow.nyanlex.cache;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ways a persistent cache could end up holding only a handful of rows after a restart even
 * though the file on disk was big. Each of them must leave the player's rows in place.
 */
class FileStoreLoadSafetyTest {
    private static final Gson GSON = new Gson();

    private static String upsert(String key, String value) {
        JsonObject row = new JsonObject();
        row.addProperty("key", key);
        row.addProperty("translation", value);
        row.addProperty("provisional", false);
        return GSON.toJson(row);
    }

    private static void writeRows(Path file, int count) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("{\"schema\":4}");
            writer.newLine();
            for (int i = 0; i < count; i++) {
                writer.write(upsert("source text " + i, "譯文第 " + i + " 條"));
                writer.newLine();
            }
        }
    }

    @Test
    void aSingleBadByteInTheMiddleCostsOnlyItsOwnRow(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("{\"schema\":4}\n".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 1_000; i++) {
            bytes.write((upsert("k" + i, "譯文 " + i) + "\n").getBytes(StandardCharsets.UTF_8));
            if (i == 500) {
                // a row whose key is hit by an invalid byte: lone continuation byte, never valid UTF-8
                bytes.write("{\"key\":\"bad".getBytes(StandardCharsets.UTF_8));
                bytes.write(new byte[] {(byte) 0xFF, (byte) 0xFE});
                bytes.write("\",\"translation\":\"x\"}\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        Files.write(file, bytes.toByteArray());

        FileStore store = new FileStore(file, false, 100_000);

        assertEquals("譯文 0", store.get("k0"));
        assertEquals("譯文 500", store.get("k500"));
        assertEquals("譯文 999", store.get("k999"));
        assertEquals(1_001, store.size(), "every healthy row survives, plus the one with the bad bytes");
    }

    @Test
    void aTornMultiByteCharacterAtTheEndOfTheLogKeepsEveryCompleteRow(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        writeRows(file, 5_000);
        // The process died halfway through appending a row: the file ends inside a character.
        try (var out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.APPEND)) {
            out.write("{\"key\":\"half\",\"translation\":\"".getBytes(StandardCharsets.UTF_8));
            out.write(new byte[] {(byte) 0xE4, (byte) 0xBD});
        }

        FileStore store = new FileStore(file, false, 100_000);

        assertEquals(5_000, store.size());
        assertEquals("譯文第 4999 條", store.get("source text 4999"));
        assertNull(store.get("half"));
        assertTrue(Files.exists(dir.resolve("cache.json.unreadable.bak")), "the damaged original is backed up first");
        // And the repaired file loads again with every row.
        assertEquals(5_000, new FileStore(file, false, 100_000).size());
    }

    @Test
    void aHealthyLargeFileIsLoadedWithoutBeingRewritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        writeRows(file, 30_000);
        byte[] before = Files.readAllBytes(file);
        long modified = Files.getLastModifiedTime(file).toMillis();

        FileStore store = new FileStore(file, false, 100_000);

        assertEquals(30_000, store.size());
        assertArrayEquals(before, Files.readAllBytes(file), "loading never rewrites a healthy file");
        assertEquals(modified, Files.getLastModifiedTime(file).toMillis());
        try (var names = Files.list(dir)) {
            assertEquals(List.of("cache.json"), names.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFileOverTheCapIsTrimmedFromTheOldestEndOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        writeRows(file, 150);

        FileStore store = new FileStore(file, false, 100);

        assertEquals(100, store.size());
        assertNull(store.get("source text 49"));
        assertEquals("譯文第 50 條", store.get("source text 50"));
        assertEquals("譯文第 149 條", store.get("source text 149"));
    }

    @Test
    void writingPastTheCapEvictsExactlyOneRowPerWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        writeRows(file, 100);
        FileStore store = new FileStore(file, false, 100);

        store.put("fresh one", "新一");
        assertEquals(100, store.size());
        assertNull(store.get("source text 0"));
        assertEquals("譯文第 1 條", store.get("source text 1"));
        store.put("fresh two", "新二");
        assertEquals(100, store.size());
        assertNull(store.get("source text 1"));
        assertEquals("譯文第 2 條", store.get("source text 2"));
        // The journal reloads to the same live set.
        FileStore again = new FileStore(file, false, 100);
        assertEquals(100, again.size());
        assertEquals("新二", again.get("fresh two"));
    }

    @Test
    void aStoreOpenedOnAnExistingFileSeesItsRowsBeforeAnyWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        writeRows(file, 1_000);
        FileStore store = new FileStore(file, false, 100_000);
        // The first write after loading must append to the loaded journal, not start a new one.
        store.put("later", "之後");

        FileStore again = new FileStore(file, false, 100_000);
        assertEquals(1_001, again.size());
        assertEquals("譯文第 0 條", again.get("source text 0"));
        assertEquals("之後", again.get("later"));
    }

    @Test
    void onlyAnExplicitClearDeletesTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        writeRows(file, 100);
        FileStore store = new FileStore(file, false, 100_000);
        store.remove("source text 1");
        store.removeBatch(List.of("source text 2", "source text 3"));
        assertTrue(Files.isRegularFile(file));
        assertEquals(97, new FileStore(file, false, 100_000).size());

        store.clear();
        assertFalse(Files.exists(file));
        // After a clear the next write starts a fresh journal.
        store.put("x", "y");
        assertEquals(Map.of("x", "y"), new FileStore(file, false, 100_000).entries());
    }

    @Test
    void provisionalRowsMovedToTheSiblingStoreDoNotShrinkTheRest(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cache.json");
        List<String> lines = new ArrayList<>();
        lines.add("{\"schema\":4}");
        for (int i = 0; i < 200; i++) lines.add(upsert("final " + i, "定稿 " + i));
        JsonObject provisional = new JsonObject();
        provisional.addProperty("key", "stand-in");
        provisional.addProperty("translation", "暫代");
        provisional.addProperty("provisional", true);
        lines.add(GSON.toJson(provisional));
        Files.write(file, lines, StandardCharsets.UTF_8);
        FileStore store = new FileStore(file, false, 100_000);
        FileStore sibling = new FileStore(dir.resolve("sibling.json"), false, 100_000);

        // What TranslationCache#migrateProvisionalRows does on wiring.
        Map<String, String> moved = store.provisionalEntries();
        sibling.putBatch(moved, java.util.Set.of());
        store.removeBatch(moved.keySet());

        assertEquals(Map.of("stand-in", "暫代"), moved);
        assertEquals(200, new FileStore(file, false, 100_000).size());
        assertEquals(1, new FileStore(dir.resolve("sibling.json"), false, 100_000).size());
    }
}
