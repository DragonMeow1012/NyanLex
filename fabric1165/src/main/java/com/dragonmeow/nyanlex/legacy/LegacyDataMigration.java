package com.dragonmeow.nyanlex.legacy;

import com.dragonmeow.nyanlex.translate.TranslationFile;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One-time, non-destructive migration of config/cache files left over from the
 * mod's two earlier identities ("nyanslate" and, before that, "mctranslator") to
 * the current "nyanlex" one. Runs once at startup.
 *
 * <p>The Java 8 counterpart of the modern trees' {@code config.LegacyDataMigration}, with
 * the same rules. The only translation data these trees keep on disk is the imported
 * snapshot ({@code <prefix>-imported-<lang>.json}, a {@link TranslationFile}); everything
 * else is settings or a Codex login.</p>
 * <ul>
 *   <li><b>No current-named file</b>: the legacy file is copied (never moved) to the
 *       current name, keeping its modification time.</li>
 *   <li><b>Current-named snapshot already exists</b>: the two are merged once. Rows already
 *       in the current snapshot win; the legacy snapshot only adds rows the current one
 *       lacks (machine rows only when both name the same provider), and the result never
 *       holds more than {@value #MAX_IMPORTED_ROWS} rows because the translator refuses to
 *       load a snapshot over that capacity at all. The current file is replaced atomically,
 *       after a verbatim {@code .pre-merge.bak} copy of it was kept.</li>
 *   <li><b>Any other file</b> (settings, Codex folders) that already exists under the
 *       current name is left alone; directories are never descended into.</li>
 *   <li>Legacy files are only read. A file that cannot be understood leaves every file as it
 *       was and is reported to {@code log}.</li>
 *   <li>Each legacy file is handled once: {@value #MARKER_FILE} records its name after it
 *       was copied, merged, found to add nothing or found unreadable, so a file the player
 *       deletes under its current name is never brought back from the legacy name. Delete
 *       the marker to run the migration again. A transient I/O failure is not recorded and
 *       is retried on the next start.</li>
 * </ul>
 * When both earlier generations exist the newer one ("nyanslate") is handled first.
 *
 * <p>Pure {@code java.nio}, Java 8 source compatible, no Minecraft/loader API; the
 * forgelegacy package holds a package-renamed copy ({@code sync-legacy-forge-core.ps1}).</p>
 */
public final class LegacyDataMigration {
    /** Earlier product identifiers, newest first. Defined only here so no other
     *  source file needs to spell an old name. */
    public static final String[] LEGACY_PREFIXES = {"nyanslate", "mctranslator"};

    /** Current product identifier that every migrated file name is rewritten to. */
    public static final String CURRENT_PREFIX = "nyanlex";

    /** Records which legacy files were already handled. */
    public static final String MARKER_FILE = CURRENT_PREFIX + "-legacy-merge.json";

    /** {@code LegacyTranslator.MAX_CACHE_ENTRIES}: a snapshot over it is rejected whole. */
    static final int MAX_IMPORTED_ROWS = 8192;

    static final String BACKUP_SUFFIX = ".pre-merge.bak";

    private static final Gson GSON = new Gson();
    private static final int MARKER_SCHEMA = 1;
    private static final int MAX_BACKUPS = 10;

    private enum Kind { OTHER, IMPORTED }

    private enum Outcome { MERGED, NOTHING_TO_ADD, UNREADABLE, IO_ERROR }

    private LegacyDataMigration() {
    }

    /**
     * Scans {@code configDir} for regular files whose name starts with one of
     * {@link #LEGACY_PREFIXES} (either exactly {@code "<prefix>.json"} or
     * {@code "<prefix>-<anything>"}) and copies or merges each one as described in the
     * class comment. Never throws: any I/O failure is reported through {@code log}
     * (if non-null) and otherwise swallowed, since migration must never block startup.
     *
     * @return the number of files copied plus the number of snapshots that received rows.
     */
    public static int migrate(Path configDir, Consumer<String> log) {
        if (configDir == null || !Files.isDirectory(configDir)) return 0;
        List<Path> entries = new ArrayList<Path>();
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
            // Handled once, whatever the outcome: a file the player later deletes under its
            // current name must never be brought back from the legacy name.
            if (marker.isDone(legacyName)) continue;
            Path target = configDir.resolve(newName);
            try {
                if (!Files.exists(target)) {
                    if (copy(entry, target, log)) {
                        changed++;
                        marker.record(legacyName, "copied", 0, null);
                    }
                } else if (kindOf(newName) == Kind.OTHER) {
                    marker.record(legacyName, "kept-existing", 0, null);
                } else if (merge(entry, target, legacyName, newName, marker, log)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                if (log != null) log.accept("legacy data migration failed for " + legacyName + ": " + e);
            }
        }
        marker.saveIfChanged(log);
        return changed;
    }

    private static void collect(Path configDir, String legacyPrefix, List<Path> out, Consumer<String> log) {
        List<Path> found = new ArrayList<Path>();
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
        Collections.sort(found, new Comparator<Path>() {
            @Override public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
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
            if (log != null) log.accept("legacy data migration failed for " + entry.getFileName() + ": " + e);
            return false;
        }
    }

    private static boolean merge(Path legacy, Path current, String legacyName, String newName,
                                 Marker marker, Consumer<String> log) {
        long started = System.nanoTime();
        String[] detail = new String[1];
        int[] added = new int[1];
        Outcome outcome = mergeImported(legacy, current, added, detail);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        switch (outcome) {
            case MERGED:
                marker.record(legacyName, "merged", added[0], null);
                if (log != null) {
                    log.accept("merged legacy snapshot " + legacyName + " into " + newName + ": +"
                            + added[0] + " rows, " + millis + " ms");
                }
                return true;
            case NOTHING_TO_ADD:
                marker.record(legacyName, "nothing-to-add", 0, null);
                if (log != null) log.accept("legacy snapshot " + legacyName + " adds nothing to " + newName);
                return false;
            case UNREADABLE:
                marker.record(legacyName, "unreadable", 0, detail[0]);
                if (log != null) {
                    log.accept("legacy snapshot merge skipped, nothing was changed (" + legacyName + " -> "
                            + newName + "): " + detail[0]);
                }
                return false;
            default:
                // IO_ERROR: not recorded, so the next start tries again.
                if (log != null) {
                    log.accept("legacy snapshot merge failed, nothing was changed (" + legacyName + " -> "
                            + newName + "): " + detail[0]);
                }
                return false;
        }
    }

    private static Outcome mergeImported(Path legacy, Path current, int[] added, String[] detail) {
        TranslationFile old;
        TranslationFile now;
        try {
            now = TranslationFile.read(current);
        } catch (IOException e) {
            detail[0] = current.getFileName() + ": " + e.getMessage();
            return isContentProblem(e) ? Outcome.UNREADABLE : Outcome.IO_ERROR;
        }
        try {
            old = TranslationFile.read(legacy);
        } catch (IOException e) {
            detail[0] = legacy.getFileName() + ": " + e.getMessage();
            return isContentProblem(e) ? Outcome.UNREADABLE : Outcome.IO_ERROR;
        }
        if (!now.format.equals(old.format) || !now.language.equals(old.language)) {
            detail[0] = "the two snapshots differ in format or language";
            return Outcome.UNREADABLE;
        }
        boolean sameProvider = now.provider.equals(old.provider);
        int room = MAX_IMPORTED_ROWS - now.machine.size() - now.ai.size();
        Map<String, String> machine = new LinkedHashMap<String, String>();
        Map<String, String> ai = new LinkedHashMap<String, String>();
        int count = 0;
        for (Map.Entry<String, String> row : old.ai.entrySet()) {
            if (room <= 0) break;
            if (now.ai.containsKey(row.getKey())) continue;
            ai.put(row.getKey(), row.getValue());
            room--;
            count++;
        }
        if (sameProvider) {
            for (Map.Entry<String, String> row : old.machine.entrySet()) {
                if (room <= 0) break;
                if (now.machine.containsKey(row.getKey())) continue;
                machine.put(row.getKey(), row.getValue());
                room--;
                count++;
            }
        }
        if (count == 0) return Outcome.NOTHING_TO_ADD;
        machine.putAll(now.machine);
        ai.putAll(now.ai);
        try {
            backupBeforeRewrite(current);
            new TranslationFile(now.format, now.language, now.provider, machine, ai).write(current, true);
        } catch (IOException | RuntimeException e) {
            detail[0] = e.toString();
            return Outcome.IO_ERROR;
        }
        added[0] = count;
        return Outcome.MERGED;
    }

    /** A parse/validation failure of the file's content, as opposed to an I/O failure. */
    private static boolean isContentProblem(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return e.getCause() instanceof RuntimeException
                || message.startsWith("Invalid") || message.startsWith("Unsupported")
                || message.startsWith("Too many") || message.startsWith("Translation file exceeds");
    }

    /** Keeps a verbatim copy of {@code file} ({@value #BACKUP_SUFFIX}) before it is replaced. */
    private static void backupBeforeRewrite(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return;
        for (int index = 0; index < MAX_BACKUPS; index++) {
            Path candidate = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX
                    + (index == 0 ? "" : "." + index));
            if (!Files.exists(candidate)) {
                Files.copy(file, candidate);
                return;
            }
            if (Files.isRegularFile(candidate) && sameContent(file, candidate)) return;
        }
        throw new IOException("no free backup name for " + file.getFileName());
    }

    private static boolean sameContent(Path a, Path b) throws IOException {
        if (Files.size(a) != Files.size(b)) return false;
        try (InputStream first = Files.newInputStream(a); InputStream second = Files.newInputStream(b)) {
            byte[] left = new byte[1 << 16];
            byte[] right = new byte[1 << 16];
            int n;
            while ((n = first.read(left)) > 0) {
                int read = 0;
                while (read < n) {
                    int r = second.read(right, read, n - read);
                    if (r < 0) return false;
                    read += r;
                }
                for (int i = 0; i < n; i++) if (left[i] != right[i]) return false;
            }
            return true;
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

    private static Kind kindOf(String currentName) {
        String suffix = currentName.substring(CURRENT_PREFIX.length());
        return suffix.startsWith("-imported-") && suffix.endsWith(".json") ? Kind.IMPORTED : Kind.OTHER;
    }

    /** {@code nyanlex-legacy-merge.json}: which legacy files were already handled. */
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
