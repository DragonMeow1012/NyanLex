package com.dragonmeow.nyanlex.cache;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-backed translation store.
 *
 * <p>Schema 4 is an append-only operation log. Ordinary mutations append one
 * upsert or tombstone instead of rewriting the complete cache. A compacted
 * file is still a valid schema-4 log: it contains one upsert for each live
 * entry. Old snapshot schemas are backed up once and migrated on load. This is
 * a best-effort cache, not a transactional database: batches may leave a
 * partial final row after a crash, and no cross-process lock or fsync guarantee
 * is provided. Startup replay isolates and repairs such tails safely.</p>
 */
public final class FileStore implements PersistentStore {
    private static final Gson GSON = new Gson();
    private static final int SCHEMA = 4;
    private static final int SNAPSHOT_SCHEMA = 3;
    private static final int LEGACY_SCHEMA = 2;
    private static final int MIN_COMPACTION_OPERATIONS = 65_536;
    private static final int COMPACTION_MULTIPLIER = 4;
    private static final int MAX_APPEND_BATCH_OPERATIONS = 1_024;
    private static final long MAX_JOURNAL_OPERATIONS = 500_000L;
    private static final long MAX_JOURNAL_BYTES = 256L * 1024L * 1024L;
    private static final long STARTUP_COMPACT_OPERATIONS = 400_000L;
    private static final long STARTUP_COMPACT_BYTES = 192L * 1024L * 1024L;
    private static final int MAX_UNREADABLE_BACKUPS = 3;

    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final Path file;
    private final Path temporaryFile;
    private final int maxEntries;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Set<String> provisional = ConcurrentHashMap.newKeySet();
    /** Write order is mutated only while holding {@link #lock}. */
    private final Set<String> writeOrder = new LinkedHashSet<>();
    private final Object lock = new Object();

    private long journalOperations;
    private long journalBytes;
    private boolean readOnly;

    public FileStore(Path file, boolean clearOnStart) {
        this(file, clearOnStart, DEFAULT_MAX_ENTRIES);
    }

    public FileStore(Path file, boolean clearOnStart, int maxEntries) {
        this.file = file;
        this.temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        if (clearOnStart) {
            clear();
        } else {
            load();
        }
    }

    @Override
    public String get(String key) {
        return key == null ? null : values.get(key);
    }

    @Override
    public void put(String key, String translation) {
        put(key, translation, false);
    }

    @Override
    public void put(String key, String translation, boolean isProvisional) {
        if (key == null || translation == null) {
            return;
        }
        synchronized (lock) {
            String previous = values.get(key);
            boolean previousProvisional = provisional.contains(key);
            if (translation.equals(previous) && previousProvisional == isProvisional) {
                return;
            }

            values.put(key, translation);
            writeOrder.remove(key);
            writeOrder.add(key);
            if (isProvisional) {
                provisional.add(key);
            } else {
                provisional.remove(key);
            }

            List<JournalOperation> operations = new ArrayList<>(2);
            operations.add(JournalOperation.upsert(key, translation, isProvisional));
            evictOverflow(operations);
            append(operations);
        }
    }

    @Override
    public boolean isProvisional(String key) {
        return key != null && provisional.contains(key);
    }

    public void putBatch(Map<String, String> entries, boolean isProvisional) {
        putBatch(entries, isProvisional && entries != null ? entries.keySet() : Set.of());
    }

