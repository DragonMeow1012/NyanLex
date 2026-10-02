package com.dragonmeow.nyanslate.translate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces protected terms (player names and do-not-translate terms) with neutral
 * placeholders before a string is sent to the translation backend, so those terms
 * never leave the client — then restores them in the translated output.
 *
 * <p>The placeholder is {@code ⟦n⟧} (U+27E6 / U+27E7 around an index). Google never
 * sees it (every {@code ⟦…⟧} token travels as a numeric sentinel) and the AI prompt
 * asks for it to be copied verbatim; both backends validate that it survived.</p>
 *
 * <p>Caching benefit: the masked text is identical regardless of <em>which</em>
 * player sent it (or which protected term it held), so {@code "⟦0⟧: hello"} is
 * cached once and reused.</p>
 */
public final class NameMasker {

    private static final char OPEN = '⟦';  // ⟦
    private static final char CLOSE = '⟧'; // ⟧

    private NameMasker() {
    }

    /**
     * Result of masking: the masked text plus the ordered list of original terms.
     * {@code names} holds every protected original — player names and do-not-translate
     * terms alike — where index {@code n} is restored for placeholder {@code ⟦n⟧}.
     * {@code patternNames} is the subset (same spelling) that ONLY a
     * {@link PlayerNamePatterns} frame isolated — neither the TAB list nor a
     * do-not-translate term claimed it.
     *
     * <p>{@code entitySlots} lists (ascending) the indices whose placeholder is an
     * <em>E</em> slot: a registered item name ({@link ItemEntityRegistry}) whose
     * restore value is the item's translated name, not the original text. Every other
     * index is a <em>P</em> slot, restored verbatim. {@code names.get(i)} of an E slot
     * is the item's core name (the English original).</p>
     */
    public record Masked(String text, List<String> names, List<String> patternNames,
                         List<Integer> entitySlots) {
        public Masked(String text, List<String> names) {
            this(text, names, List.of(), List.of());
        }

        public Masked(String text, List<String> names, List<String> patternNames) {
            this(text, names, patternNames, List.of());
        }

        public boolean hasMasks() {
            return !names.isEmpty();
        }

        /** Whether any placeholder is an item-name E slot. */
        public boolean hasEntities() {
            return !entitySlots.isEmpty();
        }

        public boolean isEntitySlot(int index) {
            return !entitySlots.isEmpty() && entitySlots.contains(index);
        }
    }

