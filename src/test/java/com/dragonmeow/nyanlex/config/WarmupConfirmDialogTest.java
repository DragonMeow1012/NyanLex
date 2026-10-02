package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.warmup.ItemWarmupPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pre-translation confirmation card: every warning shows, long text scrolls, the buttons never move (inline fakes only). */
class WarmupConfirmDialogTest {

    private static final String[] LANGS = {"zh_tw", "zh_hk", "zh_cn", "en_us"};
    /** GUI-scaled sizes: 854x480 at scale 2, 640x480 at scale 2, a tiny window, and a large one. */
    private static final int[][] SIZES = {{427, 240}, {320, 240}, {256, 240}, {640, 360}, {284, 160}};

    private static ItemWarmupPlan plan() {
        return new ItemWarmupPlan(1332, 100, 1232, 800, 50_000, 1232, 9, 13_000, false, 20, 1);
    }

    private static final class Drawn implements UiCanvas {
        record Line(String text, int x, int y, int[] clip) {}
        final List<Line> lines = new ArrayList<>();
        int[] clip;
        @Override public void fill(int x, int y, int w, int h, int argb) { }
        @Override public void text(String text, int x, int y, int argb) { lines.add(new Line(text, x, y, clip)); }
        @Override public void pushClip(int x, int y, int w, int h) { clip = new int[] {x, y, w, h}; }
        @Override public void popClip() { clip = null; }
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static DialogPanel panel(DialogPanel.Content content, int[] size) {
        DialogPanel p = new DialogPanel(DialogPanelTest::width);
        p.set(content);
        p.resize(size[0], size[1]);
        return p;
    }

    private static String where(String code, int[] size) {
        return code + " " + size[0] + "x" + size[1];
    }

    @Test
    void allFiveWarningsAreInTheCardInEveryLanguageAndSize() {
        for (String code : LANGS) {
            DialogContent.Lang lang = DialogPanelTest.lang(code);
            DialogPanel.Content content = WarmupConfirmDialog.content(true,
                    new WarmupConfirmDialog.Scan(1332, 1332, 0), plan(), true, lang);
            for (int[] size : SIZES) {
                DialogPanel p = panel(content, size);
                String all = squash(String.join("", p.shownTexts()));
                for (int i = 1; i <= WarmupConfirmDialog.WARNING_COUNT; i++) {
                    String warning = squash(lang.get("screen.nyanlex.warmup.warn." + i));
                    assertTrue(all.contains(warning), where(code, size) + " warning " + i + " is in the card");
                }
                assertTrue(all.contains(squash(lang.get("screen.nyanlex.warmup.estimate", 1232, 9, "13K", 1))),
                        where(code, size) + " the estimate is in the card");
                assertTrue(all.contains(squash(lang.get("screen.nyanlex.warmup.summary", 1332, 100, 20, 1232))),
                        where(code, size) + " the summary is in the card");
            }
        }
    }

    @Test
    void theCardAndItsPinnedButtonsStayInsideTheScreenAndNeverSitOnTheText() {
        for (String code : LANGS) {
            DialogContent.Lang lang = DialogPanelTest.lang(code);
            DialogPanel.Content content = WarmupConfirmDialog.content(true,
                    new WarmupConfirmDialog.Scan(1332, 1332, 3), plan(), true, lang);
            for (int[] size : SIZES) {
                String label = where(code, size);
                DialogPanel p = panel(content, size);
                int[] box = p.boxRect();
                int[] view = p.viewRect();
                assertTrue(box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= size[0] && box[1] + box[3] <= size[1],
                        label + " card inside the screen: " + java.util.Arrays.toString(box));
                int[] cancel = p.buttonRect(WarmupConfirmDialog.CANCEL);
                int[] start = p.buttonRect(WarmupConfirmDialog.START);
                assertNotNull(cancel, label);
                assertNotNull(start, label);
                for (int[] rc : List.of(cancel, start)) {
                    assertTrue(rc[0] >= box[0] && rc[0] + rc[2] <= box[0] + box[2]
                            && rc[1] >= box[1] && rc[1] + rc[3] <= box[1] + box[3], label + " button inside the card");
                    assertTrue(rc[1] >= view[1] + view[3], label + " the button row is below the text window");
                }
                assertTrue(cancel[0] + cancel[2] <= start[0], label + " [取消] left of [開始], not overlapping");
                // everything that is drawn stays inside the card, and the text is clipped above the button row
                Drawn d = new Drawn();
                p.render(d, -1, -1);
                for (Drawn.Line line : d.lines) {
                    assertTrue(line.x() >= box[0] && line.x() + DialogPanelTest.width(line.text()) <= box[0] + box[2],
                            label + " line inside the card: " + line.text());
                    if (line.y() >= view[1] && line.y() < view[1] + view[3]) {
                        assertNotNull(line.clip(), label + " content lines are clipped: " + line.text());
                        assertTrue(line.clip()[1] + line.clip()[3] <= cancel[1],
                                label + " the clip ends above the buttons: " + line.text());
                    }
                }
            }
        }
    }

