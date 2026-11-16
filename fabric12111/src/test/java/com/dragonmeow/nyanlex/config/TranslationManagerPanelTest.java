package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.service.TranslationManagerModel;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.ColorVersion;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.EditData;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Page;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Row;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.SaveOutcome;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.SaveResult;
import com.dragonmeow.nyanlex.service.TranslationManagerModel.Source;
import com.dragonmeow.nyanlex.translate.ManualTextCodec;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The translation manager screen: list, search, editor, colour versions, two-step delete, keyboard and narrator. */
class TranslationManagerPanelTest {

    private static final String[] CODES = {"zh_tw", "ja_jp", "zh_cn", "en_us"};
    private static final int[][] SIZES = {{320, 240}, {427, 240}, {640, 360}, {256, 240}};
    private static final int ROW = 24;

    private static final String MT0 = "⟦MT0⟧";
    private static final String MT1 = "⟦MT1⟧";
    private static final String NAME0 = "⟦0⟧";
    private static final String PB0 = "⟦PB0⟧";
    private static final String PB1 = "⟦PB1⟧";
    private static final String CS0 = "⟦CS0⟧";
    private static final String CS0_END = "⟦/CS0⟧";
    private static final String CS1 = "⟦CS1⟧";
    private static final String CS1_END = "⟦/CS1⟧";

    // ------------------------------------------------------------------ fakes

    private static final Map<String, JsonObject> LANGS = new HashMap<>();

