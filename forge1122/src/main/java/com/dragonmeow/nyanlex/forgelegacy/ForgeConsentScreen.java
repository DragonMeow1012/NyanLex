package com.dragonmeow.nyanlex.forgelegacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Asks before the first request goes out: shown when R or P is pressed while online
 * translation is off. This API generation cannot draw an overlay inside another screen, so it
 * is a screen of its own, but it never closes the screen underneath: it swaps itself in as the
 * current screen without going through displayGuiScreen, so an open container is not told to
 * close. Cancel (or Esc) puts the original screen back untouched. Neither button has focus.
 */
final class ForgeConsentScreen extends GuiScreen {
    private static final int BOX_W = 280;
    private static final int BUTTON_W = 130;
    private static final int LINE_H = 10;
    private static final int CANCEL = 1;
    private static final int ACCEPT = 2;

    private final GuiScreen parent;
    private final boolean screenScope;
    private final Runnable onAccept;
    private final int openedWidth;
    private final int openedHeight;
    private List<String> body = Collections.emptyList();
    private List<String> note = Collections.emptyList();
    private int boxTop;
    private int boxBottom;

    private ForgeConsentScreen(GuiScreen parent, boolean screenScope, Runnable onAccept, int w, int h) {
        this.parent = parent;
        this.screenScope = screenScope;
        this.onAccept = onAccept;
        this.openedWidth = w;
        this.openedHeight = h;
    }

    /** Opens the consent window; onAccept runs after online translation was switched on. */
    static void open(Minecraft mc, boolean screenScope, Runnable onAccept) {
        if (mc == null) return;
        ScaledResolution size = new ScaledResolution(mc);
        int w = size.getScaledWidth();
        int h = size.getScaledHeight();
        GuiScreen current = mc.currentScreen;
        ForgeConsentScreen consent = new ForgeConsentScreen(current, screenScope, onAccept, w, h);
        if (current == null) {
            mc.displayGuiScreen(consent);
        } else {
            mc.currentScreen = consent;
            consent.setWorldAndResolution(mc, w, h);
        }
    }

    @Override public void initGui() {
        LegacyConfig cfg = NyanLexForge.config();
        int textW = BOX_W - 24;
        body = fontRenderer.listFormattedStringToWidth(I18n.format(screenScope
                ? "screen.nyanlex.consent.body.screen" : "screen.nyanlex.consent.body.item",
                LegacyUiModel.serviceName(ForgeFormScreen.TEXT, cfg)), textW);
        note = fontRenderer.listFormattedStringToWidth(I18n.format("screen.nyanlex.consent.note"), textW);
        int contentH = 22 + body.size() * LINE_H + 8 + note.size() * LINE_H + 14 + 20 + 12;
        boxTop = Math.max(8, height / 2 - contentH / 2);
        boxBottom = boxTop + contentH;
        int buttonsY = boxBottom - 12 - 20;
        addButton(new GuiButton(CANCEL, width / 2 - 6 - BUTTON_W, buttonsY, BUTTON_W, 20,
                I18n.format("screen.nyanlex.consent.cancel")));
        addButton(new GuiButton(ACCEPT, width / 2 + 6, buttonsY, BUTTON_W, 20,
                I18n.format("screen.nyanlex.consent.accept")));
    }

    private void restoreParent() {
        if (parent == null) {
            mc.displayGuiScreen(null);
            return;
        }
        mc.currentScreen = parent;
        ScaledResolution size = new ScaledResolution(mc);
        if (size.getScaledWidth() != openedWidth || size.getScaledHeight() != openedHeight) {
            parent.setWorldAndResolution(mc, size.getScaledWidth(), size.getScaledHeight());
        }
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ACCEPT) accept();
        else restoreParent();
    }

    void accept() {
        NyanLexForge.config().translationRequestsEnabled = true;
        NyanLexForge.save();
        restoreParent();
        if (onAccept != null) onAccept.run();
    }

    @Override protected void keyTyped(char typed, int key) throws IOException {
        if (key == 1) restoreParent();
    }

    @Override public void drawScreen(int mouseX, int mouseY, float delta) {
        if (parent != null) parent.drawScreen(-1, -1, delta);
        drawGradientRect(0, 0, width, height, 0xC0101010, 0xD0101010);
        int left = width / 2 - BOX_W / 2;
        drawRect(left, boxTop, left + BOX_W, boxBottom, 0xFF1E1E1E);
        drawRect(left, boxTop, left + BOX_W, boxTop + 1, 0xFF5E8BFF);
        String heading = I18n.format("screen.nyanlex.consent.title");
        fontRenderer.drawStringWithShadow(heading, width / 2f - fontRenderer.getStringWidth(heading) / 2f, boxTop + 8, 0xFFFFFF);
        int y = boxTop + 22;
        for (String line : body) { fontRenderer.drawStringWithShadow(line, left + 12, y, 0xE0E0E0); y += LINE_H; }
        y += 8;
        for (String line : note) { fontRenderer.drawStringWithShadow(line, left + 12, y, 0x909090); y += LINE_H; }
        super.drawScreen(mouseX, mouseY, delta);
    }

    @Override public void onGuiClosed() { }
}
