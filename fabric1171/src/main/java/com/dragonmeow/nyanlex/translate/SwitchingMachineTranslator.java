package com.dragonmeow.nyanlex.translate;

import java.util.List;
import java.util.function.Supplier;

/**
 * Machine-translation front: Google's web endpoint (unofficial) is the only source. The
 * {@code provider} supplier is kept so callers that still hand over the stored source id
 * compile unchanged; whatever it returns, Google is used.
 */
public final class SwitchingMachineTranslator implements Translator {
    private final Translator google;

    public SwitchingMachineTranslator(HttpTransport transport,
                                      Supplier<String> sourceLanguage,
                                      Supplier<String> provider,
                                      RequestPacer pacer) {
        Supplier<String> source = sourceLanguage == null ? () -> "auto" : sourceLanguage;
        this.google = new GoogleFreeTranslator(transport, safe(source.get()),
                pacer == null ? RequestPacer.disabled() : pacer, MachineTranslationGate.shared());
    }

    @Override public boolean sendBlocked() {
        return google.sendBlocked();
    }

    @Override public TranslationResult translate(String text, String targetLang)
            throws TranslationException {
        return google.translate(text, targetLang);
    }

    @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
            throws TranslationException {
        return google.translateBatch(texts, targetLang);
    }

    @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                            List<String> surfaceContext)
            throws TranslationException {
        return google.translateBatch(texts, targetLang, surfaceContext);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "auto" : value;
    }
}
