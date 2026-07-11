package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.AiSettings;
import com.dragonmeow.mctranslator.translate.GoogleFreeTranslator;
import com.dragonmeow.mctranslator.translate.HttpTransport;
import com.dragonmeow.mctranslator.translate.OpenAiTranslator;
import com.dragonmeow.mctranslator.translate.RequestPacer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
