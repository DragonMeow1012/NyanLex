package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.service.TranslationService;
import com.dragonmeow.nyanslate.translate.AiSettings;
import com.dragonmeow.nyanslate.translate.HttpTransport;
import com.dragonmeow.nyanslate.translate.OpenAiTranslator;
import com.dragonmeow.nyanslate.translate.RequestPacer;
import com.dragonmeow.nyanslate.translate.TranslationException;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import com.dragonmeow.nyanslate.translate.Translator;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.0.7 P1.5 window batching of an AI engine: everything a screen produces during the
 * batching window (hovered tooltips included) goes out in ONE request once the engine may
 * send, each entry with its own surface context. Every collaborator is an inline fake:
 * a recording translator, a manual executor, a fake clock and a recording pacer sleeper.
 */
class TranslationCacheWindowedBatchTest {

    private static final Executor DIRECT = Runnable::run;
    /** Mirrors TranslationCache.MAX_WINDOWED_BATCH_CHARS and its per-entry overhead. */
    private static final int BUDGET = 4_000;
    private static final int ITEM_OVERHEAD = 16;

    private static final List<String> TOOLTIP_A = List.of(
            "Aspect of the Dragons", "Legendary Sword", "", "Ability: Dragon Rage", "Deals massive damage");
    private static final List<String> TOOLTIP_B = List.of(
            "Enchanted Book", "", "Sharpness", "Apply to a weapon");
    private static final List<String> SIDEBAR = List.of(
            "Spring Festival", "Private Island", "Objective", "Talk to the Elder");

    /** Inline engine: records every request with its per-entry contexts; peekable pacing. */
    private static final class RecordingEngine implements Translator {
        final List<List<String>> batches = new ArrayList<>();
        final List<List<List<String>>> contexts = new ArrayList<>();
        final AtomicLong delay = new AtomicLong();
        final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public TranslationResult translate(String text, String targetLang) {
            batches.add(List.of(text));
            contexts.add(null);
            return new TranslationResult("T:" + text, "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
            return translateBatchWithContexts(texts, targetLang, null);
        }

        @Override
        public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                                  List<List<String>> itemContexts) {
            if (failuresLeft.getAndDecrement() > 0) throw new IllegalStateException("worker crashed");
            batches.add(new ArrayList<>(texts));
            contexts.add(itemContexts == null ? null : new ArrayList<>(itemContexts));
            List<TranslationResult> out = new ArrayList<>();
            for (String text : texts) out.add(new TranslationResult("T:" + text, "en"));
            return out;
        }

        @Override
        public long nextRequestDelayMs() {
            return delay.get();
        }
    }

    private static TranslationCache windowed(Translator translator, Executor executor, long[] now) {
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", executor, 1_000, 10_000L, () -> now[0]);
        cache.setBatchWindowMs(() -> 5_000);
        cache.setWindowedBatching(true);
        return cache;
    }

    private static List<String> misses(List<String> surface, int... rows) {
        List<String> out = new ArrayList<>();
        for (int row : rows) out.add(surface.get(row));
        return out;
    }

