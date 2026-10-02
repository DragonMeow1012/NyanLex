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
        JsonObject l = lang(code);
        List<ManualPanel.Section> sections = new ArrayList<>();
        for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
            sections.add(new ManualPanel.Section(l.get(SettingsModel.manualTitleKey(i)).getAsString(),
                    l.get(SettingsModel.manualBodyKey(i)).getAsString()));
        }
        ManualPanel p = new ManualPanel(ManualPanelTest::width, l.get(SettingsModel.KEY_MANUAL_TITLE).getAsString(),
                l.get(SettingsModel.KEY_MANUAL_BACK).getAsString(), sections);
        p.resize(w, h);
        return p;
    }

    @Test
    void theManualScrollsShowsHeadingsAndGoesBackByButtonOrEscape() {
        for (String code : new String[] {"zh_tw", "zh_hk", "zh_cn", "en_us"}) {
            for (int[] size : new int[][] {{320, 240}, {427, 240}, {640, 360}}) {
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
                // keys and wheel scroll and clamp
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
                // back
                assertEquals(ManualPanel.BACK, p.keyPressed(SettingsPanel.KEY_ESCAPE));
                int[] back = p.backBounds();
                assertEquals(ManualPanel.BACK, p.mouseClicked(back[0] + 2, back[1] + 2, 0));
                assertEquals(ManualPanel.NONE, p.mouseClicked(box[0] + 2, box[1] + 2, 0));
                assertEquals(ManualPanel.NONE, p.keyPressed(257), "Enter does nothing");
            }
        }
    }

    @Test
    void theManualIsRewrittenForTheCurrentDesign() {
        String[] wanted = {"線上翻譯", "首次", "全部項目", "R", "P", "預熱", "倉庫", "非官方", "MIT"};
        JsonObject l = lang("zh_tw");
        StringBuilder all = new StringBuilder();
        for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
            all.append(l.get(SettingsModel.manualTitleKey(i)).getAsString()).append('\n')
                    .append(l.get(SettingsModel.manualBodyKey(i)).getAsString()).append('\n');
        }
        for (String w : wanted) assertTrue(all.toString().contains(w), w);
        assertFalse(all.toString().contains("分享"), "the share feature is gone from the text");
        assertFalse(all.toString().contains("原文＋譯文或"), "old mode wording");
        assertFalse(all.toString().contains("最底下"), "old master-switch wording");
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
