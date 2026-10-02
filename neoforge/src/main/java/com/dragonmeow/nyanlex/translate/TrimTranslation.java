package com.dragonmeow.nyanlex.translate;

import java.util.Collection;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Decides which string a width-based trim (the vanilla "cut this label to N pixels" helper)
 * should cut.
 *
 * <p>GUI code often shortens a label that is too wide and appends an ellipsis before drawing it,
 * so the draw hooks only ever see the shortened fragment, which has no translation. Handing the
 * WHOLE string to the interface-text pipeline first lets the trim cut the translation instead.
 * The pipeline itself ({@code screenText}) owns every policy: display mode, which screens are
 * eligible, the consent gate, cache and repository lookups, and whether a request may be
 * queued. This class adds nothing to it, so a trim never creates a request that a plain draw of
 * the same string would not.</p>
 *
 * <p>Text inputs trim the string the player is typing to place the caret; their text must never
 * be replaced, so the (more expensive) caller check runs only once a translation was found, and
 * even then only on a screen that owns a text input at all ({@link TextInputGate}); the stack
 * walk behind {@link #callerIsTextInput} is the last resort, not a per-frame cost.</p>
 */
public final class TrimTranslation {
    /** The vanilla widgets that edit text the player types (Mojang names, stable across the
     *  loaders that share them). Matched by name so this class needs no Minecraft type. */
    public static final Set<String> TEXT_INPUT_CLASS_NAMES = Set.of(
            "net.minecraft.client.gui.components.EditBox",
            "net.minecraft.client.gui.components.MultilineTextField",
            "net.minecraft.client.gui.components.MultiLineEditBox");

    private static final StackWalker CALLER_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private TrimTranslation() {
    }

    /** Whether {@code type}, or any superclass, is one of {@link #TEXT_INPUT_CLASS_NAMES}. */
    public static boolean isTextInputClass(Class<?> type) {
        return isTextInputClass(type, TEXT_INPUT_CLASS_NAMES);
    }

    static boolean isTextInputClass(Class<?> type, Set<String> names) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (names.contains(c.getName())) return true;
        }
        return false;
    }

    /**
     * Whether the code that asked for the trim is a text input: the first class on the stack
     * that is neither this class nor one {@code skip} names (the loader glue and the vanilla
     * font the trim hook is mixed into) is, or extends, a text-input widget. This class is
     * always skipped: it sits between the glue and the real caller, and not skipping it made
     * the check look at this class and never find a text input.
     */
    public static boolean callerIsTextInput(Predicate<Class<?>> skip) {
        return callerIsTextInput(skip, TEXT_INPUT_CLASS_NAMES);
    }

    static boolean callerIsTextInput(Predicate<Class<?>> skip, Set<String> names) {
        Class<?> caller = CALLER_WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c != TrimTranslation.class && !skip.test(c))
                .findFirst()).orElse(null);
        return isTextInputClass(caller, names);
    }

    /**
     * Remembers whether the open screen has a text input among its widgets, so the stack walk
     * is skipped on every screen that has none (most of them) instead of being repeated for
     * each translated label on every frame. The answer is recomputed when the screen object
     * or its number of widgets changes. A text input nested inside another widget's own
     * children is not seen from the screen's top level; such a screen answers "none".
     */
    public static final class TextInputGate {
        private Object screen;
        private int widgetCount = -1;
        private boolean present;

        public synchronized boolean hasTextInput(Object currentScreen, Collection<?> widgets) {
            return hasTextInput(currentScreen, widgets, TEXT_INPUT_CLASS_NAMES);
        }

        synchronized boolean hasTextInput(Object currentScreen, Collection<?> widgets, Set<String> names) {
            if (currentScreen == null || widgets == null) return false;
            if (currentScreen == screen && widgets.size() == widgetCount) return present;
            boolean found = false;
            for (Object widget : widgets) {
                if (widget != null && isTextInputClass(widget.getClass(), names)) {
                    found = true;
                    break;
                }
            }
            screen = currentScreen;
            widgetCount = widgets.size();
            present = found;
            return found;
        }
    }

    /**
     * @param text            the full string about to be trimmed
     * @param screenText      the interface-text pipeline (returns its argument when untranslated)
     * @param callerIsTextInput evaluated only when a translation exists; true for a text input
     * @return the translation to trim, or {@code text} itself when nothing should change
     */
    public static String resolve(String text, UnaryOperator<String> screenText,
                                 BooleanSupplier callerIsTextInput) {
        if (text == null || text.length() < 2 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return text;
        }
        String translated = screenText.apply(text);
        if (translated == null || translated.equals(text)) return text;
        return callerIsTextInput.getAsBoolean() ? text : translated;
    }
}
