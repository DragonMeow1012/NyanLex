package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliRequestLimitsTest {
    @Test
    void structuredRateErrorRespectsRetryAfterWithoutSendingDuringBackoff() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        CliRequestLimits limits = limits(clock);
        IOException error = limits.failure(JsonParser.parseString(
                "{\"code\":429,\"message\":\"Too many requests\",\"retryAfterSeconds\":120}"), "rate error");
        assertInstanceOf(CliRequestLimits.LimitedException.class, error);
        assertEquals(121_000, limits.blockedUntil());
        assertThrows(CliRequestLimits.LimitedException.class, limits::acquire);
        clock.set(121_000);
        limits.acquire();
    }

    @Test
    void unknownQuotaResetStopsAutomaticRetriesButAnExplicitReconnectCanProbeAgain() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        CliRequestLimits limits = limits(clock);
        assertInstanceOf(CliRequestLimits.LimitedException.class, limits.failure(JsonParser.parseString(
                "{\"codexErrorInfo\":\"usageLimitExceeded\"}"), "failed"));
        clock.set(86_400_000);
        assertThrows(CliRequestLimits.LimitedException.class, limits::acquire);
        limits.reconnect();
        limits.acquire();
    }

    @Test
    void missingQuotaDataAllowsTranslationAndAnUnrelatedBucketDoesNotBlockIt() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        CliRequestLimits limits = limits(clock);
        limits.updateCodex(JsonParser.parseString("{}").getAsJsonObject());
        limits.updateCodex(JsonParser.parseString(
                "{\"rateLimitsByLimitId\":{\"unrelated\":{\"primary\":{\"usedPercent\":100}}}}")
                .getAsJsonObject());
        limits.acquire();
    }

    @Test
    void quotaSnapshotUsesTheLatestDepletedWindowReset() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        CliRequestLimits limits = limits(clock);
        limits.updateCodex(JsonParser.parseString("{\"rateLimits\":{"
                + "\"primary\":{\"usedPercent\":100,\"resetsAt\":30},"
                + "\"secondary\":{\"usedPercent\":100,\"resetsAt\":120}}}").getAsJsonObject());
        assertEquals(120_000, limits.blockedUntil());
        clock.set(30_000);
        assertThrows(CliRequestLimits.LimitedException.class, limits::acquire);
        clock.set(120_000);
        limits.acquire();
    }

    @Test
    void authenticationFailureIsNotConfusedWithExhaustedAllowance() {
        assertFalse(limits(new AtomicLong()).failure(JsonParser.parseString(
                "{\"message\":\"Authentication required\"}"), "Authentication required")
                instanceof CliRequestLimits.LimitedException);
    }

    private static CliRequestLimits limits(AtomicLong clock) {
        return new CliRequestLimits(clock::get, new RequestPacer(() -> 0L, clock::get, clock::addAndGet));
    }
}
