package com.dragonmeow.mctranslator.cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned JSON-Lines disk cache.
 *
 * <p>Schema 3 stores a header on the first physical line and exactly one translation
 * on each following line. It remains a canonical snapshot rather than an append log,
 * so a key exists at most once on disk. Updates are written to a sibling temporary
 * file and atomically replaced where the filesystem supports it. Schema 2 is migrated
 * losslessly. Unknown or unreadable files are backed up instead of deleted.</p>
 */
public final class FileStore implements PersistentStore {
    private static final int SCHEMA = 3;
    private static final int LEGACY_SCHEMA = 2;

    private final Path file;
    private final Path temporary;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Set<String> provisional = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private String requiredBackupSuffix;

    public FileStore(Path file, boolean clearOnStart) {
        this.file = file;
        this.temporary = file.resolveSibling(file.getFileName() + ".tmp");
        if (clearOnStart) clear();
        else load();
    }

    @Override
    public String get(String key) {
        return key == null ? null : values.get(key);
    }

    @Override
    public void put(String key, String value) {
        put(key, value, false);
    }

    @Override
    public void put(String key, String value, boolean isProvisional) {
        if (key == null || value == null) return;
        synchronized (lock) {
            String previous = values.put(key, value);
            boolean previousState = provisional.contains(key);
            if (isProvisional) provisional.add(key);
            else provisional.remove(key);
            if (!value.equals(previous) || previousState != isProvisional) persist();
        }
    }

    @Override
    public boolean isProvisional(String key) {
        return key != null && provisional.contains(key);
    }

    @Override
    public void putBatch(Map<String, String> entries, Set<String> provisionalKeys) {
        if (entries == null || entries.isEmpty()) return;
        Set<String> states = provisionalKeys == null ? Set.of() : provisionalKeys;
        synchronized (lock) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                String previous = values.put(entry.getKey(), entry.getValue());
                boolean oldState = provisional.contains(entry.getKey());
                boolean newState = states.contains(entry.getKey());
                if (newState) provisional.add(entry.getKey());
                else provisional.remove(entry.getKey());
                changed |= !entry.getValue().equals(previous) || oldState != newState;
            }
            if (changed) persist();
        }
    }

    @Override
    public void remove(String key) {
        if (key == null) return;
        synchronized (lock) {
            boolean changed = values.remove(key) != null;
            changed |= provisional.remove(key);
            if (changed) persist();
        }
    }

    @Override
    public void removeBatch(java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        synchronized (lock) {
            boolean changed = false;
            for (String key : keys) {
                if (key == null) continue;
                changed |= values.remove(key) != null;
                changed |= provisional.remove(key);
            }
            if (changed) persist();
        }
    }

    @Override
    public Map<String, String> provisionalEntries() {
        Map<String, String> out = new LinkedHashMap<>();
        synchronized (lock) {
            for (String key : provisional) {
                String value = values.get(key);
                if (value != null) out.put(key, value);
            }
        }
        return out;
    }

    @Override
    public Map<String, String> entries() {
        synchronized (lock) {
            return new LinkedHashMap<>(values);
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            values.clear();
            provisional.clear();
            requiredBackupSuffix = null;
            delete(file);
            delete(temporary);
        }
    }

    public int size() {
        return values.size();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        synchronized (lock) {
            boolean migrateAfterClose = false;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String headerLine = reader.readLine();
                if (headerLine == null || headerLine.isBlank()) {
                    preserveUnreadableFile();
                    return;
                }
                JsonObject header = new JsonParser().parse(headerLine).getAsJsonObject();
                if (!header.has("schema")) {
                    preserveUnreadableFile();
                    return;
                }

                Map<String, String> loaded = new LinkedHashMap<>();
                Set<String> loadedProvisional = ConcurrentHashMap.newKeySet();
                int schema = header.get("schema").getAsInt();
                boolean migrated = schema == LEGACY_SCHEMA;
                boolean damaged = false;
                if (migrated) {
                    if (!header.has("entries") || !header.get("entries").isJsonArray()) {
                        preserveUnreadableFile();
                        return;
                    }
                    for (var element : header.getAsJsonArray("entries")) {
                        if (element.isJsonObject()) {
                            readEntry(element.getAsJsonObject(), loaded, loadedProvisional);
                        }
                    }
                } else if (schema == SCHEMA) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        try {
                            damaged |= !readEntry(new JsonParser().parse(line).getAsJsonObject(),
                                    loaded, loadedProvisional);
                        } catch (RuntimeException ignored) {
                            damaged = true;
                        }
                    }
                } else {
                    preserveUnreadableFile();
                    return;
                }
                values.clear();
                values.putAll(loaded);
                provisional.clear();
                provisional.addAll(loadedProvisional);
                if (migrated) {
                    requireBackup(".schema2.bak");
                    migrateAfterClose = true;
                } else if (damaged) {
                    requireBackup(".unreadable.bak");
                }
            } catch (IOException | RuntimeException ignored) {
                preserveUnreadableFile();
                return;
            }
            if (migrateAfterClose) persist();
        }
    }

    private static boolean readEntry(JsonObject entry, Map<String, String> loaded,
                                     Set<String> loadedProvisional) {
        if (!entry.has("key") || !entry.has("translation")) return false;
        String key = entry.get("key").getAsString();
        loaded.put(key, entry.get("translation").getAsString());
        if (entry.has("provisional") && entry.get("provisional").getAsBoolean()) {
            loadedProvisional.add(key);
        }
        return true;
    }

    private void preserveUnreadableFile() {
        requireBackup(".unreadable.bak");
    }

    private void requireBackup(String suffix) {
        requiredBackupSuffix = suffix;
        ensureRequiredBackup();
    }

    private boolean ensureRequiredBackup() {
        if (requiredBackupSuffix == null) return true;
        if (!Files.exists(file)) return true;
        if (!Files.isRegularFile(file)) return false;

        Path backup = file.resolveSibling(file.getFileName() + requiredBackupSuffix);
        boolean backupExisted = Files.exists(backup);
        try {
            if (backupExisted) {
                return Files.isRegularFile(backup) && Files.mismatch(file, backup) == -1L;
            }
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            if (Files.isRegularFile(backup) && Files.mismatch(file, backup) == -1L) {
                return true;
            }
        } catch (IOException ignored) {
        }
        if (!backupExisted) delete(backup);
        return false;
    }

    private void persist() {
        if (!ensureRequiredBackup()) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                JsonObject header = new JsonObject();
                header.addProperty("schema", SCHEMA);
                writer.write(header.toString());
                writer.newLine();

                for (Map.Entry<String, String> entry : values.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    JsonObject json = new JsonObject();
                    json.addProperty("key", entry.getKey());
                    json.addProperty("translation", entry.getValue());
                    if (provisional.contains(entry.getKey())) json.addProperty("provisional", true);
                    writer.write(json.toString());
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            requiredBackupSuffix = null;
        } catch (IOException ignored) {
            delete(temporary); // memory remains authoritative for this session
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cache cleanup.
        }
    }
}
