package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.SessionTokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionTokenUsageTest {

    @Test
    void ordinaryRequestsAccumulateSanitizedTotals() {
        SessionTokenUsage usage = new SessionTokenUsage();

        usage.recordRequest(10, 4, 3, 1, 0);
        usage.recordRequest(-5, -2, 7, 2, 20);

        assertEquals(new SessionTokenUsage.Snapshot(10, 4, 10, 3, 33, 2),
                usage.snapshot());
    }

    @Test
    void cumulativeCodexUpdatesOnlyAddNewDeltas() {
        SessionTokenUsage usage = new SessionTokenUsage();

        usage.recordCumulative("thread-1", 100, 80, 10, 3, 110);
        usage.recordCumulative("thread-1", 100, 80, 10, 3, 110);
        usage.recordCumulative("thread-1", 130, 90, 16, 5, 146);

        assertEquals(new SessionTokenUsage.Snapshot(130, 90, 16, 5, 146, 1),
                usage.snapshot());
        assertEquals(new SessionTokenUsage.Snapshot(0, 0, 0, 0, 0, 0),
                SessionTokenUsage.Snapshot.EMPTY);
    }
}
