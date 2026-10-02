package com.dragonmeow.nyanslate.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A key-scoped view over one shared persistent store.  AI and GT failures live in the
 * same language file, but never share a key or clear each other's state.
 */
public final class NamespacedStore implements PersistentStore {
    private static final String SEPARATOR = "\u001F";

    private final PersistentStore delegate;
    private final String prefix;

    public NamespacedStore(PersistentStore delegate, String namespace) {
        if (delegate == null) throw new IllegalArgumentException("delegate");
        String name = namespace == null ? "" : namespace.strip().toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty()) throw new IllegalArgumentException("namespace");
        this.delegate = delegate;
        this.prefix = name + SEPARATOR;
    }

    private String key(String key) {
        return key == null ? null : prefix + key;
    }

    @Override public String get(String key) { return delegate.get(key(key)); }
    @Override public void put(String key, String value) { delegate.put(key(key), value); }
    @Override public void put(String key, String value, boolean provisional) {
        delegate.put(key(key), value, provisional);
    }
    @Override public boolean isProvisional(String key) { return delegate.isProvisional(key(key)); }

    @Override
    public void putBatch(Map<String, String> entries, Set<String> provisionalKeys) {
        if (entries == null || entries.isEmpty()) return;
        Map<String, String> scoped = new LinkedHashMap<>();
        Set<String> states = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String scopedKey = key(entry.getKey());
            scoped.put(scopedKey, entry.getValue());
            if (provisionalKeys != null && provisionalKeys.contains(entry.getKey())) {
                states.add(scopedKey);
            }
        }
        delegate.putBatch(scoped, states);
    }

    @Override public void remove(String key) { delegate.remove(key(key)); }

    @Override
    public void removeBatch(java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        delegate.removeBatch(keys.stream().map(this::key).toList());
    }

    @Override
    public Map<String, String> entries() {
        Map<String, String> scoped = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : delegate.entries().entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith(prefix)) {
                scoped.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return scoped;
    }

    /** Clear only this engine's rows in the shared ledger. */
    @Override
    public void clear() {
        delegate.removeBatch(delegate.entries().keySet().stream()
                .filter(key -> key != null && key.startsWith(prefix)).toList());
    }
    @Override public void setLanguage(String targetLanguage) { delegate.setLanguage(targetLanguage); }
}
