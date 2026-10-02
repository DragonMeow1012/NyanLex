package com.dragonmeow.nyanslate.translate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Writes a bounded, rotating on-disk trace of how ONE structured tooltip paragraph was
 * planned and resolved — a per-segment companion to {@link ExchangeDumpWriter}'s own
 * {@code exchange-*.txt} files (same {@code dumpDir}, same "only while {@code
 * config.debugTranslationOverlay} is on" gate, same background single-thread + bounded
 * rotation discipline), capturing the ONE layer an HTTP exchange dump cannot see: which
 * {@link TooltipSegmentPlanner} segment a row became, what LOCAL (position-independent)
 * cache key it actually resolved/requested under (see {@code
 * TranslationService#resolveEnchantName}'s 2026-10-02 segment-key-normalisation doc), and
 * whether that lookup hit, is still pending, or is missing — so a future "why did this ONE
 * row never show translated" investigation reads it straight off disk instead of
 * re-deriving it by hand from raw exchange dumps (exactly what THIS fix's own investigation
 * had to do, live, against a real Hypixel session).
 *
 * <p>Active only while {@code enabled} reports {@code true} AT CALL TIME, writes run on a
 * dedicated single background thread (a slow disk can never stall a render frame), and a
 * failed write is swallowed (debug tooling must never break translation). Keeps at most
 * {@code maxFiles} files on disk, pruning the oldest by filename order (zero-padded
 * millisecond timestamp prefix), same as {@link ExchangeDumpWriter}.</p>
 */
public final class TooltipTraceWriter {
    private static final String PREFIX = "tooltip-trace-";
    private static final String SUFFIX = ".txt";

    private final Path dumpDir;
    private final BooleanSupplier enabled;
    private final int maxFiles;
    private final ExecutorService writer;
    private final AtomicLong counter = new AtomicLong();
    private final AtomicReference<Future<?>> lastSubmitted = new AtomicReference<>();

    public TooltipTraceWriter(Path dumpDir, BooleanSupplier enabled, int maxFiles) {
        this.dumpDir = dumpDir;
        this.enabled = enabled == null ? () -> false : enabled;
        this.maxFiles = Math.max(1, maxFiles);
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nyanslate-tooltip-trace");
            t.setDaemon(true);
            return t;
        });
    }

    /** One classified segment's own trace line. {@code localizedKey} is {@code null} for a
     *  kind that never goes through the shared cache chokepoint under its raw text (RARITY
     *  is resolved from the local term table; ENCHANT/SCROLL/INERT key per NAME, not per
     *  row — see {@code TranslationService}). {@code verdict} is a short human word: {@code
     *  "ok"} (resolved this pass), {@code "pending"} (a request is already in flight for
     *  this exact key), or {@code "missing"} (nothing cached, nothing pending — about to be
     *  freshly requested, or blocked by the master switch/protection rules). */
    public record SegmentTrace(String kind, String rawText, String localizedKey, String verdict) {
    }

    /** Cheap, side-effect-free check a caller should make BEFORE doing any extra work to
     *  build a trace (segment-by-segment key localisation, verdict lookups, …) — so the
     *  common "overlay off" case costs only this one boolean read. */
    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    public void record(String original, List<SegmentTrace> segments, String finalDisplay) {
        if (!enabled.getAsBoolean()) return;
        long n = counter.incrementAndGet();
        long atMs = System.currentTimeMillis();
        List<SegmentTrace> copy = segments == null ? List.of() : List.copyOf(segments);
        lastSubmitted.set(writer.submit(() -> writeNow(n, atMs, original, copy, finalDisplay)));
    }

    /** Test-only: blocks until every write submitted so far has finished. */
    void awaitIdleForTest() {
        Future<?> future = lastSubmitted.get();
        if (future == null) return;
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort for tests only; a timed-out wait just leaves the assertion to fail.
        }
    }

    private void writeNow(long n, long atMs, String original, List<SegmentTrace> segments,
                          String finalDisplay) {
        try {
            Files.createDirectories(dumpDir);
            String content = render(atMs, original, segments, finalDisplay);
            String fileName = PREFIX + String.format("%020d", atMs) + "-" + n + SUFFIX;
            Files.writeString(dumpDir.resolve(fileName), content, StandardCharsets.UTF_8);
            pruneOldest();
        } catch (IOException | RuntimeException ignored) {
            // Debug tooling must never break a real translation request.
        }
    }

    private static String render(long atMs, String original, List<SegmentTrace> segments,
                                 String finalDisplay) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Nyanslate tooltip trace\n")
                .append("time=").append(Instant.ofEpochMilli(atMs)).append('\n')
                .append("=== ORIGINAL (raw planned paragraph; CS/MT/WS/PB markers and any "
                        + "remaining section codes exactly as TooltipSegmentPlanner saw it) ===\n")
                .append(nullToEmpty(original)).append("\n\n")
                .append("=== PARAGRAPH PLAN (one entry per classified/unclassified segment, "
                        + "in row order) ===\n");
        int i = 0;
        for (SegmentTrace s : segments) {
            sb.append(i++).append(": kind=").append(s.kind())
                    .append(" verdict=").append(s.verdict()).append('\n')
                    .append("    raw=").append(oneLine(s.rawText())).append('\n');
            if (s.localizedKey() != null) {
                sb.append("    key=").append(oneLine(s.localizedKey())).append('\n');
            }
        }
        sb.append("\n=== FINAL DISPLAY (null when still incomplete this pass) ===\n")
                .append(nullToEmpty(finalDisplay)).append('\n');
        return sb.toString();
    }

    private void pruneOldest() {
        try (var stream = Files.list(dumpDir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().startsWith(PREFIX)
                            && p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
            int excess = files.size() - maxFiles;
            for (int i = 0; i < excess; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException ignored) {
            // Best-effort rotation; a failed prune never blocks the write that already happened.
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