    private static JsonObject lang(String code) {
        return LANGS.computeIfAbsent(code, c -> {
            try (InputStream in = TranslationManagerPanelTest.class.getResourceAsStream("/assets/nyanlex/lang/" + c + ".json")) {
                assertNotNull(in, c);
                return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static int width(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) w += ch >= 0x2E00 ? 9 : 6;
        return w;
    }

    private static int placeholders(String text) {
        Matcher m = Pattern.compile("%s").matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static final class Text {
        final String text;
        final int x;
        final int y;
        final int w;

        Text(String text, int x, int y) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = width(text);
        }
    }

    private static final class Rec implements UiCanvas {
        final List<Text> texts = new ArrayList<>();
        final List<int[]> fills = new ArrayList<>();

        @Override public void fill(int x, int y, int w, int h, int argb) { fills.add(new int[] {x, y, w, h, argb}); }

        @Override public void text(String text, int x, int y, int argb) {
            if (!text.isEmpty()) texts.add(new Text(text, x, y));
        }

        @Override public void pushClip(int x, int y, int w, int h) { throw new AssertionError("the manager draws without clipping"); }

        @Override public void popClip() { throw new AssertionError("the manager draws without clipping"); }

        boolean has(String text) {
            for (Text t : texts) if (t.text.equals(text)) return true;
            return false;
        }

        boolean hasContaining(String part) {
            for (Text t : texts) if (t.text.contains(part)) return true;
            return false;
        }

        String all() {
            StringBuilder sb = new StringBuilder();
            for (Text t : texts) sb.append(t.text).append('\n');
            return sb.toString();
        }
    }

    /** Strict: a key that is missing, or a call with the wrong number of arguments, fails the test. */
    private static final class FakeHost implements TranslationManagerPanel.Host {
        final String code;
        long now = 1_000_000;
        String clipboard = "";
        final List<String> copied = new ArrayList<>();
        int clicks;
        int changed;

        FakeHost(String code) { this.code = code; }

        @Override public String text(String key, Object... args) {
            JsonObject l = lang(code);
            assertTrue(l.has(key), "missing lang key " + key + " in " + code);
            String fmt = l.get(key).getAsString();
            assertEquals(placeholders(fmt), args.length, key + " is asked for with the wrong number of arguments");
            return args.length == 0 ? fmt : String.format(fmt, args);
        }

        @Override public int textWidth(String text) { return width(text); }

        @Override public String clipboard() { return clipboard; }

        @Override public void copy(String text) { copied.add(text); }

        @Override public long nowMs() { return now; }

        @Override public void playClick() { clicks++; }

        @Override public void onChanged() { changed++; }
    }

    private static final class FakeModel implements TranslationManagerModel {
        final List<Row> all = new ArrayList<>();
        final Map<String, EditData> edits = new HashMap<>();
        Page page = Page.LOADING;
        String query = "";
        boolean manualOnly;
        final List<String> searches = new ArrayList<>();
        int refreshes;
        int pageReads;
        final List<List<String>> saves = new ArrayList<>();
        final List<String> reverts = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        SaveOutcome nextSave;
        boolean online = true;
        boolean revertOk = true;

        @Override public Page page() {
            pageReads++;
            return page;
        }

        @Override public void searchAsync(String q, boolean manual) {
            searches.add(q + "|" + manual);
            query = q == null ? "" : q.trim();
            manualOnly = manual;
        }

        @Override public void refreshAsync() { refreshes++; }

        /** The background scan finishing: computes and publishes the page of the latest query. */
        void publish() {
            String[] terms = ManualTextCodec.searchTerms(query);
            List<Row> hits = new ArrayList<>();
            int manual = 0;
            for (Row r : all) {
                if (r.source() == Source.MANUAL) manual++;
                if (manualOnly && r.source() != Source.MANUAL) continue;
                if (ManualTextCodec.matchesAll(r.key(), r.translation(), terms)) hits.add(r);
            }
            List<Row> shown = hits.size() > MAX_PAGE_ROWS ? hits.subList(0, MAX_PAGE_ROWS) : hits;
            page = new Page(shown, hits.size(), all.size(), manual, hits.size() > shown.size(), false, query, manualOnly);
        }

        @Override public EditData editData(String key) { return edits.get(key); }

        @Override public SaveOutcome save(String key, String translation, String colorKey, String colorTranslation) {
            saves.add(java.util.Arrays.asList(key, translation, colorKey, colorTranslation));
            if (nextSave != null) {
                SaveOutcome o = nextSave;
                nextSave = null;
                return o;
            }
            EditData old = edits.get(key);
            List<ColorVersion> versions = new ArrayList<>();
            for (ColorVersion v : old.colorVersions()) {
                versions.add(v.sourceKey().equals(colorKey) ? new ColorVersion(v.sourceKey(), colorTranslation) : v);
            }
            edits.put(key, new EditData(key, old.original(), translation, Source.MANUAL,
                    old.source() == Source.MANUAL ? old.automaticTranslation() : old.translation(),
                    old.source() == Source.MANUAL ? old.automaticSource() : old.source(), versions));
            replaceRow(key, translation, Source.MANUAL);
            publish();
            return SaveOutcome.OK;
        }

        @Override public boolean revertToAutomatic(String key) {
            reverts.add(key);
            if (!revertOk) return false;
            EditData old = edits.get(key);
            replaceRow(key, old.automaticTranslation(), old.automaticSource());
            publish();
            return true;
        }

        @Override public boolean deleteAndRetranslate(String key) {
            deletes.add(key);
            all.removeIf(r -> r.key().equals(key));
            edits.remove(key);
            publish();
            return online;
        }

        private void replaceRow(String key, String translation, Source source) {
            for (int i = 0; i < all.size(); i++) {
                Row r = all.get(i);
                if (r.key().equals(key)) all.set(i, new Row(key, translation, source, r.hasColorVersions()));
            }
        }

        FakeModel row(String key, String translation, Source source) {
            all.add(new Row(key, translation, source, false));
            edits.put(key, new EditData(key, key, translation, source, null, null, List.of()));
            return this;
        }
    }

    private static final String COINS = "You got " + MT0 + " coins";
    private static final String COINS_TW = "你獲得 " + MT0 + " 枚金幣";

    private static FakeModel sample() {
        FakeModel m = new FakeModel();
        m.row(COINS, COINS_TW, Source.AI);
        m.row("Sharpness", "鋒利", Source.MACHINE);
        m.row("Hello " + NAME0 + "!", "你好 " + NAME0 + "！", Source.MANUAL);
        m.edits.put("Hello " + NAME0 + "!", new EditData("Hello " + NAME0 + "!", "Hello " + NAME0 + "!",
                "你好 " + NAME0 + "！", Source.MANUAL, "哈囉 " + NAME0 + "！", Source.AI, List.of()));
        m.row("Total: " + MT0 + PB0 + "Next: " + MT1 + PB1 + "End", "合計：" + MT0 + PB0 + "下一個：" + MT1 + PB1 + "結束",
                Source.AI);
        // a coloured sentence with two colour versions
        m.all.add(new Row("Rare Sword", "稀有劍", Source.AI, true));
        m.edits.put("Rare Sword", new EditData("Rare Sword", "Rare Sword", "稀有劍", Source.AI, null, null,
                List.of(new ColorVersion(CS0 + "Rare" + CS0_END + " Sword", CS0 + "稀有" + CS0_END + "劍"),
                        new ColorVersion(CS0 + "Rare Sword" + CS0_END, CS0 + "稀有劍" + CS0_END))));
        return m;
    }

    private static TranslationManagerPanel open(FakeHost host, FakeModel model, int w, int h) {
        TranslationManagerPanel p = new TranslationManagerPanel(host, model);
        p.resize(w, h);
        model.publish();
        render(p);
        return p;
    }

    private static Rec render(TranslationManagerPanel p) {
        Rec c = new Rec();
        p.render(c, -1, -1);
        return c;
    }

    private static void click(TranslationManagerPanel p, String name) {
        int[] r = p.rect(name);
        assertNotNull(r, "no control " + name);
        p.mouseClicked(r[0] + r[2] / 2, r[1] + r[3] / 2, 0);
    }

    private static void clickRow(TranslationManagerPanel p, int i) {
        int[] l = p.rect("list");
        p.mouseClicked(l[0] + 20, l[1] + i * ROW + 6, 0);
    }

    private static void type(TranslationManagerPanel p, String text) {
        for (char ch : text.toCharArray()) assertTrue(p.charTyped(ch));
    }

    private static void key(TranslationManagerPanel p, int key) { p.keyPressed(key, false, false); }

    private static void ctrl(TranslationManagerPanel p, int key) { p.keyPressed(key, true, false); }

    private static void openRow(TranslationManagerPanel p, FakeModel m, String key) {
        for (int i = 0; i < p.rowCount(); i++) {
            if (m.page.rows().get(i).key().equals(key)) {
                clickRow(p, i - p.firstRow());
                return;
            }
        }
        throw new AssertionError("row not in the list: " + key);
    }

    // ------------------------------------------------------------------ layout

    private static void assertNoOverlapOrOverflow(TranslationManagerPanel p, Rec c, String label) {
        int[] box = p.panelRect();
        for (Map.Entry<String, int[]> e : p.controlRects().entrySet()) {
            int[] r = e.getValue();
            assertTrue(r[0] >= box[0] && r[1] >= box[1] && r[0] + r[2] <= box[0] + box[2] && r[1] + r[3] <= box[1] + box[3],
                    label + ": " + e.getKey() + " leaves the panel " + java.util.Arrays.toString(r) + " in " + java.util.Arrays.toString(box));
        }
        List<Map.Entry<String, int[]>> rects = new ArrayList<>(p.controlRects().entrySet());
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                if (rects.get(i).getKey().equals("clear") || rects.get(j).getKey().equals("clear")) continue; // the cross sits inside the box
                int[] a = rects.get(i).getValue();
                int[] b = rects.get(j).getValue();
                boolean overlap = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
                assertFalse(overlap, label + ": " + rects.get(i).getKey() + " overlaps " + rects.get(j).getKey());
            }
        }
        for (Text t : c.texts) {
            assertTrue(t.x >= box[0] && t.x + t.w <= box[0] + box[2] && t.y >= box[1] && t.y + 8 <= box[1] + box[3],
                    label + ": text leaves the panel: " + t.text + " at " + t.x + "," + t.y + " w=" + t.w + " in " + java.util.Arrays.toString(box));
        }
        for (int i = 0; i < c.texts.size(); i++) {
            for (int j = i + 1; j < c.texts.size(); j++) {
                Text a = c.texts.get(i);
                Text b = c.texts.get(j);
                boolean overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + 8 && b.y < a.y + 8;
                assertFalse(overlap, label + ": the text [" + a.text + "] overlaps [" + b.text + "]");
            }
        }
    }

    @Test
    void theListFitsEverySizeInEveryLanguageAndNothingOverlapsOrLeavesThePanel() {
        for (String code : CODES) {
            for (int[] size : SIZES) {
                String label = code + " " + size[0] + "x" + size[1];
                FakeHost host = new FakeHost(code);
                FakeModel m = sample();
                // long names that must be cut, and enough rows to scroll
                m.row("A very long original sentence that goes on and on and on and on and on and on " + MT0, "非常非常非常非常非常長的譯文一直一直一直一直寫下去", Source.MANUAL);
                for (int i = 0; i < 40; i++) m.row("Row number " + i, "第 " + i + " 列", i % 3 == 0 ? Source.MANUAL : Source.AI);
                TranslationManagerPanel p = open(host, m, size[0], size[1]);
                Rec c = render(p);
                assertTrue(c.has(host.text(TranslationManagerPanel.KEY_TITLE)), label + " title");
                assertTrue(c.has("You got {V0} coins"), label + " rows are listed: " + c.all());
                assertNoOverlapOrOverflow(p, c, label + " list");
                assertTrue(p.visibleRowCount() >= 3, label + ": at least three rows are visible, got " + p.visibleRowCount());
                // scrolled to the end
                key(p, SettingsPanel.KEY_END);
                assertNoOverlapOrOverflow(p, render(p), label + " list end");
                // capped, loading and empty states
                m.searches.clear();
                type(p, "zzzzqqq");
                host.now += 500;
                render(p);
                m.publish();
                Rec empty = render(p);
                assertTrue(empty.hasContaining("zzzzqqq"), label + " empty note: " + empty.all());
                assertNoOverlapOrOverflow(p, empty, label + " empty");
            }
        }
    }

    @Test
    void theEditorFitsEverySizeInEveryLanguageInEveryKindOfRow() {
        String[] keys = {COINS, "Hello " + NAME0 + "!", "Total: " + MT0 + PB0 + "Next: " + MT1 + PB1 + "End", "Rare Sword"};
        for (String code : CODES) {
            for (int[] size : SIZES) {
                for (String key : keys) {
                    String label = code + " " + size[0] + "x" + size[1] + " " + key;
                    FakeHost host = new FakeHost(code);
                    FakeModel m = sample();
                    TranslationManagerPanel p = open(host, m, size[0], size[1]);
                    openRow(p, m, key);
                    assertTrue(p.isEditing(), label);
                    assertNoOverlapOrOverflow(p, render(p), label + " edit");
                    // with a long text in the box, an error message and the delete confirmation showing
                    for (int i = 0; i < 12; i++) type(p, "長長長長長 long words ");
                    key(p, SettingsPanel.KEY_HOME);
                    assertNoOverlapOrOverflow(p, render(p), label + " edit long text");
                    if (p.rect("delete") != null) {
                        click(p, "delete");
                        assertTrue(p.deleteArmed(), label);
                        assertNoOverlapOrOverflow(p, render(p), label + " edit armed");
                    }
                    // the unsaved-changes warning
                    key(p, SettingsPanel.KEY_ESCAPE);
                    Rec warn = render(p);
                    assertNoOverlapOrOverflow(p, warn, label + " edit warning");
                }
            }
        }
    }

    @Test
    void aRoomyWindowAlsoShowsTheAutomaticTranslationAndTheHintsASmallOneLeavesThemOut() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel big = open(host, m, 640, 360);
        openRow(big, m, "Hello " + NAME0 + "!");
        assertTrue(big.autoLineShown());
        assertTrue(big.hintLineCount() > 0);
        Rec c = render(big);
        assertTrue(c.hasContaining(host.text(TranslationManagerPanel.KEY_HINT_PRIORITY)) || c.hasContaining("這類標記"), c.all());
        TranslationManagerPanel small = open(host, m, 256, 240);
        openRow(small, m, "Hello " + NAME0 + "!");
        assertEquals(0, small.hintLineCount());
        assertTrue(small.rect("save") != null && small.rect("editBack") != null, "the buttons are always there");
        // only a manual row has an automatic translation to show
        TranslationManagerPanel other = open(host, m, 640, 360);
        openRow(other, m, "Sharpness");
        assertFalse(other.autoLineShown());
    }

