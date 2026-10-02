package com.dragonmeow.nyanslate.hub;

/**
 * Progress callback for {@link HubDownloader#download}. Reported once per completed
 * file (file granularity: every hub file is small — at most a few MiB — so there is no
 * need to track partial-file byte progress).
 */
public interface HubDownloadProgressListener {
    void onProgress(long downloadedBytes, long totalBytes, String currentFileName,
                     int completedFiles, int totalFiles);
}
