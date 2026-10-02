package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslatorConfig;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.Arrays;
import java.util.List;

/**
 * 不翻譯詞彙過濾 — the do-not-translate term list. The box is parsed and saved on
 * Done / close (Esc). The master send/stop switch lives on the main settings screen.
 */
public final class TranslationRequestsScreen extends Screen {

    private static final int W = 320;
    /** Term separators: ASCII comma, full-width comma (，), ideographic comma (、), newline. */
    private static final String TERM_SEPARATORS = "[,，、\\n]";

    private final Screen parent;
    private EditBox termsBox;
    /** Box text as first shown; an untouched box is never re-parsed or rewritten. */
    private String shownTerms;
    private int contentX;
    private int termsLabelY;
    private int termsHintY;
    private List<FormattedCharSequence> termsHint = List.of();

    public TranslationRequestsScreen(Screen parent) {
        super(Component.translatable("screen.nyanlex.requests.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanLexFabric.config();
        int contentW = Math.max(120, Math.min(W, this.width - 20));
        contentX = this.width / 2 - contentW / 2;
        int lineH = this.font.lineHeight + 1;
        int y = 40;

        termsLabelY = y;
        y += lineH + 2;
        // A resize re-runs init(): keep what the user typed but has not saved yet.
        String text = termsBox != null ? termsBox.getValue() : joinTerms(cfg.doNotTranslateTerms);
        termsBox = new EditBox(this.font, contentX, y, contentW, 20,
                Component.translatable("screen.nyanlex.requests.terms")) {
            @Override
            public void insertText(String typed) {
                // EditBox drops line breaks, which would glue a pasted one-per-line list into
                // a single term: turn them into separators first.
                super.insertText(typed == null ? null : typed.replaceAll("[\\r\\n]+", ", "));
            }
        };
        termsBox.setMaxLength(8000);
        termsBox.setValue(text);
        if (shownTerms == null) shownTerms = termsBox.getValue();
        this.addRenderableWidget(termsBox);
        y += 26;

        termsHintY = y;
        termsHint = this.font.split(Component.translatable("screen.nyanlex.requests.terms.hint"), contentW);
        y += termsHint.size() * lineH + 12;

        int doneW = Math.min(200, contentW);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(this.width / 2 - doneW / 2, y, doneW, 20).build());
    }

    /** Stored terms as the box shows them: joined with ", ". */
    static String joinTerms(List<String> terms) {
        return terms == null ? "" : String.join(", ", terms);
    }

    /** Box text to the stored list: split on , ， 、 and newlines, then trimmed, blanks
     *  dropped and case-insensitive duplicates removed (the first spelling is kept). */
    static List<String> parseTerms(String raw) {
        if (raw == null) return TranslatorConfig.normalizedTerms(null);
        return TranslatorConfig.normalizedTerms(Arrays.asList(raw.split(TERM_SEPARATORS)));
    }

    /** Parse and persist the box; the service reads the new list on its next lookup. */
    private void saveTerms() {
        if (termsBox == null) return;
        String raw = termsBox.getValue();
        if (raw.equals(shownTerms)) return;
        shownTerms = raw;
        TranslatorConfig cfg = NyanLexFabric.config();
        List<String> terms = parseTerms(raw);
        if (terms.equals(cfg.doNotTranslateTerms)) return;
        cfg.doNotTranslateTerms = terms; // a fresh list: never mutate one a worker may be reading
        NyanLexFabric.saveConfig();
        NyanLexFabric.clearFtbPending();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("screen.nyanlex.requests.terms"),
                contentX, termsLabelY, 0xFFA0A0A0, false);
        drawCentered(graphics, termsHint, termsHintY);
    }

    private void drawCentered(GuiGraphics graphics, List<FormattedCharSequence> lines, int y) {
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFF909090);
            y += this.font.lineHeight + 1;
        }
    }

    @Override
    public void onClose() {
        saveTerms();
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }

    @Override
    public void removed() {
        // Also reached when something else replaces this screen (disconnect, a server GUI):
        // keep what was typed. After onClose() this is a no-op (the box is already saved).
        saveTerms();
        super.removed();
    }
}
