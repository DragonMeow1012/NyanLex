package com.dragonmeow.nyanlex.translate;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Live router between API-key AI and the two account-authenticated local CLI routes. */
public final class SwitchingAiTranslator implements Translator {

    private final OpenAiTranslator api;
    private final OpenAiTranslator codex;
    private final OpenAiTranslator antigravity;
    private final Supplier<String> provider;

    public SwitchingAiTranslator(OpenAiTranslator api, OpenAiTranslator codex,
                                 BooleanSupplier useCodex) {
        this(api, codex, api,
                () -> useCodex.getAsBoolean() ? "codex" : "api");
    }

    public SwitchingAiTranslator(OpenAiTranslator api, OpenAiTranslator codex,
                                 OpenAiTranslator antigravity, Supplier<String> provider) {
        this.api = api;
        this.codex = codex;
        this.antigravity = antigravity;
        this.provider = provider;
    }

    private OpenAiTranslator current() {
        String selected = provider.get();
        if ("codex".equals(selected)) return codex;
        if ("antigravity".equals(selected)) return antigravity;
        return api;
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
