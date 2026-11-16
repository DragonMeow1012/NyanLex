package com.dragonmeow.nyanlex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Text that is on screen right now is requested at high priority and only when the engine can
 * send at once; otherwise nothing is queued and no send slot is reserved. All collaborators
 * are inline fakes.
 */
class ImmediateRequestTest {

    /** Engine whose pacing and rate-limit gate the test controls. */
    private static final class Engine implements Translator {
        final List<List<String>> batches = new CopyOnWriteArrayList<>();
        volatile long delayMs;
        volatile boolean blocked;
        volatile boolean fail;

        @Override
        public TranslationResult translate(String text, String targetLang) throws TranslationException {
            if (fail) {
                throw new TranslationException("engine down");
            }
            return new TranslationResult("譯:" + text, "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
                throws TranslationException {
            batches.add(new ArrayList<>(texts));
            List<TranslationResult> out = new ArrayList<>();
            for (String text : texts) {
                out.add(translate(text, targetLang));
            }
            return out;
        }

        @Override
        public long nextRequestDelayMs() {
            return delayMs;
        }

        @Override
        public boolean sendBlocked() {
            return blocked;
        }

        int requests() {
            return batches.size();
        }
    }

    private static TranslationCache cache(Engine engine, long[] now, boolean windowed) {
        TranslationCache cache = new TranslationCache(engine, "zh-TW", Runnable::run, 100, 10_000L, () -> now[0]);
        cache.setBatchWindowMs(() -> 5_000);
        cache.setWindowedBatching(windowed);
        return cache;
    }

    // ---------------------------------------------------------------- cache

    @Test
    void aHighPriorityRequestDoesNotWaitForTheCollectionWindow() {
        for (boolean windowed : new boolean[] {false, true}) {
            Engine engine = new Engine();
            long[] now = {1_000L};
            TranslationCache cache = cache(engine, now, windowed);

            cache.requestCoalesced("Alpha", s -> { }, false);
            cache.flushBatch();
            assertEquals(0, engine.requests(), "a normal request waits for its window (windowed=" + windowed + ")");

            now[0] += 100L;
            cache.requestCoalescedHigh("Beta", s -> { }, false);
            cache.flushBatch();
            assertEquals(1, engine.requests(), "windowed=" + windowed);
            assertEquals(List.of("Beta", "Alpha"), engine.batches.get(0),
                    "the urgent text leads and takes the collected text along");
        }
    }

    @Test
    void aHighPriorityRequestStillWaitsForTheEngine() {
        Engine engine = new Engine();
        long[] now = {1_000L};
        TranslationCache cache = cache(engine, now, true);
        engine.delayMs = 4_000L;
        cache.requestCoalescedHigh("Beta", s -> { }, false);
        cache.flushBatch();
        assertEquals(0, engine.requests());
        engine.delayMs = 0L;
        cache.flushBatch();
        assertEquals(1, engine.requests());
    }

    @Test
    void maySendNowFollowsThePacerTheGateAndTheSwitch() {
        Engine engine = new Engine();
        long[] now = {1_000L};
        TranslationCache cache = cache(engine, now, false);
        assertTrue(cache.maySendNow());
        engine.delayMs = 1L;
        assertFalse(cache.maySendNow(), "cooling down");
        engine.delayMs = 0L;
        engine.blocked = true;
        assertFalse(cache.maySendNow(), "rate-limit gate closed");
        engine.blocked = false;
        assertTrue(cache.maySendNow());
        cache.setRequestGate(() -> false);
        assertFalse(cache.maySendNow(), "requests switched off");
        cache.setRequestGate(() -> true);
        assertTrue(cache.maySendNow());
    }

    @Test
    void maySendNowIsFalseWhileAWindowedBatchIsStillRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Translator slow = new Translator() {
            @Override
            public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("譯:" + text, "en");
            }

            @Override
            public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                List<TranslationResult> out = new ArrayList<>();
                for (String text : texts) {
                    out.add(translate(text, targetLang));
                }
                return out;
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            long[] now = {1_000L};
            TranslationCache cache = new TranslationCache(slow, "zh-TW", pool, 100, 10_000L, () -> now[0]);
            cache.setBatchWindowMs(() -> 5_000);
            cache.setWindowedBatching(true);
            assertTrue(cache.maySendNow());
            cache.requestCoalescedHigh("Beta", s -> { }, false);
            cache.flushBatch();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(cache.maySendNow(), "an earlier batch is still running");
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(cache.maySendNow());
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- service

    private static TranslatorConfig config() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.actionBarMode = DisplayMode.TRANSLATION;
        cfg.aiActionBar = true;
        cfg.targetLang = "zh-TW";
        return cfg;
    }

