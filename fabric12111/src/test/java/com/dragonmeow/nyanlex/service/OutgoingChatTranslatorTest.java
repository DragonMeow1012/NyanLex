package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OutgoingChatTranslatorTest {
    private static TranslatorConfig config() {
        TranslatorConfig config = new TranslatorConfig(); config.translationRequestsEnabled = true;
        config.chatComposerEnabled = true; return config;
    }
    @Test void independentTargetAndPlayerNameProtection() {
        TranslatorConfig c = config();
        Translator backend = (source, target) -> {
            assertEquals("en", target); assertFalse(source.contains("Jerry"));
            return new TranslationResult("Wait, ⟦0⟧.", "zh");
        };
        var service = new OutgoingChatTranslator(c, backend, backend, Runnable::run, () -> List.of("Jerry"));
        service.translate("Jerry 等一下", "en", (value, error) -> assertEquals("Wait, Jerry.", value));
        assertEquals("zh-TW", c.targetLang);
    }
    @Test void offlineBlockedAndQueuedProfileChangeSendNothing() {
        TranslatorConfig c = config(); AtomicInteger calls = new AtomicInteger();
        Translator backend = (s,t) -> { calls.incrementAndGet(); return new TranslationResult("ok", "zh"); };
        List<Runnable> work = new ArrayList<>();
        var service = new OutgoingChatTranslator(c, backend, backend, work::add, List::of);
        c.translationRequestsEnabled = false;
        service.translate("hi", "en", (v,e) -> assertEquals("offline", e)); assertTrue(work.isEmpty());
        c.translationRequestsEnabled = true; c.aiChat = true;
        service.translate("hi", "en", (v,e) -> assertEquals("changed", e));
        c.aiModel = "different-model"; work.remove(0).run(); assertEquals(0, calls.get());
    }
    @Test void googleGateIsSharedAndNeverRelaxedByManualDraft() {
        TranslatorConfig c = config(); AtomicInteger calls = new AtomicInteger();
        MachineTranslationGate gate = new MachineTranslationGate(() -> 100L); gate.onRateLimited();
        Translator google = new GoogleFreeTranslator(url -> { calls.incrementAndGet(); return ""; }, "auto", RequestPacer.disabled(), gate);
        var service = new OutgoingChatTranslator(c, google, google, Runnable::run, List::of);
        service.translate("你好", "en", (v,e) -> assertEquals("paused", e));
        assertEquals(0, calls.get()); assertTrue(gate.blocksRequests());
    }
    @Test void turningRequestsOffDuringPacingPreventsHttp() {
        TranslatorConfig c = config(); AtomicInteger calls = new AtomicInteger();
        RequestPacer pacer = new RequestPacer(() -> 5L, () -> 1L, ms -> c.translationRequestsEnabled = false);
        pacer.acquire();
        Translator google = new GoogleFreeTranslator(url -> { calls.incrementAndGet(); return ""; }, "auto", pacer, new MachineTranslationGate());
        var service = new OutgoingChatTranslator(c, google, google, Runnable::run, List::of);
        service.translate("你好", "en", (v,e) -> assertNull(v));
        assertEquals(0, calls.get()); assertTrue(RequestGate.isOpen());
    }
    @Test void singleFlightIsReleasedAfterFailureAndQueueRejection() {
        TranslatorConfig c = config(); List<Runnable> work = new ArrayList<>();
        Translator bad = (s,t) -> { throw new TranslationException("failure"); };
        var service = new OutgoingChatTranslator(c, bad, bad, work::add, List::of);
        service.translate("first", "en", (v,e) -> assertEquals("failed", e));
        service.translate("second", "en", (v,e) -> assertEquals("busy", e));
        work.remove(0).run();
        service.translate("third", "en", (v,e) -> assertEquals("failed", e)); assertEquals(1, work.size());
        work.remove(0).run();
    }
}
