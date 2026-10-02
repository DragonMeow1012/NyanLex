package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Item-name entity layer, core half: every item the loader glue sees (tooltip, container
 * scan, hotbar, held item) is registered here with the data the item itself carries —
 * its display name, SkyBlock {@code id} and reforge {@code modifier} (from
 * {@code minecraft:custom_data} / {@code ExtraAttributes}; the glue reads them, this
 * class never touches a Minecraft type).
 *
 * <p>Two things are derived from a registration, both purely structural:</p>
 * <ul>
 *   <li><b>Reforge split.</b> A name is split into reforge word + base name ONLY when
 *   (1) {@code modifier} is present, (2) the name's first word(s) spell a shipped reforge
 *   word {@code W} of {@link TermTableDefaults#REFORGE} (case-insensitively) followed by a
 *   space and a non-empty rest, and (3) {@code normalize(W)} equals
 *   {@code normalize(modifier)}, or the modifier's lower-cased text up to one of its
 *   {@code _} separators normalises to {@code normalize(W)} (so {@code rich_bow} proves
 *   {@code Rich}). {@code normalize} = lower-case, letters and digits only. Only that
 *   first {@code W} is removed ({@code Wise Wise Dragon Chestplate} -> base
 *   {@code Wise Dragon Chestplate}). Anything else — no modifier, a modifier that
 *   disagrees with the first word ({@code Giant's Sword}, {@code Wise Dragon Chestplate}),
 *   an unknown modifier — is never split: nothing is guessed from the name.</li>
 *   <li><b>Line matching.</b> {@link #spans} finds registered <em>core names</em> (the
 *   display name without {@code §} codes and without leading/trailing decoration such
 *   as {@code ✪✪✪✪✪➎} or {@code ⚚}) inside a line: same case, whole word, longest first,
 *   never across a {@code ⟦CS⟧} run (a core name never contains {@code ⟦}). Only names
 *   of at least two words or {@value #MIN_SINGLE_WORD_LENGTH} characters, and only items
 *   that carry a SkyBlock id or a modifier, are matchable — a vanilla/menu/other-modpack
 *   item without SkyBlock data is registered but never substituted.</li>
 * </ul>
 *
 * <p>Bounded ({@link #DEFAULT_CAPACITY} display names, least-recently-registered evicted
 * first) and thread-safe. Re-registering a known display name with the same (or less)
 * data is a single map lookup; all derivation happens once per new display name.
 * {@link #spans} runs a literal first-word prefilter (a bloom filter over the first words
 * of the matchable names, no allocation) and then a bounded two-generation memo keyed by
 * the text and validated against {@link #version()}.</p>
 */
public final class ItemEntityRegistry {

    public static final int DEFAULT_CAPACITY = 4096;
    /** A single-word name must be at least this long to be substituted inside a line. */
    public static final int MIN_SINGLE_WORD_LENGTH = 8;
    private static final int MEMO_GENERATION_SIZE = 1024;
    private static final int BLOOM_BITS = 1 << 16;
    private static final int BLOOM_MASK = BLOOM_BITS - 1;
    private static final int[] NONE = new int[0];
    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';

    /** Lower-cased first token of every shipped reforge word -> the words, longest first. */
    private static final Map<String, List<String>> REFORGE_BY_FIRST_TOKEN = reforgeIndex();

    /** One registered display name. Immutable. */
    public static final class Entry {
        private final String displayName;
        private final String coreName;
        private final String skyblockId;
        private final String modifier;
        private final String reforgeWord;
        private final String baseName;
        private final boolean matchable;

        private Entry(String displayName, String coreName, String skyblockId, String modifier,
                      String reforgeWord, String baseName, boolean matchable) {
            this.displayName = displayName;
            this.coreName = coreName;
            this.skyblockId = skyblockId;
            this.modifier = modifier;
            this.reforgeWord = reforgeWord;
            this.baseName = baseName;
            this.matchable = matchable;
        }

        /** The name exactly as registered (its own cache key, e.g. {@code "X ✪✪✪"}). */
        public String displayName() { return displayName; }
        /** Display name without § codes and leading/trailing decoration. */
        public String coreName() { return coreName; }
        public String skyblockId() { return skyblockId; }
        public String modifier() { return modifier; }
        /** Canonical {@link TermTableDefaults#REFORGE} key, or {@code null} when not split. */
        public String reforgeWord() { return reforgeWord; }
        /** Core name without the proven reforge word, or {@code null} when not split. */
        public String baseName() { return baseName; }
        public boolean split() { return reforgeWord != null; }
        /** Whether {@link #spans} may substitute this name inside a line. */
        public boolean matchable() { return matchable; }

        private int richness() {
            return split() ? 2 : matchable ? 1 : 0;
        }
    }

    /** All display names sharing one core name (for example different star counts). */
    private static final class CoreRef {
        final String core;
        final String firstWord;
        final int firstWordOffset;
        final List<Entry> members = new ArrayList<>(1);
        Entry representative;
        boolean indexed;

        CoreRef(String core, String firstWord, int firstWordOffset) {
            this.core = core;
            this.firstWord = firstWord;
            this.firstWordOffset = firstWordOffset;
        }
    }

    private static final class Memo {
        final long version;
        final int[] spans;

        Memo(long version, int[] spans) {
            this.version = version;
            this.spans = spans;
        }
    }

    private final int capacity;
    /** Display name -> entry, access order (the eldest is the least recently registered). */
    private final LinkedHashMap<String, Entry> byDisplay = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, CoreRef> byCore = new HashMap<>();
    /** First word run of a matchable core -> its cores, longest first. */
    private final Map<String, List<CoreRef>> byFirstWord = new HashMap<>();
    private final long[] bloom = new long[BLOOM_BITS / 64];
    private int bloomRemovals;
    private volatile int matchableCores;
    private volatile long version;
    private Map<String, Memo> memoYoung = new HashMap<>();
    private Map<String, Memo> memoOld = new HashMap<>();

    public ItemEntityRegistry() {
        this(DEFAULT_CAPACITY);
    }

    public ItemEntityRegistry(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Register (or refresh) one item. {@code skyblockId} and {@code modifier} may be
     * {@code null}. A repeat with the same or less data only touches the LRU order. A
     * registration never loses data: a later call without a modifier keeps the
     * modifier an earlier call proved for the same display name.
     *
     * @return the entry now stored for {@code displayName}, or {@code null} when the
     *         name has no core (blank, only decoration, or protocol text)
     */
    public Entry register(String displayName, String skyblockId, String modifier) {
        if (displayName == null || displayName.isEmpty()) return null;
        String id = blankToNull(skyblockId);
        String mod = blankToNull(modifier);
        Entry existing;
        synchronized (this) {
            existing = byDisplay.get(displayName);
            if (existing != null && coveredBy(existing, id, mod)) return existing;
        }
        if (existing != null) {
            if (id == null) id = existing.skyblockId;
            if (mod == null) mod = existing.modifier;
        }
        Entry fresh = build(displayName, id, mod);
        if (fresh == null) return null;
        synchronized (this) {
            Entry current = byDisplay.get(displayName);
            if (current != null) {
                if (coveredBy(current, fresh.skyblockId, fresh.modifier)) return current;
                unindex(current);
            }
            byDisplay.put(displayName, fresh);
            index(fresh);
            while (byDisplay.size() > capacity) {
                Iterator<Map.Entry<String, Entry>> eldest = byDisplay.entrySet().iterator();
                Entry evicted = eldest.next().getValue();
                eldest.remove();
                unindex(evicted);
            }
            return fresh;
        }
    }

    /** The entry registered under this exact display name (counts as a use for LRU). */
    public synchronized Entry forDisplayName(String displayName) {
        return displayName == null ? null : byDisplay.get(displayName);
    }

    /** The preferred entry for a core name (a split entry wins over an unsplit one). */
    public synchronized Entry forCore(String coreName) {
        if (coreName == null) return null;
        CoreRef ref = byCore.get(coreName);
        return ref == null ? null : ref.representative;
    }

    public synchronized int size() {
        return byDisplay.size();
    }

    /** Whether any registered name can currently be matched inside a line (O(1)). */
    public boolean hasMatchableNames() {
        return matchableCores > 0;
    }

    /** Changes whenever the set of matchable names (or a name's split) changes. */
    public long version() {
        return version;
    }

    public synchronized void clear() {
        byDisplay.clear();
        byCore.clear();
        byFirstWord.clear();
        java.util.Arrays.fill(bloom, 0L);
        bloomRemovals = 0;
        matchableCores = 0;
        version++;
        memoYoung = new HashMap<>();
        memoOld = new HashMap<>();
    }

    // ---------------------------------------------------------------------------------
    // Line matching
    // ---------------------------------------------------------------------------------

    /** Copy of {@link #spans} as {@code [start, end, split(0/1)]} triples. */
    public List<int[]> matchSpans(String text) {
        int[] flat = spans(text);
        if (flat.length == 0) return Collections.emptyList();
        List<int[]> out = new ArrayList<>(flat.length / 3);
        for (int i = 0; i < flat.length; i += 3) {
            out.add(new int[] {flat[i], flat[i + 1], flat[i + 2]});
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Flat {@code [s0, e0, split0, s1, e1, split1, …]} of every matchable registered core
     * name in {@code text}: exact case, whole word (a word boundary is required only at
     * an edge whose own character is a word character), longest first, non-overlapping,
     * in text order. Protocol tokens ({@code ⟦…⟧}) are never matched into. The array is
     * shared with the memo: callers must treat it as read-only.
     */
    int[] spans(String text) {
        if (text == null || text.isEmpty() || matchableCores == 0) return NONE;
        synchronized (this) {
            if (!mayContainFirstWord(text)) return NONE;
            long now = version;
            Memo memo = memoYoung.get(text);
            if (memo == null) {
                memo = memoOld.get(text);
                if (memo != null && memo.version == now) remember(text, memo);
            }
            if (memo != null && memo.version == now) return memo.spans;
            int[] computed = compute(text);
            remember(text, new Memo(now, computed));
            return computed;
        }
    }

    /** Memoised entry count (both generations); bounded by twice the generation size. */
    synchronized int memoSize() {
        return memoYoung.size() + memoOld.size();
    }

    private void remember(String text, Memo memo) {
        memoYoung.put(text, memo);
        if (memoYoung.size() >= MEMO_GENERATION_SIZE) {
            memoOld = memoYoung;
            memoYoung = new HashMap<>();
        }
    }

    /** Allocation-free literal prefilter: does any word run hit the first-word bloom? */
    private boolean mayContainFirstWord(String text) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                i = end > 0 ? end : i + 1;
                continue;
            }
            if (c == '§') {
                i += 2;
                continue;
            }
            if (!wordChar(c)) {
                i++;
                continue;
            }
            int h = 0;
            int j = i;
            while (j < n && wordChar(text.charAt(j))) {
                h = 31 * h + text.charAt(j);
                j++;
            }
            if (bloomHas(h)) return true;
            i = j;
        }
        return false;
    }

    private int[] compute(String text) {
        int n = text.length();
        List<int[]> found = null;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                i = end > 0 ? end : i + 1;
                continue;
            }
            if (c == '§') {
                i += 2;
                continue;
            }
            if (!wordChar(c)) {
                i++;
                continue;
            }
            int h = 0;
            int j = i;
            while (j < n && wordChar(text.charAt(j))) {
                h = 31 * h + text.charAt(j);
                j++;
            }
            if (bloomHas(h)) {
                List<CoreRef> candidates = byFirstWord.get(text.substring(i, j));
                if (candidates != null) {
                    for (CoreRef ref : candidates) {
                        int start = i - ref.firstWordOffset;
                        int end = start + ref.core.length();
                        if (start < 0 || end > n || !text.startsWith(ref.core, start)) continue;
                        if (!boundaryOk(text, start, end, ref.core)) continue;
                        if (found == null) found = new ArrayList<>(2);
                        found.add(new int[] {start, end, ref.representative.split() ? 1 : 0});
                        break; // candidates are longest first
                    }
                }
            }
            i = j;
        }
        if (found == null) return NONE;
        List<int[]> chosen = longestNonOverlapping(found);
        int[] flat = new int[chosen.size() * 3];
        int k = 0;
        for (int[] span : chosen) {
            flat[k++] = span[0];
            flat[k++] = span[1];
            flat[k++] = span[2];
        }
        return flat;
    }

    private static boolean boundaryOk(String text, int start, int end, String core) {
        if (wordChar(core.charAt(0)) && start > 0 && wordChar(text.charAt(start - 1))
                && !(start >= 2 && text.charAt(start - 2) == '§')) {
            return false;
        }
        return !(wordChar(core.charAt(core.length() - 1)) && end < text.length()
                && wordChar(text.charAt(end)));
    }

    private static List<int[]> longestNonOverlapping(List<int[]> spans) {
        if (spans.size() == 1) return spans;
        List<int[]> byLength = new ArrayList<>(spans);
        byLength.sort((a, b) -> {
            int lengths = Integer.compare(b[1] - b[0], a[1] - a[0]);
            return lengths != 0 ? lengths : Integer.compare(a[0], b[0]);
        });
        List<int[]> chosen = new ArrayList<>(byLength.size());
        for (int[] span : byLength) {
            boolean overlaps = false;
            for (int[] kept : chosen) {
                if (span[0] < kept[1] && kept[0] < span[1]) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) chosen.add(span);
        }
        chosen.sort((a, b) -> Integer.compare(a[0], b[0]));
        return chosen;
    }

    // ---------------------------------------------------------------------------------
    // Index maintenance (always under the monitor)
    // ---------------------------------------------------------------------------------

    private void index(Entry entry) {
        CoreRef ref = byCore.get(entry.coreName);
        if (ref == null) {
            int[] word = firstWordRun(entry.coreName);
            ref = new CoreRef(entry.coreName,
                    word == null ? null : entry.coreName.substring(word[0], word[1]),
                    word == null ? 0 : word[0]);
            byCore.put(entry.coreName, ref);
        }
        ref.members.add(entry);
        refresh(ref);
    }

    private void unindex(Entry entry) {
        CoreRef ref = byCore.get(entry.coreName);
        if (ref == null) return;
        ref.members.remove(entry);
        if (ref.members.isEmpty()) {
            if (ref.indexed) removeFromWordIndex(ref);
            byCore.remove(entry.coreName);
            return;
        }
        refresh(ref);
    }

    /** Re-pick the representative (richest, then most recent) and sync the word index. */
    private void refresh(CoreRef ref) {
        Entry best = null;
        for (Entry member : ref.members) {
            if (best == null || member.richness() >= best.richness()) best = member;
        }
        Entry previous = ref.representative;
        ref.representative = best;
        boolean shouldIndex = best != null && best.matchable && ref.firstWord != null;
        if (shouldIndex && !ref.indexed) {
            addToWordIndex(ref);
        } else if (!shouldIndex && ref.indexed) {
            removeFromWordIndex(ref);
        } else if (ref.indexed && previous != null && previous.split() != best.split()) {
            version++;
        }
    }

    private void addToWordIndex(CoreRef ref) {
        List<CoreRef> list = byFirstWord.computeIfAbsent(ref.firstWord, k -> new ArrayList<>(1));
        int at = 0;
        while (at < list.size() && list.get(at).core.length() >= ref.core.length()) at++;
        list.add(at, ref);
        bloomAdd(ref.firstWord.hashCode());
        ref.indexed = true;
        matchableCores++;
        version++;
    }

    private void removeFromWordIndex(CoreRef ref) {
        List<CoreRef> list = byFirstWord.get(ref.firstWord);
        if (list != null) {
            list.remove(ref);
            if (list.isEmpty()) byFirstWord.remove(ref.firstWord);
        }
        ref.indexed = false;
        matchableCores--;
        version++;
        if (++bloomRemovals > Math.max(64, matchableCores)) rebuildBloom();
    }

    private void rebuildBloom() {
        java.util.Arrays.fill(bloom, 0L);
        for (String word : byFirstWord.keySet()) bloomAdd(word.hashCode());
        bloomRemovals = 0;
    }

    private void bloomAdd(int h) {
        int a = bloomIndexA(h);
        int b = bloomIndexB(h);
        bloom[a >>> 6] |= 1L << (a & 63);
        bloom[b >>> 6] |= 1L << (b & 63);
    }

    private boolean bloomHas(int h) {
        int a = bloomIndexA(h);
        if ((bloom[a >>> 6] & (1L << (a & 63))) == 0) return false;
        int b = bloomIndexB(h);
        return (bloom[b >>> 6] & (1L << (b & 63))) != 0;
    }

    private static int bloomIndexA(int h) {
        return (h ^ (h >>> 16)) & BLOOM_MASK;
    }

    private static int bloomIndexB(int h) {
        return ((h * 0x9E3779B9) >>> 16) & BLOOM_MASK;
    }

    // ---------------------------------------------------------------------------------
    // Derivation
    // ---------------------------------------------------------------------------------

    private static boolean coveredBy(Entry existing, String id, String mod) {
        return (id == null || id.equals(existing.skyblockId))
                && (mod == null || mod.equals(existing.modifier));
    }

    private static Entry build(String displayName, String id, String mod) {
        String core = coreName(displayName);
        if (core == null || core.isEmpty()
                || core.indexOf(OPEN) >= 0 || core.indexOf(CLOSE) >= 0) {
            return null;
        }
        String[] split = splitReforge(core, mod);
        boolean longEnough = core.indexOf(' ') > 0 || core.length() >= MIN_SINGLE_WORD_LENGTH;
        boolean skyblockData = id != null || mod != null;
        return new Entry(displayName, core, id, mod,
                split == null ? null : split[0], split == null ? null : split[1],
                longEnough && skyblockData && firstWordRun(core) != null);
    }

    /**
     * {@code {reforgeKey, baseName}} when {@code modifier} proves the core name's leading
     * reforge word, else {@code null}. See the class comment for the exact rule.
     */
    static String[] splitReforge(String core, String modifier) {
        if (core == null || modifier == null) return null;
        int space = core.indexOf(' ');
        if (space <= 0) return null;
        List<String> candidates = REFORGE_BY_FIRST_TOKEN.get(
                core.substring(0, space).toLowerCase(Locale.ROOT));
        if (candidates == null) return null;
        for (String word : candidates) {
            int length = word.length();
            if (core.length() <= length + 1 || core.charAt(length) != ' ') continue;
            if (!core.regionMatches(true, 0, word, 0, length)) continue;
            String base = core.substring(length + 1).strip();
            if (base.isEmpty() || !modifierProves(word, modifier)) continue;
            return new String[] {word, base};
        }
        return null;
    }

    /** {@code normalize(word)} equals {@code normalize(modifier)}, or equals the
     *  normalised text of {@code modifier} up to one of its {@code _} separators. */
    static boolean modifierProves(String word, String modifier) {
        String expected = normalize(word);
        if (expected.isEmpty() || modifier == null) return false;
        String lower = modifier.strip().toLowerCase(Locale.ROOT);
        if (normalize(lower).equals(expected)) return true;
        for (int i = lower.indexOf('_'); i > 0; i = lower.indexOf('_', i + 1)) {
            if (normalize(lower.substring(0, i)).equals(expected)) return true;
        }
        return false;
    }

    static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(Character.toLowerCase(cp));
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /**
     * Display name without {@code §} codes and without leading/trailing whitespace and
     * decoration (the same icon classes {@link TemplateText} slots: symbols, private-use
     * glyphs, circled digits such as {@code ➎}). {@code null} for {@code null}.
     */
    public static String coreName(String displayName) {
        if (displayName == null) return null;
        String plain = stripSectionCodes(displayName);
        int start = 0;
        int end = plain.length();
        while (start < end) {
            int cp = plain.codePointAt(start);
            if (!isDecoration(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = plain.codePointBefore(end);
            if (!isDecoration(cp)) break;
            end -= Character.charCount(cp);
        }
        return plain.substring(start, end);
    }

    static boolean isDecoration(int cp) {
        if (Character.isWhitespace(cp) || cp == 0x00A0) return true;
        if (cp == 0x00A7 || cp == OPEN || cp == CLOSE) return false;
        if (cp >= 0x2460 && cp <= 0x24FF) return true;
        if (cp >= 0x2776 && cp <= 0x2793) return true;
        int type = Character.getType(cp);
        return type == Character.OTHER_SYMBOL || type == Character.PRIVATE_USE
                || type == Character.SURROGATE;
    }

    private static String stripSectionCodes(String text) {
        if (text.indexOf('§') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** {@code [start, end)} of the first word run (letters, digits, {@code _}), or null. */
    private static int[] firstWordRun(String core) {
        int n = core.length();
        for (int i = 0; i < n; i++) {
            if (!wordChar(core.charAt(i))) continue;
            int j = i + 1;
            while (j < n && wordChar(core.charAt(j))) j++;
            return new int[] {i, j};
        }
        return null;
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int protocolTokenEnd(String text, int open) {
        for (int j = open + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == CLOSE) return j + 1;
            if (c == OPEN) return -1;
        }
        return -1;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Map<String, List<String>> reforgeIndex() {
        Map<String, List<String>> index = new HashMap<>();
        for (String word : TermTableDefaults.REFORGE.keySet()) {
            int space = word.indexOf(' ');
            String token = (space < 0 ? word : word.substring(0, space)).toLowerCase(Locale.ROOT);
            index.computeIfAbsent(token, k -> new ArrayList<>(1)).add(word);
        }
        for (List<String> words : index.values()) {
            words.sort((a, b) -> Integer.compare(b.length(), a.length()));
        }
        Map<String, List<String>> frozen = new HashMap<>();
        for (Map.Entry<String, List<String>> e : index.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }
}
