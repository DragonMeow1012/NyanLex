package com.dragonmeow.nyanslate.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** A namespaced store whose namespace follows a live setting. */
public final class DynamicNamespacedStore implements PersistentStore {
    private static final String SEPARATOR = "\u001F";
    private final PersistentStore delegate;
    private final Supplier<String> namespace;

    public DynamicNamespacedStore(PersistentStore delegate, Supplier<String> namespace) {
        if (delegate == null) throw new IllegalArgumentException("delegate");
        this.delegate = delegate;
        this.namespace = namespace;
    }

    private String prefix() {
        String value;
        try { value = namespace == null ? null : namespace.get(); }
        catch (RuntimeException ignored) { value = null; }
        String name = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty()) name = "default";
        return name + SEPARATOR;
    }

    private String key(String key) { return key == null ? null : prefix() + key; }
    @Override public String get(String key) { return delegate.get(key(key)); }
    @Override public void put(String key, String value) { delegate.put(key(key), value); }
    @Override public void put(String key, String value, boolean provisional) {
        delegate.put(key(key), value, provisional);
    }
    @Override public boolean isProvisional(String key) { return delegate.isProvisional(key(key)); }

    @Override public void putBatch(Map<String, String> entries, Set<String> provisionalKeys) {
        if (entries == null || entries.isEmpty()) return;
        Map<String, String> scoped = new LinkedHashMap<>();
        Set<String> states = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String scopedKey = key(entry.getKey());
            scoped.put(scopedKey, entry.getValue());
            if (provisionalKeys != null && provisionalKeys.contains(entry.getKey())) states.add(scopedKey);
        }
        delegate.putBatch(scoped, states);
    }

    @Override public void remove(String key) { delegate.remove(key(key)); }
    @Override public void removeBatch(java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        delegate.removeBatch(keys.stream().map(this::key).toList());
    }
    @Override public Map<String, String> entries() {
        String active = prefix();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : delegate.entries().entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith(active)) {
                out.put(entry.getKey().substring(active.length()), entry.getValue());
            }
        }
        return out;
    }
    @Override public void clear() {
        String active = prefix();
        delegate.removeBatch(delegate.entries().keySet().stream()
                .filter(key -> key != null && key.startsWith(active)).toList());
    }
    @Override public void setLanguage(String targetLanguage) { delegate.setLanguage(targetLanguage); }
}
