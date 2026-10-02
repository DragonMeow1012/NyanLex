package com.dragonmeow.nyanslate.translate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a SkyBlock Auction House tooltip "field" row — {@code Seller:}/{@code
 * Buyer:}/{@code Bidder:} and {@code Buy it now:}/{@code Starting bid:}/{@code Top
 * bid:}/{@code Current bid:}/{@code Ends in:}/{@code Time left:} — that {@link
 * com.dragonmeow.nyanslate.fabric.FabricTextStyle#joinParagraph} glues, via {@code
 * ⟦PBn⟧}, to unrelated neighbouring rows (a rarity line, an item-name slot, …) with no
 * blank line between them into one otherwise opaque multi-row paragraph key.
 *
 * <p>Once such a row is isolated and requested on its own, ITS OWN masked/templated form
 * ({@code "Seller: ⟦0⟧"}, {@code "Buy it now: ⟦MT0⟧ coins"}) is already stable across
 * every seller/price/listing — {@link PlayerNamePatterns} neutralises the player name and
 * {@link TemplateText} neutralises the price the moment the row is requested/cached
 * standalone, exactly like any other short isolated line. This composer's only job is
 * therefore BOUNDARY detection: which {@code ⟦PBn⟧}-delimited rows of a larger paragraph
 * are a trade field, so {@link com.dragonmeow.nyanslate.service.TranslationService} can
 * treat each one as its own independent, cacheable unit instead of folding it into the
 * surrounding paragraph's single near-unique key.</p>
 *
 * <p>Unlike {@link RarityLineComposer}/{@link EnchantListComposer} this never resolves or
 * substitutes a WORD inside the row: {@link Item#text()} is the WHOLE raw row (colour
 * markers included), handed back to the caller to translate exactly as any other isolated
 * line would be — see {@link Match#compose}.</p>
 */
public final class TradeLineComposer {

    /** "Seller: Name", "Buyer: [MVP+] Name", "Bidder: Name" — the row may be wrapped in a
     *  shared colour run, so matching runs on the {@link TextFilter#stripFormatting}
     *  projection rather than the raw row. */
    private static final Pattern AUCTION_FIELD_ROW = Pattern.compile(
            "(?i)^\\s*(?:Seller|Buyer|Bidder)\\s*:\\s*\\S.*$");
    /** "Buy it now: 1,000,000 coins", "Starting bid: 500,000 coins", "Ends in: 2h 30m". */
    private static final Pattern PRICE_OR_TIMER_ROW = Pattern.compile(
            "(?i)^\\s*(?:Buy it now|Starting bid|Top bid|Current bid|Ends in|Time left)\\s*:\\s*\\S.*$");

    /** One claimed trade-field row: {@code [rawStart, rawEnd)} is the WHOLE row (never a
     *  sub-span within it) in the text {@link #match}/{@link #matchRow} was given. */
    public static final class Item {
        private final String text;
        private final int rawStart;
        private final int rawEnd;

        Item(String text, int rawStart, int rawEnd) {
            this.text = text;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
        }

        public String text() {
            return text;
        }

        public int rawStart() {
            return rawStart;
        }

        public int rawEnd() {
            return rawEnd;
        }
    }

    /** A recognised set of trade-field rows, in row order. */
    public static final class Match {
        private final List<Item> items;

        Match(List<Item> items) {
            this.items = items;
        }

        public List<Item> items() {
            return items;
        }

        /** Distinct row texts in first-appearance order — the independent translation
         *  units the caller needs to resolve/request (two auctions with the same seller
         *  share one key; the row text already fully determines it). */
        public List<String> texts() {
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            for (Item item : items) distinct.add(item.text());
            return List.copyOf(distinct);
        }

        /** Splice every item's resolved row text into {@code rawLine}; {@code null} when
         *  {@code resolver} cannot resolve one of {@link #texts()} yet. Everything between
         *  rows (the {@code ⟦PBn⟧} tokens, the rest of the paragraph) is copied through
         *  byte-for-byte. */
        public String compose(String rawLine, Function<String, String> resolver) {
            StringBuilder out = new StringBuilder(rawLine.length() + 16);
            int cursor = 0;
            for (Item item : items) {
                String value = resolver.apply(item.text());
                if (value == null) return null;
                out.append(rawLine, cursor, item.rawStart());
                out.append(value);
                cursor = item.rawEnd();
            }
            out.append(rawLine, cursor, rawLine.length());
            return out.toString();
        }
    }

    private TradeLineComposer() {
    }

    /** Shape test for exactly ONE row (no ⟦PBn⟧ splitting): the row itself when it is a
     *  trade field, or {@code null} otherwise. Package-visible for {@link
     *  TooltipSegmentPlanner}'s row-by-row classification. */
    static boolean matchesRow(String rowText) {
        if (rowText == null) return false;
        String projected = TextFilter.stripFormatting(rowText).strip();
        if (projected.isEmpty()) return false;
        return AUCTION_FIELD_ROW.matcher(projected).matches()
                || PRICE_OR_TIMER_ROW.matcher(projected).matches();
    }

    /** {@code null} when {@code text} holds no trade-field row at all; otherwise every
     *  {@code ⟦PBn⟧}-delimited row of {@code text} that independently matches one of the
     *  recognised shapes (rows that do not match are simply left unclaimed — this never
     *  rejects the whole text the way {@link EnchantListComposer#match} does). */
    public static Match match(String text) {
        if (text == null || text.length() < 4) return null;
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null || rows.isEmpty()) return null;
        List<Item> items = null;
        for (int[] row : rows) {
            String rowText = text.substring(row[0], row[1]);
            if (matchesRow(rowText)) {
                // ParagraphModel.join() pads every ⟦PBn⟧ token with a space on each side
                // (see ParagraphModel#breakToken), so a row that is not the very first/last
                // in the joined text carries a leading/trailing space the PB splitter
                // leaves attached to it. Trimming here (and shrinking the raw span to
                // match) keeps the unit text identical regardless of the row's position —
                // "Seller: DragonMeow" must stay ONE cache key whether it is tooltip row 0
                // or row 3 — while the trimmed whitespace stays in the untouched "gap"
                // compose() copies through, so spacing is unaffected either way.
                int[] trimmed = trimmedSpan(text, row[0], row[1]);
                if (trimmed[0] < trimmed[1]) {
                    if (items == null) items = new ArrayList<>();
                    items.add(new Item(text.substring(trimmed[0], trimmed[1]), trimmed[0], trimmed[1]));
                }
            }
        }
        return items == null ? null : new Match(List.copyOf(items));
    }

    /** Recognises a ⟦CSn⟧/⟦/CSn⟧ colour marker (same protocol as {@code
     *  FabricTextStyle#markChatContent}'s own MARKER pattern). */
    private static final Pattern CS_MARKER =
            Pattern.compile("⟦\\s*(/?)\\s*CS\\s*(\\d+)\\s*⟧");

    /**
     * Shrinks {@code [start, end)} past any leading/trailing horizontal whitespace AND any
     * ⟦CSn⟧ colour marker at the boundary that this span alone cannot balance: a leading
     * ⟦/CSn⟧ with no matching ⟦CSn⟧ earlier IN THIS SPAN (that run opened on a PREVIOUS
     * row, before the ⟦PBn⟧ row break this composer/{@link TooltipSegmentPlanner} just
     * split on), or a trailing ⟦CSn⟧ with no matching ⟦/CSn⟧ later in this span (that
     * run only closes on the NEXT row).
     *
     * <p>2026-10 fix for the CS-orphan request-loop bug: {@link TooltipSegmentPlanner}
     * carves a TRADE/STATS/PROSE/ABILITY segment out of a paragraph whose ⟦CSn⟧ colour
     * runs were marked ACROSS the WHOLE paragraph before any row-level splitting ever
     * happened (see {@code FabricTextStyle#markChatContent}); a colour run shared by the
     * row-break boundary itself (the identical colour either side of a ⟦PBn⟧ token —
     * real Hypixel stat blocks confirm this) then lands exactly half inside this row and
     * half inside its neighbour. Sending that half-pair to the translator as this unit's
     * own text can never pass {@code TranslationCache#matchingCsShape}: the SOURCE itself
     * is already unbalanced, so EVERY response — even a perfect verbatim echo of the
     * exact markers sent — is rejected forever (the live bug: a deterministic
     * {@code FAILED(format/token lost)} retried every ~2s, burning tokens with no way to
     * ever succeed).</p>
     *
     * <p>The marker is never deleted, only EXCLUDED from this segment's own span: {@link
     * TooltipSegmentPlanner#compose}/{@link Match#compose}/{@link
     * StatsLineComposer.Match#compose} always copy the untouched text BETWEEN two
     * segments through byte-for-byte, so the orphaned marker still renders at exactly its
     * original position. When BOTH neighbours trim their own half away — the common
     * case: a trailing ⟦CSn⟧ orphan on one row, a leading ⟦/CSn⟧ orphan on the very
     * next — the untouched gap between the two segments reconstitutes the COMPLETE,
     * balanced ⟦CSn⟧…⟦/CSn⟧ pair on its own, unchanged, with zero extra bookkeeping.</p>
     */
    static int[] trimmedSpan(String text, int start, int end) {
        int s = start;
        int e = end;
        // Bounded: each round either shrinks the span or returns immediately, so this
        // never loops more than the handful of adjacent markers a real row could ever
        // carry at one boundary; the guard only protects against a future change breaking
        // that invariant.
        for (int guard = 0; guard < 8; guard++) {
            int s1 = skipLeadingWhitespace(text, s, e);
            int e1 = skipTrailingWhitespace(text, s1, e);
            int s2 = skipLeadingOrphanClose(text, s1, e1);
            int e2 = skipTrailingOrphanOpen(text, s2, e1);
            if (s2 == s && e2 == e) return new int[] {s2, e2};
            s = s2;
            e = e2;
        }
        return new int[] {s, e};
    }

    private static int skipLeadingWhitespace(String text, int start, int end) {
        int s = start;
        while (s < end && isTrimmable(text.charAt(s))) s++;
        return s;
    }

    private static int skipTrailingWhitespace(String text, int start, int end) {
        int e = end;
        while (e > start && isTrimmable(text.charAt(e - 1))) e--;
        return e;
    }

    /** {@code start} moved past a leading ⟦/CSn⟧ at the very front of {@code [start,
     *  end)} — orphaned by construction: nothing can precede it inside the span to match
     *  it. */
    private static int skipLeadingOrphanClose(String text, int start, int end) {
        if (start >= end || text.charAt(start) != '⟦') return start;
        Matcher m = CS_MARKER.matcher(text).region(start, end);
        if (!m.lookingAt()) return start;
        boolean closing = !m.group(1).isEmpty();
        return closing ? m.end() : start;
    }

    /** {@code end} moved back past a trailing ⟦CSn⟧ at the very back of {@code [start,
     *  end)} — orphaned by construction: nothing can follow it inside the span to match
     *  it (the last marker {@code find()} locates in the span is, by definition, the one
     *  closest to {@code end}; if it is an OPEN marker and it ends exactly at {@code end},
     *  no close for it exists anywhere in the span). */
    private static int skipTrailingOrphanOpen(String text, int start, int end) {
        if (start >= end) return end;
        Matcher m = CS_MARKER.matcher(text).region(start, end);
        int lastStart = -1;
        int lastEnd = -1;
        boolean lastClosing = true;
        while (m.find()) {
            lastStart = m.start();
            lastEnd = m.end();
            lastClosing = !m.group(1).isEmpty();
        }
        if (lastEnd == end && !lastClosing) return lastStart;
        return end;
    }

    private static boolean isTrimmable(char c) {
        return c == ' ' || c == '	' || c == ' ';
    }
}
