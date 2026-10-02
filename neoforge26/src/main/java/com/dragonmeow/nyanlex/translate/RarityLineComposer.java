package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1.7: recognises a SkyBlock tooltip's isolated rarity line ({@code "EPIC DUNGEON
 * GLOVES"}, {@code "a MYTHIC ACCESSORY a"}, {@code "RARE COMBAT SHARD (ID C18)"}, …)
 * so {@link com.dragonmeow.nyanlex.service.TranslationService} can compose its
 * translation locally from {@link TermTable} instead of sending the whole line to a
 * translator (which regularly mistranslates EPIC as "傳奇").
 *
 * <p>Only the 8 shapes in term-table-draft.md §2 match: an isolated, fully upper-case
 * rarity phrase, optionally {@code SHINY}-prefixed, optionally {@code DUNGEON}-infixed,
 * optionally wrapped in a pair of single lower-case "confusion" letters (recombobulated
 * gear renders its obfuscated glyph as a lower-case {@code a}), or the
 * {@code "{RARITY} {FAMILY} SHARD (ID …)"} shape. Anything else — including a line
 * that merely CONTAINS one of these words inside ordinary prose, or a line joined from
 * several paragraph rows ({@code ⟦PBn⟧} present) — is rejected so this never touches a
 * genuine sentence or a multi-line paragraph unit (P1.7 §4: "多列段落裡的稀有度行不處理").</p>
 *
 * <p>Matching runs on a projection with {@code ⟦CSn⟧}/{@code ⟦/CSn⟧} colour markers and
 * bare {@code §x} format codes removed (the same technique {@link PlayerNamePatterns}
 * uses for player names), then the translatable span is mapped back to raw offsets. If
 * any marker turns out to sit INSIDE that span — meaning the rarity phrase itself is
 * split across two colour runs, a shape the fixed 8 forms never produce — the match is
 * rejected rather than risk corrupting the CS structure; the confusion letters and any
 * literal {@code "(ID …)"} suffix always fall outside the span, so splicing in the
 * composed Chinese text leaves them untouched.</p>
 *
 * <p>Cost: a literal prefilter rejects any line without a rarity-word substring before
 * a projection is even built, and accepted/rejected results are memoised in a bounded
 * two-generation map keyed by the raw line — the same discipline {@link
 * PlayerNamePatterns} uses for its per-frame lookup.</p>
 */
public final class RarityLineComposer {

    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';
    private static final char SECTION = '§';

    /** Every rarity word contains (or IS) one of these; a line without any of them
     *  cannot be a rarity line. Checked on the RAW string, before any projection is
     *  built or any regex runs (VERY SPECIAL/UNCOMMON are covered via SPECIAL/COMMON). */
    private static final String[] PREFILTER_WORDS = {
            "COMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL",
            "ULTIMATE", "SUPREME", "ADMIN",
    };

    private static final String RARITY_ALT =
            "VERY SPECIAL|UNCOMMON|COMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL"
                    + "|ULTIMATE|SUPREME|ADMIN";

    // Group 1=confusion prefix, 2=SHINY, 3=rarity, 4=DUNGEON, 5=type phrase, 6=confusion suffix.
    private static final Pattern GENERIC = Pattern.compile(
            "^\\s*(?:([a-z])\\s+)?(?:(SHINY)\\s+)?(" + RARITY_ALT + ")(?:\\s+(DUNGEON))?"
                    + "(?:\\s+([A-Z][A-Z-]*(?:\\s+[A-Z][A-Z-]*)*))?(?:\\s+([a-z]))?\\s*$");
    // Group 1=rarity, 2="FAMILY SHARD", 3=literal " (ID …)" suffix (untouched).
    private static final Pattern SHARD = Pattern.compile(
            "^\\s*(" + RARITY_ALT + ")\\s+([A-Z]+\\s+SHARD)(\\s+\\(ID\\s+[A-Za-z0-9]+\\))?\\s*$");

    /** Which half of {@link TermTable} resolves a {@link Word}. */
    public enum Kind { RARITY, TYPE }

    /** One literal word/phrase this line needs translated, in output order. */
    public record Word(Kind kind, String text) {
    }

