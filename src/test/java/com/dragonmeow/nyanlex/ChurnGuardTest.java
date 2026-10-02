package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.translate.ChurnGuard;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ChurnGuard frequency detection with a fake clock, plus its TranslationCache hook.
 *  All collaborators are inline fakes — no real clock, no network. */
class ChurnGuardTest {

    private static final Executor DIRECT = Runnable::run;

    /** Four distinct keys sharing one signature inside the window trip the cooldown. */
    @Test
    void variantThresholdTripsCooldown() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);

        assertFalse(guard.shouldSuppress("»VOTE«"));
        assertFalse(guard.shouldSuppress("»»VOTE««"));
        assertFalse(guard.shouldSuppress("»»»VOTE«««"));
        assertTrue(guard.shouldSuppress("«VOTE»"), "the 4th distinct variant must trip the guard");
        assertTrue(guard.shouldSuppress("»VOTE«"), "cooldown must suppress every variant of the signature");
    }

    /** After the cooldown expires (and the window slid empty) requests flow again. */
    @Test
    void cooldownExpiryRestoresRequests() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        for (String key : new String[]{"»VOTE«", "»»VOTE««", "»»»VOTE«««", "«VOTE»"}) {
            guard.shouldSuppress(key);
        }
        now[0] = 300_001L; // cooldown over; the window entries (all at t=0) are stale too
        assertFalse(guard.shouldSuppress("»VOTE«"), "an idle signature must recover after cooldown");
    }

    /** Churn that CONTINUES during cooldown re-trips the guard at expiry (no free burst). */
    @Test
    void ongoingChurnDuringCooldownReTripsAtExpiry() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        for (String key : new String[]{"»VOTE«", "»»VOTE««", "»»»VOTE«««", "«VOTE»"}) {
            guard.shouldSuppress(key);
        }
        // The animation keeps rendering (and being recorded) right before expiry…
        now[0] = 299_000L;
        for (String key : new String[]{"»VOTE« 1", "»VOTE« 2", "»VOTE« 3", "»VOTE« 4"}) {
            assertTrue(guard.shouldSuppress(key));
        }
        // …so the very first attempt after expiry is still churning and stays suppressed.
        now[0] = 300_001L;
        assertTrue(guard.shouldSuppress("»VOTE« 5"),
                "still-churning text must re-trip immediately, not buy another request burst");
    }

    /** Variants outside the sliding window no longer count toward the threshold. */
    @Test
    void windowSlidesStaleVariantsOut() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        assertFalse(guard.shouldSuppress("»VOTE«"));
        assertFalse(guard.shouldSuppress("»»VOTE««"));
        assertFalse(guard.shouldSuppress("»»»VOTE«««"));
        now[0] = 61_000L; // all three slid out of the 60s window
        assertFalse(guard.shouldSuppress("«VOTE»"),
                "only ONE variant is inside the window now — must not trip");
    }

    /** The same key repeated any number of times is a cache-hit scenario, never churn. */
    @Test
    void repeatedIdenticalKeyNeverTrips() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        for (int i = 0; i < 20; i++) {
            assertFalse(guard.shouldSuppress("Welcome to the server!"),
                    "one distinct key can never be churn");
            now[0] += 1_000L;
        }
    }

    /** Letter-free keys (numbers, bars, pure tokens) have no signature: never guarded. */
    @Test
    void emptySignatureIsNeverSuppressed() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(2, 60_000L, 300_000L, () -> now[0]);
        for (String key : new String[]{"123", "456", "⟦MT0⟧", "⟦MT0⟧/⟦MT1⟧", "+++", "---"}) {
            assertFalse(guard.shouldSuppress(key), key);
        }
    }

    /** Tokens are stripped BEFORE signing: templated countdown frames share a signature. */
    @Test
    void signatureIgnoresTokensCaseAndSymbols() {
        assertEquals(ChurnGuard.signatureOf("Ends in ⟦MT0⟧!"), ChurnGuard.signatureOf("ENDS IN ⟦MT1⟧ »»"));
        assertEquals("vote", ChurnGuard.signatureOf("»» V O T E ««"));
        assertEquals("", ChurnGuard.signatureOf("⟦MT0⟧ 12:34 ---"));
    }

    /** The signature table is bounded: overflowing it clears rather than growing forever. */
    @Test
    void signatureTableIsBounded() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        for (int i = 0; i < 600; i++) {
            guard.shouldSuppress("line " + uniqueWord(i));
        }
        assertTrue(guard.signatureCount() <= 512,
                "signature table must stay bounded, got " + guard.signatureCount());
    }

    /** Hitting the 512-cap must NOT wipe a signature that is still on cooldown — a live
     *  animation keeps its suppression instead of getting a fresh doomed burst. */
    @Test
    void capEvictionPreservesCoolingSignatures() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);

        // Trip "vote": four distinct variants put the signature on cooldown until t=300_000.
        for (String key : new String[]{"»VOTE«", "»»VOTE««", "»»»VOTE«««", "«VOTE»"}) {
            guard.shouldSuppress(key);
        }

        // Flood with 512 distinct SETTLED signatures (one variant each, never tripped) to
        // force the cap eviction while "vote" is still cooling (the clock stays at t=0).
        for (int i = 0; i < 512; i++) {
            guard.shouldSuppress("line " + uniqueWord(i));
        }

        // A brand-new COSMETIC variant of the cooled signature (still signs to "vote") is
        // suppressed: eviction kept the cooling entry.
        assertTrue(guard.shouldSuppress("»»»»VOTE««««"),
                "a signature still on cooldown must survive the cap eviction");
        assertTrue(guard.signatureCount() >= 1 && guard.signatureCount() <= 512,
                "the table stays bounded but was not fully cleared, got " + guard.signatureCount());
    }

    /** S5: once player names are one ⟦n⟧ slot, lobby arrivals of different rank colourings
     *  share a signature and differ only in colour-run topology. Topologies of just two
     *  texts must not count as four churning variants. */
    @Test
    void colourTopologiesOfAFewTextsAreNotChurn() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        String[] lobby = {
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧+⟦/CS1⟧⟦CS0⟧] ⟦0⟧⟦/CS0⟧⟦CS2⟧ joined the lobby!⟦/CS2⟧",
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧++⟦/CS1⟧⟦CS0⟧] ⟦0⟧⟦/CS0⟧⟦CS2⟧ joined the lobby!⟦/CS2⟧",
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧+⟦/CS1⟧⟦CS2⟧] ⟦0⟧⟦/CS2⟧⟦CS3⟧ joined the lobby!⟦/CS3⟧",
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧++⟦/CS1⟧⟦CS2⟧] ⟦0⟧⟦/CS2⟧⟦CS3⟧ joined the lobby!⟦/CS3⟧",
        };
        assertEquals(ChurnGuard.signatureOf(lobby[0]), ChurnGuard.signatureOf(lobby[3]),
                "all four share one signature");
        for (String key : lobby) {
            assertFalse(guard.shouldSuppress(key), "only two texts, four colourings: " + key);
        }
    }

    /** …while distinct TEXTS still trip exactly as before, colour markers or not. */
    @Test
    void distinctTextVariantsStillTripThroughColourMarkers() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        assertFalse(guard.shouldSuppress("⟦CS0⟧»VOTE«⟦/CS0⟧"));
        assertFalse(guard.shouldSuppress("⟦CS0⟧»»VOTE««⟦/CS0⟧"));
        assertFalse(guard.shouldSuppress("⟦CS1⟧»»»VOTE«««⟦/CS1⟧"));
        assertTrue(guard.shouldSuppress("⟦CS0⟧«VOTE»⟦/CS0⟧"), "4th distinct text trips");
    }

    /** A single text whose colour topology alone keeps sweeping is still an animation. */
    @Test
    void oneTextCyclingOnlyItsColourTopologyStillTrips() {
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(4, 60_000L, 300_000L, () -> now[0]);
        String[] sweep = {
                "⟦CS0⟧S⟦/CS0⟧⟦CS1⟧KYBLOCK⟦/CS1⟧",
                "⟦CS0⟧S⟦/CS0⟧⟦CS1⟧K⟦/CS1⟧⟦CS0⟧YBLOCK⟦/CS0⟧",
                "⟦CS0⟧SK⟦/CS0⟧⟦CS1⟧Y⟦/CS1⟧⟦CS0⟧BLOCK⟦/CS0⟧",
                "⟦CS0⟧SKY⟦/CS0⟧⟦CS1⟧B⟦/CS1⟧⟦CS0⟧LOCK⟦/CS0⟧",
        };
        assertFalse(guard.shouldSuppress(sweep[0]));
        assertFalse(guard.shouldSuppress(sweep[1]));
        assertFalse(guard.shouldSuppress(sweep[2]));
        assertTrue(guard.shouldSuppress(sweep[3]),
                "four colourings of ONE text inside the window are still churn");
    }

    @Test
    void variantIgnoresOnlyColourMarkers() {
        assertEquals("[MVP+] ⟦0⟧ joined the lobby!", ChurnGuard.variantOf(
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧+⟦/CS1⟧⟦CS0⟧] ⟦0⟧⟦/CS0⟧⟦CS2⟧ joined the lobby!⟦/CS2⟧"));
        assertEquals("Ends in ⟦MT0⟧!", ChurnGuard.variantOf("Ends in ⟦MT0⟧!"));
        assertEquals("CSV ⟦MT0⟧ ⟦0⟧", ChurnGuard.variantOf("⟦CS1⟧CSV ⟦MT0⟧ ⟦0⟧⟦/CS1⟧"),
                "letters C,S in text and other tokens stay");
        assertEquals("a b", ChurnGuard.variantOf("⟦ CS 0 ⟧a⟦ / CS 0 ⟧ b"), "spaced markers too");
    }

    /** Distinct letter suffix per index so every line really is a distinct signature. */
    private static String uniqueWord(int i) {
        StringBuilder sb = new StringBuilder();
        for (char c : Integer.toString(i).toCharArray()) sb.append((char) ('a' + c - '0'));
        return sb.toString();
    }

    // ---- TranslationCache integration: the guard sits at the request-enqueue throat ----

    /** Inline translator that counts REQUESTS and echoes per line. */
    private static final class CountingBatchTranslator implements Translator {
        final AtomicInteger requests = new AtomicInteger();

        @Override
        public TranslationResult translate(String text, String targetLang) {
            requests.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
            requests.incrementAndGet();
            List<TranslationResult> out = new ArrayList<>();
            for (String t : texts) out.add(new TranslationResult("T:" + t, "en"));
            return out;
        }
    }

    /** Once the guard trips, requestBatched drops new variants: nothing becomes pending. */
    @Test
    void suppressedRequestBatchedProducesNoPending() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        long[] now = {0L};
        cache.setChurnGuard(new ChurnGuard(2, 60_000L, 300_000L, () -> now[0]));

        cache.requestBatched("»VOTE«");            // 1st variant: allowed, buffered
        assertEquals(1, cache.pendingCount());
        cache.requestBatched("»»VOTE««");          // 2nd variant: trips the guard, dropped
        assertEquals(1, cache.pendingCount(), "a suppressed request must not be buffered");
        cache.requestBatched("»»»VOTE«««");
        assertEquals(1, cache.pendingCount());
    }

    /** Suppressed coalesced requests behave like backoff: always-callbacks get null. */
    @Test
    void suppressedCoalescedRequestDeliversNullToAlwaysCallback() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        long[] now = {0L};
        cache.setChurnGuard(new ChurnGuard(2, 60_000L, 300_000L, () -> now[0]));
        List<String> got = new ArrayList<>();

        cache.requestCoalesced("»VOTE«", got::add, true);
        cache.requestCoalesced("»»VOTE««", got::add, true); // trips: dropped with null
        assertEquals(1, got.size());
        assertNull(got.get(0));
        assertEquals(0, t.requests.get(), "the suppressed variant must not reach the translator");
    }

    /** Already-cached translations keep rendering during a cooldown (guard is enqueue-only). */
    @Test
    void cachedTranslationsSurviveCooldown() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        long[] now = {0L};
        cache.setChurnGuard(new ChurnGuard(2, 60_000L, 300_000L, () -> now[0]));

        assertEquals("T:»VOTE«", cache.translateBlocking("»VOTE«")); // cached before the churn
        cache.requestBatched("»»VOTE««");
        cache.requestBatched("»»»VOTE«««");                          // guard trips
        assertEquals("T:»VOTE«", cache.getCached("»VOTE«"),
                "cached variants must keep rendering while the signature cools down");
    }

    /** warmBatch (the shared HTTP throat) also drops suppressed keys. */
    @Test
    void warmBatchSkipsSuppressedKeys() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        long[] now = {0L};
        ChurnGuard guard = new ChurnGuard(2, 60_000L, 300_000L, () -> now[0]);
        cache.setChurnGuard(guard);
        guard.shouldSuppress("»VOTE«");
        guard.shouldSuppress("»»VOTE««"); // signature "vote" is now cooling down

        assertTrue(cache.warmBatch(List.of("»»»VOTE«««", "Diamond Sword")));
        assertNull(cache.getCached("»»»VOTE«««"), "suppressed key must not be translated");
        assertEquals("T:Diamond Sword", cache.getCached("Diamond Sword"),
                "unrelated lines in the same batch must still translate");
        assertEquals(1, t.requests.get());
    }

    /** setChurnGuard(null) turns the guard off entirely. */
    @Test
    void nullGuardDisablesSuppression() {
        CountingBatchTranslator t = new CountingBatchTranslator();
        TranslationCache cache = new TranslationCache(t, "zh-TW", DIRECT, 100);
        cache.setChurnGuard(null);

        for (int i = 0; i < 8; i++) cache.requestBatched("»VOTE« " + "x".repeat(i + 1));
        assertEquals(8, cache.pendingCount(), "with the guard off every variant may queue");
    }
}
