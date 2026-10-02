package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.ChatRequestProfile;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher;
import com.dragonmeow.nyanlex.translate.NameMasker;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不翻譯詞彙 {@code doNotTranslateTerms}: terms are masked before translation (so the
 * cache key changes with the list), matched case-insensitively as whole words, and
 * restored in the spelling the text actually used. Every collaborator is an inline fake.
 */
class DoNotTranslateTermsTest {

    private static final Executor DIRECT = Runnable::run;

    /** Inline translator: records every request text and answers with {@code rule}. */
    private static Translator recording(List<String> sent, UnaryOperator<String> rule) {
        return (text, target) -> {
            sent.add(text);
            return new TranslationResult(rule.apply(text), "en");
        };
    }

    private static TranslationService service(TranslatorConfig cfg, Translator translator) {
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        return new TranslationService(cfg, gt, ai);
    }

    /** Two client ticks: the collector holds one tick after growth, then sends. */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    private static DoNotTranslateMatcher terms(String... terms) {
        return DoNotTranslateMatcher.compile(Arrays.asList(terms));
    }

    private static NameMasker.Masked mask(String text, DoNotTranslateMatcher terms) {
        return NameMasker.mask(text, List.of(), terms);
    }

    @Test
    void termIsMaskedBeforeSendingAndShownInTheOriginalSpelling() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("skyblock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg,
                recording(sent, text -> text.replace("Welcome to ", "歡迎來到 ")));

        assertFalse(s.translateChat("Welcome to SkyBlock!").changed());
        pump(s);

