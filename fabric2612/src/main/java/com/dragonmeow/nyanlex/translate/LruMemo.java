package com.dragonmeow.nyanlex.translate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Bounded memo for a PURE function of a string, for work the render path repeats every frame
 * on the same few hundred strings (regex sweeps that strip or classify a line).
 *
 * <p>Least-recently-used, evicting a single entry when full (never a whole-table clear, so a
 * full memo cannot cause a next-frame cost spike). The function runs outside the lock, so a
 * slow computation never blocks other threads; two threads may then compute the same value,
 * which is harmless because the function is pure. The memoised values must be immutable.</p>
 */
public final class LruMemo<V> {
    private final Map<String, V> map;

    public LruMemo(int maxEntries) {
        this.map = new LinkedHashMap<String, V>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** The memoised value of {@code compute} for {@code key}, computing and remembering it on a miss. */
    public V get(String key, Function<String, V> compute) {
        synchronized (map) {
            V hit = map.get(key);
            if (hit != null) return hit;
        }
        V computed = compute.apply(key);
        if (computed != null) {
            synchronized (map) {
                map.put(key, computed);
            }
        }
        return computed;
    }

    public int size() {
        synchronized (map) {
            return map.size();
        }
    }
}
