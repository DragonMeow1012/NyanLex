package com.dragonmeow.nyanslate.hub;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.translate.TextFilter;

/**
 * Row-level gate applied to every hub-downloaded translation before it enters
 * {@link HubLocalCache}: the same validation an export/import between two local
 * caches already passes ({@link TranslationCache#usableForBulkTransfer}), plus a
 * check that forbids a translated row from introducing a URL/domain the source text
 * never had.
 */
public final class HubImportValidator {
    private HubImportValidator() {
    }

    public static boolean accepts(String source, String translated) {
        return TranslationCache.usableForBulkTransfer(source, translated)
                && !TextFilter.hasForeignUrl(source, translated);
    }
}
