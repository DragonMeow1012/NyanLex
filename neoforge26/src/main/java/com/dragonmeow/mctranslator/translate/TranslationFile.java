package com.dragonmeow.mctranslator.translate;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Portable translation rows only: never configuration, credentials or failure state. */
public final class TranslationFile {
    private static final int MAX_ROWS = 100000;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    public final String format, language, provider;
    public final Map<String, String> machine, ai;

    public TranslationFile(String format, String language, String provider,
                           Map<String, String> machine, Map<String, String> ai) {
        this.format = format;
        this.language = language.toLowerCase(Locale.ROOT).replace('_', '-');
        this.provider = provider;
        this.machine = new LinkedHashMap<>(machine);
        this.ai = new LinkedHashMap<>(ai);
    }

    public void requireCompatible(String expectedFormat, String expectedLanguage, String expectedProvider)
            throws IOException {
        if (!format.equals(expectedFormat)) throw new IOException("Incompatible translation format: " + format);
        if (!language.equalsIgnoreCase(expectedLanguage.replace('_', '-')))
            throw new IOException("Translation language mismatch: " + language);
        if (!machine.isEmpty() && !provider.equals(expectedProvider))
            throw new IOException("Select machine provider: " + provider);
    }

    public static TranslationFile read(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new IOException("Translation file exceeds 32 MiB");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            if (json.get("schema").getAsInt() != 1) throw new IOException("Unsupported translation file schema");
            Map<String, String> machine = rows(json.getAsJsonObject("machine"));
            Map<String, String> ai = rows(json.getAsJsonObject("ai"));
            if (machine.size() + ai.size() > MAX_ROWS) throw new IOException("Too many translations");
            return new TranslationFile(string(json,"format"), string(json,"language"),
                    string(json,"provider"), machine, ai);
        } catch (RuntimeException error) {
            throw new IOException("Invalid translation file", error);
        }
    }

    private static String string(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Invalid translation field");
        String result = value.getAsString();
        if (result.isEmpty() || result.length() > 16384) throw new IOException("Invalid translation length");
        return result;
    }

    private static Map<String,String> rows(JsonObject object) throws IOException {
        if (object == null || object.entrySet().size() > MAX_ROWS) throw new IOException("Invalid translation rows");
        Map<String,String> result = new LinkedHashMap<>();
        for (Map.Entry<String,JsonElement> row : object.entrySet()) {
            if (row.getKey().isEmpty() || row.getKey().length() > 16384)
                throw new IOException("Invalid source length");
            result.put(row.getKey(), string(object, row.getKey()));
        }
        return result;
    }

    public void write(Path path) throws IOException {
        write(path, false);
    }

    /** Replacement is reserved for the mod's own imported-translation snapshot. */
    public void write(Path path, boolean replaceExisting) throws IOException {
        if (machine.size() + ai.size() > MAX_ROWS) throw new IOException("Too many translations");
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("format", format);
        json.addProperty("language", language);
        json.addProperty("provider", provider);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        json.add("machine", gson.toJsonTree(machine));
        json.add("ai", gson.toJsonTree(ai));
        Path destination = path.toAbsolutePath();
        Path temp = Files.createTempFile(destination.getParent(), ".mctranslator-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { gson.toJson(json, writer); }
            if (Files.size(temp) > MAX_BYTES) throw new IOException("Translation file exceeds 32 MiB");
            // Never silently replace an existing friend/user file.
            if (replaceExisting) Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp, destination);
        } finally { Files.deleteIfExists(temp); }
    }
}
