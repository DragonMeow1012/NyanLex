package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

/** Shared OpenAI chat-envelope conversion for local account-authenticated CLI transports. */
final class LocalAiTransportSupport {

    private LocalAiTransportSupport() {
    }

    static Request parseRequest(String body) throws IOException {
        if (body == null || body.length() > 1_000_000) throw new IOException("AI request exceeded the safety limit");
        final JsonObject request;
        try {
            JsonElement parsed = new JsonParser().parse(body);
            request = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid AI request body", e);
        }

        String model = string(request, "model");
        JsonElement messagesElement = request.get("messages");
        if (messagesElement == null || !messagesElement.isJsonArray()
                || messagesElement.getAsJsonArray().size() != 2)
            throw new IOException("CLI translation requires one system message and one game-data message");
        String system = messageText(messagesElement.getAsJsonArray().get(0), "system");
        String user = messageText(messagesElement.getAsJsonArray().get(1), "user");
        if (system.length() + user.length() > 250_000) throw new IOException("Translation input exceeded the safety limit");
        return new Request(model, system, user);
    }

    private static String messageText(JsonElement element, String role) throws IOException {
        if (!element.isJsonObject()) throw new IOException("Invalid CLI translation message");
        JsonObject message = element.getAsJsonObject();
        JsonElement content = message.get("content");
        if (!role.equals(string(message, "role")) || content == null || !content.isJsonPrimitive()
                || !content.getAsJsonPrimitive().isString())
            throw new IOException("CLI translation accepts text data only, without extra roles or tool input");
        return content.getAsString();
    }

    static String response(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
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

    record Request(String model, String system, String user) {
    }
}
