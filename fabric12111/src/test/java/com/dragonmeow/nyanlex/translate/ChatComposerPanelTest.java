package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

class ChatComposerPanelTest {
    static final class Host implements ChatComposerPanel.Host {
        String draft = "等我一下", chat = "", language = "en";
        boolean current = true;
        double x, y = 1;
        int requests, fills;
        BiConsumer<String, String> result;
        public String text(String key) { return key; }
        public int textWidth(String value) { return value.length(); }
        public String draft() { return draft; }
        public String chat() { return chat; }
        public String language() { return language; }
        public void language(String value) { language = value; }
        public String[][] languages() { return new String[][]{{"en", "English"}, {"ja", "日本語"}}; }
        public double positionX() { return x; }
        public double positionY() { return y; }
        public void position(double x, double y) { this.x = x; this.y = y; }
        public void fill(String text) { chat = text; fills++; }
        public boolean current() { return current; }
        public void execute(Runnable task) { task.run(); }
        public void translate(String source, String target, BiConsumer<String, String> callback) {
            requests++; result = callback;
        }
    }
    @Test void fillsDraftOnlyAndBoundsDuplicateRequests() {
        Host h = new Host(); ChatComposerPanel panel = new ChatComposerPanel(h);
        panel.submit(); panel.submit();
        assertEquals(1, h.requests);
        h.result.accept("Wait for me.", null);
        assertEquals("Wait for me.", h.chat);
        assertEquals("等我一下", h.draft);
        assertEquals(1, h.fills);
        assertFalse(panel.busy());
    }
    @Test void editsEvenWhenRevertedInvalidateOldResult() {
        Host h = new Host(); ChatComposerPanel panel = new ChatComposerPanel(h);
        panel.submit(); h.chat = "new text"; panel.observe(); h.chat = ""; panel.observe();
        h.result.accept("old result", null);
        assertEquals(0, h.fills);
        panel.submit(); h.result.accept("fresh result", null);
        assertEquals("fresh result", h.chat);
    }
    @Test void closedScreenAndChangedLanguageRejectLateResults() {
        Host h = new Host(); ChatComposerPanel panel = new ChatComposerPanel(h);
        panel.submit(); panel.close(); h.result.accept("late", null);
        assertEquals(0, h.fills);
        panel = new ChatComposerPanel(h);
        panel.submit(); h.language = "ja"; h.result.accept("old language", null);
        assertEquals(0, h.fills);
        panel.submit(); h.current = false; h.result.accept("different screen", null);
        assertEquals(0, h.fills);
    }
    @Test void preservesOriginalOnFailureOverlengthAndCommandOutput() {
        Host h = new Host(); h.chat = "existing draft";
        ChatComposerPanel panel = new ChatComposerPanel(h);
        for (String value : new String[]{null, "x".repeat(257), "/op someone", "bad\u0000text"}) {
            panel.submit(); h.result.accept(value, "failed");
            assertEquals("existing draft", h.chat);
            assertEquals("等我一下", h.draft);
        }
        h.draft = "/msg person hi"; panel.submit(); assertFalse(panel.busy());
    }
    @Test void draggingPersistsRelativePositionAndClampsAfterResize() {
        Host h = new Host(); ChatComposerPanel panel = new ChatComposerPanel(h);
        panel.resize(800, 600);
        int headerY = panel.inputY() - 18;
        assertTrue(panel.click(panel.inputX(), headerY, 0));
        assertTrue(panel.drag(9999, -9999)); assertTrue(panel.release());
        assertEquals(1, h.x); assertEquals(0, h.y);
        panel.resize(400, 300);
        assertEquals(84, panel.inputX()); assertEquals(23, panel.inputY());
        h.x = Double.NaN; h.y = Double.POSITIVE_INFINITY;
        panel.resize(400, 300);
        assertTrue(panel.inputX() >= 0); assertTrue(panel.inputY() < 300);
    }
}
