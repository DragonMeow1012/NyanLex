package com.dragonmeow.mctranslator.translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final char SLOT_SPACE = '\u0001';
    private static final char SLOT_TAB = '\u0002';
    private static final char SLOT_NBSP = '\u0003';
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:(?:https?://|www\\.)\\S+"
                    + "|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z]{2,24}(?::\\d{1,5})?(?:/\\S*)?)");
    // Progress-bar / divider runs ("──────", "----"): pure formatting the model must not
    // touch — and long runs confuse batch line alignment if sent raw.
    private static final Pattern BAR = Pattern.compile("[─━—=\\-]{4,}");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    // Dynamic values may start immediately after a literal Minecraft section code
    // ("§62,525", "§b1,605"). The style code itself is not part of the value.
    private static final String DYNAMIC_START = "(?:(?<=§.)|(?<![A-Za-z0-9_⟦§]))";
    // Hypixel-style scoreboard footer/header row: "07/10/26 m6GA5". The shard is
    // deliberately recognised only after a complete slash date and must contain a
    // digit, so ordinary short names elsewhere remain translatable.
    private static final Pattern SCOREBOARD_DATE_SHARD = Pattern.compile(
            "(?i)" + DYNAMIC_START + "\\d{1,2}/\\d{1,2}/\\d{2,4}\\s+"
                    + "(?=[A-Za-z][A-Za-z0-9_-]{2,11}(?![A-Za-z0-9_-]))"
                    + "(?=[A-Za-z0-9_-]*\\d)[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern TIME = Pattern.compile("(?i)" + DYNAMIC_START + "\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[ap]\\.?m\\.?)?(?![A-Za-z_⟧])");
    // Countdown / duration runs ("59s", "10min", "2h 30m", "1m30s", "1天2小時3分30秒"):
    // scoreboards tick these every second, so an untemplated duration mints a brand-new
    // request key each tick — the single worst request-storm (429) driver. The whole run
    // collapses into ONE token; the trailing (?![A-Za-z]) keeps ordinary words safe
    // ("5 may" never matches: the unit letter must not be followed by a letter).
    // Calendar ordinals such as "23rd" are handled separately as complete slots below.
    private static final String DURATION_UNIT_EN =
            "(?:d|days?|h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)";
    private static final Pattern DURATION_EN = Pattern.compile(
            "(?i)" + DYNAMIC_START + "\\d+(?:[.,]\\d+)?\\s*" + DURATION_UNIT_EN + "(?![A-Za-z])"
                    + "(?:\\s*\\d+(?:[.,]\\d+)?\\s*" + DURATION_UNIT_EN + "(?![A-Za-z]))*");
    private static final Pattern DURATION_CJK = Pattern.compile(
            DYNAMIC_START + "(?:\\d+(?:[.,]\\d+)?\\s*(?:天|日|小時|時|分鐘|分|秒))+");
    // Calendar/day counters such as "Late Summer 23rd" change without changing
    // meaning. Keep the suffix inside the slot so 23rd/24th share one cache key.
    private static final Pattern ORDINAL = Pattern.compile(
            "(?i)" + DYNAMIC_START + "\\d+(?:st|nd|rd|th)(?![A-Za-z_\\u27E7])");
    // Digits adjacent to ⟦…⟧ are inside a NameMasker/TemplateText token — never re-template those.
    // Atomic group (?>…): when the trailing guard rejects ("10kg", "31x?"), the whole match
    // fails instead of backtracking into a half number ("1", "3") that shreds the cache key.
    // The quantity suffix x ("Sold 31x String") rides inside the slot — its own lookahead
    // keeps hex/dimensions/words ("0x1F", "2x2", "4xp") untouched — so 31x/1x/31 variants
    // fold into one key.
    private static final Pattern NUMBER = Pattern.compile(DYNAMIC_START
            + "(?>[-+]?\\d+(?:[.,]\\d+)*(?:[%％]|[kKmMbB]|[xX](?![0-9A-Za-z_⟧]))?)(?![A-Za-z_⟧])");
    // Decorative icon runs (⚔, ✪✪✪✪✪, ☀, 🔹, modded PUA icon fonts): OTHER_SYMBOL +
    // private-use + surrogates, MINUS the ⟦⟧ token brackets and '§' (style, not icon).
    // One slot per RUN, so star-upgrade variants ("✪✪✪" vs "✪✪✪✪✪") share a key and each
    // restore puts back its OWN icons — and the icons never reach a translator that could
    // eat or shuffle them. Registered LAST in compute(): BAR keeps priority on ─-dividers
    // (BOX DRAWINGS is also So) and URL keeps an icon glued to a link inside the link.
    private static final Pattern SYMBOL_RUN = Pattern.compile("[\\p{So}\\p{Co}\\p{Cs}&&[^§⟦⟧]]+");
    // Bracketed ALL-CAPS rank/title tags ([VIP] [MVP+] [MVP++] [ADMIN] [MOD] [YOUTUBE]):
    // pure badges, never prose — a translator renders them as nonsense ("[最有價值球員+]"),
    // so they ride as slots and come back verbatim. Deliberately strict: lowercase/mixed
    // content ([Lv5], [dungeon]) is real text and stays translatable; digit tags ([144])
    // are already NUMBER's. Bonus: "[VIP] x" and "[MVP+] x" share one key.
    private static final Pattern RANK_TAG = Pattern.compile("\\[[A-Z]{2,10}\\+{0,2}\\]");
    private static final Pattern CS_TOKEN = Pattern.compile("\\u27E6\\s*(/?)\\s*CS\\s*\\d+\\s*\\u27E7");
    // Do not mistake ordinary sentence subjects for usernames. "You received 5 coins"
    // is an action-bar sentence, not a player event; slotting "You" would leave the
    // backend unable to translate the sentence correctly.
    private static final String PLAYER_ID =
            "(?:(?!(?:you|your|the|this|that|there|someone|everyone|player)\\b)"
                    + "[A-Za-z_][A-Za-z0-9_]{2,16}|\\u27E6\\d+\\u27E7)";
    /**
     * Rank + player identity at the start of an event after optional announcement arrows.
     * Matching runs on a CS-marker-stripped view, then maps the identity span back to the
     * original string. This handles Hypixel's MVP++ rainbow layout where [MVP, ++, ] Name
     * are three separate style runs. NameMasker's temporary ⟦0⟧ form is accepted too, so
     * TAB-list timing cannot create a second family of cache keys.
     */
    private static final Pattern PLAYER_EVENT_IDENTITY = Pattern.compile(
            "^\\s*(?:[>»]{2,3}\\s*)?"
                    + "((?:\\[[A-Z]{2,10}\\+{0,2}\\]\\s+)?" + PLAYER_ID + ")"
                    + "(?=\\s+(?:joined|left|quit|was|has|is|died|fell|burned|drowned|suffocated|blew|tried|hit|lost|won|teleported|moved|voted|claimed|unclaimed|entered|exited|discovered|found|picked|dropped|sold|bought|paid|received|earned|made|completed|reached|killed|slain|shot|whispered|says|said)\\b)",
            Pattern.CASE_INSENSITIVE);
    // A player event may start with a rank badge: "[MVP+] Name joined the lobby!".
    // Capture only the username group; RANK_TAG separately protects the badge, so all
    // ranks and all names converge to one template and no player ID reaches a backend.
    private static final Pattern LEADING_PLAYER = Pattern.compile(
            "^\\s*(?:\\[[A-Z]{2,10}\\+{0,2}\\]\\s+)?"
                    + "((?!(?:you|your|the|this|that|there|someone|everyone|player)\\b)"
                    + "[A-Za-z_][A-Za-z0-9_]{2,16})"
                    + "(?=\\s+(?:joined|left|quit|was|has|is|died|fell|burned|drowned|suffocated|blew|tried|hit|lost|won|teleported|moved|voted|claimed|unclaimed|entered|exited|discovered|found|picked|dropped|sold|bought|paid|received|earned|made|completed|reached|killed|slain|shot|whispered|says|said)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_PLAYER = Pattern.compile("\\b(?:by|from|to|with|for)\\s+([A-Za-z_][A-Za-z0-9_]{2,16})\\b");
    /** Opaque shard/instance id after a Server field (mega33A, alphaShard, xxxxx).
     *  The label gives this field its meaning; its machine-assigned value is volatile
     *  regardless of prefix or whether it happens to contain digits. */
    private static final Pattern SERVER_INSTANCE = Pattern.compile(
            "(?i)(?:\\bserver\\s*:|伺服器\\s*[：:])"
                    + "(?:\\s|§.|⟦\\s*/?\\s*CS\\s*\\d+\\s*⟧)*"
                    + "([A-Za-z0-9][A-Za-z0-9_.-]*)");

    // prepare() is called per render-frame for cache misses; memoise so the regex
    // sweep runs once per distinct string. Prepared is immutable, so sharing is safe.
    private static final int MEMO_MAX = 2048;
    private static final Map<String, Prepared> MEMO = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Prepared> eldest) {
                    return size() > MEMO_MAX;
                }
            });

    private TemplateText() {
    }

    public record Prepared(String text, List<String> values) {
        public boolean changed() {
            return !values.isEmpty();
        }

        /** Substitute the original values back into a translated template. Tolerates the
         *  translator inserting spaces around/inside the token (common with CJK output),
         *  and puts back the template's own ASCII spacing when the translator ATE it
         *  ("Jun \u27E6MT0\u27E7, \u27E6MT1\u27E7" answered as "6\u6708\u27E6MT0\u27E7,\u27E6MT1\u27E7" must not render "6\u670830,2026").
         *  A space is only re-added between an ASCII visible neighbour and the value \u2014
         *  never next to CJK or full-width punctuation, where no space belongs. */
        public String restore(String translated) {
            if (translated == null || values.isEmpty()) return translated;
            String out = translated;
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i);
                String visible = CS_TOKEN.matcher(value).replaceAll("");
                boolean leadingSpace = !visible.isEmpty()
                        && Character.isWhitespace(visible.charAt(0));
                boolean trailingSpace = !visible.isEmpty()
                        && Character.isWhitespace(visible.charAt(visible.length() - 1));
                String regex = (leadingSpace ? "[ \\t\\u00A0]*" : "")
                        + "\\u27E6\\s*MT\\s*" + i + "\\s*\\u27E7"
                        + (trailingSpace ? "[ \\t\\u00A0]*" : "");
                String protectedValue = value.replace(' ', SLOT_SPACE)
                        .replace('\t', SLOT_TAB).replace('\u00A0', SLOT_NBSP);

                // Did the TEMPLATE carry horizontal whitespace around this token? Each
                // token is unique in text() (sequentially numbered), so indexOf is exact.
                String tok = OPEN + "MT" + i + String.valueOf(CLOSE);
                int at = text.indexOf(tok);
                boolean hadSpaceBefore = at > 0 && isHorizontalSpace(text.charAt(at - 1));
                boolean hadSpaceAfter = at >= 0 && at + tok.length() < text.length()
                        && isHorizontalSpace(text.charAt(at + tok.length()));

                Matcher m = Pattern.compile(regex).matcher(out);
                StringBuilder sb = new StringBuilder(out.length() + 8);
                int last = 0;
                while (m.find()) {
                    String rep = protectedValue;
                    // Only restore "ASCII visible char \u2194 token" spacing the template had:
                    // full-width punctuation (\uFF1A\uFF0C\u3002), CJK, existing whitespace and \u27E6\u27E7
                    // brackets get nothing. SLOT_SPACE dodges tightenCjkSpacing and is
                    // turned back into ' ' below.
                    if (hadSpaceBefore && !leadingSpace && m.start() > 0
                            && isAsciiVisible(out.charAt(m.start() - 1))) {
                        rep = SLOT_SPACE + rep;
                    }
                    if (hadSpaceAfter && !trailingSpace && m.end() < out.length()
                            && isAsciiVisible(out.charAt(m.end()))) {
                        rep = rep + SLOT_SPACE;
                    }
                    sb.append(out, last, m.start()).append(rep);
                    last = m.end();
                }
                sb.append(out, last, out.length());
                out = sb.toString();
            }
            return tightenCjkSpacing(out)
                    .replace(SLOT_SPACE, ' ')
                    .replace(SLOT_TAB, '\t')
                    .replace(SLOT_NBSP, '\u00A0');
        }
    }

    // A single space is translator typography; two or more spaces are fixed Minecraft
    // HUD columns and must never be tightened away.
    private static final Pattern CJK_BEFORE_NUM = Pattern.compile("(?<=[\\u4e00-\\u9fff，。！？：])[ \\u00A0](?=[0-9+\\-])");
    private static final Pattern NUM_BEFORE_CJK = Pattern.compile("(?<=[0-9%％.,kKmMbB])[ \\u00A0](?=[\\u4e00-\\u9fff，。！？：])");
    // A translator (esp. the AI) sometimes renders a number's ASCII separator as its
    // full-width CJK form ("1,950" → "1，950", "3.5" → "3．5") while keeping half-width
    // digits. BETWEEN digits that is always wrong (a full-width comma between words is
    // correct Chinese prose), so the rewrite is digit-flanked and safe for ordinary text.
    // It also self-heals such a value already sitting in the persistent cache.
    private static final Pattern FW_COMMA_IN_NUMBER = Pattern.compile("(?<=[0-9])，(?=[0-9])");
    private static final Pattern FW_DOT_IN_NUMBER = Pattern.compile("(?<=[0-9])．(?=[0-9])");

    /**
     * CJK typography clean-up applied to every restored translation: full-width number
     * separators back to ASCII ("1，950" → "1,950"), and no space between a number and an
     * adjacent CJK character ("擊中了1 敵人" vs "擊中2敵人") so every restored message reads
     * the same way. No-op for output with neither a digit-flanked full-width separator nor
     * a CJK/number space boundary.
     */
    static String tightenCjkSpacing(String text) {
        if (text == null) return text;
        String out = text;
        if (out.indexOf('，') >= 0) out = FW_COMMA_IN_NUMBER.matcher(out).replaceAll(",");
        if (out.indexOf('．') >= 0) out = FW_DOT_IN_NUMBER.matcher(out).replaceAll(".");
        if (out.indexOf(' ') < 0) return out;
        return NUM_BEFORE_CJK.matcher(CJK_BEFORE_NUM.matcher(out).replaceAll("")).replaceAll("");
    }

    /** ASCII visible character (letters, digits, ASCII punctuation) — the only
     *  neighbours that may earn back a template space in {@code restore}. */
    private static boolean isAsciiVisible(char c) {
        return c >= '!' && c <= '~';
    }

    private static boolean isHorizontalSpace(char c) {
        return c == ' ' || c == '\t' || c == '\u00A0';
    }

    // Tooltip label/value rows are padded with a wide space run ("NPC Sell Price:     50,000").
    // Translated CJK text is far narrower than the English original, so the preserved padding
    // renders as a huge hole after the colon. The (?<=\S) lookbehind keeps leading indentation
    // (ParagraphModel's indent-paragraph semantics) untouched.
    private static final Pattern TRANSLATED_COLUMN_GAP =
            Pattern.compile("(?<=\\S)[ \\t\\u00A0]{3,}");
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u4e00-\\u9fff]");

    /** Tooltip-only display clean-up: on lines that actually translated into CJK, collapse any
     *  in-line run of 3+ horizontal spaces to a fixed 2 — label and value stay visibly
     *  separated. Lines without CJK (untranslated, or a non-Chinese target) pass through
     *  unchanged, as does every other surface (this is only applied by translateItemLine). */
    public static String collapseTranslatedColumnGaps(String text) {
        if (text == null || text.indexOf(' ') < 0 || !CJK_CHAR.matcher(text).find()) return text;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (CJK_CHAR.matcher(lines[i]).find()) {
                lines[i] = TRANSLATED_COLUMN_GAP.matcher(lines[i]).replaceAll("  ");
            }
        }
        return String.join("\n", lines);
    }

    public static Prepared prepare(String source) {
        if (source == null || source.isEmpty()) return new Prepared(source, List.of());
        Prepared hit = MEMO.get(source);
        if (hit != null) return hit;
        Prepared computed = compute(source);
        MEMO.put(source, computed);
        return computed;
    }

    /**
     * True when the line starts with a Minecraft username followed by a common
     * player-event verb, optionally after a server rank badge. TranslationCache
     * uses this to discard legacy per-player cache rows instead of letting them
     * bypass the new privacy-preserving shared template.
     */
    public static boolean isLeadingPlayerEvent(String source) {
        return source != null && PLAYER_EVENT_IDENTITY.matcher(stripCs(source).strip()).find();
    }

    private static Prepared compute(String source) {
        List<Span> spans = new ArrayList<>();
        addPattern(source, spans, BAR, 0, false);
        addPattern(source, spans, URL, 0, false);
        addPattern(source, spans, UUID, 0, false);
        addPattern(source, spans, SCOREBOARD_DATE_SHARD, 0, false);
        addPattern(source, spans, TIME, 0, false);
        addPattern(source, spans, DURATION_EN, 0, false);
        addPattern(source, spans, DURATION_CJK, 0, false);
        addPattern(source, spans, ORDINAL, 0, false);
        addPlayerEventIdentity(source, spans);
        addPattern(source, spans, LEADING_PLAYER, 1, false);
        addPattern(source, spans, TARGET_PLAYER, 1, true);
        addPattern(source, spans, SERVER_INSTANCE, 1, false);
        addPattern(source, spans, NUMBER, 0, false);
        addPattern(source, spans, SYMBOL_RUN, 0, false);
        addPattern(source, spans, RANK_TAG, 0, false);
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

    private static void addPlayerEventIdentity(String source, List<Span> spans) {
        VisibleText visible = visibleWithoutCs(source);
        Matcher event = PLAYER_EVENT_IDENTITY.matcher(visible.text());
        if (!event.find()) return;
        int visibleStart = event.start(1);
        int visibleEnd = event.end(1);
        if (visibleStart < 0 || visibleEnd <= visibleStart) return;

        // Keep the separator whitespace inside the identity slot. In styled chat the
        // trailing space commonly sits before the CS closing marker; stopping at the
        // username would leave an orphan ⟦/CS#⟧ in the request key and make every
        // coloured lobby event fail marker validation forever.
        while (visibleEnd < visible.text().length()
                && Character.isWhitespace(visible.text().charAt(visibleEnd))) {
            visibleEnd++;
        }

        int start = visible.sourceOffsets().get(visibleStart);
        int end = visible.sourceOffsets().get(visibleEnd - 1) + 1;
        // Include the style opener before the first identity character and the closer
        // after its last character. All intervening CS runs are already inside the span.
        for (CsRange marker : visible.markers()) {
            if (!marker.closing() && marker.end() == start) start = marker.start();
        }
        boolean expanded;
        do {
            expanded = false;
            for (CsRange marker : visible.markers()) {
                if (marker.closing() && marker.start() == end) {
                    end = marker.end();
                    expanded = true;
                }
            }
        } while (expanded);
        spans.add(new Span(start, end));
    }

    private static String stripCs(String source) {
        return source == null ? "" : CS_TOKEN.matcher(source).replaceAll("");
    }

    private static VisibleText visibleWithoutCs(String source) {
        StringBuilder text = new StringBuilder(source.length());
        List<Integer> offsets = new ArrayList<>(source.length());
        List<CsRange> markers = new ArrayList<>();
        Matcher matcher = CS_TOKEN.matcher(source);
        int cursor = 0;
        while (matcher.find()) {
            appendVisible(source, cursor, matcher.start(), text, offsets);
            markers.add(new CsRange(matcher.start(), matcher.end(), !matcher.group(1).isEmpty()));
            cursor = matcher.end();
        }
        appendVisible(source, cursor, source.length(), text, offsets);
        return new VisibleText(text.toString(), offsets, markers);
    }

    private static void appendVisible(String source, int start, int end,
                                      StringBuilder text, List<Integer> offsets) {
        for (int i = start; i < end; i++) {
            text.append(source.charAt(i));
            offsets.add(i);
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

    private record CsRange(int start, int end, boolean closing) {
    }

    private record VisibleText(String text, List<Integer> sourceOffsets,
                               List<CsRange> markers) {
    }
}
