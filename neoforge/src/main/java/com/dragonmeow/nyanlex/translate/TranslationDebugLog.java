package com.dragonmeow.nyanlex.translate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Thread-safe bounded trace of requests that actually reached a backend. */
public final class TranslationDebugLog {
    private static final int CAPACITY = 80;
    private static final long COMPLETED_TTL_MS = 20_000L;
    /** 2026-10 fix: an {@link Status#IN_FLIGHT} row older than this is reclassified as a
     *  timeout instead of being left to show "等待中" forever. {@link #snapshot} used to
     *  exempt IN_FLIGHT rows from its own TTL sweep unconditionally, so any requestId that
     *  never reached a matching {@link #completed(long, Failure)}/{@link #discard} call —
     *  a stuck transport call below its own HTTP timeout, a dropped callback, any future
     *  code path this trace could not anticipate — stayed visible as permanently pending.
     *  Every normal success/failure exit already resolves well under a minute (the real
     *  OpenAI-compatible transport's own POST timeout is 30s); this margin is generous
     *  enough to never misclassify a legitimately slow multi-key-rotation retry while
     *  still giving the overlay a bounded worst case instead of an unbounded one. */
    private static final long IN_FLIGHT_STALE_MS = 3 * 60_000L;
    private static final java.util.regex.Pattern CS_MARKER = java.util.regex.Pattern.compile(
            "(?i)\\u27E6\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7");
    private static final java.util.regex.Pattern MT_SLOT = java.util.regex.Pattern.compile(
            "(?i)\\u27E6\\s*MT\\s*\\d+\\s*\\u27E7");
    private static final java.util.regex.Pattern WS_SLOT = java.util.regex.Pattern.compile(
            "(?i)\\u27E6\\s*WS\\s*\\d+\\s*\\u27E7");
    private static final java.util.regex.Pattern NAME_SLOT = java.util.regex.Pattern.compile(
            "\\u27E6\\s*\\d+\\s*\\u27E7");
    private static final java.util.regex.Pattern SECTION_CODE = java.util.regex.Pattern.compile(
            "§.", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern RATE_LIMITED_ERROR = java.util.regex.Pattern.compile(
            "(?i)(?:\\bHTTP\\s*429\\b|\\brate[\\s_-]*limit(?:ed|ing)?\\b|\\btoo\\s+many\\s+requests\\b)");
    private static final java.util.regex.Pattern SERVER_ERROR = java.util.regex.Pattern.compile(
            "(?i)\\bHTTP\\s*5\\d\\d\\b");
    private static final java.util.regex.Pattern AUTH_ERROR = java.util.regex.Pattern.compile(
            "(?i)(?:\\bHTTP\\s*(?:401|403)\\b|\\bauth(?:entication|orization)?\\b|"
                    + "\\bunauthori[sz]ed\\b|\\bforbidden\\b|\\binvalid\\s+(?:api\\s*)?key\\b)");
    private static final java.util.regex.Pattern NETWORK_ERROR = java.util.regex.Pattern.compile(
            "(?i)(?:\\btime(?:d)?\\s*out\\b|\\btimeout\\b|\\bnetwork\\b|\\bconnection\\b|"
                    + "\\bconnect(?:ion)?\\s+(?:reset|refused|failed)\\b|\\bunknown\\s+host\\b|"
                    + "\\bdns\\b|\\bno\\s+route\\b|\\bsocket\\b)");
    private static final java.util.regex.Pattern ANCHOR_ERROR = java.util.regex.Pattern.compile(
            "(?i)(?:(?:\\banchor(?:ed)?\\b|\\border\\b).*(?:\\bdamag(?:e|ed)\\b|\\bmissing\\b|"
                    + "\\binvalid\\b|\\bmismatch\\b|\\breorder(?:ed)?\\b)|(?:\\bdamag(?:e|ed)\\b|"
                    + "\\bmissing\\b|\\binvalid\\b|\\bmismatch\\b|\\breorder(?:ed)?\\b).*"
                    + "(?:\\banchor(?:ed)?\\b|\\border\\b))");
    private static final java.util.regex.Pattern PARAGRAPH_ERROR = java.util.regex.Pattern.compile(
            "(?i)(?:(?:\\bparagraph\\b|\\bhard[\\s_-]*line\\b|\\bline[\\s_-]*break\\b|\\bPB\\d*\\b)"
                    + ".*(?:\\blost\\b|\\bmissing\\b|\\bdamag(?:e|ed)\\b|\\bmismatch\\b|\\binvalid\\b)"
                    + "|(?:\\blost\\b|\\bmissing\\b|\\bdamag(?:e|ed)\\b|\\bmismatch\\b|\\binvalid\\b).*"
                    + "(?:\\bparagraph\\b|\\bhard[\\s_-]*line\\b|\\bline[\\s_-]*break\\b|\\bPB\\d*\\b))");
    private static final java.util.regex.Pattern FORMAT_ERROR = java.util.regex.Pattern.compile(
            "(?i)(?:(?:\\bformat\\b|\\btoken\\b|\\bmarker\\b|\\bplaceholder\\b|\\bsentinel\\b)"
                    + ".*(?:\\blost\\b|\\bmissing\\b|\\bdamag(?:e|ed)\\b|\\bmismatch\\b|\\binvalid\\b)"
                    + "|(?:\\blost\\b|\\bmissing\\b|\\bdamag(?:e|ed)\\b|\\bmismatch\\b|\\binvalid\\b)"
                    + ".*(?:\\bformat\\b|\\btoken\\b|\\bmarker\\b|\\bplaceholder\\b|\\bsentinel\\b))");
    private static final java.util.regex.Pattern EMPTY_ERROR = java.util.regex.Pattern.compile(
            "(?i)(?:\\bempty\\s+(?:response|body|translation|result)\\b|\\bblank\\s+(?:response|body|translation|result)\\b|"
                    + "\\bno\\s+(?:choices?|content|translation|result)\\b)");

    public enum Status { IN_FLIGHT, SUCCESS, FALLBACK, KEEP_ORIGINAL, RATE_LIMITED, FAILED }

    public record Entry(long requestId, String engine, String text, String translation,
                        int batchSize, long submittedAtMs, Status status,
                        String failureReason) {
    }

    /** Status plus the stable, user-facing reason shown by the debug overlay. */
    public record Failure(Status status, String reason) {
        public Failure {
            status = status == null ? Status.FAILED : status;
            reason = normalizedFailureReason(status, reason);
        }
    }

    private final BooleanSupplier enabled;
    private final LongSupplier clock;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long nextRequestId;

    public TranslationDebugLog(BooleanSupplier enabled) {
        this(enabled, System::currentTimeMillis);
    }

    /** Clock-injecting constructor so {@link #IN_FLIGHT_STALE_MS} staleness is
     *  unit-testable without an actual multi-minute sleep. */
    public TranslationDebugLog(BooleanSupplier enabled, LongSupplier clock) {
        this.enabled = enabled == null ? () -> false : enabled;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Returns 0 when tracing is disabled. */
    public synchronized long submitted(String engine, List<String> texts) {
        if (!enabled.getAsBoolean() || texts == null || texts.isEmpty()) return 0L;
        long id = ++nextRequestId;
        long now = clock.getAsLong();
        for (String text : texts) {
            entries.addLast(new Entry(id, engine, text, null, texts.size(), now,
                    Status.IN_FLIGHT, null));
        }
        trim();
        return id;
    }

    public synchronized void completed(long requestId, boolean success) {
        completed(requestId, success ? Status.SUCCESS : Status.FAILED);
    }

    public synchronized void completed(long requestId, Status status) {
        String reason = normalizedFailureReason(status, null);
        completed(requestId, List.of(), List.of(status),
                reason == null ? List.of() : List.of(reason));
    }

    public synchronized void completed(long requestId, Failure failure) {
        Failure resolved = failure == null ? new Failure(Status.FAILED, "unknown") : failure;
        completed(requestId, List.of(), List.of(resolved.status()),
                resolved.reason() == null ? List.of() : List.of(resolved.reason()));
    }

    /** Completes every row in a batch in submission order, attaching the backend's
     *  corresponding translation to the original text. Missing translations stay null. */
    public synchronized void completed(long requestId, List<String> translations,
                                       List<Status> statuses) {
        completed(requestId, translations, statuses, List.of());
    }

    /** Completes a batch while retaining a per-item validation or transport failure. */
    public synchronized void completed(long requestId, List<String> translations,
                                       List<Status> statuses, List<String> failureReasons) {
        if (requestId == 0L) return;
        List<Entry> replaced = new ArrayList<>(entries.size());
        int item = 0;
        for (Entry entry : entries) {
            if (entry.requestId != requestId) {
                replaced.add(entry);
                continue;
            }
            String translation = translations != null && item < translations.size()
                    ? translations.get(item) : null;
            Status status = statuses != null && item < statuses.size()
                    ? statuses.get(item)
                    : statuses != null && !statuses.isEmpty() ? statuses.get(statuses.size() - 1)
                    : Status.FAILED;
            String failureReason = failureReasons != null && item < failureReasons.size()
                    ? failureReasons.get(item)
                    : failureReasons != null && !failureReasons.isEmpty()
                    ? failureReasons.get(failureReasons.size() - 1) : null;
            replaced.add(new Entry(entry.requestId, entry.engine, entry.text, translation,
                    entry.batchSize, entry.submittedAtMs, status,
                    normalizedFailureReason(status, failureReason)));
            reportFailure(entry, status, normalizedFailureReason(status, failureReason));
            item++;
        }
        entries.clear();
        entries.addAll(replaced);
    }

    /** Forget a submitted request that never reached its backend (new requests were
     *  switched off while it waited for a send slot). It is neither a failure nor a
     *  permanent "waiting" row, so its entries are simply removed. */
    public synchronized void discard(long requestId) {
        if (requestId == 0L) return;
        entries.removeIf(entry -> entry.requestId == requestId);
    }

    /** Newest first; completed rows automatically expire, and a row still IN_FLIGHT past
     *  {@link #IN_FLIGHT_STALE_MS} is resolved to a timeout first (see its javadoc) so it
     *  is never left showing "等待中" forever. */
    public synchronized List<Entry> snapshot(int limit) {
        if (!enabled.getAsBoolean()) return List.of();
        long now = clock.getAsLong();
        resolveStaleInFlight(now);
        long cutoff = now - COMPLETED_TTL_MS;
        entries.removeIf(entry -> entry.status != Status.IN_FLIGHT && entry.submittedAtMs < cutoff);
        List<Entry> result = new ArrayList<>(Math.min(Math.max(0, limit), entries.size()));
        var iterator = entries.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) result.add(iterator.next());
        return List.copyOf(result);
    }

    private void resolveStaleInFlight(long now) {
        boolean anyStale = false;
        for (Entry entry : entries) {
            if (isStaleInFlight(entry, now)) {
                anyStale = true;
                break;
            }
        }
        if (!anyStale) return;
        List<Entry> replaced = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (isStaleInFlight(entry, now)) reportFailure(entry, Status.FAILED, "timed out (no response)");
            // submittedAtMs is re-stamped to NOW (not kept at its original, long-past
            // submission time): otherwise this resolved row would be older than
            // COMPLETED_TTL_MS already and the ordinary completed-row sweep right below
            // would delete it in the very same snapshot() call that just resolved it --
            // the overlay would still show nothing, never an actual timeout reason.
            replaced.add(isStaleInFlight(entry, now)
                    ? new Entry(entry.requestId, entry.engine, entry.text, null,
                            entry.batchSize, now, Status.FAILED, "timed out (no response)")
                    : entry);
        }
        entries.clear();
        entries.addAll(replaced);
    }

