package com.dragonmeow.nyanlex.forgelegacy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory token totals for the current Minecraft process. */
final class LegacySessionTokenUsage {
    private static final int MAX_CUMULATIVE_SOURCES = 1024;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong reasoningOutputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final Map<String, Counters> cumulativeSources = new HashMap<String, Counters>();

    void recordRequest(long input, long cachedInput, long output,
                       long reasoningOutput, long total) {
        add(sanitize(input), sanitize(cachedInput), sanitize(output),
                sanitize(reasoningOutput), resolvedTotal(input, output, total), 1L);
    }

    synchronized void recordCumulative(String sourceId, long input, long cachedInput, long output,
                                       long reasoningOutput, long total) {
        if (sourceId == null || sourceId.trim().isEmpty()) return;
        Counters current = new Counters(
                sanitize(input), sanitize(cachedInput), sanitize(output),
                sanitize(reasoningOutput), resolvedTotal(input, output, total));
        Counters previous = cumulativeSources.get(sourceId);
        if (previous == null) {
            if (cumulativeSources.size() >= MAX_CUMULATIVE_SOURCES) return;
            cumulativeSources.put(sourceId, current);
            add(current.input, current.cachedInput, current.output,
                    current.reasoningOutput, current.total, 1L);
            return;
        }
        Counters highWater = new Counters(
                Math.max(current.input, previous.input),
                Math.max(current.cachedInput, previous.cachedInput),
                Math.max(current.output, previous.output),
                Math.max(current.reasoningOutput, previous.reasoningOutput),
                Math.max(current.total, previous.total));
        cumulativeSources.put(sourceId, highWater);
        add(delta(highWater.input, previous.input),
                delta(highWater.cachedInput, previous.cachedInput),
                delta(highWater.output, previous.output),
                delta(highWater.reasoningOutput, previous.reasoningOutput),
                delta(highWater.total, previous.total), 0L);
    }

    synchronized void finishCumulative(String sourceId) {
        if (sourceId != null) cumulativeSources.remove(sourceId);
    }

    synchronized int activeCumulativeSources() { return cumulativeSources.size(); }

    Snapshot snapshot() {
        return new Snapshot(inputTokens.get(), cachedInputTokens.get(), outputTokens.get(),
                reasoningOutputTokens.get(), totalTokens.get(), requests.get());
    }

    private void add(long input, long cachedInput, long output,
                     long reasoningOutput, long total, long requestCount) {
        inputTokens.addAndGet(input);
        cachedInputTokens.addAndGet(cachedInput);
        outputTokens.addAndGet(output);
        reasoningOutputTokens.addAndGet(reasoningOutput);
        totalTokens.addAndGet(total);
        requests.addAndGet(requestCount);
    }

    private static long sanitize(long value) { return Math.max(0L, value); }
    private static long resolvedTotal(long input, long output, long total) {
        return total > 0L ? total : sanitize(input) + sanitize(output);
    }
    private static long delta(long current, long previous) {
        return Math.max(0L, current - previous);
    }

    private static final class Counters {
        final long input, cachedInput, output, reasoningOutput, total;
        Counters(long input, long cachedInput, long output, long reasoningOutput, long total) {
            this.input = input;
            this.cachedInput = cachedInput;
            this.output = output;
            this.reasoningOutput = reasoningOutput;
            this.total = total;
        }
    }

    static final class Snapshot {
        private final long inputTokens, cachedInputTokens, outputTokens;
        private final long reasoningOutputTokens, totalTokens, requests;
        Snapshot(long inputTokens, long cachedInputTokens, long outputTokens,
                 long reasoningOutputTokens, long totalTokens, long requests) {
            this.inputTokens = inputTokens;
            this.cachedInputTokens = cachedInputTokens;
            this.outputTokens = outputTokens;
            this.reasoningOutputTokens = reasoningOutputTokens;
            this.totalTokens = totalTokens;
            this.requests = requests;
        }
        long inputTokens() { return inputTokens; }
        long cachedInputTokens() { return cachedInputTokens; }
        long outputTokens() { return outputTokens; }
        long reasoningOutputTokens() { return reasoningOutputTokens; }
        long totalTokens() { return totalTokens; }
        long requests() { return requests; }
    }
}
