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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Writes a bounded, rotating on-disk trace of real {@link OpenAiTranslator} HTTP
 * exchanges — request/response BODIES ONLY, never a header or an API key (those never
 * reach {@link OpenAiTranslator.ExchangeDumpSink#record} in the first place: {@code
 * postWithKeyRotation} keeps the {@code Authorization} header completely separate from
 * the body this class ever sees) — plus each exchange's per-unit accept/reject verdict,
 * so a player who hits a translation failure can send the files under {@code dumpDir}
 * for offline diagnosis (the exact investigation {@code scratchpad/live-ai}'s manual
 * probe had to improvise by hand).
 *
 * <p>Active only while {@code enabled} reports {@code true} AT CALL TIME (the loader
 * wires this to {@code config.debugTranslationOverlay}, the same flag the in-game "MT
 * DEBUG" overlay uses), so turning the overlay off in-game stops new writes immediately;
 * existing files are left untouched for the player to find and share. Writes run on a
 * dedicated single background thread — a slow disk can never stall a translation
 * request — and a failed write is swallowed (debug tooling must never break
 * translation). Keeps at most {@code maxFiles} files on disk, pruning the chronologically
 * oldest ones by filename order (zero-padded millisecond timestamp prefix) on every
 * write, so the cap holds even across game restarts, not just within one session.</p>
 *
 * <p><b>2026-10 belt-and-suspenders key redaction:</b> the design invariant above (the
 * Authorization header never reaches {@link #record}) is exactly that — a design
 * invariant upheld by {@code postWithKeyRotation}'s own code shape, not something this
 * class can verify from here. Per an explicit user request, every body is ALSO compared
 * against the actual configured API key VALUES (via {@code secretsSupplier}) and any
 * match is replaced before the bytes ever reach disk, so a future refactor of the
 * transport that accidentally let a key leak into a body (a custom base URL embedding
 * it, a provider echoing a header back into its JSON error body, …) still cannot write
 * that key to a file a player might share for diagnosis.</p>
 */
public final class ExchangeDumpWriter implements OpenAiTranslator.ExchangeDumpSink {
    private static final String PREFIX = "exchange-";
    private static final String SUFFIX = ".txt";
    private static final String REDACTED = "***REDACTED-API-KEY***";
    /** Below this length a "key" is too short to safely blanket-replace (it risks
     *  mangling ordinary request text that happens to contain the same short substring);
     *  every real provider API key in practice is far longer than this. */
    private static final int MIN_REDACTABLE_SECRET_LENGTH = 8;

    private final Path dumpDir;
    private final BooleanSupplier enabled;
    private final int maxFiles;
    private final Supplier<List<String>> secretsSupplier;
    private final ExecutorService writer;
    private final AtomicLong counter = new AtomicLong();
    private final AtomicReference<Future<?>> lastSubmitted = new AtomicReference<>();

    public ExchangeDumpWriter(Path dumpDir, BooleanSupplier enabled, int maxFiles) {
        this(dumpDir, enabled, maxFiles, List::of);
    }

    /** @param secretsSupplier live API key values to redact from every body before it is
     *      ever written to disk (see the class doc's "belt-and-suspenders key
     *      redaction"); read fresh on every {@link #record} call, {@code null}/empty-safe. */
    public ExchangeDumpWriter(Path dumpDir, BooleanSupplier enabled, int maxFiles,
                              Supplier<List<String>> secretsSupplier) {
        this.dumpDir = dumpDir;
        this.enabled = enabled == null ? () -> false : enabled;
        this.maxFiles = Math.max(1, maxFiles);
        this.secretsSupplier = secretsSupplier == null ? List::of : secretsSupplier;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nyanslate-debug-dump");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void record(String requestBody, String responseBody, List<String> sourceTexts,
                       List<TranslationResult> results) {
        if (!enabled.getAsBoolean()) return;
        long n = counter.incrementAndGet();
        long atMs = System.currentTimeMillis();
        // Copy now: the caller's lists must never be read from the background thread
        // after this method returns (TranslationResult/String are both immutable, but
        // the LIST itself — e.g. a caller-owned ArrayList — is not guaranteed to be).
        List<String> texts = sourceTexts == null ? null : List.copyOf(sourceTexts);
        List<TranslationResult> verdicts = results == null ? null : List.copyOf(results);
        // Snapshot the live key values NOW (render thread), not from the background
        // writer thread later: config may be edited concurrently, and redaction must use
        // exactly the keys that were actually live for THIS exchange.
        List<String> secrets = snapshotSecrets();
        lastSubmitted.set(writer.submit(() -> writeNow(n, atMs, redact(requestBody, secrets),
                redact(responseBody, secrets), texts, verdicts)));
    }

    /** Test-only: blocks until every write submitted so far has finished (the writer is a
     *  single background thread, so waiting on the most recently submitted task also
     *  waits on every earlier one by FIFO ordering). A no-op when nothing was submitted. */
    void awaitIdleForTest() {
        Future<?> future = lastSubmitted.get();
        if (future == null) return;
        try {
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort for tests only; a timed-out wait just leaves the assertion to fail.
        }
    }

    private List<String> snapshotSecrets() {
        try {
            List<String> live = secretsSupplier.get();
            return live == null ? List.of() : List.copyOf(live);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** Replaces every occurrence of each non-blank, long-enough secret in {@code text}
     *  with {@link #REDACTED}. {@code null}/blank-safe; never throws. */
    private static String redact(String text, List<String> secrets) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) return text;
        String out = text;
        for (String secret : secrets) {
            if (secret == null) continue;
            String trimmed = secret.strip();
            if (trimmed.length() < MIN_REDACTABLE_SECRET_LENGTH) continue;
            if (out.contains(trimmed)) out = out.replace(trimmed, REDACTED);
        }
        return out;
    }

    private void writeNow(long n, long atMs, String requestBody, String responseBody,
                          List<String> sourceTexts, List<TranslationResult> results) {
        try {
            Files.createDirectories(dumpDir);
            String content = render(atMs, requestBody, responseBody, sourceTexts, results);
            String fileName = PREFIX + String.format("%020d", atMs) + "-" + n + SUFFIX;
            Files.writeString(dumpDir.resolve(fileName), content, StandardCharsets.UTF_8);
            pruneOldest();
        } catch (IOException | RuntimeException ignored) {
            // Debug tooling must never break a real translation request.
        }
    }

    private static String render(long atMs, String requestBody, String responseBody,
                                 List<String> sourceTexts, List<TranslationResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Nyanslate AI exchange dump\n")
                .append("time=").append(Instant.ofEpochMilli(atMs)).append('\n')
                .append("# Bodies only -- never the Authorization header or API key.\n\n")
                .append("=== REQUEST BODY ===\n").append(nullToEmpty(requestBody)).append("\n\n")
                .append("=== RESPONSE BODY (raw, before wire-codec decode) ===\n")
                .append(nullToEmpty(responseBody)).append('\n');
        if (results == null) {
            sb.append("\n=== PER-UNIT VERDICTS ===\n(not available -- multi-unit anchor/order"
                    + " damaged; this chunk is about to be bisected into fresh requests, each"
                    + " with its own dump entry)\n");
        } else {
            sb.append("\n=== PER-UNIT VERDICTS ===\n");
            int count = sourceTexts == null ? results.size() : sourceTexts.size();
            for (int i = 0; i < count; i++) {
                String source = sourceTexts == null ? null : sourceTexts.get(i);
                TranslationResult result = i < results.size() ? results.get(i) : null;
                String reason = result == null ? null : result.failureReason();
                sb.append(i).append(": ")
                        .append(reason == null ? "OK" : "FAILED(" + reason + ")")
                        .append(" source=").append(oneLine(source)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Lexical order == chronological order (zero-padded timestamp prefix), so the
     *  oldest files are always at the front of this listing. */
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
