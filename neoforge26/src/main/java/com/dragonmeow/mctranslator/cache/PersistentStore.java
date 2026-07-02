package com.dragonmeow.mctranslator.cache;

/**
 * A durable key→value store sitting behind the in-memory LRU. Keyed by the raw
 * source string; values are translations. Implementations may persist to disk.
 *
 * <p>An interface so {@link TranslationCache} can be unit-tested with an inline
 * in-memory fake instead of touching the filesystem.</p>
 */
public interface PersistentStore {

    /** @return the stored value, or {@code null} if absent. */
    String get(String key);

    /** Store (or overwrite) a value. */
    void put(String key, String value);

    /** Store many values at once (implementations may persist in a single I/O op). */
    default void putBatch(java.util.Map<String, String> entries) {
        entries.forEach(this::put);
    }

    /** Remove everything. */
    void clear();

    /** Remove a single key (best-effort). Default no-op for in-memory fakes. */
    default void remove(String key) {
    }
}
