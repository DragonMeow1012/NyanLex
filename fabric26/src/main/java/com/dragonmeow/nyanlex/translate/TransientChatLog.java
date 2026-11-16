package com.dragonmeow.nyanlex.translate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rules for copying the translations of short-lived text (titles, subtitles, dialogue boxes)
 * into the player's own chat, where they can be read again after the screen text is gone.
 *
 * <p>This class only decides <em>whether</em> a translation is recorded and how its line
 * reads; the loader glue builds the chat line and adds it locally (never sent anywhere). It
 * keeps two small bounded lists of what was recorded recently, in memory only:</p>
 * <ul>
 *   <li>Titles and subtitles are keyed by their text without digits, so a countdown that
 *       changes a number every second is one entry; the same key within
 *       {@link #REPEAT_WINDOW_MS} is not recorded again, and every sighting extends the
 *       window (a title that stays on screen, or repeats at once, is recorded once).</li>
 *   <li>Dialogue pages are keyed by speaker and spoken text; the same page as one of the
 *       last {@link #RECENT_DIALOGUES} recorded is not recorded again.</li>
 * </ul>
 * Not thread-safe: use from the render/client thread only.
 */
public final class TransientChatLog {

    /** What is being recorded. */
    public enum Kind { TITLE, SUBTITLE, DIALOGUE }

    /** A title that was seen again within this time is the same title. */
    public static final long REPEAT_WINDOW_MS = 10_000L;
    public static final int RECENT_TITLES = 32;
    public static final int RECENT_DIALOGUES = 16;

    private final Map<String, Long> titles = bounded(RECENT_TITLES);
    private final Map<String, Long> dialogues = bounded(RECENT_DIALOGUES);

    private static Map<String, Long> bounded(int limit) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > limit;
            }
        };
    }

    /**
     * Whether recording is on at all: the player's switch, and the surface shows translations
     * (not original-only, which also covers the global "show original" toggle).
     */
    public static boolean enabled(boolean switchOn, boolean surfaceShowsOriginalOnly) {
        return switchOn && !surfaceShowsOriginalOnly;
    }

    // ---------------------------------------------------------------- titles

    /**
     * Whether the translation of this title or subtitle should be recorded now. A {@code true}
     * answer marks it as recorded; so does a repeat inside the window, only to extend the window.
     */
    public boolean acceptTitle(Kind kind, String original, long now) {
        String key = titleKey(kind, original);
        Long seen = titles.get(key);
        titles.put(key, now);
        return seen == null || now - seen >= REPEAT_WINDOW_MS;
    }

    /** Takes back {@link #acceptTitle} (the line could not be built after all). */
    public void forgetTitle(Kind kind, String original) {
        titles.remove(titleKey(kind, original));
    }

    /** The de-duplication key of a title: kind plus the text without digits, section codes and extra spaces. */
    public static String titleKey(Kind kind, String original) {
        String text = original == null ? "" : original;
        StringBuilder out = new StringBuilder(text.length() + 8);
        out.append(kind).append(':');
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (Character.isDigit(ch)) {
                continue;
            }
            if (Character.isWhitespace(ch)) {
                space = out.charAt(out.length() - 1) != ':';
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(ch);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- dialogue

    /** Whether this dialogue page (speaker and spoken text, both original) is new; marks it recorded. */
    public boolean acceptDialogue(String speakerOriginal, String bodyOriginal) {
        String key = dialogueKey(speakerOriginal, bodyOriginal);
        boolean fresh = !dialogues.containsKey(key);
        dialogues.put(key, 0L);
        return fresh;
    }

    /** Takes back {@link #acceptDialogue}. */
    public void forgetDialogue(String speakerOriginal, String bodyOriginal) {
        dialogues.remove(dialogueKey(speakerOriginal, bodyOriginal));
    }

    static String dialogueKey(String speakerOriginal, String bodyOriginal) {
        return (speakerOriginal == null ? "" : speakerOriginal) + '\u0000' + (bodyOriginal == null ? "" : bodyOriginal);
    }

    // ---------------------------------------------------------------- line text

    /** The speaker label of a dialogue line: the translated name if there is one, else the original, else none. */
    public static String speakerPrefix(String speakerTranslated, String speakerOriginal) {
        String name = speakerTranslated != null && !speakerTranslated.isBlank() ? speakerTranslated.strip()
                : speakerOriginal != null ? speakerOriginal.strip() : "";
        return name.isEmpty() ? "" : "[" + name + "]";
    }

    /** A recorded line: the prefix, one space, the text (no prefix: just the text). */
    public static String line(String prefix, String text) {
        return prefix == null || prefix.isEmpty() ? text : prefix + " " + text;
    }
}
