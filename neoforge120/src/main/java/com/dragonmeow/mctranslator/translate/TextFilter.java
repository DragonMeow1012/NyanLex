package com.dragonmeow.mctranslator.translate;

/**
 * Decides whether a piece of text is worth translating.
 *
 * <p>Skips empty strings, strings without any letters (pure numbers / symbols /
 * Minecraft score values), and — when the target is Chinese — text that is
 * already mostly Chinese, to avoid pointless "Chinese to Chinese" round-trips.</p>
 */
public final class TextFilter {

    /** Fraction of letters that must be CJK for text to count as "already Chinese". */
    private static final double CJK_THRESHOLD = 0.5;

    private TextFilter() {
    }

    public static boolean shouldTranslate(String text, String targetLang) {
        if (text == null) return false;
        // Judge translatability on the CORE text: decorative icons (⚔ ✪ ☀ 🔹 …) are
        // stripped first, so "⚔ Heroic Spirit Sceptre ✪✪✪✪✪" is judged as
        // "Heroic Spirit Sceptre" instead of being rejected/garbled by its icons.
        // A line that is ONLY icons still comes out empty → untranslatable, as before.
        String t = stripDecorativeSymbols(text).strip();
        if (t.isEmpty()) return false;
        if (!hasLetters(t)) return false;
        if (looksStructuredData(t)) return false;
        if (isTargetChinese(targetLang) && isMostlyCjk(t)) return false;
        return true;
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
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"[A-Za-z0-9_.:-]+\"\\s*:")
                .matcher(t);
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

    private static boolean isAsciiLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    /** A literal §-formatting sequence baked into the string by the server ('§' + the next
     *  char, exactly as Minecraft's own parser consumes it; DOTALL so "§\n" can't survive). */
    private static final java.util.regex.Pattern SECTION_CODE =
            java.util.regex.Pattern.compile("§.", java.util.regex.Pattern.DOTALL);

    /** Remove literal §-formatting sequences. They are STYLE, not text: any content check
     *  that treats their code letters as letters mis-reads the string (see
     *  {@link #isPartialTransliteration}). Null-safe. */
    public static String stripSectionCodes(String text) {
        return text == null ? null : SECTION_CODE.matcher(text).replaceAll("");
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
        for (String token : s.split("\\s+")) {
            if (!isAsciiDominantWord(token)) continue;
            if (hasGluedResidueOf(token, t)) return true;
        }
        return false;
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
                        return true;
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
        return false;
    }
}
