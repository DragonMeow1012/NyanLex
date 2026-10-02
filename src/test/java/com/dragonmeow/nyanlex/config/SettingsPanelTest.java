package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.warmup.ItemWarmupDriver;
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

class SettingsPanelTest {

    // ------------------------------------------------------------------ inline fakes

    private static final java.util.Map<String, JsonObject> LANGS = new java.util.HashMap<>();

    private static String lookup(String code, String key, Object... args) {
        try {
            if (!LANGS.containsKey(code)) {
                try (InputStream in = SettingsPanelTest.class.getResourceAsStream(
                        "/assets/nyanlex/lang/" + code + ".json")) {
                    assertNotNull(in);
                    LANGS.put(code, new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        JsonObject zhTw = LANGS.get(code);
        String text = zhTw.has(key) ? zhTw.get(key).getAsString() : key;
        for (Object a : args) text = text.replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(String.valueOf(a)));
        return text;
    }

    /** CJK / full-width glyphs 9 px, everything else 6 px (close to the Minecraft font). */
    static int width(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) w += ch >= 0x2E00 ? 9 : 6;
        return w;
    }

    private static final class FakeHost implements UiHost {
        final TranslatorConfig cfg = new TranslatorConfig();
        int saves;
        final List<SettingAction> actions = new ArrayList<>();
        final List<SettingEntry.SideEffect> effects = new ArrayList<>();
        final List<WarmupCommand> commands = new ArrayList<>();
        boolean closed;
        boolean allowToggle = true;
        WarmupStatus warm = new WarmupStatus(true, ItemWarmupDriver.State.IDLE,
                ItemWarmupDriver.PauseReason.NONE, 0, 0, 0, false);
        String clipboard = "";
        String lang = "zh_tw";

        @Override public TranslatorConfig config() { return cfg; }
        @Override public void saveConfig() { saves++; }
        @Override public String text(String key, Object... args) { return lookup(lang, key, args); }
        @Override public int textWidth(String text) { return width(text); }
        @Override public void runAction(SettingAction action) { actions.add(action); }
        @Override public void sideEffect(SettingEntry.SideEffect effect) { effects.add(effect); }
        @Override public boolean beforeToggle(SettingEntry entry) { return allowToggle; }
        @Override public WarmupStatus warmupStatus() { return warm; }
        @Override public void warmupCommand(WarmupCommand c) { commands.add(c); }
        final List<FileLocations.Entry> opened = new ArrayList<>();
        @Override public List<FileLocations.Entry> fileLocations() {
            return FileLocations.entries(java.nio.file.Path.of(
                    "C:/Users/SomeVeryLongUserName/AppData/Roaming/.minecraft/config"), "zh-TW");
        }
        @Override public void openFileLocation(FileLocations.Entry entry) { opened.add(entry); }
        @Override public String modVersion() { return "1.0.0"; }
        @Override public String clipboard() { return clipboard; }
        @Override public void close() { closed = true; }
        @Override public long nowMs() { return 0; }
    }

    private record Text(String s, int x, int y, int w, int[] clip) {
        boolean overlaps(Text o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + 9 && o.y < y + 9;
        }
    }

    private static final class Rec implements UiCanvas {
        final List<Text> texts = new ArrayList<>();
        int[] clip;
        final java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        @Override public void fill(int x, int y, int w, int h, int argb) { }
        @Override public void text(String text, int x, int y, int argb) {
            if (text.isEmpty()) return;
            texts.add(new Text(text, x, y, width(text), clip));
        }
        @Override public void pushClip(int x, int y, int w, int h) {
            if (clip != null) stack.push(clip);
            int[] n = {x, y, w, h};
            if (clip != null) {
                int nx = Math.max(x, clip[0]);
                int ny = Math.max(y, clip[1]);
                n = new int[] {nx, ny, Math.max(0, Math.min(x + w, clip[0] + clip[2]) - nx),
                        Math.max(0, Math.min(y + h, clip[1] + clip[3]) - ny)};
            } else {
                stack.push(new int[0]);
            }
            clip = n;
        }
        @Override public void popClip() {
            int[] prev = stack.pop();
            clip = prev.length == 0 ? null : prev;
        }
    }

    private static SettingsPanel panel(FakeHost host, int w, int h) {
        SettingsPanel p = new SettingsPanel(host, new SettingsPanel.State());
        p.resize(w, h);
        return p;
    }

    private static int cx(int[] r) { return r[0] + r[2] / 2; }
    private static int cy(int[] r) { return r[1] + r[3] / 2; }

    private static void click(SettingsPanel p, int[] r) {
        assertTrue(p.mouseClicked(cx(r), cy(r), 0));
        p.mouseReleased(cx(r), cy(r), 0);
    }

    // ------------------------------------------------------------------ layout

    private static final int[][] SIZES = {{320, 240}, {320, 270}, {427, 240}, {480, 270}, {256, 240},
            {240, 180}, {284, 160}, {640, 360}};

    @Test
    void layoutHasNoOverlapOrOverflowInEveryCategoryAndSize() {
        for (int[] size : SIZES) {
            for (String code : new String[] {"zh_tw", "en_us", "zh_cn"})
            for (SettingsCategory cat : SettingsCategory.values()) {
                for (String query : new String[] {"", "a", "聊天"}) {
                    FakeHost host = new FakeHost();
                    host.lang = code;
                    SettingsPanel.State st = new SettingsPanel.State();
                    for (SettingGroup g : groups()) st.expanded.add(g.id());
                    SettingsPanel p = new SettingsPanel(host, st);
                    p.resize(size[0], size[1]);
                    p.setCategory(cat);
                    if (!query.isEmpty()) p.setQuery(query);
                    String label = code + " " + size[0] + "x" + size[1] + " " + cat + " q=" + query;
                    check(p, label, size);
                }
            }
        }
    }

    private static List<SettingGroup> groups() {
        List<SettingGroup> out = new ArrayList<>();
        for (SettingsModel.Node n : SettingsModel.nodes(SettingsCategory.ADVANCED)) if (n.isGroup()) out.add(n.group());
        return out;
    }

    private void check(SettingsPanel p, String label, int[] size) {
        int[] panel = p.panelRect();
        assertTrue(panel[0] >= 0 && panel[1] >= 0 && panel[0] + panel[2] <= size[0]
                && panel[1] + panel[3] <= size[1], label + " panel inside screen");
        // rows: inside the list horizontally, ordered, no overlap
        int[] list = p.listRect();
        List<int[]> rects = p.rowRects();
        int prevBottom = Integer.MIN_VALUE;
        for (int[] r : rects) {
            assertTrue(r[0] >= list[0] && r[0] + r[2] <= list[0] + list[2], label + " row inside list width");
            assertTrue(r[1] >= prevBottom, label + " rows do not overlap");
            assertTrue(r[3] >= 12, label + " row height");
            prevBottom = r[1] + r[3];
        }
        Rec c = new Rec();
        p.render(c, -1, -1);
        for (Text t : c.texts) {
            int[] bound = t.clip() != null ? t.clip() : panel;
            assertTrue(t.x() >= bound[0] && t.x() + t.w() <= bound[0] + bound[2],
                    label + " text inside horizontally: '" + t.s() + "' " + t.x() + "+" + t.w()
                            + " in " + bound[0] + ".." + (bound[0] + bound[2]));
        }
        // texts sharing one clip (one card / header) must not collide
        for (int i = 0; i < c.texts.size(); i++) {
            for (int j = i + 1; j < c.texts.size(); j++) {
                Text a = c.texts.get(i);
                Text b = c.texts.get(j);
                if (a.clip() == null && b.clip() == null || a.clip() != null && b.clip() != null
                        && java.util.Arrays.equals(a.clip(), b.clip())) {
                    // list-wide clip texts of different cards never share an identical clip rect
                    assertFalse(a.overlaps(b), label + " texts overlap: '" + a.s() + "' / '" + b.s() + "'");
                }
            }
        }
        // sidebar entries stay inside the sidebar
        int[] sb = p.sidebarRect();
        for (Text t : c.texts) {
            if (t.clip() == null && t.x() >= sb[0] && t.x() < sb[0] + sb[2] && t.y() >= sb[1] && t.y() < sb[1] + sb[3]) {
                assertTrue(t.x() + t.w() <= sb[0] + sb[2], label + " sidebar text '" + t.s() + "'");
            }
        }
    }

    @Test
    void sidebarCollapsesWhenNarrow() {
        assertFalse(panel(new FakeHost(), 320, 240).narrowLayout());
        assertTrue(panel(new FakeHost(), 256, 240).narrowLayout());
        assertEquals(SettingsPanel.SIDEBAR_W_NARROW, panel(new FakeHost(), 256, 240).sidebarRect()[2]);
        assertEquals(SettingsPanel.SIDEBAR_W, panel(new FakeHost(), 427, 240).sidebarRect()[2]);
    }

    @Test
    void narrowSidebarShowsShortLabelsOnly() {
        SettingsPanel p = panel(new FakeHost(), 256, 240);
        Rec c = new Rec();
        p.render(c, -1, -1);
        List<String> shown = new ArrayList<>();
        for (Text t : c.texts) shown.add(t.s());
        assertTrue(shown.contains("完"), "short Done label");
        assertTrue(shown.contains("服務"));
        assertFalse(shown.contains("翻譯設定"), "no title in the collapsed sidebar");
        assertFalse(shown.stream().anyMatch(t -> t.startsWith("已翻譯")), "no counters in the collapsed sidebar");
    }

    // ------------------------------------------------------------------ controls

    @Test
    void clickingAToggleCardFlipsTheConfigAndRunsSideEffect() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        boolean before = host.cfg.translationRequestsEnabled;
        click(p, p.focusBounds("master", 0));
        assertEquals(!before, host.cfg.translationRequestsEnabled);
        assertEquals(1, host.saves);
        assertEquals(List.of(SettingEntry.SideEffect.CLEAR_PENDING), host.effects);
    }

    @Test
    void toggleCanBeVetoedByTheHost() {
        FakeHost host = new FakeHost();
        host.allowToggle = false;
        SettingsPanel p = panel(host, 427, 240);
        boolean before = host.cfg.translationRequestsEnabled;
        click(p, p.focusBounds("master", 0));
        assertEquals(before, host.cfg.translationRequestsEnabled);
        assertEquals(0, host.saves);
    }

    @Test
    void buttonCardRunsItsAction() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        click(p, p.controlBounds("language"));
        assertEquals(List.of(SettingAction.OPEN_LANGUAGE), host.actions);
        // clicking the card but not the button does nothing
        host.actions.clear();
        int[] card = p.cardBounds("language");
        p.mouseClicked(card[0] + 4, card[1] + 3, 0);
        assertTrue(host.actions.isEmpty());
    }

