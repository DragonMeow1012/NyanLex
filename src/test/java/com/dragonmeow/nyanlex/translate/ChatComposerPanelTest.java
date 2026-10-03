package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

class ChatComposerPanelTest {
    @Test void closeKeepsLatestDraftAcrossScreensAndIgnoresLateResults() {
        Host first = new Host();
        ChatComposerPanel panel = new ChatComposerPanel(first);
        panel.submit();
        first.draft = "等一下，我要離開一下";
        panel.close();
        assertEquals(first.draft, ChatComposerPanel.savedDraft());
        first.result.accept("stale translation", null);
        assertEquals(0, first.fills);
        Host second = new Host();
        second.draft = ChatComposerPanel.savedDraft();
        ChatComposerPanel reopened = new ChatComposerPanel(second);
        assertEquals(first.draft, second.draft);
        second.draft += "，馬上回來";
        reopened.close();
        panel.close();
        assertEquals(second.draft, ChatComposerPanel.savedDraft());
        Host cleared = new Host();
        cleared.draft = "";
        new ChatComposerPanel(cleared).close();
        assertEquals("", ChatComposerPanel.savedDraft());
    }
    @Test void defaultPositionIsBottomRightAndLeavesChatInputClear() {
        com.dragonmeow.nyanlex.config.TranslatorConfig config = new com.dragonmeow.nyanlex.config.TranslatorConfig();
        Host host = new Host();
        host.x = config.chatComposerX;
        host.y = config.chatComposerY;
        ChatComposerPanel panel = new ChatComposerPanel(host);
        panel.resize(800, 600);
        assertEquals(484, panel.inputX());
        assertEquals(507, panel.inputY());
        host.x = Double.NaN;
        panel.resize(800, 600);
        assertEquals(484, panel.inputX());
    }
    static final class Host implements ChatComposerPanel.Host {
        String draft = "等我一下", chat = "", language = "en";
        boolean current = true;
        double x, y = 1;
        int requests, fills;
        BiConsumer<String, String> result;
        String clientLanguage = "en_us";
        boolean formatTokensConsumed;
        public String text(String key) {
            if (key.equals("language.code")) return clientLanguage;
            if (key.equals("nyanlex.composer.target_language")) {
                String value = clientLanguage.equals("zh_tw") ? "翻譯成：%s" : "Translate to: %s";
                return formatTokensConsumed ? value.replace("%s", "") : value;
            }
            return key;
        }
        public int textWidth(String value) { return value.length(); }
        public String draft() { return draft; }
        public String chat() { return chat; }
        public String language() { return language; }
        public void language(String value) { language = value; }
        String[][] rows = {{"en", "English"}, {"ja", "日本語"}};
        public String[][] languages() { return rows; }
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
    @Test void languageLabelTracksClientLanguageWithoutChangingTranslationTarget() {
        Host host = new Host();
        ChatComposerPanel panel = new ChatComposerPanel(host);
        panel.resize(800, 600);
        java.util.List<String> labels = new java.util.ArrayList<>();
        ChatComposerPanel.Canvas canvas = new ChatComposerPanel.Canvas() {
            public void fill(int x, int y, int w, int h, int color) {}
            public void text(String value, int x, int y, int color) { labels.add(value); }
        };
        panel.render(canvas);
        assertTrue(labels.contains("Translate to: English ▾"));
        host.clientLanguage = "zh_tw";
        labels.clear();
        panel.render(canvas);
        String translatedName = java.util.Locale.ENGLISH.getDisplayName(java.util.Locale.TAIWAN);
        assertTrue(labels.contains("翻譯成：" + translatedName + " ▾"));
        assertEquals("en", host.language);

        // Real Minecraft resolves a no-argument translatable before this shared panel
        // sees it, so an unmatched %s has already disappeared at this boundary.
        host.formatTokensConsumed = true;
        labels.clear();
        panel.render(canvas);
        assertTrue(labels.contains("翻譯成：" + translatedName + " ▾"));
    }
    @Test void languageSearchAcceptsCodesNativeEnglishAndClientLanguageNames() {
        Host host = new Host();
        host.clientLanguage = "zh_tw";
        ChatComposerPanel panel = new ChatComposerPanel(host);
        panel.resize(800, 600);
        panel.click(panel.inputX(), panel.inputY() + 30, 0);
        assertTrue(panel.choosing());
        int searchY = panel.searchY();
        for (String query : new String[]{" ja ", "日本語", "JAPANESE",
                java.util.Locale.JAPANESE.getDisplayName(java.util.Locale.TAIWAN)}) {
            panel.search(query);
            assertEquals(1, panel.matchingLanguages().length);
            assertEquals("ja", panel.matchingLanguages()[0][0]);
            assertEquals(searchY, panel.searchY(), "typing must not move the search field");
        }
        panel.chooseFirst();
        assertEquals("ja", host.language);
        assertFalse(panel.choosing());
        assertEquals("等我一下", host.draft);
        assertEquals("", host.chat);
        assertEquals(0, host.requests);
    }
    @Test void searchAcceptsAccentsAndBothMinecraftAndLanguageTagCodes() {
        Host host = new Host();
        host.rows = new String[][]{{"fr", "Français"}, {"vi", "Tiếng Việt"}, {"zh-TW", "繁體中文"}};
        ChatComposerPanel panel = new ChatComposerPanel(host);
        panel.search(" FRANCAIS ");
        assertEquals("fr", panel.matchingLanguages()[0][0]);
        panel.search("tieng viet");
        assertEquals("vi", panel.matchingLanguages()[0][0]);
        panel.search("ZH_tw");
        assertEquals("zh-TW", panel.matchingLanguages()[0][0]);
        panel.search(null);
        assertEquals(3, panel.matchingLanguages().length);
        assertEquals(0, host.requests);
    }
    @Test void filteringResetsScrolledListAndEmptyResultsDoNotSelectOrCloseChat() {
        Host host = new Host();
        host.rows = new String[20][];
        for (int i = 0; i < 20; i++) host.rows[i] = new String[]{"x-" + i, "Language " + i};
        ChatComposerPanel panel = new ChatComposerPanel(host);
        panel.resize(800, 600);
        panel.click(panel.inputX(), panel.inputY() + 30, 0);
        for (int i = 0; i < 20; i++) panel.scroll(-1);
        panel.search("does not exist");
        panel.chooseFirst();
        assertTrue(panel.choosing());
        assertEquals("en", host.language);
        panel.search("Language 3");
        panel.click(panel.searchX() + 2, panel.searchY() + 23, 0);
        assertEquals("x-3", host.language);
        assertFalse(panel.choosing());
        panel.click(panel.inputX(), panel.inputY() + 30, 0);
        panel.closeChoices();
        assertFalse(panel.choosing());
        panel.submit();
        assertEquals(1, host.requests, "closing the picker must not close the composer");
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
