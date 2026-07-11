package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request-count behaviour of the per-tick coalescer: settle window, shared batches,
 *  key normalisation and the cross-engine fallback. All collaborators are inline fakes. */
class TranslationCacheCoalescingTest {

    private static final Executor DIRECT = Runnable::run;

    /** Inline translator that counts REQUESTS (not items) and echoes per line. */
    private static final class CountingBatchTranslator implements Translator {
        final AtomicInteger requests = new AtomicInteger();
        final List<List<String>> batches = new ArrayList<>();

        @Override
        public TranslationResult translate(String text, String targetLang) {
            requests.incrementAndGet();
            batches.add(List.of(text));
            return new TranslationResult("T:" + text, "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
            requests.incrementAndGet();
            batches.add(new ArrayList<>(texts));
            List<TranslationResult> out = new ArrayList<>();
            for (String t : texts) out.add(new TranslationResult("T:" + t, "en"));
            return out;
        }
    }

    /** flushBatch holds while the buffer is still growing, then sends ONE request. */
    @Test
    void settleWindowMergesAScreenIntoOneRequest() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);

        cache.requestBatched("Alpha");
        cache.flushBatch();                    // tick 1: buffer grew this tick -> hold
        assertEquals(0, t.requests.get());

        cache.requestBatched("Beta");          // screen still populating
        cache.flushBatch();                    // tick 2: grew again -> hold
        assertEquals(0, t.requests.get());

        cache.flushBatch();                    // tick 3: stable -> send everything at once
        assertEquals(1, t.requests.get(), "both strings must share one request");
        assertEquals("T:Alpha", cache.getCached("Alpha"));
        assertEquals("T:Beta", cache.getCached("Beta"));
    }

    /** A continuously-growing buffer is force-flushed after the wait cap. */
    @Test
    void continuousGrowthIsForceFlushedAfterCap() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);

        for (int tick = 0; tick < 10 && t.requests.get() == 0; tick++) {
            cache.requestBatched("Line " + tick); // something new every tick
            cache.flushBatch();
        }
        assertTrue(t.requests.get() >= 1, "the settle window must not starve the flush forever");
    }

    /** Chat callbacks join the same tick batch as render misses: one request total. */
    @Test
    void chatAndRenderMissesShareOneRequest() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();

        cache.requestBatched("Tooltip line");
        cache.requestCoalesced("Hello everyone", got::add, true);
        cache.requestCoalesced("Welcome!", got::add, true);
        cache.flushBatch();                    // grew -> hold
        cache.flushBatch();                    // stable -> ONE request for all three

        assertEquals(1, t.requests.get());
        assertEquals(3, t.batches.get(0).size());
        // Callback order across different keys is unspecified (concurrent map iteration).
        assertEquals(java.util.Set.of("T:Hello everyone", "T:Welcome!"), new java.util.HashSet<>(got));
        assertEquals(2, got.size());
        assertEquals("T:Tooltip line", cache.getCached("Tooltip line"));
    }

    /** Duplicate coalesced requests for the same text share one line and both hear back. */
    @Test
    void duplicateCoalescedRequestsShareOneLine() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello", got::add, true);
        cache.requestCoalesced("Hello", got::add, true);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(1, t.requests.get());
        assertEquals(1, t.batches.get(0).size(), "one text must occupy one request line");
        assertEquals(List.of("T:Hello", "T:Hello"), got);
    }

    /** Failed coalesced requests still fire always-callbacks (with null). */
    @Test
    void coalescedFailureDeliversNullToAlwaysCallbacks() {
        Translator failing = (text, target) -> {
            throw new TranslationException("boom");
        };
        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello", got::add, true);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(1, got.size());
        assertNull(got.get(0), "always-callback must fire with null on failure");
    }

    @Test
    void clearingAQueuedRequestCompletesItsAlwaysCallback() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello", got::add, true);
        cache.clear();

        assertEquals(1, got.size());
        assertNull(got.get(0));
        assertEquals(0, cache.pendingCount());
        assertEquals(0, t.requests.get());
    }

    /** Outer whitespace variants share one cache entry and one request. */
    @Test
    void whitespaceVariantsShareOneRequest() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);

        cache.requestBatched("  Diamond Sword  ");
        cache.requestBatched("Diamond Sword");
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(1, t.requests.get());
        assertEquals(1, t.batches.get(0).size(), "trim variants must collapse to one line");
        assertEquals("T:Diamond Sword", cache.getCached("Diamond Sword"));
        assertEquals("T:Diamond Sword", cache.getCached("  Diamond Sword  "));
    }

    /** Numeric variants share one templated request; values are restored per variant. */
    @Test
    void numberVariantsShareOneTemplatedRequest() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);

        cache.requestBatched("You got 5 coins");
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(1, t.requests.get());

        // A different number must be a pure cache hit (restored from the template).
        String other = cache.getCached("You got 99 coins");
        assertEquals(1, t.requests.get(), "template hit must not issue a new request");
        assertTrue(other != null && other.contains("99"), "value must be restored: " + other);
    }

    /** The Google cache reuses a translation the AI cache already has (one-way fallback). */
    @Test
    void fallbackCacheReusesSiblingTranslation() {
        CountingBatchTranslator google = new CountingBatchTranslator();
        CountingBatchTranslator ai = new CountingBatchTranslator();
        TranslationCache googleCache = new TranslationCache(google, "zh-TW", DIRECT, 100);
        TranslationCache aiCache = new TranslationCache(ai, "zh-TW", DIRECT, 100);
        googleCache.setFallback(aiCache);

        assertEquals("T:Hello", aiCache.translateBlocking("Hello")); // AI has it
        assertEquals("T:Hello", googleCache.getCached("Hello"), "google cache must reuse the AI result");
        assertEquals(0, google.requests.get(), "no Google request may be spent on it");

        // And the reuse also suppresses queued requests for the same text.
        googleCache.requestBatched("Hello");
        googleCache.flushBatch();
        googleCache.flushBatch();
        assertEquals(0, google.requests.get());
    }

    /** In-flight coalesced keys must absorb late callbacks instead of re-requesting. */
    @Test
    void lateCallbackAttachesToInFlightBatch() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        TranslationCache cache = new TranslationCache(t, "zh-TW", queue::add, 100);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello", got::add, true);
        cache.flushBatch();
        cache.flushBatch();                    // batch handed to the (manual) worker
        assertEquals(1, queue.size());

        cache.requestCoalesced("Hello", got::add, true); // arrives while in flight
        queue.poll().run();                    // worker completes

        assertEquals(1, t.requests.get());
        assertEquals(List.of("T:Hello", "T:Hello"), got, "both callbacks must hear the one result");
    }
}
