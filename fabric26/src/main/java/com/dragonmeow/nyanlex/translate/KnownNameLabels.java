package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes labels made only of known product names, versions and already-localized text.
 * Used after translation lookup, never as a mask that changes existing cache keys. */
public final class KnownNameLabels {
    // Shader/format names do not necessarily have their own loader metadata.
    private static final List<String> SHADER_NAMES = List.of(
            "Complementary Shaders", "Complementary Reimagined", "Complementary Unbound",
            "ComplementaryReimagined", "ComplementaryUnbound", "Euphoria Patches",
            "EuphoriaPatches", "labPBR", "seusPBR");
    private static final String NUMBER = "(?:[0-9]+(?:\\.[0-9]+)*|⟦MT[0-9#]+⟧)";
    private static final Pattern VERSION = Pattern.compile(
            "\\s*[rv]?" + NUMBER + "(?:[.+_-](?:mc)?(?:" + NUMBER
                    + "|snapshot|local|alpha|beta|pre|rc))*(?![\\p{L}\\p{N}_])",
            Pattern.CASE_INSENSITIVE);
    private final DoNotTranslateMatcher names;
    private final LruMemo<LruMemo<Boolean>> verdicts = new LruMemo<>(8);

    public KnownNameLabels(Collection<String> loadedNames) {
        List<String> all = new ArrayList<>(SHADER_NAMES);
        if (loadedNames != null) all.addAll(loadedNames);
        names = DoNotTranslateMatcher.compile(all);
    }

    public boolean keepsOriginal(String source, String targetLanguage) {
        if (source == null || source.isEmpty()) return false;
        return verdicts.get(String.valueOf(targetLanguage), ignored -> new LruMemo<>(4096))
                .get(source, text -> keepsOriginalUncached(text, targetLanguage));
    }

    private boolean keepsOriginalUncached(String source, String targetLanguage) {
        String plain = TextFilter.stripFormatting(source);
        List<int[]> matches = names.candidates(plain);
        if (matches.isEmpty()) return false;
        StringBuilder remaining = new StringBuilder(plain);
        for (int[] span : matches) {
            int end = span[1];
            Matcher version = VERSION.matcher(plain).region(end, plain.length());
            if (version.lookingAt()) end = version.end();
            for (int i = span[0]; i < end; i++) remaining.setCharAt(i, ' ');
        }
        // Do not exempt sentences or item names merely because they mention a product.
        return !TextFilter.shouldTranslate(remaining.toString(), targetLanguage);
    }
}
