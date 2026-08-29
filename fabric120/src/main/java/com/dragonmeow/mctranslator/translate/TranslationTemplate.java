package com.dragonmeow.mctranslator.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single entry point for translation-key preparation. A {@link Snapshot} captures
 * the exact deterministic slot layout at one instant; callers must carry that
 * immutable snapshot from cache lookup through HTTP dispatch, restore, and storage.
 */
public final class TranslationTemplate {
    private static final char OPEN = '\u27E6';
    private static final char CLOSE = '\u27E7';
    /**
     * Minecraft HUDs commonly use wide horizontal gaps as fixed columns.  They are
     * layout, not language, and must never be collapsed by a translator or by the CJK
     * typography pass.  Newlines deliberately are not included: each rendered line
     * remains an independent translation unit.
     */
    private static final Pattern HORIZONTAL_GAP = Pattern.compile("[ \\t\\u00A0]{2,}");
    private static final Pattern WS_TOKEN = Pattern.compile(
            "\\u27E6\\s*WS\\s*(\\d+)\\s*\\u27E7");
    private static final Pattern LAYOUT_PROTOCOL_TOKEN = Pattern.compile(
            "\\u27E6\\s*(/?)\\s*(CS|MT|WS)\\s*(\\d+)\\s*\\u27E7");

    public Snapshot prepare(String source) {
        String normalized = source == null ? "" : source.strip();
        List<String> gaps = new ArrayList<>();
        Matcher matcher = HORIZONTAL_GAP.matcher(normalized);
        StringBuilder protectedText = new StringBuilder(normalized.length());
        int cursor = 0;
        while (matcher.find()) {
            protectedText.append(normalized, cursor, matcher.start());
            int index = gaps.size();
            gaps.add(matcher.group());
            // Synthetic single spaces keep the sentinel separated from words for
            // providers such as Google. restoreLayout() consumes them again.
            protectedText.append(' ').append(layoutToken(index)).append(' ');
            cursor = matcher.end();
        }
        protectedText.append(normalized, cursor, normalized.length());
        String layoutProtected = gaps.isEmpty() ? normalized : protectedText.toString();
        TemplateText.Prepared base = TemplateText.prepare(layoutProtected);
        // Deterministic numbers, times, icons and player IDs are safe slots. A location
        // is semantic content: translate each distinct location once and cache it.
        return new Snapshot(source, normalized, base, List.copyOf(gaps));
    }

    public record Snapshot(String source, String normalized, TemplateText.Prepared base,
                           List<String> layoutGaps) {
        public String key() {
            return base.text();
        }

        public String restore(String translated) {
            return restoreLayout(base.restore(translated), layoutGaps);
        }

        public boolean changed() {
            return base.changed() || !layoutGaps.isEmpty();
        }

        /**
         * Rebuild this snapshot's template after style codes have been removed.  The
         * translated wording is retained, while the current dynamic values and layout
         * gaps are converted back to their stable tokens for durable plain-cache reuse.
         */
        public String retokenize(String restored) {
            String withValues = retokenizeValues(
                    restored, base.values(), base.slotIndices());
            if (withValues == null) return null;
            return retokenizeLayout(withValues, layoutGaps);
        }

        /**
         * False for static clocks, symbol-only text, and other placeholder-only lines.
         * Language filtering belongs to TranslationService; the cache also supports
         * explicit/manual requests for Chinese source text, so this check deliberately
         * uses a non-Chinese target and judges content only.
         */
        public boolean hasTranslatableContent() {
            return !key().isEmpty() && TextFilter.shouldTranslate(key(), "template-content-check");
        }

    }

