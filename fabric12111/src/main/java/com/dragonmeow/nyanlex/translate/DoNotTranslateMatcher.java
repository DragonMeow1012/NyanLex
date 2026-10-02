package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Compiled do-not-translate (不翻譯詞彙) term list. {@link NameMasker} masks its matches
 * together with player names in one pass, so every protected span gets its own
 * {@code ⟦n⟧} placeholder and the original spelling is restored after translation.
 *
 * <p>Matching rules:</p>
 * <ul>
 *   <li>Case-insensitive; the ORIGINAL spelling found in the text is what gets restored.</li>
 *   <li>Whole words: when a term starts (ends) with an ASCII letter, digit or {@code _},
 *       the neighbouring character before (after) the match must not be one of those,
 *       so {@code skyblock} never matches inside {@code Skyfoo} or {@code skyblocks}.
 *       A position right after a literal {@code §x} format code counts as a word start.
 *       Terms that start/end with any other character (for example CJK) need no boundary.</li>
 *   <li>Words of a multi-word term match across one or more horizontal whitespace
 *       characters. Overlapping matches are resolved longest-first by {@link NameMasker}.</li>
 *   <li>Never inside or across a {@code ⟦…⟧} protocol token (a term split over two colour
 *       runs is therefore not masked), and never inside a URL/domain fragment, which
 *       {@link TemplateText} keeps verbatim on its own.</li>
 * </ul>
 */
public final class DoNotTranslateMatcher {

    public static final DoNotTranslateMatcher EMPTY =
            new DoNotTranslateMatcher(Collections.emptyList(), new Term[0]);

    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';
    private static final char SECTION = '§';

    /** Exact configured list this matcher was compiled from (change detection only). */
    private final List<String> source;
    private final Term[] terms;
    /** Terms bucketed by their case-folded first character, longest first. */
    private final Term[][] asciiBuckets = new Term[128][];
    private final Map<Character, Term[]> otherBuckets;

    private static final class Term {
        final String[] words;
        final String key;
        final int length;
        final boolean wordStart;
        final boolean wordEnd;

        Term(String[] words) {
            this.words = words;
            this.key = String.join(" ", words).toLowerCase(Locale.ROOT);
            this.length = key.length();
            String first = words[0];
            String last = words[words.length - 1];
            this.wordStart = isAsciiWordChar(first.charAt(0));
            this.wordEnd = isAsciiWordChar(last.charAt(last.length() - 1));
        }

        /** @return the end of a case-insensitive match starting at {@code start}, or -1 */
        int matchAt(String text, int start) {
            int n = text.length();
            int pos = start;
            for (int w = 0; w < words.length; w++) {
                if (w > 0) {
                    int gap = pos;
                    while (gap < n && isHorizontalSpace(text.charAt(gap))) gap++;
                    if (gap == pos) return -1;
                    pos = gap;
                }
                String word = words[w];
                if (!text.regionMatches(true, pos, word, 0, word.length())) return -1;
                pos += word.length();
            }
            return pos;
        }
    }

    private DoNotTranslateMatcher(List<String> source, Term[] terms) {
        this.source = source;
        this.terms = terms;
        Map<Character, List<Term>> building = new HashMap<>();
        for (Term term : terms) {
            Character key = fold(term.words[0].charAt(0));
            building.computeIfAbsent(key, ignored -> new ArrayList<>()).add(term);
        }
        Map<Character, Term[]> others = new HashMap<>();
        for (Map.Entry<Character, List<Term>> entry : building.entrySet()) {
            Term[] bucket = entry.getValue().toArray(new Term[0]);
            char key = entry.getKey();
            if (key < 128) asciiBuckets[key] = bucket;
            else others.put(entry.getKey(), bucket);
        }
        this.otherBuckets = others;
    }