    /** 偵錯模式's error log: a request that failed is written there (nothing else is). */
    private static void reportFailure(Entry entry, Status status, String reason) {
        if (status != Status.FAILED && status != Status.RATE_LIMITED) return;
        DebugErrorLog.report(DebugErrorLog.typeForReason(reason), reason == null ? "unknown" : reason,
                "engine", entry.engine, "request", Long.toString(entry.requestId),
                "batch", Integer.toString(entry.batchSize), "text", entry.text == null ? "" : entry.text);
    }

    private static boolean isStaleInFlight(Entry entry, long now) {
        return entry.status == Status.IN_FLIGHT
                && now - entry.submittedAtMs >= IN_FLIGHT_STALE_MS;
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Classify backend failures without depending on a particular transport wrapper.
     * Every cause message is inspected because HTTP errors are commonly wrapped in one
     * or more TranslationExceptions before reaching the cache. */
    public static Status statusFor(Throwable error) {
        return failureFor(error).status();
    }

    public static Failure failureFor(Throwable error) {
        List<String> messages = new ArrayList<>();
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 32; depth++, cursor = cursor.getCause()) {
            String message = cursor.getMessage();
            if (message != null) messages.add(message);
            if (cursor.getCause() == cursor) break;
        }
        String combined = String.join(" | ", messages);
        if (RATE_LIMITED_ERROR.matcher(combined).find()) {
            return new Failure(Status.RATE_LIMITED, "429 rate limit");
        }
        if (SERVER_ERROR.matcher(combined).find()) return new Failure(Status.FAILED, "HTTP 5xx");
        if (AUTH_ERROR.matcher(combined).find()) return new Failure(Status.FAILED, "authentication");

        cursor = error;
        for (int depth = 0; cursor != null && depth < 32; depth++, cursor = cursor.getCause()) {
            if (isNetworkFailure(cursor)) return new Failure(Status.FAILED, "timeout/network");
            if (cursor.getCause() == cursor) break;
        }
        if (NETWORK_ERROR.matcher(combined).find()) return new Failure(Status.FAILED, "timeout/network");
        if (ANCHOR_ERROR.matcher(combined).find()) return new Failure(Status.FAILED, "anchor/order damaged");
        if (PARAGRAPH_ERROR.matcher(combined).find()) return new Failure(Status.FAILED, "paragraph lost");
        if (FORMAT_ERROR.matcher(combined).find()) return new Failure(Status.FAILED, "format/token lost");
        if (EMPTY_ERROR.matcher(combined).find()) return new Failure(Status.FAILED, "empty response");
        return new Failure(Status.FAILED, "unknown");
    }

