package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugErrorLogTest {
    private static final String KEY = "sk-live-ABCDEF1234567890secret";

    @TempDir
    Path dir;

    @BeforeEach
    void start() {
        // Drops any early report an earlier test class left waiting for a log.
        DebugErrorLog.install(null);
    }

    @AfterEach
    void uninstall() {
        DebugErrorLog.install(null);
    }

    private DebugErrorLog log(AtomicBoolean on, int max) {
        return new DebugErrorLog(file(), on::get, max, () -> List.of(KEY));
    }

    private Path file() {
        return dir.resolve("log").resolve("nyanlex-debug-log.jsonl");
    }

    @Test
    void offWritesNothingAndCreatesNothing() {
        AtomicBoolean on = new AtomicBoolean(false);
        DebugErrorLog log = log(on, 1000);
        DebugErrorLog.install(log);
        log.record(DebugErrorLog.HTTP_ERROR, "HTTP 500", Map.of("text", "x"));
        DebugErrorLog.report(DebugErrorLog.HOOK, "hook broke");
        log.exchangeSink().record("req", "resp", List.of("a"), null);
        log.awaitIdleForTest();
        assertFalse(Files.exists(file()));
        assertFalse(Files.exists(dir.resolve("log")));
    }

    @Test
    void onWithoutAnErrorWritesNothing() {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        // a good exchange is not an error
        log.exchangeSink().record("req", "resp", List.of("a"), List.of(new TranslationResult("b", null)));
        log.awaitIdleForTest();
        assertFalse(Files.exists(file()));
        assertFalse(Files.exists(dir.resolve("log")));
    }

    @Test
    void anErrorIsWrittenWithTimeTypeMessageAndContext() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        log.record(DebugErrorLog.TOKEN_LOST, "format/token lost", Map.of("text", "Hello ⟦CS0⟧"));
        log.awaitIdleForTest();
        List<String> lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"token_lost\""));
        assertTrue(lines.get(0).contains("\"message\":\"format/token lost\""));
        assertTrue(lines.get(0).contains("\"time\":\""));
        assertTrue(lines.get(0).contains("Hello"));
    }

    @Test
    void aFailedExchangeIsLoggedWithRequestAndResponseSummary() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        log.exchangeSink().record("{\"req\":1}", "{\"resp\":2}", List.of("a"),
                List.of(new TranslationResult("a", null, false, "format/token lost")));
        log.awaitIdleForTest();
        String text = Files.readString(file());
        assertTrue(text.contains("token_lost"));
        assertTrue(text.contains("req"));
        assertTrue(text.contains("resp"));
    }

    @Test
    void keepsOnlyTheNewestEntriesAndDropsTheOldest() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        for (int i = 0; i < 1005; i++) log.record(DebugErrorLog.EXCEPTION, "error number " + i, null);
        log.awaitIdleForTest();
        List<String> lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
        assertEquals(1000, lines.size());
        assertTrue(lines.get(0).contains("error number 5\""), lines.get(0));
        assertTrue(lines.get(999).contains("error number 1004\""));
        assertFalse(Files.readString(file()).contains("error number 4\""));
    }

    @Test
    void theLimitAlsoHoldsAcrossRestarts() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog first = log(on, 3);
        for (int i = 0; i < 3; i++) first.record(DebugErrorLog.EXCEPTION, "old " + i, null);
        first.awaitIdleForTest();
        DebugErrorLog second = log(on, 3);
        second.record(DebugErrorLog.EXCEPTION, "new", null);
        second.awaitIdleForTest();
        List<String> lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("old 1"));
        assertTrue(lines.get(2).contains("new"));
    }

    @Test
    void noKeyEverReachesTheFile() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        log.record(DebugErrorLog.HTTP_ERROR, "HTTP 401 for key " + KEY, Map.of(
                "request", "POST https://x/v1?key=AIzaSyDUMMYDUMMYDUMMYDUMMY1234 Authorization: Bearer abcdef0123456789",
                "response", "{\"error\":\"bad key " + KEY + "\"}",
                "other", "api_key=\"plainsecretvalue\""));
        log.exchangeSink().record("{\"k\":\"" + KEY + "\"}", "resp " + KEY, List.of("a"), null);
        log.awaitIdleForTest();
        String text = Files.readString(file());
        assertFalse(text.contains(KEY));
        assertFalse(text.contains("ABCDEF1234567890"));
        assertFalse(text.contains("AIzaSyDUMMY"));
        assertFalse(text.contains("abcdef0123456789"));
        assertFalse(text.contains("plainsecretvalue"));
        assertTrue(text.contains("***"));
    }

    @Test
    void recordingDoesNotWaitForTheDisk() {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) log.record(DebugErrorLog.EXCEPTION, "e" + i, Map.of("text", "t".repeat(500)));
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(millis < 1000, "queueing 200 errors took " + millis + " ms");
        log.awaitIdleForTest();
    }

    @Test
    void reasonsMapToTheRightTypes() {
        assertEquals(DebugErrorLog.TOKEN_LOST, DebugErrorLog.typeForReason("format/token lost"));
        assertEquals(DebugErrorLog.TOKEN_LOST, DebugErrorLog.typeForReason("anchor/order damaged"));
        assertEquals(DebugErrorLog.HTTP_ERROR, DebugErrorLog.typeForReason("429 rate limit"));
        assertEquals(DebugErrorLog.HTTP_ERROR, DebugErrorLog.typeForReason("HTTP 5xx"));
        assertEquals(DebugErrorLog.NETWORK, DebugErrorLog.typeForReason("timeout/network"));
        assertEquals(DebugErrorLog.TRANSLATION_FAILED, DebugErrorLog.typeForReason("unknown"));
    }

    @Test
    void aFailedRequestInTheOverlayTraceReachesTheLogOnlyWhileOn() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        DebugErrorLog.install(log);
        TranslationDebugLog trace = new TranslationDebugLog(on::get);
        long id = trace.submitted("AI", List.of("Sword"));
        trace.completed(id, TranslationDebugLog.failureFor(new RuntimeException("HTTP 500 boom")));
        log.awaitIdleForTest();
        assertTrue(Files.readString(file()).contains("http_error"));

        Files.delete(file());
        on.set(false);
        long off = trace.submitted("AI", List.of("Shield"));
        assertEquals(0L, off);
        trace.completed(off, TranslationDebugLog.Status.FAILED);
        log.awaitIdleForTest();
        assertFalse(Files.exists(file()));
    }

    @Test
    void aReportMadeBeforeTheLogExistsIsRecordedWhenItIsInstalled() throws IOException {
        DebugErrorLog.reportEarly(DebugErrorLog.MIGRATION, "legacy cache merge skipped", "file", "nyanslate-ai-cache-zh-tw.json");
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);

        DebugErrorLog.install(log);
        log.awaitIdleForTest();

        String text = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"type\":\"migration\""), text);
        assertTrue(text.contains("legacy cache merge skipped"), text);
        assertTrue(text.contains("nyanslate-ai-cache-zh-tw.json"), text);
    }

    @Test
    void anEarlyReportIsKeptOnlyUntilTheNextInstall() throws IOException {
        DebugErrorLog.reportEarly(DebugErrorLog.MIGRATION, "first", "file", "a");
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog first = log(on, 1000);
        DebugErrorLog.install(first);
        first.awaitIdleForTest();
        DebugErrorLog.install(null);

        DebugErrorLog second = new DebugErrorLog(dir.resolve("second.jsonl"), on::get, 1000, List::of);
        DebugErrorLog.install(second);
        second.awaitIdleForTest();
        assertFalse(Files.exists(dir.resolve("second.jsonl")), "an early report is replayed once, not on every install");
    }

    @Test
    void aModelThatEchoesTheSourceIsNotAnError() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        DebugErrorLog.install(log);
        TranslationDebugLog debug = new TranslationDebugLog(on::get);

        long echoed = debug.submitted("AI", List.of("C418 - cat", "Hyperion"));
        debug.completed(echoed, new TranslationDebugLog.Failure(TranslationDebugLog.Status.FAILED,
                DebugErrorLog.UNCHANGED_ECHO_REASON));
        log.awaitIdleForTest();
        assertFalse(Files.exists(file()), "unchanged (echo) is the right answer for a name, so it is not logged");

        long broken = debug.submitted("AI", List.of("Hello"));
        debug.completed(broken, new TranslationDebugLog.Failure(TranslationDebugLog.Status.FAILED, "format/token lost"));
        log.awaitIdleForTest();
        List<String> lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("format/token lost"));
    }

    @Test
    void anEchoedUnitInAnExchangeIsNotLoggedEither() throws IOException {
        AtomicBoolean on = new AtomicBoolean(true);
        DebugErrorLog log = log(on, 1000);
        log.exchangeSink().record("req", "resp", List.of("TNT"),
                List.of(new TranslationResult("TNT", null, false, DebugErrorLog.UNCHANGED_ECHO_REASON)));
        log.awaitIdleForTest();
        assertFalse(Files.exists(file()));
    }
}
