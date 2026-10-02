package com.dragonmeow.nyanlex.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Do-not-translate terms filter (1.0.7) for the Java 8 client UI. */
final class LegacyRequestsScreen extends Screen {
    private static final int FIELD_W = 310;
    private static final int TERMS_MAX_LENGTH = 8000;
    private static final int LINE_H = 10;
    /** Term separators: ASCII comma, full-width comma (U+FF0C), ideographic comma (U+3001), newline. */
    private static final String TERM_SEPARATORS = "[,\uFF0C\u3001\\n]";

    private final Screen parent;
    private EditBox termsBox;
    /** Box text as first shown; unchanged text is not re-parsed, so hand-edited terms survive. */
    private String shownTerms;
    /** Set once this display session has been applied and saved; init() starts a new session. */
    private boolean committed;
    private List<FormattedCharSequence> termsHint = Collections.emptyList();
    private int termsLabelY;
    private int termsHintY;

    LegacyRequestsScreen(Screen parent) {
        super(new TranslatableComponent("screen.nyanlex.requests.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        int x = width / 2 - FIELD_W / 2;
        committed = false;
        // init() re-runs on window resize: keep what the user typed.
        String draft = termsBox == null ? formatTerms(cfg.doNotTranslateTerms) : termsBox.getValue();
        termsLabelY = 40;
        termsBox = new EditBox(font, x, termsLabelY + 11, FIELD_W, 20,
                new TranslatableComponent("screen.nyanlex.requests.terms"));
        termsBox.setMaxLength(TERMS_MAX_LENGTH);
        termsBox.setValue(draft);
        if (shownTerms == null) shownTerms = termsBox.getValue();
        addButton(termsBox);
        termsHintY = termsBox.y + 26;
        termsHint = font.split(new TranslatableComponent("screen.nyanlex.requests.terms.hint"), FIELD_W);
        addButton(new Button(width / 2 - 100, height - 26, 200, 20,
                new TranslatableComponent("gui.done"), button -> onClose()));
    }

    /** Applies the typed terms to the live config (the client tick picks the change up). */
    private void applyTerms() {
        if (termsBox == null) return;
        String value = termsBox.getValue();
        if (value.equals(shownTerms)) return;
        LegacyTranslatorMod.config().doNotTranslateTerms = parseTerms(value);
        shownTerms = value;
    }

    /** Split on the term separators, then trim, drop blanks, case-insensitive de-duplication. */
    static List<String> parseTerms(String raw) {
        List<String> parts = new ArrayList<String>();
        if (raw != null) Collections.addAll(parts, raw.split(TERM_SEPARATORS));
        return LegacyConfig.normalizeDoNotTranslateTerms(parts);
    }

    static String formatTerms(List<String> terms) {
        StringBuilder out = new StringBuilder();
        for (String term : LegacyConfig.normalizeDoNotTranslateTerms(terms)) {
            if (out.length() > 0) out.append(", ");
            out.append(term);
        }
        return out.toString();
    }

    @Override public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        renderBackground(pose);
        GuiComponent.drawCenteredString(pose, font, title, width / 2, 20, 0xFFFFFF);
        int x = width / 2 - FIELD_W / 2;
        font.drawShadow(pose, new TranslatableComponent("screen.nyanlex.requests.terms").getString(),
                x, termsLabelY, 0xA0A0A0);
        drawLines(pose, termsHint, x, termsHintY);
        super.render(pose, mouseX, mouseY, delta);
    }

    private void drawLines(PoseStack pose, List<FormattedCharSequence> lines, int x, int y) {
        for (int i = 0; i < lines.size(); i++) font.drawShadow(pose, lines.get(i), x, y + i * LINE_H, 0xA0A0A0);
    }

    @Override public void tick() {
        if (termsBox != null) termsBox.tick();
    }

    /**
     * Applies the typed terms and saves the config once per display session. Idempotent: onClose()
     * commits, then Minecraft.setScreen(parent) calls removed(), which finds nothing left to do.
     */
    private void commit() {
        if (committed) return;
        committed = true;
        applyTerms();
        LegacyTranslatorMod.saveConfig();
    }

    @Override public void onClose() {
        commit();
        minecraft.setScreen(parent);
    }

    /** Replaced from outside (server-opened container, disconnect, death) or game shutdown. */
    @Override public void removed() {
        commit();
    }
}
