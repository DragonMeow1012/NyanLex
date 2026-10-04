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
        final JsonObject request;
        try {
            JsonElement parsed = new JsonParser().parse(body);
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
        return new Request(model, system, user);
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
