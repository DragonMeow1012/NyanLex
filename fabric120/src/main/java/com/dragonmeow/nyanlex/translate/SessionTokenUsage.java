package com.dragonmeow.nyanlex.translate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory token totals for the current Minecraft process.
 *
 * <p>Nothing is persisted. Ordinary API responses are recorded once, while
 * cumulative Codex thread updates are de-duplicated by thread id.</p>
 */
public final class SessionTokenUsage {
    private static final int MAX_CUMULATIVE_SOURCES = 1_024;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong reasoningOutputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final Map<String, Counters> cumulativeSources = new HashMap<>();

    public void recordRequest(long input, long cachedInput, long output,
                              long reasoningOutput, long total) {
        add(sanitize(input), sanitize(cachedInput), sanitize(output),
                sanitize(reasoningOutput), resolvedTotal(input, output, total), 1L);
    }

    /**
     * Record a cumulative counter without double-counting repeated updates.
     * Codex app-server sends thread totals, sometimes more than once per turn.
     */
    public synchronized void recordCumulative(String sourceId, long input, long cachedInput,
                                              long output, long reasoningOutput, long total) {
        if (sourceId == null || sourceId.isBlank()) return;
        Counters current = new Counters(
                sanitize(input),
                sanitize(cachedInput),
                sanitize(output),
                sanitize(reasoningOutput),
                resolvedTotal(input, output, total));
        Counters previous = cumulativeSources.get(sourceId);
        if (previous == null) {
            // Never evict a live de-duplication baseline. A late cumulative update for
            // an evicted source would otherwise be counted again in full. Lifecycle
            // owners release completed sources through finishCumulative().
            if (cumulativeSources.size() >= MAX_CUMULATIVE_SOURCES) return;
            cumulativeSources.put(sourceId, current);
            add(current.input, current.cachedInput, current.output,
                    current.reasoningOutput, current.total, 1L);
        } else {
            // Cumulative notifications can be duplicated or arrive out of order.
            // Preserve the per-field high-water marks so a later update cannot count
            // an already-recorded range twice.
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
    }

    /**
     * Release the de-duplication baseline for a source that cannot emit any more
     * cumulative updates. The totals themselves remain part of this session; only the
     * per-source bookkeeping is discarded.
     */
    public synchronized void finishCumulative(String sourceId) {
        if (sourceId != null) cumulativeSources.remove(sourceId);
    }

    /** Visible to package diagnostics and resource-bound regression tests. */
    synchronized int activeCumulativeSources() {
        return cumulativeSources.size();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                inputTokens.get(),
                cachedInputTokens.get(),
                outputTokens.get(),
                reasoningOutputTokens.get(),
                totalTokens.get(),
                requests.get());
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

    private static long sanitize(long value) {
        return Math.max(0L, value);
    }

    private static long resolvedTotal(long input, long output, long total) {
        return total > 0L ? total : sanitize(input) + sanitize(output);
    }

    private static long delta(long current, long previous) {
        return Math.max(0L, current - previous);
    }

    private record Counters(long input, long cachedInput, long output,
                            long reasoningOutput, long total) {
    }

    public record Snapshot(long inputTokens, long cachedInputTokens,
                           long outputTokens, long reasoningOutputTokens,
                           long totalTokens, long requests) {
    }
}
