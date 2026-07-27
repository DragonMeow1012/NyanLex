package com.dragonmeow.mctranslator.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        final JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            request = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid AI request body", e);
        }

        String model = string(request, "model");
        String system = "";
        String user = "";
        JsonElement messagesElement = request.get("messages");
        if (messagesElement != null && messagesElement.isJsonArray()) {
            for (JsonElement element : messagesElement.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject message = element.getAsJsonObject();
                String role = string(message, "role");
                if ("system".equals(role)) system = string(message, "content");
                else if ("user".equals(role)) user = string(message, "content");
            }
        }

        String translated = client.complete(model, reasoningEffort.get(), system, user);
        JsonObject message = new JsonObject();
        message.addProperty("content", translated);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
