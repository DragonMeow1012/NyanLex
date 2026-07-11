package com.dragonmeow.mctranslator.translate;

import java.util.ArrayList;
import java.util.List;

/** Abstraction over a translation backend so it can be swapped / mocked. */
public interface Translator {

    /**
     * Translate {@code text} into {@code targetLang}.
     *
     * @throws TranslationException if the request fails
     */
    TranslationResult translate(String text, String targetLang) throws TranslationException;

    /**
     * Translate several texts. The default loops {@link #translate}; backends that
     * support batching (one request for many texts) should override this for speed.
     * The returned list is aligned 1:1 with {@code texts}.
     */
    default List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        List<TranslationResult> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(translate(text, targetLang));
        }
        return out;
    }

    /**
     * Batch translation with optional shared surface context: {@code surfaceContext} is the
     * COMPLETE paragraph list of the surface the batch came from (e.g. a whole item
     * tooltip, book page or scoreboard), including units already cached and therefore absent
     * from {@code texts}. No generic first-unit/title assumption is implied. Context-aware
     * backends use it so partial batches still translate
     * coherently with the whole surface; the default simply ignores it and delegates to
     * {@link #translateBatch(List, String)}, so existing implementations keep working.
     * {@code surfaceContext} may be {@code null} (no context).
     */
    default List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                   List<String> surfaceContext) throws TranslationException {
        return translateBatch(texts, targetLang);
    }
}
