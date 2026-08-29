package com.dragonmeow.mctranslator.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java-8 translation core used by MC 1.14-1.16. */
final class LegacyTranslator {
    private static final int MAX_BATCH_CHARS = 1400;
    private static final int BATCH_ITEM_OVERHEAD = 16;
    private static final int MAX_CACHE_ENTRIES = 8192;
    private static final int MAX_IN_FLIGHT_KEYS = 512;
    private static final int MAX_WAITERS_PER_KEY = 2048;
    private static final int MAX_TOTAL_WAITERS = 8192;
    private static final int MAX_EXECUTOR_TASKS = 128;
    private static final int MAX_FAILURE_BACKOFFS = 512;
    private static final int MAX_KEY_BACKOFFS = 256;
    private static final int MAX_SCHEDULED_RETRIES = 512;
    private static final int MAX_AUTO_RETRIES = 1;
    private static final int MAX_HIGH_BATCH_BURST = 3;
    private static final int MAX_AI_KEYS = 64;
    private static final int MAX_HTTP_RESPONSE_CHARS = 2_000_000;
    private static final Pattern FORMAT_TOKEN = Pattern.compile(
            "(?i)(?:\\u00a7[0-9A-FK-ORX]|%(?:\\d+\\$)?[A-Z%]|\\{\\d+\\}"
                    + "|\\u27E6\\s*MT\\s*\\d+\\s*\\u27E7)");
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile(
            "\\u27E6\\s*MT\\s*\\d+\\s*\\u27E7", Pattern.CASE_INSENSITIVE);

    /** Inline-test seam; production always uses the real provider branches below. */
    interface TestBackend {
        List<String> translate(List<String> canonicalSources) throws Exception;
        default List<String> translate(List<String> canonicalSources, LegacyConfig requestConfig)
                throws Exception {
            return translate(canonicalSources);
        }
    }

    /** Package-private HTTP seam used only by the exhaustive inline harness. */
    interface TestAiHttp {
        String request(String text, String target, LegacyConfig requestConfig, String apiKey)
                throws Exception;
    }

    static final class DebugEntry {
        final String engine, source, status;
        DebugEntry(String engine, String source, String status) {
            this.engine = engine; this.source = source; this.status = status;
        }
    }

    private static final class Pending {
        final String key, source, target, sourceLang, machineProvider, aiProfile;
        final boolean ai;
        final LegacyConfig config;
        final int requestGeneration;
        final int autoRetryAttempt;
        final long queuedAtMs = System.currentTimeMillis();
        final List<Waiter> waiters = new ArrayList<Waiter>();
        volatile boolean highPriority;
        volatile boolean cancelled;
        volatile PrioritizedTask queuedTask;
        boolean completed;

        Pending(String key, String source, String target, String sourceLang,
                String machineProvider, String aiProfile, boolean ai,
                boolean highPriority, LegacyConfig config, int requestGeneration,
                int autoRetryAttempt) {
            this.key = key;
            this.source = source;
            this.target = target;
            this.sourceLang = sourceLang;
            this.machineProvider = machineProvider;
            this.aiProfile = aiProfile;
            this.ai = ai;
            this.highPriority = highPriority;
            this.config = config;
            this.requestGeneration = requestGeneration;
            this.autoRetryAttempt = autoRetryAttempt;
        }
    }

    private static final class FailureBackoff {
        final long untilMs;
        final long sequence;
        FailureBackoff(long untilMs, long sequence) {
            this.untilMs = untilMs;
            this.sequence = sequence;
        }
    }

    private static final class Waiter {
        final LegacyTemplateText.Prepared prepared;
        final Consumer<String> callback;
        Waiter(LegacyTemplateText.Prepared prepared, Consumer<String> callback) {
            this.prepared = prepared;
            this.callback = callback;
        }
    }

    private static final class BatchWire {
        final String text;
        final int anchorBase;
        BatchWire(String text, int anchorBase) {
            this.text = text;
            this.anchorBase = anchorBase;
        }
    }

    /** Runnable envelope understood by the one shared dual-lane executor queue. */
    private static final class PrioritizedTask implements Runnable {
        volatile boolean high;
        final Runnable delegate;

        PrioritizedTask(boolean high, Runnable delegate) {
            this.high = high;
            this.delegate = delegate;
        }

        @Override public void run() { delegate.run(); }
    }

    /**
     * Bounded FIFO lanes shared by both workers. At most three queued high tasks
     * may pass an already-waiting low task, so either lane keeps making progress.
     */
    static final class FairTaskQueue extends AbstractQueue<Runnable>
            implements BlockingQueue<Runnable> {
        private final int capacity;
        private final ArrayDeque<Runnable> high = new ArrayDeque<Runnable>();
        private final ArrayDeque<Runnable> low = new ArrayDeque<Runnable>();
        private int consecutiveHigh;

        FairTaskQueue(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity");
            this.capacity = capacity;
        }

        @Override public synchronized boolean offer(Runnable task) {
            if (task == null) throw new NullPointerException("task");
            if (sizeLocked() >= capacity) return false;
            lane(task).addLast(task);
            notifyAll();
            return true;
        }

        @Override public synchronized void put(Runnable task) throws InterruptedException {
            if (task == null) throw new NullPointerException("task");
            while (sizeLocked() >= capacity) wait();
            lane(task).addLast(task);
            notifyAll();
        }

        @Override public synchronized boolean offer(Runnable task, long timeout, TimeUnit unit)
                throws InterruptedException {
            if (task == null) throw new NullPointerException("task");
            long remaining = unit.toNanos(timeout);
            long deadline = System.nanoTime() + remaining;
            while (sizeLocked() >= capacity) {
                if (remaining <= 0L) return false;
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                wait(millis, nanos);
                remaining = deadline - System.nanoTime();
            }
            lane(task).addLast(task);
            notifyAll();
            return true;
        }

        @Override public synchronized Runnable poll() {
            Runnable task = pollLocked();
            if (task != null) notifyAll();
            return task;
        }

        @Override public synchronized Runnable take() throws InterruptedException {
            while (sizeLocked() == 0) wait();
            Runnable task = pollLocked();
            notifyAll();
            return task;
        }

        @Override public synchronized Runnable poll(long timeout, TimeUnit unit)
                throws InterruptedException {
            long remaining = unit.toNanos(timeout);
            long deadline = System.nanoTime() + remaining;
            while (sizeLocked() == 0) {
                if (remaining <= 0L) return null;
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                wait(millis, nanos);
                remaining = deadline - System.nanoTime();
            }
            Runnable task = pollLocked();
            notifyAll();
            return task;
        }

        @Override public synchronized Runnable peek() {
            if (high.isEmpty()) return low.peekFirst();
            if (low.isEmpty()) return high.peekFirst();
            return consecutiveHigh >= MAX_HIGH_BATCH_BURST
                    ? low.peekFirst() : high.peekFirst();
        }

        @Override public synchronized int size() { return sizeLocked(); }

        @Override public synchronized int remainingCapacity() {
            return capacity - sizeLocked();
        }

        @Override public synchronized int drainTo(Collection<? super Runnable> target) {
            return drainTo(target, Integer.MAX_VALUE);
        }

        @Override public synchronized int drainTo(Collection<? super Runnable> target, int maximum) {
            if (target == null) throw new NullPointerException("target");
            if (target == this) throw new IllegalArgumentException("target");
            int moved = 0;
            while (moved < maximum) {
                Runnable task = pollLocked();
                if (task == null) break;
                target.add(task);
                moved++;
            }
            if (moved > 0) notifyAll();
            return moved;
        }

        @Override public synchronized boolean remove(Object task) {
            boolean removed = high.remove(task) || low.remove(task);
            if (removed) notifyAll();
            return removed;
        }

        synchronized boolean promote(Runnable task) {
            if (!(task instanceof PrioritizedTask)) return false;
            PrioritizedTask prioritized = (PrioritizedTask) task;
            if (prioritized.high) return high.contains(task);
            if (!low.remove(task)) return false;
            prioritized.high = true;
            high.addLast(task);
            notifyAll();
            return true;
        }

        @Override public synchronized Iterator<Runnable> iterator() {
            List<Runnable> snapshot = new ArrayList<Runnable>(sizeLocked());
            snapshot.addAll(high);
            snapshot.addAll(low);
            return Collections.unmodifiableList(snapshot).iterator();
        }

        private ArrayDeque<Runnable> lane(Runnable task) {
            return task instanceof PrioritizedTask && ((PrioritizedTask) task).high ? high : low;
        }

        private int sizeLocked() { return high.size() + low.size(); }

        private Runnable pollLocked() {
            if (high.isEmpty()) {
                if (low.isEmpty()) return null;
                consecutiveHigh = 0;
                return low.removeFirst();
            }
            if (low.isEmpty()) {
                consecutiveHigh = Math.min(MAX_HIGH_BATCH_BURST, consecutiveHigh + 1);
                return high.removeFirst();
            }
            if (consecutiveHigh >= MAX_HIGH_BATCH_BURST) {
                consecutiveHigh = 0;
                return low.removeFirst();
            }
            consecutiveHigh++;
            return high.removeFirst();
        }
    }

