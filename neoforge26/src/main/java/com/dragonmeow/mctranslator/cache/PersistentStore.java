package com.dragonmeow.mctranslator.cache;

/**
 * A durable key→value store sitting behind the in-memory LRU. Keys are canonical
 * translation-template strings; values are translated templates. Implementations
 * may persist to disk.
 *
 * <p>An interface so {@link TranslationCache} can be unit-tested with an inline
 * in-memory fake instead of touching the filesystem.</p>
 */
public interface PersistentStore {

    /** @return the stored value, or {@code null} if absent. */
    String get(String key);

    /** Store (or overwrite) a value. */
    void put(String key, String value);

    /** Store a value with its PROVISIONAL flag (a GT stand-in awaiting an AI redo).
     *  Default ignores the flag, so in-memory fakes and old implementations still work. */
    default void put(String key, String value, boolean provisional) {
        put(key, value);
    }

    /** Whether the stored value under {@code key} is provisional. Default: final. */
    default boolean isProvisional(String key) {
        return false;
    }

    /** Store many values at once (implementations may persist in a single I/O op). */
    default void putBatch(java.util.Map<String, String> entries) {
        entries.forEach(this::put);
    }

    /** {@link #putBatch(java.util.Map)} with the subset of keys whose values are provisional. */
    default void putBatch(java.util.Map<String, String> entries, java.util.Set<String> provisionalKeys) {
        for (java.util.Map.Entry<String, String> e : entries.entrySet()) {
            put(e.getKey(), e.getValue(), provisionalKeys != null && provisionalKeys.contains(e.getKey()));
        }
    }

    /** Remove everything. */
    void clear();

    /** Remove a single key (best-effort). Default no-op for in-memory fakes. */
    default void remove(String key) {
    }
}
