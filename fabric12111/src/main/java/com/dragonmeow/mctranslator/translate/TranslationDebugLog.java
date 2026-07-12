package com.dragonmeow.mctranslator.translate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Thread-safe bounded trace of requests that actually reached a backend. */
public final class TranslationDebugLog {
    private static final int CAPACITY = 80;
    private static final long COMPLETED_TTL_MS = 20_000L;
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
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long nextRequestId;

    public TranslationDebugLog(BooleanSupplier enabled) {
        this.enabled = enabled == null ? () -> false : enabled;
    }

    /** Returns 0 when tracing is disabled. */
    public synchronized long submitted(String engine, List<String> texts) {
        if (!enabled.getAsBoolean() || texts == null || texts.isEmpty()) return 0L;
        long id = ++nextRequestId;
        long now = System.currentTimeMillis();
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
            item++;
        }
        entries.clear();
        entries.addAll(replaced);
    }

    /** Newest first; completed rows automatically expire. */
    public synchronized List<Entry> snapshot(int limit) {
        if (!enabled.getAsBoolean()) return List.of();
        long cutoff = System.currentTimeMillis() - COMPLETED_TTL_MS;
        entries.removeIf(entry -> entry.status != Status.IN_FLIGHT && entry.submittedAtMs < cutoff);
        List<Entry> result = new ArrayList<>(Math.min(Math.max(0, limit), entries.size()));
        var iterator = entries.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) result.add(iterator.next());
        return List.copyOf(result);
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