    @Test
    void whenTheWindowIsTooSmallTheCardScrollsToTheEndAndTheButtonsStayPut() {
        for (String code : LANGS) {
            DialogContent.Lang lang = DialogPanelTest.lang(code);
            DialogPanel.Content content = WarmupConfirmDialog.content(true,
                    new WarmupConfirmDialog.Scan(1332, 1332, 0), plan(), true, lang);
            for (int[] size : new int[][] {{320, 240}, {256, 240}, {284, 160}}) {
                String label = where(code, size);
                DialogPanel p = panel(content, size);
                int[] view = p.viewRect();
                assertTrue(p.contentHeight() > view[3], label + " needs scrolling");
                int[] startBefore = p.buttonRect(WarmupConfirmDialog.START);
                for (int i = 0; i < 80; i++) p.mouseScrolled(view[0] + 10, view[1] + 10, -1);
                assertEquals(p.contentHeight() - view[3], p.scrollOffset(), label + " scrolled to the end");
                assertEquals(startBefore[1], p.buttonRect(WarmupConfirmDialog.START)[1], label + " buttons did not move");
                // after scrolling to the end, the last paragraph's last line is on screen
                Drawn d = new Drawn();
                p.render(d, -1, -1);
                String lastWarning = lang.get("screen.nyanlex.warmup.warn.5");
                String tail = squash(lastWarning);
                StringBuilder seen = new StringBuilder();
                for (Drawn.Line line : d.lines) {
                    if (line.y() >= view[1] && line.y() + 9 <= view[1] + view[3] + 1 && line.clip() != null) seen.append(squash(line.text()));
                }
                assertTrue(seen.toString().contains(tail.substring(Math.max(0, tail.length() - 12))),
                        label + " the end of warning 5 is visible after scrolling: " + d.lines);
                // and back to the top: the first line (the summary) is on screen again
                for (int i = 0; i < 80; i++) p.mouseScrolled(view[0] + 10, view[1] + 10, 1);
                assertEquals(0, p.scrollOffset(), label);
                Drawn top = new Drawn();
                p.render(top, -1, -1);
                String summary = squash(lang.get("screen.nyanlex.warmup.summary", 1332, 100, 20, 1232));
                assertTrue(squash(top.lines.get(1).text()).length() > 0
                        && summary.startsWith(squash(top.lines.get(1).text())), label + " the summary starts the card");
            }
        }
    }

