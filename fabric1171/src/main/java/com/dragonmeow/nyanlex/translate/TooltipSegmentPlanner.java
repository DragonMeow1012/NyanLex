package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies a tooltip paragraph already joined with {@code ⟦PBn⟧} row breaks (see
 * {@code FabricTextStyle#joinParagraph}) into independent, per-row/per-run translation
 * segments, so {@link com.dragonmeow.nyanlex.service.TranslationService} can cache/
 * request each one on its own instead of treating every row glued without a blank line
 * between them (a rarity line, a trade field, an ability-name list, an item-name slot, …)
 * as one opaque, near-combinatorially-unique multi-row key.
 *
 * <p>Delegates all shape recognition to the five existing row-aware composers —
 * {@link RarityLineComposer#matchRuns}, {@link EnchantListComposer#matchRuns}, {@link
 * ScrollNameListComposer#matchRuns}, {@link TradeLineComposer#matchesRow}, {@link
 * StatsLineComposer#matchesRow} — plus its own {@code Ability:}/{@code Cooldown:} block
 * carve-out and blank/placeholder ({@link #isInert}) recognition.</p>
 *
 * <p><b>Partial planning (2026-10-01 real-cache calibration):</b> real 97,691-row account
 * data showed the ORIGINAL all-or-nothing design ("one genuinely unrecognised row declines
 * the WHOLE paragraph") left only 2 rows splittable — 93.3% of abandoned multi-row
 * paragraphs were declined purely because they carried ordinary lore/description PROSE
 * glued next to an otherwise perfectly classifiable row (a rarity line, a trade field, a
 * stat row), not because that other row was unrecognisable. {@link #plan} now instead
 * merges every maximal run of consecutive rows none of the five composers (or the
 * {@code Ability:}/{@code Cooldown:} block check) recognises into ONE {@link Kind#PROSE}
 * segment — kept whole, never further split, so Chinese word order is never cut mid-
 * sentence — and still returns a {@link Plan} as long as AT LEAST ONE row is independently
 * RARITY/ENCHANT/TRADE/STATS/SCROLL (an {@code Ability:}/{@code Cooldown:} block alone does
 * NOT by itself authorise planning — seen standalone in >99% of real occurrences, where
 * carving it out would just reproduce today's single-whole-text cache key one layer deeper,
 * for zero gain but non-zero new-code risk; it still gets its OWN {@link Kind#ABILITY}
 * segment, never bailing the whole plan, the moment some OTHER row on the same paragraph IS
 * independently classifiable). A paragraph where NOTHING is independently classifiable
 * still declines entirely ({@code null}), exactly matching pre-existing behaviour for a
 * genuinely plain multi-row prose paragraph (no new cache key shape, no new risk, nothing to
 * gain from decomposing it). A PROSE/ABILITY segment's own raw span is itself a normal,
 * independent cache key — see {@code TranslationService#resolveEnchantName}/{@code
 * requestEnchantName}, reused verbatim for these segments exactly as for TRADE/STATS, so the
 * SAME free-form description text recurring across many different items (confirmed on real
 * data, e.g. a reusable "Works while in Accessory Bag!" footnote) shares ONE translation.</p>
 *
 * <p><b>Known limitation — a multi-row PROSE/ABILITY segment's own INTERNAL {@code ⟦PBn⟧}
 * tokens keep their GLOBAL index</b> from the full paragraph, not a locally-renumbered one
 * (unlike {@link LocalTokenRenumberer}'s legacy-whole-cache conversion path, which exists
 * precisely because this gap matters there too). A single-row PROSE run (the overwhelming
 * majority in practice — most unrecognised content is one row) is unaffected: no internal
 * PB token exists to renumber, and the leading/trailing join padding is already trimmed
 * (see {@link #flushProse}), so its key is fully position-independent. A multi-row block
 * (chiefly {@link Kind#ABILITY}) only shares a cache key with another occurrence of the
 * identical text when the SAME number of rows precedes it in both paragraphs (true for same-
 * type gear in practice, since the ability block's own internal row count is fixed) — a
 * narrower reduction in reuse, never an incorrect composition, consistent with this
 * codebase's existing scope boundaries (compare ENCHANT/SCROLL's deliberate exclusion from
 * legacy-cache conversion in {@code TranslationService#convertLegacyWholeCache}).</p>
 */
public final class TooltipSegmentPlanner {

    /** "Ability: Flame Breath", "Item Ability: Rain of Arrows RIGHT CLICK" — the start of
     *  an ability description block. Matched per-ROW (not against the whole text, unlike
     *  the pre-2026-10-01 design), so only the rows between this and {@link #ABILITY_END}
     *  (inclusive) are carved out as one {@link Kind#ABILITY} segment; any OTHER row of the
     *  same paragraph is still classified normally. */
    private static final Pattern ABILITY_START = Pattern.compile("(?i)\\b(?:Item )?Ability:\\s*\\S");
    /** "Cooldown: 20s" — real Hypixel data confirms every multi-row Ability block that has
     *  one at all carries it as its OWN trailing row (never combined with other text on the
     *  same row); ends the block, inclusive. A block with no such row simply runs to the end
     *  of the paragraph (the conservative, always-safe fallback — never splits anything that
     *  should have stayed inside the block). */
    private static final Pattern ABILITY_END = Pattern.compile(
            "(?i)^\\s*(?:\\u27E6\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7\\s*)*Cooldown\\s*:");

    public enum Kind { RARITY, ENCHANT, TRADE, STATS, SCROLL, PROSE, ABILITY, INERT }

    /**
     * One classified row or run. {@code [start, end)} is the raw span in the text {@link
     * #plan} was given. {@code detail} is composer-specific: a {@link
     * RarityLineComposer.Match} for RARITY, an {@link EnchantListComposer.Match} for
     * ENCHANT, a {@link ScrollNameListComposer.Match} for SCROLL — each already carrying
     * item/word spans relative to {@code start} (index 0), so a caller resolves it with
     * {@code match.compose(original.substring(start, end), resolver)}. {@code null} for
     * TRADE/STATS/PROSE/ABILITY/INERT: the segment's own raw text (via {@code
     * original.substring(start, end)}) is everything a resolver needs for those.
     */
    public record Segment(Kind kind, int start, int end, Object detail) {
    }

    /** Every row of the planned text, contiguous and in row order — see {@link #plan}. */
    public record Plan(List<Segment> segments) {
    }

    /**
     * Resolves one classified segment's own raw span to its translated form, or {@code
     * null} while still missing. A resolver is expected to QUEUE the missing piece itself
     * as a side effect (mirroring {@link EnchantListComposer.Match}'s existing resolver
     * contract) — {@link #compose} always calls every segment's resolver once per pass,
     * even after an earlier one came back missing, so nothing is left unqueued.
     */
    @FunctionalInterface
    public interface SegmentResolver {
        String resolve(Segment segment, String rawSegmentText);
    }

    private TooltipSegmentPlanner() {
    }

    // ---- bounded two-generation memo (raw text + allowRarity -> Plan, or NONE) ----
    // 2026-10-01 audit finding (segment review): composeStructuredTooltip/
    // isTooltipTranslationReady/isTooltipTranslationPending each independently call plan()
    // on the SAME tooltip text within one render frame (glue confirms ~3 calls/frame while a
    // tooltip stays on screen), yet plan() re-ran every composer's matchRuns() — full
    // ParagraphModel.splitRawRows + per-row regex matching — from scratch every single time,
    // bypassing the exact "two-generation memo" convention this codebase otherwise uses
    // everywhere else a composer is re-invoked per-frame (RarityLineComposer/
    // EnchantListComposer/ScrollNameListComposer's own matchRuns already skip their internal
    // match()/compute() work via memoYoung/memoOld — only the Planner's OWN classification
    // loop around them was unmemoized). Mirrors that same convention exactly.
    private static final int MEMO_GENERATION_SIZE = 512;
    private static final Object MEMO_LOCK = new Object();
    /** Sentinel for "memoized, and the real result is null" — plan() only ever returns a
     *  non-null Plan with at least one segment (anyClassifiable guards it), so an empty-
     *  segment Plan can never occur naturally and is safe to use as a reference-compared
     *  sentinel here. */
    private static final Plan NONE = new Plan(List.of());
    private static Map<String, Plan> memoYoung = new HashMap<>();
    private static Map<String, Plan> memoOld = new HashMap<>();

    /**
     * {@code null} when {@code text} is not eligible for row-level decomposition at all:
     * fewer than 2 rows, or no row is independently RARITY/ENCHANT/TRADE/STATS/SCROLL (see
     * class javadoc "Partial planning" — an {@code Ability:} block or plain prose ALONE
     * never earns a {@link Plan} by itself). {@code allowRarity} gates {@link
     * RarityLineComposer}'s fixed term-table composition, which — like {@link
     * com.dragonmeow.nyanlex.service.TranslationService#composeRarityLine} — only applies
     * to a zh-TW/zh-HK target; for any other target a rarity-shaped row is simply treated
     * as unclassified prose, exactly preserving today's behaviour for those targets.
     *
     * <p>Memoized (bounded two-generation cache, like every other row-classifying composer
     * in this package): repeat calls with the same {@code text}/{@code allowRarity} pair —
     * expected multiple times per render frame while a tooltip is on screen — skip the
     * whole row-walk/composer re-invocation on a cache hit.</p>
     */
    public static Plan plan(String text, boolean allowRarity) {
        if (text == null) return null;
        String key = text + '\u0000' + (allowRarity ? '1' : '0');
        synchronized (MEMO_LOCK) {
            Plan hit = memoYoung.get(key);
            if (hit == null) hit = memoOld.get(key);
            if (hit != null) {
                rememberLocked(key, hit);
                return hit == NONE ? null : hit;
            }
        }
        Plan computed = computePlan(text, allowRarity);
        synchronized (MEMO_LOCK) {
            rememberLocked(key, computed == null ? NONE : computed);
        }
        return computed;
    }

    private static void rememberLocked(String key, Plan plan) {
        memoYoung.put(key, plan);
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

    private static Plan computePlan(String text, boolean allowRarity) {
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null || rows.size() < 2) return null;

        Map<Integer, Integer> rowIndexAfterEnd = new HashMap<>(rows.size() * 2);
        for (int k = 0; k < rows.size(); k++) rowIndexAfterEnd.put(rows.get(k)[1], k + 1);

        Map<Integer, RarityLineComposer.Run> rarityByStart = allowRarity
                ? indexByStart(RarityLineComposer.matchRuns(text), RarityLineComposer.Run::start)
                : Map.of();
        Map<Integer, EnchantListComposer.Run> enchantByStart =
                indexByStart(EnchantListComposer.matchRuns(text), EnchantListComposer.Run::start);
        Map<Integer, ScrollNameListComposer.Run> scrollByStart =
                indexByStart(ScrollNameListComposer.matchRuns(text), ScrollNameListComposer.Run::start);

        List<Segment> segments = new ArrayList<>();
        boolean anyClassifiable = false;
        int[] prose = {-1, -1}; // [proseStart, proseEnd]; proseStart < 0 = no pending run
        int i = 0;
        while (i < rows.size()) {
            int[] row = rows.get(i);
            String rowText = text.substring(row[0], row[1]);

            if (ABILITY_START.matcher(rowText).find()) {
                flushProse(segments, text, prose);
                int end = i;
                boolean stoppedBeforeClassifiable = false;
                while (end < rows.size()
                        && !ABILITY_END.matcher(text.substring(rows.get(end)[0], rows.get(end)[1])).find()) {
                    // A rarity line or an auction trade row is never part of an ability
                    // description: close the block just BEFORE it (exclusive), so a block
                    // with no "Cooldown:" row no longer swallows the rarity/Seller/Buy-it-now
                    // rows after it into one never-reusable key (2026-10-02 colour/rank
                    // sharing: those rows must stay independent, shareable segments).
                    if (end > i && (rarityByStart.containsKey(rows.get(end)[0])
                            || TradeLineComposer.matchesRow(
                                    text.substring(rows.get(end)[0], rows.get(end)[1])))) {
                        stoppedBeforeClassifiable = true;
                        break;
                    }
                    end++;
                }
                int lastRowInBlock = stoppedBeforeClassifiable
                        ? end - 1 : Math.min(end, rows.size() - 1);
                // Same leading/trailing ⟦PBn⟧-join padding trim as TRADE/STATS below (see
                // TradeLineComposer#trimmedSpan): keeps the segment's own raw span — and
                // therefore its cache key — identical regardless of row position, so the
                // SAME ability block recurring on another item still shares one key.
                int[] trimmed = TradeLineComposer.trimmedSpan(text, row[0], rows.get(lastRowInBlock)[1]);
                segments.add(new Segment(Kind.ABILITY, trimmed[0], trimmed[1], null));
                i = lastRowInBlock + 1;
                continue;
            }

            RarityLineComposer.Run rarity = rarityByStart.get(row[0]);
            if (rarity != null) {
                flushProse(segments, text, prose);
                segments.add(new Segment(Kind.RARITY, rarity.start(), rarity.end(), rarity.match()));
                anyClassifiable = true;
                i = rowIndexAfterEnd.get(rarity.end());
                continue;
            }
            EnchantListComposer.Run enchant = enchantByStart.get(row[0]);
            if (enchant != null) {
                flushProse(segments, text, prose);
                segments.add(new Segment(Kind.ENCHANT, enchant.start(), enchant.end(), enchant.match()));
                anyClassifiable = true;
                i = rowIndexAfterEnd.get(enchant.end());
                continue;
            }
            ScrollNameListComposer.Run scroll = scrollByStart.get(row[0]);
            if (scroll != null) {
                flushProse(segments, text, prose);
                segments.add(new Segment(Kind.SCROLL, scroll.start(), scroll.end(), scroll.match()));
                anyClassifiable = true;
                i = rowIndexAfterEnd.get(scroll.end());
                continue;
            }
            if (TradeLineComposer.matchesRow(rowText)) {
                flushProse(segments, text, prose);
                // Trim the ⟦PBn⟧ join's leading/trailing space (see
                // TradeLineComposer#trimmedSpan) so the segment's own raw span — what
                // TranslationService resolves/caches/requests — is position-independent.
                int[] trimmed = TradeLineComposer.trimmedSpan(text, row[0], row[1]);
                segments.add(new Segment(Kind.TRADE, trimmed[0], trimmed[1], null));
                anyClassifiable = true;
                i++;
                continue;
            }
            if (StatsLineComposer.matchesRow(rowText)) {
                flushProse(segments, text, prose);
                int[] trimmed = TradeLineComposer.trimmedSpan(text, row[0], row[1]);
                segments.add(new Segment(Kind.STATS, trimmed[0], trimmed[1], null));
                anyClassifiable = true;
                i++;
                continue;
            }
            if (isInert(rowText)) {
                flushProse(segments, text, prose);
                segments.add(new Segment(Kind.INERT, row[0], row[1], null));
                i++;
                continue;
            }
            // Genuinely unclassified row: extend (or start) the pending PROSE run instead
            // of declining the whole plan (see class javadoc "Partial planning").
            if (prose[0] < 0) prose[0] = row[0];
            prose[1] = row[1];
            i++;
        }
        flushProse(segments, text, prose);
        if (!anyClassifiable) return null;
        return new Plan(List.copyOf(segments));
    }

    /** Appends the pending PROSE run (if any) as one trimmed segment and clears it —
     *  shared by every branch of {@link #plan}'s row walk so a PROSE run is always closed
     *  out before a classified row/block starts, and once more after the loop ends. */
    private static void flushProse(List<Segment> segments, String text, int[] prose) {
        if (prose[0] < 0) return;
        // Same leading/trailing ⟦PBn⟧-join padding trim as TRADE/STATS/ABILITY (see
        // TradeLineComposer#trimmedSpan): without it, the SAME recurring prose/footnote text
        // (confirmed on real data, e.g. "Works while in Accessory Bag!") would mint a
        // DIFFERENT cache key depending on which row position it happened to sit at on a
        // given item — defeating the entire point of giving it an independent segment key.
        int[] trimmed = TradeLineComposer.trimmedSpan(text, prose[0], prose[1]);
        segments.add(new Segment(Kind.PROSE, trimmed[0], trimmed[1], null));
        prose[0] = -1;
        prose[1] = -1;
    }

    private static <T> Map<Integer, T> indexByStart(List<T> runs, java.util.function.ToIntFunction<T> start) {
        if (runs.isEmpty()) return Map.of();
        Map<Integer, T> byStart = new HashMap<>(runs.size() * 2);
        for (T run : runs) byStart.put(start.applyAsInt(run), run);
        return byStart;
    }

    /** A row with no letter at all once colour markers/format codes are stripped — a
     *  blank row, or a row that is nothing but a decorative icon/number placeholder.
     *  Nothing to translate, so it is always "resolved" verbatim, no request ever needed. */
    private static boolean isInert(String rowText) {
        String projected = TextFilter.stripFormatting(rowText);
        for (int i = 0; i < projected.length(); ) {
            int cp = projected.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) return false;
        }
        return true;
    }

    /**
     * Resolve every segment of {@code plan} against {@code original} using {@code
     * resolver}, then splice the results back in; {@code null} when at least one segment
     * is still missing — {@code resolver} is still called for EVERY segment first (so a
     * resolver that queues a request as a side effect queues every missing piece in one
     * pass, not just the first), matching {@link EnchantListComposer.Match#compose}'s
     * existing "resolve everything or compose nothing" contract, generalised across kinds.
     */
    public static String compose(String original, Plan plan, SegmentResolver resolver) {
        return composeDetailed(original, plan, resolver).full();
    }

    /**
     * Result of one resolver pass over a plan.
     *
     * @param full     every segment resolved, spliced; {@code null} while any is missing
     * @param partial  the segments that DID resolve spliced in, each still-missing segment kept
     *                 as its raw original span (tokens untouched, so the paragraph's marker
     *                 multiset is unchanged); {@code null} unless some segment that needed the
     *                 provider (anything but a locally composed rarity row) resolved and some
     *                 other segment did not
     * @param resolved number of non-inert segments that resolved
     * @param total    number of non-inert segments
     */
    public record Composition(String full, String partial, int resolved, int total) {
    }

    /** Like {@link #compose}, but also reports and splices the partial state (R6: a segment
     *  that failed validation must not hold every other, finished segment of the paragraph
     *  back in English). {@code resolver} is called exactly once per segment, same contract. */
    public static Composition composeDetailed(String original, Plan plan, SegmentResolver resolver) {
        List<Segment> segments = plan.segments();
        List<String> resolved = new ArrayList<>(segments.size());
        boolean anyMissing = false;
        int resolvedCount = 0;
        int remoteResolved = 0;
        int total = 0;
        for (Segment segment : segments) {
            String rawSegmentText = original.substring(segment.start(), segment.end());
            String value = resolver.resolve(segment, rawSegmentText);
            resolved.add(value);
            if (value == null) anyMissing = true;
            if (segment.kind() != Kind.INERT) {
                total++;
                if (value != null) {
                    resolvedCount++;
                    if (segment.kind() != Kind.RARITY) remoteResolved++;
                }
            }
        }
        String full = anyMissing ? null : splice(original, segments, resolved, false);
        String partial = anyMissing && remoteResolved > 0
                ? splice(original, segments, resolved, true) : null;
        return new Composition(full, partial, resolvedCount, total);
    }

    private static String splice(String original, List<Segment> segments, List<String> resolved,
                                 boolean rawForMissing) {
        StringBuilder out = new StringBuilder(original.length());
        int cursor = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            out.append(original, cursor, segment.start());
            String value = resolved.get(i);
            out.append(value != null || !rawForMissing
                    ? value : original.substring(segment.start(), segment.end()));
            cursor = segment.end();
        }
        out.append(original, cursor, original.length());
        return out.toString();
    }
}