        assertEquals(List.of("Welcome to ⟦0⟧!"), sent, "the term never leaves the client");
        assertEquals("歡迎來到 SkyBlock!", s.translateChat("Welcome to SkyBlock!").translated());
        List<String> chat = new ArrayList<>();
        s.translateChatAsync("Welcome to SkyBlock!", chat::add);
        assertEquals(List.of("歡迎來到 SkyBlock!"), chat);
        assertEquals(1, sent.size(), "one masked key serves render and chat alike");
    }

    @Test
    void everyOriginalSpellingOwnsItsPlaceholder() {
        NameMasker.Masked m = mask("SKYBLOCK beats Skyblock and SKYBLOCK", terms("SkyBlock"));
        assertEquals("⟦0⟧ beats ⟦1⟧ and ⟦0⟧", m.text());
        assertEquals(List.of("SKYBLOCK", "Skyblock"), m.names());
        assertEquals("SKYBLOCK 勝過 Skyblock 和 SKYBLOCK",
                NameMasker.unmask("⟦0⟧ 勝過 ⟦1⟧ 和 ⟦0⟧", m.names()));

        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> text.replace(" or ", " 或 ")));
        s.translateScoreboardLine("SKYBLOCK or Skyblock");
        pump(s);
        assertEquals(List.of("⟦0⟧ or ⟦1⟧"), sent);
        assertEquals("SKYBLOCK 或 Skyblock", s.translateScoreboardLine("SKYBLOCK or Skyblock").translated());
    }

    @Test
    void onlyWholeWordsAreMasked() {
        DoNotTranslateMatcher sky = terms("skyblock");
        for (String text : List.of("Skyfoo rocks", "two skyblocks", "skyblock_2",
                "SKYBLOCK2", "megaskyblock")) {
            assertFalse(mask(text, sky).hasMasks(), text);
        }
        assertEquals("(⟦0⟧) - ⟦0⟧'s", mask("(SkyBlock) - SkyBlock's", sky).text());
        assertEquals("在⟦0⟧玩", mask("在SkyBlock玩", sky).text(),
                "CJK neighbours are word boundaries");
    }

    @Test
    void multiWordTermsSpanAnyHorizontalSpaceAndTheLongestMatchWins() {
        NameMasker.Masked hub = mask("Go to Dungeon   Hub now, not the Hub",
                terms("hub", "Dungeon Hub"));
        assertEquals("Go to ⟦0⟧ now, not the ⟦1⟧", hub.text());
        assertEquals(List.of("Dungeon   Hub", "Hub"), hub.names(),
                "the exact original spacing is restored");

        NameMasker.Masked overlap = mask("Dwarven Mines of Moria",
                terms("Dwarven Mines", "Mines of Moria"));
        assertEquals("Dwarven ⟦0⟧", overlap.text(), "the longer overlapping term wins");
        assertEquals(List.of("Mines of Moria"), overlap.names());

        assertFalse(mask("Dungeon\nHub", terms("Dungeon Hub")).hasMasks(),
                "a hard line break is not horizontal space");
    }

    @Test
    void literalFormatCodesAreWordBoundaries() {
        assertEquals("§e⟦0⟧ §7Level", mask("§eSkyBlock §7Level", terms("skyblock")).text());
        assertEquals("§e§l⟦0⟧", mask("§e§lSKYBLOCK", terms("skyblock")).text());
    }

    @Test
    void urlAndDomainFragmentsAreNeverMasked() {
        DoNotTranslateMatcher hypixel = terms("hypixel");
        assertFalse(mask("Visit hypixel.net or mc.hypixel.net", hypixel).hasMasks());
        assertFalse(mask("See https://hypixel.net/forums", hypixel).hasMasks());
        assertEquals("Welcome to ⟦0⟧! Visit hypixel.net",
                mask("Welcome to Hypixel! Visit hypixel.net", hypixel).text());
    }

    @Test
    void protocolTokensAreNeverMaskedInto() {
        NameMasker.Masked m = mask("⟦CS0⟧Hello cs0⟦/CS0⟧ ⟦MT0⟧", terms("CS0", "MT0"));
        assertEquals("⟦CS0⟧Hello ⟦0⟧⟦/CS0⟧ ⟦MT0⟧", m.text(), "only the visible word is masked");
        assertFalse(NameMasker.mask("⟦CS0⟧Hi⟦/CS0⟧", Set.of("CS0")).hasMasks(),
                "a player literally named CS0 cannot corrupt a colour marker");
    }

    @Test
    void lineMadeOnlyOfTermsIsNeverSentOnAnySurface() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> "T:" + text));

        for (String line : List.of("SKYBLOCK", "⟦CS0⟧SKYBLOCK⟦/CS0⟧", "§e§lSKYBLOCK",
                "⟦CS0⟧SkyBlock⟦/CS0⟧ ⟦CS1⟧2026⟦/CS1⟧", "SKYBLOCK ⟦PB0⟧ SKYBLOCK",
                "SKYBLOCK ⟦PB0⟧ SKYBLOCK ⟦PB1⟧ SKYBLOCK",
                "⟦CS0⟧SKYBLOCK⟦/CS0⟧ ⟦PB0⟧ ⟦CS1⟧2026⟦/CS1⟧")) {
            assertFalse(s.translateScoreboardLine(line).changed(), line);
            assertFalse(s.translateItemLine(line).changed(), line);
            assertTrue(s.isTooltipTranslationReady(line), line);
            List<String> chat = new ArrayList<>();
            s.translateChatAsync(line, chat::add);
            assertEquals(Arrays.asList((String) null), chat, line);
            List<String> async = new ArrayList<>();
            s.requestActionBarAsync(line, async::add);
            s.requestLiveScreenTextAsync(line, async::add);
            s.warmTooltipBatch(List.of(line));
            s.warmScoreboardBatch(List.of(line));
            pump(s);
            assertTrue(async.isEmpty(), line);
        }
        assertEquals(List.of(), sent, "no request for a line made only of protected terms");
    }

    @Test
    void termPlusAShortWordIsStillTranslated() {
        // "⟦0⟧ XP" must not read as the machine code "0XP" (TextFilter judges both the
        // service short-circuit and the cache's translatable-content check).
        assertTrue(com.dragonmeow.nyanlex.translate.TextFilter.shouldTranslate("⟦0⟧ XP", "zh-TW"));
        assertTrue(com.dragonmeow.nyanlex.translate.TextFilter.shouldTranslate("Hi ⟦0⟧", "zh-TW"));
        assertFalse(com.dragonmeow.nyanlex.translate.TextFilter.shouldTranslate("⟦0⟧", "zh-TW"));
        assertFalse(com.dragonmeow.nyanlex.translate.TextFilter.shouldTranslate(
                "⟦0⟧ ⟦PB0⟧ ⟦0⟧ ⟦PB1⟧ ⟦1⟧", "zh-TW"), "placeholders and breaks are not wording");
        assertFalse(com.dragonmeow.nyanlex.translate.TextFilter.shouldTranslate(
                "⟦0⟧ n6400", "zh-TW"), "a real short machine code stays untranslated");

        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> text
                .replace("XP", "經驗").replace("Hub", "大廳").replace("Go", "前往")
                .replace("Hi", "嗨")));
        s.setProtectedNames(() -> Set.of("Steve"));
        List<String> lines = List.of("SkyBlock XP", "SkyBlock Hub", "SkyBlock XP: 1,250",
                "Go SkyBlock", "Hi Steve");

        for (String line : lines) s.translateScoreboardLine(line);
        pump(s);

        assertEquals(List.of("⟦0⟧ XP", "⟦0⟧ Hub", "⟦0⟧ XP: ⟦MT0⟧", "Go ⟦0⟧", "Hi ⟦0⟧"), sent);
        assertEquals("SkyBlock 經驗", s.translateScoreboardLine("SkyBlock XP").translated());
        assertEquals("SkyBlock 大廳", s.translateScoreboardLine("SkyBlock Hub").translated());
        assertEquals("SkyBlock 經驗: 1,250", s.translateScoreboardLine("SkyBlock XP: 1,250").translated());
        assertEquals("前往 SkyBlock", s.translateScoreboardLine("Go SkyBlock").translated());
        assertEquals("嗨 Steve", s.translateScoreboardLine("Hi Steve").translated(),
                "the same fix lets a greeting to a player translate");
    }

    @Test
    void unmaskedListedPlayerIdStillGuardsTheDisplay() {
        // "Steve說…" keeps Steve inside one Unicode word, so only Alex is masked; the
        // pre-1.0.7 R17 scan must still refuse a translation that rewrote Steve.
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> "史蒂夫說你好，⟦0⟧"));
        s.setProtectedNames(() -> Set.of("Steve", "Alex"));

        s.translateChat("Steve說hello, Alex");
        pump(s);

        assertEquals(List.of("Steve說hello, ⟦0⟧"), sent);
        assertFalse(s.translateChat("Steve說hello, Alex").changed(),
                "a translation that lost a listed player ID must never display");
    }

    @Test
    void termWithNonAsciiEdgesNeedsNoWordBoundary() {
        assertEquals("前往⟦0⟧嶼", mask("前往天空島嶼", terms("天空島")).text());
        assertEquals("ようこそ⟦0⟧へ", mask("ようこそスカイブロックへ", terms("スカイブロック")).text());
    }

    @Test
    void termContainingAPlayerNameWinsAsTheLongerSpan() {
        NameMasker.Masked m = NameMasker.mask("Visit Steve's Island today, Steve",
                Set.of("Steve"), terms("Steve's Island"));
        assertEquals("Visit ⟦0⟧ today, ⟦1⟧", m.text());
        assertEquals(List.of("Steve's Island", "Steve"), m.names());
    }

    @Test
    void entriesThatAreNotUsedByGlueAreMaskedToo() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> "T:" + text));
        List<List<String>> segments = new ArrayList<>();
        List<String> chat = new ArrayList<>();
        List<String> scan = new ArrayList<>();

        s.translateChatSegmentsAsync(List.of("Welcome to SkyBlock", "SKYBLOCK"), segments::add);
        s.requestChatAsync("Play SkyBlock now", chat::add);
        s.requestScreenTextAsync("Open the SkyBlock menu", scan::add);
        pump(s);
        assertTrue(s.warmUp("SkyBlock Sword"));

        assertFalse(sent.isEmpty());
        assertFalse(String.join("|", sent).contains("SkyBlock"), "sent: " + sent);
        assertEquals(List.of(List.of("T:Welcome to SkyBlock", "SKYBLOCK")), segments);
        assertEquals(List.of("T:Play SkyBlock now"), chat);
        assertEquals(List.of("T:Open the SkyBlock menu"), scan);
    }

    @Test
    void possessiveAfterAProtectedTermOrPlayerNameStillDisplays() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> text
                .replace("⟦0⟧'s best island", "⟦0⟧的最佳島嶼")
                .replace("⟦0⟧'s sword", "⟦0⟧的劍")));
        s.setProtectedNames(() -> Set.of("Steve"));

        s.translateItemLine("SkyBlock's best island");
        s.translateItemLine("Steve's sword");
        pump(s);

        assertEquals(List.of("⟦0⟧'s best island", "⟦0⟧'s sword"), sent);
        assertEquals("SkyBlock的最佳島嶼", s.translateItemLine("SkyBlock's best island").translated(),
                "a verbatim term before CJK is not a half-transliterated word");
        assertEquals("Steve的劍", s.translateItemLine("Steve's sword").translated(),
                "the same display gate fix applies to player names");
    }

    @Test
    void aiPlaceholderWithInnerSpacesIsStillRestored() {
        assertEquals("歡迎來到 SkyBlock！",
                NameMasker.unmask("歡迎來到 ⟦ 0 ⟧！", List.of("SkyBlock")));

        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("skyblock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg,
                recording(sent, text -> text.replace("Welcome to ⟦0⟧!", "歡迎來到 ⟦ 0 ⟧！")));
        s.translateChat("Welcome to SkyBlock!");
        pump(s);
        assertEquals("歡迎來到 SkyBlock！", s.translateChat("Welcome to SkyBlock!").translated());
    }

    @Test
    void translationThatLostATermNeverDisplaysAndSelfHealsOnlyOnce() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        AtomicInteger calls = new AtomicInteger();
        Translator placeholderEater = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("歡迎來到天空島", "en");
        };
        TranslationService s = service(cfg, placeholderEater);

        s.translateItemLine("Welcome to SkyBlock");
        pump(s);
        assertEquals(1, calls.get());
        assertFalse(s.translateItemLine("Welcome to SkyBlock").changed(),
                "a translation that lost the protected term must never display");

        s.translateItemLine("Welcome to SkyBlock");
        pump(s);
        assertEquals(2, calls.get(), "one self-heal re-buy after the eviction");
        assertFalse(s.translateItemLine("Welcome to SkyBlock").changed());

        s.translateItemLine("Welcome to SkyBlock");
        pump(s);
        assertEquals(2, calls.get(), "debounced like a mangled player ID");
    }

    @Test
    void addingOrRemovingATermNeedsNoCacheClear() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> text
                .replace("Welcome to SkyBlock", "歡迎來到天空島")
                .replace("Welcome to ⟦0⟧", "歡迎來到⟦0⟧")));

        s.translateScoreboardLine("Welcome to SkyBlock");
        pump(s);
        assertEquals("歡迎來到天空島", s.translateScoreboardLine("Welcome to SkyBlock").translated());

        cfg.doNotTranslateTerms.add("skyblock");
        assertFalse(s.translateScoreboardLine("Welcome to SkyBlock").changed(),
                "adding the term changes the key: the old wording is no longer served");
        pump(s);
        assertEquals(List.of("Welcome to SkyBlock", "Welcome to ⟦0⟧"), sent);
        assertEquals("歡迎來到SkyBlock", s.translateScoreboardLine("Welcome to SkyBlock").translated());

        cfg.doNotTranslateTerms.add("Hypixel");
        assertEquals("歡迎來到Hypixel", s.translateScoreboardLine("Welcome to Hypixel").translated(),
                "an edited list is recompiled, and every term shares the same sentence key");
        pump(s);
        assertEquals(2, sent.size());

        cfg.doNotTranslateTerms.clear();
        assertEquals("歡迎來到天空島", s.translateScoreboardLine("Welcome to SkyBlock").translated(),
                "removing the term brings the old row back");
        pump(s);
        assertEquals(2, sent.size(), "and nothing is sent again");
    }

    @Test
    void termsAndPlayerNamesShareOneIndexSpace() {
        NameMasker.Masked m = NameMasker.mask("SkyBlock loves Steve and SkyBlock",
                Set.of("Steve"), terms("skyblock"));
        assertEquals("⟦0⟧ loves ⟦1⟧ and ⟦0⟧", m.text());
        assertEquals(List.of("SkyBlock", "Steve"), m.names());
        NameMasker.Masked same = NameMasker.mask("SkyBlock", Set.of("SkyBlock"), terms("skyblock"));
        assertEquals("⟦0⟧", same.text());
        assertEquals(List.of("SkyBlock"), same.names(), "one span, one placeholder");

        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("skyblock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> text.replace(" joined ", " 加入了 ")));
        s.setProtectedNames(() -> Set.of("Steve"));
        s.translateChat("Steve joined SkyBlock");
        pump(s);
        assertEquals(List.of("⟦0⟧ joined ⟦1⟧"), sent);
        assertEquals("Steve 加入了 SkyBlock", s.translateChat("Steve joined SkyBlock").translated());
    }

    @Test
    void termsApplyEvenWhenPlayerNameProtectionIsOff() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.protectPlayerNames = false;
        cfg.doNotTranslateTerms.add("skyblock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg, recording(sent, text -> "T:" + text));
        s.setProtectedNames(() -> Set.of("Steve"));

        s.translateChat("Steve plays SkyBlock");
        pump(s);
        assertEquals(List.of("Steve plays ⟦0⟧"), sent);
        assertEquals("T:Steve plays SkyBlock", s.translateChat("Steve plays SkyBlock").translated());
    }

    @Test
    void termInsideAColourRunIsMaskedAndChatKeepsItsColours() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg,
                recording(sent, text -> text.replace("Welcome to", "歡迎來到")));
        List<String> chat = new ArrayList<>();

        s.translateChatAsync("⟦CS0⟧Welcome to⟦/CS0⟧ ⟦CS1⟧SkyBlock⟦/CS1⟧", chat::add);
        pump(s);

        assertEquals(List.of("⟦CS0⟧Welcome to⟦/CS0⟧ ⟦CS1⟧⟦0⟧⟦/CS1⟧"), sent);
        assertEquals(List.of("⟦CS0⟧歡迎來到⟦/CS0⟧ ⟦CS1⟧SkyBlock⟦/CS1⟧"), chat);
    }

    @Test
    void questWidgetLiveScreenTextUsesTheSameMaskedKeyAsItsRenderLookup() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScreenText = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("skyblock");
        List<String> sent = new ArrayList<>();
        TranslationService s = service(cfg,
                recording(sent, text -> text.replace("Welcome to ", "歡迎來到 ")));
        List<String> got = new ArrayList<>();

        s.requestLiveScreenTextAsync("Welcome to SkyBlock", got::add);
        pump(s);

        assertEquals(List.of("Welcome to ⟦0⟧"), sent, "the quest widget path is masked too");
        assertEquals(List.of("歡迎來到 SkyBlock"), got);
        assertEquals("歡迎來到 SkyBlock", s.translateScreenText("Welcome to SkyBlock").translated());
        assertEquals(1, sent.size(), "the render lookup hits the very same key");
    }

    @Test
    void warmupContextNeverCarriesTheRawTerm() {
        List<String> seen = new ArrayList<>();
        Translator contextual = new Translator() {
            @Override
            public TranslationResult translate(String text, String targetLang) {
                seen.add(text);
                return new TranslationResult("T:" + text, "en");
            }

            @Override
            public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                          List<String> surfaceContext) {
                seen.addAll(texts);
                if (surfaceContext != null) seen.addAll(surfaceContext);
                List<TranslationResult> out = new ArrayList<>();
                for (String text : texts) out.add(new TranslationResult("T:" + text, "en"));
                return out;
            }
        };
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.doNotTranslateTerms.add("SkyBlock");
        TranslationService s = service(cfg, contextual);

        s.warmTooltipBatch(List.of("SkyBlock Menu", "Click to open the SkyBlock menu"));

        assertFalse(seen.isEmpty());
        assertFalse(String.join("|", seen).contains("SkyBlock"), "sent: " + seen);
        assertEquals("T:SkyBlock Menu", s.translateItemLine("SkyBlock Menu").translated());
    }

    @Test
    void itemNameCorrectionRequestsItsSuffixMasked() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        cfg.doNotTranslateTerms.add("SkyBlock");
        List<String> sent = new ArrayList<>();
        Translator translator = recording(sent, text -> text.replace("⟦0⟧ Edition", "⟦0⟧版"));
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        ai.importTranslations(Map.of(
                "Aspect of the End", "末影之視",
                "Aspect of the End ⟦0⟧ Edition", "終界之刃 ⟦0⟧版"));
        TranslationService s = new TranslationService(cfg, gt, ai);
        List<String> tooltip = List.of("Aspect of the End SkyBlock Edition", "Right click to use");

        s.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
        pump(s);
        assertEquals(List.of("⟦0⟧ Edition"), sent, "the suffix is requested masked");

        s.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
        assertEquals("終界之刃", s.translateHeld("Aspect of the End").translated(),
                "restored wording on both sides still derives the contextual name");
        assertEquals(1, sent.size());
    }

    @Test
    void chatRequestProfileTracksTheTermList() {
        for (boolean aiChat : new boolean[] {false, true}) {
            TranslatorConfig cfg = TestConfigs.translating();
            cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
            cfg.aiChat = aiChat;
            ChatRequestProfile none = ChatRequestProfile.capture(cfg, cfg.targetLang);
            cfg.doNotTranslateTerms.add("SkyBlock");
            ChatRequestProfile one = ChatRequestProfile.capture(cfg, cfg.targetLang);
            assertNotEquals(none, one, "changing the list retires the chat backlog");
            assertEquals(one, ChatRequestProfile.capture(cfg, cfg.targetLang));
            assertEquals(one.hashCode(), ChatRequestProfile.capture(cfg, cfg.targetLang).hashCode());
            cfg.doNotTranslateTerms.add("Hypixel");
            assertNotEquals(one, ChatRequestProfile.capture(cfg, cfg.targetLang));
            cfg.doNotTranslateTerms.remove("Hypixel");
            assertEquals(one, ChatRequestProfile.capture(cfg, cfg.targetLang));
        }
    }

    @Test
    void compiledTermListIgnoresUnusableEntriesAndTracksItsSource() {
        assertTrue(DoNotTranslateMatcher.compile(Arrays.asList(null, " ", "⟦0⟧", "§eGold")).isEmpty(),
                "blank and protocol/format-code entries can never match");
        assertTrue(DoNotTranslateMatcher.compile(List.of(".", "、", "--")).isEmpty(),
                "punctuation-only entries (list typos) would mask every sentence");
        assertEquals("Hello. ⟦0⟧.", mask("Hello. SkyBlock.", terms(".", "SkyBlock")).text());
        assertTrue(DoNotTranslateMatcher.compile(null).isEmpty());

        List<String> configured = new ArrayList<>(List.of("SkyBlock", "skyblock "));
        DoNotTranslateMatcher compiled = DoNotTranslateMatcher.compile(configured);
        assertTrue(compiled.compiledFrom(configured));
        assertEquals(List.of("SKYBLOCK", "skyblock"),
                NameMasker.mask("SKYBLOCK skyblock", List.of(), compiled).names());
        configured.add("Hypixel");
        assertFalse(compiled.compiledFrom(configured), "an edited list is recompiled");
    }
}
