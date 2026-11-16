package com.dragonmeow.nyanlex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import com.dragonmeow.nyanlex.warmup.WarmupCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * While the show-original key is on, the game's own language files read as English, so text the
 * target-language filter normally skips looks untranslated. The rule: no NEW translation work is
 * opened while it is on (that would spend tokens on text nobody needs translated); work queued or
 * in flight before it went on finishes normally; with the key off everything works as before.
 * The translator is an inline fake that counts calls; every surface runs on the AI engine, so each
 * entry point would send if the key were off.
 */
class ShowOriginalNoNewWorkTest {

    private static final Executor DIRECT = Runnable::run;
    private static final List<String> LINES = List.of("Diamond Sword", "A legendary blade");

    private final AtomicInteger calls = new AtomicInteger();

    private TranslationService service() {
        return service(cfg -> { });
    }

    private TranslationService service(Consumer<TranslatorConfig> tweak) {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.aiTooltip = true;
        cfg.aiScoreboard = true;
        cfg.aiName = true;
        cfg.aiBossBar = true;
        cfg.aiTitle = true;
        cfg.aiActionBar = true;
        cfg.aiBook = true;
        cfg.aiScreenText = true;
        tweak.accept(cfg);
        Translator counting = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
        TranslationCache gt = new TranslationCache(counting, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(counting, cfg.targetLang, DIRECT, 1000);
        return new TranslationService(cfg, gt, ai);
    }

    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
        s.flushBatches();
    }

    /** Every public entry that can open translation work, named for the failure message. */
    private static Map<String, Consumer<TranslationService>> entries() {
        Map<String, Consumer<TranslationService>> m = new LinkedHashMap<>();
        m.put("requestScreenTextAsync", s -> s.requestScreenTextAsync("Open Settings", r -> { }));
        m.put("requestLiveScreenTextAsync", s -> s.requestLiveScreenTextAsync("Open Settings", r -> { }));
        m.put("requestActionBarAsync", s -> s.requestActionBarAsync("Collected 10 coins", r -> { }));
        m.put("requestActionBarNow", s -> s.requestActionBarNow("Collected 10 coins"));
        m.put("requestItemLines", s -> s.requestItemLines(LINES));
        m.put("warmTooltipBatch", s -> s.warmTooltipBatch(LINES));
        m.put("warmTooltipBatchBackground", s -> s.warmTooltipBatchBackground(LINES));
        m.put("warmContentBatchBackground(items)",
                s -> s.warmContentBatchBackground(WarmupCategory.ITEMS, LINES));
        m.put("warmContentBatchBackground(screen)",
                s -> s.warmContentBatchBackground(WarmupCategory.SCREEN, List.of("Quest title")));
        m.put("warmBookBatch", s -> s.warmBookBatch(List.of("Once upon a time there was a book.")));
        m.put("warmWorldTextBatch", s -> s.warmWorldTextBatch(List.of("Welcome to the shop")));
        m.put("warmScoreboardBatch", s -> s.warmScoreboardBatch(List.of("Objective: Collect coins")));
        m.put("warmNamesBatch", s -> s.warmNamesBatch(List.of("Ender Pearl", "Enchanted Book")));
        m.put("retranslate", s -> s.retranslate(LINES));
        m.put("retranslateScreen", s -> s.retranslateScreen(List.of("Open Settings")));
        m.put("warmUp", s -> s.warmUp("Diamond Sword"));
        m.put("translateItemLine", s -> s.translateItemLine("Diamond Sword"));
        m.put("translateHeld", s -> s.translateHeld("Netherite Pickaxe"));
        m.put("translateScoreboardLine", s -> s.translateScoreboardLine("Objective: Collect coins"));
        m.put("translateUi", s -> s.translateUi("Welcome to the shop"));
        m.put("translateBossBar", s -> s.translateBossBar("The Ender Dragon"));
        m.put("translateTitle", s -> s.translateTitle("You have won"));
        m.put("translateActionBar", s -> s.translateActionBar("Collected 10 coins"));
        m.put("peekActionBar", s -> s.peekActionBar("Collected 10 coins"));
        m.put("translateBook", s -> s.translateBook("Once upon a time there was a book."));
        m.put("translateScreenText", s -> s.translateScreenText("Open Settings"));
        m.put("translateQuestHudText", s -> s.translateQuestHudText("Kill ten zombies"));
        m.put("translateScreenString", s -> s.translateScreenString("Open Settings"));
        m.put("translateScreenScanText", s -> s.translateScreenScanText("Open Settings"));
        m.put("translateChat", s -> s.translateChat("Hello everyone, welcome to the server"));
        return m;
    }

