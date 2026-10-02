package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.ConsentGate;
import com.dragonmeow.nyanlex.config.DialogContent;
import com.dragonmeow.nyanlex.config.DialogPanel;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The on-the-spot "start online translation?" box for R, P and the warm-up while 線上翻譯 is
 * off. It is drawn on top of whatever screen is open and swallows mouse and keyboard input, so a
 * chest or server menu is never replaced, closed or sent a close packet; only when no screen is
 * open (R in the world) a bare {@link ConsentScreen} carries it. Nothing is sent unless the
 * player presses 開始翻譯. Nothing is focused when it opens: Tab and Shift+Tab move a frame, Enter or
 * Space press the framed button, and Escape cancels.
 */
final class ConsentOverlay {
    private static final ConsentGate GATE =
            new ConsentGate(NyanLexFabric::config, NyanLexFabric::saveConfig);
    private static final DialogContent.Lang LANG = (key, args) -> new net.minecraft.network.chat.TranslatableComponent(key, args).getString();

    private static DialogPanel panel;
    private static Screen anchor;

    private ConsentOverlay() {}

    static ConsentGate gate() {
        return GATE;
    }

    static boolean active() {
        return panel != null;
    }

    /** Runs {@code action} now when 線上翻譯 is on, otherwise asks first; the action runs once after 開始翻譯. */
    static void ask(ConsentGate.Kind kind, Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (GATE.request(kind, action)) return;
        panel = new DialogPanel(mc.font::width);
        panel.setNarration(DialogContent.narration(LANG));
        panel.set(DialogContent.consent(kind, NyanLexFabric.config(), LANG));
        Screen current = mc.screen;
        if (current == null) {
            ConsentScreen screen = new ConsentScreen();
            anchor = screen;
            mc.setScreen(screen);
        } else {
            anchor = current;
        }
    }

    /** True when the box is up for {@code screen} (input to it must be swallowed). */
    static boolean covers(Screen screen) {
        return panel != null && screen == anchor;
    }

    static void render(Screen screen, PoseStack g, int mouseX, int mouseY) {
        if (!covers(screen)) return;
        panel.resize(screen.width, screen.height);
        // item tooltips are drawn at z=400 after the screen: keep the box above them
        g.pushPose();
        g.translate(0.0F, 0.0F, 1000.0F);
        panel.render(new GuiCanvas(g, Minecraft.getInstance().font), mouseX, mouseY);
        g.popPose();
    }

    /** Left click on the box; returns true while the box is up (the click never reaches the screen). */
    static boolean mouseClicked(Screen screen, double x, double y, int button) {
        if (!covers(screen)) return false;
        handle(panel.mouseClicked((int) x, (int) y, button));
        return true;
    }

    static boolean keyPressed(Screen screen, int key, int modifiers) {
        if (!covers(screen)) return false;
        handle(panel.keyPressed(key, (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0));
        if (panel != null && panel.consumeNarrationRequest() && screen instanceof ConsentScreen own) own.narrateNow();
        return true;
    }

    /** Wheel over the box: it never reaches the screen underneath. */
    static boolean mouseScrolled(Screen screen, double x, double y, double dy) {
        if (!covers(screen)) return false;
        panel.mouseScrolled((int) x, (int) y, dy);
        return true;
    }

    private static void handle(int id) {
        if (id == DialogContent.CONSENT_START) {
            close();
            GATE.confirm();
        } else if (id == DialogContent.CONSENT_CANCEL) {
            close();
            GATE.cancel();
        }
    }

    /** Drops the box without answering (the screen it sat on is gone). */
    static void tick() {
        if (panel == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != anchor) {
            panel = null;
            anchor = null;
            GATE.cancel();
        }
    }

    private static void close() {
        Screen was = anchor;
        panel = null;
        anchor = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && was instanceof ConsentScreen && mc.screen == was) mc.setScreen(null);
    }

    /** Blank screen that only exists to carry the box when nothing else is open. */
    static final class ConsentScreen extends Screen {
        ConsentScreen() {
            super(new net.minecraft.network.chat.TranslatableComponent("nyanlex.ui.consent.title"));
        }

        /** The narration trigger is protected on this version, so only the carrier screen can ask for it. */
        void narrateNow() {
            triggerImmediateNarration(true);
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
