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
    private static final Gson JSON = new Gson();
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

    /** Each part is an ordinary schema-1 file, readable by existing releases. */
    public List<Path> writeParts(Path path) throws IOException {
        return writeParts(path, MAX_ROWS, MAX_BYTES);
    }

    List<Path> writeParts(Path path, int maxRows, long maxBytes) throws IOException {
        Path destination = path.toAbsolutePath();
        List<Path> staged = new ArrayList<>(), published = new ArrayList<>();
        Map<String,String> machinePart = new LinkedHashMap<>(), aiPart = new LinkedHashMap<>();
        TranslationFile empty = new TranslationFile(format, language, provider, machinePart, aiPart);
        long baseBytes = JSON.toJson(empty.json()).getBytes(StandardCharsets.UTF_8).length;
        if (maxRows < 1 || baseBytes > maxBytes) throw new IOException("Translation metadata exceeds part limit");
        long bytes = baseBytes;
        int count = 0;
        try {
            for (int group = 0; group < 2; group++) {
                Map<String,String> source = group == 0 ? machine : ai;
                for (Map.Entry<String,String> row : source.entrySet()) {
                    if (row.getKey() == null || row.getValue() == null || row.getKey().isEmpty()
                            || row.getValue().isEmpty() || row.getKey().length() > 16384 || row.getValue().length() > 16384)
                        throw new IOException("Invalid translation length");
                    long rowBytes = (JSON.toJson(row.getKey()) + ":" + JSON.toJson(row.getValue()))
                            .getBytes(StandardCharsets.UTF_8).length;
                    if (baseBytes + rowBytes > maxBytes) throw new IOException("Translation row exceeds part limit");
                    Map<String,String> part = group == 0 ? machinePart : aiPart;
                    long separator = part.isEmpty() ? 0 : 1;
                    if (count >= maxRows || bytes + rowBytes + separator > maxBytes) {
                        stagePart(destination, machinePart, aiPart, staged);
                        machinePart.clear(); aiPart.clear();
                        bytes = baseBytes; count = 0; separator = 0;
                    }
                    part.put(row.getKey(), row.getValue());
                    bytes += rowBytes + separator;
                    count++;
                }
            }
            if (count > 0 || staged.isEmpty()) stagePart(destination, machinePart, aiPart, staged);
            String name = destination.getFileName().toString();
            String stem = name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            List<Path> targets = new ArrayList<>();
            for (int i = 0; i < staged.size(); i++) {
                Path target = staged.size() == 1 ? destination
                        : destination.resolveSibling(stem + String.format(Locale.ROOT, ".part-%04d.json", i + 1));
                if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
                targets.add(target);
            }
            for (int i = 0; i < staged.size(); i++) {
                Files.move(staged.get(i), targets.get(i));
                published.add(targets.get(i));
            }
            return published;
        } catch (IOException | RuntimeException error) {
            for (Path output : published) {
                try { Files.deleteIfExists(output); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        } finally {
            for (Path temp : staged) Files.deleteIfExists(temp);
        }
    }

    private void stagePart(Path destination, Map<String,String> machinePart, Map<String,String> aiPart,
                           List<Path> staged) throws IOException {
        Path temp = Files.createTempFile(destination.getParent(), ".mctranslator-part-", ".tmp");
        staged.add(temp);
        new TranslationFile(format, language, provider, machinePart, aiPart).write(temp, true);
    }

    private JsonObject json() {
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("format", format);
        json.addProperty("language", language);
        json.addProperty("provider", provider);
        json.add("machine", JSON.toJsonTree(machine));
        json.add("ai", JSON.toJsonTree(ai));
        return json;
    }

    /** Replacement is reserved for the mod's own imported-translation snapshot. */
    public void write(Path path, boolean replaceExisting) throws IOException {
        if (machine.size() + ai.size() > MAX_ROWS) throw new IOException("Too many translations");
        Path destination = path.toAbsolutePath();
        Path temp = Files.createTempFile(destination.getParent(), ".mctranslator-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { JSON.toJson(json(), writer); }
            if (Files.size(temp) > MAX_BYTES) throw new IOException("Translation file exceeds 32 MiB");
            // Never silently replace an existing friend/user file.
            if (replaceExisting) Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp, destination);
        } finally { Files.deleteIfExists(temp); }
    }
}
