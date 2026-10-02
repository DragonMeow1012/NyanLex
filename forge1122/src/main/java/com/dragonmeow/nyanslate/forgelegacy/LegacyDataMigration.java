package com.dragonmeow.nyanslate.forgelegacy;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * One-time, non-destructive migration of config/cache files left over from the
 * mod's old "mctranslator" identity (pre-Nyanslate rename) to the current
 * "nyanslate" one.
 *
 * <p>Every matching file is <em>copied</em>, never moved or deleted, and only
 * when the new-prefixed target does not already exist - so this is safe to
 * call on every startup: the first run copies, every run after that is a
 * no-op (Files.exists check) without needing a separate "already migrated"
 * flag. Directories (notably {@code mctranslator-codex-home} /
 * {@code mctranslator-codex-workspace}) are deliberately skipped: codex-home
 * is excluded from migration by design (fresh Codex login is expected after
 * a mod rename), and this scan simply never descends into any directory.</p>
 *
 * <p>Pure {@code java.nio}, Java 8 source compatible (no var/records/text
 * blocks) so it compiles unmodified on every legacy target. This is a
 * deliberate duplicate of the modern core's
 * {@code com.dragonmeow.nyanslate.config.LegacyDataMigration} - the legacy
 * trees don't share that package, only this one (mirrored to forge1122 /
 * forge1132 by verification/sync-legacy-forge-core.ps1; fabric1152 /
 * fabric1165 carry a manual copy like the rest of this package).</p>
 */
public final class LegacyDataMigration {
    /** Old product identifier. Deliberately its own constant - never written as
     *  a plain {@code "mctranslator"} literal elsewhere - so a future mechanical
     *  rename pass has nothing to accidentally catch here. */
    public static final String LEGACY_PREFIX = "mctranslator";

    /** Current product identifier that every migrated file name is rewritten to. */
    public static final String CURRENT_PREFIX = "nyanslate";

    private LegacyDataMigration() {
    }

    /**
     * Scans {@code configDir} for regular files whose name starts with
     * {@link #LEGACY_PREFIX} (either exactly {@code "mctranslator.json"} or
     * {@code "mctranslator-<anything>"}) and copies each one to the equivalent
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path entry : stream) {
                String newName = currentPrefixedName(entry);
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
            // a device going away) — unlike every other failure path here it wraps an
            // IOException but is itself a RuntimeException, so it is NOT an IOException and
            // would otherwise escape this method entirely, breaking the "Never throws" promise
            // documented above and potentially crashing startup at every one of this class's
            // 16 production call sites.
            if (log != null) log.accept("legacy data migration scan failed: " + e);
        }
        return migrated;
    }

    /** Returns the {@link #CURRENT_PREFIX}-renamed basename for a legacy-prefixed
     *  entry, or {@code null} if the entry's name doesn't start with
     *  {@link #LEGACY_PREFIX} at a name-segment boundary (i.e. is exactly
     *  {@code "mctranslator.json"} or starts with {@code "mctranslator-"}). */
    static String currentPrefixedName(Path entry) {
        String name = entry.getFileName().toString();
        if (!name.startsWith(LEGACY_PREFIX)) return null;
        String suffix = name.substring(LEGACY_PREFIX.length());
        if (!(suffix.equals(".json") || suffix.startsWith("-"))) return null;
        return CURRENT_PREFIX + suffix;
    }
}
