package com.dragonmeow.nyanlex.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Text measuring helpers for the settings panel (wrapping and ellipsis), font-independent. */
public final class UiText {

    private static final String NO_LINE_START = "，。、；：！？）」』】》,.;:!?)]}%…";
    /** An opening bracket or quote never ends a line (it goes down with what it opens). */
    private static final String NO_LINE_END = "（「『【《(";
    private static final String OPEN_QUOTES = "「『（";
    private static final String CLOSE_QUOTES = "」』）";
    /** A short parenthetical such as （原文＋譯文） stays whole too, but a long one may break. */
    private static final int MAX_PAREN_UNIT = 14;
    /** A quoted name longer than this is allowed to break like ordinary text. */
    private static final int MAX_QUOTED_UNIT = 24;
    /** A last line with fewer characters than this takes some from the line above it. */
    private static final int MIN_LAST_LINE = 3;

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

    /**
     * Splits into units that must not be broken: runs of ASCII letters/digits, a name inside 「」 or
     * 『』 (so a quoted term such as 「快速設定」 never splits in the middle), and single other characters.
     */
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
            } else if (OPEN_QUOTES.indexOf(ch) >= 0) {
                int end = -1;
                int limit = ch == '（' ? MAX_PAREN_UNIT : MAX_QUOTED_UNIT;
                for (int j = i + 1; j < text.length() && j <= i + limit; j++) {
                    char c = text.charAt(j);
                    if (c == '\n') break;
                    if (CLOSE_QUOTES.indexOf(c) >= 0 && (ch == '（') == (c == '）')) {
                        end = j;
                        break;
                    }
                }
                if (end > 0) {
                    out.add(text.substring(i, end + 1));
                    i = end + 1;
                } else {
                    out.add(String.valueOf(ch));
                    i++;
                }
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
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        String joined = "";
        for (String t : tokens(text)) {
            if (line.isEmpty()) {
                if (t.equals(" ")) continue; // no leading blanks
                if (width.applyAsInt(t) > maxWidth && t.length() > 1) {
                    // a single word longer than the line: hard-split it by characters
                    StringBuilder piece = new StringBuilder();
                    for (char ch : t.toCharArray()) {
                        if (piece.length() > 0 && width.applyAsInt(piece.toString() + ch) > maxWidth) {
                            lines.add(new ArrayList<>(List.of(piece.toString())));
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
            String candidate = joined + t;
            if (width.applyAsInt(candidate) <= maxWidth) {
                line.add(t);
                joined = candidate;
                continue;
            }
            // overflow: break before t; a closing mark drags the previous unit down with it, and an
            // opening bracket or quote never stays behind at the end of the line
            List<String> carry = new ArrayList<>();
            if (t.length() == 1 && NO_LINE_START.indexOf(t.charAt(0)) >= 0 && line.size() > 1) {
                carry.add(0, line.remove(line.size() - 1));
            }
            while (line.size() > 1) {
                String last = line.get(line.size() - 1);
                if (last.length() == 1 && NO_LINE_END.indexOf(last.charAt(0)) >= 0) {
                    carry.add(0, line.remove(line.size() - 1));
                } else {
                    break;
                }
            }
            lines.add(line);
            line = new ArrayList<>(carry);
            if (!(t.equals(" ") && carry.isEmpty())) line.add(t);
            joined = String.join("", line);
        }
        if (!line.isEmpty() || lines.isEmpty()) lines.add(line);
        avoidShortLastLine(lines, maxWidth, width);
        for (List<String> l : lines) out.add(String.join("", l).stripTrailing());
    }

    /** A last line of one or two characters takes units from the end of the line above it. */
    private static void avoidShortLastLine(List<List<String>> lines, int maxWidth, ToIntFunction<String> width) {
        if (lines.size() < 2) return;
        List<String> last = lines.get(lines.size() - 1);
        List<String> prev = lines.get(lines.size() - 2);
        String lastText = String.join("", last).strip();
        if (lastText.isEmpty() || lastText.length() >= MIN_LAST_LINE) return;
        int length = lastText.length();
        while (length < MIN_LAST_LINE && prev.size() > 1) {
            String token = prev.get(prev.size() - 1);
            if (token.equals(" ")) {
                prev.remove(prev.size() - 1);
                continue;
            }
            if (width.applyAsInt(token + String.join("", last)) > maxWidth) break;
            last.add(0, token);
            prev.remove(prev.size() - 1);
            length += token.length();
        }
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
