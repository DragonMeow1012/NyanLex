package com.dragonmeow.nyanslate.legacy;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * "Is the player typing?" for the mod hotkeys (MC 1.14-1.16): true while the current screen has a
 * focused text input, so G / P never fire from keys meant for that input.
 * Covers chat (incl. in-bed chat), sign and book editing (text helpers, no EditBox), and an EditBox that
 * would take the key (EditBox.canConsumeInput: visible, focused, editable) found on the focus chain
 * (nested lists such as the game-rule editor), among the screen's children (creative search; Realms boxes
 * on 1.14/1.15 are proxy children), in a field (other mods) or as the recipe-book search while the book
 * is open. Never throws: a mod screen that breaks the lookup (e.g. a field whose optional type is missing)
 * only loses that part of the check.
 */
final class LegacyTextInput {
    /** Screen, list, row, box is the deepest vanilla chain; the cap also ends a self-referencing chain. */
    private static final int MAX_FOCUS_DEPTH = 8;

    private LegacyTextInput() {}

    static boolean focused(Screen screen) {
        if (screen == null) return false;
        if (screen instanceof ChatScreen || screen instanceof SignEditScreen
                || screen instanceof BookEditScreen) return true;
        return focusChainHasBox(screen) || childHasBox(screen)
                || recipeSearchFocused(screen) || hasFocusedBoxField(screen);
    }

    /** Keys go to getFocused() at every level, so follow that chain down to the widget that gets them. */
    private static boolean focusChainHasBox(ContainerEventHandler container) {
        try {
            GuiEventListener focus = container.getFocused();
            for (int depth = 0; focus != null && depth < MAX_FOCUS_DEPTH; depth++) {
                if (focusedBox(focus)) return true;
                if (!(focus instanceof ContainerEventHandler)) return false;
                focus = ((ContainerEventHandler) focus).getFocused();
            }
        } catch (LinkageError | RuntimeException broken) {
            // a failing mod widget is not evidence of typing
        }
        return false;
    }

    private static boolean childHasBox(Screen screen) {
        try {
            List<? extends GuiEventListener> children = screen.children();
            if (children != null) for (GuiEventListener child : children) if (focusedBox(child)) return true;
        } catch (LinkageError | RuntimeException broken) {
            // a failing mod widget is not evidence of typing
        }
        return false;
    }

    private static boolean recipeSearchFocused(Screen screen) {
        if (!(screen instanceof RecipeUpdateListener)) return false;
        try {
            RecipeBookComponent book = ((RecipeUpdateListener) screen).getRecipeBookComponent();
            return book != null && book.isVisible() && hasFocusedBoxField(book);
        } catch (LinkageError | RuntimeException notInitialised) {
            return false;
        }
    }

    /** The gate EditBox.keyPressed / charTyped use themselves (1.14.4-1.16.5): visible, focused, editable. */
    private static boolean focusedBox(Object widget) {
        return widget instanceof EditBox && ((EditBox) widget).canConsumeInput();
    }

    /** Field scan by type, so it works with dev (Mojang) and production (intermediary) names alike. */
    private static boolean hasFocusedBoxField(Object owner) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (LinkageError | RuntimeException unloadable) {
                continue; // e.g. a field typed with an absent optional dependency; superclasses still count
            }
            for (Field field : fields) {
                try {
                    if (Modifier.isStatic(field.getModifiers())
                            || !EditBox.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    if (focusedBox(field.get(owner))) return true;
                } catch (ReflectiveOperationException | LinkageError | RuntimeException inaccessible) {
                    // unreadable field: not evidence of typing
                }
            }
        }
        return false;
    }
}
