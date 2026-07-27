package com.dragonmeow.mctranslator.translate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory token totals for the current Minecraft process.
 *
 * <p>Nothing is persisted. Ordinary API responses are recorded once, while
 * cumulative Codex thread updates are de-duplicated by thread id.</p>
 */
public final class SessionTokenUsage {
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong reasoningOutputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final ConcurrentHashMap<String, Counters> cumulativeSources =
            new ConcurrentHashMap<>();

    public void recordRequest(long input, long cachedInput, long output,
                              long reasoningOutput, long total) {
        add(sanitize(input), sanitize(cachedInput), sanitize(output),
                sanitize(reasoningOutput), resolvedTotal(input, output, total), 1L);
    }

    /**
     * Record a cumulative counter without double-counting repeated updates.
     * Codex app-server sends thread totals, sometimes more than once per turn.
     */
    public void recordCumulative(String sourceId, long input, long cachedInput, long output,
                                 long reasoningOutput, long total) {
        if (sourceId == null || sourceId.isBlank()) return;
        Counters current = new Counters(
                sanitize(input),
                sanitize(cachedInput),
                sanitize(output),
                sanitize(reasoningOutput),
                resolvedTotal(input, output, total));
        cumulativeSources.compute(sourceId, (key, previous) -> {
            if (previous == null) {
                add(current.input, current.cachedInput, current.output,
                        current.reasoningOutput, current.total, 1L);
            } else {
                add(delta(current.input, previous.input),
                        delta(current.cachedInput, previous.cachedInput),
                        delta(current.output, previous.output),
                        delta(current.reasoningOutput, previous.reasoningOutput),
                        delta(current.total, previous.total), 0L);
            }
            return current;
        });
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
        public static final Snapshot EMPTY = new Snapshot(0L, 0L, 0L, 0L, 0L, 0L);
    }
}
