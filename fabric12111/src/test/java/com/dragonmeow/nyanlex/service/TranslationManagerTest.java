package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.TestConfigs;
import com.dragonmeow.nyanlex.cache.ManualTranslations;
import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.EditData;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Page;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Row;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.SaveOutcome;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.SaveResult;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Source;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translation manager model over real caches and a real service with inline fakes only: what it
 * lists and how it searches, the save validation matrix, colour versions, revert and delete, and the
 * guarantee that nothing automatic can undo a manual translation.
 */
class TranslationManagerTest {

    private static final Executor DIRECT = Runnable::run;

    private static final String COINS = "You got ⟦MT0⟧ coins";
    private static final String TITLE = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";
    private static final String TITLE_PLAIN = "Aspect of the End ✪✪✪";
    private static final String SEMANTIC = "Aspect of the End ⟦MT0⟧";
    private static final String COLOUR = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String OTHER_COLOUR = "⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ⟦MT0⟧⟦/CS1⟧";
    private static final String OTHER_TITLE = "⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ✪✪✪⟦/CS1⟧";

    private static final class MapStore implements PersistentStore {
        final Map<String, String> rows = new LinkedHashMap<>();
        final Set<String> provisional = new HashSet<>();
        boolean writable = true;

        @Override public String get(String key) { return rows.get(key); }
        @Override public void put(String key, String value) { put(key, value, false); }
        @Override public void put(String key, String value, boolean isProvisional) {
            rows.put(key, value);
            if (isProvisional) provisional.add(key);
            else provisional.remove(key);
        }
        @Override public boolean isProvisional(String key) { return provisional.contains(key); }
        @Override public void clear() { rows.clear(); provisional.clear(); }
        @Override public void remove(String key) { rows.remove(key); provisional.remove(key); }
        @Override public Map<String, String> entries() { return new LinkedHashMap<>(rows); }
        @Override public int size() { return rows.size(); }
        @Override public boolean isWritable() { return writable; }
    }

    private static final class Backend implements Translator {
        final String prefix;
        final AtomicInteger calls = new AtomicInteger();
        boolean fallbackProduct;

        Backend(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public TranslationResult translate(String text, String targetLang) {
            calls.incrementAndGet();
            return new TranslationResult(prefix + text, "en", fallbackProduct);
        }
    }

    /** Everything one test needs, wired like the loader does it. */
    private static final class Env {
        final TranslatorConfig config = TestConfigs.translating();
        final Backend aiBackend = new Backend("AI:");
        final Backend gtBackend = new Backend("GT:");
        final MapStore aiRows = new MapStore();
        final MapStore gtRows = new MapStore();
        final MapStore manualRows = new MapStore();
        final ManualTranslations manual;
        final TranslationCache ai;
        final TranslationCache gt;
        final TranslationService service;
        final TranslationManager manager;

        Env() {
            this(DIRECT);
        }

        Env(Executor managerExecutor) {
            config.aiTooltip = true;
            config.aiChat = true;
            config.churnGuard = false;
            gt = new TranslationCache(gtBackend, config.targetLang, DIRECT, 1000, 10_000L, () -> 0L, gtRows);
            ai = new TranslationCache(aiBackend, config.targetLang, DIRECT, 1000, 10_000L, () -> 0L, aiRows);
            service = new TranslationService(config, gt, ai);
            manual = new ManualTranslations(manualRows);
            service.setManualTranslations(manual);
            manager = new TranslationManager(service, ai, gt, manual, managerExecutor);
        }

        Page list() {
            manager.searchAsync("", false);
            manager.refreshAsync();
            return manager.page();
        }

        /** What an item tooltip line shows for this source (null: the original). */
        String tooltip(String source) {
            TranslationDecision decision = service.translateItemLine(source);
            return decision.changed() ? TextFilter.stripStyleFallback(decision.translated()) : null;
        }

        void ticks() {
            for (int i = 0; i < 8; i++) service.flushBatches();
        }
    }

    // ------------------------------------------------------------------ the list

    @Test
    void listsManualRowsFirstThenAiThenMachineNewestFirst() {
        Env env = new Env();
        env.aiRows.rows.put("A1", "一");
        env.aiRows.rows.put("A2", "二");
        env.aiRows.rows.put("Shared", "AI共用");
        env.gtRows.rows.put("G1", "機一");
        env.gtRows.rows.put("Shared", "機共用");
        env.gtRows.rows.put("A1", "機A1");
        env.manual.put("M1", "手一");
        env.manual.put("M2", "手二");
        env.manual.put("A2", "手A2");

        Page page = env.list();

        List<String> order = new ArrayList<>();
        for (Row row : page.rows()) order.add(row.key() + ":" + row.source() + ":" + row.translation());
        assertEquals(List.of("A2:MANUAL:手A2", "M2:MANUAL:手二", "M1:MANUAL:手一",
                "Shared:AI:AI共用", "A1:AI:一", "G1:MACHINE:機一"), order,
                "manual first (newest edit first), then AI, then machine; a sentence appears once");
        assertEquals(6, page.matched());
        assertEquals(6, page.totalSaved());
        assertEquals(3, page.manualCount());
        assertFalse(page.truncated());
        assertFalse(page.loading());
        assertEquals("", page.query());
    }