    static String token(int index) {
        return OPEN + Integer.toString(index) + CLOSE;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Mask every whole-word occurrence of any term in {@code names} (plus strong-frame
     * player names, see {@link #mask(String, Collection, DoNotTranslateMatcher)}).
     *
     * <p>Single pass over the text with a set lookup per word token — O(text length)
     * regardless of how many names there are. This matters on large servers where
     * the protected-name set can be hundreds of players.</p>
     */
    public static Masked mask(String text, Collection<String> names) {
        return mask(text, names, DoNotTranslateMatcher.EMPTY);
    }

    /**
     * Mask player names, do-not-translate terms and strong-frame player names
     * ({@link PlayerNamePatterns}) in ONE pass, so no two sources can ever assign the
     * same {@code ⟦n⟧} index. TAB player names keep their exact, case-sensitive
     * whole-token lookup; do-not-translate terms follow {@link DoNotTranslateMatcher}'s
     * rules; frame names are detected on the colour/format-free projection and only
     * when they sit inside a single style run. Where matches overlap the longer one
     * wins (an identical TAB/term span outranks the frame span). Every distinct
     * original spelling owns one index, assigned in order of first appearance.
     * {@code ⟦…⟧} protocol tokens are never masked into, so masking is idempotent.
     *
     * <p>This is the single masking choke point: every lookup, warm, readiness check,
     * async request and invalidation derives its key from here (B1).</p>
     */
    public static Masked mask(String text, Collection<String> names, DoNotTranslateMatcher terms) {
        return mask(text, names, terms, null);
    }

    /**
     * {@link #mask(String, Collection, DoNotTranslateMatcher)} plus registered item names
     * ({@code entities}, may be {@code null}) in the SAME pass, so P and E slots share one
     * index space (B1). Precedence: the P spans (TAB names, do-not-translate terms, frame
     * names) are chosen exactly as without {@code entities}; an item-name span is then
     * kept only where it overlaps none of them. P and E slots are indexed separately by
     * spelling (an E slot never shares an index with a P slot), all in order of first
     * appearance.
     *
     * <p>Name-only lines: when nothing but slots, protocol tokens, § codes and non-word
     * characters is left (an item's own title such as
     * {@code ⟦CS0⟧Wise Dragon Chestplate⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧}), only E slots of names
     * with a proven reforge split are kept; an unsplit item keeps its own whole-line key
     * exactly as before.</p>
     */
    public static Masked mask(String text, Collection<String> names, DoNotTranslateMatcher terms,
                              ItemEntityRegistry entities) {
        if (text == null || text.isEmpty()) return new Masked(text, List.of());
        boolean anyNames = names != null && !names.isEmpty();
        boolean anyTerms = terms != null && !terms.isEmpty();
        int[] frameSpans = PlayerNamePatterns.spansWithRank(text);
        int[] entitySpans = entities == null ? NO_SPANS : entities.spans(text);
        if (!anyNames && !anyTerms && frameSpans.length == 0 && entitySpans.length == 0) {
            return new Masked(text, List.of());
        }
        List<int[]> spans = anyTerms ? terms.candidates(text) : Collections.emptyList();
        if (anyNames) spans = addNameSpans(text, names, spans);
        if (frameSpans.length > 0) {
            List<int[]> all = new ArrayList<>(spans.size() + frameSpans.length / 2);
            all.addAll(spans);
            // Third element marks a frame span. Added AFTER the TAB/term spans so the
            // stable sort in longestNonOverlapping lets an identical strict span win.
            for (int i = 0; i < frameSpans.length; i += 2) {
                all.add(new int[] {frameSpans[i], frameSpans[i + 1], FRAME_SPAN});
            }
            spans = all;
        }
        List<int[]> chosen = spans.isEmpty() ? spans : longestNonOverlapping(spans);
        if (entitySpans.length > 0) chosen = withEntities(text, chosen, entitySpans);
        if (chosen.isEmpty()) return new Masked(text, List.of());

        List<String> placeholders = new ArrayList<>();     // index -> original term
        Map<String, Integer> assigned = new HashMap<>();   // original term -> index (P)
        Map<String, Integer> assignedEntities = null;      // core name -> index (E)
        // A chosen frame span is never TAB- or term-claimed: both of those match their
        // spelling at EVERY whole-word position, frame names always sit between non-word
        // neighbours, and an identical strict span wins the tie at that same position.
        List<String> patternNames = null;
        List<Integer> entitySlots = null;
        StringBuilder out = new StringBuilder(text.length() + 8);
        int pos = 0;
        for (int[] span : chosen) {
            out.append(text, pos, span[0]);
            String original = text.substring(span[0], span[1]);
            boolean entity = span.length > 2 && span[2] == ENTITY_SPAN;
            Map<String, Integer> byKind = assigned;
            if (entity) {
                if (assignedEntities == null) assignedEntities = new HashMap<>(4);
                byKind = assignedEntities;
            }
            Integer idx = byKind.get(original);
            if (idx == null) {
                idx = placeholders.size();
                placeholders.add(original);
                byKind.put(original, idx);
                if (entity) {
                    if (entitySlots == null) entitySlots = new ArrayList<>(2);
                    entitySlots.add(idx);
                } else if (span.length > 2 && span[2] == FRAME_SPAN) {
                    if (patternNames == null) patternNames = new ArrayList<>(2);
                    patternNames.add(original);
                }
            }
            out.append(token(idx));
            pos = span[1];
        }
        out.append(text, pos, text.length());
        if (entitySlots != null) {
            return new Masked(out.toString(), placeholders,
                    patternNames == null ? List.of() : List.copyOf(patternNames),
                    List.copyOf(entitySlots));
        }
        return patternNames == null ? new Masked(out.toString(), placeholders)
                : new Masked(out.toString(), placeholders, List.copyOf(patternNames));
    }

    /** Marker stored as the third element of a span that came from a frame. */
    private static final int FRAME_SPAN = 1;
    /** Marker stored as the third element of an item-name (E) span; the fourth element
     *  is 1 when that item has a proven reforge split. */
    private static final int ENTITY_SPAN = 2;
    private static final int[] NO_SPANS = new int[0];

    /**
     * Add the item-name spans that overlap no chosen P span. On a name-only line keep
     * only the split ones (see {@link #mask(String, Collection, DoNotTranslateMatcher,
     * ItemEntityRegistry)}).
     */
    private static List<int[]> withEntities(String text, List<int[]> chosen, int[] entitySpans) {
        List<int[]> accepted = null;
        for (int k = 0; k + 2 < entitySpans.length; k += 3) {
            int start = entitySpans[k];
            int end = entitySpans[k + 1];
            boolean overlaps = false;
            for (int[] kept : chosen) {
                if (start < kept[1] && kept[0] < end) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;
            if (accepted == null) accepted = new ArrayList<>(2);
            accepted.add(new int[] {start, end, ENTITY_SPAN, entitySpans[k + 2]});
        }
        if (accepted == null) return chosen;
        List<int[]> all = new ArrayList<>(chosen.size() + accepted.size());
        all.addAll(chosen);
        all.addAll(accepted);
        all.sort((a, b) -> Integer.compare(a[0], b[0]));
        if (!hasWordOutside(text, all)) {
            boolean anySplit = false;
            for (int[] span : accepted) anySplit |= span[3] == 1;
            if (!anySplit) return chosen;
            all.removeIf(span -> span.length > 3 && span[2] == ENTITY_SPAN && span[3] == 0);
        }
        return all;
    }

    /** Whether a letter or digit remains outside every span, protocol token and § code. */
    private static boolean hasWordOutside(String text, List<int[]> sortedSpans) {
        int next = 0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (next < sortedSpans.size() && i >= sortedSpans.get(next)[0]) {
                i = Math.max(i, sortedSpans.get(next)[1]);
                next++;
                continue;
            }
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (c == '§') {
                i += 2;
                continue;
            }
            if (Character.isLetterOrDigit(c)) return true;
            i++;
        }
        return false;
    }

    /** Word tokens (letters, digits, {@code _}) that are protected player names. */
    private static List<int[]> addNameSpans(String text, Collection<String> names,
                                           List<int[]> spans) {
        Set<?> nameSet = names instanceof Set<?> ? (Set<?>) names : new HashSet<>(names);
        List<int[]> out = spans;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == OPEN) {
                int end = protocolTokenEnd(text, i);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (isNameChar(c)) {
                int j = i + 1;
                while (j < n && isNameChar(text.charAt(j))) j++;
                if (nameSet.contains(text.substring(i, j))) {
                    if (out == spans) out = new ArrayList<>(spans);
                    out.add(new int[] {i, j});
                }
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Keep the longest of overlapping spans (earlier start on ties), in text order. */
    private static List<int[]> longestNonOverlapping(List<int[]> spans) {
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

    private static int protocolTokenEnd(String text, int open) {
        for (int j = open + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == CLOSE) return j + 1;
            if (c == OPEN) return -1;
        }
        return -1;
    }

    /**
     * Restore the original terms in a translated string. Whitespace inside a
     * placeholder is tolerated ({@code ⟦ 0 ⟧}, as some AI models answer); indices
     * without an original and every non-numeric token are left untouched.
     */
    public static String unmask(String translated, List<String> names) {
        if (translated == null || names == null || names.isEmpty()) {
            return translated;
        }
        int open = translated.indexOf(OPEN);
        if (open < 0) return translated;
        StringBuilder out = null;
        int copied = 0;
        while (open >= 0) {
            int close = translated.indexOf(CLOSE, open + 1);
            if (close < 0) break;
            int index = placeholderIndex(translated, open + 1, close);
            if (index >= 0 && index < names.size()) {
                if (out == null) out = new StringBuilder(translated.length() + 16);
                out.append(translated, copied, open).append(names.get(index));
                copied = close + 1;
                open = translated.indexOf(OPEN, copied);
            } else {
                open = translated.indexOf(OPEN, open + 1);
            }
        }
        if (out == null) return translated;
        out.append(translated, copied, translated.length());
        return out.toString();
    }

    /** Index of a {@code ⟦ n ⟧} body between {@code from} and {@code to}, or -1. */
    private static int placeholderIndex(String text, int from, int to) {
        int start = from;
        int end = to;
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start == end || end - start > 6) return -1;
        int value = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
