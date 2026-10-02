package com.dragonmeow.nyanslate.hub;

/**
 * One item of a {@link HubDownloader#plan} result: a server, modpack or mod the
 * repository may (or may not) have a translation file for, in the CURRENT target
 * language only.
 *
 * @param hasContent whether the repository's index lists a file for this source/language
 * @param rows       row count reported by the index (0 when {@code !hasContent})
 * @param bytes      byte size reported by the index (0 when {@code !hasContent})
 * @param upToDate   whether the locally recorded sha256 already matches the index
 * @param sha256     the index's content hash for this source/language, or {@code null}
 */
public record HubPlanItem(HubSource source, String label, boolean hasContent,
                           int rows, long bytes, boolean upToDate, String sha256) {
}
