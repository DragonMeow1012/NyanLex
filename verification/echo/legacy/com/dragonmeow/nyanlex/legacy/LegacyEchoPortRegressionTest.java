package com.dragonmeow.nyanlex.legacy;

import com.dragonmeow.nyanlex.translate.TranslationFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java-8 core, real queues/template/store paths, and exclusively in-process fake backends. */
class LegacyEchoPortRegressionTest {
    private static final String FAMILY = "Maybe now";

    @Test
    void threeConsecutiveValidEchoesKeepAllStylesAndFinishCallers() throws Exception {
        try (Fixture f = new Fixture()) {
            f.learnKeep(false);
            assertEquals(FAMILY, f.t.cached(FAMILY, "zh-TW", false, f.config));
            for (String source : Arrays.asList(FAMILY, "§d" + FAMILY,
                    "⟦CS0⟧Maybe⟦/CS0⟧ ⟦CS1⟧now⟦/CS1⟧")) {
                CompletableFuture<String> done = f.request(source, false, false);
                assertTrue(done.isDone(), "terminal identity is returned without another tick");
                assertEquals(source, done.get());
            }
            f.t.flushBatch();
            assertEquals(3, f.sent.size());
            f.assertIdle();
            assertEquals(0, mapSize(f.t, "failedUntil"));
        }
    }

