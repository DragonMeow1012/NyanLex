package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-03 lookup order of a machine-translation surface: wording the AI engine has
 * already produced for a text is shown first, even though the surface itself selected the
 * machine engine.
 *
 * <pre>
 *   AI service:      AI cache -> repository -> ask the AI                      (unchanged)
 *   machine service: AI cache -> repository -> machine cache -> ask Google
 * </pre>
 *
 * Only the AI cache's final wording counts (a provisional stand-in, a kept-original row and
 * a failure mark do not), an AI hit sends no Google request and is never copied into the
 * machine cache, and an explicit retranslate (R) of such a text buys a machine wording that
 * is then shown from then on. Every translator, cache and executor is an inline fake: no
 * file or network is touched, requests are counted by the fake transports.
 */
class MachineModeAiFirstTest {

    private static final Executor DIRECT = Runnable::run;
    private static final String SWORD = "Diamond Sword";

    private final AtomicInteger aiCalls = new AtomicInteger();
    private final AtomicInteger gtCalls = new AtomicInteger();
    private final AtomicReference<String> aiMode = new AtomicReference<>("ok");
    private final long[] now = {0L};

    private TranslationCache newAiCache(TranslatorConfig cfg, Map<String, String> dictionary) {
        Translator translator = (text, target) -> {
            aiCalls.incrementAndGet();
            if ("down".equals(aiMode.get())) throw new TranslationException("AI offline");
            return new TranslationResult(dictionary.getOrDefault(text, "AI譯文:" + text), "en");
        };
        return new TranslationCache(translator, cfg.targetLang, DIRECT, 1000, 1_000L, () -> now[0]);
    }

    private TranslationCache newGtCache(TranslatorConfig cfg, Map<String, String> dictionary) {
        Translator translator = (text, target) -> {
            gtCalls.incrementAndGet();
            return new TranslationResult(dictionary.getOrDefault(text, "機翻譯文:" + text), "en");
        };
        return new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
    }

