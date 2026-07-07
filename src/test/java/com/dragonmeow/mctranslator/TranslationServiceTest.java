package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceTest {

    private static final Executor DIRECT = Runnable::run;

    /** Inline translator returning a fixed Chinese rendering for known inputs. */
    private static Translator inlineTranslator(AtomicInteger calls) {
        return (text, target) -> {
            calls.incrementAndGet();
            String out = switch (text) {
                case "Hello" -> "你好";
                case "Welcome to the server" -> "歡迎來到伺服器";
                case "Diamond Sword" -> "鑽石劍";
                default -> "[" + text + "]";
            };
            return new TranslationResult(out, "en");
        };
    }

    private static TranslationService service(TranslatorConfig cfg, Translator t, Executor exec) {
        TranslationCache cache = new TranslationCache(t, cfg.targetLang, exec, 100);
        TranslationCache aiCache = new TranslationCache(t, cfg.targetLang, exec, 100);
        return new TranslationService(cfg, cache, aiCache);
    }

    /** Simulate two client ticks: the coalescer holds one tick after growth, then sends. */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    @Test
    void chatTranslationModeReplacesText() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.TRANSLATION;
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);

        s.translateChat("Hello"); // cache-or-warm: queues the miss
        pump(s);                  // per-tick batch goes out via the DIRECT executor
        TranslationDecision d = s.translateChat("Hello");
        assertTrue(d.changed());
        assertEquals(DisplayMode.TRANSLATION, d.mode());
        assertEquals("你好", d.translated());
        assertEquals("你好", d.renderPlain());
    }

    @Test
    void chatBothModeUsesBlockFormat() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.BOTH;
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);

        s.translateChat("Hello"); // queue
        pump(s);
        TranslationDecision d = s.translateChat("Hello");
        assertTrue(d.changed());
        assertEquals(DisplayMode.BOTH, d.mode());
        assertEquals("Hello", d.original());
        assertEquals("你好", d.translated());
        assertEquals("Hello\n你好", d.renderPlain());
    }

    @Test
    void chatOriginalOnlyDoesNotTranslate() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.ORIGINAL_ONLY;
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateChat("Hello").changed());
        assertFalse(s.wantsChatTranslation("Hello"));
        assertEquals(0, calls.get(), "ORIGINAL_ONLY must not call the translator");
    }

    @Test
    void perSurfaceModesAreIndependent() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.ORIGINAL_ONLY;   // chat off
        cfg.tooltipMode = DisplayMode.TRANSLATION;  // items on
        AtomicInteger calls = new AtomicInteger();
        // tooltip uses cache-or-warm; seed cache via a direct executor.
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateChat("Hello").changed());        // chat off
        s.translateItemLine("Diamond Sword");                   // queues the miss
        pump(s);
        assertTrue(s.translateItemLine("Diamond Sword").changed()); // items on
    }

    @Test
    void alreadyChineseIsLeftAlone() {
        TranslatorConfig cfg = new TranslatorConfig();
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateChat("你好世界").changed());
        assertEquals(0, calls.get(), "Chinese-to-Chinese must be filtered out before calling translator");
    }

    @Test
    void itemTooltipShowsOriginalFirstThenTranslationAfterCacheFills() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        AtomicInteger calls = new AtomicInteger();
        // Manual executor: simulates the async background worker.
        Deque<Runnable> queue = new ArrayDeque<>();
        Executor manual = queue::add;
        TranslationService s = service(cfg, inlineTranslator(calls), manual);

        // First frame: cache miss -> unchanged, queued for the per-tick batch.
        assertFalse(s.translateItemLine("Diamond Sword").changed());
        assertEquals(0, queue.size(), "render path must not spawn work directly");

        // Two ticks later the coalesced batch is handed to the worker.
        pump(s);
        assertEquals(1, queue.size());
        queue.poll().run();

        // Next frame: cache hit -> translated.
        TranslationDecision d = s.translateItemLine("Diamond Sword");
        assertTrue(d.changed());
        assertEquals("鑽石劍", d.translated());
        assertEquals(1, calls.get(), "should translate exactly once");
    }

    @Test
    void renderPathNeverBlocks() {
        TranslatorConfig cfg = new TranslatorConfig();
        AtomicInteger calls = new AtomicInteger();
        // Manual executor we deliberately never drain -> proves the render path never blocks.
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationService s = service(cfg, inlineTranslator(calls), queue::add);

        assertFalse(s.translateItemLine("Welcome to the server").changed());
        assertEquals(0, queue.size(), "render path only queues; the tick flush spawns the work");
        pump(s);
        assertEquals(1, queue.size(), "flush must coalesce the miss into one background task");
    }

    @Test
    void requestChatAsyncDeliversTranslation() {
        TranslatorConfig cfg = new TranslatorConfig();
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);
        List<String> got = new ArrayList<>();
        s.requestChatAsync("Hello", got::add);
        assertEquals(List.of(), got, "chat joins the per-tick batch; nothing sent yet");
        pump(s);
        assertEquals(List.of("你好"), got);
    }

    @Test
    void wantsChatTranslationRespectsTogglesAndFilter() {
        TranslatorConfig cfg = new TranslatorConfig();
        TranslationService s = service(cfg, inlineTranslator(new AtomicInteger()), DIRECT);
        assertTrue(s.wantsChatTranslation("Hello"));
        assertFalse(s.wantsChatTranslation("你好世界")); // already Chinese
        assertFalse(s.wantsChatTranslation("12345"));    // no letters

        cfg.chatMode = DisplayMode.ORIGINAL_ONLY; // chat off
        assertFalse(s.wantsChatTranslation("Hello"));
    }

    @Test
    void playerNamesNeverLeaveTheClientAndComeBackVerbatim() {
        TranslatorConfig cfg = new TranslatorConfig();
        List<String> sent = new ArrayList<>();
        Translator t = (text, target) -> {
            sent.add(text);
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = service(cfg, t, DIRECT);
        s.setProtectedNames(() -> java.util.Set.of("Steve123"));

        List<String> got = new ArrayList<>();
        s.translateChatAsync("Steve123 sold the dragon egg", got::add);
        pump(s);

        assertEquals(1, got.size());
        assertTrue(got.get(0) != null && got.get(0).contains("Steve123"),
                "the name must be restored verbatim: " + got.get(0));
        assertFalse(String.join(" ", sent).contains("Steve123"),
                "the raw name must never be sent to the backend: " + sent);
    }

    @Test
    void surfacesShowingAPlayerNameAreLeftAlone() {
        TranslatorConfig cfg = new TranslatorConfig();
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);
        s.setProtectedNames(() -> java.util.Set.of("Steve123"));

        assertFalse(s.translateUi("Steve123").changed(), "a name tag that IS a player name stays original");
        pump(s);
        assertEquals(0, calls.get());
    }

    @Test
    void nameTagsTranslateEverythingButRestorePlayerNames() {
        TranslatorConfig cfg = new TranslatorConfig();
        List<String> sent = new ArrayList<>();
        Translator t = (text, target) -> {
            sent.add(text);
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = service(cfg, t, DIRECT);
        s.setProtectedNames(() -> java.util.Set.of("Steve123"));

        // NPC / ground-item labels translate normally (1.0.0 behaviour)…
        s.translateUi("Lone Adventurer");
        pump(s);
        assertTrue(s.translateUi("Lone Adventurer").changed(), "NPC names must be translated");

        // …and a hologram CONTAINING a player name translates with the name restored.
        s.translateUi("Steve123 slain the dragon");
        pump(s);
        TranslationDecision d = s.translateUi("Steve123 slain the dragon");
        assertTrue(d.changed());
        assertTrue(d.translated().contains("Steve123"), "player name restored verbatim: " + d.translated());
        assertFalse(String.join(" ", sent).contains("Steve123"), "name never sent to the backend: " + sent);
    }

    @Test
    void numericTooltipValuesAreNotTranslated() {
        TranslatorConfig cfg = new TranslatorConfig();
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, inlineTranslator(calls), DIRECT);

        assertFalse(s.translateItemLine("64").changed());
        assertEquals(0, calls.get());
    }

    @Test
    void halfTransliteratedWordIsNeitherCachedNorDisplayed() {
        // The backend (AI) returns the half-transliterated hybrid "傑cob" for "jacob".
        Translator poison = (text, target) -> {
            String out = switch (text) {
                case "jacob" -> "傑cob";        // "Ja" transliterated, "cob" left as English
                case "Diamond Sword" -> "鑽石劍"; // a clean translation on the same engine
                default -> "[" + text + "]";
            };
            return new TranslationResult(out, "en");
        };
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        TranslationService s = service(cfg, poison, DIRECT);

        // First frame queues the miss; the tick flush hands it to the backend.
        assertFalse(s.translateItemLine("jacob").changed());
        pump(s);
        // The hybrid is rejected: the surface still shows the ORIGINAL, and nothing usable was
        // cached — so it can never serve other surfaces via the AI→Google read-through fallback.
        assertFalse(s.translateItemLine("jacob").changed(),
                "half-transliterated 傑cob must be rejected, never displayed");

        // A clean translation on the very same engine is unaffected.
        s.translateItemLine("Diamond Sword");
        pump(s);
        TranslationDecision d = s.translateItemLine("Diamond Sword");
        assertTrue(d.changed());
        assertEquals("鑽石劍", d.translated());
    }
}
