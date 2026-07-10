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

    public enum Status { IN_FLIGHT, SUCCESS, FALLBACK, KEEP_ORIGINAL, FAILED }

    public record Entry(long requestId, String engine, String text, String translation,
                        int batchSize, long submittedAtMs, Status status) {
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
            entries.addLast(new Entry(id, engine, text, null, texts.size(), now, Status.IN_FLIGHT));
        }
        trim();
        return id;
    }

    public synchronized void completed(long requestId, boolean success) {
        completed(requestId, success ? Status.SUCCESS : Status.FAILED);
    }

    public synchronized void completed(long requestId, Status status) {
        completed(requestId, List.of(), List.of(status));
    }

    /** Completes every row in a batch in submission order, attaching the backend's
     *  corresponding translation to the original text. Missing translations stay null. */
    public synchronized void completed(long requestId, List<String> translations,
                                       List<Status> statuses) {
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
            replaced.add(new Entry(entry.requestId, entry.engine, entry.text, translation,
                    entry.batchSize, entry.submittedAtMs, status));
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