    /**
     * Compile the configured list. Entries are trimmed; blank entries, entries without
     * any letter or digit, entries holding protocol/format characters ({@code ⟦ ⟧ §})
     * and case-insensitive duplicates are ignored. Never returns {@code null}.
     */
    public static DoNotTranslateMatcher compile(Collection<String> configured) {
        if (configured == null || configured.isEmpty()) return EMPTY;
        List<String> snapshot = Collections.unmodifiableList(new ArrayList<>(configured));
        List<Term> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : snapshot) {
            Term term = parse(raw);
            if (term != null && seen.add(term.key)) parsed.add(term);
        }
        parsed.sort((a, b) -> Integer.compare(b.length, a.length));
        return new DoNotTranslateMatcher(snapshot, parsed.toArray(new Term[0]));
    }

    private static Term parse(String raw) {
        if (raw == null) return null;
        String text = raw.strip();
        if (text.isEmpty()) return null;
        boolean hasWordCharacter = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == OPEN || c == CLOSE || c == SECTION) return null;
            if (Character.isLetterOrDigit(c)) hasWordCharacter = true;
        }
        // A punctuation-only entry (a stray "." from a mistyped list) would mask every
        // sentence and invalidate most cache keys; it can never be a meaningful term.
        if (!hasWordCharacter) return null;
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && isTermSpace(text.charAt(i))) i++;
            int start = i;
            while (i < text.length() && !isTermSpace(text.charAt(i))) i++;
            if (i > start) words.add(text.substring(start, i));
        }
        return words.isEmpty() ? null : new Term(words.toArray(new String[0]));
    }

    /** True when there is nothing to match. */
    public boolean isEmpty() {
        return terms.length == 0;
    }

    /** Whether this matcher was compiled from exactly {@code configured}. */
    public boolean compiledFrom(List<String> configured) {
        if (configured == null) return source.isEmpty();
        try {
            return source.equals(configured);
        } catch (RuntimeException concurrentEdit) {
            return true; // keep the current compilation until the edit settles
        }
    }

    /**
     * Every match as {@code {start, end}}; matches may overlap (the caller resolves
     * overlaps longest-first). Never returns {@code null}.
     */
    List<int[]> candidates(String text) {
        if (terms.length == 0 || text == null || text.isEmpty()) return Collections.emptyList();
        List<int[]> found = null;
        int n = text.length();
        int i = 0;
        boolean afterFormatCode = false;
        while (i < n) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                if (end > 0) {
                    i = end;
                    afterFormatCode = false;
                    continue;
                }
            } else if (c == SECTION) {
                if (i + 1 < n && text.charAt(i + 1) == OPEN) {
                    i++; // "§⟦MTn⟧": the code character itself is a template slot
                } else {
                    i += 2; // a literal §x format code is never part of a term
                    afterFormatCode = true;
                }
                continue;
            }
            Term[] bucket = bucketFor(c);
            if (bucket != null) {
                for (Term term : bucket) {
                    if (!leftBoundary(text, i, term, afterFormatCode)) continue;
                    int end = term.matchAt(text, i);
                    if (end < 0 || !rightBoundary(text, end, term)) continue;
                    if (found == null) found = new ArrayList<>(4);
                    found.add(new int[] {i, end});
                }
            }
            afterFormatCode = false;
            i++;
        }
        if (found == null) return Collections.emptyList();
        dropUrlFragments(text, found);
        return found;
    }

    private Term[] bucketFor(char c) {
        char key = fold(c);
        if (key < 128) return asciiBuckets[key];
        return otherBuckets.isEmpty() ? null : otherBuckets.get(key);
    }

    private static boolean leftBoundary(String text, int start, Term term,
                                        boolean afterFormatCode) {
        if (start == 0 || afterFormatCode || !term.wordStart) return true;
        return !isAsciiWordChar(text.charAt(start - 1));
    }

    private static boolean rightBoundary(String text, int end, Term term) {
        if (!term.wordEnd || end >= text.length()) return true;
        return !isAsciiWordChar(text.charAt(end));
    }

    /** End (exclusive) of a well-formed {@code ⟦…⟧} token starting at {@code open}, or -1. */
    private static int protocolTokenEnd(String text, int open) {
        for (int j = open + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == CLOSE) return j + 1;
            if (c == OPEN) return -1;
        }
        return -1;
    }

    /** Remove matches that touch a URL/domain fragment ({@code hypixel.net}). */
    private static void dropUrlFragments(String text, List<int[]> found) {
        if (text.indexOf('.') < 0 && text.indexOf("://") < 0) return;
        Matcher matcher = TemplateText.URL.matcher(text);
        List<int[]> urls = null;
        while (matcher.find()) {
            if (urls == null) urls = new ArrayList<>(2);
            urls.add(new int[] {matcher.start(), matcher.end()});
        }
        if (urls == null) return;
        List<int[]> fragments = urls;
        found.removeIf(match -> {
            for (int[] url : fragments) {
                if (match[0] < url[1] && url[0] < match[1]) return true;
            }
            return false;
        });
    }

    private static char fold(char c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }

    static boolean isAsciiWordChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    private static boolean isHorizontalSpace(char c) {
        return c == ' ' || c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    private static boolean isTermSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
