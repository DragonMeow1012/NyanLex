package com.dragonmeow.nyanslate.legacy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Exhaustive Java-8 inline regression for the shared legacy translation core. */
public final class InlineLegacyCoreSimulation {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        testTranslationSharing();
        testTemplateBoundaries();
        testReservedTemplateTokenCollisions();
        testPureDynamicFastPath();
        testThousandWaiterCoalescing();
        testTokenSentinelProtocol();
        testRequestSnapshotAndSemanticKeys();
        testConditionalBatchAndCacheProfiles();
        testFailureCallback();
        testBoundedRetryPolicyAndGeneration();
        testAiHttpAttemptBudget();
        testDisabledCancellation();
        testCancelGenerationInterleaving();
        testWaiterAndFlightCaps();
        testFairDualLaneQueue();
        testQueuedPriorityPromotion();
        testEligibleBatchSelection();
        testChatDeliveryModes();
        testConcurrentChatDelivery();
        testAllChatDeliveryPermutationsAndSwitches();
        testConfigPersistence();
        testChatRequestProfilePolicy();
        testTokenHighWaterAndCap();
        testCodexCompletedFirstGrace();
        testCodexPendingRegistrationRace();
        testCodexInitializationReaderFailureSignal();
        testCodexStaleReaderIsolation();
        testCodexPostResponseGenerationRace();
        testCodexDeadGenerationTakeover();
        testCodexLoginRegistrationRace();
        testCodexStateCapsAndLifecycle();
        testProtocolAndProviderSizeCaps();
        // 1.0.7: master request switch + do-not-translate terms (inline fakes only).
        testRequestSwitchConfigAndProfile();
        caseOk("request-switch config/normalize/snapshot/chat-profile");
        testRequestSwitchCacheOnly();
        caseOk("request-switch hits shown, misses original, zero requests/state, reopen resends");
        testRequestSwitchCancelsQueuedWork();
        caseOk("request-switch cancels batch-queued and executor-queued work without failure");
        testRequestSwitchRechecksAfterPacing();
        caseOk("request-switch re-checks a paced AI request before the wire");
        testRequestSwitchActionsAndRetries();
        caseOk("request-switch screen retranslate no-op, live-gated retry, connection test allowed");
        testDoNotTranslateMatching();
        caseOk("dnt matching rules (case, word boundary, section code, multi-word, url, cjk, whole line)");
        testDoNotTranslateEndToEnd();
        caseOk("dnt placeholder to translator, restored display, AI/machine numeric transport");
        testDoNotTranslateTokenLoss();
        caseOk("dnt lost placeholder shows original, temporary backoff only, retry keeps key");
        testDoNotTranslateCacheLifecycle();
        caseOk("dnt add/remove term cache behaviour and switch-off interplay");
        testSentinelOrderRelaxation();
        caseOk("sentinels order-free within their item; lost/duplicate/glued/cross-item still fail");
        System.out.println("INLINE_LEGACY_REQUEST_SWITCH_DNT_OK cases=10");
        System.out.println("INLINE_LEGACY_CORE_OK x1000=1000/1000 caps=bounded fairness=verified");
    }

    private static void caseOk(String name) {
        System.out.println("LEGACY_1_0_7_CASE_OK " + name);
    }

    private static void testTranslationSharing() throws Exception {
        LegacyTranslator translator = new LegacyTranslator(sources -> {
            throw new AssertionError("Import must not call a translation backend");
        });
        LegacyConfig config = new LegacyConfig();
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("translation-sharing-");
        try {
            translator.loadSharedTranslations(dir, "zh-TW", config);
            com.dragonmeow.nyanslate.translate.TranslationFile first = new com.dragonmeow.nyanslate.translate.TranslationFile(
                    "legacy-template-v1", "zh-TW", "google", Collections.singletonMap("Hello", "你好"), Collections.emptyMap());
            check(translator.importTranslations(first, "zh-TW", config) == 1, "import count");
            check(translator.importTranslations(first, "zh-TW", config) == 0, "duplicate import");
            check("你好".equals(translator.cached("Hello", "zh-TW", false, config)), "import not reusable");
            Map<String,String> oversized = new java.util.LinkedHashMap<String,String>();
            for (int i=0;i<8193;i++) oversized.put("Entry " + i, "翻譯 " + i);
            try {
                translator.importTranslations(new com.dragonmeow.nyanslate.translate.TranslationFile(
                        "legacy-template-v1", "zh-TW", "google", oversized, Collections.emptyMap()), "zh-TW", config);
                throw new AssertionError("capacity overflow accepted");
            } catch (IOException expected) { }
            check(translator.exportTranslations("zh-TW", config).machine.size() == 1, "overflow partially mutated cache");
            LegacyTranslator restored = new LegacyTranslator(sources -> {throw new AssertionError("network");});
            try {
                restored.loadSharedTranslations(dir, "zh-TW", config);
                check("你好".equals(restored.cached("Hello", "zh-TW", false, config)), "import did not survive restart");
            } finally { restored.shutdownForTests(); }
        } finally {
            translator.shutdownForTests();
            try (java.nio.file.DirectoryStream<java.nio.file.Path> files = java.nio.file.Files.newDirectoryStream(dir)) {
                for (java.nio.file.Path file : files) java.nio.file.Files.delete(file);
            }
            java.nio.file.Files.delete(dir);
        }
    }

    private static void testTemplateBoundaries() {
        LegacyTemplateText.Prepared lower = LegacyTemplateText.prepare("Reward x100");
        LegacyTemplateText.Prepared upper = LegacyTemplateText.prepare("Reward X100");
        check("Reward ⟦MT0⟧".equals(lower.text()), "lowercase x quantity was not templated");
        check(lower.text().equals(upper.text()), "x/X quantities do not share a canonical key");
        check("獎勵 x100".equals(lower.restore("獎勵 ⟦MT0⟧")), "x quantity restore failed");
        check("獎勵 X100".equals(upper.restore("獎勵 ⟦MT0⟧")), "X quantity restore failed");
        check(!LegacyTemplateText.prepare("x100").hasTranslatableContent(),
                "placeholder-only x100 reported static translatable content");
        check(!LegacyTemplateText.prepare("§ax100").hasTranslatableContent(),
                "format-code plus placeholder reported translatable content");
        check("2x2".equals(LegacyTemplateText.prepare("2x2").text()), "dimension 2x2 was templated");
        check("0x1F".equals(LegacyTemplateText.prepare("0x1F").text()), "hex 0x1F was templated");
        check("ax100".equals(LegacyTemplateText.prepare("ax100").text()), "glued identifier was templated");
        check("x100kg".equals(LegacyTemplateText.prepare("x100kg").text()), "unit identifier was templated");
        check("Reward ⟦MT0⟧!".equals(LegacyTemplateText.prepare("Reward x100!").text()),
                "punctuated x quantity was not templated");
        for (String variant : Arrays.asList(
                "x100", "X100", "x1,000", "x1.5", "x5%", "x2k", "X3M")) {
            LegacyTemplateText.Prepared prepared = LegacyTemplateText.prepare("Reward " + variant);
            check("Reward ⟦MT0⟧".equals(prepared.text()),
                    "quantity variant did not share canonical form: " + variant);
            check(("獎勵 " + variant).equals(prepared.restore("獎勵 ⟦MT0⟧")),
                    "quantity variant did not restore exactly: " + variant);
        }
        for (String boundary : Arrays.asList(
                "1920x1080", "x100foo", "x100xp", "_x100", "box100")) {
            check(!LegacyTemplateText.prepare(boundary).changed(),
                    "quantity matcher damaged boundary: " + boundary);
        }
    }

    private static void testReservedTemplateTokenCollisions() {
        String open = "\u27E6";
        String close = "\u27E7";
        LegacyTemplateText.Prepared literalBracket =
                LegacyTemplateText.prepare("Say " + open + "HELLO" + close);
        check(literalBracket.hasTranslatableContent(),
                "literal non-protocol bracket text was erased from content detection");

        String literalMt0 = open + "MT0" + close;
        String generatedMt1 = open + "MT1" + close;
        LegacyTemplateText.Prepared reserved =
                LegacyTemplateText.prepare("Reward " + literalMt0 + " x100");
        check(("Reward " + literalMt0 + " " + generatedMt1).equals(reserved.text()),
                "generated template slot collided with a literal reserved MT index");
        check(("Prize " + literalMt0 + " x100").equals(
                        reserved.restore("Prize " + literalMt0 + " " + generatedMt1)),
                "restore replaced a literal reserved MT token");
        check(!LegacyTemplateText.prepare(literalMt0 + " x100").hasTranslatableContent(),
                "protocol placeholders alone reported translatable static content");
    }

    private static void testPureDynamicFastPath() {
        final AtomicInteger backendCalls = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                backendCalls.incrementAndGet();
                return canonicalSources;
            }
        });
        try {
            LegacyConfig config = config();
            final AtomicReference<String> callback = new AtomicReference<String>();
            translator.translate("x100", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) { callback.set(value); }
                    });
            check("x100".equals(callback.get()), "pure x100 did not callback original immediately");
            translator.prefetch("X999", "zh-TW", false, false, config);
            translator.flushBatch();
            check(backendCalls.get() == 0, "pure dynamic translate/prefetch reached backend");
            check(translator.inFlightCountForTests() == 0, "pure dynamic request created a flight");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void testThousandWaiterCoalescing() throws Exception {
        final AtomicInteger backendCalls = new AtomicInteger();
        final AtomicInteger backendItems = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                backendCalls.incrementAndGet();
                backendItems.addAndGet(canonicalSources.size());
                List<String> output = new ArrayList<String>(canonicalSources.size());
                for (String source : canonicalSources) output.add(source.replace("Reward", "獎勵"));
                return output;
            }
        });
        try {
            final String[] results = new String[1000];
            final CountDownLatch callbacks = new CountDownLatch(1000);
            LegacyConfig config = config();
            for (int i = 1; i <= 1000; i++) {
                final int index = i - 1;
                translator.translate("Reward x" + i, "zh-TW", false, false, config,
                        new Consumer<String>() {
                            @Override public void accept(String value) {
                                results[index] = value;
                                callbacks.countDown();
                            }
                        });
            }
            check(translator.inFlightCountForTests() == 1, "x1..x1000 created multiple flights");
            check(translator.waiterCountForTests() == 1000, "x1..x1000 waiter count mismatch");
            translator.flushBatch();
            check(callbacks.await(10L, TimeUnit.SECONDS), "x1..x1000 callbacks timed out");
            for (int i = 1; i <= 1000; i++) {
                check(("獎勵 x" + i).equals(results[i - 1]), "wrong restore for x" + i + ": " + results[i - 1]);
            }
            check(backendCalls.get() == 1 && backendItems.get() == 1,
                    "x1..x1000 did not coalesce to exactly one backend item");
            check("獎勵 x1001".equals(translator.cached("Reward x1001", "zh-TW", false, config)),
                    "canonical cache did not restore an unseen quantity");
            check(backendCalls.get() == 1, "cache restore caused another backend request");
            for (String variant : Arrays.asList(
                    "Reward X100", "Reward x1,000", "Reward x1.5",
                    "Reward x5%", "Reward x2k", "Reward X3M")) {
                check(("獎勵 " + variant.substring("Reward ".length())).equals(
                                translator.cached(variant, "zh-TW", false, config)),
                        variant + " did not restore from the same canonical cache entry");
                check(backendCalls.get() == 1, variant + " caused a duplicate request");
            }
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void testTokenSentinelProtocol() throws Exception {
        Method encode = LegacyTranslator.class.getDeclaredMethod("encodeTemplateTokens", List.class);
        encode.setAccessible(true);

        Object single = encode.invoke(null, Collections.singletonList("Reward ⟦MT0⟧"));
        check(encodedTexts(single).equals(Collections.singletonList("Reward 30001")),
                "single token did not use numeric transport sentinel");
        check("獎勵 ⟦MT0⟧".equals(decode(single, "獎勵 30001")),
                "single sentinel did not strictly restore MT token");

        Object multiple = encode.invoke(null, Arrays.asList("A ⟦MT0⟧", "B ⟦MT0⟧ ⟦MT1⟧"));
        check(encodedTexts(multiple).equals(Arrays.asList("A 30001", "B 30002 30003")),
                "multi-token sentinels were not unique across batch items");
        check("甲 ⟦MT0⟧\n乙 ⟦MT0⟧ ⟦MT1⟧".equals(
                        decode(multiple, "甲 30001\n乙 30002 30003")),
                "multi-token sentinel restore failed");
        expectDecodeFailure(single, "獎勵");
        expectDecodeFailure(single, "獎勵 30001 30001");

        expectDecodeFailure(single, "130001");
        expectDecodeFailure(single, "300010");
        expectDecodeFailure(multiple, "30002 30001 30003");
        expectDecodeFailure(single, repeatMarker("30001", 10_000));
        check(("literal 130001 and 300010 then \u27E6MT0\u27E7").equals(
                        decode(single, "literal 130001 and 300010 then 30001")),
                "digit substrings were mistaken for complete sentinels");

        Object adjacent = encode.invoke(null, Collections.singletonList(
                "\u27E6MT0\u27E7\u27E6MT1\u27E7"));
        check(encodedTexts(adjacent).equals(Collections.singletonList("3000130002")),
                "adjacent template slots were not encoded adjacently");
        check("\u27E6MT0\u27E7\u27E6MT1\u27E7".equals(decode(adjacent, "3000130002")),
                "maximal numeric run did not decode adjacent template slots");

        Object edgeSlots = encode.invoke(null, Arrays.asList(
                "\u27E6MT0\u27E7Hello\u27E6MT1\u27E7", "\u27E6MT2\u27E7"));
        Method buildWire = LegacyTranslator.class.getDeclaredMethod("buildBatchWire", List.class);
        buildWire.setAccessible(true);
        Object wire = buildWire.invoke(null, encodedTexts(edgeSlots));
        String wireText = (String) reflected(wire, "text");
        int anchorBase = ((Integer) reflected(wire, "anchorBase")).intValue();
        @SuppressWarnings("unchecked")
        List<String> sentinels = (List<String>) invokeNoArgs(edgeSlots, "sentinels");
        Method split = LegacyTranslator.class.getDeclaredMethod("splitBatchEncoded",
                String.class, int.class, int.class, List.class);
        split.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> splitItems = (List<String>) split.invoke(null,
                wireText.replace("\n", ""), 2, anchorBase, sentinels);
        check(splitItems.equals(encodedTexts(edgeSlots)),
                "adjacent anchor/token or close/open runs were not split exactly");
        @SuppressWarnings("unchecked")
        List<String> decodedEdges = (List<String>) invoke(edgeSlots, "decodeItems",
                new Class<?>[]{List.class}, new Object[]{splitItems});
        check(decodedEdges.equals(Arrays.asList(
                        "\u27E6MT0\u27E7Hello\u27E6MT1\u27E7", "\u27E6MT2\u27E7")),
                "beginning/end/adjacent token batch round-trip failed");

        Object collision = encode.invoke(null,
                Collections.singletonList("Literal 30001 and ⟦MT0⟧"));
        check(encodedTexts(collision).equals(
                        Collections.singletonList("Literal 30001 and 32001")),
                "sentinel base did not move away from a source collision");
        check("文字 30001 和 ⟦MT0⟧".equals(decode(collision, "文字 30001 和 32001")),
                "collision-safe sentinel restore failed");

        LegacyTranslator malformed = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                return Collections.singletonList("壞掉");
            }
        });
        try {
            final CountDownLatch callback = new CountDownLatch(1);
            final AtomicReference<String> value = new AtomicReference<String>("not-called");
            LegacyConfig config = config();
            malformed.translate("Sentinel x1", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String result) {
                            value.set(result);
                            callback.countDown();
                        }
                    });
            malformed.flushBatch();
            check(callback.await(10L, TimeUnit.SECONDS), "malformed-token callback timed out");
            check(value.get() == null, "malformed token result was accepted");
            check(malformed.cached("Sentinel x2", "zh-TW", false, config) == null,
                    "malformed token result poisoned canonical cache");
        } finally {
            malformed.shutdownForTests();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> encodedTexts(Object tokenBatch) throws Exception {
        Field texts = tokenBatch.getClass().getDeclaredField("texts");
        texts.setAccessible(true);
        return (List<String>) texts.get(tokenBatch);
    }

    private static String decode(Object tokenBatch, String value) throws Exception {
        Method decode = tokenBatch.getClass().getDeclaredMethod("decode", String.class);
        decode.setAccessible(true);
        return (String) decode.invoke(tokenBatch, value);
    }

    private static void expectDecodeFailure(Object tokenBatch, String value) throws Exception {
        try {
            decode(tokenBatch, value);
            throw new AssertionError("invalid sentinel response was accepted: " + value);
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException,
                    "invalid sentinel threw wrong failure: " + expected.getCause());
        }
    }

    private static void testRequestSnapshotAndSemanticKeys() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> captured = new AtomicReference<String>();
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                throw new AssertionError("snapshot-aware backend overload was bypassed");
            }

            @Override public List<String> translate(List<String> canonicalSources,
                                                    LegacyConfig requestConfig) {
                calls.incrementAndGet();
                captured.set(requestConfig.sourceLang + "|"
                        + requestConfig.machineTranslationProvider + "|"
                        + requestConfig.aiModel + "|" + requestConfig.requestCooldownMs + "|"
                        + requestConfig.disableGoogleFallbackForAi);
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) {
                    String translated = source.replace("Snapshot", "Translated");
                    output.add(translated.equals(source) ? "Translated " + source : translated);
                }
                return output;
            }
        });
        try {
            final CountDownLatch oldDone = new CountDownLatch(1);
            LegacyConfig mutable = config();
            mutable.sourceLang = "en";
            mutable.machineTranslationProvider = "google";
            mutable.aiModel = "old-model";
            mutable.requestCooldownMs = 17;
            mutable.disableGoogleFallbackForAi = false;
            translator.translate("Snapshot x1", "zh-TW", false, false, mutable,
                    new Consumer<String>() {
                        @Override public void accept(String value) { oldDone.countDown(); }
                    });
            mutable.sourceLang = "fr";
            mutable.machineTranslationProvider = "deepl";
            mutable.aiModel = "new-model";
            mutable.requestCooldownMs = 99;
            mutable.disableGoogleFallbackForAi = true;
            translator.flushBatch();
            check(oldDone.await(10L, TimeUnit.SECONDS), "snapshot request timed out");
            check("en|google|old-model|17|false".equals(captured.get()),
                    "queued request observed later mutable config: " + captured.get());
            check(translator.cached("Snapshot x2", "zh-TW", false, mutable) == null,
                    "old source/provider cache entry poisoned the new semantic key");

            final CountDownLatch newDone = new CountDownLatch(1);
            translator.translate("Snapshot x2", "zh-TW", false, false, mutable,
                    new Consumer<String>() {
                        @Override public void accept(String value) { newDone.countDown(); }
                    });
            translator.flushBatch();
            check(newDone.await(10L, TimeUnit.SECONDS) && calls.get() == 2,
                    "new semantic profile incorrectly reused old cache/in-flight state");

            LegacyConfig aiFallback = config();
            aiFallback.sourceLang = "en";
            aiFallback.machineTranslationProvider = "google";
            aiFallback.disableGoogleFallbackForAi = false;
            final CountDownLatch aiDone = new CountDownLatch(1);
            translator.translate("AI profile x1", "zh-TW", true, false, aiFallback,
                    new Consumer<String>() {
                        @Override public void accept(String value) { aiDone.countDown(); }
                    });
            translator.flushBatch();
            check(aiDone.await(10L, TimeUnit.SECONDS), "AI profile request timed out");
            check(translator.cached("AI profile x2", "zh-TW", true, aiFallback) != null,
                    "AI fallback profile did not populate its own cache key");
            LegacyConfig aiStrict = aiFallback.snapshotForRequest();
            aiStrict.disableGoogleFallbackForAi = true;
            check(translator.cached("AI profile x2", "zh-TW", true, aiStrict) == null,
                    "fallback result polluted strict-AI cache semantics");
            LegacyConfig otherSource = aiFallback.snapshotForRequest();
            otherSource.sourceLang = "ja";
            check(translator.cached("AI profile x2", "zh-TW", true, otherSource) == null,
                    "AI cache key omitted source language");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void testConditionalBatchAndCacheProfiles() throws Exception {
        final AtomicInteger backendCalls = new AtomicInteger();
        final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<Integer>());
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                backendCalls.incrementAndGet();
                batchSizes.add(Integer.valueOf(canonicalSources.size()));
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add("T " + source);
                return output;
            }
        });
        try {
            LegacyConfig machine = config();
            machine.requestCooldownMs = 0;
            machine.batchWindowMs = 0;
            final CountDownLatch machineDone = new CountDownLatch(2);
            translator.translate("Machine alpha", "zh-TW", false, false, machine,
                    countDownConsumer(machineDone));
            machine.aiBaseUrl = "https://inactive.invalid/v1";
            machine.aiModel = "inactive-model";
            machine.aiUseCodex = true;
            machine.codexModel = "inactive-codex";
            machine.codexReasoningEffort = "high";
            machine.disableGoogleFallbackForAi = true;
            machine.aiApiKeys.add("inactive-key");
            machine.aiKeysByEndpoint.put("inactive", "inactive-key");
            translator.translate("Machine beta", "zh-TW", false, false, machine,
                    countDownConsumer(machineDone));
            translator.flushBatch();
            check(machineDone.await(10L, TimeUnit.SECONDS)
                            && backendCalls.get() == 1 && batchSizes.get(0).intValue() == 2,
                    "inactive AI settings split one machine-translation batch");

            LegacyConfig strictApi = config();
            strictApi.requestCooldownMs = 0;
            strictApi.batchWindowMs = 0;
            strictApi.disableGoogleFallbackForAi = true;
            final CountDownLatch strictApiFirst = new CountDownLatch(1);
            translator.translate("Strict API x1", "zh-TW", true, false, strictApi,
                    countDownConsumer(strictApiFirst));
            translator.flushBatch();
            check(strictApiFirst.await(10L, TimeUnit.SECONDS), "strict API seed timed out");
            int afterStrictApi = backendCalls.get();
            strictApi.sourceLang = "ja";
            strictApi.machineTranslationProvider = "deepl";
            final CountDownLatch strictApiHit = new CountDownLatch(1);
            translator.translate("Strict API x2", "zh-TW", true, false, strictApi,
                    countDownConsumer(strictApiHit));
            check(strictApiHit.await(1L, TimeUnit.SECONDS)
                            && backendCalls.get() == afterStrictApi,
                    "strict API cache key included inactive machine settings");

            LegacyConfig strictCodex = strictApi.snapshotForRequest();
            strictCodex.aiUseCodex = true;
            strictCodex.codexModel = "gpt-5.6-terra";
            final CountDownLatch strictCodexFirst = new CountDownLatch(1);
            translator.translate("Strict Codex x1", "zh-TW", true, false, strictCodex,
                    countDownConsumer(strictCodexFirst));
            translator.flushBatch();
            check(strictCodexFirst.await(10L, TimeUnit.SECONDS), "strict Codex seed timed out");
            int afterStrictCodex = backendCalls.get();
            strictCodex.sourceLang = "ko";
            strictCodex.machineTranslationProvider = "microsoft";
            final CountDownLatch strictCodexHit = new CountDownLatch(1);
            translator.translate("Strict Codex x2", "zh-TW", true, false, strictCodex,
                    countDownConsumer(strictCodexHit));
            check(strictCodexHit.await(1L, TimeUnit.SECONDS)
                            && backendCalls.get() == afterStrictCodex,
                    "strict Codex cache key included inactive machine settings");

            LegacyConfig fallback = config();
            fallback.requestCooldownMs = 0;
            fallback.batchWindowMs = 0;
            fallback.disableGoogleFallbackForAi = false;
            fallback.sourceLang = "en";
            fallback.machineTranslationProvider = "google";
            final CountDownLatch fallbackFirst = new CountDownLatch(1);
            translator.translate("Fallback AI x1", "zh-TW", true, false, fallback,
                    countDownConsumer(fallbackFirst));
            translator.flushBatch();
            check(fallbackFirst.await(10L, TimeUnit.SECONDS), "fallback AI seed timed out");
            int beforeFallbackChange = backendCalls.get();
            fallback.sourceLang = "fr";
            fallback.machineTranslationProvider = "deepl";
            final CountDownLatch fallbackChanged = new CountDownLatch(1);
            translator.translate("Fallback AI x2", "zh-TW", true, false, fallback,
                    countDownConsumer(fallbackChanged));
            translator.flushBatch();
            check(fallbackChanged.await(10L, TimeUnit.SECONDS)
                            && backendCalls.get() == beforeFallbackChange + 1,
                    "fallback-enabled AI cache omitted active machine settings");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static Consumer<String> countDownConsumer(final CountDownLatch latch) {
        return new Consumer<String>() {
            @Override public void accept(String value) { latch.countDown(); }
        };
    }

    private static void testFailureCallback() throws Exception {
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                throw new Exception("expected backend failure");
            }
        });
        try {
            final CountDownLatch callback = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<String>("not-called");
            translator.translate("Failure x1", "zh-TW", false, false, config(),
                    new Consumer<String>() {
                        @Override public void accept(String value) {
                            result.set(value);
                            callback.countDown();
                        }
                    });
            translator.flushBatch();
            check(callback.await(10L, TimeUnit.SECONDS), "failure callback was swallowed");
            check(result.get() == null, "failure callback did not receive null");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void testBoundedRetryPolicyAndGeneration() throws Exception {
        final AtomicInteger transientCalls = new AtomicInteger();
        LegacyTranslator transientFailure = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                transientCalls.incrementAndGet();
                throw new Exception("HTTP 503 temporary outage");
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            final CountDownLatch callback = new CountDownLatch(1);
            transientFailure.translate("Transient x1", "zh-TW", true, false, strict,
                    new Consumer<String>() {
                        @Override public void accept(String value) { callback.countDown(); }
                    });
            transientFailure.flushBatch();
            check(callback.await(10L, TimeUnit.SECONDS), "transient failure callback timed out");
            flushUntil(transientFailure, transientCalls, 2, 1500L);
            check(transientCalls.get() == 2, "transient failure did not retry exactly once");
            long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(450L);
            while (System.nanoTime() < settle) {
                transientFailure.flushBatch();
                Thread.sleep(10L);
            }
            check(transientCalls.get() == 2, "transient retry formed an unbounded retry chain");
        } finally {
            transientFailure.shutdownForTests();
        }

        final AtomicInteger permanentCalls = new AtomicInteger();
        LegacyTranslator permanent = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                permanentCalls.incrementAndGet();
                throw new Exception("HTTP 401 invalid API key authentication");
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            permanent.translate("Permanent x1", "zh-TW", true, false, strict,
                    new Consumer<String>() { @Override public void accept(String value) {} });
            permanent.flushBatch();
            Thread.sleep(600L);
            permanent.flushBatch();
            check(permanentCalls.get() == 1, "permanent authentication failure auto-retried");
        } finally {
            permanent.shutdownForTests();
        }

        final AtomicInteger cancelledCalls = new AtomicInteger();
        LegacyTranslator cancelled = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                cancelledCalls.incrementAndGet();
                throw new Exception("timeout/network");
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            final CountDownLatch failed = new CountDownLatch(1);
            cancelled.translate("Generation x1", "zh-TW", true, false, strict,
                    new Consumer<String>() {
                        @Override public void accept(String value) { failed.countDown(); }
                    });
            cancelled.flushBatch();
            check(failed.await(10L, TimeUnit.SECONDS), "generation failure did not finish");
            cancelled.cancelPending();
            Thread.sleep(350L);
            cancelled.flushBatch();
            check(cancelledCalls.get() == 1,
                    "cancelled request generation dispatched its scheduled retry");
        } finally {
            cancelled.shutdownForTests();
        }

        final AtomicInteger staleCalls = new AtomicInteger();
        LegacyTranslator staleBackoff = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                staleCalls.incrementAndGet();
                throw new Exception("HTTP 503");
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            final CountDownLatch failed = new CountDownLatch(1);
            staleBackoff.translate("Stale x1", "zh-TW", true, false, strict,
                    new Consumer<String>() {
                        @Override public void accept(String value) { failed.countDown(); }
                    });
            staleBackoff.flushBatch();
            check(failed.await(10L, TimeUnit.SECONDS), "stale-backoff setup failed");
            @SuppressWarnings("unchecked")
            Map<String, Object> backoffs = (Map<String, Object>) reflected(staleBackoff, "failedUntil");
            check(backoffs.size() == 1, "failure backoff was not installed atomically");
            String key = backoffs.keySet().iterator().next();
            Class<?> backoffType = Class.forName(
                    "com.dragonmeow.nyanslate.legacy.LegacyTranslator$FailureBackoff");
            java.lang.reflect.Constructor<?> constructor =
                    backoffType.getDeclaredConstructor(long.class, long.class);
            constructor.setAccessible(true);
            Object newer = constructor.newInstance(System.currentTimeMillis() + 60_000L, 999_999L);
            backoffs.put(key, newer);
            Thread.sleep(350L);
            staleBackoff.flushBatch();
            check(backoffs.get(key) == newer && staleCalls.get() == 1,
                    "stale retry removed a newer backoff generation");
        } finally {
            staleBackoff.shutdownForTests();
        }
    }

    private static void flushUntil(LegacyTranslator translator, AtomicInteger counter,
                                   int expected, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (counter.get() < expected && System.nanoTime() < deadline) {
            translator.flushBatch();
            Thread.sleep(10L);
        }
    }

    private static void testAiHttpAttemptBudget() throws Exception {
        assertPermanentAiBudget("authentication", new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) throws Exception {
                throw new Exception("HTTP 401 invalid API key authentication");
            }
        });
        assertPermanentAiBudget("empty", new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) {
                return "";
            }
        });
        assertPermanentAiBudget("format", new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) {
                return text.replace("30001", "");
            }
        });
        assertPermanentAiBudget("unknown", new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) throws Exception {
                throw new Exception("mystery provider failure");
            }
        });
        assertTransientAiBudget("HTTP 429 rate limit", "rate");
        assertTransientAiBudget("HTTP 503 service unavailable", "server");
        assertTransientAiBudget("timeout/network", "network");
    }

    private static void assertPermanentAiBudget(final String label,
                                                final LegacyTranslator.TestAiHttp behavior)
            throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator();
        translator.setAiHttpForTests(new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) throws Exception {
                calls.incrementAndGet();
                return behavior.request(text, target, requestConfig, apiKey);
            }
        });
        try {
            LegacyConfig config = aiBudgetConfig();
            final CountDownLatch callback = new CountDownLatch(1);
            translator.translate("Permanent " + label + " x1", "zh-TW", true, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) { callback.countDown(); }
                    });
            translator.flushBatch();
            check(callback.await(10L, TimeUnit.SECONDS), label + " AI callback timed out");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600L);
            while (System.nanoTime() < deadline) {
                translator.flushBatch();
                Thread.sleep(10L);
            }
            check(calls.get() == 1,
                    label + " failure multiplied across the configured 64 API keys");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void assertTransientAiBudget(final String failure, final String label)
            throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator();
        translator.setAiHttpForTests(new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) throws Exception {
                if (calls.incrementAndGet() == 1) throw new Exception(failure);
                return text.replace("Transient", "Translated");
            }
        });
        try {
            LegacyConfig config = aiBudgetConfig();
            final CountDownLatch callback = new CountDownLatch(1);
            final AtomicReference<String> callbackValue = new AtomicReference<String>("not-called");
            translator.translate("Transient " + label + " x1", "zh-TW", true, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) {
                            callbackValue.set(value);
                            callback.countDown();
                        }
                    });
            translator.flushBatch();
            check(callback.await(10L, TimeUnit.SECONDS) && callbackValue.get() == null,
                    label + " initial failure did not fail subscribers promptly");
            flushUntil(translator, calls, 2, 1500L);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(450L);
            String cached = null;
            while (System.nanoTime() < deadline) {
                translator.flushBatch();
                cached = translator.cached("Transient " + label + " x2",
                        "zh-TW", true, config);
                if (cached != null) break;
                Thread.sleep(10L);
            }
            check(calls.get() == 2 && cached != null,
                    label + " did not use exactly one cache-warm transient retry");
            Thread.sleep(350L);
            translator.flushBatch();
            check(calls.get() == 2, label + " retry multiplied across API keys/outer retry");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static LegacyConfig aiBudgetConfig() {
        LegacyConfig config = config();
        config.disableGoogleFallbackForAi = true;
        for (int i = 0; i < 64; i++) config.aiApiKeys.add("key-" + i);
        return config;
    }

    private static void testDisabledCancellation() throws Exception {
        final AtomicInteger backendCalls = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                backendCalls.incrementAndGet();
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add(source.replace("Cancel", "完成"));
                return output;
            }
        });
        try {
            final CountDownLatch cancelled = new CountDownLatch(1000);
            final AtomicInteger nullCallbacks = new AtomicInteger();
            final LegacyConfig config = config();
            for (int i = 1; i <= 1000; i++) {
                translator.translate("Cancel x" + i, "zh-TW", false, false, config,
                        new Consumer<String>() {
                            @Override public void accept(String value) {
                                if (value == null) nullCallbacks.incrementAndGet();
                                cancelled.countDown();
                            }
                        });
            }
            config.enabled = false;
            translator.cancelPending();
            check(cancelled.await(1L, TimeUnit.SECONDS),
                    "disabled cancellation did not callback every waiter");
            check(nullCallbacks.get() == 1000, "disabled cancellation returned a translation");
            check(translator.inFlightCountForTests() == 0
                            && translator.waiterCountForTests() == 0,
                    "disabled cancellation leaked flights/waiters");
            translator.flushBatch();
            Thread.sleep(50L);
            check(backendCalls.get() == 0, "disabled queued request reached backend");

            config.enabled = true;
            final CountDownLatch resumed = new CountDownLatch(1);
            final AtomicReference<String> resumedValue = new AtomicReference<String>();
            translator.translate("Cancel x1001", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) {
                            resumedValue.set(value);
                            resumed.countDown();
                        }
                    });
            translator.flushBatch();
            check(resumed.await(10L, TimeUnit.SECONDS), "re-enabled request did not finish");
            check("完成 x1001".equals(resumedValue.get()) && backendCalls.get() == 1,
                    "old cancelled flight leaked into re-enabled request");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void testCancelGenerationInterleaving() throws Exception {
        final AtomicInteger backendCalls = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                backendCalls.incrementAndGet();
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) {
                    output.add(source.replace("Generation", "Renewed"));
                }
                return output;
            }
        });
        final CountDownLatch detached = new CountDownLatch(1);
        final CountDownLatch releaseCancel = new CountDownLatch(1);
        translator.setCancelDetachedHookForTests(new Runnable() {
            @Override public void run() {
                detached.countDown();
                try {
                    check(releaseCancel.await(5L, TimeUnit.SECONDS),
                            "new generation did not submit during cancel barrier");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
            }
        });
        try {
            final LegacyConfig config = config();
            final CountDownLatch oldDone = new CountDownLatch(1);
            final AtomicReference<String> oldValue = new AtomicReference<String>("missing");
            translator.translate("Generation x1", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) {
                            oldValue.set(value);
                            oldDone.countDown();
                        }
                    });

            Thread cancelling = new Thread(new Runnable() {
                @Override public void run() { translator.cancelPending(); }
            }, "inline-legacy-cancel-generation");
            cancelling.start();
            check(detached.await(5L, TimeUnit.SECONDS),
                    "cancel did not detach its old flight");

            final CountDownLatch newDone = new CountDownLatch(1);
            final AtomicReference<String> newValue = new AtomicReference<String>();
            translator.translate("Generation x1", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) {
                            newValue.set(value);
                            newDone.countDown();
                        }
                    });
            check(translator.inFlightCountForTests() == 1
                            && translator.waiterCountForTests() == 1,
                    "new request joined the detached cancelled flight");
            releaseCancel.countDown();
            cancelling.join(5000L);
            check(oldDone.await(1L, TimeUnit.SECONDS) && oldValue.get() == null,
                    "old generation waiter was not cancelled exactly once");

            translator.flushBatch();
            check(newDone.await(10L, TimeUnit.SECONDS)
                            && "Renewed x1".equals(newValue.get())
                            && backendCalls.get() == 1,
                    "new generation request was cancelled or not dispatched independently");
        } finally {
            releaseCancel.countDown();
            translator.setCancelDetachedHookForTests(null);
            translator.shutdownForTests();
        }
    }

    private static void testWaiterAndFlightCaps() throws Exception {
        LegacyTranslator perKey = successfulTranslator();
        try {
            final CountDownLatch accepted = new CountDownLatch(2048);
            LegacyConfig config = config();
            for (int i = 0; i < 2048; i++) {
                perKey.translate("Capacity x1", "zh-TW", false, false, config,
                        new Consumer<String>() {
                            @Override public void accept(String value) { accepted.countDown(); }
                        });
            }
            final AtomicReference<String> overflow = new AtomicReference<String>("not-called");
            perKey.translate("Capacity x2", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) { overflow.set(value); }
                    });
            check(overflow.get() == null, "per-key overflow was not rejected immediately");
            check(perKey.waiterCountForTests() == 2048, "per-key waiter hard cap mismatch");
            perKey.flushBatch();
            check(accepted.await(10L, TimeUnit.SECONDS), "accepted per-key waiters did not finish");
        } finally {
            perKey.shutdownForTests();
        }

        LegacyTranslator flights = successfulTranslator();
        try {
            LegacyConfig config = config();
            for (int i = 0; i < 512; i++) {
                flights.prefetch("Unique " + letters(i), "zh-TW", false, false, config);
            }
            final AtomicReference<String> overflow = new AtomicReference<String>("not-called");
            flights.translate("Unique overflow", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) { overflow.set(value); }
                    });
            check(flights.inFlightCountForTests() == 512, "flight hard cap mismatch");
            check(overflow.get() == null, "flight overflow was not rejected immediately");
        } finally {
            flights.shutdownForTests();
        }

        LegacyTranslator global = successfulTranslator();
        try {
            LegacyConfig config = config();
            Consumer<String> ignored = new Consumer<String>() {
                @Override public void accept(String value) {}
            };
            for (int key = 0; key < 512; key++) {
                String source = "Global " + letters(key);
                for (int waiter = 0; waiter < 16; waiter++) {
                    global.translate(source, "zh-TW", false, false, config, ignored);
                }
            }
            final AtomicReference<String> overflow = new AtomicReference<String>("not-called");
            global.translate("Global " + letters(0), "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) { overflow.set(value); }
                    });
            check(global.waiterCountForTests() == 8192, "global waiter hard cap mismatch");
            check(overflow.get() == null, "global waiter overflow was not rejected immediately");
        } finally {
            global.shutdownForTests();
        }
    }

    private static LegacyTranslator successfulTranslator() {
        return new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                List<String> output = new ArrayList<String>(canonicalSources.size());
                for (String source : canonicalSources) output.add("譯 " + source);
                return output;
            }
        });
    }

    private static void testFairDualLaneQueue() {
        LegacyTranslator.FairTaskQueue queue = new LegacyTranslator.FairTaskQueue(5);
        final List<String> ran = new ArrayList<String>();
        queue.offer(task(false, "L1", ran));
        queue.offer(task(false, "L2", ran));
        queue.offer(task(true, "H1", ran));
        queue.offer(task(true, "H2", ran));
        queue.offer(task(true, "H3", ran));
        check(!queue.offer(task(true, "overflow", ran)), "executor queue exceeded capacity");
        while (!queue.isEmpty()) queue.poll().run();
        check(Arrays.asList("H1", "H2", "H3", "L1", "L2").equals(ran),
                "dual-lane FIFO/fairness order mismatch: " + ran);

        ran.clear();
        queue.offer(task(false, "L", ran));
        queue.offer(task(true, "H4", ran));
        queue.offer(task(true, "H5", ran));
        queue.offer(task(true, "H6", ran));
        queue.offer(task(true, "H7", ran));
        while (!queue.isEmpty()) queue.poll().run();
        check(Arrays.asList("H4", "H5", "H6", "L", "H7").equals(ran),
                "low lane could starve behind high backlog: " + ran);

        LegacyTranslator.FairTaskQueue promotion = new LegacyTranslator.FairTaskQueue(4);
        Runnable firstLow = task(false, "first-low", ran);
        Runnable promotedLow = task(false, "promoted-low", ran);
        promotion.offer(firstLow);
        promotion.offer(promotedLow);
        check(promotion.promote(promotedLow), "queued low task could not be promoted");
        check(promotion.poll() == promotedLow,
                "promotion changed only metadata instead of moving executor lanes");
    }

    private static void testQueuedPriorityPromotion() throws Exception {
        final CountDownLatch blockersEntered = new CountDownLatch(2);
        final CountDownLatch releaseBlockers = new CountDownLatch(1);
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                if (!canonicalSources.isEmpty() && canonicalSources.get(0).startsWith("Block")) {
                    blockersEntered.countDown();
                    if (!releaseBlockers.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("blocker timeout");
                    }
                }
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add("Translated " + source);
                return output;
            }
        });
        try {
            LegacyConfig config = config();
            translator.prefetch("Block one", "zh-TW", false, true, config);
            translator.flushBatch();
            translator.prefetch("Block two", "zh-TW", false, true, config);
            translator.flushBatch();
            check(blockersEntered.await(10L, TimeUnit.SECONDS),
                    "could not occupy both executor workers for promotion test");

            final CountDownLatch promotedDone = new CountDownLatch(1);
            translator.translate("Promote x1", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) { promotedDone.countDown(); }
                    });
            translator.flushBatch();
            translator.prefetch("Promote x2", "zh-TW", false, true, config);

            @SuppressWarnings("unchecked")
            Map<String, Object> flights = (Map<String, Object>) reflected(translator, "inFlight");
            Object promoted = null;
            synchronized (reflected(translator, "flightLock")) {
                for (Object candidate : flights.values()) {
                    if (((String) reflected(candidate, "source")).startsWith("Promote")) {
                        promoted = candidate;
                        break;
                    }
                }
            }
            check(promoted != null, "promoted flight disappeared");
            Object queuedTask = reflected(promoted, "queuedTask");
            check(queuedTask != null && ((Boolean) reflected(queuedTask, "high")).booleanValue(),
                    "duplicate high request did not promote its already-queued task");
            releaseBlockers.countDown();
            check(promotedDone.await(10L, TimeUnit.SECONDS), "promoted queued task did not finish");
        } finally {
            releaseBlockers.countDown();
            translator.shutdownForTests();
        }
    }

    private static void testEligibleBatchSelection() throws Exception {
        final CountDownLatch eligibleRan = new CountDownLatch(1);
        final AtomicReference<String> firstSource = new AtomicReference<String>();
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                firstSource.compareAndSet(null, canonicalSources.get(0));
                eligibleRan.countDown();
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add("Translated " + source);
                return output;
            }
        });
        try {
            LegacyConfig head = config();
            head.batchWindowMs = 60_000;
            head.sourceLang = "en";
            LegacyConfig eligible = config();
            eligible.batchWindowMs = 0;
            eligible.sourceLang = "fr";
            translator.prefetch("Waiting head", "zh-TW", false, false, head);
            translator.prefetch("Eligible now", "zh-TW", false, false, eligible);
            translator.flushBatch();
            check(eligibleRan.await(10L, TimeUnit.SECONDS)
                            && firstSource.get().startsWith("Eligible now"),
                    "first long-window low group hid a later zero-window group");
        } finally {
            translator.shutdownForTests();
        }

        final CountDownLatch fullRan = new CountDownLatch(1);
        final AtomicReference<String> fullFirst = new AtomicReference<String>();
        LegacyTranslator full = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                fullFirst.compareAndSet(null, canonicalSources.get(0));
                fullRan.countDown();
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add("Translated " + source);
                return output;
            }
        });
        try {
            LegacyConfig head = config();
            head.batchWindowMs = 60_000;
            head.sourceLang = "en";
            LegacyConfig fullGroup = config();
            fullGroup.batchWindowMs = 60_000;
            fullGroup.sourceLang = "fr";
            full.prefetch("Waiting head", "zh-TW", false, false, head);
            full.prefetch("Full eligible alpha " + repeat('a', 690),
                    "zh-TW", false, false, fullGroup);
            full.prefetch("Full eligible beta " + repeat('b', 690),
                    "zh-TW", false, false, fullGroup);
            full.flushBatch();
            check(fullRan.await(10L, TimeUnit.SECONDS)
                            && fullFirst.get().startsWith("Full eligible"),
                    "first long-window low group hid a later full group");
        } finally {
            full.shutdownForTests();
        }
    }

    private static Runnable task(boolean high, final String value, final List<String> output) {
        return LegacyTranslator.prioritizedTaskForTests(high, new Runnable() {
            @Override public void run() { output.add(value); }
        });
    }

    private static void testChatDeliveryModes() {
        LegacyChatDeliveryQueue<Object> queue = new LegacyChatDeliveryQueue<Object>();
        Object first = new Object(), second = new Object();
        queue.addLast(first);
        queue.addLast(second);
        queue.markReady(second);
        check(queue.drainReady(true).isEmpty(), "ordered mode released past an unfinished head");
        queue.markReady(first);
        check(Arrays.asList(first, second).equals(queue.drainReady(true)),
                "ordered mode did not preserve receive order");

        first = new Object(); second = new Object();
        queue.addLast(first);
        queue.addLast(second);
        queue.markReady(second);
        check(Collections.singletonList(second).equals(queue.drainReady(false)),
                "ready-first mode did not release completed second entry");
        queue.markReady(first);
        check(Collections.singletonList(first).equals(queue.drainReady(false)),
                "ready-first mode lost the remaining entry");
        check(queue.isEmpty(), "chat queue did not retire drained entries");

        queue.markReady(first);
        check(queue.drainReady(false).isEmpty(), "stale completion resurrected a retired entry");
        queue.markReady(new Object());
        check(queue.drainReady(false).isEmpty(), "unknown completion entered the queue");

        EqualEntry equalA = new EqualEntry(), equalB = new EqualEntry();
        LegacyChatDeliveryQueue<EqualEntry> identityQueue =
                new LegacyChatDeliveryQueue<EqualEntry>();
        identityQueue.addLast(equalA);
        identityQueue.addLast(equalB);
        identityQueue.markReady(equalB);
        check(identityQueue.drainReady(false).get(0) == equalB,
                "equal-but-distinct entry was confused by value equality");
        identityQueue.markReady(equalA);
        check(identityQueue.drainReady(false).get(0) == equalA && identityQueue.isEmpty(),
                "identity queue lost equal-but-distinct head");

        boolean duplicateRejected = false;
        identityQueue.addLast(equalA);
        try {
            identityQueue.addLast(equalA);
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        check(duplicateRejected, "same identity could be enqueued twice");
        identityQueue.clear();
    }

    private static void testConcurrentChatDelivery() throws Exception {
        for (int round = 0; round < 16; round++) {
            final LegacyChatDeliveryQueue<Object> queue =
                    new LegacyChatDeliveryQueue<Object>();
            final Object[] entries = new Object[512];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = new Object();
                queue.addLast(entries[i]);
            }
            final CountDownLatch ready = new CountDownLatch(8);
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(8);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            for (int worker = 0; worker < 8; worker++) {
                final int lane = worker;
                Thread thread = new Thread(new Runnable() {
                    @Override public void run() {
                        ready.countDown();
                        try {
                            start.await();
                            for (int i = lane; i < entries.length; i += 8) {
                                queue.markReady(entries[i]);
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            done.countDown();
                        }
                    }
                }, "inline-chat-marker-" + round + "-" + worker);
                thread.start();
            }
            check(ready.await(10L, TimeUnit.SECONDS), "chat marker threads did not start");
            start.countDown();
            check(done.await(10L, TimeUnit.SECONDS), "chat marker threads did not finish");
            check(failure.get() == null, "cross-thread chat queue failure: " + failure.get());
            List<Object> drained = queue.drainReady(false);
            Set<Object> identities = Collections.newSetFromMap(
                    new IdentityHashMap<Object, Boolean>());
            identities.addAll(drained);
            check(drained.size() == entries.length && identities.size() == entries.length
                            && queue.isEmpty(),
                    "cross-thread ready callbacks lost/duplicated/ghosted entries");
        }
    }

    private static void testAllChatDeliveryPermutationsAndSwitches() {
        Object[] entries = new Object[8];
        int[] order = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Object();
            order[i] = i;
        }
        int permutations = 0;
        int switchScenarios = 0;
        do {
            verifyFixedModePermutation(entries, order, true);
            verifyFixedModePermutation(entries, order, false);
            for (int split = 0; split <= entries.length; split++) {
                verifyModeSwitchPermutation(entries, order, split, true);
                verifyModeSwitchPermutation(entries, order, split, false);
                switchScenarios += 2;
            }
            permutations++;
        } while (nextPermutation(order));
        check(permutations == 40_320,
                "did not enumerate all 8! completion permutations: " + permutations);
        check(switchScenarios == 725_760,
                "did not enumerate all mode-switch scenarios: " + switchScenarios);
    }

    private static void verifyFixedModePermutation(Object[] entries, int[] order,
                                                   boolean ordered) {
        LegacyChatDeliveryQueue<Object> queue = new LegacyChatDeliveryQueue<Object>();
        for (Object entry : entries) queue.addLast(entry);
        List<Object> delivered = new ArrayList<Object>();
        for (int index : order) {
            queue.markReady(entries[index]);
            delivered.addAll(queue.drainReady(ordered));
        }
        List<Object> expected = new ArrayList<Object>();
        if (ordered) {
            expected.addAll(Arrays.asList(entries));
        } else {
            for (int index : order) expected.add(entries[index]);
        }
        check(expected.equals(delivered), "fixed delivery mismatch ordered=" + ordered);
        check(queue.isEmpty(), "fixed delivery permutation leaked an entry");
    }

    private static void verifyModeSwitchPermutation(Object[] entries, int[] order,
                                                    int split, boolean initialOrdered) {
        LegacyChatDeliveryQueue<Object> queue = new LegacyChatDeliveryQueue<Object>();
        List<Object> remaining = new ArrayList<Object>(Arrays.asList(entries));
        List<Object> readyOrder = new ArrayList<Object>();
        Map<Object, Boolean> ready = new IdentityHashMap<Object, Boolean>();
        Map<Object, Boolean> delivered = new IdentityHashMap<Object, Boolean>();
        for (Object entry : entries) queue.addLast(entry);

        for (int step = 0; step < order.length; step++) {
            Object completed = entries[order[step]];
            queue.markReady(completed);
            if (containsIdentity(remaining, completed) && !ready.containsKey(completed)) {
                ready.put(completed, Boolean.TRUE);
                readyOrder.add(completed);
            }
            boolean ordered = step < split ? initialOrdered : !initialOrdered;
            List<Object> actual = queue.drainReady(ordered);
            List<Object> expected = modelDrain(remaining, readyOrder, ready, ordered);
            check(expected.equals(actual), "mode-switch drain mismatch split=" + split
                    + " initial=" + initialOrdered + " step=" + step);
            for (Object entry : actual) {
                check(delivered.put(entry, Boolean.TRUE) == null,
                        "mode switch delivered an identity twice");
            }
        }
        check(queue.isEmpty() && remaining.isEmpty() && readyOrder.isEmpty()
                        && delivered.size() == entries.length,
                "mode-switch scenario leaked/lost entries split=" + split
                        + " initial=" + initialOrdered);
    }

    private static List<Object> modelDrain(List<Object> remaining, List<Object> readyOrder,
                                           Map<Object, Boolean> ready, boolean ordered) {
        List<Object> drained = new ArrayList<Object>();
        if (ordered) {
            while (!remaining.isEmpty() && ready.containsKey(remaining.get(0))) {
                Object entry = remaining.remove(0);
                ready.remove(entry);
                removeIdentity(readyOrder, entry);
                drained.add(entry);
            }
            return drained;
        }
        while (!readyOrder.isEmpty()) {
            Object entry = readyOrder.remove(0);
            if (!ready.containsKey(entry) || !removeIdentity(remaining, entry)) continue;
            ready.remove(entry);
            drained.add(entry);
        }
        return drained;
    }

    private static boolean containsIdentity(List<Object> values, Object target) {
        for (Object value : values) if (value == target) return true;
        return false;
    }

    private static boolean removeIdentity(List<Object> values, Object target) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != target) continue;
            values.remove(i);
            return true;
        }
        return false;
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) pivot--;
        if (pivot < 0) return false;
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) successor--;
        int swap = values[pivot];
        values[pivot] = values[successor];
        values[successor] = swap;
        for (int left = pivot + 1, right = values.length - 1;
             left < right; left++, right--) {
            swap = values[left];
            values[left] = values[right];
            values[right] = swap;
        }
        return true;
    }

    private static void testConfigPersistence() {
        Gson gson = new Gson();
        LegacyConfig missing = LegacyConfig.normalizeLoaded(gson.fromJson("{}", LegacyConfig.class));
        check(missing.deliverChatTranslationsInOrder,
                "missing chat delivery field did not retain ordered default");
        missing.deliverChatTranslationsInOrder = false;
        LegacyConfig restored = LegacyConfig.normalizeLoaded(
                gson.fromJson(gson.toJson(missing), LegacyConfig.class));
        check(!restored.deliverChatTranslationsInOrder,
                "explicit ready-first setting did not survive Gson round-trip");
    }

    private static void testChatRequestProfilePolicy() {
        LegacyConfig base = config();
        // 1.0.7 UI round 3: disableGoogleFallbackForAi now defaults to true ("補譯關"). This
        // test exercises the false -> true transition itself, so it needs the pre-change
        // (fallback-enabled) state explicitly rather than relying on the class default.
        base.disableGoogleFallbackForAi = false;
        LegacyChatRequestProfile original = LegacyChatRequestProfile.capture(base, "zh-TW");
        LegacyConfig displayOnly = base.snapshotForRequest();
        displayOnly.deliverChatTranslationsInOrder = false;
        displayOnly.showOriginal = !displayOnly.showOriginal;
        displayOnly.requestCooldownMs = 999;
        displayOnly.batchWindowMs = 999;
        check(original.equals(LegacyChatRequestProfile.capture(displayOnly, "zh-TW")),
                "delivery/display/pacing-only change invalidated chat request profile");

        check(!original.equals(LegacyChatRequestProfile.capture(base, "ja")),
                "target-language change did not invalidate chat profile");
        LegacyConfig changed = base.snapshotForRequest();
        changed.sourceLang = "en";
        check(!original.equals(LegacyChatRequestProfile.capture(changed, "zh-TW")),
                "source-language change did not invalidate chat profile");
        changed = base.snapshotForRequest();
        changed.machineTranslationProvider = "deepl";
        check(!original.equals(LegacyChatRequestProfile.capture(changed, "zh-TW")),
                "machine provider change did not invalidate chat profile");

        LegacyConfig ai = base.snapshotForRequest();
        ai.aiEnabled = true;
        LegacyChatRequestProfile aiProfile = LegacyChatRequestProfile.capture(ai, "zh-TW");
        check(!original.equals(aiProfile), "AI enable change did not invalidate chat profile");
        LegacyConfig aiModel = ai.snapshotForRequest();
        aiModel.aiModel = "another-model";
        check(!aiProfile.equals(LegacyChatRequestProfile.capture(aiModel, "zh-TW")),
                "AI model change did not invalidate chat profile");
        LegacyConfig fallback = ai.snapshotForRequest();
        fallback.disableGoogleFallbackForAi = true;
        LegacyChatRequestProfile strictAiProfile =
                LegacyChatRequestProfile.capture(fallback, "zh-TW");
        check(!aiProfile.equals(strictAiProfile),
                "AI fallback policy did not invalidate chat profile");
        LegacyConfig strictMachineChange = fallback.snapshotForRequest();
        strictMachineChange.sourceLang = "ja";
        strictMachineChange.machineTranslationProvider = "deepl";
        check(strictAiProfile.equals(
                        LegacyChatRequestProfile.capture(strictMachineChange, "zh-TW")),
                "strict AI profile included inactive machine settings");
        LegacyConfig fallbackMachineChange = ai.snapshotForRequest();
        fallbackMachineChange.sourceLang = "ja";
        fallbackMachineChange.machineTranslationProvider = "deepl";
        check(!aiProfile.equals(
                        LegacyChatRequestProfile.capture(fallbackMachineChange, "zh-TW")),
                "fallback-enabled AI profile omitted active machine settings");
        LegacyConfig codex = ai.snapshotForRequest();
        codex.aiUseCodex = true;
        LegacyChatRequestProfile codexProfile =
                LegacyChatRequestProfile.capture(codex, "zh-TW");
        check(!aiProfile.equals(codexProfile), "Codex switch did not invalidate chat profile");
        LegacyConfig effort = codex.snapshotForRequest();
        effort.codexReasoningEffort = "high";
        check(!codexProfile.equals(LegacyChatRequestProfile.capture(effort, "zh-TW")),
                "Codex effort change did not invalidate chat profile");
        LegacyConfig strictCodex = codex.snapshotForRequest();
        strictCodex.disableGoogleFallbackForAi = true;
        LegacyChatRequestProfile strictCodexProfile =
                LegacyChatRequestProfile.capture(strictCodex, "zh-TW");
        strictCodex.sourceLang = "fr";
        strictCodex.machineTranslationProvider = "microsoft";
        check(strictCodexProfile.equals(
                        LegacyChatRequestProfile.capture(strictCodex, "zh-TW")),
                "strict Codex profile included inactive machine settings");

        LegacyConfig dormantAi = base.snapshotForRequest();
        dormantAi.aiModel = "dormant-change";
        check(original.equals(LegacyChatRequestProfile.capture(dormantAi, "zh-TW")),
                "inactive AI settings unnecessarily invalidated machine chat profile");
    }

    private static void testTokenHighWaterAndCap() {
        LegacySessionTokenUsage usage = new LegacySessionTokenUsage();
        usage.recordCumulative("thread", 10, 3, 4, 1, 14);
        usage.recordCumulative("thread", 8, 2, 3, 0, 12);
        usage.recordCumulative("thread", 12, 4, 5, 2, 17);
        LegacySessionTokenUsage.Snapshot snapshot = usage.snapshot();
        check(snapshot.inputTokens() == 12 && snapshot.cachedInputTokens() == 4
                        && snapshot.outputTokens() == 5 && snapshot.reasoningOutputTokens() == 2
                        && snapshot.totalTokens() == 17 && snapshot.requests() == 1,
                "out-of-order cumulative token high-water double-counted");
        usage.finishCumulative("thread");
        check(usage.activeCumulativeSources() == 0, "finishCumulative did not release baseline");

        LegacySessionTokenUsage capped = new LegacySessionTokenUsage();
        for (int i = 0; i < 1025; i++) {
            capped.recordCumulative("source-" + i, 1, 0, 0, 0, 1);
        }
        check(capped.activeCumulativeSources() == 1024, "cumulative source map exceeded cap");
        check(capped.snapshot().requests() == 1024, "overflow cumulative source was counted");
    }

    private static void testCodexCompletedFirstGrace() throws Exception {
        final LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-grace-home"),
                Paths.get("build", "inline-codex-grace-workspace"));
        setField(client, "ready", Boolean.TRUE);
        final Method register = privateMethod(LegacyCodexClient.class,
                "registerTurn", String.class);
        final Method complete = privateMethod(LegacyCodexClient.class,
                "completeTurn", String.class, JsonObject.class);
        final Method record = privateMethod(LegacyCodexClient.class,
                "recordTurnMessage", String.class, String.class);
        final Method await = privateMethod(LegacyCodexClient.class,
                "awaitTurnMessage", String.class, long.class);
        Method closeTurn = privateMethod(LegacyCodexClient.class, "closeTurn", String.class);
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<JsonObject> completed =
                    (CompletableFuture<JsonObject>) register.invoke(client, "completed-first");
            complete.invoke(client, "completed-first", completedParams("completed-first"));
            check(completed.isDone(), "completed-first event did not finish turn future");
            Thread lateMessage = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(120L);
                        record.invoke(client, "completed-first", "late translation");
                    } catch (Throwable failure) {
                        throw new RuntimeException(failure);
                    }
                }
            }, "inline-codex-late-message");
            lateMessage.start();
            long started = System.nanoTime();
            String message = (String) await.invoke(client, "completed-first", 500L);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            lateMessage.join(2000L);
            check("late translation".equals(message) && waitedMs >= 80L && waitedMs < 500L,
                    "completed-first grace did not retain the late item/completed message");
            closeTurn.invoke(client, "completed-first");

            register.invoke(client, "message-first");
            record.invoke(client, "message-first", "early translation");
            complete.invoke(client, "message-first", completedParams("message-first"));
            check("early translation".equals(await.invoke(client, "message-first", 500L)),
                    "message-first turn lost its already-complete message");
            closeTurn.invoke(client, "message-first");

            register.invoke(client, "missing-message");
            complete.invoke(client, "missing-message", completedParams("missing-message"));
            started = System.nanoTime();
            Object missing = await.invoke(client, "missing-message", 500L);
            waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(missing == null && waitedMs >= 400L && waitedMs < 1500L,
                    "missing message did not use a bounded 500ms grace");
            closeTurn.invoke(client, "missing-message");
        } finally {
            client.close();
        }
    }

    private static void testCodexPendingRegistrationRace() throws Exception {
        final LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-register-home"),
                Paths.get("build", "inline-codex-register-workspace"));
        final FakeProcess process = new FakeProcess();
        final Object lifecycleLock = reflected(client, "lifecycleLock");
        final LockAssertingWriter wire = new LockAssertingWriter(lifecycleLock);
        setField(client, "process", process);
        setField(client, "writer", new BufferedWriter(wire));
        setField(client, "ready", Boolean.TRUE);
        final CountDownLatch hookEntered = new CountDownLatch(1);
        final CountDownLatch failureAttempted = new CountDownLatch(1);
        final Method failCurrent = privateMethod(LegacyCodexClient.class,
                "failIfCurrent", Process.class, IOException.class);
        client.setRequestRegistrationHookForTests(new Runnable() {
            @Override public void run() {
                hookEntered.countDown();
                try {
                    check(failureAttempted.await(5L, TimeUnit.SECONDS),
                            "reader failure thread did not contend for lifecycle lock");
                    Thread.sleep(30L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
            }
        });
        Thread readerFailure = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    hookEntered.await();
                    failureAttempted.countDown();
                    failCurrent.invoke(client, process,
                            new IOException("Codex JSONL line too large"));
                } catch (Throwable problem) {
                    throw new RuntimeException(problem);
                }
            }
        }, "inline-codex-reader-failure");
        readerFailure.start();
        Method request = privateMethod(LegacyCodexClient.class, "requestOnRunning",
                String.class, JsonObject.class, long.class);
        long started = System.nanoTime();
        try {
            request.invoke(client, "race/test", new JsonObject(), 10_000L);
            throw new AssertionError("reader failure did not fail registered request");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IOException,
                    "registration race failed with wrong cause: " + expected.getCause());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        readerFailure.join(5000L);
        check(elapsedMs < 5000L && privateSize(client, "pending") == 0,
                "request registered after failAll and waited for timeout");
        check(wire.wroteWhileLocked && wire.toString().contains("\"method\":\"race/test\""),
                "pending registration and request write were not one lifecycle transaction");
        check(!process.isAlive() && reflected(client, "process") == null,
                "reader oversize failure orphaned the child process");
        client.setRequestRegistrationHookForTests(null);
        client.close();

        LegacyCodexClient writeFailureClient = new LegacyCodexClient(
                Paths.get("build", "inline-codex-write-failure-home"),
                Paths.get("build", "inline-codex-write-failure-workspace"));
        FakeProcess writeFailureProcess = new FakeProcess();
        setField(writeFailureClient, "process", writeFailureProcess);
        setField(writeFailureClient, "writer", new BufferedWriter(new FailingWriter()));
        setField(writeFailureClient, "ready", Boolean.TRUE);
        try {
            final CompletableFuture<com.google.gson.JsonElement> otherPending =
                    new CompletableFuture<com.google.gson.JsonElement>();
            @SuppressWarnings("unchecked")
            Map<String, CompletableFuture<com.google.gson.JsonElement>> pending =
                    (Map<String, CompletableFuture<com.google.gson.JsonElement>>)
                            reflected(writeFailureClient, "pending");
            synchronized (reflected(writeFailureClient, "requestStateLock")) {
                pending.put("other-pending", otherPending);
            }
            expectInvocationIOException(request, writeFailureClient,
                    "write/failure", new JsonObject(), 100L);
            check(privateSize(writeFailureClient, "pending") == 0
                            && otherPending.isCompletedExceptionally()
                            && reflected(writeFailureClient, "process") == null
                            && !(Boolean) reflected(writeFailureClient, "ready"),
                    "failed request write did not fail and retire its whole generation");

            final FakeProcess replacement = new FakeProcess();
            final StringWriter replacementWire = new StringWriter();
            final BufferedWriter replacementWriter = new BufferedWriter(replacementWire);
            setField(writeFailureClient, "process", replacement);
            setField(writeFailureClient, "writer", replacementWriter);
            setField(writeFailureClient, "ready", Boolean.TRUE);
            final Method handleResponse = privateMethod(LegacyCodexClient.class,
                    "handleLine", Process.class, BufferedWriter.class, String.class);
            writeFailureClient.setRequestRegistrationHookForTests(new Runnable() {
                @Override public void run() {
                    try {
                        java.util.concurrent.atomic.AtomicLong ids =
                                (java.util.concurrent.atomic.AtomicLong)
                                        reflected(writeFailureClient, "nextId");
                        JsonObject response = new JsonObject();
                        response.addProperty("id", ids.get() - 1L);
                        JsonObject result = new JsonObject();
                        result.addProperty("ok", true);
                        response.add("result", result);
                        handleResponse.invoke(writeFailureClient, replacement,
                                replacementWriter, response.toString());
                    } catch (Throwable failure) {
                        throw new RuntimeException(failure);
                    }
                }
            });
            Object restartedResult = request.invoke(writeFailureClient,
                    "after/write/failure", new JsonObject(), 1000L);
            check(restartedResult instanceof com.google.gson.JsonObject
                            && ((com.google.gson.JsonObject) restartedResult).get("ok").getAsBoolean()
                            && replacementWire.toString().contains("after/write/failure"),
                    "clean replacement generation could not serve the next request");
            writeFailureClient.setRequestRegistrationHookForTests(null);
        } finally {
            writeFailureClient.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testCodexInitializationReaderFailureSignal() throws Exception {
        for (boolean stderrFailure : Arrays.asList(Boolean.FALSE, Boolean.TRUE)) {
            InputStream stderr = stderrFailure
                    ? new ByteArrayInputStream(repeat('e', 16_385)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : new ByteArrayInputStream(new byte[0]);
            LegacyCodexClient client = new LegacyCodexClient(
                    Paths.get("build", "inline-codex-init-reader-home-" + stderrFailure),
                    Paths.get("build", "inline-codex-init-reader-workspace-" + stderrFailure));
            FakeProcess process = new FakeProcess(stderr);
            BufferedWriter writer = new BufferedWriter(new StringWriter());
            try {
                setField(client, "process", process);
                setField(client, "writer", writer);
                setField(client, "ready", Boolean.FALSE);
                Method beginInitialization = privateMethod(LegacyCodexClient.class,
                        "beginInitialization", Process.class);
                beginInitialization.invoke(client, process);

                CompletableFuture<com.google.gson.JsonElement> initialize =
                        new CompletableFuture<com.google.gson.JsonElement>();
                Object requestStateLock = reflected(client, "requestStateLock");
                synchronized (requestStateLock) {
                    Map<String, CompletableFuture<com.google.gson.JsonElement>> pending =
                            (Map<String, CompletableFuture<com.google.gson.JsonElement>>)
                                    reflected(client, "pending");
                    pending.put("initializing", initialize);
                }

                Method startReader = privateMethod(LegacyCodexClient.class,
                        stderrFailure ? "startStderrReader" : "startReader",
                        stderrFailure
                                ? new Class<?>[]{Process.class}
                                : new Class<?>[]{Process.class, BufferedWriter.class});
                Object lifecycleLock = reflected(client, "lifecycleLock");
                Throwable signalled;
                synchronized (lifecycleLock) {
                    if (stderrFailure) startReader.invoke(client, process);
                    else startReader.invoke(client, process, writer);
                    try {
                        initialize.get(2L, TimeUnit.SECONDS);
                        throw new AssertionError("initialization reader failure completed successfully");
                    } catch (ExecutionException expected) {
                        signalled = expected.getCause();
                    }
                    check(!process.isAlive(),
                            "initialization reader failure did not destroy its child promptly");
                }
                check(signalled instanceof IOException,
                        "initialization reader failure did not wake the pending future: " + signalled);
            } finally {
                client.close();
            }
        }
    }

    private static void testCodexStaleReaderIsolation() throws Exception {
        LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-stale-reader-home"),
                Paths.get("build", "inline-codex-stale-reader-workspace"));
        GatedStderrInputStream oldStderr =
                new GatedStderrInputStream("stale generation stderr\n");
        FakeProcess oldProcess = new FakeProcess(oldStderr);
        FakeProcess currentProcess = new FakeProcess();
        StringWriter oldWire = new StringWriter();
        StringWriter currentWire = new StringWriter();
        BufferedWriter oldWriter = new BufferedWriter(oldWire);
        BufferedWriter currentWriter = new BufferedWriter(currentWire);
        Method handleFromProcess = privateMethod(LegacyCodexClient.class,
                "handleLine", Process.class, BufferedWriter.class, String.class);
        Method bestEffort = privateMethod(LegacyCodexClient.class,
                "sendBestEffortRequest", Process.class, String.class, JsonObject.class);
        Method startStderrReader = privateMethod(LegacyCodexClient.class,
                "startStderrReader", Process.class);
        Method recordProcessError = privateMethod(LegacyCodexClient.class,
                "recordProcessError", Process.class, String.class);
        Method registerLogin = privateMethod(LegacyCodexClient.class,
                "registerLogin", Process.class, String.class);
        JsonObject serverRequest = new JsonObject();
        serverRequest.addProperty("id", 77);
        serverRequest.addProperty("method", "filesystem/read");
        serverRequest.add("params", new JsonObject());
        final CountDownLatch errorChecksEntered = new CountDownLatch(2);
        final CountDownLatch releaseErrorChecks = new CountDownLatch(1);
        try {
            setField(client, "process", oldProcess);
            setField(client, "writer", oldWriter);
            setField(client, "ready", Boolean.TRUE);
            startStderrReader.invoke(client, oldProcess);
            check(oldStderr.readerEntered.await(5L, TimeUnit.SECONDS),
                    "old stderr reader did not reach its deterministic barrier");
            recordProcessError.invoke(client, oldProcess, "old generation baseline error");
            check("old generation baseline error".equals(client.lastError()),
                    "old generation baseline error was not installed");

            client.setProcessErrorHookForTests(new Runnable() {
                @Override public void run() {
                    errorChecksEntered.countDown();
                    try {
                        check(releaseErrorChecks.await(5L, TimeUnit.SECONDS),
                                "replacement generation did not release stale error writers");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interrupted);
                    }
                }
            });
            JsonObject loginParams = new JsonObject();
            loginParams.addProperty("loginId", "stale-error-login");
            loginParams.addProperty("success", false);
            loginParams.addProperty("error", "stale login failure");
            final JsonObject loginFailure = new JsonObject();
            loginFailure.addProperty("method", "account/login/completed");
            loginFailure.add("params", loginParams);
            final AtomicReference<Throwable> loginFailureThread =
                    new AtomicReference<Throwable>();
            final AtomicReference<Throwable> oversizeFailureThread =
                    new AtomicReference<Throwable>();
            Thread staleLogin = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        handleFromProcess.invoke(client, oldProcess, oldWriter,
                                loginFailure.toString());
                    } catch (Throwable failure) {
                        loginFailureThread.set(failure);
                    }
                }
            }, "inline-codex-stale-login-error");
            Thread staleOversize = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        handleFromProcess.invoke(client, oldProcess, oldWriter,
                                repeat('z', 2_000_001));
                    } catch (Throwable failure) {
                        oversizeFailureThread.set(failure);
                    }
                }
            }, "inline-codex-stale-oversize-error");
            staleLogin.start();
            staleOversize.start();
            check(errorChecksEntered.await(5L, TimeUnit.SECONDS),
                    "stale login/oversize paths did not reach the post-entry error barrier");

            synchronized (reflected(client, "lifecycleLock")) {
                setField(client, "process", currentProcess);
                setField(client, "writer", currentWriter);
                setField(client, "lastError", "current generation fallback");
                // Mirror ensureStarted's new-generation reset after both old writers
                // have already read the old tag but before either can attempt its CAS.
                ((AtomicReference<?>) reflected(client, "processError")).set(null);
            }
            // The old workers have passed their first process check and read the old
            // tag before capturing the hook. Removing it now lets a real current-
            // generation error land before they resume; their post-read identity check
            // must reject both old CAS attempts without erasing the new tagged value.
            client.setProcessErrorHookForTests(null);
            recordProcessError.invoke(client, currentProcess, "current generation error");
            releaseErrorChecks.countDown();
            staleLogin.join(5000L);
            staleOversize.join(5000L);
            check(!staleLogin.isAlive() && !staleOversize.isAlive()
                            && loginFailureThread.get() == null
                            && oversizeFailureThread.get() == null,
                    "stale login/oversize error path did not complete cleanly");
            check("current generation error".equals(client.lastError()),
                    "stale login failure or oversized JSONL overwrote replacement error state");
            oldStderr.release.countDown();
            check(oldStderr.eofReached.await(5L, TimeUnit.SECONDS),
                    "old stderr reader did not consume its buffered line");
            check("current generation error".equals(client.lastError()),
                    "stale stderr reader overwrote the replacement generation error");

            final CountDownLatch currentErrorDone = new CountDownLatch(1);
            final AtomicReference<Throwable> currentErrorFailure =
                    new AtomicReference<Throwable>();
            Thread currentErrorWriter = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        recordProcessError.invoke(client, currentProcess,
                                "current error while lifecycle held");
                    } catch (Throwable failure) {
                        currentErrorFailure.set(failure);
                    } finally {
                        currentErrorDone.countDown();
                    }
                }
            }, "inline-codex-current-error-no-lifecycle");
            final CountDownLatch currentErrorRead = new CountDownLatch(1);
            final AtomicReference<String> currentErrorValue = new AtomicReference<String>();
            Thread currentErrorReader = new Thread(new Runnable() {
                @Override public void run() {
                    currentErrorValue.set(client.lastError());
                    currentErrorRead.countDown();
                }
            }, "inline-codex-current-error-read-no-lifecycle");
            synchronized (reflected(client, "lifecycleLock")) {
                currentErrorWriter.start();
                check(currentErrorDone.await(2L, TimeUnit.SECONDS),
                        "current process error waited behind initialize lifecycle lock");
                currentErrorReader.start();
                check(currentErrorRead.await(2L, TimeUnit.SECONDS),
                        "lastError waited behind initialize lifecycle lock");
            }
            currentErrorWriter.join(2000L);
            currentErrorReader.join(2000L);
            check(currentErrorFailure.get() == null
                            && "current error while lifecycle held".equals(currentErrorValue.get()),
                    "current generation error was not atomically visible without lifecycle lock");

            final String earlyLoginId = "early-failed-login-no-lifecycle";
            JsonObject earlyFailureParams = new JsonObject();
            earlyFailureParams.addProperty("loginId", earlyLoginId);
            earlyFailureParams.addProperty("success", false);
            earlyFailureParams.addProperty("error", "early login error while lifecycle held");
            final JsonObject earlyFailure = new JsonObject();
            earlyFailure.addProperty("method", "account/login/completed");
            earlyFailure.add("params", earlyFailureParams);
            final CountDownLatch earlyLoginHandled = new CountDownLatch(1);
            final AtomicReference<Throwable> earlyLoginFailure =
                    new AtomicReference<Throwable>();
            Thread earlyLoginReader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        handleFromProcess.invoke(client, currentProcess, currentWriter,
                                earlyFailure.toString());
                    } catch (Throwable failure) {
                        earlyLoginFailure.set(failure);
                    } finally {
                        earlyLoginHandled.countDown();
                    }
                }
            }, "inline-codex-early-login-no-lifecycle");
            synchronized (reflected(client, "lifecycleLock")) {
                earlyLoginReader.start();
                check(earlyLoginHandled.await(2L, TimeUnit.SECONDS),
                        "early failed login blocked the stdout reader behind lifecycle lock");
            }
            earlyLoginReader.join(2000L);
            check(earlyLoginFailure.get() == null,
                    "early failed login reader path failed: " + earlyLoginFailure.get());
            registerLogin.invoke(client, currentProcess, earlyLoginId);
            check(!client.awaitLogin(earlyLoginId, 1000L)
                            && "early login error while lifecycle held".equals(client.lastError()),
                    "early failed login state/error was not retained without lifecycle lock");

            handleFromProcess.invoke(client, oldProcess, oldWriter, serverRequest.toString());
            check(oldWire.toString().isEmpty() && currentWire.toString().isEmpty(),
                    "stale reader replied through a different process generation");

            JsonObject unsubscribe = new JsonObject();
            unsubscribe.addProperty("threadId", "old-thread");
            bestEffort.invoke(client, oldProcess, "thread/unsubscribe", unsubscribe);
            check(currentWire.toString().isEmpty(),
                    "stale thread cleanup wrote an unsubscribe into the new process");

            final CountDownLatch readerHandled = new CountDownLatch(1);
            final AtomicReference<Throwable> readerFailure = new AtomicReference<Throwable>();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        handleFromProcess.invoke(client, currentProcess, currentWriter,
                                serverRequest.toString());
                    } catch (Throwable failure) {
                        readerFailure.set(failure);
                    } finally {
                        readerHandled.countDown();
                    }
                }
            }, "inline-codex-server-request-reader");
            synchronized (reflected(client, "lifecycleLock")) {
                reader.start();
                check(readerHandled.await(2L, TimeUnit.SECONDS),
                        "server-request reply deadlocked behind initialize lifecycle lock");
            }
            reader.join(2000L);
            check(readerFailure.get() == null,
                    "current reader reply failed: " + readerFailure.get());
            check(currentWire.toString().contains("\"id\":77")
                            && currentWire.toString().contains("\"code\":-32601"),
                    "current reader did not reject a server request on its own writer");
            bestEffort.invoke(client, currentProcess, "thread/unsubscribe", unsubscribe);
            check(currentWire.toString().contains("\"method\":\"thread/unsubscribe\"")
                            && currentWire.toString().contains("\"threadId\":\"old-thread\""),
                    "current process best-effort cleanup was not sent");
        } finally {
            releaseErrorChecks.countDown();
            oldStderr.release.countDown();
            client.setProcessErrorHookForTests(null);
            oldProcess.destroy();
            client.close();
        }
    }

    private static void testCodexPostResponseGenerationRace() throws Exception {
        LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-post-response-home"),
                Paths.get("build", "inline-codex-post-response-workspace"));
        FakeProcess oldProcess = new FakeProcess();
        final FakeProcess currentProcess = new FakeProcess();
        final StringWriter currentWire = new StringWriter();
        final BufferedWriter currentWriter = new BufferedWriter(currentWire);
        Method registerLogin = privateMethod(LegacyCodexClient.class,
                "registerLogin", Process.class, String.class);
        Method openThread = privateMethod(LegacyCodexClient.class,
                "openThread", Process.class, String.class);
        Method registerTurn = privateMethod(LegacyCodexClient.class,
                "registerTurn", Process.class, String.class);
        Method closeThread = privateMethod(LegacyCodexClient.class,
                "closeThread", Process.class, String.class);
        Method closeTurn = privateMethod(LegacyCodexClient.class,
                "closeTurn", Process.class, String.class);
        Method failCurrent = privateMethod(LegacyCodexClient.class,
                "failIfCurrent", Process.class, IOException.class);
        try {
            setField(client, "process", oldProcess);
            setField(client, "writer", new BufferedWriter(new StringWriter()));
            setField(client, "ready", Boolean.TRUE);
            registerLogin.invoke(client, oldProcess, "old-login");

            failCurrent.invoke(client, oldProcess,
                    new IOException("old process failed after response"));
            synchronized (reflected(client, "lifecycleLock")) {
                setField(client, "process", currentProcess);
                setField(client, "writer", currentWriter);
                setField(client, "ready", Boolean.TRUE);
            }

            long started = System.nanoTime();
            try {
                client.awaitLogin("old-login", 10_000L);
                throw new AssertionError("expired login id was attached to a new process");
            } catch (IOException expected) {
                check(expected.getMessage().contains("Unknown or expired"),
                        "expired login failed with wrong reason: " + expected.getMessage());
            }
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000L,
                    "expired login waited for its old timeout");

            expectInvocationIOException(
                    registerLogin, client, oldProcess, "response-login");
            expectInvocationIOException(
                    openThread, client, oldProcess, "response-thread");
            expectInvocationIOException(
                    registerTurn, client, oldProcess, "response-turn");
            check(privateSize(client, "loginResults") == 0
                            && privateSize(client, "activeThreads") == 0
                            && privateSize(client, "turnResults") == 0,
                    "post-response stale registration polluted the new process state");

            openThread.invoke(client, currentProcess, "reused-thread");
            registerTurn.invoke(client, currentProcess, "reused-turn");
            closeThread.invoke(client, oldProcess, "reused-thread");
            closeTurn.invoke(client, oldProcess, "reused-turn");
            check(privateSize(client, "activeThreads") == 1
                            && privateSize(client, "turnResults") == 1,
                    "stale cleanup removed state owned by the new process");
            closeThread.invoke(client, currentProcess, "reused-thread");
            closeTurn.invoke(client, currentProcess, "reused-turn");

            final String reusedLoginId = "reused-login";
            registerLogin.invoke(client, currentProcess, reusedLoginId);
            final CountDownLatch oldAwaitWaiting = new CountDownLatch(1);
            final CountDownLatch oldCleanupEntered = new CountDownLatch(1);
            final CountDownLatch releaseOldCleanup = new CountDownLatch(1);
            final AtomicReference<Throwable> oldAwaitFailure = new AtomicReference<Throwable>();
            client.setLoginAwaitWaitHookForTests(new Runnable() {
                @Override public void run() { oldAwaitWaiting.countDown(); }
            });
            client.setLoginAwaitCleanupHookForTests(new Runnable() {
                @Override public void run() {
                    oldCleanupEntered.countDown();
                    try {
                        check(releaseOldCleanup.await(5L, TimeUnit.SECONDS),
                                "new login generation did not complete during old cleanup barrier");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interrupted);
                    }
                }
            });
            Thread oldAwait = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        client.awaitLogin(reusedLoginId, 10_000L);
                    } catch (Throwable failure) {
                        oldAwaitFailure.set(failure);
                    }
                }
            }, "inline-codex-old-login-await");
            oldAwait.start();
            check(oldAwaitWaiting.await(5L, TimeUnit.SECONDS),
                    "old login await did not capture its generation future");

            failCurrent.invoke(client, currentProcess,
                    new IOException("old reused-login generation failed"));
            check(oldCleanupEntered.await(5L, TimeUnit.SECONDS),
                    "old login await did not pause before identity cleanup");
            final FakeProcess reusedProcess = new FakeProcess();
            final StringWriter reusedWire = new StringWriter();
            final BufferedWriter reusedWriter = new BufferedWriter(reusedWire);
            synchronized (reflected(client, "lifecycleLock")) {
                setField(client, "process", reusedProcess);
                setField(client, "writer", reusedWriter);
                setField(client, "ready", Boolean.TRUE);
            }
            registerLogin.invoke(client, reusedProcess, reusedLoginId);
            Method handleLogin = privateMethod(LegacyCodexClient.class,
                    "handleLine", Process.class, BufferedWriter.class, String.class);
            JsonObject loginParams = new JsonObject();
            loginParams.addProperty("loginId", reusedLoginId);
            loginParams.addProperty("success", true);
            JsonObject loginCompleted = new JsonObject();
            loginCompleted.addProperty("method", "account/login/completed");
            loginCompleted.add("params", loginParams);
            handleLogin.invoke(client, reusedProcess, reusedWriter, loginCompleted.toString());

            releaseOldCleanup.countDown();
            oldAwait.join(5000L);
            check(oldAwaitFailure.get() instanceof IOException,
                    "old login await did not fail with its retired generation");
            client.setLoginAwaitWaitHookForTests(null);
            client.setLoginAwaitCleanupHookForTests(null);

            final Method handleAccount = handleLogin;
            client.setRequestRegistrationHookForTests(new Runnable() {
                @Override public void run() {
                    try {
                        java.util.concurrent.atomic.AtomicLong ids =
                                (java.util.concurrent.atomic.AtomicLong) reflected(client, "nextId");
                        JsonObject account = new JsonObject();
                        account.addProperty("type", "chatgpt");
                        JsonObject result = new JsonObject();
                        result.add("account", account);
                        JsonObject response = new JsonObject();
                        response.addProperty("id", ids.get() - 1L);
                        response.add("result", result);
                        handleAccount.invoke(client, reusedProcess, reusedWriter,
                                response.toString());
                    } catch (Throwable failure) {
                        throw new RuntimeException(failure);
                    }
                }
            });
            check(client.awaitLogin(reusedLoginId, 1000L),
                    "old await cleanup deleted the reused generation completion");
            client.setRequestRegistrationHookForTests(null);
        } finally {
            client.close();
        }
    }

    private static void testCodexDeadGenerationTakeover() throws Exception {
        LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-takeover-home"),
                Paths.get("build", "inline-codex-takeover-workspace"));
        FakeProcess oldProcess = new FakeProcess();
        Method registerLogin = privateMethod(LegacyCodexClient.class,
                "registerLogin", Process.class, String.class);
        Method openThread = privateMethod(LegacyCodexClient.class,
                "openThread", Process.class, String.class);
        Method registerTurn = privateMethod(LegacyCodexClient.class,
                "registerTurn", Process.class, String.class);
        Method recordMessage = privateMethod(LegacyCodexClient.class,
                "recordTurnMessage", String.class, String.class);
        Method handleLine = privateMethod(LegacyCodexClient.class,
                "handleLine", String.class);
        Method retire = privateMethod(LegacyCodexClient.class,
                "retireGenerationBeforeStart");
        Method failInitialization = privateMethod(LegacyCodexClient.class,
                "failInitialization", IOException.class);
        try {
            setField(client, "process", oldProcess);
            setField(client, "writer", new BufferedWriter(new StringWriter()));
            setField(client, "ready", Boolean.TRUE);
            registerLogin.invoke(client, oldProcess, "takeover-login");
            openThread.invoke(client, oldProcess, "takeover-thread");
            @SuppressWarnings("unchecked")
            CompletableFuture<JsonObject> oldTurn =
                    (CompletableFuture<JsonObject>) registerTurn.invoke(
                            client, oldProcess, "takeover-turn");
            recordMessage.invoke(client, "takeover-early", "early message");

            JsonObject earlyLoginParams = new JsonObject();
            earlyLoginParams.addProperty("loginId", "takeover-early-login");
            earlyLoginParams.addProperty("success", true);
            JsonObject earlyLogin = new JsonObject();
            earlyLogin.addProperty("method", "account/login/completed");
            earlyLogin.add("params", earlyLoginParams);
            handleLine.invoke(client, earlyLogin.toString());

            final CompletableFuture<com.google.gson.JsonElement> oldRequest =
                    new CompletableFuture<com.google.gson.JsonElement>();
            @SuppressWarnings("unchecked")
            Map<String, CompletableFuture<com.google.gson.JsonElement>> pending =
                    (Map<String, CompletableFuture<com.google.gson.JsonElement>>)
                            reflected(client, "pending");
            synchronized (reflected(client, "requestStateLock")) {
                pending.put("takeover-request", oldRequest);
            }
            @SuppressWarnings("unchecked")
            Map<String, CompletableFuture<Boolean>> logins =
                    (Map<String, CompletableFuture<Boolean>>) reflected(client, "loginResults");
            CompletableFuture<Boolean> oldLogin;
            synchronized (reflected(client, "loginStateLock")) {
                oldLogin = logins.get("takeover-login");
            }

            oldProcess.destroy();
            retire.invoke(client);
            check(reflected(client, "process") == null
                            && !(Boolean) reflected(client, "ready")
                            && privateSize(client, "pending") == 0
                            && privateSize(client, "loginResults") == 0
                            && privateSize(client, "completedLoginResults") == 0
                            && privateSize(client, "turnResults") == 0
                            && privateSize(client, "earlyTurns") == 0
                            && privateSize(client, "activeThreads") == 0
                            && oldRequest.isCompletedExceptionally()
                            && oldLogin.isCompletedExceptionally()
                            && oldTurn.isCompletedExceptionally(),
                    "dead generation takeover did not fail and clear all protocol state");

            FakeProcess replacement = new FakeProcess();
            setField(client, "process", replacement);
            setField(client, "writer", new BufferedWriter(new StringWriter()));
            setField(client, "ready", Boolean.TRUE);
            registerLogin.invoke(client, replacement, "takeover-login");
            openThread.invoke(client, replacement, "takeover-thread");
            registerTurn.invoke(client, replacement, "takeover-turn");
            check(privateSize(client, "loginResults") == 1
                            && privateSize(client, "activeThreads") == 1
                            && privateSize(client, "turnResults") == 1,
                    "replacement generation could not safely reuse old identifiers");

            recordMessage.invoke(client, "initialize-early", "early during initialize");
            failInitialization.invoke(client,
                    new IOException("synthetic initialize failure"));
            check(privateSize(client, "loginResults") == 0
                            && privateSize(client, "turnResults") == 0
                            && privateSize(client, "earlyTurns") == 0
                            && privateSize(client, "activeThreads") == 0
                            && client.lastError().contains("synthetic initialize failure"),
                    "initialize failure retained generation state or lost its error");
        } finally {
            client.close();
        }
    }

    private static void testCodexLoginRegistrationRace() throws Exception {
        final LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-login-race-home"),
                Paths.get("build", "inline-codex-login-race-workspace"));
        final FakeProcess process = new FakeProcess();
        setField(client, "process", process);
        setField(client, "writer", new BufferedWriter(new StringWriter()));
        setField(client, "ready", Boolean.TRUE);
        final CountDownLatch hookEntered = new CountDownLatch(1);
        final CountDownLatch failureAttempted = new CountDownLatch(1);
        final Method failCurrent = privateMethod(LegacyCodexClient.class,
                "failIfCurrent", Process.class, IOException.class);
        final Method registerLogin = privateMethod(LegacyCodexClient.class,
                "registerLogin", String.class);
        client.setLoginRegistrationHookForTests(new Runnable() {
            @Override public void run() {
                hookEntered.countDown();
                try {
                    check(failureAttempted.await(5L, TimeUnit.SECONDS),
                            "login failure thread did not contend for lifecycle lock");
                    Thread.sleep(30L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
            }
        });
        Thread readerFailure = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    hookEntered.await();
                    failureAttempted.countDown();
                    failCurrent.invoke(client, process, new IOException("login process stopped"));
                } catch (Throwable problem) {
                    throw new RuntimeException(problem);
                }
            }
        }, "inline-codex-login-failure");
        readerFailure.start();
        registerLogin.invoke(client, "login-race");
        readerFailure.join(5000L);
        check(privateSize(client, "loginResults") == 0 && !process.isAlive(),
                "login future was registered after failAll and orphaned");
        client.setLoginRegistrationHookForTests(null);
        client.close();
    }

    private static void testCodexStateCapsAndLifecycle() throws Exception {
        LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-state-home"),
                Paths.get("build", "inline-codex-state-workspace"));
        LegacySessionTokenUsage usage = new LegacySessionTokenUsage();
        client.setTokenUsage(usage);
        Method recordMessage = privateMethod(LegacyCodexClient.class,
                "recordTurnMessage", String.class, String.class);
        Method closeTurn = privateMethod(LegacyCodexClient.class, "closeTurn", String.class);
        Method openThread = privateMethod(LegacyCodexClient.class, "openThread", String.class);
        Method closeThread = privateMethod(LegacyCodexClient.class, "closeThread", String.class);
        Method recordTokens = privateMethod(LegacyCodexClient.class,
                "recordTokenUsage", JsonObject.class);
        Method handleLine = privateMethod(LegacyCodexClient.class, "handleLine", String.class);
        Method registerTurn = privateMethod(LegacyCodexClient.class, "registerTurn", String.class);
        try {
            for (int i = 0; i < 600; i++) {
                recordMessage.invoke(client, "early-" + i, "message");
            }
            check(privateSize(client, "earlyTurns") == 512,
                    "early-turn map exceeded hard cap");
            for (int i = 0; i < 600; i++) closeTurn.invoke(client, "closed-" + i);
            check(privateSize(client, "recentlyClosedTurns") == 512,
                    "recently-closed turn set exceeded hard cap");

            for (int i = 0; i < 600; i++) {
                JsonObject params = new JsonObject();
                params.addProperty("loginId", "login-" + i);
                params.addProperty("success", true);
                JsonObject notification = new JsonObject();
                notification.addProperty("method", "account/login/completed");
                notification.add("params", params);
                handleLine.invoke(client, notification.toString());
            }
            check(privateSize(client, "completedLoginResults") == 512,
                    "completed login result map exceeded hard cap");
            check(privateSize(client, "loginResults") == 0,
                    "unknown login notifications leaked active futures");

            FakeProcess loginProcess = new FakeProcess();
            setField(client, "process", loginProcess);
            setField(client, "writer", new BufferedWriter(new StringWriter()));
            setField(client, "ready", Boolean.TRUE);
            Method registerLogin = privateMethod(LegacyCodexClient.class,
                    "registerLogin", String.class);
            registerLogin.invoke(client, "legitimate-login");
            check(privateSize(client, "loginResults") == 1,
                    "unknown login flood prevented a legitimate login registration");
            @SuppressWarnings("unchecked")
            Map<String, CompletableFuture<Boolean>> activeLogins =
                    (Map<String, CompletableFuture<Boolean>>) reflected(client, "loginResults");
            synchronized (reflected(client, "loginStateLock")) { activeLogins.clear(); }

            for (int i = 0; i < 512; i++) registerTurn.invoke(client, "active-turn-" + i);
            expectInvocationIOException(registerTurn, client, "active-turn-overflow");
            check(privateSize(client, "turnResults") == 512,
                    "active turn map exceeded hard cap");
            for (int i = 0; i < 512; i++) closeTurn.invoke(client, "active-turn-" + i);

            for (int i = 0; i < 600; i++) {
                String thread = "thread-" + i;
                openThread.invoke(client, thread);
                recordTokens.invoke(client, tokenParams(thread, i + 1));
                closeThread.invoke(client, thread);
            }
            check(privateSize(client, "recentlyClosedThreads") == 512,
                    "recently-closed thread set exceeded hard cap");
            check(usage.activeCumulativeSources() == 512,
                    "evicted thread token baselines were not finished");

            for (int i = 0; i < 512; i++) openThread.invoke(client, "active-thread-" + i);
            expectInvocationIOException(openThread, client, "active-thread-overflow");
            check(privateSize(client, "activeThreads") == 512,
                    "active thread set exceeded hard cap");
            for (int i = 0; i < 512; i++) closeThread.invoke(client, "active-thread-" + i);

            @SuppressWarnings("unchecked")
            Map<String, CompletableFuture<com.google.gson.JsonElement>> pending =
                    (Map<String, CompletableFuture<com.google.gson.JsonElement>>)
                            reflected(client, "pending");
            synchronized (reflected(client, "requestStateLock")) {
                for (int i = 0; i < 512; i++) {
                    pending.put("pending-" + i,
                            new CompletableFuture<com.google.gson.JsonElement>());
                }
            }
            setField(client, "writer", new BufferedWriter(new StringWriter()));
            setField(client, "ready", Boolean.TRUE);
            Method request = privateMethod(LegacyCodexClient.class, "requestOnRunning",
                    String.class, JsonObject.class, long.class);
            expectInvocationIOException(request, client, "cap/test", new JsonObject(), 100L);
            check(privateSize(client, "pending") == 512,
                    "pending request overflow mutated/exceeded hard cap");
            synchronized (reflected(client, "requestStateLock")) { pending.clear(); }
        } finally {
            client.close();
        }
        check(usage.activeCumulativeSources() == 0,
                "Codex close did not finish remaining cumulative baselines");
    }

    private static void testProtocolAndProviderSizeCaps() throws Exception {
        Method readLine = privateMethod(LegacyCodexClient.class,
                "readBoundedLine", BufferedReader.class, int.class);
        check(repeat('x', 16).equals(readLine.invoke(null,
                        new BufferedReader(new StringReader(repeat('x', 16) + "\n")), 16)),
                "bounded JSONL reader rejected an exact-limit line");
        expectInvocationIOException(readLine, null,
                new BufferedReader(new StringReader(repeat('x', 2_000_001) + "\n")),
                2_000_000);
        expectInvocationIOException(readLine, null,
                new BufferedReader(new StringReader(repeat('e', 16_385) + "\n")),
                16_384);

        LegacyCodexClient client = new LegacyCodexClient(
                Paths.get("build", "inline-codex-size-home"),
                Paths.get("build", "inline-codex-size-workspace"));
        setField(client, "ready", Boolean.TRUE);
        Method register = privateMethod(LegacyCodexClient.class, "registerTurn", String.class);
        Method record = privateMethod(LegacyCodexClient.class,
                "recordTurnMessage", String.class, String.class);
        Method await = privateMethod(LegacyCodexClient.class,
                "awaitTurnMessage", String.class, long.class);
        Method closeTurn = privateMethod(LegacyCodexClient.class, "closeTurn", String.class);
        Method handleLine = privateMethod(LegacyCodexClient.class, "handleLine", String.class);
        Method send = privateMethod(LegacyCodexClient.class, "send", JsonObject.class);
        try {
            register.invoke(client, "oversize-turn");
            record.invoke(client, "oversize-turn", repeat('m', 2_000_001));
            record.invoke(client, "oversize-turn", "later valid message");
            try {
                await.invoke(client, "oversize-turn", 10L);
                throw new AssertionError("oversize turn message was retained");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof ExecutionException
                                && expected.getCause().getCause() instanceof IOException,
                        "oversize turn message failed with wrong semantics: " + expected.getCause());
            }
            closeTurn.invoke(client, "oversize-turn");

            register.invoke(client, "valid-first-turn");
            record.invoke(client, "valid-first-turn", "first valid message");
            record.invoke(client, "valid-first-turn", repeat('m', 2_000_001));
            check("first valid message".equals(
                            await.invoke(client, "valid-first-turn", 10L)),
                    "later oversize item overrode the first valid turn message");
            closeTurn.invoke(client, "valid-first-turn");

            record.invoke(client, "early-oversize", repeat('m', 2_000_001));
            record.invoke(client, "early-oversize", "later early valid");
            register.invoke(client, "early-oversize");
            try {
                await.invoke(client, "early-oversize", 10L);
                throw new AssertionError("early oversize message was bypassed by a later item");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof ExecutionException,
                        "early oversize turn failed with wrong semantics");
            }
            closeTurn.invoke(client, "early-oversize");

            record.invoke(client, "early-valid", "first early valid");
            record.invoke(client, "early-valid", repeat('m', 2_000_001));
            register.invoke(client, "early-valid");
            check("first early valid".equals(await.invoke(client, "early-valid", 10L)),
                    "early valid message was overwritten by later oversize item");
            closeTurn.invoke(client, "early-valid");

            handleLine.invoke(client, repeat('j', 2_000_001));
            check(client.lastError().contains("too large"),
                    "oversize JSONL line was parsed instead of rejected");

            setField(client, "writer", new BufferedWriter(new StringWriter()));
            JsonObject huge = new JsonObject();
            huge.addProperty("payload", repeat('q', 2_000_001));
            expectInvocationIOException(send, client, huge);
        } finally {
            client.close();
        }

        Method providerRead = privateMethod(LegacyMachineProvider.class,
                "read", HttpURLConnection.class, boolean.class);
        HttpURLConnection connection = new SyntheticConnection(
                new RepeatingInputStream(2_000_001));
        try {
            providerRead.invoke(null, connection, false);
            throw new AssertionError("experimental provider accepted an oversize HTTP response");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof Exception
                            && expected.getCause().getMessage().contains("too large"),
                    "experimental provider oversize failure was not bounded");
        }
    }

    // ---------------------------------------------------------------------------------------
    // 1.0.7 master request switch (translationRequestsEnabled) + do-not-translate terms.
    // Inline fakes only (TestBackend / TestAiHttp). Every AI config below is strict
    // (disableGoogleFallbackForAi) so no scenario can fall back to the real Google endpoint.
    // ---------------------------------------------------------------------------------------

    private static void testRequestSwitchConfigAndProfile() {
        Gson gson = new Gson();
        LegacyConfig defaults = new LegacyConfig();
        check(defaults.translationRequestsEnabled && defaults.doNotTranslateTerms != null
                        && defaults.doNotTranslateTerms.isEmpty(),
                "request switch / DNT defaults are wrong");
        LegacyConfig missing = LegacyConfig.normalizeLoaded(gson.fromJson("{}", LegacyConfig.class));
        check(missing.translationRequestsEnabled && missing.doNotTranslateTerms != null
                        && missing.doNotTranslateTerms.isEmpty(),
                "missing request switch / DNT fields did not keep their defaults");
        LegacyConfig nullTerms = LegacyConfig.normalizeLoaded(
                gson.fromJson("{\"doNotTranslateTerms\":null}", LegacyConfig.class));
        check(nullTerms.doNotTranslateTerms != null && nullTerms.doNotTranslateTerms.isEmpty(),
                "null DNT list was not normalized to an empty list");
        LegacyConfig messy = LegacyConfig.normalizeLoaded(gson.fromJson(
                "{\"translationRequestsEnabled\":false,\"doNotTranslateTerms\":"
                        + "[\"  SkyBlock \",\"\",\"   \",\"skyblock\",\"Hypixel\","
                        + "\"\\u3000Bed Wars\\u3000\",\"SKYBLOCK\",null]}",
                LegacyConfig.class));
        check(!messy.translationRequestsEnabled, "explicit request switch off was not loaded");
        check(Arrays.asList("SkyBlock", "Hypixel", "Bed Wars").equals(messy.doNotTranslateTerms),
                "DNT normalize (trim, drop blank, case-insensitive dedupe keeping first spelling) "
                        + "mismatch: " + messy.doNotTranslateTerms);
        LegacyConfig roundTrip = LegacyConfig.normalizeLoaded(
                gson.fromJson(gson.toJson(messy), LegacyConfig.class));
        check(!roundTrip.translationRequestsEnabled
                        && messy.doNotTranslateTerms.equals(roundTrip.doNotTranslateTerms),
                "request switch / DNT list did not survive a Gson round-trip");
        LegacyConfig snapshot = messy.snapshotForRequest();
        check(!snapshot.translationRequestsEnabled
                        && messy.doNotTranslateTerms.equals(snapshot.doNotTranslateTerms)
                        && snapshot.doNotTranslateTerms != messy.doNotTranslateTerms,
                "snapshotForRequest did not deep-copy the request switch / DNT list");
        messy.doNotTranslateTerms.add("Later");
        check(!snapshot.doNotTranslateTerms.contains("Later"),
                "queued request snapshot observed a later DNT edit");

        LegacyConfig base = config();
        LegacyChatRequestProfile original = LegacyChatRequestProfile.capture(base, "zh-TW");
        LegacyConfig paused = base.snapshotForRequest();
        paused.translationRequestsEnabled = false;
        check(!original.equals(LegacyChatRequestProfile.capture(paused, "zh-TW")),
                "request switch did not invalidate the chat request profile");
        LegacyConfig withTerm = base.snapshotForRequest();
        withTerm.doNotTranslateTerms.add("SkyBlock");
        LegacyChatRequestProfile termProfile = LegacyChatRequestProfile.capture(withTerm, "zh-TW");
        check(!original.equals(termProfile),
                "DNT edit did not invalidate the chat request profile");
        LegacyConfig caseOnly = base.snapshotForRequest();
        caseOnly.doNotTranslateTerms.add("  skyBLOCK ");
        LegacyChatRequestProfile caseProfile = LegacyChatRequestProfile.capture(caseOnly, "zh-TW");
        check(termProfile.equals(caseProfile) && termProfile.hashCode() == caseProfile.hashCode(),
                "case/whitespace-only DNT edit needlessly invalidated the chat profile");
        LegacyConfig two = base.snapshotForRequest();
        two.doNotTranslateTerms.addAll(Arrays.asList("Hypixel", "SkyBlock"));
        LegacyConfig reordered = base.snapshotForRequest();
        reordered.doNotTranslateTerms.addAll(Arrays.asList("skyblock", "HYPIXEL", "SkyBlock"));
        check(LegacyChatRequestProfile.capture(two, "zh-TW").equals(
                        LegacyChatRequestProfile.capture(reordered, "zh-TW")),
                "order/duplicate-only DNT edit needlessly invalidated the chat profile");

        // Glue keeps on-the-wire requests only for a switch-only toggle.
        LegacyChatRequestProfile pausedProfile = LegacyChatRequestProfile.capture(paused, "zh-TW");
        check(original.differsOnlyInRequestSwitch(pausedProfile)
                        && pausedProfile.differsOnlyInRequestSwitch(original),
                "switch-only toggle was not recognised as switch-only");
        check(!original.differsOnlyInRequestSwitch(original)
                        && !original.differsOnlyInRequestSwitch(null)
                        && !original.differsOnlyInRequestSwitch(termProfile)
                        && !original.differsOnlyInRequestSwitch(
                                LegacyChatRequestProfile.capture(base, "ja")),
                "a non-switch profile change was treated as switch-only");
        LegacyConfig pausedWithTerm = withTerm.snapshotForRequest();
        pausedWithTerm.translationRequestsEnabled = false;
        check(!original.differsOnlyInRequestSwitch(
                        LegacyChatRequestProfile.capture(pausedWithTerm, "zh-TW")),
                "switch + DNT change was treated as switch-only");
    }

    private static void testRequestSwitchCacheOnly() throws Exception {
        RecordingBackend backend = new RecordingBackend(null);
        LegacyTranslator translator = new LegacyTranslator(backend);
        try {
            LegacyConfig config = config();
            config.debugTranslationOverlay = true;
            check("譯 Gate cached x1".equals(
                            translateAndWait(translator, "Gate cached x1", false, config)),
                    "request-switch seed translation failed");
            config.translationRequestsEnabled = false;
            check("譯 Gate cached x7".equals(
                            translateImmediate(translator, "Gate cached x7", false, config)),
                    "switch off hid a canonical cache hit from translate()");
            check("譯 Gate cached x8".equals(
                            translator.cached("Gate cached x8", "zh-TW", false, config)),
                    "switch off hid a cache hit from cached()");
            check(translateImmediate(translator, "Gate miss one", false, config) == null,
                    "switch off did not show the original for a miss immediately");
            check(translateImmediate(translator, "Gate miss ai", true, config) == null,
                    "switch off did not show the original for an AI miss immediately");
            translator.prefetch("Gate miss prefetch", "zh-TW", false, true, config);
            for (int i = 0; i < 5; i++) {
                translator.flushBatch();
                Thread.sleep(20L);
            }
            check(backend.calls.get() == 1, "switch off still sent a translation request");
            assertNoRequestState(translator, "switch-off miss");
            config.translationRequestsEnabled = true;
            check("譯 Gate miss one".equals(
                            translateAndWait(translator, "Gate miss one", false, config))
                            && backend.calls.get() == 2,
                    "switch back on did not resend an earlier miss");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void testRequestSwitchCancelsQueuedWork() throws Exception {
        RecordingBackend backend = new RecordingBackend(null);
        LegacyTranslator translator = new LegacyTranslator(backend);
        try {
            LegacyConfig config = config();
            config.batchWindowMs = 60_000;
            final CountDownLatch released = new CountDownLatch(20);
            final AtomicInteger originals = new AtomicInteger();
            for (int i = 0; i < 20; i++) {
                translator.translate("Queued " + letters(i), "zh-TW", false, false, config,
                        new Consumer<String>() {
                            @Override public void accept(String value) {
                                if (value == null) originals.incrementAndGet();
                                released.countDown();
                            }
                        });
            }
            translator.flushBatch();
            check(privateSize(translator, "pending") == 20 && released.getCount() == 20,
                    "long batch window did not hold the queued work");
            config.translationRequestsEnabled = false;
            translator.flushBatch();
            check(released.await(1L, TimeUnit.SECONDS) && originals.get() == 20,
                    "switch off did not release every queued chat waiter as original promptly");
            Thread.sleep(50L);
            check(backend.calls.get() == 0, "queued work reached the backend after switch off");
            assertNoRequestState(translator, "queued cancellation");
            config.translationRequestsEnabled = true;
            config.batchWindowMs = 0;
            check("譯 Queued a".equals(translateAndWait(translator, "Queued a", false, config))
                            && backend.calls.get() == 1,
                    "cancelled queued work could not be resent after switch on");
        } finally {
            translator.shutdownForTests();
        }

        final CountDownLatch blockersEntered = new CountDownLatch(2);
        final CountDownLatch releaseBlockers = new CountDownLatch(1);
        final AtomicInteger queuedCalls = new AtomicInteger();
        LegacyTranslator executorQueued = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                if (canonicalSources.get(0).startsWith("Block")) {
                    blockersEntered.countDown();
                    if (!releaseBlockers.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("blocker timeout");
                    }
                } else {
                    queuedCalls.incrementAndGet();
                }
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add("譯 " + source);
                return output;
            }
        });
        try {
            LegacyConfig config = config();
            executorQueued.prefetch("Block one", "zh-TW", false, true, config);
            executorQueued.flushBatch();
            executorQueued.prefetch("Block two", "zh-TW", false, true, config);
            executorQueued.flushBatch();
            check(blockersEntered.await(10L, TimeUnit.SECONDS),
                    "could not occupy both legacy executor workers");
            final CountDownLatch queuedDone = new CountDownLatch(1);
            final AtomicReference<String> queuedValue = new AtomicReference<String>("not-called");
            executorQueued.translate("Executor queued", "zh-TW", false, false, config,
                    new Consumer<String>() {
                        @Override public void accept(String value) {
                            queuedValue.set(value);
                            queuedDone.countDown();
                        }
                    });
            executorQueued.flushBatch();
            check(((java.util.concurrent.ThreadPoolExecutor) reflected(executorQueued, "executor"))
                            .getQueue().size() == 1,
                    "third batch did not wait in the executor queue");
            config.translationRequestsEnabled = false;
            releaseBlockers.countDown();
            check(queuedDone.await(10L, TimeUnit.SECONDS) && queuedValue.get() == null,
                    "executor-queued batch was not cancelled to the original");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (executorQueued.inFlightCountForTests() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            check(queuedCalls.get() == 0,
                    "executor-queued batch reached the backend after switch off");
            check(privateSize(executorQueued, "failedUntil") == 0,
                    "executor-queued cancellation wrote a failure backoff");
            check("譯 Block one".equals(executorQueued.cached("Block one", "zh-TW", false, config)),
                    "a request already on the wire lost its result when the switch closed");
        } finally {
            releaseBlockers.countDown();
            executorQueued.shutdownForTests();
        }
    }

    private static void testRequestSwitchRechecksAfterPacing() throws Exception {
        final AtomicInteger httpCalls = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator();
        translator.setAiHttpForTests(new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) {
                httpCalls.incrementAndGet();
                return text.replace("Paced", "節流");
            }
        });
        try {
            LegacyConfig config = config();
            config.disableGoogleFallbackForAi = true;
            config.requestCooldownMs = 1500;
            config.debugTranslationOverlay = true;
            config.aiApiKeys.add("key-a");
            config.aiApiKeys.add("key-b");
            check("節流 first".equals(translateAndWait(translator, "Paced first", true, config))
                            && httpCalls.get() == 1,
                    "paced seed request failed");
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<String> value = new AtomicReference<String>("not-called");
            translator.translate("Paced second", "zh-TW", true, false, config, new Consumer<String>() {
                @Override public void accept(String result) {
                    value.set(result);
                    done.countDown();
                }
            });
            translator.flushBatch();
            Thread.sleep(300L);
            check(done.getCount() == 1 && httpCalls.get() == 1
                            && translator.inFlightCountForTests() == 1,
                    "second AI request was not waiting inside pace()");
            config.translationRequestsEnabled = false;
            check(done.await(5L, TimeUnit.SECONDS) && value.get() == null,
                    "paced request did not end as original after switch off");
            Thread.sleep(50L);
            check(httpCalls.get() == 1, "paced request still reached the wire after switch off");
            assertNoRequestState(translator, "paced cancellation");
            boolean pausedLogged = false;
            for (LegacyTranslator.DebugEntry entry : translator.debugSnapshot()) {
                if (entry.source.startsWith("Paced second") && "paused".equals(entry.status)) {
                    pausedLogged = true;
                }
            }
            check(pausedLogged, "paced cancellation left no non-failure debug status");
            config.translationRequestsEnabled = true;
            check("節流 second".equals(translateAndWait(translator, "Paced second", true, config))
                            && httpCalls.get() == 2,
                    "paused paced request could not be resent after switch on");
        } finally {
            translator.shutdownForTests();
        }
    }

    private static void testRequestSwitchActionsAndRetries() throws Exception {
        final AtomicReference<String> screenWord = new AtomicReference<String>("畫面");
        RecordingBackend backend = new RecordingBackend(
                new java.util.function.Function<String, String>() {
                    @Override public String apply(String source) {
                        return source.replace("Screen", screenWord.get());
                    }
                });
        LegacyTranslator translator = new LegacyTranslator(backend);
        try {
            LegacyConfig config = config();
            check("畫面 title".equals(translateAndWait(translator, "Screen title", false, config)),
                    "screen seed translation failed");
            config.translationRequestsEnabled = false;
            translator.retranslateScreen(Collections.singletonList("Screen title"), "zh-TW", config);
            for (int i = 0; i < 5; i++) {
                translator.flushBatch();
                Thread.sleep(20L);
            }
            check("畫面 title".equals(translator.cached("Screen title", "zh-TW", false, config))
                            && backend.calls.get() == 1,
                    "switch-off screen retranslation deleted a cached row or sent a request");
            config.translationRequestsEnabled = true;
            screenWord.set("螢幕");
            translator.retranslateScreen(Collections.singletonList("Screen title"), "zh-TW", config);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
            while (!"螢幕 title".equals(translator.cached("Screen title", "zh-TW", false, config))
                    && System.nanoTime() < deadline) {
                translator.flushBatch();
                Thread.sleep(10L);
            }
            check("螢幕 title".equals(translator.cached("Screen title", "zh-TW", false, config))
                            && backend.calls.get() == 2,
                    "switch-on screen retranslation no longer refreshes the row");
        } finally {
            translator.shutdownForTests();
        }

        final AtomicInteger retryCalls = new AtomicInteger();
        LegacyTranslator retrying = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                if (retryCalls.incrementAndGet() == 1) throw new Exception("HTTP 503 temporary outage");
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add("譯 " + source);
                return output;
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            check(translateAndWait(retrying, "Retry gate x1", true, strict) == null,
                    "retry seed did not fail");
            strict.translationRequestsEnabled = false;
            Thread.sleep(600L);
            check(retrying.inFlightCountForTests() == 0 && privateSize(retrying, "pending") == 0,
                    "paused scheduled retry still created request work");
            long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200L);
            while (System.nanoTime() < settle) {
                retrying.flushBatch();
                Thread.sleep(10L);
            }
            check(retryCalls.get() == 1, "scheduled retry was sent while the switch was off");
            assertNoRequestState(retrying, "paused scheduled retry");
            strict.translationRequestsEnabled = true;
            check("譯 Retry gate x1".equals(translateAndWait(retrying, "Retry gate x1", true, strict))
                            && retryCalls.get() == 2,
                    "switch on could not resend after a paused retry");
        } finally {
            retrying.shutdownForTests();
        }

        final AtomicInteger testCalls = new AtomicInteger();
        LegacyTranslator connection = new LegacyTranslator();
        connection.setAiHttpForTests(new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) {
                testCalls.incrementAndGet();
                return "紫色凳子";
            }
        });
        try {
            LegacyConfig off = config();
            off.translationRequestsEnabled = false;
            final CountDownLatch tested = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<String>();
            connection.testAi("zh-TW", off, new Consumer<String>() {
                @Override public void accept(String value) {
                    result.set(value);
                    tested.countDown();
                }
            });
            check(tested.await(10L, TimeUnit.SECONDS) && "OK: 紫色凳子".equals(result.get())
                            && testCalls.get() == 1,
                    "switch off blocked the manual AI connection test: " + result.get());
        } finally {
            connection.shutdownForTests();
        }
    }

    private static void testDoNotTranslateMatching() {
        String open = "⟦";
        String close = "⟧";
        String slot0 = open + "MT0" + close;
        String slot1 = open + "MT1" + close;
        List<String> skyblock = Collections.singletonList("SkyBlock");
        LegacyTemplateText.Prepared welcome =
                LegacyTemplateText.prepare("Welcome to SkyBlock!", skyblock);
        check(("Welcome to " + slot0 + "!").equals(welcome.text())
                        && Collections.singletonList("SkyBlock").equals(welcome.values()),
                "DNT term was not masked as a slot: " + welcome.text());
        check("歡迎來到SkyBlock！".equals(welcome.restore("歡迎來到" + slot0 + "！")),
                "DNT slot restore failed");
        LegacyTemplateText.Prepared upper = LegacyTemplateText.prepare(
                "Welcome to SKYBLOCK!", Collections.singletonList("skyblock"));
        check(welcome.text().equals(upper.text()) && "SKYBLOCK".equals(upper.values().get(0)),
                "case variant did not share the masked key or lost its own spelling");
        check("歡迎來到SKYBLOCK！".equals(upper.restore("歡迎來到" + slot0 + "！")),
                "case variant restore did not keep SKYBLOCK");
        for (String untouched : Arrays.asList("Install Skyfoo now", "Build skyblocks here",
                "Play SkyBlock_2 today", "Try xSkyBlock mode", "Say SkyBlock2 please")) {
            check(!LegacyTemplateText.prepare(untouched, skyblock).text().contains(open),
                    "DNT matched inside a longer word: " + untouched);
        }
        // Only ASCII letters/digits/underscore glue words together (same as the modern matcher).
        check(("歡迎來到" + slot0 + "！").equals(
                        LegacyTemplateText.prepare("歡迎來到SkyBlock！", skyblock).text()),
                "CJK neighbour blocked an ASCII DNT term");
        LegacyTemplateText.Prepared nbspEdged = LegacyTemplateText.prepare(
                "Play Bed Wars now", Collections.singletonList(" Bed Wars "));
        check(("Play " + slot0 + " now").equals(nbspEdged.text()),
                "space-edged DNT term was not matched by its words: " + nbspEdged.text());
        check(!LegacyTemplateText.prepare("Play Bed Warsaw now",
                        Collections.singletonList(" Bed Wars ")).text().contains(open),
                "space-edged DNT term lost its word boundary");
        check(!LegacyTemplateText.prepare("Welcome to §6SkyBlock",
                        Arrays.asList("§6SkyBlock", open + "MT0" + close)).text().contains(open),
                "DNT terms holding protocol/format characters were not ignored");
        check(("Say " + open + "SkyBlock" + close + " at " + slot0).equals(LegacyTemplateText.prepare(
                        "Say " + open + "SkyBlock" + close + " at SkyBlock", skyblock).text()),
                "DNT matched inside a literal bracket token");
        // Case folding must not turn non-ASCII neighbours (U+017F, Kelvin sign) into ASCII.
        check(("ſ" + slot0).equals(LegacyTemplateText.prepare(
                        "ſkyBlock", Collections.singletonList("kyBlock")).text()),
                "folded long s blocked a DNT word boundary");
        check((slot0 + "K").equals(LegacyTemplateText.prepare("SkyBlockK", skyblock).text()),
                "folded Kelvin sign blocked a DNT word boundary");

        // A term never cuts a number/duration/time/rank tag apart: re-preparing the canonical
        // text (the transient-retry path does) is idempotent and rank tags keep their slot.
        String[][] corpus = {
                {"Season 2.5 is live", "Season 2"},
                {"Sky 5,000 coins here", "Sky 5"},
                {"Ends in 3d 4h", "4h"},
                {"[MVP++] Steve joined the lobby", "MVP"},
                {"Event 12:00 starts", "Event 12"},
                {"C++5 compiler", "C++"},
                {"Welcome to Area 51!", "Area 51"},
                {"§eWelcome to §6SkyBlock§r 2!", "SkyBlock"}};
        for (String[] row : corpus) {
            List<String> terms = Collections.singletonList(row[1]);
            LegacyTemplateText.Prepared once = LegacyTemplateText.prepare(row[0], terms);
            LegacyTemplateText.Prepared twice = LegacyTemplateText.prepare(once.text(), terms);
            check(once.text().equals(twice.text()) && !twice.changed(),
                    "DNT preparation is not idempotent: " + row[0] + " -> " + once.text()
                            + " -> " + twice.text());
        }
        check(LegacyTemplateText.prepare("[MVP++] Steve joined the lobby").text().equals(
                        LegacyTemplateText.prepare("[MVP++] Steve joined the lobby",
                                Collections.singletonList("MVP")).text()),
                "DNT term inside a rank tag weakened the rank-tag slot");
        check(("Season " + slot0 + " is live").equals(LegacyTemplateText.prepare(
                        "Season 2.5 is live", Collections.singletonList("Season 2")).text()),
                "DNT term cut a decimal number apart");
        check((slot0 + "'s best island").equals(
                        LegacyTemplateText.prepare("SkyBlock's best island", skyblock).text()),
                "possessive DNT was not masked");
        LegacyTemplateText.Prepared colored =
                LegacyTemplateText.prepare("§eWelcome to §6SkyBlock§r!", skyblock);
        check(("§eWelcome to §6" + slot0 + "§r!").equals(colored.text()),
                "§x code before a DNT term was not a word boundary: " + colored.text());
        check(!LegacyTemplateText.prepare("§eSkyBlock", skyblock).hasTranslatableContent(),
                "colored whole-DNT text was still translatable");
        check(("§e Hello " + slot0 + " world").equals(LegacyTemplateText.prepare(
                        "§e Hello e world", Collections.singletonList("e")).text()),
                "single-letter DNT term split a §x formatting code");

        List<String> island = Arrays.asList("Island", "Private Island");
        LegacyTemplateText.Prepared spaced =
                LegacyTemplateText.prepare("Visit your Private  Island today", island);
        check(("Visit your " + slot0 + " today").equals(spaced.text())
                        && "Private  Island".equals(spaced.values().get(0)),
                "multi-word DNT with repeated spaces was not one slot: " + spaced.text());
        check(("Visit your " + slot0).equals(
                        LegacyTemplateText.prepare("Visit your Private Island", island).text()),
                "no-break space did not match a DNT word gap");
        check(("Your " + slot0 + " awaits").equals(
                        LegacyTemplateText.prepare("Your Island awaits", island).text()),
                "shorter DNT term was not masked on its own");
        check(!LegacyTemplateText.prepare("Private\nIsland",
                        Collections.singletonList("Private Island")).text().contains(open),
                "multi-word DNT crossed a line break");
        LegacyTemplateText.Prepared overlap = LegacyTemplateText.prepare(
                "Sky Block Party", Arrays.asList("Sky Block", "Block Party"));
        check(("Sky " + slot0).equals(overlap.text())
                        && "Block Party".equals(overlap.values().get(0)),
                "overlapping DNT terms did not keep the longest: " + overlap.text());

        List<String> hypixel = Collections.singletonList("Hypixel");
        LegacyTemplateText.Prepared domain =
                LegacyTemplateText.prepare("Join hypixel.net now", hypixel);
        check(("Join " + slot0 + " now").equals(domain.text())
                        && Collections.singletonList("hypixel.net").equals(domain.values()),
                "DNT split a domain: " + domain.values());
        check(Collections.singletonList("https://hypixel.net/forums").equals(LegacyTemplateText.prepare(
                        "Visit https://hypixel.net/forums today", hypixel).values()),
                "DNT split a URL");
        check(Collections.singletonList("Hypixel").equals(
                        LegacyTemplateText.prepare("Welcome to Hypixel", hypixel).values()),
                "standalone DNT name was not masked");

        LegacyTemplateText.Prepared area = LegacyTemplateText.prepare(
                "Welcome to Area 51!", Collections.singletonList("Area 51"));
        check(("Welcome to " + slot0 + "!").equals(area.text())
                        && "Area 51".equals(area.values().get(0)),
                "digit-bearing DNT term lost to the number slot: " + area.text());
        check(("歡迎來到" + slot0 + "！").equals(LegacyTemplateText.prepare(
                        "歡迎來到天空島！", Collections.singletonList("天空島")).text()),
                "CJK DNT term was not masked without an ASCII word boundary");
        LegacyTemplateText.Prepared reserved = LegacyTemplateText.prepare(
                "Keep " + slot0 + " and MT0", Collections.singletonList("MT0"));
        check(("Keep " + slot0 + " and " + slot1).equals(reserved.text())
                        && Collections.singletonList("MT0").equals(reserved.values()),
                "DNT matched inside a reserved MT token: " + reserved.text());

        for (String whole : Arrays.asList("SkyBlock", "§6SkyBlock§r", "SkyBlock!",
                "SkyBlock 2", "  SKYBLOCK  ")) {
            check(!LegacyTemplateText.prepare(whole, skyblock).hasTranslatableContent(),
                    "whole-line DNT text was still translatable: " + whole);
        }
        check(!LegacyTemplateText.prepare("SkyBlock Hypixel",
                        Arrays.asList("SkyBlock", "Hypixel")).hasTranslatableContent(),
                "two whole DNT terms were still translatable");
        check(LegacyTemplateText.prepare("Welcome to SkyBlock!", Arrays.asList("", "  "))
                        == LegacyTemplateText.prepare("Welcome to SkyBlock!"),
                "blank DNT list changed preparation");
        check(LegacyTemplateText.prepare("Welcome to SkyBlock!", null)
                        == LegacyTemplateText.prepare("Welcome to SkyBlock!"),
                "null DNT list changed preparation");
    }

    @SuppressWarnings("unchecked")
    private static void testDoNotTranslateEndToEnd() throws Exception {
        final String slot0 = "⟦MT0⟧";
        final Map<String, String> answers = new java.util.HashMap<String, String>();
        answers.put("Welcome to " + slot0 + "!", "歡迎來到" + slot0 + "！");
        answers.put(slot0 + "'s best island", slot0 + "的最佳島嶼");
        answers.put("§eWelcome to §6" + slot0 + "§r!", "§e歡迎來到§6" + slot0 + "§r！");
        RecordingBackend backend = new RecordingBackend(
                new java.util.function.Function<String, String>() {
                    @Override public String apply(String source) {
                        String answer = answers.get(source);
                        return answer == null ? "譯 " + source : answer;
                    }
                });
        LegacyTranslator translator = new LegacyTranslator(backend);
        try {
            LegacyConfig config = config();
            config.doNotTranslateTerms.add("SkyBlock");
            check("歡迎來到SkyBlock！".equals(
                            translateAndWait(translator, "Welcome to SkyBlock!", false, config)),
                    "DNT display was not restored to SkyBlock");
            check(Collections.singletonList("Welcome to " + slot0 + "!").equals(backend.received),
                    "translator did not receive the DNT placeholder: " + backend.received);
            LegacyConfig lower = config();
            lower.doNotTranslateTerms.add("skyblock");
            check("歡迎來到SKYBLOCK！".equals(
                            translateImmediate(translator, "Welcome to SKYBLOCK!", false, lower))
                            && backend.calls.get() == 1,
                    "case variant did not reuse the masked row with its own spelling");
            check("SkyBlock的最佳島嶼".equals(
                            translateAndWait(translator, "SkyBlock's best island", false, config)),
                    "possessive DNT translation was rejected or not restored");
            check("SkyBlock的最佳島嶼".equals(
                            translator.cached("SkyBlock's best island", "zh-TW", false, config)),
                    "possessive DNT result was not cached");
            check("§e歡迎來到§6SkyBlock§r！".equals(translateAndWait(
                            translator, "§eWelcome to §6SkyBlock§r!", false, config)),
                    "colored DNT display was not restored");
            int before = backend.calls.get();
            for (String whole : Arrays.asList("SkyBlock", "§6SkyBlock", "SkyBlock!")) {
                check(whole.equals(translateImmediate(translator, whole, false, config)),
                        "whole-line DNT did not show the original immediately: " + whole);
                check(whole.equals(translator.cached(whole, "zh-TW", false, config)),
                        "whole-line DNT cached() did not return the original: " + whole);
            }
            translator.prefetch("SkyBlock", "zh-TW", false, true, config);
            for (int i = 0; i < 5; i++) {
                translator.flushBatch();
                Thread.sleep(10L);
            }
            check(backend.calls.get() == before && translator.inFlightCountForTests() == 0,
                    "whole-line DNT created a request");
            synchronized (backend.received) {
                for (String sent : backend.received) {
                    check(!sent.toLowerCase(java.util.Locale.ROOT).contains("skyblock"),
                            "a DNT term leaked to the translator: " + sent);
                }
            }
        } finally {
            translator.shutdownForTests();
        }

        final List<String> wire = Collections.synchronizedList(new ArrayList<String>());
        LegacyTranslator ai = new LegacyTranslator();
        ai.setAiHttpForTests(new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) {
                wire.add(text);
                return text.replace("Welcome to ", "歡迎來到").replace("!", "！");
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            strict.doNotTranslateTerms.add("SkyBlock");
            check("歡迎來到SkyBlock！".equals(
                            translateAndWait(ai, "Welcome to SkyBlock!", true, strict)),
                    "AI DNT display was not restored");
            check(Collections.singletonList("Welcome to 30001!").equals(wire),
                    "AI wire did not carry the DNT slot as a verified numeric sentinel: " + wire);
        } finally {
            ai.shutdownForTests();
        }

        List<String> skyblock = Collections.singletonList("SkyBlock");
        LegacyTemplateText.Prepared first = LegacyTemplateText.prepare("Welcome to SkyBlock!", skyblock);
        LegacyTemplateText.Prepared second = LegacyTemplateText.prepare("Leave SkyBlock now", skyblock);
        Method encode = LegacyTranslator.class.getDeclaredMethod("encodeTemplateTokens", List.class);
        encode.setAccessible(true);
        Method buildWire = LegacyTranslator.class.getDeclaredMethod("buildBatchWire", List.class);
        buildWire.setAccessible(true);
        Method split = LegacyTranslator.class.getDeclaredMethod("splitBatchEncoded",
                String.class, int.class, int.class, List.class);
        split.setAccessible(true);
        Object tokens = encode.invoke(null, Arrays.asList(first.text(), second.text()));
        check(encodedTexts(tokens).equals(Arrays.asList("Welcome to 30001!", "Leave 30002 now")),
                "machine transport did not encode DNT slots as sentinels: " + encodedTexts(tokens));
        Object batchWire = buildWire.invoke(null, encodedTexts(tokens));
        String wireText = (String) reflected(batchWire, "text");
        int anchorBase = ((Integer) reflected(batchWire, "anchorBase")).intValue();
        List<String> sentinels = (List<String>) invokeNoArgs(tokens, "sentinels");
        String reply = wireText.replace("Welcome to ", "歡迎來到").replace("!", "！")
                .replace("Leave ", "離開").replace(" now", "吧");
        List<String> items = (List<String>) split.invoke(null, reply, 2, anchorBase, sentinels);
        List<String> decoded = (List<String>) invoke(tokens, "decodeItems",
                new Class<?>[]{List.class}, new Object[]{items});
        check("歡迎來到SkyBlock！".equals(first.restore(decoded.get(0)))
                        && "離開SkyBlock吧".equals(second.restore(decoded.get(1))),
                "machine batch transport did not restore DNT terms: " + decoded);
        List<String> dropped = (List<String>) split.invoke(null, reply.replace("30002", ""),
                2, anchorBase, sentinels);
        try {
            invoke(tokens, "decodeItems", new Class<?>[]{List.class}, new Object[]{dropped});
            throw new AssertionError("machine reply that dropped a DNT sentinel was accepted");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException
                            && "format/token lost".equals(expected.getCause().getMessage()),
                    "dropped DNT sentinel failed with wrong semantics: " + expected.getCause());
        }
    }

    private static void testDoNotTranslateTokenLoss() throws Exception {
        final String slot0 = "⟦MT0⟧";
        final AtomicInteger calls = new AtomicInteger();
        LegacyTranslator translator = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) {
                int call = calls.incrementAndGet();
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) {
                    String translated = source.replace("Welcome to ", "歡迎來到");
                    output.add(call == 1 ? translated.replace(slot0, "") : translated);
                }
                return output;
            }
        });
        try {
            LegacyConfig config = config();
            config.debugTranslationOverlay = true;
            config.doNotTranslateTerms.add("SkyBlock");
            check(translateAndWait(translator, "Welcome to SkyBlock!", false, config) == null,
                    "lost DNT placeholder was displayed instead of the original");
            check(translator.cached("Welcome to SkyBlock!", "zh-TW", false, config) == null,
                    "lost DNT placeholder was cached");
            check(debugHasStatus(translator, "failed (format/token lost)"),
                    "lost DNT placeholder was not classified as format/token lost");
            @SuppressWarnings("unchecked")
            Map<String, Object> backoffs = (Map<String, Object>) reflected(translator, "failedUntil");
            check(backoffs.size() == 1, "token loss did not leave exactly one temporary backoff");
            long until = ((Long) reflected(backoffs.values().iterator().next(), "untilMs")).longValue();
            check(until - System.currentTimeMillis() <= 1000L,
                    "token loss backoff was not the short temporary failure backoff");
            check(((AtomicInteger) reflected(translator, "scheduledRetries")).get() == 0,
                    "token loss scheduled an automatic retry");
            Thread.sleep(350L);
            check("歡迎來到SkyBlock!".equals(
                            translateAndWait(translator, "Welcome to SkyBlock!", false, config))
                            && calls.get() == 2,
                    "token loss was learned as a permanent keep-original");
        } finally {
            translator.shutdownForTests();
        }

        final AtomicInteger aiCalls = new AtomicInteger();
        LegacyTranslator ai = new LegacyTranslator();
        ai.setAiHttpForTests(new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) {
                String translated = text.replace("Welcome to ", "歡迎來到");
                return aiCalls.incrementAndGet() == 1 ? translated.replace("30001", "") : translated;
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            strict.debugTranslationOverlay = true;
            strict.doNotTranslateTerms.add("SkyBlock");
            check(translateAndWait(ai, "Welcome to SkyBlock!", true, strict) == null,
                    "AI reply without the DNT sentinel was displayed");
            check(debugHasStatus(ai, "failed (format/token lost)"),
                    "AI sentinel loss was not classified as format/token lost");
            long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400L);
            while (System.nanoTime() < settle) {
                ai.flushBatch();
                Thread.sleep(10L);
            }
            check(aiCalls.get() == 1, "AI sentinel loss auto-retried like a transient failure");
            check("歡迎來到SkyBlock!".equals(translateAndWait(ai, "Welcome to SkyBlock!", true, strict))
                            && aiCalls.get() == 2,
                    "AI sentinel loss was learned as a permanent keep-original");
        } finally {
            ai.shutdownForTests();
        }

        final AtomicInteger retryCalls = new AtomicInteger();
        LegacyTranslator retrying = new LegacyTranslator(new LegacyTranslator.TestBackend() {
            @Override public List<String> translate(List<String> canonicalSources) throws Exception {
                if (retryCalls.incrementAndGet() == 1) throw new Exception("HTTP 503 temporary outage");
                List<String> output = new ArrayList<String>();
                for (String source : canonicalSources) output.add(source.replace("Welcome to ", "歡迎來到"));
                return output;
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            strict.doNotTranslateTerms.add("SkyBlock");
            check(translateAndWait(retrying, "Welcome to SkyBlock!", true, strict) == null,
                    "transient DNT seed did not fail");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            String cached = null;
            while (cached == null && System.nanoTime() < deadline) {
                retrying.flushBatch();
                cached = retrying.cached("Welcome to SkyBlock!", "zh-TW", true, strict);
                Thread.sleep(10L);
            }
            check("歡迎來到SkyBlock!".equals(cached) && retryCalls.get() == 2,
                    "DNT canonical text lost its cache key across the transient retry: " + cached);
        } finally {
            retrying.shutdownForTests();
        }

        // Reviewer case: a term overlapping a decimal must not change the retry wire/cache key.
        final AtomicInteger seasonCalls = new AtomicInteger();
        final List<String> seasonWire = Collections.synchronizedList(new ArrayList<String>());
        LegacyTranslator season = new LegacyTranslator();
        season.setAiHttpForTests(new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) throws Exception {
                seasonWire.add(text);
                if (seasonCalls.incrementAndGet() == 1) throw new Exception("HTTP 503 temporary outage");
                return text.replace("Season", "賽季").replace(" is live", "開放中");
            }
        });
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            strict.doNotTranslateTerms.add("Season 2");
            check(translateAndWait(season, "Season 2.5 is live", true, strict) == null,
                    "season retry seed did not fail");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            String cached = null;
            while (cached == null && System.nanoTime() < deadline) {
                season.flushBatch();
                cached = season.cached("Season 2.5 is live", "zh-TW", true, strict);
                Thread.sleep(10L);
            }
            check(cached != null && cached.startsWith("賽季") && cached.contains("2.5")
                            && seasonCalls.get() == 2
                            && seasonWire.size() == 2 && seasonWire.get(0).equals(seasonWire.get(1)),
                    "transient retry changed the wire or cache key: " + seasonWire + " cached=" + cached);
        } finally {
            season.shutdownForTests();
        }
    }

    private static void testDoNotTranslateCacheLifecycle() throws Exception {
        final String slot0 = "⟦MT0⟧";
        RecordingBackend backend = new RecordingBackend(
                new java.util.function.Function<String, String>() {
                    @Override public String apply(String source) {
                        if ("Welcome to SkyBlock!".equals(source)) return "歡迎來到空島！";
                        if (("Welcome to " + slot0 + "!").equals(source)) {
                            return "歡迎來到" + slot0 + "！";
                        }
                        return "譯 " + source;
                    }
                });
        LegacyTranslator translator = new LegacyTranslator(backend);
        try {
            LegacyConfig config = config();
            check("歡迎來到空島！".equals(
                            translateAndWait(translator, "Welcome to SkyBlock!", false, config)),
                    "unmasked seed translation failed");
            config.doNotTranslateTerms.add("SkyBlock");
            check(translator.cached("Welcome to SkyBlock!", "zh-TW", false, config) == null,
                    "adding a DNT term kept serving the old unmasked row");
            check("歡迎來到SkyBlock！".equals(
                            translateAndWait(translator, "Welcome to SkyBlock!", false, config))
                            && backend.calls.get() == 2
                            && ("Welcome to " + slot0 + "!").equals(backend.received.get(1)),
                    "added DNT term did not take effect immediately");
            config.doNotTranslateTerms.clear();
            check("歡迎來到空島！".equals(
                            translateImmediate(translator, "Welcome to SkyBlock!", false, config))
                            && backend.calls.get() == 2,
                    "removing the DNT term did not bring the old row back without a request");
            config.doNotTranslateTerms.add("skyblock");
            check("歡迎來到SkyBlock！".equals(
                            translator.cached("Welcome to SkyBlock!", "zh-TW", false, config))
                            && backend.calls.get() == 2,
                    "re-adding the DNT term did not reuse its masked row");
            config.translationRequestsEnabled = false;
            check("歡迎來到SkyBlock！".equals(
                            translateImmediate(translator, "Welcome to SkyBlock!", false, config)),
                    "switch off hid a masked cache hit");
            check(translateImmediate(translator, "Enjoy SkyBlock today", false, config) == null,
                    "switch off did not show the original for a DNT miss");
            check("SkyBlock".equals(translateImmediate(translator, "SkyBlock", false, config)),
                    "switch off broke whole-line DNT passthrough");
            for (int i = 0; i < 3; i++) {
                translator.flushBatch();
                Thread.sleep(10L);
            }
            check(backend.calls.get() == 2, "switch off sent a DNT request");
            assertNoRequestState(translator, "switch-off DNT");
        } finally {
            translator.shutdownForTests();
        }
    }

    /**
     * Legacy sentinels are order-free inside their own batch item (translators reorder names and
     * numbers), but must still appear exactly once there; both transports share TokenBatch.
     */
    @SuppressWarnings("unchecked")
    private static void testSentinelOrderRelaxation() throws Exception {
        final String slot0 = "⟦MT0⟧";
        final String slot1 = "⟦MT1⟧";
        List<String> skyblock = Collections.singletonList("SkyBlock");
        Method encode = LegacyTranslator.class.getDeclaredMethod("encodeTemplateTokens", List.class);
        encode.setAccessible(true);
        Method buildWire = LegacyTranslator.class.getDeclaredMethod("buildBatchWire", List.class);
        buildWire.setAccessible(true);
        Method split = LegacyTranslator.class.getDeclaredMethod("splitBatchEncoded",
                String.class, int.class, int.class, List.class);
        split.setAccessible(true);

        // Machine transport (Google / experimental share TokenBatch): DNT + number swapped.
        LegacyTemplateText.Prepared coins =
                LegacyTemplateText.prepare("5 coins from SkyBlock", skyblock);
        check((slot0 + " coins from " + slot1).equals(coins.text())
                        && Arrays.asList("5", "SkyBlock").equals(coins.values()),
                "unexpected canonical coin sentence: " + coins.text());
        Object single = encode.invoke(null, Collections.singletonList(coins.text()));
        check(Collections.singletonList("30001 coins from 30002").equals(encodedTexts(single)),
                "coin sentence was not encoded to two sentinels: " + encodedTexts(single));
        String swapped = decode(single, "來自30002的30001枚硬幣");
        check(("來自" + slot1 + "的" + slot0 + "枚硬幣").equals(swapped),
                "swapped sentinels were not restored by identity: " + swapped);
        check("來自SkyBlock的5枚硬幣".equals(coins.restore(swapped)),
                "swapped DNT + number did not restore: " + coins.restore(swapped));
        expectTokenLost(single, "來自的30001枚硬幣", "lost sentinel");
        expectTokenLost(single, "來自30002的30001枚30001硬幣", "duplicated sentinel");
        expectTokenLost(single, "來自300025的30001枚硬幣", "sentinel glued to a following digit");
        expectTokenLost(single, "來自30002的130001枚硬幣", "sentinel glued to a preceding digit");

        // Two DNT terms swapped.
        LegacyTemplateText.Prepared hosts = LegacyTemplateText.prepare(
                "Hypixel hosts SkyBlock", Arrays.asList("Hypixel", "SkyBlock"));
        check((slot0 + " hosts " + slot1).equals(hosts.text()),
                "unexpected canonical host sentence: " + hosts.text());
        Object hostTokens = encode.invoke(null, Collections.singletonList(hosts.text()));
        check("SkyBlock由Hypixel主辦".equals(hosts.restore(decode(hostTokens, "30002由30001主辦"))),
                "two swapped DNT terms did not restore");

        // Anchored batch: order-free inside an item, but never across items.
        LegacyTemplateText.Prepared welcome =
                LegacyTemplateText.prepare("Welcome to SkyBlock!", skyblock);
        Object batch = encode.invoke(null, Arrays.asList(coins.text(), welcome.text()));
        check(Arrays.asList("30001 coins from 30002", "Welcome to 30003!").equals(encodedTexts(batch)),
                "batch sentinels were not numbered per item: " + encodedTexts(batch));
        Object batchWire = buildWire.invoke(null, encodedTexts(batch));
        String wireText = (String) reflected(batchWire, "text");
        int anchorBase = ((Integer) reflected(batchWire, "anchorBase")).intValue();
        List<String> sentinels = (List<String>) invokeNoArgs(batch, "sentinels");
        String goodReply = wireText.replace("30001 coins from 30002", "來自30002的30001枚硬幣")
                .replace("Welcome to 30003!", "歡迎來到30003！");
        List<String> decoded = (List<String>) invoke(batch, "decodeItems", new Class<?>[]{List.class},
                new Object[]{split.invoke(null, goodReply, 2, anchorBase, sentinels)});
        check("來自SkyBlock的5枚硬幣".equals(coins.restore(decoded.get(0)))
                        && "歡迎來到SkyBlock！".equals(welcome.restore(decoded.get(1))),
                "batch with an in-item swap did not restore: " + decoded);
        String crossReply = wireText.replace("30001 coins from 30002", "來自30002的30003枚硬幣")
                .replace("Welcome to 30003!", "歡迎來到30001！");
        expectItemsTokenLost(batch, (List<String>) split.invoke(null, crossReply, 2, anchorBase,
                sentinels), "sentinels swapped across batch items");
        String leakReply = wireText.replace("Welcome to 30003!", "歡迎來到30003和30001！");
        expectItemsTokenLost(batch, (List<String>) split.invoke(null, leakReply, 2, anchorBase,
                sentinels), "sentinel copied into another batch item");
        String forwardReply = wireText.replace("30001 coins from 30002", "來自30002的枚硬幣")
                .replace("Welcome to 30003!", "30001歡迎來到30003！");
        expectItemsTokenLost(batch, (List<String>) split.invoke(null, forwardReply, 2, anchorBase,
                sentinels), "sentinel moved forward into the next batch item");

        // End to end through the real AI HTTP path (inline fake; strict AI, no Google fallback).
        final AtomicReference<String> mode = new AtomicReference<String>("swap");
        final List<String> wire = Collections.synchronizedList(new ArrayList<String>());
        LegacyTranslator.TestAiHttp fake = new LegacyTranslator.TestAiHttp() {
            @Override public String request(String text, String target, LegacyConfig requestConfig,
                                            String apiKey) {
                wire.add(text);
                String current = mode.get();
                if ("cross".equals(current)) {
                    return text.replace("30001 coins from 30002", "來自30002的30003枚硬幣")
                            .replace("Welcome to 30003!", "歡迎來到30001！");
                }
                if ("lost".equals(current)) {
                    return text.replace("30001 coins from 30002", "來自的30001枚硬幣");
                }
                if ("duplicate".equals(current)) {
                    return text.replace("30001 coins from 30002", "來自30002的30001枚30001硬幣");
                }
                return text.replace("30001 coins from 30002", "來自30002的30001枚硬幣")
                        .replace("30001 hosts 30002", "30002由30001主辦")
                        .replace("Welcome to 30003!", "歡迎來到30003！");
            }
        };
        LegacyTranslator ai = new LegacyTranslator();
        ai.setAiHttpForTests(fake);
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            strict.doNotTranslateTerms.add("SkyBlock");
            check("來自SkyBlock的5枚硬幣".equals(
                            translateAndWait(ai, "5 coins from SkyBlock", true, strict)),
                    "AI path rejected or mis-restored a swapped DNT + number");
            check("來自SkyBlock的12枚硬幣".equals(
                            ai.cached("12 coins from SkyBlock", "zh-TW", true, strict)),
                    "swapped canonical row was not cached for other numbers");
            LegacyConfig two = strict.snapshotForRequest();
            two.doNotTranslateTerms.add("Hypixel");
            check("SkyBlock由Hypixel主辦".equals(
                            translateAndWait(ai, "Hypixel hosts SkyBlock", true, two)),
                    "AI path rejected two swapped DNT terms");
            check(wire.size() == 2, "unexpected AI request count: " + wire);
        } finally {
            ai.shutdownForTests();
        }

        LegacyTranslator batched = new LegacyTranslator();
        batched.setAiHttpForTests(fake);
        wire.clear();
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            strict.debugTranslationOverlay = true;
            strict.doNotTranslateTerms.add("SkyBlock");
            mode.set("cross");
            String[] crossed = translatePairAndWait(batched, "5 coins from SkyBlock",
                    "Welcome to SkyBlock!", strict);
            check(crossed[0] == null && crossed[1] == null,
                    "cross-item sentinels were accepted: " + Arrays.asList(crossed));
            check(wire.size() == 1 && wire.get(0).contains("30001 coins from 30002")
                            && wire.get(0).contains("Welcome to 30003!"),
                    "cross-item scenario was not one anchored AI batch: " + wire);
            int tokenLost = 0;
            for (LegacyTranslator.DebugEntry entry : batched.debugSnapshot()) {
                if ("failed (format/token lost)".equals(entry.status)) tokenLost++;
            }
            check(tokenLost == 2, "cross-item batch was not classified as format/token lost");
            check(privateSize(batched, "failedUntil") == 2
                            && ((AtomicInteger) reflected(batched, "scheduledRetries")).get() == 0,
                    "cross-item failure was not a short temporary backoff without auto retry");
            Thread.sleep(350L);
            mode.set("swap");
            String[] recovered = translatePairAndWait(batched, "5 coins from SkyBlock",
                    "Welcome to SkyBlock!", strict);
            check("來自SkyBlock的5枚硬幣".equals(recovered[0])
                            && "歡迎來到SkyBlock！".equals(recovered[1]) && wire.size() == 2,
                    "cross-item failure was learned as permanent or the in-item swap batch failed: "
                            + Arrays.asList(recovered));
        } finally {
            batched.shutdownForTests();
        }

        LegacyTranslator broken = new LegacyTranslator();
        broken.setAiHttpForTests(fake);
        try {
            LegacyConfig strict = config();
            strict.disableGoogleFallbackForAi = true;
            strict.debugTranslationOverlay = true;
            strict.doNotTranslateTerms.add("SkyBlock");
            mode.set("lost");
            check(translateAndWait(broken, "5 coins from SkyBlock", true, strict) == null,
                    "AI reply without a sentinel was displayed");
            Thread.sleep(350L);
            mode.set("duplicate");
            check(translateAndWait(broken, "5 coins from SkyBlock", true, strict) == null,
                    "AI reply with a duplicated sentinel was displayed");
            check(debugHasStatus(broken, "failed (format/token lost)"),
                    "lost/duplicated sentinel was not format/token lost");
            Thread.sleep(350L);
            mode.set("swap");
            check("來自SkyBlock的5枚硬幣".equals(
                            translateAndWait(broken, "5 coins from SkyBlock", true, strict)),
                    "sentinel loss was learned as a permanent keep-original");
        } finally {
            broken.shutdownForTests();
        }
    }

    private static String[] translatePairAndWait(LegacyTranslator translator, String first,
                                                 String second, LegacyConfig config)
            throws Exception {
        final String[] results = new String[]{"not-called", "not-called"};
        final CountDownLatch done = new CountDownLatch(2);
        String[] sources = {first, second};
        for (int i = 0; i < 2; i++) {
            final int index = i;
            translator.translate(sources[i], "zh-TW", true, false, config, new Consumer<String>() {
                @Override public void accept(String value) {
                    results[index] = value;
                    done.countDown();
                }
            });
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (!done.await(10L, TimeUnit.MILLISECONDS)) {
            check(System.nanoTime() < deadline, "paired translation timed out");
            translator.flushBatch();
        }
        return results;
    }

    private static void expectTokenLost(Object tokenBatch, String value, String label)
            throws Exception {
        try {
            decode(tokenBatch, value);
            throw new AssertionError(label + " was accepted: " + value);
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException
                            && "format/token lost".equals(expected.getCause().getMessage()),
                    label + " failed with wrong semantics: " + expected.getCause());
        }
    }

    private static void expectItemsTokenLost(Object tokenBatch, List<String> items, String label)
            throws Exception {
        try {
            invoke(tokenBatch, "decodeItems", new Class<?>[]{List.class}, new Object[]{items});
            throw new AssertionError(label + " was accepted: " + items);
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException
                            && "format/token lost".equals(expected.getCause().getMessage()),
                    label + " failed with wrong semantics: " + expected.getCause());
        }
    }

    private static String translateAndWait(LegacyTranslator translator, String source, boolean ai,
                                           LegacyConfig config) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> value = new AtomicReference<String>("not-called");
        translator.translate(source, "zh-TW", ai, false, config, new Consumer<String>() {
            @Override public void accept(String result) {
                value.set(result);
                done.countDown();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (!done.await(10L, TimeUnit.MILLISECONDS)) {
            check(System.nanoTime() < deadline, "translation callback timed out: " + source);
            translator.flushBatch();
        }
        return value.get();
    }

    /** Returns what the callback delivered synchronously, or "not-called". */
    private static String translateImmediate(LegacyTranslator translator, String source, boolean ai,
                                             LegacyConfig config) {
        final AtomicReference<String> value = new AtomicReference<String>("not-called");
        translator.translate(source, "zh-TW", ai, false, config, new Consumer<String>() {
            @Override public void accept(String result) { value.set(result); }
        });
        return value.get();
    }

    private static void assertNoRequestState(LegacyTranslator translator, String label)
            throws Exception {
        check(translator.inFlightCountForTests() == 0 && translator.waiterCountForTests() == 0,
                label + " left flights/waiters");
        check(privateSize(translator, "pending") == 0, label + " left queued work");
        check(privateSize(translator, "failedUntil") == 0, label + " wrote a failure backoff");
        check(privateSize(translator, "keyUnavailableUntil") == 0, label + " cooled down an API key");
        check(((AtomicInteger) reflected(translator, "scheduledRetries")).get() == 0,
                label + " left a scheduled retry");
        for (LegacyTranslator.DebugEntry entry : translator.debugSnapshot()) {
            check(!entry.status.startsWith("failed"), label + " logged a failure: " + entry.status);
        }
    }

    private static boolean debugHasStatus(LegacyTranslator translator, String status) {
        for (LegacyTranslator.DebugEntry entry : translator.debugSnapshot()) {
            if (status.equals(entry.status)) return true;
        }
        return false;
    }

    private static JsonObject tokenParams(String threadId, int totalValue) {
        JsonObject total = new JsonObject();
        total.addProperty("inputTokens", totalValue);
        total.addProperty("cachedInputTokens", 0);
        total.addProperty("outputTokens", 0);
        total.addProperty("reasoningOutputTokens", 0);
        total.addProperty("totalTokens", totalValue);
        JsonObject tokenUsage = new JsonObject();
        tokenUsage.add("total", total);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("tokenUsage", tokenUsage);
        return params;
    }

    private static Method privateMethod(Class<?> owner, String name, Class<?>... types)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static int privateSize(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(owner);
        if (value instanceof Map<?, ?>) return ((Map<?, ?>) value).size();
        if (value instanceof java.util.Collection<?>) return ((java.util.Collection<?>) value).size();
        throw new AssertionError("unsupported reflected state: " + name);
    }

    private static Object reflected(Object owner, String name) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(owner, value);
                return;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invokeNoArgs(Object owner, String name) throws Exception {
        return invoke(owner, name, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object owner, String name, Class<?>[] types, Object[] values)
            throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(owner, values);
    }

    private static void expectInvocationIOException(Method method, Object owner, Object... args)
            throws Exception {
        try {
            method.invoke(owner, args);
            throw new AssertionError("expected bounded IOException from " + method.getName());
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IOException,
                    method.getName() + " failed with wrong cause: " + expected.getCause());
        }
    }

    private static JsonObject completedParams(String turnId) {
        JsonObject turn = new JsonObject();
        turn.addProperty("id", turnId);
        turn.addProperty("status", "completed");
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        return params;
    }

    private static LegacyConfig config() {
        LegacyConfig config = new LegacyConfig();
        config.batchWindowMs = 0;
        config.requestCooldownMs = 0;
        config.failureBackoffMs = 250;
        return config;
    }

    private static String letters(int value) {
        StringBuilder result = new StringBuilder();
        int current = value;
        do {
            result.append((char) ('a' + current % 26));
            current = current / 26;
        } while (current > 0);
        return result.toString();
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static String repeatMarker(String marker, int count) {
        StringBuilder value = new StringBuilder(marker.length() * count);
        for (int i = 0; i < count; i++) value.append(marker);
        return value.toString();
    }

    /** Inline translator fake that records every canonical source it receives. */
    private static final class RecordingBackend implements LegacyTranslator.TestBackend {
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger calls = new AtomicInteger();
        private final java.util.function.Function<String, String> answer;

        RecordingBackend(java.util.function.Function<String, String> answer) {
            this.answer = answer;
        }

        @Override public List<String> translate(List<String> canonicalSources) {
            calls.incrementAndGet();
            List<String> output = new ArrayList<String>(canonicalSources.size());
            for (String source : canonicalSources) {
                received.add(source);
                output.add(answer == null ? "譯 " + source : answer.apply(source));
            }
            return output;
        }
    }

    private static final class FakeProcess extends Process {
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final InputStream empty = new ByteArrayInputStream(new byte[0]);
        private final InputStream stderr;

        FakeProcess() { this(new ByteArrayInputStream(new byte[0])); }

        FakeProcess(InputStream stderr) { this.stderr = stderr; }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return empty; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() throws InterruptedException {
            while (alive.get()) Thread.sleep(1L);
            return 0;
        }
        @Override public int exitValue() {
            if (alive.get()) throw new IllegalThreadStateException("alive");
            return 0;
        }
        @Override public void destroy() { alive.set(false); }
    }

    private static final class GatedStderrInputStream extends InputStream {
        private final byte[] data;
        private int offset;
        private final AtomicBoolean gated = new AtomicBoolean();
        final CountDownLatch readerEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch eofReached = new CountDownLatch(1);

        GatedStderrInputStream(String line) {
            data = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        private void awaitRelease() throws IOException {
            if (!gated.compareAndSet(false, true)) return;
            readerEntered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new IOException("stderr test barrier timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("stderr test barrier interrupted", interrupted);
            }
        }

        @Override public int read() throws IOException {
            awaitRelease();
            if (offset >= data.length) {
                eofReached.countDown();
                return -1;
            }
            return data[offset++] & 0xff;
        }

        @Override public int read(byte[] target, int targetOffset, int length) throws IOException {
            awaitRelease();
            if (offset >= data.length) {
                eofReached.countDown();
                return -1;
            }
            int count = Math.min(length, data.length - offset);
            System.arraycopy(data, offset, target, targetOffset, count);
            offset += count;
            return count;
        }
    }

    private static final class LockAssertingWriter extends StringWriter {
        private final Object requiredLock;
        boolean wroteWhileLocked;

        LockAssertingWriter(Object requiredLock) {
            this.requiredLock = requiredLock;
        }

        @Override public void write(char[] buffer, int offset, int length) {
            if (!Thread.holdsLock(requiredLock)) {
                throw new AssertionError("Codex request write escaped lifecycle lock");
            }
            wroteWhileLocked = true;
            super.write(buffer, offset, length);
        }
    }

    private static final class FailingWriter extends java.io.Writer {
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("synthetic Codex write failure");
        }
        @Override public void flush() throws IOException {
            throw new IOException("synthetic Codex flush failure");
        }
        @Override public void close() {}
    }

    private static final class SyntheticConnection extends HttpURLConnection {
        private final InputStream input;
        SyntheticConnection(InputStream input) throws Exception {
            super(new URL("http://inline.invalid"));
            this.input = input;
        }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return input; }
    }

    private static final class RepeatingInputStream extends InputStream {
        private int remaining;
        RepeatingInputStream(int remaining) { this.remaining = remaining; }
        @Override public int read() {
            if (remaining <= 0) return -1;
            remaining--;
            return 'a';
        }
        @Override public int read(byte[] target, int offset, int length) {
            if (remaining <= 0) return -1;
            int count = Math.min(length, remaining);
            Arrays.fill(target, offset, offset + count, (byte) 'a');
            remaining -= count;
            return count;
        }
    }

    private static final class EqualEntry {
        @Override public boolean equals(Object other) { return other instanceof EqualEntry; }
        @Override public int hashCode() { return 1; }
    }
}