    private static boolean isNetworkFailure(Throwable error) {
        return error instanceof java.net.SocketTimeoutException
                || error instanceof java.net.ConnectException
                || error instanceof java.net.UnknownHostException
                || error instanceof java.net.NoRouteToHostException
                || error instanceof java.net.SocketException
                || error instanceof java.io.InterruptedIOException
                || error instanceof java.util.concurrent.TimeoutException;
    }

    private static String normalizedFailureReason(Status status, String reason) {
        if (status != Status.FAILED && status != Status.RATE_LIMITED) return null;
        if (reason != null && !reason.isBlank()) return reason.strip();
        return status == Status.RATE_LIMITED ? "429 rate limit" : "unknown";
    }

    /** Human-readable projection for the in-game overlay. Internal style markers have
     *  diagnostic value in files/tests, but displaying them makes one short request fill
     *  half the screen. Dynamic and player slots remain visible as concise labels. */
    public static String compactText(String text) {
        if (text == null || text.isBlank()) return "";
        String out = CS_MARKER.matcher(text).replaceAll("");
        out = MT_SLOT.matcher(out).replaceAll("{值}");
        out = WS_SLOT.matcher(out).replaceAll(" {欄距} ");
        out = NAME_SLOT.matcher(out).replaceAll("{玩家}");
        out = SECTION_CODE.matcher(out).replaceAll("");
        return WHITESPACE.matcher(out).replaceAll(" ").strip();
    }

    private void trim() {
        while (entries.size() > CAPACITY) entries.removeFirst();
    }
}
