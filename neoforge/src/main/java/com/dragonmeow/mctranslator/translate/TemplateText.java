package com.dragonmeow.mctranslator.translate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises volatile fragments (numbers, times, URLs, UUIDs, player names) into
 * stable {@code ⟦MT#⟧} placeholder tokens before translation, and restores them
 * afterwards. "You got 5 coins" and "You got 99 coins" then share ONE cached
 * translation ("You got ⟦MT0⟧ coins"), which is the single biggest request saver
 * for server chat / scoreboards where only the numbers change.
 *
 * <p>{@link #prepare} results are memoised: it runs on the render path for cache
 * misses, and the pattern set is regex-heavy.</p>
 */
public final class TemplateText {

    private static final char OPEN = '⟦';   // ⟦
    private static final char CLOSE = '⟧';  // ⟧
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    // Progress-bar / divider runs ("──────", "----"): pure formatting the model must not
    // touch — and long runs confuse batch line alignment if sent raw.
    private static final Pattern BAR = Pattern.compile("[─━—=\\-]{4,}");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b");
    // Countdown / duration runs ("59s", "10min", "2h 30m", "1m30s", "1天2小時3分30秒"):
    // scoreboards tick these every second, so an untemplated duration mints a brand-new
    // request key each tick — the single worst request-storm (429) driver. The whole run
    // collapses into ONE token; the trailing (?![A-Za-z]) keeps ordinary words safe
    // ("5 may" / "3rd" never match: the unit letter must not be followed by a letter).
    private static final String DURATION_UNIT_EN =
            "(?:d|days?|h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)";
    private static final Pattern DURATION_EN = Pattern.compile(
            "(?i)(?<![A-Za-z_⟦])\\d+(?:[.,]\\d+)?\\s*" + DURATION_UNIT_EN + "(?![A-Za-z])"
                    + "(?:\\s*\\d+(?:[.,]\\d+)?\\s*" + DURATION_UNIT_EN + "(?![A-Za-z]))*");
    private static final Pattern DURATION_CJK = Pattern.compile(
            "(?<![A-Za-z_⟦])(?:\\d+(?:[.,]\\d+)?\\s*(?:天|日|小時|時|分鐘|分|秒))+");
    // Digits adjacent to ⟦…⟧ are inside a NameMasker/TemplateText token — never re-template those.
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_⟦])[-+]?\\d+(?:[.,]\\d+)*(?:[%％]|[kKmMbB])?(?![A-Za-z_⟧])");
    private static final Pattern LEADING_PLAYER = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]{2,16})(?=\\s+(?:joined|left|quit|was|has|is|died|fell|burned|drowned|suffocated|blew|tried|hit|lost|won|teleported|moved|voted|claimed|unclaimed|entered|exited|discovered|found|picked|dropped|sold|bought|paid|received|earned|made|completed|reached|killed|slain|shot|whispered|says|said)\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_PLAYER = Pattern.compile("\\b(?:by|from|to|with|for)\\s+([A-Za-z_][A-Za-z0-9_]{2,16})\\b");

    // prepare() is called per render-frame for cache misses; memoise so the regex
    // sweep runs once per distinct string. Prepared is immutable, so sharing is safe.
    private static final int MEMO_MAX = 2048;
    private static final ConcurrentHashMap<String, Prepared> MEMO = new ConcurrentHashMap<>();

    private TemplateText() {
    }

    public record Prepared(String text, List<String> values) {
        public boolean changed() {
            return !values.isEmpty();
        }

        /** Substitute the original values back into a translated template. Tolerates the
         *  translator inserting spaces around/inside the token (common with CJK output). */
        public String restore(String translated) {
            if (translated == null || values.isEmpty()) return translated;
            String out = translated;
            for (int i = 0; i < values.size(); i++) {
                out = out.replace(token(i), values.get(i));
                out = out.replace(OPEN + " MT" + i + " " + CLOSE, values.get(i));
                out = out.replace(OPEN + "MT" + i + " " + CLOSE, values.get(i));
                out = out.replace(OPEN + " MT" + i + CLOSE, values.get(i));
            }
            return tightenCjkSpacing(out);
        }
    }

    private static final Pattern CJK_BEFORE_NUM = Pattern.compile("(?<=[\\u4e00-\\u9fff，。！？：])[ \\u00A0]+(?=[0-9+\\-])");
    private static final Pattern NUM_BEFORE_CJK = Pattern.compile("(?<=[0-9%％.,kKmMbB])[ \\u00A0]+(?=[\\u4e00-\\u9fff，。！？：])");

    /**
     * CJK typography: no space between a number and an adjacent CJK character. The
     * translator is inconsistent about spacing around restored tokens ("擊中了1 敵人" vs
     * "擊中2敵人"); normalising here makes every restored message read the same way.
     * No-op for non-CJK output (the patterns require a CJK neighbour).
     */
    static String tightenCjkSpacing(String text) {
        if (text == null || text.indexOf(' ') < 0) return text;
        return NUM_BEFORE_CJK.matcher(CJK_BEFORE_NUM.matcher(text).replaceAll("")).replaceAll("");
    }

    public static Prepared prepare(String source) {
        if (source == null || source.isEmpty()) return new Prepared(source, List.of());
        Prepared hit = MEMO.get(source);
        if (hit != null) return hit;
        Prepared computed = compute(source);
        if (MEMO.size() >= MEMO_MAX) MEMO.clear(); // crude but O(1); refills fast
        MEMO.put(source, computed);
        return computed;
    }

    private static Prepared compute(String source) {
        List<Span> spans = new ArrayList<>();
        addPattern(source, spans, BAR, 0, false);
        addPattern(source, spans, URL, 0, false);
        addPattern(source, spans, UUID, 0, false);
        addPattern(source, spans, TIME, 0, false);
        addPattern(source, spans, DURATION_EN, 0, false);
        addPattern(source, spans, DURATION_CJK, 0, false);
        addPattern(source, spans, LEADING_PLAYER, 1, false);
        addPattern(source, spans, TARGET_PLAYER, 1, true);
        addPattern(source, spans, NUMBER, 0, false);
        if (spans.isEmpty()) return new Prepared(source, List.of());
        // Earlier patterns win on overlap (URL beats the numbers inside it, a duration
        // run beats the bare number at its head, etc.): spans are already collected in
        // pattern order, so accepting them in INSERTION order is the priority rule —
        // sorting by position first would let a shorter same-start NUMBER match beat
        // the DURATION/UUID span that contains it.
        List<Span> accepted = new ArrayList<>();
        for (Span span : spans) {
            boolean overlaps = false;
            for (Span existing : accepted) {
                if (span.start < existing.end && existing.start < span.end) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) accepted.add(span);
        }
        if (accepted.isEmpty()) return new Prepared(source, List.of());
        accepted.sort(Comparator.comparingInt(s -> s.start));
        StringBuilder out = new StringBuilder(source.length());
        List<String> values = new ArrayList<>();
        int pos = 0;
        for (Span span : accepted) {
            if (span.start < pos) continue;
            out.append(source, pos, span.start);
            int index = values.size();
            values.add(source.substring(span.start, span.end));
            out.append(token(index));
            pos = span.end;
        }
        out.append(source, pos, source.length());
        return values.isEmpty() ? new Prepared(source, List.of()) : new Prepared(out.toString(), List.copyOf(values));
    }

    private static void addPattern(String source, List<Span> spans, Pattern pattern, int group, boolean capitalizedOnly) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int start = matcher.start(group);
            int end = matcher.end(group);
            if (start < 0 || end <= start) continue;
            if (capitalizedOnly && !looksLikePlayerName(source.substring(start, end))) continue;
            spans.add(new Span(start, end));
        }
    }

    private static boolean looksLikePlayerName(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isUpperCase(c) || Character.isDigit(c) || c == '_') return true;
        }
        return false;
    }

    private static String token(int index) {
        return OPEN + "MT" + index + CLOSE;
    }

    private record Span(int start, int end) {
    }
}