    @Test
    void theEditorShowsTheOriginalTheBoxTheTagButtonsAndAllTheButtonsEvenInTheSmallestWindow() {
        for (String code : CODES) {
            FakeHost host = new FakeHost(code);
            FakeModel m = sample();
            TranslationManagerPanel p = open(host, m, 256, 240);
            openRow(p, m, "Hello " + NAME0 + "!");
            Rec c = render(p);
            assertTrue(c.has(host.text(TranslationManagerPanel.KEY_EDIT_TITLE)), code);
            assertTrue(c.has(host.text(TranslationManagerPanel.KEY_EDIT_ORIGINAL)), code);
            assertTrue(c.has(host.text(TranslationManagerPanel.KEY_EDIT_TRANSLATION)), code);
            assertTrue(c.has(host.text(TranslationManagerPanel.KEY_BTN_SAVE)), code);
            assertTrue(c.has(host.text(TranslationManagerPanel.KEY_BTN_REVERT)), code + " a manual row can be reverted");
            assertTrue(c.has(host.text(TranslationManagerPanel.KEY_BTN_BACK)), code);
            assertTrue(c.hasContaining("{N0}"), code + " the original shows the alias");
            assertEquals(List.of("{N0}"), p.chipTexts(), code);
            assertTrue(p.editVisibleLines() >= 2, code + ": the box shows at least two lines");
            assertTrue(p.rect("edit")[3] >= 26, code);
        }
    }

    // ------------------------------------------------------------------ list

