package com.dragonmeow.nyanlex.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manual screen (說明書) and the 全部項目 helpers of the catalog. */
class ManualPanelTest {

    private static JsonObject lang(String code) {
        try (InputStream in = ManualPanelTest.class.getResourceAsStream("/assets/nyanlex/lang/" + code + ".json")) {
            assertNotNull(in);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int width(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) w += ch >= 0x2E00 ? 9 : 6;
        return w;
    }

    private static final class Rec implements UiCanvas {
        final List<String> texts = new ArrayList<>();
        @Override public void fill(int x, int y, int w, int h, int argb) { }
        @Override public void text(String text, int x, int y, int argb) { texts.add(text); }
        @Override public void pushClip(int x, int y, int w, int h) { }
        @Override public void popClip() { }
    }

    private static ManualPanel panel(String code, int w, int h) {
        return panel(code, w, h, java.util.Map.of("R", "R", "P", "P", "G", "G", "S", ""));
    }

    private static ManualPanel panel(String code, int w, int h, java.util.Map<String, String> keys) {
        JsonObject l = lang(code);
        List<ManualPanel.Section> sections = new ArrayList<>();
        for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
            sections.add(new ManualPanel.Section(l.get(SettingsModel.manualTitleKey(i)).getAsString(),
                    l.get(SettingsModel.manualBodyKey(i)).getAsString()));
        }
        ManualPanel p = new ManualPanel(ManualPanelTest::width, l.get(SettingsModel.KEY_MANUAL_TITLE).getAsString(),
                l.get(SettingsModel.KEY_MANUAL_BACK).getAsString(), sections)
                .withTopButton(l.get(SettingsModel.KEY_MANUAL_SETUP_BTN).getAsString())
                .withChaptersLabel(l.get(SettingsModel.KEY_MANUAL_CHAPTERS).getAsString())
                .withKeys(keys, l.get(SettingsModel.KEY_KEY_UNSET).getAsString())
                .withNarration(l.get(SettingsModel.KEY_NARRATE_BUTTON).getAsString(),
                        l.get(SettingsModel.KEY_NARRATE_CHAPTER).getAsString());
        p.resize(w, h);
        return p;
    }

    private static final String[] CODES = {"zh_tw", "zh_hk", "zh_cn", "en_us"};

    @Test
    void theManualFitsEverySizeScrollsAndGoesBackByButtonOrEscape() {
        for (String code : CODES) {
            for (int[] size : new int[][] {{320, 240}, {427, 240}, {640, 360}, {256, 240}}) {
                ManualPanel p = panel(code, size[0], size[1]);
                String label = code + " " + size[0] + "x" + size[1];
                int[] box = p.panelRect();
                assertTrue(box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= size[0] && box[1] + box[3] <= size[1], label);
                assertEquals(SettingsModel.MANUAL_SECTIONS, p.headingTexts().size(), label + ": one heading per section (no wrapping)");
                assertTrue(p.contentHeight() > p.listHeight(), label + ": long enough to scroll");
                Rec c = new Rec();
                p.render(c, -1, -1);
                assertTrue(c.texts.contains(lang(code).get(SettingsModel.KEY_MANUAL_TITLE).getAsString()), label + " title");
                assertTrue(c.texts.contains(lang(code).get(SettingsModel.KEY_MANUAL_BACK).getAsString()), label + " back button");
                assertEquals(0, p.scroll());
                p.keyPressed(SettingsPanel.KEY_PAGE_DOWN);
                assertTrue(p.scroll() > 0);
                p.keyPressed(SettingsPanel.KEY_END);
                assertEquals(p.contentHeight() - p.listHeight(), p.scroll());
                p.keyPressed(SettingsPanel.KEY_HOME);
                assertEquals(0, p.scroll());
                p.mouseScrolled(-1);
                assertTrue(p.scroll() > 0);
                p.mouseScrolled(5);
                p.mouseScrolled(5);
                assertEquals(0, p.scroll());
                assertEquals(ManualPanel.BACK, p.keyPressed(SettingsPanel.KEY_ESCAPE));
                int[] back = p.backBounds();
                assertEquals(ManualPanel.BACK, p.mouseClicked(back[0] + 2, back[1] + 2, 0));
                assertEquals(ManualPanel.NONE, p.mouseClicked(box[0] + 2, box[1] + 2, 0));
                assertEquals(ManualPanel.NONE, p.keyPressed(257), "Enter with nothing focused does nothing");
            }
        }
    }

