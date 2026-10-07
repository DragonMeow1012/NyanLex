package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the literal cache_request keys from the user's second diagnostic capture.
 * Rank values replaced by MT slots in the trace cannot be reconstructed from this file;
 * raw-rank and section-code cases below are explicitly synthetic variants. */
class LobbyNativeAnnouncementRegressionTest {
    // trace-20261006T132536174Z-b4026dd0-0001.jsonl: lines 674, 1009, 1478.
    private static final String ZQDRLA =
            "⟦CS0⟧⟦MT0⟧⟦/CS0⟧ ⟦CS1⟧zqdrla⟦/CS1⟧ ⟦CS2⟧飄入了大廳！⟦/CS2⟧";
    private static final String ABBKING =
            "⟦CS0⟧>⟦/CS0⟧⟦CS1⟧>⟦/CS1⟧⟦CS2⟧>⟦/CS2⟧ ⟦CS3⟧⟦MT0⟧⟦/CS3⟧ "
            + "⟦CS4⟧abbking⟦/CS4⟧ ⟦CS5⟧飄入了大廳！⟦/CS5⟧ "
            + "⟦CS6⟧<⟦/CS6⟧⟦CS7⟧<⟦/CS7⟧⟦CS8⟧<⟦/CS8⟧";
    private static final String SHEDOS =
            "⟦CS0⟧⟦MT0⟧⟦/CS0⟧ ⟦CS1⟧shedos⟦/CS1⟧ ⟦CS2⟧飄入了大廳！⟦/CS2⟧";

    static Stream<String> announcements() {
        return Stream.of(
                ZQDRLA, ABBKING, SHEDOS,
                "⟦CS0⟧zqdrla⟦/CS0⟧ ⟦CS1⟧飄入⟦/CS1⟧⟦CS2⟧了大廳！⟦/CS2⟧",
                "zqdrla 飄入了大廳！",
                "[MVP+] zqdrla 飄入了大廳！",
                ">>> [MVP++] abbking 飄入了大廳！ <<<",
                "⟦MT0⟧ shedos 飄入了大廳！",
                "§b[MVP§c+§b] §azqdrla §6飄入了大廳！",
                "[VIP] freshplayername 飄入了大廳！",
                "  [MVP+] zqdrla\t飄入了大廳!  ");
    }

    @ParameterizedTest(name = "native announcement: {0}")
    @MethodSource("announcements")
    void alreadyTraditionalAnnouncementNeedsNoTranslationBeforeFirstEcho(String source) {
        assertFalse(TextFilter.shouldTranslate(source, "zh-TW"), source);
    }