    private final AtomicInteger threadSequence = new AtomicInteger();
    private final ThreadPoolExecutor executor = createExecutor();
    private final ScheduledThreadPoolExecutor retryScheduler = createRetryScheduler();
    private final AtomicInteger scheduledRetries = new AtomicInteger();
    private final AtomicInteger requestGeneration = new AtomicInteger();
    private final AtomicLong failureSequence = new AtomicLong();
    private final Map<String, String> cache = boundedMap(MAX_CACHE_ENTRIES);
    private final Object flightLock = new Object();
    private final Map<String, Pending> inFlight = new LinkedHashMap<String, Pending>();
    private int totalWaiters;
    private final Map<String, FailureBackoff> failedUntil = boundedMap(MAX_FAILURE_BACKOFFS);
    private final Map<String, Long> keyUnavailableUntil = boundedMap(MAX_KEY_BACKOFFS);
    private final LegacyMachineProvider experimentalProviders = new LegacyMachineProvider();
    private final AtomicInteger keyCursor = new AtomicInteger();
    private final List<DebugEntry> debug = Collections.synchronizedList(new ArrayList<DebugEntry>());
    private final Object dispatchLock = new Object();
    private final Object batchLock = new Object();
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<String, Pending>();
    private final Object paceLock = new Object();
    private long lastGtRequest, lastAiRequest;
    private int consecutiveHighBatches;
    private final LegacySessionTokenUsage tokenUsage = new LegacySessionTokenUsage();
    private final TestBackend testBackend;
    private volatile TestAiHttp testAiHttp;
    private volatile LegacyCodexClient codexClient;
    private volatile Runnable cancelDetachedHookForTests;

    LegacyTranslator() { this(null); }

    LegacyTranslator(TestBackend testBackend) {
        this.testBackend = testBackend;
        executor.prestartAllCoreThreads();
    }

    void setAiHttpForTests(TestAiHttp hook) { testAiHttp = hook; }

    void setCancelDetachedHookForTests(Runnable hook) { cancelDetachedHookForTests = hook; }

    void setCodexClient(LegacyCodexClient client) {
        this.codexClient = client;
        if (client != null) client.setTokenUsage(tokenUsage);
    }

    LegacyCodexClient codexClient() { return codexClient; }
    LegacySessionTokenUsage.Snapshot tokenUsageSnapshot() { return tokenUsage.snapshot(); }

    void testAi(final String target, final LegacyConfig config, final Consumer<String> callback) {
        try {
            executor.execute(new PrioritizedTask(true, new Runnable() {
                @Override public void run() {
                    try {
                        String translated = requestAi("Purple Stool", target, config);
                        callback.accept(translated == null || translated.trim().isEmpty()
                                ? "empty response" : "OK: " + translated.trim());
                    } catch (Exception e) {
                        callback.accept("Failed: " + failureReason(e));
                    }
                }
            }));
        } catch (RejectedExecutionException full) {
            callback.accept("Failed: executor queue full");
        }
    }

    /** Numeric transport for MT slots used only around machine-provider HTTP calls. */
    private static final class TokenBatch {
        final List<String> texts;
        final List<String> tokens;
        final int sentinelBase;

        TokenBatch(List<String> texts, List<String> tokens, int sentinelBase) {
            this.texts = texts;
            this.tokens = tokens;
            this.sentinelBase = sentinelBase;
        }

        String decode(String translated) {
            return decodeItems(Collections.singletonList(translated)).get(0);
        }

        List<String> sentinels() {
            List<String> values = new ArrayList<String>(tokens.size());
            for (int i = 0; i < tokens.size(); i++) {
                values.add(Integer.toString(sentinelBase + i));
            }
            return values;
        }