    @Override
    public void putBatch(Map<String, String> entries, Set<String> provisionalKeys) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Set<String> states = provisionalKeys == null ? Set.of() : provisionalKeys;
        synchronized (lock) {
            List<JournalOperation> operations = new ArrayList<>(
                    Math.min(entries.size(), MAX_APPEND_BATCH_OPERATIONS + 1));
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = entry.getKey();
                String translation = entry.getValue();
                if (key == null || translation == null) {
                    continue;
                }

                String previous = values.get(key);
                boolean previousProvisional = provisional.contains(key);
                boolean isProvisional = states.contains(key);
                if (translation.equals(previous) && previousProvisional == isProvisional) {
                    continue;
                }

                values.put(key, translation);
                writeOrder.remove(key);
                writeOrder.add(key);
                if (isProvisional) {
                    provisional.add(key);
                } else {
                    provisional.remove(key);
                }
                operations.add(JournalOperation.upsert(key, translation, isProvisional));
                evictOverflow(operations);
                if (operations.size() >= MAX_APPEND_BATCH_OPERATIONS) {
                    append(operations);
                    operations.clear();
                }
            }
            if (!operations.isEmpty()) {
                append(operations);
            }
        }
    }

    @Override
    public void remove(String key) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            boolean changed = values.remove(key) != null;
            changed |= provisional.remove(key);
            writeOrder.remove(key);
            if (!changed) {
                return;
            }
            append(List.of(JournalOperation.delete(key)));
        }
    }

    @Override
    public void removeBatch(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        synchronized (lock) {
            List<JournalOperation> operations = new ArrayList<>();
            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                boolean changed = values.remove(key) != null;
                changed |= provisional.remove(key);
                writeOrder.remove(key);
                if (changed) {
                    operations.add(JournalOperation.delete(key));
                }
            }
            if (operations.isEmpty()) {
                return;
            }
            append(operations);
        }
    }

    @Override
    public Map<String, String> entries() {
        synchronized (lock) {
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : writeOrder) {
                String value = values.get(key);
                if (value != null) {
                    result.put(key, value);
                }
            }
            return result;
        }
    }

    @Override
    public Map<String, String> provisionalEntries() {
        synchronized (lock) {
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : writeOrder) {
                String value = values.get(key);
                if (value != null && provisional.contains(key)) {
                    result.put(key, value);
                }
            }
            return result;
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            values.clear();
            provisional.clear();
            writeOrder.clear();
            journalOperations = 0L;
            journalBytes = 0L;
            boolean removed = delete(file);
            delete(temporaryFile);
            readOnly = !removed;
        }
    }

    @Override
    public int size() {
        return values.size();
    }

    private void load() {
        synchronized (lock) {
            if (!Files.isRegularFile(file)) {
                return;
            }
            try {
                journalBytes = Files.size(file);
            } catch (IOException | SecurityException ignored) {
                readOnly = true;
                return;
            }

            Map<String, String> loadedValues = new LinkedHashMap<>();
            Set<String> loadedProvisional = new HashSet<>();
            int schema = -1;
            long loadedOperations = 0L;
            boolean damaged = false;
            boolean trimmedDuringLoad = false;
            Set<String> retainedSchema4Keys = null;

            boolean ioFailure = false;
            try (BufferedReader reader = openLenient(file)) {
                String headerLine = reader.readLine();
                if (headerLine == null || headerLine.isBlank()) {
                    damaged = true;
                } else {
                    JsonObject header = JsonParser.parseString(headerLine).getAsJsonObject();
                    schema = header.has("schema") ? header.get("schema").getAsInt() : -1;

                    if (schema == LEGACY_SCHEMA) {
                        JsonArray entries = header.has("entries") && header.get("entries").isJsonArray()
                                ? header.getAsJsonArray("entries")
                                : new JsonArray();
                        for (JsonElement element : entries) {
                            if (!element.isJsonObject()
                                    || !applyUpsert(element.getAsJsonObject(), loadedValues, loadedProvisional)) {
                                damaged = true;
                            } else {
                                loadedOperations++;
                                trimmedDuringLoad |= trimLoadedValues(loadedValues, loadedProvisional);
                            }
                        }
                    } else if (schema == SNAPSHOT_SCHEMA) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            try {
                                JsonObject operation = JsonParser.parseString(line).getAsJsonObject();
                                boolean applied = applyUpsert(
                                        operation, loadedValues, loadedProvisional);
                                if (applied) {
                                    loadedOperations++;
                                    trimmedDuringLoad |= trimLoadedValues(loadedValues, loadedProvisional);
                                } else {
                                    damaged = true;
                                }
                            } catch (RuntimeException exception) {
                                damaged = true;
                            }
                        }
                    } else if (schema == SCHEMA) {
                        // First pass stores only live-key/write-order metadata,
                        // not every translation. Cap is applied after complete
                        // last-wins replay so a later tombstone cannot wrongly
                        // evict an older row that should survive.
                        Set<String> liveKeys = new LinkedHashSet<>();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            try {
                                JsonObject operation = JsonParser.parseString(line).getAsJsonObject();
                                if (applyOperationMetadata(operation, liveKeys)) {
                                    loadedOperations++;
                                } else {
                                    damaged = true;
                                }
                            } catch (RuntimeException exception) {
                                damaged = true;
                            }
                        }
                        while (liveKeys.size() > maxEntries) {
                            liveKeys.remove(liveKeys.iterator().next());
                            trimmedDuringLoad = true;
                        }
                        retainedSchema4Keys = new LinkedHashSet<>(liveKeys);
                    } else {
                        damaged = true;
                    }
                }
            } catch (IOException exception) {
                // Real I/O trouble (not bad content): what was read is only part of the file,
                // so nothing may be rewritten from it. See the ioFailure branch below.
                ioFailure = true;
                damaged = true;
            } catch (RuntimeException exception) {
                damaged = true;
            }

            if (ioFailure) {
                // Fail closed: keep the file exactly as it is and run memory-only this session,
                // rather than compacting a partial read over the player's whole cache.
                readOnly = true;
                return;
            }

            if (schema == SCHEMA && retainedSchema4Keys != null
                    && !retainedSchema4Keys.isEmpty()) {
                damaged |= !loadRetainedSchema4Values(
                        retainedSchema4Keys, loadedValues, loadedProvisional);
            }

            values.clear();
            values.putAll(loadedValues);
            provisional.clear();
            provisional.addAll(loadedProvisional);
            writeOrder.clear();
            writeOrder.addAll(loadedValues.keySet());
            journalOperations = loadedOperations;

            String backupSuffix = null;
            if (schema == LEGACY_SCHEMA) {
                backupSuffix = ".schema2.bak";
            } else if (schema == SNAPSHOT_SCHEMA && !damaged) {
                backupSuffix = ".schema3.bak";
            } else if (schema != SCHEMA || damaged) {
                backupSuffix = ".unreadable.bak";
            }

            if (backupSuffix != null) {
                if (!createOrVerifyBackup(backupSuffix)) {
                    readOnly = true;
                    return;
                }
                if (schema != LEGACY_SCHEMA && schema != SNAPSHOT_SCHEMA && schema != SCHEMA) {
                    // A future schema is not corruption. Preserve its verified
                    // backup and fail closed rather than downgrading it.
                    readOnly = true;
                    return;
                }
                // Migration and damaged-log repair are startup-only full
                // rewrites. compact() fail-closes this instance on any error.
                compact();
                return;
            }

            // Routine compaction belongs to partition startup. A render-thread
            // mutation must never cross a size threshold and suddenly rewrite the
            // whole cache; normal runtime writes stay one-record appends.
            if (trimmedDuringLoad
                    || shouldCompactOnLoad(journalOperations, journalBytes, values.size())) {
                compact();
            }
        }
    }

    /**
     * UTF-8 reader that substitutes U+FFFD for bytes it cannot decode instead of throwing.
     * A strict reader throws {@code MalformedInputException} at the first bad byte (a torn
     * multi-byte character at the end of a log cut short by a crash, one byte damaged by a
     * disk or an editor), which used to abort the whole load and have the damaged-file repair
     * compact an EMPTY store over a cache of 100 000 rows. Now only the damaged row is
     * affected; the repair still backs the file up first.
     */
    private static BufferedReader openLenient(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8),
                1 << 16);
    }

    private static boolean applyOperation(JsonObject object, Map<String, String> target,
            Set<String> targetProvisional) {
        if (object == null || !object.has("key") || object.get("key").isJsonNull()) {
            return false;
        }
        String key = object.get("key").getAsString();
        if (object.has("deleted") && object.get("deleted").getAsBoolean()) {
            target.remove(key);
            targetProvisional.remove(key);
            return true;
        }
        return applyUpsert(object, target, targetProvisional);
    }

    private static boolean applyOperationMetadata(JsonObject object, Set<String> liveKeys) {
        if (object == null || !object.has("key") || object.get("key").isJsonNull()) {
            return false;
        }
        String key = object.get("key").getAsString();
        if (object.has("deleted") && object.get("deleted").getAsBoolean()) {
            liveKeys.remove(key);
            return true;
        }
        if (!object.has("translation") || object.get("translation").isJsonNull()) {
            return false;
        }
        // Validate the same scalar fields the value replay will consume while
        // deliberately discarding the potentially large translation string.
        object.get("translation").getAsString();
        if (object.has("provisional")) {
            object.get("provisional").getAsBoolean();
        }
        liveKeys.remove(key);
        liveKeys.add(key);
        return true;
    }

    private boolean loadRetainedSchema4Values(Set<String> retainedKeys,
            Map<String, String> target, Set<String> targetProvisional) {
        try (BufferedReader reader = openLenient(file)) {
            if (reader.readLine() == null) {
                return false;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject operation = JsonParser.parseString(line).getAsJsonObject();
                    if (operation.has("key") && !operation.get("key").isJsonNull()
                            && retainedKeys.contains(operation.get("key").getAsString())) {
                        applyOperation(operation, target, targetProvisional);
                    }
                } catch (RuntimeException ignored) {
                    // The first pass already marks malformed rows as damaged.
                }
            }
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean applyUpsert(JsonObject object, Map<String, String> target,
            Set<String> targetProvisional) {
        if (object == null
                || !object.has("key")
                || object.get("key").isJsonNull()
                || !object.has("translation")
                || object.get("translation").isJsonNull()) {
            return false;
        }
        String key = object.get("key").getAsString();
        String translation = object.get("translation").getAsString();
        boolean isProvisional = object.has("provisional") && object.get("provisional").getAsBoolean();
        target.remove(key);
        target.put(key, translation);
        if (isProvisional) {
            targetProvisional.add(key);
        } else {
            targetProvisional.remove(key);
        }
        return true;
    }

    private void evictOverflow(List<JournalOperation> operations) {
        while (values.size() > maxEntries) {
            String eldest = writeOrder.iterator().next();
            writeOrder.remove(eldest);
            values.remove(eldest);
            provisional.remove(eldest);
            operations.add(JournalOperation.delete(eldest));
        }
    }

    private boolean trimLoadedValues(Map<String, String> loadedValues, Set<String> loadedProvisional) {
        boolean trimmed = false;
        while (loadedValues.size() > maxEntries) {
            String eldest = loadedValues.keySet().iterator().next();
            loadedValues.remove(eldest);
            loadedProvisional.remove(eldest);
            trimmed = true;
        }
        return trimmed;
    }

    private void append(List<JournalOperation> operations) {
        if (operations.isEmpty() || readOnly) {
            return;
        }
        List<String> encoded = new ArrayList<>(operations.size());
        long appendedBytes = 0L;
        int newlineBytes = System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
        for (JournalOperation operation : operations) {
            String line = GSON.toJson(operation.toJson());
            encoded.add(line);
            appendedBytes += line.getBytes(StandardCharsets.UTF_8).length + newlineBytes;
        }
        if (journalOperations + operations.size() >= MAX_JOURNAL_OPERATIONS
                || journalBytes + appendedBytes >= MAX_JOURNAL_BYTES) {
            // Cache persistence is best-effort. A healthy but very long
            // session stops writing at a hard bound and lets next startup
            // compact, never the mutation/render thread.
            readOnly = true;
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
                if (journalOperations > 0L) {
                    // The journal disappeared or changed type underneath a
                    // live store. Appending only the latest mutation would
                    // lose older rows after restart, so fail closed.
                    readOnly = true;
                    return;
                }
                try (BufferedWriter writer = Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    JsonObject header = new JsonObject();
                    header.addProperty("schema", SCHEMA);
                    writer.write(GSON.toJson(header));
                    writer.newLine();
                    for (String line : encoded) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
                journalOperations = operations.size();
                journalBytes = Files.size(file);
                return;
            }
            try (BufferedWriter writer = Files.newBufferedWriter(
                    file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                for (String line : encoded) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            journalOperations += operations.size();
            journalBytes += appendedBytes;
        } catch (IOException | SecurityException ignored) {
            // A failed append may be partial. Never retry or rewrite the full
            // store on a later render-thread mutation; the next process load
            // can back up and repair a damaged tail once.
            readOnly = true;
        }
    }

    private static boolean shouldCompactOnLoad(long operations, long bytes, int liveEntries) {
        if (operations >= STARTUP_COMPACT_OPERATIONS || bytes >= STARTUP_COMPACT_BYTES) {
            return true;
        }
        if (operations < MIN_COMPACTION_OPERATIONS) {
            return false;
        }
        long usefulOperations = Math.max(1L, liveEntries);
        return operations > usefulOperations * COMPACTION_MULTIPLIER;
    }

    private boolean compact() {
        if (readOnly) {
            return false;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporaryFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                JsonObject header = new JsonObject();
                header.addProperty("schema", SCHEMA);
                writer.write(GSON.toJson(header));
                writer.newLine();
                for (String key : writeOrder) {
                    String value = values.get(key);
                    if (value == null) {
                        continue;
                    }
                    writer.write(GSON.toJson(JournalOperation.upsert(
                            key,
                            value,
                            provisional.contains(key)).toJson()));
                    writer.newLine();
                }
            }
            try {
                Files.move(
                        temporaryFile,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            journalOperations = values.size();
            journalBytes = Files.size(file);
            if (journalOperations >= MAX_JOURNAL_OPERATIONS
                    || journalBytes >= MAX_JOURNAL_BYTES) {
                readOnly = true;
            }
            return true;
        } catch (IOException | SecurityException ignored) {
            readOnly = true;
            delete(temporaryFile);
            return false;
        }
    }

    private boolean createOrVerifyBackup(String suffix) {
        boolean rotate = ".unreadable.bak".equals(suffix);
        List<Path> candidates = new ArrayList<>(rotate ? MAX_UNREADABLE_BACKUPS : 1);
        Path base = file.resolveSibling(file.getFileName() + suffix);
        candidates.add(base);
        if (rotate) {
            for (int index = 1; index < MAX_UNREADABLE_BACKUPS; index++) {
                candidates.add(file.resolveSibling(file.getFileName() + suffix + "." + index));
            }
        }

        try {
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate) && Files.mismatch(file, candidate) == -1L) {
                    return true;
                }
            }
            if (!rotate && Files.exists(base)) {
                return false;
            }

            Path target = selectBackupTarget(candidates);
            if (target == null) {
                return false;
            }
            return writeBackupAtomically(target);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static Path selectBackupTarget(List<Path> candidates) throws IOException {
        Path oldest = null;
        long oldestModified = Long.MAX_VALUE;
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                return candidate;
            }
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            long modified = Files.getLastModifiedTime(candidate).toMillis();
            if (modified < oldestModified) {
                oldest = candidate;
                oldestModified = modified;
            }
        }
        return oldest;
    }

    private boolean writeBackupAtomically(Path backup) {
        Path parent = backup.toAbsolutePath().getParent();
        Path temporaryBackup = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporaryBackup = Files.createTempFile(
                    parent,
                    backup.getFileName() + ".",
                    ".tmp");
            Files.copy(file, temporaryBackup, StandardCopyOption.REPLACE_EXISTING);
            if (Files.mismatch(file, temporaryBackup) != -1L) {
                return false;
            }
            try {
                Files.move(
                        temporaryBackup,
                        backup,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryBackup, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            temporaryBackup = null;
            return Files.isRegularFile(backup) && Files.mismatch(file, backup) == -1L;
        } catch (IOException | SecurityException ignored) {
            return false;
        } finally {
            if (temporaryBackup != null) {
                delete(temporaryBackup);
            }
        }
    }

    private static boolean delete(Path path) {
        try {
            Files.deleteIfExists(path);
            return !Files.exists(path);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private record JournalOperation(String key, String translation, boolean provisional, boolean deleted) {
        private static JournalOperation upsert(String key, String translation, boolean provisional) {
            return new JournalOperation(key, translation, provisional, false);
        }

        private static JournalOperation delete(String key) {
            return new JournalOperation(key, null, false, true);
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("key", key);
            if (deleted) {
                object.addProperty("deleted", true);
            } else {
                object.addProperty("translation", translation);
                object.addProperty("provisional", provisional);
            }
            return object;
        }
    }
}
