package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy line wrapping for text that mixes Latin words with CJK text.
 *
 * <ul>
 *   <li>A Latin/digit word is never cut in the middle (unless it alone is wider than a line).</li>
 *   <li>Every wide (CJK, kana, hangul, full-width) character is its own break opportunity.</li>
 *   <li>A line never starts with a closing punctuation mark and never ends with an opening one:
 *       the previous character is moved down with it instead.</li>
 *   <li>Spaces at a break are dropped.</li>
 * </ul>
 */
public final class OverlayLineWrap {

    /** Width in px of one code point in the font the text will be drawn with. */
    @FunctionalInterface
    public interface CharWidth {
        int of(int codePoint);
    }

    private static final String NO_START = "，。、．！？；：）」』】》〉〕｝”’…～ー,.!?;:)]}%";
    private static final String NO_END = "（「『【《〈〔｛“‘([{";

    private OverlayLineWrap() {
    }

    private static final class Unit {
        final String text;
        final int width;
        final boolean space;
        final boolean wide;

        Unit(String text, int width, boolean space, boolean wide) {
            this.text = text;
            this.width = width;
            this.space = space;
            this.wide = wide;
        }

        int first() {
            return text.codePointAt(0);
        }
    }

    /** True for characters that may break anywhere around themselves (no spaces needed). */
    public static boolean isWide(int cp) {
        return (cp >= 0x2E80 && cp <= 0xD7FF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFF00 && cp <= 0xFFEF)
                || (cp >= 0x20000 && cp <= 0x3FFFF);
    }

    /**
     * Wraps {@code text} into lines no wider than {@code limit} px (a single character wider
     * than {@code limit} still gets its own line). Whitespace runs, including line breaks,
     * count as a single break opportunity. Returns an empty list for blank text.
     */
    public static List<String> wrap(String text, int limit, CharWidth charWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        Map<Integer, Integer> widths = new HashMap<>();
        CharWidth cached = cp -> widths.computeIfAbsent(cp, charWidth::of);
        List<Unit> units = units(text, cached);

        List<Unit> line = new ArrayList<>();
        int lineWidth = 0;
        for (Unit unit : units) {
            if (unit.space) {
                if (!line.isEmpty() && lineWidth + unit.width <= limit) {
                    line.add(unit);
                    lineWidth += unit.width;
                }
                continue;
            }
            if (lineWidth + unit.width <= limit) {
                line.add(unit);
                lineWidth += unit.width;
                continue;
            }
            if (line.isEmpty()) {
                // a single unit wider than the line: hard-split it by character
                hardSplit(unit, limit, cached, lines, line);
                lineWidth = widthOf(line);
                continue;
            }
            List<Unit> carry = breakLine(line, unit);
            flush(line, lines);
            line = carry;
            lineWidth = widthOf(line);
            if (lineWidth + unit.width <= limit) {
                line.add(unit);
                lineWidth += unit.width;
            } else {
                // the carried characters leave no room: the unit starts a line of its own
                flush(line, lines);
                line = new ArrayList<>();
                if (unit.width > limit) {
                    hardSplit(unit, limit, cached, lines, line);
                } else {
                    line.add(unit);
                }
                lineWidth = widthOf(line);
            }
        }
        flush(line, lines);
        return lines;
    }

    private static List<Unit> units(String text, CharWidth widthOf) {
        List<Unit> units = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int wordWidth = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                if (word.length() > 0) {
                    units.add(new Unit(word.toString(), wordWidth, false, false));
                    word.setLength(0);
                    wordWidth = 0;
                }
                if (units.isEmpty() || !units.get(units.size() - 1).space) {
                    units.add(new Unit(" ", widthOf.of(' '), true, false));
                }
            } else if (isWide(cp)) {
                if (word.length() > 0) {
                    units.add(new Unit(word.toString(), wordWidth, false, false));
                    word.setLength(0);
                    wordWidth = 0;
                }
                units.add(new Unit(new String(Character.toChars(cp)), widthOf.of(cp), false, true));
            } else {
                word.appendCodePoint(cp);
                wordWidth += widthOf.of(cp);
            }
        }
        if (word.length() > 0) {
            units.add(new Unit(word.toString(), wordWidth, false, false));
        }
        return units;
    }

    /** Units that must move to the next line together with {@code next} (kinsoku). */
    private static List<Unit> breakLine(List<Unit> line, Unit next) {
        while (line.size() > 0 && line.get(line.size() - 1).space) {
            line.remove(line.size() - 1);
        }
        List<Unit> carry = new ArrayList<>();
        Unit head = next;
        while (line.size() > 1) {
            Unit last = line.get(line.size() - 1);
            boolean headNoStart = NO_START.indexOf(head.first()) >= 0;
            boolean lastNoEnd = last.wide && NO_END.indexOf(last.first()) >= 0;
            if (!headNoStart && !lastNoEnd) {
                break;
            }
            carry.add(0, line.remove(line.size() - 1));
            head = last;
        }
        return carry;
    }

    private static void hardSplit(Unit unit, int limit, CharWidth widthOf, List<String> lines,
                                  List<Unit> line) {
        StringBuilder piece = new StringBuilder();
        int pieceWidth = 0;
        int i = 0;
        while (i < unit.text.length()) {
            int cp = unit.text.codePointAt(i);
            i += Character.charCount(cp);
            int w = widthOf.of(cp);
            if (pieceWidth > 0 && pieceWidth + w > limit) {
                lines.add(piece.toString());
                piece.setLength(0);
                pieceWidth = 0;
            }
            piece.appendCodePoint(cp);
            pieceWidth += w;
        }
        line.clear();
        if (piece.length() > 0) {
            line.add(new Unit(piece.toString(), pieceWidth, false, unit.wide));
        }
    }

    private static int widthOf(List<Unit> line) {
        int w = 0;
        for (Unit unit : line) {
            w += unit.width;
        }
        return w;
    }

    private static void flush(List<Unit> line, List<String> lines) {
        while (!line.isEmpty() && line.get(line.size() - 1).space) {
            line.remove(line.size() - 1);
        }
        if (line.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Unit unit : line) {
            sb.append(unit.text);
        }
        lines.add(sb.toString());
    }
}
