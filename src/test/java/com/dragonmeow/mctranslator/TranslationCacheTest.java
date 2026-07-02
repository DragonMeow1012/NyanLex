package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.PersistentStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationCacheTest {


    private static final Executor DIRECT = Runnable::run;


    private static Translator countingUpper(AtomicInteger counter) {
        return (text, target) -> {
            counter.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
    }

    @Test
    void blockingTranslatesThenCaches() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        assertEquals("T:hi", cache.translateBlocking("hi"));
        assertEquals("T:hi", cache.translateBlocking("hi")); 
        assertEquals(1, calls.get(), "second call must hit cache, not the translator");
        assertEquals("T:hi", cache.getCached("hi"));
    }

    @Test
    void blockingReturnsNullOnFailureAndDoesNotCache() {
        AtomicInteger calls = new AtomicInteger();

        Translator failing = (text, target) -> {
            calls.incrementAndGet();
            throw new TranslationException("boom");
        };
        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100);

        assertNull(cache.translateBlocking("hi"));
        assertNull(cache.getCached("hi"));

        assertNull(cache.translateBlocking("hi"));
        assertEquals(2, calls.get());
    }

    @Test
    void asyncWithDirectExecutorFillsCacheImmediately() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        assertNull(cache.getCached("hi"));
        cache.requestAsync("hi");
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(1, calls.get());
    }

    @Test
    void asyncIsDeduplicatedWhileInFlight() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();

        Executor manual = queue::add;
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", manual, 100);

        cache.requestAsync("hi");
        cache.requestAsync("hi"); 
        assertTrue(cache.isPending("hi"));
        assertEquals(1, queue.size(), "duplicate request must not enqueue a second task");

        queue.poll().run(); 
        assertEquals("T:hi", cache.getCached("hi"));
        assertFalse(cache.isPending("hi"));
        assertEquals(1, calls.get());
    }

    @Test
    void blockingDoesNotCorruptAsyncPending() {


        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);

        cache.requestAsync("hi");                 
        assertTrue(cache.isPending("hi"));

        cache.translateBlocking("hi");            
        assertTrue(cache.isPending("hi"),
                "blocking translate must leave the async pending entry intact");
    }

    @Test
    void failureBackoffSuppressesPerFrameRetries() {

        AtomicInteger calls = new AtomicInteger();
        Translator failing = (text, target) -> {
            calls.incrementAndGet();
            throw new TranslationException("boom");
        };
        long[] now = {1_000L};
        LongSupplier clock = () -> now[0];

        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100, 5_000L, clock);

        cache.requestAsync("hi");                 
        assertEquals(1, calls.get());
        cache.requestAsync("hi");                 
        cache.requestAsync("hi");
        assertEquals(1, calls.get(), "retries during backoff must be suppressed");

        now[0] = 6_000L;                          
        cache.requestAsync("hi");                 
        assertEquals(2, calls.get());
    }

    @Test
    void successClearsPreviousFailureBackoff() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] failNext = {true};
        Translator flaky = (text, target) -> {
            calls.incrementAndGet();
            if (failNext[0]) throw new TranslationException("boom");
            return new TranslationResult("T:" + text, "en");
        };
        long[] now = {0L};
        TranslationCache cache = new TranslationCache(flaky, "zh-TW", DIRECT, 100, 5_000L, () -> now[0]);

        cache.requestAsync("hi");                 
        failNext[0] = false;
        now[0] = 10_000L;                         
        cache.requestAsync("hi");                 
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(2, calls.get());
    }


    private static PersistentStore inlineStore(Map<String, String> backing) {
        return new PersistentStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void clear() { backing.clear(); }
            @Override public void remove(String key) { backing.remove(key); }
        };
    }

    @Test
    void diskTierRecoversEntriesEvictedFromMemory() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();

        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", DIRECT, 1, 10_000L, () -> 0L, inlineStore(disk));

        assertEquals("T:a", cache.translateBlocking("a")); 
        assertEquals("T:b", cache.translateBlocking("b")); 
        assertEquals(2, calls.get());


        assertEquals("T:a", cache.getCached("a"));
        assertEquals(2, calls.get(), "disk hit must not call the translator again");
        assertTrue(disk.containsKey("a") && disk.containsKey("b"));
    }

    @Test
    void diskHitPreventsRedundantAsyncTranslation() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("hi", "T:hi"); 
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", queue::add, 100, 10_000L, () -> 0L, inlineStore(disk));

        cache.requestAsync("hi");                 
        assertEquals(0, queue.size());
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(0, calls.get());
    }

    @Test
    void mojibakeDiskHitIsDiscardedAndRetranslated() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("hi", "\u83F4\uF8F0\u8782\uFF7D");
        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        assertNull(cache.getCached("hi"));
        assertFalse(disk.containsKey("hi"));

        assertEquals("T:hi", cache.translateBlocking("hi"));
        assertEquals(1, calls.get());
        assertEquals("T:hi", disk.get("hi"));
    }

    @Test
    void asyncCallbackFiresWithTranslation() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertEquals(List.of("T:hi"), got);
    }

    @Test
    void asyncCallbackFiresImmediatelyWhenAlreadyCached() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);
        cache.translateBlocking("hi"); 
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertEquals(List.of("T:hi"), got);
        assertEquals(1, calls.get(), "cached callback must not re-translate");
    }

    @Test
    void duplicateAlwaysAsyncRequestsShareOneBackendCallAndBothCallbacksFire() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);
        List<String> got = new ArrayList<>();

        cache.translateAsyncAlways("hi", got::add);
        cache.translateAsyncAlways("hi", got::add);

        assertTrue(cache.isPending("hi"));
        assertEquals(1, queue.size());
        queue.poll().run();

        assertEquals(List.of("T:hi", "T:hi"), got);
        assertEquals(1, calls.get());
        assertFalse(cache.isPending("hi"));
    }

    @Test
    void cachedAlwaysAsyncReturnsImmediatelyWithoutQueueing() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);
        cache.translateBlocking("hi");
        List<String> got = new ArrayList<>();

        cache.translateAsyncAlways("hi", got::add);

        assertEquals(List.of("T:hi"), got);
        assertEquals(0, queue.size());
        assertEquals(1, calls.get());
    }

    @Test
    void asyncCallbackNotFiredOnFailure() {
        Translator failing = (text, target) -> {
            throw new TranslationException("boom");
        };
        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertTrue(got.isEmpty(), "callback must not fire when translation fails");
    }

    @Test
    void lruEvictsOldestBeyondMaxSize() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 2);

        cache.translateBlocking("a");
        cache.translateBlocking("b");

        cache.getCached("a");
        cache.translateBlocking("c"); 

        assertEquals(2, cache.size());
        assertEquals("T:a", cache.getCached("a"));
        assertNull(cache.getCached("b"), "b should have been evicted as least-recently-used");
        assertEquals("T:c", cache.getCached("c"));
    }
}
