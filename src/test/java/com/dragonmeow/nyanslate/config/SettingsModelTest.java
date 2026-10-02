package com.dragonmeow.nyanslate.config;

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

    private static final List<String> LANGS = List.of("en_us", "zh_tw", "zh_cn", "zh_hk");

    private static JsonObject lang(String code) throws Exception {
        try (InputStream in = SettingsModelTest.class.getResourceAsStream(
                "/assets/nyanslate/lang/" + code + ".json")) {
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
    void sevenCategoriesWithAboutLast() {
        assertEquals(7, SettingsModel.categories().size());
        assertEquals(SettingsCategory.ABOUT, SettingsModel.categories().get(6));
        assertNull(SettingsCategory.ABOUT.page());
    }

    @Test
    void everyCatalogEntryBecomesExactlyOneCard() {
        List<String> cardIds = ids(SettingsModel.allCards());
        assertEquals(cardIds.size(), new HashSet<>(cardIds).size(), "card ids are unique");
        // every entry once, plus the text-only info card under 關於
        assertEquals(SettingsCatalog.allEntries().size() + 1, cardIds.size());
        for (SettingEntry entry : SettingsCatalog.allEntries()) {
            assertNotNull(SettingsModel.byId(entry.id()), entry.id());
        }
    }

    @Test
    void cardKindsFollowTheEntries() {
        assertEquals(SettingCard.Kind.TOGGLE, SettingsModel.byId("master").kind());
        assertEquals(SettingCard.Kind.DROPDOWN, SettingsModel.byId("chat").kind());
        assertEquals(SettingCard.Kind.TOGGLE, SettingsModel.byId("chat.engine").kind());
        assertEquals(SettingCard.Kind.SLIDER, SettingsModel.byId("cooldown").kind());
        assertEquals(SettingCard.Kind.SLIDER, SettingsModel.byId("batch").kind());
        assertEquals(SettingCard.Kind.WARMUP, SettingsModel.byId("warmup").kind());
        assertEquals(SettingCard.Kind.BUTTON, SettingsModel.byId("language").kind());
        assertEquals(SettingCard.Kind.BUTTON, SettingsModel.byId("clear_cache").kind());
        assertEquals(SettingCard.Kind.INFO, SettingsModel.byId("about_info").kind());
        assertEquals(SettingsCategory.ABOUT, SettingsModel.byId("help").category());
        assertTrue(ids(SettingsModel.cards(SettingsCategory.GENERAL)).stream().noneMatch("help"::equals));
    }

    @Test
    void displayCategoryIsNineGroupsOfModeAndEngine() {
        List<SettingsModel.Node> nodes = SettingsModel.nodes(SettingsCategory.DISPLAY);
        assertEquals(9, nodes.size());
        for (SettingsModel.Node node : nodes) {
            assertTrue(node.isGroup());
            assertEquals(2, node.group().cards().size());
            assertEquals(SettingCard.Kind.DROPDOWN, node.group().cards().get(0).kind());
            assertEquals(SettingCard.Kind.TOGGLE, node.group().cards().get(1).kind());
            assertEquals(node.group().id(), node.group().cards().get(0).groupId());
        }
        assertEquals("chat", nodes.get(0).group().id());
        assertEquals("screen", nodes.get(8).group().id());
    }

    @Test
    void titlesDropTheStatePartAndEllipsis() throws Exception {
        Function<String, String> lang = zhTw();
        assertEquals("翻譯總開關", SettingsModel.title(SettingsModel.byId("master"), lang));
        assertEquals("全物品預熱", SettingsModel.title(SettingsModel.byId("warmup"), lang));
        assertEquals("清除快取", SettingsModel.title(SettingsModel.byId("clear_cache"), lang));
        assertEquals("引擎", SettingsModel.title(SettingsModel.byId("chat.engine"), lang));
        assertEquals("顯示方式", SettingsModel.title(SettingsModel.byId("chat"), lang));
        assertEquals("聊天", SettingsModel.groupTitle(SettingsModel.groupById("chat"), lang));
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
        // group title brings both cards of the group
        assertEquals(List.of("chat", "chat.engine"), ids(SettingsModel.search("聊天", lang)).stream()
                .filter(id -> id.equals("chat") || id.equals("chat.engine")).collect(Collectors.toList()));
        // every word must match
        assertEquals(List.of("chat.engine"), ids(SettingsModel.search("聊天 引擎", lang)).stream()
                .filter(id -> id.equals("chat") || id.equals("chat.engine")).collect(Collectors.toList()));
        // category name
        assertTrue(ids(SettingsModel.search("倉庫", lang)).containsAll(List.of("share", "startup", "download")));
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
        assertEquals(3, s.indexOf(3900));
        assertEquals(6, s.indexOf(99999));
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
        int n = 0;
        for (int i = s.indexOf("%s"); i >= 0; i = s.indexOf("%s", i + 2)) n++;
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
        com.dragonmeow.nyanslate.warmup.ItemWarmupDriver.State running =
                com.dragonmeow.nyanslate.warmup.ItemWarmupDriver.State.RUNNING;
        WarmupStatus st = new WarmupStatus(true, running,
                com.dragonmeow.nyanslate.warmup.ItemWarmupDriver.PauseReason.NONE, 50, 200, 10, false);
        WarmupHud.View v = WarmupHud.view(st, true, 0, lang);
        assertEquals(0.25f, v.fraction(), 1e-6);
        assertTrue(v.text().contains("50"));
        assertNull(WarmupHud.view(st, false, 0, lang));
        WarmupStatus paused = new WarmupStatus(true,
                com.dragonmeow.nyanslate.warmup.ItemWarmupDriver.State.PAUSED,
                com.dragonmeow.nyanslate.warmup.ItemWarmupDriver.PauseReason.NO_WORLD, 50, 200, 10, false);
        assertEquals(SettingsModel.KEY_WARMUP_HUD_PAUSED + "[50, 200]", WarmupHud.view(paused, true, 0, lang).text());
        WarmupStatus done = new WarmupStatus(true,
                com.dragonmeow.nyanslate.warmup.ItemWarmupDriver.State.DONE,
                com.dragonmeow.nyanslate.warmup.ItemWarmupDriver.PauseReason.NONE, 200, 200, 10, false);
        assertEquals(1f, WarmupHud.view(done, true, 100, lang).fraction());
        assertNull(WarmupHud.view(done, true, WarmupHud.DONE_SHOW_MS, lang));
        assertNull(WarmupHud.view(WarmupStatus.UNAVAILABLE, true, 0, lang));
    }
}