    @ParameterizedTest(name = "service must send zero: {0}")
    @MethodSource("announcements")
    void serviceCompletesNativeChatAndNeverQueuesItsWarmOrRenderVariants(String source) {
        Fixture f = new Fixture(true);
        List<TranslationService.ChatTranslationResult> callbacks = new ArrayList<>();
        List<List<String>> segmentCallbacks = new ArrayList<>();

        f.service.translateChatAsyncDetailed(source, callbacks::add);
        f.service.requestChatAsync(source, ignored -> fail("request-only callback is absent for native text"));
        f.service.translateChatSegmentsAsync(List.of(source), segmentCallbacks::add);
        f.service.translateChat(source);
        f.service.translateUi(source);
        f.service.translateScoreboardLine(source);
        f.service.warmNamesBatch(List.of(source));
        f.service.warmScoreboardBatch(List.of(source));
        f.service.flushBatches();

        // A later visible frame and warm pass must not create a cooldown retry either.
        f.now.addAndGet(60_000);
        f.service.translateChat(source);
        f.service.warmNamesBatch(List.of(source));
        f.service.flushBatches();

        assertAll(
                () -> assertEquals(List.of(), f.sent, "native lobby text must never enter a backend"),
                () -> assertEquals(1, callbacks.size(), "the display receives a completed result"),
                () -> assertNull(callbacks.getFirst().text(), "null keeps the original chat component"),
                () -> assertTrue(callbacks.getFirst().finalResult()),
                () -> assertEquals(List.of(List.of(source)), segmentCallbacks),
                () -> assertEquals(0, f.ai.pendingCount() + f.machine.pendingCount()),
                () -> assertFalse(f.service.translateChat(source).changed()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNewPlayerIdDoesNotBuyAnotherThreeEchoLearningCycle(boolean useAi) {
        Fixture f = new Fixture(useAi);
        List<TranslationService.ChatTranslationResult> callbacks = new ArrayList<>();
        for (String name : List.of("zqdrla", "abbking", "shedos", "freshplayername", "anothernewname")) {
            f.service.translateChatAsyncDetailed("[MVP+] " + name + " 飄入了大廳！", callbacks::add);
            f.service.flushBatches();
            f.now.addAndGet(60_000);
        }
        assertAll(
                () -> assertEquals(List.of(), f.sent, "IDs need no per-family learning for this exact native notice"),
                () -> assertEquals(5, callbacks.size()),
                () -> assertTrue(callbacks.stream().allMatch(r -> r.finalResult() && r.text() == null)),
                () -> assertEquals(0, f.ai.pendingCount() + f.machine.pendingCount()));
    }

    @Test
    void mixedChatSegmentsOnlySendTheActualEnglishQuestion() {
        Fixture f = new Fixture(true);
        f.service.setProtectedNames(() -> List.of("hypixel"));
        List<List<String>> completed = new ArrayList<>();
        List<String> source = List.of(ZQDRLA, "is hypixel down?", ABBKING, SHEDOS);
        f.service.translateChatSegmentsAsync(source, completed::add);
        f.service.flushBatches();
        assertAll(
                () -> assertEquals(List.of("is ⟦0⟧ down?"), f.sent),
                () -> assertEquals(1, completed.size()),
                () -> assertEquals(ZQDRLA, completed.getFirst().get(0)),
                () -> assertEquals(ABBKING, completed.getFirst().get(2)),
                () -> assertEquals(SHEDOS, completed.getFirst().get(3)),
                () -> assertEquals(0, f.ai.pendingCount() + f.machine.pendingCount()));
    }

    static Stream<String> stillTranslatable() {
        return Stream.of(
                "is ⟦0⟧ down?", "is hypixel down?",
                "[MVP+] zqdrla joined the lobby!",
                "[MVP+] zqdrla has joined the lobby!",
                "Bored while hypixel is offline? Visit ParkourHub for handmade parkour levels!",
                "Hello 世界 everyone here now",
                "Right click to open 設定 menu",
                "zqdrla 飄入了大廳！ server is down",
                "zqdrla: 飄入了大廳！",
                "zqdrla 飄入了大廳！ https://hypixel.net",
                "zqdrla 飄入了大廳！ 12345",
                "zqdrla 飄入了大廳！\nplease help",
                "zqdrla 飄入了大廳！ ⟦PB0⟧ please help",
                "zqdrla 飄入了大廳！ ⟦MT7⟧",
                "⟦WS0⟧ zqdrla 飄入了大廳！", // a column gap is not a rank slot
                "⟦MT\n0⟧ zqdrla 飄入了大廳！",
                "⟦MT\r0⟧ zqdrla 飄入了大廳！",
                "⟦CS\n0⟧zqdrla⟦/CS0⟧ 飄入了大廳！",
                "⟦CS0⟧zqdrla⟦/\rCS0⟧ 飄入了大廳！",
                "zqdrla\n飄入了大廳！",
                "zqdrla\r飄入了大廳！",
                ">>> zqdrla 飄入了大廳！", // incomplete decoration is outside the narrow frame
                "zqdrla 飄入了大廳！ <<<",
                "zqdrla 飘入了大厅！", // a Simplified notice still needs Traditional conversion
                "zqdrla 飄入了大厅！", // mixed script is not the proven native notice
                "fettywap", "Lv001 fettywap", "Lv100 Golden Dragon",
                "5. tobias49 [OREBLO] - 32,269",
                "10. XandaPandaMC [PANDA] - 31,160",
                "abcdefghijklmnopq 飄入了大廳！", // too long for a Minecraft account name
                "joinedlobby 飄入了大廳？"); // a question is not the exact server notice
    }

    @ParameterizedTest(name = "retain existing translation decision: {0}")
    @MethodSource("stillTranslatable")
    void englishChatIdsRankingsAndSimplifiedNoticesRemainTranslatable(String source) {
        assertTrue(TextFilter.shouldTranslate(source, "zh-TW"), source);
    }

    @ParameterizedTest(name = "backend still receives: {0}")
    @ValueSource(strings = {
            "is hypixel down?",
            "[MVP+] zqdrla joined the lobby!",
            "Hello 世界 everyone here now",
            "zqdrla 飄入了大廳！ server is down",
            "zqdrla 飘入了大厅！",
            "fettywap", "Lv001 fettywap", "Lv100 Golden Dragon",
            "5. tobias49 [OREBLO] - 32,269"
    })
    void serviceStillSendsOrdinaryEnglishNamesPetsAndRankings(String source) {
        Fixture f = new Fixture(true);
        f.service.translateChatAsyncDetailed(source, ignored -> { });
        f.service.flushBatches();
        assertFalse(f.sent.isEmpty(), "unchanged content must still enter the real cache/translator path: " + source);
        assertEquals(0, f.ai.pendingCount() + f.machine.pendingCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ja-JP", "ko-KR", "fr"})
    void nativeChineseIsStillContentWhenTheTargetIsAnotherLanguage(String target) {
        assertTrue(TextFilter.shouldTranslate(ZQDRLA, target));
        assertTrue(TextFilter.shouldTranslate(ABBKING, target));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ja_jp", "ko_kr"})
    void explicitJapaneseAndKoreanSourceHintsRetainTheirExistingException(String sourceHint) {
        assertTrue(TextFilter.shouldTranslate(ZQDRLA, "zh-TW", sourceHint));
        assertTrue(TextFilter.shouldTranslate(ABBKING, "zh-TW", sourceHint));
    }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong(1_000);
        final List<String> sent = new ArrayList<>();
        final TranslationCache machine;
        final TranslationCache ai;
        final TranslationService service;

        Fixture(boolean useAi) {
            TranslatorConfig cfg = TestConfigs.translating();
            cfg.targetLang = "zh-TW";
            cfg.chatMode = cfg.tooltipMode = cfg.scoreboardMode = cfg.nameMode = DisplayMode.TRANSLATION;
            cfg.aiChat = cfg.aiTooltip = cfg.aiScoreboard = cfg.aiName = useAi;
            // Echo is intentional: this verifies the actual number of backend entries,
            // without introducing success-validation/style expectations into filter tests.
            Translator fake = (text, target) -> {
                sent.add(text);
                return new TranslationResult(text, "en");
            };
            machine = new TranslationCache(fake, cfg.targetLang, Runnable::run, 100, 10_000L, now::get);
            ai = new TranslationCache(fake, cfg.targetLang, Runnable::run, 100, 10_000L, now::get);
            service = new TranslationService(cfg, machine, ai);
            service.setBatchWindowMs(() -> 0);
        }
    }
}
