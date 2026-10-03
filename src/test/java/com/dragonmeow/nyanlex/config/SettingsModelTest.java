package com.dragonmeow.nyanlex.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsModelTest {

    private static final List<String> LANGS = List.of("en_us", "zh_tw", "zh_cn", "ja_jp");

    private static JsonObject lang(String code) throws Exception {
        try (InputStream in = SettingsModelTest.class.getResourceAsStream(
                "/assets/nyanlex/lang/" + code + ".json")) {
            assertNotNull(in, "missing lang file " + code);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    private static Function<String, String> zhTw() throws Exception {
        JsonObject zh = lang("zh_tw");
        return key -> zh.has(key) ? zh.get(key).getAsString() : key;
    }

    private static List<String> ids(List<SettingCard> cards) {
        return cards.stream().map(SettingCard::id).collect(Collectors.toList());
    }

    @Test
    void legacyCategoryIdsMapToTheirNewHome() {
        assertEquals(SettingsCategory.SERVICE, SettingsCategory.fromId("ai"));
        assertEquals(SettingsCategory.PACK, SettingsCategory.fromId("hub"));
        assertEquals(SettingsCategory.ADVANCED, SettingsCategory.fromId("requests"));
        assertEquals(SettingsCategory.DISPLAY, SettingsCategory.fromId("Display"));
        assertEquals(SettingsCategory.GENERAL, SettingsCategory.fromId("no-such-category"));
        assertEquals(SettingsCategory.GENERAL, SettingsCategory.fromId(null));
        for (SettingsCategory c : SettingsCategory.values()) assertEquals(c, SettingsCategory.fromId(c.id()));
        SettingsPanel.rememberCategory("requests");
        assertEquals(SettingsCategory.ADVANCED, SettingsPanel.sessionState().category);
        SettingsPanel.rememberCategory(SettingsCategory.GENERAL);
    }

    @Test
    void sevenCategoriesWithAboutLast() {
        assertEquals(7, SettingsModel.categories().size());
        assertEquals(SettingsCategory.ABOUT, SettingsModel.categories().get(6));
        assertNull(SettingsCategory.ABOUT.page());
    }

    @Test
    void everyCatalogEntryBecomesExactlyOneCard() {
        List<String> cardIds = ids(SettingsModel.allCards());
        assertEquals(cardIds.size(), new HashSet<>(cardIds).size(), "card ids are unique");
        // every entry once (a display surface's engine entry rides on its surface card), plus the one
        // 全部項目 row, the fixed machine-service line, the three 關於 cards (version, manual, GitHub) and one FILE card per mod file
        int surfaces = SettingsCatalog.rows(SettingsPage.DISPLAY).size();
        assertEquals(SettingsCatalog.allEntries().size() - surfaces + 1 + 1 + 3
                + FileLocations.IDS.size(), cardIds.size());
        for (SettingCard card : SettingsModel.allCards()) {
            if (card.entry() != null && card.category().page() != null) {
                assertEquals(card.category().page(), card.entry().page(), card.id() + " sits on its own category");
            }
        }
        for (SettingEntry entry : SettingsCatalog.allEntries()) {
            if (entry.id().endsWith(".engine")) continue;
            assertNotNull(SettingsModel.byId(entry.id()), entry.id());
        }
    }

    @Test
    void cardKindsFollowTheEntries() {
        assertEquals(SettingCard.Kind.MASTER, SettingsModel.byId("master").kind());
        assertEquals(SettingCard.Kind.SURFACE, SettingsModel.byId("chat").kind());
        assertEquals("chat.engine", SettingsModel.byId("chat").engineEntry().id());
        assertEquals(List.of("quick", "master", "composer", "language", "keybind"),
                ids(SettingsModel.cards(SettingsCategory.GENERAL)), "一般: one 線上翻譯 card, no duplicate privacy card");
        assertEquals(SettingCard.Kind.BUTTON, SettingsModel.byId("quick").kind());
        assertNull(SettingsModel.byId("privacy"));
        assertEquals(SettingCard.Kind.ALL, SettingsModel.byId("all_items").kind());
        assertEquals(SettingCard.Kind.SLIDER, SettingsModel.byId("cooldown").kind());
        assertEquals(SettingCard.Kind.SLIDER, SettingsModel.byId("batch").kind());
        assertEquals(SettingCard.Kind.WARMUP, SettingsModel.byId("warmup").kind());
        assertEquals(SettingCard.Kind.BUTTON, SettingsModel.byId("language").kind());
        assertEquals(SettingCard.Kind.BUTTON, SettingsModel.byId("clear_cache").kind());
        assertEquals(SettingCard.Kind.INFO, SettingsModel.byId("about_info").kind());
        assertNull(SettingsModel.byId("help"), "no help card on 一般");
        assertNull(SettingsModel.byId("screen_scan"));
        // 關於 is just the version and the manual button
        assertEquals(List.of("about_info", "about_manual", "about_github"), ids(SettingsModel.cards(SettingsCategory.ABOUT)));
        assertEquals(SettingAction.OPEN_GITHUB, SettingsModel.byId("about_github").entry().action());
        assertEquals(SettingCard.Kind.BUTTON, SettingsModel.byId("about_manual").kind());
        assertEquals(SettingAction.OPEN_MANUAL, SettingsModel.byId("about_manual").entry().action());
    }

    @Test
    void displayCategoryIsOneAllItemsRowThenOneSurfaceRowEach() {
        List<SettingsModel.Node> nodes = SettingsModel.nodes(SettingsCategory.DISPLAY);
        assertEquals(1 + 9 + 1, nodes.size());
        for (SettingsModel.Node node : nodes) assertFalse(node.isGroup(), "no folding groups on the display page");
        assertEquals("all_items", nodes.get(0).card().id());
        assertEquals(SettingCard.Kind.ALL, nodes.get(0).card().kind());
        assertEquals("dnt", nodes.get(10).card().id(), "不翻譯詞彙 sits under the surfaces");
        for (int i = 1; i < 10; i++) {
            SettingCard card = nodes.get(i).card();
            assertEquals(SettingCard.Kind.SURFACE, card.kind());
            assertEquals(SettingEntry.Type.CYCLE, card.entry().type());
            assertEquals(SettingEntry.Type.TOGGLE, card.engineEntry().type());
        }
        assertEquals("chat", nodes.get(1).card().id());
        assertEquals("screen", nodes.get(9).card().id());
    }

    @Test
    void titlesDropTheStatePartAndEllipsis() throws Exception {
        Function<String, String> lang = zhTw();
        assertEquals("線上翻譯", SettingsModel.title(SettingsModel.byId("master"), lang));
        assertEquals("全內容預熱", SettingsModel.title(SettingsModel.byId("warmup"), lang));
        assertEquals("清除已存的翻譯", SettingsModel.title(SettingsModel.byId("clear_cache"), lang));
        assertEquals("快速設定", SettingsModel.title(SettingsModel.byId("quick"), lang));
        assertEquals("聊天", SettingsModel.title(SettingsModel.byId("chat"), lang));
        assertEquals("全部項目", SettingsModel.title(SettingsModel.byId("all_items"), lang));
        assertEquals("說明書", SettingsModel.title(SettingsModel.byId("about_manual"), lang));
        assertEquals("偵測並下載翻譯包", SettingsModel.title(SettingsModel.byId("download"), lang));
        assertEquals("檔案位置", SettingsModel.groupTitle(SettingsModel.groupById(SettingsModel.FILES_GROUP_ID), lang));
        for (SettingCard card : SettingsModel.allCards()) {
            String title = SettingsModel.title(card, lang);
            assertFalse(title.isBlank(), card.id());
            assertFalse(title.contains("%"), card.id());
        }
    }

    @Test
    void everyCardHasADescription() throws Exception {
        Function<String, String> lang = zhTw();
        for (SettingCard card : SettingsModel.allCards()) {
            assertFalse(SettingsModel.description(card, lang).isBlank(), card.id());
        }
    }

    @Test
    void searchMatchesTitleDescriptionCategoryAndKeywords() throws Exception {
        Function<String, String> lang = zhTw();
        assertEquals(List.of("cooldown"), ids(SettingsModel.search("請求冷卻", lang)));
        assertTrue(ids(SettingsModel.search("cooldown", lang)).contains("cooldown"));
        assertTrue(ids(SettingsModel.search("COOLDOWN", lang)).contains("cooldown"));
        // description text ("避免被服務限流")
        assertTrue(ids(SettingsModel.search("限流", lang)).contains("cooldown"));
        // a surface row is found by its name and, with "引擎", by its engine button too
        assertTrue(ids(SettingsModel.search("聊天", lang)).contains("chat"));
        assertTrue(ids(SettingsModel.search("聊天 引擎", lang)).contains("chat"));
        // 2026-10-03: every row's engine tip now says "only chat translates on its own", so the
        // word 聊天 no longer separates the chat row from the others -- a row name that the tip
        // does not repeat still does.
        assertTrue(ids(SettingsModel.search("記分板 引擎", lang)).contains("scoreboard"));
        assertFalse(ids(SettingsModel.search("記分板 引擎", lang)).contains("tooltip"));
        // the guide on 關於 is read, never listed as a search hit
        assertTrue(ids(SettingsModel.search("冷卻", lang)).stream().noneMatch(id -> id.startsWith("about_")));
        // the words of older builds still find the new cards (synonyms)
        assertTrue(ids(SettingsModel.search("倉庫", lang)).contains("download"));
        assertTrue(ids(SettingsModel.search("預熱", lang)).contains("warmup"));
        assertTrue(ids(SettingsModel.search("快取", lang)).contains("clear_cache"));
        assertTrue(ids(SettingsModel.search("引擎", lang)).containsAll(List.of("all_items", "chat", "screen")));
        assertTrue(ids(SettingsModel.search("請求", lang)).containsAll(List.of("master", "cooldown")));
        // the new words and the category names
        assertTrue(ids(SettingsModel.search("翻譯包", lang)).contains("download"));
        assertTrue(ids(SettingsModel.search("翻譯服務", lang)).contains("ai"));
        assertTrue(ids(SettingsModel.search("隱私", lang)).contains("master"));
        assertTrue(SettingsModel.search("   ", lang).isEmpty());
        assertTrue(SettingsModel.search(null, lang).isEmpty());
        assertTrue(SettingsModel.search("zzzzqqq", lang).isEmpty());
    }

    @Test
    void searchKeepsCategoryOrder() throws Exception {
        List<SettingCard> hits = SettingsModel.search("a", zhTw());
        int last = -1;
        for (SettingCard card : hits) {
            assertTrue(card.category().ordinal() >= last);
            last = card.category().ordinal();
        }
    }

    @Test
    void sliderSnapsToNearestStepAndWritesConfig() {
        SettingEntry.Slider s = SettingsCatalog.byId("cooldown").slider();
        assertEquals(0, s.min());
        assertEquals(10000, s.max());
        assertEquals(0, s.indexOf(-5));
        assertEquals(4, s.indexOf(3900));
        assertEquals(10, s.indexOf(99999));
        TranslatorConfig cfg = new TranslatorConfig();
        s.set().accept(cfg, 6000);
        assertEquals(6000, cfg.requestCooldownMs);
        assertEquals(6000, s.get().applyAsInt(cfg));
        SettingEntry.Slider batch = SettingsCatalog.byId("batch").slider();
        batch.set().accept(cfg, 3000);
        assertEquals(3000, cfg.batchWindowMs);
    }

    @Test
    void dropdownOptionsRoundTripEveryMode() {
        for (SettingsPage page : List.of(SettingsPage.DISPLAY)) {
            for (SettingsRow row : SettingsCatalog.rows(page)) {
                SettingEntry.Options o = row.primary().options();
                assertEquals(3, o.labels().size());
                TranslatorConfig cfg = new TranslatorConfig();
                for (int i = 0; i < 3; i++) {
                    o.select().accept(cfg, i);
                    assertEquals(i, o.index().applyAsInt(cfg), row.primary().id());
                }
            }
        }
        TranslatorConfig cfg = new TranslatorConfig();
        SettingsCatalog.byId("chat").options().select().accept(cfg, 2);
        assertEquals(DisplayMode.ORIGINAL_ONLY, cfg.chatMode);
        SettingsCatalog.byId("chat").options().select().accept(cfg, 1);
        assertEquals(DisplayMode.BOTH, cfg.chatMode);
    }

    @Test
    void togglePositionMatchesInvertedStorage() {
        TranslatorConfig cfg = new TranslatorConfig();
        SettingEntry fallback = SettingsCatalog.byId("ai_fallback");
        cfg.disableGoogleFallbackForAi = true;
        assertFalse(fallback.isOn(cfg));
        fallback.press(cfg);
        assertTrue(fallback.isOn(cfg));
        assertFalse(cfg.disableGoogleFallbackForAi);
        SettingEntry hud = SettingsCatalog.byId("warmup_hud");
        assertTrue(hud.isOn(cfg));
        hud.press(cfg);
        assertFalse(cfg.itemWarmupHud);
        assertTrue(SettingsCatalog.byId("master").isOn(new TranslatorConfig()) == new TranslatorConfig().translationRequestsEnabled);
    }

    @Test
    void everyUiKeyExistsInAllFourLangFilesWithMatchingPlaceholders() throws Exception {
        JsonObject reference = lang("zh_tw");
        for (String code : LANGS) {
            JsonObject lang = lang(code);
            for (String key : SettingsModel.allLangKeys()) {
                assertTrue(lang.has(key), code + " missing " + key);
                assertEquals(count(reference.get(key).getAsString()), count(lang.get(key).getAsString()),
                        code + " placeholder count for " + key);
            }
        }
    }

    private static int count(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([0-9]+[$])?s").matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void uiTextWrapsChineseAndLatinAndKeepsPunctuationOffLineStart() {
        java.util.function.ToIntFunction<String> w = s -> s.length() * 6;
        List<String> lines = UiText.wrap("一二三四五六七八九十，結束", 30, w);
        assertTrue(lines.size() >= 2);
        for (String line : lines) assertFalse(line.startsWith("，"));
        assertEquals("a bb", UiText.wrap("a bb ccc", 24, w).get(0));
        assertEquals(List.of("ab", "cd"), UiText.wrap("ab\ncd", 100, w));
        assertEquals("…", UiText.fit("abcdef", 6, w));
        assertEquals("abc…", UiText.fit("abcdef", 24, w));
        assertEquals("abc", UiText.fit("abc", 24, w));
        assertEquals("清除快取", SettingCard.stripState("清除快取…"));
        assertEquals("Warm all items", SettingCard.stripState("Warm all items..."));
        assertEquals("Debug overlay", SettingCard.stripState("Debug overlay: %s"));
    }

    @Test
    void uiTextNeverSplitsAnAsciiWordAndHardSplitsOversizedOnes() {
        java.util.function.ToIntFunction<String> w = s -> s.length() * 6;
        // "「AI」" style: the latin word moves to the next line whole
        List<String> lines = UiText.wrap("一二三四AI五六", 30, w);
        for (String line : lines) assertFalse(line.endsWith("A"), line);
        assertTrue(lines.stream().anyMatch(l -> l.contains("AI")));
        // one word longer than the line is cut by characters instead of overflowing
        List<String> cut = UiText.wrap("abcdefghijkl", 30, w);
        assertEquals(List.of("abcde", "fghij", "kl"), cut);
        for (String line : UiText.wrap("hello world wide web", 40, w)) assertTrue(w.applyAsInt(line) <= 40, line);
    }

    @Test
    void warmupHudViewsByState() {
        java.util.function.BiFunction<String, Object[], String> lang =
                (k, a) -> k + java.util.Arrays.toString(a);
        com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State running =
                com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.RUNNING;
        WarmupStatus st = new WarmupStatus(true, running,
                com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.PauseReason.NONE, 50, 200, 10, false);
        WarmupHud.View v = WarmupHud.view(st, true, 0, lang);
        assertEquals(0.25f, v.fraction(), 1e-6);
        assertTrue(v.text().contains("50"));
        assertNull(WarmupHud.view(st, false, 0, lang));
        WarmupStatus paused = new WarmupStatus(true,
                com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.PAUSED,
                com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.PauseReason.NO_WORLD, 50, 200, 10, false);
        assertEquals(SettingsModel.KEY_WARMUP_HUD_PAUSED + "[50, 200]", WarmupHud.view(paused, true, 0, lang).text());
        WarmupStatus done = new WarmupStatus(true,
                com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.DONE,
                com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.PauseReason.NONE, 200, 200, 10, false);
        assertEquals(1f, WarmupHud.view(done, true, 100, lang).fraction());
        assertNull(WarmupHud.view(done, true, WarmupHud.DONE_SHOW_MS, lang));
        assertNull(WarmupHud.view(WarmupStatus.UNAVAILABLE, true, 0, lang));
    }
}