    /** Exact ordered layout-token shape. Used by cache/provider validation. */
    public static List<String> layoutTokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher matcher = WS_TOKEN.matcher(text);
        while (matcher.find()) out.add(layoutToken(Integer.parseInt(matcher.group(1))));
        return out;
    }

    /**
     * Validate fixed-column protocol shape.  Outside a layout row, MT/CS pairs may move
     * with target-language grammar.  Once a WS slot exists, however, crossing that slot
     * moves a value or styled cell into another HUD column.  Preserve the exact ordered
     * WS/MT/CS skeleton for those rows while still allowing wording inside each cell to
     * be translated freely.
     */
    public static boolean layoutSkeletonMatches(String source, String translated) {
        List<String> sourceLayout = layoutTokens(source);
        if (sourceLayout.isEmpty()) {
            return sourceLayout.equals(layoutTokens(translated));
        }
        return layoutProtocolSkeleton(source).equals(layoutProtocolSkeleton(translated));
    }

    /** Normalized ordered protocol tokens used by {@link #layoutSkeletonMatches}. */
    public static List<String> layoutProtocolSkeleton(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher matcher = LAYOUT_PROTOCOL_TOKEN.matcher(text);
        while (matcher.find()) {
            out.add(OPEN + matcher.group(1) + matcher.group(2)
                    + Integer.parseInt(matcher.group(3)) + CLOSE);
        }
        return out;
    }

    /**
     * Dynamic values may move with a complete styled phrase, but may not escape into a
     * different CS region.  This compares MT-to-enclosing-CS ownership as a multiset, so
     * target grammar can reorder whole CS pairs without recolouring the live values.
     */
    public static boolean styleSlotShapeMatches(String source, String translated) {
        StyleSlotShape first = styleSlotShape(source);
        StyleSlotShape second = styleSlotShape(translated);
        return first.valid() && second.valid() && first.slots().equals(second.slots());
    }

    private static StyleSlotShape styleSlotShape(String text) {
        List<String> slots = new ArrayList<>();
        java.util.ArrayDeque<String> styles = new java.util.ArrayDeque<>();
        if (text == null) return new StyleSlotShape(true, slots);
        Matcher matcher = LAYOUT_PROTOCOL_TOKEN.matcher(text);
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String type = matcher.group(2);
            String index = Integer.toString(Integer.parseInt(matcher.group(3)));
            if ("CS".equals(type)) {
                if (!closing) {
                    styles.push(index);
                } else if (styles.isEmpty() || !styles.pop().equals(index)) {
                    return new StyleSlotShape(false, List.of());
                }
            } else if ("MT".equals(type)) {
                slots.add("MT" + index + "@CS" + (styles.isEmpty() ? "-" : styles.peek()));
            }
        }
        if (!styles.isEmpty()) return new StyleSlotShape(false, List.of());
        slots.sort(String::compareTo);
        return new StyleSlotShape(true, List.copyOf(slots));
    }

    private record StyleSlotShape(boolean valid, List<String> slots) {
    }

    private static String restoreLayout(String translated, List<String> gaps) {
        if (translated == null || gaps == null || gaps.isEmpty()) return translated;
        String out = translated;
        for (int i = 0; i < gaps.size(); i++) {
            Pattern token = Pattern.compile(
                    "[ \\t\\u00A0]*\\u27E6\\s*WS\\s*" + i
                            + "\\s*\\u27E7[ \\t\\u00A0]*");
            out = token.matcher(out).replaceAll(Matcher.quoteReplacement(gaps.get(i)));
        }
        return out;
    }

    private static String retokenizeLayout(String restored, List<String> gaps) {
        if (restored == null || gaps == null || gaps.isEmpty()) return restored;
        StringBuilder out = new StringBuilder(restored);
        int searchFrom = 0;
        for (int i = 0; i < gaps.size(); i++) {
            String gap = gaps.get(i);
            int found = out.indexOf(gap, searchFrom);
            if (found < 0) return null;
            String replacement = " " + layoutToken(i) + " ";
            out.replace(found, found + gap.length(), replacement);
            searchFrom = found + replacement.length();
        }
        return out.toString();
    }

    /** Replaces de-styled dynamic values in order while ignoring literal section codes. */
    private static String retokenizeValues(String restored, List<String> values,
                                           List<Integer> slotIndices) {
        if (restored == null || values == null || values.isEmpty()) return restored;
        if (slotIndices == null || slotIndices.size() != values.size()) return null;

        StringBuilder projection = new StringBuilder(restored.length());
        int[] rawIndex = new int[restored.length()];
        for (int i = 0; i < restored.length(); i++) {
            if (restored.charAt(i) == '\u00A7' && i + 1 < restored.length()
                    && !TextFilter.isTemplatedSectionCode(restored, i)) {
                i++;
                continue;
            }
            rawIndex[projection.length()] = i;
            projection.append(restored.charAt(i));
        }

        StringBuilder result = new StringBuilder(restored);
        int searchFrom = 0;
        int shift = 0;
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            int found = projection.indexOf(value, searchFrom);
            if (found < 0 || value.isEmpty()) return null;
            int rawStart = rawIndex[found];
            int rawEnd = rawIndex[found + value.length() - 1] + 1;
            if (rawEnd - rawStart != value.length()) return null;
            String token = "\u27E6MT" + slotIndices.get(i) + "\u27E7";
            result.replace(rawStart + shift, rawEnd + shift, token);
            shift += token.length() - value.length();
            searchFrom = found + value.length();
        }
        return result.toString();
    }

    private static String layoutToken(int index) {
        return OPEN + "WS" + index + CLOSE;
    }
}
