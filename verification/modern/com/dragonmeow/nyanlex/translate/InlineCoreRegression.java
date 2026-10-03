package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.cache.FileStore;
import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.ChatDeliveryQueue;
import com.dragonmeow.nyanlex.service.ChatDeliverySession;
import com.dragonmeow.nyanlex.service.ChatRequestProfile;
import com.dragonmeow.nyanlex.service.RecoveryAssembly;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Loader-independent release regression that is intentionally Java 16 compatible.
 * It executes the compiled target classes instead of retesting a copied source tree.
 */
public final class InlineCoreRegression {
    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private static TranslatorConfig translatingConfig() {
        TranslatorConfig config = new TranslatorConfig();
        config.translationRequestsEnabled = true;
        // These fixtures exercise automatic requests; non-chat Google surfaces are manual.
        config.aiTooltip = true;
        config.aiScoreboard = true;
        config.aiName = true;
        config.aiBossBar = true;
        config.aiTitle = true;
        config.aiActionBar = true;
        config.aiBook = true;
        config.aiScreenText = true;
        return config;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path runtime = Paths.get(args[0]);
        Files.createDirectories(runtime);
        readableParagraphDoesNotRepeatCachedRequests();
        quantityTemplatesShareOneRequest();
        numericMarkersRejectSubstringCollisions();
        chatDeliveryModesBehaveDifferently();
        allDeliveryCompletionPermutations();
        chatDeliverySessionBoundaries();
        requestProfileBoundaries();
        recoveryAssemblyHostileState();
        resultProgressHostileState();
        globalAnnouncementBudgetHostileState();
        int codexHostileCases = CodexStateHostileSuite.runAll();
        check(codexHostileCases == 21,
                "modern Codex hostile suite count changed: " + codexHostileCases);
        configRoundTripsAndClamps();
        schemaFourJournalReopens(runtime.resolve("inline-core-cache.json"));
        int v107Cases = RequestSwitchAndTermsSuite.runAll();
        check(v107Cases == RequestSwitchAndTermsSuite.CASES,
                "1.0.7 request-switch/do-not-translate case count changed: " + v107Cases);
        // verify-release-matrix.ps1 matches this complete line (Get-HarnessSpec, modern core);
        // changing CASES therefore requires updating its pinned "v107=" count as well.
        System.out.println("INLINE_CORE_OK hostile=24 codex=21 v107=" + RequestSwitchAndTermsSuite.CASES
                + " coverage=recovery-assembly,result-progress,batch-budget,codex-state,"
                + "request-switch,do-not-translate");
    }