    /** A recognised rarity line: {@code [rawStart, rawEnd)} is the exact raw-string span
     *  to replace with the composed translation; everything outside it (confusion
     *  letters, CS/format markers, a literal ID suffix) is copied through unchanged.
     *  {@code wordSpans} is {@code words}' raw-string {@code [start, end)} counterpart,
     *  one entry per word, in the same order — used only by {@link #composeProtecting}
     *  to carve a do-not-translate hit back out of an already-matched word. */
    public record Match(int rawStart, int rawEnd, List<Word> words, List<int[]> wordSpans,
                        boolean wordwise) {

        public Match(int rawStart, int rawEnd, List<Word> words, List<int[]> wordSpans) {
            this(rawStart, rawEnd, words, wordSpans, false);
        }

        /** Splice the resolved words into {@code rawLine}, or {@code null} when
         *  {@code resolver} cannot resolve one of {@link #words()} yet (an out-of-table
         *  type word not learned). Chinese words are simply concatenated: no spaces,
         *  no "的/之/級" connective (P1.7 §6.1 composition rule). */
        public String compose(String rawLine, BiFunction<Kind, String, String> resolver) {
            List<String> values = new ArrayList<>(words.size());
            for (Word word : words) {
                String value = resolver.apply(word.kind(), word.text());
                if (value == null) return null;
                values.add(value);
            }
            return assemble(rawLine, values);
        }

        /** Like {@link #compose}, but a word whose raw span overlaps a do-not-translate
         *  term match is copied through verbatim from {@code rawLine} (exact original
         *  spelling/case) instead of being resolved by {@code resolver} — P1.7's local
         *  term-table composition must never override a term the user explicitly asked
         *  to keep untranslated (a whole-phrase table entry such as {@code "GARDEN
         *  CHIP"} would otherwise translate a protected word hiding inside it). A word
         *  free of any overlap composes exactly as {@link #compose} does; when nothing
         *  in {@code doNotTranslate} is configured this delegates straight to {@link
         *  #compose} (zero extra scanning, unchanged behaviour). Returns {@code null}
         *  under the same condition as {@link #compose}. */
        public String composeProtecting(String rawLine, DoNotTranslateMatcher doNotTranslate,
                                        BiFunction<Kind, String, String> resolver) {
            if (doNotTranslate == null || doNotTranslate.isEmpty()) return compose(rawLine, resolver);
            List<int[]> hits = doNotTranslate.candidates(rawLine);
            if (hits.isEmpty()) return compose(rawLine, resolver);
            List<String> values = new ArrayList<>(words.size());
            for (int idx = 0; idx < words.size(); idx++) {
                int[] span = wordSpans.get(idx);
                if (overlapsAny(span, hits)) {
                    values.add(rawLine.substring(span[0], span[1]));
                    continue;
                }
                Word word = words.get(idx);
                String value = resolver.apply(word.kind(), word.text());
                if (value == null) return null;
                values.add(value);
            }
            return assemble(rawLine, values);
        }

        /** Places the per-word values into {@code rawLine}. A marker-free core is replaced
         *  as one block (the classic path). A {@link #wordwise} core — the phrase is drawn in
         *  several colour runs whose boundaries all sit BETWEEN words — instead replaces each
         *  word's own span in place, keeping every ⟦CSn⟧ marker tag exactly where it was (so
         *  each translated word keeps its own source word's colour, never a proportional
         *  split) and dropping only the blank gaps between words (Chinese is unspaced). */
        private String assemble(String rawLine, List<String> values) {
            if (!wordwise) {
                StringBuilder core = new StringBuilder();
                for (String value : values) core.append(value);
                return rawLine.substring(0, rawStart) + core + rawLine.substring(rawEnd);
            }
            StringBuilder out = new StringBuilder(rawLine.length());
            out.append(rawLine, 0, wordSpans.get(0)[0]);
            for (int idx = 0; idx < values.size(); idx++) {
                out.append(values.get(idx));
                int end = wordSpans.get(idx)[1];
                int next = idx + 1 < values.size() ? wordSpans.get(idx + 1)[0] : -1;
                if (next < 0) {
                    out.append(rawLine, end, rawLine.length());
                } else {
                    for (int p = end; p < next; p++) {
                        char c = rawLine.charAt(p);
                        if (c != ' ' && c != ' ' && c != '	') out.append(c);
                    }
                }
            }
            return out.toString();
        }

        /** The subset of {@link #words()} NOT covered by a do-not-translate hit — the
         *  ones actually eligible for table lookup/learning. A protected word must never
         *  be queued as an "unknown word to learn": {@link #composeProtecting} keeps it
         *  literal regardless of the table, so requesting its translation would only
         *  waste a request for a value that can never be surfaced. */
        public List<Word> unprotectedWords(String rawLine, DoNotTranslateMatcher doNotTranslate) {
            if (doNotTranslate == null || doNotTranslate.isEmpty()) return words;
            List<int[]> hits = doNotTranslate.candidates(rawLine);
            if (hits.isEmpty()) return words;
            List<Word> result = new ArrayList<>(words.size());
            for (int idx = 0; idx < words.size(); idx++) {
                if (!overlapsAny(wordSpans.get(idx), hits)) result.add(words.get(idx));
            }
            return result;
        }

        private static boolean overlapsAny(int[] span, List<int[]> hits) {
            for (int[] hit : hits) {
                if (span[0] < hit[1] && hit[0] < span[1]) return true;
            }
            return false;
        }
    }

