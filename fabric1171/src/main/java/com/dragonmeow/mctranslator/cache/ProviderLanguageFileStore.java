package com.dragonmeow.mctranslator.cache;

import com.dragonmeow.mctranslator.config.MachineTranslationProvider;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Language cache additionally partitioned by the selected machine provider.
 * Google deliberately keeps the historical filename; experimental providers use
 * their own sibling files, so switching never deletes or silently reuses another
 * provider's wording.
 */
public final class ProviderLanguageFileStore implements PersistentStore {
    private final Path directory;
    private final String basePrefix;
    private final Supplier<String> provider;
    private final Map<String, LanguageFileStore> stores = new ConcurrentHashMap<>();
    private volatile String language;

    public ProviderLanguageFileStore(Path directory, String basePrefix,
                                     String initialLanguage, Supplier<String> provider) {
        this.directory = directory;
        this.basePrefix = basePrefix;
        this.language = initialLanguage;
        this.provider = provider;
    }

    private String providerId() {
        String value;
        try { value = provider == null ? null : provider.get(); }
        catch (RuntimeException ignored) { value = null; }
        return MachineTranslationProvider.normalize(value);
    }

    private LanguageFileStore current() {
        String id = providerId();
        return stores.computeIfAbsent(id, key -> new LanguageFileStore(
                directory,
                MachineTranslationProvider.GOOGLE.id().equals(key)
                        ? basePrefix : basePrefix + "-" + key,
                language));
    }

    @Override public synchronized void setLanguage(String targetLanguage) {
        language = targetLanguage;
        for (LanguageFileStore store : stores.values()) store.setLanguage(targetLanguage);
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
    @Override public void removeBatch(java.util.Collection<String> keys) { current().removeBatch(keys); }
    @Override public Map<String, String> provisionalEntries() { return current().provisionalEntries(); }
    @Override public Map<String, String> entries() { return current().entries(); }
    @Override public void clear() { current().clear(); }
}
