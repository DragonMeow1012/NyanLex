package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds, in a translated string, the icons and decoration of the original text, so the style
 * they had (above all their custom font) can be put back on exactly those characters.
 *
 * <p>An icon or decoration character is one that is not language: private-use characters (the
 * icons of a resource pack's own fonts), the horizontal-move characters of an overlay layout,
 * other symbols and format characters. A rebuild that paints the whole translation in one
 * "dominant" style, or whose markers were lost on the way, would leave such a character in
 * the wrong font, which the game draws as a missing-glyph box. This class pairs every
 * maximal run of them in the original (one run of one style) with the same characters in the
 * translation: by content, and in order of appearance among equal contents. A run that
 * cannot be paired (the translation dropped or changed it) is left alone.</p>
 *
 * <p>Minecraft-free: a style is an opaque integer chosen by the caller.</p>
 */
public final class DecorStyleMatcher {

    private DecorStyleMatcher() {
    }

    /** One run of the original text and the id of its style. */
    public record Piece(String text, int style) {
    }

    /** One maximal decoration run of the original. */
    private record Decor(String content, int style) {
    }

    /** True for a character that carries no language: icon, layout move or decoration. */
    public static boolean isDecor(int cp) {
        if (cp >= OverlaySpace.FIRST && cp <= OverlaySpace.LAST) {
            return true;
        }
        switch (Character.getType(cp)) {
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
            case Character.OTHER_SYMBOL:
            case Character.FORMAT:
                return cp != 0xA7 && cp != 0x27E6 && cp != 0x27E7 && cp != 0xFFFD;
            default:
                return false;
        }
    }

    /** Whether any character of {@code text} is an icon or decoration. */
    public static boolean hasDecor(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isDecor(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * For every UTF-16 unit of {@code translated}, the style id of the original decoration run
     * it was paired with, or {@code -1}. Returns {@code null} when nothing is paired.
     */
    public static int[] match(List<Piece> original, String translated) {
        List<Decor> decor = decorRuns(original);
        if (decor.isEmpty() || translated.isEmpty()) {
            return null;
        }
        // longer contents first, so a run of two icons is found before either icon alone
        Map<String, List<Decor>> byContent = new HashMap<>();
        for (Decor d : decor) {
            byContent.computeIfAbsent(d.content(), k -> new ArrayList<>()).add(d);
        }
        List<String> contents = new ArrayList<>(byContent.keySet());
        contents.sort((a, b) -> b.length() != a.length() ? Integer.compare(b.length(), a.length())
                : Integer.compare(firstIndex(decor, a), firstIndex(decor, b)));
        int[] styles = new int[translated.length()];
        java.util.Arrays.fill(styles, -1);
        boolean[] claimed = new boolean[translated.length()];
        boolean any = false;
        for (String content : contents) {
            List<Decor> runs = byContent.get(content);
            int found = 0;
            int at = translated.indexOf(content);
            while (at >= 0 && found < runs.size()) {
                if (free(claimed, at, at + content.length())) {
                    int style = runs.get(found++).style();
                    for (int k = at; k < at + content.length(); k++) {
                        styles[k] = style;
                        claimed[k] = true;
                    }
                    any = true;
                    at = translated.indexOf(content, at + content.length());
                } else {
                    at = translated.indexOf(content, at + 1);
                }
            }
        }
        return any ? styles : null;
    }

    private static boolean free(boolean[] claimed, int from, int to) {
        for (int i = from; i < to; i++) {
            if (claimed[i]) {
                return false;
            }
        }
        return true;
    }

    private static int firstIndex(List<Decor> decor, String content) {
        for (int i = 0; i < decor.size(); i++) {
            if (decor.get(i).content().equals(content)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Maximal runs of decoration characters of one style (runs of equal style that touch are one). */
    private static List<Decor> decorRuns(List<Piece> pieces) {
        List<Decor> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStyle = -1;
        for (Piece piece : pieces) {
            String text = piece.text();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (isDecor(cp)) {
                    if (current.length() > 0 && currentStyle != piece.style()) {
                        out.add(new Decor(current.toString(), currentStyle));
                        current.setLength(0);
                    }
                    currentStyle = piece.style();
                    current.appendCodePoint(cp);
                } else if (current.length() > 0) {
                    out.add(new Decor(current.toString(), currentStyle));
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            out.add(new Decor(current.toString(), currentStyle));
        }
        return out;
    }
}
