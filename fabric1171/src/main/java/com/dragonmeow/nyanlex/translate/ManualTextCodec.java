package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure text helpers of the translation manager (譯文管理): how the protected markers of a stored
 * sentence are shown to the player, how a typed translation is turned back, which markers a
 * sentence has, what a translation lacks or adds, and a case-insensitive search that never looks
 * inside a marker. No Minecraft type, no state; written in Java 16 syntax so older trees can share it.
 *
 * <p>Raw form (what the caches store) and display alias:</p>
 * <pre>
 *   ⟦MT0⟧   {V0}   a value that changes (number, time, ...)
 *   ⟦0⟧     {N0}   a player name or a do-not-translate term
 *   ⟦WS0⟧   {S0}   spacing between fields
 *   ⟦PB0⟧   {B0}   paragraph break
 *   ⟦CS0⟧   {C0}   start of a colour run
 *   ⟦/CS0⟧  {/C0}  end of a colour run
 * </pre>
 *
 * <p>Only the exact canonical shapes are markers: a plain index without a leading zero and of at
 * most nine digits, no blanks inside. Anything else is ordinary text and passes through untouched.</p>
 *
 * <p>{@link #toDisplay} and {@link #fromDisplay} are inverse for every string. A literal
 * {@code '{'} of the raw text that would otherwise read as part of an alias (it is followed by
 * another {@code '{'}, by something that looks like an alias, or by a marker that becomes one) is
 * written doubled in the display form, and {@code "{{"} is read back as one literal brace.</p>
 */
public final class ManualTextCodec {

    private static final char RAW_OPEN = '⟦';
    private static final char RAW_CLOSE = '⟧';
    private static final int MAX_DIGITS = 9;

    private ManualTextCodec() {}

    /** What a marker stands for. */
    public enum Kind {
        /** {@code ⟦MTn⟧}: a value that changes. */
        VALUE('V', "MT"),
        /** {@code ⟦n⟧}: a player name or a protected term. */
        NAME('N', ""),
        /** {@code ⟦WSn⟧}: spacing between fields. */
        SPACE('S', "WS"),
        /** {@code ⟦PBn⟧}: paragraph break. */
        BREAK('B', "PB"),
        /** {@code ⟦CSn⟧}: a colour run starts. */
        COLOR_OPEN('C', "CS"),
        /** {@code ⟦/CSn⟧}: a colour run ends. */
        COLOR_CLOSE('C', "/CS");

        private final char letter;
        private final String rawName;

        Kind(char letter, String rawName) {
            this.letter = letter;
            this.rawName = rawName;
        }

        public boolean isColor() { return this == COLOR_OPEN || this == COLOR_CLOSE; }
    }

    /** One marker: its kind and its index. */
    public record Tag(Kind kind, int index) implements Comparable<Tag> {
        /** The stored form, e.g. {@code ⟦MT0⟧}. */
        public String raw() { return RAW_OPEN + kind.rawName + index + RAW_CLOSE; }

        /** The alias the player sees, e.g. {@code {V0}} or {@code {/C1}}. */
        public String display() {
            return kind == Kind.COLOR_CLOSE ? "{/C" + index + "}" : "{" + kind.letter + index + "}";
        }

        @Override
        public int compareTo(Tag other) {
            int byKind = kind.compareTo(other.kind);
            return byKind != 0 ? byKind : Integer.compare(index, other.index);
        }
    }

    /** A marker found in a text: {@code [start, end)} are the offsets of its characters. */
    public record Span(int start, int end, Tag tag) {}

    /** One tag of a difference and how many times it is missing or extra. */
    public record Entry(Tag tag, int count) {
        /** {@code {V0}}, or {@code {V0}×2} when more than one copy is concerned. */
        public String display() { return count > 1 ? tag.display() + "×" + count : tag.display(); }
    }

    /** What a translation lacks and what it adds, compared with the original. */
    public record Diff(List<Entry> missing, List<Entry> extra) {
        public Diff {
            missing = List.copyOf(missing);
            extra = List.copyOf(extra);
        }

        public boolean isEmpty() { return missing.isEmpty() && extra.isEmpty(); }

        /** The missing tags as player-readable text, separated by a space. */
        public String missingText() { return describe(missing); }

        /** The extra tags as player-readable text, separated by a space. */
        public String extraText() { return describe(extra); }

        /** The missing tags in display form, one string per different tag ({@code {V0}} or {@code {V0}×2}). */
        public List<String> missingDisplay() { return displays(missing); }

        /** The extra tags in display form, one string per different tag. */
        public List<String> extraDisplay() { return displays(extra); }
    }

    private static String describe(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.display());
        }
        return sb.toString();
    }

    private static List<String> displays(List<Entry> entries) {
        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.display());
        return out;
    }

    // ------------------------------------------------------------------ raw <-> display

    /** The stored text with every marker replaced by its alias; {@code null} reads as empty. */
    public static String toDisplay(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length() + 8);
        int n = raw.length();
        int i = 0;
        while (i < n) {
            char ch = raw.charAt(i);
            if (ch == RAW_OPEN) {
                Span span = rawSpanAt(raw, i);
                if (span != null) {
                    out.append(span.tag().display());
                    i = span.end();
                    continue;
                }
            } else if (ch == '{') {
                boolean ambiguous = (i + 1 < n && raw.charAt(i + 1) == '{')
                        || aliasSpanAt(raw, i) != null
                        || (i + 1 < n && raw.charAt(i + 1) == RAW_OPEN && rawSpanAt(raw, i + 1) != null);
                out.append(ambiguous ? "{{" : "{");
                i++;
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** The typed text with every alias replaced by its marker; the inverse of {@link #toDisplay}. */
    public static String fromDisplay(String display) {
        if (display == null || display.isEmpty()) return "";
        StringBuilder out = new StringBuilder(display.length());
        int n = display.length();
        int i = 0;
        while (i < n) {
            char ch = display.charAt(i);
            if (ch == '{') {
                if (i + 1 < n && display.charAt(i + 1) == '{') {
                    out.append('{');
                    i += 2;
                    continue;
                }
                Span span = aliasSpanAt(display, i);
                if (span != null) {
                    out.append(span.tag().raw());
                    i = span.end();
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ finding markers

    /** Every marker of a stored text in reading order (a tag that occurs twice is listed twice). */
    public static List<Tag> tags(String raw) {
        List<Tag> out = new ArrayList<>();
        if (raw == null) return out;
        for (Span s : rawSpans(raw)) out.add(s.tag());
        return out;
    }

    /** The markers of a stored text with their offsets. */
    public static List<Span> rawSpans(String raw) {
        List<Span> out = new ArrayList<>();
        if (raw == null) return out;
        int i = raw.indexOf(RAW_OPEN);
        while (i >= 0) {
            Span span = rawSpanAt(raw, i);
            if (span != null) {
                out.add(span);
                i = raw.indexOf(RAW_OPEN, span.end());
            } else {
                i = raw.indexOf(RAW_OPEN, i + 1);
            }
        }
        return out;
    }

    /** The aliases typed in a display text with their offsets (escaped literal braces are not aliases). */
    public static List<Span> displaySpans(String display) {
        List<Span> out = new ArrayList<>();
        if (display == null) return out;
        int n = display.length();
        int i = 0;
        while (i < n) {
            if (display.charAt(i) == '{') {
                if (i + 1 < n && display.charAt(i + 1) == '{') {
                    i += 2;
                    continue;
                }
                Span span = aliasSpanAt(display, i);
                if (span != null) {
                    out.add(span);
                    i = span.end();
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    /** The markers of a display text (aliases) in reading order. */
    public static List<Tag> displayTags(String display) {
        List<Tag> out = new ArrayList<>();
        for (Span s : displaySpans(display)) out.add(s.tag());
        return out;
    }

    /**
     * The different markers a stored text has, in order of first appearance: the buttons the editor
     * offers for inserting a marker at the cursor.
     */
    public static List<Tag> distinctTags(String raw) {
        List<Tag> out = new ArrayList<>();
        for (Tag t : tags(raw)) if (!out.contains(t)) out.add(t);
        return out;
    }

    // ------------------------------------------------------------------ comparing

    /**
     * Compares the markers of the original with those of a translation as multisets: a tag the
     * translation has fewer copies of is missing, one it has more copies of is extra. The order is
     * not looked at (see {@link #breakOrderMatches}); lists are sorted by kind and index.
     */
    public static Diff compare(String sourceRaw, String translatedRaw) {
        Map<Tag, Integer> source = counts(sourceRaw);
        Map<Tag, Integer> translated = counts(translatedRaw);
        List<Entry> missing = new ArrayList<>();
        List<Entry> extra = new ArrayList<>();
        java.util.TreeSet<Tag> all = new java.util.TreeSet<>(source.keySet());
        all.addAll(translated.keySet());
        for (Tag t : all) {
            int want = source.getOrDefault(t, 0);
            int have = translated.getOrDefault(t, 0);
            if (have < want) missing.add(new Entry(t, want - have));
            else if (have > want) extra.add(new Entry(t, have - want));
        }
        return new Diff(missing, extra);
    }

    private static Map<Tag, Integer> counts(String raw) {
        Map<Tag, Integer> out = new LinkedHashMap<>();
        for (Tag t : tags(raw)) out.merge(t, 1, Integer::sum);
        return out;
    }

    /**
     * The colour tags that do not form a clean pair, in reading order and without repeats: an
     * opener that is never closed, a closer with no opener, a closer of another index than the open
     * run, or a run opened inside another one (runs cannot overlap or nest). Empty when every colour
     * tag is part of a proper pair, including when there are none.
     */
    public static List<Tag> unpairedColorTags(String raw) {
        List<Tag> bad = new ArrayList<>();
        Tag open = null;
        for (Tag t : tags(raw)) {
            if (t.kind() == Kind.COLOR_OPEN) {
                if (open != null) addOnce(bad, open);
                open = t;
            } else if (t.kind() == Kind.COLOR_CLOSE) {
                if (open == null) {
                    addOnce(bad, t);
                } else if (open.index() != t.index()) {
                    addOnce(bad, open);
                    addOnce(bad, t);
                    open = null;
                } else {
                    open = null;
                }
            }
        }
        if (open != null) addOnce(bad, open);
        return bad;
    }

    private static void addOnce(List<Tag> list, Tag tag) {
        if (!list.contains(tag)) list.add(tag);
    }

    /**
     * Whether the markers that must keep their place do: the paragraph breaks come in the same order
     * as in the original and every value marker stays in the paragraph row it belongs to. A text with
     * no paragraph break always matches. This is the order rule the caches apply to a translation, kept
     * here so the editor can warn before saving; it assumes the marker sets are already equal.
     */
    public static boolean breakOrderMatches(String sourceRaw, String translatedRaw) {
        List<Tag> source = tags(sourceRaw);
        List<Tag> translated = tags(translatedRaw);
        List<Tag> sourceBreaks = ofKind(source, Kind.BREAK);
        if (!sourceBreaks.equals(ofKind(translated, Kind.BREAK))) return false;
        if (sourceBreaks.isEmpty()) return true;
        List<Map<Tag, Integer>> sourceRows = valueRows(source);
        List<Map<Tag, Integer>> translatedRows = valueRows(translated);
        return sourceRows.equals(translatedRows);
    }

    private static List<Tag> ofKind(List<Tag> tags, Kind kind) {
        List<Tag> out = new ArrayList<>();
        for (Tag t : tags) if (t.kind() == kind) out.add(t);
        return out;
    }

    /** Value markers per paragraph row (rows are separated by paragraph breaks). */
    private static List<Map<Tag, Integer>> valueRows(List<Tag> tags) {
        List<Map<Tag, Integer>> rows = new ArrayList<>();
        Map<Tag, Integer> row = new java.util.TreeMap<>();
        for (Tag t : tags) {
            if (t.kind() == Kind.BREAK) {
                rows.add(row);
                row = new java.util.TreeMap<>();
            } else if (t.kind() == Kind.VALUE) {
                row.merge(t, 1, Integer::sum);
            }
        }
        rows.add(row);
        return rows;
    }

    // ------------------------------------------------------------------ plain text helpers

    /** Whether the stored text has a colour tag. */
    public static boolean hasColorTags(String raw) {
        if (raw == null) return false;
        for (Span s : rawSpans(raw)) if (s.tag().kind().isColor()) return true;
        return false;
    }

    /** The stored text without its colour tags (the plain sentence of a coloured one). */
    public static String stripColorTags(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        List<Span> spans = rawSpans(raw);
        StringBuilder out = new StringBuilder(raw.length());
        int cursor = 0;
        for (Span s : spans) {
            if (!s.tag().kind().isColor()) continue;
            out.append(raw, cursor, s.start());
            cursor = s.end();
        }
        out.append(raw, cursor, raw.length());
        return out.toString();
    }

    /** How many line breaks the text has. */
    public static int newlineCount(String text) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    // ------------------------------------------------------------------ search

    /**
     * The words of a search query: split at blanks; a word typed with aliases ({@code {V0}}) is
     * turned into the stored marker so it can find the sentences that have that marker.
     */
    public static String[] searchTerms(String query) {
        if (query == null) return new String[0];
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return new String[0];
        String[] words = trimmed.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            if (words[i].indexOf('{') >= 0) words[i] = fromDisplay(words[i]);
        }
        return words;
    }

    /**
     * Whether every word occurs (case-insensitive) in {@code first} or in {@code second}; for a blank
     * word list that is always true. Markers are skipped, see {@link #contains}. Allocation-free.
     */
    public static boolean matchesAll(String first, String second, String[] terms) {
        for (String term : terms) {
            if (!contains(first, term) && !contains(second, term)) return false;
        }
        return true;
    }

    /**
     * Case-insensitive substring test that does not look inside a marker, so searching "mt" or "cs"
     * does not hit every sentence that has a value or colour marker. A word that itself holds a marker
     * ({@code ⟦…⟧}) is looked for as plain text, markers included.
     */
    public static boolean contains(String text, String needle) {
        if (text == null || needle == null) return false;
        int nl = needle.length();
        if (nl == 0) return true;
        int n = text.length();
        boolean markerNeedle = needle.indexOf(RAW_OPEN) >= 0 || needle.indexOf(RAW_CLOSE) >= 0;
        int i = 0;
        while (i + nl <= n) {
            if (!markerNeedle && text.charAt(i) == RAW_OPEN) {
                int close = text.indexOf(RAW_CLOSE, i + 1);
                if (close >= 0) {
                    i = close + 1;
                    continue;
                }
            }
            if (text.regionMatches(true, i, needle, 0, nl)) return true;
            i++;
        }
        return false;
    }

    // ------------------------------------------------------------------ scanning

    /** The marker that starts exactly at {@code i} of a stored text, or null. */
    private static Span rawSpanAt(String s, int i) {
        int n = s.length();
        if (i >= n || s.charAt(i) != RAW_OPEN) return null;
        int p = i + 1;
        Kind kind;
        if (s.startsWith("/CS", p)) {
            kind = Kind.COLOR_CLOSE;
            p += 3;
        } else if (s.startsWith("CS", p)) {
            kind = Kind.COLOR_OPEN;
            p += 2;
        } else if (s.startsWith("MT", p)) {
            kind = Kind.VALUE;
            p += 2;
        } else if (s.startsWith("WS", p)) {
            kind = Kind.SPACE;
            p += 2;
        } else if (s.startsWith("PB", p)) {
            kind = Kind.BREAK;
            p += 2;
        } else {
            kind = Kind.NAME;
        }
        int end = indexEnd(s, p);
        if (end < 0 || end >= n || s.charAt(end) != RAW_CLOSE) return null;
        return new Span(i, end + 1, new Tag(kind, Integer.parseInt(s.substring(p, end))));
    }

    /** The alias that starts exactly at {@code i} of a display text, or null. */
    private static Span aliasSpanAt(String s, int i) {
        int n = s.length();
        if (i >= n || s.charAt(i) != '{') return null;
        int p = i + 1;
        if (p >= n) return null;
        Kind kind;
        char c = s.charAt(p);
        if (c == '/') {
            if (p + 1 >= n || s.charAt(p + 1) != 'C') return null;
            kind = Kind.COLOR_CLOSE;
            p += 2;
        } else {
            switch (c) {
                case 'V' -> kind = Kind.VALUE;
                case 'N' -> kind = Kind.NAME;
                case 'S' -> kind = Kind.SPACE;
                case 'B' -> kind = Kind.BREAK;
                case 'C' -> kind = Kind.COLOR_OPEN;
                default -> {
                    return null;
                }
            }
            p++;
        }
        int end = indexEnd(s, p);
        if (end < 0 || end >= n || s.charAt(end) != '}') return null;
        return new Span(i, end + 1, new Tag(kind, Integer.parseInt(s.substring(p, end))));
    }

    /**
     * End (exclusive) of the canonical number that starts at {@code from}: one or more digits, at most
     * nine, no leading zero unless it is the single digit 0. -1 when there is none.
     */
    private static int indexEnd(String s, int from) {
        int n = s.length();
        int p = from;
        while (p < n && p - from <= MAX_DIGITS && s.charAt(p) >= '0' && s.charAt(p) <= '9') p++;
        int len = p - from;
        if (len == 0 || len > MAX_DIGITS) return -1;
        if (len > 1 && s.charAt(from) == '0') return -1;
        return p;
    }
}
