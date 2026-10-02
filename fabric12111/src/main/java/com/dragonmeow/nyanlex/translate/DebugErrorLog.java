package com.dragonmeow.nyanlex.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The one file 偵錯模式 writes: a local error log, one JSON object per line, holding at most the
 * newest {@value #DEFAULT_MAX_ENTRIES} entries (older ones fall off the front).
 *
 * <p>Nothing is written unless something went wrong while 偵錯模式 is on: while it is off
 * {@link #record} drops everything, and with it on but no error there is no file at all (this
 * class does no I/O until its first entry). Each entry carries the time, a type, a message and a
 * little context (which request failed, the request and response summary). API keys are masked
 * before anything is stored: every configured key value, plus the usual key shapes
 * ({@code sk-...}, {@code AIza...}, {@code Bearer ...}, {@code key=...}).</p>
 *
 * <p>Writing never blocks the caller: {@link #record} only masks, formats and queues a line; one
 * background thread merges the queue into the file and rewrites it atomically.</p>
 */
public final class DebugErrorLog {
    public static final int DEFAULT_MAX_ENTRIES = 1000;

    public static final String TRANSLATION_FAILED = "translation_failed";
    public static final String TOKEN_LOST = "token_lost";
    public static final String HTTP_ERROR = "http_error";
    public static final String NETWORK = "network";
    public static final String EXCEPTION = "exception";
    public static final String HOOK = "hook";
    /** Startup migration of files left by an earlier product name (see LegacyDataMigration). */
    public static final String MIGRATION = "migration";
    /** The failure reason for a model that handed the source text back unchanged. It is the
     *  correct answer for a name, a code or an abbreviation, so it is not an error to report. */
    public static final String UNCHANGED_ECHO_REASON = "unchanged (echo)";
    private static final int MAX_EARLY_REPORTS = 20;

    private static final String MASK = "***";
    private static final int MIN_SECRET_LENGTH = 8;
    private static final int MAX_FIELD_CHARS = 600;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern[] KEY_SHAPES = {
            Pattern.compile("sk-[A-Za-z0-9_\\-]{12,}"),
            Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}"),
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=\\-]{8,}"),
    };
    private static final Pattern KEY_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|auth(?:orization)?|secret|[?&]key)[\"']?\\s*[:=]\\s*[\"']?)"
                    + "[^\\s\"'&,}\\]]{6,}");

    private static volatile DebugErrorLog current;
    /** Reports made before the log was installed (the startup migration runs first). */
    private static final List<Object[]> EARLY = new ArrayList<>();

    private final Path file;
    private final BooleanSupplier enabled;
    private final int maxEntries;
    private final Supplier<? extends Collection<String>> secrets;
    private final Object lock = new Object();
    private final List<String> pending = new ArrayList<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicReference<Future<?>> lastSubmitted = new AtomicReference<>();
    private final ExecutorService writer;
    private Deque<String> kept; // loaded on the writer thread

    public DebugErrorLog(Path file, BooleanSupplier enabled, int maxEntries,
                         Supplier<? extends Collection<String>> secrets) {
        this.file = file;
        this.enabled = enabled == null ? () -> false : enabled;
        this.maxEntries = Math.max(1, maxEntries);
        this.secrets = secrets == null ? List::of : secrets;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nyanlex-debug-log");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ the shared instance

    /** The log the game runs with; {@link #report} goes to it. {@code null} removes it (and
     *  forgets any {@link #reportEarly} report that was still waiting for a log). */
    public static void install(DebugErrorLog log) {
        current = log;
        if (log == null) {
            synchronized (EARLY) {
                EARLY.clear();
            }
            return;
        }
        List<Object[]> waiting;
        synchronized (EARLY) {
            waiting = new ArrayList<>(EARLY);
            EARLY.clear();
        }
        for (Object[] report : waiting) {
            report((String) report[0], (String) report[1], (String[]) report[2]);
        }
    }

    /** Like {@link #report}, but a report made before any log is installed is kept (up to a
     *  few) and recorded when {@link #install} runs. For startup work that precedes the log. */
    public static void reportEarly(String type, String message, String... contextPairs) {
        if (current == null) {
            synchronized (EARLY) {
                if (current == null) {
                    if (EARLY.size() < MAX_EARLY_REPORTS) EARLY.add(new Object[] {type, message, contextPairs});
                    return;
                }
            }
        }
        report(type, message, contextPairs);
    }

    public static DebugErrorLog current() {
        return current;
    }

    /** Records an error in the installed log; does nothing when there is none or 偵錯模式 is off. */
    public static void report(String type, String message, String... contextPairs) {
        DebugErrorLog log = current;
        if (log == null) return;
        Map<String, String> context = new LinkedHashMap<>();
        for (int i = 0; i + 1 < contextPairs.length; i += 2) context.put(contextPairs[i], contextPairs[i + 1]);
        log.record(type, message, context);
    }

    // ------------------------------------------------------------------ recording

    /** Whether an error recorded now would be kept (偵錯模式 is on). */
    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    public Path file() {
        return file;
    }

    /** Queues one error. Cheap: masks and formats the entry, then hands it to the writer thread. */
    public void record(String type, String message, Map<String, String> context) {
        if (!enabled.getAsBoolean()) return;
        try {
            List<String> live = snapshotSecrets();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", Instant.now().toString());
            entry.put("type", type == null ? EXCEPTION : type);
            entry.put("message", clip(mask(message, live)));
            if (context != null && !context.isEmpty()) {
                Map<String, String> shown = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : context.entrySet()) {
                    shown.put(e.getKey(), clip(mask(e.getValue(), live)));
                }
                entry.put("context", shown);
            }
            String line = GSON.toJson(entry).replace('\n', ' ').replace('\r', ' ');
            synchronized (lock) {
                pending.add(line);
            }
            schedule();
        } catch (RuntimeException ignored) {
            // The error log must never break translation.
        }
    }

    /** Records an exception: its class and message, with the cause chain. */
    public void recordException(String type, Throwable error, Map<String, String> context) {
        StringBuilder text = new StringBuilder();
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 6; depth++, cursor = cursor.getCause()) {
            if (depth > 0) text.append(" <- ");
            text.append(cursor.getClass().getSimpleName());
            if (cursor.getMessage() != null) text.append(": ").append(cursor.getMessage());
            if (cursor.getCause() == cursor) break;
        }
        record(type, text.toString(), context);
    }

    /**
     * A sink for {@link OpenAiTranslator}'s per-exchange hook that keeps only the exchanges that
     * went wrong (an unreadable batch, or a unit that was rejected), with a request and response
     * summary. Successful exchanges leave no trace.
     */
    public OpenAiTranslator.ExchangeDumpSink exchangeSink() {
        return (requestBody, responseBody, sourceTexts, results) -> {
            if (!enabled.getAsBoolean()) return;
            String problem = null;
            if (results == null) {
                problem = "anchor/order damaged";
            } else {
                for (TranslationResult result : results) {
                    if (result != null && result.failureReason() != null) {
                        problem = result.failureReason();
                        break;
                    }
                }
            }
            if (problem == null || isBenignReason(problem)) return;
            Map<String, String> context = new LinkedHashMap<>();
            context.put("request", requestBody);
            context.put("response", responseBody);
            if (sourceTexts != null && !sourceTexts.isEmpty()) {
                context.put("sources", String.join(" | ", sourceTexts));
            }
            record(typeForReason(problem), problem, context);
        };
    }

    /** A failure reason that is a normal outcome, not a fault: nothing to write to the error log. */
    public static boolean isBenignReason(String reason) {
        return reason != null && UNCHANGED_ECHO_REASON.equals(reason.strip());
    }

    /** The error type that goes with a failure reason from the translation engine. */
    public static String typeForReason(String reason) {
        if (reason == null) return TRANSLATION_FAILED;
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("token") || r.contains("anchor") || r.contains("paragraph") || r.contains("format")) {
            return TOKEN_LOST;
        }
        if (r.contains("429") || r.contains("http") || r.contains("authentication")) return HTTP_ERROR;
        if (r.contains("timeout") || r.contains("network") || r.contains("timed out")) return NETWORK;
        return TRANSLATION_FAILED;
    }

    // ------------------------------------------------------------------ masking

    /** Replaces every configured key value and every common key shape in {@code text} with {@code ***}. */
    public static String mask(String text, Collection<String> keys) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        if (keys != null) {
            for (String key : keys) {
                if (key == null) continue;
                String k = key.strip();
                if (k.length() >= MIN_SECRET_LENGTH && out.contains(k)) out = out.replace(k, MASK);
            }
        }
        for (Pattern shape : KEY_SHAPES) {
            out = shape.matcher(out).replaceAll(m -> m.groupCount() > 0 && m.group(1) != null
                    ? java.util.regex.Matcher.quoteReplacement(m.group(1) + MASK) : MASK);
        }
        out = KEY_ASSIGNMENT.matcher(out).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(m.group(1) + MASK));
        return out;
    }

    private List<String> snapshotSecrets() {
        try {
            Collection<String> live = secrets.get();
            return live == null ? List.of() : new ArrayList<>(live);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= MAX_FIELD_CHARS ? text : text.substring(0, MAX_FIELD_CHARS) + "...";
    }

    // ------------------------------------------------------------------ writing (background thread)

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        lastSubmitted.set(writer.submit(this::flush));
    }

    private void flush() {
        scheduled.set(false);
        List<String> batch;
        synchronized (lock) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        try {
            if (kept == null) kept = load();
            kept.addAll(batch);
            while (kept.size() > maxEntries) kept.removeFirst();
            write(kept);
        } catch (IOException | RuntimeException ignored) {
            // A failed write is dropped; the log is a courtesy, not a guarantee.
        }
    }

    private Deque<String> load() {
        Deque<String> lines = new ArrayDeque<>();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) lines.addLast(line);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable old file is simply replaced
        }
        while (lines.size() > maxEntries) lines.removeFirst();
        return lines;
    }

    private void write(Deque<String> lines) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Test-only: blocks until everything queued so far has been written. */
    public void awaitIdleForTest() {
        for (int i = 0; i < 3; i++) {
            Future<?> future = lastSubmitted.get();
            if (future == null) return;
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                return;
            }
            synchronized (lock) {
                if (pending.isEmpty() && !scheduled.get()) return;
            }
        }
    }
}
