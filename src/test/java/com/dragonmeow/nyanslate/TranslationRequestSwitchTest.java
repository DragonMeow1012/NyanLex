package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.cache.PersistentStore;
import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.service.ChatDeliverySession;
import com.dragonmeow.nyanslate.service.ChatRequestProfile;
import com.dragonmeow.nyanslate.service.TranslationService;
import com.dragonmeow.nyanslate.translate.ChurnGuard;
import com.dragonmeow.nyanslate.translate.GoogleFreeTranslator;
import com.dragonmeow.nyanslate.translate.HttpTransport;
import com.dragonmeow.nyanslate.translate.RequestPacer;
import com.dragonmeow.nyanslate.translate.TranslationDebugLog;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import com.dragonmeow.nyanslate.translate.Translator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 總開關 {@code translationRequestsEnabled}: while it is off, cached rows keep showing,
 * nothing new is queued or sent, nothing is recorded as a failure, and switching it
 * back on simply resumes requests. Every collaborator is an inline fake.
 */
class TranslationRequestSwitchTest {

    private static final Executor DIRECT = Runnable::run;

    private static PersistentStore inlineStore(Map<String, String> backing) {
        return new PersistentStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void clear() { backing.clear(); }
            @Override public void remove(String key) { backing.remove(key); }
            @Override public Map<String, String> entries() { return new HashMap<>(backing); }
        };
    }

    /** Inline translator: counts calls and answers "T:" + text. */
    private static Translator counting(AtomicInteger calls) {
        return (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
    }

    private static void pump(TranslationService service, int ticks) {
        for (int i = 0; i < ticks; i++) service.flushBatches();
    }

    /** Size of a private session table (map or collection) inside the cache. */
    private static int tracked(TranslationCache cache, String field) {
        try {
            Field declared = TranslationCache.class.getDeclaredField(field);
            declared.setAccessible(true);
            Object value = declared.get(cache);
            if (value instanceof Map) return ((Map<?, ?>) value).size();
            if (value instanceof Collection) return ((Collection<?>) value).size();
            throw new AssertionError(field + " is not a map or collection");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertNoFailureState(TranslationCache cache, String label) {
        for (String field : List.of("failedUntil", "contentFailures", "contentRetryAttempts",
                "retrySnapshots", "provisionalRetryAttempts")) {
            assertEquals(0, tracked(cache, field), label + " must not record " + field);
        }
    }

    @Test
    void switchedOffStillShowsCachedRowsOfBothEngines() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.aiScoreboard = false;
        cfg.translationRequestsEnabled = false;
        AtomicInteger gtCalls = new AtomicInteger();
        AtomicInteger aiCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(gtCalls), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(aiCalls), cfg.targetLang, DIRECT, 100);
        gt.importTranslations(Map.of("Purse", "錢包"));
        ai.importTranslations(Map.of("Hello world", "你好，世界"));
        TranslationService s = new TranslationService(cfg, gt, ai);

        assertEquals("錢包", s.translateScoreboardLine("Purse").translated(), "GT cache hit");
        assertEquals("你好，世界", s.translateChat("Hello world").translated(), "AI cache hit");
        List<String> chat = new ArrayList<>();
        s.translateChatAsync("Hello world", chat::add);
        assertEquals(List.of("你好，世界"), chat, "a cached AI row still completes one-shot chat");

        pump(s, 3);
        assertEquals(0, gtCalls.get());
        assertEquals(0, aiCalls.get());
    }

    @Test
    void switchedOffMissShowsOriginalAndRecordsNothingEvenAfterTimePasses() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.bookMode = DisplayMode.TRANSLATION;
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.translationRequestsEnabled = false;
        long[] now = {0L};
        AtomicInteger gtCalls = new AtomicInteger();
        AtomicInteger aiCalls = new AtomicInteger();
        Map<String, String> gtDisk = new HashMap<>();
        Map<String, String> aiDisk = new HashMap<>();
        Map<String, String> gtFailures = new HashMap<>();
        Map<String, String> aiFailures = new HashMap<>();
        TranslationCache gt = new TranslationCache(counting(gtCalls), cfg.targetLang, DIRECT, 100,
                1_000L, () -> now[0], inlineStore(gtDisk));
        gt.setFailureStore(inlineStore(gtFailures));
        TranslationCache ai = new TranslationCache(counting(aiCalls), cfg.targetLang, DIRECT, 100,
                1_000L, () -> now[0], inlineStore(aiDisk));
        ai.setFailureStore(inlineStore(aiFailures));
        ai.setProvisionalRetryGate(() -> true);
        TranslationService s = new TranslationService(cfg, gt, ai);
        s.setBatchWindowMs(() -> 0);
        ChurnGuard guard = new ChurnGuard(2, 60_000L, 300_000L, () -> now[0]);
        gt.setChurnGuard(guard);
        ai.setChurnGuard(guard);
        List<String> chat = new ArrayList<>();
        List<String> actionBar = new ArrayList<>();
        List<String> screen = new ArrayList<>();

        for (int round = 0; round < 3; round++) {
            assertFalse(s.translateChat("Hello world").changed());
            assertFalse(s.translateItemLine("Diamond Sword").changed());
            assertFalse(s.translateScoreboardLine("Vote now !").changed());
            assertFalse(s.translateScoreboardLine("Vote now !!").changed());
            assertFalse(s.translateUi("Lone Adventurer").changed());
            assertFalse(s.isTooltipTranslationReady("Diamond Sword"));
            s.warmTooltipBatch(List.of("Diamond Sword", "Used in smelting"));
            s.warmNamesBatch(List.of("Ender Pearl"));
            s.warmScoreboardBatch(List.of("Purse: 100", "Bits: 20"));
            s.warmBookBatch(List.of("Once upon a time"));
            s.translateChatAsync("Welcome to the server", chat::add);
            s.requestActionBarAsync("You received 5 coins!", actionBar::add);
            s.requestLiveScreenTextAsync("Open the menu", screen::add);
            now[0] += 600_000L;
            pump(s, 5);
        }

        assertEquals(0, gtCalls.get(), "no GT request while switched off");
        assertEquals(0, aiCalls.get(), "no AI request while switched off");
        assertEquals(Arrays.asList(null, null, null), chat, "every chat line completes as original");
        assertTrue(actionBar.isEmpty());
        assertTrue(screen.isEmpty());
        assertTrue(gtFailures.isEmpty(), "GT failure ledger: " + gtFailures);
        assertTrue(aiFailures.isEmpty(), "AI failure ledger: " + aiFailures);
        assertTrue(gtDisk.isEmpty(), "nothing (not even keep-original) was learned: " + gtDisk);
        assertTrue(aiDisk.isEmpty(), "nothing (not even keep-original) was learned: " + aiDisk);
        assertEquals(0, guard.signatureCount(), "ChurnGuard must not be fed");
        for (TranslationCache cache : List.of(gt, ai)) {
            assertNoFailureState(cache, "switched-off lookup");
            assertEquals(0, tracked(cache, "sessionRetryDemand"), "no session retry demand");
            assertEquals(0, tracked(cache, "queue"));
            assertEquals(0, tracked(cache, "flights"));
            assertEquals(0, tracked(cache, "finalWaiters"), "no AI recovery waiter");
            assertEquals(0, cache.pendingCount());
            assertFalse(cache.hasFailureState("Hello world"));
            assertFalse(cache.hasFailureState("Diamond Sword"));
        }
    }

    @Test
    void switchedOffChatCallbackCompletesImmediatelyWithTheOriginal() {
        for (boolean aiChat : new boolean[] {false, true}) {
            TranslatorConfig cfg = new TranslatorConfig();
            cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
            cfg.aiChat = aiChat;
            cfg.translationRequestsEnabled = false;
            AtomicInteger calls = new AtomicInteger();
            TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            TranslationService s = new TranslationService(cfg, gt, ai);
            List<String> texts = new ArrayList<>();
            List<Boolean> finals = new ArrayList<>();

            s.translateChatAsyncDetailed("Hello world", result -> {
                texts.add(result.text());
                finals.add(result.finalResult());
            });
            s.translateChatAsyncDetailed("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧world⟦/CS1⟧", result -> {
                texts.add(result.text());
                finals.add(result.finalResult());
            });

            assertEquals(Arrays.asList(null, null), texts,
                    "no tick is needed: the chat queue must not wait for its timeout");
            assertEquals(List.of(true, true), finals,
                    "nothing can recover while switched off, so no chat is kept waiting (ai=" + aiChat + ")");
            assertEquals(0, calls.get());
        }
    }

    @Test
    void switchedOffColourChatStillShowsACachedSemanticRow() {
        for (boolean aiChat : new boolean[] {false, true}) {
            TranslatorConfig cfg = new TranslatorConfig();
            cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
            cfg.aiChat = aiChat;
            cfg.disableGoogleFallbackForAi = false; // this part exercises the GT-fallback path
            cfg.translationRequestsEnabled = false;
            AtomicInteger calls = new AtomicInteger();
            TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            gt.importTranslations(Map.of("Hello brave world", "你好勇敢的世界"));
            TranslationService s = new TranslationService(cfg, gt, ai);
            List<String> texts = new ArrayList<>();
            List<Boolean> finals = new ArrayList<>();

            s.translateChatAsyncDetailed("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧brave world⟦/CS1⟧", result -> {
                texts.add(result.text());
                finals.add(result.finalResult());
            });

            assertEquals(1, texts.size(), "ai=" + aiChat);
            assertTrue(com.dragonmeow.nyanslate.translate.TextFilter.isStyleFallback(texts.get(0)),
                    "the exact colour projection cannot be bought; the semantic row is shown (ai=" + aiChat + ")");
            assertEquals("你好勇敢的世界",
                    com.dragonmeow.nyanslate.translate.TextFilter.stripStyleFallback(texts.get(0)));
            assertEquals(List.of(true), finals);
            assertEquals(0, calls.get());
        }

        // Strict AI mode never shows GT wording, not even as a colour fallback.
        TranslatorConfig strict = new TranslatorConfig();
        strict.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        strict.aiChat = true;
        strict.disableGoogleFallbackForAi = true;
        strict.translationRequestsEnabled = false;
        AtomicInteger calls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(calls), strict.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(calls), strict.targetLang, DIRECT, 100);
        gt.importTranslations(Map.of("Hello brave world", "你好勇敢的世界"));
        TranslationService s = new TranslationService(strict, gt, ai);
        List<String> texts = new ArrayList<>();
        s.translateChatAsyncDetailed("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧brave world⟦/CS1⟧",
                result -> texts.add(result.text()));
        assertEquals(Arrays.asList((String) null), texts);
        assertEquals(0, calls.get());
    }

    @Test
    void itemNameCorrectionKeepsTheGtRowWhileSwitchedOff() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        AtomicInteger calls = new AtomicInteger();
        List<String> tooltip = List.of("Aspect of the End Sword Ability", "Right click to use");
        for (boolean on : new boolean[] {false, true}) {
            cfg.translationRequestsEnabled = on;
            TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            gt.importTranslations(Map.of("Aspect of the End", "末影之眼"));
            ai.importTranslations(Map.of(
                    "Aspect of the End", "末影之視",
                    "Aspect of the End Sword Ability", "終界之刃 劍技",
                    "Sword Ability", "劍技"));
            TranslationService s = new TranslationService(cfg, gt, ai);

            s.reconcileItemNameWithTooltip("Aspect of the End", tooltip);

            assertEquals("終界之刃", ai.getCachedFinal("Aspect of the End"),
                    "the local, request-free correction still runs (on=" + on + ")");
            if (on) {
                assertNull(gt.getCached("Aspect of the End"), "switched on: the GT copy is retired");
            } else {
                assertEquals("末影之眼", gt.getCached("Aspect of the End"),
                        "switched off: no row is deleted that could not be re-requested");
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    void collectedWorkIsDroppedWhenSwitchedOffAndNothingCountsAsFailure() {
        AtomicInteger calls = new AtomicInteger();
        long[] now = {0L};
        boolean[] open = {true};
        Map<String, String> failures = new HashMap<>();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100,
                1_000L, () -> now[0]);
        cache.setFailureStore(inlineStore(failures));
        cache.setRequestGate(() -> open[0]);
        cache.setBatchWindowMs(() -> 5_000);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello world", got::add, true);
        cache.requestBatched("Good morning");
        cache.flushBatch(); // the collection window is still open
        assertEquals(2, cache.pendingCount());
        assertTrue(got.isEmpty());

        open[0] = false;
        cache.flushBatch();
        assertEquals(Arrays.asList((String) null), got, "the always-callback completes with null");
        assertEquals(0, cache.pendingCount(), "every permit is returned");
        assertEquals(0, tracked(cache, "queue"));
        for (int i = 0; i < 5; i++) {
            now[0] += 60_000L;
            cache.flushBatch();
        }
        assertEquals(0, calls.get());
        assertTrue(failures.isEmpty());
        assertNoFailureState(cache, "dropped collector");

        open[0] = true;
        cache.setBatchWindowMs(() -> 0);
        cache.requestCoalesced("Hello world", got::add, true);
        cache.flushBatch();
        assertEquals(1, calls.get(), "switching back on requests the same text again");
        assertEquals("T:Hello world", got.get(1));
    }

    @Test
    void workAlreadyHandedToTheExecutorGivesUpUnsent() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] open = {true};
        Deque<Runnable> workers = new ArrayDeque<>();
        Map<String, String> failures = new HashMap<>();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", workers::add, 100,
                1_000L, () -> 0L);
        cache.setFailureStore(inlineStore(failures));
        cache.setRequestGate(() -> open[0]);
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        cache.setDebugLog("GT", debug);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello world", got::add, true);
        cache.flushBatch();
        cache.flushBatch(); // collected batch handed to the (manual) worker queue
        cache.translateAsyncAlways("Good morning", got::add); // single flight, also queued
        assertEquals(2, workers.size());
        assertEquals(2, cache.pendingCount());

        open[0] = false;
        while (!workers.isEmpty()) workers.poll().run();

        assertEquals(0, calls.get(), "queued tasks must not reach the backend");
        assertEquals(Arrays.asList(null, null), got, "their flights complete with null");
        assertEquals(0, cache.pendingCount());
        assertEquals(0, tracked(cache, "flights"));
        assertTrue(failures.isEmpty());
        assertNoFailureState(cache, "abandoned task");
        assertTrue(debug.snapshot(10).isEmpty(), "an unsent request leaves no debug row");
        assertNull(cache.translateBlocking("Good evening"),
                "an explicit blocking request sends nothing either");
        assertTrue(cache.warmBatch(List.of("Good night")));
        assertEquals(0, calls.get());

        open[0] = true;
        cache.requestCoalesced("Hello world", got::add, true);
        cache.flushBatch();
        cache.flushBatch();
        workers.poll().run();
        assertEquals(1, calls.get());
        assertEquals("T:Hello world", got.get(2));
    }

    @Test
    void requestSleepingInThePacerGivesUpWhenSwitchedOffMeanwhile() {
        AtomicLong clock = new AtomicLong(1_000L);
        boolean[] open = {true};
        AtomicInteger http = new AtomicInteger();
        // Inline sleeper: the user switches requests off while this worker is sleeping.
        RequestPacer pacer = new RequestPacer(() -> 400L, clock::get, ms -> open[0] = false);
        HttpTransport transport = url -> {
            http.incrementAndGet();
            return "[[[\"你好世界\",\"Hello world\",null,null]],null,\"en\"]";
        };
        GoogleFreeTranslator google = new GoogleFreeTranslator(transport, "auto", pacer);
        pacer.acquire(); // an earlier request owns the current send slot
        Map<String, String> failures = new HashMap<>();
        TranslationCache cache = new TranslationCache(google, "zh-TW", DIRECT, 100,
                1_000L, clock::get);
        cache.setFailureStore(inlineStore(failures));
        cache.setRequestGate(() -> open[0]);
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        cache.setDebugLog("GT", debug);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello world", got::add, true);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(0, http.get(), "the woken worker must not send");
        assertEquals(Arrays.asList((String) null), got, "its flight callback completes with null");
        assertEquals(0, cache.pendingCount());
        assertTrue(failures.isEmpty(), "not a failure: " + failures);
        assertFalse(cache.hasFailureState("Hello world"));
        assertNoFailureState(cache, "paced request");
        assertTrue(debug.snapshot(10).isEmpty(),
                "neither FAILED nor a permanent waiting row: " + debug.snapshot(10));

        open[0] = true;
        clock.addAndGet(10_000L);
        cache.requestCoalesced("Hello world", got::add, true);
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(1, http.get(), "switching back on resumes immediately (no backoff)");
        assertEquals("你好世界", got.get(1));
    }

    @Test
    void requestAlreadyOnTheWireStillStoresItsResult() {
        boolean[] open = {true};
        AtomicInteger calls = new AtomicInteger();
        Translator inFlight = (text, target) -> {
            calls.incrementAndGet();
            open[0] = false; // switched off while this HTTP request was already sent
            return new TranslationResult("T:" + text, "en");
        };
        TranslationCache cache = new TranslationCache(inFlight, "zh-TW", DIRECT, 100);
        cache.setRequestGate(() -> open[0]);
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("Hello world", got::add, true);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(1, calls.get());
        assertEquals(List.of("T:Hello world"), got, "an answer that already arrived is delivered");
        assertEquals("T:Hello world", cache.getCached("Hello world"), "and cached for display");
    }

    @Test
    void switchingBackOnRequestsThePreviousMissNormally() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.translationRequestsEnabled = false;
        AtomicInteger calls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, gt, ai);

        assertFalse(s.translateItemLine("Diamond Sword").changed());
        assertFalse(s.translateChat("Hello world").changed());
        pump(s, 4);
        assertEquals(0, calls.get());

        cfg.translationRequestsEnabled = true;
        assertFalse(s.translateItemLine("Diamond Sword").changed(), "the next frame queues it");
        assertFalse(s.translateChat("Hello world").changed());
        pump(s, 2);
        assertEquals(2, calls.get());
        assertEquals("T:Diamond Sword", s.translateItemLine("Diamond Sword").translated());
        assertEquals("T:Hello world", s.translateChat("Hello world").translated());
    }

    @Test
    void provisionalHitIsShownWithoutStartingItsSupplementWhileSwitchedOff() {
        AtomicInteger calls = new AtomicInteger();
        Translator standInThenFinal = (text, target) -> calls.incrementAndGet() == 1
                ? new TranslationResult("GT:" + text, "en", true)
                : new TranslationResult("AI:" + text, "en");
        boolean[] open = {true};
        Deque<Runnable> workers = new ArrayDeque<>();
        TranslationCache ai = new TranslationCache(standInThenFinal, "zh-TW", workers::add, 100);
        ai.setProvisionalRetryGate(() -> true);
        ai.setRequestGate(() -> open[0]);
        assertEquals("GT:Hello world", ai.translateBlocking("Hello world"));
        assertEquals(1, calls.get());

        open[0] = false;
        List<String> finals = new ArrayList<>();
        assertEquals("GT:Hello world", ai.getCached("Hello world"), "the stand-in still shows");
        ai.requestCoalescedFinal("Hello world", finals::add);
        ai.flushBatch();
        ai.flushBatch();
        assertTrue(workers.isEmpty(), "no provisional supplement is even scheduled");
        assertEquals(0, tracked(ai, "provisionalRetryAttempts"));
        assertEquals(0, tracked(ai, "finalWaiters"));
        assertTrue(finals.isEmpty());

        // A supplement scheduled just before switching off gives up unsent.
        open[0] = true;
        ai.getCached("Hello world");
        assertEquals(1, workers.size());
        open[0] = false;
        workers.poll().run();
        assertEquals(1, calls.get(), "the scheduled supplement must not reach the backend");
        assertEquals(0, tracked(ai, "provisionalRetrying"));
        assertEquals(0, tracked(ai, "failedUntil"));
        assertEquals(0, tracked(ai, "contentRetryAttempts"));

        open[0] = true;
        ai.getCached("Hello world"); // observing the stand-in again starts the supplement
        workers.poll().run();
        assertEquals(2, calls.get());
        assertEquals("AI:Hello world", ai.getCached("Hello world"));
    }

    @Test
    void immediateRequestsCompleteWithNullWhileSwitchedOff() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> workers = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", workers::add, 100);
        cache.setRequestGate(() -> false);
        List<String> got = new ArrayList<>();

        cache.translateAsyncAlways("Hello world", got::add);
        cache.requestAsync("Good morning", got::add);

        assertEquals(Arrays.asList((String) null), got,
                "the always-callback completes at once; the optional one stays silent");
        assertTrue(workers.isEmpty(), "no flight is created");
        assertEquals(0, cache.pendingCount());
        assertEquals(0, tracked(cache, "sessionRetryDemand"));
        assertEquals(0, calls.get());
    }

    @Test
    void backendCallPausedMidWayEndsWithoutFailureAtEveryCallSite() {
        boolean[] open = {true};
        List<Boolean> gateSeenByBackend = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        // Inline backend standing in for a paced worker that wakes up after the switch
        // was turned off: exactly what RequestPacer does after its sleep.
        Translator pausedWhileWaiting = (text, target) -> {
            calls.incrementAndGet();
            open[0] = false;
            gateSeenByBackend.add(com.dragonmeow.nyanslate.translate.RequestGate.isOpen());
            com.dragonmeow.nyanslate.translate.RequestGate.checkOpen();
            return new TranslationResult("T:" + text, "en");
        };
        Map<String, String> failures = new HashMap<>();
        TranslationCache cache = new TranslationCache(pausedWhileWaiting, "zh-TW", DIRECT, 100,
                1_000L, () -> 0L);
        cache.setFailureStore(inlineStore(failures));
        cache.setRequestGate(() -> open[0]);
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        cache.setDebugLog("AI", debug);
        List<String> got = new ArrayList<>();

        assertNull(cache.translateBlocking("Alpha one"));            // blocking call site
        open[0] = true;
        cache.translateAsyncAlways("Beta two", got::add);             // single-flight call site
        open[0] = true;
        cache.requestCoalesced("Gamma three", got::add, true);        // collected batch call site
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(3, calls.get());
        assertEquals(List.of(false, false, false), gateSeenByBackend,
                "the cache's live gate is bound to the worker during the backend call");
        assertTrue(com.dragonmeow.nyanslate.translate.RequestGate.isOpen(),
                "the binding is removed again after the call");
        assertEquals(Arrays.asList(null, null), got);
        assertTrue(failures.isEmpty(), "paused is not a failure: " + failures);
        assertNoFailureState(cache, "paused backend call");
        assertTrue(debug.snapshot(10).isEmpty(), "no FAILED and no endless waiting row");

        // Provisional-supplement call site.
        AtomicInteger supplements = new AtomicInteger();
        boolean[] open2 = {true};
        Translator standInThenPaused = (text, target) -> {
            if (supplements.incrementAndGet() == 1) {
                return new TranslationResult("GT:" + text, "en", true);
            }
            open2[0] = false;
            com.dragonmeow.nyanslate.translate.RequestGate.checkOpen();
            return new TranslationResult("AI:" + text, "en");
        };
        Map<String, String> failures2 = new HashMap<>();
        TranslationCache ai = new TranslationCache(standInThenPaused, "zh-TW", DIRECT, 100,
                1_000L, () -> 0L);
        ai.setFailureStore(inlineStore(failures2));
        ai.setProvisionalRetryGate(() -> true);
        ai.setRequestGate(() -> open2[0]);
        assertEquals("GT:Delta four", ai.translateBlocking("Delta four"));
        assertEquals("GT:Delta four", ai.getCached("Delta four")); // starts the supplement inline
        assertEquals(2, supplements.get());
        assertTrue(failures2.isEmpty(), "paused supplement is not a failure: " + failures2);
        assertEquals(0, tracked(ai, "failedUntil"));
        assertEquals(0, tracked(ai, "contentRetryAttempts"));
        assertEquals(0, tracked(ai, "provisionalRetrying"));
    }

    @Test
    void retranslateHotkeysAndItemNameCorrectionNeverDeleteRowsWhileSwitchedOff() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        cfg.translationRequestsEnabled = false;
        AtomicInteger gtCalls = new AtomicInteger();
        AtomicInteger aiCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(gtCalls), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(aiCalls), cfg.targetLang, DIRECT, 100);
        gt.importTranslations(Map.of("Hello", "你好"));
        ai.importTranslations(Map.of(
                "Hello", "哈囉",
                "Aspect of the End", "末影之視",
                "Aspect of the End Sword Ability", "終界之刃 劍技",
                "Sword Ability", "不相符的後綴"));
        TranslationService s = new TranslationService(cfg, gt, ai);

        s.retranslate(List.of("Hello"));
        s.retranslateScreen(List.of("Hello"));
        assertEquals("你好", gt.getCached("Hello"), "R/P must not delete the GT row");
        assertEquals("哈囉", ai.getCached("Hello"), "R/P must not delete the AI row");

        List<String> tooltip = List.of("Aspect of the End Sword Ability", "Right click to use");
        s.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
        assertEquals("末影之視", ai.getCachedFinal("Aspect of the End"),
                "the invalidate-and-reask branch is a no-op while switched off");
        pump(s, 3);
        assertEquals(0, gtCalls.get());
        assertEquals(0, aiCalls.get());

        // Control: the same correction really reaches that branch once requests are on.
        cfg.translationRequestsEnabled = true;
        s.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
        assertEquals(1, aiCalls.get(), "switched on, the name is re-asked with its tooltip");
        s.retranslate(List.of("Hello"));
        assertEquals("T:Hello", ai.getCached("Hello"), "switched on, R re-buys the row");
    }

    @Test
    void aiSurfaceReadsCachedGtRowsWhileSwitchedOffUnlessStrictAiMode() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.disableGoogleFallbackForAi = false; // this part exercises the GT-fallback path
        AtomicInteger gtCalls = new AtomicInteger();
        AtomicInteger aiCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(gtCalls), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(aiCalls), cfg.targetLang, DIRECT, 100);
        gt.importTranslations(Map.of("Hello world", "機器譯文"));
        TranslationService s = new TranslationService(cfg, gt, ai);

        assertFalse(s.translateChat("Hello world").changed(),
                "switched on, cached GT stays hidden until AI actually fails");

        cfg.translationRequestsEnabled = false;
        assertEquals("機器譯文", s.translateChat("Hello world").translated(),
                "switched off, the AI surface shows the cached GT row");
        List<String> chat = new ArrayList<>();
        s.translateChatAsync("Hello world", chat::add);
        assertEquals(List.of("機器譯文"), chat);

        cfg.disableGoogleFallbackForAi = true;
        assertFalse(s.translateChat("Hello world").changed(), "strict AI mode never reads GT");
        chat.clear();
        s.translateChatAsync("Hello world", chat::add);
        assertEquals(Arrays.asList((String) null), chat);

        pump(s, 3);
        assertEquals(0, gtCalls.get());
        assertEquals(0, aiCalls.get());
    }

    @Test
    void chatRequestProfileTracksTheSwitchAndFlushesTheBacklog() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        ChatRequestProfile on = ChatRequestProfile.capture(cfg, cfg.targetLang);
        cfg.translationRequestsEnabled = false;
        ChatRequestProfile off = ChatRequestProfile.capture(cfg, cfg.targetLang);
        assertNotEquals(on, off);
        assertEquals(off, ChatRequestProfile.capture(cfg, cfg.targetLang));
        assertEquals(off.hashCode(), ChatRequestProfile.capture(cfg, cfg.targetLang).hashCode());
        cfg.translationRequestsEnabled = true;
        assertEquals(on, ChatRequestProfile.capture(cfg, cfg.targetLang));

        ChatDeliverySession<Long> session = new ChatDeliverySession<>(id -> id, id -> false, 512);
        Object connection = new Object();
        Object world = new Object();
        session.observe(connection, world, on, false);
        session.add(1L);
        ChatDeliverySession.Transition<Long> transition = session.observe(connection, world, off, false);
        assertEquals(ChatDeliverySession.TransitionKind.FLUSH_ORIGINALS, transition.kind(),
                "switching requests off releases waiting chat as originals");
        assertEquals(List.of(1L), transition.originals());
    }

    @Test
    void closedGateIsAssertedByTheCacheItself() {
        TranslationCache cache = new TranslationCache(counting(new AtomicInteger()), "zh-TW", DIRECT, 10);
        assertTrue(cache.requestsAllowed(), "default: requests allowed");
        cache.setRequestGate(() -> false);
        assertFalse(cache.requestsAllowed());
        cache.setRequestGate(() -> {
            throw new IllegalStateException("broken supplier");
        });
        assertTrue(cache.requestsAllowed(), "a broken supplier must not silently stop translation");
        cache.setRequestGate(null);
        assertTrue(cache.requestsAllowed());
    }
}
