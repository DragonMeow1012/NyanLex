package com.dragonmeow.nyanlex.cache;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * Merges the entries of a legacy-named {@link FileStore} journal (AI cache, machine
 * translation cache, failure ledger) into the journal of the same store under the current
 * name, without ever loading either file into memory as a whole.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>The entries already in {@code current} always win: a key that is live in
 *       {@code current} is never taken from {@code legacy}, and keeps its value and its
 *       provisional flag.</li>
 *   <li>{@code legacy} only adds the keys {@code current} does not hold (a key whose latest
 *       legacy row is a tombstone is not added).</li>
 *   <li>Output order is "legacy-only rows first, then {@code current}'s rows", each group in
 *       its own write order. {@link FileStore} evicts the oldest rows first when a cache is
 *       over its cap, so the merged-in rows are the first to go and the live rows the last.</li>
 *   <li>{@code legacy} is only ever read. {@code current} is replaced atomically (temporary
 *       file in the same folder, then a move) and only after a verbatim copy of it was kept
 *       as {@value #BACKUP_SUFFIX}; any failure leaves both files exactly as they were.</li>
 * </ul>
 *
 * <p>Memory and time: two sequential passes over each file. The first pass only keeps the
 * live keys and the line numbers of their latest rows; the second pass copies exactly those
 * lines verbatim. Translations are never retained, so a 100 000-entry / 60 MB journal needs
 * a few MB of heap. Both files may be schema 2, 3 or 4 ({@link FileStore}'s formats); the
 * result is always schema 4.</p>
 *
 * <p>A damaged row (not JSON, no key, no translation) is skipped, as {@link FileStore}
 * would skip it; bytes that are not valid UTF-8 are decoded as U+FFFD, so one bad byte only
 * costs the row it sits in. A file whose header is not a
 * {@code {"schema":N}} object of a known schema is {@link Status#UNREADABLE}: nothing is
 * written. Java 8-style APIs only, plus {@code JsonParser#parse} (not {@code parseString})
 * so the same source compiles against the oldest Gson shipped by any target.</p>
 */
public final class CacheLogMerger {
    /** Verbatim copy of the replaced current file; the first free of {@code .1}..{@code .9} is used if taken. */
    public static final String BACKUP_SUFFIX = ".pre-merge.bak";

    private static final Gson GSON = new Gson();
    private static final int SCHEMA = 4;
    private static final int SNAPSHOT_SCHEMA = 3;
    private static final int LEGACY_SCHEMA = 2;
    private static final int MAX_BACKUPS = 10;

    public enum Status {
        /** Rows were added to the current file. */
        MERGED,
        /** The legacy file held nothing the current file lacks; no file was written. */
        NOTHING_TO_ADD,
        /** A file's content cannot be understood (bad header or unknown schema); nothing was written. */
        UNREADABLE,
        /** An I/O problem; nothing was written and a later run may succeed. */
        IO_ERROR
    }

    /** Outcome of one {@link #merge}. */
    public static final class Result {
        private final Status status;
        private final int added;
        private final int kept;
        private final int skippedRows;
        private final String detail;

        public Result(Status status, int added, int kept, int skippedRows, String detail) {
            this.status = status;
            this.added = added;
            this.kept = kept;
            this.skippedRows = skippedRows;
            this.detail = detail;
        }

        public Status status() { return status; }
        /** Rows taken from the legacy file. */
        public int added() { return added; }
        /** Live rows the current file already had. */
        public int kept() { return kept; }
        /** Damaged rows ignored in either file. */
        public int skippedRows() { return skippedRows; }
        /** Human-readable explanation; for failures this is the reason. */
        public String detail() { return detail; }

        @Override public String toString() {
            return status + " added=" + added + " kept=" + kept + " skipped=" + skippedRows
                    + (detail == null ? "" : " (" + detail + ")");
        }
    }

    private CacheLogMerger() {
    }

    /**
     * Merges {@code legacy} into {@code current} under the rules above. Never throws.
     * {@code current} must already exist (copy the legacy file instead when it does not).
     */
    public static Result merge(Path legacy, Path current) {
        Path temporary = current.resolveSibling(current.getFileName() + ".merge.tmp");
        try {
            Scan existing;
            try {
                existing = scan(current, null);
            } catch (UnreadableException e) {
                return new Result(Status.UNREADABLE, 0, 0, 0,
                        current.getFileName() + ": " + e.getMessage());
            }
            Scan old;
            try {
                old = scan(legacy, existing.liveKeys);
            } catch (UnreadableException e) {
                return new Result(Status.UNREADABLE, 0, existing.liveKeys.size(), existing.skipped,
                        legacy.getFileName() + ": " + e.getMessage());
            }
            int skipped = existing.skipped + old.skipped;
            int added = old.keep.cardinality();
            if (added == 0) {
                return new Result(Status.NOTHING_TO_ADD, 0, existing.liveKeys.size(), skipped, null);
            }

            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                JsonObject header = new JsonObject();
                header.addProperty("schema", SCHEMA);
                writer.write(GSON.toJson(header));
                writer.newLine();
                int fromLegacy = copyKept(legacy, old.keep, writer);
                int fromCurrent = copyKept(current, existing.keep, writer);
                if (fromLegacy != added || fromCurrent != existing.keep.cardinality()) {
                    throw new IOException("a file changed while it was being merged");
                }
            }
            backupBeforeRewrite(current);
            try {
                Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(Status.MERGED, added, existing.liveKeys.size(), skipped, null);
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

    /**
     * Keeps a verbatim copy of {@code file} next to it ({@value #BACKUP_SUFFIX}) before it is
     * replaced. Does nothing for a missing or empty file or when an identical copy already
     * exists; throws if no copy could be made, so the caller does not overwrite unbacked data.
     */
    public static void backupBeforeRewrite(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return;
        for (int index = 0; index < MAX_BACKUPS; index++) {
            Path candidate = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX
                    + (index == 0 ? "" : "." + index));
            if (!Files.exists(candidate)) {
                Files.copy(file, candidate);
                return;
            }
            if (Files.isRegularFile(candidate) && Files.mismatch(file, candidate) == -1L) return;
        }
        throw new IOException("no free backup name for " + file.getFileName());
    }

    // ------------------------------------------------------------------ scanning

    private static final class UnreadableException extends Exception {
        UnreadableException(String message) {
            super(message);
        }
    }

    /** Live keys plus the (0-based, header excluded) numbers of the rows that define them. */
    private static final class Scan {
        final Set<String> liveKeys;
        final BitSet keep;
        final int skipped;

        Scan(Set<String> liveKeys, BitSet keep, int skipped) {
            this.liveKeys = liveKeys;
            this.keep = keep;
            this.skipped = skipped;
        }
    }

    /**
     * First pass: replays the journal last-wins, ignoring every key in {@code exclude}, and
     * records which row numbers hold the final state of each live key.
     */
    private static Scan scan(Path file, Set<String> exclude) throws IOException, UnreadableException {
        Map<String, Integer> lastRow = new HashMap<>();
        int skipped = 0;
        boolean[] deleted = new boolean[1];
        try (Rows rows = Rows.open(file)) {
            skipped += rows.skippedAtOpen();
            String line;
            int index = 0;
            while ((line = rows.next()) != null) {
                int row = index++;
                if (line.trim().isEmpty()) continue;
                String key = parseKey(line, deleted);
                if (key == null) {
                    skipped++;
                    continue;
                }
                if (exclude != null && exclude.contains(key)) continue;
                if (deleted[0]) {
                    lastRow.remove(key);
                } else {
                    lastRow.put(key, row);
                }
            }
        }
        BitSet keep = new BitSet();
        for (int row : lastRow.values()) keep.set(row);
        return new Scan(lastRow.keySet(), keep, skipped);
    }

    /** Second pass: writes exactly the kept rows of {@code file}, verbatim, in file order. */
    private static int copyKept(Path file, BitSet keep, BufferedWriter out)
            throws IOException {
        int written = 0;
        try (Rows rows = Rows.open(file)) {
            String line;
            int index = 0;
            while ((line = rows.next()) != null) {
                int row = index++;
                if (keep.get(row)) {
                    out.write(line);
                    out.newLine();
                    written++;
                }
            }
        } catch (UnreadableException e) {
            throw new IOException("a file changed while it was being merged: " + e.getMessage());
        }
        return written;
    }

    /**
     * Reads the row's key and whether it is a tombstone, validating the same fields
     * {@link FileStore} does: a string/number key, and for an upsert a non-null string
     * translation. Returns {@code null} for a damaged row.
     */
    private static String parseKey(String line, boolean[] deletedOut) {
        deletedOut[0] = false;
        String key = null;
        boolean hasTranslation = false;
        boolean deleted = false;
        try (JsonReader reader = new JsonReader(new StringReader(line))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("key".equals(name)) {
                    if (reader.peek() == JsonToken.NULL) return null;
                    key = reader.nextString();
                } else if ("translation".equals(name)) {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                    } else {
                        reader.nextString();
                        hasTranslation = true;
                    }
                } else if ("deleted".equals(name)) {
                    if (reader.peek() == JsonToken.BOOLEAN) {
                        deleted = reader.nextBoolean();
                    } else {
                        reader.skipValue();
                    }
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (key == null) return null;
        if (!deleted && !hasTranslation) return null;
        deletedOut[0] = deleted;
        return key;
    }

    /**
     * The logical rows of a journal after its header line. Schema 3 and 4 stream the file's
     * lines; schema 2 keeps its entries inside the header object, so they are re-serialised
     * one per row. Decoding replaces undecodable bytes with U+FFFD instead of throwing (the
     * same as {@link FileStore}), so a torn tail never hides the rows before it.
     */
    private static final class Rows implements Closeable {
        private final BufferedReader reader;
        private final Iterator<String> synthetic;
        private final int skippedAtOpen;

        private Rows(BufferedReader reader, Iterator<String> synthetic, int skippedAtOpen) {
            this.reader = reader;
            this.synthetic = synthetic;
            this.skippedAtOpen = skippedAtOpen;
        }

        int skippedAtOpen() {
            return skippedAtOpen;
        }

        static Rows open(Path file) throws IOException, UnreadableException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(file), StandardCharsets.UTF_8), 1 << 16);
            try {
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    // A zero-byte file: no rows at all.
                    return new Rows(reader, Collections.<String>emptyList().iterator(), 0);
                }
                if (headerLine.trim().isEmpty()) throw new UnreadableException("blank header line");
                JsonObject header;
                int schema;
                try {
                    header = new JsonParser().parse(headerLine).getAsJsonObject();
                    schema = header.has("schema") ? header.get("schema").getAsInt() : -1;
                } catch (RuntimeException e) {
                    throw new UnreadableException("header is not a schema object");
                }
                if (schema == SCHEMA || schema == SNAPSHOT_SCHEMA) {
                    return new Rows(reader, null, 0);
                }
                if (schema == LEGACY_SCHEMA) {
                    List<String> rows = new ArrayList<>();
                    int skipped = 0;
                    JsonArray entries = header.has("entries") && header.get("entries").isJsonArray()
                            ? header.getAsJsonArray("entries") : new JsonArray();
                    for (JsonElement element : entries) {
                        JsonObject entry = element.isJsonObject() ? element.getAsJsonObject() : null;
                        if (entry == null || !entry.has("key") || entry.get("key").isJsonNull()
                                || !entry.has("translation") || entry.get("translation").isJsonNull()) {
                            skipped++;
                            continue;
                        }
                        JsonObject row = new JsonObject();
                        row.add("key", entry.get("key"));
                        row.add("translation", entry.get("translation"));
                        if (entry.has("provisional")) row.add("provisional", entry.get("provisional"));
                        rows.add(GSON.toJson(row));
                    }
                    return new Rows(reader, rows.iterator(), skipped);
                }
                throw new UnreadableException("unsupported schema " + schema);
            } catch (IOException | UnreadableException | RuntimeException e) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Closing a reader that already failed.
                }
                throw e;
            }
        }

        String next() throws IOException {
            if (synthetic != null) return synthetic.hasNext() ? synthetic.next() : null;
            return reader.readLine();
        }

        @Override public void close() throws IOException {
            reader.close();
        }
    }
}
