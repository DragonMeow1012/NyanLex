package com.dragonmeow.nyanlex.translate;

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
 * P2: recognises a SkyBlock tooltip paragraph that is nothing but a comma-separated
 * "Enchant Name + Level" list — one or more rows joined by {@code ⟦PBn⟧}, for example
 * {@code "Soul Eater V, Toxophilite IV, Chance IV"} on one row and
 * {@code "Cubism V, Power VI, Snipe III"} on the next — so
 * {@link com.dragonmeow.nyanlex.service.TranslationService} can decompose it into one
 * independent, cacheable translation unit PER ENCHANT NAME instead of sending the whole,
 * near-combinatorially-unique paragraph as one translation. That whole-paragraph shape
 * is exactly why the reported bug happened: almost every item has a different
 * combination/order of enchants and levels, so the paragraph text is essentially never
 * reused, and every fresh hover bought a brand new (and inconsistently ordered/worded)
 * translation of the whole list.
 *
 * <p>Matching runs on a per-row projection with {@code ⟦CSn⟧}/{@code ⟦/CSn⟧} colour
 * markers and bare {@code §x} format codes removed (same technique as
 * {@link RarityLineComposer}); rows are split first on {@code ⟦PBn⟧} paragraph-break
 * tokens (kept byte-for-byte; never part of a name span). Every row of the paragraph
 * must independently be a valid comma list of "Title Case name" + "Roman numeral or
 * Arabic level" pairs (a trailing comma is tolerated); the whole paragraph is rejected —
 * falling through to the ordinary lookup exactly as if this class did not exist — unless
 * at least one row holds 2 or more items, so an isolated single-enchant row (e.g.
 * {@code "Sharpness VII"}) is left alone: there is no reordering risk and nothing to
 * gain from decomposing it.</p>
 *
 * <p>Only the exact NAME span of each item is ever meant to be replaced; the level,
 * every comma (including a trailing one), every {@code ⟦PBn⟧} row break and each item's
 * own {@code ⟦CSn⟧} colour run travel through {@link Match#compose} completely
 * unchanged, so the glue keeps colouring exactly as it did before. If a marker turns out
 * to sit INSIDE a name span — the fixed shape this class recognises never produces that
 * — the whole match is rejected rather than risk splicing across a colour boundary,
 * mirroring {@link RarityLineComposer}.</p>
 *
 * <p>Cost: a literal prefilter (a comma AND a digit-or-Roman-letter) rejects any row
 * without one before a projection is even built, and accepted/rejected results are
 * memoised in a bounded two-generation map keyed by the raw paragraph text.</p>
 */
public final class EnchantListComposer {

    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';
    private static final char SECTION = '§';

    /** Title Case word: apostrophe/hyphen allowed inside, never as the first character. */
    private static final String WORD = "[A-Z][A-Za-z]*(?:['’-][A-Za-z]+)*";
    /** A small, fixed whitelist of lowercase connective words tolerated in the MIDDLE of
     *  a multi-word enchant name only — never as the first or last token — so names such
     *  as {@code "Bane of Arthropods"}, {@code "Luck of the Sea"} and {@code "Chef de
     *  Partie"} still satisfy {@link #ROW_SHAPE} instead of dragging the whole row (and
     *  then the whole paragraph) back into whole-line translation. Kept deliberately
     *  short: an ordinary English sentence reaches for far more function words than these
     *  nine, so it still fails {@link #ROW_SHAPE} (no comma-separated NAME+LEVEL items at
     *  all) exactly as before. Because every word here is lowercase and {@link #WORD}
     *  always starts uppercase, a token can never match both, so this alternation adds no
     *  backtracking ambiguity. NAME's own shape — it must both start AND end with a
     *  {@link #WORD} by construction (see {@link #NAME}) — is what keeps a connector from
     *  ever opening or closing a name. */
    private static final String CONNECTOR = "(?:of|the|and|de|du|la|le|von|a)";
    /** One or more {@link #WORD}s, optionally with {@link #CONNECTOR} tokens between them
     *  (never first, never last — the trailing {@code (?:\s+WORD)} below is what always
     *  supplies the final token, so a lone trailing connector can never be the end of a
     *  match: the engine backtracks until it finds a real closing WORD, or the whole item
     *  fails to match). */
    private static final String NAME =
            WORD + "(?:\\s+(?:" + CONNECTOR + "\\s+)*" + WORD + ")*";
    /** Every legal Roman-numeral shape from I to XX (the practical SkyBlock enchant
     *  level range); deliberately an explicit enumeration rather than a general Roman
     *  numeral grammar, matching the task's own "I–XX 範圍的合法形狀" scope. */
    private static final String ROMAN =
            "I|II|III|IV|V|VI|VII|VIII|IX|X|XI|XII|XIII|XIV|XV|XVI|XVII|XVIII|XIX|XX";
    private static final String ARABIC = "[0-9]{1,3}";
    private static final String LEVEL = "(?:" + ROMAN + "|" + ARABIC + ")";
    private static final String ITEM_SHAPE = NAME + "\\s+" + LEVEL;

    /** Whole-row validator: no captures, used only to reject any row holding something
     *  other than a comma-separated list of NAME+LEVEL items (an optional trailing comma
     *  tolerated). */
    private static final Pattern ROW_SHAPE = Pattern.compile(
            "^\\s*" + ITEM_SHAPE + "(?:\\s*,\\s*" + ITEM_SHAPE + ")*\\s*,?\\s*$");
    /** Extraction pattern applied (via repeated {@code find()}) to a row already
     *  confirmed by {@link #ROW_SHAPE}; group 1 is one item's NAME span. */
    private static final Pattern ITEM_NAME = Pattern.compile("(" + NAME + ")\\s+" + LEVEL);

    /** One recognised item: {@code [rawStart, rawEnd)} is this item's NAME span only
     *  (never the level) in the original raw paragraph text. */
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

    /** A recognised enchant-list paragraph: every item, in row/left-to-right order. */
    public static final class Match {
        private final List<Item> items;

        Match(List<Item> items) {
            this.items = items;
        }

        public List<Item> items() {
            return items;
        }

        /** Distinct enchant names in first-appearance order — the independent
         *  translation units the caller needs to resolve/request. */
        public List<String> names() {
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            for (Item item : items) distinct.add(item.name());
            return List.copyOf(distinct);
        }

        /** Splice every item's resolved name into {@code rawLine}; {@code null} when
         *  {@code resolver} cannot resolve one of {@link #names()} yet. The level,
         *  separators, row breaks and each item's own colour run are copied through
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

    // ---- bounded two-generation memo (raw paragraph text -> Match, or NONE) ----
    private static final int MEMO_GENERATION_SIZE = 512;
    private static final Object MEMO_LOCK = new Object();
    private static final Match NONE = new Match(List.of());
    private static Map<String, Match> memoYoung = new HashMap<>();
    private static Map<String, Match> memoOld = new HashMap<>();

    private EnchantListComposer() {
    }

    /** {@code null} when {@code text} is not a whole-paragraph enchant-name list with at
     *  least one 2+-item row. Never mutates or allocates beyond the memo on a repeat call
     *  with the same string. */
    public static Match match(String text) {
        if (text == null || text.length() < 4 || !mayBeEnchantList(text)) return null;
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

    /** Cheap literal rejection before any projection/regex work: a genuine enchant list
     *  always has a comma AND at least one digit or Roman-numeral letter somewhere. */
    private static boolean mayBeEnchantList(String text) {
        boolean sawComma = false;
        boolean sawLevelHint = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',') sawComma = true;
            else if (c >= '0' && c <= '9' || c == 'I' || c == 'V' || c == 'X') sawLevelHint = true;
            if (sawComma && sawLevelHint) return true;
        }
        return false;
    }

    private static Match compute(String text) {
        List<int[]> rows = splitRowsByParagraphBreak(text);
        if (rows == null || rows.isEmpty()) return null;
        List<Item> items = new ArrayList<>();
        int maxItemsInRow = 0;
        for (int[] row : rows) {
            List<Item> rowItems = matchOneRow(text.substring(row[0], row[1]));
            if (rowItems == null) return null;
            items.addAll(shift(rowItems, row[0]));
            maxItemsInRow = Math.max(maxItemsInRow, rowItems.size());
        }
        // An isolated single-item row (or a paragraph of several such rows) has no
        // reordering risk and nothing to gain from decomposition; leave it alone.
        if (maxItemsInRow < 2) return null;
        return new Match(List.copyOf(items));
    }

    /**
     * Shape+extraction for exactly ONE row (no ⟦PBn⟧ splitting): this row's own NAME+LEVEL
     * items when it independently satisfies {@link #ROW_SHAPE} (raw offsets relative to
     * {@code rowText} itself, index 0), or {@code null} when it does not — an
     * unterminated/non-CS marker, a shape mismatch, or nothing actually extracted.
     *
     * <p>Package-visible so {@link #matchRuns} can group a maximal run of matching rows
     * without requiring EVERY row of a larger, mixed paragraph to match (unlike {@link
     * #match}, which rejects the whole text the moment one row does not fit — see
     * {@code paragraphMixedWithOtherContentIsRejectedEntirely}).</p>
     */
    static List<Item> matchOneRow(String rowText) {
        RowProjection projection = projectRow(rowText, 0, rowText.length());
        if (projection == null) return null;
        String line = projection.text;
        if (!ROW_SHAPE.matcher(line).matches()) return null;
        List<Item> items = new ArrayList<>();
        Matcher found = ITEM_NAME.matcher(line);
        while (found.find()) {
            int projectedStart = found.start(1);
            int projectedEnd = found.end(1);
            int rawStart = projection.rawIndex[projectedStart];
            int rawEnd = projection.rawIndex[projectedEnd - 1] + 1;
            if (rawEnd - rawStart != projectedEnd - projectedStart) {
                return null; // a marker sits inside the name span: never composed
            }
            items.add(new Item(rowText.substring(rawStart, rawEnd), rawStart, rawEnd));
        }
        return items.isEmpty() ? null : items; // shape matched but nothing extracted: be conservative
    }

    private static List<Item> shift(List<Item> items, int offset) {
        if (offset == 0) return items;
        List<Item> out = new ArrayList<>(items.size());
        for (Item item : items) {
            out.add(new Item(item.name(), item.rawStart() + offset, item.rawEnd() + offset));
        }
        return out;
    }

    /** One maximal contiguous run of ⟦PBn⟧-delimited rows that independently satisfy
     *  {@link #ROW_SHAPE}, found by {@link #matchRuns}. {@code [start, end)} is the run's
     *  raw span in the text {@code matchRuns} was called with; {@code match}'s own item
     *  spans are relative to {@code start} (index 0 at the run's own first character), so
     *  a caller resolves it with {@code match.compose(text.substring(start, end), ...)}. */
    public record Run(int start, int end, Match match) {
    }

    /**
     * Tolerant variant of {@link #match}: classifies each maximal contiguous run of
     * ⟦PBn⟧-delimited rows that independently satisfy {@link #ROW_SHAPE} into its own
     * {@link Run} (same per-run "at least one row with 2+ items" guard as {@link #match}),
     * instead of requiring EVERY row of {@code text} to match. A row outside any such run
     * is simply left unclaimed — used by {@link TooltipSegmentPlanner} so a comma enchant
     * list glued to unrelated rows (a rarity line, a trade field, …) via {@code ⟦PBn⟧} is
     * still decomposed, instead of {@link #match} rejecting the whole paragraph outright.
     * Never {@code null}; empty when nothing qualifies.
     */
    public static List<Run> matchRuns(String text) {
        if (text == null || text.length() < 4 || !mayBeEnchantList(text)) return List.of();
        List<int[]> rows = splitRowsByParagraphBreak(text);
        if (rows == null || rows.isEmpty()) return List.of();
        List<Run> runs = null;
        int i = 0;
        while (i < rows.size()) {
            int runStart = rows.get(i)[0];
            List<Item> runItems = null;
            int maxItemsInRow = 0;
            int j = i;
            while (j < rows.size()) {
                int[] row = rows.get(j);
                List<Item> rowItems = matchOneRow(text.substring(row[0], row[1]));
                if (rowItems == null) break;
                if (runItems == null) runItems = new ArrayList<>();
                runItems.addAll(shift(rowItems, row[0] - runStart));
                maxItemsInRow = Math.max(maxItemsInRow, rowItems.size());
                j++;
            }
            if (runItems != null && maxItemsInRow >= 2) {
                if (runs == null) runs = new ArrayList<>();
                runs.add(new Run(runStart, rows.get(j - 1)[1], new Match(List.copyOf(runItems))));
                i = j;
            } else {
                i++;
            }
        }
        return runs == null ? List.of() : List.copyOf(runs);
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
     *  unterminated marker, or any bracket token that is not a CS colour marker (a stray
     *  {@code ⟦PBn⟧} would mean this "row" boundary was computed wrong; anything else —
     *  MT/WS/a bare protected placeholder — has no place in a pre-mask enchant row). */
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

    /** Split {@code text} into raw {@code [start, end)} rows on every {@code ⟦PBn⟧}
     *  token (the token itself belongs to neither row); {@code null} on an unterminated
     *  marker anywhere. Text with no PB token at all yields exactly one row. Delegates to
     *  {@link ParagraphModel#splitRawRows}, shared with every other row-aware composer. */
    private static List<int[]> splitRowsByParagraphBreak(String text) {
        return ParagraphModel.splitRawRows(text);
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
