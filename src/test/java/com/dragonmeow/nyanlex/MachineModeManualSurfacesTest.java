package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.ConsentGate;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-03 "只有聊天自動翻譯，其他全部改手動": under the machine-translation engine (Google)
 * chat is the only surface that sends on its own. Scoreboard, boss bar, title, action bar, name
 * tags, book, GUI text and items show a cache hit (AI cache, repository, machine cache) but a miss
 * stays original until the player asks: R (item), P with a screen open (the screen), or P in the
 * world (the HUD, collected by {@link TranslationService#beginHudCapture}). A surface set to the
 * AI engine keeps translating on its own. Every translator, cache and executor is an inline
 * fake; requests are counted by the fake transports.
 */
class MachineModeManualSurfacesTest {

    private static final Executor DIRECT = Runnable::run;

    private final AtomicInteger gtRequests = new AtomicInteger();
    private final AtomicInteger gtBatches = new AtomicInteger();
    private final List<String> gtSent = new ArrayList<>();
    private final AtomicInteger aiRequests = new AtomicInteger();

    /** Machine backend: answers "機翻:" + text; counts requests (texts) and batches (HTTP calls). */
    private Translator machine() {
        return new Translator() {
            @Override
            public TranslationResult translate(String text, String targetLang) {
                gtBatches.incrementAndGet();
                gtRequests.incrementAndGet();
                gtSent.add(text);
                return new TranslationResult("機翻:" + text, "en");
            }

            @Override
            public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
                    throws TranslationException {
                gtBatches.incrementAndGet();
                List<TranslationResult> out = new ArrayList<>();
                for (String text : texts) {
                    gtRequests.incrementAndGet();
                    gtSent.add(text);
                    out.add(new TranslationResult("機翻:" + text, "en"));
                }
                return out;
            }
        };
    }

    private Translator ai(Map<String, String> words) {
        return (text, target) -> {
            aiRequests.incrementAndGet();
            return new TranslationResult(words.getOrDefault(text, "AI譯文:" + text), "en");
        };
    }

    private static TranslatorConfig machineConfig() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.bossBarMode = DisplayMode.TRANSLATION;
        cfg.titleMode = DisplayMode.TRANSLATION;
        cfg.actionBarMode = DisplayMode.TRANSLATION;
        cfg.nameMode = DisplayMode.TRANSLATION;
        cfg.bookMode = DisplayMode.TRANSLATION;
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiChat = cfg.aiTooltip = cfg.aiScoreboard = cfg.aiBossBar = cfg.aiTitle = false;
        cfg.aiActionBar = cfg.aiName = cfg.aiBook = cfg.aiScreenText = false;
        return cfg;
    }

    private TranslationService service(TranslatorConfig cfg, TranslationCache gt, TranslationCache ai) {
        return new TranslationService(cfg, gt, ai);
    }

    private TranslationCache gtCache(TranslatorConfig cfg) {
        return new TranslationCache(machine(), cfg.targetLang, DIRECT, 1000);
    }

    private TranslationCache aiCache(TranslatorConfig cfg, Map<String, String> words) {
        return new TranslationCache(ai(words), cfg.targetLang, DIRECT, 1000);
    }

    private static void pump(TranslationService service) {
        service.flushBatches();
        service.flushBatches();
        service.flushBatches();
        service.flushBatches();
    }

    private static final String[] SURFACE_TEXTS = {
            "Kuudra Boss Fight", "Ancient Dragon", "Wave Cleared", "Quest Completed", "Wandering Merchant",
            "The Old Library", "Open Settings"};

    private static List<TranslationDecision> allSurfaces(TranslationService s) {
        return List.of(
                s.translateScoreboardLine(SURFACE_TEXTS[0]),
                s.translateBossBar(SURFACE_TEXTS[1]),
                s.translateTitle(SURFACE_TEXTS[2]),
                s.translateActionBar(SURFACE_TEXTS[3]),
                s.translateUi(SURFACE_TEXTS[4]),
                s.translateBook(SURFACE_TEXTS[5]),
                s.translateScreenText(SURFACE_TEXTS[6]),
                s.translateItemLine("Diamond Sword"),
                s.translateHeld("Netherite Pickaxe"));
    }

    // ---- no cache: nothing is sent on its own, the original is shown ----

    @Test
    void surfacesOtherThanChatSendNothingOnTheirOwnAndShowTheOriginal() {
        TranslatorConfig cfg = machineConfig();
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));

        for (int frame = 0; frame < 3; frame++) {
            for (TranslationDecision decision : allSurfaces(s)) assertFalse(decision.changed());
            s.warmScoreboardBatch(List.of(SURFACE_TEXTS[0], "Bits Collected"));
            s.warmBookBatch(List.of(SURFACE_TEXTS[5], "Chapter Two"));
            s.warmTooltipBatch(List.of("Diamond Sword"));
            s.warmNamesBatch(List.of("Diamond Sword"));
            s.requestActionBarAsync(SURFACE_TEXTS[3], ignored -> { });
            s.requestLiveScreenTextAsync(SURFACE_TEXTS[6], ignored -> { });
            pump(s);
        }

        assertEquals(0, gtRequests.get(), "nothing but chat translates on its own under Google");
        assertEquals(0, aiRequests.get());
    }

    @Test
    void chatStillSendsOnItsOwn() {
        TranslatorConfig cfg = machineConfig();
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));

        assertFalse(s.translateChat("Welcome to the server").changed());
        pump(s);
        assertEquals("機翻:Welcome to the server", s.translateChat("Welcome to the server").translated());
        assertEquals(1, gtRequests.get());

        AtomicReference<String> async = new AtomicReference<>();
        s.translateChatAsync("Another chat line", async::set);
        pump(s);
        assertEquals("機翻:Another chat line", async.get());
        assertEquals(2, gtRequests.get());
    }

    @Test
    void aSurfaceSetToTheAiEngineStillTranslatesOnItsOwn() {
        TranslatorConfig cfg = machineConfig();
        cfg.aiScoreboard = true;
        cfg.aiActionBar = true;
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));

        s.translateScoreboardLine(SURFACE_TEXTS[0]);
        s.requestActionBarAsync(SURFACE_TEXTS[3], ignored -> { });
        s.translateBossBar(SURFACE_TEXTS[1]); // still the machine engine: manual
        pump(s);

        assertEquals(0, gtRequests.get());
        assertTrue(aiRequests.get() >= 1, "the AI-engine surfaces still send");
        assertEquals("AI譯文:" + SURFACE_TEXTS[0], s.translateScoreboardLine(SURFACE_TEXTS[0]).translated());
        assertFalse(s.translateBossBar(SURFACE_TEXTS[1]).changed());
    }

    // ---- cache hits keep displaying everywhere ----

    @Test
    void aCacheHitFromTheAiCacheTheRepositoryOrTheMachineCacheStillDisplays() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache gt = gtCache(cfg);
        TranslationCache ai = aiCache(cfg, Map.of(SURFACE_TEXTS[0], "AI:計分板"));
        assertEquals("AI:計分板", ai.translateBlocking(SURFACE_TEXTS[0]));
        assertEquals("機翻:" + SURFACE_TEXTS[1], gt.translateBlocking(SURFACE_TEXTS[1]));
        gtRequests.set(0);
        aiRequests.set(0);
        TranslationService s = service(cfg, gt, ai);
        s.setHubLookup(Map.of(SURFACE_TEXTS[2], "倉庫:標題")::get);

        assertEquals("AI:計分板", s.translateScoreboardLine(SURFACE_TEXTS[0]).translated());
        assertEquals("機翻:" + SURFACE_TEXTS[1], s.translateBossBar(SURFACE_TEXTS[1]).translated());
        assertEquals("倉庫:標題", s.translateTitle(SURFACE_TEXTS[2]).translated());
        assertFalse(s.translateActionBar(SURFACE_TEXTS[3]).changed(), "a miss stays original");
        pump(s);

        assertEquals(0, gtRequests.get());
        assertEquals(0, aiRequests.get());
    }

    // ---- P in the world: the HUD is collected and sent as one batch ----

    @Test
    void hudScanCollectsTheDrawnTextAndSendsOneBatchThenShowsTheTranslations() {
        TranslatorConfig cfg = machineConfig();
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));
        AtomicInteger reported = new AtomicInteger(-1);

        assertTrue(s.beginHudCapture(reported::set));
        assertTrue(s.isHudCaptureActive());
        assertFalse(s.beginHudCapture(ignored -> { }), "one window at a time");
        // The next render frames: the hooks look up what they draw (the same text every frame).
        for (int frame = 0; frame < 2; frame++) {
            s.translateScoreboardLine(SURFACE_TEXTS[0]);
            s.translateScoreboardLine("Bits Collected");
            s.translateBossBar(SURFACE_TEXTS[1]);
            s.translateTitle(SURFACE_TEXTS[2]);
            s.translateActionBar(SURFACE_TEXTS[3]);
            s.translateUi(SURFACE_TEXTS[4]);
            s.translateQuestHudText(SURFACE_TEXTS[6]);
            s.translateScoreboardLine("12345"); // nothing to translate: never collected
            s.translateBook("Chapter Two"); // not a HUD surface: not collected
            s.flushBatches();
        }
        assertEquals(0, gtRequests.get(), "nothing is sent while the window is open");
        pump(s);

        assertEquals(7, reported.get(), "seven distinct drawn texts were sent");
        assertFalse(s.isHudCaptureActive());
        assertEquals(1, gtBatches.get(), "one batch for the whole HUD");
        assertEquals(7, gtRequests.get());
        assertTrue(gtSent.contains(SURFACE_TEXTS[4]) && !gtSent.contains("Chapter Two"));
        assertEquals("機翻:" + SURFACE_TEXTS[1], s.translateBossBar(SURFACE_TEXTS[1]).translated());
        assertEquals("機翻:" + SURFACE_TEXTS[0], s.translateScoreboardLine(SURFACE_TEXTS[0]).translated());
        assertEquals("機翻:" + SURFACE_TEXTS[4], s.translateUi(SURFACE_TEXTS[4]).translated());
        assertEquals("機翻:" + SURFACE_TEXTS[6], s.translateQuestHudText(SURFACE_TEXTS[6]).translated());
        assertFalse(s.translateBook("Chapter Two").changed());
    }

    @Test
    void hudScanOnAnEmptyHudReportsZeroAndSendsNothing() {
        TranslatorConfig cfg = machineConfig();
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));
        AtomicInteger reported = new AtomicInteger(-1);

        assertTrue(s.beginHudCapture(reported::set));
        pump(s);

        assertEquals(0, reported.get());
        assertEquals(0, gtRequests.get());
    }

    @Test
    void hudScanRetranslatesAHudTextThatShowsAiWording() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache ai = aiCache(cfg, Map.of(SURFACE_TEXTS[0], "AI:計分板"));
        assertEquals("AI:計分板", ai.translateBlocking(SURFACE_TEXTS[0]));
        TranslationService s = service(cfg, gtCache(cfg), ai);
        assertEquals("AI:計分板", s.translateScoreboardLine(SURFACE_TEXTS[0]).translated());
        AtomicInteger reported = new AtomicInteger(-1);

        s.beginHudCapture(reported::set);
        s.translateScoreboardLine(SURFACE_TEXTS[0]);
        pump(s);

        assertEquals(1, reported.get());
        assertEquals(1, gtRequests.get(), "the displayed AI wording is redone with the machine engine");
        assertEquals("機翻:" + SURFACE_TEXTS[0], s.translateScoreboardLine(SURFACE_TEXTS[0]).translated());
        assertNull(ai.peekFinal(SURFACE_TEXTS[0], false), "the AI row of that text is voided");
    }

    @Test
    void hudScanWithOnlineTranslationOffSendsNothingAndKeepsWhatIsShown() {
        TranslatorConfig cfg = machineConfig();
        TranslationCache gt = gtCache(cfg);
        assertEquals("機翻:" + SURFACE_TEXTS[0], gt.translateBlocking(SURFACE_TEXTS[0]));
        gtRequests.set(0);
        TranslationService s = service(cfg, gt, aiCache(cfg, Map.of()));
        cfg.translationRequestsEnabled = false;
        AtomicInteger reported = new AtomicInteger(-1);

        s.beginHudCapture(reported::set);
        s.translateScoreboardLine(SURFACE_TEXTS[0]);
        s.translateBossBar(SURFACE_TEXTS[1]);
        pump(s);

        assertEquals(0, reported.get());
        assertEquals(0, gtRequests.get());
        assertEquals("機翻:" + SURFACE_TEXTS[0], s.translateScoreboardLine(SURFACE_TEXTS[0]).translated(),
                "nothing cached is discarded while requests are off");
    }

    @Test
    void hudScanWhileOnlineTranslationIsOffWaitsForTheConsentBox() {
        TranslatorConfig cfg = machineConfig();
        cfg.translationRequestsEnabled = false;
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));
        AtomicInteger saves = new AtomicInteger();
        ConsentGate gate = new ConsentGate(() -> cfg, saves::incrementAndGet);
        AtomicInteger reported = new AtomicInteger(-1);

        // P in the world: the glue parks the scan behind the consent box.
        boolean ranNow = gate.request(ConsentGate.Kind.HUD, () -> s.beginHudCapture(reported::set));
        assertFalse(ranNow, "online translation is off: the consent box comes first");
        assertTrue(gate.hasPending());
        assertEquals(ConsentGate.Kind.HUD, gate.pendingKind());
        assertFalse(s.isHudCaptureActive(), "nothing starts before the player agrees");

        gate.confirm(); // 開始翻譯
        assertTrue(cfg.translationRequestsEnabled);
        assertTrue(s.isHudCaptureActive(), "the scan runs once after the consent");
        s.translateBossBar(SURFACE_TEXTS[1]);
        pump(s);
        assertEquals(1, reported.get());
        assertEquals(1, gtRequests.get());
    }

    @Test
    void hudScanCancelledAtTheConsentBoxStartsNothing() {
        TranslatorConfig cfg = machineConfig();
        cfg.translationRequestsEnabled = false;
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));
        ConsentGate gate = new ConsentGate(() -> cfg, () -> { });

        gate.request(ConsentGate.Kind.HUD, () -> s.beginHudCapture(ignored -> { }));
        gate.cancel();

        assertFalse(s.isHudCaptureActive());
        assertFalse(cfg.translationRequestsEnabled);
    }

    @Test
    void hudScanIsAMachineActionOnlyWhenSomeHudSurfaceUsesTheMachineEngine() {
        TranslatorConfig cfg = machineConfig();
        TranslationService s = service(cfg, gtCache(cfg), aiCache(cfg, Map.of()));
        assertTrue(s.usesMachineEngineForHud());
        cfg.aiScoreboard = cfg.aiBossBar = cfg.aiTitle = cfg.aiActionBar = cfg.aiName = cfg.aiScreenText = true;
        assertFalse(s.usesMachineEngineForHud());
    }
}