    /** One rarity line found by {@link #matchRuns}, located among a larger paragraph's
     *  {@code ⟦PBn⟧}-delimited rows. {@code [start, end)} is the row's raw span in the
     *  text {@code matchRuns} was called with; {@code match}'s own span is relative to
     *  {@code start} (index 0 at the row's own first character, exactly as {@link #match}
     *  always returns it), matching {@link EnchantListComposer.Run}'s contract so {@link
     *  TooltipSegmentPlanner} can treat every composer's runs uniformly. */
    public record Run(int start, int end, Match match) {
    }

    /**
     * Row-tolerant variant of {@link #match}: a rarity line today is rejected outright the
     * moment ANY {@code ⟦PBn⟧} token appears anywhere in {@code text} (see {@link #compute}
     * below), which is correct for an isolated line but means a rarity line glued by the
     * glue's paragraph joiner to an unrelated row (an item-name slot, a trade field, …)
     * with no blank line between them is never composed at all — the bug {@code
     * TooltipSegmentPlanner} exists to fix. This splits {@code text} on {@link
     * ParagraphModel#splitRawRows} first and runs {@link #match} on each row
     * INDEPENDENTLY, so only the rows that genuinely are a rarity line are claimed; every
     * other row is left for the caller. Never {@code null}; empty when nothing qualifies.
     */
    public static List<Run> matchRuns(String text) {
        if (text == null) return List.of();
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null || rows.isEmpty()) return List.of();
        List<Run> runs = null;
        for (int[] row : rows) {
            Match match = matchLenient(text.substring(row[0], row[1]));
            if (match != null) {
                if (runs == null) runs = new ArrayList<>();
                runs.add(new Run(row[0], row[1], match));
            }
        }
        return runs == null ? List.of() : List.copyOf(runs);
    }

    // ---- bounded two-generation memo (raw line -> Match, or NONE) ----
    private static final int MEMO_GENERATION_SIZE = 512;
    private static final Object MEMO_LOCK = new Object();
    private static final Match NONE = new Match(-1, -1, List.of(), List.of());
    private static Map<String, Match> memoYoung = new HashMap<>();
    private static Map<String, Match> memoOld = new HashMap<>();

    private RarityLineComposer() {
    }

    /** {@code null} when {@code text} is not one of the shapes above. Never mutates or
     *  allocates beyond the memo on a repeat call with the same string. */
    public static Match match(String text) {
        return match(text, false);
    }

    /**
     * Like {@link #match} but ALSO accepts a phrase drawn in several colour runs when every
     * run boundary sits between two WORDS (never inside one) — e.g. {@code "MYTHIC"} /
     * {@code "DUNGEON"} / {@code "BOW"} each in its own colour. Composition is then
     * {@link Match#wordwise}: each translated word is spliced into its own source word's
     * place with every colour marker untouched, so each Chinese word keeps its source
     * word's colour (never a proportional split). A phrase with a marker or {@code §} code
     * INSIDE a word still returns {@code null}. The strict {@link #match} contract (and its
     * tests) is unchanged.
     */
    public static Match matchLenient(String text) {
        return match(text, true);
    }

    private static Match match(String text, boolean lenient) {
        if (text == null || text.length() < 4 || !mayContainRarityWord(text)) return null;
        String memoKey = lenient ? "" + text : text;
        synchronized (MEMO_LOCK) {
            Match hit = memoYoung.get(memoKey);
            if (hit == null) hit = memoOld.get(memoKey);
            if (hit != null) {
                rememberLocked(memoKey, hit);
                return hit == NONE ? null : hit;
            }
        }
        Match computed = compute(text, lenient);
        synchronized (MEMO_LOCK) {
            rememberLocked(memoKey, computed == null ? NONE : computed);
        }
        return computed;
    }

    private static void rememberLocked(String text, Match match) {
        memoYoung.put(text, match);
        if (memoYoung.size() >= MEMO_GENERATION_SIZE) {
            memoOld = memoYoung;
            memoYoung = new HashMap<>();
        }
    }

    /** Memoised entry count (both generations); test-only. */
    static int memoSize() {
        synchronized (MEMO_LOCK) {
            return memoYoung.size() + memoOld.size();
        }
    }

    private static boolean mayContainRarityWord(String text) {
        for (String word : PREFILTER_WORDS) {
            if (text.indexOf(word) >= 0) return true;
        }
        return false;
    }

    private static Match compute(String text, boolean lenient) {
        int n = text.length();
        char[] projected = new char[n];
        int[] rawIndex = new int[n];
        int p = 0;
        for (int i = 0; i < n; ) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                if (end < 0) return null; // unterminated marker: not a plain rarity line
                if (!isCsToken(text, i + 1, end - 1)) {
                    // ⟦PBn⟧ (multi-line paragraph unit), ⟦MTn⟧/⟦WSn⟧, or a bare protected
                    // placeholder: none of the 8 shapes carry one, so this line is
                    // either a paragraph key (must not be touched) or has unexpected
                    // structure. Either way, leave it to the ordinary lookup path.
                    return null;
                }
                i = end;
                continue;
            }
            if (c == SECTION && i + 1 < n) {
                i += text.charAt(i + 1) == OPEN ? 1 : 2;
                continue;
            }
            projected[p] = c;
            rawIndex[p++] = i;
            i++;
        }
        String projection = new String(projected, 0, p);

        Matcher shard = SHARD.matcher(projection);
        if (shard.matches()) {
            List<Word> words = List.of(
                    new Word(Kind.RARITY, shard.group(1)),
                    new Word(Kind.TYPE, normalizeSpaces(shard.group(2))));
            List<int[]> spans = List.of(
                    new int[] {shard.start(1), shard.end(1)},
                    new int[] {shard.start(2), shard.end(2)});
            return spanned(text, lenient, rawIndex, shard.start(1), shard.end(2), words, spans);
        }

        Matcher generic = GENERIC.matcher(projection);
        if (!generic.matches()) return null;
        int coreStart = generic.start(2) >= 0 ? generic.start(2) : generic.start(3);
        int coreEnd = generic.end(5) >= 0 ? generic.end(5)
                : generic.end(4) >= 0 ? generic.end(4) : generic.end(3);
        List<Word> words = new ArrayList<>(4);
        List<int[]> spans = new ArrayList<>(4);
        if (generic.start(2) >= 0) {
            words.add(new Word(Kind.TYPE, "SHINY"));
            spans.add(new int[] {generic.start(2), generic.end(2)});
        }
        words.add(new Word(Kind.RARITY, generic.group(3)));
        spans.add(new int[] {generic.start(3), generic.end(3)});
        if (generic.start(4) >= 0) {
            words.add(new Word(Kind.TYPE, "DUNGEON"));
            spans.add(new int[] {generic.start(4), generic.end(4)});
        }
        if (generic.start(5) >= 0) {
            words.add(new Word(Kind.TYPE, normalizeSpaces(generic.group(5))));
            spans.add(new int[] {generic.start(5), generic.end(5)});
        }
        return spanned(text, lenient, rawIndex, coreStart, coreEnd, List.copyOf(words), List.copyOf(spans));
    }

    private static Match spanned(String text, boolean lenient, int[] rawIndex, int coreStart,
                                 int coreEnd, List<Word> words, List<int[]> projectedSpans) {
        if (coreEnd <= coreStart) return null;
        int rawStart = rawIndex[coreStart];
        int rawEnd = rawIndex[coreEnd - 1] + 1;
        // A CS colour run or a bare format code inside the translatable span means the
        // rarity phrase is split across two styles: an unexpected shape the fixed 8
        // forms never produce. The strict form rejects it rather than risk corrupting the
        // CS structure; the lenient form accepts it only when every WORD is still marker-
        // free (and no § code hides in a gap), then composes word by word.
        boolean wordwise = rawEnd - rawStart != coreEnd - coreStart;
        if (wordwise && !lenient) return null;
        // Each word's own narrower projected span is mapped through the same rawIndex table.
        List<int[]> rawSpans = new ArrayList<>(projectedSpans.size());
        for (int[] span : projectedSpans) {
            int ws = rawIndex[span[0]];
            int we = rawIndex[span[1] - 1] + 1;
            if (we - ws != span[1] - span[0]) return null; // marker/§ INSIDE a word
            rawSpans.add(new int[] {ws, we});
        }
        if (wordwise) {
            for (int i = 0; i + 1 < rawSpans.size(); i++) {
                if (text.substring(rawSpans.get(i)[1], rawSpans.get(i + 1)[0]).indexOf(SECTION) >= 0) {
                    return null;
                }
            }
        }
        return new Match(rawStart, rawEnd, words, List.copyOf(rawSpans), wordwise);
    }

    private static String normalizeSpaces(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /** Whether the {@code ⟦…⟧} token body (between the brackets) is a CS colour marker
     *  (open or close); anything else (PB, MT, WS, a bare protected-term index) is not. */
    private static boolean isCsToken(String text, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        if (i < to && text.charAt(i) == '/') {
            i++;
            while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        }
        return i + 2 <= to && text.charAt(i) == 'C' && text.charAt(i + 1) == 'S';
    }

    private static int protocolTokenEnd(String text, int open) {
        for (int j = open + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == CLOSE) return j + 1;
            if (c == OPEN) return -1;
        }
        return -1;
    }
}