    @Test
    void fileLocationCardsListEveryFileAndOpenTheirOwnEntry() {
        FakeHost host = new FakeHost();
        SettingsPanel.State st = new SettingsPanel.State();
        st.expanded.add(SettingsModel.FILES_GROUP_ID);
        SettingsPanel p = new SettingsPanel(host, st);
        p.resize(480, 270);
        p.setCategory(SettingsCategory.ADVANCED);
        assertTrue(p.isExpanded(SettingsModel.FILES_GROUP_ID));
        for (String id : FileLocations.IDS) assertNotNull(SettingsModel.byId("file." + id), id);
        int[] ctl = p.controlBounds("file.ai_cache");
        assertNotNull(ctl, "AI cache card must be laid out");
        click(p, ctl);
        assertEquals(1, host.opened.size());
        assertEquals("ai_cache", host.opened.get(0).id());
        assertTrue(host.opened.get(0).path().toString().replace('\\', '/').endsWith("config/nyanlex-ai-cache-zh-tw.json"));
    }

    @Test
    void hoveringAShortenedPathShowsTheFullPath() {
        FakeHost host = new FakeHost();
        SettingsPanel.State st = new SettingsPanel.State();
        st.expanded.add(SettingsModel.FILES_GROUP_ID);
        SettingsPanel p = new SettingsPanel(host, st);
        p.resize(256, 240);
        p.setCategory(SettingsCategory.ADVANCED);
        int[] card = p.cardBounds("file.config");
        assertNotNull(card);
        for (int i = 0; i < 60 && (card[1] < p.listRect()[1] || card[1] + card[3] > p.listRect()[1] + p.listRect()[3]); i++) {
            p.keyPressed(SettingsPanel.KEY_DOWN, false, false);
            card = p.cardBounds("file.config");
        }
        Rec c = new Rec();
        p.render(c, -1, -1);
        String full = host.fileLocations().get(0).path().toString();
        assertTrue(c.texts.stream().noneMatch(t -> t.s().equals(full)), "narrow screen shortens the path");
        // hover the last text line of the card (the path line)
        Rec hovered = new Rec();
        p.render(hovered, card[0] + 20, card[1] + card[3] - 6);
        String joined = hovered.texts.stream().map(Text::s).reduce("", String::concat);
        assertTrue(joined.contains(full.substring(0, 10)) && joined.contains(full.substring(full.length() - 10)),
                "hover tooltip carries the complete path");
    }