    @Test
    void theReadingColumnIsAboutTwentySixCharactersWideAndTheContentsStandOnTheLeftWhenThereIsRoom() {
        for (String code : CODES) {
            ManualPanel wide = panel(code, 427, 240);
            assertFalse(wide.isNarrow(), code);
            assertNotNull(wide.tocBounds(0), "a contents column on the left");
            int[] read = wide.readRect();
            assertTrue(wide.tocBounds(0)[0] + wide.tocBounds(0)[2] <= read[0], code + ": contents beside the reading column");
            assertTrue(wide.readableWidth() <= 26 * 9, code + ": at most about 26 CJK characters per line: " + wide.readableWidth());
            for (String line : wide.bodyTexts()) {
                assertTrue(width(line) <= wide.readableWidth(), code + " line too wide: " + line);
            }
            ManualPanel narrow = panel(code, 320, 240);
            assertTrue(narrow.isNarrow(), code);
            assertNull(narrow.tocBounds(0), "no contents column on a narrow screen");
            assertNotNull(narrow.dropdownBounds(), code + ": a chapter drop-down instead");
        }
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }

    private static void assertNull(Object o, String message) {
        org.junit.jupiter.api.Assertions.assertNull(o, message);
    }

    @Test
    void clickingAChapterJumpsToItAndTheChapterBeingReadIsLit() {
        ManualPanel p = panel("zh_tw", 640, 360);
        assertEquals(0, p.currentChapter());
        for (int chapter : new int[] {2, 5, 7}) {
            int[] row = p.tocBounds(chapter);
            assertEquals(ManualPanel.NONE, p.mouseClicked(row[0] + 4, row[1] + 4, 0));
            assertEquals(Math.min(p.chapterTop(chapter), p.contentHeight() - p.listHeight()), p.scroll(), "chapter " + chapter);
            assertEquals(chapter, p.currentChapter(), "the chapter that was jumped to is the lit one");
        }
        p.keyPressed(SettingsPanel.KEY_HOME);
        assertEquals(0, p.currentChapter());
        p.keyPressed(SettingsPanel.KEY_END);
        assertEquals(SettingsModel.MANUAL_SECTIONS - 1, p.currentChapter(), "at the end the last chapter is lit");
        p.keyPressed(SettingsPanel.KEY_HOME);
        for (int i = 0; i < 40; i++) p.keyPressed(SettingsPanel.KEY_DOWN);
        assertTrue(p.currentChapter() > 0, "scrolling moves the lit chapter along");
    }

