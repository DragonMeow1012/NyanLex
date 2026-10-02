package com.dragonmeow.nyanlex.translate;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Live router between ordinary API-key AI and ChatGPT-authenticated Codex. */
public final class SwitchingAiTranslator implements Translator {

    private final OpenAiTranslator api;
    private final OpenAiTranslator codex;
    private final BooleanSupplier useCodex;

    public SwitchingAiTranslator(OpenAiTranslator api, OpenAiTranslator codex,
                                 BooleanSupplier useCodex) {
        this.api = api;
        this.codex = codex;
        this.useCodex = useCodex;
    }

    private OpenAiTranslator current() {
        return useCodex.getAsBoolean() ? codex : api;
    }

    public boolean isRateLimited() {
        return current().isRateLimited();
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws TranslationException {
        return current().translate(text, targetLang);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
            throws TranslationException {
        return current().translateBatch(texts, targetLang);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                  List<String> surfaceContext)
            throws TranslationException {
        return current().translateBatch(texts, targetLang, surfaceContext);
    }

    @Override
    public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                              List<List<String>> itemContexts)
            throws TranslationException {
        return current().translateBatchWithContexts(texts, targetLang, itemContexts);
    }

    /** Pacing of whichever backend the next request would actually use. */
    @Override
    public long nextRequestDelayMs() {
        return current().nextRequestDelayMs();
    }
}
