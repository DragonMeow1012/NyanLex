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
        String t = text.strip();
        if (t.isEmpty()) return false;
        if (!hasLetters(t)) return false;
        if (looksStructuredData(t)) return false;
        if (isTargetChinese(targetLang) && isMostlyCjk(t)) return false;
        return true;
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
}
