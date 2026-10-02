package com.dragonmeow.nyanslate.translate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers the exact {@code ⟦...⟧} protocol-token numbering a STANDALONE request for one
 * row alone would have produced, from a key/value pair that was instead extracted out of a
 * LARGER multi-row paragraph.
 *
 * <p>Every numbered token kind this mod uses ({@code ⟦n⟧} bare player-name/do-not-translate
 * P-slots, {@code ⟦MTn⟧} {@link TemplateText}/{@link TranslationTemplate} value slots,
 * {@code ⟦WSn⟧} layout-gap slots, {@code ⟦PBn⟧} row breaks (2026-10-02), {@code ⟦CSn⟧}/{@code ⟦/CSn⟧} colour-run markers) is
 * numbered sequentially from 0, in first-appearance order, <em>within whatever single call
 * actually produced the text</em> — {@link NameMasker#mask}, {@link
 * TranslationTemplate#prepare}, and the glue's own colour-run marking all number this way.
 * A row glued into a larger paragraph (via {@code ⟦PBn⟧}, see {@link
 * ParagraphModel#joinParagraph}) therefore usually does NOT carry the same token numbers a
 * request for that row BY ITSELF would have produced — a row is only the GLOBAL first
 * occurrence of every one of its kinds when it happens to be the paragraph's first row that
 * uses them.
 *
 * <p>This matters for lazily converting an already-cached WHOLE-paragraph translation into
 * independent, reusable row-level cache rows (design-segment-cache.md §3/§4): the row's own
 * fresh, standalone key ({@code "Buy it now: ⟦MT0⟧ coins"}) will almost certainly NOT equal
 * its substring as it appears inside the whole paragraph's key ({@code "Buy it now:
 * ⟦MT3⟧ coins"}, say) — naively pairing the extracted value substring with the freshly
 * recomputed standalone key would then fail every shape validation (a real safety net, not
 * a bug — see {@link com.dragonmeow.nyanslate.cache.TranslationCache#usableForBulkTransfer}),
 * silently discarding a row that COULD have converted correctly. {@link #renumber} fixes
 * this: it renumbers every token kind independently (bare slots, {@code MT}, {@code WS},
 * {@code CS} each have their own 0,1,2… sequence) to the LOCAL numbering {@code keyRow}
 * alone would produce, then applies that EXACT SAME old→new mapping to {@code valueRow}'s
 * matching tokens — recovering precisely the key/value pair a standalone request for this
 * one row would have cached.</p>
 */
public final class LocalTokenRenumberer {

    private static final Pattern TOKEN = Pattern.compile(
            "⟦\\s*(/?)\\s*(MT|WS|CS|PB)?\\s*(\\d+)\\s*⟧");

    private LocalTokenRenumberer() {
    }

    public record Renumbered(String key, String value) {
    }

    /** {@code text} with every token kind renumbered to a fresh local 0,1,2,… sequence
     *  (first-appearance order, same per-kind independence as {@link #renumber}), plus the
     *  new→old index mapping needed to invert that exact renumbering later — see {@link
     *  #localize}/{@link #restore}. */
    public record Localized(String text, Map<String, String> newToOld) {
    }

    /**
     * Renumbers every {@code ⟦...⟧} token in {@code text} to the LOCAL numbering a
     * standalone request for {@code text} alone would produce — the same transformation
     * {@link #renumber}'s key side applies, exposed here for a single string instead of a
     * key/value pair. Used so a cache/request KEY derived from one row carved out of a
     * larger, globally-numbered paragraph (see {@code TooltipSegmentPlanner}) no longer
     * depends on how many OTHER style runs/value slots happened to precede that row in
     * whichever paragraph it was glued into: the exact same semantic row — a {@code
     * Seller:}/{@code Buy it now:} field, a stat line, … — shares one cache entry
     * regardless of its position in any particular item's tooltip. {@code null}-safe
     * ({@code null} in, {@code null} out); a text with no tokens at all comes back
     * unchanged with an empty mapping (a harmless no-op, so every existing caller that
     * already passes plain token-free text — an enchant/scroll name, a rarity type word —
     * is completely unaffected).
     */
    public static Localized localize(String text) {
        if (text == null) return null;
        Map<String, String> oldToNew = new HashMap<>();
        Map<String, Integer> nextByKind = new HashMap<>();
        Map<String, String> newToOld = new LinkedHashMap<>();
        String localized = rewriteLocalize(text, oldToNew, nextByKind, newToOld);
        return new Localized(localized, newToOld);
    }

    /**
     * Inverts {@link #localize}: rewrites every token in {@code localizedResult} (a
     * translated value whose tokens are still numbered in the LOCAL scheme {@link
     * #localize} produced for the request that was actually sent) back to the exact
     * GLOBAL index {@code newToOld} recorded for that {@code (kind, local index)} pair, so
     * splicing the result back into the full paragraph lines up with that paragraph's own
     * style-run table. A {@code (kind, local index)} pair absent from {@code newToOld}
     * (only possible if the translator introduced a token index that was never part of
     * the localized request — a structural failure the existing shape validation already
     * rejects before this is ever reached) is left completely unchanged, never guessed.
     * {@code null}-safe; an empty/{@code null} mapping is a no-op passthrough.
     */
    public static String restore(String localizedResult, Map<String, String> newToOld) {
        if (localizedResult == null) return null;
        if (newToOld == null || newToOld.isEmpty()) return localizedResult;
        Matcher matcher = TOKEN.matcher(localizedResult);
        StringBuilder out = new StringBuilder(localizedResult.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(localizedResult, cursor, matcher.start());
            String slash = matcher.group(1);
            String kind = matcher.group(2) == null ? "" : matcher.group(2);
            String localIndex = matcher.group(3);
            String oldIndex = newToOld.get(kind + ':' + localIndex);
            out.append('⟦').append(slash).append(kind)
                    .append(oldIndex != null ? oldIndex : localIndex).append('⟧');
            cursor = matcher.end();
        }
        out.append(localizedResult, cursor, localizedResult.length());
        return out.toString();
    }

    private static String rewriteLocalize(String text, Map<String, String> oldToNew,
                                          Map<String, Integer> nextByKind,
                                          Map<String, String> newToOld) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(text, cursor, matcher.start());
            String slash = matcher.group(1);
            String kind = matcher.group(2) == null ? "" : matcher.group(2);
            String oldIndex = matcher.group(3);
            String oldId = kind + ':' + oldIndex;
            String newIndex = oldToNew.get(oldId);
            if (newIndex == null) {
                int next = nextByKind.merge(kind, 1, Integer::sum) - 1;
                newIndex = String.valueOf(next);
                oldToNew.put(oldId, newIndex);
                newToOld.put(kind + ':' + newIndex, oldIndex);
            }
            out.append('⟦').append(slash).append(kind).append(newIndex).append('⟧');
            cursor = matcher.end();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /**
     * {@code null} when {@code keyRow}/{@code valueRow} is {@code null}, or when
     * {@code valueRow} references a {@code (kind, index)} pair that never occurs anywhere
     * in {@code keyRow} — a token the row-level boundary does not actually own (its real
     * scope is some OTHER row of the original paragraph), so renumbering it would be a
     * guess, not a recovery; the caller should treat the row as unconvertible, never write
     * a guessed mapping.
     */
    public static Renumbered renumber(String keyRow, String valueRow) {
        if (keyRow == null || valueRow == null) return null;
        Map<String, String> assigned = new LinkedHashMap<>();
        Map<String, Integer> nextByKind = new HashMap<>();
        String newKey = rewrite(keyRow, assigned, nextByKind, true);
        if (newKey == null) return null;
        String newValue = rewrite(valueRow, assigned, nextByKind, false);
        if (newValue == null) return null;
        return new Renumbered(newKey, newValue);
    }

    private static String rewrite(String text, Map<String, String> assigned,
                                  Map<String, Integer> nextByKind, boolean allowNew) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(text, cursor, matcher.start());
            String slash = matcher.group(1);
            String kind = matcher.group(2) == null ? "" : matcher.group(2);
            String oldIndex = matcher.group(3);
            String id = kind + ':' + oldIndex;
            String newIndex = assigned.get(id);
            if (newIndex == null) {
                if (!allowNew) return null;
                int next = nextByKind.merge(kind, 1, Integer::sum) - 1;
                newIndex = String.valueOf(next);
                assigned.put(id, newIndex);
            }
            out.append('⟦').append(slash).append(kind).append(newIndex).append('⟧');
            cursor = matcher.end();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }
}
