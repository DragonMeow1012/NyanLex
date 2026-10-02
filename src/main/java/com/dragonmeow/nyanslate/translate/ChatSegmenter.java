package com.dragonmeow.nyanslate.translate;

public final class ChatSegmenter {

    private static final String[] SEPARATORS = {"\u00BB", "\u203A", " >> ", " > ", ": ", "\uFF1A"};
    private static final int MAX_PREFIX = 96;

    private ChatSegmenter() {
    }

    public static int contentStart(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (text.charAt(0) == '<') {
            int gt = text.indexOf('>');
            if (gt > 0 && gt <= MAX_PREFIX) {
                int start = skipSpaces(text, gt + 1);
                return (start >= text.length()) ? -1 : start;
            }
        }
        int sepPos = -1;
        int sepLen = 0;
        for (String sep : SEPARATORS) {
            int i = text.indexOf(sep);
            if (i > 0 && i <= MAX_PREFIX && (sepPos == -1 || i < sepPos)) {
                sepPos = i;
                sepLen = sep.length();
            }
        }
        if (sepPos < 0) return -1;
        int start = skipSpaces(text, sepPos + sepLen);
        return (start >= text.length()) ? -1 : start;
    }

    private static int skipSpaces(String text, int start) {
        while (start < text.length() && text.charAt(start) == ' ') start++;
        return start;
    }
}
