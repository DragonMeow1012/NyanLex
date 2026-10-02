package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.translate.TextFilter;

/**
 * Row-level gates for hub-downloaded translations. A schema 2 repository file carries
 * no source text, so validation is split in two:
 *
 * <ul>
 *   <li>{@link #acceptsOnMerge}: source-free checks on the translation alone (bounded,
 *       non-empty, no URL/domain at all), applied when the file is merged;</li>
 *   <li>{@link #acceptsOnHit}: the full check — the same validation a local-cache
 *       export/import passes ({@link TranslationCache#usableForBulkTransfer}: protected
 *       token counts/shape, reshaped tokens, layout) plus "introduces no URL the source
 *       never had" — applied against the REAL local key at the moment of a lookup hit.</li>
 * </ul>
 *
 * <p>Security effect: nothing is ever shown unless it passes against the text the player
 * is actually looking at, which is stricter than the old merge-time check (that one ran
 * against the file's own claimed source). A row whose tokens do not fit the local key is
 * dropped and never displayed; a hostile row can at worst occupy space, bounded by the
 * per-file row/byte caps.</p>
 */
public final class HubImportValidator {
    private HubImportValidator() {
    }

    public static boolean acceptsOnMerge(String translated) {
        return HubFile.plausibleValue(translated);
    }

    public static boolean acceptsOnHit(String sourceKey, String translated) {
        return sourceKey != null && translated != null
                && TranslationCache.usableForBulkTransfer(sourceKey, translated)
                && !TextFilter.hasForeignUrl(sourceKey, translated);
    }
}
