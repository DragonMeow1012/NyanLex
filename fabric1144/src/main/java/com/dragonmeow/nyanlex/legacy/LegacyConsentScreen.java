package com.dragonmeow.nyanlex.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.Collections;
import java.util.List;

/**
 * Asks before the first request goes out: shown when R or P is pressed while online
 * translation is off. This API generation cannot draw an overlay inside another screen, so it
 * is a screen of its own, but it never closes the screen underneath: it swaps itself in as the
 * current screen without going through {@code setScreen}, so an open container is not told to
 * close. Cancel (or Esc) puts the original screen back untouched. Neither button has focus.
 */
final class LegacyConsentScreen extends Screen {
    private static final int BOX_W = 280;
    private static final int BUTTON_W = 130;
    private static final int LINE_H = 10;

    private final Screen parent;
    private final boolean screenScope;
    private final Runnable onAccept;
    private final int openedWidth;
    private final int openedHeight;
    private List<String> body = Collections.emptyList();
    private List<String> note = Collections.emptyList();
    private int boxTop;
    private int boxBottom;

    private LegacyConsentScreen(Screen parent, boolean screenScope, Runnable onAccept,
                                int width, int height) {
        super(new TranslatableComponent("screen.nyanlex.consent.title"));
        this.parent = parent;
        this.screenScope = screenScope;
        this.onAccept = onAccept;
        this.openedWidth = width;
        this.openedHeight = height;
    }

    /** Opens the consent window; {@code onAccept} runs after online translation was switched on. */
    static void open(Minecraft mc, boolean screenScope, Runnable onAccept) {
        if (mc == null) return;
        int w = mc.window.getGuiScaledWidth();
        int h = mc.window.getGuiScaledHeight();
        Screen current = mc.screen;
        LegacyConsentScreen consent = new LegacyConsentScreen(current, screenScope, onAccept, w, h);
        if (current == null) {
            mc.setScreen(consent);
        } else {
            mc.screen = consent;
            consent.init(mc, w, h);
        }
    }

    @Override protected void init() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        int textW = BOX_W - 24;
        body = font.split(new TranslatableComponent(screenScope
                ? "screen.nyanlex.consent.body.screen" : "screen.nyanlex.consent.body.item",
                LegacyUiModel.serviceName(LegacyFormScreen.TEXT, cfg)).getString(), textW);
        note = font.split(new TranslatableComponent("screen.nyanlex.consent.note").getString(), textW);
        int contentH = 22 + body.size() * LINE_H + 8 + note.size() * LINE_H + 14 + 20 + 12;
        boxTop = Math.max(8, height / 2 - contentH / 2);
        boxBottom = boxTop + contentH;
        int buttonsY = boxBottom - 12 - 20;
        addButton(new Button(width / 2 - 6 - BUTTON_W, buttonsY, BUTTON_W, 20,
                new TranslatableComponent("screen.nyanlex.consent.cancel").getString(),
                button -> cancel()));
        addButton(new Button(width / 2 + 6, buttonsY, BUTTON_W, 20,
                new TranslatableComponent("screen.nyanlex.consent.accept").getString(),
                button -> accept()));
    }

    private void restoreParent() {
        if (parent == null) {
            minecraft.setScreen(null);
            return;
        }
        minecraft.screen = parent;
        int w = minecraft.window.getGuiScaledWidth();
        int h = minecraft.window.getGuiScaledHeight();
        if (w != openedWidth || h != openedHeight) parent.resize(minecraft, w, h);
    }

    private void cancel() {
        restoreParent();
    }

    void accept() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        cfg.translationRequestsEnabled = true;
        LegacyTranslatorMod.saveConfig();
        restoreParent();
        if (onAccept != null) onAccept.run();
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        if (parent != null) parent.render(-1, -1, delta);
        // Text of the screen underneath writes depth; lift everything of ours above it.
        com.mojang.blaze3d.platform.GlStateManager.pushMatrix();
        com.mojang.blaze3d.platform.GlStateManager.translatef(0.0F, 0.0F, 300.0F);
        fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);
        int left = width / 2 - BOX_W / 2;
        fill(left, boxTop, left + BOX_W, boxBottom, 0xFF1E1E1E);
        fill(left, boxTop, left + BOX_W, boxTop + 1, 0xFF5E8BFF);
        String heading = title.getString();
        font.drawShadow(heading, width / 2f - font.width(heading) / 2f, boxTop + 8, 0xFFFFFF);
        int y = boxTop + 22;
        for (String line : body) { font.drawShadow(line, left + 12, y, 0xE0E0E0); y += LINE_H; }
        y += 8;
        for (String line : note) { font.drawShadow(line, left + 12, y, 0x909090); y += LINE_H; }
        super.render(mouseX, mouseY, delta);
        com.mojang.blaze3d.platform.GlStateManager.popMatrix();
    }

    @Override public boolean shouldCloseOnEsc() { return true; }

    @Override public void onClose() { cancel(); }

    @Override public void removed() { }
}
