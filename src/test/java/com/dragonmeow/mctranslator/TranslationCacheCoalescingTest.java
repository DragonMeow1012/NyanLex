package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.PriorityTranslationExecutor;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    @Test
    void configuredWindowCollectsUntilItsDeadline() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        long[] now = {1_000L};
        TranslationCache cache = new TranslationCache(
                t, "zh-TW", DIRECT, 100, 10_000L, () -> now[0]);
        cache.setBatchWindowMs(() -> 5_000);

        cache.requestBatched("Alpha");
        cache.flushBatch();
        now[0] += 4_999L;
        cache.requestBatched("Beta");
        cache.flushBatch();
        assertEquals(0, t.requests.get());

        now[0] += 1L;
        cache.flushBatch();
        assertEquals(1, t.requests.get());
        assertEquals(List.of("Alpha", "Beta"), t.batches.get(0));
    }

    @Test
    void backgroundWarmupsAlsoJoinTheConfiguredWindow() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        long[] now = {1_000L};
        TranslationCache cache = new TranslationCache(
                t, "zh-TW", DIRECT, 100, 10_000L, () -> now[0]);
        cache.setBatchWindowMs(() -> 5_000);

        cache.warmBatchAsync(List.of("Witch Hazel Boat"));
        now[0] += 1_000L;
        cache.warmBatchAsync(List.of("Zelkova Stairs"));
        cache.flushBatch();
        assertEquals(0, t.requests.get());

        now[0] = 6_000L;
        cache.flushBatch();
        assertEquals(1, t.requests.get());
        assertEquals(List.of("Witch Hazel Boat", "Zelkova Stairs"),
                t.batches.get(0));
    }

    @Test
    void cachedWarmBatchDoesNotInspectUnusedContext() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        assertEquals("T:Hello", cache.translateBlocking("Hello"));
        List<String> unusedContext = new java.util.AbstractList<>() {
            @Override public String get(int index) {
                throw new AssertionError("cached warmup must not inspect context rows");
            }

            @Override public int size() {
                throw new AssertionError("cached warmup must not inspect context size");
            }
        };

        assertDoesNotThrow(() -> cache.warmBatchAsync(List.of("Hello"), unusedContext));
        assertEquals(1, t.requests.get(), "a fully cached warmup schedules no backend work");
    }

    @Test
    void zeroConfiguredWindowSendsOnNextTick() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        cache.setBatchWindowMs(() -> 0);

        cache.requestBatched("Alpha");
        cache.flushBatch();

        assertEquals(1, t.requests.get());
    }

    @Test
    void characterBudgetFlushesBeforeDeadline() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        cache.setBatchWindowMs(() -> 10_000);

        cache.requestBatched("A".repeat(700));
        cache.requestBatched("B".repeat(700));
        cache.flushBatch();

        assertEquals(1, t.requests.get());
        assertEquals(1, t.batches.get(0).size(),
                "the collector must cut between entries before exceeding the safety budget");
        assertEquals("A".repeat(700), t.batches.get(0).get(0));
        assertEquals(1, cache.pendingCount(), "the next complete entry remains queued, not truncated");

        cache.setBatchWindowMs(() -> 0);
        cache.flushBatch();
        assertEquals(2, t.requests.get());
        assertEquals(List.of("B".repeat(700)), t.batches.get(1));
    }

    @Test
    void singleEntryOverBudgetIsStillSentWhole() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        cache.setBatchWindowMs(() -> 5_000);
        String completeName = "A".repeat(1_501);

        cache.requestBatched(completeName);
        cache.flushBatch();

        assertEquals(1, t.requests.get());
        assertEquals(List.of(completeName), t.batches.get(0),
                "an oversized item name is atomic and must never be truncated");
    }

    @Test
    void shortEntriesAreNotSplitAtTheOldSixtyFourItemLimit() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 200);
        cache.setBatchWindowMs(() -> 5_000);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            String name = "x" + (char) (0x0100 + i);
            names.add(name);
            cache.requestBatched(name);
        }

        cache.setBatchWindowMs(() -> 0);
        cache.flushBatch();

        assertEquals(1, t.requests.get());
        assertEquals(names, t.batches.get(0));
    }

    @Test
    void hoveredEntryFlushesPendingCollectorFirstInOneRequest() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        cache.setBatchWindowMs(() -> 5_000);

        cache.warmBatchAsync(List.of("Normal A", "Normal B"));
        cache.warmBatchAsyncHigh(List.of("Hovered item"), List.of("Hovered item"));
        assertEquals(0, t.requests.get(), "hover waits only until the next client tick");

        cache.flushBatch();

        assertEquals(1, t.requests.get(), "hover must not buy a second HTTP batch");
        assertEquals(List.of("Hovered item", "Normal A", "Normal B"), t.batches.get(0));
    }

    @Test
    void failedSurfaceDoesNotRetryAfterItDisappears() {
        AtomicInteger requests = new AtomicInteger();
        long[] now = {0L};
        Translator offline = (text, target) -> {
            requests.incrementAndGet();
            throw new TranslationException("offline");
        };
        TranslationCache cache = new TranslationCache(
                offline, "zh-TW", DIRECT, 100, 1_000L, () -> now[0]);
        cache.setBatchWindowMs(() -> 0);

        cache.requestBatchedPassive("Visible once");
        cache.flushBatch();
        assertEquals(1, requests.get());

        now[0] = 10_000L;
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(1, requests.get(),
                "ticks alone must not resurrect a vanished tooltip failure");

        cache.requestBatchedPassive("Visible once");
        cache.flushBatch();
        assertEquals(2, requests.get(), "seeing the same surface again permits one retry");
    }

    @Test
    void synchronousBatchReportsPartialFailure() {
        Translator partial = new Translator() {
            @Override
            public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("T:" + text, "en");
            }

            @Override
            public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
                return List.of(new TranslationResult("T:" + texts.get(0), "en"),
                        new TranslationResult(texts.get(1), "en"));
            }
        };
        TranslationCache cache = new TranslationCache(partial, "zh-TW", DIRECT, 100);

        assertFalse(cache.warmBatch(List.of("Alpha", "Beta")));
    }

    @Test
    void queueHasAHardEntryLimit() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 1_000);
        cache.setBatchWindowMs(() -> 10_000);

        for (int i = 0; i < 2_000; i++) {
            String unique = "Dynamic row "
                    + (char) ('A' + i / (26 * 26) % 26)
                    + (char) ('A' + i / 26 % 26)
                    + (char) ('A' + i % 26);
            cache.requestBatched(unique);
        }

        assertEquals(TranslationCache.MAX_QUEUED_ENTRIES, cache.pendingCount());
    }

    @Test
    void collectorAndFlightsShareOneHardLimitWithBlockedWorker() throws Exception {
        CountingBatchTranslator translator = new CountingBatchTranslator();
        PriorityTranslationExecutor worker = priorityExecutor(1, 1_024);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertTrue(worker.tryExecuteHigh(() -> {
                occupied.countDown();
                await(release);
            }));
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            TranslationCache cache = new TranslationCache(
                    translator, "zh-TW", worker, 2_000);
            cache.setBatchWindowMs(() -> 0);

            for (int i = 0; i < 2_000; i++) {
                cache.requestBatched(uniqueLetters(i));
                cache.flushBatch();
                assertTrue(cache.pendingCount() <= TranslationCache.MAX_QUEUED_ENTRIES,
                        "flushing into a slow executor must not free collector admission");
            }

            assertEquals(TranslationCache.MAX_QUEUED_ENTRIES, cache.pendingCount());
            assertEquals(TranslationCache.MAX_QUEUED_ENTRIES,
                    worker.queuedSnapshot().size(),
                    "only the admitted keys may become executor tasks");

            release.countDown();
            awaitPending(cache, 0);
            assertEquals(TranslationCache.MAX_QUEUED_ENTRIES, translator.requests.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void saturatedExecutorClosesCreatedFlightAndAlwaysCallback() throws Exception {
        CountingBatchTranslator translator = new CountingBatchTranslator();
        PriorityTranslationExecutor worker = priorityExecutor(1, 1);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> completed = new CopyOnWriteArrayList<>();

        try {
            assertTrue(worker.tryExecuteHigh(() -> {
                occupied.countDown();
                await(release);
            }));
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            assertTrue(worker.tryExecuteLow(() -> { }), "fill the sole queue slot");
            TranslationCache cache = new TranslationCache(translator, "zh-TW", worker, 100);

            cache.translateAsyncAlways("Rejected request", completed::add);

            assertEquals(0, cache.pendingCount(), "rejected flight must release its permit");
            assertEquals(1, completed.size());
            assertNull(completed.get(0), "always callback must close with null on rejection");
            assertEquals(0, translator.requests.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void clearReleasesPermitAndStaleQueuedTaskSkipsBackend() throws Exception {
        CountingBatchTranslator translator = new CountingBatchTranslator();
        PriorityTranslationExecutor worker = priorityExecutor(1, 4);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> stale = new CopyOnWriteArrayList<>();
        List<String> fresh = new CopyOnWriteArrayList<>();

        try {
            assertTrue(worker.tryExecuteHigh(() -> {
                occupied.countDown();
                await(release);
            }));
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            TranslationCache cache = new TranslationCache(translator, "zh-TW", worker, 100);

            cache.translateAsyncAlways("Old generation", stale::add);
            assertEquals(1, cache.pendingCount());
            cache.clear();
            assertEquals(0, cache.pendingCount());
            assertEquals(1, stale.size());
            assertNull(stale.get(0));

            cache.translateAsyncAlways("New generation", fresh::add);
            assertEquals(1, cache.pendingCount(), "released permit is immediately reusable");
            release.countDown();
            awaitPending(cache, 0);

            assertEquals(1, translator.requests.get(),
                    "the queued old-generation task must cancel before reaching backend");
            assertEquals(List.of("T:New generation"), fresh);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void invalidateReleasesPermitAndOldRevisionSkipsBackend() throws Exception {
        CountingBatchTranslator translator = new CountingBatchTranslator();
        PriorityTranslationExecutor worker = priorityExecutor(1, 4);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> stale = new CopyOnWriteArrayList<>();
        List<String> fresh = new CopyOnWriteArrayList<>();

        try {
            assertTrue(worker.tryExecuteHigh(() -> {
                occupied.countDown();
                await(release);
            }));
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            TranslationCache cache = new TranslationCache(translator, "zh-TW", worker, 100);

            cache.translateAsyncAlways("Revision target", stale::add);
            cache.invalidate("Revision target");
            assertEquals(0, cache.pendingCount());
            assertEquals(1, stale.size());
            assertNull(stale.get(0));

            cache.translateAsyncAlways("Revision target", fresh::add);
            release.countDown();
            awaitPending(cache, 0);

            assertEquals(1, translator.requests.get(),
                    "the detached old revision must cancel before reaching backend");
            assertEquals(List.of("T:Revision target"), fresh);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
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

    /** Inline render-flow simulation: every quantity from x1 through x1000 is a cache
     * hit after the first row and restores its own exact value. */
    @Test
    void inlineQuantitySimulationReusesOneRequestForEveryAmount() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);

        cache.requestBatched("Reward x1 Diamonds");
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(1, t.requests.get());
        assertEquals(List.of("Reward ⟦MT0⟧ Diamonds"), t.batches.get(0));

        for (int amount = 2; amount <= 1_000; amount++) {
            String source = "Reward x" + amount + " Diamonds";
            cache.requestBatched(source);
            cache.flushBatch();
            cache.flushBatch();

            assertEquals(1, t.requests.get(),
                    "x" + amount + " must not issue another translation request");
            assertEquals("T:" + source, cache.getCached(source),
                    "x" + amount + " must be restored exactly");
        }

        for (String source : List.of(
                "Reward X100 Diamonds",
                "Reward x1,000 Diamonds",
                "Reward x1.5 Diamonds",
                "Reward x86 Diamonds")) {
            cache.requestBatched(source);
            cache.flushBatch();
            cache.flushBatch();
            assertEquals(1, t.requests.get(), source + " must reuse the same request");
            assertEquals("T:" + source, cache.getCached(source));
        }
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

    @Test
    void fullInFlightCallbackListRejectsQuicklyWithoutLeakingItsPermit() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        java.util.ArrayDeque<Runnable> workers = new java.util.ArrayDeque<>();
        TranslationCache cache = new TranslationCache(t, "zh-TW", workers::add, 100);
        AtomicInteger acceptedCallbacks = new AtomicInteger();

        cache.requestCoalesced("Hello", ignored -> acceptedCallbacks.incrementAndGet(), true);
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(1, workers.size(), "the backend flight must remain blocked in the worker queue");
        assertEquals(1, cache.pendingCount(), "the active flight owns exactly one permit");

        for (int i = 1; i < 64; i++) {
            cache.requestCoalesced("Hello", ignored -> acceptedCallbacks.incrementAndGet(), true);
        }
        AtomicInteger rejectedCallbacks = new AtomicInteger();
        AtomicReference<String> rejectedValue = new AtomicReference<>("not-called");
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                cache.requestCoalesced("Hello", value -> {
                    rejectedCallbacks.incrementAndGet();
                    rejectedValue.set(value);
                }, true));

        assertEquals(1, rejectedCallbacks.get(), "the saturated callback must complete exactly once");
        assertNull(rejectedValue.get(), "always-callback saturation completes with null");
        AtomicInteger optionalCallback = new AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                cache.requestCoalesced("Hello", ignored -> optionalCallback.incrementAndGet(), false));
        assertEquals(0, optionalCallback.get(),
                "a non-always callback keeps the established null-is-silent contract");
        assertEquals(1, cache.pendingCount(), "rejection must not acquire or release the flight permit");

        workers.removeFirst().run();
        assertEquals(64, acceptedCallbacks.get(), "all callbacks admitted to the flight still complete");
        assertEquals(0, cache.pendingCount(), "the original flight releases its permit exactly once");
        assertEquals(1, t.requests.get(), "callback saturation must not create another request");
    }

    private static PriorityTranslationExecutor priorityExecutor(int workers, int capacity) {
        return new PriorityTranslationExecutor(workers, runnable -> {
            Thread thread = new Thread(runnable, "cache-backpressure-test");
            thread.setDaemon(true);
            return thread;
        }, capacity);
    }

    private static String uniqueLetters(int value) {
        return "Dynamic row "
                + (char) ('A' + value / (26 * 26) % 26)
                + (char) ('A' + value / 26 % 26)
                + (char) ('A' + value % 26);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitPending(TranslationCache cache, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.pendingCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(expected, cache.pendingCount(), "pending work did not drain in time");
    }
}
