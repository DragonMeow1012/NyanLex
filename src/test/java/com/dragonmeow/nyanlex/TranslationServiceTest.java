package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceTest {

    @Test
    void explicitScreenRescanInvalidatesOnlyCapturedRowsAndUsesScreenEngine() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.targetLang = "zh-TW";
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiScreenText = true;
        AtomicInteger calls = new AtomicInteger();
        TranslationCache machine = new TranslationCache(inlineTranslator(new AtomicInteger()), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(inlineTranslator(calls), cfg.targetLang, DIRECT, 100);
        machine.importTranslations(java.util.Map.of("Hello", "舊譯文", "Diamond Sword", "鑽石劍"));
        ai.importTranslations(java.util.Map.of("Hello", "舊譯文"));
        TranslationService service = new TranslationService(cfg, machine, ai);
        service.retranslateScreen(List.of("Hello"));
        pump(service);
        assertEquals(1, calls.get());
        assertEquals("你好", service.translateScreenText("Hello").translated());
        assertEquals("鑽石劍", machine.getCached("Diamond Sword"));
    }

    @Test
    void aFreshConfigSendsNothingFromAnySurface() {
        for (boolean ai : new boolean[] {false, true}) {
            TranslatorConfig cfg = new TranslatorConfig(); // fresh install: 送出翻譯請求 is OFF
            cfg.screenTextMode = DisplayMode.TRANSLATION; // every surface shows translations ...
            assertFalse(cfg.translationRequestsEnabled);
            cfg.aiChat = cfg.aiTooltip = cfg.aiScoreboard = cfg.aiName = cfg.aiBossBar = cfg.aiTitle
                    = cfg.aiActionBar = cfg.aiBook = cfg.aiScreenText = ai;
            AtomicInteger calls = new AtomicInteger();
            TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);
            List<String> texts = List.of("Hello", "Diamond Sword", "Welcome to the server");
            for (String t : texts) {
                s.translateChat(t);
                s.translateItemLine(t);
                s.translateHeld(t);
                s.translateScoreboardLine(t);
                s.translateUi(t);
                s.translateBossBar(t);
                s.translateTitle(t);
                s.translateActionBar(t);
                s.translateBook(t);
                s.translateScreenText(t);
                s.warmUp(t);
                s.requestChatAsync(t, r -> { });
                s.translateChatAsync(t, r -> { });
                s.requestActionBarAsync(t, r -> { });
                s.requestLiveScreenTextAsync(t, r -> { });
            }
            s.retranslate(texts);              // R
            s.retranslateScreen(texts);        // P
            s.warmTooltipBatch(texts);
            s.warmTooltipBatchBackground(texts); // the warm-up path
            s.requestItemLines(texts);
            s.warmBookBatch(texts);
            s.warmScoreboardBatch(texts);
            s.warmNamesBatch(texts);
            s.translateChatSegmentsAsync(texts, r -> { });
            pump(s);
            pump(s);
            assertEquals(0, calls.get(), "ai=" + ai + ": no text may reach a translation service while the master switch is off");
        }
    }

    @Test
    void manualRequestsAreSentOnlyAfterConsentAndExactlyOnce() {
        TranslatorConfig cfg = new TranslatorConfig(); // 線上翻譯 off
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);
        com.dragonmeow.nyanlex.config.ConsentGate gate =
                new com.dragonmeow.nyanlex.config.ConsentGate(() -> cfg, () -> { });
        List<String> sources = List.of("Diamond Sword");
        // R / P / warm-up asked while off: parked, nothing reaches the service
        gate.request(com.dragonmeow.nyanlex.config.ConsentGate.Kind.ITEM, () -> s.retranslate(sources));
        pump(s);
        assertEquals(0, calls.get());
        // 開始翻譯: the parked action completes once
        gate.confirm();
        pump(s);
        assertEquals(1, calls.get(), "the asked item request goes out exactly once");

        // 取消: nothing is ever sent
        TranslatorConfig cfg2 = new TranslatorConfig();
        AtomicInteger calls2 = new AtomicInteger();
        TranslationService s2 = service(cfg2, inlineTranslator(calls2), DIRECT);
        com.dragonmeow.nyanlex.config.ConsentGate gate2 =
                new com.dragonmeow.nyanlex.config.ConsentGate(() -> cfg2, () -> { });
        gate2.request(com.dragonmeow.nyanlex.config.ConsentGate.Kind.SCREEN, () -> s2.retranslateScreen(sources));
        gate2.cancel();
        pump(s2);
        assertEquals(0, calls2.get());
        assertFalse(cfg2.translationRequestsEnabled);
    }

    private static final Executor DIRECT = Runnable::run;

    /** Inline translator returning a fixed Chinese rendering for known inputs. */
    private static Translator inlineTranslator(AtomicInteger calls) {
        return (text, target) -> {
            calls.incrementAndGet();
            String out = switch (text) {
                case "Hello" -> "你好";
                case "Welcome to the server" -> "歡迎來到伺服器";
                case "Diamond Sword" -> "鑽石劍";
                default -> "[" + text + "]";
            };
            return new TranslationResult(out, "en");
        };
    }

    private static TranslationService service(TranslatorConfig cfg, Translator t, Executor exec) {
        TranslationCache cache = new TranslationCache(t, cfg.targetLang, exec, 100);
        TranslationCache aiCache = new TranslationCache(t, cfg.targetLang, exec, 100);
        return new TranslationService(cfg, cache, aiCache);
    }

    @Test
    void screenProseDropsEnglishLayoutGapsButScoreboardKeepsColumns() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.targetLang = "zh-TW";
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.aiScreenText = true;
        cfg.aiScoreboard = true;
        List<String> requests = new ArrayList<>();
        Translator translator = (text, target) -> {
            requests.add(text);
            if (text.contains("⟦WS0⟧")) {
                return new TranslationResult("你 ⟦WS0⟧ 知道 ⟦WS1⟧ 嗎？", "en");
            }
            return new TranslationResult("你      知道     嗎？", "en");
        };
        TranslationService service = service(cfg, translator, DIRECT);
        String source = "Did     you     know?";

        service.translateScreenText(source);
        pump(service);
        assertEquals("你 知道 嗎？", service.translateScreenText(source).translated());
        assertTrue(requests.contains("Did you know?"),
                "GUI prose must reach the translator as one semantic sentence");

        service.translateScoreboardLine(source);
        pump(service);
        assertEquals("你     知道     嗎？",
                service.translateScoreboardLine(source).translated(),
                "a real column surface still restores its exact horizontal gaps");
        assertTrue(requests.stream().anyMatch(request -> request.contains("⟦WS0⟧")),
                "scoreboard requests retain explicit layout tokens");
    }

    /** Simulate two client ticks: the coalescer holds one tick after growth, then sends. */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    @Test
    void chatTranslationModeReplacesText() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);

        s.translateChat("Hello"); // cache-or-warm: queues the miss
        pump(s);                  // per-tick batch goes out via the DIRECT executor
        TranslationDecision d = s.translateChat("Hello");
        assertTrue(d.changed());
        assertEquals(DisplayMode.TRANSLATION, d.mode());
        assertEquals("你好", d.translated());
        assertEquals("你好", d.renderPlain());
    }

    @Test
    void chatBothModeUsesBlockFormat() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.BOTH;
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);

        s.translateChat("Hello"); // queue
        pump(s);
        TranslationDecision d = s.translateChat("Hello");
        assertTrue(d.changed());
        assertEquals(DisplayMode.BOTH, d.mode());
        assertEquals("Hello", d.original());
        assertEquals("你好", d.translated());
        assertEquals("Hello\n你好", d.renderPlain());
    }

    @Test
    void chatOriginalOnlyDoesNotTranslate() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.ORIGINAL_ONLY;
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateChat("Hello").changed());
        assertFalse(s.wantsChatTranslation("Hello"));
        assertEquals(0, calls.get(), "ORIGINAL_ONLY must not call the translator");
    }

    @Test
    void targetLanguageChangeNotifiesVisibleSurfaceOnce() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);
        AtomicInteger changes = new AtomicInteger();
        s.setTargetLangChangeListener(changes::incrementAndGet);

        s.setTargetLang("ja-JP");
        assertEquals("ja-JP", s.targetLang());
        assertEquals(1, changes.get());

        s.setTargetLang("ja-JP");
        assertEquals(1, changes.get(), "selecting the same target must not reset the page again");
    }

    @Test
    void actionBarMissCompletesAsynchronouslyAndReusesNumberTemplate() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.actionBarMode = DisplayMode.TRANSLATION;
        cfg.aiActionBar = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("You received", "你獲得了")
                    .replace("coins!", "硬幣！"), "en");
        };
        TranslationService s = service(cfg, translator, DIRECT);
        List<String> results = new ArrayList<>();

        s.requestActionBarAsync("You received 10,518.2 coins!", results::add);
        pump(s);
        s.requestActionBarAsync("You received 26.2 coins!", results::add);

        assertEquals(List.of("你獲得了10,518.2硬幣！", "你獲得了26.2硬幣！"), results);
        assertEquals(1, calls.get());
    }

    @Test
    void perSurfaceModesAreIndependent() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.ORIGINAL_ONLY;   // chat off
        cfg.tooltipMode = DisplayMode.TRANSLATION;  // items on
        AtomicInteger calls = new AtomicInteger();
        // tooltip uses cache-or-warm; seed cache via a direct executor.
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateChat("Hello").changed());        // chat off
        s.translateItemLine("Diamond Sword");                   // queues the miss
        pump(s);
        assertTrue(s.translateItemLine("Diamond Sword").changed()); // items on
    }

    @Test
    void alreadyChineseIsLeftAlone() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateChat("你好世界").changed());
        assertEquals(0, calls.get(), "Chinese-to-Chinese must be filtered out before calling translator");
    }

    @Test
    void itemTooltipShowsOriginalFirstThenTranslationAfterCacheFills() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        // Manual executor: simulates the async background worker.
        Deque<Runnable> queue = new ArrayDeque<>();
        Executor manual = queue::add;
        TranslationService s = service(cfg, inlineTranslator(calls), manual);

        // First frame: cache miss -> unchanged, queued for the per-tick batch.
        assertFalse(s.translateItemLine("Diamond Sword").changed());
        assertEquals(0, queue.size(), "render path must not spawn work directly");

        // Two ticks later the coalesced batch is handed to the worker.
        pump(s);
        assertEquals(1, queue.size());
        queue.poll().run();

        // Next frame: cache hit -> translated.
        TranslationDecision d = s.translateItemLine("Diamond Sword");
        assertTrue(d.changed());
        assertEquals("鑽石劍", d.translated());
        assertEquals(1, calls.get(), "should translate exactly once");
    }

    @Test
    void japaneseItemLocaleDisambiguatesAllHanItemWithoutAffectingChat() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("鐵砧", "ja");
        };
        TranslationService s = service(cfg, translator, DIRECT);
        s.setItemSourceLanguage(() -> "ja_jp");

        s.warmNamesBatch(List.of("金床"));
        assertEquals(1, calls.get());
        assertTrue(s.translateItemLine("金床").changed());

        assertFalse(s.translateChat("金床").changed(),
                "the item locale hint must not force free-form Chinese chat through");
        assertEquals(1, calls.get());
    }

    @Test
    void renderPathNeverBlocks() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        AtomicInteger calls = new AtomicInteger();
        // Manual executor we deliberately never drain -> proves the render path never blocks.
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationService s = service(cfg, inlineTranslator(calls), queue::add);

        assertFalse(s.translateItemLine("Welcome to the server").changed());
        assertEquals(0, queue.size(), "render path only queues; the tick flush spawns the work");
        pump(s);
        assertEquals(1, queue.size(), "flush must coalesce the miss into one background task");
    }

    @Test
    void requestChatAsyncDeliversTranslation() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);
        List<String> got = new ArrayList<>();
        s.requestChatAsync("Hello", got::add);
        assertEquals(List.of(), got, "chat joins the per-tick batch; nothing sent yet");
        pump(s);
        assertEquals(List.of("你好"), got);
    }

    @Test
    void oneShotMarkedChatWaitsForExactStyleProjectionAfterPlainCacheHit() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        List<String> sent = new ArrayList<>();
        Translator translator = (text, target) -> {
            sent.add(text);
            return new TranslationResult(text
                    .replace("Hello", "你好")
                    .replace("World", "世界"), "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, cfg.targetLang, DIRECT, 100);
        assertEquals("你好 世界", cache.translateBlocking("Hello World"));
        sent.clear();
        TranslationService service = new TranslationService(cfg, cache, cache);
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        List<String> got = new ArrayList<>();

        service.translateChatAsync(marked, got::add);

        assertTrue(got.isEmpty(), "the plain semantic hit must not finish immutable chat");
        pump(service);
        assertEquals(List.of(marked), sent);
        assertEquals(List.of("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧"), got);
    }

    @Test
    void wantsChatTranslationRespectsTogglesAndFilter() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);
        assertTrue(s.wantsChatTranslation("Hello"));
        assertFalse(s.wantsChatTranslation("你好世界")); // already Chinese
        assertFalse(s.wantsChatTranslation("12345"));    // no letters

        cfg.chatMode = DisplayMode.ORIGINAL_ONLY; // chat off
        assertFalse(s.wantsChatTranslation("Hello"));
    }

    @Test
    void playerNamesNeverLeaveTheClientAndComeBackVerbatim() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        List<String> sent = new ArrayList<>();
        Translator t = (text, target) -> {
            sent.add(text);
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = service(cfg, t, DIRECT);
        s.setProtectedNames(() -> java.util.Set.of("Steve123"));

        List<String> got = new ArrayList<>();
        s.translateChatAsync("Steve123 sold Bloom Boat with Chest", got::add);
        pump(s);

        assertEquals(1, got.size());
        assertTrue(got.get(0) != null && got.get(0).contains("Steve123"),
                "the name must be restored verbatim: " + got.get(0));
        assertFalse(String.join(String.valueOf((char) 0), sent).contains("Steve123"),
                "the raw name must never be sent to the backend: " + sent);
        assertTrue(sent.get(0).contains("Bloom Boat with Chest"),
                "capitalized text not present in TAB must remain literal: " + sent);
    }

    @Test
    void surfacesShowingAPlayerNameAreLeftAlone() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);
        s.setProtectedNames(() -> java.util.Set.of("Steve123"));

        assertFalse(s.translateUi("Steve123").changed(), "a name tag that IS a player name stays original");
        pump(s);
        assertEquals(0, calls.get());
    }

    @Test
    void nameTagsTranslateEverythingButRestorePlayerNames() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiName = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        List<String> sent = new ArrayList<>();
        Translator t = (text, target) -> {
            sent.add(text);
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = service(cfg, t, DIRECT);
        s.setProtectedNames(() -> java.util.Set.of("Steve123"));

        // NPC / ground-item labels translate normally (1.0.0 behaviour)…
        s.translateUi("Lone Adventurer");
        pump(s);
        assertTrue(s.translateUi("Lone Adventurer").changed(), "NPC names must be translated");

        // …and a hologram CONTAINING a player name translates with the name restored.
        s.translateUi("Steve123 slain the dragon");
        pump(s);
        TranslationDecision d = s.translateUi("Steve123 slain the dragon");
        assertTrue(d.changed());
        assertTrue(d.translated().contains("Steve123"), "player name restored verbatim: " + d.translated());
        assertFalse(String.join(" ", sent).contains("Steve123"), "name never sent to the backend: " + sent);
    }

    @Test
    void cacheHitWithoutAProtectedNameDoesNotRescanProtectedNames() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiName = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        AtomicInteger nameSnapshots = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);
        s.setProtectedNames(() -> {
            nameSnapshots.incrementAndGet();
            return Set.of("Steve123");
        });
        s.translateUi("Lone Adventurer");
        pump(s);
        nameSnapshots.set(0);

        assertTrue(s.translateUi("Lone Adventurer").changed());
        assertEquals(1, nameSnapshots.get(),
                "a no-mask cache hit must use only the masking snapshot");
    }

    @Test
    void protectedNameVerificationReusesTheSuppliedSet() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiName = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        Set<String> containsOnly = new AbstractSet<>() {
            @Override
            public Iterator<String> iterator() {
                throw new AssertionError("the supplied Set must not be copied");
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public boolean contains(Object value) {
                return "Steve123".equals(value);
            }
        };
        Translator echo = (text, target) -> new TranslationResult("T:" + text, "en");
        TranslationService s = service(cfg, echo, DIRECT);
        s.setProtectedNames(() -> containsOnly);

        s.translateUi("Steve123 guards the village");
        pump(s);
        TranslationDecision decision = s.translateUi("Steve123 guards the village");

        assertTrue(decision.changed());
        assertTrue(decision.translated().contains("Steve123"));
    }

    @Test
    void translatedTooltipColumnGapCollapsesButScoreboardKeepsItsLayout() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        // Inline fake: translates the label into CJK and echoes every ⟦WS/MT⟧ token, so
        // the preserved padding comes back through Snapshot.restore untouched.
        Translator t = (text, target) -> new TranslationResult(
                text.replace("NPC Sell Price", "NPC 出售價格"), "en");
        TranslationService s = service(cfg, t, DIRECT);
        String line = "NPC Sell Price:     50,000";

        s.translateItemLine(line); // queue the miss
        pump(s);
        TranslationDecision tooltip = s.translateItemLine(line);
        assertTrue(tooltip.changed());
        assertEquals("NPC 出售價格：  50,000", tooltip.translated(),
                "the tooltip display collapses the translated column gap to two spaces "
                        + "(and, R3, writes the colon after Chinese text full-width)");

        s.translateScoreboardLine(line);
        pump(s);
        assertEquals("NPC 出售價格:     50,000",
                s.translateScoreboardLine(line).translated(),
                "scoreboard fixed HUD columns must stay untouched");
    }

    @Test
    void numericTooltipValuesAreNotTranslated() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateItemLine("64").changed());
        assertEquals(0, calls.get());
    }

    // ---- P1.4: calendar-date display localisation (cache keeps English, zh shows it localised) ----

    @Test
    void calendarDateSharesOneTranslatorCallAcrossDatesAndLocalizesOnlyForZhDisplay() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Obtained: ", "獲得："), "en");
        };
        TranslationService s = service(cfg, translator, DIRECT);

        s.translateItemLine("Obtained: November 15, 2026");   // queue the miss
        pump(s);
        assertEquals("獲得：2026年11月15日",
                s.translateItemLine("Obtained: November 15, 2026").translated(),
                "the cached English date is localized only at display time");

        // A different month/day/year shares the SAME cached template: zero extra requests.
        assertEquals("獲得：2027年1月3日",
                s.translateItemLine("Obtained: January 3, 2027").translated());
        assertEquals(1, calls.get(), "every date variant shares one cached translation");
    }

    @Test
    void nonChineseTargetNeverLocalizesTheCalendarDate() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.targetLang = "fr";
        Translator translator = (text, target) ->
                new TranslationResult(text.replace("Obtained:", "Obtenu :"), "en");
        TranslationService s = service(cfg, translator, DIRECT);

        s.translateItemLine("Obtained: November 15, 2026");
        pump(s);
        assertEquals("Obtenu : November 15, 2026",
                s.translateItemLine("Obtained: November 15, 2026").translated(),
                "a non-Chinese target must keep the English calendar date as-is");
    }

    @Test
    void untranslatedCalendarDateIsNeverLocalized() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        // First frame: cache miss -> the ORIGINAL English line is shown untouched, even
        // though it contains a date and the target is Chinese.
        TranslationDecision d = s.translateItemLine("Obtained: November 15, 2026");
        assertFalse(d.changed());
        assertEquals("Obtained: November 15, 2026", d.original());
    }

    @Test
    void warmNamesBatchSendsNoSurfaceContextButWarmTooltipBatchDoes() {
        List<List<String>> seenContexts = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("T:" + text, null);
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenContexts.add(surfaceContext == null ? null : new ArrayList<>(surfaceContext));
                List<TranslationResult> out = new ArrayList<>();
                for (String t : texts) out.add(new TranslationResult("T:" + t, null));
                return out;
            }
        };
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        TranslationService s = service(cfg, fake, DIRECT);

        // Unrelated container item names: NO tooltip surface context may reach the translator.
        s.warmNamesBatch(List.of("Diamond Sword", "Ender Pearl"));
        assertEquals(1, seenContexts.size(), "one batched request for the names");
        assertEquals(null, seenContexts.get(0),
                "warmNamesBatch must not attach any surface context");

        // Contrast: a real tooltip batch DOES carry its full line list as context.
        s.warmTooltipBatch(List.of("Iron Pickaxe", "Used in smelting"));
        assertEquals(2, seenContexts.size());
        assertEquals(List.of("Iron Pickaxe", "Used in smelting"), seenContexts.get(1),
                "warmTooltipBatch keeps sending the whole tooltip as context");
    }

    @Test
    void scoreboardRowsKeepStableKeysWhileWholeSidebarRemainsContext() {
        List<List<String>> seenBatches = new ArrayList<>();
        List<List<String>> seenContexts = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return translated(text);
            }

            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenBatches.add(new ArrayList<>(texts));
                seenContexts.add(surfaceContext == null ? null : new ArrayList<>(surfaceContext));
                return texts.stream().map(this::translated).toList();
            }

            private TranslationResult translated(String text) {
                return new TranslationResult(text.replace("Purse", "錢包")
                        .replace("Bits", "比特").replace("Gems", "寶石"), "en");
            }
        };
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        TranslationService service = service(cfg, fake, DIRECT);

        service.warmScoreboardBatch(List.of("Purse: 100", "Bits: 20"));
        assertEquals(1, seenBatches.size());
        assertEquals(2, seenBatches.get(0).size());
        assertEquals(2, seenContexts.get(0).size(),
                "both independent rows are still supplied as one sidebar context");

        service.warmScoreboardBatch(List.of("Gems: 3", "Purse: 999", "Bits: 21"));
        assertEquals(2, seenBatches.size());
        assertEquals(1, seenBatches.get(1).size(),
                "adding Gems must not re-request the cached Purse/Bits rows");
        assertTrue(seenBatches.get(1).get(0).contains("Gems"));
        assertEquals(3, seenContexts.get(1).size(),
                "the new full sidebar is still available to disambiguate the one miss");
        assertEquals("比特: 21", service.translateScoreboardLine("Bits: 21").translated());
    }

    @Test
    void halfTransliteratedWordIsNeitherCachedNorDisplayed() {
        // The backend (AI) returns the half-transliterated hybrid "傑cob" for "jacob".
        Translator poison = (text, target) -> {
            String out = switch (text) {
                case "jacob" -> "傑cob";        // "Ja" transliterated, "cob" left as English
                case "Diamond Sword" -> "鑽石劍"; // a clean translation on the same engine
                default -> "[" + text + "]";
            };
            return new TranslationResult(out, "en");
        };
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        TranslationService s = service(cfg, poison, DIRECT);

        // First frame queues the miss; the tick flush hands it to the backend.
        assertFalse(s.translateItemLine("jacob").changed());
        pump(s);
        // The hybrid is rejected: the surface still shows the ORIGINAL, and nothing usable was
        // cached — so it can never serve other surfaces via the AI→Google read-through fallback.
        assertFalse(s.translateItemLine("jacob").changed(),
                "half-transliterated 傑cob must be rejected, never displayed");

        // A clean translation on the very same engine is unaffected.
        s.translateItemLine("Diamond Sword");
        pump(s);
        TranslationDecision d = s.translateItemLine("Diamond Sword");
        assertTrue(d.changed());
        assertEquals("鑽石劍", d.translated());
    }

    @Test
    void churnGuardSuppressesFlashingDecorationOnceItChurns() {
        // A flashing scoreboard decoration: the word is stable but a cosmetic "!" run grows
        // every tick. The bangs are letter-free, so every variant shares signature "votenow"
        // while carrying a distinct request key — exactly the 429 request-storm pattern.
        // (★-style icon runs no longer churn at all: TemplateText slots them, so those
        // variants share ONE key — punctuation runs are what is left for the guard.)
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.churnGuard = true;
        cfg.churnVariantThreshold = 2; // trip on the 2nd distinct variant
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        s.translateScoreboardLine("Vote now !");   pump(s); // 1st variant: translated
        s.translateScoreboardLine("Vote now !!");  pump(s); // 2nd distinct variant: trips the guard
        s.translateScoreboardLine("Vote now !!!"); pump(s); // dropped (signature on cooldown)

        assertEquals(1, calls.get(),
                "once the decoration churns past the threshold, new variants must be dropped");
        assertFalse(s.translateScoreboardLine("Vote now !!!").changed(),
                "a churning variant stays untranslated (original shown)");
    }

    @Test
    void churnGuardDisabledTranslatesEveryVariant() {
        // config.churnGuard=false is the safety valve: if the detector ever misfires on a
        // real server, turning it off must restore translate-everything behaviour.
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.churnGuard = false;
        cfg.churnVariantThreshold = 2;
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        s.translateScoreboardLine("Vote now !");   pump(s);
        s.translateScoreboardLine("Vote now !!");  pump(s);
        s.translateScoreboardLine("Vote now !!!"); pump(s);

        assertEquals(3, calls.get(),
                "with the guard disabled every distinct variant is translated");
    }

    // ---- R8: warm/render key alignment (cached tooltips must hit on the FIRST lookup) ----

    @Test
    void warmedTooltipLineWithPlayerNameIsAFirstLookupHit() {
        // The warm path must park translations under the SAME (NameMasker-masked) key the
        // render lookup queries — otherwise a warmed line containing a player name misses
        // on its first frame, is bought a SECOND time, and flashes the original until the
        // extra round trip lands.
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Translator echo = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = service(cfg, echo, DIRECT);
        s.setProtectedNames(() -> List.of("Steve"));

        s.warmTooltipBatch(List.of("Sold by Steve for coins")); // DIRECT: completes inline
        assertEquals(1, calls.get(), "the warm buys the line once");

        TranslationDecision d = s.translateItemLine("Sold by Steve for coins");
        assertTrue(d.changed(), "the very FIRST render lookup must hit what the warm stored");
        assertTrue(d.translated().contains("Steve"), "the masked name comes back verbatim");
        assertFalse(d.translated().contains("⟦"), "no placeholder residue reaches the screen");
        assertEquals(1, calls.get(), "no second purchase of the same line");
    }

    @Test
    void wholeLinePlayerNameIsNeverBoughtByTheTooltipWarm() {
        // Preserved protection: a line that IS just a protected player name must neither
        // be bought by the warm (money) nor translated by the render (IDs stay verbatim).
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Translator echo = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = service(cfg, echo, DIRECT);
        s.setProtectedNames(() -> List.of("DragonMeow"));

        s.warmTooltipBatch(List.of("DragonMeow"));
        assertEquals(0, calls.get(), "a line that is ONLY a protected name is never bought");
        assertFalse(s.translateItemLine("DragonMeow").changed(), "the ID stays verbatim");
    }

    // ---- R10: hover-first — the hovered tooltip outruns background render misses ----

    @Test
    void hoveredTooltipDispatchesImmediatelyAheadOfBackgroundMisses() {
        // A background render surface queues its miss for the TICK coalescer (settle
        // window); the hovered tooltip's warm dispatches to the worker IMMEDIATELY. Even
        // when the background line was enqueued FIRST, the hover line's request is the
        // first to exist and complete.
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> workers = new ArrayDeque<>();
        TranslationService s = service(cfg, inlineTranslator(calls), workers::add);

        s.translateScoreboardLine("Background scoreboard line"); // queued for the coalescer
        assertEquals(0, workers.size(), "background misses wait for the settle window");

        s.warmTooltipBatch(List.of("Hovered tooltip line"));     // hover: fires NOW
        assertEquals(1, workers.size(), "the hover batch bypasses the settle window");
        workers.poll().run();
        assertEquals(1, calls.get());
        assertTrue(s.translateItemLine("Hovered tooltip line").changed(),
                "the hovered line is translated before the background line was even sent");

        pump(s);                                                  // settle window elapses
        assertEquals(1, workers.size(), "the background batch follows afterwards");
        workers.poll().run();
        assertTrue(s.translateScoreboardLine("Background scoreboard line").changed());
        assertEquals(2, calls.get());
    }

    @Test
    void repeatedHoverFramesDoNotEnqueueDuplicates() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> workers = new ArrayDeque<>();
        TranslationService s = service(cfg, inlineTranslator(calls), workers::add);

        s.warmTooltipBatch(List.of("Hovered tooltip line")); // frame 1
        s.warmTooltipBatch(List.of("Hovered tooltip line")); // frame 2, same hover
        assertEquals(1, workers.size(), "the in-flight guard must deduplicate hover frames");

        workers.poll().run();
        s.warmTooltipBatch(List.of("Hovered tooltip line")); // translated: nothing to enqueue
        assertEquals(0, workers.size(), "a cached line must not be re-enqueued");
        assertEquals(1, calls.get(), "one purchase in total");
    }

    // ---- R11: the retranslate hotkey must genuinely re-buy styled lines ----

    @Test
    void retranslateReallyRebuysAStyledLine() {
        // User bug: R felt like a no-op. The de-styled tier-2 copy survived invalidate,
        // kept serving the OLD value and made the re-warm skip the purchase entirely.
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Translator versioned = (text, target) ->
                new TranslationResult("T" + calls.incrementAndGet() + ":" + text, "en");
        TranslationService s = service(cfg, versioned, DIRECT);

        s.warmTooltipBatch(List.of("§eHello §aWorld"));
        assertEquals(1, calls.get());
        assertEquals("T1:Hello World", s.translateItemLine("§eHello §aWorld").translated());

        s.retranslate(List.of("§eHello §aWorld")); // DIRECT: invalidate + re-warm inline
        assertEquals(2, calls.get(), "the hotkey must actually re-buy the line");
        assertEquals("T2:Hello World", s.translateItemLine("§eHello §aWorld").translated(),
                "the NEW translation is served after the hotkey");
    }

    // ---- R13: PUA-icon lines must display AND stop the endless re-buys ----

    @Test
    void puaIconTitleTranslatesOnceAndDisplays() {
        // Hypixel bakes resource-pack icon glyphs (Unicode PRIVATE USE, e.g. U+E23A) into
        // item titles. The mojibake heuristic used to flag the RESTORED text (icon put
        // back), so the title never displayed its translation, never stored its raw alias,
        // and was re-bought on every encounter — while an icon-free line worked fine.
        String title = " Heroic Spirit Sceptre ✪✪✪✪✪";
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Translator echo = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Heroic Spirit Sceptre", "英靈權杖"), "en");
        };
        TranslationService s = service(cfg, echo, DIRECT);

        s.warmTooltipBatch(List.of(title));                    // hover: buy once
        assertEquals(1, calls.get());

        TranslationDecision d = s.translateItemLine(title);    // the very next render frame
        assertTrue(d.changed(), "the PUA-icon title must display its translation");
        assertTrue(d.translated().contains("英靈權杖"));
        assertTrue(d.translated().contains(""), "the icon is restored in place");
        assertTrue(d.translated().contains("✪✪✪✪✪"), "the stars are restored in place");

        s.warmTooltipBatch(List.of(title));                    // second encounter
        assertEquals(1, calls.get(), "cache hit: the endless re-buy loop is broken");
    }

    // ---- R17: TAB-listed player IDs override every translation channel ----

    @Test
    void mangledListedNameRevertsToOriginalAndSelfHealsOnce() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        // The backend EATS the name-mask token: unmask cannot restore the player name.
        Translator maskEater = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text.replace("⟦0⟧", "誰某"), "en");
        };
        TranslationService s = service(cfg, maskEater, DIRECT);
        s.setProtectedNames(() -> List.of("Aand_"));

        s.translateItemLine("Aand_ sells melons");
        pump(s);                                              // buy #1 (name gets mangled)
        assertEquals(1, calls.get());

        assertFalse(s.translateItemLine("Aand_ sells melons").changed(),
                "a translation that lost the player ID must never display");

        // The gate invalidated the entry ONCE: the next encounter re-buys through the
        // masked pipeline (still poisoned here)…
        s.translateItemLine("Aand_ sells melons");
        pump(s);
        assertEquals(2, calls.get(), "one self-heal re-buy after the eviction");
        assertFalse(s.translateItemLine("Aand_ sells melons").changed());

        // …and the debounce stops any further eviction/re-buy storm.
        s.translateItemLine("Aand_ sells melons");
        pump(s);
        assertEquals(2, calls.get(), "debounced: no per-frame invalidate storm");
    }

    @Test
    void listedNameKeptVerbatimStillDisplays() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        Translator good = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en"); // mask token survives
        };
        TranslationService s = service(cfg, good, DIRECT);
        s.setProtectedNames(() -> List.of("Aand_"));

        s.translateItemLine("Aand_ sells melons");
        pump(s);
        TranslationDecision d = s.translateItemLine("Aand_ sells melons");
        assertTrue(d.changed(), "a translation that KEEPS the player ID displays normally");
        assertTrue(d.translated().contains("Aand_"), "the ID is verbatim in the output");
        assertEquals(1, calls.get());
    }

    @Test
    void inconsistentHeldItemNameIsDeterministicallyDerivedFromTooltipTitle() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        AtomicInteger contextualNameCalls = new AtomicInteger();
        Translator contextAware = new Translator() {
            @Override
            public TranslationResult translate(String text, String target) {
                return new TranslationResult("T:" + text, "en");
            }

            @Override
            public List<TranslationResult> translateBatch(List<String> texts, String target,
                                                           List<String> context) {
                boolean hasAbilityContext = context != null && context.stream()
                        .anyMatch(line -> line.contains("Instant Transmission"));
                List<TranslationResult> out = new ArrayList<>();
                for (String text : texts) {
                    String translated;
                    if (text.equals("Aspect of the End")) {
                        if (hasAbilityContext) contextualNameCalls.incrementAndGet();
                        translated = hasAbilityContext ? "終界之刃" : "末影之視";
                    } else if (text.contains("Aspect of the End") && text.contains("Damage:")) {
                        translated = text.replace("Aspect of the End Damage:", "終界之刃 傷害：");
                        translated = translated.replace("Aspect of the End", "終界之刃")
                                .replace("Damage:", "傷害：");
                    } else if (text.startsWith("Damage:")) {
                        translated = text.replace("Damage:", "傷害：");
                    } else {
                        translated = "譯：" + text;
                    }
                    out.add(new TranslationResult(translated, "en"));
                }
                return out;
            }
        };
        TranslationService s = service(cfg, contextAware, DIRECT);

        s.warmNamesBatch(List.of("Aspect of the End"));
        pump(s);
        assertEquals("末影之視", s.translateHeld("Aspect of the End").translated());

        List<String> tooltip = List.of(
                "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧Damage: +100⟦/CS1⟧",
                "Ability: Instant Transmission RIGHT CLICK");
        s.warmTooltipBatch(tooltip);
        pump(s);
        s.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
        pump(s); // translates the reusable "Damage: [n]" suffix
        s.reconcileItemNameWithTooltip("Aspect of the End", tooltip); // extracts + stores prefix

        assertEquals("終界之刃", s.translateHeld("Aspect of the End").translated());
        assertEquals(0, contextualNameCalls.get(),
                "deterministic prefix extraction must not re-ask AI for the item name");
        s.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
        pump(s);
        assertEquals(0, contextualNameCalls.get(), "matching names must not be re-bought per frame");
    }

    @Test
    void aiAndGtSurfaceSettingsUseDifferentEnginePaths() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        AtomicInteger aiCalls = new AtomicInteger();
        AtomicInteger gtCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            return new TranslationResult("機器譯文", "en");
        }, cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult("人工譯文", "en");
        }, cfg.targetLang, DIRECT, 100);
        TranslationService service = new TranslationService(cfg, gt, ai);

        service.translateChat("Hello world");
        pump(service);
        assertEquals("人工譯文", service.translateChat("Hello world").translated());
        assertEquals(1, aiCalls.get());
        assertEquals(0, gtCalls.get(), "healthy AI mode must never buy GT");

        cfg.aiChat = false;
        service.translateChat("Welcome to the server");
        pump(service);
        assertEquals("機器譯文", service.translateChat("Welcome to the server").translated());
        assertEquals(1, aiCalls.get(), "GT mode must never consult AI");
        assertEquals(1, gtCalls.get());
    }

    @Test
    void existingGtCacheStaysHiddenUntilAiActuallyFailsThenAiRecoveryWins() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.disableGoogleFallbackForAi = false; // this test exercises the GT-fallback path
        long[] now = {0L};
        boolean[] aiUp = {false};
        AtomicInteger aiCalls = new AtomicInteger();
        AtomicInteger gtCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            return new TranslationResult("機器候補", "en");
        }, cfg.targetLang, DIRECT, 100);
        assertEquals("機器候補", gt.translateBlocking("Hello world"));
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            if (!aiUp[0]) throw new TranslationException("AI offline");
            return new TranslationResult("人工精翻", "en");
        }, cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        ai.setProvisionalRetryGate(() -> true);
        TranslationService service = new TranslationService(cfg, gt, ai);

        assertFalse(service.translateChat("Hello world").changed(),
                "pre-cached GT must stay hidden before the first AI attempt");
        assertFalse(service.translateChat("Hello world").changed());
        pump(service);
        assertEquals(1, aiCalls.get());
        assertEquals("機器候補", service.translateChat("Hello world").translated(),
                "GT becomes displayable only after AI records failure");
        assertEquals(1, gtCalls.get(), "the existing GT row is reused without another request");

        aiUp[0] = true;
        now[0] = 1_000L;
        pump(service);
        assertEquals("人工精翻", service.translateChat("Hello world").translated(),
                "AI is always the final display priority after recovery");
        assertEquals(1, gtCalls.get(), "AI recovery must not rebuy GT");
    }

    @Test
    void strictAiModeNeverFallsBackToGtAndRecoversOnAi() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.disableGoogleFallbackForAi = true;
        long[] now = {0L};
        boolean[] aiUp = {false};
        AtomicInteger aiCalls = new AtomicInteger();
        AtomicInteger gtCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            return new TranslationResult("GT result", "en");
        }, cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            if (!aiUp[0]) throw new TranslationException("AI offline");
            return new TranslationResult("AI result", "en");
        }, cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        ai.setProvisionalRetryGate(() -> true);
        TranslationService service = new TranslationService(cfg, gt, ai);

        service.translateChat("Hello world");
        pump(service);
        assertEquals(1, aiCalls.get());
        assertEquals(0, gtCalls.get(), "strict AI mode must not start GT after AI failure");
        assertFalse(service.translateChat("Hello world").changed());

        aiUp[0] = true;
        now[0] = 1_000L;
        pump(service);
        assertEquals("AI result", service.translateChat("Hello world").translated());
        assertEquals(0, gtCalls.get());
    }

    @Test
    void gtModeFailureNeverStartsAi() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = false;
        AtomicInteger aiCalls = new AtomicInteger();
        AtomicInteger gtCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            throw new TranslationException("GT offline");
        }, cfg.targetLang, DIRECT, 100, 10_000L, () -> 0L);
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult("不應呼叫", "en");
        }, cfg.targetLang, DIRECT, 100);
        TranslationService service = new TranslationService(cfg, gt, ai);

        service.translateChat("Hello world");
        pump(service);
        assertEquals(1, gtCalls.get());
        assertEquals(0, aiCalls.get(), "GT mode owns its own retries and never falls upward to AI");
    }

    @Test
    void afterBothFailGtMayDisplayFirstButAiStillBecomesFinal() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.disableGoogleFallbackForAi = false; // this test exercises the GT-fallback path
        long[] now = {0L};
        boolean[] aiUp = {false};
        boolean[] gtUp = {false};
        TranslationCache gt = new TranslationCache((text, target) -> {
            if (!gtUp[0]) throw new TranslationException("GT offline");
            return new TranslationResult("機器先到", "en");
        }, cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        TranslationCache ai = new TranslationCache((text, target) -> {
            if (!aiUp[0]) throw new TranslationException("AI offline");
            return new TranslationResult("人工最終", "en");
        }, cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        ai.setProvisionalRetryGate(() -> true);
        TranslationService service = new TranslationService(cfg, gt, ai);

        service.translateChat("Hello world");
        pump(service); // AI attempt fails and starts GT
        pump(service); // GT attempt also fails

        gtUp[0] = true;
        now[0] = 1_000L;
        pump(service);
        assertEquals("機器先到", service.translateChat("Hello world").translated());

        aiUp[0] = true;
        now[0] = 3_000L;
        pump(service);
        assertEquals("人工最終", service.translateChat("Hello world").translated(),
                "a later AI retry overrides GT; GT never overrides a landed AI final");
    }

    @Test
    void exactStyleChatCallbackUpgradesGtToRecoveredAiSpanResult() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.disableGoogleFallbackForAi = false; // this test exercises the GT-fallback path
        long[] now = {0L};
        boolean[] aiUp = {false};
        String source = "⟦CS0⟧sold⟦/CS0⟧ ⟦CS1⟧White Gift Talisman⟦/CS1⟧";
        TranslationCache gt = new TranslationCache((text, target) -> new TranslationResult(
                "⟦CS1⟧白色禮物護符⟦/CS1⟧，⟦CS0⟧機翻出售⟦/CS0⟧", "en"),
                cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        TranslationCache ai = new TranslationCache((text, target) -> {
            if (!aiUp[0]) throw new TranslationException("AI offline");
            return new TranslationResult(
                    "⟦CS1⟧白色禮物護符⟦/CS1⟧，⟦CS0⟧精翻售出⟦/CS0⟧", "en");
        }, cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        ai.setProvisionalRetryGate(() -> true);
        TranslationService service = new TranslationService(cfg, gt, ai);
        List<String> delivered = new ArrayList<>();

        service.translateChatAsync(source, delivered::add);
        pump(service); // AI fails, GT is queued
        pump(service); // GT exact spans display first
        assertEquals(List.of("⟦CS1⟧白色禮物護符⟦/CS1⟧，⟦CS0⟧機翻出售⟦/CS0⟧"), delivered);

        aiUp[0] = true;
        now[0] = 1_000L;
        pump(service);
        assertEquals(List.of(
                "⟦CS1⟧白色禮物護符⟦/CS1⟧，⟦CS0⟧機翻出售⟦/CS0⟧",
                "⟦CS1⟧白色禮物護符⟦/CS1⟧，⟦CS0⟧精翻售出⟦/CS0⟧"), delivered,
                "the second callback lets the loader replace the same displayed chat row");
    }

    // ---- chat back-fill: style debt stays passive until the text is seen again ----

    /** Fake AI: eats the CS markers on the first marked round, restores them on the
     *  second; plain semantic keys always translate cleanly. */
    private static Translator markerEatingThenHealingAi(AtomicInteger markedRound) {
        return (text, target) -> {
            if (text.contains("⟦CS")) {
                return markedRound.incrementAndGet() == 1
                        ? new TranslationResult("你好 世界", "en")
                        : new TranslationResult("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", "en");
            }
            return new TranslationResult("你好 世界", "en");
        };
    }

    @Test
    void exactStyleChatDoesNotRetryInBackgroundAndHealsWhenSeenAgain() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        long[] now = {0L};
        AtomicInteger gtCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            throw new TranslationException("GT must not be consulted");
        }, cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        AtomicInteger markedRound = new AtomicInteger();
        TranslationCache ai = new TranslationCache(markerEatingThenHealingAi(markedRound),
                cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        TranslationService s = new TranslationService(cfg, gt, ai);
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        List<String> delivered = new ArrayList<>();

        s.translateChat("Hello World");   // seed the final plain semantic row
        pump(s);
        s.translateChatAsync(marked, t -> {
            if (t != null) delivered.add(t);
        });
        pump(s);                          // marked round loses its markers -> style debt

        assertEquals(1, delivered.size(), "the semantic wording must ship immediately");
        assertTrue(TextFilter.isStyleFallback(delivered.get(0)),
                "the first delivery is explicitly marked as a style fallback");
        assertEquals("你好 世界", TextFilter.stripStyleFallback(delivered.get(0)));

        now[0] = 10_000L;
        pump(s);
        assertEquals(1, markedRound.get(),
                "an expired colour debt must not spend another request in the background");
        assertEquals(1, delivered.size(), "the semantic fallback remains the final shown row");

        s.translateChatAsync(marked, t -> {             // a new occurrence may retry once
            if (t != null) delivered.add(t);
        });
        pump(s);
        assertEquals(2, markedRound.get());
        assertEquals(3, delivered.size(),
                "the healed projection updates both the older waiter and the new chat row");
        assertEquals("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", delivered.get(1));
        assertEquals(delivered.get(1), delivered.get(2));
        assertFalse(TextFilter.isStyleFallback(delivered.get(1)));
        assertEquals(0, gtCalls.get(), "a style-only projection debt must never buy GT");
    }

    @Test
    void styleFallbackPrefixSurvivesOuterWhitespaceRestoration() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        long[] now = {0L};
        TranslationCache gt = new TranslationCache((text, target) -> {
            throw new TranslationException("GT must not be consulted");
        }, cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        TranslationCache ai = new TranslationCache(markerEatingThenHealingAi(new AtomicInteger()),
                cfg.targetLang, DIRECT, 100, 1_000L, () -> now[0]);
        TranslationService s = new TranslationService(cfg, gt, ai);
        String content = " ⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧ ";
        List<String> delivered = new ArrayList<>();

        s.translateChat("Hello World");
        pump(s);
        s.translateChatAsync(content, t -> {
            if (t != null) delivered.add(t);
        });
        pump(s);

        assertEquals(1, delivered.size());
        assertTrue(TextFilter.isStyleFallback(delivered.get(0)),
                "outer whitespace must be restored INSIDE the fallback prefix, not before it");
        assertEquals(" 你好 世界 ", TextFilter.stripStyleFallback(delivered.get(0)));
    }

    @Test
    void screenTextMissStillDeliversExactlyOnce() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScreenText = true;
        AtomicInteger aiCalls = new AtomicInteger();
        AtomicInteger gtCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            return new TranslationResult("機器譯文", "en");
        }, cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult("歡迎來到伺服器", "en");
        }, cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, gt, ai);
        List<String> results = new ArrayList<>();

        s.requestScreenTextAsync("Welcome to the server", results::add);
        pump(s);

        assertEquals(List.of("歡迎來到伺服器"), results,
                "a healthy screen-text miss still resolves with exactly one callback");
        assertEquals(1, aiCalls.get());
        assertEquals(0, gtCalls.get(), "healthy AI screen text must never buy GT");
    }

    @Test
    void gtOnlyScreenTextPathIsUnchanged() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScreenText = false;
        AtomicInteger aiCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) ->
                new TranslationResult("機器譯文", "en"), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult("不應呼叫", "en");
        }, cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, gt, ai);
        List<String> results = new ArrayList<>();

        s.requestScreenTextAsync("Welcome to the server", results::add);
        pump(s);

        assertEquals(List.of("機器譯文"), results);
        assertEquals(0, aiCalls.get(), "GT mode must never consult AI");
    }
}