    @Test
    void aboutCategoryIsJustTheVersionAndTheManualButton() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        p.setCategory(SettingsCategory.ABOUT);
        assertEquals(2, p.rowCount());
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("NyanLex Translator")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().contains("1.0.0")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("說明書")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("開啟")), "the button says 開啟");
        assertEquals(List.of(), host.actions);
        click(p, p.controlBounds("about_manual"));
        assertEquals(List.of(SettingAction.OPEN_MANUAL), host.actions);
    }

    @Test
    void onlineTranslationCardStatesTheThreeSentencesInEveryLanguage() {
        String[][] expect = {{"zh_tw", "送什麼", "送去哪", "怎麼停", "隱私說明"}, {"zh_hk", "送什麼", "送去哪", "怎麼停", "隱私說明"},
                {"zh_cn", "发送什么", "发送到哪", "怎么停", "隐私说明"}, {"en_us", "What is sent", "Where it goes", "How to stop", "Privacy"}};
        for (String[] e : expect) {
            FakeHost host = new FakeHost();
            host.lang = e[0];
            SettingsPanel p = panel(host, 427, 240);
            assertEquals(SettingsCategory.GENERAL, p.category());
            int[] card = p.cardBounds("master");
            assertNotNull(card, e[0]);
            Rec c = new Rec();
            p.render(c, -1, -1);
            String all = c.texts.stream().map(Text::s).reduce("", String::concat);
            assertTrue(all.contains(e[1]) && all.contains(e[2]) && all.contains(e[3]), e[0] + " says what, where, how to stop");
            assertTrue(c.texts.stream().anyMatch(t -> t.s().equals(e[4])), e[0] + " has the privacy button");
            int saves = host.saves;
            click(p, new int[] {card[0] + 2, card[1] + 2, 4, 4});
            assertEquals(saves, host.saves, "clicking the card's text changes nothing");
            assertTrue(host.actions.isEmpty());
        }
    }

    @Test
    void theOnlineTranslationCardHasOneSwitchAndAPrivacyButton() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        assertFalse(host.cfg.translationRequestsEnabled, "fresh install: nothing is sent");
        Rec off = new Rec();
        p.render(off, -1, -1);
        assertTrue(off.texts.stream().anyMatch(t -> t.s().equals("關：不會送出任何文字")), "grey status row on the card");
        assertTrue(off.texts.stream().anyMatch(t -> t.s().equals("隱私說明")));
        int[] sw = p.focusBounds("master", 0);
        click(p, sw);
        assertTrue(host.cfg.translationRequestsEnabled);
        assertEquals(List.of(SettingEntry.SideEffect.CLEAR_PENDING), host.effects);
        Rec on = new Rec();
        p.render(on, -1, -1);
        assertTrue(on.texts.stream().anyMatch(t -> t.s().startsWith("開：送往 ")), "green status row names the service");
        click(p, p.focusBounds("master", 0));
        assertFalse(host.cfg.translationRequestsEnabled);
        click(p, p.focusBounds("master", 1));
        assertEquals(List.of(SettingAction.OPEN_PRIVACY), host.actions);
        assertFalse(host.cfg.translationRequestsEnabled, "the privacy button never flips the switch");
    }

    @Test
    void theManualCoversEveryTopicInEveryLanguage() {
        for (String code : new String[] {"zh_tw", "zh_hk", "zh_cn", "en_us"}) {
            for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
                assertFalse(lookup(code, SettingsModel.manualTitleKey(i)).equals(SettingsModel.manualTitleKey(i)), code + " title " + i);
                assertFalse(lookup(code, SettingsModel.manualBodyKey(i)).equals(SettingsModel.manualBodyKey(i)), code + " body " + i);
            }
            String privacy = lookup(code, SettingsModel.manualBodyKey(6));
            assertTrue(privacy.contains("Google") && privacy.contains("API"), code);
            String last = lookup(code, SettingsModel.manualBodyKey(10));
            assertTrue(last.contains("MIT") && last.contains("github.com/DragonMeow1012/NyanLex"), code);
            // the old wording is gone for good
            for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
                String body = lookup(code, SettingsModel.manualBodyKey(i));
                assertFalse(body.contains("最底下的總開關") || body.contains("匯出／匯入翻譯檔可以把"), code + " " + i);
            }
        }
    }

    @Test
    void helpButtonNextToSearchOpensTheManualAndStaysWhereItWas() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        p.setCategory(SettingsCategory.DISPLAY);
        p.keyPressed(SettingsPanel.KEY_DOWN, false, false);
        int scroll = p.scroll();
        int[] s = p.searchRect();
        p.mouseClicked(s[0] + s[2] + 4 + 6, s[1] + 5, 0);
        assertEquals(SettingsCategory.DISPLAY, p.category(), "coming back from the manual lands on the same category");
        assertEquals(scroll, p.scroll(), "...at the same scroll position");
        assertEquals(List.of(SettingAction.OPEN_MANUAL), host.actions, "the ? button opens the manual straight away");
    }

    @Test
    void subscreenButtonsUseSettingsAndEditWording() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 480, 270);
        Rec general = new Rec();
        p.render(general, -1, -1);
        p.keyPressed(SettingsPanel.KEY_END, false, false);
        Rec general2 = new Rec();
        p.render(general2, -1, -1);
        assertTrue(general.texts.stream().anyMatch(t -> t.s().equals("開始")), "quick setup card button says 開始");
        assertTrue(general2.texts.stream().anyMatch(t -> t.s().equals("變更")), "language card button says 變更");
        assertTrue(general2.texts.stream().anyMatch(t -> t.s().equals("設定")), "keybind card button says 設定");
        assertFalse(general2.texts.stream().anyMatch(t -> t.s().equals("開啟")));
        p.setCategory(SettingsCategory.DISPLAY);
        p.keyPressed(SettingsPanel.KEY_END, false, false);
        Rec display = new Rec();
        p.render(display, -1, -1);
        assertTrue(display.texts.stream().anyMatch(t -> t.s().equals("編輯")), "do-not-translate card button says 編輯");
    }

    @Test
    void sliderDragSnapsToStepsAndSavesOnRelease() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 480, 270);
        p.setCategory(SettingsCategory.ADVANCED);
        int[] ctl = p.controlBounds("cooldown");
        int trackY = ctl[1] + SettingsPanel.LINE_H + 3 + 3;
        p.mouseClicked(ctl[0] + ctl[2] - 4, trackY, 0); // far right
        assertEquals(10000, host.cfg.requestCooldownMs);
        p.mouseDragged(ctl[0] + 4, trackY);
        assertEquals(0, host.cfg.requestCooldownMs);
        int mid = ctl[0] + 4 + (ctl[2] - 8) / 2;
        p.mouseDragged(mid, trackY);
        assertEquals(SettingsCatalog.COOLDOWN_STEPS[3], host.cfg.requestCooldownMs);
        int before = host.saves;
        p.mouseReleased(mid, trackY, 0);
        assertTrue(host.saves > before);
    }

    @Test
    void surfaceRowCyclesTheModeAndTogglesTheEngineWithoutGroupsOrDropdowns() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 480, 270);
        p.setCategory(SettingsCategory.DISPLAY);
        assertNotNull(p.cardBounds("chat"));
        assertNotNull(p.cardBounds("screen"));
        assertEquals(null, p.headerBounds("chat"), "no accordion groups on the display page");
        host.cfg.chatMode = DisplayMode.ORIGINAL_ONLY;
        click(p, p.buttonBounds("chat", 0));
        assertEquals(DisplayMode.BOTH, host.cfg.chatMode);
        click(p, p.buttonBounds("chat", 0));
        assertEquals(DisplayMode.TRANSLATION, host.cfg.chatMode);
        click(p, p.buttonBounds("chat", 0));
        assertEquals(DisplayMode.ORIGINAL_ONLY, host.cfg.chatMode, "原文 → 雙語 → 譯文 → 原文");
        assertEquals(3, host.saves);
        boolean before = host.cfg.aiChat;
        click(p, p.buttonBounds("chat", 1));
        assertEquals(!before, host.cfg.aiChat);
        assertEquals(4, host.saves);
        // the other surfaces are untouched
        assertFalse(host.cfg.aiTooltip);
        assertEquals(DisplayMode.TRANSLATION, host.cfg.tooltipMode);
    }

    @Test
    void surfaceButtonsShowTheCurrentModeAndEngine() {
        FakeHost host = new FakeHost();
        host.cfg.chatMode = DisplayMode.BOTH;
        host.cfg.aiChat = true;
        host.cfg.tooltipMode = DisplayMode.ORIGINAL_ONLY;
        SettingsPanel p = panel(host, 480, 270);
        p.setCategory(SettingsCategory.DISPLAY);
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("雙語")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("不翻譯")), "an untouched surface reads 不翻譯");
        assertFalse(c.texts.stream().anyMatch(t -> t.s().equals("原文")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("AI")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("機翻")));
    }

    @Test
    void allItemsRowShowsTheSharedValueOrMixedAndActsOnEverySurface() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 480, 270);
        p.setCategory(SettingsCategory.DISPLAY);
        assertNotNull(p.cardBounds("all_items"));
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("全部項目")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("一次設定下面所有項目")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("顯示方式")), "column header over the mode column");
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("翻譯服務")), "column header over the service column");
        // defaults: chat 雙語, the rest 譯文 -> the display columns differ -> 混合; every engine is 機翻
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("混合")));
        // pressing the mixed mode button: everything becomes 譯文
        click(p, p.buttonBounds("all_items", 0));
        assertAllModes(host.cfg, DisplayMode.TRANSLATION);
        // now they agree: the button follows 不翻譯 -> 雙語 -> 譯文 for all of them
        click(p, p.buttonBounds("all_items", 0));
        assertAllModes(host.cfg, DisplayMode.ORIGINAL_ONLY);
        click(p, p.buttonBounds("all_items", 0));
        assertAllModes(host.cfg, DisplayMode.BOTH);
        click(p, p.buttonBounds("all_items", 0));
        assertAllModes(host.cfg, DisplayMode.TRANSLATION);
        // engine: all 機翻 -> all AI -> all 機翻
        click(p, p.buttonBounds("all_items", 1));
        assertTrue(host.cfg.aiChat && host.cfg.aiTooltip && host.cfg.aiScoreboard && host.cfg.aiName
                && host.cfg.aiBossBar && host.cfg.aiTitle && host.cfg.aiActionBar && host.cfg.aiBook
                && host.cfg.aiScreenText);
        click(p, p.buttonBounds("all_items", 1));
        assertFalse(host.cfg.aiChat || host.cfg.aiTooltip || host.cfg.aiScoreboard || host.cfg.aiName
                || host.cfg.aiBossBar || host.cfg.aiTitle || host.cfg.aiActionBar || host.cfg.aiBook
                || host.cfg.aiScreenText);
        // a mixed engine state reads 混合 and pressing it sets everything to 機翻
        host.cfg.aiChat = true;
        host.cfg.aiBook = true;
        p.invalidate();
        Rec mixed = new Rec();
        p.render(mixed, -1, -1);
        assertEquals(1, mixed.texts.stream().filter(t -> t.s().equals("混合")).count(), "only the engine column is mixed now");
        click(p, p.buttonBounds("all_items", 1));
        assertFalse(host.cfg.aiChat || host.cfg.aiBook);
        assertEquals(7, host.saves);
        // the surface rows below keep their own buttons
        click(p, p.buttonBounds("chat", 1));
        assertTrue(host.cfg.aiChat);
        assertFalse(host.cfg.aiTooltip);
    }

    private static void assertAllModes(TranslatorConfig cfg, DisplayMode mode) {
        assertEquals(mode, cfg.chatMode);
        assertEquals(mode, cfg.tooltipMode);
        assertEquals(mode, cfg.scoreboardMode);
        assertEquals(mode, cfg.nameMode);
        assertEquals(mode, cfg.bossBarMode);
        assertEquals(mode, cfg.titleMode);
        assertEquals(mode, cfg.actionBarMode);
        assertEquals(mode, cfg.bookMode);
        assertEquals(mode, cfg.screenTextMode);
    }

    @Test
    void allItemsAndSurfaceRowsShareTheSameButtonColumns() {
        for (String code : new String[] {"zh_tw", "en_us", "zh_cn"}) {
            FakeHost host = new FakeHost();
            host.lang = code;
            SettingsPanel p = panel(host, 480, 270);
            p.setCategory(SettingsCategory.DISPLAY);
            int[] allMode = p.buttonBounds("all_items", 0);
            int[] allEngine = p.buttonBounds("all_items", 1);
            int[] chatMode = p.buttonBounds("chat", 0);
            int[] chatEngine = p.buttonBounds("chat", 1);
            assertEquals(chatMode[0], allMode[0], code + " mode column x");
            assertEquals(chatMode[2], allMode[2], code + " mode column width");
            assertEquals(chatEngine[0], allEngine[0], code + " engine column x");
            assertEquals(chatEngine[2], allEngine[2], code + " engine column width");
        }
    }

    @Test
    void filesGroupHeaderCollapsesAndExpandsAndSessionStateIsKept() {
        FakeHost host = new FakeHost();
        SettingsPanel.State st = new SettingsPanel.State();
        SettingsPanel p = new SettingsPanel(host, st);
        p.resize(480, 270);
        p.setCategory(SettingsCategory.ADVANCED);
        int collapsed = p.rowCount();
        assertFalse(p.isExpanded(SettingsModel.FILES_GROUP_ID));
        click(p, p.headerBounds(SettingsModel.FILES_GROUP_ID));
        assertTrue(p.isExpanded(SettingsModel.FILES_GROUP_ID));
        assertEquals(collapsed + FileLocations.IDS.size(), p.rowCount());
        SettingsPanel again = new SettingsPanel(host, st);
        again.resize(480, 270);
        assertEquals(SettingsCategory.ADVANCED, again.category());
        assertTrue(again.isExpanded(SettingsModel.FILES_GROUP_ID));
    }

    // ------------------------------------------------------------------ search

    private static void type(SettingsPanel p, String text) {
        for (char ch : text.toCharArray()) p.charTyped(ch);
    }

    @Test
    void searchBoxFiltersAcrossCategoriesAndKeepsHotkeysOut() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        assertFalse(p.isTyping());
        int[] s = p.searchRect();
        p.mouseClicked(s[0] + 10, s[1] + 5, 0);
        assertTrue(p.isTyping());
        type(p, "冷卻");
        assertTrue(p.isSearching());
        assertEquals("冷卻", p.query());
        assertEquals(1, p.rowCount());
        assertNotNull(p.cardBounds("cooldown"));
        // letters that are mod hotkeys are swallowed while typing
        assertTrue(p.keyPressed(71, false, false));
        assertTrue(p.isTyping());
        // backspace edits
        p.keyPressed(SettingsPanel.KEY_BACKSPACE, false, false);
        assertEquals("冷", p.query());
        // Escape clears, second Escape unfocuses, third is not consumed
        assertTrue(p.escape());
        assertEquals("", p.query());
        assertFalse(p.isSearching());
        assertTrue(p.escape());
        assertFalse(p.isTyping());
        assertFalse(p.escape());
    }

    @Test
    void searchResultsShowBreadcrumbAndEmptyNote() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        p.setQuery("引擎");
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.stream().anyMatch(t -> t.clip() != null && t.s().equals("顯示")), "breadcrumb");
        p.setQuery("zzzzqqq");
        Rec c2 = new Rec();
        p.render(c2, -1, -1);
        assertEquals(0, p.rowCount());
        assertTrue(c2.texts.stream().anyMatch(t -> t.s().contains("zzzzqqq")), "empty note");
    }

    @Test
    void slashAndCtrlFFocusSearchWithoutTypingTheSlash() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        assertTrue(p.keyPressed(47, false, false));
        assertTrue(p.isTyping());
        p.charTyped('/');
        assertEquals("", p.query());
        p.escape();
        p.escape();
        assertFalse(p.isTyping());
        assertTrue(p.keyPressed(70, true, false));
        assertTrue(p.isTyping());
    }

    @Test
    void pasteGoesIntoTheSearchBox() {
        FakeHost host = new FakeHost();
        host.clipboard = "cooldown\n";
        SettingsPanel p = panel(host, 427, 240);
        p.focusSearch();
        p.keyPressed(86, true, false);
        assertEquals("cooldown", p.query());
        assertTrue(p.rowCount() >= 1);
    }

    @Test
    void clickingACategoryClearsTheSearchAndSwitches() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        p.setQuery("聊天");
        click(p, p.categoryBounds(3));
        assertEquals(SettingsCategory.PACK, p.category());
        assertEquals("", p.query());
    }

    @Test
    void doneButtonClosesTheScreen() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 427, 240);
        click(p, p.doneBounds());
        assertTrue(host.closed);
    }

    @Test
    void noFirstUseHintIsEverShown() {
        for (String code : new String[] {"zh_tw", "zh_cn", "zh_hk", "en_us"}) {
            FakeHost host = new FakeHost();
            host.lang = code;
            SettingsPanel p = panel(host, 427, 240);
            Rec c = new Rec();
            p.render(c, -1, -1);
            assertTrue(c.texts.stream().noneMatch(t -> t.s().contains("第一次")
                    || t.s().contains("New here")), code);
            int[] s = p.searchRect();
            assertEquals(s[1] + s[3] + 4, p.listRect()[1], "the list starts right under the search box");
        }
    }

    // ------------------------------------------------------------------ scrolling

    @Test
    void wheelAndKeysScrollAndClamp() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 320, 240);
        p.setCategory(SettingsCategory.DISPLAY);
        assertTrue(p.contentHeight() > p.listRect()[3]);
        assertEquals(0, p.scroll());
        p.mouseScrolled(100, 100, -1);
        assertEquals(SettingsPanel.SCROLL_STEP, p.scroll());
        p.mouseScrolled(100, 100, 1);
        p.mouseScrolled(100, 100, 1);
        assertEquals(0, p.scroll());
        p.keyPressed(SettingsPanel.KEY_END, false, false);
        assertEquals(p.contentHeight() - p.listRect()[3], p.scroll());
        p.keyPressed(SettingsPanel.KEY_HOME, false, false);
        assertEquals(0, p.scroll());
        p.keyPressed(SettingsPanel.KEY_PAGE_DOWN, false, false);
        assertTrue(p.scroll() > 0);
        // scrollbar drag reaches the end
        int[] l = p.listRect();
        p.mouseClicked(l[0] + l[2] - 2, l[1] + 3, 0);
        p.mouseDragged(l[0] + l[2] - 2, l[1] + l[3] + 50);
        p.mouseReleased(0, 0, 0);
        assertEquals(p.contentHeight() - l[3], p.scroll());
    }

    @Test
    void scrollPositionIsRememberedPerCategory() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 320, 240);
        p.setCategory(SettingsCategory.DISPLAY);
        p.keyPressed(SettingsPanel.KEY_END, false, false);
        int saved = p.scroll();
        p.setCategory(SettingsCategory.GENERAL);
        assertEquals(0, p.scroll());
        p.setCategory(SettingsCategory.DISPLAY);
        assertEquals(saved, p.scroll());
    }

    // ------------------------------------------------------------------ warm-up card

    @Test
    void warmupCardStartsThroughTheHostAndShowsStateButtons() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 480, 270);
        p.setCategory(SettingsCategory.MINE);
        p.keyPressed(SettingsPanel.KEY_END, false, false);
        // idle: one start button
        assertNotNull(p.warmButtonBounds(0));
        assertEquals(null, p.warmButtonBounds(1));
        click(p, p.warmButtonBounds(0));
        assertEquals(List.of(WarmupCommand.START), host.commands);

        host.commands.clear();
        host.warm = new WarmupStatus(true, ItemWarmupDriver.State.RUNNING, ItemWarmupDriver.PauseReason.NONE, 120, 400, 30, false);
        p.invalidate();
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.stream().anyMatch(t -> t.s().contains("120 / 400 (30%)")));
        click(p, p.warmButtonBounds(0));
        assertEquals(List.of(WarmupCommand.PAUSE), host.commands);
        click(p, p.warmButtonBounds(1));
        click(p, p.warmButtonBounds(2));
        assertEquals(List.of(WarmupCommand.PAUSE, WarmupCommand.STOP, WarmupCommand.OPEN_PROGRESS), host.commands);

        host.commands.clear();
        host.warm = new WarmupStatus(true, ItemWarmupDriver.State.PAUSED, ItemWarmupDriver.PauseReason.USER, 120, 400, 30, false);
        click(p, p.warmButtonBounds(0));
        assertEquals(List.of(WarmupCommand.RESUME), host.commands);

        // automatic pause (left the world): no resume button, reason shown
        host.commands.clear();
        host.warm = new WarmupStatus(true, ItemWarmupDriver.State.PAUSED, ItemWarmupDriver.PauseReason.NO_WORLD, 120, 400, 30, false);
        Rec c2 = new Rec();
        p.render(c2, -1, -1);
        assertTrue(c2.texts.stream().anyMatch(t -> t.s().contains("尚未進入世界")));
        click(p, p.warmButtonBounds(0)); // pause button, disabled while not running
        assertTrue(host.commands.isEmpty());
    }

    @Test
    void warmupWithoutAiServiceLeadsToTheServiceCategoryInsteadOfADeadEnd() {
        FakeHost host = new FakeHost();
        host.warm = WarmupStatus.UNAVAILABLE;
        SettingsPanel p = panel(host, 480, 270);
        p.setCategory(SettingsCategory.MINE);
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.stream().anyMatch(t -> t.s().contains("需要 AI 翻譯服務")));
        assertTrue(c.texts.stream().anyMatch(t -> t.s().equals("改用 AI…")), "the start button becomes 改用 AI…");
        assertTrue(c.texts.stream().noneMatch(t -> t.s().equals("開始…")));
        click(p, p.warmButtonBounds(0));
        assertTrue(host.commands.isEmpty(), "nothing is started");
        assertEquals(SettingsCategory.SERVICE, p.category(), "it jumps to 翻譯服務");
    }

    @Test
    void warmupHudToggleCardIsInMyTranslations() {
        FakeHost host = new FakeHost();
        SettingsPanel p = panel(host, 480, 270);
        p.setCategory(SettingsCategory.MINE);
        p.keyPressed(SettingsPanel.KEY_END, false, false);
        assertTrue(host.cfg.itemWarmupHud);
        click(p, p.cardBounds("warmup_hud"));
        assertFalse(host.cfg.itemWarmupHud);
    }
}
