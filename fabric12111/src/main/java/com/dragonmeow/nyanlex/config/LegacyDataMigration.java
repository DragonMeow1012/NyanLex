package com.dragonmeow.nyanlex.config;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * One-time, non-destructive migration of config/cache files left over from the
 * mod's two earlier identities ("nyanslate" and, before that, "mctranslator") to
 * the current "nyanlex" one.
 *
 * <p>Every matching file is <em>copied</em>, never moved or deleted, and only
 * when the new-prefixed target does not already exist - so this is safe to
 * call on every startup: the first run copies, every run after that is a
 * no-op (Files.exists check) without needing a separate "already migrated"
 * flag. When both generations of a file exist the newer one ("nyanslate") is
 * copied first, so it wins and the older copy is then skipped because the
 * target exists. Directories (notably the codex-home / codex-workspace
 * folders) are deliberately skipped: codex-home is excluded from migration by
 * design (a fresh Codex login is expected after a rename), and this scan
 * simply never descends into any directory.</p>
 *
 * <p>Pure {@code java.nio}, Java 8 source compatible, no Minecraft/loader API,
 * so the same code is carried by every tree (the legacy and forgelegacy
 * packages hold their own copy).</p>
 */
public final class LegacyDataMigration {
    /** Earlier product identifiers, newest first. Defined only here so no other
     *  source file needs to spell an old name. */
    public static final String[] LEGACY_PREFIXES = {"nyanslate", "mctranslator"};

    /** Current product identifier that every migrated file name is rewritten to. */
    public static final String CURRENT_PREFIX = "nyanlex";

    private LegacyDataMigration() {
    }

    /**
     * Scans {@code configDir} for regular files whose name starts with one of
     * {@link #LEGACY_PREFIXES} (either exactly {@code "<prefix>.json"} or
     * {@code "<prefix>-<anything>"}) and copies each one to the equivalent
     * {@link #CURRENT_PREFIX}-named file, skipping any file whose target
     * already exists. Never throws: any I/O failure is reported through
     * {@code log} (if non-null) and otherwise swallowed, since migration must
     * never block startup.
     *
     * @return the number of files actually copied.
     */
    public static int migrate(Path configDir, Consumer<String> log) {
        if (configDir == null || !Files.isDirectory(configDir)) return 0;
        int migrated = 0;
        for (String legacyPrefix : LEGACY_PREFIXES) {
            migrated += migrateOne(configDir, legacyPrefix, log);
        }
        return migrated;
    }

    private static int migrateOne(Path configDir, String legacyPrefix, Consumer<String> log) {
        int migrated = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path entry : stream) {
                String newName = currentPrefixedName(entry, legacyPrefix);
                if (newName == null) continue;
                if (!Files.isRegularFile(entry)) continue; // skips codex-home/codex-workspace dirs
                Path target = configDir.resolve(newName);
                if (Files.exists(target)) continue;
                try {
                    Files.copy(entry, target, StandardCopyOption.COPY_ATTRIBUTES);
                    migrated++;
                    if (log != null) {
                        log.accept("migrated legacy data file: " + entry.getFileName() + " -> " + newName);
                    }
                } catch (IOException e) {
                    if (log != null) {
                        log.accept("legacy data migration failed for " + entry.getFileName() + ": " + e);
                    }
                }
            }
        } catch (IOException e) {
            if (log != null) log.accept("legacy data migration scan failed: " + e);
        } catch (DirectoryIteratorException e) {
            // Thrown by the enhanced-for's hasNext()/next() mid-iteration (e.g. a locked file,
            // a device going away). It wraps an IOException but is itself a RuntimeException,
            // so it must be caught here to keep the "never throws" promise at every call site.
            if (log != null) log.accept("legacy data migration scan failed: " + e);
        }
        return migrated;
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
}
