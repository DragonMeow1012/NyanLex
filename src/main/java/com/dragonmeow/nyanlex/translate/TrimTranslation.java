package com.dragonmeow.nyanlex.translate;

import java.util.function.BooleanSupplier;
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
 * be replaced, so the (more expensive) caller check runs only once a translation was found.</p>
 */
public final class TrimTranslation {
    private TrimTranslation() {
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
