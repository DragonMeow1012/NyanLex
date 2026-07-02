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
}
