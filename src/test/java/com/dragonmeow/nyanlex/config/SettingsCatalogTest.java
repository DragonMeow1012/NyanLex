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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsCatalogTest {

    private static final List<String> LANGS = List.of("en_us", "zh_tw", "zh_cn", "zh_hk");

    private static JsonObject lang(String code) throws Exception {
        try (InputStream in = SettingsCatalogTest.class.getResourceAsStream(
                "/assets/nyanlex/lang/" + code + ".json")) {
            assertNotNull(in, "missing lang file " + code);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    private static int placeholders(String text) {
        Matcher m = Pattern.compile("%s").matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void hasSixPagesInDesignOrder() {
        assertEquals(List.of(SettingsPage.GENERAL, SettingsPage.DISPLAY, SettingsPage.SERVICE,
                SettingsPage.PACK, SettingsPage.MINE, SettingsPage.ADVANCED), SettingsCatalog.pages());
    }

    @Test
    void entryCountsPerPage() {
        assertEquals(5, SettingsCatalog.entries(SettingsPage.GENERAL).size(), "快速設定, 線上翻譯, 翻譯語言, 快捷鍵");
        assertEquals(18 + 1, SettingsCatalog.entries(SettingsPage.DISPLAY).size(), "nine surfaces with their service, plus 不翻譯詞彙");
        assertEquals(9, SettingsCatalog.rows(SettingsPage.DISPLAY).size());
        assertEquals(2, SettingsCatalog.entries(SettingsPage.SERVICE).size());
        assertEquals(2, SettingsCatalog.entries(SettingsPage.PACK).size(), "偵測並下載, 清除下載的翻譯包");
        assertEquals(5, SettingsCatalog.entries(SettingsPage.MINE).size());
        assertEquals(4, SettingsCatalog.entries(SettingsPage.ADVANCED).size());
    }

    @Test
    void idsAreUniqueAndKeysWellFormed() {
        Set<String> ids = new HashSet<>();
        for (SettingEntry e : SettingsCatalog.allEntries()) {
            assertTrue(ids.add(e.id()), "duplicate id " + e.id());
            assertTrue(e.labelKey().startsWith("nyanlex.settings."), e.labelKey());
            assertTrue(e.tipKey().startsWith("nyanlex.settings.") && e.tipKey().endsWith(".tip"), e.tipKey());
            assertNotNull(e.page());
            boolean needsAction = e.type() == SettingEntry.Type.SUBSCREEN || e.type() == SettingEntry.Type.ACTION;
            assertEquals(needsAction, e.action() != null, e.id());
        }
    }

    @Test
    void everyKeyExistsInAllFourHandWrittenLangFilesWithMatchingPlaceholders() throws Exception {
        List<String> keys = SettingsCatalog.allLangKeys();
        JsonObject reference = lang("zh_tw");
        for (String code : LANGS) {
            JsonObject json = lang(code);
            for (String key : keys) {
                assertTrue(json.has(key), code + " is missing " + key);
                String value = json.get(key).getAsString();
                assertFalse(value.isBlank(), code + " blank " + key);
                assertEquals(placeholders(reference.get(key).getAsString()), placeholders(value),
                        code + " placeholder count differs for " + key);
            }
        }
    }

    @Test
    void labelsWithStateCarryExactlyOnePlaceholder() throws Exception {
        JsonObject zh = lang("zh_tw");
        for (SettingEntry e : SettingsCatalog.allEntries()) {
            int expected = e.hasState() ? 1 : 0;
            assertEquals(expected, placeholders(zh.get(e.labelKey()).getAsString()), e.id());
        }
    }

    @Test
    void masterSwitchOnMeansRequestsAllowed() {
        TranslatorConfig cfg = new TranslatorConfig();
        SettingEntry master = SettingsCatalog.byId("master");
        assertFalse(cfg.translationRequestsEnabled, "fresh install: off");
        assertEquals(SettingsCatalog.STATE_OFF, master.state(cfg).key());
        assertEquals(SettingEntry.SideEffect.CLEAR_PENDING, master.sideEffect());
        master.press(cfg);
        assertTrue(cfg.translationRequestsEnabled);
        assertEquals(SettingsCatalog.STATE_ON, master.state(cfg).key());
        master.press(cfg);
        assertFalse(cfg.translationRequestsEnabled);
    }

    @Test
    void invertedFieldsShowPositiveWording() {
        TranslatorConfig cfg = new TranslatorConfig();
        SettingEntry fallback = SettingsCatalog.byId("ai_fallback");
        cfg.disableGoogleFallbackForAi = true; // fallback NOT allowed
        assertEquals(SettingsCatalog.STATE_OFF, fallback.state(cfg).key());
        fallback.press(cfg);
        assertFalse(cfg.disableGoogleFallbackForAi);
        assertEquals(SettingsCatalog.STATE_ON, fallback.state(cfg).key());
        assertNull(SettingsCatalog.byId("startup"), "the startup check no longer exists");
    }

    @Test
    void displayModeButtonCyclesOriginalBothTranslation() {
        TranslatorConfig cfg = new TranslatorConfig();
        SettingEntry chat = SettingsCatalog.byId("chat");
        cfg.chatMode = DisplayMode.ORIGINAL_ONLY;
        chat.press(cfg);
        assertEquals(DisplayMode.BOTH, cfg.chatMode);
        assertEquals(SettingsCatalog.STATE_BOTH, chat.state(cfg).key());
        chat.press(cfg);
        assertEquals(DisplayMode.TRANSLATION, cfg.chatMode);
        chat.press(cfg);
        assertEquals(DisplayMode.ORIGINAL_ONLY, cfg.chatMode);
    }

    @Test
    void everySurfaceRowPairsModeWithCompactEngineToggle() {
        for (SettingsRow row : SettingsCatalog.rows(SettingsPage.DISPLAY)) {
            assertEquals(SettingEntry.Type.CYCLE, row.primary().type());
            assertEquals(SettingEntry.Type.TOGGLE, row.secondary().type());
            assertTrue(row.secondary().compactInPair());
            assertTrue(row.secondary().id().endsWith(".engine"));
        }
        TranslatorConfig cfg = new TranslatorConfig();
        SettingEntry engine = SettingsCatalog.byId("tooltip.engine");
        assertFalse(cfg.aiTooltip);
        assertEquals(SettingsCatalog.STATE_MACHINE, engine.state(cfg).key());
        engine.press(cfg);
        assertTrue(cfg.aiTooltip);
        assertEquals(SettingsCatalog.STATE_AI, engine.state(cfg).key());
    }

    @Test
    void engineTogglesWriteTheirOwnSurfaceOnly() {
        TranslatorConfig cfg = new TranslatorConfig();
        SettingsCatalog.byId("screen.engine").press(cfg);
        assertTrue(cfg.aiScreenText);
        assertFalse(cfg.aiChat);
        assertFalse(cfg.aiTooltip);
        assertNull(SettingsCatalog.byId("screen_scan"), "the 介面掃描 card is gone: P uses the 介面 engine");
    }

    @Test
    void cooldownAndBatchCycleThroughStepsAndWrap() {
        TranslatorConfig cfg = new TranslatorConfig();
        SettingEntry cooldown = SettingsCatalog.byId("cooldown");
        cfg.requestCooldownMs = 0;
        for (int i = 1; i < SettingsCatalog.TIMING_STEPS.length; i++) {
            cooldown.press(cfg);
            assertEquals(SettingsCatalog.TIMING_STEPS[i], cfg.requestCooldownMs);
        }
        cooldown.press(cfg);
        assertEquals(0, cfg.requestCooldownMs);

        cfg.requestCooldownMs = 3500; // off-list value snaps up
        cooldown.press(cfg);
        assertEquals(4000, cfg.requestCooldownMs);

        SettingEntry batch = SettingsCatalog.byId("batch");
        cfg.batchWindowMs = 10000;
        batch.press(cfg);
        assertEquals(0, cfg.batchWindowMs);
        batch.press(cfg);
        assertEquals(1000, cfg.batchWindowMs);
    }

    @Test
    void millisStateFormatting() {
        assertEquals(SettingsCatalog.STATE_OFF, SettingsCatalog.millisState(0).key());
        StateText ten = SettingsCatalog.millisState(10000);
        assertEquals(SettingsCatalog.UNIT_SECONDS, ten.key());
        assertEquals(List.of("10"), ten.args());
        assertEquals(List.of("1.5"), SettingsCatalog.millisState(1500).args());
    }

    @Test
    void languageStateAndFixedProviderLine() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.followGameLanguage = true;
        cfg.targetLang = "zh-TW";
        StateText follow = SettingsCatalog.byId("language").state(cfg);
        assertEquals("config.nyanlex.language.follow", follow.key());
        assertEquals(List.of("zh-TW"), follow.args());
        cfg.followGameLanguage = false;
        StateText fixed = SettingsCatalog.byId("language").state(cfg);
        assertTrue(fixed.isLiteral());
        assertEquals("zh-TW", fixed.literalText());

        // Google is the only machine source: no picker entry, a fixed info card instead.
        assertNull(SettingsCatalog.byId("provider"));
        assertEquals(SettingCard.Kind.INFO, SettingsModel.byId(SettingsModel.PROVIDER_INFO_ID).kind());
    }

    @Test
    void actionsAndDestructiveMarking() {
        assertTrue(SettingsCatalog.byId("clear_cache").destructive());
        assertTrue(SettingsCatalog.byId("clear_packs").destructive());
        assertEquals(SettingAction.HUB_CLEAR, SettingsCatalog.byId("clear_packs").action());
        assertEquals(SettingsPage.PACK, SettingsCatalog.byId("clear_packs").page());
        assertEquals(SettingsPage.PACK, SettingsCatalog.byId("download").page());
        assertNull(SettingsCatalog.byId("open_repo"));
        assertFalse(SettingsCatalog.byId("export").destructive());
        assertEquals(SettingAction.OPEN_ITEM_WARMUP, SettingsCatalog.byId("warmup").action());
        assertEquals(SettingsPage.MINE, SettingsCatalog.byId("warmup").page());
        assertEquals(SettingsPage.DISPLAY, SettingsCatalog.byId("dnt").page());
        assertEquals(SettingsPage.ADVANCED, SettingsCatalog.byId("cooldown").page());
        assertEquals(SettingAction.OPEN_QUICK_SETUP, SettingsCatalog.byId("quick").action());
        assertNull(SettingsCatalog.byId("export").state(new TranslatorConfig()));
        // pressing a non-config entry never touches the config
        TranslatorConfig cfg = new TranslatorConfig();
        SettingsCatalog.byId("clear_cache").press(cfg);
        assertFalse(cfg.translationRequestsEnabled);
    }

    @Test
    void debugToggleDeclaresLogClearingSideEffect() {
        TranslatorConfig cfg = new TranslatorConfig();
        SettingEntry debug = SettingsCatalog.byId("debug");
        assertEquals(SettingEntry.SideEffect.CLEAR_DEBUG_LOG_WHEN_OFF, debug.sideEffect());
        debug.press(cfg);
        assertTrue(cfg.debugTranslationOverlay);
    }

    @Test
    void introFlagDefaultsAndNoShareEntryExists() {
        TranslatorConfig cfg = new TranslatorConfig();
        assertFalse(cfg.settingsIntroSeen);
        assertNull(SettingsCatalog.byId("share"), "the hub is download-only: no share toggle");
    }

    @Test
    void oldConfigWithRemovedShareFieldsStillLoadsAndTheStartupFlagIsIgnoredAndNeverWritten() {
        TranslatorConfig cfg = TranslatorConfig.fromReader(new java.io.StringReader(
                "{\"hubShareConsent\":true,\"hubIntroSeen\":true,\"hubStartupPromptDisabled\":true}"));
        assertFalse(cfg.hubStartupPromptDisabled, "removed fields are ignored when read");
        cfg.hubStartupPromptDisabled = true; // legacy glue may still set it in memory
        java.io.StringWriter out = new java.io.StringWriter();
        cfg.writeTo(out);
        assertFalse(out.toString().contains("hubStartupPromptDisabled"), "and it is never written back");
    }

    @Test
    void oldConfigWithTheRemovedScreenScanFieldStillLoadsAndKeepsTheScreenEngine() {
        TranslatorConfig cfg = TranslatorConfig.fromReader(new java.io.StringReader(
                "{\"aiScreenScan\":true,\"aiScreenText\":false,\"settingsIntroSeen\":true}"));
        assertFalse(cfg.aiScreenText, "the removed 介面掃描 field is ignored; P follows aiScreenText");
        assertTrue(cfg.settingsIntroSeen);
    }

    @Test
    void everySettingIdLivesOnExactlyOnePage() {
        Set<String> seen = new HashSet<>();
        for (SettingsPage page : SettingsPage.values()) {
            for (SettingEntry e : SettingsCatalog.entries(page)) {
                assertEquals(page, e.page(), e.id());
                assertTrue(seen.add(e.id()), "setting " + e.id() + " appears on more than one page");
            }
        }
    }

    @Test
    void engineButtonsHaveDistinctPerSurfaceLabelsForTheOneColumnLayout() {
        Set<String> labels = new HashSet<>();
        for (SettingEntry e : SettingsCatalog.entries(SettingsPage.DISPLAY)) {
            if (!e.id().endsWith(".engine")) continue;
            assertTrue(labels.add(e.labelKey()), "duplicate engine label " + e.labelKey());
            assertEquals("nyanlex.settings." + e.id(), e.labelKey());
        }
        assertEquals(9, labels.size());
    }
}
