package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.cache.CacheLogMerger;
import com.dragonmeow.nyanlex.hub.HubFileMerger;
import com.dragonmeow.nyanlex.translate.DebugErrorLog;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One-time, non-destructive migration of config/cache files left over from the
 * mod's two earlier identities ("nyanslate" and, before that, "mctranslator") to
 * the current "nyanlex" one. Runs once at startup, before any store opens a file.
 *
 * <h3>Rules, per legacy file</h3>
 * <ul>
 *   <li><b>No current-named file</b>: the legacy file is copied (never moved) to the
 *       current name, keeping its modification time.</li>
 *   <li><b>Current-named file already exists</b> and the legacy file is a cache (AI cache,
 *       machine-translation cache, failure ledger, hub cache, hub download ledger):
 *       the two are <em>merged</em> once. Entries already in the current file win; the
 *       legacy file only adds the keys the current one lacks. See {@link CacheLogMerger}
 *       and {@link HubFileMerger}. Merging a cache with a few thousand fresh rows into a
 *       100 000-row legacy cache is the normal case: the player ran a new build once
 *       before this migration existed, so a small current-named file was already there.</li>
 *   <li><b>Any other file</b> (settings, backups, logs) that already exists under the
 *       current name is left alone: settings are never merged.</li>
 *   <li>Legacy files are only ever read, never changed or deleted. A merge replaces the
 *       current file atomically, and only after {@value CacheLogMerger#BACKUP_SUFFIX} of it
 *       was kept. A file that cannot be parsed, or any I/O failure, leaves every file
 *       untouched and is reported to {@code log}, to latest.log through it, and to the
 *       偵錯模式 error log (once that log is installed).</li>
 *   <li>Each legacy file is handled once: {@value #MARKER_FILE} records its name after it
 *       was copied, merged, found to add nothing, kept apart from an existing settings file
 *       or found unreadable. A later start therefore costs one small file read, and a file
 *       the player deletes or clears under its current name (the "clear saved
 *       translations" button deletes the cache files) is never brought back from the legacy
 *       name. Delete the marker to run the migration again. A transient I/O failure is not
 *       recorded and is retried on the next start.</li>
 * </ul>
 *
 * <p>When both earlier generations exist the newer one ("nyanslate") is handled first, so
 * its rows outrank the older generation's ("mctranslator"). Directories (notably the
 * codex-home / codex-workspace folders) are skipped: codex-home is excluded from migration
 * by design (a fresh Codex login is expected after a rename).</p>
 *
 * <p>Cost: two sequential streaming passes over each file of a pair, no translation text
 * kept in memory; see {@link CacheLogMerger}. This runs on the startup thread, once.</p>
 */
public final class LegacyDataMigration {
    /** Earlier product identifiers, newest first. Defined only here so no other
     *  source file needs to spell an old name. */
    public static final String[] LEGACY_PREFIXES = {"nyanslate", "mctranslator"};

    /** Current product identifier that every migrated file name is rewritten to. */
    public static final String CURRENT_PREFIX = "nyanlex";

    /** Records which legacy cache files were already copied or merged. */
    public static final String MARKER_FILE = CURRENT_PREFIX + "-legacy-merge.json";

    private static final Gson GSON = new Gson();
    private static final int MARKER_SCHEMA = 1;

    private enum Kind { OTHER, JOURNAL, HUB_CACHE, HUB_STATE }

    private LegacyDataMigration() {
    }

    /**
     * Scans {@code configDir} for regular files whose name starts with one of
     * {@link #LEGACY_PREFIXES} (either exactly {@code "<prefix>.json"} or
     * {@code "<prefix>-<anything>"}) and copies or merges each one as described in the
     * class comment. Never throws: any I/O failure is reported through {@code log}
     * (if non-null) and otherwise swallowed, since migration must never block startup.
     *
     * @return the number of files copied plus the number of caches that received rows.
     */
    public static int migrate(Path configDir, Consumer<String> log) {
        if (configDir == null || !Files.isDirectory(configDir)) return 0;
        List<Path> entries = new ArrayList<>();
        for (String legacyPrefix : LEGACY_PREFIXES) {
            collect(configDir, legacyPrefix, entries, log);
        }
        if (entries.isEmpty()) return 0;

        Marker marker = Marker.load(configDir, log);
        int changed = 0;
        for (Path entry : entries) {
            String legacyName = entry.getFileName().toString();
            String newName = currentPrefixedName(entry, prefixOf(legacyName));
            if (newName == null || !Files.isRegularFile(entry)) continue; // skips codex-home/codex-workspace dirs
            // Handled once, whatever the outcome: a file the player later deletes or clears
            // under its current name must never be brought back from the legacy name.
            if (marker.isDone(legacyName)) continue;
            Path target = configDir.resolve(newName);
            Kind kind = kindOf(newName);
            try {
                if (!Files.exists(target)) {
                    if (copy(entry, target, log)) {
                        changed++;
                        marker.record(legacyName, "copied", 0, null);
                    }
                } else if (kind == Kind.OTHER) {
                    marker.record(legacyName, "kept-existing", 0, null);
                } else if (merge(kind, entry, target, legacyName, newName, marker, log)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                problem(log, "legacy data migration failed for " + legacyName + ": " + e, legacyName);
            }
        }
        marker.saveIfChanged(log);
        return changed;
    }

    private static void collect(Path configDir, String legacyPrefix, List<Path> out, Consumer<String> log) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path entry : stream) {
                if (currentPrefixedName(entry, legacyPrefix) != null) found.add(entry);
            }
        } catch (IOException e) {
            if (log != null) log.accept("legacy data migration scan failed: " + e);
        } catch (DirectoryIteratorException e) {
            // Thrown by the enhanced-for's hasNext()/next() mid-iteration (e.g. a locked file,
            // a device going away). It wraps an IOException but is itself a RuntimeException,
            // so it must be caught here to keep the "never throws" promise at every call site.
            if (log != null) log.accept("legacy data migration scan failed: " + e);
        }
        found.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        out.addAll(found);
    }

    private static String prefixOf(String legacyName) {
        for (String prefix : LEGACY_PREFIXES) {
            if (legacyName.startsWith(prefix)) return prefix;
        }
        return "";
    }

    /** Copies through a temporary name so a crash never leaves a half-written current file. */
    private static boolean copy(Path entry, Path target, Consumer<String> log) {
        Path temporary = target.resolveSibling(target.getFileName() + ".migrating.tmp");
        try {
            Files.copy(entry, temporary, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            Files.move(temporary, target); // never replaces: fails if the target appeared meanwhile
            if (log != null) {
                log.accept("migrated legacy data file: " + entry.getFileName() + " -> " + target.getFileName());
            }
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Overwritten by the next attempt.
            }
            problem(log, "legacy data migration failed for " + entry.getFileName() + ": " + e,
                    entry.getFileName().toString());
            return false;
        }
    }

    private static boolean merge(Kind kind, Path entry, Path target, String legacyName, String newName,
                                 Marker marker, Consumer<String> log) {
        long started = System.nanoTime();
        CacheLogMerger.Result result;
        switch (kind) {
            case HUB_CACHE: result = HubFileMerger.mergeCache(entry, target); break;
            case HUB_STATE: result = HubFileMerger.mergeState(entry, target); break;
            default: result = CacheLogMerger.merge(entry, target); break;
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        switch (result.status()) {
            case MERGED:
                marker.record(legacyName, "merged", result.added(), null);
                if (log != null) {
                    log.accept("merged legacy cache " + legacyName + " into " + newName + ": +"
                            + result.added() + " rows, " + result.kept() + " existing rows kept, "
                            + result.skippedRows() + " damaged rows skipped, " + millis + " ms");
                }
                return true;
            case NOTHING_TO_ADD:
                marker.record(legacyName, "nothing-to-add", 0, null);
                if (log != null) {
                    log.accept("legacy cache " + legacyName + " adds nothing to " + newName + " ("
                            + result.kept() + " rows already there)");
                }
                return false;
            case UNREADABLE:
                marker.record(legacyName, "unreadable", 0, result.detail());
                problem(log, "legacy cache merge skipped, nothing was changed (" + legacyName + " -> "
                        + newName + "): " + result.detail(), legacyName);
                return false;
            default:
                // IO_ERROR: not recorded, so the next start tries again.
                problem(log, "legacy cache merge failed, nothing was changed (" + legacyName + " -> "
                        + newName + "): " + result.detail(), legacyName);
                return false;
        }
    }

    /** latest.log through {@code log}, plus the 偵錯模式 error log once it is installed. */
    private static void problem(Consumer<String> log, String message, String file) {
        if (log != null) log.accept(message);
        try {
            DebugErrorLog.reportEarly(DebugErrorLog.MIGRATION, message, "file", file);
        } catch (RuntimeException ignored) {
            // The error log must never break startup.
        }
    }

    /** Returns the {@link #CURRENT_PREFIX}-renamed basename for an entry carrying
     *  {@code legacyPrefix} at a name-segment boundary (exactly
     *  {@code "<prefix>.json"} or {@code "<prefix>-..."}), or {@code null}. */
    static String currentPrefixedName(Path entry, String legacyPrefix) {
        String name = entry.getFileName().toString();
        if (!name.startsWith(legacyPrefix)) return null;
        String suffix = name.substring(legacyPrefix.length());
        if (!(suffix.equals(".json") || suffix.startsWith("-"))) return null;
        return CURRENT_PREFIX + suffix;
    }

    /** Which merge a current-named file takes part in, by its name alone. */
    private static Kind kindOf(String currentName) {
        String suffix = currentName.substring(CURRENT_PREFIX.length());
        if (!suffix.endsWith(".json")) return Kind.OTHER;
        if (suffix.equals("-hub-state.json")) return Kind.HUB_STATE;
        if (suffix.startsWith("-hub-cache-")) return Kind.HUB_CACHE;
        if (suffix.startsWith("-ai-cache-") || suffix.startsWith("-cache-")
                || suffix.startsWith("-failures-")) {
            return Kind.JOURNAL;
        }
        return Kind.OTHER;
    }

    /** {@code nyanlex-legacy-merge.json}: which legacy cache files were already handled. */
    private static final class Marker {
        private final Path file;
        private final JsonObject done;
        private boolean dirty;

        private Marker(Path file, JsonObject done) {
            this.file = file;
            this.done = done;
        }

        static Marker load(Path configDir, Consumer<String> log) {
            Path file = configDir.resolve(MARKER_FILE);
            JsonObject done = new JsonObject();
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonElement root = new JsonParser().parse(reader);
                    if (root != null && root.isJsonObject() && root.getAsJsonObject().has("done")
                            && root.getAsJsonObject().get("done").isJsonObject()) {
                        done = root.getAsJsonObject().getAsJsonObject("done");
                    }
                } catch (IOException | RuntimeException e) {
                    // An unreadable marker only means a merge may run again; merging is idempotent.
                    if (log != null) log.accept("legacy merge marker unreadable, starting a new one: " + e);
                }
            }
            return new Marker(file, done);
        }

        boolean isDone(String legacyName) {
            return done.has(legacyName);
        }

        void record(String legacyName, String result, int added, String detail) {
            JsonObject entry = new JsonObject();
            entry.addProperty("result", result);
            entry.addProperty("added", added);
            entry.addProperty("at", Instant.now().toString());
            if (detail != null) entry.addProperty("detail", detail);
            done.add(legacyName, entry);
            dirty = true;
        }

        void saveIfChanged(Consumer<String> log) {
            if (!dirty) return;
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                JsonObject root = new JsonObject();
                root.addProperty("schema", MARKER_SCHEMA);
                root.add("done", done);
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    GSON.toJson(root, writer);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
            } catch (IOException | RuntimeException e) {
                // Without the marker a later start repeats the (idempotent) merge.
                if (log != null) log.accept("could not write " + MARKER_FILE + ": " + e);
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Overwritten by the next attempt.
                }
            }
        }
    }
}
