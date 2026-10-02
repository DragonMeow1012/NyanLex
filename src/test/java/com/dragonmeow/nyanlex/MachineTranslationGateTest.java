package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.translate.GoogleFreeTranslator;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.MachineGateGuard;
import com.dragonmeow.nyanlex.translate.MachineTranslationGate;
import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.RequestsPausedException;
import com.dragonmeow.nyanlex.translate.TranslationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The global Google 429 gate. Fake transport and fake clock only: nothing touches the network. */
class MachineTranslationGateTest {

    private static final String OK = "[[[\"你好\",\"Hello\",null,null]],null,\"en\"]";
    private static final long MIN = 60_000L;

    /** Scripted transport: counts requests, answers by the current mode. */
    private static final class FakeGoogle implements HttpTransport {
        final AtomicInteger requests = new AtomicInteger();
        volatile String mode = "429"; // 429 | html | ok
        volatile Runnable duringRequest = () -> { };

        @Override public String get(String url) throws IOException {
            requests.incrementAndGet();
            duringRequest.run();
            switch (mode) {
                case "429":
                    throw new IOException("HTTP 429");
                case "html":
                    return "<html><body>Our systems have detected unusual traffic from your computer network."
                            + "</body></html>";
                default:
                    return OK;
            }
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final MachineTranslationGate gate = new MachineTranslationGate(now::get);
    private final FakeGoogle google = new FakeGoogle();
    private final GoogleFreeTranslator translator =
            new GoogleFreeTranslator(google, "auto", RequestPacer.disabled(), gate);

    private void tryOnce() {
        try {
            translator.translate("Hello", "zh-TW");
        } catch (RequestsPausedException | TranslationException ignored) {
            // outcome is observed through the gate and the counter
        }
    }

    @Test
    void http429ClosesTheGateAndAbandonsTheRequestWithoutFailure() {
        assertFalse(gate.blocksRequests());
        assertThrows(RequestsPausedException.class, () -> translator.translate("Hello", "zh-TW"));
        assertTrue(gate.blocksRequests());
        assertEquals(1, google.requests.get());
        assertEquals(1, gate.minutesUntilReopen());
    }

    @Test
    void unusualTrafficPageClosesTheGateEvenWithStatus200() {
        google.mode = "html";
        assertThrows(RequestsPausedException.class, () -> translator.translate("Hello", "zh-TW"));
        assertTrue(gate.blocksRequests());
    }

    @Test
    void closedGateSendsNothingOnAnyPath() {
        tryOnce(); // trips
        int before = google.requests.get();
        assertThrows(RequestsPausedException.class, () -> translator.translate("Hello", "zh-TW"));
        assertThrows(RequestsPausedException.class,
                () -> translator.translate("Hello ⟦CS#0⟧there⟦CS#1⟧", "zh-TW"));
        assertThrows(RequestsPausedException.class,
                () -> translator.translateBatch(List.of("A", "B", "C"), "zh-TW"));
        assertThrows(RequestsPausedException.class,
                () -> translator.translateBatch(List.of("A", "B"), "zh-TW", List.of("ctx")));
        assertTrue(translator.sendBlocked());
        assertEquals(before, google.requests.get(), "no request may leave while the gate is closed");
    }

    @Test
    void backoffStepsAre1_2_5_15_30_30Minutes() {
        List<Integer> told = new ArrayList<>();
        gate.setListener(told::add);
        long[] expected = {1, 2, 5, 15, 30, 30, 30};
        for (long minutes : expected) {
            tryOnce(); // the probe (or first request) is rate limited again
            assertEquals((int) minutes, gate.minutesUntilReopen());
            now.addAndGet(minutes * MIN - 1);
            assertTrue(gate.blocksRequests(), "still closed 1 ms before the end of " + minutes + " min");
            now.addAndGet(1);
        }
        assertEquals(List.of(1, 2, 5, 15, 30, 30, 30), told);
    }

    @Test
    void successResetsToTheFirstStep() {
        tryOnce();                       // 1 min
        now.addAndGet(MIN);
        tryOnce();                       // probe fails -> 2 min
        assertEquals(2, gate.minutesUntilReopen());
        now.addAndGet(2 * MIN);
        google.mode = "ok";
        tryOnce();                       // probe succeeds
        assertFalse(gate.blocksRequests());
        google.mode = "429";
        tryOnce();                       // next trip starts at 1 min again
        assertEquals(1, gate.minutesUntilReopen());
    }

    @Test
    void reopenedGateSendsExactlyOneProbeAtATime() {
        tryOnce();
        now.addAndGet(MIN);
        int before = google.requests.get();
        google.mode = "ok";
        List<Boolean> concurrentPaused = new ArrayList<>();
        google.duringRequest = () -> {
            // while the probe is on the wire, a second request must be held back
            try {
                translator.translate("Other", "zh-TW");
                concurrentPaused.add(false);
            } catch (RequestsPausedException expected) {
                concurrentPaused.add(true);
            } catch (Exception e) {
                concurrentPaused.add(false);
            }
        };
        tryOnce();
        assertEquals(List.of(true), concurrentPaused);
        assertEquals(before + 1, google.requests.get(), "only the probe went out");
        // probe succeeded: normal service resumes
        google.duringRequest = () -> { };
        assertFalse(gate.blocksRequests());
        tryOnce();
        assertEquals(before + 2, google.requests.get());
    }

    @Test
    void ordinaryFailureOfTheProbeFreesTheProbeSlot() {
        tryOnce();
        now.addAndGet(MIN);
        GoogleFreeTranslator flaky = new GoogleFreeTranslator(url -> {
            throw new IOException("connection reset");
        }, "auto", RequestPacer.disabled(), gate);
        assertThrows(TranslationException.class, () -> flaky.translate("Hello", "zh-TW"));
        assertFalse(gate.blocksRequests(), "probe slot released, a new probe may go out");
        tryOnce();                       // this probe gets 429
        assertEquals(2, gate.minutesUntilReopen());
    }

    @Test
    void inFlightRequestsThatAre429AfterTheGateClosedDoNotEscalate() {
        List<Integer> told = new ArrayList<>();
        gate.setListener(told::add);
        tryOnce();
        assertEquals(1, gate.onRateLimited()); // a straggler reports 429 while already closed
        assertEquals(List.of(1), told);
        assertEquals(1, gate.minutesUntilReopen());
    }

    @Test
    void waitingItemsStayQueuedAndAreNeverMarkedFailed() {
        AtomicLong cacheClock = new AtomicLong(5_000L);
        // A huge failure backoff: if the 429 had marked the text as failed it could not be requested again.
        TranslationCache cache = new TranslationCache(translator, "zh-TW", Runnable::run, 100,
                10 * 60 * MIN, cacheClock::get, null);
        cache.requestBatched("Hello world");
        for (int i = 0; i < 4; i++) cache.flushBatch();
        assertEquals(1, google.requests.get(), "the first batch trips the gate");
        assertTrue(gate.blocksRequests());

        // Closed: another text is observed, flushed repeatedly, still nothing is sent.
        cache.requestBatched("Second line");
        for (int i = 0; i < 6; i++) cache.flushBatch();
        assertEquals(1, google.requests.get());

        // Gate reopens and Google is healthy again: the waiting text goes out and is translated.
        now.addAndGet(MIN);
        google.mode = "ok";
        for (int i = 0; i < 6; i++) cache.flushBatch();
        assertTrue(google.requests.get() >= 2);
        assertNotNull(cache.getCached("Second line"));
        // the first text was not failed either: seen again, it is requested again right away
        cache.requestBatched("Hello world");
        for (int i = 0; i < 6; i++) cache.flushBatch();
        assertNotNull(cache.getCached("Hello world"));
    }

    // ---------------- player feedback ----------------

    private static final class RecordingUi implements MachineGateGuard.Feedback {
        final List<String> events = new ArrayList<>();
        @Override public void gateClosed(int minutes) { events.add("closed:" + minutes); }
        @Override public void manualBlocked(int minutes) { events.add("blocked:" + minutes); }
    }

    @Test
    void closingTheGateAnnouncesOncePerClose() {
        RecordingUi ui = new RecordingUi();
        MachineGateGuard.install(gate, ui);
        tryOnce();
        tryOnce(); // refused by the closed gate: no second announcement
        tryOnce();
        assertEquals(List.of("closed:1"), ui.events);
    }

    @Test
    void manualKeyPressOnClosedGateShowsHintAndSendsNothing() {
        RecordingUi ui = new RecordingUi();
        tryOnce();
        int before = google.requests.get();
        assertTrue(MachineGateGuard.blocksManualAction(true, true, gate, ui));
        assertEquals(List.of("blocked:1"), ui.events);
        assertEquals(before, google.requests.get());
    }

    @Test
    void manualKeyPressFollowsTheConsentFlowWhenOnlineTranslationIsOff() {
        RecordingUi ui = new RecordingUi();
        tryOnce();
        assertFalse(MachineGateGuard.blocksManualAction(false, true, gate, ui), "consent box comes first");
        assertTrue(ui.events.isEmpty());
    }

    @Test
    void aiSurfacesAndOpenGateAreNeverBlocked() {
        RecordingUi ui = new RecordingUi();
        assertFalse(MachineGateGuard.blocksManualAction(true, true, gate, ui), "gate open");
        tryOnce();
        assertFalse(MachineGateGuard.blocksManualAction(true, false, gate, ui), "AI engine surface");
        assertTrue(ui.events.isEmpty());
    }
}
