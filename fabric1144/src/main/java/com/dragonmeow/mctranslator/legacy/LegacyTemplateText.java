package com.dragonmeow.mctranslator.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-8 placeholder normalisation used by the legacy translation pipeline.
 * Volatile values are removed before cache lookup/request dispatch and restored
 * independently for every caller sharing the canonical request.
 */
final class LegacyTemplateText {
    private static final char OPEN = '\u27E6';
    private static final char CLOSE = '\u27E7';
    private static final char SLOT_SPACE = '\u0001';
    private static final char SLOT_TAB = '\u0002';
    private static final char SLOT_NBSP = '\u0003';

    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:(?:https?://|www\\.)\\S+"
                    + "|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z]{2,24}(?::\\d{1,5})?(?:/\\S*)?)");
    private static final Pattern BAR = Pattern.compile("[─━—=\\-]{4,}");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final String DYNAMIC_START = "(?:(?<=§.)|(?<![A-Za-z0-9_⟦§]))";
    private static final Pattern SCOREBOARD_DATE_SHARD = Pattern.compile(
            "(?i)" + DYNAMIC_START + "\\d{1,2}/\\d{1,2}/\\d{2,4}\\s+"
                    + "(?=[A-Za-z][A-Za-z0-9_-]{2,11}(?![A-Za-z0-9_-]))"
                    + "(?=[A-Za-z0-9_-]*\\d)[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern TIME = Pattern.compile(
            "(?i)" + DYNAMIC_START + "\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[ap]\\.?m\\.?)?(?![A-Za-z_⟧])");
    private static final String DURATION_UNIT_EN =
            "(?:d|days?|h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)";
    private static final Pattern DURATION_EN = Pattern.compile(
            "(?i)" + DYNAMIC_START + "\\d+(?:[.,]\\d+)?\\s*" + DURATION_UNIT_EN + "(?![A-Za-z])"
                    + "(?:\\s*\\d+(?:[.,]\\d+)?\\s*" + DURATION_UNIT_EN + "(?![A-Za-z]))*");
    private static final Pattern DURATION_CJK = Pattern.compile(
            DYNAMIC_START + "(?:\\d+(?:[.,]\\d+)?\\s*(?:天|日|小時|時|分鐘|分|秒))+");
    private static final Pattern ORDINAL = Pattern.compile(
            "(?i)" + DYNAMIC_START + "\\d+(?:st|nd|rd|th)(?![A-Za-z_\\u27E7])");

    // Prefix quantities are a complete slot. The boundaries deliberately keep
    // dimensions, hex and glued identifiers (2x2, 0x1F, x100kg) untouched.
    private static final Pattern PREFIX_QUANTITY = Pattern.compile(DYNAMIC_START
            + "(?>[xX]\\d+(?:[.,]\\d+)*(?:[%％]|[kKmMbB])?)(?![A-Za-z_⟧])");
    private static final Pattern NUMBER = Pattern.compile(DYNAMIC_START
            + "(?>[-+]?\\d+(?:[.,]\\d+)*(?:[%％]|[kKmMbB]|[xX](?![0-9A-Za-z_⟧]))?)(?![A-Za-z_⟧])");
    private static final Pattern SYMBOL_RUN = Pattern.compile("[\\p{So}\\p{Co}\\p{Cs}&&[^§⟦⟧]]+");
    private static final Pattern RANK_TAG = Pattern.compile("\\[[A-Z]{2,10}\\+{0,2}\\]");
    private static final Pattern CS_TOKEN = Pattern.compile("\\u27E6\\s*(/?)\\s*CS\\s*\\d+\\s*\\u27E7");
    private static final Pattern RESERVED_MT_TOKEN = Pattern.compile(
            "\\u27E6\\s*MT\\s*(\\d+)\\s*\\u27E7", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMAT_CODE = Pattern.compile("§.", Pattern.DOTALL);
    private static final Pattern SERVER_INSTANCE = Pattern.compile(
            "(?i)(?:\\bserver\\s*:|伺服器\\s*[：:])"
                    + "(?:\\s|§.|⟦\\s*/?\\s*CS\\s*\\d+\\s*⟧)*"
                    + "([A-Za-z0-9][A-Za-z0-9_.-]*)");

    // restore() runs per render frame on cache HITS (scoreboard rows, tooltips, name
    // tags). Its per-slot regex is fully determined by (leadingSpace, trailingSpace,
    // slotIndex), so the compiled Pattern is shared instead of recompiled every call.
    // Pattern is immutable/thread-safe; the bound only guards against unbounded growth.
    private static final int PATTERN_CACHE_MAX = 4096;
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private static Pattern cachedPattern(String regex) {
        Pattern cached = PATTERN_CACHE.get(regex);
        if (cached != null) return cached;
        if (PATTERN_CACHE.size() >= PATTERN_CACHE_MAX) PATTERN_CACHE.clear();
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    private static final int MEMO_MAX = 2048;
    private static final Map<String, Prepared> MEMO = Collections.synchronizedMap(
            new LinkedHashMap<String, Prepared>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Prepared> eldest) {
                    return size() > MEMO_MAX;
                }
            });

