package com.dragonmeow.nyanslate.translate;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits a chat line into "speaker prefix" and "spoken content" so that the content is
 * translated (and cached) independently of who said it.
 *
 * <p>A separator such as {@code ": "} only marks a speaker when the text before it
 * really looks like one: a player name, optionally wrapped in rank/level tags
 * ({@code [MVP+] Name}), optionally preceded by a channel word ({@code Guild >},
 * {@code From}). An ordinary sentence ({@code Your quest 'X' is complete. Reward: 250
 * coins!}) or a label ({@code Reward: 250 coins}) is NOT split, so the whole line is
 * translated.</p>
 */
public final class ChatSegmenter {

    private static final String[] SEPARATORS = {"\u00BB", "\u203A", " >> ", " > ", ": ", "\uFF1A"};
    private static final int MAX_PREFIX = 96;
    private static final int MAX_NAME = 24;

    private static final Pattern SECTION = Pattern.compile("\u00A7.");
    private static final Pattern TAG_GROUP = Pattern.compile("\\[[^\\[\\]]{0,32}\\]|\\([^()]{0,32}\\)");
    private static final Pattern NAME = Pattern.compile("^[~*+]?[\\p{L}\\p{N}_]{1," + MAX_NAME + "}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Words that route a message to a channel; they are never the speaker themselves. */
    private static final Set<String> CHANNELS = Set.of(
            "guild", "party", "officer", "from", "to", "co-op", "coop", "team", "global",
            "local", "trade", "staff", "all", "shout", "whisper", "pm", "dm", "msg");

    /** Words that head a "Label: value" line instead of naming a speaker. */
    private static final Set<String> LABELS = Set.of(
            "reward", "rewards", "tip", "tips", "note", "warning", "error", "info", "usage",
            "cost", "price", "requirement", "requirements", "objective", "total", "time",
            "level", "status", "cooldown", "rarity", "hint", "example", "result", "results",
            "progress", "damage", "health", "stats", "description", "click", "type");

    private ChatSegmenter() {
    }

    public static int contentStart(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (text.charAt(0) == '<') {
            int gt = text.indexOf('>');
            if (gt > 1 && gt <= MAX_PREFIX && text.charAt(1) != ' ') {
                int start = skipSpaces(text, gt + 1);
                return (start >= text.length()) ? -1 : start;
            }
        }
        return scan(text, 0);
    }

    private static int scan(String text, int from) {
        int sepPos = -1;
        int sepLen = 0;
        for (String sep : SEPARATORS) {
            int i = text.indexOf(sep, from);
            if (i > from && i - from <= MAX_PREFIX && (sepPos == -1 || i < sepPos)) {
                sepPos = i;
                sepLen = sep.length();
            }
        }
        if (sepPos < 0) return -1;
        int start = skipSpaces(text, sepPos + sepLen);
        if (start >= text.length()) return -1;
        String prefix = text.substring(from, sepPos);
        switch (classify(prefix, text.substring(start))) {
            case SPEAKER:
                return start;
            case CHANNEL:
                // "Guild > [VIP] Name: hi": the real speaker may follow the channel label.
                int deeper = scan(text, start);
                return deeper >= 0 ? deeper : start;
            default:
                return -1;
        }
    }

    private enum Kind { SPEAKER, CHANNEL, NONE }

    private static Kind classify(String prefix, String content) {
        String p = SECTION.matcher(prefix).replaceAll("");
        boolean hadTags = TAG_GROUP.matcher(p).find();
        p = TAG_GROUP.matcher(p).replaceAll(" ").strip();
        if (p.isEmpty()) return hadTags ? Kind.SPEAKER : Kind.NONE;

        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String raw : WHITESPACE.split(p)) {
            if (raw.codePoints().anyMatch(Character::isLetterOrDigit)) tokens.add(raw);
        }
        boolean sawChannel = false;
        while (!tokens.isEmpty() && CHANNELS.contains(tokens.get(0).toLowerCase(Locale.ROOT))) {
            tokens.remove(0);
            sawChannel = true;
        }
        if (tokens.isEmpty()) return sawChannel ? Kind.CHANNEL : (hadTags ? Kind.SPEAKER : Kind.NONE);
        if (tokens.size() != 1) return Kind.NONE;
        String name = tokens.get(0);
        if (!NAME.matcher(name).matches()) return Kind.NONE;
        if (!hadTags && !sawChannel) {
            // A bare word: a label ("Reward: 250 coins") or a value line is not a speaker.
            if (LABELS.contains(name.toLowerCase(Locale.ROOT))) return Kind.NONE;
            char first = content.charAt(0);
            if (Character.isDigit(first) || first == '$' || first == '+' || first == '-') return Kind.NONE;
        }
        return Kind.SPEAKER;
    }

    private static int skipSpaces(String text, int start) {
        while (start < text.length() && text.charAt(start) == ' ') start++;
        return start;
    }
}
