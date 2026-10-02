package com.dragonmeow.nyanslate.hub;

import com.dragonmeow.nyanslate.translate.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses a synchronous ({@code Runnable::run}) executor throughout, so every assertion
 *  observes the job's state deterministically right after {@link HubDownloadJob#start}
 *  returns -- no sleeps or polling needed. */
class HubDownloadJobTest {

    private static final String BASE = "https://example.com/hub";
    private static final String INDEX_URL = BASE + "/index.json";

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("nyanslate-hub-job-test");
    }

    private static String translationFileJson(String key, String value) {
        return "{\"schema\":1,\"format\":\"modern-template-v1\",\"language\":\"zh-tw\","
                + "\"provider\":\"none\",\"machine\":{},\"ai\":{\"" + key + "\":\"" + value + "\"}}";
    }

    private static HttpTransport fakeTransport(Map<String, String> responses) {
        return url -> {
            String body = responses.get(url);
            if (body == null) throw new IOException("404: " + url);
            return body;
        };
    }

    private static HubPlan threeModPlan() {
        List<HubPlanItem> items = new ArrayList<>();
        items.add(new HubPlanItem(HubSource.mod("mod-a"), "mod-a", true, 1, 10, false, "sha-a"));
        items.add(new HubPlanItem(HubSource.mod("mod-b"), "mod-b", true, 1, 20, false, "sha-b"));
        items.add(new HubPlanItem(HubSource.mod("mod-c"), "mod-c", true, 1, 30, false, "sha-c"));
        return new HubPlan(items);
    }

    @Test
    void startReturnsFalseWhileAlreadyRunning() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(BASE + "/mods/mod-a/zh-tw.json", translationFileJson("A", "甲"));
        responses.put(BASE + "/mods/mod-b/zh-tw.json", translationFileJson("B", "乙"));
        responses.put(BASE + "/mods/mod-c/zh-tw.json", translationFileJson("C", "丙"));
        HubDownloader downloader = new HubDownloader(new HubRepository(fakeTransport(responses), BASE));
        Path dir = tempDir();
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubDownloadJob job = new HubDownloadJob();

        AtomicInteger reentrantStartResult = new AtomicInteger(-1);
        job.addListener(j -> {
            if (j.state() == HubDownloadJob.State.DOWNLOADING && reentrantStartResult.get() == -1) {
                // Called synchronously from inside the still-running download: start()
                // must report "already running" rather than launching a second job.
                boolean secondStart = j.start(threeModPlan(), downloader, cache, state, Runnable::run);
                reentrantStartResult.set(secondStart ? 1 : 0);
            }
        });

        boolean started = job.start(threeModPlan(), downloader, cache, state, Runnable::run);

        assertTrue(started);
        assertEquals(0, reentrantStartResult.get(), "a second start() while running must return false");
        assertEquals(HubDownloadJob.State.DONE, job.state());
    }

    @Test
    void progressAdvancesAsEachFileCompletes() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(BASE + "/mods/mod-a/zh-tw.json", translationFileJson("A", "甲"));
        responses.put(BASE + "/mods/mod-b/zh-tw.json", translationFileJson("B", "乙"));
        responses.put(BASE + "/mods/mod-c/zh-tw.json", translationFileJson("C", "丙"));
        HubDownloader downloader = new HubDownloader(new HubRepository(fakeTransport(responses), BASE));
        Path dir = tempDir();
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubDownloadJob job = new HubDownloadJob();
        List<Integer> completedSnapshots = new ArrayList<>();
        job.addListener(j -> completedSnapshots.add(j.completedFiles()));

        job.start(threeModPlan(), downloader, cache, state, Runnable::run);

        assertEquals(HubDownloadJob.State.DONE, job.state());
        assertEquals(3, job.completedFiles());
        assertEquals(3, job.totalFiles());
        assertEquals(60, job.totalBytes());
        assertEquals(60, job.downloadedBytes());
        assertEquals(3, job.result().added());
        assertTrue(completedSnapshots.contains(1));
        assertTrue(completedSnapshots.contains(2));
        assertTrue(completedSnapshots.contains(3));
        assertFalse(job.isRunning());
    }

    @Test
    void cancelStopsBeforeTheNextFileAndKeepsCompletedOnesMerged() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(BASE + "/mods/mod-a/zh-tw.json", translationFileJson("A", "甲"));
        responses.put(BASE + "/mods/mod-b/zh-tw.json", translationFileJson("B", "乙"));
        responses.put(BASE + "/mods/mod-c/zh-tw.json", translationFileJson("C", "丙"));
        HubDownloader downloader = new HubDownloader(new HubRepository(fakeTransport(responses), BASE));
        Path dir = tempDir();
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubDownloadJob job = new HubDownloadJob();
        job.addListener(j -> {
            if (j.completedFiles() == 1) j.cancel();
        });

        job.start(threeModPlan(), downloader, cache, state, Runnable::run);

        assertEquals(HubDownloadJob.State.CANCELLED, job.state());
        assertTrue(job.result().cancelled());
        assertEquals(1, job.result().added(), "only the first file was merged before cancellation");
        assertEquals("甲", cache.get("A"));
        assertEquals(null, cache.get("B"), "the second file must never have been fetched/merged");
    }

    @Test
    void failureIsReportedWithoutThrowing() throws IOException {
        HttpTransport allFail = url -> {
            throw new IOException("boom");
        };
        HubDownloader downloader = new HubDownloader(new HubRepository(allFail, BASE));
        Path dir = tempDir();
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubDownloadJob job = new HubDownloadJob();

        job.start(threeModPlan(), downloader, cache, state, Runnable::run);

        assertEquals(HubDownloadJob.State.FAILED, job.state());
        assertFalse(job.isRunning());
    }
}