    private static final Pattern CJK_BEFORE_NUM = Pattern.compile(
            "(?<=[\\u4e00-\\u9fff，。！？：])[ \\u00A0](?=[0-9+\\-])");
    private static final Pattern NUM_BEFORE_CJK = Pattern.compile(
            "(?<=[0-9%％.,kKmMbB])[ \\u00A0](?=[\\u4e00-\\u9fff，。！？：])");
    private static final Pattern FW_COMMA_IN_NUMBER = Pattern.compile("(?<=[0-9])，(?=[0-9])");
    private static final Pattern FW_DOT_IN_NUMBER = Pattern.compile("(?<=[0-9])．(?=[0-9])");

    private LegacyTemplateText() {}

    static final class Prepared {
        private final String text;
        private final List<String> values;
        private final List<Integer> slotIndices;

        Prepared(String text, List<String> values, List<Integer> slotIndices) {
            this.text = text;
            this.values = values;
            this.slotIndices = slotIndices;
        }

        String text() { return text; }
        List<String> values() { return values; }
        boolean changed() { return !values.isEmpty(); }

        boolean hasTranslatableContent() {
            if (text == null || text.isEmpty()) return false;
            String skeleton = FORMAT_CODE.matcher(
                    RESERVED_MT_TOKEN.matcher(text).replaceAll("")).replaceAll("");
            for (int i = 0; i < skeleton.length(); i++) {
                if (Character.isLetter(skeleton.charAt(i))) return true;
            }
            return false;
        }

        String restore(String translated) {
            if (translated == null || values.isEmpty()) return translated;
            String out = translated;
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i);
                int slotIndex = slotIndices.get(i).intValue();
                String visible = CS_TOKEN.matcher(value).replaceAll("");
                boolean leadingSpace = !visible.isEmpty() && Character.isWhitespace(visible.charAt(0));
                boolean trailingSpace = !visible.isEmpty()
                        && Character.isWhitespace(visible.charAt(visible.length() - 1));
                String regex = (leadingSpace ? "[ \\t\\u00A0]*" : "")
                        + "\\u27E6\\s*MT\\s*" + slotIndex + "\\s*\\u27E7"
                        + (trailingSpace ? "[ \\t\\u00A0]*" : "");
                String protectedValue = value.replace(' ', SLOT_SPACE)
                        .replace('\t', SLOT_TAB).replace('\u00A0', SLOT_NBSP);

                String token = OPEN + "MT" + slotIndex + String.valueOf(CLOSE);
                int at = text.indexOf(token);
                boolean hadSpaceBefore = at > 0 && isHorizontalSpace(text.charAt(at - 1));
                boolean hadSpaceAfter = at >= 0 && at + token.length() < text.length()
                        && isHorizontalSpace(text.charAt(at + token.length()));

