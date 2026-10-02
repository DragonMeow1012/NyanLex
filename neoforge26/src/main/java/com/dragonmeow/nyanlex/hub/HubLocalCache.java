package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One local, per-target-language JSON cache of translations merged in from the shared
 * GitHub repository. Deliberately separate from the mod's own AI/GT cache files: a hub
 * row is never final wording the user's own engine produced, so it must never be
 * exported, migrated into, or mistaken for one.
 *
 * <p>Merge order encodes priority: once a key exists here it is never overwritten, so
 * a caller that merges server, then modpack, then mod files in that order naturally
 * gets the "server &gt; modpack &gt; mod" precedence documented for the hub. The AI/GT
 * cache's own precedence over every hub row is enforced by the caller: it consults its
 * own cache first and only falls back to {@link #get} on a miss.</p>
 */
public final class HubLocalCache {
    /** Local file schema 2: rows keyed by {@link HubKeyHash} (same as the repository files;
     *  no source text is ever stored). A schema 1 file (keyed by source text) is ignored. */
    private static final int SCHEMA = 2;
    private static final Gson GSON = new Gson();

    private static final class Row {
        final String value;
        final String source;
        /** In-memory only: passed {@link HubImportValidator#acceptsOnHit} once already. */
        boolean verified;

        Row(String value, String source) {
            this.value = value;
            this.source = source;
        }
    }

    /** Outcome of one {@link #mergeFromFile} call. */
    public static final class MergeResult {
        static final MergeResult LANGUAGE_REJECTED = new MergeResult(0, 0, 0, true);

        private final int added;
        private final int rejectedValidation;
        private final int alreadyPresent;
        private final boolean languageRejected;

        MergeResult(int added, int rejectedValidation, int alreadyPresent, boolean languageRejected) {
            this.added = added;
            this.rejectedValidation = rejectedValidation;
            this.alreadyPresent = alreadyPresent;
            this.languageRejected = languageRejected;
        }

        public int added() {
            return added;
        }

        public int rejectedValidation() {
            return rejectedValidation;
        }

        public int alreadyPresent() {
            return alreadyPresent;
        }

        public boolean languageRejected() {
            return languageRejected;
        }
    }

    /** Bound of each lookup memo below; single-entry (LRU) eviction, never a whole-table clear. */
    private static final int LOOKUP_MEMO_MAX = 4096;

    private final Path directory;
    private boolean dirty;
    private String language;
    private Map<String, Row> rows = new LinkedHashMap<>();
    /**
     * Render-frame lookups ask for the same few hundred keys every frame, and most of them are
     * misses (a line nobody has translated yet). So the SHA-256 of a key is remembered, and so is
     * "this key is not in the hub": a repeated miss costs one map probe instead of a digest.
     * The negative memo is only valid for the current row set, so every change of the rows
     * (a merge that adds rows, a cleared source, a language switch) drops it. Guarded by
     * {@code this}, like {@link #rows}.
     */
    private final Map<String, String> hashMemo = lruMap();
    private final Map<String, Boolean> missMemo = lruMap();

    private static <V> Map<String, V> lruMap() {
        return new LinkedHashMap<String, V>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > LOOKUP_MEMO_MAX;
            }
        };
    }

    public HubLocalCache(Path directory, String language) {
        this.directory = directory;
        // Force the first load even though languageTag(null) may coincide with a
        // caller-supplied default: `language` starts null, which never equals a tag.
        this.language = null;
        setLanguage(language);
    }

    public synchronized void setLanguage(String targetLanguage) {
        String next = LanguageFileStore.languageTag(targetLanguage);
        if (next.equals(language)) return;
        language = next;
        dirty = false;
        rows = load();
        missMemo.clear();
    }

    public String language() {
        return language;
    }

    public Path activeFile() {
        return directory.resolve(HubPaths.hubCacheFileName(language));
    }

    /**
     * Look up by the normalized cache key (hashed here with {@link HubKeyHash}). A row is
     * validated against this real key the first time it hits; a row that does not fit
     * (token counts, reshaped tokens, foreign URL) is dropped for good and {@code null}
     * returned, so a structurally wrong row never reaches the display path.
     */
    public synchronized String get(String key) {
        if (key == null) return null;
        if (missMemo.get(key) != null) return null;
        String hash = hashMemo.get(key);
        if (hash == null) {
            hash = HubKeyHash.of(key);
            hashMemo.put(key, hash);
        }
        Row row = rows.get(hash);
        if (row == null) {
            missMemo.put(key, Boolean.TRUE);
            return null;
        }
        if (!row.verified) {
            if (!HubImportValidator.acceptsOnHit(key, row.value)) {
                rows.remove(hash);
                dirty = true;
                return null;
            }
            row.verified = true;
        }
        return row.value;
    }

    public synchronized int size() {
        return rows.size();
    }

    /**
     * Merge every usable row of {@code file}, tagged with {@code source}. Rejects the
     * WHOLE file (zero rows merged) when its {@code language} does not match the
     * active language. A key that already exists — from an earlier, higher-priority
     * source, or from this exact source re-downloaded — is never replaced.
     */
    public synchronized MergeResult mergeFromFile(HubFile file, HubSource source) {
        if (file == null) return MergeResult.LANGUAGE_REJECTED;
        if (!LanguageFileStore.languageTag(file.language()).equals(language)) {
            return MergeResult.LANGUAGE_REJECTED;
        }
        int added = 0, rejected = 0, present = 0;
        String encodedSource = source == null ? null : source.encode();
        for (Map.Entry<String, String> entry : file.entries().entrySet()) {
            String hash = entry.getKey();
            String value = entry.getValue();
            if (rows.containsKey(hash)) {
                present++;
                continue;
            }
            if (!HubImportValidator.acceptsOnMerge(value)) {
                rejected++;
                continue;
            }
            rows.put(hash, new Row(value, encodedSource));
            added++;
        }
        if (added > 0) missMemo.clear();
        if (added > 0 || dirty) persist();
        return new MergeResult(added, rejected, present, false);
    }

    /** Remove every row tagged with {@code source} (for example a stale mod's rows
     *  after it is uninstalled, or before a clean re-download). */
    public synchronized int clearSource(HubSource source) {
        if (source == null) return 0;
        String encoded = source.encode();
        int before = rows.size();
        rows.values().removeIf(row -> encoded.equals(row.source));
        int removed = before - rows.size();
        if (removed > 0) missMemo.clear();
        if (removed > 0 || dirty) persist();
        return removed;
    }

    public synchronized void clearAll() {
        if (rows.isEmpty()) return;
        rows = new LinkedHashMap<>();
        missMemo.clear();
        persist();
    }

    private Map<String, Row> load() {
        Path file = activeFile();
        Map<String, Row> loaded = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return loaded;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            if (!json.has("schema") || json.get("schema").getAsInt() != SCHEMA) return loaded;
            JsonObject rowsJson = json.has("rows") && json.get("rows").isJsonObject()
                    ? json.getAsJsonObject("rows") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : rowsJson.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject row = entry.getValue().getAsJsonObject();
                String value = row.has("v") && !row.get("v").isJsonNull() ? row.get("v").getAsString() : null;
                String source = row.has("s") && !row.get("s").isJsonNull() ? row.get("s").getAsString() : null;
                if (value != null && HubKeyHash.isHash(entry.getKey())) loaded.put(entry.getKey(), new Row(value, source));
            }
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cache: a damaged file simply starts empty again.
        }
        return loaded;
    }

    private void persist() {
        try {
            Files.createDirectories(directory);
            Path target = activeFile();
            Path temp = Files.createTempFile(directory, ".nyanlex-hub-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                JsonObject json = new JsonObject();
                json.addProperty("schema", SCHEMA);
                json.addProperty("language", language);
                JsonObject rowsJson = new JsonObject();
                for (Map.Entry<String, Row> entry : rows.entrySet()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("v", entry.getValue().value);
                    if (entry.getValue().source != null) row.addProperty("s", entry.getValue().source);
                    rowsJson.add(entry.getKey(), row);
                }
                json.add("rows", rowsJson);
                GSON.toJson(json, writer);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException ignored) {
            // Best-effort cache: a failed write is retried on the next successful merge.
        }
    }
}