        List<String> decodeItems(List<String> translatedItems) {
            if (translatedItems == null) throw new IllegalStateException("empty response");
            if (tokens.isEmpty()) return new ArrayList<String>(translatedItems);
            List<String> markers = sentinels();
            Map<String, String> replacements = new LinkedHashMap<String, String>();
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < markers.size(); i++) {
                replacements.put(markers.get(i), tokens.get(i));
                counts.put(markers.get(i), Integer.valueOf(0));
            }
            List<ParsedNumeric> parsed = new ArrayList<ParsedNumeric>(translatedItems.size());
            int expectedMarker = 0;
            for (String translated : translatedItems) {
                if (translated == null || translated.trim().isEmpty()) {
                    throw new IllegalStateException("empty response");
                }
                ParsedNumeric item = parseNumericMarkers(translated, markers);
                parsed.add(item);
                for (NumericOccurrence occurrence : item.occurrences) {
                    if (expectedMarker >= markers.size()
                            || !markers.get(expectedMarker).equals(occurrence.marker)) {
                        throw new IllegalStateException("format/token lost");
                    }
                    expectedMarker++;
                    counts.put(occurrence.marker,
                            Integer.valueOf(counts.get(occurrence.marker).intValue() + 1));
                }
            }
            for (String marker : markers) {
                if (counts.get(marker).intValue() != 1) {
                    throw new IllegalStateException("format/token lost");
                }
            }
            if (expectedMarker != markers.size()) {
                throw new IllegalStateException("format/token lost");
            }
            List<String> decoded = new ArrayList<String>(parsed.size());
            for (ParsedNumeric item : parsed) {
                decoded.add(replaceNumericOccurrences(item, replacements));
            }
            return decoded;
        }
    }

    private static final class NumericOccurrence {
        final int start, end;
        final String marker;
        NumericOccurrence(int start, int end, String marker) {
            this.start = start;
            this.end = end;
            this.marker = marker;
        }
    }

    private static final class ParsedNumeric {
        final String text;
        final List<NumericOccurrence> occurrences;
        ParsedNumeric(String text, List<NumericOccurrence> occurrences) {
            this.text = text;
            this.occurrences = occurrences;
        }
    }

    private static final class Segmentation {
        final int ways;
        final List<String> markers;
        Segmentation(int ways, List<String> markers) {
            this.ways = ways;
            this.markers = markers;
        }
    }

    String cached(String source, String target, boolean ai) {
        LegacyTemplateText.Prepared prepared = LegacyTemplateText.prepare(source);
        if (!prepared.hasTranslatableContent()) return source;
        String hit = cache.get(cacheKey(prepared.text(), target, ai, "google", null));
        return hit == null ? null : prepared.restore(hit);
    }

    String cached(String source, String target, boolean ai, LegacyConfig config) {
        if (source == null || config == null) return null;
        LegacyTemplateText.Prepared prepared = LegacyTemplateText.prepare(source);
        if (!prepared.hasTranslatableContent()) return source;
        String provider = LegacyConfig.normalizeMachineProvider(
                config.machineTranslationProvider);
        String hit = cache.get(cacheKey(prepared.text(), target, ai, provider, config));
        return hit == null ? null : prepared.restore(hit);
    }

    void translate(final String source, final String target, final boolean ai, final boolean highPriority,
                   final LegacyConfig config, final Consumer<String> callback) {
        submit(source, target, ai, highPriority, config, callback);
    }

    /** Cache-only request: duplicates promote priority but never accumulate subscribers. */
    void prefetch(final String source, final String target, final boolean ai,
                  final boolean highPriority, final LegacyConfig config) {
        submit(source, target, ai, highPriority, config, null);
    }

    private void submit(final String source, final String target, final boolean ai,
                        final boolean highPriority, final LegacyConfig config,
                        final Consumer<String> callback) {
        if (source == null || target == null || config == null || !config.enabled) {
            accept(callback, null);
            return;
        }
        int generation = requestGeneration.get();
        LegacyConfig snapshot = config.snapshotForRequest();
        if (!config.enabled || generation != requestGeneration.get()) {
            accept(callback, null);
            return;
        }
        submitSnapshot(source, target, ai, highPriority, snapshot, config, callback,
                generation, 0);
    }

    private void submitSnapshot(final String source, final String target, final boolean ai,
                                final boolean highPriority, final LegacyConfig config,
                                final LegacyConfig liveConfig, final Consumer<String> callback,
                                final int generation, final int autoRetryAttempt) {
        if (generation != requestGeneration.get()) {
            accept(callback, null);
            return;
        }
        final LegacyTemplateText.Prepared prepared = LegacyTemplateText.prepare(source);
        if (!prepared.hasTranslatableContent()) {
            accept(callback, source);
            return;
        }
        final String provider = LegacyConfig.normalizeMachineProvider(
                config.machineTranslationProvider);
        final String aiProfile = aiProfile(config);
        final String key = cacheKey(prepared.text(), target, ai, provider, config);
        String hit = cache.get(key);
        if (hit != null) {
            accept(callback, prepared.restore(hit));
            return;
        }
        FailureBackoff blocked = failedUntil.get(key);
        if (blocked != null) {
            if (blocked.untilMs > System.currentTimeMillis()) {
                accept(callback, null);
                return;
            }
            removeFailureIfCurrent(key, blocked);
        }

        Pending created = null;
        Pending joined = null;
        boolean waiterRejected = false;
        boolean cancelledBeforeFlight = false;
        synchronized (dispatchLock) {
            if (generation != requestGeneration.get()
                    || liveConfig != null && !liveConfig.enabled) {
                cancelledBeforeFlight = true;
            } else {
                synchronized (flightLock) {
                    hit = cache.get(key);
                    if (hit == null) {
                        Pending existing = inFlight.get(key);
                        if (existing != null) {
                            joined = existing;
                            if (callback != null) {
                                if (existing.waiters.size() >= MAX_WAITERS_PER_KEY
                                        || totalWaiters >= MAX_TOTAL_WAITERS) {
                                    waiterRejected = true;
                                } else {
                                    existing.waiters.add(new Waiter(prepared, callback));
                                    totalWaiters++;
                                }
                            }
                        } else if (inFlight.size() >= MAX_IN_FLIGHT_KEYS) {
                            waiterRejected = callback != null;
                        } else {
                            created = new Pending(key, prepared.text(), target, config.sourceLang,
                                    provider, aiProfile, ai, highPriority, config, generation,
                                    autoRetryAttempt);
                            if (callback != null) {
                                created.waiters.add(new Waiter(prepared, callback));
                                totalWaiters++;
                            }
                            inFlight.put(key, created);
                        }
                    }
                }
            }
        }
        if (cancelledBeforeFlight) {
            accept(callback, null);
            return;
        }
        if (joined != null) {
            if (highPriority) promote(joined);
            if (waiterRejected) accept(callback, null);
            return;
        }
        if (hit != null) {
            accept(callback, prepared.restore(hit));
            return;
        }
        if (created == null) {
            if (waiterRejected) accept(callback, null);
            return;
        }
        boolean cancelled = false;
        synchronized (dispatchLock) {
            if (generation != requestGeneration.get()
                    || liveConfig != null && !liveConfig.enabled
                    || created.cancelled || created.completed) {
                cancelled = true;
            } else {
                synchronized (batchLock) {
                    pending.put(key, created);
                }
            }
        }
        if (cancelled) cancelItem(created);
    }

    /** Called once at the end of each client tick. Zero window therefore means next tick. */
    void flushBatch() {
        synchronized (dispatchLock) {
            final List<Pending> batch = new ArrayList<Pending>();
            final boolean high;
            synchronized (batchLock) {
                if (pending.isEmpty()) return;
                long now = System.currentTimeMillis();
                Pending firstHigh = null;
                Pending eligibleLow = null;
                for (Pending item : pending.values()) {
                    if (item.highPriority) {
                        if (firstHigh == null) firstHigh = item;
                    } else if (eligibleLow == null && lowBatchEligible(item, now)) {
                        /*
                         * A long-window group at the head must not hide a later zero-window
                         * or already-full group.  We still preserve receive order within the
                         * selected compatible group.
                         */
                        eligibleLow = item;
                    }
                }
                high = firstHigh != null && (eligibleLow == null
                        || consecutiveHighBatches < MAX_HIGH_BATCH_BURST);
                Pending seed = high ? firstHigh : eligibleLow;
                if (seed == null) return;

                int chars = 0;
                java.util.Iterator<Map.Entry<String, Pending>> iterator = pending.entrySet().iterator();
                while (iterator.hasNext()) {
                    Pending item = iterator.next().getValue();
                    if (item.highPriority != high || !sameBatch(seed, item)) continue;
                    int next = batchChars(item.source);
                    if (!batch.isEmpty() && chars + next > MAX_BATCH_CHARS) break;
                    batch.add(item);
                    chars += next;
                    iterator.remove();
                    if (chars >= MAX_BATCH_CHARS) break;
                }
                if (high) consecutiveHighBatches++;
                else consecutiveHighBatches = 0;
            }
            if (batch.isEmpty()) return;
            final PrioritizedTask task = new PrioritizedTask(high, new Runnable() {
                @Override public void run() {
                    for (Pending item : batch) item.queuedTask = null;
                    processBatch(batch);
                }
            });
            for (Pending item : batch) item.queuedTask = task;
            try {
                executor.execute(task);
            } catch (RejectedExecutionException full) {
                for (Pending item : batch) {
                    item.queuedTask = null;
                    completeFailure(item, "executor queue full", false);
                }
            }
        }
    }

    private boolean lowBatchEligible(Pending seed, long now) {
        int totalChars = 0;
        for (Pending item : pending.values()) {
            if (!item.highPriority && sameBatch(seed, item)) totalChars += batchChars(item.source);
        }
        return totalChars >= MAX_BATCH_CHARS
                || Math.max(0, seed.config.batchWindowMs) == 0
                || now - seed.queuedAtMs >= Math.max(0, seed.config.batchWindowMs);
    }

    private void promote(Pending item) {
        synchronized (dispatchLock) {
            item.highPriority = true;
            PrioritizedTask task = item.queuedTask;
            if (task != null) {
                ((FairTaskQueue) executor.getQueue()).promote(task);
            }
        }
    }

    /** Cancels every collected/dispatched flight without retaining subscribers. */
    void cancelPending() {
        List<Waiter> cancelledWaiters = new ArrayList<Waiter>();
        synchronized (dispatchLock) {
            requestGeneration.incrementAndGet();
            synchronized (batchLock) { pending.clear(); }
            synchronized (flightLock) {
                java.util.Iterator<Map.Entry<String, Pending>> iterator =
                        inFlight.entrySet().iterator();
                while (iterator.hasNext()) {
                    Pending item = iterator.next().getValue();
                    if (!item.completed) {
                        item.cancelled = true;
                        item.completed = true;
                        cancelledWaiters.addAll(item.waiters);
                        totalWaiters -= item.waiters.size();
                        item.waiters.clear();
                    }
                    iterator.remove();
                }
                if (totalWaiters < 0) totalWaiters = 0;
            }
        }
        Runnable hook = cancelDetachedHookForTests;
        if (hook != null) hook.run();
        for (Waiter waiter : cancelledWaiters) accept(waiter.callback, null);
    }

    private void processBatch(List<Pending> batch) {
        boolean anyActive = false;
        for (Pending item : batch) if (!item.cancelled && !item.completed) {
            anyActive = true;
            break;
        }
        if (!anyActive) return;
        Pending first = batch.get(0);
        String engine = first.ai ? "AI" : "GT";
        for (Pending item : batch) log(item.config, engine, item.source, "...");
        List<String> translated;
        try {
            if (testBackend != null) {
                List<String> canonicalSources = new ArrayList<String>(batch.size());
                for (Pending item : batch) canonicalSources.add(item.source);
                translated = testBackend.translate(Collections.unmodifiableList(canonicalSources),
                        first.config);
                engine = "TEST";
            } else if (first.ai) {
                try {
                    translated = requestAiBatch(batch, first.target, first.config);
                } catch (Exception aiFailure) {
                    if (first.config.disableGoogleFallbackForAi) throw aiFailure;
                    engine = "GT";
                    translated = requestMachineBatch(batch, first.sourceLang, first.target,
                            first.machineProvider, first.config.requestCooldownMs);
                }
            } else {
                translated = requestMachineBatch(batch, first.sourceLang, first.target,
                        first.machineProvider, first.config.requestCooldownMs);
            }
        } catch (Exception failure) {
            for (Pending item : batch) fail(item, engine, failure);
            return;
        }
        if (translated == null || translated.size() != batch.size()) {
            for (Pending item : batch) fail(item, engine, "paragraph lost");
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            Pending item = batch.get(i);
            try {
                String result = translated.get(i);
                String validationFailure = validationFailureFor(item.source, result);
                if (validationFailure != null || result.trim().equals(item.source.trim())) {
                    fail(item, engine, validationFailure == null ? "unknown" : validationFailure);
                    continue;
                }
                if (completeSuccess(item, result)) {
                    log(item.config, engine, item.source, "OK");
                }
            } catch (RuntimeException malformed) {
                fail(item, engine, malformed);
            }
        }
    }

    private void fail(final Pending item, String engine, Throwable failure) {
        fail(item, engine, failureReason(failure));
    }

    private void fail(final Pending item, String engine, String reason) {
        String normalized = normalizeFailureReason(reason);
        boolean retry = item.ai && item.config.disableGoogleFallbackForAi
                && item.autoRetryAttempt < MAX_AUTO_RETRIES
                && isTransientFailure(normalized);
        if (completeFailure(item, reason, retry)) {
            log(item.config, engine, item.source,
                    "failed (" + normalized + ")");
        }
    }

    private boolean completeFailure(final Pending item, String reason, boolean retry) {
        final long retryDelay = Math.max(250L, item.config.failureBackoffMs);
        final FailureBackoff backoff = new FailureBackoff(
                System.currentTimeMillis() + retryDelay, failureSequence.incrementAndGet());
        if (!notifyWaiters(item, null, backoff)) return false;
        /* Subscribers fail promptly; the single transient retry is cache-warm only. */
        if (retry && scheduledRetries.incrementAndGet() <= MAX_SCHEDULED_RETRIES) {
            try {
                retryScheduler.schedule(new Runnable() {
                    @Override public void run() {
                        scheduledRetries.decrementAndGet();
                        if (item.requestGeneration != requestGeneration.get()) return;
                        if (!removeFailureIfCurrent(item.key, backoff)) return;
                        submitSnapshot(item.source, item.target, true, item.highPriority,
                                item.config, null, null, item.requestGeneration,
                                item.autoRetryAttempt + 1);
                    }
                }, retryDelay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                scheduledRetries.decrementAndGet();
            }
        } else if (retry) {
            scheduledRetries.decrementAndGet();
        }
        return true;
    }

    private boolean completeSuccess(Pending item, String translatedTemplate) {
        return notifyWaiters(item, translatedTemplate, null);
    }

    private void cancelItem(Pending item) {
        List<Waiter> waiters;
        synchronized (flightLock) {
            if (item.completed) return;
            item.cancelled = true;
            item.completed = true;
            if (inFlight.get(item.key) == item) inFlight.remove(item.key);
            waiters = new ArrayList<Waiter>(item.waiters);
            item.waiters.clear();
            totalWaiters -= waiters.size();
            if (totalWaiters < 0) totalWaiters = 0;
        }
        for (Waiter waiter : waiters) accept(waiter.callback, null);
    }

    private boolean notifyWaiters(Pending item, String translatedTemplate,
                                  FailureBackoff failureBackoff) {
        List<Waiter> waiters;
        synchronized (flightLock) {
            if (item.completed || translatedTemplate != null && item.cancelled) return false;
            item.completed = true;
            if (inFlight.get(item.key) == item) inFlight.remove(item.key);
            if (translatedTemplate != null) {
                cache.put(item.key, translatedTemplate);
                removeFailure(item.key);
            } else if (failureBackoff != null) {
                failedUntil.put(item.key, failureBackoff);
            }
            waiters = new ArrayList<Waiter>(item.waiters);
            item.waiters.clear();
            totalWaiters -= waiters.size();
            if (totalWaiters < 0) totalWaiters = 0;
        }
        for (Waiter waiter : waiters) {
            String value = translatedTemplate == null
                    ? null : waiter.prepared.restore(translatedTemplate);
            accept(waiter.callback, value);
        }
        return true;
    }

    private static boolean sameBatch(Pending first, Pending other) {
        if (first.ai != other.ai || !first.target.equals(other.target)
                || first.config.requestCooldownMs != other.config.requestCooldownMs
                || first.config.batchWindowMs != other.config.batchWindowMs) return false;
        if (!first.ai) {
            return safe(first.sourceLang).equals(safe(other.sourceLang))
                    && first.machineProvider.equals(other.machineProvider);
        }
        if (!first.aiProfile.equals(other.aiProfile)) return false;
        if (first.config.aiUseCodex) return true;
        return first.config.aiApiKeys.equals(other.config.aiApiKeys)
                && first.config.aiKeysByEndpoint.equals(other.config.aiKeysByEndpoint);
    }

    private static int batchChars(String source) {
        return (source == null ? 0 : source.length()) + BATCH_ITEM_OVERHEAD;
    }

    List<DebugEntry> debugSnapshot() {
        synchronized (debug) { return new ArrayList<DebugEntry>(debug); }
    }

    void clearDebug() { debug.clear(); }

    private void log(LegacyConfig config, String engine, String source, String status) {
        if (!config.debugTranslationOverlay) return;
        synchronized (debug) {
            debug.add(new DebugEntry(engine, compact(source), status));
            while (debug.size() > 24) debug.remove(0);
        }
    }

    private List<String> requestAiBatch(List<Pending> batch, String target,
                                        LegacyConfig config) throws Exception {
        TokenBatch tokens = encodeTemplateTokens(canonicalSources(batch));
        if (batch.size() == 1) {
            return Collections.singletonList(tokens.decode(
                    requestAi(tokens.texts.get(0), target, config)));
        }
        BatchWire wire = buildBatchWire(tokens.texts);
        String translated = requestAi(wire.text, target, config);
        return tokens.decodeItems(splitBatchEncoded(
                translated, batch.size(), wire.anchorBase, tokens.sentinels()));
    }

    private List<String> requestGoogleBatch(List<Pending> batch, String sourceLang,
                                            String target, int cooldown) throws Exception {
        TokenBatch tokens = encodeTemplateTokens(canonicalSources(batch));
        if (batch.size() == 1) {
            String translated = requestGoogle(tokens.texts.get(0), sourceLang, target, cooldown);
            return Collections.singletonList(tokens.decode(translated));
        }
        BatchWire wire = buildBatchWire(tokens.texts);
        String translated = requestGoogle(wire.text, sourceLang, target, cooldown);
        return tokens.decodeItems(splitBatchEncoded(
                translated, batch.size(), wire.anchorBase, tokens.sentinels()));
    }

    private List<String> requestMachineBatch(List<Pending> batch, String sourceLang,
                                             String target, String provider,
                                             int cooldown) throws Exception {
        String selected = LegacyConfig.normalizeMachineProvider(provider);
        if ("google".equals(selected)) {
            // Keep the historical Google path byte-for-byte equivalent.
            return requestGoogleBatch(batch, sourceLang, target, cooldown);
        }
        // Experimental sources always carry anchors, including a one-item batch. A
        // malformed/error-shaped response therefore cannot be accepted as cache data.
        TokenBatch tokens = encodeTemplateTokens(canonicalSources(batch));
        BatchWire wire = buildBatchWire(tokens.texts);
        pace(false, cooldown);
        String translated = experimentalProviders.translate(
                selected, wire.text, sourceLang, target);
        return tokens.decodeItems(splitBatchEncoded(
                translated, batch.size(), wire.anchorBase, tokens.sentinels()));
    }

    private static List<String> canonicalSources(List<Pending> batch) {
        List<String> sources = new ArrayList<String>(batch.size());
        for (Pending item : batch) sources.add(item.source);
        return sources;
    }

    private static TokenBatch encodeTemplateTokens(List<String> sources) {
        List<String> tokens = new ArrayList<String>();
        for (String source : sources) {
            Matcher matcher = TEMPLATE_TOKEN.matcher(source == null ? "" : source);
            while (matcher.find()) tokens.add(matcher.group());
        }
        if (tokens.isEmpty()) {
            return new TokenBatch(new ArrayList<String>(sources), tokens, 0);
        }
        int base = 30001;
        outer:
        while (true) {
            for (String source : sources) {
                for (int i = 0; i < tokens.size(); i++) {
                    if (source.contains(Integer.toString(base + i))) {
                        base += 2000;
                        continue outer;
                    }
                }
            }
            break;
        }
        List<String> encoded = new ArrayList<String>(sources.size());
        int tokenIndex = 0;
        for (String source : sources) {
            Matcher matcher = TEMPLATE_TOKEN.matcher(source);
            StringBuilder text = new StringBuilder(source.length());
            int pos = 0;
            while (matcher.find()) {
                text.append(source, pos, matcher.start());
                text.append(base + tokenIndex++);
                pos = matcher.end();
            }
            text.append(source, pos, source.length());
            encoded.add(text.toString());
        }
        return new TokenBatch(encoded, tokens, base);
    }

    private static BatchWire buildBatchWire(List<String> sources) {
        int anchorCount = sources.size() * 2;
        int base = 70001;
        outer:
        while (true) {
            for (String source : sources) {
                for (int i = 0; i < anchorCount; i++) {
                    if (source.contains(Integer.toString(base + i))) {
                        base += 2000;
                        continue outer;
                    }
                }
            }
            break;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) joined.append('\n');
            joined.append(base + i * 2).append(sources.get(i)).append(base + i * 2 + 1);
        }
        return new BatchWire(joined.toString(), base);
    }

    private static String validationFailureFor(String source, String translated) {
        if (translated == null || translated.trim().isEmpty()) return "empty response";
        if (lineBreakCount(translated) < lineBreakCount(source)) return "paragraph lost";
        if (!formatTokens(source).equals(formatTokens(translated))) return "format/token lost";
        return null;
    }

    private static int lineBreakCount(String value) {
        if (value == null || value.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\r') {
                count++;
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
            } else if (current == '\n') {
                count++;
            }
        }
        return count;
    }

    private static List<String> formatTokens(String value) {
        List<String> tokens = new ArrayList<String>();
        Matcher matcher = FORMAT_TOKEN.matcher(value == null ? "" : value);
        while (matcher.find()) tokens.add(matcher.group().toLowerCase(java.util.Locale.ROOT));
        Collections.sort(tokens);
        return tokens;
    }

    private static String failureReason(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        boolean serverError = false;
        boolean authentication = false;
        boolean network = false;
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 32; depth++) {
            if (cursor instanceof HttpStatusException) {
                int code = ((HttpStatusException) cursor).code;
                if (code == 429) return "429 rate limit";
                if (code >= 500 && code <= 599) serverError = true;
                if (code == 401 || code == 403) authentication = true;
            }
            if (isNetworkFailure(cursor)) network = true;
            String message = cursor.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                if (messages.length() > 0) messages.append(" | ");
                messages.append(message);
            }
            Throwable cause = cursor.getCause();
            if (cause == cursor) break;
            cursor = cause;
        }
        String combined = messages.toString().toLowerCase(java.util.Locale.ROOT);
        if (containsAny(combined, "http 429", "rate limit", "rate_limit",
                "rate-limit", "too many requests")) return "429 rate limit";
        if (serverError || combined.matches("(?s).*\\bhttp\\s*5\\d\\d\\b.*")) return "HTTP 5xx";
        if (authentication || containsAny(combined, "http 401", "http 403", "authentication",
                "authorization", "unauthorized", "unauthorised", "forbidden",
                "invalid api key", "invalid key")) return "authentication";
        if (network || containsAny(combined, "timed out", "timeout", "network", "connection",
                "connect reset", "connect refused", "connect failed", "unknown host",
                "dns", "no route", "socket")) return "timeout/network";
        if (containsAny(combined, "anchor", "order")
                && containsAny(combined, "damage", "damaged", "missing", "invalid",
                "mismatch", "reorder", "out-of-order", "unexpected")) return "anchor/order damaged";
        if (containsAny(combined, "paragraph", "hard line", "hard_line", "line break", "line-break")
                && containsAny(combined, "lost", "missing", "damage", "damaged",
                "mismatch", "invalid")) return "paragraph lost";
        if (containsAny(combined, "format", "token", "marker", "placeholder", "sentinel")
                && containsAny(combined, "lost", "missing", "damage", "damaged",
                "mismatch", "invalid")) return "format/token lost";
        if (containsAny(combined, "empty response", "empty body", "empty translation", "empty result",
                "blank response", "blank body", "blank translation", "blank result",
                "no choice", "no content", "no translation", "no result")) return "empty response";
        return "unknown";
    }

    private static boolean isNetworkFailure(Throwable failure) {
        return failure instanceof java.net.SocketTimeoutException
                || failure instanceof java.net.ConnectException
                || failure instanceof java.net.UnknownHostException
                || failure instanceof java.net.NoRouteToHostException
                || failure instanceof java.net.SocketException
                || failure instanceof java.io.InterruptedIOException
                || failure instanceof java.util.concurrent.TimeoutException;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String normalizeFailureReason(String reason) {
        if ("429 rate limit".equals(reason) || "HTTP 5xx".equals(reason)
                || "authentication".equals(reason) || "timeout/network".equals(reason)
                || "anchor/order damaged".equals(reason) || "paragraph lost".equals(reason)
                || "format/token lost".equals(reason) || "empty response".equals(reason)) {
            return reason;
        }
        return "unknown";
    }

    private static boolean isTransientFailure(String reason) {
        return "429 rate limit".equals(reason) || "HTTP 5xx".equals(reason)
                || "timeout/network".equals(reason);
    }

    private boolean removeFailureIfCurrent(String key, FailureBackoff expected) {
        synchronized (failedUntil) {
            if (failedUntil.get(key) != expected) return false;
            failedUntil.remove(key);
            return true;
        }
    }

    private void removeFailure(String key) {
        synchronized (failedUntil) { failedUntil.remove(key); }
    }

    private static List<String> splitBatchEncoded(String translated, int count, int base,
                                                  List<String> tokenSentinels) {
        if (translated == null || translated.trim().isEmpty())
            throw new IllegalStateException("empty response");
        List<String> anchors = new ArrayList<String>(count * 2);
        for (int i = 0; i < count * 2; i++) anchors.add(Integer.toString(base + i));
        List<String> allowed = new ArrayList<String>(anchors.size() + tokenSentinels.size());
        allowed.addAll(anchors);
        allowed.addAll(tokenSentinels);
        ParsedNumeric parsed = parseNumericMarkers(translated, allowed);
        List<String> out = new ArrayList<String>(count);
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            NumericOccurrence open = uniqueOccurrence(parsed, anchors.get(i * 2));
            NumericOccurrence close = uniqueOccurrence(parsed, anchors.get(i * 2 + 1));
            if (open.start < cursor || close.start < open.end)
                throw new IllegalStateException("out-of-order batch anchor");
            if (!translated.substring(cursor, open.start).trim().isEmpty())
                throw new IllegalStateException("out-of-order batch anchor");
            out.add(translated.substring(open.end, close.start).trim());
            cursor = close.end;
        }
        if (!translated.substring(cursor).trim().isEmpty())
            throw new IllegalStateException("unexpected text outside batch anchors");
        return out;
    }

    private static NumericOccurrence uniqueOccurrence(ParsedNumeric parsed, String marker) {
        NumericOccurrence found = null;
        for (NumericOccurrence occurrence : parsed.occurrences) {
            if (!marker.equals(occurrence.marker)) continue;
            if (found != null) throw new IllegalStateException("duplicate batch anchor");
            found = occurrence;
        }
        if (found == null) throw new IllegalStateException("missing batch anchor");
        return found;
    }

    private static ParsedNumeric parseNumericMarkers(String text, List<String> allowed) {
        List<NumericOccurrence> occurrences = new ArrayList<NumericOccurrence>();
        if (text == null || text.isEmpty() || allowed.isEmpty()) {
            return new ParsedNumeric(text, occurrences);
        }
        List<String> distinctAllowed = new ArrayList<String>(allowed.size());
        long maximumValidRun = 0L;
        for (String marker : allowed) {
            if (marker == null || marker.isEmpty() || distinctAllowed.contains(marker)) continue;
            distinctAllowed.add(marker);
            maximumValidRun += marker.length();
            if (maximumValidRun > Integer.MAX_VALUE) maximumValidRun = Integer.MAX_VALUE;
        }
        int cursor = 0;
        while (cursor < text.length()) {
            if (!isAsciiDigit(text.charAt(cursor))) {
                cursor++;
                continue;
            }
            int start = cursor;
            while (cursor < text.length() && isAsciiDigit(text.charAt(cursor))) cursor++;
            /* A valid response can contain every unique required marker at most once. */
            if (cursor - start > maximumValidRun) continue;
            String run = text.substring(start, cursor);
            Segmentation segmented = segmentNumericRun(run, 0, distinctAllowed,
                    new LinkedHashMap<Integer, Segmentation>());
            if (segmented.ways != 1) continue;
            int position = start;
            for (String marker : segmented.markers) {
                occurrences.add(new NumericOccurrence(position, position + marker.length(), marker));
                position += marker.length();
            }
        }
        return new ParsedNumeric(text, occurrences);
    }

    private static Segmentation segmentNumericRun(String run, int offset, List<String> allowed,
                                                   Map<Integer, Segmentation> memo) {
        if (offset == run.length()) {
            return new Segmentation(1, Collections.<String>emptyList());
        }
        Segmentation cached = memo.get(Integer.valueOf(offset));
        if (cached != null) return cached;
        int ways = 0;
        List<String> chosen = Collections.emptyList();
        for (String marker : allowed) {
            if (marker == null || marker.isEmpty() || !run.startsWith(marker, offset)) continue;
            Segmentation tail = segmentNumericRun(run, offset + marker.length(), allowed, memo);
            if (tail.ways == 0) continue;
            if (ways == 0 && tail.ways == 1) {
                chosen = new ArrayList<String>(tail.markers.size() + 1);
                chosen.add(marker);
                chosen.addAll(tail.markers);
            }
            ways = Math.min(2, ways + tail.ways);
            if (ways > 1) {
                chosen = Collections.emptyList();
                break;
            }
        }
        Segmentation result = new Segmentation(ways, chosen);
        memo.put(Integer.valueOf(offset), result);
        return result;
    }

    private static String replaceNumericOccurrences(ParsedNumeric parsed,
                                                    Map<String, String> replacements) {
        if (parsed.occurrences.isEmpty()) return parsed.text;
        StringBuilder output = new StringBuilder(parsed.text.length());
        int cursor = 0;
        for (NumericOccurrence occurrence : parsed.occurrences) {
            output.append(parsed.text, cursor, occurrence.start);
            String replacement = replacements.get(occurrence.marker);
            output.append(replacement == null
                    ? parsed.text.substring(occurrence.start, occurrence.end) : replacement);
            cursor = occurrence.end;
        }
        output.append(parsed.text, cursor, parsed.text.length());
        return output.toString();
    }

    private static boolean isAsciiDigit(char value) { return value >= '0' && value <= '9'; }

    private String requestAi(String text, String target, LegacyConfig config) throws Exception {
        if (config.aiUseCodex) {
            LegacyCodexClient client = codexClient;
            if (client == null) throw new IllegalStateException("Codex not initialized");
            String model = config.codexModel == null ? "" : config.codexModel.trim();
            String effort = config.codexReasoningEffort == null ? "" : config.codexReasoningEffort.trim();
            String systemPrompt = "Translate Minecraft text to " + target
                    + ". Preserve names, numbers, formatting codes, line breaks, numeric boundary markers,"
                    + " and every ⟦MTn⟧ placeholder exactly."
                    + " Return translation only.";
            return client.complete(model, effort, systemPrompt, text);
        }
        String baseUrl = config.aiBaseUrl == null ? "" : config.aiBaseUrl.trim();
        String model = config.aiModel == null ? "" : config.aiModel.trim();
        if (baseUrl.isEmpty() || model.isEmpty())
            throw new IllegalStateException("AI not configured");
        List<String> keys = new ArrayList<String>();
        if (config.aiApiKeys != null) for (String key : config.aiApiKeys)
            if (key != null && !key.trim().isEmpty() && keys.size() < MAX_AI_KEYS) keys.add(key.trim());
        if (keys.isEmpty()) {
            pace(true, config.requestCooldownMs);
            return executeAiHttp(text, target, config, null);
        }
        int start = keyCursor.getAndIncrement() & Integer.MAX_VALUE;
        String selectedKey = null;
        long now = System.currentTimeMillis();
        for (int offset = 0; offset < keys.size(); offset++) {
            String candidate = keys.get((start + offset) % keys.size());
            Long until = keyUnavailableUntil.get(candidate);
            if (until != null && until.longValue() > now) continue;
            if (until != null) keyUnavailableUntil.remove(candidate);
            selectedKey = candidate;
            break;
        }
        if (selectedKey == null) throw new IllegalStateException("all AI keys cooling down");
        try {
            pace(true, config.requestCooldownMs);
            return executeAiHttp(text, target, config, selectedKey);
        } catch (HttpStatusException status) {
            long delay = status.code == 429 ? 60_000L
                    : status.code == 401 || status.code == 403
                    ? Math.max(60_000L, config.failureBackoffMs) : 10_000L;
            keyUnavailableUntil.put(selectedKey, System.currentTimeMillis() + delay);
            throw status;
        } catch (Exception failure) {
            keyUnavailableUntil.put(selectedKey, System.currentTimeMillis() + 10_000L);
            throw failure;
        }
    }

    private String executeAiHttp(String text, String target, LegacyConfig config, String apiKey)
            throws Exception {
        TestAiHttp hook = testAiHttp;
        return hook == null ? postAi(text, target, config, apiKey)
                : hook.request(text, target, config, apiKey);
    }

    private String postAi(String text, String target, LegacyConfig config, String apiKey) throws Exception {
        String base = config.aiBaseUrl == null ? "" : config.aiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        JsonObject root = new JsonObject();
        root.addProperty("model", config.aiModel);
        root.addProperty("temperature", 0.1);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "Translate Minecraft text to " + target
                + ". Preserve names, numbers, formatting codes, line breaks, numeric boundary markers,"
                + " and every ⟦MTn⟧ placeholder exactly."
                + " Return translation only.");
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", text);
        messages.add(user);
        root.add("messages", messages);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(45000);
        if (apiKey != null && !apiKey.trim().isEmpty())
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream output = connection.getOutputStream();
        try { output.write(body); } finally { output.close(); }
        try {
            int code = connection.getResponseCode();
            String response = read(connection, code >= 400);
            if (code >= 400) throw new HttpStatusException(code, response);
            if (response == null || response.trim().isEmpty())
                throw new IllegalStateException("empty response");
            JsonObject parsed = new JsonParser().parse(response).getAsJsonObject();
            recordTokenUsage(parsed);
            JsonArray choices = parsed.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0)
                throw new IllegalStateException("no choices in response");
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull())
                throw new IllegalStateException("no content in response");
            String content = message.get("content").getAsString().trim();
            if (content.isEmpty()) throw new IllegalStateException("empty response");
            return content;
        } finally { connection.disconnect(); }
    }

    private void recordTokenUsage(JsonObject root) {
        if (root == null) return;
        JsonObject usage = objectMember(root, "usage");
        if (usage == null) return;
        long input = firstLong(usage, "prompt_tokens", "input_tokens", "inputTokens");
        long output = firstLong(usage, "completion_tokens", "output_tokens", "outputTokens");
        long total = firstLong(usage, "total_tokens", "totalTokens");
        long cached = firstLong(usage, "cached_input_tokens", "cachedInputTokens");
        long reasoning = firstLong(usage, "reasoning_output_tokens", "reasoningOutputTokens");
        JsonObject inputDetails = objectMember(usage, "prompt_tokens_details");
        if (inputDetails == null) inputDetails = objectMember(usage, "input_tokens_details");
        if (inputDetails != null)
            cached = Math.max(cached, firstLong(inputDetails, "cached_tokens", "cached_input_tokens"));
        JsonObject outputDetails = objectMember(usage, "completion_tokens_details");
        if (outputDetails == null) outputDetails = objectMember(usage, "output_tokens_details");
        if (outputDetails != null)
            reasoning = Math.max(reasoning, firstLong(outputDetails, "reasoning_tokens", "reasoning_output_tokens"));
        tokenUsage.recordRequest(input, cached, output, reasoning, total);
    }

    private static JsonObject objectMember(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static long firstLong(JsonObject object, String... keys) {
        if (object == null) return 0L;
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) continue;
            try { return Math.max(0L, value.getAsLong()); }
            catch (RuntimeException ignored) {}
        }
        return 0L;
    }
    private String requestGoogle(String text, String sourceLang, String target, int cooldown) throws Exception {
        pace(false, cooldown);
        String endpoint = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl="
                + enc(sourceLang) + "&tl=" + enc(target) + "&q=" + enc(text);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "MinecraftTranslator/1.0.4");
        try {
            int code = connection.getResponseCode();
            String body = read(connection, code >= 400);
            if (code >= 400) throw new HttpStatusException(code, body);
            if (body == null || body.trim().isEmpty())
                throw new IllegalStateException("empty response");
            JsonArray chunks = new JsonParser().parse(body).getAsJsonArray().get(0).getAsJsonArray();
            StringBuilder translated = new StringBuilder();
            for (JsonElement element : chunks) {
                JsonArray chunk = element.getAsJsonArray();
                if (chunk.size() > 0 && !chunk.get(0).isJsonNull()) translated.append(chunk.get(0).getAsString());
            }
            return translated.toString();
        } finally { connection.disconnect(); }
    }

    private void pace(boolean ai, int cooldown) throws InterruptedException {
        synchronized (paceLock) {
            long now = System.currentTimeMillis();
            long last = ai ? lastAiRequest : lastGtRequest;
            long wait = Math.max(0L, Math.max(0, cooldown) - (now - last));
            if (wait > 0L) Thread.sleep(wait);
            if (ai) lastAiRequest = System.currentTimeMillis(); else lastGtRequest = System.currentTimeMillis();
        }
    }

    private static String read(HttpURLConnection connection, boolean error) throws Exception {
        InputStream stream = error ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (body.length() + read > MAX_HTTP_RESPONSE_CHARS) {
                    throw new IllegalStateException("HTTP response too large");
                }
                body.append(buffer, 0, read);
            }
            return body.toString();
        } finally { reader.close(); }
    }

    private ThreadPoolExecutor createExecutor() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new FairTaskQueue(MAX_EXECUTOR_TASKS), new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "mctranslator-legacy-worker-"
                        + threadSequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private ScheduledThreadPoolExecutor createRetryScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "mctranslator-legacy-retry");
                thread.setDaemon(true);
                return thread;
            }
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static <K, V> Map<K, V> boundedMap(final int maximumSize) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maximumSize;
            }
        });
    }

    private static void accept(Consumer<String> callback, String value) {
        if (callback == null) return;
        try { callback.accept(value); }
        catch (RuntimeException ignored) {}
    }

    void shutdownForTests() {
        cancelPending();
        executor.shutdownNow();
        retryScheduler.shutdownNow();
    }

    int inFlightCountForTests() {
        synchronized (flightLock) { return inFlight.size(); }
    }

    int waiterCountForTests() {
        synchronized (flightLock) { return totalWaiters; }
    }

    static Runnable prioritizedTaskForTests(boolean high, Runnable task) {
        return new PrioritizedTask(high, task);
    }

    private static String cacheKey(String source, String target, boolean ai, String provider,
                                   LegacyConfig config) {
        String sourceLang = normalizedSourceLanguage(config);
        if (ai) return "AI\n" + aiProfile(config) + '\n' + target + '\n' + source;
        String selected = LegacyConfig.normalizeMachineProvider(provider);
        return "GT\n" + selected + '\n' + sourceLang + '\n' + target + '\n' + source;
    }

    private static String aiProfile(LegacyConfig config) {
        String provider = LegacyConfig.normalizeMachineProvider(
                config == null ? "google" : config.machineTranslationProvider);
        String fallback = config != null && config.disableGoogleFallbackForAi
                ? "disabled" : "enabled";
        boolean machineFallback = config == null || !config.disableGoogleFallbackForAi;
        String engine;
        if (config != null && config.aiUseCodex) {
            engine = "codex:" + safe(config.codexModel) + ':'
                    + safe(config.codexReasoningEffort);
        } else {
            engine = "api:" + safe(config == null ? null : config.aiBaseUrl) + ':'
                    + safe(config == null ? null : config.aiModel);
        }
        String profile = engine + "|fallback:" + fallback;
        if (machineFallback) {
            profile += "|source:" + normalizedSourceLanguage(config)
                    + "|provider:" + provider;
        }
        return profile;
    }

    private static String normalizedSourceLanguage(LegacyConfig config) {
        String value = config == null ? "auto" : safe(config.sourceLang).trim();
        return value.isEmpty() ? "auto" : value.toLowerCase(java.util.Locale.ROOT);
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static String compact(String value) {
        String flat = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 64 ? flat : flat.substring(0, 61) + "...";
    }
    private static final class HttpStatusException extends Exception {
        final int code;
        HttpStatusException(int code, String body) { super("HTTP " + code + ": " + compact(body)); this.code = code; }
    }
}
