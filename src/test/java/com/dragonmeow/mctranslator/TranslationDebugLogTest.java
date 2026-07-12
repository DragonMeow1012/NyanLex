package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
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

    private static void assertFailure(String reason, Throwable error) {
        TranslationDebugLog.Failure failure = TranslationDebugLog.failureFor(error);
        assertEquals(reason, failure.reason());
        assertEquals("429 rate limit".equals(reason)
                        ? TranslationDebugLog.Status.RATE_LIMITED
                        : TranslationDebugLog.Status.FAILED,
                failure.status());
    }
}
