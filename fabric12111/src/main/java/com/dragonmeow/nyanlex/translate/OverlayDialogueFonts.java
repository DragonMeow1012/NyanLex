package com.dragonmeow.nyanlex.translate;

/**
 * The one place that knows the font ids of an overlay dialogue box. Everything else asks this
 * class for the {@link Kind} of a font and never compares id strings itself.
 *
 * <p>Layout of the ids this class understands (all under {@code minecraft:hud/dialogue/text/}):
 * {@code nameplate}, {@code control}, {@code <style>/body_<0..4>} and
 * {@code <style>/choice_<0..3>}. The {@code <style>} segment is deliberately not enumerated.
 * Every other font under {@code minecraft:hud/dialogue/} (frames, portraits, effects) is
 * artwork and is never touched.</p>
 */
public final class OverlayDialogueFonts {

    private static final String ROOT = "minecraft:hud/dialogue/";
    private static final String TEXT = ROOT + "text/";
    private static final String BODY = "body_";
    private static final String CHOICE = "choice_";
    private static final String NAMEPLATE = "nameplate";
    private static final String CONTROL = "control";

    /** Number of body lines a box can show (body_0 .. body_4). */
    public static final int BODY_LINES = 5;
    /** Number of choice rows a box can show (choice_0 .. choice_3). */
    public static final int CHOICE_ROWS = 4;

    private OverlayDialogueFonts() {
    }

    /** What a font draws inside the dialogue box. */
    public enum Kind {
        /** Not a dialogue text font (artwork, HUD, anything else). */
        OTHER,
        /** One line of spoken text; {@link #line(String)} is its row. */
        BODY,
        /** The speaker name. */
        NAMEPLATE,
        /** The "continue" hint. */
        CONTROL,
        /** One selectable answer; {@link #line(String)} is its row. */
        CHOICE
    }

    /** True for any font that belongs to a dialogue box, text or artwork. */
    public static boolean isDialogue(String font) {
        return font != null && font.startsWith(ROOT);
    }

    /** Classifies a font id; {@link Kind#OTHER} for everything that is not dialogue text. */
    public static Kind kind(String font) {
        if (font == null || !font.startsWith(TEXT)) {
            return Kind.OTHER;
        }
        String rest = font.substring(TEXT.length());
        if (NAMEPLATE.equals(rest)) {
            return Kind.NAMEPLATE;
        }
        if (CONTROL.equals(rest)) {
            return Kind.CONTROL;
        }
        int slash = rest.indexOf('/');
        if (slash <= 0 || rest.indexOf('/', slash + 1) >= 0) {
            return Kind.OTHER;
        }
        String name = rest.substring(slash + 1);
        if (name.startsWith(BODY) && indexOf(name, BODY, BODY_LINES) >= 0) {
            return Kind.BODY;
        }
        if (name.startsWith(CHOICE) && indexOf(name, CHOICE, CHOICE_ROWS) >= 0) {
            return Kind.CHOICE;
        }
        return Kind.OTHER;
    }

    /** Row of a body or choice font, or -1 for every other font. */
    public static int line(String font) {
        Kind kind = kind(font);
        if (kind != Kind.BODY && kind != Kind.CHOICE) {
            return -1;
        }
        String name = font.substring(font.lastIndexOf('/') + 1);
        return indexOf(name, kind == Kind.BODY ? BODY : CHOICE, kind == Kind.BODY ? BODY_LINES : CHOICE_ROWS);
    }

    /** The font id of row {@code line} in the same style as the body font {@code sample}. */
    public static String bodyFont(String sample, int line) {
        return sample.substring(0, sample.lastIndexOf(BODY) + BODY.length()) + line;
    }

    /** Everything of a body font id up to its row number: identical for all rows of one style. */
    public static String stylePrefix(String font) {
        int at = font.lastIndexOf('/');
        return at < 0 ? font : font.substring(0, at + 1);
    }

    private static int indexOf(String name, String prefix, int limit) {
        if (name.length() != prefix.length() + 1) {
            return -1;
        }
        char digit = name.charAt(prefix.length());
        if (digit < '0' || digit >= '0' + limit) {
            return -1;
        }
        return digit - '0';
    }
}
