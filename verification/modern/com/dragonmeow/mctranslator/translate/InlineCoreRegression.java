package com.dragonmeow.mctranslator.translate;

import com.dragonmeow.mctranslator.cache.FileStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.service.ChatDeliveryQueue;
import com.dragonmeow.mctranslator.service.ChatDeliverySession;
import com.dragonmeow.mctranslator.service.ChatRequestProfile;
import com.dragonmeow.mctranslator.service.RecoveryAssembly;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path runtime = Paths.get(args[0]);
        Files.createDirectories(runtime);
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
        System.out.println("INLINE_CORE_OK hostile=24 codex=21 "
                + "coverage=recovery-assembly,result-progress,batch-budget,codex-state");
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
        TranslatorConfig config = new TranslatorConfig();
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

        TranslatorConfig config = new TranslatorConfig();
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
        TranslatorConfig changedTarget = new TranslatorConfig();
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
        config = new TranslatorConfig();
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
        TranslatorConfig machine = new TranslatorConfig();
        ChatRequestProfile machineBefore = profile(machine);
        machine.aiBaseUrl = "https://unused.invalid";
        machine.aiModel = "unused-api";
        machine.aiUseCodex = true;
        machine.codexModel = "unused-codex";
        machine.codexReasoningEffort = "high";
        machine.aiGlossary.add("unused=unused");
        check(machineBefore.equals(profile(machine)),
                "inactive AI settings changed a machine request profile");

        TranslatorConfig apiAi = new TranslatorConfig();
        apiAi.aiChat = true;
        ChatRequestProfile apiBefore = profile(apiAi);
        apiAi.codexModel = "inactive-codex";
        apiAi.codexReasoningEffort = "high";
        check(apiBefore.equals(profile(apiAi)),
                "inactive Codex settings changed an API-AI request profile");

        TranslatorConfig codexAi = new TranslatorConfig();
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

        TranslatorConfig target = new TranslatorConfig();
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
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "mctranslator-codex-state-test");
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
