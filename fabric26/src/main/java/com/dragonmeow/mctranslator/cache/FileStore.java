package com.dragonmeow.mctranslator.cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-backed {@link PersistentStore}. Append-only NDJSON log (one
 * {@code {"k":..,"v":..}} object per line), mirrored in memory for fast reads.
 *
 * <p>Per the requested behaviour the cache is <strong>cleared on game restart</strong>:
 * when constructed with {@code clearOnStart = true} (the default in this mod) the
 * existing file is deleted, so each session starts empty and translations never
 * accumulate across sessions. The on-disk log still lets entries that the bounded
 * in-memory LRU evicts <em>during</em> a session be recovered without
 * re-translating.</p>
 *
 * <p>Minecraft-free so it can be unit-tested against a temp directory.</p>
 */
public final class FileStore implements PersistentStore {

    private final Path file;
    private final Map<String, String> mirror = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public FileStore(Path file, boolean clearOnStart) {
        this.file = file;
        if (clearOnStart) {
            deleteQuietly();
        } else {
            load();
        }
    }

    @Override
    public String get(String key) {
        return (key == null) ? null : mirror.get(key);
    }

    @Override
    public void put(String key, String value) {
        if (key == null || value == null) return;
        String previous = mirror.put(key, value);
        if (value.equals(previous)) return; // unchanged: skip the disk write
        append(key, value);
    }

    @Override
    public void putBatch(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return;
        // Update the in-memory mirror and collect only the genuinely-changed entries.
        Map<String, String> changed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String prev = mirror.put(e.getKey(), e.getValue());
            if (!e.getValue().equals(prev)) changed.put(e.getKey(), e.getValue());
        }
        if (changed.isEmpty()) return;
        // One file open/flush/close for the whole batch (not one per entry).
        synchronized (writeLock) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (Map.Entry<String, String> e : changed.entrySet()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("k", e.getKey());
                        obj.addProperty("v", e.getValue());
                        w.write(obj.toString());
                        w.write("\n");
                    }
                }
            } catch (IOException ignored) {
                // best-effort; mirror still holds the entries this session
            }
        }
    }

    @Override
    public void clear() {
        mirror.clear();
        synchronized (writeLock) {
            deleteQuietly();
        }
    }

    /**
     * Drop a single key from the in-memory mirror so {@link #get} no longer returns it
     * this session. The append-only log keeps the old line, but a subsequent re-warm
     * appends the new value (last-wins on next load), so re-translation is honoured.
     */
    @Override
    public void remove(String key) {
        if (key != null) mirror.remove(key);
    }

    public int size() {
        return mirror.size();
    }

    private void append(String key, String value) {
        synchronized (writeLock) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("k", key);
                obj.addProperty("v", value);
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(obj.toString());
                    w.write("\n");
                }
            } catch (IOException ignored) {
                // disk cache is best-effort; in-memory mirror still serves this session
            }
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                    if (obj.has("k") && obj.has("v")) {
                        mirror.put(obj.get("k").getAsString(), obj.get("v").getAsString());
                    }
                } catch (RuntimeException ignored) {
                    // skip a corrupt line, keep the rest
                }
            }
        } catch (IOException ignored) {
            // unreadable cache: start empty
        }
    }

    private void deleteQuietly() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // ignore
        }
    }
}
