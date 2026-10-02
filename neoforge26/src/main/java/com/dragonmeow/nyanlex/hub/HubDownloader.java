package com.dragonmeow.nyanlex.hub;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Two-step download flow driven entirely by the glue's "confirm" screen: {@link #plan}
 * fetches only {@code index.json} (one small file) and reports what is available and
 * its size; {@link #download} then fetches exactly the items the plan says need it, in
 * priority order, merging each into {@link HubLocalCache} and reporting progress after
 * every file. No automatic/background download ever happens on its own — every call
 * here is explicitly triggered by the loader (a button press, or the once-per-launch
 * startup mod check).
 */
public final class HubDownloader {
    private final HubRepository repository;

    public HubDownloader(HubRepository repository) {
        this.repository = repository;
    }

    /**
     * @param serverHost   normalized server host ({@link ServerHostNormalizer}), or
     *                     {@code null} for a singleplayer/LAN/Realms session
     * @param modpack      detected modpack identity, or {@code null}
     * @param loadedModIds every currently loaded mod id; EVERY one gets a
     *                     {@link HubPlanItem} (see {@link #planFromIndex}), not only the
     *                     ones the index happens to list
     */
    public HubPlan plan(String serverHost, ModpackIdentity modpack, List<String> loadedModIds,
            String language, HubDownloadState state) throws IOException {
        HubIndex index = repository.fetchIndex();
        return planFromIndex(index, serverHost, modpack, loadedModIds, language, state);
    }

    /**
     * Startup-only plan: mods, plus a modpack when one was detected from local instance
     * files (title-screen startup has no server yet, but CAN already read
     * launcher instance metadata — see
     * {@code design-hub-download.md} "啟動提示" 2026-10-01, "模組包在啟動時也可識別").
     * Returns an empty plan WITHOUT any network call when {@code disabled} is
     * {@code true} (the caller opted out of the check).
     */
    public HubPlan planStartupMods(boolean disabled, ModpackIdentity modpack, List<String> loadedModIds,
            String language, HubDownloadState state) throws IOException {
        if (disabled) return HubPlan.empty();
        HubIndex index = repository.fetchIndex();
        return planFromIndex(index, null, modpack, loadedModIds, language, state);
    }

    /**
     * Every installed mod gets ITS OWN item regardless of whether the repository index
     * has ever heard of it (2026-10-01 user instruction: the confirmation/startup
     * screens must show every installed mod's status — "有翻譯可下載"/"已是最新"/
     * "倉庫沒有" — never silently omit an unknown one). {@link HubPlanItem#hasContent()}
     * is {@code false} for a mod the index does not list; {@link HubPlan#downloadable()}
     * and {@link HubPlan#totalDownloadBytes()} already skip every {@code !hasContent()}
     * item, so this never inflates a download or its reported total size.
     */
    private HubPlan planFromIndex(HubIndex index, String serverHost, ModpackIdentity modpack,
            List<String> loadedModIds, String language, HubDownloadState state) {
        List<HubPlanItem> items = new ArrayList<>();
        if (serverHost != null) {
            addItem(items, HubSource.server(serverHost), serverHost, language, state,
                    index.server(serverHost, language));
        }
        if (modpack != null) {
            String label = modpack.displayName() != null ? modpack.displayName() : modpack.slug();
            addItem(items, HubSource.modpack(modpack.slug()), label, language, state,
                    index.modpack(modpack.slug(), language));
        }
        if (loadedModIds != null) {
            for (String modId : loadedModIds) {
                if (modId == null) continue;
                addItem(items, HubSource.mod(modId), modId, language, state, index.mod(modId, language));
            }
        }
        return new HubPlan(items);
    }

    private void addItem(List<HubPlanItem> items, HubSource source, String label, String language,
            HubDownloadState state, HubIndex.LanguageStats stats) {
        boolean hasContent = stats != null;
        int rows = hasContent ? stats.rows() : 0;
        long bytes = hasContent ? stats.bytes() : 0L;
        String sha256 = hasContent ? stats.sha256() : null;
        boolean upToDate = hasContent && sha256 != null && sha256.equals(state.sha256(source, language));
        items.add(new HubPlanItem(source, label, hasContent, rows, bytes, upToDate, sha256));
    }

    /**
     * Fetch and merge every item {@link HubPlan#downloadable()} lists, in order.
     * Cancellation is checked between files (not mid-file: every hub file is small and
     * fetched as one GET); files already merged before a cancellation/failure are kept.
     */
    public HubDownloadResult download(HubPlan plan, HubLocalCache cache, HubDownloadState state,
            HubDownloadProgressListener listener, BooleanSupplier cancelled) throws IOException {
        List<HubPlanItem> downloadable = plan.downloadable();
        long totalBytes = plan.totalDownloadBytes();
        long downloadedBytes = 0L;
        HubDownloadResult result = HubDownloadResult.empty();
        int total = downloadable.size();
        int completed = 0;
        // A4: lock the language once, at the moment the job starts — not re-read from
        // cache.language() per item. The target language can change (via the settings
        // screen) while this background download is still running; HubLocalCache#setLanguage
        // would otherwise make later iterations fetch/record against the NEW language while
        // `plan`'s items (rows/bytes/sha256) were computed for the OLD one, corrupting that
        // new language's sha256 throttling ledger with a stale/mismatched value.
        String language = cache.language();
        for (HubPlanItem item : downloadable) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return result.asCancelled();
            }
            HubFile file = fetch(item.source(), language);
            if (file != null) {
                // If the player switched languages mid-download, cache's active language no
                // longer matches `language`; mergeFromFile already rejects a language mismatch
                // (0 rows, see HubLocalCache#mergeFromFile), so this is a safe no-op rather than
                // writing into the wrong language's rows.
                HubLocalCache.MergeResult merge = cache.mergeFromFile(file, item.source());
                result = result.plus(merge);
                state.record(item.source(), language, item.sha256());
            }
            downloadedBytes += item.bytes();
            completed++;
            if (listener != null) {
                listener.onProgress(downloadedBytes, totalBytes, item.label(), completed, total);
            }
        }
        return result;
    }

    private HubFile fetch(HubSource source, String language) throws IOException {
        switch (source.kind()) {
            case SERVER:
                return repository.fetchServerFile(source.identifier(), language);
            case MODPACK:
                return repository.fetchModpackFile(source.identifier(), language);
            default:
                return repository.fetchModFile(source.identifier(), language);
        }
    }
}
