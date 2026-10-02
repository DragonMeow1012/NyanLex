package com.dragonmeow.nyanslate.translate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned, static table of STRONG sentence frames that carry a player name the TAB
 * list does not know about: auction sellers/buyers/bidders, lobby arrivals, leaderboard
 * rivals, auction notices and party events. {@link NameMasker#mask} merges
 * the spans found here with TAB names and do-not-translate terms in ONE pass, so every
 * seller of the same auction tooltip shares one cache key ({@code Seller: ⟦MT1⟧ ⟦0⟧}).
 *
 * <p>Rules (P1.2):</p>
 * <ul>
 *   <li>Every frame hinges on a literal anchor; a weak shape ({@code by NAME$},
 *       {@code [A] NAME}, {@code From NAME:}, a bare chat speaker) is never shipped, so
 *       a mob, NPC or item name is never frozen in English.</li>
 *   <li>The captured word must look like a Minecraft account name
 *       ({@code [A-Za-z0-9_]{3,16}}, not all digits) and must not be a stop word.</li>
 *   <li>Matching runs on a projection with {@code ⟦CS⟧} colour markers and {@code §x}
 *       format codes removed ({@code ⟦PBn⟧} and line breaks split lines; every other
 *       {@code ⟦…⟧} token stays verbatim), then maps the name back to the raw string.
 *       A name that crosses a colour run or a format code is left alone.</li>
 *   <li>Line-anchored frames run per line, so a seller on the 4th paragraph line is
 *       found like one on the first.</li>
 *   <li>Deterministic: the table is fixed per release ({@link #VERSION}); nothing is
 *       learned at runtime.</li>
 * </ul>
 *
 * <p>Cost: a literal prefilter rejects text without any anchor before any regex runs,
 * and results for anchor-bearing text are memoised in a bounded two-generation map
 * keyed by the raw string. Only the frame spans are memoised: TAB names change every
 * second and are merged fresh on every call by {@link NameMasker}.</p>
 *
 * <p>The same function also works on a STORED cache key (templated {@code ⟦MTn⟧} rank
 * tags and numbers, {@code ⟦WSn⟧} column gaps, already masked {@code ⟦n⟧} names): the
 * rank and number slots accept an {@code ⟦MTn⟧} token and column gaps count as blanks.
 * The one-off old-cache conversion (P1.3) relies on that.</p>
 */
public final class PlayerNamePatterns {

    /** Frame-table version. Bump whenever a frame is added, removed or changed. */
    public static final String VERSION = "names-v1";

    private static final char OPEN = '⟦';
    private static final char CLOSE = '⟧';
    private static final char SECTION = '§';
    private static final char LINE_BREAK = '\n';
    private static final int[] NONE = new int[0];

    // ---- projection-side regex building blocks ----
    /** A blank inside a line: horizontal space, or a stored-key column gap token. */
    private static final String GAP = "(?:[ \\t\\u00A0]|\\u27E6WS\\d+\\u27E7)";
    private static final String LINE_START = "^" + GAP + "*";
    private static final String LINE_END = GAP + "*$";
    /** Optional rank badge ([VIP] [MVP+] [MVP++] [YOUTUBE]) or its stored MT slot, captured
     *  under {@code "rank" + suffix} so {@link #compute} can ALSO mask the badge itself
     *  (2026-10 Hypixel-rank slotting): "Seller: [VIP] Name" and "Seller: [MVP+] Other"
     *  must share one cache key, which requires the rank text — not just the name — to
     *  leave the translatable string entirely. The capturing group never changes matching:
     *  every frame still accepts the same text whether or not the badge is present. */
    private static String rank(String suffix) {
        return "(?:(?<rank" + suffix + ">\\[[A-Z]{2,10}\\+{0,3}\\]|\\u27E6MT\\d+\\u27E7) )?";
    }
    /** A number as sent (1,234 / 12.5k) or its stored MT slot. */
    private static final String NUM = "(?:[-+]?\\d+(?:[.,]\\d+)*[%kKmMbB]?|\\u27E6MT\\d+\\u27E7)";

    private static String name(String group) {
        return "(?<![A-Za-z0-9_\\u27E6/])(?<" + group + ">[A-Za-z0-9_]{3,16})(?![A-Za-z0-9_\\u27E7])";
    }

    private static final class Frame {
        final String id;
        final String[] prefilter;
        final String[] anchors;
        final Pattern pattern;
        final String[] groups;
        /** Parallel to {@link #groups}: {@code "rank" + groups[i]} when the regex source
         *  actually declares that named group (via {@link #rank}), else {@code null}. Computed
         *  once from the regex SOURCE text so every existing {@code new Frame(...)} call site
         *  needs no change — only the regex string itself opts a group into rank co-capture. */
        final String[] rankGroups;

        Frame(String id, String[] prefilter, String[] anchors, String regex, String... groups) {
            this.id = id;
            this.prefilter = prefilter;
            this.anchors = anchors;
            this.pattern = Pattern.compile(regex);
            this.groups = groups;
            this.rankGroups = new String[groups.length];
            for (int i = 0; i < groups.length; i++) {
                String candidate = "rank" + groups[i];
                if (regex.contains("<" + candidate + ">")) rankGroups[i] = candidate;
            }
        }

        boolean anchoredIn(String projection, int from, int to) {
            for (String anchor : anchors) {
                int at = projection.indexOf(anchor, from);
                if (at >= 0 && at + anchor.length() <= to) return true;
            }
            return false;
        }
    }

    private static String[] words(String... values) {
        return values;
    }

    // names-v1. Each frame has (1) raw PREFILTER words: short literals that a colour
    // run or a format code never splits in practice, checked on the raw string before
    // anything else; (2) projection ANCHORS: the frame's full literal, checked per line on
    // the marker-free projection before its regex runs.
    private static final Frame[] FRAMES = {
            // Auction House tooltip fields: "Seller: [MVP+] Name", "Buyer: ...", "Bidder: ...".
            new Frame("ah.field", words("Seller", "Buyer", "Bidder"),
                    words("Seller: ", "Buyer: ", "Bidder: "),
                    LINE_START + "(?:Seller|Buyer|Bidder): " + rank("n") + name("n") + LINE_END, "n"),
            // "Top bid: 1,000 coins by [MVP+] Name" (bid-auction tooltip).
            new Frame("ah.topBid", words("Top bid"), words("Top bid: "),
                    LINE_START + "Top bid: " + NUM + " coins by " + rank("n") + name("n") + LINE_END, "n"),
            // "[MVP+] Name joined the lobby!" / ">>> [MVP++] Name joined the lobby! <<<".
            new Frame("lobby.join", words("joined the lobby"), words(" joined the lobby!"),
                    LINE_START + "(?:>>> )?" + rank("n") + name("n") + " joined the lobby!(?: <<<)?"
                            + LINE_END, "n"),
            // "[Mod] You passed Name in the Nether Wart Collection Leaderboard!".
            new Frame("leaderboard.passed", words("You passed"), words("You passed "),
                    "(?<![A-Za-z0-9_])You passed " + name("n") + " in the ", "n"),
            // leaderboard HUD row: "1,234 behind Name" / "1,234 behind Name [#12]".
            new Frame("leaderboard.behind", words("behind"), words(" behind "),
                    LINE_START + NUM + " behind " + name("n") + "(?: \\[#" + NUM + "\\])?"
                            + LINE_END, "n"),
            // "Item sold to [MVP+] Name for a marvelous 1,000 coins, gg!" (name may end the line).
            new Frame("auction.soldTo", words("sold to"), words("Item sold to "),
                    LINE_START + "Item sold to " + rank("n") + name("n") + "(?= for |" + LINE_END + ")", "n"),
            // "You collected 5 coins from selling Item to [MVP+] Name in an auction!".
            new Frame("auction.collected", words("in an auction"), words(" in an auction!"),
                    " to " + rank("n") + name("n") + " in an auction!", "n"),
            // "You claimed Item from [MVP+] Name's auction!".
            new Frame("auction.claimed", words("auction!"), words("'s auction!"),
                    " from " + rank("n") + name("n") + "'s auction!", "n"),
            // "[Auction] Name bought Item for 1,000 coins CLICK".
            new Frame("auction.bought", words("[Auction]"), words("[Auction] "),
                    LINE_START + "\\[Auction\\] " + rank("n") + name("n") + " bought ", "n"),
            // Party events: joined / left / disbanded / invited you.
            new Frame("party.event", words("the party", "their party"),
                    words(" joined the party.", " has left the party.",
                            " has disbanded the party!", " has invited you to join their party!"),
                    LINE_START + rank("n") + name("n") + " (?:joined the party\\.|has left the party\\."
                            + "|has disbanded the party!|has invited you to join their party!)", "n"),
            // "The party was transferred to [VIP+] A because [MVP+] B left" / "... to A by B".
            new Frame("party.transfer", words("transferred to"),
                    words("The party was transferred to "),
                    LINE_START + "The party was transferred to " + rank("a") + name("a")
                            + " (?:because " + rank("b") + name("b") + " left|by " + rank("c") + name("c") + ")"
                            + LINE_END, "a", "b", "c"),
            // "Name joined the dungeon group! (Archer Level 30)".
            new Frame("dungeon.group", words("dungeon group"), words(" joined the dungeon group! ("),
                    LINE_START + name("n") + " joined the dungeon group! \\(", "n"),
            // Party Finder listing title: "Name's Party".
            new Frame("partyFinder.title", words("'s Party"), words("'s Party"),
                    LINE_START + name("n") + "'s Party" + LINE_END, "n"),
    };

    /** Union of every frame's prefilter words: the raw literal prefilter. */
    private static final String[] PREFILTER = allPrefilterWords();

    private static String[] allPrefilterWords() {
        Set<String> all = new LinkedHashSet<>();
        for (Frame frame : FRAMES) all.addAll(Arrays.asList(frame.prefilter));
        return all.toArray(new String[0]);
    }

    /** Common words a frame slot must never treat as an account name. */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList((
            "a about above after again against all am an and any are as at be because been"
                    + " before being below between both but by can could did do does doing down"
                    + " during each few for from further had has have having he her here hers"
                    + " herself him himself his how i if in into is it its itself just me more"
                    + " most my myself no nor not now of off on once only or other our ours"
                    + " ourselves out over own same she should so some such than that the their"
                    + " theirs them themselves then there these they this those through to too"
                    + " under until up very was we were what when where which while who whom why"
                    + " will with would you your yours yourself yourselves every many much none"
                    + " one two three four five new old click right left ago today yesterday"
                    + " auction auctions bazaar coins coin buy sell sold seller bidder buyer price"
                    + " bid bids item items party guild friend friends lobby server hub island"
                    + " islands skyblock hypixel dungeon dungeons catacombs level levels tier skill"
                    + " skills slayer boss bosses pet pets rarity common uncommon rare epic"
                    + " legendary mythic divine special unknown someone player players everyone"
                    + " nobody team missing empty selected class classes members leader note")
            .split(" ")));

    // ---- bounded two-generation memo (raw text -> frame spans) ----
    private static final int MEMO_GENERATION_SIZE = 1024;
    private static final Object MEMO_LOCK = new Object();
    private static Map<String, int[]> memoYoung = new HashMap<>();
    private static Map<String, int[]> memoOld = new HashMap<>();
    // Separate memo for spansWithRank(): a DIFFERENT result shape (may include the rank
    // badge span too) keyed by the SAME raw text, so it cannot collide with memoYoung/memoOld.
    private static Map<String, int[]> memoYoungRank = new HashMap<>();
    private static Map<String, int[]> memoOldRank = new HashMap<>();

    private PlayerNamePatterns() {
    }

    /**
     * Raw {@code [start, end)} spans of every player name a shipped frame isolates, in
     * text order and non-overlapping. Works on live text and on stored cache keys.
     * Never {@code null}; the returned arrays are copies.
     */
    public static List<int[]> nameSpans(String text) {
        int[] flat = spans(text);
        if (flat.length == 0) return Collections.emptyList();
        List<int[]> out = new ArrayList<>(flat.length / 2);
        for (int i = 0; i < flat.length; i += 2) out.add(new int[] {flat[i], flat[i + 1]});
        return Collections.unmodifiableList(out);
    }

    /**
     * Flat {@code [s0, e0, s1, e1, …]} frame spans. The array is shared with the memo:
     * callers must treat it as read-only.
     */
    static int[] spans(String text) {
        if (text == null || text.length() < 6 || !mayContainFrame(text)) return NONE;
        synchronized (MEMO_LOCK) {
            int[] hit = memoYoung.get(text);
            if (hit != null) return hit;
            hit = memoOld.get(text);
            if (hit != null) {
                rememberLocked(text, hit);
                return hit;
            }
        }
        int[] computed = compute(text, null, false);
        synchronized (MEMO_LOCK) {
            rememberLocked(text, computed);
        }
        return computed;
    }

    /**
     * Like {@link #spans} but ALSO includes the Hypixel rank badge ({@code [VIP]},
     * {@code [MVP+]}, {@code [MVP++]}, …) immediately preceding a found name, as its own
     * span — so {@code NameMasker}, which is this method's only caller, can mask the badge
     * the same as the name itself. "Seller: [VIP] Alice" and "Seller: [MVP+] Bob" then mask
     * to the SAME template ({@code "Seller: ⟦0⟧ ⟦1⟧"}), sharing one cache key regardless of
     * rank or name (2026-10 Hypixel-rank slotting). A badge split across more than one
     * colour run (e.g. the "+" in {@code [MVP+]} drawn in its own colour) is left unmasked,
     * exactly like a name that crosses a colour run — see {@link #compute}'s raw-span
     * contiguity check. {@link #spans}/{@link #nameSpans} are UNCHANGED (name-only), since
     * other callers (hub export, legacy-key conversion, diagnostics) specifically expect
     * only the name.
     */
    static int[] spansWithRank(String text) {
        if (text == null || text.length() < 6 || !mayContainFrame(text)) return NONE;
        synchronized (MEMO_LOCK) {
            int[] hit = memoYoungRank.get(text);
            if (hit != null) return hit;
            hit = memoOldRank.get(text);
            if (hit != null) {
                rememberLockedRank(text, hit);
                return hit;
            }
        }
        int[] computed = compute(text, null, true);
        synchronized (MEMO_LOCK) {
            rememberLockedRank(text, computed);
        }
        return computed;
    }

    private static void rememberLocked(String text, int[] spans) {
        memoYoung.put(text, spans);
        if (memoYoung.size() >= MEMO_GENERATION_SIZE) {
            // Rotate instead of clearing: the previous generation still answers (and is
            // promoted on use), so a steady working set never falls off a cliff.
            memoOld = memoYoung;
            memoYoung = new HashMap<>();
        }
    }

    private static void rememberLockedRank(String text, int[] spans) {
        memoYoungRank.put(text, spans);
        if (memoYoungRank.size() >= MEMO_GENERATION_SIZE) {
            memoOldRank = memoYoungRank;
            memoYoungRank = new HashMap<>();
        }
    }

    /** Memoised entry count of the name-only table (both generations); bounded by twice
     *  {@link #MEMO_GENERATION_SIZE}. */
    static int memoSize() {
        synchronized (MEMO_LOCK) {
            return memoYoung.size() + memoOld.size();
        }
    }

    /** Literal prefilter on the raw string: no prefilter word, no projection, no regex. */
    static boolean mayContainFrame(String text) {
        for (String word : PREFILTER) {
            if (text.indexOf(word) >= 0) return true;
        }
        return false;
    }

    /** Uncached evaluation that also reports {@code frameId + '\t' + name} per accepted
     *  match (diagnostics/replay only; production goes through {@link #spans}). */
    static int[] computeForDiagnostics(String text, List<String> frameSink) {
        if (text == null || text.isEmpty()) return NONE;
        return compute(text, frameSink, false);
    }

    private static int[] compute(String text, List<String> frameSink, boolean includeRank) {
        int n = text.length();
        char[] projected = new char[n];
        int[] rawIndex = new int[n];
        int p = 0;
        for (int i = 0; i < n; ) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                if (end > 0) {
                    int kind = tokenKind(text, i + 1, end - 1);
                    if (kind == KIND_CS) {
                        i = end;
                        continue;
                    }
                    if (kind == KIND_PB) {
                        projected[p] = LINE_BREAK;
                        rawIndex[p++] = i;
                        i = end;
                        continue;
                    }
                    for (int j = i; j < end; j++) {
                        projected[p] = text.charAt(j);
                        rawIndex[p++] = j;
                    }
                    i = end;
                    continue;
                }
            } else if (c == SECTION && i + 1 < n) {
                // "§⟦MTn⟧" keeps the slot; a literal "§x" format code disappears entirely.
                i += text.charAt(i + 1) == OPEN ? 1 : 2;
                continue;
            }
            projected[p] = c;
            rawIndex[p++] = i;
            i++;
        }
        String projection = new String(projected, 0, p);

        List<int[]> found = null;
        int lineStart = 0;
        while (lineStart <= p) {
            int lineEnd = lineStart;
            while (lineEnd < p && projection.charAt(lineEnd) != LINE_BREAK
                    && projection.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            if (lineEnd - lineStart >= 6) {
                for (Frame frame : FRAMES) {
                    if (!frame.anchoredIn(projection, lineStart, lineEnd)) continue;
                    Matcher matcher = frame.pattern.matcher(projection);
                    matcher.region(lineStart, lineEnd);
                    matcher.useTransparentBounds(true);
                    matcher.useAnchoringBounds(true);
                    while (matcher.find()) {
                        for (int gi = 0; gi < frame.groups.length; gi++) {
                            String group = frame.groups[gi];
                            int s = matcher.start(group);
                            int e = matcher.end(group);
                            if (s < 0 || e <= s || !accountShaped(projection, s, e)) continue;
                            int rawStart = rawIndex[s];
                            int rawEnd = rawIndex[e - 1] + 1;
                            // A CS run boundary or a § code inside the name widens the raw
                            // span: the name straddles two style runs and stays unmasked.
                            if (rawEnd - rawStart != e - s) continue;
                            if (found == null) found = new ArrayList<>(2);
                            found.add(new int[] {rawStart, rawEnd});
                            if (frameSink != null) {
                                frameSink.add(frame.id + '\t' + text.substring(rawStart, rawEnd));
                            }
                            String rankGroup = frame.rankGroups[gi];
                            if (includeRank && rankGroup != null) {
                                int rs = matcher.start(rankGroup);
                                int re = matcher.end(rankGroup);
                                if (rs >= 0 && re > rs) {
                                    int rawRs = rawIndex[rs];
                                    int rawRe = rawIndex[re - 1] + 1;
                                    // Same contiguity rule as the name itself: a badge split
                                    // across more than one colour run (e.g. a differently-
                                    // coloured "+") is left unmasked rather than guessed.
                                    if (rawRe - rawRs == re - rs) {
                                        found.add(new int[] {rawRs, rawRe});
                                        if (frameSink != null) {
                                            frameSink.add(frame.id + ".rank\t"
                                                    + text.substring(rawRs, rawRe));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            lineStart = lineEnd + 1;
        }
        if (found == null) return NONE;
        found.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0])
                : Integer.compare(b[1], a[1]));
        int[] flat = new int[found.size() * 2];
        int size = 0;
        int lastEnd = -1;
        for (int[] span : found) {
            if (span[0] < lastEnd) continue; // two frames on the same name: keep one
            flat[size++] = span[0];
            flat[size++] = span[1];
            lastEnd = span[1];
        }
        return size == flat.length ? flat : Arrays.copyOf(flat, size);
    }

    private static boolean accountShaped(String projection, int start, int end) {
        int length = end - start;
        if (length < 3 || length > 16) return false;
        boolean allDigits = true;
        for (int i = start; i < end; i++) {
            char c = projection.charAt(i);
            if (c < '0' || c > '9') {
                allDigits = false;
                break;
            }
        }
        if (allDigits) return false;
        return !STOP_WORDS.contains(projection.substring(start, end).toLowerCase(Locale.ROOT));
    }

    private static final int KIND_OTHER = 0;
    private static final int KIND_CS = 1;
    private static final int KIND_PB = 2;

    /** Classify the body of a {@code ⟦…⟧} token: colour marker, paragraph break, other. */
    private static int tokenKind(String text, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        boolean closing = i < to && text.charAt(i) == '/';
        if (closing) {
            i++;
            while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        }
        if (i + 2 > to) return KIND_OTHER;
        char first = text.charAt(i);
        char second = text.charAt(i + 1);
        int kind;
        if (first == 'C' && second == 'S') kind = KIND_CS;
        else if (first == 'P' && second == 'B' && !closing) kind = KIND_PB;
        else return KIND_OTHER;
        i += 2;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        int digits = i;
        while (i < to && text.charAt(i) >= '0' && text.charAt(i) <= '9') i++;
        if (i == digits) return KIND_OTHER;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        return i == to ? kind : KIND_OTHER;
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
