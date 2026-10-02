package com.dragonmeow.nyanlex.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /*
     * Do-not-translate terms become ordinary MT slots: every GT/AI/experimental transport
     * already carries a slot as a verified numeric sentinel, a lost slot fails as a transient
     * "format/token lost", and restore() writes back the exact source spelling. The masked
     * text is the cache key, so adding/removing a term takes effect without clearing caches.
     */
    private static final int TERM_MATCHERS_MAX = 8;
    private static final Map<List<String>, TermMatcher> TERM_MATCHERS = Collections.synchronizedMap(
            new LinkedHashMap<List<String>, TermMatcher>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<List<String>, TermMatcher> eldest) {
                    return size() > TERM_MATCHERS_MAX;
                }
            });
    /** ASCII only, never case-folded (so U+017F ſ or U+212A K is not a word character). */
    private static final String TERM_WORD_CHAR = "(?-i:[A-Za-z0-9_])";
    private static final Comparator<Span> LONGEST_FIRST = new Comparator<Span>() {
        @Override public int compare(Span left, Span right) {
            int leftLength = left.end - left.start;
            int rightLength = right.end - right.start;
            if (leftLength != rightLength) return leftLength > rightLength ? -1 : 1;
            return left.start < right.start ? -1 : left.start == right.start ? 0 : 1;
        }
    };

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
        Prepared computed = compute(source, null);
        MEMO.put(source, computed);
        return computed;
    }

    /** Same as {@link #prepare(String)}, additionally masking do-not-translate terms. */
    static Prepared prepare(String source, List<String> doNotTranslateTerms) {
        TermMatcher terms = termMatcher(doNotTranslateTerms);
        if (terms == null) return prepare(source);
        if (source == null || source.isEmpty()) return empty(source);
        Prepared hit = terms.memo.get(source);
        if (hit != null) return hit;
        Prepared computed = compute(source, terms);
        terms.memo.put(source, computed);
        return computed;
    }

    private static TermMatcher termMatcher(List<String> terms) {
        if (terms == null || terms.isEmpty()) return null;
        TermMatcher matcher = TERM_MATCHERS.get(terms);
        if (matcher == null) {
            List<String> key = Collections.unmodifiableList(new ArrayList<String>(terms));
            matcher = new TermMatcher(key);
            TERM_MATCHERS.put(key, matcher);
        }
        return matcher.patterns.isEmpty() ? null : matcher;
    }

    private static Prepared compute(String source, TermMatcher terms) {
        List<Span> spans = new ArrayList<Span>();
        List<Span> protectedTokens = reservedTokenSpans(source);
        addPattern(source, spans, BAR, 0);
        addPattern(source, spans, URL, 0);
        addPattern(source, spans, UUID, 0);
        int termsAt = spans.size();
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
        // Terms rank after URL/UUID (hypixel.net stays one URL slot) and before the dynamic
        // patterns, but only where a term fully covers every dynamic span it touches: a term
        // never cuts a number/duration/rank tag apart, so re-preparing stays idempotent.
        if (terms != null) {
            List<Span> termSpans = terms.spans(source, spans.subList(termsAt, spans.size()));
            spans.addAll(termsAt, termSpans);
        }
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

    /**
     * Compiled do-not-translate matcher for one term list (same rules as the modern
     * DoNotTranslateMatcher). Case-insensitive. A first/last word edge that is an ASCII
     * letter/digit/underscore needs a word boundary: the neighbour must not be an ASCII
     * letter/digit/underscore, and a position right after a complete {@code §x} code counts
     * as a boundary. Words match across one or more horizontal spaces; overlapping matches
     * keep the longest; nothing inside a {@code ⟦…⟧} token. Terms holding ⟦ ⟧ § are ignored.
     */
    private static final class TermMatcher {
        final List<Pattern> patterns;
        final Map<String, Prepared> memo = Collections.synchronizedMap(
                new LinkedHashMap<String, Prepared>(256, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(Map.Entry<String, Prepared> eldest) {
                        return size() > MEMO_MAX;
                    }
                });

        TermMatcher(List<String> terms) {
            List<Pattern> compiled = new ArrayList<Pattern>();
            Set<String> seen = new HashSet<String>();
            for (String term : LegacyConfig.normalizeDoNotTranslateTerms(terms)) {
                List<String> words = termWords(term);
                if (words.isEmpty()) continue;
                StringBuilder key = new StringBuilder();
                for (String word : words) {
                    if (key.length() > 0) key.append(' ');
                    key.append(word);
                }
                if (seen.add(key.toString().toLowerCase(Locale.ROOT))) compiled.add(termPattern(words));
            }
            patterns = Collections.unmodifiableList(compiled);
        }

        /** Term spans, longest first, that neither enter a ⟦…⟧ token nor cut a dynamic span. */
        List<Span> spans(String source, List<Span> dynamic) {
            List<Span> tokens = bracketTokenSpans(source);
            List<Span> found = new ArrayList<Span>();
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    Span span = new Span(matcher.start(), matcher.end());
                    if (span.end > span.start && !overlapsAny(span, tokens)
                            && coversEveryTouched(span, dynamic)) found.add(span);
                }
            }
            Collections.sort(found, LONGEST_FIRST);
            return found;
        }
    }

    /** A term may replace dynamic spans it covers completely; otherwise the dynamic span wins. */
    private static boolean coversEveryTouched(Span term, List<Span> dynamic) {
        for (Span other : dynamic) {
            if (term.start < other.end && other.start < term.end
                    && (other.start < term.start || other.end > term.end)) return false;
        }
        return true;
    }

    /** Words of a term split on any Unicode space; empty when the term holds ⟦ ⟧ or §. */
    private static List<String> termWords(String term) {
        List<String> words = new ArrayList<String>();
        for (int i = 0; i < term.length(); i++) {
            char value = term.charAt(i);
            if (value == OPEN || value == CLOSE || value == '§') return words;
        }
        int i = 0;
        while (i < term.length()) {
            while (i < term.length() && isTermSpace(term.charAt(i))) i++;
            int start = i;
            while (i < term.length() && !isTermSpace(term.charAt(i))) i++;
            if (i > start) words.add(term.substring(start, i));
        }
        return words;
    }

    private static Pattern termPattern(List<String> words) {
        String first = words.get(0);
        String last = words.get(words.size() - 1);
        StringBuilder regex = new StringBuilder("(?<!§)");
        if (isAsciiWordChar(first.charAt(0))) {
            regex.append("(?:(?<=§.)|(?<!").append(TERM_WORD_CHAR).append("))");
        }
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) regex.append("\\h+");
            regex.append(Pattern.quote(words.get(i)));
        }
        if (isAsciiWordChar(last.charAt(last.length() - 1))) {
            regex.append("(?!").append(TERM_WORD_CHAR).append(')');
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Well-formed {@code ⟦…⟧} runs (protocol tokens or literal brackets) are never term text. */
    private static List<Span> bracketTokenSpans(String source) {
        List<Span> tokens = new ArrayList<Span>();
        int open = source.indexOf(OPEN);
        while (open >= 0) {
            int close = -1;
            for (int j = open + 1; j < source.length(); j++) {
                char value = source.charAt(j);
                if (value == CLOSE) {
                    close = j;
                    break;
                }
                if (value == OPEN) break;
            }
            if (close < 0) {
                open = source.indexOf(OPEN, open + 1);
            } else {
                tokens.add(new Span(open, close + 1));
                open = source.indexOf(OPEN, close + 1);
            }
        }
        return tokens;
    }

    private static boolean isTermSpace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    private static boolean isAsciiWordChar(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9' || value == '_';
    }
}