    @Test
    void startIsOnlyPressableWhenThereIsSomethingToTranslateOnTheAiService() {
        DialogContent.Lang lang = DialogPanelTest.lang("zh_tw");
        int[] size = {427, 240};
        // scanning: no plan yet
        DialogPanel scanning = panel(WarmupConfirmDialog.content(true, new WarmupConfirmDialog.Scan(40, 1332, 0),
                null, false, lang), size);
        int[] start = scanning.buttonRect(WarmupConfirmDialog.START);
        assertEquals(DialogPanel.NONE, scanning.mouseClicked(start[0] + 2, start[1] + 2, 0), "start is greyed out while scanning");
        int[] cancel = scanning.buttonRect(WarmupConfirmDialog.CANCEL);
        assertEquals(WarmupConfirmDialog.CANCEL, scanning.mouseClicked(cancel[0] + 2, cancel[1] + 2, 0));
        assertEquals(WarmupConfirmDialog.CANCEL, scanning.keyPressed(SettingsPanel.KEY_ESCAPE, false));
        assertTrue(String.join("", scanning.shownTexts()).contains("40"), "the scan counter shows");

        // machine translation: only the explanation, nothing to start
        DialogPanel unavailable = panel(WarmupConfirmDialog.content(false, null, null, false, lang), size);
        start = unavailable.buttonRect(WarmupConfirmDialog.START);
        assertEquals(DialogPanel.NONE, unavailable.mouseClicked(start[0] + 2, start[1] + 2, 0));
        assertTrue(squash(String.join("", unavailable.shownTexts()))
                .contains(squash(lang.get("screen.nyanlex.warmup.unavailable.engine"))));

        // everything already saved: the message, no warnings, no start
        ItemWarmupPlan nothing = new ItemWarmupPlan(1332, 1300, 0, 0, 0, 0, 0, 0, false, 32, 0);
        DialogPanel done = panel(WarmupConfirmDialog.content(true, new WarmupConfirmDialog.Scan(1332, 1332, 0),
                nothing, true, lang), size);
        start = done.buttonRect(WarmupConfirmDialog.START);
        assertEquals(DialogPanel.NONE, done.mouseClicked(start[0] + 2, start[1] + 2, 0));
        String doneText = squash(String.join("", done.shownTexts()));
        assertTrue(doneText.contains(squash(lang.get("screen.nyanlex.warmup.nothing"))));
        assertFalse(doneText.contains(squash(lang.get("screen.nyanlex.warmup.warn.1"))));

        // a real plan: start works by click, and Enter does nothing until Tab put the frame on it
        DialogPanel ready = panel(WarmupConfirmDialog.content(true, new WarmupConfirmDialog.Scan(1332, 1332, 0),
                plan(), true, lang), size);
        assertEquals(DialogPanel.NONE, ready.keyPressed(SettingsPanel.KEY_ENTER, false));
        start = ready.buttonRect(WarmupConfirmDialog.START);
        assertEquals(WarmupConfirmDialog.START, ready.mouseClicked(start[0] + 2, start[1] + 2, 0));
        ready.keyPressed(SettingsPanel.KEY_TAB, false);
        ready.keyPressed(SettingsPanel.KEY_TAB, false);
        assertEquals(WarmupConfirmDialog.START, ready.keyPressed(SettingsPanel.KEY_ENTER, false));
    }

    @Test
    void theSkippedLineNamesTheReasonAndTheContentOnlyChangesWhenWhatItShowsChanges() {
        DialogContent.Lang lang = DialogPanelTest.lang("zh_tw");
        WarmupConfirmDialog.Scan scan = new WarmupConfirmDialog.Scan(1332, 1332, 7);
        String inWorld = squash(String.join("", panel(WarmupConfirmDialog.content(true, scan, plan(), true, lang),
                new int[] {427, 240}).shownTexts()));
        String title = squash(String.join("", panel(WarmupConfirmDialog.content(true, scan, plan(), false, lang),
                new int[] {427, 240}).shownTexts()));
        assertTrue(inWorld.contains(squash(lang.get("screen.nyanlex.warmup.skipped.world", 7))));
        assertTrue(title.contains(squash(lang.get("screen.nyanlex.warmup.skipped", 7))));
        // the screen rebuilds the card only when the content differs (record equality)
        assertEquals(WarmupConfirmDialog.content(true, scan, plan(), true, lang),
                WarmupConfirmDialog.content(true, scan, plan(), true, lang));
        assertFalse(WarmupConfirmDialog.content(true, scan, plan(), true, lang)
                .equals(WarmupConfirmDialog.content(true, new WarmupConfirmDialog.Scan(1333, 1333, 7), null, true, lang)));
    }

    @Test
    void tokenFiguresAreShortened() {
        assertEquals("999", WarmupConfirmDialog.formatTokens(999));
        assertEquals("13K", WarmupConfirmDialog.formatTokens(13_000));
        assertEquals("2.5M", WarmupConfirmDialog.formatTokens(2_500_000));
    }

    @Test
    void everyLangKeyTheCardUsesExistsInEveryLanguage() {
        for (String code : LANGS) {
            DialogContent.Lang lang = DialogPanelTest.lang(code);
            for (String key : WarmupConfirmDialog.allLangKeys()) {
                assertFalse(lang.get(key).equals(key), code + " has " + key);
            }
        }
    }
}
