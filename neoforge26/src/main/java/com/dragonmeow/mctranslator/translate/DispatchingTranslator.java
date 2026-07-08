package com.dragonmeow.mctranslator.translate;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Routes translation to a primary backend (AI 精翻) when {@code usePrimary} is true,
 * falling back to a secondary backend (Google) when the primary is off OR fails.
 * This keeps translation working even if the AI endpoint is misconfigured / down.
 */
public final class DispatchingTranslator implements Translator {

    private final Translator primary;
    private final Translator fallback;
    private final BooleanSupplier usePrimary;

    public DispatchingTranslator(Translator primary, Translator fallback, BooleanSupplier usePrimary) {
        this.primary = primary;
        this.fallback = fallback;
        this.usePrimary = usePrimary;
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws TranslationException {
        if (usePrimary.getAsBoolean()) {
            try {
                return primary.translate(text, targetLang);
            } catch (RuntimeException | TranslationException ignored) {
                // fall back to the secondary backend
            }
        }
        return fallback.translate(text, targetLang);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        if (usePrimary.getAsBoolean()) {
            try {
                return primary.translateBatch(texts, targetLang);
            } catch (RuntimeException | TranslationException ignored) {
                // fall back to the secondary backend
            }
        }
        return fallback.translateBatch(texts, targetLang);
    }

    /** Forwards the surface context so a context-aware primary (the AI backend) can use it;
     *  the fallback's default implementation simply ignores it. */
    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                  List<String> surfaceContext) throws TranslationException {
        if (usePrimary.getAsBoolean()) {
            try {
                return primary.translateBatch(texts, targetLang, surfaceContext);
            } catch (RuntimeException | TranslationException ignored) {
                // fall back to the secondary backend
            }
        }
        return fallback.translateBatch(texts, targetLang, surfaceContext);
    }
}
