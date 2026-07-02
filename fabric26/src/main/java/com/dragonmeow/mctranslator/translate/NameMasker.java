package com.dragonmeow.mctranslator.translate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces protected terms (player names) with neutral placeholders before a
 * string is sent to the translation backend, so those names never leave the
 * client — then restores them in the translated output.
 *
 * <p>The placeholder is {@code ⟦n⟧} (U+27E6 / U+27E7 around an index). These were
 * empirically confirmed to pass through the Google endpoint unchanged, and they
 * effectively never occur in real chat, so they round-trip cleanly.</p>
 *
 * <p>Caching benefit: the masked text is identical regardless of <em>which</em>
 * player sent it, so {@code "⟦0⟧: hello"} is cached once and reused.</p>
 */
public final class NameMasker {

    private static final char OPEN = '⟦';  // ⟦
    private static final char CLOSE = '⟧'; // ⟧

    private NameMasker() {
    }

    /** Result of masking: the masked text plus the ordered list of original terms. */
    public record Masked(String text, List<String> names) {
        public boolean hasMasks() {
            return !names.isEmpty();
        }
    }

    static String token(int index) {
        return OPEN + Integer.toString(index) + CLOSE;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Mask every whole-word occurrence of any term in {@code names}.
     *
     * <p>Single pass over the text with a set lookup per word token — O(text length)
     * regardless of how many names there are. This matters on large servers where
     * the protected-name set can be hundreds of players.</p>
     */
    public static Masked mask(String text, Collection<String> names) {
        if (text == null || text.isEmpty() || names == null || names.isEmpty()) {
            return new Masked(text, List.of());
        }
        Set<String> nameSet = (names instanceof Set) ? (Set<String>) names : new HashSet<>(names);

        List<String> placeholders = new ArrayList<>();     // index -> original term
        Map<String, Integer> assigned = new HashMap<>();   // term -> placeholder index
        StringBuilder out = new StringBuilder(text.length() + 8);
        int i = 0;
        int n = text.length();
        boolean masked = false;
        while (i < n) {
            char c = text.charAt(i);
            if (isNameChar(c)) {
                int j = i + 1;
                while (j < n && isNameChar(text.charAt(j))) j++;
                String token = text.substring(i, j);
                if (nameSet.contains(token)) {
                    Integer idx = assigned.get(token);
                    if (idx == null) {
                        idx = placeholders.size();
                        placeholders.add(token);
                        assigned.put(token, idx);
                    }
                    out.append(token(idx));
                    masked = true;
                } else {
                    out.append(token);
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return masked ? new Masked(out.toString(), placeholders) : new Masked(text, List.of());
    }

    /** Restore the original terms in a translated string. */
    public static String unmask(String translated, List<String> names) {
        if (translated == null || names == null || names.isEmpty()) {
            return translated;
        }
        String result = translated;
        for (int i = 0; i < names.size(); i++) {
            result = result.replace(token(i), names.get(i));
        }
        return result;
    }
}
