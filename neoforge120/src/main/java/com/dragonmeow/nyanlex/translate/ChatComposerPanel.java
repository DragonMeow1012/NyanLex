package com.dragonmeow.nyanlex.translate;

import java.util.function.BiConsumer;
import java.util.Locale;
import java.text.Normalizer;

/** Shared, client-thread-only floating composer. The host owns native text editing and sending. */
public final class ChatComposerPanel {
    // Session-only: closing chat must not discard the player's unfinished source text.
    private static String savedDraft = "";

    public static String savedDraft() { return savedDraft; }

    public interface Host {
        String text(String key);
        int textWidth(String text);
        String draft();
        String chat();
        String language();
        void language(String tag);
        String[][] languages();
        double positionX();
        double positionY();
        void position(double x, double y);
        void fill(String text);
        boolean current();
        void execute(Runnable action);
        void translate(String source, String target, BiConsumer<String, String> result);
    }
    public interface Canvas {
        void fill(int x, int y, int width, int height, int color);
        void text(String text, int x, int y, int color);
    }

    private final Host host;
    private int x, y, width, screenWidth, screenHeight;
    private int dragX, dragY, languageOffset;
    private boolean dragging, choosing, closed, busy;
    private long revision;
    private String status = "hint";
    private String lastDraft, lastChat;
    private String search = "";
    private static final int SEARCH_HEIGHT = 36;
    public static final int HEIGHT = 90;

    public ChatComposerPanel(Host host) { this.host = host; }

    public void resize(int w, int h) {
        screenWidth = w;
        screenHeight = h;
        width = Math.max(40, Math.min(320, w - 8));
        int maxX = Math.max(0, w - width - 4);
        int maxY = Math.max(0, h - HEIGHT - 26);
        x = clamp((int) Math.round(finite(host.positionX(), 1) * maxX), 0, maxX);
        y = clamp((int) Math.round(finite(host.positionY(), 1) * maxY), 0, maxY);
    }

    private static double finite(double value, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : Math.max(0, Math.min(1, value));
    }
    private static int clamp(int n, int min, int max) { return Math.max(min, Math.min(max, n)); }
    public int inputX() { return x + 8; }
    public int inputY() { return y + 23; }
    public int inputWidth() { return width - 16; }
    public boolean busy() { return busy; }
    public boolean contains(double mx, double my) { return inside(mx, my, x, y, width, HEIGHT); }
    public boolean choosing() { return choosing; }
    public int searchX() { return x + 8; }
    public int searchY() { return listY() + 14; }
    public void search(String value) {
        search = value == null ? "" : searchText(value.trim());
        languageOffset = 0;
    }
    private static String searchText(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('_', '-');
    }
    public String[][] matchingLanguages() {
        if (search.isEmpty()) return host.languages();
        java.util.List<String[]> matches = new java.util.ArrayList<>();
        for (String[] row : host.languages()) {
            String text = row[0] + " " + row[1] + " " + languageLabel(row[0], row[1])
                    + " " + Locale.forLanguageTag(row[0].replace('_', '-')).getDisplayName(Locale.ENGLISH);
            if (searchText(text).contains(search)) matches.add(row);
        }
        return matches.toArray(new String[0][]);
    }
    public void closeChoices() { choosing = false; }
    public void chooseFirst() {
        String[][] rows = matchingLanguages();
        if (rows.length > languageOffset) selectLanguage(rows[languageOffset][0]);
    }
    private void selectLanguage(String tag) {
        host.language(tag);
        revision++;
        status = "hint";
        choosing = false;
    }

    /** Called after any native edit, including vanilla history/command completion. */
    public void observe() {
        String draft = host.draft(), chat = host.chat();
        if (lastDraft != null && (!lastDraft.equals(draft) || !lastChat.equals(chat))) {
            revision++;
            if (busy) status = "changed";
        }
        lastDraft = draft;
        lastChat = chat;
    }

    public void submit() {
        observe();
        if (busy || closed || host.draft().trim().isEmpty()) return;
        if (host.draft().trim().startsWith("/")) { status = "command"; return; }
        final long request = ++revision;
        final String draft = host.draft(), chat = host.chat(), language = host.language();
        busy = true;
        status = "working";
        host.translate(draft, language, (translated, error) -> host.execute(() -> {
            if (closed) return;
            busy = false;
            observe();
            if (!host.current() || revision != request || !draft.equals(host.draft())
                    || !chat.equals(host.chat()) || !language.equals(host.language())) {
                status = "changed";
                return;
            }
            if (translated == null || translated.trim().isEmpty()) {
                status = error == null ? "failed" : error;
                return;
            }
            String value = translated.replace('\r', ' ').replace('\n', ' ').trim();
            if (value.length() > 256) { status = "too_long"; return; }
            if (value.startsWith("/") || value.indexOf('\u00a7') >= 0
                    || value.chars().anyMatch(c -> c < 32 || c == 127)) {
                status = "invalid";
                return;
            }
            host.fill(value);
            lastChat = host.chat();
            status = "ready";
        }));
    }

    public void close() {
        if (closed) return;
        savedDraft = host.draft();
        closed = true;
        revision++;
        dragging = false;
    }

