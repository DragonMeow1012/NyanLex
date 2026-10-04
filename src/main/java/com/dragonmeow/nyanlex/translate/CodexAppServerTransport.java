package com.dragonmeow.nyanlex.translate;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adapts Codex app-server to the small OpenAI-compatible transport expected by
 * {@link OpenAiTranslator}. This keeps the existing Minecraft prompt, batching,
 * boundary validation and placeholder restoration identical across API and
 * ChatGPT/Codex login modes.
 */
public final class CodexAppServerTransport implements HttpTransport {

    private final CodexAppServerClient client;
    private final Supplier<String> reasoningEffort;

    public CodexAppServerTransport(CodexAppServerClient client, Supplier<String> reasoningEffort) {
        this.client = Objects.requireNonNull(client, "client");
        this.reasoningEffort = reasoningEffort == null ? () -> "" : reasoningEffort;
    }

    @Override
    public String get(String url) throws IOException {
        throw new IOException("GET is not supported by Codex app-server");
    }

    @Override
    public String post(String url, String body, Map<String, String> headers) throws IOException {
        LocalAiTransportSupport.Request request = LocalAiTransportSupport.parseRequest(body);
        String translated = client.complete(request.model(), reasoningEffort.get(),
                request.system(), request.user());
        return LocalAiTransportSupport.response(translated);
    }
}
