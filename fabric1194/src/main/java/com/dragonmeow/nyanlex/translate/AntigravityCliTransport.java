package com.dragonmeow.nyanlex.translate;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Bridges the existing AI prompt/validation pipeline to Antigravity CLI headless mode. */
public final class AntigravityCliTransport implements HttpTransport {

    private final AntigravityCliClient client;
    private final Supplier<String> model;

    public AntigravityCliTransport(AntigravityCliClient client, Supplier<String> model) {
        this.client = Objects.requireNonNull(client, "client");
        this.model = model == null ? () -> "" : model;
    }

    @Override
    public String get(String url) throws IOException {
        throw new IOException("GET is not supported by Antigravity CLI");
    }

    @Override
    public String post(String url, String body, Map<String, String> headers) throws IOException {
        LocalAiTransportSupport.Request request = LocalAiTransportSupport.parseRequest(body);
        String configuredModel = model.get();
        String translated = client.complete(configuredModel, request.system(), request.user());
        return LocalAiTransportSupport.response(translated);
    }
}
