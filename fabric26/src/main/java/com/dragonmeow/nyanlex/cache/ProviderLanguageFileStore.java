package com.dragonmeow.nyanlex.cache;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Language cache for the machine-translation source. Google is the only source, so
 * this always resolves to the historical filename; the partition logic is kept so an
 * old config naming a removed source keeps reading the same file.
 */
public final class ProviderLanguageFileStore implements PersistentStore {
    private final Path directory;
    private final String basePrefix;
    private final Supplier<String> provider;
    private final int maxEntries;
    /** Normalized provider ids are a fixed one-value domain. */
    private final Set<String> legacyMigrationConsidered = new HashSet<>();
    private volatile String language;
    private volatile ActivePartition active;

    public ProviderLanguageFileStore(Path directory, String basePrefix,
                                     String initialLanguage, Supplier<String> provider) {
        this(directory, basePrefix, initialLanguage, provider, FileStore.DEFAULT_MAX_ENTRIES);
    }

    public ProviderLanguageFileStore(Path directory, String basePrefix,
                                     String initialLanguage, Supplier<String> provider, int maxEntries) {
        this.directory = directory;
        this.basePrefix = basePrefix;
        this.language = initialLanguage;
        this.provider = provider;
        this.maxEntries = maxEntries;
        // Open the initial partition during service construction, where
        // migration/startup compaction belongs, rather than on the first UI
        // cache lookup.
        current();
    }

    private String providerId() {
        String value;
        try { value = provider == null ? null : provider.get(); }
        catch (RuntimeException ignored) { value = null; }
        return MachineTranslationProvider.normalize(value);
    }

    private LanguageFileStore current() {
        String id = providerId();
        ActivePartition current = active;
        if (current != null && id.equals(current.providerId())) {
            return current.store();
        }
        synchronized (this) {
            current = active;
            if (current == null || !id.equals(current.providerId())) {
                boolean allowLegacyMigration = legacyMigrationConsidered.add(id);
                LanguageFileStore next = new LanguageFileStore(
                        directory,
                        MachineTranslationProvider.GOOGLE.id().equals(id)
                                ? basePrefix : basePrefix + "-" + id,
                        language,
                        maxEntries,
                        allowLegacyMigration);
                current = new ActivePartition(id, next);
                active = current;
            }
            return current.store();
        }
    }

    @Override public synchronized void setLanguage(String targetLanguage) {
        language = targetLanguage;
        ActivePartition current = active;
        if (current != null) current.store().setLanguage(targetLanguage);
    }

    /** Diagnostic used by regression tests for the bounded retention contract. */
    public int retainedStoreCount() { return active == null ? 0 : 1; }

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
    @Override public void removeBatch(java.util.Collection<String> keys) { current().removeBatch(keys); }
    @Override public Map<String, String> provisionalEntries() { return current().provisionalEntries(); }
    @Override public Map<String, String> entries() { return current().entries(); }
    @Override public void clear() { current().clear(); }

    private record ActivePartition(String providerId, LanguageFileStore store) {
    }
}
