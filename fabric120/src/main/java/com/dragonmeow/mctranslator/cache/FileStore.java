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
    /** Keys whose stored value is PROVISIONAL (a GT stand-in awaiting an AI redo). On disk
     *  this is the optional {@code "g":1} field; absent = final, so old files load as-is. */
    private final java.util.Set<String> provisionalKeys = ConcurrentHashMap.newKeySet();
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
        put(key, value, false);
    }

    @Override
    public void put(String key, String value, boolean provisional) {
        if (key == null || value == null) return;
        String previous = mirror.put(key, value);
        boolean wasProvisional = provisional ? !provisionalKeys.add(key) : provisionalKeys.remove(key);
        // Skip the disk write only when BOTH the value and the flag are unchanged —
        // a provisional→final transition with identical text must still be persisted.
        if (value.equals(previous) && provisional == wasProvisional) return;
        append(key, value, provisional);
    }

    @Override
    public boolean isProvisional(String key) {
        return key != null && provisionalKeys.contains(key);
    }

    @Override
    public void putBatch(Map<String, String> entries) {
        putBatch(entries, java.util.Set.of());
    }

    @Override
    public void putBatch(Map<String, String> entries, java.util.Set<String> provisional) {
        if (entries == null || entries.isEmpty()) return;
        java.util.Set<String> prov = (provisional == null) ? java.util.Set.of() : provisional;
        // Update the in-memory mirror and collect only the genuinely-changed entries.
        Map<String, String> changed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            boolean p = prov.contains(e.getKey());
            String prevValue = mirror.put(e.getKey(), e.getValue());
            boolean wasProvisional = p ? !provisionalKeys.add(e.getKey()) : provisionalKeys.remove(e.getKey());
            if (!e.getValue().equals(prevValue) || p != wasProvisional) changed.put(e.getKey(), e.getValue());
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
                        w.write(line(e.getKey(), e.getValue(), prov.contains(e.getKey())));
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
        provisionalKeys.clear();
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
        if (key != null) {
            mirror.remove(key);
            provisionalKeys.remove(key);
        }
    }

    public int size() {
        return mirror.size();
    }

    /** One NDJSON line; the {@code "g":1} field is emitted ONLY for provisional entries,
     *  so files written by this build stay readable by older code (unknown field ignored). */
    private static String line(String key, String value, boolean provisional) {
        JsonObject obj = new JsonObject();
        obj.addProperty("k", key);
        obj.addProperty("v", value);
        if (provisional) obj.addProperty("g", 1);
        return obj.toString();
    }

    private void append(String key, String value, boolean provisional) {
        synchronized (writeLock) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line(key, value, provisional));
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
                        String key = obj.get("k").getAsString();
                        mirror.put(key, obj.get("v").getAsString());
                        // Optional "g":1 marks a provisional (GT stand-in) entry; absent
                        // (every pre-R9 line) = final. Last line wins, like the value.
                        if (obj.has("g") && obj.get("g").getAsInt() == 1) provisionalKeys.add(key);
                        else provisionalKeys.remove(key);
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