    private static String letters(char seed, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) chars[i] = (char) ('a' + (seed + i) % 26);
        chars[0] = Character.toUpperCase(seed);
        return new String(chars);
    }

    // ---------------------------------------------------------------------------------

    @Test
    void differentContextsCollectedInTheWindowShareOneRequestWithTheirOwnContext() {
        RecordingEngine engine = new RecordingEngine();
        long[] now = {0L};
        TranslationCache cache = windowed(engine, DIRECT, now);
        List<String> chat = new ArrayList<>();

        cache.warmBatchAsyncHigh(misses(TOOLTIP_A, 0, 3), TOOLTIP_A);      // hovered tooltip A
        cache.flushBatch();
        assertEquals(1, engine.batches.size(),
                "a hovered tooltip with the engine idle sends right away (no contention to "
                        + "wait out), matching pre-1.0.7 responsiveness for the common "
                        + "single-item case -- see investigate-slowness.md");
        assertEquals(List.of("Aspect of the Dragons", "Ability: Dragon Rage"), engine.batches.get(0));
        // From here on the engine behaves like a real one: busy with its own cooldown right
        // after a send, so everything else arriving during that cooldown still collects into
        // ONE window exactly as before -- the immediate-send path only ever fires for a
        // genuinely idle engine, it never splits a window that is already forming.
        engine.delay.set(10_000L);

        now[0] = 1_000L;
        cache.warmBatchAsync(misses(SIDEBAR, 0, 3), SIDEBAR);              // sidebar rows
        cache.flushBatch();
        now[0] = 2_000L;
        cache.requestCoalesced("Hello from the chat", chat::add, true);     // chat, no context
        cache.flushBatch();
        now[0] = 3_000L;
        cache.warmBatchAsyncHigh(misses(TOOLTIP_B, 0), TOOLTIP_B);         // hovered tooltip B
        cache.flushBatch();
        now[0] = 5_999L;
        cache.flushBatch();
        assertEquals(1, engine.batches.size(), "collecting until the oldest entry's window closes");

        now[0] = 6_000L;
        engine.delay.set(0L); // its cooldown has elapsed by the time this window closes
        cache.flushBatch();

        assertEquals(2, engine.batches.size(), "the second window is its own single request");
        assertEquals(List.of("Enchanted Book", "Spring Festival", "Talk to the Elder", "Hello from the chat"),
                engine.batches.get(1), "hovered entries lead, the rest follow in arrival order");
        assertEquals(Arrays.asList(TOOLTIP_B, SIDEBAR, SIDEBAR, null),
                engine.contexts.get(1), "every entry keeps its own surface; chat carries none");
        assertEquals("T:Ability: Dragon Rage", cache.getCached("Ability: Dragon Rage"));
        assertEquals("T:Talk to the Elder", cache.getCached("Talk to the Elder"));
        assertEquals(List.of("T:Hello from the chat"), chat);
        assertEquals(0, cache.pendingCount());
    }

    @Test
    void realAiEngineSendsTheWindowAsOneLabelledRequestExactlyWhenItsPacerSlotOpens() throws Exception {
        long[] now = {0L};
        List<Long> sleeps = new ArrayList<>();
        EchoModel model = new EchoModel();
        RequestPacer pacer = new RequestPacer(() -> 10_000L, () -> now[0], sleeps::add);
        OpenAiTranslator ai = new OpenAiTranslator(model,
                () -> new AiSettings("https://x/v1", "m", List.of("k")), () -> now[0], pacer);
        TranslationCache cache = windowed(ai, DIRECT, now);

        cache.warmBatchAsyncHigh(misses(TOOLTIP_A, 0, 3), TOOLTIP_A);
        now[0] = 1_000L;
        cache.warmBatchAsyncHigh(misses(TOOLTIP_B, 0), TOOLTIP_B);
        now[0] = 2_000L;
        cache.requestBatched("Hello from the chat");
        cache.flushBatch();
        now[0] = 5_000L;
        cache.flushBatch();

        assertEquals(1, model.bodies.size(), "one HTTP request for two tooltips and a chat line");
        String user = userMessage(model.bodies.get(0));
        assertEquals(""
                + "Minecraft visible block contexts (semantic reference for domain and terminology; layout is program-owned):\n"
                + "[Context for units 86001-86004]\n"
                + "[L1:TEXT] Legendary Sword\n"
                + "[SECTION]\n"
                + "[L4:TEXT] Deals massive damage\n"
                + "[Context for units 86005-86006]\n"
                + "[SECTION]\n"
                + "[L2:TEXT] Sharpness\n"
                + "[L3:TEXT] Apply to a weapon\n"
                + "Translate ONLY the strictly anchored units below; do not output the visible-block context.\n"
                + "\n"
                + "86001 Aspect of the Dragons 86002\n"
                + "86003 Ability: Dragon Rage 86004\n"
                + "86005 Enchanted Book 86006\n"
                + "86007 Hello from the chat 86008\n", user,
                "each context names its units, rows being translated are not repeated, chat gets none");
        assertTrue(sleeps.isEmpty(), "composed when the slot was free: the pacer never sleeps");
        assertEquals("T:Enchanted Book", cache.getCached("Enchanted Book"));

        // The first request went out at t=2s (the engine was idle: see the hover-bypass note
        // on the previous test), so its 10s cooldown reserves the pacer until t=12s, not the
        // t=15s a first send delayed to the full 5s window would have produced.
        // Next window: the text closes its window at 11 s, but the engine's slot opens at 12 s.
        now[0] = 6_000L;
        cache.warmBatchAsyncHigh(List.of("Midas Staff"), List.of("Midas Staff", "Legendary Staff"));
        cache.flushBatch();
        now[0] = 11_000L;
        cache.flushBatch();
        now[0] = 11_999L;
        cache.requestBatched("Joined late while the engine was pacing");
        cache.flushBatch();
        assertEquals(1, model.bodies.size(), "held until the pacer allows the next request");

        now[0] = 12_000L;
        cache.flushBatch();
        assertEquals(2, model.bodies.size());
        String second = userMessage(model.bodies.get(1));
        assertTrue(second.contains("86001 Midas Staff 86002")
                && second.contains("86003 Joined late while the engine was pacing 86004"), second);
        assertTrue(sleeps.isEmpty(), "late binding: text that arrived during the wait rode along");
    }

    @Test
    void hoveredEntryIsNotStarvedByAnOlderBacklog() {
        RecordingEngine engine = new RecordingEngine();
        long[] now = {0L};
        TranslationCache cache = windowed(engine, DIRECT, now);
        List<String> backlog = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            backlog.add(letters((char) ('A' + i), 900));
            cache.requestBatched(backlog.get(i));
        }

        now[0] = 100L;
        cache.warmBatchAsyncHigh(List.of("Hovered Sword"), List.of("Hovered Sword", "Rare"));
        now[0] = 5_000L;
        cache.flushBatch();

        assertEquals(1, engine.batches.size());
        assertEquals("Hovered Sword", engine.batches.get(0).get(0), "the hovered entry leads");
        assertEquals(backlog.subList(0, 4), engine.batches.get(0).subList(1, 5),
                "the budget is filled with the oldest backlog");

        now[0] = 5_050L;
        cache.warmBatchAsyncHigh(List.of("Second Hover"), List.of("Second Hover"));
        cache.requestBatched("Newest background line");
        cache.flushBatch();

        assertEquals(2, engine.batches.size(), "the leftover backlog never waits a second window");
        assertEquals("Second Hover", engine.batches.get(1).get(0),
                "a later hover still overtakes the older backlog in the next request");
        assertEquals(backlog.subList(4, 8), engine.batches.get(1).subList(1, 5));
        for (int tick = 0; tick < 3; tick++) {
            now[0] += 50L;
            cache.flushBatch();
        }
        assertEquals(0, cache.pendingCount(), "everything drains without another window");
    }

    @Test
    void onlyTheOverflowBeyondTheBudgetBecomesASecondRequest() {
        RecordingEngine engine = new RecordingEngine();
        long[] now = {0L};
        TranslationCache cache = windowed(engine, DIRECT, now);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) rows.add(letters((char) ('K' + i), 1_200));
        rows.forEach(cache::requestBatched);
        assertTrue(3 * (1_200 + ITEM_OVERHEAD) <= BUDGET);

        now[0] = 5_000L;
        cache.flushBatch();
        assertEquals(1, engine.batches.size(),
                "three entries the old 1,400-char cap sent as three requests fit one AI request");
        assertEquals(rows, engine.batches.get(0));

        List<String> more = new ArrayList<>();
        for (int i = 0; i < 4; i++) more.add(letters((char) ('P' + i), 1_200));
        now[0] = 6_000L;
        more.forEach(cache::requestBatched);
        assertTrue(4 * (1_200 + ITEM_OVERHEAD) > BUDGET);
        cache.flushBatch();
        assertEquals(2, engine.batches.size(), "a full request does not wait for its window");
        assertEquals(more.subList(0, 3), engine.batches.get(1));
        assertEquals(1, cache.pendingCount(), "only the overflow is left for the next request");

        // The overflow arrived at 6 s: it keeps collecting until its own window closes.
        now[0] = 10_999L;
        cache.requestBatched("Joins the overflow");
        cache.flushBatch();
        assertEquals(2, engine.batches.size());
        now[0] = 11_000L;
        cache.flushBatch();
        assertEquals(3, engine.batches.size());
        assertEquals(List.of(more.get(3), "Joins the overflow"), engine.batches.get(2));
    }

    @Test
    void collectorKeepsCollectingWhileTheEngineIsPacing() {
        RecordingEngine engine = new RecordingEngine();
        long[] now = {0L};
        TranslationCache cache = windowed(engine, DIRECT, now);
        engine.delay.set(3_000L);

        cache.requestBatched("First line");
        now[0] = 5_000L;
        cache.flushBatch();
        now[0] = 7_000L;
        cache.requestBatched("Second line");
        cache.flushBatch();
        assertEquals(0, engine.batches.size(), "window closed, but the engine may not send yet");

        engine.delay.set(0L);
        cache.flushBatch();
        assertEquals(List.of(List.of("First line", "Second line")), engine.batches);
    }

    @Test
    void anUnfinishedBatchHoldsTheNextOneUntilItCompletes() {
        RecordingEngine engine = new RecordingEngine();
        Deque<Runnable> workers = new ArrayDeque<>();
        long[] now = {0L};
        TranslationCache cache = windowed(engine, workers::add, now);

        cache.requestBatched("First line");
        now[0] = 5_000L;
        cache.flushBatch();
        assertEquals(1, workers.size(), "the first batch is handed to a worker");

        cache.requestBatched("Second line");
        now[0] = 10_000L;
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(1, workers.size(), "no second batch is composed behind an unfinished one");

        workers.poll().run();
        assertEquals(List.of(List.of("First line")), engine.batches);
        cache.flushBatch();
        assertEquals(1, workers.size());
        workers.poll().run();
        assertEquals(List.of("Second line"), engine.batches.get(1));
    }

    @Test
    void aStuckBatchStopsHoldingTheCollectorAfterTwoMinutes() {
        RecordingEngine engine = new RecordingEngine();
        Deque<Runnable> workers = new ArrayDeque<>();
        long[] now = {0L};
        TranslationCache cache = windowed(engine, workers::add, now);

        cache.requestBatched("Stuck line");
        now[0] = 5_000L;
        cache.flushBatch();
        cache.requestBatched("Next line");
        now[0] = 5_000L + 119_999L;
        cache.flushBatch();
        assertEquals(1, workers.size());

        now[0] = 5_000L + 120_000L;
        cache.flushBatch();
        assertEquals(2, workers.size(), "a hung worker cannot stall the engine for good");
        workers.pollLast().run();
        assertEquals(List.of(List.of("Next line")), engine.batches);
        workers.poll().run(); // the stale batch finishing late must not disturb the next one
        cache.requestBatched("After the stale one");
        now[0] += 5_000L;
        cache.flushBatch();
        assertEquals(1, workers.size());
    }

    @Test
    void workerFailuresNeverStallLaterBatches() {
        RecordingEngine engine = new RecordingEngine();
        long[] now = {0L};
        boolean[] reject = {false};
        Executor flaky = task -> {
            if (reject[0]) throw new RejectedExecutionException("pool saturated");
            task.run();
        };
        TranslationCache cache = windowed(engine, flaky, now);

        engine.failuresLeft.set(1);
        cache.requestBatchedPassive("Crashes the worker"); // passive: no ledger retry later
        now[0] = 5_000L;
        cache.flushBatch();
        assertEquals(0, engine.batches.size());

        cache.requestBatched("After a crash");
        now[0] = 10_000L;
        cache.flushBatch();
        assertEquals(List.of(List.of("After a crash")), engine.batches,
                "a crashed batch releases the collector");

        reject[0] = true;
        cache.requestBatched("Rejected by the pool");
        now[0] = 15_000L;
        cache.flushBatch();
        assertEquals(0, cache.pendingCount(), "a rejected batch returns its permits");

        reject[0] = false;
        cache.requestBatched("After a rejection");
        now[0] = 20_000L;
        cache.flushBatch();
        assertEquals(List.of("After a rejection"), engine.batches.get(1),
                "a rejected batch releases the collector too");
    }

    @Test
    void switchingRequestsOffDropsTheWindowAndSendsNothing() {
        RecordingEngine engine = new RecordingEngine();
        long[] now = {0L};
        boolean[] open = {true};
        Deque<Runnable> workers = new ArrayDeque<>();
        TranslationCache cache = windowed(engine, workers::add, now);
        cache.setRequestGate(() -> open[0]);
        List<String> chat = new ArrayList<>();

        cache.requestCoalesced("Hello world", chat::add, true);
        // Not a hovered (high-priority) entry: this test is about the request-gate/ticket
        // interaction while the window is still collecting, not about the hover-idle bypass
        // (covered by differentContextsCollectedInTheWindowShareOneRequestWithTheirOwnContext),
        // so it deliberately keeps this batch inside the ordinary window-wait path.
        cache.warmBatchAsync(misses(TOOLTIP_A, 0), TOOLTIP_A);
        now[0] = 1_000L;
        cache.flushBatch();
        open[0] = false;
        cache.flushBatch();
        assertEquals(Collections.singletonList(null), chat, "the always-callback completes now");
        assertEquals(0, cache.pendingCount());
        for (int i = 0; i < 5; i++) {
            now[0] += 60_000L;
            cache.flushBatch();
        }
        assertEquals(0, workers.size());

        // A batch handed out just before the switch closes gives up unsent and frees the engine.
        open[0] = true;
        cache.requestBatched("Handed out, then switched off");
        now[0] += 5_000L;
        cache.flushBatch();
        assertEquals(1, workers.size());
        open[0] = false;
        workers.poll().run();
        assertEquals(0, engine.batches.size(), "0 requests while switched off");

        open[0] = true;
        cache.requestBatched("Back on");
        now[0] += 5_000L;
        cache.flushBatch();
        assertEquals(1, workers.size(), "the abandoned batch released the collector");
        workers.poll().run();
        assertEquals(List.of(List.of("Back on")), engine.batches);
    }

    @Test
    void onlyTheAiEngineUsesTheWindowWhileGoogleKeepsItsHoverFlush() {
        long[] now = {0L};
        RecordingEngine google = new RecordingEngine();
        RecordingEngine ai = new RecordingEngine();
        TranslationCache gtCache = new TranslationCache(google, "zh-TW", DIRECT, 100, 10_000L, () -> now[0]);
        TranslationCache aiCache = new TranslationCache(ai, "zh-TW", DIRECT, 100, 10_000L, () -> now[0]);
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = false;
        TranslationService service = new TranslationService(cfg, gtCache, aiCache);
        service.setBatchWindowMs(() -> 5_000);

        // 2026-10-02: warmTooltipBatch (the AUTOMATIC hover-warm path) is now gated by
        // config.aiTooltip itself (GT/manual mode sends nothing on a plain hover -- see
        // TranslationService#isManualItemTranslation) -- requestItemLines is the
        // engine-independent "always sends" entry point (the translate-key equivalent),
        // which still exercises the exact same underlying windowed-batching timing this
        // test is actually about.
        service.requestItemLines(List.of("Diamond Sword", "Used in smelting"));
        service.flushBatches();
        assertEquals(1, google.batches.size(), "Google: a hovered tooltip still flushes on the next tick");

        cfg.aiTooltip = true;
        ai.delay.set(10_000L); // engine mid-cooldown from an earlier request: not idle
        service.warmTooltipBatch(List.of("Golden Apple", "Restores health"));
        service.flushBatches();
        assertEquals(0, ai.batches.size(), "AI: a busy engine still makes the hovered tooltip wait for the window");
        now[0] = 5_000L;
        ai.delay.set(0L); // its cooldown has elapsed by the time the window closes
        service.flushBatches();
        assertEquals(1, ai.batches.size());
        assertEquals(1, google.batches.size());

        // A second, ISOLATED hover with the AI engine now idle (no other traffic queued)
        // sends right away instead of paying the window again -- exactly like Google always
        // has, restoring 1.0.6's responsiveness for the everyday "look at one item" case.
        // See investigate-slowness.md.
        now[0] = 20_000L;
        service.warmTooltipBatch(List.of("Enchanted Book", "Used to enchant gear"));
        service.flushBatches();
        assertEquals(2, ai.batches.size(), "AI: idle engine, nothing else queued -- sends immediately");
    }

    // ---- perf ②: due retries after reopening the master switch ----

    @Test
    void dueRetriesEnterTheCollectorAtMostSixteenPerTick() {
        AtomicInteger requests = new AtomicInteger();
        boolean[] failing = {true};
        Translator engine = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang)
                    throws TranslationException {
                return translateBatch(List.of(text), targetLang).get(0);
            }

            @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
                    throws TranslationException {
                requests.incrementAndGet();
                if (failing[0]) throw new TranslationException("HTTP 503");
                List<TranslationResult> out = new ArrayList<>();
                for (String text : texts) out.add(new TranslationResult("T:" + text, "en"));
                return out;
            }
        };
        long[] now = {0L};
        boolean[] open = {true};
        TranslationCache cache = new TranslationCache(engine, "zh-TW", DIRECT, 1_000, 1_000L, () -> now[0]);
        cache.setRequestGate(() -> open[0]);
        cache.setBatchWindowMs(() -> 0);
        int lines = 40;
        for (int i = 0; i < lines; i++) {
            // Short lines: all 40 stay under the machine collector's 1,400-char budget.
            String line = "Chat " + letters((char) ('A' + i % 26), 6)
                    + " " + letters((char) ('A' + i / 26), 3);
            cache.requestCoalesced(line, ignored -> { }, true);
        }
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(0, cache.pendingCount(), "all 40 lines were sent (and failed)");
        int failedRequests = requests.get();
        assertTrue(failedRequests >= 1);

        open[0] = false;                      // switched off: every backoff runs out meanwhile
        cache.flushBatch();
        now[0] = 30 * 60_000L;
        failing[0] = false;
        cache.setBatchWindowMs(() -> 5_000);  // keep the re-entered retries collected
        open[0] = true;

        cache.flushBatch();
        assertEquals(16, cache.pendingCount(), "one tick admits at most 16 due retries");
        cache.flushBatch();
        assertEquals(32, cache.pendingCount());
        cache.flushBatch();
        assertEquals(lines, cache.pendingCount(), "the following ticks reach every due retry");
        assertEquals(failedRequests, requests.get(), "nothing was sent while collecting");

        for (int round = 0; round < 3; round++) {
            now[0] += 5_000L;
            cache.flushBatch();
        }
        assertEquals(0, cache.pendingCount(), "and they all heal afterwards");
        assertTrue(requests.get() > failedRequests);
    }

    // ---------------------------------------------------------------------------------

    /** Inline OpenAI-compatible model: answers every anchored unit with "T:" + its text. */
    private static final class EchoModel implements HttpTransport {
        private static final Pattern UNIT = Pattern.compile("(?m)^(\\d{5}) (.*) (\\d{5})$");
        final List<String> bodies = new ArrayList<>();

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) {
            bodies.add(body);
            StringBuilder reply = new StringBuilder();
            Matcher unit = UNIT.matcher(userMessage(body));
            while (unit.find()) {
                reply.append(unit.group(1)).append("T:").append(unit.group(2))
                        .append(unit.group(3)).append('\n');
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", reply.toString());
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject root = new JsonObject();
            root.add("choices", choices);
            return new Gson().toJson(root);
        }
    }

    private static String userMessage(String body) {
        JsonObject root = new Gson().fromJson(body, JsonObject.class);
        return root.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
    }
}
