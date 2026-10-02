package com.dragonmeow.nyanslate.neoforge26;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;

/**
 * Whether the keys pressed on a screen are being typed into a text input. The translation
 * hotkeys (G show original, R retranslate item, P scan screen) run for every screen, so
 * without this check typing "g" into chat or a search / settings box would fire them.
 */
public final class Neo26TextInput {
    private static final int MAX_LOGGED_BROKEN_SCREENS = 50;
    private static final java.util.Set<String> LOGGED_BROKEN_SCREENS = new java.util.HashSet<>();

    private Neo26TextInput() {
    }

    public static boolean isTyping(Screen screen) {
        if (screen == null) return false;
        try {
            // The mod's own keybind screen is itself a text input while it waits for the next
            // keypress to bind, so that keypress must not also fire a hotkey.
            if (screen instanceof Neo26KeybindScreen keybindScreen && keybindScreen.isListening()) {
                return true;
            }
            // Vanilla text-entry screens, whichever widget holds focus (chat incl. the bed chat,
            // signs incl. hanging signs, books, command blocks).
            if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen
                    || screen instanceof BookEditScreen || screen instanceof AbstractCommandBlockEditScreen) {
                return true;
            }
            // Follow the focus path down (screen -> list -> entry -> box), e.g. the game rule
            // screen or list-based mod config screens keep their boxes inside list entries.
            GuiEventListener focused = screen.getFocused();
            for (int depth = 0; focused != null && depth < 8; depth++) {
                if (acceptsText(focused)) return true;
                if (!(focused instanceof ContainerEventHandler)) break;
                focused = ((ContainerEventHandler) focused).getFocused();
            }
            // Some boxes take keys without being the screen's focused child (creative-mode search).
            for (GuiEventListener child : screen.children()) {
                if (acceptsText(child)) return true;
            }
            return false;
        } catch (RuntimeException brokenScreen) {
            logBrokenScreenOnce(screen, brokenScreen);
            return false; // a misbehaving third-party screen must not break key handling
        }
    }

    private static boolean acceptsText(GuiEventListener listener) {
        if (listener instanceof EditBox box) return box.canConsumeInput();
        if (listener instanceof MultiLineEditBox box) return box.visible && box.isFocused();
        return false;
    }

    /** Logs a misbehaving third-party screen once per screen class, capped so a screen that
     *  throws on every keypress cannot flood the log. */
    private static void logBrokenScreenOnce(Screen screen, RuntimeException error) {
        String className = screen.getClass().getName();
        synchronized (LOGGED_BROKEN_SCREENS) {
            if (LOGGED_BROKEN_SCREENS.size() >= MAX_LOGGED_BROKEN_SCREENS) return;
            if (!LOGGED_BROKEN_SCREENS.add(className)) return;
        }
        NyanslateNeoForge26.LOGGER.warn("Screen {} threw while checking for a focused text input: {}",
                className, error.toString());
    }
}
