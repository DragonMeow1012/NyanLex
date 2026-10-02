package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.GoogleFreeTranslator;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.RequestGate;
import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.RequestsPausedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事前冷卻節流：unit tests with inline fake clock/sleeper — no real sleeping. */
class RequestPacerTest {

    /** Inline fake sleeper: records every requested sleep instead of blocking. */
    private static final class RecordingSleeper implements RequestPacer.Sleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long ms) {
            sleeps.add(ms);
        }
    }

    @Test
    void secondAcquireTooSoonSleepsTheRemainder() {
        AtomicLong now = new AtomicLong(1_000);
        RecordingSleeper sleeper = new RecordingSleeper();
        RequestPacer pacer = new RequestPacer(() -> 400L, now::get, sleeper);

        pacer.acquire(); // first request goes out immediately
        assertTrue(sleeper.sleeps.isEmpty(), "first acquire must not sleep");

        pacer.acquire(); // clock frozen: full cooldown still outstanding
        assertEquals(List.of(400L), sleeper.sleeps, "second acquire sleeps the remainder");

        // Third acquire with the clock STILL frozen reserves the slot after the second
        // one (concurrent-caller semantics): it sleeps its own accumulated remainder.
        pacer.acquire();
        assertEquals(List.of(400L, 800L), sleeper.sleeps);
    }

    @Test
    void partialElapsedIntervalSleepsOnlyTheDifference() {
        AtomicLong now = new AtomicLong(1_000);
        RecordingSleeper sleeper = new RecordingSleeper();
        RequestPacer pacer = new RequestPacer(() -> 400L, now::get, sleeper);

        pacer.acquire();
        now.addAndGet(150); // 150ms elapsed of the 400ms cooldown
        pacer.acquire();
        assertEquals(List.of(250L), sleeper.sleeps);
    }

    @Test
    void sufficientIntervalDoesNotSleep() {
        AtomicLong now = new AtomicLong(1_000);
        RecordingSleeper sleeper = new RecordingSleeper();
        RequestPacer pacer = new RequestPacer(() -> 400L, now::get, sleeper);

        pacer.acquire();
        now.addAndGet(400); // exactly the cooldown
        pacer.acquire();
        now.addAndGet(1_000); // way past
        pacer.acquire();
        assertTrue(sleeper.sleeps.isEmpty(), "spaced acquires must never sleep");
    }

    @Test
    void zeroCooldownDisablesPacingEntirely() {
        AtomicLong now = new AtomicLong(1_000);
        RecordingSleeper sleeper = new RecordingSleeper();
        RequestPacer pacer = new RequestPacer(() -> 0L, now::get, sleeper);

        for (int i = 0; i < 5; i++) pacer.acquire(); // clock frozen, burst of 5
        assertTrue(sleeper.sleeps.isEmpty(), "requestCooldownMs=0 must never throttle");
    }

    @Test
    void closedGateStopsEvenAnUnpacedRequestButUnboundThreadsAreNeverBlocked() {
        RequestPacer unpaced = RequestPacer.disabled(); // e.g. the Codex transport
        unpaced.acquire(); // no binding (connection test / account refresh): always allowed

        BooleanSupplier previous = RequestGate.bind(() -> false);
        try {
            assertThrows(RequestsPausedException.class, unpaced::acquire,
                    "the check runs before the cooldown<=0 early return");
        } finally {
            RequestGate.restore(previous);
        }
        unpaced.acquire(); // restored: unbound again
    }

    @Test
    void sleepingRequestGivesUpWhenSwitchedOffMeanwhile() {
        AtomicLong now = new AtomicLong(1_000);
        boolean[] open = {true};
        List<Long> sleeps = new ArrayList<>();
        RequestPacer pacer = new RequestPacer(() -> 400L, now::get, ms -> {
            sleeps.add(ms);
            open[0] = false; // the user switches requests off while this worker sleeps
        });

        BooleanSupplier previous = RequestGate.bind(() -> open[0]);
        try {
            pacer.acquire(); // first slot: no sleep, allowed
            assertThrows(RequestsPausedException.class, pacer::acquire);
            assertEquals(List.of(400L), sleeps, "it slept for its slot, then gave up");
            assertThrows(RequestsPausedException.class, pacer::acquire,
                    "still off: refused before reserving another slot");
            assertEquals(1, sleeps.size());
        } finally {
            RequestGate.restore(previous);
        }
    }

    @Test
    void openAiRequestPausedWhilePacedSendsNothingAndLeavesKeysHealthy() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        boolean[] open = {true};
        RequestPacer pacer = new RequestPacer(() -> 400L, now::get, ms -> open[0] = false);
        AtomicInteger httpCalls = new AtomicInteger();
        HttpTransport transport = new HttpTransport() {
            @Override
            public String get(String url) {
                throw new AssertionError("AI path must POST");
            }

            @Override
            public String post(String url, String body, Map<String, String> headers) {
                httpCalls.incrementAndGet();
                String anchored = OpenAiTranslator.BATCH_ANCHOR_BASE + "你好"
                        + (OpenAiTranslator.BATCH_ANCHOR_BASE + 1);
                return "{\"choices\":[{\"message\":{\"content\":\"" + anchored + "\"}}]}";
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(transport,
                () -> new AiSettings("https://example.test/v1", "test-model", List.of("k1")),
                now::get, pacer);
        pacer.acquire(); // an earlier request owns the current slot

        BooleanSupplier previous = RequestGate.bind(() -> open[0]);
        try {
            assertThrows(RequestsPausedException.class, () -> t.translate("Hello", "zh-TW"),
                    "not wrapped into a TranslationException / failure");
        } finally {
            RequestGate.restore(previous);
        }
        assertEquals(0, httpCalls.get());
        assertFalse(t.isRateLimited(), "a paused request must not trip the 429 gate");

        now.addAndGet(10_000);
        assertEquals("你好", t.translate("Hello", "zh-TW").translatedText(),
                "the same key is used right away: nothing was quarantined");
        assertEquals(1, httpCalls.get());
    }

    @Test
    void machineRouterAndWebEnginesLetThePauseThrough() {
        for (String provider : List.of("deepl_api", "microsoft_api", "google")) {
            AtomicLong now = new AtomicLong(1_000);
            boolean[] open = {true};
            RequestPacer pacer = new RequestPacer(() -> 400L, now::get, ms -> open[0] = false);
            AtomicInteger httpCalls = new AtomicInteger();
            HttpTransport transport = new HttpTransport() {
                @Override
                public String get(String url) {
                    httpCalls.incrementAndGet();
                    throw new AssertionError("must not reach the network: " + url);
                }

                @Override
                public String post(String url, String body, Map<String, String> headers) {
                    httpCalls.incrementAndGet();
                    throw new AssertionError("must not reach the network: " + url);
                }
            };
            com.dragonmeow.nyanlex.translate.SwitchingMachineTranslator router =
                    new com.dragonmeow.nyanlex.translate.SwitchingMachineTranslator(
                            transport, () -> "auto", () -> provider, pacer, () -> {
                                com.dragonmeow.nyanlex.config.TranslatorConfig c =
                                        new com.dragonmeow.nyanlex.config.TranslatorConfig();
                                c.deeplApiKey = "test-deepl-key:fx";
                                c.microsoftApiKey = "test-ms-key";
                                return c;
                            });
            pacer.acquire(); // an earlier request owns the current slot

            BooleanSupplier previous = RequestGate.bind(() -> open[0]);
            try {
                assertThrows(RequestsPausedException.class,
                        () -> router.translateBatch(List.of("Hello", "World"), "zh-TW"),
                        provider + ": the pause must not become a TranslationException");
            } finally {
                RequestGate.restore(previous);
            }
            assertEquals(0, httpCalls.get(), provider);
        }
    }

    @Test
    void googleTranslatorPacesEveryHttpRequest() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        RecordingSleeper sleeper = new RecordingSleeper();
        RequestPacer pacer = new RequestPacer(() -> 400L, now::get, sleeper);
        AtomicInteger httpCalls = new AtomicInteger();
        // Inline fake transport: canned Google-shaped body, no network.
        HttpTransport transport = url -> {
            httpCalls.incrementAndGet();
            return "[[[\"你好\",\"Hello\",null,null]],null,\"en\"]";
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(transport, "auto", pacer);

        t.translate("Hello", "zh-TW"); // request #1: immediate
        t.translate("World", "zh-TW"); // request #2: clock frozen → paced
        assertEquals(2, httpCalls.get());
        assertEquals(List.of(400L), sleeper.sleeps, "each outbound request passes the pacer");
    }

    @Test
    void openAiTranslatorPacesEveryHttpRequest() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        RecordingSleeper sleeper = new RecordingSleeper();
        RequestPacer pacer = new RequestPacer(() -> 400L, now::get, sleeper);
        AtomicInteger httpCalls = new AtomicInteger();
        // Inline fake transport: canned chat-completions body, no network.
        HttpTransport transport = new HttpTransport() {
            @Override
            public String get(String url) {
                throw new AssertionError("AI path must POST");
            }

            @Override
            public String post(String url, String body, Map<String, String> headers) {
                httpCalls.incrementAndGet();
                return "{\"choices\":[{\"message\":{\"content\":\"1. 你好\"}}]}";
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(transport,
                () -> new AiSettings("https://example.test/v1", "test-model", List.of("k1")),
                now::get, pacer);

        t.translate("Hello", "zh-TW"); // request #1: immediate
        t.translate("Hello again", "zh-TW"); // request #2: clock frozen → paced
        assertEquals(2, httpCalls.get());
        assertEquals(List.of(400L), sleeper.sleeps, "each outbound POST passes the pacer");
    }
}