    private static TranslatorConfig machineConfig() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiChat = false;
        cfg.aiTooltip = false;
        cfg.aiScreenText = false;
        return cfg;
    }

    private static void pump(TranslationService service) {
        service.flushBatches();
        service.flushBatches();
    }

    // ---- AI cache hit wins over the machine engine, zero Google requests ----

    @Test
    void machineModeShowsFinalAiWordingWithoutAnyGoogleRequest() {
        TranslatorConfig cfg = machineConfig();
        Map<String, String> aiWords = Map.of(SWORD, "鑽石劍", "Welcome to the server", "歡迎來到伺服器");
        TranslationCache ai = newAiCache(cfg, aiWords);
        TranslationCache gt = newGtCache(cfg, Map.of());
        assertEquals("鑽石劍", ai.translateBlocking(SWORD));
        assertEquals("歡迎來到伺服器", ai.translateBlocking("Welcome to the server"));
        TranslationService service = new TranslationService(cfg, gt, ai);

        for (int frame = 0; frame < 3; frame++) {
            TranslationDecision item = service.translateItemLine(SWORD);
            assertTrue(item.changed());
            assertEquals("鑽石劍", item.translated());
            TranslationDecision chat = service.translateChat("Welcome to the server");
            assertTrue(chat.changed());
            assertEquals("歡迎來到伺服器", chat.translated());
        }
        pump(service);

        assertEquals(0, gtCalls.get(), "an AI cache hit must never reach Google");
        assertEquals(0, gt.size(), "an AI hit is never copied into the machine cache");
    }

    @Test
    void machineModeAsyncChatTakesTheAiWordingWithoutAGoogleRequest() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache ai = newAiCache(cfg, Map.of("Welcome to the server", "歡迎來到伺服器"));
        TranslationCache gt = newGtCache(cfg, Map.of());
        assertEquals("歡迎來到伺服器", ai.translateBlocking("Welcome to the server"));
        TranslationService service = new TranslationService(cfg, gt, ai);

        AtomicReference<String> shown = new AtomicReference<>();
        service.translateChatAsync("Welcome to the server", shown::set);
        pump(service);

        assertEquals("歡迎來到伺服器", shown.get());
        assertEquals(0, gtCalls.get());
        assertEquals(0, gt.size());
    }

    // ---- not an AI hit: provisional stand-in, failure mark, kept original ----

    @Test
    void aiProvisionalRowIsNotAHitAndPeekingNeverWakesTheAiEngine() {
        TranslatorConfig cfg = machineConfig();
        cfg.disableGoogleFallbackForAi = false;
        // The AI backend answers through its Google fallback: the AI cache keeps the result as a
        // PROVISIONAL stand-in awaiting the real AI wording.
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult("暫代譯文", "en", true);
        }, cfg.targetLang, DIRECT, 1000, 1_000L, () -> now[0]);
        TranslationCache gt = newGtCache(cfg, Map.of("Hello world", "機翻哈囉"));
        ai.setProvisionalRetryGate(() -> true);
        TranslationService service = new TranslationService(cfg, gt, ai);

        cfg.aiChat = true;
        service.translateChat("Hello world");
        pump(service);
        assertEquals("暫代譯文", service.translateChat("Hello world").translated());
        assertNull(ai.peekFinal("Hello world", false), "a stand-in is not final AI wording");
        int aiCallsBeforeMachineMode = aiCalls.get();

        // Machine mode: the stand-in is not a hit. Render frames only (no engine tick): the AI
        // backoff has long expired and the retry gate is open, so an ordinary AI-cache read WOULD
        // upgrade the stand-in right here -- a machine surface must never wake the AI engine.
        cfg.aiChat = false;
        now[0] = 1_000_000L;
        for (int frame = 0; frame < 3; frame++) {
            assertFalse(service.translateChat("Hello world").changed(),
                    "the stand-in is not displayed on a machine surface");
            assertFalse(service.translateItemLine("Hello world").changed());
        }
        assertEquals(aiCallsBeforeMachineMode, aiCalls.get(),
                "reading the AI cache from a machine surface must not retry the AI");

        // The text goes the ordinary machine way: one Google request, then it is shown.
        service.translateChat("Hello world");
        service.flushBatches();
        service.flushBatches();
        assertEquals(1, gtCalls.get());
        assertEquals("機翻哈囉", service.translateChat("Hello world").translated());
    }

    @Test
    void aiFailureMarkIsNotAHitSoTheTextGoesToGoogle() {
        TranslatorConfig cfg = machineConfig();
        cfg.disableGoogleFallbackForAi = true;
        TranslationCache ai = newAiCache(cfg, Map.of());
        TranslationCache gt = newGtCache(cfg, Map.of("Hello world", "機翻哈囉"));
        TranslationService service = new TranslationService(cfg, gt, ai);

        aiMode.set("down");
        cfg.aiChat = true;
        service.translateChat("Hello world");
        pump(service);
        assertEquals(1, aiCalls.get());
        assertTrue(ai.hasFailureState("Hello world"));
        assertFalse(service.translateChat("Hello world").changed());

        cfg.aiChat = false;
        service.translateChat("Hello world");
        pump(service);

        assertEquals(1, gtCalls.get(), "a failure mark is not an AI hit: Google is asked once");
        assertEquals("機翻哈囉", service.translateChat("Hello world").translated());
        assertEquals(1, aiCalls.get());
    }

    @Test
    void aiKeptOriginalIsNotAHitSoTheTextGoesToGoogle() {
        TranslatorConfig cfg = machineConfig();
        // The AI answers with the source three times: the cache learns "keep the original".
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult(text, "en");
        }, cfg.targetLang, DIRECT, 1000, 1_000L, () -> now[0]);
        TranslationCache gt = newGtCache(cfg, Map.of("Welcome to the server", "歡迎來到伺服器"));
        TranslationService service = new TranslationService(cfg, gt, ai);
        cfg.aiChat = true;
        for (int attempt = 0; attempt < 6; attempt++) {
            service.translateChat("Welcome to the server");
            pump(service);
            now[0] += 1_000_000L;
        }
        cfg.aiChat = false;
        assertNull(ai.peekFinal("Welcome to the server", false), "a kept-original row is not a hit");

        service.translateChat("Welcome to the server");
        pump(service);

        assertEquals(1, gtCalls.get());
        assertEquals("歡迎來到伺服器", service.translateChat("Welcome to the server").translated());
    }

    // ---- repository sits between the AI cache and the machine cache ----

    @Test
    void machineModeShowsTheRepositoryWordingWhenTheAiCacheLacksItWithoutRequests() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache ai = newAiCache(cfg, Map.of());
        TranslationCache gt = newGtCache(cfg, Map.of());
        TranslationService service = new TranslationService(cfg, gt, ai);
        service.setHubLookup(Map.of(SWORD, "鑽石劍(倉庫)", "Welcome to the server", "歡迎(倉庫)")::get);

        TranslationDecision item = service.translateItemLine(SWORD);
        TranslationDecision chat = service.translateChat("Welcome to the server");
        pump(service);

        assertEquals("鑽石劍(倉庫)", item.translated());
        assertEquals("歡迎(倉庫)", chat.translated());
        assertEquals(0, gtCalls.get());
        assertEquals(0, aiCalls.get());
    }

    @Test
    void theAiCacheBeatsTheRepositoryAndTheRepositoryBeatsTheMachineCache() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache ai = newAiCache(cfg, Map.of(SWORD, "鑽石劍(AI)"));
        TranslationCache gt = newGtCache(cfg, Map.of(SWORD, "鑽石劍(機翻)", "Iron Sword", "鐵劍(機翻)"));
        assertEquals("鑽石劍(AI)", ai.translateBlocking(SWORD));
        assertEquals("鑽石劍(機翻)", gt.translateBlocking(SWORD));
        assertEquals("鐵劍(機翻)", gt.translateBlocking("Iron Sword"));
        gtCalls.set(0);
        TranslationService service = new TranslationService(cfg, gt, ai);
        service.setHubLookup(Map.of(SWORD, "鑽石劍(倉庫)", "Iron Sword", "鐵劍(倉庫)")::get);

        assertEquals("鑽石劍(AI)", service.translateItemLine(SWORD).translated());
        assertEquals("鐵劍(倉庫)", service.translateItemLine("Iron Sword").translated());
        assertEquals(0, gtCalls.get());
    }

    // ---- online translation off: AI cache and repository still show, 0 requests ----

    @Test
    void withOnlineTranslationOffTheAiCacheAndTheRepositoryStillShow() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache ai = newAiCache(cfg, Map.of(SWORD, "鑽石劍"));
        TranslationCache gt = newGtCache(cfg, Map.of());
        assertEquals("鑽石劍", ai.translateBlocking(SWORD));
        aiCalls.set(0);
        TranslationService service = new TranslationService(cfg, gt, ai);
        service.setHubLookup(Map.of("Iron Sword", "鐵劍(倉庫)")::get);
        cfg.translationRequestsEnabled = false;

        for (int frame = 0; frame < 3; frame++) {
            assertEquals("鑽石劍", service.translateItemLine(SWORD).translated());
            assertEquals("鐵劍(倉庫)", service.translateItemLine("Iron Sword").translated());
            assertFalse(service.translateChat("Something nobody translated").changed());
        }
        AtomicReference<String> asyncChat = new AtomicReference<>();
        service.translateChatAsync(SWORD, asyncChat::set);
        pump(service);

        assertEquals("鑽石劍", asyncChat.get(), "the async path shows the AI wording too");
        assertEquals(0, gtCalls.get());
        assertEquals(0, aiCalls.get());
    }

    // ---- R on a text that currently shows AI wording ----

    @Test
    void retranslateWithMachineEngineBuysOneGoogleRequestAndKeepsShowingTheMachineWording() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache ai = newAiCache(cfg, Map.of(SWORD, "鑽石劍"));
        TranslationCache gt = newGtCache(cfg, Map.of(SWORD, "鑽石劍(機翻)"));
        assertEquals("鑽石劍", ai.translateBlocking(SWORD));
        TranslationService service = new TranslationService(cfg, gt, ai);
        // The repository holds yet another wording: it must not cover the retranslate either.
        service.setHubLookup(Map.of(SWORD, "鑽石劍(倉庫)")::get);
        // Before R the AI wording wins over the repository.
        assertEquals("鑽石劍", service.translateItemLine(SWORD).translated());
        assertEquals(0, gtCalls.get());

        List<String> lines = List.of(SWORD);
        assertTrue(service.shouldFullyRetranslateOnKeyPress(lines),
                "a line showing AI wording is a displayed translation: R redoes it");
        service.retranslate(lines);
        pump(service);

        assertEquals(1, gtCalls.get(), "R sends exactly one Google request");
        assertNull(ai.getCachedFinal(SWORD), "the AI wording of that text is voided");
        assertEquals("鑽石劍(機翻)", service.translateItemLine(SWORD).translated());
        for (int frame = 0; frame < 3; frame++) {
            assertEquals("鑽石劍(機翻)", service.translateItemLine(SWORD).translated(),
                    "later lookups keep the machine wording");
        }
        pump(service);
        assertEquals(1, gtCalls.get());
        assertEquals(0, aiCalls.get() - 1, "the AI engine is asked nothing after the seed");
    }

    @Test
    void aiModeRetranslateIsUnchanged() {
        TranslatorConfig cfg = machineConfig();
        cfg.aiTooltip = true;
        TranslationCache ai = newAiCache(cfg, Map.of(SWORD, "鑽石劍(AI新)"));
        TranslationCache gt = newGtCache(cfg, Map.of(SWORD, "鑽石劍(機翻)"));
        assertEquals("鑽石劍(AI新)", ai.translateBlocking(SWORD));
        aiCalls.set(0);
        TranslationService service = new TranslationService(cfg, gt, ai);
        assertEquals("鑽石劍(AI新)", service.translateItemLine(SWORD).translated());

        service.retranslate(List.of(SWORD));
        pump(service);

        assertEquals(1, aiCalls.get(), "AI mode R asks the AI again");
        assertEquals(0, gtCalls.get(), "AI mode R never buys Google");
        assertEquals("鑽石劍(AI新)", service.translateItemLine(SWORD).translated());
    }

    // ---- AI mode never reads the machine cache ahead of the AI ----

    @Test
    void aiModeIgnoresTheMachineCacheAndStillOrdersAiThenRepository() {
        TranslatorConfig cfg = machineConfig();
        cfg.aiTooltip = true;
        TranslationCache ai = newAiCache(cfg, Map.of());
        TranslationCache gt = newGtCache(cfg, Map.of(SWORD, "鑽石劍(機翻)"));
        assertEquals("鑽石劍(機翻)", gt.translateBlocking(SWORD));
        gtCalls.set(0);
        TranslationService service = new TranslationService(cfg, gt, ai);
        service.setHubLookup(Map.of(SWORD, "鑽石劍(倉庫)")::get);

        TranslationDecision item = service.translateItemLine(SWORD);

        assertNotNull(item);
        assertEquals("鑽石劍(倉庫)", item.translated(), "AI service: AI cache -> repository");
        assertEquals(0, gtCalls.get());
        assertEquals(0, aiCalls.get());
    }

    // ---- warm path: a machine engine does not buy what the AI cache already answers ----

    @Test
    void requestItemLinesDoesNotBuyATextTheAiCacheOrTheRepositoryAlreadyAnswers() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache ai = newAiCache(cfg, Map.of(SWORD, "鑽石劍"));
        TranslationCache gt = newGtCache(cfg, Map.of());
        assertEquals("鑽石劍", ai.translateBlocking(SWORD));
        TranslationService service = new TranslationService(cfg, gt, ai);
        service.setHubLookup(Map.of("Iron Sword", "鐵劍(倉庫)")::get);

        service.requestItemLines(List.of(SWORD, "Iron Sword", "Gold Sword"));
        pump(service);

        assertEquals(1, gtCalls.get(), "only the text nobody answered is bought");
        Map<String, String> shown = new HashMap<>();
        for (String line : List.of(SWORD, "Iron Sword", "Gold Sword")) {
            shown.put(line, service.translateItemLine(line).translated());
        }
        assertEquals("鑽石劍", shown.get(SWORD));
        assertEquals("鐵劍(倉庫)", shown.get("Iron Sword"));
        assertEquals("機翻譯文:Gold Sword", shown.get("Gold Sword"));
    }
}
