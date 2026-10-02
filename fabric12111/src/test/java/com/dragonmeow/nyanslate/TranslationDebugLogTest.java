package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.TranslationDebugLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationDebugLogTest {

    @Test
    void compactOverlayTextHidesProtocolNoiseButKeepsSlotMeaning() {
        String raw = "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧++] RummelDumm⟦/CS1⟧ "
                + "joined with ⟦MT0⟧ coins for ⟦2⟧";

        assertEquals("[MVP++] RummelDumm joined with {值} coins for {玩家}",
                TranslationDebugLog.compactText(raw));
    }

    @Test
    void compactOverlayTextFlattensLinesAndLegacyFormatting() {
        assertEquals("原文 → 翻譯", TranslationDebugLog.compactText("§c原文\n  →   §a翻譯"));
    }

    @Test
    void classifiesRateLimitsAcrossTheExceptionChain() {
        RuntimeException wrapped429 = new RuntimeException("provider failed",
                new IOException("HTTP 429: quota exceeded"));
        assertEquals(TranslationDebugLog.Status.RATE_LIMITED,
                TranslationDebugLog.statusFor(wrapped429));
        assertEquals(TranslationDebugLog.Status.RATE_LIMITED,
                TranslationDebugLog.statusFor(new RuntimeException("AI rate-limited: backing off")));
        assertEquals(TranslationDebugLog.Status.FAILED,
                TranslationDebugLog.statusFor(new RuntimeException("empty response body")));
        assertEquals("429 rate limit", TranslationDebugLog.failureFor(wrapped429).reason());
    }

    @Test
    void classifiesStableFailureReasonsAcrossWrappedExceptions() {
        assertFailure("HTTP 5xx", new IOException("HTTP 503: overloaded"));
        assertFailure("authentication", new RuntimeException("HTTP 401 invalid API key"));
        assertFailure("timeout/network", new RuntimeException("wrapper",
                new java.net.SocketTimeoutException("read timed out")));
        assertFailure("anchor/order damaged", new RuntimeException("anchor order damaged"));
        assertFailure("paragraph lost", new RuntimeException("paragraph break lost"));
        assertFailure("format/token lost", new RuntimeException("format token lost"));
        assertFailure("empty response", new RuntimeException("empty response body"));
        assertFailure("unknown", new RuntimeException("provider rejected request"));
    }

    @Test
    void completedBatchRetainsPerItemFailureReasons() {
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        long id = debug.submitted("AI", java.util.List.of("one", "two"));
        debug.completed(id, java.util.List.of(),
                java.util.List.of(TranslationDebugLog.Status.FAILED,
                        TranslationDebugLog.Status.RATE_LIMITED),
                java.util.List.of("paragraph lost", "429 rate limit"));

        java.util.List<TranslationDebugLog.Entry> entries = debug.snapshot(10);
        assertEquals("429 rate limit", entries.get(0).failureReason());
        assertEquals("paragraph lost", entries.get(1).failureReason());
    }

    @Test
    void discardRemovesAnUnsentRequestWithoutMarkingItFailed() {
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        long unsent = debug.submitted("GT", java.util.List.of("one", "two"));
        long sent = debug.submitted("GT", java.util.List.of("three"));
        debug.completed(sent, true);

        debug.discard(unsent);
        debug.discard(0L); // disabled-log id: no-op

        java.util.List<TranslationDebugLog.Entry> entries = debug.snapshot(10);
        assertEquals(1, entries.size(), "no permanent waiting row is left behind");
        assertEquals(sent, entries.get(0).requestId());
        assertEquals(TranslationDebugLog.Status.SUCCESS, entries.get(0).status());
    }

    @Test
    void staleInFlightRowEventuallyResolvesInsteadOfWaitingForever() {
        // 2026-10 fix: a requestId that never receives a matching completed()/discard()
        // call (a stuck transport call below its own HTTP timeout, a dropped callback, a
        // future code path this trace could not anticipate) used to stay IN_FLIGHT —
        // "等待中" — in the overlay forever, since snapshot()'s own TTL sweep explicitly
        // exempted IN_FLIGHT rows. A clock-injected log lets this be proven without an
        // actual multi-minute sleep.
        long[] now = {0L};
        TranslationDebugLog debug = new TranslationDebugLog(() -> true, () -> now[0]);
        long id = debug.submitted("AI", java.util.List.of("Vitality: ⟦MT0⟧"));

        java.util.List<TranslationDebugLog.Entry> stillWaiting = debug.snapshot(10);
        assertEquals(1, stillWaiting.size());
        assertEquals(TranslationDebugLog.Status.IN_FLIGHT, stillWaiting.get(0).status());

        now[0] += 3 * 60_000L + 1L; // just past IN_FLIGHT_STALE_MS, no completed() ever came
        java.util.List<TranslationDebugLog.Entry> resolved = debug.snapshot(10);
        assertEquals(1, resolved.size());
        assertEquals(TranslationDebugLog.Status.FAILED, resolved.get(0).status(),
                "a row stuck in flight must eventually resolve instead of waiting forever");
        assertEquals("timed out (no response)", resolved.get(0).failureReason());
    }

    @Test
    void freshInFlightRowIsNotPrematurelyResolved() {
        long[] now = {0L};
        TranslationDebugLog debug = new TranslationDebugLog(() -> true, () -> now[0]);
        debug.submitted("AI", java.util.List.of("Strength: ⟦MT0⟧"));

        now[0] += 5_000L; // a normal in-flight request, nowhere near the stale threshold
        java.util.List<TranslationDebugLog.Entry> stillWaiting = debug.snapshot(10);
        assertEquals(TranslationDebugLog.Status.IN_FLIGHT, stillWaiting.get(0).status());
    }

    private static void assertFailure(String reason, Throwable error) {
        TranslationDebugLog.Failure failure = TranslationDebugLog.failureFor(error);
        assertEquals(reason, failure.reason());
        assertEquals("429 rate limit".equals(reason)
                        ? TranslationDebugLog.Status.RATE_LIMITED
                        : TranslationDebugLog.Status.FAILED,
                failure.status());
    }
}