    @Test
    void theServiceAsksOnlyWhenTheEngineCanSendAtOnce() {
        Engine engine = new Engine();
        TranslatorConfig cfg = config();
        long[] now = {1_000L};
        TranslationCache ai = cache(engine, now, true);
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(new Engine(), cfg.targetLang, Runnable::run, 100), ai);

        engine.delayMs = 3_000L;
        assertFalse(service.requestActionBarNow("A page of story text."), "cooling down");
        service.flushBatches();
        service.flushBatches();
        assertEquals(0, engine.requests(), "nothing was queued, so nothing is waiting for a slot");

        engine.delayMs = 0L;
        assertTrue(service.requestActionBarNow("Another page of story."));
        service.flushBatches();
        assertEquals(1, engine.requests(), "sent at the next tick, not after the 5 s window");
        assertEquals(List.of("Another page of story."), engine.batches.get(0));
        assertEquals("譯:Another page of story.", service.peekActionBar("Another page of story.").translated());
    }

    @Test
    void notTheAiEngineOrNotWantedMeansNoRequest() {
        Engine engine = new Engine();
        TranslatorConfig cfg = config();
        long[] now = {1_000L};
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(new Engine(), cfg.targetLang, Runnable::run, 100), cache(engine, now, true));
        assertFalse(service.requestActionBarNow("12345"), "no letters: not worth asking");
        cfg.aiActionBar = false;
        assertFalse(service.requestActionBarNow("A page of story text."), "machine engine is manual");
        service.flushBatches();
        assertEquals(0, engine.requests());
    }

    @Test
    void aFailingAiEngineFallsBackExactlyAsTheOrdinaryRequestDoes() {
        int[] machineRequests = new int[2];
        int[] aiRequests = new int[2];
        for (int path = 0; path < 2; path++) {
            Engine ai = new Engine();
            Engine machine = new Engine();
            ai.fail = true;
            TranslatorConfig cfg = config();
            long[] now = {1_000L};
            TranslationService service = new TranslationService(cfg,
                    new TranslationCache(machine, cfg.targetLang, Runnable::run, 100), cache(ai, now, true));
            if (path == 0) {
                service.requestActionBarAsync("A page of story text.", s -> { });
            } else {
                assertTrue(service.requestActionBarNow("A page of story text."));
            }
            now[0] += 6_000L; // the ordinary path waits out its collection window
            service.flushBatches();
            service.flushBatches();
            assertTrue(ai.requests() >= 1, "path " + path);
            machineRequests[path] = machine.requests();
            aiRequests[path] = ai.requests();
        }
        assertEquals(aiRequests[0], aiRequests[1]);
        assertEquals(machineRequests[0], machineRequests[1],
                "the AI-failure fallback must not depend on how the request was asked for");
    }

    @Test
    void aRowOfNothingButGraphicsNeverReachesTheEngine() {
        Engine engine = new Engine();
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiScoreboard = true;
        cfg.targetLang = "zh-TW";
        long[] now = {1_000L};
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(new Engine(), cfg.targetLang, Runnable::run, 100), cache(engine, now, true));
        String icon = new String(Character.toChars(0xE001));
        String move = new String(Character.toChars(0xD0002));
        String divider = icon + move + icon + move + icon;
        assertFalse(service.translateItemLine(divider).changed());
        assertFalse(service.translateItemLine(move + icon + move).changed());
        now[0] += 6_000L;
        service.flushBatches();
        service.flushBatches();
        assertEquals(0, engine.requests());
    }
}
