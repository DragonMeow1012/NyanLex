package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.cache.NamespacedStore;
import com.dragonmeow.nyanslate.cache.PersistentStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class NamespacedStoreTest {

    @Test
    void aiAndGtFailuresShareOneFileWithoutSharingState() {
        Map<String, String> rows = new HashMap<>();
        PersistentStore shared = mapStore(rows);
        PersistentStore ai = new NamespacedStore(shared, "AI");
        PersistentStore gt = new NamespacedStore(shared, "GT");

        ai.put("Hello world", "temporary:1:1000");
        gt.put("Hello world", "identity:2:2000");

        assertEquals("temporary:1:1000", ai.get("Hello world"));
        assertEquals("identity:2:2000", gt.get("Hello world"));
        assertEquals(2, rows.size());
        assertEquals("temporary:1:1000", rows.get("ai\u001fHello world"));
        assertEquals("identity:2:2000", rows.get("gt\u001fHello world"));

        ai.remove("Hello world");
        assertNull(ai.get("Hello world"));
        assertEquals("identity:2:2000", gt.get("Hello world"),
                "AI success/removal must not clear the GT retry record");
        assertFalse(rows.containsKey("ai\u001fHello world"));
    }

    private static PersistentStore mapStore(Map<String, String> rows) {
        return new PersistentStore() {
            @Override public String get(String key) { return rows.get(key); }
            @Override public void put(String key, String value) { rows.put(key, value); }
            @Override public void remove(String key) { rows.remove(key); }
            @Override public void clear() { rows.clear(); }
        };
    }
}
