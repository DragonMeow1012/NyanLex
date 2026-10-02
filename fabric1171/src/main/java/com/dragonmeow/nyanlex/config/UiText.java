package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Text measuring helpers for the settings panel (wrapping and ellipsis), font-independent. */
public final class UiText {

    private static final String NO_LINE_START = "，。、；：！？）」』】》,.;:!?)]}%…";

    private UiText() {}

    /**
     * Greedy line wrap to {@code maxWidth}. Breaks at spaces, or between any two characters
     * when a word has no space (Chinese); a closing punctuation mark never starts a line.
     * Explicit {@code '\n'} starts a new line. Never returns an empty list for non-empty text.
     */
    public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(paragraph, Math.max(8, maxWidth), width, lines);
        }
        return lines;
    }

    /** Splits into units that must not be broken: runs of ASCII letters/digits, single other characters. */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (isWordChar(ch)) {
                int j = i + 1;
                while (j < text.length() && isWordChar(text.charAt(j))) j++;
                out.add(text.substring(i, j));
                i = j;
            } else {
                out.add(String.valueOf(ch));
                i++;
            }
        }
        return out;
    }

    private static boolean isWordChar(char ch) {
        return ch < 128 && (Character.isLetterOrDigit(ch) || ch == '_' || ch == '\'' || ch == '-' || ch == '.' || ch == '%');
    }

    private static void wrapParagraph(String text, int maxWidth, ToIntFunction<String> width, List<String> out) {
        List<String> line = new ArrayList<>();
        String joined = "";
        for (String t : tokens(text)) {
            String candidate = joined + t;
            if (line.isEmpty()) {
                if (t.equals(" ")) continue; // no leading blanks
                if (width.applyAsInt(t) > maxWidth && t.length() > 1) {
                    // a single word longer than the line: hard-split it by characters
                    StringBuilder piece = new StringBuilder();
                    for (char ch : t.toCharArray()) {
                        if (piece.length() > 0 && width.applyAsInt(piece.toString() + ch) > maxWidth) {
                            out.add(piece.toString());
                            piece.setLength(0);
                        }
                        piece.append(ch);
                    }
                    line.add(piece.toString());
                    joined = piece.toString();
                    continue;
                }
                line.add(t);
                joined = t;
                continue;
            }
            if (width.applyAsInt(candidate) <= maxWidth) {
                line.add(t);
                joined = candidate;
                continue;
            }
            // overflow: break before t; a closing mark drags the previous unit down with it
            String carry = "";
            if (t.length() == 1 && NO_LINE_START.indexOf(t.charAt(0)) >= 0 && line.size() > 1) {
                carry = line.remove(line.size() - 1);
                joined = String.join("", line);
            }
            out.add(joined.stripTrailing());
            line = new ArrayList<>();
            joined = "";
            String start = carry + t;
            if (start.equals(" ")) continue;
            if (!carry.isEmpty()) {
                line.add(carry);
                line.add(t);
            } else {
                line.add(t);
            }
            joined = start;
        }
        if (!line.isEmpty() || out.isEmpty()) out.add(joined.stripTrailing());
    }

    /** Shortens {@code text} with "…" so that it fits {@code maxWidth}. */
    public static String fit(String text, int maxWidth, ToIntFunction<String> width) {
        if (text == null) return "";
        if (width.applyAsInt(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - width.applyAsInt(ellipsis);
        int end = text.length();
        while (end > 0 && width.applyAsInt(text.substring(0, end)) > budget) end--;
        return text.substring(0, end).stripTrailing() + ellipsis;
    }

    /** Like {@link #fit} but keeps both ends and cuts the middle ("C:\Users\…\cache.json"),
     *  the useful shape for a long file path. */
    public static String fitMiddle(String text, int maxWidth, ToIntFunction<String> width) {
        if (text == null) return "";
        if (width.applyAsInt(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - width.applyAsInt(ellipsis);
        if (budget <= 0) return ellipsis;
        int head = (text.length() + 1) / 2;
        int tail = text.length() - head;
        // Drop characters alternately from the middle outwards until it fits.
        int left = head, right = tail;
        while (left + right > 0
                && width.applyAsInt(text.substring(0, left) + text.substring(text.length() - right)) > budget) {
            if (left >= right) left--; else right--;
        }
        return text.substring(0, left) + ellipsis + text.substring(text.length() - right);
    }
}
