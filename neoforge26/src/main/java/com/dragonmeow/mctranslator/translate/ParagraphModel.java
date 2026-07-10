package com.dragonmeow.mctranslator.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Loader-independent paragraph boundaries used by every translated surface.
 *
 * <p>An actual blank row is a hard boundary. When prose has no blank row, a row
 * beginning with a conventional paragraph indent (at least two horizontal columns)
 * starts a new paragraph. All other adjacent rows belong to one semantic request.
 * A caller may still isolate a row using verified surface metadata (for example an
 * ItemStack hover name); this class deliberately makes no generic "first row is a
 * title" assumption.</p>
 */
public final class ParagraphModel {

    public static final Pattern BREAK_TOKEN_PATTERN = Pattern.compile(
            "[ \\t\\u00A0]*\\u27E6\\s*PB\\s*(\\d+)\\s*\\u27E7[ \\t\\u00A0]*");

    private ParagraphModel() {
    }

    /** Inclusive line range. Blank rows are represented as one-row ranges. */
    public record Range(int start, int end) {
        public Range {
            if (start < 0 || end < start) throw new IllegalArgumentException("invalid paragraph range");
        }

        public int size() {
            return end - start + 1;
        }
    }

    public static List<Range> ranges(List<String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<Range> ranges = new ArrayList<>();
        for (int start = 0; start < lines.size(); ) {
            String first = lines.get(start);
            if (isBlank(first)) {
                ranges.add(new Range(start, start));
                start++;
                continue;
            }
            int end = start;
            while (end + 1 < lines.size()) {
                String next = lines.get(end + 1);
                if (isBlank(next) || startsIndentedParagraph(next)) break;
                end++;
            }
            ranges.add(new Range(start, end));
            start = end + 1;
        }
        return List.copyOf(ranges);
    }

    /** Join one non-blank paragraph into one backend unit with immutable row anchors. */
    public static String join(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(breakToken(i - 1));
            String line = lines.get(i);
            if (line != null) out.append(line);
        }
        return out.toString();
    }

    public static String breakToken(int index) {
        if (index < 0) throw new IllegalArgumentException("negative paragraph break index");
        return " ⟦PB" + index + "⟧ ";
    }

    /** Split a validated translated paragraph back into its protected hard rows. */
    public static List<String> split(String translated) {
        if (translated == null) return List.of();
        java.util.regex.Matcher matcher = BREAK_TOKEN_PATTERN.matcher(translated);
        List<String> rows = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            rows.add(translated.substring(cursor, matcher.start()));
            cursor = matcher.end();
        }
        rows.add(translated.substring(cursor));
        return List.copyOf(rows);
    }

    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** Two ASCII/NBSP columns, one tab, or one ideographic/full-width space. */
    public static boolean startsIndentedParagraph(String text) {
        if (text == null || text.isEmpty()) return false;
        int columns = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ' || cp == '\u00A0') columns++;
            else if (cp == '\t' || cp == '\u3000') columns += 2;
            else break;
            if (columns >= 2) return true;
        }
        return false;
    }
}
