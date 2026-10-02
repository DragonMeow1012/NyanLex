package com.dragonmeow.nyanlex.hub.tool;

import com.dragonmeow.nyanlex.translate.PlayerNamePatterns;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a legacy AI-cache row that still carries a RAW (unmasked) player account name
 * — one a CURRENT {@link PlayerNamePatterns} frame recognises, but that an older release
 * of this mod wrote to disk before that frame (or {@code NameMasker}'s coverage of its
 * shape) existed — into the same masked shape the CURRENT mod would have produced, instead
 * of discarding the row outright (2026-10-01 user decision, superseding the original
 * "reject the whole row" rule {@link HubExportTool} shipped with).
 *
 * <p><b>Why not just call {@code NameMasker.mask()} again:</b> the row's KEY is already
 * PARTIALLY masked — it may already hold bare {@code ⟦n⟧} placeholders from whatever the
 * mod's masking recognised at capture time (TAB names, do-not-translate terms, older
 * frames). The original raw text is gone; only this partially-masked string survives.
 * Re-deriving the correct final numbering is still possible, though, because masking
 * never reorders text — it only substitutes spans in place — so the LEFT-TO-RIGHT order
 * of (a) the row's existing bare {@code ⟦n⟧} tokens and (b) the newly found raw name
 * span(s) is exactly the order a fresh mask of the true original text would visit every
 * protected span in. Renumbering 0..N-1 by that merged, position-sorted order of FIRST
 * appearance reconstructs precisely what {@code NameMasker.mask()} would have produced
 * (verified by {@code UnmaskedNameConverterTest}, which round-trips a synthetic
 * "legacy, frame-not-yet-recognised" key against a fresh {@code NameMasker.mask()} call
 * over the equivalent original text and asserts byte-for-byte equality).</p>
 *
 * <p>The VALUE side is converted by locating the exact (whole-token, case-sensitive)
 * original name text in the value and replacing it with the same new index — if that
 * exact text is not present (the AI reshaped/translated the proper noun), conversion
 * FAILS for the whole row: {@link #convert} returns a failure result and the row must be
 * rejected, per the user's explicit instruction. A hard backstop re-runs
 * {@link PlayerNamePatterns#nameSpans} on the converted key: if a raw name is still
 * recognised there (this should never happen when the algorithm is correct), conversion
 * also fails, so a correctness bug can never leak a real name into the published file.</p>
 */
final class UnmaskedNameConverter {

    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';
    private static final Pattern BARE_SLOT = Pattern.compile("⟦(\\d+)⟧");

    private UnmaskedNameConverter() {
    }

    /** @param ok      whether conversion succeeded
     *  @param key     the converted key (only meaningful when {@code ok})
     *  @param value   the converted value (only meaningful when {@code ok})
     *  @param reason  stable diagnostic tag (never shown to end users) explaining a
     *                 failure, or the no-op case where there was nothing to convert */
    record Result(boolean ok, String key, String value, String reason) {
        static Result ok(String key, String value) {
            return new Result(true, key, value, null);
        }

        static Result fail(String reason) {
            return new Result(false, null, null, reason);
        }
    }

    /** One slot in the key's left-to-right structure: either an existing bare
     *  {@code ⟦n⟧} placeholder (identity = its digit text, as written) or a raw name
     *  span {@link PlayerNamePatterns#nameSpans} found (identity = the exact substring). */
    private record Slot(int start, int end, boolean isName, String identity) {
    }

    /**
     * Attempt conversion. The caller is expected to have already confirmed
     * {@code !PlayerNamePatterns.nameSpans(key).isEmpty()}; calling this when there is
     * nothing to convert simply returns the row unchanged.
     */
    static Result convert(String key, String value) {
        List<int[]> nameSpans = PlayerNamePatterns.nameSpans(key);
        if (nameSpans.isEmpty()) return Result.ok(key, value);
        if (value == null) return Result.fail("no-value");

        List<Slot> slots = new ArrayList<>();
        Matcher bare = BARE_SLOT.matcher(key);
        while (bare.find()) {
            slots.add(new Slot(bare.start(), bare.end(), false, bare.group(1)));
        }
        for (int[] span : nameSpans) {
            slots.add(new Slot(span[0], span[1], true, key.substring(span[0], span[1])));
        }
        slots.sort(Comparator.comparingInt(Slot::start));
        for (int i = 1; i < slots.size(); i++) {
            if (slots.get(i).start() < slots.get(i - 1).end()) {
                // A bare-slot token and a name span should never be able to overlap
                // (nameSpans() never matches inside a ⟦...⟧ token's own digits), but
                // refuse rather than risk a corrupted rewrite if this ever happened.
                return Result.fail("slot-overlap-anomaly");
            }
        }

        Map<String, Integer> oldIndexToNew = new LinkedHashMap<>();
        Map<String, Integer> nameToNew = new LinkedHashMap<>();
        Map<String, Integer> newIndexBySlotIdentity = new LinkedHashMap<>();
        int next = 0;
        StringBuilder newKey = new StringBuilder(key.length() + 16);
        int pos = 0;
        for (Slot slot : slots) {
            newKey.append(key, pos, slot.start());
            String identityKey = (slot.isName() ? "N:" : "I:") + slot.identity();
            Integer idx = newIndexBySlotIdentity.get(identityKey);
            if (idx == null) {
                idx = next++;
                newIndexBySlotIdentity.put(identityKey, idx);
                if (slot.isName()) nameToNew.put(slot.identity(), idx);
                else oldIndexToNew.put(slot.identity(), idx);
            }
            newKey.append(OPEN).append(idx.intValue()).append(CLOSE);
            pos = slot.end();
        }
        newKey.append(key, pos, key.length());
        String convertedKey = newKey.toString();

        // ---- value: first remap every pre-existing bare slot using the SAME mapping ----
        String valueStep1 = oldIndexToNew.isEmpty() ? value
                : BARE_SLOT.matcher(value).replaceAll((MatchResult m) -> {
                    Integer mapped = oldIndexToNew.get(m.group(1));
                    return mapped == null ? Matcher.quoteReplacement(m.group())
                            : OPEN + Integer.toString(mapped) + CLOSE;
                });

        // ---- value: then substitute every raw name's exact (whole-token) text ----
        List<int[]> nameOccurrencesInValue = findWholeTokenOccurrences(valueStep1, nameToNew.keySet());
        Set<String> foundNames = new LinkedHashSet<>();
        for (int[] occ : nameOccurrencesInValue) foundNames.add(valueStep1.substring(occ[0], occ[1]));
        for (String name : nameToNew.keySet()) {
            if (!foundNames.contains(name)) {
                // The AI reshaped/translated the raw name differently than it appears in
                // the key (e.g. case change, transliteration): cannot safely locate it to
                // mask, so the whole row must be rejected per the user's instruction.
                return Result.fail("name-not-found-in-value");
            }
        }
        nameOccurrencesInValue.sort(Comparator.comparingInt(a -> a[0]));
        StringBuilder newValue = new StringBuilder(valueStep1.length() + 16);
        int vPos = 0;
        for (int[] occ : nameOccurrencesInValue) {
            newValue.append(valueStep1, vPos, occ[0]);
            int idx = nameToNew.get(valueStep1.substring(occ[0], occ[1]));
            newValue.append(OPEN).append(idx).append(CLOSE);
            vPos = occ[1];
        }
        newValue.append(valueStep1, vPos, valueStep1.length());
        String convertedValue = newValue.toString();

        // ---- hard backstop: the converted key must never still show a raw name ----
        if (!PlayerNamePatterns.nameSpans(convertedKey).isEmpty()) {
            return Result.fail("still-unmasked-after-conversion");
        }
        return Result.ok(convertedKey, convertedValue);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Every {@code [start, end)} span in {@code text} whose whole {@code isNameChar}
     *  token exactly equals one of {@code candidates}. {@code ⟦...⟧} protocol tokens are
     *  skipped as opaque (mirrors {@code NameMasker}'s own scanning), so a name candidate
     *  can never accidentally match digits/letters inside an MT/WS/PB/CS slot. */
    private static List<int[]> findWholeTokenOccurrences(String text, Set<String> candidates) {
        List<int[]> out = new ArrayList<>();
        if (candidates.isEmpty()) return out;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (isNameChar(c)) {
                int j = i + 1;
                while (j < n && isNameChar(text.charAt(j))) j++;
                String token = text.substring(i, j);
                if (candidates.contains(token)) out.add(new int[] {i, j});
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    private static int protocolTokenEnd(String text, int open) {
        for (int j = open + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == CLOSE) return j + 1;
            if (c == OPEN) return -1;
        }
        return -1;
    }
}
