package com.dragonmeow.nyanlex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.RequestGate;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
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
 * The action bar has one cell: when the next text arrives, the request of the previous one must
 * not cost anything if it has not been sent. All collaborators are inline fakes.
 */
class ActionBarSupersedeTest {

    private static TranslatorConfig config() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.actionBarMode = DisplayMode.TRANSLATION;
        cfg.aiActionBar = true;
        cfg.targetLang = "zh-TW";
        return cfg;
    }

    private static final class RecordingTranslator implements Translator {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public TranslationResult translate(String text, String targetLang) {
            sent.add(text);
            return new TranslationResult("譯:" + text, "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
            List<TranslationResult> out = new ArrayList<>();
            for (String text : texts) {
                out.add(translate(text, targetLang));
            }
            return out;
        }
    }

    @Test
    void aTextReplacedBeforeTheNextFlushIsNeverSent() {
        RecordingTranslator translator = new RecordingTranslator();
        TranslatorConfig cfg = config();
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(translator, cfg.targetLang, Runnable::run, 100),
                new TranslationCache(translator, cfg.targetLang, Runnable::run, 100));
        List<String> shown = new ArrayList<>();

        service.requestActionBarAsync("Quest started: Alpha Hunt", shown::add);
        service.cancelActionBarRequest("Quest started: Alpha Hunt");
        service.requestActionBarAsync("Quest started: Beta Hunt", shown::add);
        service.flushBatches();
        service.flushBatches();

        assertEquals(List.of("Quest started: Beta Hunt"), translator.sent);
        assertEquals(List.of("譯:Quest started: Beta Hunt"), shown);
    }

    @Test
    void cancellingTwiceOrAnUnknownTextIsHarmless() {
        RecordingTranslator translator = new RecordingTranslator();
        TranslatorConfig cfg = config();
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(translator, cfg.targetLang, Runnable::run, 100),
                new TranslationCache(translator, cfg.targetLang, Runnable::run, 100));
        service.cancelActionBarRequest("never asked");
        service.cancelActionBarRequest(null);
        service.requestActionBarAsync("Quest started: Alpha Hunt", s -> { });
        service.cancelActionBarRequest("Quest started: Alpha Hunt");
        service.cancelActionBarRequest("Quest started: Alpha Hunt");
        service.flushBatches();
        service.flushBatches();
        assertTrue(translator.sent.isEmpty());
    }

    @Test
    void askingAgainAfterCancellingStillWorks() {
        RecordingTranslator translator = new RecordingTranslator();
        TranslatorConfig cfg = config();
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(translator, cfg.targetLang, Runnable::run, 100),
                new TranslationCache(translator, cfg.targetLang, Runnable::run, 100));
        List<String> shown = new ArrayList<>();
        service.requestActionBarAsync("Quest started: Alpha Hunt", shown::add);
        service.cancelActionBarRequest("Quest started: Alpha Hunt");
        service.requestActionBarAsync("Quest started: Alpha Hunt", shown::add);
        service.flushBatches();
        service.flushBatches();
        assertEquals(List.of("Quest started: Alpha Hunt"), translator.sent);
        assertEquals(List.of("譯:Quest started: Alpha Hunt"), shown);
    }

    @Test
    void aWorkerStillWaitingForItsSendSlotStandsDownAndLeavesNoWaitingEntry() throws Exception {
        // The translator stands in for the real pacer: it sleeps for a slot (the latch) and
        // then checks the request gate right before sending, like RequestPacer does.
        CountDownLatch inSlotWait = new CountDownLatch(1);
        CountDownLatch slotArrived = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        Translator translator = new Translator() {
            @Override
            public TranslationResult translate(String text, String targetLang) {
                sent.add(text);
                return new TranslationResult("譯:" + text, "en");
            }

            @Override
            public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
                inSlotWait.countDown();
                try {
                    slotArrived.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                RequestGate.checkOpen();
                List<TranslationResult> out = new ArrayList<>();
                for (String text : texts) {
                    out.add(translate(text, targetLang));
                }
                return out;
            }
        };
        TranslatorConfig cfg = config();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TranslationCache ai = new TranslationCache(translator, cfg.targetLang, pool, 100);
            TranslationDebugLog debug = new TranslationDebugLog(() -> true);
            ai.setDebugLog("AI", debug);
            TranslationService service = new TranslationService(cfg,
                    new TranslationCache(translator, cfg.targetLang, Runnable::run, 100), ai);

            service.requestActionBarAsync("Quest started: Alpha Hunt", s -> { });
            service.flushBatches();
            service.flushBatches();
            assertTrue(inSlotWait.await(5, TimeUnit.SECONDS), "the batch should be waiting for its slot");
            assertEquals(1, debug.snapshot(10).size());
            assertEquals(TranslationDebugLog.Status.IN_FLIGHT, debug.snapshot(10).get(0).status());

            service.cancelActionBarRequest("Quest started: Alpha Hunt");
            slotArrived.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(sent.isEmpty(), "a withdrawn request must not be sent: " + sent);
            for (TranslationDebugLog.Entry entry : debug.snapshot(10)) {
                assertFalse(entry.status() == TranslationDebugLog.Status.IN_FLIGHT,
                        "no entry may stay waiting");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAlreadySentRequestIsNotDisturbed() throws Exception {
        CountDownLatch onTheWire = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        Translator translator = new Translator() {
            @Override
            public TranslationResult translate(String text, String targetLang) {
                sent.add(text);
                return new TranslationResult("譯:" + text, "en");
            }

            @Override
            public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
                RequestGate.checkOpen(); // slot reached: the request goes out here
                onTheWire.countDown();
                try {
                    answer.await(5, TimeUnit.SECONDS);
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
        TranslatorConfig cfg = config();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TranslationCache ai = new TranslationCache(translator, cfg.targetLang, pool, 100);
            TranslationService service = new TranslationService(cfg,
                    new TranslationCache(translator, cfg.targetLang, Runnable::run, 100), ai);
            service.requestActionBarAsync("Quest started: Alpha Hunt", s -> { });
            service.flushBatches();
            service.flushBatches();
            assertTrue(onTheWire.await(5, TimeUnit.SECONDS));
            service.cancelActionBarRequest("Quest started: Alpha Hunt");
            answer.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(List.of("Quest started: Alpha Hunt"), sent);
            assertEquals("譯:Quest started: Alpha Hunt", ai.getCached("Quest started: Alpha Hunt"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void peekNeverStartsARequest() {
        RecordingTranslator translator = new RecordingTranslator();
        TranslatorConfig cfg = config();
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, Runnable::run, 100);
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(translator, cfg.targetLang, Runnable::run, 100), ai);
        for (int i = 0; i < 5; i++) {
            assertFalse(service.peekActionBar("Quest started: Alpha Hunt").changed());
            service.flushBatches();
        }
        assertTrue(translator.sent.isEmpty());
        ai.importTranslations(java.util.Map.of("Quest started: Alpha Hunt", "任務開始：阿爾法獵人"));
        assertEquals("任務開始：阿爾法獵人", service.peekActionBar("Quest started: Alpha Hunt").translated());
    }
}