    @Test
    void everyEntryOpensNothingWhileTheKeyIsOn() {
        for (Map.Entry<String, Consumer<TranslationService>> entry : entries().entrySet()) {
            calls.set(0);
            TranslationService s = service();
            assertTrue(s.toggleShowOriginal());
            entry.getValue().accept(s);
            pump(s);
            assertEquals(0, calls.get(), entry.getKey() + " must not open translation work while the key is on");
        }
    }

    @Test
    void theSameEntriesDoSendWhenTheKeyIsOff() {
        // Guards the test above against passing for the wrong reason (an entry that never sends).
        // The lookups are not asked to: they send only on a render miss, which the pump covers.
        List<String> neverSendsHere = List.of("requestActionBarNow", "peekActionBar", "warmUp");
        for (Map.Entry<String, Consumer<TranslationService>> entry : entries().entrySet()) {
            if (neverSendsHere.contains(entry.getKey())) continue;
            calls.set(0);
            TranslationService s = service();
            entry.getValue().accept(s);
            pump(s);
            assertTrue(calls.get() > 0, entry.getKey() + " should send with the key off");
        }
    }

    @Test
    void theActionBarAndWarmUpEntriesAlsoWorkOnceTheKeyIsOffAgain() {
        TranslationService s = service();
        assertTrue(s.toggleShowOriginal());
        assertFalse(s.requestActionBarNow("Collected 10 coins"), "refused while the key is on");
        assertFalse(s.warmUp("Diamond Sword"), "not reported as warmed while the key is on");
        pump(s);
        assertEquals(0, calls.get());

        assertFalse(s.toggleShowOriginal());
        assertTrue(s.requestActionBarNow("Collected 10 coins"));
        assertTrue(s.warmUp("Diamond Sword"));
        pump(s);
        assertTrue(calls.get() > 0);
    }

    @Test
    void aHudScanStartedBeforeTheKeyWentOnSendsNothing() {
        // The scoreboard is on the machine engine here, so a render miss sends nothing by itself
        // and only the scan buys it. Control first: without the key the scan does send.
        TranslationService control = service(cfg -> cfg.aiScoreboard = false);
        AtomicInteger controlSent = new AtomicInteger(-1);
        assertTrue(control.beginHudCapture(controlSent::set));
        control.translateScoreboardLine("Objective: Collect coins");
        pump(control);
        pump(control);
        assertEquals(1, controlSent.get());
        assertTrue(calls.get() > 0);

        calls.set(0);
        TranslationService s = service(cfg -> cfg.aiScoreboard = false);
        AtomicInteger sent = new AtomicInteger(-1);
        assertTrue(s.beginHudCapture(sent::set));
        s.translateScoreboardLine("Objective: Collect coins");
        s.toggleShowOriginal();
        pump(s);
        pump(s);
        assertEquals(0, sent.get(), "the scan ends with nothing sent");
        assertEquals(0, calls.get());
    }

    @Test
    void retranslateWhileTheKeyIsOnKeepsTheCachedRows() {
        TranslationService s = service();
        s.requestItemLines(List.of("Diamond Sword"));
        pump(s);
        int afterSeed = calls.get();
        assertTrue(afterSeed > 0);

        s.toggleShowOriginal();
        s.retranslate(List.of("Diamond Sword"));
        s.retranslateScreen(List.of("Diamond Sword"));
        pump(s);
        assertEquals(afterSeed, calls.get(), "nothing resent");

        s.toggleShowOriginal();
        TranslationDecision back = s.translateItemLine("Diamond Sword");
        assertTrue(back.changed(), "the stored translation survived the key being on");
        assertEquals(afterSeed, calls.get());
    }

    @Test
    void workQueuedBeforeTheKeyWentOnStillCompletes() {
        TranslationService s = service();
        // A render miss queues the line; the batch has not been sent yet.
        assertFalse(s.translateScreenText("Open Settings").changed());
        assertFalse(s.translateScoreboardLine("Objective: Collect coins").changed());
        assertEquals(0, calls.get());

        s.toggleShowOriginal();
        pump(s);
        assertTrue(calls.get() >= 2, "both queued lines were sent although the key went on");

        s.toggleShowOriginal();
        int afterQueued = calls.get();
        assertTrue(s.translateScreenText("Open Settings").changed());
        assertTrue(s.translateScoreboardLine("Objective: Collect coins").changed());
        assertEquals(afterQueued, calls.get(), "answered from the cache, nothing new asked");
    }

    @Test
    void asyncRequestsQueuedBeforeTheKeyWentOnStillDeliver() {
        TranslationService s = service();
        List<String> got = new ArrayList<>();
        s.requestLiveScreenTextAsync("Open Settings", got::add);
        s.toggleShowOriginal();
        pump(s);
        assertEquals(1, got.size(), "the callback of the request sent before the key went on still fires");
    }
}
