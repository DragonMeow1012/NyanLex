package com.dragonmeow.nyanlex.hub;

/**
 * Removes everything that came from translation packs, leaving the player's own translations
 * (kept in the main cache, not in {@link HubLocalCache}) untouched.
 *
 * <p>The download state is forgotten too: otherwise the next "detect and download" would see a
 * matching checksum and skip files whose rows are gone.</p>
 */
public final class HubPackCleaner {
    private HubPackCleaner() { }

    /** Number of downloaded translations that a {@link #clear} would remove. */
    public static int downloadedCount(HubLocalCache cache) {
        return cache == null ? 0 : cache.size();
    }

    /** Clears the downloaded translations of the active language; returns how many were removed. */
    public static int clear(HubLocalCache cache, HubDownloadState state) {
        if (cache == null) return 0;
        int removed = cache.size();
        String language = cache.language();
        cache.clearAll();
        if (state != null && language != null) state.forgetLanguage(language);
        return removed;
    }
}
