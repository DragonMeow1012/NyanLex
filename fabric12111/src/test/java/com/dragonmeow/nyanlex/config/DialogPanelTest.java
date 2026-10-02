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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** First-start card, consent box and the gate behind them (inline fakes only). */
class DialogPanelTest {

    private static DialogContent.Lang lang(String code) {
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

    private static final String[] LANGS = {"zh_tw", "zh_hk", "zh_cn", "en_us"};
    private static final int[][] SIZES = {{320, 240}, {427, 240}, {320, 240}, {640, 360}};

    private static List<DialogPanel.Content> allDialogs(String code) {
        DialogContent.Lang l = lang(code);
        TranslatorConfig cfg = new TranslatorConfig();
        List<DialogPanel.Content> out = new ArrayList<>();
        for (ConsentGate.Kind kind : ConsentGate.Kind.values()) out.add(DialogContent.consent(kind, cfg, l));
        out.add(DialogContent.firstRun(cfg, 0, "", l));
        out.add(DialogContent.firstRun(cfg, 12, "3.4 MB", l));
        return out;
    }

    @Test
    void everyDialogFitsEverySizeInEveryLanguageWithNoOverlap() {
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
                    List<int[]> rects = new ArrayList<>();
                    for (DialogPanel.Block b : content.blocks()) {
                        if (b instanceof DialogPanel.Row r) {
                            for (DialogPanel.Btn btn : r.buttons()) {
                                int[] rc = p.buttonRect(btn.id());
                                assertNotNull(rc, label + " " + btn.label());
                                assertTrue(rc[0] >= box[0] && rc[0] + rc[2] <= box[0] + box[2]
                                        && rc[1] >= box[1] && rc[1] + rc[3] <= box[1] + box[3], label + " button inside box");
                                for (int[] other : rects) {
                                    boolean overlap = rc[0] < other[0] + other[2] && other[0] < rc[0] + rc[2]
                                            && rc[1] < other[1] + other[3] && other[1] < rc[1] + rc[3];
                                    assertFalse(overlap, label + " buttons overlap");
                                }
                                rects.add(rc);
                            }
                        }
                    }
                    Rec c = new Rec();
                    p.render(c, -1, -1);
                    assertFalse(c.texts.isEmpty());
                }
            }
        }
    }

    @Test
    void nothingIsFocusedEnterDoesNothingAndEscapeCancels() {
        for (String code : LANGS) {
            DialogPanel consent = new DialogPanel(DialogPanelTest::width);
            consent.set(DialogContent.consent(ConsentGate.Kind.ITEM, new TranslatorConfig(), lang(code)));
            consent.resize(320, 240);
            assertEquals(DialogPanel.NONE, consent.keyPressed(257), "Enter must not start anything");
            assertEquals(DialogPanel.NONE, consent.keyPressed(335), "keypad Enter either");
            assertEquals(DialogPanel.NONE, consent.keyPressed(32), "space either");
            assertEquals(DialogContent.CONSENT_CANCEL, consent.keyPressed(SettingsPanel.KEY_ESCAPE));
            assertEquals(DialogPanel.NONE, consent.mouseClicked(0, 0, 0), "a click outside any button chooses nothing");
            int[] start = consent.buttonRect(DialogContent.CONSENT_START);
            int[] cancel = consent.buttonRect(DialogContent.CONSENT_CANCEL);
            assertEquals(DialogContent.CONSENT_START, consent.mouseClicked(start[0] + 3, start[1] + 3, 0));
            assertEquals(DialogContent.CONSENT_CANCEL, consent.mouseClicked(cancel[0] + 3, cancel[1] + 3, 0));
            assertEquals(DialogPanel.NONE, consent.mouseClicked(start[0] + 3, start[1] + 3, 1), "right click does nothing");

            DialogPanel first = new DialogPanel(DialogPanelTest::width);
            first.set(DialogContent.firstRun(new TranslatorConfig(), 3, "1 MB", lang(code)));
            first.resize(320, 240);
            assertEquals(DialogPanel.NONE, first.keyPressed(257));
            assertEquals(DialogContent.FIRST_LATER, first.keyPressed(SettingsPanel.KEY_ESCAPE), "Esc = not now");
        }
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    void consentNamesTheEngineAndMarksGoogleAsUnofficial() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiModel = "gemini-test";
        DialogContent.Lang tw = lang("zh_tw");
        assertTrue(DialogContent.engineName(cfg, false, tw).contains("Google"));
        assertTrue(DialogContent.engineName(cfg, false, tw).contains("非官方端點"));
        assertEquals("AI（gemini-test）", DialogContent.engineName(cfg, true, tw));
        assertEquals(1, countOf(DialogContent.engineName(cfg, false, tw), "非官方端點"), "marked once, not twice");
        for (String old : new String[] {"deepl_api", "microsoft_api", "youdao"}) {
            // a stale id from an old config still resolves to the one Google label
            cfg.machineTranslationProvider = old;
            assertEquals(1, countOf(DialogContent.engineName(cfg, false, tw), "非官方端點"), old);
        }
        cfg.machineTranslationProvider = "google";

        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.resize(427, 240);
        p.set(DialogContent.consent(ConsentGate.Kind.ITEM, cfg, tw));
        assertTrue(String.join("", p.shownTexts()).contains("這個物品的文字會送到"));
        p.set(DialogContent.consent(ConsentGate.Kind.SCREEN, cfg, tw));
        assertTrue(String.join("", p.shownTexts()).contains("這個畫面的文字會送到"));
        cfg.aiModel = "m";
        p.set(DialogContent.consent(ConsentGate.Kind.WARMUP, cfg, tw));
        String warm = String.join("", p.shownTexts());
        assertTrue(warm.contains("所有物品的名稱與說明會送到") && warm.contains("AI（m）"), warm);
        assertTrue(warm.contains("你可以隨時在設定關閉"));
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
        assertTrue(cfg.firstRunDone, "answering the box also settles the first-start card");
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

    @Test
    void firstRunCardShowsTheRepositoryLineOnlyWhenPacksWereFound() {
        DialogContent.Lang tw = lang("zh_tw");
        TranslatorConfig cfg = new TranslatorConfig();
        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.resize(427, 240);
        p.set(DialogContent.firstRun(cfg, 0, "", tw));
        assertNull(p.buttonRect(DialogContent.FIRST_HUB));
        assertNotNull(p.buttonRect(DialogContent.FIRST_MACHINE));
        assertNotNull(p.buttonRect(DialogContent.FIRST_AI));
        assertNotNull(p.buttonRect(DialogContent.FIRST_LATER));
        assertNotNull(p.buttonRect(DialogContent.FIRST_CHANGE));
        assertNotNull(p.buttonRect(DialogContent.FIRST_PRIVACY));
        p.set(DialogContent.firstRun(cfg, 12, "3.4 MB", tw));
        assertNotNull(p.buttonRect(DialogContent.FIRST_HUB));
        assertTrue(String.join("", p.shownTexts()).contains("倉庫有 12 個模組的翻譯包（共 3.4 MB）"));
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }
}
