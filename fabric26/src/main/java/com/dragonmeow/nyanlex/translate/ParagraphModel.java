package com.dragonmeow.nyanlex.translate;

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

    /** Words that cannot end a sentence: a row ending in one is cut mid-clause by a server-side
     *  visual wrap and continues on the next row, whatever that row starts with. */
    private static final java.util.Set<String> DANGLING_WORDS = java.util.Set.of(
            "and", "or", "but", "nor", "on", "in", "of", "to", "for", "with", "the", "a", "an",
            "by", "at", "from", "as", "that", "when", "while", "if", "your", "you", "is", "are",
            "be", "per", "into", "onto", "than", "then", "which", "who", "its", "their", "his",
            "her", "our", "every", "each", "all", "up", "off", "over", "under", "after",
            "before", "until", "within", "without", "against", "between", "through");
    /** "Cooldown: 5s", "Seller: x": the next row is a labelled field of its own, never a tail. */
    private static final Pattern LABEL_ROW = Pattern.compile("^[\\p{L}][\\p{L} ]{0,24}:\\s");

    /**
     * Whether {@code next} is the visual continuation of the server-wrapped sentence that
     * {@code prev} starts, so the two rows are one semantic sentence joined by a SPACE (no
     * {@code ⟦PB⟧} row token on the wire). Every row boundary that stays a {@code ⟦PB⟧} is one
     * more thing the model must carry through a re-ordered clause; live runs failed 85% of the
     * time through a PB that sat in the middle of a sentence (0.5% for PB-free units).
     *
     * <p>Two evidence rules, both conservative so independent stat/enchant/ability rows keep
     * their own boundary: (1) the original one -- {@code prev} has at least four words, ends in
     * a letter and {@code next} starts in lower case; (2) {@code prev} has at least three
     * words and ends mid-clause -- in a function word ("and", "on", "the"...), or in a comma
     * with a lower-case row after it -- and {@code next} is not a labelled field.</p>
     */
    public static boolean continuesWrappedSentence(String prev, String next) {
        if (prev == null || next == null) return false;
        String p = prev.strip();
        String n = next.strip();
        if (p.isEmpty() || n.isEmpty()) return false;
        int words = p.split("\\s+").length;
        int last = p.codePointBefore(p.length());
        int first = n.codePointAt(0);
        if (words >= 4 && Character.isLetter(last) && Character.isLetter(first)
                && Character.isLowerCase(first)) {
            return true;
        }
        if (words < 2 || LABEL_ROW.matcher(n).find()) return false;
        if (TextFilter.isDecorativeSymbol(first)) return false;
        if (last == ',') return Character.isLetter(first) && Character.isLowerCase(first);
        if (words < 3 || !Character.isLetter(last)) return false;
        int lastSpace = p.lastIndexOf(' ');
        String lastWord = p.substring(lastSpace + 1).toLowerCase(java.util.Locale.ROOT);
        return DANGLING_WORDS.contains(lastWord);
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

    /**
     * Raw {@code [start, end)} row spans split on every top-level {@code ⟦PBn⟧} token (the
     * token itself belongs to neither row); {@code null} on an unterminated {@code ⟦...⟧}
     * marker anywhere. Text with no PB token at all yields exactly one row spanning the
     * whole text.
     *
     * <p>Shared by every composer that needs to classify a joined paragraph's rows
     * independently instead of treating the whole {@link #join}ed string as one opaque
     * unit: {@link EnchantListComposer}, {@link RarityLineComposer#matchRuns}, {@link
     * TradeLineComposer}, {@link StatsLineComposer} and {@link ScrollNameListComposer} all
     * split on this same boundary, so a mixed paragraph (a rarity line glued to a trade
     * field, an ability-name list, …) is classified row-by-row consistently everywhere.</p>
     */
    public static List<int[]> splitRawRows(String text) {
        if (text == null) return null;
        List<int[]> rows = new ArrayList<>();
        int rowStart = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '⟦') {
                int tokenEnd = protocolTokenEnd(text, i, n);
                if (tokenEnd < 0) return null;
                if (isPbToken(text, i + 1, tokenEnd - 1)) {
                    rows.add(new int[] {rowStart, i});
                    rowStart = tokenEnd;
                }
                i = tokenEnd;
                continue;
            }
            i++;
        }
        rows.add(new int[] {rowStart, n});
        return rows;
    }

    /** Whether the {@code ⟦…⟧} token body is a paragraph-break marker ({@code PBn}). */
    private static boolean isPbToken(String text, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        if (i + 2 > to || text.charAt(i) != 'P' || text.charAt(i + 1) != 'B') return false;
        i += 2;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        int digitsStart = i;
        while (i < to && Character.isDigit(text.charAt(i))) i++;
        if (i == digitsStart) return false;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        return i == to;
    }

    private static int protocolTokenEnd(String text, int open, int limit) {
        for (int j = open + 1; j < limit; j++) {
            char c = text.charAt(j);
            if (c == '⟧') return j + 1;
            if (c == '⟦') return -1;
        }
        return -1;
    }

    /** Number of {@link #BREAK_TOKEN_PATTERN} tokens occurring in {@code text} (0 for null). */
    public static int countBreakTokens(String text) {
        if (text == null) return 0;
        java.util.regex.Matcher matcher = BREAK_TOKEN_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /** True when the PB indices in {@code translated} are exactly {@code 0..expectedBreaks-1}
     *  and appear in that order (an unparseable index is invalid). With
     *  {@code expectedBreaks == 0} the translated text must not contain any PB token, so an
     *  AI-hallucinated break is caught too. */
    public static boolean validBreakSequence(String translated, int expectedBreaks) {
        if (translated == null) return false;
        java.util.regex.Matcher matcher = BREAK_TOKEN_PATTERN.matcher(translated);
        int next = 0;
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return false;
            }
            if (index != next) return false;
            next++;
        }
        return next == expectedBreaks;
    }

    /** Flatten every PB token (together with the surrounding whitespace the pattern
     *  swallows): a token whose OUTER neighbours are both ASCII letters/digits becomes one
     *  single space, anything else is removed outright — no seam is left between CJK. */
    public static String flattenBreakTokens(String translated) {
        if (translated == null) return null;
        java.util.regex.Matcher matcher = BREAK_TOKEN_PATTERN.matcher(translated);
        StringBuilder out = new StringBuilder(translated.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(translated, cursor, matcher.start());
            boolean asciiBefore = matcher.start() > 0
                    && isAsciiAlnum(translated.charAt(matcher.start() - 1));
            boolean asciiAfter = matcher.end() < translated.length()
                    && isAsciiAlnum(translated.charAt(matcher.end()));
            if (asciiBefore && asciiAfter) out.append(' ');
            cursor = matcher.end();
        }
        out.append(translated, cursor, translated.length());
        return out.toString();
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * Whether tooltip row 0 is the stack's hover name, so the caller may isolate it as the
     * title instead of merging it into the first paragraph. Other mods often decorate the
     * title row (a different star count, stars rendered as digits such as "10✪", a trailing
     * "(+10)"); an exact comparison then merged the title into the body and gave the name a
     * paragraph-local wording. Both sides are compared without formatting (§ codes, colour
     * markers), decorative symbols (So/Co/Cs) and numbers, with whitespace collapsed: row 0
     * must equal the name or start with it at a word boundary.
     */
    public static boolean sameItemTitle(String line0, String hoverName) {
        if (line0 == null || hoverName == null) return false;
        String name = titleCore(hoverName);
        if (name.isEmpty()) {
            // A name made only of symbols or numbers keeps the exact pre-1.0.7 comparison.
            String exactName = TextFilter.stripFormatting(hoverName).strip();
            return !exactName.isEmpty()
                    && exactName.equals(TextFilter.stripFormatting(line0).strip());
        }
        String first = titleCore(line0);
        if (first.equals(name)) return true;
        if (!first.startsWith(name)) return false;
        int next = first.codePointAt(name.length());
        return !Character.isLetterOrDigit(next) && next != '_';
    }

    /** Title text without formatting, decorative symbols and numbers; whitespace collapsed. */
    private static String titleCore(String text) {
        String plain = TextFilter.stripFormatting(text);
        StringBuilder out = new StringBuilder(plain.length());
        boolean pendingSpace = false;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            i += Character.charCount(cp);
            if (TextFilter.isDecorativeSymbol(cp) || isNumber(cp)) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static boolean isNumber(int cp) {
        int type = Character.getType(cp);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
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
