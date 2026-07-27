package com.dragonmeow.mctranslator.translate;

import com.dragonmeow.mctranslator.config.TranslatorConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Owns the AI translation backends used by one Minecraft client process.
 *
 * <p>The loader layer supplies paths and the HTTP transport. This class owns
 * provider routing, Codex process lifetime, request pacing and session token
 * accounting, so those concerns are not duplicated in each loader entry point.</p>
 */
public final class AiTranslationRuntime implements Translator, AutoCloseable {

    private static final String CODEX_ENDPOINT = "codex://app-server";

    private final TranslatorConfig config;
    private final HttpTransport httpTransport;
    private final SessionTokenUsage tokenUsage = new SessionTokenUsage();
    private final OpenAiTranslator apiTranslator;
    private final CodexAppServerClient codexClient;
    private final OpenAiTranslator codexTranslator;

    public AiTranslationRuntime(TranslatorConfig config, HttpTransport httpTransport,
                                Path codexHome, Path codexWorkspace) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpTransport = Objects.requireNonNull(httpTransport, "httpTransport");

        this.apiTranslator = new OpenAiTranslator(
                httpTransport,
                () -> new AiSettings(config.aiBaseUrl, config.aiModel,
                        config.aiApiKeys, config.aiGlossary),
                new RequestPacer(() -> config.requestCooldownMs));
        this.apiTranslator.setTokenUsage(tokenUsage);

        this.codexClient = new CodexAppServerClient(codexHome, codexWorkspace);
        this.codexClient.setTokenUsage(tokenUsage);
        CodexAppServerTransport codexTransport = new CodexAppServerTransport(
                codexClient, () -> config.codexReasoningEffort);
        this.codexTranslator = new OpenAiTranslator(
                codexTransport,
                () -> new AiSettings(CODEX_ENDPOINT, config.codexModel,
                        List.of(), config.aiGlossary),
                new RequestPacer(() -> config.requestCooldownMs));
    }

    public CodexAppServerClient codexClient() {
        return codexClient;
    }

    public SessionTokenUsage.Snapshot tokenUsage() {
        return tokenUsage.snapshot();
    }

    public boolean isConfigured() {
        if (config.aiUseCodex) {
            return codexClient.isSignedInCached()
                    && config.codexModel != null
                    && !config.codexModel.isBlank();
        }
        return apiTranslator.isConfigured();
    }

    public boolean isRateLimited() {
        return current().isRateLimited();
    }

    public TranslationResult testApi(String baseUrl, String model, List<String> keys)
            throws TranslationException {
        OpenAiTranslator testTranslator = new OpenAiTranslator(
                httpTransport,
                () -> new AiSettings(baseUrl, model, keys),
                new RequestPacer(() -> config.requestCooldownMs));
        testTranslator.setTokenUsage(tokenUsage);
        return testTranslator.translate("Hello, world", "zh-TW");
    }

    public TranslationResult testCodex() throws TranslationException {
        return codexTranslator.translate("Hello, world", "zh-TW");
    }

    @Override
    public TranslationResult translate(String text, String targetLang)
            throws TranslationException {
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
    public void close() {
        codexClient.close();
    }

    private OpenAiTranslator current() {
        return config.aiUseCodex ? codexTranslator : apiTranslator;
    }
}