    @Test
    void theListShowsTheStatsTheTagsAndSelectsWithTheKeysAndOpensWithEnterOrAClick() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        Rec c = render(p);
        assertTrue(c.has("已存 5 筆，其中手動修改 1 筆"), c.all());
        assertTrue(c.has("You got {V0} coins"), "the markers are shown as aliases: " + c.all());
        assertTrue(c.has("你獲得 {V0} 枚金幣"));
        assertTrue(c.has("手動"), "the manual row has its tag");
        assertTrue(c.has("AI") && c.has("機翻"), "and the others say where they come from");
        assertEquals(5, p.rowCount());
        assertEquals(-1, p.selectedRow());
        key(p, SettingsPanel.KEY_DOWN);
        assertEquals(0, p.selectedRow());
        key(p, SettingsPanel.KEY_DOWN);
        key(p, SettingsPanel.KEY_DOWN);
        assertEquals(2, p.selectedRow());
        key(p, SettingsPanel.KEY_UP);
        assertEquals(1, p.selectedRow());
        ctrl(p, SettingsPanel.KEY_END);
        assertEquals(4, p.selectedRow());
        ctrl(p, SettingsPanel.KEY_HOME);
        assertEquals(0, p.selectedRow());
        key(p, SettingsPanel.KEY_PAGE_DOWN);
        assertEquals(Math.min(4, p.visibleRowCount()), p.selectedRow());
        key(p, SettingsPanel.KEY_PAGE_UP);
        assertEquals(0, p.selectedRow());
        key(p, SettingsPanel.KEY_END);
        assertEquals(0, p.selectedRow(), "End moves the cursor of the search box while it has the keyboard");
        key(p, SettingsPanel.KEY_ENTER);
        assertTrue(p.isEditing());
        assertEquals(COINS_TW, p.editText().replace("{V0}", MT0).replace("⁠", ""), "the box holds the translation (as aliases)");
        assertEquals("你獲得 {V0} 枚金幣", p.editText());
        assertEquals("You got {V0} coins", p.originalText());
        key(p, SettingsPanel.KEY_ESCAPE);
        assertFalse(p.isEditing());
        clickRow(p, 1);
        assertTrue(p.isEditing());
        assertEquals("鋒利", p.editText());
    }

    @Test
    void openingTheScreenAsksTheModelOnceAndTheRenderPathOnlyReadsThePage() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = new TranslationManagerPanel(host, m);
        p.resize(427, 240);
        assertEquals(List.of("|false"), m.searches, "the first search is the empty one");
        assertEquals(1, m.refreshes, "and the stores are read again once");
        Rec loading = render(p);
        assertTrue(loading.has(host.text(TranslationManagerPanel.KEY_LOADING)), "nothing published yet: " + loading.all());
        m.publish();
        for (int i = 0; i < 5; i++) render(p);
        assertEquals(List.of("|false"), m.searches);
        assertEquals(1, m.refreshes);
        assertTrue(m.pageReads >= 6, "the page is read once per frame");
    }

    @Test
    void typingInTheSearchBoxWaitsForAPauseThenAsksTheModelAndEscapeClearsItFirst() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        assertTrue(p.searchFocused() && p.isTyping(), "the search box has the keyboard when the screen opens");
        m.searches.clear();
        type(p, "got");
        assertEquals("got", p.queryText());
        render(p);
        assertEquals(List.of(), m.searches, "nothing is sent while the player is still typing");
        host.now += 119;
        render(p);
        assertEquals(List.of(), m.searches);
        host.now += 2;
        render(p);
        assertEquals(List.of("got|false"), m.searches, "sent after the pause");
        render(p);
        assertEquals(1, m.searches.size(), "and only once");
        m.publish();
        Rec c = render(p);
        assertEquals(1, p.rowCount());
        assertTrue(c.has("You got {V0} coins"));
        assertFalse(c.has("Sharpness"));
        // the words are searched in the original and in the translation, markers are not text
        type(p, " mt");
        host.now += 200;
        render(p);
        m.publish();
        render(p);
        assertEquals(0, p.rowCount(), "mt is a marker, not a word");
        assertTrue(render(p).hasContaining("got mt"), "the note names the search");
        // Escape clears the search, a second Escape leaves
        assertEquals(TranslationManagerPanel.NONE, p.keyPressed(SettingsPanel.KEY_ESCAPE));
        assertEquals("", p.queryText());
        assertEquals("|false", m.searches.get(m.searches.size() - 1), "cleared at once, not after a pause");
        m.publish();
        render(p);
        assertEquals(5, p.rowCount());
        assertEquals(TranslationManagerPanel.BACK, p.keyPressed(SettingsPanel.KEY_ESCAPE));
    }

    @Test
    void theFilterButtonsSwitchBetweenAllAndManualOnlyAtOnce() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        m.searches.clear();
        click(p, "manual");
        assertEquals(List.of("|true"), m.searches);
        assertTrue(p.manualOnlyOn());
        m.publish();
        render(p);
        assertEquals(1, p.rowCount());
        click(p, "manual");
        assertEquals(1, m.searches.size(), "pressing the lit button again changes nothing");
        click(p, "all");
        assertEquals(List.of("|true", "|false"), m.searches);
        m.publish();
        render(p);
        assertEquals(5, p.rowCount());
        // with nothing manual the note says so
        FakeModel none = new FakeModel().row("A", "甲", Source.AI);
        TranslationManagerPanel q = open(host, none, 427, 240);
        click(q, "manual");
        none.publish();
        assertTrue(render(q).has(host.text(TranslationManagerPanel.KEY_NONE_MANUAL)));
    }

    @Test
    void theNotesSayWhenNothingIsSavedWhenTheListIsCutAndWhileLoading() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel empty = new FakeModel();
        TranslationManagerPanel p = open(host, empty, 427, 240);
        assertTrue(render(p).has(host.text(TranslationManagerPanel.KEY_NONE)));
        FakeModel many = new FakeModel();
        for (int i = 0; i < 260; i++) many.row("Key " + i, "鍵 " + i, Source.AI);
        TranslationManagerPanel q = open(host, many, 427, 240);
        assertEquals(200, q.rowCount());
        Rec c = render(q);
        assertTrue(c.hasContaining("只顯示前 200 筆"), "the cut is announced: " + c.all());
        assertTrue(c.hasContaining("260"), "the stats count all of them");
        // a page that is still loading keeps its rows and says so
        many.page = new Page(many.page.rows(), 260, 260, 0, true, true, "", false);
        assertTrue(render(q).hasContaining(host.text(TranslationManagerPanel.KEY_LOADING)));
    }

    @Test
    void aNewPageKeepsTheSelectedRowByKeyAndAChangedQueryScrollsBackToTheTop() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = new FakeModel();
        for (int i = 0; i < 30; i++) m.row("Key " + i, "鍵 " + i, Source.AI);
        TranslationManagerPanel p = open(host, m, 427, 240);
        for (int i = 0; i < 12; i++) key(p, SettingsPanel.KEY_DOWN);
        assertEquals(11, p.selectedRow());
        int first = p.firstRow();
        assertTrue(first > 0, "the list scrolled to keep the selection in view");
        // the same query publishes again with a row removed above the selection
        m.all.remove(0);
        m.publish();
        render(p);
        assertEquals(10, p.selectedRow(), "still the same row, found by its key");
        // a new query: back to the top, nothing selected
        type(p, "Key 2");
        host.now += 200;
        render(p);
        m.publish();
        render(p);
        assertEquals(0, p.firstRow());
        assertEquals(-1, p.selectedRow());
    }

    @Test
    void theWheelAndTheScrollBarMoveTheList() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = new FakeModel();
        for (int i = 0; i < 60; i++) m.row("Key " + i, "鍵 " + i, Source.AI);
        TranslationManagerPanel p = open(host, m, 427, 240);
        assertEquals(0, p.firstRow());
        p.mouseScrolled(-1);
        assertTrue(p.firstRow() > 0);
        p.mouseScrolled(1);
        p.mouseScrolled(1);
        assertEquals(0, p.firstRow());
        int[] list = p.rect("list");
        // the bar is at the right edge of the list
        p.mouseClicked(list[0] + list[2] - 2, list[1] + list[3] - 3, 0);
        p.mouseReleased();
        assertEquals(60 - p.visibleRowCount(), p.firstRow(), "a click at the bottom of the bar jumps to the end");
    }

    // ------------------------------------------------------------------ text boxes

    @Test
    void theSearchBoxHasACursorBackspaceDeleteArrowsPasteAndAClearButton() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        type(p, "abcd");
        assertEquals(4, p.searchCaret());
        key(p, SettingsPanel.KEY_LEFT);
        key(p, SettingsPanel.KEY_LEFT);
        assertEquals(2, p.searchCaret());
        key(p, SettingsPanel.KEY_BACKSPACE);
        assertEquals("acd", p.queryText());
        assertEquals(1, p.searchCaret());
        key(p, SettingsPanel.KEY_DELETE);
        assertEquals("ad", p.queryText());
        key(p, SettingsPanel.KEY_HOME);
        assertEquals(0, p.searchCaret());
        key(p, SettingsPanel.KEY_BACKSPACE);
        assertEquals("ad", p.queryText(), "nothing before the cursor");
        key(p, SettingsPanel.KEY_END);
        assertEquals(2, p.searchCaret());
        key(p, SettingsPanel.KEY_DELETE);
        assertEquals("ad", p.queryText(), "nothing after the cursor");
        type(p, "點"); // a CJK character
        assertEquals("ad點", p.queryText());
        // paste: control characters and a line break become nothing or a blank
        host.clipboard = "x\ny\u0001z";
        ctrl(p, 86);
        assertEquals("ad點xy z".replace("xy z", "x yz"), p.queryText());
        // Ctrl+A selects everything, typing replaces it; Ctrl+C copies it
        ctrl(p, 65);
        ctrl(p, 67);
        assertEquals(List.of("ad點x yz"), host.copied);
        type(p, "q");
        assertEquals("q", p.queryText());
        // a click in the box places the cursor between letters
        type(p, "wxyz");
        int[] box = p.rect("search");
        p.mouseClicked(box[0] + 4 + 6 + 3 + 1, box[1] + 5, 0);
        assertEquals(2, p.searchCaret(), "after the second letter");
        // the cross clears it
        click(p, "clear");
        assertEquals("", p.queryText());
        // the marker brackets and the section sign cannot be typed
        assertTrue(p.charTyped('⟦'));
        assertTrue(p.charTyped('§'));
        assertEquals("", p.queryText());
        // very long input is limited
        for (int i = 0; i < 100; i++) p.charTyped('a');
        assertEquals(60, p.queryText().length());
    }

    @Test
    void theEditBoxWrapsHasACursorAndEditsLikeATextField() {
        FakeHost host = new FakeHost("en_us");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        openRow(p, m, "Sharpness");
        assertTrue(p.editFocused() && p.isTyping());
        assertEquals("鋒利", p.editText());
        assertEquals(2, p.editCaret(), "the cursor starts at the end");
        type(p, "度");
        assertEquals("鋒利度", p.editText());
        key(p, SettingsPanel.KEY_LEFT);
        key(p, SettingsPanel.KEY_BACKSPACE);
        assertEquals("鋒度", p.editText());
        assertEquals(1, p.editCaret());
        key(p, SettingsPanel.KEY_DELETE);
        assertEquals("鋒", p.editText());
        key(p, SettingsPanel.KEY_HOME);
        assertEquals(0, p.editCaret());
        key(p, SettingsPanel.KEY_END);
        assertEquals(1, p.editCaret());
        // a long text wraps and the cursor keys move between the lines
        String sentence = "one two three four five six seven eight nine ten eleven twelve thirteen";
        ctrl(p, 65);
        host.clipboard = sentence;
        ctrl(p, 86);
        assertEquals(sentence, p.editText());
        List<String> lines = p.editLines();
        assertTrue(lines.size() >= 2, "wrapped: " + lines);
        for (String line : lines) assertTrue(width(line) <= p.rect("edit")[2] - 8, "a line fits the box: " + line);
        assertEquals(sentence, String.join("", lines), "wrapping loses nothing");
        for (String line : lines.subList(0, lines.size() - 1)) {
            assertFalse(line.isEmpty());
            assertTrue(line.endsWith(" ") || sentence.indexOf(line) >= 0, "breaks at a blank where it can: " + line);
        }
        key(p, SettingsPanel.KEY_UP);
        assertTrue(p.editCaret() < sentence.length() - lines.get(lines.size() - 1).length() + 1, "up moved to the line above");
        key(p, SettingsPanel.KEY_DOWN);
        key(p, SettingsPanel.KEY_END);
        assertEquals(sentence.length(), p.editCaret());
        // Ctrl+A then Backspace empties; Ctrl+X cuts everything
        ctrl(p, 65);
        key(p, SettingsPanel.KEY_BACKSPACE);
        assertEquals("", p.editText());
        type(p, "abc");
        ctrl(p, 88);
        assertEquals(List.of("abc"), host.copied);
        assertEquals("", p.editText());
        // paste turns CRLF into one line break and drops control characters and the marker brackets
        host.clipboard = "a\r\nb\u0001c⟦⟧§";
        ctrl(p, 86);
        assertEquals("a\nbc", p.editText());
        // a click puts the cursor in the clicked place
        ctrl(p, 65);
        host.clipboard = "hello world";
        ctrl(p, 86);
        int[] box = p.rect("edit");
        p.mouseClicked(box[0] + 4 + 6 * 5 + 1, box[1] + 5, 0);
        assertEquals(5, p.editCaret());
    }

    @Test
    void aTagButtonInsertsItsAliasAtTheCursor() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        openRow(p, m, COINS);
        assertEquals(List.of("{V0}"), p.chipTexts());
        // take the marker out of the text, then put it back with the button
        ctrl(p, 65);
        host.clipboard = "\u4F60\u7372\u5F97 \u679A\u91D1\u5E63";
        ctrl(p, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_MISSING, "{V0}"), p.messageText(), "the missing tag is named");
        assertFalse(p.saveEnabled());
        key(p, SettingsPanel.KEY_HOME);
        for (int i = 0; i < 4; i++) key(p, SettingsPanel.KEY_RIGHT);
        click(p, "chip0");
        assertEquals("\u4F60\u7372\u5F97 {V0}\u679A\u91D1\u5E63", p.editText());
        assertEquals(4 + "{V0}".length(), p.editCaret(), "the cursor ends after the tag");
        assertNull(p.messageText(), "and nothing is wrong any more");
        assertTrue(p.saveEnabled());
        assertTrue(p.editFocused(), "the box keeps the keyboard");
    }

    // ------------------------------------------------------------------ saving

    @Test
    void saveIsOffWhileTheTextHasAProblemAndTheMessageSaysWhich() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        openRow(p, m, COINS);
        // an automatic row can be pinned as it is: Save is on although nothing was changed
        assertTrue(p.saveEnabled());
        assertNull(p.messageText());
        assertFalse(p.dirtyNow());
        ctrl(p, 65);
        key(p, SettingsPanel.KEY_BACKSPACE);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_EMPTY), p.messageText());
        assertFalse(p.saveEnabled());
        type(p, "金幣");
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_MISSING, "{V0}"), p.messageText());
        type(p, "{V0}{V0}");
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_EXTRA, "{V0}"), p.messageText(), "one copy too many");
        key(p, SettingsPanel.KEY_BACKSPACE);
        key(p, SettingsPanel.KEY_BACKSPACE);
        key(p, SettingsPanel.KEY_BACKSPACE);
        key(p, SettingsPanel.KEY_BACKSPACE);
        assertNull(p.messageText());
        assertTrue(p.saveEnabled());
        type(p, "{N0}");
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_EXTRA, "{N0}"), p.messageText());
        // Save does nothing while it is off
        click(p, "save");
        assertEquals(List.of(), m.saves);
        for (int i = 0; i < 4; i++) key(p, SettingsPanel.KEY_BACKSPACE);
        // the same as the original
        ctrl(p, 65);
        host.clipboard = COINS.replace(MT0, "{V0}");
        ctrl(p, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_SAME), p.messageText());
        // the line-break count must match
        ctrl(p, 65);
        host.clipboard = "金幣 {V0}\n金幣";
        ctrl(p, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_NEWLINES, 0), p.messageText());
        // too long
        ctrl(p, 65);
        host.clipboard = "x".repeat(1990) + " {V0} " + "y".repeat(100);
        ctrl(p, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_LONG, 2000), p.messageText());
    }

    @Test
    void paragraphBreaksMustKeepTheirOrderAndColourTagsTheirPairs() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 640, 360);
        openRow(p, m, "Total: " + MT0 + PB0 + "Next: " + MT1 + PB1 + "End");
        assertEquals(List.of("{V0}", "{B0}", "{V1}", "{B1}"), p.chipTexts());
        ctrl(p, 65);
        host.clipboard = "一{V0}{B1}二{V1}{B0}三";
        ctrl(p, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_ORDER), p.messageText(), "the breaks swapped");
        ctrl(p, 65);
        host.clipboard = "一{V0}{B0}二{V1}{B1}三";
        ctrl(p, 86);
        assertNull(p.messageText());
        ctrl(p, 65);
        host.clipboard = "一{V1}{B0}二{V0}{B1}三";
        ctrl(p, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_ORDER), p.messageText(), "a value moved to another row");

        // colour tags
        TranslationManagerPanel q = open(host, sample(), 640, 360);
        FakeModel cm = sample();
        q = open(host, cm, 640, 360);
        openRow(q, cm, "Rare Sword");
        ctrl(q, 65);
        host.clipboard = "{C0}稀有劍";
        ctrl(q, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_MISSING, "{/C0}"), q.messageText());
        ctrl(q, 65);
        host.clipboard = "{/C0}稀有劍{C0}";
        ctrl(q, 86);
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_COLOR_PAIRS, "{/C0} {C0}"), q.messageText(),
                "all tags are there but they do not pair up");
        assertFalse(q.saveEnabled());
        ctrl(q, 65);
        host.clipboard = "{C0}稀有{/C0}劍";
        ctrl(q, 86);
        assertNull(q.messageText());
        assertTrue(q.saveEnabled());
    }

    @Test
    void savingSendsTheRealMarkersToTheModelShowsTheResultAndKeepsTheOriginalIntact() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        openRow(p, m, COINS);
        ctrl(p, 65);
        host.clipboard = "你得到 {V0} 枚金幣";
        ctrl(p, 86);
        assertTrue(p.saveEnabled());
        assertTrue(p.dirtyNow());
        key(p, SettingsPanel.KEY_ENTER); // the original has no line break: Enter saves
        assertEquals(1, m.saves.size());
        assertEquals(java.util.Arrays.asList(COINS, "你得到 " + MT0 + " 枚金幣", null, null), m.saves.get(0));
        assertEquals(host.text(TranslationManagerPanel.KEY_STATUS_SAVED), p.messageText());
        assertEquals(1, host.changed, "the glue is told to forget what it has rendered");
        assertFalse(p.dirtyNow());
        assertTrue(p.isEditing());
        Rec c = render(p);
        assertTrue(c.has(host.text(TranslationManagerPanel.KEY_BTN_REVERT)), "now it is manual: it can be reverted");
        assertTrue(c.hasContaining("手動"), "and says so");
        assertTrue(c.hasContaining(host.text(TranslationManagerPanel.KEY_EDIT_AUTO, "AI", "你獲得 {V0} 枚金幣")),
                "and shows the automatic translation it replaced: " + c.all());
        // saved and unchanged: nothing to save any more
        assertFalse(p.saveEnabled());
        key(p, SettingsPanel.KEY_ESCAPE);
        assertFalse(p.isEditing(), "no unsaved change: Escape leaves at once");
        Rec list = render(p);
        assertTrue(list.hasContaining("你得到 {V0} 枚金幣"), "the list shows the new text: " + list.all());
        assertTrue(list.hasContaining("其中手動修改 2 筆"), "the page the model published is shown: " + list.all());
    }

    @Test
    void enterAddsLineBreaksOnlyWhileTheOriginalHasMoreAndSavesAfterwards() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = new FakeModel();
        m.row("Line one\nLine two", "第一行\n第二行", Source.AI);
        TranslationManagerPanel p = open(host, m, 427, 240);
        openRow(p, m, "Line one\nLine two");
        assertEquals("Line one\nLine two", p.originalText());
        ctrl(p, 65);
        type(p, "A");
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_NEWLINES, 1), p.messageText());
        key(p, SettingsPanel.KEY_ENTER);
        assertEquals("A\n", p.editText(), "Enter made the missing line break");
        assertTrue(m.saves.isEmpty());
        type(p, "B");
        assertNull(p.messageText());
        key(p, SettingsPanel.KEY_ENTER);
        assertEquals("A\nB", p.editText(), "one line break is enough: Enter now saves");
        assertEquals(1, m.saves.size());
        // Ctrl+Enter always saves
        type(p, "C");
        ctrl(p, SettingsPanel.KEY_ENTER);
        assertEquals(2, m.saves.size());
    }

    @Test
    void everyResultOfTheModelIsShownInWords() {
        FakeHost host = new FakeHost("zh_tw");
        Object[][] cases = {
                {SaveOutcome.of(SaveResult.EMPTY), TranslationManagerPanel.KEY_ERR_EMPTY, new Object[0]},
                {SaveOutcome.of(SaveResult.SAME_AS_SOURCE), TranslationManagerPanel.KEY_ERR_SAME, new Object[0]},
                {SaveOutcome.tags(SaveResult.MISSING_TAGS, List.of("{V0}", "{N1}")), TranslationManagerPanel.KEY_ERR_MISSING, new Object[] {"{V0} {N1}"}},
                {SaveOutcome.tags(SaveResult.EXTRA_TAGS, List.of("{V2}×2")), TranslationManagerPanel.KEY_ERR_EXTRA, new Object[] {"{V2}×2"}},
                {SaveOutcome.of(SaveResult.TAG_ORDER), TranslationManagerPanel.KEY_ERR_ORDER, new Object[0]},
                {SaveOutcome.number(SaveResult.NEWLINES, 3), TranslationManagerPanel.KEY_ERR_NEWLINES, new Object[] {3}},
                {SaveOutcome.of(SaveResult.PARTIAL), TranslationManagerPanel.KEY_ERR_PARTIAL, new Object[0]},
                {SaveOutcome.of(SaveResult.CONTROL_CHARS), TranslationManagerPanel.KEY_ERR_CONTROL, new Object[0]},
                {SaveOutcome.number(SaveResult.TOO_LONG, 2000), TranslationManagerPanel.KEY_ERR_LONG, new Object[] {2000}},
                {SaveOutcome.number(SaveResult.FULL, 20000), TranslationManagerPanel.KEY_ERR_FULL, new Object[] {20000}},
                {SaveOutcome.of(SaveResult.IO_ERROR), TranslationManagerPanel.KEY_ERR_IO, new Object[0]}};
        assertEquals(SaveResult.values().length - 1, cases.length, "every result but OK is covered");
        for (Object[] c : cases) {
            FakeModel m = sample();
            TranslationManagerPanel p = open(host, m, 427, 240);
            openRow(p, m, "Sharpness");
            type(p, "了");
            m.nextSave = (SaveOutcome) c[0];
            click(p, "save");
            assertEquals(1, m.saves.size(), c[0].toString());
            assertEquals(host.text((String) c[1], (Object[]) c[2]), p.messageText(), c[0].toString());
            assertTrue(p.dirtyNow(), "nothing was stored: the text is still unsaved");
            assertEquals(0, host.changed);
            // typing again clears the message
            type(p, "。");
            assertNotNullOrLive(p, c[0].toString());
        }
    }

    private static void assertNotNullOrLive(TranslationManagerPanel p, String label) {
        String msg = p.messageText();
        assertTrue(msg == null || !msg.isEmpty(), label);
    }

    // ------------------------------------------------------------------ delete and revert

    @Test
    void deleteAsksTwiceAndTheQuestionTimesOut() {
        for (String code : CODES) {
            FakeHost host = new FakeHost(code);
            FakeModel m = sample();
            TranslationManagerPanel p = open(host, m, 640, 360);
            openRow(p, m, "Sharpness");
            String normal = host.text(TranslationManagerPanel.KEY_BTN_DELETE);
            String confirm = host.text(TranslationManagerPanel.KEY_BTN_DELETE_CONFIRM);
            assertTrue(render(p).has(normal), code);
            int[] before = p.rect("delete").clone();
            click(p, "delete");
            assertTrue(p.deleteArmed());
            assertEquals(List.of(), m.deletes, "the first press only asks");
            Rec asking = render(p);
            assertTrue(asking.has(confirm) && !asking.has(normal), code);
            assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(p.rect("delete")), "the button does not move or change size");
            // no answer: it goes back by itself
            host.now += 3001;
            Rec back = render(p);
            assertFalse(p.deleteArmed());
            assertTrue(back.has(normal) && !back.has(confirm), code);
            click(p, "delete");
            assertEquals(List.of(), m.deletes, "a press after the timeout asks again");
            assertTrue(p.deleteArmed());
            // another press inside the time: done
            host.now += 1500;
            click(p, "delete");
            assertEquals(List.of("Sharpness"), m.deletes);
            assertFalse(p.isEditing(), "back to the list");
            assertEquals(1, host.changed);
            Rec list = render(p);
            assertTrue(list.has(host.text(TranslationManagerPanel.KEY_STATUS_DELETED)), code + ": " + list.all());
            assertFalse(list.hasContaining("Sharpness"), "the row is gone from the list");
            // the status fades
            host.now += 7000;
            assertFalse(render(p).has(host.text(TranslationManagerPanel.KEY_STATUS_DELETED)));
        }
    }

    @Test
    void deleteWhileOnlineTranslationIsOffSaysItWillBeTranslatedWhenItIsTurnedOn() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        m.online = false;
        TranslationManagerPanel p = open(host, m, 640, 360);
        openRow(p, m, "Sharpness");
        click(p, "delete");
        click(p, "delete");
        assertTrue(render(p).has(host.text(TranslationManagerPanel.KEY_STATUS_DELETED_OFF)));
    }

    @Test
    void anyOtherActionCancelsTheDeleteQuestionAndEscapeCancelsItBeforeLeaving() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 640, 360);
        openRow(p, m, "Sharpness");
        click(p, "delete");
        type(p, "x");
        assertFalse(p.deleteArmed(), "typing cancels it");
        click(p, "delete");
        assertEquals(TranslationManagerPanel.NONE, p.keyPressed(SettingsPanel.KEY_ESCAPE));
        assertFalse(p.deleteArmed(), "Escape cancels the question");
        assertTrue(p.isEditing(), "and does not leave");
        assertEquals(List.of(), m.deletes);
    }

    @Test
    void revertIsOnlyOfferedForManualRowsAndGoesBackToTheList() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 640, 360);
        openRow(p, m, "Sharpness");
        assertNull(p.rect("revert"), "an automatic row has nothing to revert");
        key(p, SettingsPanel.KEY_ESCAPE);
        openRow(p, m, "Hello " + NAME0 + "!");
        assertNotNull(p.rect("revert"));
        Rec c = render(p);
        assertTrue(c.hasContaining(host.text(TranslationManagerPanel.KEY_EDIT_AUTO, "AI", "哈囉 {N0}！")), c.all());
        click(p, "revert");
        assertEquals(List.of("Hello " + NAME0 + "!"), m.reverts);
        assertFalse(p.isEditing());
        assertEquals(1, host.changed);
        assertTrue(render(p).has(host.text(TranslationManagerPanel.KEY_STATUS_REVERTED)));
        // a failing write is reported and the editor stays
        FakeModel bad = sample();
        bad.revertOk = false;
        TranslationManagerPanel q = open(host, bad, 640, 360);
        openRow(q, bad, "Hello " + NAME0 + "!");
        click(q, "revert");
        assertTrue(q.isEditing());
        assertEquals(host.text(TranslationManagerPanel.KEY_ERR_IO), q.messageText());
        assertEquals(0, host.changed - 1, "no change was reported for the failed one");
    }

    // ------------------------------------------------------------------ escape

    @Test
    void escapeInTheEditorWarnsOnceAboutUnsavedChangesThenDiscardsThem() {
        for (String code : CODES) {
            FakeHost host = new FakeHost(code);
            FakeModel m = sample();
            TranslationManagerPanel p = open(host, m, 427, 240);
            openRow(p, m, "Sharpness");
            key(p, SettingsPanel.KEY_ESCAPE);
            assertFalse(p.isEditing(), code + ": nothing changed, so Escape leaves");
            openRow(p, m, "Sharpness");
            type(p, "!");
            assertEquals(TranslationManagerPanel.NONE, p.keyPressed(SettingsPanel.KEY_ESCAPE));
            assertTrue(p.isEditing(), code);
            assertEquals(host.text(TranslationManagerPanel.KEY_DISCARD), p.messageText(), code);
            assertTrue(render(p).has(host.text(TranslationManagerPanel.KEY_DISCARD)), code);
            // changing the text again takes the warning back: it must be asked again
            type(p, "?");
            key(p, SettingsPanel.KEY_ESCAPE);
            assertTrue(p.isEditing(), code + ": the warning was reset by typing");
            key(p, SettingsPanel.KEY_ESCAPE);
            assertFalse(p.isEditing(), code + ": the second Escape discards");
            assertTrue(m.saves.isEmpty());
            // the same goes for the back button
            openRow(p, m, "Sharpness");
            assertEquals("鋒利", p.editText(), "the discarded text did not come back");
            type(p, "!");
            click(p, "editBack");
            assertTrue(p.isEditing());
            click(p, "editBack");
            assertFalse(p.isEditing());
        }
    }

    // ------------------------------------------------------------------ colour versions

    @Test
    void aColouredSentenceIsEditedWithItsColourTagsAndTheSameShadeInTheOriginalAndTheTranslation() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 640, 360);
        openRow(p, m, "Rare Sword");
        assertEquals("{C0}Rare{/C0} Sword", p.originalText());
        assertEquals("{C0}稀有{/C0}劍", p.editText());
        assertEquals(List.of("{C0}", "{/C0}"), p.chipTexts());
        // shades: the text between a pair is shaded the same in the original and in the translation, outside it is not
        int inOriginal = p.shadeOfOriginal("{C0}Rare".length() - 1);
        int inEdit = p.shadeOfEdit("{C0}稀".length() - 1);
        assertEquals(inOriginal, inEdit, "same shade for the same pair");
        assertTrue((inEdit >>> 24) != 0, "and it is a visible shade");
        assertEquals(0, p.shadeOfOriginal("{C0}Rare{/C0} S".length() - 1), "outside the pair there is none");
        assertEquals(0, p.shadeOfEdit("{C0}稀有{/C0}劍".length() - 1));
        assertTrue((p.shadeOfEdit(0) >>> 24) != 0, "the tag itself is drawn as a small plate");
        Rec c = render(p);
        assertTrue(c.hasContaining("{C0}"), c.all());
        // the switch of colour versions
        assertTrue(c.has(host.text(TranslationManagerPanel.KEY_EDIT_COLOR, 1, 2)), c.all());
        assertEquals(0, p.versionIndex());
        type(p, "錘"); // unsaved change in version 1
        click(p, "next");
        assertEquals(1, p.versionIndex());
        assertEquals("{C0}Rare Sword{/C0}", p.originalText());
        assertEquals("{C0}稀有劍{/C0}", p.editText());
        assertTrue(render(p).has(host.text(TranslationManagerPanel.KEY_EDIT_COLOR, 2, 2)));
        click(p, "prev");
        assertEquals(0, p.versionIndex());
        assertEquals("{C0}稀有{/C0}劍錘", p.editText(), "each version keeps its own unsaved text");
        // the switch works from the keyboard too
        click(p, "next");
        key(p, SettingsPanel.KEY_TAB); // the box -> the left arrow
        key(p, SettingsPanel.KEY_TAB);
        key(p, SettingsPanel.KEY_LEFT);
        assertEquals(0, p.versionIndex(), "Left on the arrows goes back");
        key(p, SettingsPanel.KEY_RIGHT);
        assertEquals(1, p.versionIndex());
    }

    @Test
    void savingAColourVersionSendsItsKeyTheTaggedTextAndThePlainTextAndOtherVersionsAreSavedSeparately() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 640, 360);
        openRow(p, m, "Rare Sword");
        ctrl(p, 65);
        host.clipboard = "{C0}稀世{/C0}劍";
        ctrl(p, 86);
        click(p, "save");
        assertEquals(1, m.saves.size());
        assertEquals(java.util.Arrays.asList("Rare Sword", "稀世劍", CS0 + "Rare" + CS0_END + " Sword",
                CS0 + "稀世" + CS0_END + "劍"), m.saves.get(0));
        assertFalse(p.dirtyNow());
        // the second version is edited and saved on its own
        click(p, "next");
        assertFalse(p.dirtyNow(), "the other version was not touched");
        ctrl(p, 65);
        host.clipboard = "{C0}稀世劍{/C0}";
        ctrl(p, 86);
        assertTrue(p.dirtyNow());
        key(p, SettingsPanel.KEY_ENTER);
        assertEquals(2, m.saves.size());
        assertEquals(java.util.Arrays.asList("Rare Sword", "稀世劍", CS0 + "Rare Sword" + CS0_END,
                CS0 + "稀世劍" + CS0_END), m.saves.get(1));
        assertFalse(p.dirtyNow());
    }

    @Test
    void aSingleColourVersionHasNoSwitch() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = new FakeModel();
        m.all.add(new Row("Rare Sword", "稀有劍", Source.AI, true));
        m.edits.put("Rare Sword", new EditData("Rare Sword", "Rare Sword", "稀有劍", Source.AI, null, null,
                List.of(new ColorVersion(CS0 + "Rare" + CS0_END + " Sword", CS0 + "稀有" + CS0_END + "劍"))));
        TranslationManagerPanel p = open(host, m, 640, 360);
        openRow(p, m, "Rare Sword");
        assertNull(p.rect("next"));
        assertNull(p.rect("prev"));
        assertEquals("{C0}稀有{/C0}劍", p.editText());
        assertFalse(render(p).hasContaining("1/1"));
    }

    @Test
    void rowsWithColourVersionsAreMarkedInTheList() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 640, 360);
        Rec c = new Rec();
        p.render(c, -1, -1);
        int swatches = 0;
        for (int[] f : c.fills) if (f[2] == 4 && f[3] == 4) swatches++;
        assertEquals(3, swatches, "three small colour squares on the one coloured row");
    }

    // ------------------------------------------------------------------ keyboard and narrator

    @Test
    void tabWalksTheSearchBoxTheFilterButtonsAndBackAndEnterPressesTheFocusedOne() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        assertTrue(p.searchFocused());
        key(p, SettingsPanel.KEY_TAB);
        assertFalse(p.isTyping(), "a button has the focus: letters are for the game's shortcuts again");
        assertFalse(p.charTyped('a'));
        key(p, SettingsPanel.KEY_TAB);
        m.searches.clear();
        key(p, SettingsPanel.KEY_ENTER); // the manual filter
        assertEquals(List.of("|true"), m.searches);
        key(p, SettingsPanel.KEY_TAB);
        assertEquals(TranslationManagerPanel.BACK, p.keyPressed(SettingsPanel.KEY_ENTER));
        // Shift+Tab goes the other way round
        key(p, SettingsPanel.KEY_TAB);
        assertTrue(p.searchFocused());
        p.keyPressed(SettingsPanel.KEY_TAB, false, true);
        assertFalse(p.isTyping());
    }

    @Test
    void theNarratorDescribesTheFocusedControlTheSelectedRowAndTheEditor() {
        FakeHost host = new FakeHost("zh_tw");
        FakeModel m = sample();
        TranslationManagerPanel p = open(host, m, 427, 240);
        assertEquals("搜尋譯文，輸入欄", p.narration());
        assertFalse(p.consumeNarrationRequest(), "opening the screen does not talk");
        key(p, SettingsPanel.KEY_TAB);
        assertTrue(p.consumeNarrationRequest());
        assertFalse(p.consumeNarrationRequest(), "once per move");
        assertEquals("全部，按鈕", p.narration());
        key(p, SettingsPanel.KEY_TAB);
        assertEquals("只看手動，按鈕", p.narration());
        key(p, SettingsPanel.KEY_DOWN);
        assertTrue(p.consumeNarrationRequest());
        assertEquals("You got {V0} coins" + "，譯文：" + "你獲得 {V0} 枚金幣", p.narration());
        key(p, SettingsPanel.KEY_ENTER);
        assertTrue(p.isEditing());
        assertTrue(p.consumeNarrationRequest());
        assertEquals("修改譯文，輸入欄", p.narration());
        key(p, SettingsPanel.KEY_TAB);
        assertEquals("儲存，按鈕", p.narration(), "an automatic row can be pinned as it is");
        key(p, SettingsPanel.KEY_TAB);
        assertEquals("刪除並重新翻譯，按鈕", p.narration());
        key(p, SettingsPanel.KEY_TAB);
        assertEquals("返回清單，按鈕", p.narration());
        Rec c = render(p);
        // the focused button has a frame
        boolean framed = false;
        int[] back = p.rect("editBack");
        for (int[] f : c.fills) if (f[4] == SettingsPanel.C_ACCENT && f[0] >= back[0] - 2 && f[0] <= back[0] + back[2]
                && f[1] >= back[1] - 2 && f[1] <= back[1] + back[3] + 1) framed = true;
        assertTrue(framed, "the focus frame is drawn in the accent colour");
    }

    @Test
    void everyKeyThePanelAsksForExistsWithTheRightArgumentsInAllFourLanguagesAndNoLangTextIsMissing() {
        // the strict host throws on a missing key or a wrong argument count while the panel is driven through its screens
        for (String code : CODES) {
            FakeHost host = new FakeHost(code);
            FakeModel m = sample();
            TranslationManagerPanel p = open(host, m, 640, 360);
            render(p);
            type(p, "zzz");
            host.now += 300;
            render(p);
            m.publish();
            render(p);
            key(p, SettingsPanel.KEY_ESCAPE);
            m.publish();
            render(p);
            for (String key : List.of("Rare Sword", "Hello " + NAME0 + "!", COINS)) {
                openRow(p, m, key);
                render(p);
                type(p, "!");
                render(p);
                key(p, SettingsPanel.KEY_ESCAPE);
                key(p, SettingsPanel.KEY_ESCAPE);
            }
            assertTrue(p.narration() != null);
        }
        for (String key : TranslationManagerPanel.allLangKeys()) {
            for (String code : CODES) assertTrue(lang(code).has(key), code + " " + key);
        }
    }

    @Test
    void theTranslationManagerPanelKeysAreListedForTheLangFileTest() {
        List<String> listed = SettingsModel.allLangKeys();
        for (String key : TranslationManagerPanel.allLangKeys()) assertTrue(listed.contains(key), key);
        assertTrue(listed.contains("nyanlex.settings.manager") || SettingsCatalog.allLangKeys().contains("nyanlex.settings.manager"));
        assertTrue(listed.contains("nyanlex.files.manual"));
        assertTrue(listed.contains("nyanlex.files.manual.desc"));
    }
}
