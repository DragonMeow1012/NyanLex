package com.dragonmeow.nyanlex.translate;

/**
 * Decides whether a piece of text is worth translating.
 *
 * <p>Skips empty strings, strings without any letters (pure numbers / symbols /
 * Minecraft score values), and — when the target is Chinese — text that is
 * already mostly Chinese, to avoid pointless "Chinese to Chinese" round-trips.</p>
 */
public final class TextFilter {

    /** Ephemeral cache→renderer signal: the semantic translation is ready but this exact
     * CS colour topology is not. It is never persisted or sent to a backend. */
    private static final String STYLE_FALLBACK_PREFIX = "\u0000MT_STYLE_FALLBACK\u0000";

    public static String markStyleFallback(String text) {
        return text == null || isStyleFallback(text) ? text : STYLE_FALLBACK_PREFIX + text;
    }

    public static boolean isStyleFallback(String text) {
        return text != null && text.startsWith(STYLE_FALLBACK_PREFIX);
    }

    public static String stripStyleFallback(String text) {
        return isStyleFallback(text) ? text.substring(STYLE_FALLBACK_PREFIX.length()) : text;
    }

    /** Fraction of letters that must be CJK for text to count as "already Chinese". */
    private static final double CJK_THRESHOLD = 0.5;
    private static final java.util.regex.Pattern MT_TOKEN =
            java.util.regex.Pattern.compile("\\u27E6\\s*(?:MT|WS)\\s*\\d+\\s*\\u27E7");
    private static final String MT_TOKEN_SOURCE =
            "\\u27E6\\s*(?:MT|WS)\\s*\\d+\\s*\\u27E7";
    private static final java.util.regex.Pattern CS_MARKER =
            java.util.regex.Pattern.compile("\\u27E6\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7");
    /** A protected-term placeholder ({@code ⟦0⟧}: player name / do-not-translate term)
     *  or a paragraph break ({@code ⟦PB0⟧}). Neither is wording to translate, and their
     *  digits/letters must not be mistaken for a machine code ("⟦0⟧ XP" is not "0XP"). */
    private static final java.util.regex.Pattern PLACEHOLDER_OR_BREAK =
            java.util.regex.Pattern.compile("\\u27E6\\s*(?:PB\\s*)?\\d+\\s*\\u27E7");
    /** Only the protected-term placeholder ({@code ⟦0⟧}), not a paragraph break. */
    private static final java.util.regex.Pattern PROTECTED_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\u27E6\\s*\\d+\\s*\\u27E7");
    private static final java.util.regex.Pattern AM_PM_AFTER_DIGIT =
            java.util.regex.Pattern.compile("(?i)(?<=\\d)\\s*[ap]\\.?m\\.?\\b");
    /** One of this protocol's reserved token names (PB paragraph break, CS/WS style
     *  runs, MT value slots — see {@link ParagraphModel}, {@link TemplateText}) echoed
     *  against a CJK quotation/bracket character instead of the real {@code ⟦…⟧}
     *  delimiter ("『PB0』" instead of "⟦PB0⟧"). A translator that garbles the rare
     *  bracket glyph but still "quotes" the token name this way leaves protocol-shaped
     *  debris in otherwise fluent prose; unlike a bare "CS50"-style name with no adjacent
     *  bracket (deliberately left alone elsewhere — see {@code TranslationCache
     *  #matchingCsShape}), PB/CS/WS/MT digits sitting directly against one of these
     *  brackets is never legitimate content. */
    private static final java.util.regex.Pattern RESHAPED_PROTOCOL_TOKEN =
            java.util.regex.Pattern.compile(
                    "[「『【]\\s*/?(?:PB|CS|WS|MT)\\d+"
                            + "|/?(?:PB|CS|WS|MT)\\d+\\s*[」』】]");
    private static final java.util.regex.Pattern NON_MACHINE_CODE =
            java.util.regex.Pattern.compile("[^A-Za-z0-9_]");
    private static final java.util.regex.Pattern SHORT_MACHINE_CODE_WITH_DIGIT =
            java.util.regex.Pattern.compile("(?i)(?:[a-z]{1,3}_?\\d+|\\d+_?[a-z]{1,3})");
    private static final java.util.regex.Pattern SHORT_MACHINE_CODE =
            java.util.regex.Pattern.compile("(?i)[a-z]{1,3}");
    private static final java.util.regex.Pattern QUOTED_STRUCTURED_KEY =
            java.util.regex.Pattern.compile("\"[A-Za-z0-9_.:-]+\"\\s*:");
    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern ANCHORED_TEXT_FIELD =
            java.util.regex.Pattern.compile(
                    "(?:" + MT_TOKEN_SOURCE
                            + "\\s+|(?i:\\b(?:location|area|zone|region|biome|island|world)\\s*:\\s*))"
                            + "([A-Z][A-Za-z0-9_'’\\-]*"
                            + "(?:\\s+(?:[A-Z0-9][A-Za-z0-9_'’\\-]*|of|the|and|to)){0,5})"
                            + "(?=\\s*(?:" + MT_TOKEN_SOURCE + "|$))");

