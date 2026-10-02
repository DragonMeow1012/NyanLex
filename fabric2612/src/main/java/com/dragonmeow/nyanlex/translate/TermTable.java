package com.dragonmeow.nyanlex.translate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P1.7: rarity/type lookups for {@link RarityLineComposer}, merging the shipped
 * {@link TermTableDefaults} with the user's {@code TranslatorConfig.termOverrides}.
 * An override always wins over the shipped default for the same upper-case key.
 *
 * <p>Deliberately a single flat override map shared by both {@link #rarity} and
 * {@link #type}: the shipped rarity-word set and type-word set never collide (see
 * term-table-draft.md §5.4's dedup check), so one user-facing list is enough.</p>
 *
 * <p>Keys are trimmed and upper-cased here, independently of whether
 * {@code TranslatorConfig.normalized()} already ran — the same defensive-normalization
 * discipline {@link DoNotTranslateMatcher#compile} uses for its own config-derived list,
 * so an in-game settings screen that edits the live map directly (without a save/reload
 * round trip) still matches. Cheap to construct (a small map copy, no regex), so
 * callers may build a fresh instance per lookup instead of caching it.</p>
 */
public final class TermTable {

    private final Map<String, String> overrides;

    public TermTable(Map<String, String> overrides) {
        this.overrides = normalize(overrides);
    }

    private static Map<String, String> normalize(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().strip().toUpperCase(Locale.ROOT);
            String value = entry.getValue().strip();
            if (key.isEmpty() || value.isEmpty()) continue;
            out.put(key, value);
        }
        return out;
    }

    /** Chinese translation of a rarity word (e.g. {@code "EPIC"} -> {@code "史詩"}), or
     *  {@code null} if unknown (should never happen for the fixed 12-word rarity set
     *  {@link RarityLineComposer} extracts from). */
    public String rarity(String upperWord) {
        return lookup(upperWord);
    }

    /** Chinese translation of a type word/phrase (e.g. {@code "DUNGEON"},
     *  {@code "PET ITEM"}), or {@code null} when neither the user's overrides nor the
     *  shipped table has learned it yet. */
    public String type(String upperWord) {
        return lookup(upperWord);
    }

    /** Chinese translation of a reforge display word (e.g. {@code "Blended"} ->
     *  {@code "混合"}), matched case-insensitively; {@code null} for a word that is not a
     *  shipped reforge. A user override keyed by the upper-cased word wins, exactly like
     *  the rarity/type lookups — which also means an override of {@code EPIC},
     *  {@code LEGENDARY}, {@code MYTHIC} or {@code SHINY} deliberately applies to both
     *  the rarity/type word and the same-spelled reforge (the draft keeps them equal).
     *  Overrides never ADD reforge words: an unknown word is never guessed to be one. */
    public String reforge(String word) {
        String key = TermTableDefaults.reforgeKey(word);
        if (key == null) return null;
        String overridden = overrides.get(key.toUpperCase(Locale.ROOT));
        if (overridden != null) return overridden;
        return TermTableDefaults.REFORGE.get(key);
    }

    private String lookup(String upperWord) {
        if (upperWord == null) return null;
        String overridden = overrides.get(upperWord);
        if (overridden != null) return overridden;
        String defaultRarity = TermTableDefaults.RARITY.get(upperWord);
        if (defaultRarity != null) return defaultRarity;
        return TermTableDefaults.TYPE.get(upperWord);
    }
}
