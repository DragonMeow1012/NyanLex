package com.dragonmeow.mctranslator.cache;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permanent language-partitioned disk cache.
 *
 * <p>Each target language owns a separate JSON-Lines file, for example
 * {@code mctranslator-ai-cache-zh-tw.json}. Switching language only swaps the
 * active file; it never clears or rewrites another language's translations.</p>
 */
public final class LanguageFileStore implements PersistentStore {
    private final Path directory;
    private final String prefix;
    private final Map<String, FileStore> stores = new ConcurrentHashMap<>();
    private boolean legacyMigrationConsidered;
    private volatile String language;
    private volatile FileStore active;

    public LanguageFileStore(Path directory, String prefix, String initialLanguage) {
        this.directory = directory;
        this.prefix = prefix;
        setLanguage(initialLanguage);
    }

    @Override
    public synchronized void setLanguage(String targetLanguage) {
        String next = languageTag(targetLanguage);
        if (next.equals(language) && active != null) return;
        language = next;
        active = stores.computeIfAbsent(next, this::open);
    }

    public String language() {
        return language;
    }

    public Path activeFile() {
        return directory.resolve(prefix + "-" + language + ".json");
    }

    public static String languageTag(String language) {
        String raw = language == null ? "" : language.strip().replace('_', '-');
        if (raw.isEmpty()) raw = "zh-TW";
        String safe = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "zh-tw" : safe;
    }

    private FileStore current() {
        return active;
    }

    private FileStore open(String tag) {
        Path target = directory.resolve(prefix + "-" + tag + ".json");
        Path legacy = directory.resolve(prefix + ".json");
        // One-time, non-destructive migration: the old unpartitioned file belongs to
        // the language active during upgrade. Keep the original as a safety backup.
        boolean initialLanguage = !legacyMigrationConsidered;
        legacyMigrationConsidered = true;
        if (initialLanguage && !Files.exists(target) && Files.isRegularFile(legacy)) {
            try {
                Files.createDirectories(directory);
                Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException ignored) {
                // A failed copy simply starts an empty target-language cache.
            }
        }
        return new FileStore(target, false);
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

    /** Clear only the currently selected language. Other language files are permanent. */
    @Override public void clear() { current().clear(); }
}
