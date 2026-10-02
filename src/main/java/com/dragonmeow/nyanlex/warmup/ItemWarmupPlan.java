package com.dragonmeow.nyanlex.warmup;

/**
 * Result of the dry-run scan shown on the confirmation screen.
 *
 * @param totalItems        items scanned
 * @param cachedItems       items whose every unit is already cached
 * @param missingItems      items with at least one missing unit
 * @param missingUnits      distinct missing units (units shared by many items count once)
 * @param missingChars      characters of those distinct units
 * @param willSubmitItems   items this launch will submit (missing, capped by the per-launch limit if one is set)
 * @param estimatedRequests estimated API requests
 * @param estimatedTokens   estimated total tokens (input + output)
 * @param calibrated        the token figure was scaled by measured session usage
 * @param nativeItems       items that need no translation (already in the target language)
 * @param estimatedMinutes  rough run time in minutes (assumed request latency, see the estimator)
 */
public record ItemWarmupPlan(int totalItems, int cachedItems, int missingItems,
                             int missingUnits, long missingChars, int willSubmitItems,
                             int estimatedRequests, long estimatedTokens, boolean calibrated,
                             int nativeItems, int estimatedMinutes) {
    public boolean nothingToDo() {
        return willSubmitItems <= 0;
    }
}
