package com.dragonmeow.nyanlex.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared card, the consent box and the gate behind it (inline fakes only). */
class DialogPanelTest {

    static DialogContent.Lang lang(String code) {
        JsonObject json;
        try (InputStream in = DialogPanelTest.class.getResourceAsStream("/assets/nyanlex/lang/" + code + ".json")) {
            assertNotNull(in);
            json = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return (key, args) -> {
            String text = json.has(key) ? json.get(key).getAsString() : key;
            for (Object a : args) text = text.replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(String.valueOf(a)));
            return text;
        };
    }

    static int width(String s) {
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

    private static final String[] LANGS = {"zh_tw", "ja_jp", "zh_cn", "en_us"};
    private static final int[][] SIZES = {{320, 240}, {427, 240}, {256, 240}, {640, 360}};

    private static List<DialogPanel.Content> allDialogs(String code) {
        DialogContent.Lang l = lang(code);
        TranslatorConfig cfg = new TranslatorConfig();
        List<DialogPanel.Content> out = new ArrayList<>();
        for (ConsentGate.Kind kind : ConsentGate.Kind.values()) out.add(DialogContent.consent(kind, cfg, l));
        cfg.aiUseCodex = true;
        for (ConsentGate.Kind kind : ConsentGate.Kind.values()) out.add(DialogContent.consent(kind, cfg, l));
        return out;
    }

    @Test
    void everyConsentBoxFitsEverySizeInEveryLanguageWithEqualFooterButtons() {
        for (String code : LANGS) {
            for (DialogPanel.Content content : allDialogs(code)) {
                for (int[] size : SIZES) {
                    DialogPanel p = new DialogPanel(DialogPanelTest::width);
                    p.set(content);
                    p.resize(size[0], size[1]);
                    String label = code + " " + size[0] + "x" + size[1] + " " + content.title();
                    int[] box = p.boxRect();
                    assertTrue(box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= size[0] && box[1] + box[3] <= size[1],
                            label + " box inside the screen: " + java.util.Arrays.toString(box));
                    int[] cancel = p.buttonRect(DialogContent.CONSENT_CANCEL);
                    int[] start = p.buttonRect(DialogContent.CONSENT_START);
                    assertNotNull(cancel, label);
                    assertNotNull(start, label);
                    assertEquals(cancel[2], start[2], label + " 取消 and 開始翻譯 are the same width");
                    assertTrue(cancel[0] < start[0], label + " 取消 on the left, the main action on the right");
                    assertEquals(cancel[1], start[1], label + " one row");
                    for (int[] rc : List.of(cancel, start)) {
                        assertTrue(rc[0] >= box[0] && rc[0] + rc[2] <= box[0] + box[2]
                                && rc[1] >= box[1] && rc[1] + rc[3] <= box[1] + box[3], label + " button inside box");
                    }
                    assertTrue(cancel[0] + cancel[2] <= start[0], label + " buttons do not overlap");
                    Rec c = new Rec();
                    p.render(c, -1, -1);
                    assertFalse(c.texts.isEmpty());
                }
            }
        }
    }

    @Test
    void nothingIsFocusedEnterDoesNothingEscapeCancelsAndTabWalksTheButtons() {
        for (String code : LANGS) {
            DialogPanel consent = new DialogPanel(DialogPanelTest::width);
            consent.set(DialogContent.consent(ConsentGate.Kind.ITEM, new TranslatorConfig(), lang(code)));
            consent.resize(320, 240);
            assertEquals(-1, consent.focusIndex(), code + ": no default focus");
            assertEquals(DialogPanel.NONE, consent.keyPressed(257, false), "Enter must not start anything");
            assertEquals(DialogPanel.NONE, consent.keyPressed(335, false), "keypad Enter either");
            assertEquals(DialogPanel.NONE, consent.keyPressed(32, false), "space either");
            assertEquals(DialogContent.CONSENT_CANCEL, consent.keyPressed(SettingsPanel.KEY_ESCAPE, false));
            assertEquals(DialogPanel.NONE, consent.mouseClicked(0, 0, 0), "a click outside any button chooses nothing");
            int[] start = consent.buttonRect(DialogContent.CONSENT_START);
            int[] cancel = consent.buttonRect(DialogContent.CONSENT_CANCEL);
            assertEquals(DialogContent.CONSENT_START, consent.mouseClicked(start[0] + 3, start[1] + 3, 0));
            assertEquals(DialogContent.CONSENT_CANCEL, consent.mouseClicked(cancel[0] + 3, cancel[1] + 3, 0));
            assertEquals(DialogPanel.NONE, consent.mouseClicked(start[0] + 3, start[1] + 3, 1), "right click does nothing");

            // Tab walks 取消 then 開始翻譯; Enter or Space presses the framed one
            consent.keyPressed(SettingsPanel.KEY_TAB, false);
            assertEquals(DialogContent.CONSENT_CANCEL, consent.keyPressed(257, false), "the first stop is the safe button");
            consent.keyPressed(SettingsPanel.KEY_TAB, false);
            assertEquals(DialogContent.CONSENT_START, consent.keyPressed(32, false));
            consent.keyPressed(SettingsPanel.KEY_TAB, true);
            assertEquals(DialogContent.CONSENT_CANCEL, consent.keyPressed(257, false), "Shift+Tab goes back");
        }
    }

    @Test
    void theNarratorDescribesTheFocusedButtonAndTheTitleWhenNothingIsFocused() {
        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.setNarration(DialogContent.narration(lang("zh_tw")));
        p.set(DialogContent.consent(ConsentGate.Kind.ITEM, new TranslatorConfig(), lang("zh_tw")));
        p.resize(320, 240);
        assertEquals("要開始線上翻譯嗎？", p.narration());
        assertFalse(p.consumeNarrationRequest());
        p.moveFocus(1);
        assertEquals("取消，按鈕", p.narration());
        assertTrue(p.consumeNarrationRequest(), "moving the focus asks the narrator to speak");
        assertFalse(p.consumeNarrationRequest());
        p.moveFocus(1);
        assertEquals("開始翻譯，按鈕", p.narration());
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    void consentNamesTheServiceTheTextGoesTo() {
        TranslatorConfig cfg = new TranslatorConfig();
        DialogContent.Lang tw = lang("zh_tw");
        assertEquals("Google 翻譯（非官方端點）", DialogContent.engineName(cfg, false, tw));
        assertEquals(1, countOf(DialogContent.engineName(cfg, false, tw), "非官方端點"), "marked once, not twice");
        for (String old : new String[] {"deepl_api", "microsoft_api", "youdao"}) {
            // a stale id from an old config still resolves to the one Google label
            cfg.machineTranslationProvider = old;
            assertEquals(1, countOf(DialogContent.engineName(cfg, false, tw), "非官方端點"), old);
        }
        cfg.machineTranslationProvider = "google";
        assertEquals("AI 服務（Gemini）", DialogContent.engineName(cfg, true, tw));
        cfg.aiBaseUrl = "https://api.openai.com/v1";
        assertEquals("AI 服務（OpenAI）", DialogContent.engineName(cfg, true, tw));
        cfg.aiBaseUrl = "https://api.deepseek.com";
        assertEquals("AI 服務（DeepSeek）", DialogContent.engineName(cfg, true, tw));
        cfg.aiBaseUrl = "http://127.0.0.1:11434/v1";
        assertEquals("AI 服務（127.0.0.1:11434）", DialogContent.engineName(cfg, true, tw));
        cfg.aiUseCodex = true;
        assertEquals("ChatGPT（使用你的 Codex 額度）", DialogContent.engineName(cfg, true, tw));
        cfg.aiUseCodex = false;
        cfg.aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";

        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.resize(427, 240);
        p.set(DialogContent.consent(ConsentGate.Kind.ITEM, cfg, tw));
        assertTrue(String.join("", p.shownTexts()).contains("這個物品的文字會送到 Google 翻譯（非官方端點）"));
        p.set(DialogContent.consent(ConsentGate.Kind.SCREEN, cfg, tw));
        assertTrue(String.join("", p.shownTexts()).contains("這個畫面的文字會送到"));
        p.set(DialogContent.consent(ConsentGate.Kind.WARMUP, cfg, tw));
        String warm = String.join("", p.shownTexts());
        assertTrue(warm.contains("勾選分類中的文字會送到 AI 服務（Gemini）"), warm);
        assertTrue(warm.contains("按下「開始翻譯」會開啟線上翻譯，之後隨時可以在設定裡關閉"), "the grey line says what the button does");
    }

    @Test
    void theLayoutIsComputedOncePerPageNotPerFrame() {
        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.set(DialogContent.consent(ConsentGate.Kind.ITEM, new TranslatorConfig(), lang("zh_tw")));
        p.resize(427, 240);
        int before = p.layoutCount();
        Rec c = new Rec();
        for (int i = 0; i < 20; i++) p.render(c, i, i);
        assertEquals(before, p.layoutCount(), "rendering never lays out again");
        p.resize(320, 240);
        assertEquals(before + 1, p.layoutCount(), "a resize does");
    }

    @Test
    void tallContentScrollsAndTheFocusedItemIsScrolledIntoView() {
        List<DialogPanel.Block> blocks = new ArrayList<>();
        blocks.add(new DialogPanel.Text("題目", 0));
        for (int i = 0; i < 6; i++) {
            blocks.add(new DialogPanel.Choice(10 + i, "選項 " + i, "這是一段說明文字，用來讓每個選項有兩行以上的高度，確保內容比畫面高。", false));
        }
        DialogPanel.Content content = new DialogPanel.Content("標題", 3, 1, blocks,
                DialogPanel.Footer.of(new DialogPanel.Btn(1, "上一步"), new DialogPanel.Btn(2, "下一步", true)), 1);
        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.set(content);
        p.resize(320, 240);
        int[] box = p.boxRect();
        assertTrue(box[1] + box[3] <= 240, "the box never grows past the screen");
        assertTrue(p.contentHeight() > p.viewRect()[3], "content is taller than its window");
        assertEquals(0, p.scrollOffset());
        for (int i = 0; i < 7; i++) p.moveFocus(1);
        assertTrue(p.scrollOffset() > 0, "tabbing to the last option scrolls it into view");
        int[] last = p.buttonRect(15);
        assertTrue(last[1] >= p.viewRect()[1] && last[1] + last[3] <= p.viewRect()[1] + p.viewRect()[3]);
        p.mouseScrolled(100, 100, 1);
        p.mouseScrolled(100, 100, 1);
        p.mouseScrolled(100, 100, 1);
        assertTrue(p.scrollOffset() >= 0);
        // the footer stays reachable at any scroll position
        int[] next = p.buttonRect(2);
        assertEquals(2, p.mouseClicked(next[0] + 2, next[1] + 2, 0));
    }

    @Test
    void choicesAreEquallyTallAndAListScrollsInsideItself() {
        List<DialogPanel.Block> blocks = new ArrayList<>();
        blocks.add(new DialogPanel.Choice(10, "甲", "短。", true));
        blocks.add(new DialogPanel.Choice(11, "乙", "這一段說明比較長，會佔用比較多行。這一段說明比較長，會佔用比較多行。這一段說明比較長，會佔用比較多行。", false));
        blocks.add(new DialogPanel.ListBox(List.of("a", "b", "c", "d", "e", "f", "g", "h"), 3));
        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.set(new DialogPanel.Content("標題", blocks, DialogPanel.Footer.of(null, new DialogPanel.Btn(2, "好")), 2));
        p.resize(427, 360);
        int[] a = p.buttonRect(10);
        int[] b = p.buttonRect(11);
        assertEquals(a[3], b[3], "all options of a page are one size");
        assertEquals(a[2], b[2]);
        assertTrue(b[1] >= a[1] + a[3], "stacked without overlap");
        Rec c = new Rec();
        p.render(c, -1, -1);
        assertTrue(c.texts.contains("a") && c.texts.contains("c") && !c.texts.contains("f"), "only three lines show");
        p.mouseScrolled(p.viewRect()[0] + 10, b[1] + b[3] + 20, -1);
        Rec after = new Rec();
        p.render(after, -1, -1);
        assertTrue(after.texts.contains("d") && !after.texts.contains("a"), "the wheel over the list scrolls the list: " + after.texts);
    }

    @Test
    void theGateSendsNothingBeforeConsentAndCompletesTheAskedActionExactlyOnceAfterIt() {
        TranslatorConfig cfg = new TranslatorConfig();
        AtomicInteger saves = new AtomicInteger();
        ConsentGate gate = new ConsentGate(() -> cfg, saves::incrementAndGet);
        AtomicInteger sent = new AtomicInteger();
        assertFalse(gate.request(ConsentGate.Kind.ITEM, sent::incrementAndGet), "off: parked, not run");
        assertEquals(0, sent.get());
        assertTrue(gate.hasPending());
        assertEquals(ConsentGate.Kind.ITEM, gate.pendingKind());
        assertFalse(cfg.translationRequestsEnabled);
        gate.confirm();
        assertEquals(1, sent.get(), "the asked action is completed once");
        assertTrue(cfg.translationRequestsEnabled);
        assertTrue(cfg.firstRunDone, "answering the box also settles the first-start questionnaire");
        assertTrue(saves.get() >= 1);
        gate.confirm();
        assertEquals(1, sent.get(), "a second confirm has nothing parked");
        // online now: later actions run straight away, no question
        assertTrue(gate.request(ConsentGate.Kind.SCREEN, sent::incrementAndGet));
        assertEquals(2, sent.get());
    }

    @Test
    void cancelSendsNothingAndKeepsTheSwitchOff() {
        TranslatorConfig cfg = new TranslatorConfig();
        ConsentGate gate = new ConsentGate(() -> cfg, () -> { });
        AtomicInteger sent = new AtomicInteger();
        gate.request(ConsentGate.Kind.WARMUP, sent::incrementAndGet);
        gate.cancel();
        gate.confirm(); // nothing parked any more: only turns the switch on, never replays the cancelled action
        assertEquals(0, sent.get());
        gate.cancel();
        TranslatorConfig again = new TranslatorConfig();
        ConsentGate g2 = new ConsentGate(() -> again, () -> { });
        g2.request(ConsentGate.Kind.SCREEN, sent::incrementAndGet);
        g2.cancel();
        assertEquals(0, sent.get());
        assertFalse(again.translationRequestsEnabled);
        assertFalse(g2.hasPending());
    }

    /** What the overlay does with the box and the gate: cancel sends nothing; start completes the asked action once. */
    private static void answer(DialogPanel panel, ConsentGate gate, int id) {
        if (id == DialogContent.CONSENT_START) gate.confirm();
        else if (id == DialogContent.CONSENT_CANCEL) gate.cancel();
    }

    @Test
    void cancellingTheBoxSendsNothingAndStartingItRunsTheAskedActionExactlyOnce() {
        for (String code : LANGS) {
            for (ConsentGate.Kind kind : ConsentGate.Kind.values()) {
                TranslatorConfig cfg = new TranslatorConfig();
                ConsentGate gate = new ConsentGate(() -> cfg, () -> { });
                AtomicInteger requests = new AtomicInteger();
                assertFalse(gate.request(kind, requests::incrementAndGet));
                DialogPanel p = new DialogPanel(DialogPanelTest::width);
                p.set(DialogContent.consent(kind, cfg, lang(code)));
                p.resize(320, 240);
                // Escape cancels: no request, the switch stays off
                answer(p, gate, p.keyPressed(SettingsPanel.KEY_ESCAPE, false));
                assertEquals(0, requests.get(), code + " " + kind + ": cancel sends nothing");
                assertFalse(cfg.translationRequestsEnabled);
                // asked again, this time Tab to 取消 then Enter: also nothing
                assertFalse(gate.request(kind, requests::incrementAndGet));
                p.set(DialogContent.consent(kind, cfg, lang(code)));
                p.moveFocus(1);
                answer(p, gate, p.keyPressed(SettingsPanel.KEY_ENTER, false));
                assertEquals(0, requests.get());
                // asked again: click 開始翻譯, then a second click on the same spot changes nothing more
                assertFalse(gate.request(kind, requests::incrementAndGet));
                p.set(DialogContent.consent(kind, cfg, lang(code)));
                int[] start = p.buttonRect(DialogContent.CONSENT_START);
                answer(p, gate, p.mouseClicked(start[0] + 2, start[1] + 2, 0));
                answer(p, gate, p.mouseClicked(start[0] + 2, start[1] + 2, 0));
                assertEquals(1, requests.get(), code + " " + kind + ": the asked action runs exactly once");
                assertTrue(cfg.translationRequestsEnabled);
            }
        }
    }

}