    public boolean click(double mx, double my, int button) {
        if (button != 0) return contains(mx, my);
        if (choosing) {
            int ly = listY();
            if (inside(mx, my, x + 8, ly, width - 16, SEARCH_HEIGHT)) return true;
            if (inside(mx, my, x + 8, ly + SEARCH_HEIGHT, width - 16, listHeight() - SEARCH_HEIGHT)) {
                int index = languageOffset + ((int) my - ly - SEARCH_HEIGHT) / 16;
                String[][] rows = matchingLanguages();
                if (index < rows.length) selectLanguage(rows[index][0]);
                return true;
            }
            choosing = false;
            return true;
        }
        if (!contains(mx, my)) return false;
        if (my < y + 20) {
            dragging = true;
            dragX = (int) mx - x;
            dragY = (int) my - y;
        } else if (my >= y + 48 && my < y + 68) {
            if (mx < x + width / 2) choosing = true;
            else submit();
        }
        return true;
    }

    public boolean drag(double mx, double my) {
        if (!dragging) return false;
        x = clamp((int) mx - dragX, 0, Math.max(0, screenWidth - width - 4));
        y = clamp((int) my - dragY, 0, Math.max(0, screenHeight - HEIGHT - 26));
        return true;
    }
    public boolean release() {
        if (!dragging) return false;
        dragging = false;
        host.position((double) x / Math.max(1, screenWidth - width - 4),
                (double) y / Math.max(1, screenHeight - HEIGHT - 26));
        return true;
    }
    public boolean scroll(double amount) {
        if (!choosing) return false;
        languageOffset = clamp(languageOffset + (amount > 0 ? -1 : 1), 0,
                Math.max(0, matchingLanguages().length - visibleLanguages()));
        return true;
    }
    private int visibleLanguages() { return Math.max(1, Math.min(7, (screenHeight - 32 - SEARCH_HEIGHT) / 16)); }
    private int listHeight() { return SEARCH_HEIGHT + visibleLanguages() * 16; }
    private int listY() { return clamp(y - listHeight(), 0, Math.max(0, screenHeight - listHeight() - 24)); }
    private String label(String key) { return host.text("nyanlex.composer." + key); }
    private String languageLabel(String tag, String nativeName) {
        Locale target = Locale.forLanguageTag(tag.replace('_', '-'));
        Locale client = Locale.forLanguageTag(host.text("language.code").replace('_', '-'));
        if (client.getLanguage().isEmpty() || target.getLanguage().isEmpty()) return nativeName;
        String name = target.getDisplayName(client);
        return name.isEmpty() || name.equalsIgnoreCase(tag) ? nativeName : name;
    }
    private String fit(String text, int maxWidth) {
        if (host.textWidth(text) <= maxWidth) return text;
        while (!text.isEmpty() && host.textWidth(text + "…") > maxWidth)
            text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        return text + "…";
    }
    public void render(Canvas g) {
        observe();
        g.fill(x, y, width, HEIGHT, 0xEF202433);
        g.fill(x, y, width, 19, 0xFF45415F);
        g.text(fit(label("title") + "  ⋮⋮", width - 16), x + 8, y + 5, 0xFFFFFFFF);
        g.fill(x + 8, y + 48, width / 2 - 12, 20, 0xFF363C55);
        g.fill(x + width / 2, y + 48, width / 2 - 8, 20, busy ? 0xFF343744 : 0xFF38765E);
        String language = host.language();
        for (String[] row : host.languages()) if (row[0].equals(language)) { language = languageLabel(row[0], row[1]); break; }
        g.text(fit(label("target_language").replace("%s", language) + " ▾", width / 2 - 24), x + 13, y + 54, 0xFFFFFFFF);
        g.text(fit(label(busy ? "working" : "fill"), width / 2 - 20), x + width / 2 + 5, y + 54, 0xFFFFFFFF);
        g.text(fit(label(status), width - 16), x + 8, y + 76,
                "ready".equals(status) ? 0xFF8EE5B1 : 0xFFC6C8D3);
    }
    public void renderChoices(Canvas g) {
        if (!choosing) return;
        int ly = listY();
        g.fill(x + 7, ly - 1, width - 14, listHeight() + 2, 0xFF77708F);
        g.fill(x + 8, ly, width - 16, SEARCH_HEIGHT, 0xFF272B3B);
        g.text(fit(host.text("screen.nyanlex.language.search"), width - 26), x + 13, ly + 3, 0xFFC6C8D3);
        String[][] rows = matchingLanguages();
        if (rows.length == 0) g.text(fit(label("no_languages"), width - 26), x + 13, ly + SEARCH_HEIGHT + 4, 0xFFC6C8D3);
        for (int i = 0; i < visibleLanguages() && languageOffset + i < rows.length; i++) {
            String[] row = rows[languageOffset + i];
            g.fill(x + 8, ly + SEARCH_HEIGHT + i * 16, width - 16, 16,
                    row[0].equals(host.language()) ? 0xFF38765E : 0xFF272B3B);
            g.text(fit(languageLabel(row[0], row[1]) + " (" + row[0] + ")", width - 26), x + 13, ly + SEARCH_HEIGHT + i * 16 + 4, 0xFFFFFFFF);
        }
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }
}
