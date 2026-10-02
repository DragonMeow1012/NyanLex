package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Live router for the key-free machine source selected in settings. */
public final class SwitchingMachineTranslator implements Translator {
    private final Supplier<String> provider;
    private final Map<MachineTranslationProvider, Translator> delegates =
            new EnumMap<>(MachineTranslationProvider.class);

    public SwitchingMachineTranslator(HttpTransport transport,
                                      Supplier<String> sourceLanguage,
                                      Supplier<String> provider,
                                      RequestPacer pacer) {
        this.provider = provider;
        Supplier<String> source = sourceLanguage == null ? () -> "auto" : sourceLanguage;
        RequestPacer sharedPacer = pacer == null ? RequestPacer.disabled() : pacer;
        delegates.put(MachineTranslationProvider.GOOGLE,
                new GoogleFreeTranslator(transport, safe(source.get()), sharedPacer));
        delegates.put(MachineTranslationProvider.YOUDAO,
                new ExperimentalWebTranslator(transport, source,
                        MachineTranslationProvider.YOUDAO, sharedPacer));
        delegates.put(MachineTranslationProvider.DEEPL,
                new ExperimentalWebTranslator(transport, source,
                        MachineTranslationProvider.DEEPL, sharedPacer));
        delegates.put(MachineTranslationProvider.MICROSOFT,
                new ExperimentalWebTranslator(transport, source,
                        MachineTranslationProvider.MICROSOFT, sharedPacer));
    }

    private Translator current() {
        String id;
        try { id = provider == null ? null : provider.get(); }
        catch (RuntimeException ignored) { id = null; }
        return delegates.get(MachineTranslationProvider.fromId(id));
    }

    @Override public TranslationResult translate(String text, String targetLang)
            throws TranslationException {
        return current().translate(text, targetLang);
    }

    @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
            throws TranslationException {
        return current().translateBatch(texts, targetLang);
    }

    @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                            List<String> surfaceContext)
            throws TranslationException {
        return current().translateBatch(texts, targetLang, surfaceContext);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "auto" : value;
    }
}
