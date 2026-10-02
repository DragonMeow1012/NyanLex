package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.dragonmeow.nyanlex.config.TranslatorConfig;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Live router for the machine source selected in settings (Google web endpoint, or an official API with the player's key). */
public final class SwitchingMachineTranslator implements Translator {
    private final Supplier<String> provider;
    private final Map<MachineTranslationProvider, Translator> delegates =
            new EnumMap<>(MachineTranslationProvider.class);

    public SwitchingMachineTranslator(HttpTransport transport,
                                      Supplier<String> sourceLanguage,
                                      Supplier<String> provider,
                                      RequestPacer pacer) {
        this(transport, sourceLanguage, provider, pacer, () -> null);
    }

    /** @param config live config supplying the player's own API keys for the official APIs */
    public SwitchingMachineTranslator(HttpTransport transport,
                                      Supplier<String> sourceLanguage,
                                      Supplier<String> provider,
                                      RequestPacer pacer,
                                      Supplier<TranslatorConfig> config) {
        this.provider = provider;
        Supplier<String> source = sourceLanguage == null ? () -> "auto" : sourceLanguage;
        RequestPacer sharedPacer = pacer == null ? RequestPacer.disabled() : pacer;
        OfficialApiTranslator.Credentials credentials = new OfficialApiTranslator.Credentials() {
            @Override public String deeplKey() {
                TranslatorConfig c = config == null ? null : config.get();
                return c == null ? "" : c.deeplApiKey;
            }
            @Override public String microsoftKey() {
                TranslatorConfig c = config == null ? null : config.get();
                return c == null ? "" : c.microsoftApiKey;
            }
            @Override public String microsoftRegion() {
                TranslatorConfig c = config == null ? null : config.get();
                return c == null ? "" : c.microsoftApiRegion;
            }
        };
        delegates.put(MachineTranslationProvider.GOOGLE,
                new GoogleFreeTranslator(transport, safe(source.get()), sharedPacer));
        delegates.put(MachineTranslationProvider.DEEPL_API,
                new OfficialApiTranslator(transport, source,
                        MachineTranslationProvider.DEEPL_API, sharedPacer, credentials));
        delegates.put(MachineTranslationProvider.MICROSOFT_API,
                new OfficialApiTranslator(transport, source,
                        MachineTranslationProvider.MICROSOFT_API, sharedPacer, credentials));
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