    @Test
    void theSameExactKeyCanRetryAfterConfiguredBackoffAndStopsAtThree() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 3; i++) {
                String result = f.translate(FAMILY, false, true);
                if (i < 2) {
                    assertNull(result);
                    assertNull(f.t.cached(FAMILY, "zh-TW", false, f.config));
                    Thread.sleep(275L); // the existing legacy minimum backoff is 250 ms
                } else assertEquals(FAMILY, result);
            }
            assertEquals(FAMILY, f.translate(FAMILY, false, true));
            assertEquals(3, f.sent.size());
            f.assertIdle();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"429", "format", "success"})
    void aNonEchoResponseResetsOnlyTheConsecutiveEchoStreak(String reset) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture f = new Fixture((sources, cfg) -> {
            int n = requests.incrementAndGet();
            String source = sources.get(0);
            if (n == 3) {
                if (reset.equals("429")) throw new Exception("HTTP 429 rate limit");
                if (reset.equals("format")) return Collections.singletonList("lost style token");
                return Collections.singletonList(source.replace("Maybe", "也許"));
            }
            return new ArrayList<String>(sources);
        })) {
            for (char color : new char[] {'a', 'b', 'c', 'd', 'e'}) f.translate("§" + color + FAMILY, false, true);
            assertNull(f.t.cached(FAMILY, "zh-TW", false, f.config),
                    "two echoes before and two after a reset cannot become KEEP");
            assertEquals("§f" + FAMILY, f.translate("§f" + FAMILY, false, true));
            assertEquals(FAMILY, f.t.cached(FAMILY, "zh-TW", false, f.config));
            assertEquals(6, requests.get());
            f.assertIdle();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCollectedStyleIsSkippedAfterOtherStylesLearnKeepWithoutLosingOtherFamilies(boolean mixed)
            throws Exception {
        try (Fixture f = new Fixture((sources, cfg) -> {
            List<String> out = new ArrayList<String>();
            for (String source : sources) out.add(source.replace("Other sentence", "別的句子"));
            return out;
        })) {
            CompletableFuture<String> kept = f.request("§d" + FAMILY, false, false);
            CompletableFuture<String> other = mixed ? f.request("Other sentence", false, false) : null;
            f.learnKeep(false); // three high-priority batches leave the low-priority collector intact
            f.t.flushBatch();
            assertEquals("§d" + FAMILY, kept.get(5, TimeUnit.SECONDS));
            if (mixed) assertEquals("別的句子", other.get(5, TimeUnit.SECONDS));
            assertEquals(mixed ? 4 : 3, f.sent.size());
            assertFalse(f.sent.contains("§d" + FAMILY));
            f.assertIdle();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executorQueuedWorkRechecksAnImportedKeepAndCompletesCallbacksOutsideTheLock(boolean mixed)
            throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture f = new Fixture((sources, cfg) -> {
            if (sources.get(0).startsWith("Block")) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            List<String> out = new ArrayList<String>();
            for (String source : sources) out.add(source.replace("Block", "阻擋").replace("Other sentence", "別的句子"));
            return out;
        })) {
            CompletableFuture<String> one = f.request("Block one", false, true); f.t.flushBatch();
            CompletableFuture<String> two = f.request("Block two", false, true); f.t.flushBatch();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<String> kept = new CompletableFuture<String>();
            f.t.translate("§a" + FAMILY, "zh-TW", false, false, f.config, result -> {
                try { assertCallbackUnlocked(f.t); kept.complete(result); }
                catch (Throwable failure) { kept.completeExceptionally(failure); }
            });
            CompletableFuture<String> other = mixed ? f.request("Other sentence", false, false) : null;
            f.t.flushBatch();
            f.importKeep(false);
            release.countDown();
            assertEquals("§a" + FAMILY, kept.get(5, TimeUnit.SECONDS));
            one.get(5, TimeUnit.SECONDS); two.get(5, TimeUnit.SECONDS);
            if (mixed) assertEquals("別的句子", other.get(5, TimeUnit.SECONDS));
            assertFalse(f.sent.contains("§a" + FAMILY));
            assertEquals(mixed ? 3 : 2, f.sent.size());
            f.assertIdle();
        } finally { release.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"echo", "success", "format", "429"})
    void aLateResultCannotUndoKeepRebuildFailureStateOrLoseItsCallback(String late) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture f = new Fixture((sources, cfg) -> {
            String source = sources.get(0);
            if (source.startsWith("§d")) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                if (late.equals("429")) throw new Exception("HTTP 429 rate limit");
                if (late.equals("format")) return Collections.singletonList("missing style marker");
                if (late.equals("success")) return Collections.singletonList(source.replace("Maybe", "也許"));
            }
            return new ArrayList<String>(sources);
        })) {
            CompletableFuture<String> pending = f.request("§d" + FAMILY, false, true);
            f.t.flushBatch();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.learnKeep(false);
            release.countDown();
            assertEquals("§d" + FAMILY, pending.get(5, TimeUnit.SECONDS));
            assertEquals(FAMILY, f.t.cached(FAMILY, "zh-TW", false, f.config));
            assertEquals("§e" + FAMILY, f.t.cached("§e" + FAMILY, "zh-TW", false, f.config));
            assertEquals(0, mapSize(f.t, "failedUntil"));
            assertEquals(0, mapSize(f.t, "identityEchoes"));
            assertEquals(4, f.sent.size());
            f.assertIdle();
        } finally { release.countDown(); }
    }

    @Test
    void anEchoAndUsableSiblingInTheSameBatchCannotUndoTheTerminalDecision() throws Exception {
        try (Fixture f = new Fixture((sources, cfg) -> {
            List<String> out = new ArrayList<String>();
            for (String source : sources) out.add(source.startsWith("§d")
                    ? source.replace("Maybe", "也許") : source);
            return out;
        })) {
            assertNull(f.translate("§a" + FAMILY, false, true));
            assertNull(f.translate("§b" + FAMILY, false, true));
            // Usable style deliberately precedes the third echo in the same response.
            CompletableFuture<String> usable = f.request("§d" + FAMILY, false, false);
            CompletableFuture<String> echo = f.request("§c" + FAMILY, false, false);
            f.t.flushBatch();
            assertEquals("§d" + FAMILY, usable.get(5, TimeUnit.SECONDS));
            assertEquals("§c" + FAMILY, echo.get(5, TimeUnit.SECONDS));
            assertEquals(FAMILY, f.t.cached(FAMILY, "zh-TW", false, f.config));
            f.assertIdle();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneEnginesKeepCannotSuppressTheOtherEngine(boolean learnedByAi) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture f = new Fixture((sources, cfg) -> {
            String source = sources.get(0);
            return Collections.singletonList(requests.incrementAndGet() <= 3
                    ? source : source.replace("Maybe", "也許"));
        })) {
            f.learnKeep(learnedByAi);
            assertEquals("§d也許 now", f.translate("§d" + FAMILY, !learnedByAi, true));
            assertEquals(4, requests.get());
            assertEquals("§d也許 now", f.t.cached("§d" + FAMILY, "zh-TW", false, f.config),
                    "existing AI wording must still answer the machine lookup after a GT KEEP");
            assertEquals(4, requests.get());
            f.assertIdle();
        }
    }

    @Test
    void aMalformedItemBeforeAnEchoInTheSameBatchResetsThePreviousTwoEchoes() throws Exception {
        try (Fixture f = new Fixture((sources, cfg) -> {
            List<String> out = new ArrayList<String>();
            for (String source : sources) out.add(source.startsWith("§d") ? "lost style marker" : source);
            return out;
        })) {
            assertNull(f.translate("§a" + FAMILY, false, true));
            assertNull(f.translate("§b" + FAMILY, false, true));
            CompletableFuture<String> malformed = f.request("§d" + FAMILY, false, false);
            CompletableFuture<String> echo = f.request("§c" + FAMILY, false, false);
            f.t.flushBatch();
            assertNull(malformed.get(5, TimeUnit.SECONDS));
            assertNull(echo.get(5, TimeUnit.SECONDS));
            assertNull(f.t.cached(FAMILY, "zh-TW", false, f.config),
                    "the malformed predecessor resets 2 to 0, so this echo is only 1/3");
            assertNull(f.translate("§e" + FAMILY, false, true));
            assertEquals("§f" + FAMILY, f.translate("§f" + FAMILY, false, true));
            f.assertIdle();
        }
    }

    @Test
    void styledEchoesCannotOverwriteAnAlreadyValidLegacySemanticTranslation() throws Exception {
        try (Fixture f = new Fixture((sources, cfg) -> {
            List<String> out = new ArrayList<String>();
            for (String source : sources) out.add(source.equals(FAMILY) ? "也許 now" : source);
            return out;
        })) {
            assertEquals("也許 now", f.translate(FAMILY, false, true));
            for (char color : new char[] {'a', 'b', 'c'}) {
                assertNull(f.translate("§" + color + FAMILY, false, true),
                        "a style failure cannot redefine a known translated family as KEEP");
                assertEquals("也許 now", f.t.cached(FAMILY, "zh-TW", false, f.config));
            }
            assertEquals("也許 now", f.t.exportTranslations("zh-TW", f.config).machine.get(FAMILY));
            assertEquals(4, f.sent.size());
            f.assertIdle();
        }
    }

    @Test
    void keepSurvivesRealExportSaveLoadAndManualRetranslationUnlocksIt() throws Exception {
        Path directory = Files.createTempDirectory("legacy-echo-keep-");
        try (Fixture first = new Fixture()) {
            first.t.loadSharedTranslations(directory, "zh-TW", first.config);
            first.learnKeep(false);
            TranslationFile exported = first.t.exportTranslations("zh-TW", first.config);
            assertEquals(FAMILY, exported.machine.get(FAMILY), "identity row must be present in the existing schema");
            first.t.importTranslations(exported, "zh-TW", first.config); // existing save path
            assertTrue(Files.isRegularFile(directory.resolve("nyanlex-imported-zh-tw.json")));
            try (Fixture restored = new Fixture((sources, cfg) -> {
                List<String> out = new ArrayList<String>();
                for (String source : sources) out.add(source.replace("Maybe", "也許"));
                return out;
            })) {
                restored.t.loadSharedTranslations(directory, "zh-TW", restored.config);
                assertEquals("§a" + FAMILY, restored.translate("§a" + FAMILY, false, false));
                assertEquals(0, restored.sent.size());
                restored.t.retranslateScreen(Collections.singletonList("§a" + FAMILY), "zh-TW", restored.config);
                CompletableFuture<String> done = restored.request("§a" + FAMILY, false, false);
                restored.t.flushBatch();
                assertEquals("§a也許 now", done.get(5, TimeUnit.SECONDS));
                assertEquals(1, restored.sent.size());
                restored.assertIdle();
            }
        } finally {
            try (java.nio.file.DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) Files.delete(path);
            }
            Files.delete(directory);
        }
    }

    @Test
    void aKeptPacedRequestNeverReachesTheHttpSeam() throws Exception {
        AtomicInteger http = new AtomicInteger();
        LegacyTranslator t = new LegacyTranslator();
        LegacyConfig cfg = config();
        cfg.requestCooldownMs = 1_000;
        cfg.aiApiKeys.add("fake-key");
        t.setAiHttpForTests((text, target, snapshot, key) -> {
            http.incrementAndGet();
            return text.replace("Seed", "種子");
        });
        try {
            CompletableFuture<String> seed = new CompletableFuture<String>();
            t.translate("Seed pacing", "zh-TW", true, true, cfg, seed::complete);
            t.flushBatch();
            assertEquals("種子 pacing", seed.get(5, TimeUnit.SECONDS));
            CompletableFuture<String> kept = new CompletableFuture<String>();
            t.translate("§a" + FAMILY, "zh-TW", true, true, cfg, kept::complete);
            t.flushBatch();
            Thread.sleep(100L);
            assertFalse(kept.isDone(), "the request must be held by the existing pacer");
            t.importTranslations(new TranslationFile("legacy-template-v1", "zh-TW", "google",
                    Collections.emptyMap(), Collections.singletonMap(FAMILY, FAMILY)), "zh-TW", cfg);
            assertEquals("§a" + FAMILY, kept.get(5, TimeUnit.SECONDS));
            assertEquals(1, http.get());
            assertEquals(0, t.inFlightCountForTests());
            assertEquals(0, t.waiterCountForTests());
            assertEquals(0, mapSize(t, "failedUntil"));
        } finally { t.shutdownForTests(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "shedos 飄入了大廳！", "[MVP+] zqdrla 飄入了大廳!",
            ">>> [VIP] abbking 飄入了大廳！ <<<", "⟦MT0⟧ Another_123 飄入了大廳！",
            "§ashe§bdos §e飄入了大廳！",
            "⟦CS0⟧shedos⟦/CS0⟧ ⟦CS1⟧飄入⟦/CS1⟧⟦CS2⟧了大廳！⟦/CS2⟧"
    })
    void recognizedTraditionalArrivalIsCompletedLocally(String source) throws Exception {
        try (Fixture f = new Fixture()) {
            CompletableFuture<String> done = f.request(source, false, false);
            assertTrue(done.isDone(), "localized arrival is already in the target language");
            assertEquals(source, done.get());
            assertEquals(source, f.t.cached(source, "zh-TW", false, f.config));
            f.t.flushBatch();
            assertEquals(0, f.sent.size());
            f.assertIdle();
        }
    }

    static Stream<Arguments> notLocalizedArrivals() {
        List<Arguments> out = new ArrayList<Arguments>();
        for (String source : Arrays.asList("shedos joined the lobby!", "is hypixel down?",
                "10. XandaPandaMC [PANDA] - 31,160", "shedos 飄入了大廳！ ask for help",
                "shedos 飄入了大廳！ https://hypixel.net", "shedos 飄入了大廳！ 123",
                "⟦WS0⟧ shedos 飄入了大廳！", "⟦MT\n0⟧ shedos 飄入了大廳！",
                "⟦CS\n0⟧shedos⟦/CS0⟧ 飄入了大廳！", "shedos\n飄入了大廳！",
                "shedos 飘入了大厅！", ">>> shedos 飄入了大廳！")) out.add(Arguments.of(source, "zh-TW", "auto"));
        out.add(Arguments.of("shedos 飄入了大廳！", "en", "auto"));
        out.add(Arguments.of("shedos 飄入了大廳！", "zh-TW", "ja"));
        out.add(Arguments.of("shedos 飄入了大廳！", "zh-TW", "ko"));
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("notLocalizedArrivals")
    void ordinaryEnglishLeaderboardMixedTextAndOtherLocalesKeepTheirNormalPath(
            String source, String target, String hint) throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.sourceLang = hint;
            CompletableFuture<String> done = new CompletableFuture<String>();
            f.t.translate(source, target, false, false, f.config, done::complete);
            f.t.flushBatch();
            done.get(5, TimeUnit.SECONDS);
            assertEquals(1, f.sent.size(), "new guard must not consume extra content or another locale");
            f.assertIdle();
        }
    }

    private static void assertCallbackUnlocked(LegacyTranslator t) throws Exception {
        FutureTask<Integer> probe = new FutureTask<Integer>(t::inFlightCountForTests);
        Thread reader = new Thread(probe, "legacy-callback-lock-probe");
        reader.setDaemon(true);
        reader.start();
        probe.get(1, TimeUnit.SECONDS); // a callback under flightLock would block this other thread
    }

    private static int mapSize(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(instance)).size();
    }

    private static LegacyConfig config() {
        LegacyConfig c = new LegacyConfig();
        c.enabled = c.translationRequestsEnabled = true;
        c.aiEnabled = false;
        c.disableGoogleFallbackForAi = true;
        c.batchWindowMs = 0;
        c.requestCooldownMs = 0;
        c.failureBackoffMs = 250;
        return c;
    }

    private interface Backend {
        List<String> translate(List<String> sources, LegacyConfig config) throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        final LegacyConfig config = config();
        final LegacyTranslator t;
        Fixture() { this((sources, cfg) -> new ArrayList<String>(sources)); }
        Fixture(final Backend backend) {
            t = new LegacyTranslator(new LegacyTranslator.TestBackend() {
                @Override public List<String> translate(List<String> sources) throws Exception {
                    return translate(sources, config);
                }
                @Override public List<String> translate(List<String> sources, LegacyConfig snapshot) throws Exception {
                    sent.addAll(sources);
                    return backend.translate(sources, snapshot);
                }
            });
        }
        CompletableFuture<String> request(String source, boolean ai, boolean high) {
            CompletableFuture<String> done = new CompletableFuture<String>();
            t.translate(source, "zh-TW", ai, high, config, done::complete);
            return done;
        }
        String translate(String source, boolean ai, boolean high) throws Exception {
            CompletableFuture<String> done = request(source, ai, high);
            t.flushBatch();
            return done.get(5, TimeUnit.SECONDS);
        }
        void learnKeep(boolean ai) throws Exception {
            assertNull(translate("§a" + FAMILY, ai, true));
            assertNull(translate("§b" + FAMILY, ai, true));
            assertEquals("§c" + FAMILY, translate("§c" + FAMILY, ai, true));
        }
        void importKeep(boolean ai) throws Exception {
            Map<String, String> keep = Collections.singletonMap(FAMILY, FAMILY);
            t.importTranslations(new TranslationFile("legacy-template-v1", "zh-TW", "google",
                    ai ? Collections.emptyMap() : keep, ai ? keep : Collections.emptyMap()), "zh-TW", config);
        }
        void assertIdle() throws Exception {
            assertEquals(0, t.inFlightCountForTests());
            assertEquals(0, t.waiterCountForTests());
            assertEquals(0, mapSize(t, "pending"));
        }
        @Override public void close() { t.shutdownForTests(); }
    }
}