    private TextFilter() {
    }

    // P1.6: shouldTranslate() is re-evaluated every render frame for every visible
    // surface (lookup/warm/ready checks across chat, tooltip, scoreboard, name tags…),
    // and it is a pure function of its three inputs. Memoising it is therefore always
    // safe (no invalidation is ever needed). Bounded LRU, exactly like TemplateText's
    // own per-string MEMO: single-entry eviction on overflow, never a whole-table
    // clear, so a full cache never causes a next-frame cost spike.
    private static final int SHOULD_TRANSLATE_MEMO_MAX = 4096;
    private static final java.util.Map<String, Boolean> SHOULD_TRANSLATE_MEMO =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, Boolean> eldest) {
                            return size() > SHOULD_TRANSLATE_MEMO_MAX;
                        }
                    });

    public static boolean shouldTranslate(String text, String targetLang) {
        return shouldTranslate(text, targetLang, null);
    }

    /** Optional locale hint for surfaces whose source language is known. This is kept
     * away from free-form chat so Chinese player messages are not translated again.
     *
     * <p>Memoised by the full (targetLang, sourceLangHint, text) triple: the hint
     * changes the verdict for identical text (see the ja/ko item-locale tests), so it
     * must be part of the key, not folded away.</p> */
    public static boolean shouldTranslate(String text, String targetLang, String sourceLangHint) {
        if (text == null) return false;
        String memoKey = targetLang + '\u0000' + (sourceLangHint == null ? "" : sourceLangHint)
                + '\u0000' + text;
        Boolean memoized = SHOULD_TRANSLATE_MEMO.get(memoKey);
        if (memoized != null) return memoized;
        boolean result = shouldTranslateUncached(text, targetLang, sourceLangHint);
        SHOULD_TRANSLATE_MEMO.put(memoKey, result);
        return result;
    }

    /** Original body, untouched apart from the null check moving to the memoised
     *  wrapper above. */
    private static boolean shouldTranslateUncached(String text, String targetLang,
                                                   String sourceLangHint) {
        if (isInternalDebugText(text)) return false;
        // Judge translatability on the CORE text: decorative icons (⚔ ✪ ☀ 🔹 …) are
        // stripped first, so "⚔ Heroic Spirit Sceptre ✪✪✪✪✪" is judged as
        // "Heroic Spirit Sceptre" instead of being rejected/garbled by its icons.
        // A line that is ONLY icons still comes out empty → untranslatable, as before.
        String t = stripSectionCodes(stripDecorativeSymbols(text)).strip();
        if (t.isEmpty()) return false;
        if (!hasLettersOutsideVolatileTokens(t)) return false;
        if (looksStructuredData(t)) return false;
        // CS/MT are internal protocol labels, not user-visible English. URLs and live
        // values are volatile verbatim slots too. Count language only on the semantic
        // text or already-Chinese rows such as "人氣鑽石: [n]/[n]" and
        // "前往 hypixel.net/ptl" are pointlessly submitted forever.
        String languageSample = languageSample(t);
        // Han characters are shared by Chinese and Japanese. A Japanese item can be
        // mostly Han while its kana still makes it unambiguously non-Chinese. Korean
        // text needs the same protection when translating into Chinese.
        String hint = sourceLangHint == null ? ""
                : sourceLangHint.toLowerCase(java.util.Locale.ROOT);
        boolean knownJapaneseOrKorean = hint.startsWith("ja") || hint.startsWith("ko");
        if (isTargetChinese(targetLang)
                && !knownJapaneseOrKorean
                && !containsJapaneseKanaOrHangul(languageSample)
                && isAlreadyChinese(languageSample)) return false;
        return true;
    }

    static boolean containsJapaneseKanaOrHangul(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x3040 && cp <= 0x30FF)        // Hiragana + Katakana
                    || (cp >= 0x31F0 && cp <= 0x31FF) // Katakana extensions
                    || (cp >= 0xFF66 && cp <= 0xFF9D) // Halfwidth Katakana
                    || (cp >= 0x1100 && cp <= 0x11FF) // Hangul Jamo
                    || (cp >= 0x3130 && cp <= 0x318F) // Hangul compatibility Jamo
                    || (cp >= 0xAC00 && cp <= 0xD7AF)) { // Hangul syllables
                return true;
            }
        }
        return false;
    }

    private static String languageSample(String text) {
        String withoutStyles = CS_MARKER.matcher(text == null ? "" : text).replaceAll("");
        TemplateText.Prepared prepared = TemplateText.prepare(withoutStyles);
        String sample = prepared.changed() ? prepared.text() : withoutStyles;
        sample = MT_TOKEN.matcher(sample).replaceAll("");
        return stripSectionCodes(stripDecorativeSymbols(sample)).strip();
    }

    /** Debug-HUD rows are generated by this mod and must never feed back into any
     *  translation surface. This second-line guard stops recursive request logging even
     *  if a loader-specific drawing bypass is accidentally missed. */
    public static boolean isInternalDebugText(String text) {
        if (text == null) return false;
        String t = stripSectionCodes(text).stripLeading();
        return t.startsWith("MT DEBUG") || t.startsWith("[AI #")
                || t.startsWith("[GT #")
                || t.startsWith("[AI x") || t.startsWith("[Google #")
                || t.startsWith("[Google x");
    }

    /**
     * Decorative icon character (⚔ ✪ ☀ ⛃ ■ ➤ 🔹, modded PUA icon fonts): Unicode
     * OTHER_SYMBOL, the private-use areas, and stray surrogates. Deliberately EXCLUDED:
     * ordinary ASCII and CJK punctuation (、。！ — categories P*, translations use them),
     * math signs (Sm — the '+' of "+30" must travel with its number), currency (Sc),
     * the ⟦⟧ token brackets (Ps/Pe), and '§' (style prefix; guarded explicitly for old
     * Unicode tables where it was still So).
     */
    public static boolean isDecorativeSymbol(int cp) {
        if (cp == 0xA7 || cp == 0x27E6 || cp == 0x27E7) return false; // § ⟦ ⟧
        if (cp == 0xFFFD) return false; // REPLACEMENT CHAR is corruption evidence, not an icon
        int type = Character.getType(cp);
        return type == Character.OTHER_SYMBOL
                || type == Character.PRIVATE_USE
                || type == Character.SURROGATE;
    }

    /** Remove decorative icon characters ({@link #isDecorativeSymbol}). Null-safe;
     *  returns the same instance when nothing was decorative. */
    public static String stripDecorativeSymbols(String text) {
        if (text == null) return null;
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (isDecorativeSymbol(cp)) {
                if (sb == null) sb = new StringBuilder(text.length()).append(text, 0, i);
            } else if (sb != null) {
                sb.appendCodePoint(cp);
            }
            i += n;
        }
        return sb == null ? text : sb.toString();
    }

    private static boolean hasLettersOutsideVolatileTokens(String t) {
        TemplateText.Prepared prepared = TemplateText.prepare(t);
        String skeleton = MT_TOKEN.matcher(prepared.changed() ? prepared.text() : t).replaceAll("");
        // A colour-run marker (\u27E6CS0\u27E7/\u27E6/CS0\u27E7) is presentation, not content \u2014 but its own
        // literal "CS" letters are real alphabetic characters, so a line that is colour
        // markers wrapped around nothing but MT/WS slots (a Hypixel-style multi-coloured
        // numeric HUD row, e.g. "\u27E6CS0\u27E7\u27E6MT0\u27E7/\u27E6MT1\u27E7\u27E6/CS0\u27E7 \u27E6WS0\u27E7 \u27E6CS1\u27E7\u27E6MT2\u27E7\u27E6/CS1\u27E7\u2026") was
        // misjudged as "has letters" by the marker text itself, not by any real wording,
        // and sent to the backend with nothing translatable in it (debug overlay then
        // shows it as pure "{\u503C}/{\u503C}{\u6B04\u8DDD}\u2026" once compactText strips the very markers that
        // fooled this check). Strip them before judging content, exactly like every other
        // content check in this class.
        skeleton = CS_MARKER.matcher(skeleton).replaceAll("");
        boolean protectedTerm = skeleton.indexOf('\u27E6') >= 0
                && PROTECTED_PLACEHOLDER.matcher(skeleton).find();
        skeleton = PLACEHOLDER_OR_BREAK.matcher(skeleton).replaceAll(" ");
        skeleton = stripSectionCodes(stripDecorativeSymbols(skeleton)).strip();
        skeleton = AM_PM_AFTER_DIGIT.matcher(skeleton).replaceAll("");
        if (!hasLetters(skeleton)) return false;
        int generatedSlots = prepared.values().size();
        // A protected placeholder stands for a whole name or term, so separate words around
        // it are prose ("[B] ⟦0⟧ [Lv27]"). Gluing them into one compact string would invent
        // a machine code ("BLv27"); only one lone run beside the name ("⟦0⟧ n6400") can
        // still be one. That exception covers only the glued-code rule: a row whose letters
        // are a few characters among several generated numbers ("⟦0⟧: x: 8, y: 9, z: 10")
        // stays a machine row, as before. Text without a protected placeholder keeps the
        // unchanged rule.
        if (protectedTerm && asciiWordRuns(skeleton) > 1) {
            return !(generatedSlots >= 2 && SHORT_MACHINE_CODE.matcher(
                    NON_MACHINE_CODE.matcher(skeleton).replaceAll("")).matches());
        }
        return !isOnlyShortMachineCode(skeleton, generatedSlots);
    }

    /** Number of maximal {@code [A-Za-z0-9_]} runs (the characters the machine-code rule keeps). */
    private static int asciiWordRuns(String text) {
        int runs = 0;
        boolean inRun = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean part = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '_';
            if (part && !inRun) runs++;
            inRun = part;
        }
        return runs;
    }

    /** A dynamic HUD row occasionally leaves only a compact server code after every
     *  template slot is removed (for example three MT slots plus {@code n6400}). It
     *  contains a letter, but no natural language and must not consume a request. */
    private static boolean isOnlyShortMachineCode(String skeleton, int generatedSlots) {
        String compact = NON_MACHINE_CODE.matcher(skeleton).replaceAll("");
        if (SHORT_MACHINE_CODE_WITH_DIGIT.matcher(compact).matches()) return true;
        return generatedSlots >= 2 && SHORT_MACHINE_CODE.matcher(compact).matches();
    }
    public static boolean hasLetters(String t) {
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) return true;
        }
        return false;
    }

    public static boolean isTargetChinese(String targetLang) {
        return targetLang != null && targetLang.toLowerCase().startsWith("zh");
    }

    public static boolean isMostlyCjk(String t) {
        int letters = 0;
        int cjk = 0;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letters++;
                if (isCjk(cp)) cjk++;
            }
        }
        if (letters == 0) return false;
        return (double) cjk / letters >= CJK_THRESHOLD;
    }

    /**
     * Whether {@code text} is already Chinese for the purpose of skipping the translator:
     * CJK letters make up at least {@value #CJK_THRESHOLD} of the letters that could still
     * need translating. Names that stay in Latin script inside a Chinese UI are not such
     * letters, so they no longer drag a Chinese row below the line: all-capital
     * abbreviations ("FPS", "TNT", "UHC", "XP"), identifiers and mixed-case brand spellings
     * ("sRGB", "PvP", "iPhone"), anything with a digit ("Mipmap 4", "v2"), a lone capital
     * letter ("X"), and capitalised words that could be proper nouns ("Minecraft", "Hypixel",
     * "Mipmap"). Ordinary lower-case English words do count, so "Right click to open 設定"
     * is still English that needs translating, and text without any CJK letter is never
     * "already Chinese" however its capitals fall.
     *
     * <p>{@link #isMostlyCjk} keeps the plain ratio (every letter counts); this is the one
     * the pre-send filter uses. Kana and Hangul are rejected before it is asked.</p>
     */
    public static boolean isAlreadyChinese(String text) {
        if (text == null) return false;
        int cjk = 0;
        int prose = 0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isAsciiAlnum(c)) {
                int start = i;
                int letters = 0;
                int upper = 0;
                boolean digit = false;
                boolean innerUpper = false;
                while (i < n && isAsciiAlnum(text.charAt(i))) {
                    char w = text.charAt(i);
                    if (w >= '0' && w <= '9') {
                        digit = true;
                    } else {
                        letters++;
                        if (w >= 'A' && w <= 'Z') {
                            upper++;
                            if (i > start) innerUpper = true;
                        }
                    }
                    i++;
                }
                if (letters == 0 || digit) continue;                 // numbers, ids, versions
                if (upper == letters) continue;                       // abbreviation or a lone capital
                boolean titlecase = text.charAt(start) >= 'A' && text.charAt(start) <= 'Z'
                        && upper == 1;
                if (titlecase || innerUpper) continue;                // proper noun, brand, identifier
                prose += letters;                                     // plain lower-case word
                continue;
            }
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                if (isCjk(cp)) cjk++;
                else prose++;
            }
        }
        if (cjk == 0) return false;
        return (double) cjk / (cjk + prose) >= CJK_THRESHOLD;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    public static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)    // CJK Unified Ideographs
                || (cp >= 0x3400 && cp <= 0x4DBF) // CJK Extension A
                || (cp >= 0xF900 && cp <= 0xFAFF) // CJK Compatibility Ideographs
                || (cp >= 0x20000 && cp <= 0x2A6DF); // CJK Extension B
    }

    public static boolean looksStructuredData(String text) {
        if (text == null) return false;
        String t = text.strip();
        if (t.length() < 8) return false;
        char first = t.charAt(0);
        char last = t.charAt(t.length() - 1);
        boolean wrapped = (first == '{' && last == '}')
                || (first == '[' && last == ']')
                || (first == '(' && last == ')');
        int quotedKeys = 0;
        java.util.regex.Matcher m = QUOTED_STRUCTURED_KEY.matcher(t);
        while (m.find()) {
            quotedKeys++;
            if (quotedKeys >= 2) return true;
        }
        return wrapped && (t.contains("\":") || t.contains("\\\":") || t.contains("="));
    }

    /**
     * Detects common mojibake left by old/bad cache entries, especially UTF-8 text
     * that was decoded as a Japanese legacy code page (for example "你好" becoming
     * "菴螂ｽ"). This is intentionally conservative: valid Traditional/Simplified
     * Chinese should not contain halfwidth kana, replacement characters, or PUA
     * bytes from broken decoding.
     */
    public static boolean isLikelyMojibake(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFFFD) return true; // replacement character
            if (cp >= 0xFF61 && cp <= 0xFF9F) return true; // halfwidth kana/punctuation
            if (cp >= 0xE000 && cp <= 0xF8FF) return true; // private-use artifacts
        }
        return false;
    }

    private static final java.util.regex.Pattern URL_OR_DOMAIN = java.util.regex.Pattern.compile(
            "(?i)(?:https?://[^\\s\"'<>\\u27E6\\u27E7]+|www\\.[^\\s\"'<>\\u27E6\\u27E7]+"
                    + "|(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/[^\\s\"'<>\\u27E6\\u27E7]*)?)");

    /** "hxxp"/"hxxps" is a common human-readable "defanging" of a link that still reads as
     *  one; fold it back to "http"/"https" before scanning. */
    private static final java.util.regex.Pattern HXXP_SCHEME =
            java.util.regex.Pattern.compile("(?i)hxxp");

    /** Spelled-out "evil dot com" style evasion: collapse " dot "/"dot" between two
     *  alphanumeric runs back into a literal '.'. */
    private static final java.util.regex.Pattern DOT_WORD =
            java.util.regex.Pattern.compile("(?i)([a-z0-9])\\s*\\bdot\\b\\s*(?=[a-z0-9])");

    /**
     * True when {@code translated} contains an http(s) link, a {@code www.} address or a
     * bare domain that {@code source} does not already contain. A hub-shared translation
     * introducing a URL/domain the original author never wrote is exactly the kind of
     * content a downloaded cache row must never be allowed to inject silently.
     */
    public static boolean hasForeignUrl(String source, String translated) {
        if (translated == null) return false;
        java.util.Set<String> sourceUrls = extractUrls(source);
        java.util.Set<String> translatedUrls = extractUrls(translated);
        translatedUrls.removeAll(sourceUrls);
        return !translatedUrls.isEmpty();
    }

    private static java.util.Set<String> extractUrls(String text) {
        java.util.Set<String> found = new java.util.HashSet<>();
        if (text == null) return found;
        java.util.regex.Matcher matcher = URL_OR_DOMAIN.matcher(normalizeForUrlScan(text));
        while (matcher.find()) {
            found.add(matcher.group().toLowerCase(java.util.Locale.ROOT));
        }
        return found;
    }

    /**
     * Detection-only projection fed to {@link #URL_OR_DOMAIN}: never used for anything the
     * player actually sees. A1: §-style codes are stripped first, exactly like every other
     * content check in this class (otherwise "evil§r.com" renders as "evil.com" in-game but
     * never matches the regex). A2 and beyond: fold the common ways a domain can be spelled
     * so a literal "." regex does not see it — fullwidth ASCII letters/digits/period via
     * NFKC, the CJK ideographic full stops U+3002/U+FF61 that NFKC does NOT fold to ASCII
     * '.', "hxxp(s)" defanging, spelled-out " dot ", and Markdown "[text](url)" bracket
     * syntax (stripped so a domain split across the brackets is still read as one run).
     */
    private static String normalizeForUrlScan(String text) {
        if (text == null) return null;
        String normalized = java.text.Normalizer.normalize(stripSectionCodes(text),
                java.text.Normalizer.Form.NFKC);
        normalized = normalized.replace('。', '.').replace('｡', '.');
        normalized = HXXP_SCHEME.matcher(normalized).replaceAll("http");
        normalized = DOT_WORD.matcher(normalized).replaceAll("$1.");
        normalized = normalized.replace("](", " ")
                .replace("[", " ").replace("]", " ")
                .replace("(", " ").replace(")", " ");
        return normalized;
    }

    /**
     * Detects a translator response that echoed a reserved protocol token name using
     * a look-alike CJK bracket instead of copying the real {@code ⟦…⟧} delimiter (see
     * {@link #RESHAPED_PROTOCOL_TOKEN}). The exact-bracket token checks elsewhere
     * ({@code OpenAiTranslator#tokensMatch}, {@code TranslationCache#usable}) compare
     * only real {@code ⟦…⟧} spans, so a reshaped token neither drops nor duplicates
     * any of THEIR counted tokens — it is invisible noise to a multiset/sequence
     * comparison — even though it is definite protocol debris the user should never
     * see. Checked only on the translated side: the source is program-built and never
     * legitimately contains this shape.
     */
    public static boolean hasReshapedProtocolToken(String text) {
        return text != null && RESHAPED_PROTOCOL_TOKEN.matcher(text).find();
    }

    private static boolean isAsciiLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    /** A literal §-formatting sequence baked into the string by the server ('§' + the next
     *  char, exactly as Minecraft's own parser consumes it; DOTALL so "§\n" can't survive).
     *  A templated sequence like "§⟦MT0⟧" is different: the style-code character itself
     *  has already been lifted into a slot, so the token must remain intact. */
    private static final java.util.regex.Pattern SECTION_CODE =
            java.util.regex.Pattern.compile("§(?!" + MT_TOKEN_SOURCE + ").", java.util.regex.Pattern.DOTALL);

    /** Remove literal §-formatting sequences. They are STYLE, not text: any content check
     *  that treats their code letters as letters mis-reads the string (see
     *  {@link #isPartialTransliteration}). Null-safe. */
    public static String stripSectionCodes(String text) {
        return text == null ? null : SECTION_CODE.matcher(text).replaceAll("");
    }

    /**
     * True when a marker-exterior fragment contains layout and punctuation only.
     * Translators commonly insert a target-language comma between two reordered colour
     * spans; accepting punctuation is safe because it carries no translatable wording and
     * the renderer can attach it to the preceding style. Letters and digits remain
     * forbidden, so escaped words can never silently inherit the wrong colour.
     *
     * <p>A decorative icon ({@link #isDecorativeSymbol}, e.g. the "●" bullet Hypixel
     * SkyBlock prefixes an item/scroll name with, before that name's own ⟦CSn⟧ colour run)
     * is likewise accepted: it is not translatable wording — every OTHER content check in
     * this class already strips it first via {@link #stripDecorativeSymbols} before judging
     * meaning — and excluding it here made {@code TranslationCache#matchingCsShape}/{@code
     * csShape} (and {@code FabricTextStyle#outsideMarkerLayoutOnly}) reject the exact-style
     * projection of any such icon-prefixed coloured row as an unparseable shape. The write
     * side ({@code TranslationCache#writeStyleProjection}) then permanently failed to cache
     * that row's own colour topology, forcing {@code TranslationService#decide}'s
     * style-fallback path on every lookup — invisible for a single top-level consumer that
     * re-anchors it (see {@code FabricTextStyle#markedChat}'s {@code isStyleFallback}
     * branch), but this value is also spliced directly into a larger composed tooltip by
     * {@code TranslationService#resolveEnchantName} (segment-cache tooltip composition),
     * where no such re-anchoring ever runs.
     */
    public static boolean isLayoutOrPunctuationOnly(String text) {
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) continue;
            if (isDecorativeSymbol(cp)) continue;
            int type = Character.getType(cp);
            if (type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION) continue;
            return false;
        }
        return true;
    }

    /** Remove internal colour-run markers used between the renderer and cache. */
    public static String stripTranslationMarkers(String text) {
        return text == null ? null : CS_MARKER.matcher(text).replaceAll("");
    }

    /** Content-only projection used when comparing differently styled surfaces. */
    public static String stripFormatting(String text) {
        return stripSectionCodes(stripTranslationMarkers(stripStyleFallback(text)));
    }

    /**
     * Detect an incomplete CJK result that translated surrounding HUD text but left
     * a location/title after an icon slot unchanged (for example "Forest ... 魔力").
     */
    public static boolean hasUntranslatedAnchoredField(String source, String translated) {
        if (source == null || translated == null) return false;
        String s = stripFormatting(source);
        String t = stripFormatting(translated);
        if (!containsCjk(t)) return false;

        java.util.regex.Matcher matcher = ANCHORED_TEXT_FIELD.matcher(s);
        while (matcher.find()) {
            String field = matcher.group(1).strip();
            // A pure Roman numeral ("VII", "IV") is enchantment/level NUMBERING, not an
            // English word that must be translated: it is legitimately left as-is
            // (its content benefit already comes from the validator, not a new slot;
            // see P1.1/P1.4). Flagging it here rejected an otherwise-good translation
            // of rows like "Sharpness VII" forever.
            if (field.length() >= 3 && !isRomanNumeral(field) && containsWholePhrase(t, field)) {
                return true;
            }
        }
        return false;
    }

    /** Strict, complete Roman-numeral shape (1-3999). Deliberately case-SENSITIVE
     *  (upper-case only, matching every real Hypixel enchant/level numeral such as
     *  "VII" or "XVI"): a case-insensitive version would also accept ordinary mixed-
     *  case English words built only from M/D/C/L/X/V/I letters ("Mix", "Civil").
     *  Used only to exempt numbering fields from the anchored-field check above;
     *  never applied to a slot/template decision (P1.4 explicitly defers a Roman
     *  numeral value slot to a later phase). */
    private static final java.util.regex.Pattern ROMAN_NUMERAL = java.util.regex.Pattern.compile(
            "M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})");

    public static boolean isRomanNumeral(String text) {
        if (text == null || text.isEmpty()) return false;
        return ROMAN_NUMERAL.matcher(text).matches();
    }

    /** Paragraph convention used by Minecraft books/mod panels: when authors do not put
     * a blank row between prose paragraphs, the first row of the next paragraph is often
     * indented by two ASCII/NBSP columns, one tab, or one full-width ideographic space. */
    public static boolean startsIndentedParagraph(String text) {
        return ParagraphModel.startsIndentedParagraph(text);
    }

    private static boolean containsWholePhrase(String text, String phrase) {
        for (int at = text.indexOf(phrase); at >= 0; at = text.indexOf(phrase, at + 1)) {
            int end = at + phrase.length();
            boolean left = at == 0 || !isAsciiWordChar(text.charAt(at - 1));
            boolean right = end == text.length() || !isAsciiWordChar(text.charAt(end));
            if (left && right) return true;
        }
        return false;
    }

    private static boolean isAsciiWordChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                || c >= '0' && c <= '9' || c == '_';
    }

    public static boolean isTemplatedSectionCode(String text, int sectionPos) {
        if (text == null || sectionPos < 0 || sectionPos + 1 >= text.length()
                || text.charAt(sectionPos) != '§') {
            return false;
        }
        java.util.regex.Matcher m = MT_TOKEN.matcher(text);
        m.region(sectionPos + 1, text.length());
        return m.lookingAt();
    }

    /**
     * Detects a HALF-transliterated word: a translation that fuses a leftover fragment of an
     * original Latin word directly onto the CJK target script. Two flavours of the same bug:
     * the AI turning "jacob" into "傑cob" (the "Ja" syllable transliterated, "cob" glued on),
     * and — even when every word is translated correctly — "Jacob's Contest" coming back as
     * "雅各的競賽t" (the final "t" of "Contest" fused onto 賽). Such hybrids are never correct,
     * so callers reject them (show the original instead) and keep them out of the cache —
     * otherwise the poison is cached and, via the AI→Google read-through fallback, served to
     * every surface (scoreboard / tooltip / name tag / boss bar / book / HUD) at once.
     *
     * <p><b>Deliberately conservative</b> to minimise false positives: a wrong reject only
     * shows the original English (acceptable), but a legitimate translation must never be
     * dropped. {@code translated} must contain a CJK ideograph; then each whitespace-split
     * {@code source} token that is ASCII-letter dominant and at least 4 characters long is
     * examined, and it is a hybrid when {@code translated} holds a maximal run of ASCII
     * letters that is a PROPER (strictly shorter), case-insensitive PREFIX or SUFFIX of such a
     * token AND that run is GLUED to a CJK ideograph, with a <b>direction-aware</b> floor:</p>
     * <ul>
     *   <li><b>Trailing residue</b> (a CJK ideograph immediately BEFORE the run — the AI left
     *       the tail of a half-converted word): a run as short as ONE letter flags it — the
     *       "t" of "競賽t", "n" of "南瓜n", "e" of "蘋果e", "cob" of "傑cob".</li>
     *   <li><b>Leading residue</b> (a CJK ideograph immediately AFTER the run, none before):
     *       require at least TWO letters — so a genuine residue like "st" of "st史蒂夫" flags,
     *       but a legitimate Chinese idiom with a single Latin head letter ("T恤", "A級",
     *       "X光") is NOT flagged.</li>
     * </ul>
     *
     * <p>A run that is not adjacent to any CJK char is never flagged ("資訊 info" is left
     * alone). A fully-kept token is never flagged either: its run equals the whole token,
     * which is not a PROPER prefix/suffix — so "TNT"→"TNT炸藥", "Java"→"Java版" and
     * "SkyBlock"→"SkyBlock年度" pass. A short kept English word ("Buy now"→"購買 now") is below
     * the 4-char token floor, and an ASCII run that is not an affix of any source token
     * ("distance"→"距離km") is left alone.</p>
     */
    public static boolean isPartialTransliteration(String source, String translated) {
        if (source == null || translated == null) return false;
        // Judge the REAL content: literal § codes baked in by the server are style, not
        // text. Left in, their code letters fuse into the ASCII runs — "§d§lSB年500" reads
        // as run "lSB" glued to 年, a proper suffix of source token "§d§lSB" → a false
        // half-transliteration verdict that discards a perfectly good cache entry every
        // frame (the 30,892-line tab-header disk-append incident). Only § sequences are
        // stripped here — ⟦⟧ markers and whitespace stay, the judgement needs them.
        String s = stripSectionCodes(source).strip();
        String t = stripSectionCodes(translated);
        if (s.isEmpty() || t.isEmpty()) return false;

        // Precondition: the translation must actually contain CJK (else nothing was converted).
        if (!containsCjk(t)) return false;

        // Evaluate each whitespace-split source token that is ASCII-letter dominant + long enough.
        // Trailing punctuation/possessive are stripped FIRST ("Bonzo's," -> "Bonzo"): the
        // possessive "'s"/"'s" is Latin grammar the translator legitimately drops when a kept
        // name is followed by a Chinese possessive particle ("Bonzo's Mask" -> "Bonzo的面具"),
        // and trailing punctuation is not part of the word either. Without this, the
        // untouched apostrophe-s suffix is a PROPER prefix of itself glued to the following
        // CJK possessive character, and the kept name was misjudged as a half-transliteration.
        for (String rawToken : WHITESPACE.split(s)) {
            String token = stripTrailingPunctuationAndPossessive(rawToken);
            if (!isAsciiDominantWord(token)) continue;
            if (hasGluedResidueOf(token, t)) return true;
        }
        return false;
    }

    /** Strips a trailing possessive ({@code 's}/{@code 's}) and any trailing ASCII/CJK
     *  punctuation (commas, periods, quotes, colons…) from a whitespace-split token,
     *  leaving the bare word a translator would actually keep or convert. The apostrophe
     *  itself is never stripped as plain punctuation ahead of the possessive check, so
     *  "Bonzo's," strips the trailing comma first, then the possessive, down to "Bonzo". */
    private static String stripTrailingPunctuationAndPossessive(String token) {
        int end = token.length();
        while (end > 0) {
            char c = token.charAt(end - 1);
            if (c == '\'' || c == '’' || Character.isLetterOrDigit(c)) break;
            end--;
        }
        if (end >= 2) {
            char last = token.charAt(end - 1);
            char before = token.charAt(end - 2);
            if ((last == 's' || last == 'S') && (before == '\'' || before == '’')) {
                end -= 2;
            }
        }
        return end == token.length() ? token : token.substring(0, end);
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) return true;
        }
        return false;
    }

    /** Rule-2 applied per token: at least 4 ASCII letters and ASCII letters are the MAJORITY. */
    private static boolean isAsciiDominantWord(String token) {
        if (token.length() < 4) return false;
        int asciiLetters = 0;
        for (int i = 0; i < token.length(); ) {
            int cp = token.codePointAt(i);
            i += Character.charCount(cp);
            if (isAsciiLetter(cp)) asciiLetters++;
        }
        return asciiLetters >= 4 && asciiLetters * 2 > token.length();
    }

    /**
     * True when {@code translated} contains a maximal ASCII-letter run that is a PROPER
     * (strictly shorter), case-insensitive prefix/suffix of {@code token} AND is glued to a
     * CJK ideograph, using a direction-aware length floor: a TRAILING residue (CJK before the
     * run) flags at length ≥1, a purely LEADING residue (CJK only after the run) flags at
     * length ≥2. A run not adjacent to any CJK char is never flagged.
     */
    private static boolean hasGluedResidueOf(String token, String translated) {
        String lowerToken = token.toLowerCase(java.util.Locale.ROOT);
        int n = translated.length();
        int i = 0;
        while (i < n) {
            if (isAsciiLetter(translated.charAt(i))) {
                int j = i;
                while (j < n && isAsciiLetter(translated.charAt(j))) j++;
                String run = translated.substring(i, j).toLowerCase(java.util.Locale.ROOT);
                if (run.length() < lowerToken.length()                 // PROPER: strictly shorter
                        && (lowerToken.startsWith(run) || lowerToken.endsWith(run))) {
                    boolean gluedBefore = i > 0 && isCjk(translated.codePointBefore(i));
                    boolean gluedAfter = j < n && isCjk(translated.codePointAt(j));
                    // Trailing residue (CJK…Latin): floor 1. Leading residue (Latin…CJK): floor 2,
                    // so a single Latin head letter of a Chinese idiom (T恤 / A級 / X光) is kept.
                    if ((gluedBefore && run.length() >= 1)
                            || (gluedAfter && !gluedBefore && run.length() >= 2)) {
                        // A character embedded in (or glued directly onto) the token that is not
                        // itself an ASCII letter — a digit/underscore inside a Hypixel account name
                        // ("Ab0cdefgh"), the leading slash of a command ("/coopclear"), or a whole
                        // "⟦CS0⟧" colour marker glued straight onto a name with no separating space —
                        // splits an otherwise fully-kept token into two letter runs here. The
                        // fragment after the split can then accidentally equal a proper prefix/suffix
                        // of the whole token — a false "residue" even though nothing was actually
                        // transliterated away. Guard: if extending this run outward through any
                        // adjacent non-whitespace, non-CJK characters reconstructs the WHOLE token
                        // verbatim, the name/command/marker was kept intact (only a Chinese
                        // possessive/particle is glued on) and this is not a hybrid. The expansion can
                        // never cross into text that WAS actually transliterated: a genuine residue
                        // (jacob -> 傑cob, Pumpkin -> 南瓜n) always has a real CJK ideograph bounding
                        // it, and CJK is exactly what stops the expansion — so real bugs are still
                        // caught below.
                        if (!isWholeTokenPreservedAround(token, translated, i, j)) {
                            return true;
                        }
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
        return false;
    }

    /** True when the {@code [start, end)} ASCII-letter run is only a fragment of {@code
     *  token} because some non-letter character inside/around the token itself split it
     *  — not because part of the word was transliterated away. Expands {@code [start,
     *  end)} outward through adjacent non-whitespace, non-CJK characters in {@code
     *  translated} (digits, underscores, slashes, "⟦CS0⟧"-style markers glued on with no
     *  separating space, …) and checks whether the result equals {@code token} exactly
     *  (case-insensitive). Safe by construction: {@link #hasGluedResidueOf} only reaches
     *  this guard when a real CJK ideograph is already glued to one side of the run, and
     *  CJK is excluded from the expansion — so it can never absorb text a translator
     *  actually replaced, only the untouched punctuation/markers around a kept token. */
    private static boolean isWholeTokenPreservedAround(String token, String translated,
                                                        int start, int end) {
        int s = start;
        while (s > 0 && isNonCjkNonSpace(translated.charAt(s - 1))) s--;
        int e = end;
        while (e < translated.length() && isNonCjkNonSpace(translated.charAt(e))) e++;
        return translated.substring(s, e).equalsIgnoreCase(token);
    }

    private static boolean isNonCjkNonSpace(char c) {
        return !Character.isWhitespace(c) && !isCjk(c);
    }
}
