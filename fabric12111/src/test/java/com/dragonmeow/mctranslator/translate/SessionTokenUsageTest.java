package com.dragonmeow.mctranslator.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionTokenUsageTest {

    @Test
    void completedCumulativeSourcesReleaseTheirBookkeepingWithoutLosingTotals() {
        SessionTokenUsage usage = new SessionTokenUsage();
        usage.recordCumulative("codex:one", 10, 2, 4, 1, 14);
        usage.recordCumulative("codex:one", 15, 3, 6, 2, 21);

        assertEquals(1, usage.activeCumulativeSources());
        usage.finishCumulative("codex:one");

        assertEquals(0, usage.activeCumulativeSources());
        assertEquals(15, usage.snapshot().inputTokens());
        assertEquals(6, usage.snapshot().outputTokens());
        assertEquals(21, usage.snapshot().totalTokens());
        assertEquals(1, usage.snapshot().requests());
    }

    @Test
    void saturatedSourcesAreRejectedUntilABaselineIsReleased() {
        SessionTokenUsage usage = new SessionTokenUsage();
        for (int i = 0; i < 1_024; i++) {
            usage.recordCumulative("codex:" + i, 1, 0, 1, 0, 2);
        }
        usage.recordCumulative("codex:overflow", 100, 0, 100, 0, 200);

        assertEquals(1_024, usage.activeCumulativeSources());
        assertEquals(1_024, usage.snapshot().requests());
        assertEquals(2_048, usage.snapshot().totalTokens());

        usage.recordCumulative("codex:0", 2, 0, 2, 0, 4);
        assertEquals(2_050, usage.snapshot().totalTokens());
        assertEquals(1_024, usage.snapshot().requests());

        usage.finishCumulative("codex:0");
        usage.recordCumulative("codex:overflow", 3, 0, 2, 0, 5);
        assertEquals(1_024, usage.activeCumulativeSources());
        assertEquals(2_055, usage.snapshot().totalTokens());
        assertEquals(1_025, usage.snapshot().requests());

        // The released source cannot displace a live baseline and be counted again.
        usage.recordCumulative("codex:0", 50, 0, 50, 0, 100);
        assertEquals(2_055, usage.snapshot().totalTokens());
        assertEquals(1_025, usage.snapshot().requests());
    }

    @Test
    void outOfOrderCumulativeUpdatesKeepTheirHighWaterMark() {
        SessionTokenUsage usage = new SessionTokenUsage();
        usage.recordCumulative("codex:one", 20, 4, 8, 2, 28);
        usage.recordCumulative("codex:one", 10, 2, 4, 1, 14);
        usage.recordCumulative("codex:one", 21, 5, 9, 3, 30);

        SessionTokenUsage.Snapshot snapshot = usage.snapshot();
        assertEquals(21, snapshot.inputTokens());
        assertEquals(5, snapshot.cachedInputTokens());
        assertEquals(9, snapshot.outputTokens());
        assertEquals(3, snapshot.reasoningOutputTokens());
        assertEquals(30, snapshot.totalTokens());
        assertEquals(1, snapshot.requests());
    }
}
