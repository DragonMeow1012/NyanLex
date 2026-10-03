package com.dragonmeow.nyanlex.cache;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Permanent language-partitioned disk cache.
 *
 * <p>Each target language owns a separate JSON-Lines file, for example
 * {@code nyanlex-ai-cache-zh-tw.json}. Switching language only swaps the
 * active file; it never clears or rewrites another language's translations.</p>
 */
public final class LanguageFileStore implements PersistentStore {
    private final Path directory;
    private final String prefix;
    private final int maxEntries;
    private final boolean allowLegacyMigration;
    private boolean legacyMigrationConsidered;
    private volatile ActivePartition active;

    public LanguageFileStore(Path directory, String prefix, String initialLanguage) {
        this(directory, prefix, initialLanguage, FileStore.DEFAULT_MAX_ENTRIES);
    }

    public LanguageFileStore(Path directory, String prefix, String initialLanguage, int maxEntries) {
        this(directory, prefix, initialLanguage, maxEntries, true);
    }

    LanguageFileStore(Path directory, String prefix, String initialLanguage, int maxEntries,
            boolean allowLegacyMigration) {
        this.directory = directory;
        this.prefix = prefix;
        this.maxEntries = maxEntries;
        this.allowLegacyMigration = allowLegacyMigration;
        setLanguage(initialLanguage);
    }

    @Override
    public synchronized void setLanguage(String targetLanguage) {
        String next = languageTag(targetLanguage);
        ActivePartition current = active;
        if (current != null && next.equals(current.language())) return;
        // Retain only the active partition. Switching back reloads its journal
        // from disk instead of pinning every language's full cache in memory.
        active = new ActivePartition(next, open(next));
    }

    public String language() {
        ActivePartition current = active;
        return current == null ? languageTag(null) : current.language();
    }

    public Path activeFile() {
        return directory.resolve(prefix + "-" + language() + ".json");
    }

    /** Diagnostic used by regression tests for the bounded retention contract. */
    public int retainedStoreCount() {
        return active == null ? 0 : 1;
    }

    public static String languageTag(String language) {
        String raw = language == null ? "" : language.strip().replace('_', '-');
        if (raw.isEmpty()) raw = "zh-TW";
        String safe = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "zh-tw" : safe;
    }

    private FileStore current() {
        return active.store();
    }

    private FileStore open(String tag) {
        Path target = directory.resolve(prefix + "-" + tag + ".json");
        Path legacy = directory.resolve(prefix + ".json");
        // One-time, non-destructive migration: the old unpartitioned file belongs to
        // the language active during upgrade. Keep the original as a safety backup.
        boolean initialLanguage = allowLegacyMigration && !legacyMigrationConsidered;
        legacyMigrationConsidered = true;
        if (initialLanguage && !Files.exists(target) && Files.isRegularFile(legacy)) {
            try {
                Files.createDirectories(directory);
                Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException ignored) {
                // A failed copy simply starts an empty target-language cache.
            }
        }
        return new FileStore(target, false, maxEntries);
    }

    @Override public String get(String key) { return current().get(key); }
    @Override public void put(String key, String value) { current().put(key, value); }
    @Override public void put(String key, String value, boolean provisional) {
        current().put(key, value, provisional);
    }
    @Override public boolean isProvisional(String key) { return current().isProvisional(key); }
    @Override public void putBatch(Map<String, String> entries, Set<String> provisionalKeys) {
        current().putBatch(entries, provisionalKeys);
    }
    @Override public void remove(String key) { current().remove(key); }
    @Override public void removeBatch(java.util.Collection<String> keys) {
        current().removeBatch(keys);
    }
    @Override public Map<String, String> provisionalEntries() {
        return current().provisionalEntries();
    }
    @Override public Map<String, String> entries() { return current().entries(); }
    @Override public int size() { return current().size(); }

    /** Clear only the currently selected language. Other language files are permanent. */
    @Override public void clear() { current().clear(); }

    private record ActivePartition(String language, FileStore store) {
    }
}