    @Test
    void whatIsNotATranslationIsNotListed() {
        Env env = new Env();
        env.aiRows.rows.put("Real", "真");
        env.aiRows.rows.put("Kept", "\u0000MT_KEEP_ORIGINAL2");
        env.aiRows.rows.put("Stand in", "暫代");
        env.aiRows.provisional.add("Stand in");
        env.aiRows.rows.put("§aStyled", "有樣式");
        env.aiRows.rows.put("Echo", "Echo");
        env.aiRows.rows.put(SEMANTIC, "終界之刃 ⟦MT0⟧");
        env.aiRows.rows.put(COLOUR, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");

        Page page = env.list();

        assertEquals(List.of(SEMANTIC, "Real"), page.rows().stream().map(Row::key).toList(),
                "the colour row is not a line of its own");
        assertTrue(page.rows().get(0).hasColorVersions(), "but the sentence says it has colour versions");
        assertFalse(page.rows().get(1).hasColorVersions());
    }

    @Test
    void searchMatchesOriginalOrTranslationIgnoringCaseAndMarkers() {
        Env env = new Env();
        env.aiRows.rows.put("Sharpness", "鋒利");
        env.aiRows.rows.put(COINS, "你得到 ⟦MT0⟧ 枚金幣");
        env.aiRows.rows.put("Smite", "重擊");
        env.list();

        assertEquals(List.of("Sharpness"), keysFor(env, "SHARP"));
        assertEquals(List.of("Sharpness"), keysFor(env, "鋒"), "the translation column is searched too");
        assertEquals(List.of(COINS), keysFor(env, "coins 金幣"), "every word must occur, in either column");
        assertEquals(List.of(), keysFor(env, "coins 鋒利"));
        assertEquals(List.of(), keysFor(env, "mt"), "a marker is not text: searching mt finds nothing");
        assertEquals(List.of(COINS), keysFor(env, "{V0}"), "a tag typed as its alias finds the sentences that have it");
        assertEquals(3, keysFor(env, "  ").size(), "a blank query lists everything");
        env.manager.searchAsync("zzz", false);
        assertEquals(0, env.manager.page().matched());
        assertEquals("zzz", env.manager.page().query());
    }

    private static List<String> keysFor(Env env, String query) {
        env.manager.searchAsync(query, false);
        return env.manager.page().rows().stream().map(Row::key).toList();
    }

    @Test
    void thePageIsCappedAtTwoHundredAndStillCountsEverything() {
        Env env = new Env();
        for (int i = 0; i < 300; i++) env.aiRows.rows.put("Item " + i, "物品 " + i);
        env.manual.put("Item 5", "手動五");

        Page page = env.list();

        assertEquals(TranslationManagerModel.MAX_PAGE_ROWS, page.rows().size());
        assertEquals(300, page.matched());
        assertEquals(300, page.totalSaved());
        assertTrue(page.truncated());
        assertEquals("Item 5", page.rows().get(0).key(), "manual first");
        assertEquals("Item 299", page.rows().get(1).key(), "then the newest");

        env.manager.searchAsync("item 29", false);
        assertEquals(13, env.manager.page().matched(), "Item 29, 129, 229 and 290..299");
        assertFalse(env.manager.page().truncated());

        env.manager.searchAsync("", true);
        assertEquals(1, env.manager.page().rows().size());
        assertTrue(env.manager.page().manualOnly());
    }

    @Test
    void aNewerSearchReplacesAnOlderOne() {
        Deque<Runnable> held = new ArrayDeque<>();
        Env env = new Env(held::add);
        env.aiRows.rows.put("Alpha", "甲");
        env.aiRows.rows.put("Beta", "乙");
        assertSame(Page.LOADING, env.manager.page(), "nothing is read before the first request");

        env.manager.searchAsync("alpha", false);
        env.manager.searchAsync("beta", false);
        held.poll().run(); // the first one: replaced before it started
        assertSame(Page.LOADING, env.manager.page());
        held.poll().run();
        assertEquals("beta", env.manager.page().query());
        assertEquals(List.of("Beta"), env.manager.page().rows().stream().map(Row::key).toList());
    }

    @Test
    void refreshReadsTheStoresAgainAndKeepsTheQuery() {
        Env env = new Env();
        env.aiRows.rows.put("Alpha", "甲");
        env.list();
        env.manager.searchAsync("a", false);
        assertEquals(1, env.manager.page().matched());

        env.aiRows.rows.put("Beta", "乙");
        env.manager.searchAsync("a", false);
        assertEquals(1, env.manager.page().matched(), "a search alone does not re-read the stores");
        env.manager.refreshAsync();
        assertEquals(2, env.manager.page().matched());
        assertEquals("a", env.manager.page().query());
    }

    // ------------------------------------------------------------------ saving

    @Test
    void savingMakesEverySurfaceShowTheTextAndNothingIsRequested() {
        Env env = new Env();
        env.aiRows.rows.put(COINS, "你得到 ⟦MT0⟧ 枚金幣");
        env.list();
        assertEquals("你得到5枚金幣", env.tooltip("You got 5 coins"));

        SaveOutcome outcome = env.manager.save(COINS, "你拿到了 ⟦MT0⟧ 個金幣", null, null);

        assertTrue(outcome.ok(), outcome.toString());
        assertEquals("你拿到了5個金幣", env.tooltip("You got 5 coins"));
        assertEquals("你拿到了99個金幣", env.tooltip("You got 99 coins"), "every number shares the one wording");
        Page page = env.manager.page();
        assertEquals(Source.MANUAL, page.rows().get(0).source(), "the list is right before any rescan");
        assertEquals("你拿到了 ⟦MT0⟧ 個金幣", page.rows().get(0).translation());
        assertEquals(1, page.manualCount());
        assertEquals(1, page.totalSaved());
        EditData edit = env.manager.editData(COINS);
        assertEquals(Source.MANUAL, edit.source());
        assertEquals("你得到 ⟦MT0⟧ 枚金幣", edit.automaticTranslation(), "the automatic wording stays for comparison");
        assertEquals(Source.AI, edit.automaticSource());
        assertEquals(0, env.aiBackend.calls.get());
        assertEquals(0, env.gtBackend.calls.get());
    }

    @Test
    void savingAnswersACallerThatWaitedForAnAutomaticFinalWording() {
        Env env = new Env();
        env.aiBackend.fallbackProduct = true; // the AI engine only produced a stand-in so far
        List<String> got = new ArrayList<>();
        env.ai.requestCoalescedFinal("You got 5 coins", got::add);
        env.ticks();
        assertTrue(got.isEmpty(), "a stand-in is not final");

        assertTrue(env.manager.save(COINS, "你拿到了 ⟦MT0⟧ 個金幣", null, null).ok());

        assertEquals(List.of("你拿到了5個金幣"), got);
    }

    @Test
    void anAutomaticWordingCanBePinnedUnchanged() {
        Env env = new Env();
        env.gtRows.rows.put("Sharpness", "機翻鋒利");
        env.list();
        assertEquals(Source.MACHINE, env.manager.editData("Sharpness").source());
        assertNull(env.manager.editData("Sharpness").automaticTranslation(), "not manual: nothing to compare");

        assertTrue(env.manager.save("Sharpness", "機翻鋒利", null, null).ok());

        assertEquals(Source.MANUAL, env.manager.editData("Sharpness").source());
        env.config.aiTooltip = false; // the machine engine's surface
        env.gtRows.rows.put("Sharpness", "機翻改了");
        assertEquals("機翻鋒利", env.tooltip("Sharpness"), "a later automatic change cannot touch it");
    }

    @Test
    void nothingAutomaticCanOverwriteOrHideAManualTranslation() {
        Env env = new Env();
        env.aiRows.rows.put(COINS, "你得到 ⟦MT0⟧ 枚金幣");
        env.list();
        assertTrue(env.manager.save(COINS, "手動 ⟦MT0⟧ 金幣", null, null).ok());
        int manualRows = env.manual.size();
        env.config.disableGoogleFallbackForAi = false;

        // R and P, the explicit retranslation entry points
        env.service.retranslate(List.of("You got 5 coins"));
        env.service.retranslateScreen(List.of("You got 5 coins"));
        // pre-translation, tooltip warm-up, name warm-up, hover warm-up, the per-tick collector
        env.service.warmTooltipBatch(List.of("You got 5 coins"));
        env.service.warmTooltipBatchBackground(List.of("You got 5 coins"));
        env.service.warmNamesBatch(List.of("You got 5 coins"));
        env.service.requestItemLines(List.of("You got 5 coins"));
        env.service.warmUp("You got 5 coins");
        env.service.requestChatAsync("You got 5 coins", ignored -> { });
        env.ticks();
        // a tooltip title reconciliation
        env.service.reconcileItemNameWithTooltip("You got 5 coins", List.of("You got 5 coins"));
        // an import and "clear stored translations"
        env.ai.importTranslations(Map.of(COINS, "匯入 ⟦MT0⟧ 金幣"));
        env.service.clearTranslations();

        assertEquals("手動5金幣", env.tooltip("You got 5 coins"));
        assertEquals(manualRows, env.manual.size());
        assertEquals("手動 ⟦MT0⟧ 金幣", env.manual.get(COINS));
        assertEquals(0, env.aiBackend.calls.get() + env.gtBackend.calls.get(),
                "every one of them left the sentence alone and sent nothing for it");
    }

    @Test
    void theRepositoryNeverShowsOverAManualTranslation() {
        for (boolean useAi : new boolean[] {true, false}) {
            Env env = new Env();
            env.config.aiTooltip = useAi;
            env.service.setHubLookup(key -> key.equals(COINS) ? "倉庫 ⟦MT0⟧ 金幣" : null);
            assertEquals("倉庫5金幣", env.tooltip("You got 5 coins"), "control: the repository answers");
            env.manual.put(COINS, "手動 ⟦MT0⟧ 金幣");

            assertEquals("手動5金幣", env.tooltip("You got 5 coins"), "AI engine: " + useAi);
            env.ticks();
            assertEquals(0, env.aiBackend.calls.get() + env.gtBackend.calls.get());
        }
    }

    @Test
    void aSingleCacheServiceStillPutsTheManualRowAboveTheRepository() {
        TranslatorConfig config = TestConfigs.translating();
        config.aiTooltip = false;
        TranslationCache only = new TranslationCache(new Backend("T:"), config.targetLang, DIRECT, 100);
        TranslationService service = new TranslationService(config, only, only);
        service.setHubLookup(key -> key.equals(COINS) ? "倉庫 ⟦MT0⟧ 金幣" : null);
        ManualTranslations manual = new ManualTranslations(new MapStore());
        service.setManualTranslations(manual);
        manual.put(COINS, "手動 ⟦MT0⟧ 金幣");

        assertEquals("手動5金幣", service.translateItemLine("You got 5 coins").translated());
    }

    @Test
    void theValidationMatrixRefusesEachProblemAndStoresNothing() {
        Env env = new Env();
        String paragraphs = "A⟦PB0⟧B⟦PB1⟧C";
        String values = "A ⟦MT0⟧⟦PB0⟧B ⟦MT1⟧";
        String lines = "Line one\nLine two";
        String halfWord = "Jaeger's Sword";
        for (String key : List.of(COINS, paragraphs, values, lines, halfWord, "⟦0⟧ joined")) {
            env.aiRows.rows.put(key, "舊譯文 " + key.length());
        }
        TranslationManagerModel m = env.manager;

        assertEquals(SaveResult.EMPTY, m.save(COINS, "   ", null, null).result());
        assertEquals(SaveResult.EMPTY, m.save(COINS, null, null, null).result());
        assertEquals(SaveResult.SAME_AS_SOURCE, m.save(COINS, " You got ⟦MT0⟧ coins ", null, null).result());
        for (String bad : List.of("你得到\u0001 ⟦MT0⟧", "你得到\u0000 ⟦MT0⟧", "你得到§a ⟦MT0⟧", "你得到\u0003⟦MT0⟧")) {
            assertEquals(SaveResult.CONTROL_CHARS, m.save(COINS, bad, null, null).result(), bad);
        }
        SaveOutcome tooLong = m.save(COINS, "你".repeat(2001) + " ⟦MT0⟧", null, null);
        assertEquals(SaveResult.TOO_LONG, tooLong.result());
        assertEquals(TranslationManagerModel.MAX_TRANSLATION_LENGTH, tooLong.number());

        SaveOutcome missing = m.save(COINS, "你得到金幣", null, null);
        assertEquals(SaveResult.MISSING_TAGS, missing.result());
        assertEquals(List.of("{V0}"), missing.tags());
        SaveOutcome extra = m.save(COINS, "你得到 ⟦MT0⟧ ⟦MT1⟧ 枚金幣", null, null);
        assertEquals(SaveResult.EXTRA_TAGS, extra.result());
        assertEquals(List.of("{V1}"), extra.tags());
        SaveOutcome twice = m.save(COINS, "你得到 ⟦MT0⟧ ⟦MT0⟧ 枚金幣", null, null);
        assertEquals(SaveResult.EXTRA_TAGS, twice.result());
        assertEquals(List.of("{V0}"), twice.tags());
        assertEquals(SaveResult.EXTRA_TAGS, m.save(COINS, "你得到 ⟦MT0⟧ ⟦CS0⟧金幣⟦/CS0⟧", null, null).result(),
                "colour tags have no place in a sentence that has none");
        SaveOutcome name = m.save("⟦0⟧ joined", "加入了", null, null);
        assertEquals(SaveResult.MISSING_TAGS, name.result());
        assertEquals(List.of("{N0}"), name.tags());

        assertEquals(SaveResult.TAG_ORDER, m.save(paragraphs, "甲⟦PB1⟧乙⟦PB0⟧丙", null, null).result(),
                "paragraph breaks keep their order");
        assertEquals(SaveResult.TAG_ORDER, m.save(values, "甲 ⟦MT1⟧⟦PB0⟧乙 ⟦MT0⟧", null, null).result(),
                "a value cannot move to another paragraph row");
        SaveOutcome newlines = m.save(lines, "一行二行", null, null);
        assertEquals(SaveResult.NEWLINES, newlines.result());
        assertEquals(1, newlines.number());
        assertEquals(SaveResult.PARTIAL, m.save(halfWord, "傑ger之劍", null, null).result(),
                "the cache would refuse to show it, so the editor must not accept it");

        assertTrue(env.manual.isEmpty(), "none of the refused texts was stored");
        // and the allowed variants of the same sentences go through
        assertTrue(m.save(COINS, "你得到 ⟦MT0⟧ 枚金幣，真棒", null, null).ok());
        assertTrue(m.save(COINS, "⟦MT0⟧ 枚金幣到手", null, null).ok(), "tags may move");
        assertTrue(m.save(paragraphs, "甲⟦PB0⟧乙⟦PB1⟧丙", null, null).ok());
        assertTrue(m.save(values, "甲 ⟦MT0⟧⟦PB0⟧乙 ⟦MT1⟧", null, null).ok());
        assertTrue(m.save(lines, "一行\n二行", null, null).ok());
        assertTrue(m.save("⟦0⟧ joined", "⟦0⟧ 加入了", null, null).ok());
    }

    @Test
    void aFullStoreAndAnUnwritableFileAreReported() {
        Env env = new Env();
        env.aiRows.rows.put("Sharpness", "鋒利");
        env.manualRows.rows.clear();
        for (int i = 0; i < ManualTranslations.MAX_ROWS; i++) env.manualRows.rows.put("row " + i, "譯 " + i);
        env.service.setManualTranslations(new ManualTranslations(env.manualRows));
        TranslationManager full = new TranslationManager(env.service, env.ai, env.gt,
                env.service.manualTranslations(), DIRECT);
        SaveOutcome outcome = full.save("Sharpness", "鋒利啊", null, null);
        assertEquals(SaveResult.FULL, outcome.result());
        assertEquals(ManualTranslations.MAX_ROWS, outcome.number());

        Env broken = new Env();
        broken.aiRows.rows.put("Sharpness", "鋒利");
        broken.manualRows.writable = false;
        assertEquals(SaveResult.IO_ERROR, broken.manager.save("Sharpness", "鋒利啊", null, null).result());
        assertNull(broken.manual.get("Sharpness"));
        assertEquals("鋒利", broken.tooltip("Sharpness"), "an unsaved text is not shown; the automatic one stays");
    }

    @Test
    void whateverTheEditorAcceptsIsActuallyShownByTheService() {
        // The editor must never accept a text the display path would then silently refuse.
        Env env = new Env();
        env.config.protectPlayerNames = true;
        env.service.setProtectedNames(() -> Set.of("Steve"));
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("You got 5 coins", new String[] {COINS, "你得到 ⟦MT0⟧ 枚金幣", "你得到5枚金幣"});
        cases.put("You got 5 coins ", new String[] {COINS, "⟦MT0⟧ 枚金幣到手", "5枚金幣到手 "}); // outer spacing is kept
        cases.put("Steve joined the game", new String[] {"⟦0⟧ joined the game", "⟦0⟧ 進入了遊戲", "Steve 進入了遊戲"});
        cases.put("Kills: 12 / 30", new String[] {"Kills: ⟦MT0⟧ / ⟦MT1⟧", "擊殺：⟦MT0⟧／⟦MT1⟧", "擊殺：12／30"});
        for (Map.Entry<String, String[]> entry : cases.entrySet()) {
            String source = entry.getKey();
            String key = entry.getValue()[0];
            env.aiRows.rows.put(key, "舊 " + key.length());
            SaveOutcome outcome = env.manager.save(key, entry.getValue()[1], null, null);
            assertTrue(outcome.ok(), source + " -> " + outcome);
            assertEquals(entry.getValue()[2], env.tooltip(source), source);
        }
        assertEquals(0, env.aiBackend.calls.get());
    }

    @Test
    void everyTextTheEditorAcceptsIsShownByTheDisplayPath() {
        // Property: whatever save() accepts, the display path (decide) shows; nothing is stored and
        // then silently refused. Texts are random arrangements of a sentence's own markers.
        Env env = new Env();
        Set<String> names = Set.of("Steve", "Alex");
        env.config.protectPlayerNames = true;
        env.service.setProtectedNames(() -> names);
        String[][] sentences = {
                {"You got 5 coins", "5"},
                {"Steve gave Alex 5 coins", "Steve", "Alex", "5"},
                {"Steve has 12 / 30 kills", "Steve", "12", "30"},
        };
        String[] separators = {"", " ", "，", "的", "了 ", "：", "、"};
        String[] words = {"你", "得到", "金幣", "擊殺", "贈送", "給"};
        java.util.Random random = new java.util.Random(7);
        int accepted = 0;
        int refused = 0;
        for (String[] sentence : sentences) {
            String source = sentence[0];
            String key = env.ai.semanticKey(com.dragonmeow.nyanlex.translate.NameMasker.mask(source, names,
                    com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher.EMPTY).text());
            List<com.dragonmeow.nyanlex.translate.ManualTextCodec.Tag> tags =
                    new ArrayList<>(com.dragonmeow.nyanlex.translate.ManualTextCodec.tags(key));
            for (int round = 0; round < 150; round++) {
                java.util.Collections.shuffle(tags, random);
                StringBuilder text = new StringBuilder(words[random.nextInt(words.length)]);
                List<String> used = new ArrayList<>();
                used.add(text.toString());
                for (com.dragonmeow.nyanlex.translate.ManualTextCodec.Tag tag : tags) {
                    text.append(separators[random.nextInt(separators.length)]).append(tag.raw());
                    String word = words[random.nextInt(words.length)];
                    text.append(random.nextBoolean() ? word : "");
                    if (text.toString().endsWith(word)) used.add(word);
                }
                SaveOutcome outcome = env.manager.save(key, text.toString(), null, null);
                if (!outcome.ok()) {
                    refused++;
                    continue;
                }
                accepted++;
                String shown = env.tooltip(source);
                assertNotNull(shown, "accepted but not shown: " + text + " for " + source);
                for (String word : used) assertTrue(shown.contains(word), shown + " lacks " + word);
                for (int i = 1; i < sentence.length; i++) {
                    assertTrue(shown.contains(sentence[i]), shown + " lacks the value " + sentence[i]);
                }
            }
        }
        assertTrue(accepted > 100, "the property must be exercised: " + accepted);
        assertTrue(refused > 20, "and some arrangements must have been refused: " + refused);
        assertEquals(0, env.aiBackend.calls.get());
    }

    @Test
    void aNameGluedToAWordIsRefusedBecauseTheDisplayWouldRefuseIt() {
        Env env = new Env();
        env.service.setProtectedNames(() -> Set.of("Steve"));
        String key = "⟦0⟧ has ⟦MT0⟧ points";
        assertEquals(SaveResult.TAG_ORDER, env.manager.save(key, "⟦0⟧⟦MT0⟧ 分", null, null).result());
        assertEquals(SaveResult.TAG_ORDER, env.manager.save(key, "x⟦0⟧ 有 ⟦MT0⟧ 分", null, null).result());
        assertEquals(SaveResult.TAG_ORDER, env.manager.save(key, "⟦0⟧9 有 ⟦MT0⟧ 分", null, null).result());
        assertTrue(env.manager.save(key, "⟦0⟧ 有 ⟦MT0⟧ 分", null, null).ok());
        assertTrue(env.manager.save(key, "⟦MT0⟧ 分屬於⟦0⟧", null, null).ok());
        assertEquals("12分屬於Steve", env.tooltip("Steve has 12 points"));
    }

    @Test
    void aNameOrTermThatChangesTheMaskedKeyMakesTheRowStopApplying() {
        // Documented behaviour: the manual row belongs to the masked sentence. Remove the
        // do-not-translate term and the sentence has another key, so the row no longer applies
        // (it is not lost: adding the term again makes it apply again).
        Env env = new Env();
        env.config.doNotTranslateTerms = new ArrayList<>(List.of("Zorbo"));
        env.aiRows.rows.put("⟦0⟧ rules the world", "舊 ⟦0⟧");
        env.list();
        assertTrue(env.manager.save("⟦0⟧ rules the world", "⟦0⟧ 統治世界", null, null).ok());
        assertEquals("Zorbo 統治世界", env.tooltip("Zorbo rules the world"));

        env.config.doNotTranslateTerms = new ArrayList<>();
        assertNull(env.tooltip("Zorbo rules the world"), "no term, another key: the row does not apply");
        assertEquals("⟦0⟧ 統治世界", env.manual.get("⟦0⟧ rules the world"), "but it is still stored");

        env.config.doNotTranslateTerms = new ArrayList<>(List.of("Zorbo"));
        assertEquals("Zorbo 統治世界", env.tooltip("Zorbo rules the world"));
    }

    // ------------------------------------------------------------------ colour versions

    private static Env colourEnv() {
        Env env = new Env();
        env.aiRows.rows.put(SEMANTIC, "終界之刃 ⟦MT0⟧");
        env.aiRows.rows.put(COLOUR, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        env.aiRows.rows.put(OTHER_COLOUR, "⟦CS0⟧終界⟦/CS0⟧⟦CS1⟧之刃 ⟦MT0⟧⟦/CS1⟧");
        env.list();
        return env;
    }

    @Test
    void theEditorOffersEveryColourVersionOfASentence() {
        Env env = colourEnv();
        EditData edit = env.manager.editData(SEMANTIC);
        assertEquals(List.of(COLOUR, OTHER_COLOUR),
                edit.colorVersions().stream().map(TranslationManagerModel.ColorVersion::sourceKey).toList());
        assertEquals("⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧", edit.colorVersions().get(0).translation());
        assertEquals("終界之刃 ⟦MT0⟧", edit.translation());
        assertTrue(env.manager.page().rows().get(0).hasColorVersions());
    }

    @Test
    void aSavedColourVersionKeepsThePerWordColoursAndTheOtherColouringsFallBack() {
        Env env = colourEnv();
        String exact = "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";

        SaveOutcome outcome = env.manager.save(SEMANTIC, "我的終界之刃 ⟦MT0⟧", COLOUR,
                "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");

        assertTrue(outcome.ok(), outcome.toString());
        assertEquals(2, env.manual.size(), "the plain row and the colour row");
        assertEquals(exact, env.ai.getCached(TITLE), "exact colours, no fallback mark");
        assertFalse(TextFilter.isStyleFallback(env.ai.getCached(TITLE)));
        String other = env.ai.getCached(OTHER_TITLE);
        assertTrue(TextFilter.isStyleFallback(other), "the other colouring has no manual colour row");
        assertEquals("我的終界之刃 ✪✪✪", TextFilter.stripStyleFallback(other));
        assertEquals("我的終界之刃5", env.tooltip("Aspect of the End 5"), "the plain line reads the plain row");

        EditData edit = env.manager.editData(SEMANTIC);
        assertEquals(COLOUR, edit.colorVersions().get(0).sourceKey());
        assertEquals("⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧", edit.colorVersions().get(0).translation(),
                "the manual wording is what the editor shows now");
        assertEquals("⟦CS0⟧終界⟦/CS0⟧⟦CS1⟧之刃 ⟦MT0⟧⟦/CS1⟧", edit.colorVersions().get(1).translation(),
                "the other colouring still shows the automatic one");
        assertEquals(0, env.aiBackend.calls.get());
    }

    @Test
    void eachColourVersionIsSavedIndependently() {
        Env env = colourEnv();
        assertTrue(env.manager.save(SEMANTIC, "我的終界之刃 ⟦MT0⟧", COLOUR,
                "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧").ok());
        assertTrue(env.manager.save(SEMANTIC, "我的終界之刃 ⟦MT0⟧", OTHER_COLOUR,
                "⟦CS0⟧我的終界⟦/CS0⟧⟦CS1⟧之刃 ⟦MT0⟧⟦/CS1⟧").ok());

        assertEquals("⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧", env.ai.getCached(TITLE));
        assertEquals("⟦CS0⟧我的終界⟦/CS0⟧⟦CS1⟧之刃 ✪✪✪⟦/CS1⟧", env.ai.getCached(OTHER_TITLE));
        assertEquals(3, env.manual.size());
    }

    @Test
    void revertRemovesTheColourRowsWithTheSentence() {
        Env env = colourEnv();
        assertTrue(env.manager.save(SEMANTIC, "我的終界之刃 ⟦MT0⟧", COLOUR,
                "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧").ok());

        assertTrue(env.manager.revertToAutomatic(SEMANTIC));

        assertTrue(env.manual.isEmpty(), "the plain row and every colour row are gone");
        assertEquals("⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧", env.ai.getCached(TITLE), "the automatic colours are back");
        assertEquals(Source.AI, env.manager.page().rows().get(0).source());
        assertEquals(0, env.manager.page().manualCount());
        assertEquals(0, env.aiBackend.calls.get());
        assertEquals("終界之刃 ⟦MT0⟧", env.aiRows.rows.get(SEMANTIC), "the automatic row was never touched");
    }

    @Test
    void theColourValidationRefusesDamagedMarkers() {
        Env env = colourEnv();
        String plain = "我的終界之刃 ⟦MT0⟧";
        SaveOutcome missing = env.manager.save(SEMANTIC, plain, COLOUR, "⟦CS0⟧我的終界之刃 ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        assertEquals(SaveResult.MISSING_TAGS, missing.result());
        assertEquals(List.of("{/C0}"), missing.tags());
        assertEquals(SaveResult.MISSING_TAGS,
                env.manager.save(SEMANTIC, plain, COLOUR, "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦MT0⟧").result(),
                "a whole colour run missing");
        SaveOutcome extra = env.manager.save(SEMANTIC, plain, COLOUR,
                "⟦CS0⟧我的⟦/CS0⟧⟦CS2⟧終界之刃⟦/CS2⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        assertEquals(SaveResult.EXTRA_TAGS, extra.result(), "a run the original does not have");
        assertEquals(List.of("{C2}", "{/C2}"), extra.tags());
        assertEquals(SaveResult.TAG_ORDER, env.manager.save(SEMANTIC, plain, COLOUR,
                "⟦CS0⟧我的⟦CS1⟧終界之刃⟦/CS0⟧ ⟦MT0⟧⟦/CS1⟧").result(),
                "runs that overlap are not pairs");
        assertEquals(SaveResult.TAG_ORDER, env.manager.save(SEMANTIC, plain, COLOUR,
                "我的終界之刃 ⟦CS0⟧⟦/CS0⟧⟦CS1⟧⟦MT0⟧⟦/CS1⟧").result(),
                "wording left outside every run");
        assertEquals(SaveResult.TAG_ORDER, env.manager.save(SEMANTIC, plain, "⟦CS0⟧x⟦/CS0⟧ ⟦MT0⟧",
                "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦MT0⟧").result(),
                "a colour row that does not belong to this sentence");
        assertTrue(env.manual.isEmpty());
    }

    // ------------------------------------------------------------------ revert and delete

    @Test
    void revertShowsTheAutomaticWordingAgainAndLeavesItUntouched() {
        Env env = new Env();
        env.aiRows.rows.put(COINS, "你得到 ⟦MT0⟧ 枚金幣");
        env.manual.put("Only manual", "只有手動");
        env.list();
        assertTrue(env.manager.save(COINS, "手動 ⟦MT0⟧ 金幣", null, null).ok());
        assertEquals("手動5金幣", env.tooltip("You got 5 coins"));

        assertTrue(env.manager.revertToAutomatic(COINS));

        assertEquals("你得到5枚金幣", env.tooltip("You got 5 coins"));
        assertEquals("你得到 ⟦MT0⟧ 枚金幣", env.aiRows.rows.get(COINS));
        assertEquals(Source.AI, env.manager.editData(COINS).source());
        assertEquals(1, env.manager.page().manualCount());

        assertTrue(env.manager.revertToAutomatic("Only manual"), "a row with no automatic wording just disappears");
        assertNull(env.manager.editData("Only manual"));
        assertEquals(List.of(COINS), env.manager.page().rows().stream().map(Row::key).toList());
        assertEquals(0, env.manager.page().manualCount());
        assertEquals(1, env.manager.page().totalSaved());
        assertTrue(env.manual.isEmpty());

        assertTrue(env.manager.revertToAutomatic("nothing here"), "nothing to revert is not an error");
    }

    @Test
    void revertReportsAnUnwritableFile() {
        Env env = new Env();
        env.aiRows.rows.put("Sharpness", "鋒利");
        assertTrue(env.manager.save("Sharpness", "鋒利啊", null, null).ok());
        env.manualRows.writable = false;
        assertFalse(env.manager.revertToAutomatic("Sharpness"));
        assertEquals("鋒利啊", env.manual.get("Sharpness"), "the row is still there and still applies");
    }

    @Test
    void deleteRemovesEverythingStoredSoItIsTranslatedAgainOnce() {
        Env env = new Env();
        env.service.setHubLookup(key -> key.equals(COINS) ? "倉庫 ⟦MT0⟧ 金幣" : null);
        env.aiRows.rows.put(COINS, "你得到 ⟦MT0⟧ 枚金幣");
        env.gtRows.rows.put(COINS, "機翻 ⟦MT0⟧ 金幣");
        env.aiRows.rows.put("Other", "別的");
        env.manual.put(COINS, "手動 ⟦MT0⟧ 金幣");
        env.list();
        assertEquals("手動5金幣", env.tooltip("You got 5 coins"));

        boolean requestsOn = env.manager.deleteAndRetranslate(COINS);

        assertTrue(requestsOn);
        assertTrue(env.manual.isEmpty());
        assertFalse(env.aiRows.rows.containsKey(COINS));
        assertFalse(env.gtRows.rows.containsKey(COINS));
        assertEquals("別的", env.aiRows.rows.get("Other"));
        assertEquals(List.of("Other"), env.manager.page().rows().stream().map(Row::key).toList());
        assertEquals(0, env.aiBackend.calls.get(), "nothing is sent at deletion time");

        assertNull(env.tooltip("You got 5 coins"), "the old repository row does not come back for any number");
        assertNull(env.tooltip("You got 99 coins"));
        env.ticks();
        assertEquals(1, env.aiBackend.calls.get(), "the next time it appears it is requested once");
        assertEquals("AI:You got ⟦MT0⟧ coins", env.aiRows.rows.get(COINS));
        assertNotNull(env.tooltip("You got 7 coins"));
        env.ticks();
        assertEquals(1, env.aiBackend.calls.get());
    }

    @Test
    void deleteAlsoRemovesColourRowsAndFailureMarksAndTellsWhenRequestsAreOff() {
        Env env = new Env();
        MapStore failures = new MapStore();
        failures.rows.put(SEMANTIC, "temporary:1:" + (System.currentTimeMillis() + 3_600_000L));
        env.ai.setFailureStore(failures);
        env.aiRows.rows.put(SEMANTIC, "終界之刃 ⟦MT0⟧");
        env.aiRows.rows.put(COLOUR, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        env.gtRows.rows.put(COLOUR, "⟦CS0⟧機翻⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        env.manual.put(SEMANTIC, "手動 ⟦MT0⟧");
        env.list();
        env.config.translationRequestsEnabled = false;

        assertFalse(env.manager.deleteAndRetranslate(SEMANTIC), "requests are switched off: the screen says so");

        assertTrue(env.aiRows.rows.isEmpty());
        assertTrue(env.gtRows.rows.isEmpty());
        assertTrue(failures.rows.isEmpty());
        assertTrue(env.manual.isEmpty());
        assertNull(env.ai.getCached(TITLE));
        assertNull(env.ai.getCached(TITLE_PLAIN));
        env.ticks();
        assertNull(env.ai.getCached(TITLE_PLAIN), "no colour row is left to rebuild the sentence from");
    }

    @Test
    void aColourRowWrittenAfterTheLastScanIsFoundByTheLateScan() {
        Deque<Runnable> held = new ArrayDeque<>();
        Env env = new Env(held::add);
        env.aiRows.rows.put(SEMANTIC, "終界之刃 ⟦MT0⟧");
        env.manager.refreshAsync();
        while (!held.isEmpty()) held.poll().run(); // the manager now knows no colour row
        env.aiRows.rows.put(COLOUR, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧"); // arrives afterwards

        env.manager.deleteAndRetranslate(SEMANTIC);
        assertTrue(env.aiRows.rows.containsKey(COLOUR), "the quick part does not scan");
        while (!held.isEmpty()) held.poll().run();

        assertTrue(env.aiRows.rows.isEmpty(), "the scan on the manager's own thread finds and removes it");
        assertNull(env.ai.getCached(TITLE));
    }

    // ------------------------------------------------------------------ the service around it

    @Test
    void clearingTheStoredTranslationsKeepsTheManualOnes() {
        Env env = new Env();
        env.aiRows.rows.put("Sharpness", "鋒利");
        env.gtRows.rows.put("Smite", "重擊");
        env.manual.put("Sharpness", "手動鋒利");

        env.service.clearTranslations();

        assertTrue(env.aiRows.rows.isEmpty());
        assertTrue(env.gtRows.rows.isEmpty());
        assertEquals("手動鋒利", env.manual.get("Sharpness"));
        assertEquals("手動鋒利", env.ai.getCached("Sharpness"));
    }

    @Test
    void theServiceHandsOutItsOwnManagerAndRunsTheChangeHook() {
        TranslatorConfig config = TestConfigs.translating();
        config.aiTooltip = true;
        TranslationService service = new TranslationService(config,
                new TranslationCache(new Backend("GT:"), config.targetLang, DIRECT, 100),
                new TranslationCache(new Backend("AI:"), config.targetLang, DIRECT, 100));
        assertNull(service.manager(), "no manual store attached yet");

        AtomicInteger hook = new AtomicInteger();
        service.setManualChangeListener(hook::incrementAndGet);
        service.setManualTranslations(new ManualTranslations(new MapStore()));
        TranslationManagerModel model = service.manager();
        assertNotNull(model);
        int attached = hook.get();

        assertTrue(model.save("Sharpness", "鋒利", null, null).ok());
        assertTrue(hook.get() > attached, "a save runs the loader's hook so it can clear its render memos");
        int afterSave = hook.get();
        assertTrue(model.revertToAutomatic("Sharpness"));
        assertTrue(hook.get() > afterSave, "so does a revert");
    }

    @Test
    void theProductionManagerScansOnItsOwnThread() throws Exception {
        Env env = new Env();
        env.aiRows.rows.put("Sharpness", "鋒利");
        TranslationManagerModel model = env.service.manager();
        assertSame(Page.LOADING, model.page());

        model.searchAsync("", false);
        model.refreshAsync();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while ((model.page().loading() || model.page().totalSaved() == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(model.page().loading());
        assertEquals(1, model.page().totalSaved());

        env.service.clearTranslations();
        assertSame(Page.LOADING, model.page(), "the list of cleared rows is dropped, not shown stale");
    }
}
