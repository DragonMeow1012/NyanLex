package com.dragonmeow.nyanlex.hub;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background download/import worker. A loader keeps exactly ONE instance shared by
 * every hub screen: the progress screen can be closed and reopened while the SAME job
 * keeps running on its own executor thread, and pressing the download button again
 * while one is already in flight is a no-op ({@link #start} returns {@code false}) —
 * the glue should simply (re)open the progress screen on that return value instead of
 * starting a second job.
 */
public final class HubDownloadJob {
    public enum State { IDLE, PLANNING, DOWNLOADING, DONE, FAILED, CANCELLED }

    /** Fired on every state/progress change, on whichever thread caused it. */
    public interface Listener {
        void onHubDownloadJobChanged(HubDownloadJob job);
    }

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.IDLE;
    private volatile long downloadedBytes;
    private volatile long totalBytes;
    private volatile String currentFileName = "";
    private volatile int completedFiles;
    private volatile int totalFiles;
    private volatile HubDownloadResult result;
    private volatile String failureMessage;

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return running.get();
    }

    public State state() {
        return state;
    }

    public long downloadedBytes() {
        return downloadedBytes;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public String currentFileName() {
        return currentFileName;
    }

    public int completedFiles() {
        return completedFiles;
    }

    public int totalFiles() {
        return totalFiles;
    }

    public HubDownloadResult result() {
        return result;
    }

    public String failureMessage() {
        return failureMessage;
    }

    /** Start downloading {@code plan} on {@code executor}. Returns {@code false} (does
     *  nothing) when a job is already running. */
    public boolean start(HubPlan plan, HubDownloader downloader, HubLocalCache cache,
            HubDownloadState downloadState, Executor executor) {
        if (!running.compareAndSet(false, true)) return false;
        cancelRequested.set(false);
        totalBytes = plan.totalDownloadBytes();
        totalFiles = plan.downloadable().size();
        downloadedBytes = 0L;
        completedFiles = 0;
        currentFileName = "";
        result = null;
        failureMessage = null;
        state = State.DOWNLOADING;
        notifyListeners();
        executor.execute(() -> runDownload(plan, downloader, cache, downloadState));
        return true;
    }

    private void runDownload(HubPlan plan, HubDownloader downloader, HubLocalCache cache,
            HubDownloadState downloadState) {
        try {
            HubDownloadResult outcome = downloader.download(plan, cache, downloadState,
                    this::onProgress, cancelRequested::get);
            result = outcome;
            state = outcome.cancelled() ? State.CANCELLED : State.DONE;
        } catch (IOException | RuntimeException error) {
            failureMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            state = State.FAILED;
        } finally {
            running.set(false);
            notifyListeners();
        }
    }

    private void onProgress(long downloadedNow, long totalNow, String fileName,
            int completedNow, int totalFilesNow) {
        this.downloadedBytes = downloadedNow;
        this.totalBytes = totalNow;
        this.currentFileName = fileName;
        this.completedFiles = completedNow;
        this.totalFiles = totalFilesNow;
        notifyListeners();
    }

    /** Cancel the running job, if any. Already-merged files are kept. A no-op while
     *  idle or already finished. */
    public void cancel() {
        cancelRequested.set(true);
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try {
                listener.onHubDownloadJobChanged(this);
            } catch (RuntimeException ignored) {
                // A loader-side UI listener must never break the download worker.
            }
        }
    }
}
