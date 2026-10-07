package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import com.dragonmeow.nyanlex.translate.RequestGate;
import com.dragonmeow.nyanlex.translate.RequestsPausedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Public-API reproductions of the October 6 user trace, using no real backend. */
class UserEchoRegressionTest {
    private static final String PLAIN = "Lv020 Herobrine";
    private static final String STYLED = "⟦CS0⟧Lv020⟦/CS0⟧ ⟦CS1⟧Herobrine⟦/CS1⟧";
    private static final String KEEP = "\0MT_KEEP_ORIGINAL2";


    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        Runnable take() {
            Runnable task = tasks.poll();
            assertNotNull(task, "the test must actually have a worker task waiting");
            return task;
        }
        void drain() {
            int count = 0;
            Runnable task;
            while ((task = tasks.poll()) != null) {
                assertTrue(count++ < 100, "unexpected endless worker scheduling");
                task.run();
            }
        }
        int size() { return tasks.size(); }
    }

    private static final class RecordingEcho implements Translator {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<List<String>> batches = new CopyOnWriteArrayList<>();
        @Override public TranslationResult translate(String text, String target) {
            sent.add(text);
            return new TranslationResult(text, "en");
        }
        @Override public List<TranslationResult> translateBatch(List<String> texts, String target) {
            batches.add(List.copyOf(texts));
            return texts.stream().map(text -> translate(text, target)).toList();
        }
    }

    private static PersistentStore store(Map<String, String> rows) {
        return new PersistentStore() {
            @Override public String get(String key) { return rows.get(key); }
            @Override public void put(String key, String value) { rows.put(key, value); }
            @Override public void clear() { rows.clear(); }
            @Override public void remove(String key) { rows.remove(key); }
            @Override public Map<String, String> entries() { return Map.copyOf(rows); }
            @Override public int size() { return rows.size(); }
        };
    }

    private static TranslationCache cache(Translator translator, Executor executor, long[] now,
                                           long backoff, Map<String, String> disk,
                                           Map<String, String> failures) {
        TranslationCache cache = new TranslationCache(translator, "zh-TW", executor, 100,
                backoff, () -> now[0], store(disk));
        cache.setFailureStore(store(failures));
        cache.setBatchWindowMs(() -> 0);
        return cache;
    }

    private static void confirmPlainIdentity(TranslationCache cache, Map<String, String> disk) {
        assertNull(cache.translateBlocking(PLAIN));
        assertNull(cache.translateBlocking(PLAIN));
        assertEquals(PLAIN, cache.translateBlocking(PLAIN));
        assertEquals(KEEP, disk.get(PLAIN), "the test must establish the real semantic terminal decision");
    }

    enum QueuedStage { COLLECTOR, BATCH_WORKER, IMMEDIATE_WORKER }

    @ParameterizedTest @EnumSource(QueuedStage.class)
    void styledWorkQueuedBeforeSemanticKeepMustFinishWithoutAnotherBackendCall(QueuedStage stage) {
        RecordingEcho translator = new RecordingEcho();
        ManualExecutor executor = new ManualExecutor();
        Map<String, String> disk = new ConcurrentHashMap<>(), failures = new ConcurrentHashMap<>();
        TranslationCache cache = cache(translator, executor, new long[]{0L}, 10_000L, disk, failures);
        List<String> callbacks = new ArrayList<>();
        List<String> finalCallbacks = new ArrayList<>();
        if (stage == QueuedStage.IMMEDIATE_WORKER) {
            cache.translateAsyncAlways(STYLED, callbacks::add);
        } else {
            cache.requestCoalescedExactStyle(STYLED, callbacks::add, true);
            if (stage == QueuedStage.BATCH_WORKER) cache.flushBatch();
        }
        cache.requestCoalescedExactStyleFinal(STYLED, finalCallbacks::add);
        assertEquals(1, cache.pendingCount());
        assertEquals(0, translator.sent.size());
        if (stage != QueuedStage.COLLECTOR) assertEquals(1, executor.size());

        confirmPlainIdentity(cache, disk);
        assertEquals(3, translator.sent.size());
        cache.flushBatch();
        executor.drain();
        cache.flushBatch();
        executor.drain();

        assertAll(
                () -> assertEquals(3, translator.sent.size(), "semantic KEEP must cancel already queued styled work"),
                () -> assertEquals(List.of(STYLED), callbacks, "normal callback must settle exactly once with original styled text"),
                () -> assertEquals(List.of(STYLED), finalCallbacks, "final-style callback must not remain waiting"),
                () -> assertEquals(0, cache.pendingCount(), "cancelled work must release its pending permit"),
                () -> assertEquals(KEEP, disk.get(PLAIN)),
                () -> assertTrue(failures.isEmpty(), "terminal identity must not create style failure debt: " + failures));
    }

    @Test void persistedEchoRehydrationMustNotCreateASecondPlainRequestAtExpiry() {
        RecordingEcho translator = new RecordingEcho();
        long[] now = {0L};
        Map<String, String> disk = new ConcurrentHashMap<>(), failures = new ConcurrentHashMap<>();
        TranslationCache cache = cache(translator, Runnable::run, now, 10_000L, disk, failures);
        cache.requestBatched(STYLED);
        cache.flushBatch();
        assertEquals(List.of(STYLED), translator.sent);
        assertEquals("identity:1:10000", failures.get(PLAIN));

        now[0] = 10_000L;
        // Real render callers look up, then offer a miss repeatedly before the next collector tick.
        for (int frame = 0; frame < 8; frame++) {
            assertNull(cache.getCached(STYLED));
            cache.requestBatched(STYLED);
        }
        cache.flushBatch();
        assertAll(
                () -> assertEquals(List.of(STYLED), translator.batches.getLast(),
                        "rehydrating a semantic ledger must preserve the live styled snapshot, not add a plain copy"),
                () -> assertEquals(2, translator.sent.size(), "second attempt is one item, not styled+plain"),
                () -> assertEquals("identity:2:30000", failures.get(PLAIN),
                        "one retry must add one echo confirmation"));

        now[0] = 30_000L;
        for (int frame = 0; frame < 8; frame++) cache.requestBatched(STYLED);
        cache.flushBatch();
        assertEquals(3, translator.sent.size());
        assertEquals(KEEP, disk.get(PLAIN));
        now[0] = 100_000L;
        for (int tick = 0; tick < 10; tick++) {
            assertEquals(STYLED, cache.getCached(STYLED));
            cache.requestBatched(STYLED);
            cache.flushBatch();
        }
        assertEquals(3, translator.sent.size(), "neither future observations nor ticks may rebuy the confirmed family");
        assertEquals(0, cache.pendingCount());
    }

    @ParameterizedTest
    @CsvSource({"immediate,echo", "batch,echo", "immediate,translated", "batch,translated",
            "immediate,transport_error", "batch,transport_error"})
    void inFlightStyledCompletionCannotUndoSemanticKeepOrCreateRetryDebt(String path, String response) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            if (text.equals(STYLED)) {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new TranslationException("test release timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new TranslationException("test interrupted", error);
                }
                if (response.equals("transport_error")) throw new TranslationException("HTTP 429: too many requests");
                if (response.equals("translated")) return new TranslationResult(
                        "⟦CS0⟧Lv020⟦/CS0⟧ ⟦CS1⟧英雄布萊恩⟦/CS1⟧", "en");
            }
            return new TranslationResult(text, "en");
        };
        ManualExecutor executor = new ManualExecutor();
        Map<String, String> disk = new ConcurrentHashMap<>(), failures = new ConcurrentHashMap<>();
        TranslationCache cache = cache(translator, executor, new long[]{0L}, 10_000L, disk, failures);
        List<String> callbacks = new CopyOnWriteArrayList<>();
        if (path.equals("immediate")) cache.translateAsyncAlways(STYLED, callbacks::add);
        else {
            cache.requestCoalescedExactStyle(STYLED, callbacks::add, true);
            cache.flushBatch();
        }

        try (var worker = Executors.newSingleThreadExecutor()) {
            var future = worker.submit(executor.take());
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS), "styled backend call must already be in flight");
                confirmPlainIdentity(cache, disk);
                assertEquals(4, calls.get(), "one in-flight styled call and three completed plain confirmations");
            } finally {
                release.countDown();
            }
            future.get(3, TimeUnit.SECONDS);
        }
        cache.flushBatch();
        executor.drain();
        assertAll(
                () -> assertEquals(KEEP, disk.get(PLAIN), "a response started before semantic KEEP may not overwrite it"),
                () -> assertEquals(STYLED, cache.getCached(STYLED)),
                () -> assertTrue(failures.isEmpty(), "late responses/errors may not recreate retry or MT_STYLE_FAILURE debt: " + failures),
                () -> assertEquals(List.of(STYLED), callbacks),
                () -> assertEquals(0, cache.pendingCount()),
                () -> assertEquals(4, calls.get(), "no extra backend request may follow the late completion"));
    }

    @Test void restartKeepsAllStyledEntryPointsLocalAndManualInvalidateUnlocksThem() {
        RecordingEcho translator = new RecordingEcho();
        Map<String, String> disk = new ConcurrentHashMap<>(), failures = new ConcurrentHashMap<>();
        TranslationCache first = cache(translator, Runnable::run, new long[]{0L}, 0L, disk, failures);
        confirmPlainIdentity(first, disk);
        TranslationCache restarted = cache(translator, Runnable::run, new long[]{0L}, 0L, disk, failures);
        List<String> callbacks = new ArrayList<>();
        restarted.translateAsyncAlways(STYLED, callbacks::add);
        restarted.requestCoalesced(STYLED, callbacks::add, true);
        restarted.requestCoalescedExactStyle(STYLED, callbacks::add, true);
        restarted.requestCoalescedExactStyleFinal(STYLED, callbacks::add);
        restarted.requestBatched(STYLED);
        restarted.warmBatchAsync(List.of(STYLED));
        restarted.flushBatch();
        assertEquals(List.of(STYLED, STYLED, STYLED, STYLED), callbacks);
        assertEquals(3, translator.sent.size());
        assertEquals(0, restarted.pendingCount());

        restarted.invalidate(STYLED);
        assertNull(restarted.getCached(STYLED));
        restarted.requestAsync(STYLED);
        assertEquals(4, translator.sent.size(), "manual invalidate, rather than a stale queued task, is allowed to reopen the family");
        assertEquals("identity:1:0", failures.get(PLAIN));
    }

    @Test void sameBatchThirdEchoFollowedByUsableResultMustRetainSemanticKeep() {
        String otherTopology = "⟦CS0⟧Lv020 Herobrine⟦/CS0⟧";
        List<List<String>> batches = new ArrayList<>();
        Translator translator = new Translator() {
            @Override public TranslationResult translate(String text, String target) {
                return new TranslationResult(text, "en");
            }
            @Override public List<TranslationResult> translateBatch(List<String> texts, String target) {
                batches.add(List.copyOf(texts));
                return texts.stream().map(text -> new TranslationResult(
                        text.equals(PLAIN) ? "Lv020 英雄布萊恩" : text, "en")).toList();
            }
        };
        long[] now = {0L};
        Map<String, String> disk = new ConcurrentHashMap<>(), failures = new ConcurrentHashMap<>();
        TranslationCache cache = cache(translator, Runnable::run, now, 10_000L, disk, failures);
        assertNull(cache.translateBlocking(PLAIN));
        now[0] = 10_000L;
        cache.warmBatch(List.of(STYLED, otherTopology, PLAIN));
        assertEquals(List.of(STYLED, otherTopology, PLAIN), batches.getFirst(),
                "this fixture must put the two confirmations and a later usable result in one batch");
        assertAll(
                () -> assertEquals(KEEP, disk.get(PLAIN)),
                () -> assertEquals(STYLED, cache.getCached(STYLED)),
                () -> assertTrue(failures.isEmpty(), "the later item must not undo the terminal decision"));
    }

    @ParameterizedTest
    @CsvSource({"single,false", "batch,false", "batch,true"})
    void aPacedRequestRechecksTerminalFamiliesAtTheLastPreHttpGate(String path, boolean mixed) throws Exception {
        final String OTHER = "Useful separate phrase";
        CountDownLatch beforeHttp = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger httpCalls = new AtomicInteger(), gateCancellations = new AtomicInteger();
        Translator translator = new Translator() {
            private void waitThenCheckGate() throws TranslationException {
                beforeHttp.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new TranslationException("test release timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new TranslationException("test interrupted", error);
                }
                try { RequestGate.checkOpen(); }
                catch (RequestsPausedException paused) {
                    gateCancellations.incrementAndGet();
                    throw paused;
                }
            }
            @Override public TranslationResult translate(String text, String target) throws TranslationException {
                if (text.equals(STYLED)) waitThenCheckGate();
                else RequestGate.checkOpen();
                httpCalls.incrementAndGet();
                return new TranslationResult(text, "en");
            }
            @Override public List<TranslationResult> translateBatch(List<String> texts, String target)
                    throws TranslationException {
                waitThenCheckGate();
                httpCalls.incrementAndGet();
                return texts.stream().map(text -> new TranslationResult(
                        text.equals(OTHER) ? "有用的另一段文字" : text, "en")).toList();
            }
        };
        ManualExecutor executor = new ManualExecutor();
        Map<String, String> disk = new ConcurrentHashMap<>(), failures = new ConcurrentHashMap<>();
        TranslationCache cache = cache(translator, executor, new long[]{0L}, 10_000L, disk, failures);
        List<String> styledCallbacks = new CopyOnWriteArrayList<>(), otherCallbacks = new CopyOnWriteArrayList<>();
        if (path.equals("single")) cache.translateAsyncAlways(STYLED, styledCallbacks::add);
        else {
            cache.requestCoalescedExactStyle(STYLED, styledCallbacks::add, true);
            if (mixed) cache.requestCoalesced(OTHER, otherCallbacks::add, true);
            cache.flushBatch();
        }
        try (var worker = Executors.newSingleThreadExecutor()) {
            var future = worker.submit(executor.take());
            try {
                assertTrue(beforeHttp.await(1, TimeUnit.SECONDS), "the backend must be waiting before the simulated HTTP call");
                assertEquals(0, httpCalls.get());
                confirmPlainIdentity(cache, disk);
                assertEquals(3, httpCalls.get());
            } finally { release.countDown(); }
            future.get(3, TimeUnit.SECONDS);
        }
        assertAll(
                () -> assertEquals(mixed ? 4 : 3, httpCalls.get(),
                        "all-terminal paced work must stop before HTTP; mixed work must still translate its live family"),
                () -> assertEquals(mixed ? 0 : 1, gateCancellations.get()),
                () -> assertEquals(List.of(STYLED), styledCallbacks),
                () -> assertEquals(mixed ? List.of("有用的另一段文字") : List.of(), otherCallbacks),
                () -> assertEquals(KEEP, disk.get(PLAIN)),
                () -> assertEquals(0, cache.pendingCount()));
    }

    @Test void lateFallbackStyleWriteCannotHideKeepFromNewFinalWaiters() throws Exception {
        CountDownLatch enteredStore = new CountDownLatch(1), releaseStore = new CountDownLatch(1);
        AtomicBoolean firstStyleStore = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new ConcurrentHashMap<>(), failureRows = new ConcurrentHashMap<>();
        PersistentStore failureStore = new PersistentStore() {
            @Override public String get(String key) { return failureRows.get(key); }
            @Override public void put(String key, String value) { failureRows.put(key, value); }
            @Override public void clear() { failureRows.clear(); }
            @Override public void remove(String key) { failureRows.remove(key); }
            @Override public Map<String, String> entries() { return Map.copyOf(failureRows); }
            @Override public void removeBatch(Collection<String> keys) {
                // clearFailureState runs after store's initial family guard, before
                // the late provisional projection is committed. No private state is edited.
                if (keys.contains(STYLED) && firstStyleStore.compareAndSet(true, false)) {
                    enteredStore.countDown();
                    try {
                        if (!releaseStore.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("store test timeout");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                keys.forEach(failureRows::remove);
            }
        };
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            if (text.equals(STYLED)) return new TranslationResult(
                    "⟦CS0⟧Lv020⟦/CS0⟧ ⟦CS1⟧英雄布萊恩⟦/CS1⟧", "en", true);
            return new TranslationResult(text, "en");
        };
        ManualExecutor executor = new ManualExecutor();
        TranslationCache cache = new TranslationCache(translator, "zh-TW", executor, 100,
                10_000L, () -> 0L, store(disk));
        cache.setFailureStore(failureStore);
        cache.setBatchWindowMs(() -> 0);
        cache.requestAsync(STYLED);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var future = worker.submit(executor.take());
            try {
                assertTrue(enteredStore.await(1, TimeUnit.SECONDS), "late fallback must already have passed the initial store guard");
                confirmPlainIdentity(cache, disk);
                assertEquals(4, calls.get());
            } finally { releaseStore.countDown(); }
            future.get(3, TimeUnit.SECONDS);
        }
        List<String> finalCallbacks = new ArrayList<>();
        cache.requestCoalescedExactStyleFinal(STYLED, finalCallbacks::add);
        cache.flushBatch();
        executor.drain();
        assertAll(
                () -> assertEquals(KEEP, disk.get(PLAIN)),
                () -> assertEquals(STYLED, cache.getCachedFinal(STYLED),
                        "a stale provisional style flag may not hide an authoritative semantic KEEP"),
                () -> assertEquals(List.of(STYLED), finalCallbacks,
                        "new exact-style final waiters must complete immediately instead of waiting forever"),
                () -> assertEquals(0, cache.pendingCount()),
                () -> assertEquals(4, calls.get(), "no retry may be launched to satisfy an already-kept family"));
    }
}
