package com.dragonmeow.nyanlex.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 快速設定 questionnaire against an inline fake of the game (no Minecraft types, no network). */
class QuickSetupPanelTest {

    private static final class FakeHost implements QuickSetupPanel.Host {
        final TranslatorConfig cfg = new TranslatorConfig();
        final DialogContent.Lang lang;
        boolean ai;
        int saves;
        int openAi;
        int openLanguage;
        int openKeys;
        int detections;
        int downloads;
        int closes;
        int onlineChanges;
        boolean appliedFollow;
        String appliedTag;
        int applyCalls;
        QuickSetupPanel.PackState packs = QuickSetupPanel.PackState.IDLE;
        boolean downloading;
        long now;

        FakeHost(String code) { lang = DialogPanelTest.lang(code); }

        @Override public TranslatorConfig config() { return cfg; }
        @Override public void saveConfig() { saves++; }
        @Override public String text(String key, Object... args) { return lang.get(key, args); }
        @Override public int textWidth(String text) { return DialogPanelTest.width(text); }
        @Override public boolean aiConfigured() { return ai; }
        @Override public void openAiSettings() { openAi++; }
        @Override public void openLanguagePicker(boolean followGame, String tag) { openLanguage++; }
        @Override public void openKeybinds() { openKeys++; }
        @Override public String gameLanguageName() { return "繁體中文"; }
        @Override public String languageName(String tag) { return "語言 " + tag; }
        @Override public String keyName(QuickSetupPanel.KeyAction action) {
            return switch (action) {
                case TRANSLATE_ITEM -> "R";
                case TRANSLATE_SCREEN -> "P";
                case TOGGLE -> "G";
            };
        }
        @Override public void applyLanguage(boolean followGame, String tag) {
            applyCalls++;
            appliedFollow = followGame;
            appliedTag = tag;
        }
        @Override public void onlineChanged() { onlineChanges++; }
        @Override public void startPackDetection() { detections++; }
        @Override public QuickSetupPanel.PackState packState() { return packs; }
        @Override public QuickSetupPanel.PackInfo packInfo() {
            return new QuickSetupPanel.PackInfo(2, "3.4 MB", List.of("ModA　2.0 MB", "ModB　1.4 MB"));
        }
        @Override public boolean packDownloading() { return downloading; }
        @Override public void startPackDownload() { downloads++; downloading = true; }
        @Override public void close() { closes++; }
        @Override public long nowMs() { return now; }
    }

    private static final class Rec implements UiCanvas {
        final List<String> texts = new ArrayList<>();
        @Override public void fill(int x, int y, int w, int h, int argb) { }
        @Override public void text(String text, int x, int y, int argb) { texts.add(text); }
        @Override public void pushClip(int x, int y, int w, int h) { }
        @Override public void popClip() { }
    }

    private static String json(TranslatorConfig cfg) {
        java.io.StringWriter w = new java.io.StringWriter();
        cfg.writeTo(w);
        return w.toString();
    }

    private static QuickSetupPanel panel(FakeHost host) {
        QuickSetupPanel p = new QuickSetupPanel(host);
        p.resize(640, 360);
        return p;
    }

    /** Clicks the button or option with this id, scrolling it into view first when the page is taller than the screen. */
    private static void press(QuickSetupPanel p, int id) {
        int[] r = p.dialog().buttonRect(id);
        assertNotNull(r, "no button " + id + " on " + p.page());
        int[] view = p.dialog().viewRect();
        for (int i = 0; i < 20 && (r[1] < view[1] || r[1] + r[3] > view[1] + view[3])
                && r[1] < 1000 && r[1] + r[3] <= view[1] + view[3] + 400 && !isFooter(p, id); i++) {
            p.mouseScrolled(view[0] + 2, view[1] + 2, r[1] < view[1] ? 1 : -1);
            r = p.dialog().buttonRect(id);
        }
        p.mouseClicked(r[0] + r[2] / 2, r[1] + r[3] / 2, 0);
    }

    private static boolean isFooter(QuickSetupPanel p, int id) {
        return id == QuickSetupPanel.ID_BACK || id == QuickSetupPanel.ID_NEXT || id == QuickSetupPanel.ID_DONE
                || id == QuickSetupPanel.ID_LATER || id == QuickSetupPanel.ID_START
                || id == QuickSetupPanel.ID_PACK_SKIP || id == QuickSetupPanel.ID_PACK_DOWNLOAD;
    }

