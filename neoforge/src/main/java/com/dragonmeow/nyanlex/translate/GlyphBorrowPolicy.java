package com.dragonmeow.nyanlex.translate;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft-free rules for letting a custom (non-vanilla) font borrow the vanilla default
 * font's glyphs for characters it does not define (typically CJK translations placed into a
 * Latin-only pixel font). The glue code owns the game types; this class only decides
 * <em>whether</em> to borrow and <em>how far</em> a borrowed glyph must move vertically.
 *
 * <p>Vertical alignment: a borrowed glyph keeps the vanilla default font's own vertical
 * layout, then moves by a shift so that it sits on the same line as the custom font's own
 * Latin letters. The shift is {@code top(custom 'A') - top(default 'A')}; when 'A' is not
 * available in either font the next reference character is tried
 * ({@link #REFERENCE_CODEPOINTS}); with no usable reference character the shift is 0.
 * "Top" is the glyph quad's upper edge relative to the text origin (smaller = higher).</p>
 */
public final class GlyphBorrowPolicy {

    /** Reference characters tried in order when measuring the vertical shift. */
    public static final int[] REFERENCE_CODEPOINTS = {'A', 'a', '0'};

    private static final String VANILLA_NAMESPACE = "minecraft";

    private GlyphBorrowPolicy() {
    }

    /** Answers the top edge of the font's own glyph for a code point, or NaN when it has none. */
    @FunctionalInterface
    public interface TopLookup {
        float topOf(int codePoint);
    }

    /**
     * True for the fonts shipped by the game itself, which are never modified:
     * {@code minecraft:default}, {@code uniform}, {@code alt}, {@code illageralt}, the internal
     * {@code missing} fallback and everything under {@code minecraft:include/}. Unknown (null)
     * ids are treated as vanilla so that nothing is touched by mistake.
     */
    public static boolean isVanillaFont(String namespace, String path) {
        if (namespace == null || path == null) {
            return true;
        }
        if (!VANILLA_NAMESPACE.equals(namespace)) {
            return false;
        }
        switch (path) {
            case "default":
            case "uniform":
            case "alt":
            case "illageralt":
            case "missing":
                return true;
            default:
                return path.startsWith("include/");
        }
    }

    /** True for the font the borrowed glyphs come from ({@code minecraft:default}). */
    public static boolean isDefaultFont(String namespace, String path) {
        return VANILLA_NAMESPACE.equals(namespace) && "default".equals(path);
    }

    /**
     * Whether a missing character may be borrowed. Private-use ranges are skipped: they carry
     * server-defined icons and layout glyphs, never translated text, so a custom font that lacks
     * one keeps showing its normal missing glyph.
     */
    public static boolean isBorrowable(int codePoint) {
        if (codePoint <= 0 || codePoint > 0x10FFFF) {
            return false;
        }
        if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
            return false;
        }
        if (codePoint >= 0xE000 && codePoint <= 0xF8FF) {
            return false;
        }
        return codePoint < 0xF0000;
    }

    /**
     * Vertical shift to add to every borrowed glyph of one custom font.
     *
     * @param own       tops of the custom font's own glyphs (NaN where it has none)
     * @param reference tops of the vanilla default font's glyphs (NaN where it has none)
     * @return {@code ownTop - referenceTop} for the first reference character both fonts have,
     *         or 0 when there is none
     */
    public static float computeShift(TopLookup own, TopLookup reference) {
        if (own == null || reference == null) {
            return 0.0F;
        }
        for (int codePoint : REFERENCE_CODEPOINTS) {
            float ownTop = own.topOf(codePoint);
            if (!Float.isFinite(ownTop)) {
                continue;
            }
            float referenceTop = reference.topOf(codePoint);
            if (!Float.isFinite(referenceTop)) {
                continue;
            }
            float shift = ownTop - referenceTop;
            return Float.isFinite(shift) ? shift : 0.0F;
        }
        return 0.0F;
    }

    /** Per-font-id cache of {@link #computeShift}; cleared whenever fonts are (re)loaded. */
    public static final class ShiftCache {
        private final ConcurrentHashMap<String, Float> shifts = new ConcurrentHashMap<>();

        /** The cached shift of {@code fontId}, measured on first use. */
        public float shiftFor(String fontId, TopLookup own, TopLookup reference) {
            Float cached = shifts.get(fontId);
            if (cached != null) {
                return cached;
            }
            float shift = computeShift(own, reference);
            Float raced = shifts.putIfAbsent(fontId, shift);
            return raced != null ? raced : shift;
        }

        /** Forgets every measurement (resource reload, font option change, font closed). */
        public void clear() {
            shifts.clear();
        }

        public int size() {
            return shifts.size();
        }
    }
}
