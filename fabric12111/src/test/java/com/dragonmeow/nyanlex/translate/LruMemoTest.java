package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded memo behind the per-frame pure-function caches. */
class LruMemoTest {

    @Test
    void computesOncePerKeyAndReturnsTheSameInstance() {
        LruMemo<String> memo = new LruMemo<>(10);
        AtomicInteger calls = new AtomicInteger();
        String first = memo.get("key", k -> {
            calls.incrementAndGet();
            return new String("value of " + k);
        });
        for (int i = 0; i < 100; i++) assertSame(first, memo.get("key", k -> {
            calls.incrementAndGet();
            return "never";
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryOneAtATime() {
        LruMemo<String> memo = new LruMemo<>(3);
        for (String key : new String[] {"a", "b", "c"}) memo.get(key, k -> k.toUpperCase());
        memo.get("a", k -> "recomputed"); // touch: "b" is now the oldest
        memo.get("d", k -> "D");           // over the bound: evicts exactly "b"
        assertEquals(3, memo.size());

        AtomicInteger calls = new AtomicInteger();
        memo.get("a", k -> { calls.incrementAndGet(); return "x"; });
        memo.get("c", k -> { calls.incrementAndGet(); return "x"; });
        memo.get("d", k -> { calls.incrementAndGet(); return "x"; });
        assertEquals(0, calls.get(), "the recently used entries were kept");
        memo.get("b", k -> { calls.incrementAndGet(); return "B again"; });
        assertEquals(1, calls.get(), "only the least recently used one was dropped");
    }

    @Test
    void aNullResultIsNotRemembered() {
        LruMemo<String> memo = new LruMemo<>(4);
        AtomicInteger calls = new AtomicInteger();
        assertNull(memo.get("k", k -> { calls.incrementAndGet(); return null; }));
        assertNull(memo.get("k", k -> { calls.incrementAndGet(); return null; }));
        assertEquals(2, calls.get());
        assertEquals(0, memo.size());
    }

    @Test
    void staysWithinItsBoundUnderManyKeysAndThreads() throws Exception {
        LruMemo<Integer> memo = new LruMemo<>(512);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int seed = t;
                done.add(pool.submit(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        int key = (i * 31 + seed) % 5_000;
                        assertEquals(Integer.valueOf(key), memo.get("k" + key, k -> Integer.valueOf(k.substring(1))));
                    }
                }));
            }
            for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(memo.size() <= 512, "size was " + memo.size());
    }
}