    @Test
    void aNarrowScreenUsesAChapterDropDownThatEscapeClosesBeforeLeaving() {
        ManualPanel p = panel("zh_tw", 320, 240);
        int[] dd = p.dropdownBounds();
        p.mouseClicked(dd[0] + 4, dd[1] + 4, 0);
        assertTrue(p.dropdownIsOpen());
        int[] row = p.dropdownRowBounds(4);
        p.mouseClicked(row[0] + 4, row[1] + 4, 0);
        assertFalse(p.dropdownIsOpen());
        assertEquals(Math.min(p.chapterTop(4), p.contentHeight() - p.listHeight()), p.scroll());
        p.mouseClicked(dd[0] + 4, dd[1] + 4, 0);
        assertEquals(ManualPanel.NONE, p.keyPressed(SettingsPanel.KEY_ESCAPE), "Escape closes the list first");
        assertFalse(p.dropdownIsOpen());
        assertEquals(ManualPanel.BACK, p.keyPressed(SettingsPanel.KEY_ESCAPE), "and then leaves");
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.stream().anyMatch(t -> t.startsWith("章節")), "the drop-down says 章節: " + c.texts);
    }

    @Test
    void theTopButtonOpensTheQuickSetup() {
        for (String code : CODES) {
            ManualPanel p = panel(code, 427, 240);
            int[] top = p.topBounds();
            assertNotNull(top);
            assertEquals(ManualPanel.OPEN_QUICK_SETUP, p.mouseClicked(top[0] + 4, top[1] + 4, 0));
            assertEquals(lang(code).get(SettingsModel.KEY_MANUAL_SETUP_BTN).getAsString().isEmpty(), false);
        }
        Rec c = new Rec();
        panel("zh_tw", 427, 240).render(c, -1, -1);
        assertTrue(c.texts.contains("字太多不想看？讓設定小精靈幫你搞定"), c.texts.toString());
        // the very first control reached by Tab, Enter on it opens the quick setup
        ManualPanel p = panel("zh_tw", 427, 240);
        p.keyPressed(SettingsPanel.KEY_TAB);
        assertEquals(ManualPanel.OPEN_QUICK_SETUP, p.keyPressed(SettingsPanel.KEY_ENTER));
    }

    @Test
    void keyCapsShowTheBoundKeysAndUnboundOnesSayNotSet() {
        ManualPanel p = panel("zh_tw", 640, 360, java.util.Map.of("R", "R", "P", "Ctrl+P", "G", "G", "S", ""));
        assertEquals(List.of("R", "Ctrl+P", "G", "未設定"), p.keycapTexts(), "the key caps read the real bindings; 開啟設定 is not bound");
        String body = String.join("", p.bodyTexts());
        assertFalse(body.contains("{"), "no raw markers are left: " + body);
        assertTrue(body.contains("Ctrl+P"), "inline mentions use the bound key too");
        ManualPanel en = panel("en_us", 640, 360, java.util.Map.of("R", "Y", "P", "P", "G", "G", "S", "O"));
        assertEquals(List.of("Y", "P", "G", "O"), en.keycapTexts());
        assertFalse(String.join("", en.bodyTexts()).contains("{"));
    }

    @Test
    void opensAtAChapterAndTabWalksTheTopButtonTheChaptersAndBack() {
        JsonObject l = lang("zh_tw");
        List<ManualPanel.Section> sections = new ArrayList<>();
        for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
            sections.add(new ManualPanel.Section(l.get(SettingsModel.manualTitleKey(i)).getAsString(),
                    l.get(SettingsModel.manualBodyKey(i)).getAsString()));
        }
        ManualPanel p = new ManualPanel(ManualPanelTest::width, "說明書", "返回", sections)
                .withTopButton("開始設定").withNarration("%s，按鈕", "%s，章節")
                .openAt(SettingsModel.MANUAL_PRIVACY_SECTION - 1);
        p.resize(640, 360);
        assertEquals(Math.min(p.chapterTop(SettingsModel.MANUAL_PRIVACY_SECTION - 1), p.contentHeight() - p.listHeight()),
                p.scroll(), "opens on the privacy chapter");
        assertTrue(p.headingTexts().get(SettingsModel.MANUAL_PRIVACY_SECTION - 1).contains("隱私"));
        assertEquals("說明書", p.narration(), "nothing focused: the title");
        p.keyPressed(SettingsPanel.KEY_TAB);
        assertEquals("開始設定，按鈕", p.narration());
        assertTrue(p.consumeNarrationRequest());
        p.keyPressed(SettingsPanel.KEY_TAB);
        assertEquals("快速開始，章節", p.narration());
        for (int i = 1; i < SettingsModel.MANUAL_SECTIONS; i++) p.keyPressed(SettingsPanel.KEY_TAB);
        p.keyPressed(SettingsPanel.KEY_TAB);
        assertEquals("返回，按鈕", p.narration());
        assertEquals(ManualPanel.BACK, p.keyPressed(SettingsPanel.KEY_SPACE), "Space presses the framed button");
        p.keyPressed(SettingsPanel.KEY_TAB, true);
        p.keyPressed(SettingsPanel.KEY_TAB, true);
        p.keyPressed(SettingsPanel.KEY_ENTER); // a chapter: jump
        assertEquals(SettingsModel.MANUAL_SECTIONS - 1, p.currentChapter());
    }

    @Test
    void theLayoutIsNotRedoneWhileRendering() {
        ManualPanel p = panel("zh_tw", 427, 240);
        int before = p.layoutCount();
        Rec c = new Rec();
        for (int i = 0; i < 30; i++) {
            p.mouseScrolled(-1);
            p.render(c, i, i);
        }
        assertEquals(before, p.layoutCount());
    }

    @Test
    void theManualIsRewrittenInTheCurrentWordsInEveryLanguage() {
        String[][] wanted = {
                {"zh_tw", "快速設定", "線上翻譯", "翻譯包", "預先翻譯", "已存的翻譯", "翻譯服務", "非官方端點", "Codex 額度", "動作列", "MIT"},
                {"zh_hk", "快速設定", "線上翻譯", "翻譯包", "預先翻譯", "已存的翻譯", "翻譯服務", "非官方端點", "Codex 額度", "動作列", "MIT"},
                {"zh_cn", "快速设置", "在线翻译", "翻译包", "预先翻译", "已存的翻译", "翻译服务", "非官方端点", "Codex 额度", "动作栏", "MIT"},
                {"en_us", "Quick setup", "Online translation", "Translation packs", "Pre-translate", "saved translations",
                        "Translation service", "unofficial endpoint", "Codex quota", "action bar", "MIT"}};
        for (String[] row : wanted) {
            JsonObject l = lang(row[0]);
            StringBuilder all = new StringBuilder();
            for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
                all.append(l.get(SettingsModel.manualTitleKey(i)).getAsString()).append('\n')
                        .append(l.get(SettingsModel.manualBodyKey(i)).getAsString()).append('\n');
            }
            for (int i = 1; i < row.length; i++) assertTrue(all.toString().contains(row[i]), row[0] + " mentions " + row[i]);
            String text = all.toString();
            assertTrue(text.contains("{R}") && text.contains("{P}") && text.contains("{G}") && text.contains("{S}"), row[0] + " key markers");
        }
        JsonObject tw = lang("zh_tw");
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
            text.append(tw.get(SettingsModel.manualTitleKey(i)).getAsString()).append(tw.get(SettingsModel.manualBodyKey(i)).getAsString());
        }
        for (String old : new String[] {"倉庫", "預熱", "快取", "引擎", "懶人包", "總開關", "首次啟動卡", "分享", "最底下"}) {
            assertFalse(text.toString().contains(old), "the old word '" + old + "' is gone from the manual");
        }
    }

    @Test
    void allItemsHelpersCycleDetectMixedAndResolveFromMixed() {
        TranslatorConfig cfg = new TranslatorConfig();
        // defaults: chat 雙語, the others 譯文 -> mixed; all machine
        assertEquals(null, SettingsCatalog.commonMode(cfg), "mixed display");
        assertEquals(Boolean.FALSE, SettingsCatalog.commonEngine(cfg));
        SettingsCatalog.cycleAllModes(cfg); // from mixed -> everything 譯文
        assertEquals(DisplayMode.TRANSLATION, SettingsCatalog.commonMode(cfg));
        SettingsCatalog.cycleAllModes(cfg);
        assertEquals(DisplayMode.ORIGINAL_ONLY, SettingsCatalog.commonMode(cfg));
        SettingsCatalog.cycleAllModes(cfg);
        assertEquals(DisplayMode.BOTH, SettingsCatalog.commonMode(cfg));
        SettingsCatalog.cycleAllModes(cfg);
        assertEquals(DisplayMode.TRANSLATION, SettingsCatalog.commonMode(cfg));
        cfg.titleMode = DisplayMode.BOTH; // one differs again
        assertEquals(null, SettingsCatalog.commonMode(cfg));
        // engines
        SettingsCatalog.toggleAllEngines(cfg);
        assertEquals(Boolean.TRUE, SettingsCatalog.commonEngine(cfg));
        SettingsCatalog.toggleAllEngines(cfg);
        assertEquals(Boolean.FALSE, SettingsCatalog.commonEngine(cfg));
        cfg.aiName = true;
        assertEquals(null, SettingsCatalog.commonEngine(cfg), "one AI among machine items reads mixed");
        SettingsCatalog.toggleAllEngines(cfg); // from mixed -> everything machine
        assertEquals(Boolean.FALSE, SettingsCatalog.commonEngine(cfg));
        assertFalse(cfg.aiName);
        cfg.aiScreenText = true;
        cfg.aiChat = true;
        SettingsCatalog.toggleAllEngines(cfg);
        assertEquals(Boolean.FALSE, SettingsCatalog.commonEngine(cfg));
    }
}