    private static void readableParagraphDoesNotRepeatCachedRequests() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        Translator backend = (text, language) -> {
            if (calls.incrementAndGet() == 2) throw new TranslationException("review unavailable");
            return new TranslationResult("第一行第二行", null);
        };
        TranslationCache cache = new TranslationCache(backend, "zh-TW", tasks::add, 100);
        String source = "First line⟦PB0⟧Second line";
        cache.requestAsync(source);
        tasks.remove().run();
        check("第一行第二行".equals(cache.getCached(source)), "readable paragraph was withheld");
        tasks.remove().run();
        for (int i = 0; i < 20; i++) cache.requestAsync(source);
        check(tasks.isEmpty() && calls.get() == 2, "cached paragraph repeated its review");
        check("第一行第二行".equals(cache.getCached(source)), "failed review lost readable text");
        System.out.println("INLINE_PARAGRAPH_RECOVERY_OK cached_requests=0 review_attempts=1");
    }

    private static void quantityTemplatesShareOneRequest() {
        CountingTranslator translator = new CountingTranslator();
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);

        cache.requestBatched("Reward x1 Diamonds");
        cache.flushBatch();
        cache.flushBatch();
        check(translator.requests == 1, "first quantity did not issue exactly one request");
        check(translator.batches.equals(List.of(List.of("Reward ⟦MT0⟧ Diamonds"))),
                "backend saw a non-templated quantity: " + translator.batches);

        for (int amount = 2; amount <= 1_000; amount++) {
            String source = "Reward x" + amount + " Diamonds";
            cache.requestBatched(source);
            cache.flushBatch();
            cache.flushBatch();
            check(translator.requests == 1, source + " issued a duplicate request");
            check(("T:" + source).equals(cache.getCached(source)),
                    source + " was not restored from the shared template");
        }

        for (String source : List.of(
                "Reward X100 Diamonds",
                "Reward x1,000 Diamonds",
                "Reward x1.5 Diamonds",
                "Reward x5% Diamonds",
                "Reward x2k Diamonds",
                "Reward X3M Diamonds",
                "Reward x86 Diamonds")) {
            cache.requestBatched(source);
            cache.flushBatch();
            cache.flushBatch();
            check(translator.requests == 1, source + " issued a duplicate request");
            check(("T:" + source).equals(cache.getCached(source)),
                    source + " did not restore its exact quantity");
        }

        for (String boundary : List.of(
                "0x1F", "2x2", "1920x1080", "x100kg", "x100xp", "x100foo",
                "_x100", "box100")) {
            check(!TemplateText.prepare(boundary).changed(),
                    "quantity matcher damaged a boundary case: " + boundary);
        }

        TemplateText.Prepared reserved =
                TemplateText.prepare("Literal ⟦MT0⟧ Reward x1");
        check("Literal ⟦MT0⟧ Reward ⟦MT1⟧".equals(reserved.text()),
                "generated quantity reused a literal reserved MT slot");
        check("字面 ⟦MT0⟧ 獎勵 x1".equals(
                        reserved.restore("字面 ⟦MT0⟧ 獎勵 ⟦MT1⟧")),
                "reserved MT literal was overwritten during restore");
        for (String literal : List.of("⟦MT0⟧", "⟦ MT 0 ⟧", "⟦ mt 42 ⟧")) {
            TemplateText.Prepared literalOnly = TemplateText.prepare(literal);
            check(!literalOnly.changed() && literal.equals(literalOnly.text()),
                    "literal MT marker was nested: " + literalOnly.text());
            TemplateText.Prepared withQuantity = TemplateText.prepare(literal + " x100");
            check((literal + (literal.contains("42") ? " ⟦MT0⟧" : " ⟦MT1⟧"))
                            .equals(withQuantity.text()),
                    "literal MT marker span was overlapped: " + withQuantity.text());
            check((literal + " x100").equals(withQuantity.restore(withQuantity.text())),
                    "literal MT marker did not restore verbatim");
        }

        CountingTranslator pureTranslator = new CountingTranslator();
        TranslationCache pureCache = new TranslationCache(pureTranslator, "zh-TW", DIRECT, 100);
        List<String> callbacks = new ArrayList<String>();
        pureCache.requestAsync("x100", callbacks::add);
        pureCache.requestAsync("§ax100", callbacks::add);
        check(callbacks.equals(List.of("x100", "§ax100")),
                "pure quantity callbacks did not receive their originals: " + callbacks);
        check("x100".equals(pureCache.getCached("x100"))
                        && "§ax100".equals(pureCache.getCached("§ax100")),
                "pure quantities were not identity cache hits");
        check(pureTranslator.requests == 0,
                "pure x-prefixed quantities reached the backend");
    }

    private static void chatDeliveryModesBehaveDifferently() {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<Entry>();
        Entry first = new Entry("first");
        Entry second = new Entry("second");
        Entry third = new Entry("third");
        queue.addLast(first);
        queue.addLast(second);
        queue.addLast(third);
        queue.markReady(third);
        queue.markReady(second);

        check(queue.drainReady(true).isEmpty(),
                "ordered mode released entries behind an unfinished head");
        check(queue.drainReady(false).equals(List.of(third, second)),
                "ready-first mode did not preserve actual completion order");
        check(queue.peekFirst() == first, "ready-first mode removed the unfinished head");

        queue.markReady(first);
        queue.markReady(first);
        check(queue.drainReady(false).equals(List.of(first)),
                "duplicate completion duplicated or lost the final entry");
        check(queue.isEmpty(), "delivery queue leaked an entry");
    }

    private static void numericMarkersRejectSubstringCollisions() {
        Map<String, String> replacements = new java.util.LinkedHashMap<String, String>();
        replacements.put("30001", "⟦MT0⟧");
        replacements.put("30002", "⟦MT1⟧");
        check("A⟦MT0⟧⟦MT1⟧B".equals(
                        NumericMarkerCodec.restoreExactlyOnce("A3000130002B", replacements)),
                "adjacent numeric markers were not restored exactly");
        check(NumericMarkerCodec.restoreExactlyOnce("A130001 30002B", replacements) == null,
                "numeric marker substring was falsely accepted");
        check(NumericMarkerCodec.restoreExactlyOnce("A300010 30002B", replacements) == null,
                "numeric marker with a digit suffix was falsely accepted");
        check(List.of("70005alpha", "beta70006").equals(
                        NumericMarkerCodec.extractAnchored(
                                "7000170005alpha7000270003beta7000670004", 2, 70001, 6)),
                "adjacent anchor and protected markers were not separated");
    }

    private static void allDeliveryCompletionPermutations() {
        Entry[] entries = new Entry[8];
        int[] order = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Entry("entry-" + i);
            order[i] = i;
        }

        int permutations = 0;
        int switchScenarios = 0;
        do {
            verifyDeliveryPermutation(entries, order, true);
            verifyDeliveryPermutation(entries, order, false);
            for (int split = 0; split <= entries.length; split++) {
                verifyModeSwitchPermutation(entries, order, split, true);
                verifyModeSwitchPermutation(entries, order, split, false);
                switchScenarios += 2;
            }
            permutations++;
        } while (nextPermutation(order));
        check(permutations == 40_320,
                "did not enumerate all eight-entry completion orders: " + permutations);
        check(switchScenarios == 725_760,
                "did not enumerate all delivery mode switches: " + switchScenarios);
    }

    private static void verifyDeliveryPermutation(Entry[] entries, int[] order,
                                                  boolean preserveReceiveOrder) {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<Entry>();
        for (Entry entry : entries) queue.addLast(entry);

        List<Entry> delivered = new ArrayList<Entry>();
        for (int index : order) {
            queue.markReady(entries[index]);
            delivered.addAll(queue.drainReady(preserveReceiveOrder));
        }

        List<Entry> expected = new ArrayList<Entry>();
        if (preserveReceiveOrder) {
            for (Entry entry : entries) expected.add(entry);
        } else {
            for (int index : order) expected.add(entries[index]);
        }
        check(expected.equals(delivered),
                "delivery mismatch for policy=" + preserveReceiveOrder);
        check(queue.isEmpty(), "delivery permutation leaked an entry");
    }

    private static void verifyModeSwitchPermutation(Entry[] entries, int[] order,
                                                    int split, boolean initialOrdered) {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<Entry>();
        List<Entry> remaining = new ArrayList<Entry>();
        List<Entry> readyOrder = new ArrayList<Entry>();
        Map<Entry, Boolean> ready = new IdentityHashMap<Entry, Boolean>();
        Map<Entry, Boolean> delivered = new IdentityHashMap<Entry, Boolean>();
        for (Entry entry : entries) {
            queue.addLast(entry);
            remaining.add(entry);
        }

        for (int step = 0; step < order.length; step++) {
            Entry completed = entries[order[step]];
            queue.markReady(completed);
            if (containsIdentity(remaining, completed) && !ready.containsKey(completed)) {
                ready.put(completed, Boolean.TRUE);
                readyOrder.add(completed);
            }
            boolean ordered = step < split ? initialOrdered : !initialOrdered;
            List<Entry> actual = queue.drainReady(ordered);
            List<Entry> expected = modelDrain(remaining, readyOrder, ready, ordered);
            check(expected.equals(actual), "mode-switch mismatch split=" + split
                    + " initial=" + initialOrdered + " step=" + step);
            for (Entry entry : actual) {
                check(delivered.put(entry, Boolean.TRUE) == null,
                        "mode switch delivered an entry twice");
            }
        }
        check(queue.isEmpty() && remaining.isEmpty() && readyOrder.isEmpty()
                        && delivered.size() == entries.length,
                "mode-switch scenario leaked or lost an entry");
    }

    private static List<Entry> modelDrain(List<Entry> remaining, List<Entry> readyOrder,
                                          Map<Entry, Boolean> ready, boolean ordered) {
        List<Entry> drained = new ArrayList<Entry>();
        if (ordered) {
            while (!remaining.isEmpty() && ready.containsKey(remaining.get(0))) {
                Entry entry = remaining.remove(0);
                ready.remove(entry);
                removeIdentity(readyOrder, entry);
                drained.add(entry);
            }
            return drained;
        }
        while (!readyOrder.isEmpty()) {
            Entry entry = readyOrder.remove(0);
            if (!ready.containsKey(entry) || !removeIdentity(remaining, entry)) continue;
            ready.remove(entry);
            drained.add(entry);
        }
        return drained;
    }

    private static boolean containsIdentity(List<Entry> entries, Entry target) {
        for (Entry entry : entries) if (entry == target) return true;
        return false;
    }

    private static boolean removeIdentity(List<Entry> entries, Entry target) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != target) continue;
            entries.remove(i);
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

    private static void configRoundTripsAndClamps() throws Exception {
        TranslatorConfig config = translatingConfig();
        config.deliverChatTranslationsInOrder = false;
        config.workerThreads = Integer.MAX_VALUE;
        config.cacheMaxSize = Integer.MAX_VALUE;
        config.persistentCacheMaxEntries = Integer.MAX_VALUE;
        config.normalized();

        check(config.workerThreads == TranslatorConfig.MAX_WORKER_THREADS,
                "worker thread limit was not clamped");
        check(config.cacheMaxSize == TranslatorConfig.MAX_MEMORY_CACHE_ENTRIES,
                "memory cache limit was not clamped");
        check(config.persistentCacheMaxEntries == TranslatorConfig.MAX_PERSISTENT_CACHE_ENTRIES,
                "persistent cache limit was not clamped");

        StringWriter json = new StringWriter();
        config.writeTo(json);
        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(json.toString()));
        check(!loaded.deliverChatTranslationsInOrder,
                "chat delivery mode did not survive config serialization");
        check(loaded.persistentCacheMaxEntries == TranslatorConfig.MAX_PERSISTENT_CACHE_ENTRIES,
                "persistent cache limit did not survive config serialization");
    }

    private static void chatDeliverySessionBoundaries() {
        ChatDeliverySession<SessionEntry> capped = session();
        for (int i = 1; i <= 512; i++) {
            capped.add(new SessionEntry(i, false, "line-" + i));
        }
        SessionEntry newest = new SessionEntry(513, false, "line-513");
        ChatDeliverySession.Admission<SessionEntry> admission = capped.add(newest);
        check(capped.trackedSize() == 512 && capped.queuedSize() == 512,
                "chat session exceeded its hard cap");
        check(admission.evicted() != null && admission.evicted().id == 1L
                        && !admission.evictedWasDisplayed(),
                "hard cap did not evict the oldest unfinished entry");
        check(admission.evicted().richOriginalLines.equals(
                        List.of("frame-open", "frame-body", "frame-close")),
                "hard-cap eviction lost the rich original frame");
        check(capped.get(1L, admission.epoch()) == null
                        && capped.get(513L, admission.epoch()) == newest,
                "hard-cap index did not retire/admit atomically");

        ChatDeliverySession<SessionEntry> displayedFirst = session();
        SessionEntry displayed = new SessionEntry(1, true, "shown");
        displayedFirst.add(displayed);
        check(displayedFirst.timeoutFirstQueued() == displayed,
                "timeout did not remove the receive-order head");
        for (int i = 2; i <= 512; i++) {
            displayedFirst.add(new SessionEntry(i, false, "line-" + i));
        }
        ChatDeliverySession.Admission<SessionEntry> displayedEviction =
                displayedFirst.add(new SessionEntry(513, false, "line-513"));
        check(displayedEviction.evicted() == displayed
                        && displayedEviction.evictedWasDisplayed()
                        && displayedFirst.trackedSize() == 512
                        && displayedFirst.queuedSize() == 512,
                "cap did not prefer the displayed late-recovery record");

        TranslatorConfig config = translatingConfig();
        Object connection = new Object();
        Object world = new Object();
        ChatDeliverySession<SessionEntry> context = session();
        context.observe(connection, world, profile(config), false);
        SessionEntry pending = new SessionEntry(1, false, "pending");
        long oldEpoch = context.add(pending).epoch();
        ChatDeliverySession.Transition<SessionEntry> disconnect =
                context.observe(null, null, profile(config), false);
        check(disconnect.kind() == ChatDeliverySession.TransitionKind.SILENT_CLEAR
                        && disconnect.retired().equals(List.of(pending))
                        && disconnect.originals().isEmpty()
                        && context.get(1L, oldEpoch) == null
                        && context.trackedSize() == 0 && context.queuedSize() == 0,
                "disconnect did not silently clear and invalidate the epoch");

        ChatDeliverySession<SessionEntry> profileSwitch = session();
        profileSwitch.observe(connection, world, profile(config), false);
        SessionEntry first = new SessionEntry(1, false, "first");
        SessionEntry shown = new SessionEntry(2, true, "shown");
        SessionEntry third = new SessionEntry(3, false, "third");
        long profileEpoch = profileSwitch.add(first).epoch();
        profileSwitch.add(shown);
        profileSwitch.add(third);
        TranslatorConfig changedTarget = translatingConfig();
        changedTarget.targetLang = "ja-JP";
        ChatDeliverySession.Transition<SessionEntry> switched = profileSwitch.observe(
                connection, world, profile(changedTarget), false);
        check(switched.kind() == ChatDeliverySession.TransitionKind.FLUSH_ORIGINALS
                        && switched.retired().equals(List.of(first, shown, third))
                        && switched.originals().equals(List.of(first, third))
                        && profileSwitch.get(1L, profileEpoch) == null
                        && profileSwitch.trackedSize() == 0,
                "request-profile switch did not flush only undisplayed originals in order");

        ChatDeliverySession<SessionEntry> deliveryToggle = session();
        config = translatingConfig();
        deliveryToggle.observe(connection, world, profile(config), false);
        pending = new SessionEntry(1, false, "pending");
        deliveryToggle.add(pending);
        config.deliverChatTranslationsInOrder = false;
        check(deliveryToggle.observe(connection, world, profile(config), false).kind()
                        == ChatDeliverySession.TransitionKind.NONE
                        && deliveryToggle.trackedSize() == 1,
                "presentation-only delivery mode invalidated a live request");
        ChatDeliverySession.Transition<SessionEntry> forced =
                deliveryToggle.forceOriginalOnly();
        check(forced.kind() == ChatDeliverySession.TransitionKind.FLUSH_ORIGINALS
                        && forced.originals().equals(List.of(pending))
                        && deliveryToggle.trackedSize() == 0,
                "original-only toggle did not flush immediately");

        ChatDeliverySession<SessionEntry> late = session();
        pending = new SessionEntry(1, false, "pending");
        long lateEpoch = late.add(pending).epoch();
        check(late.timeoutFirstQueued() == pending,
                "timeout did not retain the tracked late-recovery entry");
        pending.displayed = true;
        check(late.get(1L, lateEpoch) == pending
                        && late.retire(1L, lateEpoch) == pending
                        && late.get(1L, lateEpoch) == null
                        && late.retire(1L, lateEpoch) == null
                        && late.trackedSize() == 0 && late.queuedSize() == 0,
                "timeout did not provide exactly one late replacement opportunity");
    }

    private static void requestProfileBoundaries() {
        TranslatorConfig machine = translatingConfig();
        ChatRequestProfile machineBefore = profile(machine);
        machine.aiBaseUrl = "https://unused.invalid";
        machine.aiModel = "unused-api";
        machine.aiUseCodex = true;
        machine.codexModel = "unused-codex";
        machine.codexReasoningEffort = "high";
        machine.aiGlossary.add("unused=unused");
        check(machineBefore.equals(profile(machine)),
                "inactive AI settings changed a machine request profile");

        TranslatorConfig apiAi = translatingConfig();
        apiAi.aiChat = true;
        ChatRequestProfile apiBefore = profile(apiAi);
        apiAi.codexModel = "inactive-codex";
        apiAi.codexReasoningEffort = "high";
        check(apiBefore.equals(profile(apiAi)),
                "inactive Codex settings changed an API-AI request profile");

        TranslatorConfig codexAi = translatingConfig();
        codexAi.aiChat = true;
        codexAi.aiUseCodex = true;
        codexAi.disableGoogleFallbackForAi = true;
        ChatRequestProfile codexBefore = profile(codexAi);
        codexAi.aiBaseUrl = "https://inactive.invalid";
        codexAi.aiModel = "inactive-api";
        codexAi.sourceLang = "fr";
        codexAi.machineTranslationProvider = "inactive-machine";
        check(codexBefore.equals(profile(codexAi)),
                "inactive API/machine settings changed a Codex-only profile");
        codexAi.codexModel = "active-codex-change";
        check(!codexBefore.equals(profile(codexAi)),
                "active Codex model change did not invalidate the request profile");

        TranslatorConfig target = translatingConfig();
        target.targetLang = "stale-config";
        check(ChatRequestProfile.capture(target, "ja-JP").equals(
                        ChatRequestProfile.capture(target, "ja-JP"))
                        && !ChatRequestProfile.capture(target, "ja-JP").equals(
                        ChatRequestProfile.capture(target, "ko-KR")),
                "request profile did not use the service's active target language");
    }

    private static ChatDeliverySession<SessionEntry> session() {
        return new ChatDeliverySession<SessionEntry>(
                entry -> entry.id, entry -> entry.displayed, 512);
    }

    private static ChatRequestProfile profile(TranslatorConfig config) {
        return ChatRequestProfile.capture(config, config.targetLang);
    }

    private static void recoveryAssemblyHostileState() {
        RecoveryAssembly<String> assembly = new RecoveryAssembly<String>(2);
        check(!assembly.accept(0, "slot-0-provisional", false).ready(),
                "one provisional slot completed the whole recovery assembly");
        check(!assembly.accept(0, "slot-0-final", true).ready(),
                "one final slot completed another missing slot");
        check(!assembly.accept(0, "slot-0-regression", false).accepted(),
                "a provisional callback regressed an already final slot");

        RecoveryAssembly.Update<String> firstReady =
                assembly.accept(1, "slot-1-provisional", false);
        check(firstReady.accepted() && firstReady.ready() && !firstReady.allFinal(),
                "mixed final/provisional assembly did not become provisionally ready");
        check(firstReady.values().equals(List.of("slot-0-final", "slot-1-provisional")),
                "provisional recovery snapshot had the wrong slot order: " + firstReady.values());

        RecoveryAssembly.Update<String> finalReady =
                assembly.accept(1, "slot-1-final", true);
        check(finalReady.accepted() && finalReady.ready() && finalReady.allFinal(),
                "all-final recovery assembly did not become terminal");
        check(finalReady.values().equals(List.of("slot-0-final", "slot-1-final")),
                "final recovery snapshot had the wrong values: " + finalReady.values());
        check(firstReady.values().equals(List.of("slot-0-final", "slot-1-provisional")),
                "a queued immutable snapshot observed a later final callback");
        check(!assembly.accept(1, "duplicate-final", true).accepted(),
                "duplicate final callback was accepted");
        check(!assembly.accept(2, "out-of-range", true).accepted(),
                "out-of-range recovery slot was accepted");

        boolean immutable = false;
        try {
            firstReady.values().set(0, "mutated");
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check(immutable, "recovery snapshot was externally mutable");

        RecoveryAssembly<String> nullFinal = new RecoveryAssembly<String>(2);
        nullFinal.accept(0, "useful-provisional", false);
        nullFinal.accept(0, null, true);
        RecoveryAssembly.Update<String> retained = nullFinal.accept(1, "other-final", true);
        check(retained.allFinal()
                        && retained.values().equals(List.of("useful-provisional", "other-final")),
                "a null final callback erased a useful provisional slot");
    }

    private static void resultProgressHostileState() {
        RecoveryAssembly.ResultProgress<String> progress =
                new RecoveryAssembly.ResultProgress<String>();
        progress.configure(2, true);
        check(progress.mayReceiveRecovery() && !progress.allTrackedSlotsFinal(),
                "fresh recovery progress was already terminal");
        check(progress.accept(0, true), "early final callback was rejected");
        check(!progress.accept(0, false),
                "queued provisional client task regressed an early final callback");
        check("useful".equals(progress.retainNonNull("useful"))
                        && "useful".equals(progress.retainNonNull(null)),
                "failed final callback erased the retained provisional value");
        check(progress.mayReceiveRecovery() && !progress.allTrackedSlotsFinal(),
                "one final callback terminated a two-slot recovery");
        check(progress.accept(1, false) && !progress.accept(1, false),
                "provisional slot first-wins gate failed");
        check(progress.accept(1, true), "final upgrade after provisional was rejected");
        check(progress.allTrackedSlotsFinal() && !progress.mayReceiveRecovery(),
                "all final callbacks left a false late-recovery retention");
        check(!progress.accept(1, true) && !progress.accept(2, true),
                "duplicate or out-of-range final callback was accepted");

        progress.configure(1, false);
        check(!progress.mayReceiveRecovery() && !progress.allTrackedSlotsFinal(),
                "non-recovering request incorrectly retained a recovery lifetime");
        check(progress.retainNonNull(null) == null,
                "reconfigure did not clear a retained previous-generation value");
        check(progress.accept(-1, false),
                "untracked single-line callback was rejected");
        check(progress.accept(0, true) && progress.allTrackedSlotsFinal(),
                "reconfigured request did not track its final callback");
    }

    private static void globalAnnouncementBudgetHostileState() throws Exception {
        ChatDeliverySession.BatchBudget budget = new ChatDeliverySession.BatchBudget(2, 5);
        check(budget.tryReserve(3), "first announcement reservation was rejected");
        check(!budget.tryReserve(3), "global character budget was exceeded");
        check(budget.tryReserve(2), "exact remaining announcement budget was rejected");
        check(!budget.tryReserve(0), "global item budget was exceeded by a zero-char block");
        check(budget.items() == 2 && budget.chars() == 5,
                "announcement budget counters diverged at the hard cap");
        budget.release(1, 3);
        check(budget.items() == 1 && budget.chars() == 2 && budget.tryReserve(3),
                "released announcement capacity was not reusable");
        budget.release(2, 5);
        check(budget.items() == 0 && budget.chars() == 0,
                "announcement retirement leaked its global reservation");
        check(!budget.tryReserve(-1) && !budget.tryReserve(Integer.MAX_VALUE),
                "negative/overflow-sized announcement reservation was accepted");

        boolean invalidRelease = false;
        try {
            budget.release(1, 0);
        } catch (IllegalArgumentException expected) {
            invalidRelease = true;
        }
        check(invalidRelease, "invalid announcement release underflowed the budget");

        ChatDeliverySession.BatchBudget concurrent =
                new ChatDeliverySession.BatchBudget(32, 32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int worker = 0; worker < 8; worker++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int attempt = 0; attempt < 16; attempt++) {
                        if (concurrent.tryReserve(1)) accepted.incrementAndGet();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    done.countDown();
                }
            }, "announcement-budget-" + worker);
            thread.start();
        }
        start.countDown();
        check(done.await(5, TimeUnit.SECONDS),
                "concurrent announcement budget workers did not finish");
        if (failure.get() != null) {
            throw new AssertionError("announcement budget worker failed", failure.get());
        }
        check(accepted.get() == 32 && concurrent.items() == 32 && concurrent.chars() == 32,
                "concurrent announcement blocks oversubscribed the global budget");
        concurrent.release(32, 32);
        check(concurrent.items() == 0 && concurrent.chars() == 0,
                "concurrent announcement reservations were not fully released");
    }

    private static void schemaFourJournalReopens(Path file) throws Exception {
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName().toString() + ".tmp"));
        FileStore store = new FileStore(file, true, 2);
        store.put("a", "A");
        store.put("b", "B");
        store.put("c", "C");
        check(store.get("a") == null && "B".equals(store.get("b")) && "C".equals(store.get("c")),
                "live journal capacity was not enforced");

        FileStore reopened = new FileStore(file, false, 2);
        check(reopened.get("a") == null
                        && "B".equals(reopened.get("b"))
                        && "C".equals(reopened.get("c")),
                "schema-4 journal did not replay with the same bounded state");
        String header = Files.readAllLines(file).get(0);
        check(header.contains("\"schema\":4"), "cache was not written as schema 4: " + header);
    }


    /**
     * 1.0.7: the "send new translation requests" master switch (總開關) and the
     * do-not-translate terms (不翻譯詞彙). Every collaborator is an inline fake (lambda
     * backends, in-memory stores, manual executors, a fake clock and a fake pacer sleeper);
     * TranslationService, TranslationCache, RequestPacer, RequestGate, DoNotTranslateMatcher,
     * NameMasker and GoogleFreeTranslator are the compiled release classes. Java 16
     * compatible and limited to core APIs shared by every modern target.
     */
    private static final class RequestSwitchAndTermsSuite {
        private static final int CASES = 24;
        private static int passed;

        private static int runAll() throws Exception {
            passed = 0;
            switchedOffServesCachedRowsOfBothEngines();
            switchedOffMissShowsOriginalAndRecordsNothing();
            switchingBackOnRequestsPreviousMisses();
            collectedWorkIsDroppedAndPermitsReturned();
            executorQueuedWorkGivesUpUnsent();
            pacedWorkerGivesUpWhenSwitchedOffWhileSleeping();
            requestAlreadyOnTheWireStillStoresItsResult();
            retranslateAndItemNameCorrectionNeverDeleteRowsWhileSwitchedOff();
            switchedOffChatCompletesImmediately();
            chatRequestProfileTracksSwitchAndTerms();
            configFieldsRoundTripAndNormalize();
            backendPausedMidCallEndsWithoutFailureAtEveryCallSite();
            termIsMaskedOnTheWireAndRestoredInOriginalSpelling();
            onlyWholeWordsAreMasked();
            multiWordTermsSpanHorizontalSpaceAndLongestWins();
            sectionCodesAreWordBoundaries();
            urlsAndDomainsAreNeverMasked();
            lineMadeOnlyOfTermsIsNeverSent();
            possessiveAfterAProtectedTermStillDisplays();
            aiPlaceholderWithInnerSpacesIsRestored();
            addingOrRemovingTermsChangesOnlyTheKey();
            termsAndPlayerNamesShareOneIndexSpace();
            translationThatLostATermNeverDisplays();
            everyServiceEntryPointMasksTerms();
            return passed;
        }

        private static void passed(String id, String description) {
            passed++;
            System.out.println("INLINE_CORE_V107_CASE_OK " + id + " " + description);
        }

        // ------------------------------------------------------------------ fakes

        private static PersistentStore inlineStore(Map<String, String> backing) {
            return new PersistentStore() {
                @Override
                public String get(String key) {
                    return backing.get(key);
                }

                @Override
                public void put(String key, String value) {
                    backing.put(key, value);
                }

                @Override
                public void clear() {
                    backing.clear();
                }

                @Override
                public void remove(String key) {
                    backing.remove(key);
                }

                @Override
                public Map<String, String> entries() {
                    return new HashMap<String, String>(backing);
                }
            };
        }

        /** Inline backend: counts calls and answers "T:" + text. */
        private static Translator counting(AtomicInteger calls) {
            return (text, target) -> {
                calls.incrementAndGet();
                return new TranslationResult("T:" + text, "en");
            };
        }

        /** Inline backend: records every request text and answers with {@code rule}. */
        private static Translator recording(List<String> sent, UnaryOperator<String> rule) {
            return (text, target) -> {
                sent.add(text);
                return new TranslationResult(rule.apply(text), "en");
            };
        }

        /** Inline context-aware backend: records request texts AND the AI surface context. */
        private static Translator contextRecording(List<String> seen) {
            return new Translator() {
                @Override
                public TranslationResult translate(String text, String targetLang) {
                    seen.add(text);
                    return new TranslationResult("T:" + text, "en");
                }

                @Override
                public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                              List<String> surfaceContext) {
                    seen.addAll(texts);
                    if (surfaceContext != null) seen.addAll(surfaceContext);
                    List<TranslationResult> out = new ArrayList<TranslationResult>();
                    for (String text : texts) out.add(new TranslationResult("T:" + text, "en"));
                    return out;
                }
            };
        }

        private static TranslationService service(TranslatorConfig config, Translator translator) {
            TranslationCache gt = new TranslationCache(translator, config.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(translator, config.targetLang, DIRECT, 100);
            return new TranslationService(config, gt, ai);
        }

        /** Client ticks: an unwindowed collector holds one tick after growth, then sends. */
        private static void pump(TranslationService service, int ticks) {
            for (int i = 0; i < ticks; i++) service.flushBatches();
        }

        /** Size of a private session table (map or collection) of the compiled cache. */
        private static int tracked(TranslationCache cache, String field) {
            try {
                Field declared = TranslationCache.class.getDeclaredField(field);
                declared.setAccessible(true);
                Object value = declared.get(cache);
                if (value instanceof Map) return ((Map<?, ?>) value).size();
                if (value instanceof Collection) return ((Collection<?>) value).size();
                throw new AssertionError("TranslationCache." + field + " is not a map or collection");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot inspect TranslationCache." + field, e);
            }
        }

        private static void assertRecordsNothing(TranslationCache cache, String label) {
            for (String field : List.of("failedUntil", "contentFailures", "contentRetryAttempts",
                    "retrySnapshots", "provisionalRetryAttempts")) {
                check(tracked(cache, field) == 0, label + " recorded failure state in " + field);
            }
        }

        private static DoNotTranslateMatcher terms(String... terms) {
            return DoNotTranslateMatcher.compile(Arrays.asList(terms));
        }

        private static NameMasker.Masked mask(String text, DoNotTranslateMatcher terms) {
            return NameMasker.mask(text, List.<String>of(), terms);
        }

        private static String googleJson(String translated, String source) {
            return "[[[\"" + translated + "\",\"" + source + "\",null,null]],null,\"en\"]";
        }

        // ------------------------------------------------- request master switch

        /** Design 1.0.7 §3.1: cached rows of both engines keep showing while switched off. */
        private static void switchedOffServesCachedRowsOfBothEngines() {
            TranslatorConfig config = translatingConfig();
            config.chatMode = DisplayMode.TRANSLATION;
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.aiChat = true;
            config.aiScoreboard = false;
            config.translationRequestsEnabled = false;
            AtomicInteger gtCalls = new AtomicInteger();
            AtomicInteger aiCalls = new AtomicInteger();
            TranslationCache gt = new TranslationCache(counting(gtCalls), config.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(aiCalls), config.targetLang, DIRECT, 100);
            gt.importTranslations(Map.of("Purse", "錢包"));
            ai.importTranslations(Map.of("Hello world", "你好，世界"));
            TranslationService service = new TranslationService(config, gt, ai);

            check("錢包".equals(service.translateScoreboardLine("Purse").translated()),
                    "switched off: a cached GT row was not displayed");
            check("你好，世界".equals(service.translateChat("Hello world").translated()),
                    "switched off: a cached AI row was not displayed");
            List<String> chat = new ArrayList<String>();
            service.translateChatAsync("Hello world", chat::add);
            check(chat.equals(List.of("你好，世界")),
                    "switched off: one-shot chat did not complete from the cached AI row: " + chat);
            pump(service, 3);
            check(gtCalls.get() == 0 && aiCalls.get() == 0,
                    "switched off: a cache hit reached a backend");

            // An AI surface may stand in with a cached GT row while nothing new can be sent,
            // except in strict AI mode.
            TranslatorConfig mixed = translatingConfig();
            mixed.chatMode = DisplayMode.TRANSLATION;
            mixed.aiChat = true;
            mixed.disableGoogleFallbackForAi = false; // this part exercises the GT-fallback path
            AtomicInteger mixedGt = new AtomicInteger();
            AtomicInteger mixedAi = new AtomicInteger();
            TranslationCache gt2 = new TranslationCache(counting(mixedGt), mixed.targetLang, DIRECT, 100);
            TranslationCache ai2 = new TranslationCache(counting(mixedAi), mixed.targetLang, DIRECT, 100);
            gt2.importTranslations(Map.of("Good morning", "機器譯文"));
            TranslationService mixedService = new TranslationService(mixed, gt2, ai2);
            check(!mixedService.translateChat("Good morning").changed(),
                    "switched on: a GT row was shown on an AI surface before AI failed");
            mixed.translationRequestsEnabled = false;
            check("機器譯文".equals(mixedService.translateChat("Good morning").translated()),
                    "switched off: the AI surface did not show the cached GT row");
            List<String> mixedChat = new ArrayList<String>();
            mixedService.translateChatAsync("Good morning", mixedChat::add);
            check(mixedChat.equals(List.of("機器譯文")),
                    "switched off: AI chat did not complete from the cached GT row: " + mixedChat);
            mixed.disableGoogleFallbackForAi = true;
            check(!mixedService.translateChat("Good morning").changed(),
                    "switched off: strict AI mode displayed a GT row");
            mixedChat.clear();
            mixedService.translateChatAsync("Good morning", mixedChat::add);
            check(mixedChat.equals(Arrays.asList((String) null)),
                    "switched off: strict AI chat did not complete as original: " + mixedChat);
            pump(mixedService, 3);
            check(mixedGt.get() == 0 && mixedAi.get() == 0,
                    "switched off: the miss queued while switched on was still sent");
            passed("S1", "switch-off shows cached GT/AI rows (AI reads GT unless strict), 0 calls");
        }

        /** Design §3.2: a miss stays original and writes no failure, negative, echo,
         *  backoff, churn or retry-demand state, even as time passes. */
        private static void switchedOffMissShowsOriginalAndRecordsNothing() {
            TranslatorConfig config = translatingConfig();
            config.chatMode = DisplayMode.TRANSLATION;
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.bookMode = DisplayMode.TRANSLATION;
            config.screenTextMode = DisplayMode.TRANSLATION;
            config.aiChat = true;
            config.translationRequestsEnabled = false;
            long[] now = {0L};
            AtomicInteger gtCalls = new AtomicInteger();
            AtomicInteger aiCalls = new AtomicInteger();
            Map<String, String> gtDisk = new HashMap<String, String>();
            Map<String, String> aiDisk = new HashMap<String, String>();
            Map<String, String> gtFailures = new HashMap<String, String>();
            Map<String, String> aiFailures = new HashMap<String, String>();
            TranslationCache gt = new TranslationCache(counting(gtCalls), config.targetLang, DIRECT,
                    100, 1_000L, () -> now[0], inlineStore(gtDisk));
            gt.setFailureStore(inlineStore(gtFailures));
            TranslationCache ai = new TranslationCache(counting(aiCalls), config.targetLang, DIRECT,
                    100, 1_000L, () -> now[0], inlineStore(aiDisk));
            ai.setFailureStore(inlineStore(aiFailures));
            ai.setProvisionalRetryGate(() -> true);
            TranslationService service = new TranslationService(config, gt, ai);
            service.setBatchWindowMs(() -> 0);
            ChurnGuard guard = new ChurnGuard(2, 60_000L, 300_000L, () -> now[0]);
            gt.setChurnGuard(guard);
            ai.setChurnGuard(guard);
            List<String> chat = new ArrayList<String>();
            List<String> actionBar = new ArrayList<String>();
            List<String> screen = new ArrayList<String>();

            for (int round = 0; round < 3; round++) {
                check(!service.translateChat("Hello world").changed(), "switched-off chat changed");
                check(!service.translateItemLine("Diamond Sword").changed(), "switched-off item changed");
                check(!service.translateScoreboardLine("Vote now !").changed()
                                && !service.translateScoreboardLine("Vote now !!").changed(),
                        "switched-off scoreboard changed");
                check(!service.translateUi("Lone Adventurer").changed(), "switched-off name tag changed");
                check(!service.translateBook("Once upon a time").changed(), "switched-off book changed");
                check(!service.isTooltipTranslationReady("Diamond Sword"),
                        "switched-off miss was reported as a ready tooltip");
                check(!service.warmUp("Iron Pickaxe"), "switched-off blocking warm-up produced a value");
                service.warmTooltipBatch(List.of("Diamond Sword", "Used in smelting"));
                service.warmNamesBatch(List.of("Ender Pearl"));
                service.warmScoreboardBatch(List.of("Purse: 100", "Bits: 20"));
                service.warmBookBatch(List.of("Once upon a time"));
                service.translateChatAsync("Welcome to the server", chat::add);
                service.requestActionBarAsync("You received 5 coins!", actionBar::add);
                service.requestLiveScreenTextAsync("Open the menu", screen::add);
                now[0] += 600_000L;
                pump(service, 5);
            }

            check(gtCalls.get() == 0 && aiCalls.get() == 0, "switched off: a miss reached a backend (gt="
                    + gtCalls.get() + ", ai=" + aiCalls.get() + ")");
            check(chat.equals(Arrays.asList(null, null, null)),
                    "switched off: chat did not complete as originals: " + chat);
            check(actionBar.isEmpty() && screen.isEmpty(),
                    "switched off: an optional callback produced a value");
            check(gtFailures.isEmpty() && aiFailures.isEmpty(),
                    "switched off: the failure ledger was written: " + gtFailures + " " + aiFailures);
            check(gtDisk.isEmpty() && aiDisk.isEmpty(),
                    "switched off: a keep-original/negative row was learned: " + gtDisk + " " + aiDisk);
            check(guard.signatureCount() == 0, "switched off: ChurnGuard was fed");
            for (TranslationCache cache : List.of(gt, ai)) {
                assertRecordsNothing(cache, "switched-off lookup");
                check(tracked(cache, "sessionRetryDemand") == 0,
                        "switched off: session retry demand was recorded");
                check(tracked(cache, "queue") == 0 && tracked(cache, "flights") == 0
                                && tracked(cache, "finalWaiters") == 0,
                        "switched off: queue/flight/recovery-waiter state was created");
                check(cache.pendingCount() == 0, "switched off: a pending permit is held");
                check(!cache.hasFailureState("Hello world") && !cache.hasFailureState("Diamond Sword"),
                        "switched off: a miss reports a failure state");
            }
            passed("S2", "switch-off misses stay original on 14 entry points, 0 calls, nothing recorded");
        }

        /** Design §3.4: nothing was recorded, so reopening requests the same misses. */
        private static void switchingBackOnRequestsPreviousMisses() {
            TranslatorConfig config = translatingConfig();
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.chatMode = DisplayMode.TRANSLATION;
            config.translationRequestsEnabled = false;
            AtomicInteger calls = new AtomicInteger();
            TranslationCache gt = new TranslationCache(counting(calls), config.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(calls), config.targetLang, DIRECT, 100);
            TranslationService service = new TranslationService(config, gt, ai);

            check(!service.translateItemLine("Diamond Sword").changed()
                            && !service.translateChat("Hello world").changed(),
                    "switched-off miss changed");
            pump(service, 4);
            check(calls.get() == 0, "switched off: a miss was sent");

            config.translationRequestsEnabled = true;
            check(!service.translateItemLine("Diamond Sword").changed()
                            && !service.translateChat("Hello world").changed(),
                    "reopened: the first frame must only queue");
            pump(service, 2);
            check(calls.get() == 2, "reopened: previous misses were not requested once each: " + calls.get());
            check("T:Diamond Sword".equals(service.translateItemLine("Diamond Sword").translated())
                            && "T:Hello world".equals(service.translateChat("Hello world").translated()),
                    "reopened: the requested rows are not displayed");
            passed("S3", "switching back on requests the previous misses normally");
        }

        /** Design §3.3: collected (not yet dispatched) work is dropped, not failed. */
        private static void collectedWorkIsDroppedAndPermitsReturned() {
            AtomicInteger calls = new AtomicInteger();
            long[] now = {0L};
            boolean[] open = {true};
            Map<String, String> failures = new HashMap<String, String>();
            TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100,
                    1_000L, () -> now[0]);
            cache.setFailureStore(inlineStore(failures));
            cache.setRequestGate(() -> open[0]);
            cache.setBatchWindowMs(() -> 5_000);
            List<String> got = new ArrayList<String>();

            cache.requestCoalesced("Hello world", got::add, true);
            cache.requestBatched("Good morning");
            cache.flushBatch(); // the collection window is still open
            check(cache.pendingCount() == 2 && got.isEmpty(),
                    "collector did not hold both entries: pending=" + cache.pendingCount());

            open[0] = false;
            cache.flushBatch();
            check(got.equals(Arrays.asList((String) null)),
                    "switched off: the queued always-callback did not complete with null: " + got);
            check(cache.pendingCount() == 0 && tracked(cache, "queue") == 0,
                    "switched off: dropped work kept its permits: pending=" + cache.pendingCount());
            for (int i = 0; i < 5; i++) {
                now[0] += 60_000L;
                cache.flushBatch();
            }
            check(calls.get() == 0, "switched off: dropped work reached the backend");
            check(failures.isEmpty(), "switched off: dropped work was recorded as a failure: " + failures);
            assertRecordsNothing(cache, "dropped collector");

            open[0] = true;
            cache.setBatchWindowMs(() -> 0);
            cache.requestCoalesced("Hello world", got::add, true);
            cache.flushBatch();
            check(calls.get() == 1 && got.size() == 2 && "T:Hello world".equals(got.get(1)),
                    "reopened: the dropped text was not requested again: " + got);
            passed("S4", "queued work is dropped on switch-off: permits back to 0, callback null");
        }

        /** Design §3.3 (B layer): work already handed to the executor gives up unsent. */
        private static void executorQueuedWorkGivesUpUnsent() {
            AtomicInteger calls = new AtomicInteger();
            boolean[] open = {true};
            Deque<Runnable> workers = new ArrayDeque<Runnable>();
            Map<String, String> failures = new HashMap<String, String>();
            TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", workers::add, 100,
                    1_000L, () -> 0L);
            cache.setFailureStore(inlineStore(failures));
            cache.setRequestGate(() -> open[0]);
            TranslationDebugLog debug = new TranslationDebugLog(() -> true);
            cache.setDebugLog("GT", debug);
            List<String> got = new ArrayList<String>();

            cache.requestCoalesced("Hello world", got::add, true);
            cache.flushBatch();
            cache.flushBatch(); // the collected batch is handed to the manual worker queue
            cache.translateAsyncAlways("Good morning", got::add); // one single flight, queued too
            check(workers.size() == 2 && cache.pendingCount() == 2,
                    "setup: expected two queued worker tasks, got " + workers.size());

            open[0] = false;
            while (!workers.isEmpty()) workers.poll().run();
            check(calls.get() == 0, "switched off: an executor-queued task reached the backend");
            check(got.equals(Arrays.asList(null, null)),
                    "switched off: abandoned flights did not complete with null: " + got);
            check(cache.pendingCount() == 0 && tracked(cache, "flights") == 0,
                    "switched off: an abandoned flight leaked");
            check(failures.isEmpty(), "switched off: an abandoned task was recorded as failure");
            assertRecordsNothing(cache, "abandoned task");
            check(debug.snapshot(10).isEmpty(), "switched off: an unsent request left a debug row");
            check(cache.translateBlocking("Good evening") == null && cache.warmBatch(List.of("Good night"))
                            && calls.get() == 0,
                    "switched off: an explicit blocking/warm request was sent");

            open[0] = true;
            cache.requestCoalesced("Hello world", got::add, true);
            cache.flushBatch();
            cache.flushBatch();
            check(workers.size() == 1, "reopened: the request was not handed to the executor");
            workers.poll().run();
            check(calls.get() == 1 && got.size() == 3 && "T:Hello world".equals(got.get(2)),
                    "reopened: the request was not sent/delivered: " + got);
            passed("S5", "executor-queued single and batch tasks give up unsent, no failure/debug row");
        }

        /** Design §3.3 (C layer): a worker sleeping in RequestPacer when the switch goes off
         *  wakes up and abandons its request (0 HTTP), for an inline paced backend and for the
         *  real GoogleFreeTranslator; reopening sends again. Unbound threads never block. */
        private static void pacedWorkerGivesUpWhenSwitchedOffWhileSleeping() {
            check(RequestGate.isOpen(), "an unbound thread must never be blocked");
            BooleanSupplier previous = RequestGate.bind(() -> false);
            try {
                check(!RequestGate.isOpen(), "a bound closed gate reported open");
                boolean paused = false;
                try {
                    RequestPacer.disabled().acquire();
                } catch (RequestsPausedException expected) {
                    paused = true;
                }
                check(paused, "a closed gate did not stop even an unpaced request");
            } finally {
                RequestGate.restore(previous);
            }
            check(RequestGate.isOpen(), "restoring the gate binding left this thread closed");
            previous = RequestGate.bind(() -> {
                throw new IllegalStateException("broken gate supplier");
            });
            try {
                check(RequestGate.isOpen(), "a broken gate supplier silently stopped translation");
            } finally {
                RequestGate.restore(previous);
            }
            check(RuntimeException.class.isAssignableFrom(RequestsPausedException.class)
                            && !TranslationException.class.isAssignableFrom(RequestsPausedException.class),
                    "a paused request must travel as an unchecked non-TranslationException");

            for (int variant = 0; variant < 2; variant++) {
                String label = variant == 0 ? "paced inline backend" : "paced GoogleFreeTranslator";
                AtomicLong clock = new AtomicLong(1_000L);
                boolean[] open = {true};
                AtomicInteger sleeps = new AtomicInteger();
                AtomicInteger http = new AtomicInteger();
                // Inline sleeper: the user switches requests off while this worker sleeps.
                RequestPacer pacer = new RequestPacer(() -> 400L, clock::get, ms -> {
                    if (sleeps.getAndIncrement() == 0) open[0] = false;
                });
                Translator backend;
                if (variant == 0) {
                    backend = (text, target) -> {
                        pacer.acquire(); // like every engine: pace immediately before each send
                        http.incrementAndGet();
                        return new TranslationResult("你好世界", "en");
                    };
                } else {
                    HttpTransport transport = url -> {
                        http.incrementAndGet();
                        return googleJson("你好世界", "Hello world");
                    };
                    backend = new GoogleFreeTranslator(transport, "auto", pacer);
                }
                pacer.acquire(); // an earlier request owns the current send slot
                Map<String, String> failures = new HashMap<String, String>();
                TranslationCache cache = new TranslationCache(backend, "zh-TW", DIRECT, 100,
                        1_000L, clock::get);
                cache.setFailureStore(inlineStore(failures));
                cache.setRequestGate(() -> open[0]);
                TranslationDebugLog debug = new TranslationDebugLog(() -> true);
                cache.setDebugLog("GT", debug);
                List<String> got = new ArrayList<String>();

                cache.requestCoalesced("Hello world", got::add, true);
                cache.flushBatch();
                cache.flushBatch();
                check(sleeps.get() == 1, label + ": the worker did not wait for its send slot");
                check(http.get() == 0, label + ": the woken worker still sent HTTP");
                check(got.equals(Arrays.asList((String) null)),
                        label + ": the flight callback did not complete with null: " + got);
                check(cache.pendingCount() == 0, label + ": a pending permit leaked");
                check(failures.isEmpty() && !cache.hasFailureState("Hello world"),
                        label + ": the pause was recorded as a failure: " + failures);
                assertRecordsNothing(cache, label);
                check(debug.snapshot(10).isEmpty(),
                        label + ": left a FAILED or endless waiting debug row: " + debug.snapshot(10));
                check(RequestGate.isOpen(), label + ": the worker gate binding outlived the backend call");

                open[0] = true;
                clock.addAndGet(10_000L);
                cache.requestCoalesced("Hello world", got::add, true);
                cache.flushBatch();
                cache.flushBatch();
                check(http.get() == 1, label + ": reopening did not send exactly once: " + http.get());
                check(got.size() == 2 && "你好世界".equals(got.get(1))
                                && "你好世界".equals(cache.getCached("Hello world")),
                        label + ": the reopened request was not delivered/cached: " + got);
            }
            passed("S6", "worker sleeping in RequestPacer gives up at switch-off (0 HTTP), reopen sends");
        }

        /** Design §3.5: an answer that already left the client is still stored. */
        private static void requestAlreadyOnTheWireStillStoresItsResult() {
            boolean[] open = {true};
            AtomicInteger calls = new AtomicInteger();
            Translator inFlight = (text, target) -> {
                calls.incrementAndGet();
                open[0] = false; // switched off while this request was already sent
                return new TranslationResult("T:" + text, "en");
            };
            TranslationCache cache = new TranslationCache(inFlight, "zh-TW", DIRECT, 100);
            cache.setRequestGate(() -> open[0]);
            List<String> got = new ArrayList<String>();

            cache.requestCoalesced("Hello world", got::add, true);
            cache.flushBatch();
            cache.flushBatch();
            check(calls.get() == 1 && got.equals(List.of("T:Hello world"))
                            && "T:Hello world".equals(cache.getCached("Hello world")),
                    "switched off mid-flight: the already-sent answer was not delivered and cached");
            passed("S7", "an answer already on the wire at switch-off is still delivered and cached");
        }

        /** Design §3.7: R, P and the item-name invalidate-and-reask branch are no-ops while
         *  switched off; the request-free local correction still runs. */
        private static void retranslateAndItemNameCorrectionNeverDeleteRowsWhileSwitchedOff() {
            TranslatorConfig config = translatingConfig();
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.screenTextMode = DisplayMode.TRANSLATION;
            config.aiTooltip = true;
            config.translationRequestsEnabled = false;
            AtomicInteger gtCalls = new AtomicInteger();
            AtomicInteger aiCalls = new AtomicInteger();
            TranslationCache gt = new TranslationCache(counting(gtCalls), config.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(aiCalls), config.targetLang, DIRECT, 100);
            gt.importTranslations(Map.of("Hello", "你好"));
            ai.importTranslations(Map.of(
                    "Hello", "哈囉",
                    "Aspect of the End", "末影之視",
                    "Aspect of the End Sword Ability", "終界之刃 劍技",
                    "Sword Ability", "不相符的後綴"));
            TranslationService service = new TranslationService(config, gt, ai);

            service.retranslate(List.of("Hello"));
            service.retranslateScreen(List.of("Hello"));
            check("你好".equals(gt.getCached("Hello")) && "哈囉".equals(ai.getCached("Hello")),
                    "switched off: R/P deleted a row that cannot be re-requested");
            List<String> tooltip = List.of("Aspect of the End Sword Ability", "Right click to use");
            service.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
            check("末影之視".equals(ai.getCachedFinal("Aspect of the End")),
                    "switched off: item-name correction invalidated the AI row");
            pump(service, 3);
            check(gtCalls.get() == 0 && aiCalls.get() == 0, "switched off: R/P/correction sent a request");

            config.translationRequestsEnabled = true; // control: the same calls really act when on
            service.reconcileItemNameWithTooltip("Aspect of the End", tooltip);
            check(aiCalls.get() == 1, "control: the contextual item-name retry did not run when on");
            service.retranslate(List.of("Hello"));
            check("T:Hello".equals(ai.getCached("Hello")), "control: R did not re-buy the row when on");

            // The request-free authoritative correction runs either way, but only deletes the
            // GT copy when that copy could be re-requested.
            AtomicInteger calls = new AtomicInteger();
            for (boolean on : new boolean[] {false, true}) {
                TranslatorConfig correction = translatingConfig();
                correction.tooltipMode = DisplayMode.TRANSLATION;
                correction.aiTooltip = true;
                correction.translationRequestsEnabled = on;
                TranslationCache gt2 = new TranslationCache(counting(calls), correction.targetLang, DIRECT, 100);
                TranslationCache ai2 = new TranslationCache(counting(calls), correction.targetLang, DIRECT, 100);
                gt2.importTranslations(Map.of("Aspect of the End", "末影之眼"));
                ai2.importTranslations(Map.of(
                        "Aspect of the End", "末影之視",
                        "Aspect of the End Sword Ability", "終界之刃 劍技",
                        "Sword Ability", "劍技"));
                TranslationService correctionService = new TranslationService(correction, gt2, ai2);
                correctionService.reconcileItemNameWithTooltip("Aspect of the End",
                        List.of("Aspect of the End Sword Ability", "Right click to use"));
                check("終界之刃".equals(ai2.getCachedFinal("Aspect of the End")),
                        "local item-name correction did not run (on=" + on + ")");
                check(on ? gt2.getCached("Aspect of the End") == null
                                : "末影之眼".equals(gt2.getCached("Aspect of the End")),
                        "GT copy handling is wrong (on=" + on + ")");
            }
            check(calls.get() == 0, "the local item-name correction sent a request");
            passed("S8", "R/P/item-name invalidate branch are no-ops when off; local correction keeps GT");
        }

        /** Chat always-callbacks complete at once while switched off (nothing can recover),
         *  and a cached semantic row is shown for colour chat as the style fallback. */
        private static void switchedOffChatCompletesImmediately() {
            for (boolean aiChat : new boolean[] {false, true}) {
                TranslatorConfig config = translatingConfig();
                config.aiChat = aiChat;
                config.translationRequestsEnabled = false;
                config.disableGoogleFallbackForAi = false; // this part exercises the GT-fallback path
                AtomicInteger calls = new AtomicInteger();
                TranslationCache gt = new TranslationCache(counting(calls), config.targetLang, DIRECT, 100);
                TranslationCache ai = new TranslationCache(counting(calls), config.targetLang, DIRECT, 100);
                gt.importTranslations(Map.of("Hello brave world", "你好勇敢的世界"));
                TranslationService service = new TranslationService(config, gt, ai);
                List<String> texts = new ArrayList<String>();
                List<Boolean> finals = new ArrayList<Boolean>();

                service.translateChatAsyncDetailed("Hello world", result -> {
                    texts.add(result.text());
                    finals.add(result.finalResult());
                });
                service.translateChatAsyncDetailed("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧world⟦/CS1⟧", result -> {
                    texts.add(result.text());
                    finals.add(result.finalResult());
                });
                service.translateChatAsyncDetailed("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧brave world⟦/CS1⟧", result -> {
                    texts.add(result.text());
                    finals.add(result.finalResult());
                });
                check(texts.size() == 3 && texts.get(0) == null && texts.get(1) == null,
                        "switched off: chat misses did not complete at once as originals (ai="
                                + aiChat + "): " + texts);
                check(TextFilter.isStyleFallback(texts.get(2))
                                && "你好勇敢的世界".equals(TextFilter.stripStyleFallback(texts.get(2))),
                        "switched off: cached colour chat did not show the semantic row (ai=" + aiChat + ")");
                check(finals.equals(List.of(true, true, true)),
                        "switched off: a chat line was kept waiting for a recovery (ai=" + aiChat + ")");
                check(calls.get() == 0, "switched off: chat reached a backend (ai=" + aiChat + ")");
            }
            passed("S9", "switch-off chat callbacks complete immediately and final (colour: cached row)");
        }

        /** The switch and the term list both belong to the chat request profile. */
        private static void chatRequestProfileTracksSwitchAndTerms() {
            TranslatorConfig config = translatingConfig();
            ChatRequestProfile on = ChatRequestProfile.capture(config, config.targetLang);
            config.translationRequestsEnabled = false;
            ChatRequestProfile off = ChatRequestProfile.capture(config, config.targetLang);
            check(!on.equals(off), "the request switch is not part of the chat request profile");
            ChatRequestProfile offAgain = ChatRequestProfile.capture(config, config.targetLang);
            check(off.equals(offAgain) && off.hashCode() == offAgain.hashCode(),
                    "the switched-off profile is not stable");
            config.translationRequestsEnabled = true;
            check(on.equals(ChatRequestProfile.capture(config, config.targetLang)),
                    "switching back on did not restore the profile");

            ChatDeliverySession<Long> session = new ChatDeliverySession<Long>(id -> id, id -> false, 512);
            Object connection = new Object();
            Object world = new Object();
            session.observe(connection, world, on, false);
            session.add(1L);
            ChatDeliverySession.Transition<Long> transition = session.observe(connection, world, off, false);
            check(transition.kind() == ChatDeliverySession.TransitionKind.FLUSH_ORIGINALS
                            && transition.originals().equals(List.of(1L)),
                    "switching requests off did not release waiting chat as originals");

            for (boolean aiChat : new boolean[] {false, true}) {
                TranslatorConfig terms = translatingConfig();
                terms.aiChat = aiChat;
                ChatRequestProfile none = ChatRequestProfile.capture(terms, terms.targetLang);
                terms.doNotTranslateTerms.add("SkyBlock");
                ChatRequestProfile one = ChatRequestProfile.capture(terms, terms.targetLang);
                ChatRequestProfile oneAgain = ChatRequestProfile.capture(terms, terms.targetLang);
                check(!none.equals(one), "a term list change kept the chat profile (ai=" + aiChat + ")");
                check(one.equals(oneAgain) && one.hashCode() == oneAgain.hashCode(),
                        "the term profile is not stable (ai=" + aiChat + ")");
                terms.doNotTranslateTerms.add("Hypixel");
                check(!one.equals(ChatRequestProfile.capture(terms, terms.targetLang)),
                        "adding a term kept the chat profile (ai=" + aiChat + ")");
                terms.doNotTranslateTerms.remove("Hypixel");
                check(one.equals(ChatRequestProfile.capture(terms, terms.targetLang)),
                        "removing the term did not restore the profile (ai=" + aiChat + ")");
            }
            passed("S10", "ChatRequestProfile carries the switch and the term list (backlog flush)");
        }

        /** Design §2: the two new persisted fields keep their JSON types and normalise. */
        private static void configFieldsRoundTripAndNormalize() {
            TranslatorConfig defaults = new TranslatorConfig();
            check(!defaults.translationRequestsEnabled && defaults.doNotTranslateTerms != null
                            && defaults.doNotTranslateTerms.isEmpty(),
                    "new config fields have wrong defaults");
            StringWriter defaultJson = new StringWriter();
            defaults.writeTo(defaultJson);
            JsonObject writtenDefaults = new JsonParser().parse(defaultJson.toString()).getAsJsonObject();
            check(writtenDefaults.has("translationRequestsEnabled")
                            && writtenDefaults.get("translationRequestsEnabled").isJsonPrimitive()
                            && writtenDefaults.get("translationRequestsEnabled").getAsJsonPrimitive().isBoolean()
                            && !writtenDefaults.get("translationRequestsEnabled").getAsBoolean(),
                    "translationRequestsEnabled is not persisted as boolean false");
            check(writtenDefaults.has("doNotTranslateTerms")
                            && writtenDefaults.get("doNotTranslateTerms").isJsonArray()
                            && writtenDefaults.getAsJsonArray("doNotTranslateTerms").size() == 0,
                    "doNotTranslateTerms is not persisted as an empty array");

            TranslatorConfig missing = TranslatorConfig.fromReader(new StringReader("{}"));
            check(missing.translationRequestsEnabled && missing.doNotTranslateTerms != null
                            && missing.doNotTranslateTerms.isEmpty(),
                    "an older config without the new fields did not keep the defaults");
            TranslatorConfig nullList = TranslatorConfig.fromReader(
                    new StringReader("{\"doNotTranslateTerms\":null}"));
            check(nullList.doNotTranslateTerms != null && nullList.doNotTranslateTerms.isEmpty(),
                    "a null term list was not normalised to an empty list");
            TranslatorConfig handEdited = TranslatorConfig.fromReader(new StringReader(
                    "{\"translationRequestsEnabled\":false,"
                            + "\"doNotTranslateTerms\":[\" SkyBlock \",\"skyblock\",\"\",\"Hypixel\"]}"));
            check(!handEdited.translationRequestsEnabled
                            && handEdited.doNotTranslateTerms.equals(List.of("SkyBlock", "Hypixel")),
                    "a hand-edited config was not loaded/normalised: " + handEdited.doNotTranslateTerms);

            TranslatorConfig edited = translatingConfig();
            edited.translationRequestsEnabled = false;
            edited.doNotTranslateTerms = new ArrayList<String>(Arrays.asList(
                    "  SkyBlock ", "", null, "skyblock", "Hypixel", "SKYBLOCK", "  ",
                    "　Dungeon Hub　"));
            edited.normalized();
            check(edited.doNotTranslateTerms.equals(List.of("SkyBlock", "Hypixel", "Dungeon Hub")),
                    "term normalisation (trim, drop blank, case-insensitive dedupe keeping the first "
                            + "spelling) failed: " + edited.doNotTranslateTerms);
            StringWriter json = new StringWriter();
            edited.writeTo(json);
            TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(json.toString()));
            check(!loaded.translationRequestsEnabled
                            && loaded.doNotTranslateTerms.equals(List.of("SkyBlock", "Hypixel", "Dungeon Hub")),
                    "the new fields did not survive a config round trip");
            loaded.doNotTranslateTerms.add("Bazaar");
            check(loaded.doNotTranslateTerms.size() == 4, "the loaded term list is not editable");
            check(TranslatorConfig.normalizedTerms(null).isEmpty()
                            && TranslatorConfig.normalizedTerms(Arrays.asList("A", "a", " b ")).equals(List.of("A", "b")),
                    "TranslatorConfig.normalizedTerms (UI save path) is wrong");
            passed("S11", "config: translationRequestsEnabled/doNotTranslateTerms defaults, JSON types, round trip");
        }

        /** Design §3.3 (C layer) at EVERY backend call site: a backend that finds the switch
         *  off when it gets its send slot (exactly what RequestPacer does after sleeping)
         *  ends the request without any failure state at the blocking, single-flight, batch
         *  and provisional-supplement call sites, with the cache gate bound meanwhile. */
        private static void backendPausedMidCallEndsWithoutFailureAtEveryCallSite() {
            boolean[] open = {true};
            List<Boolean> gateSeenByBackend = new ArrayList<Boolean>();
            AtomicInteger calls = new AtomicInteger();
            Translator pausedWhileWaiting = (text, target) -> {
                calls.incrementAndGet();
                open[0] = false;
                gateSeenByBackend.add(RequestGate.isOpen());
                RequestGate.checkOpen();
                return new TranslationResult("T:" + text, "en");
            };
            Map<String, String> failures = new HashMap<String, String>();
            TranslationCache cache = new TranslationCache(pausedWhileWaiting, "zh-TW", DIRECT, 100,
                    1_000L, () -> 0L);
            cache.setFailureStore(inlineStore(failures));
            cache.setRequestGate(() -> open[0]);
            TranslationDebugLog debug = new TranslationDebugLog(() -> true);
            cache.setDebugLog("AI", debug);
            List<String> got = new ArrayList<String>();

            check(cache.translateBlocking("Alpha one") == null,
                    "blocking call site: a paused request produced a value");
            open[0] = true;
            cache.translateAsyncAlways("Beta two", got::add);          // single-flight call site
            open[0] = true;
            cache.requestCoalesced("Gamma three", got::add, true);     // collected batch call site
            cache.flushBatch();
            cache.flushBatch();
            check(calls.get() == 3 && gateSeenByBackend.equals(List.of(false, false, false)),
                    "the live gate was not bound to the worker during every backend call: "
                            + gateSeenByBackend);
            check(RequestGate.isOpen(), "a worker gate binding outlived its backend call");
            check(got.equals(Arrays.asList(null, null)),
                    "paused single/batch flights did not complete with null: " + got);
            check(cache.pendingCount() == 0 && tracked(cache, "flights") == 0, "a paused flight leaked");
            check(failures.isEmpty(), "a paused backend call was recorded as a failure: " + failures);
            assertRecordsNothing(cache, "paused backend call");
            check(!cache.hasFailureState("Alpha one") && !cache.hasFailureState("Beta two")
                            && !cache.hasFailureState("Gamma three"),
                    "a paused backend call reports a failure state");
            check(debug.snapshot(10).isEmpty(),
                    "a paused request left a FAILED or endless waiting debug row: " + debug.snapshot(10));

            // Provisional-supplement call site: the AI re-asks after a GT stand-in was stored.
            AtomicInteger supplements = new AtomicInteger();
            boolean[] supplementOpen = {true};
            Translator standInThenPaused = (text, target) -> {
                if (supplements.incrementAndGet() == 1) {
                    return new TranslationResult("GT:" + text, "en", true);
                }
                supplementOpen[0] = false;
                RequestGate.checkOpen();
                return new TranslationResult("AI:" + text, "en");
            };
            Map<String, String> supplementFailures = new HashMap<String, String>();
            TranslationCache ai = new TranslationCache(standInThenPaused, "zh-TW", DIRECT, 100,
                    1_000L, () -> 0L);
            ai.setFailureStore(inlineStore(supplementFailures));
            ai.setProvisionalRetryGate(() -> true);
            ai.setRequestGate(() -> supplementOpen[0]);
            check("GT:Delta four".equals(ai.translateBlocking("Delta four")),
                    "setup: the provisional stand-in was not produced");
            check("GT:Delta four".equals(ai.getCached("Delta four")), // starts the supplement inline
                    "the provisional stand-in is not displayed");
            check(supplements.get() == 2, "the provisional supplement did not reach its backend call");
            check(supplementFailures.isEmpty(),
                    "a paused supplement was recorded as a failure: " + supplementFailures);
            check(tracked(ai, "failedUntil") == 0 && tracked(ai, "contentRetryAttempts") == 0
                            && tracked(ai, "provisionalRetrying") == 0,
                    "a paused supplement left failure or in-flight state");
            check("GT:Delta four".equals(ai.getCached("Delta four")) && supplements.get() == 2,
                    "after a paused supplement the stand-in vanished or another request started");
            passed("S12", "backend paused mid-call ends with no failure at blocking/single/batch/supplement sites");
        }

        // -------------------------------------------------- do-not-translate terms

        /** Design §4: a term is masked before the request (the Google wire carries only the
         *  numeric sentinel) and restored in the spelling the text actually used. */
        private static void termIsMaskedOnTheWireAndRestoredInOriginalSpelling() {
            TranslatorConfig config = translatingConfig();
            config.chatMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("skyblock");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config,
                    recording(sent, text -> text.replace("Welcome to ", "歡迎來到 ")));
            check(!service.translateChat("Welcome to SkyBlock!").changed(), "a miss displayed a value");
            pump(service, 2);
            check(sent.equals(List.of("Welcome to ⟦0⟧!")), "the term left the client: " + sent);
            check("歡迎來到 SkyBlock!".equals(service.translateChat("Welcome to SkyBlock!").translated()),
                    "the term was not restored in its original spelling");
            List<String> chat = new ArrayList<String>();
            service.translateChatAsync("Welcome to SkyBlock!", chat::add);
            check(chat.equals(List.of("歡迎來到 SkyBlock!")), "chat did not restore the term: " + chat);
            check("歡迎來到 SKYBLOCK!".equals(service.translateChat("Welcome to SKYBLOCK!").translated())
                            && sent.size() == 1,
                    "a case variant did not share the masked row while keeping its own spelling");

            NameMasker.Masked spellings = mask("SKYBLOCK beats Skyblock and SKYBLOCK", terms("SkyBlock"));
            check("⟦0⟧ beats ⟦1⟧ and ⟦0⟧".equals(spellings.text())
                            && spellings.names().equals(List.of("SKYBLOCK", "Skyblock")),
                    "every original spelling must own one placeholder: " + spellings.text());
            check("SKYBLOCK 勝過 Skyblock 和 SKYBLOCK".equals(
                            NameMasker.unmask("⟦0⟧ 勝過 ⟦1⟧ 和 ⟦0⟧", spellings.names())),
                    "unmask did not restore each spelling");

            // Real Google engine: ⟦0⟧ crosses the wire only as its numeric sentinel.
            TranslatorConfig wire = translatingConfig();
            wire.chatMode = DisplayMode.TRANSLATION;
            wire.doNotTranslateTerms.add("SkyBlock");
            List<String> queries = new ArrayList<String>();
            HttpTransport transport = url -> {
                String query = URLDecoder.decode(url.substring(url.indexOf("&q=") + 3),
                        StandardCharsets.UTF_8);
                queries.add(query);
                return googleJson(query.replace("Welcome to ", "歡迎來到").replace("!", "！"), query);
            };
            TranslationCache gt = new TranslationCache(new GoogleFreeTranslator(transport, "auto"),
                    wire.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(new AtomicInteger()), wire.targetLang, DIRECT, 100);
            TranslationService wired = new TranslationService(wire, gt, ai);
            check(!wired.translateChat("Welcome to SkyBlock!").changed(), "a Google miss displayed a value");
            pump(wired, 2);
            check(queries.equals(List.of("Welcome to 70001!")),
                    "the Google request carried something other than the numeric sentinel: " + queries);
            check("歡迎來到SkyBlock！".equals(wired.translateChat("Welcome to SkyBlock!").translated()),
                    "the Google result did not restore the term: "
                            + wired.translateChat("Welcome to SkyBlock!").translated());
            check("歡迎來到SKYBLOCK！".equals(wired.translateChat("Welcome to SKYBLOCK!").translated())
                            && queries.size() == 1,
                    "a case variant was re-requested or lost its spelling on the Google path");
            passed("D1", "term masked before sending (Google wire: 70001 only), restored in original spelling");
        }

        /** Terms match whole words only; CJK neighbours are word boundaries. */
        private static void onlyWholeWordsAreMasked() {
            DoNotTranslateMatcher sky = terms("skyblock");
            for (String text : List.of("Skyfoo rocks", "two skyblocks", "skyblock_2",
                    "SKYBLOCK2", "megaskyblock")) {
                check(!mask(text, sky).hasMasks(), "a term matched inside a longer word: " + text);
            }
            check("(⟦0⟧) - ⟦0⟧'s".equals(mask("(SkyBlock) - SkyBlock's", sky).text()),
                    "punctuation next to a term is not a word boundary");
            check("在⟦0⟧玩".equals(mask("在SkyBlock玩", sky).text()),
                    "CJK neighbours are not word boundaries");
            check("前往⟦0⟧嶼".equals(mask("前往天空島嶼", terms("天空島")).text()),
                    "a CJK-edged term must not need a word boundary");

            TranslatorConfig config = translatingConfig();
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("skyblock");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config, recording(sent, text -> "T:" + text));
            service.translateScoreboardLine("Install Skyfoo now");
            pump(service, 2);
            check(sent.equals(List.of("Install Skyfoo now")),
                    "a longer word containing the term was masked on the service path: " + sent);
            passed("D2", "whole-word matching only (Skyfoo/skyblocks/skyblock_2 untouched)");
        }

        /** Multi-word terms match across any horizontal space; the longest match wins. */
        private static void multiWordTermsSpanHorizontalSpaceAndLongestWins() {
            NameMasker.Masked hub = mask("Go to Dungeon   Hub now, not the Hub",
                    terms("hub", "Dungeon Hub"));
            check("Go to ⟦0⟧ now, not the ⟦1⟧".equals(hub.text())
                            && hub.names().equals(List.of("Dungeon   Hub", "Hub")),
                    "multi-word term with repeated spaces was not one span: " + hub.text());
            NameMasker.Masked nbsp = mask("Visit your Private Island", terms("Private Island"));
            check("Visit your ⟦0⟧".equals(nbsp.text())
                            && nbsp.names().equals(List.of("Private Island")),
                    "a no-break space inside a multi-word term was not matched");
            NameMasker.Masked overlap = mask("Dwarven Mines of Moria",
                    terms("Dwarven Mines", "Mines of Moria"));
            check("Dwarven ⟦0⟧".equals(overlap.text())
                            && overlap.names().equals(List.of("Mines of Moria")),
                    "the longer overlapping term did not win: " + overlap.text());
            check(!mask("Dungeon\nHub", terms("Dungeon Hub")).hasMasks(),
                    "a hard line break was treated as horizontal space");

            TranslatorConfig config = translatingConfig();
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("Private Island");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config,
                    recording(sent, text -> text.replace("Welcome to your ", "歡迎來到你的")));
            service.translateScoreboardLine("Welcome to your Private   Island");
            pump(service, 2);
            check(sent.equals(List.of("Welcome to your ⟦0⟧")), "service masked a multi-word term wrongly: " + sent);
            check("歡迎來到你的Private   Island".equals(
                            service.translateScoreboardLine("Welcome to your Private   Island").translated()),
                    "the exact original spacing of a multi-word term was not restored");
            passed("D3", "multi-word terms span 1+ horizontal spaces (incl. NBSP), longest wins");
        }

        /** A literal §x code before a term is a word boundary; a code letter is never a term. */
        private static void sectionCodesAreWordBoundaries() {
            check("§e⟦0⟧ §7Level".equals(mask("§eSkyBlock §7Level", terms("skyblock")).text()),
                    "a section code before a term was not a word boundary");
            check("§e§l⟦0⟧".equals(mask("§e§lSKYBLOCK", terms("skyblock")).text()),
                    "stacked section codes before a term were not word boundaries");
            NameMasker.Masked sentence = mask("§eWelcome to §6SkyBlock§r!", terms("SkyBlock"));
            check("§eWelcome to §6⟦0⟧§r!".equals(sentence.text())
                            && sentence.names().equals(List.of("SkyBlock")),
                    "a term wrapped in section codes was not masked alone: " + sentence.text());
            // The code letter itself is never a term start ("§e" + a word boundary after it).
            for (String codeOnly : List.of("§e Hello", "§e", "§e-Hello")) {
                check(!mask(codeOnly, terms("e")).hasMasks(),
                        "a single-letter term matched a section code letter: " + codeOnly);
            }

            TranslatorConfig config = translatingConfig();
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("SkyBlock");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config,
                    recording(sent, text -> text.replace("Profile", "檔案")));
            service.translateScoreboardLine("§a§lSkyBlock Profile");
            pump(service, 2);
            check(sent.equals(List.of("§a§l⟦0⟧ Profile")),
                    "a section-code-prefixed term was not masked alone on the wire: " + sent);
            String shown = service.translateScoreboardLine("§a§lSkyBlock Profile").translated();
            check(shown != null && shown.contains("SkyBlock") && shown.contains("檔案"),
                    "a section-code-prefixed term was not restored: " + shown);
            passed("D4", "section-sign format codes are word boundaries and never part of a term");
        }

        /** URL and domain fragments are never masked (TemplateText keeps them verbatim). */
        private static void urlsAndDomainsAreNeverMasked() {
            DoNotTranslateMatcher hypixel = terms("hypixel");
            check(!mask("Visit hypixel.net or mc.hypixel.net", hypixel).hasMasks(),
                    "a domain fragment was masked");
            check(!mask("See https://hypixel.net/forums", hypixel).hasMasks(), "a URL was masked");
            check("Welcome to ⟦0⟧! Visit hypixel.net".equals(
                            mask("Welcome to Hypixel! Visit hypixel.net", hypixel).text()),
                    "a standalone term next to its domain was not masked alone");

            TranslatorConfig config = translatingConfig();
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("hypixel");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config, recording(sent, text -> text
                    .replace("Welcome to ", "歡迎來到").replace(" Visit ", " 造訪 ")));
            service.translateScoreboardLine("Welcome to Hypixel! Visit hypixel.net");
            pump(service, 2);
            check(sent.size() == 1 && sent.get(0).startsWith("Welcome to ⟦0⟧! Visit ")
                            && !sent.get(0).contains("Hypixel"),
                    "the standalone term or the domain was handled wrongly on the service path: " + sent);
            check("歡迎來到Hypixel! 造訪 hypixel.net".equals(
                            service.translateScoreboardLine("Welcome to Hypixel! Visit hypixel.net").translated()),
                    "the term or the domain was not restored verbatim");
            passed("D5", "URLs/domains (hypixel.net, https://...) are never masked");
        }

        /** Design §4: a line made only of terms (also colour-wrapped) is never sent anywhere;
         *  a term plus a real word still is. */
        private static void lineMadeOnlyOfTermsIsNeverSent() {
            TranslatorConfig config = translatingConfig();
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.chatMode = DisplayMode.TRANSLATION;
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.screenTextMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("SkyBlock");
            config.doNotTranslateTerms.add("Hypixel");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config, recording(sent, text -> "T:" + text));

            for (String line : List.of("SKYBLOCK", "⟦CS0⟧SKYBLOCK⟦/CS0⟧", "§e§lSKYBLOCK",
                    "⟦CS0⟧SkyBlock⟦/CS0⟧ ⟦CS1⟧2026⟦/CS1⟧", "SKYBLOCK ⟦PB0⟧ SKYBLOCK",
                    "⟦CS0⟧SKYBLOCK⟦/CS0⟧ ⟦PB0⟧ ⟦CS1⟧2026⟦/CS1⟧",
                    "⟦CS0⟧SkyBlock⟦/CS0⟧ ⟦CS1⟧Hypixel⟦/CS1⟧", "SkyBlock Hypixel")) {
                check(!service.translateScoreboardLine(line).changed()
                                && !service.translateItemLine(line).changed()
                                && !service.translateChat(line).changed(),
                        "a term-only line changed: " + line);
                check(service.isTooltipTranslationReady(line), "a term-only tooltip line is not ready: " + line);
                List<String> chat = new ArrayList<String>();
                service.translateChatAsync(line, chat::add);
                check(chat.equals(Arrays.asList((String) null)),
                        "term-only chat did not complete as original: " + line + " -> " + chat);
                List<String> async = new ArrayList<String>();
                service.requestActionBarAsync(line, async::add);
                service.requestLiveScreenTextAsync(line, async::add);
                service.warmTooltipBatch(List.of(line));
                service.warmScoreboardBatch(List.of(line));
                pump(service, 2);
                check(async.isEmpty(), "a term-only line produced an async value: " + line);
            }
            check(sent.isEmpty(), "a line made only of protected terms was sent: " + sent);

            TranslationService shortWords = service(config, recording(sent, text -> text
                    .replace("XP", "經驗").replace("Hub", "大廳")));
            shortWords.translateScoreboardLine("SkyBlock XP");
            shortWords.translateScoreboardLine("SkyBlock Hub");
            pump(shortWords, 2);
            check(sent.equals(List.of("⟦0⟧ XP", "⟦0⟧ Hub")), "a term plus a short word was not sent: " + sent);
            check("SkyBlock 經驗".equals(shortWords.translateScoreboardLine("SkyBlock XP").translated())
                            && "SkyBlock 大廳".equals(shortWords.translateScoreboardLine("SkyBlock Hub").translated()),
                    "a term plus a short word was not translated");
            passed("D6", "term-only lines (also CS/PB-marker or section-code wrapped) send 0 requests");
        }

        /** decide() judges half-transliteration on the masked pair, so "SkyBlock's …"
         *  (and "Steve's …") translations display. */
        private static void possessiveAfterAProtectedTermStillDisplays() {
            TranslatorConfig config = translatingConfig();
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("SkyBlock");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config, recording(sent, text -> text
                    .replace("⟦0⟧'s best island", "⟦0⟧的最佳島嶼")
                    .replace("⟦0⟧'s sword", "⟦0⟧的劍")));
            service.setProtectedNames(() -> Set.of("Steve"));
            service.translateItemLine("SkyBlock's best island");
            service.translateItemLine("Steve's sword");
            pump(service, 2);
            check(sent.equals(List.of("⟦0⟧'s best island", "⟦0⟧'s sword")),
                    "possessive lines were not masked: " + sent);
            check("SkyBlock的最佳島嶼".equals(service.translateItemLine("SkyBlock's best island").translated()),
                    "a verbatim term before CJK was rejected as half-transliteration");
            check("Steve的劍".equals(service.translateItemLine("Steve's sword").translated()),
                    "a verbatim player name before CJK was rejected as half-transliteration");
            passed("D7", "possessive after a term or player name still displays (masked half-translit check)");
        }

        /** AI answers may space the placeholder ("⟦ 0 ⟧"); it is still restored. */
        private static void aiPlaceholderWithInnerSpacesIsRestored() {
            check("歡迎來到 SkyBlock！".equals(NameMasker.unmask("歡迎來到 ⟦ 0 ⟧！", List.of("SkyBlock"))),
                    "unmask did not tolerate a spaced placeholder");
            TranslatorConfig config = translatingConfig();
            config.chatMode = DisplayMode.TRANSLATION;
            config.aiChat = true;
            config.doNotTranslateTerms.add("skyblock");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config,
                    recording(sent, text -> text.replace("Welcome to ⟦0⟧!", "歡迎來到 ⟦ 0 ⟧！")));
            service.translateChat("Welcome to SkyBlock!");
            pump(service, 2);
            check(sent.equals(List.of("Welcome to ⟦0⟧!")), "the AI request was not masked: " + sent);
            check("歡迎來到 SkyBlock！".equals(service.translateChat("Welcome to SkyBlock!").translated()),
                    "a spaced ⟦ 0 ⟧ from the AI was not restored");
            passed("D8", "AI placeholder with inner spaces is restored");
        }

        /** The cache key is the masked text: adding a term switches keys, removing it brings
         *  the old row back without a request, re-adding (any case) hits the masked row. */
        private static void addingOrRemovingTermsChangesOnlyTheKey() {
            TranslatorConfig config = translatingConfig();
            config.scoreboardMode = DisplayMode.TRANSLATION;
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config, recording(sent, text -> text
                    .replace("Welcome to SkyBlock", "歡迎來到天空島")
                    .replace("Welcome to ⟦0⟧", "歡迎來到⟦0⟧")));

            service.translateScoreboardLine("Welcome to SkyBlock");
            pump(service, 2);
            check("歡迎來到天空島".equals(service.translateScoreboardLine("Welcome to SkyBlock").translated()),
                    "setup: the unmasked row was not translated");

            config.doNotTranslateTerms.add("skyblock");
            check(!service.translateScoreboardLine("Welcome to SkyBlock").changed(),
                    "adding a term still served the old (unmasked) wording");
            pump(service, 2);
            check(sent.equals(List.of("Welcome to SkyBlock", "Welcome to ⟦0⟧")),
                    "adding a term did not request the masked key: " + sent);
            check("歡迎來到SkyBlock".equals(service.translateScoreboardLine("Welcome to SkyBlock").translated()),
                    "the masked row did not display the term verbatim");

            config.doNotTranslateTerms.add("Hypixel");
            check("歡迎來到Hypixel".equals(service.translateScoreboardLine("Welcome to Hypixel").translated()),
                    "an edited list was not recompiled onto the shared masked key");

            config.doNotTranslateTerms.clear();
            check("歡迎來到天空島".equals(service.translateScoreboardLine("Welcome to SkyBlock").translated()),
                    "removing the term did not bring the old row back");
            config.doNotTranslateTerms.add("SKYBLOCK");
            check("歡迎來到SkyBlock".equals(service.translateScoreboardLine("Welcome to SkyBlock").translated()),
                    "re-adding the term in another case did not hit the masked row");
            pump(service, 2);
            check(sent.size() == 2, "a term list edit re-sent a cached key: " + sent);
            passed("D9", "add/remove/re-add term only switches the cache key (no clear, no re-send)");
        }

        /** Player names and terms are masked in one pass and never share an index. */
        private static void termsAndPlayerNamesShareOneIndexSpace() {
            NameMasker.Masked both = NameMasker.mask("SkyBlock loves Steve and SkyBlock",
                    Set.of("Steve"), terms("skyblock"));
            check("⟦0⟧ loves ⟦1⟧ and ⟦0⟧".equals(both.text())
                            && both.names().equals(List.of("SkyBlock", "Steve")),
                    "terms and names collided in the index space: " + both.text());
            NameMasker.Masked same = NameMasker.mask("SkyBlock", Set.of("SkyBlock"), terms("skyblock"));
            check("⟦0⟧".equals(same.text()) && same.names().equals(List.of("SkyBlock")),
                    "one span listed as name and term did not get one placeholder");
            NameMasker.Masked longer = NameMasker.mask("Visit Steve's Island today, Steve",
                    Set.of("Steve"), terms("Steve's Island"));
            check("Visit ⟦0⟧ today, ⟦1⟧".equals(longer.text())
                            && longer.names().equals(List.of("Steve's Island", "Steve")),
                    "a term containing a player name did not win as the longer span");

            TranslatorConfig config = translatingConfig();
            config.chatMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("skyblock");
            List<String> sent = new ArrayList<String>();
            TranslationService service = service(config,
                    recording(sent, text -> text.replace(" joined ", " 加入了 ")));
            service.setProtectedNames(() -> Set.of("Steve"));
            service.translateChat("Steve joined SkyBlock");
            pump(service, 2);
            check(sent.equals(List.of("⟦0⟧ joined ⟦1⟧")), "name + term masking collided: " + sent);
            check("Steve 加入了 SkyBlock".equals(service.translateChat("Steve joined SkyBlock").translated()),
                    "name + term were not both restored");

            TranslatorConfig namesOff = translatingConfig();
            namesOff.chatMode = DisplayMode.TRANSLATION;
            namesOff.protectPlayerNames = false;
            namesOff.doNotTranslateTerms.add("skyblock");
            List<String> sentOff = new ArrayList<String>();
            TranslationService offService = service(namesOff, recording(sentOff, text -> "T:" + text));
            offService.setProtectedNames(() -> Set.of("Steve"));
            offService.translateChat("Steve plays SkyBlock");
            pump(offService, 2);
            check(sentOff.equals(List.of("Steve plays ⟦0⟧")),
                    "terms must apply even with player-name protection off: " + sentOff);
            passed("D10", "terms and player names share one placeholder index space (no collision)");
        }

        /** R17 extended to terms: a translation that lost the placeholder never displays and
         *  self-heals only once. */
        private static void translationThatLostATermNeverDisplays() {
            TranslatorConfig config = translatingConfig();
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("SkyBlock");
            AtomicInteger calls = new AtomicInteger();
            Translator placeholderEater = (text, target) -> {
                calls.incrementAndGet();
                return new TranslationResult("歡迎來到天空島", "en");
            };
            TranslationService service = service(config, placeholderEater);

            service.translateItemLine("Welcome to SkyBlock");
            pump(service, 2);
            check(calls.get() == 1, "setup: the masked line was not requested");
            check(!service.translateItemLine("Welcome to SkyBlock").changed(),
                    "a translation that lost the protected term was displayed");
            service.translateItemLine("Welcome to SkyBlock");
            pump(service, 2);
            check(calls.get() == 2, "the damaged row did not self-heal exactly once");
            check(!service.translateItemLine("Welcome to SkyBlock").changed(),
                    "the re-bought damaged translation was displayed");
            service.translateItemLine("Welcome to SkyBlock");
            pump(service, 2);
            check(calls.get() == 2, "self-heal was not debounced");
            passed("D11", "translation that lost a term never displays; self-heals once");
        }

        /** Design §4 "all surfaces": every service entry point that sends text (20 senders,
         *  including the ones no glue uses and the quest live-screen path, plus the item-name
         *  re-ask) sends only masked text and never the raw term, not even as AI surface
         *  context; render lookups then hit the same masked keys. The remaining masking entry
         *  points are covered by D1 (chat), D6 (tooltip ready), D11 and S8 (invalidation). */
        private static void everyServiceEntryPointMasksTerms() {
            TranslatorConfig config = translatingConfig();
            config.chatMode = DisplayMode.TRANSLATION;
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.scoreboardMode = DisplayMode.TRANSLATION;
            config.nameMode = DisplayMode.TRANSLATION;
            config.bossBarMode = DisplayMode.TRANSLATION;
            config.titleMode = DisplayMode.TRANSLATION;
            config.actionBarMode = DisplayMode.TRANSLATION;
            config.bookMode = DisplayMode.TRANSLATION;
            config.screenTextMode = DisplayMode.TRANSLATION;
            config.doNotTranslateTerms.add("SkyBlock");
            List<String> seen = new ArrayList<String>();
            TranslationService service = service(config, contextRecording(seen));
            List<List<String>> segments = new ArrayList<List<String>>();
            List<String> chat = new ArrayList<String>();
            List<String> scan = new ArrayList<String>();
            List<String> live = new ArrayList<String>();
            List<String> bar = new ArrayList<String>();

            service.translateChatSegmentsAsync(List.of("Welcome to SkyBlock", "SKYBLOCK"), segments::add);
            service.requestChatAsync("Play SkyBlock now", chat::add);
            service.requestScreenTextAsync("Open the SkyBlock menu", scan::add);
            service.requestLiveScreenTextAsync("Visit the SkyBlock hub", live::add); // quest widgets
            service.requestActionBarAsync("You found SkyBlock coins", bar::add);
            service.warmTooltipBatch(List.of("SkyBlock Menu", "Click to open the SkyBlock menu"));
            service.warmNamesBatch(List.of("SkyBlock Sword"));
            service.warmScoreboardBatch(List.of("SkyBlock Purse"));
            service.warmBookBatch(List.of("Chapter one of SkyBlock"));
            check(service.warmUp("SkyBlock Pickaxe"), "the blocking warm-up of a term line failed");
            String[] lookups = {"SkyBlock Helmet", "SkyBlock Boots", "SkyBlock Guide", "SkyBlock Dragon",
                    "Welcome back to SkyBlock", "SkyBlock mana restored", "The SkyBlock story",
                    "SkyBlock settings"};
            for (int round = 0; round < 2; round++) {
                TranslationDecision[] decisions = {
                        service.translateItemLine(lookups[0]),
                        service.translateHeld(lookups[1]),
                        service.translateUi(lookups[2]),
                        service.translateBossBar(lookups[3]),
                        service.translateTitle(lookups[4]),
                        service.translateActionBar(lookups[5]),
                        service.translateBook(lookups[6]),
                        service.translateScreenScanText(lookups[7])
                };
                for (int i = 0; i < decisions.length; i++) {
                    if (round == 0) {
                        check(!decisions[i].changed(), "a masked miss displayed a value: " + lookups[i]);
                    } else {
                        check(("T:" + lookups[i]).equals(decisions[i].translated()),
                                "a render lookup was not restored: " + lookups[i] + " -> "
                                        + decisions[i].translated());
                    }
                }
                pump(service, 2);
            }

            check(segments.equals(List.of(List.of("T:Welcome to SkyBlock", "SKYBLOCK"))),
                    "chat segments were not masked/restored (term-only segment unsent): " + segments);
            check(chat.equals(List.of("T:Play SkyBlock now")), "requestChatAsync: " + chat);
            check(scan.equals(List.of("T:Open the SkyBlock menu")), "requestScreenTextAsync: " + scan);
            check(live.equals(List.of("T:Visit the SkyBlock hub")), "requestLiveScreenTextAsync: " + live);
            check(bar.equals(List.of("T:You found SkyBlock coins")), "requestActionBarAsync: " + bar);
            int sentBeforeRender = seen.size();
            check("T:Visit the SkyBlock hub".equals(service.translateScreenText("Visit the SkyBlock hub").translated())
                            && "T:SkyBlock Menu".equals(service.translateItemLine("SkyBlock Menu").translated())
                            && "T:SkyBlock Purse".equals(service.translateScoreboardLine("SkyBlock Purse").translated()),
                    "async/warm entry points and their render lookups do not share the masked keys");
            pump(service, 2);
            check(seen.size() == sentBeforeRender, "a render lookup re-sent an already cached masked key");
            String joined = String.join("|", seen);
            check(!joined.toLowerCase(java.util.Locale.ROOT).contains("skyblock"),
                    "the raw term left the client (request text or AI surface context): " + joined);
            for (String masked : List.of("Welcome to ⟦0⟧", "Play ⟦0⟧ now", "Open the ⟦0⟧ menu",
                    "Visit the ⟦0⟧ hub", "You found ⟦0⟧ coins", "⟦0⟧ Menu", "Click to open the ⟦0⟧ menu",
                    "⟦0⟧ Sword", "⟦0⟧ Purse", "Chapter one of ⟦0⟧", "⟦0⟧ Pickaxe", "⟦0⟧ Helmet",
                    "⟦0⟧ Boots", "⟦0⟧ Guide", "⟦0⟧ Dragon", "Welcome back to ⟦0⟧", "⟦0⟧ mana restored",
                    "The ⟦0⟧ story", "⟦0⟧ settings")) {
                check(seen.contains(masked), "an entry point did not send its masked form: " + masked);
            }

            // reconcileItemNameWithTooltip re-asks the AI for a mismatching item name with the
            // tooltip read so far as surface context; that context must be masked too.
            TranslatorConfig tooltipConfig = translatingConfig();
            tooltipConfig.tooltipMode = DisplayMode.TRANSLATION;
            tooltipConfig.aiTooltip = true;
            tooltipConfig.doNotTranslateTerms.add("SkyBlock");
            List<String> reasked = new ArrayList<String>();
            TranslationCache gt = new TranslationCache(contextRecording(reasked), tooltipConfig.targetLang,
                    DIRECT, 100);
            TranslationCache ai = new TranslationCache(contextRecording(reasked), tooltipConfig.targetLang,
                    DIRECT, 100);
            ai.importTranslations(Map.of(
                    "Aspect of the End", "末影之視",
                    "Aspect of the End Sword Ability", "終界之刃 劍技",
                    "Sword Ability", "不相符的後綴"));
            TranslationService reconciling = new TranslationService(tooltipConfig, gt, ai);
            reconciling.reconcileItemNameWithTooltip("Aspect of the End",
                    List.of("Right click on SkyBlock to use", "Aspect of the End Sword Ability"));
            check(reasked.contains("Aspect of the End") && reasked.contains("Right click on ⟦0⟧ to use"),
                    "the item-name re-ask did not carry its masked tooltip context: " + reasked);
            check(!String.join("|", reasked).toLowerCase(java.util.Locale.ROOT).contains("skyblock"),
                    "the item-name re-ask leaked the raw term as AI context: " + reasked);
            passed("D12", "21 sending entry points use masked text only (incl. quest widgets, AI context, item re-ask)");
        }
    }

    /** Dependency-free copy of every hostile Codex state regression.
     * It is compiled against and executes each release output/JAR, including Java 16. */
    private static final class CodexStateHostileSuite {
        private interface ThrowingAction {
            void run() throws Exception;
        }

        private static int runAll() throws Exception {
            CodexStateHostileSuite suite = new CodexStateHostileSuite();
            suite.earlyNotificationsAreConsumedAtomicallyAndUnknownFloodStaysBounded();
            suite.messageAndCompletionOrderingIsLosslessBeforeAndAfterRegistration();
            suite.completedTurnWithoutMessageFailsAfterBoundedGrace();
            suite.oversizedTurnMessageAndJsonlInputFailWithoutRetainingPayloads();
            suite.pendingRequestsAndLoginStateHaveIndependentHardCaps();
            suite.oversizedProcessStreamsStopTheChildClearStateAndRejectLateRequests();
            suite.requestRegistrationAndWriteAreAtomicAgainstProcessFailure();
            suite.staleGenerationCannotRegisterStateOrWriteToReplacementWriter();
            suite.mainRequestWriteFailureStopsAndFailsTheWholeGeneration();
            suite.localOutgoingOversizeDoesNotKillHealthyGenerationOrOtherRequests();
            suite.earlyTurnMessageAndCompletionAreFirstWinsIncludingOversize();
            suite.taggedProcessErrorsAndEarlyLoginNeverWaitForLifecycleMonitor();
            suite.pausedOldProcessErrorCannotEraseReplacementDiagnostic();
            suite.readerFailureDuringInitializeWakesPendingWithoutLifecycleMonitor();
            suite.stateOnlyGenerationIsClearedBeforeTakeover();
            suite.identifierAndThreadCapsRejectFloodAndStillUnsubscribeCreatedThread();
            suite.readerServerRequestReplyDoesNotWaitForLifecycleMonitor();
            suite.unknownLoginIdFailsImmediatelyAndOldCleanupCannotRemoveNewIdentity();
            suite.closedThreadsAcceptLateUsageUntilEvictionAndReuseStartsANewBaseline();
            suite.processFailureClearsActiveTurnsAndReadyGuardRejectsLateRegistration();
            suite.turnStartFailureAlwaysClosesKnownThreadAndUnsubscribes();
            return 21;
        }

        private static void assertTrue(boolean condition) {
            assertTrue(condition, "expected condition to be true");
        }

        private static void assertTrue(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }

        private static void assertTrue(boolean condition, Supplier<String> message) {
            if (!condition) throw new AssertionError(message.get());
        }

        private static void assertFalse(boolean condition, String message) {
            if (condition) throw new AssertionError(message);
        }

        private static void assertFalse(boolean condition) {
            assertFalse(condition, "expected condition to be false");
        }

        private static void assertSame(Object expected, Object actual) {
            if (expected != actual) {
                throw new AssertionError("expected same identity but got " + actual);
            }
        }

        private static void assertEquals(long expected, long actual) {
            assertEquals(expected, actual, "values differed");
        }

        private static void assertEquals(long expected, long actual, String message) {
            if (expected != actual) {
                throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
            }
        }

        private static void assertEquals(Object expected, Object actual) {
            assertEquals(expected, actual, "values differed");
        }

        private static void assertEquals(Object expected, Object actual, String message) {
            if (!Objects.equals(expected, actual)) {
                throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
            }
        }

        private static <T extends Throwable> T assertThrows(
                Class<T> expectedType, ThrowingAction action) throws Exception {
            try {
                action.run();
            } catch (Throwable failure) {
                if (expectedType.isInstance(failure)) return expectedType.cast(failure);
                if (failure instanceof Exception exception) throw exception;
                if (failure instanceof Error error) throw error;
                throw new AssertionError("unexpected throwable", failure);
            }
            throw new AssertionError("expected " + expectedType.getName() + " to be thrown");
        }



        void earlyNotificationsAreConsumedAtomicallyAndUnknownFloodStaysBounded()
                throws Exception {
            CodexAppServerClient client = client();

            notifyItem(client, "turn-early", "translated");
            notifyCompleted(client, "turn-early");
            assertEquals(1, retainedSize(client, "earlyTurns"));
            assertEquals(0, retainedSize(client, "turnResults"));

            setReady(client, true);
            CompletableFuture<JsonObject> completed = registerTurn(client, "turn-early");
            assertEquals("turn-early", completed.get(1, TimeUnit.SECONDS)
                    .getAsJsonObject("turn").get("id").getAsString());
            assertEquals("translated", awaitTurnMessage(client, "turn-early"));
            assertEquals(0, retainedSize(client, "earlyTurns"));
            assertEquals(1, retainedSize(client, "turnResults"));

            closeTurn(client, "turn-early");
            notifyItem(client, "turn-early", "late");
            notifyCompleted(client, "turn-early");
            assertEquals(0, retainedSize(client, "earlyTurns"));
            assertEquals(0, retainedSize(client, "turnResults"));

            for (int i = 0; i < 1_000; i++) {
                String turnId = "unknown-" + i;
                notifyItem(client, turnId, "message-" + i);
                notifyCompleted(client, turnId);
            }
            assertEquals(512, retainedSize(client, "earlyTurns"));
            assertEquals(0, retainedSize(client, "turnResults"));

            client.close();
        }

        void messageAndCompletionOrderingIsLosslessBeforeAndAfterRegistration()
                throws Exception {
            CodexAppServerClient client = client(Duration.ofSeconds(1));
            setReady(client, true);

            CompletableFuture<JsonObject> messageFirst = registerTurn(client, "registered-message-first");
            notifyItem(client, "registered-message-first", "message first");
            notifyCompleted(client, "registered-message-first");
            messageFirst.get(1, TimeUnit.SECONDS);
            assertEquals("message first", awaitTurnMessage(client, "registered-message-first"));

            CompletableFuture<JsonObject> completedFirst = registerTurn(client, "registered-completed-first");
            notifyCompleted(client, "registered-completed-first");
            completedFirst.get(1, TimeUnit.SECONDS);
            AtomicReference<Throwable> lateFailure = new AtomicReference<>();
            Thread lateMessage = new Thread(() -> {
                try {
                    Thread.sleep(50L);
                    notifyItem(client, "registered-completed-first", "completed first");
                } catch (Throwable failure) {
                    lateFailure.set(failure);
                }
            }, "codex-state-test-late-message");
            lateMessage.start();
            assertEquals("completed first", awaitTurnMessage(client, "registered-completed-first"));
            lateMessage.join(1_000L);
            assertTrue(!lateMessage.isAlive(), "late-message test thread did not finish");
            if (lateFailure.get() != null) {
                throw new AssertionError("late message delivery failed", lateFailure.get());
            }

            notifyItem(client, "early-message-first", "early message first");
            notifyCompleted(client, "early-message-first");
            CompletableFuture<JsonObject> earlyMessageFirst = registerTurn(client, "early-message-first");
            earlyMessageFirst.get(1, TimeUnit.SECONDS);
            assertEquals("early message first", awaitTurnMessage(client, "early-message-first"));

            notifyCompleted(client, "early-completed-first");
            notifyItem(client, "early-completed-first", "early completed first");
            CompletableFuture<JsonObject> earlyCompletedFirst = registerTurn(client, "early-completed-first");
            earlyCompletedFirst.get(1, TimeUnit.SECONDS);
            assertEquals("early completed first", awaitTurnMessage(client, "early-completed-first"));

            for (String turnId : List.of(
                    "registered-message-first",
                    "registered-completed-first",
                    "early-message-first",
                    "early-completed-first")) {
                closeTurn(client, turnId);
            }
            client.close();
        }

        void completedTurnWithoutMessageFailsAfterBoundedGrace() throws Exception {
            CodexAppServerClient client = client(Duration.ofMillis(500));
            setReady(client, true);
            CompletableFuture<JsonObject> completed = registerTurn(client, "missing-message");
            notifyCompleted(client, "missing-message");
            completed.get(1, TimeUnit.SECONDS);

            long started = System.nanoTime();
            IOException failure = assertThrows(IOException.class,
                    () -> awaitTurnMessage(client, "missing-message"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(failure.getMessage().contains("within 500 ms"), failure::getMessage);
            assertTrue(elapsedMillis >= 350L, "message grace ended too early: " + elapsedMillis + " ms");
            assertTrue(elapsedMillis < 2_000L, "message grace was not bounded: " + elapsedMillis + " ms");

            client.close();
        }

        void oversizedTurnMessageAndJsonlInputFailWithoutRetainingPayloads() throws Exception {
            CodexAppServerClient client = client(Duration.ofSeconds(1));
            setReady(client, true);
            registerTurn(client, "oversized-message");
            notifyItem(client, "oversized-message", "x".repeat(65_537));
            notifyCompleted(client, "oversized-message");

            IOException messageFailure = assertThrows(IOException.class,
                    () -> awaitTurnMessage(client, "oversized-message"));
            assertTrue(messageFailure.getMessage().contains("exceeds 65536 characters"),
                    messageFailure::getMessage);

            IOException jsonlFailure = assertThrows(IOException.class, () -> invoke(
                    client, "handleLine", new Class<?>[]{String.class}, "x".repeat(1_000_001)));
            assertTrue(jsonlFailure.getMessage().contains("exceeds 1000000 characters"),
                    jsonlFailure::getMessage);

            BufferedReader reader = new BufferedReader(new StringReader("12345\n"));
            IOException lineFailure = assertThrows(IOException.class, () -> invoke(
                    client, "readBoundedLine", new Class<?>[]{BufferedReader.class, int.class},
                    reader, 4));
            assertTrue(lineFailure.getMessage().contains("exceeds 4 characters"),
                    lineFailure::getMessage);

            client.close();
        }

        void pendingRequestsAndLoginStateHaveIndependentHardCaps() throws Exception {
            CodexAppServerClient client = client();
            StubProcess process = new StubProcess();
            field(client, "process").set(client, process);
            field(client, "writer").set(client, new BufferedWriter(Writer.nullWriter()));
            List<CompletableFuture<JsonElement>> pendingFutures = new ArrayList<>();
            for (int i = 0; i < 512; i++) {
                CompletableFuture<JsonElement> future = new CompletableFuture<>();
                pendingFutures.add(future);
                registerPending(client, "pending-" + i, future);
            }
            assertEquals(512, retainedSize(client, "pending"));
            IOException pendingFailure = assertThrows(IOException.class, () ->
                    registerPending(client, "pending-overflow", new CompletableFuture<>()));
            assertTrue(pendingFailure.getMessage().contains("Too many pending"),
                    pendingFailure::getMessage);

            setReady(client, true);
            for (int i = 0; i < 1_000; i++) {
                notifyLoginCompleted(client, "early-login-" + i, true);
            }
            assertEquals(0, retainedSize(client, "loginResults"));
            assertEquals(128, retainedSize(client, "completedLoginResults"));
            CompletableFuture<Boolean> latestEarly = registerLogin(client, "early-login-999");
            assertTrue(latestEarly.get(1, TimeUnit.SECONDS));
            assertEquals(1, retainedSize(client, "loginResults"));
            assertEquals(127, retainedSize(client, "completedLoginResults"));
            invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                    "early-login-999", latestEarly);

            List<CompletableFuture<Boolean>> activeLogins = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                CompletableFuture<Boolean> login = registerLogin(client, "active-login-" + i);
                activeLogins.add(login);
            }
            assertEquals(128, retainedSize(client, "loginResults"));
            IOException loginFailure = assertThrows(IOException.class, () ->
                    registerLogin(client, "active-login-overflow"));
            assertTrue(loginFailure.getMessage().contains("Too many active"),
                    loginFailure::getMessage);

            client.close();
            assertTrue(pendingFutures.stream().allMatch(CompletableFuture::isCompletedExceptionally));
            assertTrue(activeLogins.stream().allMatch(CompletableFuture::isCompletedExceptionally));
            assertEquals(0, retainedSize(client, "pending"));
            assertEquals(0, retainedSize(client, "loginResults"));
            assertEquals(0, retainedSize(client, "completedLoginResults"));
        }

        void oversizedProcessStreamsStopTheChildClearStateAndRejectLateRequests()
                throws Exception {
            for (boolean stderr : List.of(false, true)) {
                int limit = stderr ? 16_384 : 1_000_000;
                byte[] oversized = "x".repeat(limit + 1).getBytes(StandardCharsets.UTF_8);
                InputStream stdout = stderr
                        ? InputStream.nullInputStream()
                        : new ByteArrayInputStream(oversized);
                InputStream error = stderr
                        ? new ByteArrayInputStream(oversized)
                        : InputStream.nullInputStream();
                StubProcess process = new StubProcess(stdout, error);
                CodexAppServerClient client = client();
                field(client, "process").set(client, process);
                field(client, "writer").set(client, new BufferedWriter(Writer.nullWriter()));
                setReady(client, true);

                CompletableFuture<JsonElement> pending = new CompletableFuture<>();
                registerPending(client, "pending-before-" + stderr, pending);
                CompletableFuture<JsonObject> turn = registerTurn(client, "turn-before-" + stderr);
                CompletableFuture<Boolean> login = registerLogin(client, "login-before-" + stderr);
                openThread(client, "thread-before-" + stderr);
                notifyItem(client, "early-before-" + stderr, "early message");
                notifyLoginCompleted(client, "early-login-before-" + stderr, true);

                if (stderr) {
                    invoke(client, "startStderrReader", new Class<?>[]{Process.class}, process);
                } else {
                    invoke(client, "startReader",
                            new Class<?>[]{Process.class, BufferedWriter.class},
                            process, field(client, "writer").get(client));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((!pending.isDone() || !turn.isDone() || !login.isDone())
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }

                assertTrue(pending.isCompletedExceptionally(), "pending future survived " + stream(stderr));
                assertTrue(turn.isCompletedExceptionally(), "turn future survived " + stream(stderr));
                assertTrue(login.isCompletedExceptionally(), "login future survived " + stream(stderr));
                assertTrue(!process.isAlive(), stream(stderr) + " failure left the child alive");
                assertEquals(null, field(client, "process").get(client));
                assertEquals(null, field(client, "writer").get(client));
                assertEquals(0, retainedSize(client, "pending"));
                assertEquals(0, retainedSize(client, "turnResults"));
                assertEquals(0, retainedSize(client, "earlyTurns"));
                assertEquals(0, retainedSize(client, "loginResults"));
                assertEquals(0, retainedSize(client, "completedLoginResults"));
                assertEquals(0, retainedSize(client, "activeThreads"));
                assertTrue(client.lastError().contains("line exceeds " + limit + " characters"),
                        client::lastError);

                IOException lateRegistration = assertThrows(IOException.class, () ->
                        registerPending(client, "pending-after-" + stderr,
                                new CompletableFuture<>()));
                assertTrue(lateRegistration.getMessage().contains("line exceeds " + limit + " characters"),
                        lateRegistration::getMessage);
                assertEquals(0, retainedSize(client, "pending"));
                client.close();
            }
        }

        void requestRegistrationAndWriteAreAtomicAgainstProcessFailure() throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            Process generation = currentProcess(client);
            BlockingWriter sink = new BlockingWriter();
            field(client, "writer").set(client, new BufferedWriter(sink));
            CompletableFuture<JsonElement> future = new CompletableFuture<>();
            AtomicReference<Throwable> requestFailure = new AtomicReference<>();
            Thread request = new Thread(() -> {
                try {
                    registerPending(client, "atomic", future);
                } catch (Throwable failure) {
                    requestFailure.set(failure);
                }
            }, "codex-atomic-request");
            request.start();
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS), "request never reached its writer");

            AtomicReference<Throwable> failureThreadError = new AtomicReference<>();
            Thread failure = new Thread(() -> {
                try {
                    invoke(client, "failIfCurrent",
                            new Class<?>[]{Process.class, IOException.class},
                            generation, new IOException("forced generation failure"));
                } catch (Throwable problem) {
                    failureThreadError.set(problem);
                }
            }, "codex-generation-failure");
            failure.start();
            Thread.sleep(50L);
            assertTrue(failure.isAlive(),
                    "process failure interleaved between pending registration and write");
            assertEquals(1, retainedSize(client, "pending"));

            sink.release.countDown();
            request.join(1_000L);
            failure.join(1_000L);
            assertTrue(!request.isAlive() && !failure.isAlive(), "atomic race threads did not finish");
            assertEquals(null, requestFailure.get());
            assertEquals(null, failureThreadError.get());
            assertTrue(future.isCompletedExceptionally());
            assertEquals(0, retainedSize(client, "pending"));
            assertEquals(null, field(client, "process").get(client));
            client.close();
        }

        void staleGenerationCannotRegisterStateOrWriteToReplacementWriter() throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            StubProcess oldProcess = (StubProcess) currentProcess(client);
            StubProcess newProcess = new StubProcess();
            RecordingWriter replacementSink = new RecordingWriter();
            field(client, "process").set(client, newProcess);
            field(client, "writer").set(client, new BufferedWriter(replacementSink));

            assertThrows(IOException.class, () -> invoke(client, "registerTurn",
                    new Class<?>[]{String.class, Process.class}, "same-turn", oldProcess));
            assertThrows(IOException.class, () -> invoke(client, "registerLogin",
                    new Class<?>[]{String.class, Process.class}, "same-login", oldProcess));
            assertThrows(IOException.class, () -> invoke(client, "openThread",
                    new Class<?>[]{String.class, Process.class}, "same-thread", oldProcess));
            assertThrows(IOException.class, () -> invoke(client, "registerAndSendRequest",
                    new Class<?>[]{Process.class, String.class, CompletableFuture.class,
                            JsonObject.class},
                    oldProcess, "stale-request", new CompletableFuture<JsonElement>(),
                    request("stale-request")));

            JsonObject unsubscribe = new JsonObject();
            unsubscribe.addProperty("threadId", "old-thread");
            invoke(client, "sendBestEffortRequest",
                    new Class<?>[]{Process.class, String.class, JsonObject.class},
                    oldProcess, "thread/unsubscribe", unsubscribe);
            assertEquals("", replacementSink.payload.toString(),
                    "old generation wrote into the replacement writer");
            assertEquals(0, retainedSize(client, "pending"));
            oldProcess.destroy();
            client.close();
        }

        void mainRequestWriteFailureStopsAndFailsTheWholeGeneration() throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            StubProcess generation = (StubProcess) currentProcess(client);
            field(client, "writer").set(client, new BufferedWriter(new FailingWriter()));
            CompletableFuture<JsonElement> future = new CompletableFuture<>();

            IOException failure = assertThrows(IOException.class,
                    () -> registerPending(client, "write-failure", future));

            assertTrue(failure.getMessage().contains("forced writer failure"), failure::getMessage);
            assertTrue(!generation.isAlive(), "failed request writer left its process alive");
            assertTrue(future.isCompletedExceptionally(), "failed request future was orphaned");
            assertEquals(null, field(client, "process").get(client));
            assertEquals(null, field(client, "writer").get(client));
            assertEquals(0, retainedSize(client, "pending"));
            client.close();
        }

        void localOutgoingOversizeDoesNotKillHealthyGenerationOrOtherRequests()
                throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            Process generation = currentProcess(client);
            CompletableFuture<JsonElement> existing = new CompletableFuture<>();
            registerPending(client, "existing", existing);

            JsonObject oversized = request("oversized");
            oversized.addProperty("payload", "x".repeat(1_000_001));
            CompletableFuture<JsonElement> rejected = new CompletableFuture<>();
            IOException failure = assertThrows(IOException.class, () -> invoke(
                    client, "registerAndSendRequest",
                    new Class<?>[]{Process.class, String.class, CompletableFuture.class,
                            JsonObject.class},
                    generation, "oversized", rejected, oversized));

            assertTrue(failure.getMessage().contains("outgoing message exceeds 1000000"),
                    failure::getMessage);
            assertSame(generation, currentProcess(client));
            assertTrue(generation.isAlive());
            assertFalse(existing.isDone(), "local invalid input failed an unrelated request");
            assertFalse(rejected.isDone(), "unregistered local input was mutated");
            assertEquals(1, retainedSize(client, "pending"));
            client.close();
        }

        void earlyTurnMessageAndCompletionAreFirstWinsIncludingOversize()
                throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);

            notifyItem(client, "early-oversized-first", "x".repeat(65_537));
            notifyItem(client, "early-oversized-first", "must-not-recover");
            notifyCompletedStatus(client, "early-oversized-first", "failed");
            notifyCompletedStatus(client, "early-oversized-first", "completed");
            CompletableFuture<JsonObject> completed =
                    registerTurn(client, "early-oversized-first");
            assertEquals("failed", completed.get(1, TimeUnit.SECONDS)
                    .getAsJsonObject("turn").get("status").getAsString());
            IOException failure = assertThrows(IOException.class,
                    () -> awaitTurnMessage(client, "early-oversized-first"));
            assertTrue(failure.getMessage().contains("exceeds 65536"), failure::getMessage);

            notifyItem(client, "early-valid-first", "first");
            notifyItem(client, "early-valid-first", "second");
            notifyCompleted(client, "early-valid-first");
            registerTurn(client, "early-valid-first").get(1, TimeUnit.SECONDS);
            assertEquals("first", awaitTurnMessage(client, "early-valid-first"));
            client.close();
        }

        void taggedProcessErrorsAndEarlyLoginNeverWaitForLifecycleMonitor()
                throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            Process old = currentProcess(client);
            field(client, "lastError").set(client, "baseline");
            invoke(client, "recordProcessError",
                    new Class<?>[]{Process.class, String.class}, old, "old stderr");
            assertEquals("old stderr", client.lastError());

            StubProcess replacement = new StubProcess();
            field(client, "process").set(client, replacement);
            assertEquals("baseline", client.lastError(), "old tagged stderr leaked generations");
            invoke(client, "recordProcessError",
                    new Class<?>[]{Process.class, String.class}, old, "stale stderr");
            assertEquals("baseline", client.lastError());

            Object lifecycle = field(client, "lifecycleLock").get(client);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            synchronized (lifecycle) {
                Thread errorReader = new Thread(() -> {
                    try {
                        invoke(client, "recordProcessError",
                                new Class<?>[]{Process.class, String.class},
                                replacement, "replacement stderr");
                        assertEquals("replacement stderr", client.lastError());
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                }, "codex-lock-free-stderr");
                errorReader.start();
                errorReader.join(500L);
                assertFalse(errorReader.isAlive(), "stderr/lastError waited for lifecycleLock");

                setReady(client, false); // initialize is still in progress
                Thread loginReader = new Thread(() -> {
                    try {
                        notifyLoginCompleted(client, "early-during-init", true);
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                }, "codex-lock-free-login");
                loginReader.start();
                loginReader.join(500L);
                assertFalse(loginReader.isAlive(), "early login notification deadlocked initialize");
            }
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(1, retainedSize(client, "completedLoginResults"));
            client.close();
        }

        void pausedOldProcessErrorCannotEraseReplacementDiagnostic() throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            Process old = currentProcess(client);
            CountDownLatch oldObserved = new CountDownLatch(1);
            CountDownLatch releaseOld = new CountDownLatch(1);
            AtomicBoolean pauseFirst = new AtomicBoolean(true);
            AtomicReference<Throwable> oldFailure = new AtomicReference<>();
            client.setProcessErrorHookForTests(() -> {
                if (!pauseFirst.compareAndSet(true, false)) return;
                oldObserved.countDown();
                try {
                    if (!releaseOld.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release stale error writer");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("stale error writer interrupted", interrupted);
                }
            });

            Thread oldReader = new Thread(() -> {
                try {
                    invoke(client, "recordProcessError",
                            new Class<?>[]{Process.class, String.class}, old, "stale stderr");
                } catch (Throwable failure) {
                    oldFailure.set(failure);
                }
            }, "codex-stale-error-writer");
            oldReader.start();
            assertTrue(oldObserved.await(1, TimeUnit.SECONDS),
                    "old reader did not pause after observing its error slot");

            StubProcess replacement = new StubProcess();
            field(client, "process").set(client, replacement);
            @SuppressWarnings("unchecked")
            AtomicReference<Object> errorSlot =
                    (AtomicReference<Object>) field(client, "processError").get(client);
            errorSlot.set(null); // production generation activation clears the prior tag
            invoke(client, "recordProcessError",
                    new Class<?>[]{Process.class, String.class}, replacement, "replacement stderr");
            assertEquals("replacement stderr", client.lastError());

            releaseOld.countDown();
            oldReader.join(1_000L);
            assertFalse(oldReader.isAlive());
            if (oldFailure.get() != null) throw new AssertionError(oldFailure.get());
            assertEquals("replacement stderr", client.lastError(),
                    "paused old reader overwrote the replacement generation's error tag");
            client.setProcessErrorHookForTests(null);
            old.destroy();
            client.close();
        }

        void readerFailureDuringInitializeWakesPendingWithoutLifecycleMonitor()
                throws Exception {
            CodexAppServerClient client = client();
            setReady(client, false);
            StubProcess generation = (StubProcess) currentProcess(client);
            field(client, "initializingProcess").set(client, generation);
            CompletableFuture<JsonElement> initialize = new CompletableFuture<>();
            registerPending(client, "initialize", initialize);
            Object lifecycle = field(client, "lifecycleLock").get(client);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            synchronized (lifecycle) {
                Thread reader = new Thread(() -> {
                    try {
                        invoke(client, "failIfCurrent",
                                new Class<?>[]{Process.class, IOException.class},
                                generation, new IOException("stdout ended during initialize"));
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                }, "codex-initialize-reader-failure");
                reader.start();
                reader.join(500L);
                assertFalse(reader.isAlive(),
                        "reader failure waited for lifecycleLock and left initialize to time out");
                assertTrue(initialize.isCompletedExceptionally(),
                        "initialize future was not failed directly by its reader generation");
                assertFalse(generation.isAlive(),
                        "failed initialization child was left alive while cleanup was pending");
                assertEquals(0, retainedSize(client, "pending"));
            }
            if (failure.get() != null) throw new AssertionError(failure.get());
            client.close();

            InputStream brokenStderr = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("stderr failed after child exit");
                }
            };
            CodexAppServerClient deadChildClient = client();
            StubProcess deadChild = new StubProcess(InputStream.nullInputStream(), brokenStderr);
            field(deadChildClient, "process").set(deadChildClient, deadChild);
            field(deadChildClient, "writer").set(deadChildClient,
                    new BufferedWriter(Writer.nullWriter()));
            setReady(deadChildClient, false);
            field(deadChildClient, "initializingProcess").set(deadChildClient, deadChild);
            CompletableFuture<JsonElement> deadChildInitialize = new CompletableFuture<>();
            registerPending(deadChildClient, "initialize-dead-child", deadChildInitialize);
            deadChild.destroy();
            Object deadChildLifecycle = field(deadChildClient, "lifecycleLock").get(deadChildClient);
            synchronized (deadChildLifecycle) {
                invoke(deadChildClient, "startStderrReader",
                        new Class<?>[]{Process.class}, deadChild);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L);
                while (!deadChildInitialize.isDone() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(deadChildInitialize.isCompletedExceptionally(),
                        "dead-child stderr failure waited for stdout or initialize timeout");
            }
            deadChildClient.close();
        }

        void stateOnlyGenerationIsClearedBeforeTakeover() throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            CompletableFuture<Boolean> stale = registerLogin(client, "state-only-login");
            ((StubProcess) currentProcess(client)).destroy();
            field(client, "process").set(client, null);
            field(client, "writer").set(client, null);
            setReady(client, false);

            invoke(client, "clearStaleGenerationState", new Class<?>[0]);
            assertTrue(stale.isCompletedExceptionally());
            assertEquals(0, retainedSize(client, "loginResults"));
            assertEquals(0, retainedSize(client, "completedLoginResults"));
            client.close();
        }

        void identifierAndThreadCapsRejectFloodAndStillUnsubscribeCreatedThread()
                throws Exception {
            CodexAppServerClient client = client();
            StubProcess process = (StubProcess) currentProcess(client);
            ProtocolWriter protocol = new ProtocolWriter(client);
            field(client, "writer").set(client, new BufferedWriter(protocol));
            setReady(client, true);

            String hugeId = "i".repeat(4_097);
            assertThrows(IOException.class, () -> registerTurn(client, hugeId));
            assertThrows(IOException.class, () -> registerLogin(client, hugeId));
            assertThrows(IOException.class, () -> openThread(client, hugeId));
            notifyItem(client, hugeId, "ignored");
            notifyCompleted(client, hugeId);
            assertEquals(0, retainedSize(client, "earlyTurns"));

            for (int i = 0; i < 512; i++) openThread(client, "active-" + i);
            assertThrows(IOException.class, () -> openThread(client, "active-overflow"));
            IOException capFailure = assertThrows(IOException.class, () -> client.complete(
                    "gpt-test", "medium", "Translate only.", "Oak Chest"));
            assertTrue(capFailure.getMessage().contains("Too many active Codex threads"),
                    capFailure::getMessage);
            assertEquals(List.of("thread/start", "thread/unsubscribe"), protocol.methods,
                    "server-created ephemeral thread leaked when local cap rejected it");
            assertTrue(process.isAlive());
            client.close();
        }

        void readerServerRequestReplyDoesNotWaitForLifecycleMonitor() throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            Process generation = currentProcess(client);
            RecordingWriter sink = new RecordingWriter();
            BufferedWriter generationWriter = new BufferedWriter(sink);
            field(client, "writer").set(client, generationWriter);
            Object lifecycle = field(client, "lifecycleLock").get(client);
            AtomicReference<Throwable> replyFailure = new AtomicReference<>();

            synchronized (lifecycle) {
                Thread reply = new Thread(() -> {
                    try {
                        invoke(client, "rejectServerRequest",
                                new Class<?>[]{Process.class, BufferedWriter.class,
                                        JsonElement.class, String.class},
                                generation, generationWriter, new JsonParser().parse("7"), "tool/call");
                    } catch (Throwable failure) {
                        replyFailure.set(failure);
                    }
                }, "codex-server-request-reply");
                reply.start();
                reply.join(1_000L);
                assertTrue(!reply.isAlive(),
                        "reader response waited on lifecycleLock during initialization");
            }
            assertEquals(null, replyFailure.get());
            assertTrue(sink.payload.toString().contains("does not allow app-server request"));
            client.close();
        }

        void unknownLoginIdFailsImmediatelyAndOldCleanupCannotRemoveNewIdentity()
                throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            long started = System.nanoTime();
            IOException unknown = assertThrows(IOException.class,
                    () -> client.awaitLogin("unknown", Duration.ofSeconds(5)));
            assertTrue(unknown.getMessage().contains("Unknown or expired"), unknown::getMessage);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500L,
                    "unknown login id waited for a future generation");

            CompletableFuture<Boolean> old = registerLogin(client, "reused-login");
            invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                    "reused-login", old);
            CompletableFuture<Boolean> replacement = registerLogin(client, "reused-login");
            invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                    "reused-login", old);
            assertEquals(1, retainedSize(client, "loginResults"));
            invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                    "reused-login", replacement);
            client.close();
        }

        private static String stream(boolean stderr) {
            return stderr ? "stderr" : "stdout";
        }

        void closedThreadsAcceptLateUsageUntilEvictionAndReuseStartsANewBaseline()
                throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            SessionTokenUsage usage = new SessionTokenUsage();
            client.setTokenUsage(usage);

            openThread(client, "thread-one");
            notifyTokenUsage(client, "thread-one", 10, 2, 4, 1, 14);
            closeThread(client, "thread-one");
            notifyTokenUsage(client, "thread-one", 15, 3, 6, 2, 21);
            notifyTokenUsage(client, "never-seen", 100, 0, 100, 0, 200);

            assertEquals(15, usage.snapshot().inputTokens());
            assertEquals(6, usage.snapshot().outputTokens());
            assertEquals(21, usage.snapshot().totalTokens());
            assertEquals(1, usage.snapshot().requests());
            assertEquals(1, usage.activeCumulativeSources());

            // A reused server id represents a new thread, not another cumulative update
            // for the previous one.
            openThread(client, "thread-one");
            notifyTokenUsage(client, "thread-one", 3, 0, 1, 0, 4);
            closeThread(client, "thread-one");
            assertEquals(18, usage.snapshot().inputTokens());
            assertEquals(7, usage.snapshot().outputTokens());
            assertEquals(25, usage.snapshot().totalTokens());
            assertEquals(2, usage.snapshot().requests());

            // The oldest closed id is now evicted and its SessionTokenUsage baseline is
            // released. Notifications after that point are unknown and ignored.
            for (int i = 0; i < 512; i++) {
                String threadId = "closed-" + i;
                openThread(client, threadId);
                closeThread(client, threadId);
            }
            assertEquals(0, usage.activeCumulativeSources());
            notifyTokenUsage(client, "thread-one", 50, 0, 50, 0, 100);
            assertEquals(25, usage.snapshot().totalTokens());
            assertEquals(2, usage.snapshot().requests());

            client.close();
        }

        void processFailureClearsActiveTurnsAndReadyGuardRejectsLateRegistration()
                throws Exception {
            CodexAppServerClient client = client();
            setReady(client, true);
            CompletableFuture<JsonObject> active = registerTurn(client, "turn-active");
            openThread(client, "thread-active");

            invoke(client, "failAll", new Class<?>[]{IOException.class},
                    new IOException("test process failure"));

            assertTrue(active.isCompletedExceptionally());
            assertEquals(0, retainedSize(client, "turnResults"));
            assertEquals(0, retainedSize(client, "activeThreads"));
            assertEquals(0, retainedSize(client, "recentlyClosedThreads"));
            assertThrows(IOException.class, () -> registerTurn(client, "turn-too-late"));

            client.close();
        }

        void turnStartFailureAlwaysClosesKnownThreadAndUnsubscribes() throws Exception {
            CodexAppServerClient client = client();
            StubProcess process = new StubProcess();
            ProtocolWriter protocol = new ProtocolWriter(client);
            field(client, "process").set(client, process);
            field(client, "writer").set(client, new BufferedWriter(protocol));
            setReady(client, true);

            IOException failure = assertThrows(IOException.class, () -> client.complete(
                    "gpt-test", "medium", "Translate only.", "Oak Chest"));

            assertEquals("forced turn/start failure", failure.getMessage());
            assertEquals(List.of("thread/start", "turn/start", "thread/unsubscribe"),
                    protocol.methods);
            assertEquals(0, retainedSize(client, "activeThreads"));
            assertEquals(1, retainedSize(client, "recentlyClosedThreads"));
            assertEquals(0, retainedSize(client, "turnResults"));

            client.close();
        }

        private static CodexAppServerClient client() {
            return client(Duration.ofSeconds(10));
        }

        private static CodexAppServerClient client(Duration turnMessageGrace) {
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "nyanlex-codex-state-test");
            CodexAppServerClient client = new CodexAppServerClient(
                    root.resolve("home"), root.resolve("workspace"), turnMessageGrace);
            try {
                field(client, "process").set(client, new StubProcess());
                field(client, "writer").set(client, new BufferedWriter(Writer.nullWriter()));
            } catch (Exception failure) {
                throw new AssertionError("Unable to attach a test process generation", failure);
            }
            return client;
        }

        @SuppressWarnings("unchecked")
        private static CompletableFuture<JsonObject> registerTurn(
                CodexAppServerClient client, String turnId) throws Exception {
            return (CompletableFuture<JsonObject>) invoke(
                    client, "registerTurn", new Class<?>[]{String.class, Process.class},
                    turnId, currentProcess(client));
        }

        @SuppressWarnings("unchecked")
        private static CompletableFuture<Boolean> registerLogin(
                CodexAppServerClient client, String loginId) throws Exception {
            return (CompletableFuture<Boolean>) invoke(
                    client, "registerLogin", new Class<?>[]{String.class, Process.class},
                    loginId, currentProcess(client));
        }

        private static void registerPending(CodexAppServerClient client, String id,
                                            CompletableFuture<JsonElement> future) throws Exception {
            JsonObject request = new JsonObject();
            request.addProperty("method", "test/pending");
            request.addProperty("id", id);
            invoke(client, "registerAndSendRequest",
                    new Class<?>[]{Process.class, String.class, CompletableFuture.class,
                            JsonObject.class},
                    currentProcess(client), id, future, request);
        }

        private static String awaitTurnMessage(CodexAppServerClient client, String turnId)
                throws Exception {
            return (String) invoke(
                    client, "awaitTurnMessage", new Class<?>[]{String.class}, turnId);
        }

        private static void closeTurn(CodexAppServerClient client, String turnId) throws Exception {
            invoke(client, "closeTurn", new Class<?>[]{String.class, Process.class},
                    turnId, currentProcess(client));
        }

        private static void openThread(CodexAppServerClient client, String threadId) throws Exception {
            invoke(client, "openThread", new Class<?>[]{String.class, Process.class},
                    threadId, currentProcess(client));
        }

        private static void closeThread(CodexAppServerClient client, String threadId) throws Exception {
            invoke(client, "closeThread", new Class<?>[]{String.class, Process.class},
                    threadId, currentProcess(client));
        }

        private static Process currentProcess(CodexAppServerClient client) throws Exception {
            return (Process) field(client, "process").get(client);
        }

        private static void notifyItem(CodexAppServerClient client, String turnId, String text)
                throws Exception {
            JsonObject item = new JsonObject();
            item.addProperty("type", "agentMessage");
            item.addProperty("text", text);
            JsonObject params = new JsonObject();
            params.addProperty("turnId", turnId);
            params.add("item", item);
            notify(client, "item/completed", params);
        }

        private static void notifyCompleted(CodexAppServerClient client, String turnId)
                throws Exception {
            notifyCompletedStatus(client, turnId, "completed");
        }

        private static void notifyCompletedStatus(
                CodexAppServerClient client, String turnId, String status) throws Exception {
            JsonObject turn = new JsonObject();
            turn.addProperty("id", turnId);
            turn.addProperty("status", status);
            JsonObject params = new JsonObject();
            params.add("turn", turn);
            notify(client, "turn/completed", params);
        }

        private static void notifyLoginCompleted(
                CodexAppServerClient client, String loginId, boolean success) throws Exception {
            JsonObject params = new JsonObject();
            params.addProperty("loginId", loginId);
            params.addProperty("success", success);
            notify(client, "account/login/completed", params);
        }

        private static void notifyTokenUsage(CodexAppServerClient client, String threadId,
                                             long input, long cachedInput, long output,
                                             long reasoningOutput, long total) throws Exception {
            JsonObject totals = new JsonObject();
            totals.addProperty("inputTokens", input);
            totals.addProperty("cachedInputTokens", cachedInput);
            totals.addProperty("outputTokens", output);
            totals.addProperty("reasoningOutputTokens", reasoningOutput);
            totals.addProperty("totalTokens", total);
            JsonObject tokenUsage = new JsonObject();
            tokenUsage.add("total", totals);
            JsonObject params = new JsonObject();
            params.addProperty("threadId", threadId);
            params.add("tokenUsage", tokenUsage);
            notify(client, "thread/tokenUsage/updated", params);
        }

        private static void notify(CodexAppServerClient client, String method, JsonObject params)
                throws Exception {
            JsonObject notification = new JsonObject();
            notification.addProperty("method", method);
            notification.add("params", params);
            invoke(client, "handleLine", new Class<?>[]{String.class}, notification.toString());
        }

        private static int retainedSize(CodexAppServerClient client, String fieldName)
                throws Exception {
            Object retained = field(client, fieldName).get(client);
            if (retained instanceof Map<?, ?> map) return map.size();
            if (retained instanceof Collection<?> collection) return collection.size();
            throw new AssertionError(fieldName + " is not a retained-state container");
        }

        private static void setReady(CodexAppServerClient client, boolean ready) throws Exception {
            field(client, "ready").setBoolean(client, ready);
        }

        private static Field field(CodexAppServerClient client, String name) throws Exception {
            Field field = client.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static Object invoke(CodexAppServerClient client, String name,
                                     Class<?>[] parameterTypes, Object... arguments) throws Exception {
            Method method = client.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            try {
                return method.invoke(client, arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw e;
            }
        }

        private static JsonObject request(String id) {
            JsonObject request = new JsonObject();
            request.addProperty("method", "test/request");
            request.addProperty("id", id);
            return request;
        }

        private static class RecordingWriter extends Writer {
            final StringBuilder payload = new StringBuilder();

            @Override
            public void write(char[] chars, int offset, int length) {
                payload.append(chars, offset, length);
            }

            @Override
            public void flush() throws IOException {
            }

            @Override
            public void close() {
            }
        }

        private static final class BlockingWriter extends RecordingWriter {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);

            @Override
            public void flush() throws IOException {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting to release test writer");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test writer interrupted", e);
                }
            }
        }

        private static final class FailingWriter extends Writer {
            @Override
            public void write(char[] chars, int offset, int length) {
            }

            @Override
            public void flush() throws IOException {
                throw new IOException("forced writer failure");
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        }

        private static final class ProtocolWriter extends Writer {
            private final CodexAppServerClient client;
            private final StringBuilder buffer = new StringBuilder();
            private final List<String> methods = new ArrayList<>();

            private ProtocolWriter(CodexAppServerClient client) {
                this.client = client;
            }

            @Override
            public void write(char[] chars, int offset, int length) {
                buffer.append(chars, offset, length);
            }

            @Override
            public void flush() throws IOException {
                String payload = buffer.toString();
                buffer.setLength(0);
                for (String line : payload.lines().toList()) {
                    if (line.isBlank()) continue;
                    JsonObject request = new JsonParser().parse(line).getAsJsonObject();
                    String method = request.get("method").getAsString();
                    methods.add(method);
                    if ("thread/unsubscribe".equals(method)) continue;

                    JsonObject response = new JsonObject();
                    response.add("id", request.get("id"));
                    if ("thread/start".equals(method)) {
                        JsonObject thread = new JsonObject();
                        thread.addProperty("id", "thread-cleanup");
                        JsonObject result = new JsonObject();
                        result.add("thread", thread);
                        response.add("result", result);
                    } else if ("turn/start".equals(method)) {
                        JsonObject error = new JsonObject();
                        error.addProperty("message", "forced turn/start failure");
                        response.add("error", error);
                    } else {
                        response.add("result", new JsonObject());
                    }
                    try {
                        invoke(client, "handleLine", new Class<?>[]{String.class}, response.toString());
                    } catch (Exception e) {
                        throw new IOException("Unable to deliver fake app-server response", e);
                    }
                }
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        }

        private static final class StubProcess extends Process {
            private final InputStream input;
            private final InputStream error;
            private boolean alive = true;

            private StubProcess() {
                this(InputStream.nullInputStream(), InputStream.nullInputStream());
            }

            private StubProcess(InputStream input, InputStream error) {
                this.input = input;
                this.error = error;
            }

            @Override
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public InputStream getInputStream() {
                return input;
            }

            @Override
            public InputStream getErrorStream() {
                return error;
            }

            @Override
            public int waitFor() {
                alive = false;
                return 0;
            }

            @Override
            public int exitValue() {
                if (alive) throw new IllegalThreadStateException("stub process is alive");
                return 0;
            }

            @Override
            public void destroy() {
                alive = false;
            }

            @Override
            public boolean isAlive() {
                return alive;
            }
        }

    }

    private static final class CountingTranslator implements Translator {
        private int requests;
        private final List<List<String>> batches = new ArrayList<List<String>>();

        @Override
        public TranslationResult translate(String text, String targetLang) {
            requests++;
            batches.add(List.of(text));
            return new TranslationResult("T:" + text, "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
            requests++;
            batches.add(new ArrayList<String>(texts));
            List<TranslationResult> translated = new ArrayList<TranslationResult>();
            for (String text : texts) {
                translated.add(new TranslationResult("T:" + text, "en"));
            }
            return translated;
        }
    }

    private static final class Entry {
        private final String value;

        private Entry(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry && value.equals(((Entry) other).value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    private static final class SessionEntry {
        private final long id;
        private boolean displayed;
        private final List<String> richOriginalLines = new ArrayList<String>();

        private SessionEntry(long id, boolean displayed, String line) {
            this.id = id;
            this.displayed = displayed;
            richOriginalLines.add(line);
            if (id == 1L && !displayed) {
                richOriginalLines.clear();
                richOriginalLines.add("frame-open");
                richOriginalLines.add("frame-body");
                richOriginalLines.add("frame-close");
            }
        }
    }
}
