package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.cache.CacheLogMerger;
import com.dragonmeow.nyanlex.cache.CacheLogMerger.Result;
import com.dragonmeow.nyanlex.cache.CacheLogMerger.Status;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges a legacy-named translation-hub file into the current-named one, with the same
 * rules as {@link CacheLogMerger}: rows already in the current file win, the legacy file
 * only adds the keys the current one lacks (placed first), the legacy file is never
 * touched, and the current file is replaced atomically after a verbatim backup was kept.
 *
 * <p>Two file kinds: the per-language hub cache ({@code {"schema":2,"language":..,"rows":
 * {<hash>:{"v":..,"s":..}}}}, see {@link HubLocalCache}) and the download ledger
 * ({@code {"schema":1,"sha":{<source+language>:<sha256>}}}, see {@link HubDownloadState}).
 * A file of any other schema, or one that is not a JSON object, is
 * {@link Status#UNREADABLE}: nothing is written.</p>
 */
public final class HubFileMerger {
    private static final Gson GSON = new Gson();
    private static final int CACHE_SCHEMA = 2;
    private static final int STATE_SCHEMA = 1;

    private HubFileMerger() {
    }

    /** Merges the hub caches; both rows keyed by {@link HubKeyHash}. */
    public static Result mergeCache(Path legacy, Path current) {
        return merge(legacy, current, true);
    }

    /** Merges the download ledgers. */
    public static Result mergeState(Path legacy, Path current) {
        return merge(legacy, current, false);
    }

    private static Result merge(Path legacy, Path current, boolean cache) {
        Path temporary = current.resolveSibling(current.getFileName() + ".merge.tmp");
        try {
            Parsed now;
            try {
                now = read(current, cache);
            } catch (UnreadableException e) {
                return new Result(Status.UNREADABLE, 0, 0, 0, current.getFileName() + ": " + e.getMessage());
            }
            Parsed old;
            try {
                old = read(legacy, cache);
            } catch (UnreadableException e) {
                return new Result(Status.UNREADABLE, 0, now.items.size(), now.skipped,
                        legacy.getFileName() + ": " + e.getMessage());
            }
            Map<String, JsonElement> merged = new LinkedHashMap<>();
            int added = 0;
            for (Map.Entry<String, JsonElement> entry : old.items.entrySet()) {
                if (!now.items.containsKey(entry.getKey())) {
                    merged.put(entry.getKey(), entry.getValue());
                    added++;
                }
            }
            int skipped = now.skipped + old.skipped;
            if (added == 0) {
                return new Result(Status.NOTHING_TO_ADD, 0, now.items.size(), skipped, null);
            }
            merged.putAll(now.items);

            JsonObject root = new JsonObject();
            root.addProperty("schema", cache ? CACHE_SCHEMA : STATE_SCHEMA);
            if (cache) {
                String language = now.language != null ? now.language : old.language;
                if (language != null) root.addProperty("language", language);
            }
            JsonObject body = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : merged.entrySet()) {
                body.add(entry.getKey(), entry.getValue());
            }
            root.add(cache ? "rows" : "sha", body);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            CacheLogMerger.backupBeforeRewrite(current);
            try {
                Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(Status.MERGED, added, now.items.size(), skipped, null);
        } catch (IOException | RuntimeException e) {
            return new Result(Status.IO_ERROR, 0, 0, 0, e.toString());
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException ignored) {
                // A stray .merge.tmp is harmless and overwritten by the next attempt.
            }
        }
    }

    private static final class UnreadableException extends Exception {
        UnreadableException(String message) {
            super(message);
        }
    }

    private static final class Parsed {
        final Map<String, JsonElement> items = new LinkedHashMap<>();
        String language;
        int skipped;
    }

    /** Rows/ledger entries of one file, each already reduced to what the owning class re-reads. */
    private static Parsed read(Path file, boolean cache) throws IOException, UnreadableException {
        Parsed parsed = new Parsed();
        if (Files.size(file) == 0L) return parsed;
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root == null || !root.isJsonObject()) throw new UnreadableException("not a JSON object");
            json = root.getAsJsonObject();
        } catch (UnreadableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnreadableException("not valid JSON");
        }
        int schema;
        try {
            schema = json.has("schema") ? json.get("schema").getAsInt() : -1;
        } catch (RuntimeException e) {
            schema = -1;
        }
        if (schema != (cache ? CACHE_SCHEMA : STATE_SCHEMA)) {
            throw new UnreadableException("unsupported schema " + schema);
        }
        if (cache) {
            if (json.has("language") && json.get("language").isJsonPrimitive()) {
                parsed.language = json.get("language").getAsString();
            }
            JsonObject rows = json.has("rows") && json.get("rows").isJsonObject()
                    ? json.getAsJsonObject("rows") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : rows.entrySet()) {
                if (!entry.getValue().isJsonObject() || !HubKeyHash.isHash(entry.getKey())) {
                    parsed.skipped++;
                    continue;
                }
                JsonObject row = entry.getValue().getAsJsonObject();
                if (!row.has("v") || !row.get("v").isJsonPrimitive()) {
                    parsed.skipped++;
                    continue;
                }
                JsonObject kept = new JsonObject();
                kept.addProperty("v", row.get("v").getAsString());
                if (row.has("s") && row.get("s").isJsonPrimitive()) {
                    kept.addProperty("s", row.get("s").getAsString());
                }
                parsed.items.put(entry.getKey(), kept);
            }
        } else {
            JsonObject sha = json.has("sha") && json.get("sha").isJsonObject()
                    ? json.getAsJsonObject("sha") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : sha.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    parsed.items.put(entry.getKey(), entry.getValue());
                } else {
                    parsed.skipped++;
                }
            }
        }
        return parsed;
    }
}