                Matcher matcher = cachedPattern(regex).matcher(out);
                StringBuilder rebuilt = new StringBuilder(out.length() + 8);
                int last = 0;
                while (matcher.find()) {
                    String replacement = protectedValue;
                    if (hadSpaceBefore && !leadingSpace && matcher.start() > 0
                            && isAsciiVisible(out.charAt(matcher.start() - 1))) {
                        replacement = SLOT_SPACE + replacement;
                    }
                    if (hadSpaceAfter && !trailingSpace && matcher.end() < out.length()
                            && isAsciiVisible(out.charAt(matcher.end()))) {
                        replacement = replacement + SLOT_SPACE;
                    }
                    rebuilt.append(out, last, matcher.start()).append(replacement);
                    last = matcher.end();
                }
                rebuilt.append(out, last, out.length());
                out = rebuilt.toString();
            }
            return tightenCjkSpacing(out)
                    .replace(SLOT_SPACE, ' ')
                    .replace(SLOT_TAB, '\t')
                    .replace(SLOT_NBSP, '\u00A0');
        }
    }

    static Prepared prepare(String source) {
        if (source == null || source.isEmpty()) return empty(source);
        Prepared hit = MEMO.get(source);
        if (hit != null) return hit;
        Prepared computed = compute(source);
        MEMO.put(source, computed);
        return computed;
    }

    private static Prepared compute(String source) {
        List<Span> spans = new ArrayList<Span>();
        List<Span> protectedTokens = reservedTokenSpans(source);
        addPattern(source, spans, BAR, 0);
        addPattern(source, spans, URL, 0);
        addPattern(source, spans, UUID, 0);
        addPattern(source, spans, SCOREBOARD_DATE_SHARD, 0);
        addPattern(source, spans, TIME, 0);
        addPattern(source, spans, DURATION_EN, 0);
        addPattern(source, spans, DURATION_CJK, 0);
        addPattern(source, spans, ORDINAL, 0);
        addPattern(source, spans, SERVER_INSTANCE, 1);
        addPattern(source, spans, PREFIX_QUANTITY, 0);
        addPattern(source, spans, NUMBER, 0);
        addPattern(source, spans, SYMBOL_RUN, 0);
        addPattern(source, spans, RANK_TAG, 0);
        if (spans.isEmpty()) return empty(source);

        List<Span> accepted = new ArrayList<Span>();
        for (Span span : spans) {
            boolean overlaps = overlapsAny(span, protectedTokens);
            for (Span existing : accepted) {
                if (span.start < existing.end && existing.start < span.end) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) accepted.add(span);
        }
        if (accepted.isEmpty()) return empty(source);
        Collections.sort(accepted, new Comparator<Span>() {
            @Override public int compare(Span left, Span right) {
                return left.start < right.start ? -1 : left.start == right.start ? 0 : 1;
            }
        });

        StringBuilder out = new StringBuilder(source.length());
        List<String> values = new ArrayList<String>();
        List<Integer> slotIndices = new ArrayList<Integer>();
        Set<Integer> reserved = reservedSlotIndices(source);
        int nextSlot = 0;
        int pos = 0;
        for (Span span : accepted) {
            if (span.start < pos) continue;
            out.append(source, pos, span.start);
            while (reserved.contains(Integer.valueOf(nextSlot))) nextSlot++;
            int index = nextSlot++;
            reserved.add(Integer.valueOf(index));
            values.add(source.substring(span.start, span.end));
            slotIndices.add(Integer.valueOf(index));
            out.append(token(index));
            pos = span.end;
        }
        out.append(source, pos, source.length());
        if (values.isEmpty()) return empty(source);
        return new Prepared(out.toString(),
                Collections.unmodifiableList(new ArrayList<String>(values)),
                Collections.unmodifiableList(new ArrayList<Integer>(slotIndices)));
    }

    private static Prepared empty(String source) {
        return new Prepared(source, Collections.<String>emptyList(),
                Collections.<Integer>emptyList());
    }

    private static Set<Integer> reservedSlotIndices(String source) {
        Set<Integer> reserved = new HashSet<Integer>();
        Matcher matcher = RESERVED_MT_TOKEN.matcher(source);
        while (matcher.find()) {
            try {
                long value = Long.parseLong(matcher.group(1));
                if (value <= Integer.MAX_VALUE) reserved.add(Integer.valueOf((int) value));
            } catch (NumberFormatException ignored) {}
        }
        return reserved;
    }

    private static List<Span> reservedTokenSpans(String source) {
        List<Span> spans = new ArrayList<Span>();
        Matcher matcher = RESERVED_MT_TOKEN.matcher(source);
        while (matcher.find()) spans.add(new Span(matcher.start(), matcher.end()));
        return spans;
    }

    private static boolean overlapsAny(Span span, List<Span> others) {
        for (Span other : others) {
            if (span.start < other.end && other.start < span.end) return true;
        }
        return false;
    }

    private static void addPattern(String source, List<Span> spans, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int start = matcher.start(group);
            int end = matcher.end(group);
            if (start >= 0 && end > start) spans.add(new Span(start, end));
        }
    }

    private static String tightenCjkSpacing(String text) {
        if (text == null) return null;
        String out = text;
        if (out.indexOf('，') >= 0) out = FW_COMMA_IN_NUMBER.matcher(out).replaceAll(",");
        if (out.indexOf('．') >= 0) out = FW_DOT_IN_NUMBER.matcher(out).replaceAll(".");
        if (out.indexOf(' ') < 0) return out;
        return NUM_BEFORE_CJK.matcher(CJK_BEFORE_NUM.matcher(out).replaceAll("")).replaceAll("");
    }

    private static String token(int index) { return OPEN + "MT" + index + CLOSE; }
    private static boolean isAsciiVisible(char value) { return value >= '!' && value <= '~'; }
    private static boolean isHorizontalSpace(char value) {
        return value == ' ' || value == '\t' || value == '\u00A0';
    }

    private static final class Span {
        final int start, end;
        Span(int start, int end) { this.start = start; this.end = end; }
    }
}
