package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Replays recorded keys and KEEP/resend intervals through real production APIs.
 * The trace did not record enqueue times. Collector/worker ordering and frame rates
 * are explicitly simulated alternatives, not reconstructed historical facts. */
class ServerTraceReplayRegressionTest {
    private static final String KEEP = "\0MT_KEEP_ORIGINAL2";
    private static final Path FIXTURES = Path.of("verification/echo/fixtures");

    private static List<JsonObject> events(String file) throws IOException {
        try (Stream<String> lines = Files.lines(FIXTURES.resolve(file), StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        }
    }

    private static JsonObject event(List<JsonObject> events, int seq) {
        return events.stream().filter(row -> row.get("seq").getAsInt() == seq).findFirst().orElseThrow();
    }

    static Stream<Arguments> resendCases() throws IOException {
        List<JsonObject> rows = events("trace-20261006T124801954Z-e84576b7.excerpt.jsonl");
        List<Arguments> cases = new ArrayList<>();
        for (int[] pair : List.of(new int[]{211, 226}, new int[]{1846, 1861})) {
            JsonObject keep = event(rows, pair[0]), resend = event(rows, pair[1]);
            assertEquals("keep_original", keep.get("decision").getAsString());
            assertEquals(3, keep.get("echo_count").getAsInt());
            assertEquals(keep.get("semantic_key").getAsString(), resend.get("semantic_key").getAsString());
            long gap = resend.get("epoch_ms").getAsLong() - keep.get("epoch_ms").getAsLong();
            assertEquals(pair[0] == 211 ? 3750 : 4225, gap);
            for (boolean workerQueued : List.of(false, true)) {
                for (int fps : List.of(15, 60, 144)) {
                    cases.add(Arguments.of(keep.get("semantic_key").getAsString(),
                            resend.get("request_key").getAsString(), gap, workerQueued, fps));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: queuedWorker={3}, {4}fps")
    @MethodSource("resendCases")
    void loggedPostKeepResendsStayLocalAcrossServerRenderFrames(
            String plain, String styled, long gap, boolean workerQueued, int fps) {
        ManualExecutor workers = new ManualExecutor();
        List<String> sent = new ArrayList<>();
        Translator backend = (text, target) -> {
            sent.add(text);
            return new TranslationResult(text, "en");
        };
        long[] now = {0L};
        Map<String, String> disk = new ConcurrentHashMap<>(), failures = new ConcurrentHashMap<>();
        TranslationCache cache = new TranslationCache(backend, "zh-TW", workers, 100, 10_000L,
                () -> now[0], store(disk));
        cache.setFailureStore(store(failures));
        cache.setBatchWindowMs(() -> 0);
        assertNull(cache.translateBlocking(plain));
        now[0] = 10_000L;
        assertNull(cache.translateBlocking(plain));
        now[0] = 30_000L; // The second confirmation's cooldown must expire before enqueue.

        List<String> callbacks = new ArrayList<>(), finalCallbacks = new ArrayList<>();
        cache.requestCoalescedExactStyle(styled, callbacks::add, true);
        cache.requestCoalescedExactStyleFinal(styled, finalCallbacks::add);
        if (workerQueued) cache.flushBatch();
        assertTrue(cache.pendingCount() > 0, "real styled work must be queued before KEEP");
        assertTrue(callbacks.isEmpty(), "the styled subscriber must still be waiting");
        assertEquals(plain, cache.translateBlocking(plain));
        assertEquals(KEEP, disk.get(plain));
        int before = sent.size();

        now[0] += gap; // The exact observed third-echo -> erroneous-resend interval.
        int frames = fps * 5;
        for (int frame = 0; frame < frames; frame++) {
            assertEquals(styled, cache.getCached(styled));
            cache.requestBatched(styled);
            cache.flushBatch();
            workers.drain();
            now[0] += Math.max(1, 1000 / fps);
        }
        now[0] += 120_000L; // Extra stress: several original cooldown intervals.
        cache.warmBatchAsync(List.of(styled));
        cache.flushBatch();
        workers.drain();
        System.out.printf("TRACE_REPLAY %s gapMs=%d queuedWorker=%s fps=%d afterKeepCalls=%d pending=%d%n",
                plain, gap, workerQueued, fps, sent.size() - before, cache.pendingCount());
        assertAll(
                () -> assertEquals(3, before),
                () -> assertEquals(before, sent.size(), "no fourth provider entry after semantic KEEP"),
                () -> assertEquals(KEEP, disk.get(plain)),
                () -> assertTrue(failures.isEmpty(), "no late style/retry debt: " + failures),
                () -> assertEquals(List.of(styled), callbacks),
                () -> assertEquals(List.of(styled), finalCallbacks),
                () -> assertEquals(0, cache.pendingCount()));
    }

    static Stream<Arguments> nativeCases() throws IOException {
        List<String> keys = events("trace-20261006T132536174Z-b4026dd0.excerpt.jsonl").stream()
                .filter(row -> row.get("event").getAsString().equals("cache_request"))
                .map(row -> row.get("request_key").getAsString()).distinct().toList();
        assertEquals(2, keys.size(), "only complete recorded announcement keys are replayed");
        return keys.stream().flatMap(key -> Stream.of(Arguments.of(key, false), Arguments.of(key, true)));
    }

    @ParameterizedTest(name = "recorded Chinese lobby notice, AI={1}: {0}")
    @MethodSource("nativeCases")
    void recordedNativeAnnouncementsNeverEnterEitherBackend(String key, boolean useAi) {
        long[] now = {0L};
        List<String> sent = new ArrayList<>();
        Translator backend = (text, target) -> { sent.add(text); return new TranslationResult(text, "en"); };
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.targetLang = "zh-TW";
        cfg.chatMode = cfg.nameMode = cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.aiChat = cfg.aiName = cfg.aiScoreboard = useAi;
        TranslationCache machine = new TranslationCache(backend, "zh-TW", Runnable::run, 100, 10_000L, () -> now[0]);
        TranslationCache ai = new TranslationCache(backend, "zh-TW", Runnable::run, 100, 10_000L, () -> now[0]);
        TranslationService service = new TranslationService(cfg, machine, ai);
        service.setBatchWindowMs(() -> 0);
        for (int tick = 0; tick < 40; tick++) {
            service.translateChatAsyncDetailed(key, ignored -> {});
            service.translateChat(key);
            service.translateUi(key);
            service.warmNamesBatch(List.of(key));
            service.warmScoreboardBatch(List.of(key));
            service.flushBatches();
            now[0] += 10_000L;
        }
        System.out.printf("TRACE_NATIVE AI=%s providerEntries=%d pending=%d%n",
                useAi, sent.size(), ai.pendingCount() + machine.pendingCount());
        assertAll(() -> assertTrue(sent.isEmpty(), "native notices must be blocked before any echo learning"),
                () -> assertEquals(0, ai.pendingCount() + machine.pendingCount()));
    }

    @Test void recordedEnglishRetryCanStillSucceedInsteadOfBeingBlanketSuppressed() throws IOException {
        List<JsonObject> rows = events("trace-20261006T124801954Z-e84576b7.excerpt.jsonl");
        String key = event(rows, 631).get("request_key").getAsString();
        String translated = event(rows, 641).get("result").getAsString();
        int[] calls = {0};
        Translator backend = (text, target) -> new TranslationResult(++calls[0] == 1 ? text : translated, "en");
        TranslationCache cache = new TranslationCache(backend, "zh-TW", Runnable::run, 100, 0L, () -> 0L);
        assertNull(cache.translateBlocking(key));
        assertEquals(translated, cache.translateBlocking(key));
        assertEquals(translated, cache.getCached(key));
        assertEquals(2, calls[0]);
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            while (!tasks.isEmpty()) {
                assertTrue(count++ < 100, "unexpected endless worker scheduling");
                tasks.remove().run();
            }
        }
    }

    private static PersistentStore store(Map<String, String> rows) {
        return new PersistentStore() {
            public String get(String key) { return rows.get(key); }
            public void put(String key, String value) { rows.put(key, value); }
            public void clear() { rows.clear(); }
            public void remove(String key) { rows.remove(key); }
            public Map<String, String> entries() { return Map.copyOf(rows); }
            public int size() { return rows.size(); }
        };
    }
}
