package com.dragonmeow.nyanslate.translate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a SkyBlock tooltip "ability/scroll name list" — one short, icon-bulleted
 * Title Case name PER ROW ({@code ⟦PBn⟧}-joined; Hyperion's "Implosion / Wither Shield /
 * Shadow Warp" equipped-scroll list is the motivating example) — so {@link
 * com.dragonmeow.nyanslate.service.TranslationService} can decompose it into one
 * independent, cacheable translation unit PER NAME instead of sending the whole combo as
 * one near-combinatorially-unique paragraph: today, swapping a single equipped scroll
 * mints a brand-new cache key for the WHOLE list even though the other names are already
 * known translations.
 *
 * <p>Structurally close to {@link EnchantListComposer} (same two-generation memo, same
 * {@code ⟦PBn⟧} row splitting, same "resolve independently / splice back" {@link Match}
 * shape), but exactly ONE name per row instead of a comma list, and each row requires a
 * leading decorative icon run (the bullet glyph Hypixel renders before each ability/scroll
 * line — see {@link #ROW_SHAPE}) so an ordinary Title-Case prose row glued into the same
 * paragraph is never misjudged as part of the list. A run of at least 2 rows is required:
 * a lone icon+name row has nothing to gain from decomposition, exactly like {@link
 * EnchantListComposer}'s single-enchant-row exclusion.</p>
 */
public final class ScrollNameListComposer {

    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';
    private static final char SECTION = '§';

    private static final String WORD = "[A-Z][A-Za-z]*(?:['’-][A-Za-z]+)*";
    private static final String CONNECTOR = "(?:of|the|and|de|du|la|le|von|a)";
    private static final String NAME = WORD + "(?:\\s+(?:" + CONNECTOR + "\\s+)*" + WORD + ")*";
    /** A leading decorative icon run: anything that is not a letter, digit or blank —
     *  deliberately broad (Hypixel's actual bullet glyph varies), since the row is only
     *  ever accepted together with a full {@link #NAME} match right after it. */
    private static final String ICON = "[^\\p{L}\\p{N}\\s]+";

    /** Group 1 = the name span. */
    private static final Pattern ROW_SHAPE = Pattern.compile(
            "^\\s*" + ICON + "\\s*(" + NAME + ")\\s*$");

    /** Same as {@link #ROW_SHAPE} but with no icon requirement at all — used after a
     *  LEADING {@code ⟦MTn⟧}/{@code ⟦WSn⟧} value token has already been consumed as the
     *  row's icon (see {@link #leadingValueTokenEnd}), so nothing icon-shaped remains in
     *  the projected text for {@link #ROW_SHAPE}'s own {@code ICON} group to match. */
    private static final Pattern NAME_ONLY = Pattern.compile("^\\s*(" + NAME + ")\\s*$");

    /** One recognised item: {@code [rawStart, rawEnd)} is this row's NAME span only (never
     *  the leading icon) in the text {@link #matchOneRow}/{@link #matchRuns} was given. */
    public static final class Item {
        private final String name;
        private final int rawStart;
        private final int rawEnd;

        Item(String name, int rawStart, int rawEnd) {
            this.name = name;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
        }

        public String name() {
            return name;
        }

        public int rawStart() {
            return rawStart;
        }

        public int rawEnd() {
            return rawEnd;
        }
    }

    /** A recognised scroll/ability name-list run: every item, in row order. */
    public static final class Match {
        private final List<Item> items;

        Match(List<Item> items) {
            this.items = items;
        }

        public List<Item> items() {
            return items;
        }

        /** Distinct names in first-appearance order — the independent translation units
         *  the caller needs to resolve/request. */
        public List<String> names() {
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            for (Item item : items) distinct.add(item.name());
            return List.copyOf(distinct);
        }

        /** Splice every item's resolved name into {@code rawLine}; {@code null} when
         *  {@code resolver} cannot resolve one of {@link #names()} yet. The leading icon,
         *  every {@code ⟦PBn⟧} row break and each item's own colour run are copied through
         *  byte-for-byte. */
        public String compose(String rawLine, Function<String, String> resolver) {
            StringBuilder out = new StringBuilder(rawLine.length() + 16);
            int cursor = 0;
            for (Item item : items) {
                String value = resolver.apply(item.name());
                if (value == null) return null;
                out.append(rawLine, cursor, item.rawStart());
                out.append(value);
                cursor = item.rawEnd();
            }
            out.append(rawLine, cursor, rawLine.length());
            return out.toString();
        }
    }

    /** One maximal contiguous run of ⟦PBn⟧-delimited rows that independently satisfy
     *  {@link #ROW_SHAPE}, found by {@link #matchRuns}. {@code [start, end)} is the run's
     *  raw span in the text {@code matchRuns} was called with; {@code match}'s own item
     *  spans are relative to {@code start} (index 0 at the run's own first character). */
    public record Run(int start, int end, Match match) {
    }

    // ---- bounded two-generation memo (raw text -> Match for match(), or NONE) ----
    private static final int MEMO_GENERATION_SIZE = 512;
    private static final Object MEMO_LOCK = new Object();
    private static final Match NONE = new Match(List.of());
    private static Map<String, Match> memoYoung = new HashMap<>();
    private static Map<String, Match> memoOld = new HashMap<>();

    private ScrollNameListComposer() {
    }

    /** {@code null} unless EVERY ⟦PBn⟧-delimited row of {@code text} is an icon+name row
     *  (at least 2 rows) — mirrors {@link EnchantListComposer#match}'s whole-text contract
     *  for simple, direct unit tests. For a paragraph mixed with other content (a rarity
     *  line, a trade field, …) use {@link #matchRuns} instead, which tolerates that. */
    public static Match match(String text) {
        if (text == null || text.length() < 4) return null;
        synchronized (MEMO_LOCK) {
            Match hit = memoYoung.get(text);
            if (hit == null) hit = memoOld.get(text);
            if (hit != null) {
                rememberLocked(text, hit);
                return hit == NONE ? null : hit;
            }
        }
        Match computed = compute(text);
        synchronized (MEMO_LOCK) {
            rememberLocked(text, computed == null ? NONE : computed);
        }
        return computed;
    }

    private static void rememberLocked(String text, Match match) {
        memoYoung.put(text, match);
        if (memoYoung.size() >= MEMO_GENERATION_SIZE) {
            memoOld = memoYoung;
            memoYoung = new HashMap<>();
        }
    }

    /** Memoised entry count (both generations); test-only. */
    static int memoSize() {
        synchronized (MEMO_LOCK) {
            return memoYoung.size() + memoOld.size();
        }
    }

    private static Match compute(String text) {
        List<Run> runs = matchRuns(text);
        if (runs.size() != 1) return null;
        Run only = runs.get(0);
        return only.start() == 0 && only.end() == text.length() ? only.match() : null;
    }

    /** Tolerant variant: classifies each maximal contiguous run of ⟦PBn⟧-delimited rows
     *  that independently satisfy {@link #ROW_SHAPE} into its own {@link Run} (same "at
     *  least 2 rows" guard as {@link #match}), instead of requiring the entire text to be
     *  nothing but the list — used by {@link TooltipSegmentPlanner} so a scroll-name list
     *  glued to unrelated rows is still decomposed. Never {@code null}; empty when nothing
     *  qualifies. Not memoised (unlike {@link #match}): callers needing the hot per-frame
     *  path should prefer {@link #match} when the whole text is known to be the list.
     */
    public static List<Run> matchRuns(String text) {
        if (text == null || text.length() < 4) return List.of();
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null || rows.isEmpty()) return List.of();
        List<Run> runs = null;
        int i = 0;
        while (i < rows.size()) {
            int runStart = rows.get(i)[0];
            List<Item> runItems = null;
            int j = i;
            while (j < rows.size()) {
                int[] row = rows.get(j);
                Item item = matchOneRow(text.substring(row[0], row[1]));
                if (item == null) break;
                int offset = row[0] - runStart;
                if (runItems == null) runItems = new ArrayList<>();
                runItems.add(new Item(item.name(), item.rawStart() + offset, item.rawEnd() + offset));
                j++;
            }
            if (runItems != null && runItems.size() >= 2) {
                if (runs == null) runs = new ArrayList<>();
                runs.add(new Run(runStart, rows.get(j - 1)[1], new Match(List.copyOf(runItems))));
                i = j;
            } else {
                i++;
            }
        }
        return runs == null ? List.of() : List.copyOf(runs);
    }

    /** Shape+extraction for exactly ONE row (no ⟦PBn⟧ splitting): {@code null} unless the
     *  row is an icon-bulleted single name (raw offsets relative to {@code rowText}
     *  itself, index 0). Package-visible for {@link #matchRuns} and {@link
     *  TooltipSegmentPlanner}.
     *
     * <p>Two icon shapes are recognised: a RAW decorative glyph run (the render path, which
     * runs on PRE-template text — {@link #ROW_SHAPE}'s {@link #ICON} group), and an already
     * TEMPLATED {@code ⟦MTn⟧}/{@code ⟦WSn⟧} value token (the export-tool/cache-key path,
     * where {@link com.dragonmeow.nyanslate.translate.TemplateText}'s own {@code SYMBOL_RUN}
     * pattern has already replaced the row's leading icon run with a value slot BEFORE this
     * composer ever sees it — confirmed on real Hypixel data: a stored scroll-list cache key
     * reads {@code "⟦MT0⟧ Implosion ⟦PB0⟧ ⟦MT1⟧ Wither Shield …"}, never a raw glyph). The
     * old {@link #projectRow}-based scan already rejects ANY non-CS bracket token outright,
     * so without this a templated row was silently unrecognised — not "no icon", but
     * "icon became a token this composer could not see past" (2026-10-01 real-cache
     * calibration; see also {@link TooltipSegmentPlanner}'s own quantification work). */
    static Item matchOneRow(String rowText) {
        int afterLeadingToken = leadingValueTokenEnd(rowText);
        if (afterLeadingToken >= 0) {
            RowProjection projection = projectRow(rowText, afterLeadingToken, rowText.length());
            if (projection == null) return null;
            Matcher matcher = NAME_ONLY.matcher(projection.text);
            if (!matcher.matches()) return null;
            return extractItem(rowText, projection, matcher);
        }
        RowProjection projection = projectRow(rowText, 0, rowText.length());
        if (projection == null) return null;
        Matcher matcher = ROW_SHAPE.matcher(projection.text);
        if (!matcher.matches()) return null;
        return extractItem(rowText, projection, matcher);
    }

    private static Item extractItem(String rowText, RowProjection projection, Matcher matcher) {
        int projectedStart = matcher.start(1);
        int projectedEnd = matcher.end(1);
        int rawStart = projection.rawIndex[projectedStart];
        int rawEnd = projection.rawIndex[projectedEnd - 1] + 1;
        if (rawEnd - rawStart != projectedEnd - projectedStart) {
            return null; // a marker sits inside the name span: never composed
        }
        return new Item(rowText.substring(rawStart, rawEnd), rawStart, rawEnd);
    }

    /** The raw end index (exclusive) of a single leading {@code ⟦MTn⟧}/{@code ⟦WSn⟧} value
     *  token (optionally preceded by whitespace), or {@code -1} when the row does not start
     *  with one — {@code -1} is also returned for a leading {@code ⟦CSn⟧}/bare P-slot token
     *  (a colour run or a protected/masked value is never treated as a decorative icon). */
    private static int leadingValueTokenEnd(String text) {
        int i = 0;
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= n || text.charAt(i) != OPEN) return -1;
        int tokenEnd = protocolTokenEnd(text, i, n);
        if (tokenEnd < 0) return -1;
        String kind = tokenKind(text, i + 1, tokenEnd - 1);
        if (!"MT".equals(kind) && !"WS".equals(kind)) return -1;
        return tokenEnd;
    }

    /** {@code "MT"}/{@code "WS"} for a value-slot token body, or {@code null} for anything
     *  else (a CS marker, a bare numeric P-slot, an unrecognised token). */
    private static String tokenKind(String text, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        int letterStart = i;
        while (i < to && Character.isLetter(text.charAt(i))) i++;
        if (i == letterStart) return null;
        String letters = text.substring(letterStart, i);
        if (letters.equalsIgnoreCase("MT")) return "MT";
        if (letters.equalsIgnoreCase("WS")) return "WS";
        return null;
    }

    private static final class RowProjection {
        final String text;
        final int[] rawIndex;

        RowProjection(String text, int[] rawIndex) {
            this.text = text;
            this.rawIndex = rawIndex;
        }
    }

    /** Project one row (CS colour markers and bare format codes stripped) with a
     *  projected-position -> raw-position map; {@code null} when the row holds an
     *  unterminated marker, or any bracket token that is not a CS colour marker. */
    private static RowProjection projectRow(String text, int start, int end) {
        char[] projected = new char[end - start];
        int[] rawIndex = new int[end - start];
        int p = 0;
        int i = start;
        while (i < end) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int tokenEnd = protocolTokenEnd(text, i, end);
                if (tokenEnd < 0) return null;
                if (!isCsToken(text, i + 1, tokenEnd - 1)) return null;
                i = tokenEnd;
                continue;
            }
            if (c == SECTION && i + 1 < end) {
                i += text.charAt(i + 1) == OPEN ? 1 : 2;
                continue;
            }
            projected[p] = c;
            rawIndex[p++] = i;
            i++;
        }
        return new RowProjection(new String(projected, 0, p), Arrays.copyOf(rawIndex, p));
    }

    /** Whether the {@code ⟦…⟧} token body (between the brackets) is a CS colour marker
     *  (open or close); anything else (PB, MT, WS, a bare protected placeholder) is not. */
    private static boolean isCsToken(String text, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        if (i < to && text.charAt(i) == '/') {
            i++;
            while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        }
        return i + 2 <= to && text.charAt(i) == 'C' && text.charAt(i + 1) == 'S';
    }

    private static int protocolTokenEnd(String text, int open, int limit) {
        for (int j = open + 1; j < limit; j++) {
            char c = text.charAt(j);
            if (c == CLOSE) return j + 1;
            if (c == OPEN) return -1;
        }
        return -1;
    }
}
