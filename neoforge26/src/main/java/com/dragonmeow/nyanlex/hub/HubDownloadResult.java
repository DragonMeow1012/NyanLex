package com.dragonmeow.nyanlex.hub;

/** Result statistics of one {@link HubDownloader#download} run. */
public record HubDownloadResult(int added, int rejected, int alreadyPresent, boolean cancelled) {

    public static HubDownloadResult empty() {
        return new HubDownloadResult(0, 0, 0, false);
    }

    HubDownloadResult plus(HubLocalCache.MergeResult merge) {
        return new HubDownloadResult(added + merge.added(), rejected + merge.rejectedValidation(),
                alreadyPresent + merge.alreadyPresent(), cancelled);
    }

    HubDownloadResult asCancelled() {
        return new HubDownloadResult(added, rejected, alreadyPresent, true);
    }
}