    /** Button id of the display mode of grid row r (0 is 全部項目, then 聊天, 物品提示, ...). */
    private static int mode(int row) { return QuickSetupPanel.ID_GRID_BASE + 2 * row; }

    /** Button id of the translation service of grid row r. */
    private static int engine(int row) { return QuickSetupPanel.ID_GRID_BASE + 2 * row + 1; }

    private static void toDisplayPage(QuickSetupPanel p, int methodId) {
        toMethodPage(p);
        press(p, methodId);
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.DISPLAY, p.page());
    }

    /** Walks to the page with the way-to-translate question. */
    private static void toMethodPage(QuickSetupPanel p) {
        press(p, QuickSetupPanel.ID_START);
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.METHOD, p.page());
    }

    private static boolean anyChoiceSelectedOnMethodPage(QuickSetupPanel p) {
        return p.method() != null;
    }

    private static List<String> texts(QuickSetupPanel p) {
        return p.dialog().shownTexts();
    }

    // ------------------------------------------------------------------ nothing happens before 完成

    @Test
    void nothingIsSentSavedOrChangedBeforeDone() {
        FakeHost host = new FakeHost("zh_tw");
        String before = json(host.cfg);
        QuickSetupPanel p = panel(host);
        assertEquals(QuickSetupPanel.Page.WELCOME, p.page());
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.DISPLAY, p.page());
        press(p, mode(1)); // 聊天 display mode
        press(p, mode(0)); // 全部項目 display mode
        press(p, engine(3)); // 記分板 translation service
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.DONE, p.page(), "no packs: straight to the end");
        assertEquals(before, json(host.cfg), "the configuration is untouched until 完成");
        assertEquals(0, host.saves, "and nothing was saved");
        assertFalse(host.cfg.translationRequestsEnabled, "online translation is still off: no request can go out");
        assertEquals(0, host.applyCalls);
        assertEquals(0, host.closes);
        assertEquals(0, host.downloads);
        press(p, QuickSetupPanel.ID_DONE);
        assertTrue(host.cfg.translationRequestsEnabled, "完成 applies the answers");
        assertEquals(1, host.closes);
    }

    @Test
    void doneAppliesLanguageServiceAndDisplayAnswers() {
        FakeHost host = new FakeHost("zh_tw");
        host.cfg.aiChat = true;
        host.cfg.aiBook = true;
        QuickSetupPanel p = panel(host);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, mode(0)); // mixed (聊天 is 雙語) -> everything 譯文
        press(p, mode(0)); // -> everything 不翻譯
        press(p, mode(1)); // 聊天: 不翻譯 -> 雙語
        press(p, mode(1)); // -> 譯文
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_DONE);
        TranslatorConfig c = host.cfg;
        assertTrue(c.translationRequestsEnabled);
        assertFalse(c.aiChat || c.aiBook || c.aiTooltip || c.aiScreenText, "machine translation on every surface");
        assertEquals(DisplayMode.TRANSLATION, c.chatMode);
        for (DisplayMode m : List.of(c.tooltipMode, c.scoreboardMode, c.nameMode, c.bossBarMode, c.titleMode,
                c.actionBarMode, c.bookMode, c.screenTextMode)) {
            assertEquals(DisplayMode.ORIGINAL_ONLY, m);
        }
        assertTrue(c.firstRunDone);
        assertEquals(1, host.applyCalls);
        assertTrue(host.appliedFollow);
        assertEquals(1, host.onlineChanges);
        assertTrue(host.saves >= 1);
    }

    @Test
    void escapeAndSetUpLaterApplyNothingAndOnlyMarkTheQuestionnaireAnswered() {
        for (int mode = 0; mode < 2; mode++) {
            FakeHost host = new FakeHost("zh_tw");
            QuickSetupPanel p = panel(host);
            if (mode == 1) {
                toMethodPage(p);
                press(p, QuickSetupPanel.ID_METHOD_MACHINE);
            }
            String before = json(host.cfg);
            if (mode == 0) press(p, QuickSetupPanel.ID_LATER);
            else p.keyPressed(SettingsPanel.KEY_ESCAPE, false);
            TranslatorConfig expected = TranslatorConfig.fromReader(new java.io.StringReader(before));
            expected.firstRunDone = true;
            assertEquals(json(expected), json(host.cfg), "only firstRunDone changed");
            assertFalse(host.cfg.translationRequestsEnabled);
            assertEquals(0, host.applyCalls);
            assertEquals(1, host.closes);
            assertTrue(p.isFinished());
        }
    }

    // ------------------------------------------------------------------ the way-to-translate question

    @Test
    void theWayToTranslateQuestionStartsWithNothingChosenAndNextWaits() {
        FakeHost host = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(host);
        toMethodPage(p);
        assertNull(p.method(), "fresh start: none of the three is chosen");
        assertFalse(anyChoiceSelectedOnMethodPage(p));
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.METHOD, p.page(), "Next does nothing until an answer is picked");
        Rec c = new Rec();
        p.render(c, -1, -1);
        String all = String.join("", c.texts);
        assertTrue(all.contains("機翻") && all.contains("AI") && all.contains("先不要"));
        assertTrue(all.contains("物品和介面需要按快捷鍵翻譯（R 翻譯物品、P 翻譯整個畫面）"), all);
        assertTrue(all.contains("Google 翻譯（非官方端點）"));
        assertTrue(all.contains("Codex 額度"));
        assertTrue(all.contains("不會送出任何文字"));
    }

    @Test
    void fromSettingsTheMethodIsPrefilledOnlyWhileOnlineTranslationIsOn() {
        FakeHost off = new FakeHost("zh_tw");
        SettingsCatalog.setAllEngines(off.cfg, true);
        off.cfg.translationRequestsEnabled = false;
        off.cfg.firstRunDone = true;
        assertNull(panel(off).method(), "online translation is off: all three stay unchosen");

        FakeHost on = new FakeHost("zh_tw");
        on.cfg.translationRequestsEnabled = true;
        assertEquals(QuickSetupPanel.Method.MACHINE, panel(on).method());
        SettingsCatalog.setAllEngines(on.cfg, true);
        assertEquals(QuickSetupPanel.Method.AI, panel(on).method());

        FakeHost lang = new FakeHost("zh_tw");
        lang.cfg.followGameLanguage = false;
        lang.cfg.targetLang = "ja-JP";
        lang.cfg.chatMode = DisplayMode.TRANSLATION;
        lang.cfg.tooltipMode = DisplayMode.BOTH;
        lang.cfg.scoreboardMode = DisplayMode.BOTH;
        lang.cfg.nameMode = DisplayMode.BOTH;
        lang.cfg.bossBarMode = DisplayMode.BOTH;
        lang.cfg.titleMode = DisplayMode.BOTH;
        lang.cfg.actionBarMode = DisplayMode.BOTH;
        lang.cfg.bookMode = DisplayMode.BOTH;
        lang.cfg.screenTextMode = DisplayMode.BOTH;
        QuickSetupPanel q = panel(lang);
        assertFalse(q.followGame());
        assertEquals("ja-JP", q.tag());
        assertEquals(DisplayMode.TRANSLATION, q.draft().chatMode);
        assertEquals(DisplayMode.BOTH, q.draft().tooltipMode, "the display rows are prefilled with the current values");
        assertEquals(DisplayMode.BOTH, q.draft().screenTextMode);
    }

    @Test
    void choosingNotNowKeepsOnlineTranslationOff() {
        FakeHost fresh = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(fresh);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_NONE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_DONE);
        assertFalse(fresh.cfg.translationRequestsEnabled, "先不要: still off, nothing is sent");
        assertTrue(fresh.cfg.firstRunDone);

        FakeHost wasOn = new FakeHost("zh_tw");
        wasOn.cfg.translationRequestsEnabled = true;
        QuickSetupPanel q = panel(wasOn);
        toMethodPage(q);
        press(q, QuickSetupPanel.ID_METHOD_NONE);
        press(q, QuickSetupPanel.ID_NEXT);
        press(q, QuickSetupPanel.ID_NEXT);
        press(q, QuickSetupPanel.ID_DONE);
        assertFalse(wasOn.cfg.translationRequestsEnabled, "re-running and choosing 先不要 turns it off");
    }

    @Test
    void choosingAiOpensTheAiSettingsAndOnlyMovesOnWhenTheyAreComplete() {
        FakeHost host = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(host);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_AI);
        assertEquals(1, host.openAi, "straight to the AI settings, no extra page");
        assertNull(p.method(), "not chosen until the settings are complete");
        assertEquals(0, host.saves);

        host.ai = false; // came back without a key or a login
        p.aiSettingsClosed();
        assertEquals(QuickSetupPanel.Page.METHOD, p.page(), "back on the question");
        assertNull(p.method(), "none of the three is chosen");
        assertFalse(host.cfg.translationRequestsEnabled);

        press(p, QuickSetupPanel.ID_METHOD_AI);
        host.ai = true; // a key or a ChatGPT sign-in is there now
        p.aiSettingsClosed();
        assertEquals(QuickSetupPanel.Page.DISPLAY, p.page(), "complete: on to the next question");
        assertEquals(QuickSetupPanel.Method.AI, p.method());
        assertEquals(2, host.openAi);
        assertFalse(host.cfg.translationRequestsEnabled, "still nothing is on before 完成");
    }

    // ------------------------------------------------------------------ the translation pack page

    private static QuickSetupPanel.Page pageAfterDisplay(QuickSetupPanel.PackState state) {
        FakeHost host = new FakeHost("zh_tw");
        host.packs = state;
        QuickSetupPanel p = panel(host);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_NEXT);
        return p.page();
    }

    @Test
    void thePackPageOnlyAppearsWhenPacksWereFound() {
        assertEquals(QuickSetupPanel.Page.PACKS, pageAfterDisplay(QuickSetupPanel.PackState.FOUND));
        assertEquals(QuickSetupPanel.Page.DONE, pageAfterDisplay(QuickSetupPanel.PackState.NONE), "nothing found: skipped silently");
        assertEquals(QuickSetupPanel.Page.DONE, pageAfterDisplay(QuickSetupPanel.PackState.FAILED), "unreachable: skipped silently");
        assertEquals(QuickSetupPanel.Page.DONE, pageAfterDisplay(QuickSetupPanel.PackState.IDLE));
    }

    @Test
    void aPackCheckThatIsStillRunningShowsAShortWaitThenDecides() {
        FakeHost host = new FakeHost("zh_tw");
        host.packs = QuickSetupPanel.PackState.DETECTING;
        QuickSetupPanel p = panel(host);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.PACKS, p.page());
        assertTrue(texts(p).contains("正在檢查你安裝的模組…"), texts(p).toString());
        Rec c = new Rec();
        host.now = 1000;
        p.render(c, -1, -1);
        assertEquals(QuickSetupPanel.Page.PACKS, p.page(), "still waiting");
        host.packs = QuickSetupPanel.PackState.FOUND;
        p.render(c, -1, -1);
        assertEquals(QuickSetupPanel.Page.PACKS, p.page());
        assertTrue(String.join("", texts(p)).contains("你安裝的 2 個模組有現成的翻譯包（共 3.4 MB）"), texts(p).toString());

        FakeHost slow = new FakeHost("zh_tw");
        slow.packs = QuickSetupPanel.PackState.DETECTING;
        QuickSetupPanel q = panel(slow);
        toMethodPage(q);
        press(q, QuickSetupPanel.ID_METHOD_MACHINE);
        press(q, QuickSetupPanel.ID_NEXT);
        press(q, QuickSetupPanel.ID_NEXT);
        slow.now = QuickSetupPanel.PACK_WAIT_MS + 1;
        q.render(new Rec(), -1, -1);
        assertEquals(QuickSetupPanel.Page.DONE, q.page(), "timed out: treated as nothing found");

        FakeHost none = new FakeHost("zh_tw");
        none.packs = QuickSetupPanel.PackState.DETECTING;
        QuickSetupPanel r = panel(none);
        toMethodPage(r);
        press(r, QuickSetupPanel.ID_METHOD_MACHINE);
        press(r, QuickSetupPanel.ID_NEXT);
        press(r, QuickSetupPanel.ID_NEXT);
        none.packs = QuickSetupPanel.PackState.NONE;
        r.render(new Rec(), -1, -1);
        assertEquals(QuickSetupPanel.Page.DONE, r.page());
    }

    @Test
    void downloadingPacksStartsAtOnceWithoutWaitingForDoneAndSendsNoText() {
        FakeHost host = new FakeHost("zh_tw");
        host.packs = QuickSetupPanel.PackState.FOUND;
        QuickSetupPanel p = panel(host);
        String before = json(host.cfg);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_NONE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.PACKS, p.page());
        press(p, QuickSetupPanel.ID_PACK_DOWNLOAD);
        assertEquals(1, host.downloads, "the download starts now");
        assertEquals(QuickSetupPanel.Page.DONE, p.page(), "and the next page opens by itself");
        assertEquals(before, json(host.cfg), "the settings are still not touched");
        assertFalse(host.cfg.translationRequestsEnabled);
        assertTrue(texts(p).contains("翻譯包正在背景下載，完成後會通知你。"), texts(p).toString());
        host.downloading = false;
        p.render(new Rec(), -1, -1);
        assertFalse(texts(p).contains("翻譯包正在背景下載，完成後會通知你。"), "the note goes away when the download is over");
    }

    @Test
    void skippingThePacksShowsWhereToGetThemLater() {
        FakeHost host = new FakeHost("zh_tw");
        host.packs = QuickSetupPanel.PackState.FOUND;
        QuickSetupPanel p = panel(host);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_PACK_SKIP);
        assertEquals(QuickSetupPanel.Page.PACKS, p.page());
        assertTrue(texts(p).contains("好的，之後隨時可以到「翻譯設定 → 翻譯包」下載。"), texts(p).toString());
        assertEquals(0, host.downloads);
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.DONE, p.page());
    }

    // ------------------------------------------------------------------ language, end page, entry

    @Test
    void theLanguageQuestionOffersFollowAndAListAndStagesTheAnswer() {
        FakeHost host = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(host);
        press(p, QuickSetupPanel.ID_START);
        assertEquals(1, host.detections, "the pack check starts when the questions start, and only reads a public index");
        assertTrue(p.followGame());
        assertTrue(texts(p).contains("要把遊戲文字翻成哪種語言？"));
        assertTrue(p.dialog().shownLabels().contains("跟隨遊戲語言（繁體中文）"), p.dialog().shownLabels().toString());
        assertTrue(p.dialog().shownLabels().contains("選擇其他語言…"));
        press(p, QuickSetupPanel.ID_LANG_OTHER);
        assertEquals(1, host.openLanguage);
        p.languageChosen(false, "ja-JP");
        assertFalse(p.followGame());
        assertEquals("ja-JP", p.tag());
        assertEquals(0, host.applyCalls, "chosen, not applied");
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(QuickSetupPanel.Page.METHOD, p.page());
        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_DONE);
        assertFalse(host.appliedFollow);
        assertEquals("ja-JP", host.appliedTag);
    }

    @Test
    void theEndPageNamesTheKeysAndOffersTheKeyScreen() {
        for (String code : new String[] {"zh_tw", "zh_hk", "zh_cn", "en_us"}) {
            FakeHost host = new FakeHost(code);
            QuickSetupPanel p = panel(host);
            toMethodPage(p);
            press(p, QuickSetupPanel.ID_METHOD_MACHINE);
            press(p, QuickSetupPanel.ID_NEXT);
            press(p, QuickSetupPanel.ID_NEXT);
            assertEquals(QuickSetupPanel.Page.DONE, p.page());
            List<String> all = new ArrayList<>(texts(p));
            all.addAll(p.dialog().shownLabels());
            String joined = String.join("", all).replaceAll("\s", "");
            for (String key : new String[] {"key.item", "key.screen", "key.toggle", "rebind", "menu", "rebind_btn"}) {
                String expected = host.text("nyanlex.setup.done." + key).replaceAll("\s", "");
                assertTrue(joined.contains(expected), code + " " + key);
            }
            press(p, QuickSetupPanel.ID_REBIND);
            assertEquals(1, host.openKeys, code);
            p.refresh();
            assertEquals(QuickSetupPanel.Page.DONE, p.page(), "coming back from the key screen lands on the same page");
        }
        // zh_tw: keys read from the actual bindings
        FakeHost tw = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(tw);
        toMethodPage(p);
        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_NEXT);
        String page = String.join("", texts(p));
        assertTrue(page.contains("翻譯游標指向的物品；沒開任何畫面時，翻譯手上拿的物品"), page);
        assertTrue(page.contains("翻譯目前開啟的整個畫面"));
        assertTrue(page.contains("切換顯示原文或譯文"));
        assertTrue(page.contains("按鍵不順手？可以到「翻譯設定 → 一般 → 快捷鍵」改成你習慣的按鍵。"));
        assertTrue(page.contains("之後要調整其他設定：按 Esc → 選項 → 翻譯設定…"));
    }

    @Test
    void theFirstStartOpensOnlyWhileUnansweredAndOffAndEntriesPrefillFromTheSettings() {
        TranslatorConfig fresh = new TranslatorConfig();
        assertTrue(QuickSetupPanel.firstStartDue(fresh));
        fresh.firstRunDone = true;
        assertFalse(QuickSetupPanel.firstStartDue(fresh));
        TranslatorConfig online = new TranslatorConfig();
        online.translationRequestsEnabled = true;
        assertFalse(QuickSetupPanel.firstStartDue(online), "an existing user who already translates is never interrupted");
        assertFalse(QuickSetupPanel.firstStartDue(null));
    }

    // ------------------------------------------------------------------ layout, keys

    @Test
    void everyPageFitsEverySizeInEveryLanguage() {
        int[][] sizes = {{427, 240}, {320, 240}, {256, 240}, {640, 360}, {320, 180}};
        for (String code : new String[] {"zh_tw", "zh_hk", "zh_cn", "en_us"}) {
            for (int[] size : sizes) {
                FakeHost host = new FakeHost(code);
                host.packs = QuickSetupPanel.PackState.FOUND;
                QuickSetupPanel p = new QuickSetupPanel(host);
                p.resize(size[0], size[1]);
                List<QuickSetupPanel.Page> seen = new ArrayList<>();
                int guard = 0;
                while (guard++ < 12) {
                    seen.add(p.page());
                    String label = code + " " + size[0] + "x" + size[1] + " " + p.page();
                    int[] box = p.dialog().boxRect();
                    assertTrue(box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= size[0] && box[1] + box[3] <= size[1],
                            label + " box inside the screen " + java.util.Arrays.toString(box));
                    Rec c = new Rec();
                    p.render(c, -1, -1);
                    assertFalse(c.texts.isEmpty(), label);
                    int[] next = p.dialog().buttonRect(QuickSetupPanel.ID_NEXT);
                    int[] start = p.dialog().buttonRect(QuickSetupPanel.ID_START);
                    int[] done = p.dialog().buttonRect(QuickSetupPanel.ID_DONE);
                    int[] download = p.dialog().buttonRect(QuickSetupPanel.ID_PACK_DOWNLOAD);
                    if (p.page() == QuickSetupPanel.Page.METHOD && p.method() == null) {
                        press(p, QuickSetupPanel.ID_METHOD_MACHINE);
                        continue;
                    }
                    if (done != null) break;
                    press(p, start != null ? QuickSetupPanel.ID_START
                            : download != null ? QuickSetupPanel.ID_PACK_SKIP : QuickSetupPanel.ID_NEXT);
                    if (next == null && start == null && download == null) break;
                }
                assertTrue(seen.contains(QuickSetupPanel.Page.PACKS), code + " visited the pack page");
                assertTrue(seen.contains(QuickSetupPanel.Page.DONE) || p.page() == QuickSetupPanel.Page.DONE);
            }
        }
    }

    @Test
    void thePageStartsWithNothingFocusedTabFocusesAndEnterChoosesButNeverAdvances() {
        FakeHost host = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(host);
        assertEquals(-1, p.dialog().focusIndex(), "welcome: nothing focused");
        p.keyPressed(257, false);
        assertEquals(QuickSetupPanel.Page.WELCOME, p.page(), "Enter with nothing focused does nothing");
        press(p, QuickSetupPanel.ID_START);
        assertEquals(-1, p.dialog().focusIndex(), "a new page starts with nothing focused");
        p.keyPressed(SettingsPanel.KEY_TAB, false); // follow
        p.keyPressed(SettingsPanel.KEY_TAB, false); // other language
        p.keyPressed(SettingsPanel.KEY_TAB, false); // 上一步
        p.keyPressed(SettingsPanel.KEY_TAB, false); // 下一步
        p.keyPressed(SettingsPanel.KEY_ENTER, false);
        assertEquals(QuickSetupPanel.Page.METHOD, p.page());
        // on the method page: Tab to 機翻, Enter selects it but does not jump to the next question
        p.keyPressed(SettingsPanel.KEY_TAB, false);
        p.keyPressed(SettingsPanel.KEY_ENTER, false);
        assertEquals(QuickSetupPanel.Method.MACHINE, p.method());
        assertEquals(QuickSetupPanel.Page.METHOD, p.page(), "choosing never skips ahead by itself");
        assertTrue(p.dialog().focusIndex() >= 0, "the frame stays on the chosen option");
    }

    @Test
    void theNarratorHasSomethingToSayOnEveryPage() {
        FakeHost host = new FakeHost("zh_tw");
        host.packs = QuickSetupPanel.PackState.FOUND;
        QuickSetupPanel p = panel(host);
        assertEquals("歡迎使用 NyanLex Translator", p.narration());
        p.keyPressed(SettingsPanel.KEY_TAB, false);
        assertEquals("稍後再設定，按鈕", p.narration());
        assertTrue(p.consumeNarrationRequest());
        press(p, QuickSetupPanel.ID_START);
        p.keyPressed(SettingsPanel.KEY_TAB, false);
        assertTrue(p.narration().startsWith("跟隨遊戲語言（繁體中文）"), p.narration());
        assertTrue(p.narration().endsWith("，已選取"), p.narration());
    }

    @Test
    void everyQuestionnaireKeyExistsInAllFourLangFilesWithMatchingPlaceholders() {
        for (String code : new String[] {"zh_hk", "zh_cn", "en_us"}) {
            DialogContent.Lang reference = DialogPanelTest.lang("zh_tw");
            DialogContent.Lang other = DialogPanelTest.lang(code);
            for (String key : QuickSetupPanel.allLangKeys()) {
                String a = reference.get(key);
                String b = other.get(key);
                assertFalse(a.equals(key), "zh_tw is missing " + key);
                assertFalse(b.equals(key), code + " is missing " + key);
                assertEquals(a.split("%s", -1).length, b.split("%s", -1).length, code + " placeholders of " + key);
            }
        }
    }

    // ------------------------------------------------------------------ the display grid

    @Test
    void theDisplayPageHasTheSettingsRowsAndTheirTwoButtons() {
        FakeHost host = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(host);
        toDisplayPage(p, QuickSetupPanel.ID_METHOD_MACHINE);
        List<String> shown = texts(p);
        for (String name : List.of("全部項目", "聊天", "物品提示", "記分板", "名牌", "Boss 血條", "標題", "動作列", "書籍", "介面")) {
            assertTrue(shown.contains(name), name + " in " + shown);
        }
        assertTrue(shown.contains("顯示方式") && shown.contains("翻譯服務"), "column headers: " + shown);
        assertFalse(shown.stream().anyMatch(t -> t.contains("不翻譯詞彙")), "the do-not-translate words are not part of it");
        for (int row = 0; row <= 9; row++) {
            assertNotNull(p.dialog().buttonRect(mode(row)), "mode button of row " + row);
            assertNotNull(p.dialog().buttonRect(engine(row)), "service button of row " + row);
        }
        assertNull(p.dialog().buttonRect(mode(10)));
        assertTrue(shown.contains("翻譯聊天時，聊天內容（包含私訊）會送去翻譯。"), "the chat note stays: " + shown);
    }

    @Test
    void theDefaultsAreChatBothAndEverythingElseTranslationWithTheServiceOfThePageBefore() {
        FakeHost machine = new FakeHost("zh_tw");
        machine.cfg.aiTooltip = true;
        QuickSetupPanel p = panel(machine);
        toDisplayPage(p, QuickSetupPanel.ID_METHOD_MACHINE);
        TranslatorConfig d = p.draft();
        assertEquals(DisplayMode.BOTH, d.chatMode);
        assertEquals(DisplayMode.TRANSLATION, d.tooltipMode);
        assertEquals(Boolean.FALSE, SettingsCatalog.commonEngine(d), "machine translation everywhere");

        FakeHost ai = new FakeHost("zh_tw");
        ai.ai = true;
        QuickSetupPanel q = panel(ai);
        toMethodPage(q);
        press(q, QuickSetupPanel.ID_METHOD_AI);
        assertEquals(1, ai.openAi);
        q.aiSettingsClosed();
        assertEquals(QuickSetupPanel.Page.DISPLAY, q.page());
        assertEquals(Boolean.TRUE, SettingsCatalog.commonEngine(q.draft()), "AI everywhere");

        FakeHost none = new FakeHost("zh_tw");
        none.cfg.aiChat = true;
        QuickSetupPanel r = panel(none);
        toDisplayPage(r, QuickSetupPanel.ID_METHOD_NONE);
        assertEquals(Boolean.FALSE, SettingsCatalog.commonEngine(r.draft()), "先不要 shows machine translation");
        press(r, QuickSetupPanel.ID_NEXT);
        press(r, QuickSetupPanel.ID_DONE);
        assertTrue(none.cfg.aiChat, "but 先不要 does not change the services that are set");
        assertFalse(none.cfg.translationRequestsEnabled);
    }

    @Test
    void theButtonsChangeOnlyTheDraftAndDoneAppliesEveryRow() {
        FakeHost host = new FakeHost("zh_tw");
        QuickSetupPanel p = panel(host);
        toDisplayPage(p, QuickSetupPanel.ID_METHOD_MACHINE);
        String before = json(host.cfg);
        press(p, engine(2)); // 物品提示 -> AI
        press(p, mode(4)); // 名牌: 譯文 -> 不翻譯
        assertEquals(before, json(host.cfg), "the configuration is untouched while the rows change");
        assertEquals(0, host.saves);
        assertTrue(p.draft().aiTooltip);
        assertEquals(DisplayMode.ORIGINAL_ONLY, p.draft().nameMode);
        press(p, QuickSetupPanel.ID_NEXT);
        press(p, QuickSetupPanel.ID_DONE);
        assertTrue(host.cfg.aiTooltip);
        assertFalse(host.cfg.aiChat);
        assertEquals(DisplayMode.ORIGINAL_ONLY, host.cfg.nameMode);
        assertEquals(DisplayMode.BOTH, host.cfg.chatMode);
    }

    @Test
    void fromTheSettingsMixedServicesStayUntilTheWayOfTranslatingChanges() {
        FakeHost host = new FakeHost("zh_tw");
        host.cfg.translationRequestsEnabled = true;
        host.cfg.firstRunDone = true;
        host.cfg.aiChat = true;
        QuickSetupPanel p = panel(host);
        toMethodPage(p);
        assertEquals(QuickSetupPanel.Method.MACHINE, p.method(), "prefilled from the settings");
        press(p, QuickSetupPanel.ID_NEXT);
        assertTrue(p.draft().aiChat, "unchanged answer: the rows keep what they have");
        assertNull(SettingsCatalog.commonEngine(p.draft()));
        press(p, QuickSetupPanel.ID_BACK);
        press(p, QuickSetupPanel.ID_METHOD_NONE);
        press(p, QuickSetupPanel.ID_NEXT);
        assertEquals(Boolean.FALSE, SettingsCatalog.commonEngine(p.draft()), "a new answer sets every row");
    }

    @Test
    void theGridIsReachableOnAThreeTwentyByTwoFortyScreen() {
        FakeHost host = new FakeHost("zh_tw");
        QuickSetupPanel p = new QuickSetupPanel(host);
        p.resize(320, 240);
        toDisplayPage(p, QuickSetupPanel.ID_METHOD_MACHINE);
        int[] box = p.dialog().boxRect();
        assertTrue(box[1] >= 0 && box[1] + box[3] <= 240, "the card fits the screen");
        assertTrue(p.dialog().contentHeight() > p.dialog().viewRect()[3], "the grid is taller than the view and scrolls");
        press(p, engine(9)); // the last row, scrolled into view
        assertTrue(p.draft().aiScreenText);
    }

    @Test
    void theThreeWaysFitTheViewWithoutScrollingOnA240HighScreen() {
        for (int[] size : new int[][] {{427, 240}, {320, 240}}) {
            FakeHost host = new FakeHost("zh_tw");
            QuickSetupPanel p = new QuickSetupPanel(host);
            p.resize(size[0], size[1]);
            toMethodPage(p);
            assertTrue(p.dialog().contentHeight() <= p.dialog().viewRect()[3],
                    size[0] + "x" + size[1] + ": content " + p.dialog().contentHeight() + " view "
                            + p.dialog().viewRect()[3]);
        }
    }
}
