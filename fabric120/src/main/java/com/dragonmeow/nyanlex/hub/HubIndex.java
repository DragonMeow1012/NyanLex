package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable snapshot of the repository's {@code index.json}: for every server host,
 * modpack slug and mod id, which target languages have a translation file and its
 * row count / byte size / content hash. The download flow fetches only this one small
 * file before showing the confirmation screen; every other repository file is fetched
 * only for an item the player actually confirmed.
 *
 * <p>An entry may carry an optional {@code license} (the license of the file, written only for
 * files that are not under the repository default). Entries are read leniently: a missing or
 * malformed license is no license, and unknown fields are ignored.</p>
 */
public final class HubIndex {
    private static final int SCHEMA = 1;
    private static final Gson GSON = new Gson();

    public record LanguageStats(int rows, long bytes, String sha256, String updatedAt, String license) {
        public LanguageStats(int rows, long bytes, String sha256, String updatedAt) {
            this(rows, bytes, sha256, updatedAt, null);
        }
    }

    private final String generatedAt;
    private final Map<String, Map<String, LanguageStats>> servers;
    private final Map<String, Map<String, LanguageStats>> modpacks;
    private final Map<String, Map<String, LanguageStats>> mods;

    private HubIndex(String generatedAt,
            Map<String, Map<String, LanguageStats>> servers,
            Map<String, Map<String, LanguageStats>> modpacks,
            Map<String, Map<String, LanguageStats>> mods) {
        this.generatedAt = generatedAt;
        this.servers = servers;
        this.modpacks = modpacks;
        this.mods = mods;
    }

    public static HubIndex empty() {
        return new HubIndex("", Map.of(), Map.of(), Map.of());
    }

    public String generatedAt() {
        return generatedAt;
    }

    public LanguageStats server(String host, String language) {
        return lookup(servers, host, language);
    }

    public LanguageStats modpack(String slug, String language) {
        return lookup(modpacks, slug, language);
    }

    public LanguageStats mod(String modId, String language) {
        return lookup(mods, modId, language);
    }

    public Set<String> modIds() {
        return mods.keySet();
    }

    private static LanguageStats lookup(Map<String, Map<String, LanguageStats>> table,
            String key, String language) {
        if (table == null || key == null || language == null) return null;
        Map<String, LanguageStats> byLanguage = table.get(key);
        return byLanguage == null ? null : byLanguage.get(LanguageFileStore.languageTag(language));
    }

    public HubIndex withServerEntry(String host, String language, LanguageStats stats) {
        return new HubIndex(generatedAt, withEntry(servers, host, language, stats), modpacks, mods);
    }

    public HubIndex withModpackEntry(String slug, String language, LanguageStats stats) {
        return new HubIndex(generatedAt, servers, withEntry(modpacks, slug, language, stats), mods);
    }

    public HubIndex withModEntry(String modId, String language, LanguageStats stats) {
        return new HubIndex(generatedAt, servers, modpacks, withEntry(mods, modId, language, stats));
    }

    private static Map<String, Map<String, LanguageStats>> withEntry(
            Map<String, Map<String, LanguageStats>> table, String key, String language, LanguageStats stats) {
        Map<String, Map<String, LanguageStats>> copy = new LinkedHashMap<>(table);
        Map<String, LanguageStats> byLanguage = new LinkedHashMap<>(copy.getOrDefault(key, Map.of()));
        byLanguage.put(LanguageFileStore.languageTag(language), stats);
        copy.put(key, Collections.unmodifiableMap(byLanguage));
        return Collections.unmodifiableMap(copy);
    }

    public static HubIndex read(Reader reader) throws IOException {
        try {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            String generatedAt = json.has("generatedAt") && !json.get("generatedAt").isJsonNull()
                    ? json.get("generatedAt").getAsString() : "";
            return new HubIndex(generatedAt,
                    readTable(json.getAsJsonObject("servers")),
                    readTable(json.getAsJsonObject("modpacks")),
                    readTable(json.getAsJsonObject("mods")));
        } catch (RuntimeException error) {
            throw new IOException("Invalid hub index", error);
        }
    }

    private static Map<String, Map<String, LanguageStats>> readTable(JsonObject object) {
        Map<String, Map<String, LanguageStats>> table = new LinkedHashMap<>();
        if (object == null) return table;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            Map<String, LanguageStats> byLanguage = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> langEntry : entry.getValue().getAsJsonObject().entrySet()) {
                if (!langEntry.getValue().isJsonObject()) continue;
                JsonObject stats = langEntry.getValue().getAsJsonObject();
                byLanguage.put(langEntry.getKey(), new LanguageStats(
                        intField(stats, "rows"), longField(stats, "bytes"),
                        stringField(stats, "sha256"), stringField(stats, "updatedAt"), licenseField(stats)));
            }
            table.put(entry.getKey(), byLanguage);
        }
        return table;
    }

    private static int intField(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    }

    private static long longField(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
    }

    private static String stringField(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    /** Informational field: anything that is not a short non-empty string counts as absent. */
    private static String licenseField(JsonObject o) {
        if (!o.has("license") || !o.get("license").isJsonPrimitive()
                || !o.get("license").getAsJsonPrimitive().isString()) return null;
        String text = o.get("license").getAsString();
        return text.isBlank() || text.length() > 256 ? null : text;
    }

    public void write(Writer writer) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA);
        json.addProperty("generatedAt", generatedAt);
        json.add("servers", writeTable(servers));
        json.add("modpacks", writeTable(modpacks));
        json.add("mods", writeTable(mods));
        GSON.toJson(json, writer);
    }

    private static JsonObject writeTable(Map<String, Map<String, LanguageStats>> table) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Map<String, LanguageStats>> entry : new TreeMap<>(table).entrySet()) {
            JsonObject byLanguage = new JsonObject();
            for (Map.Entry<String, LanguageStats> langEntry : new TreeMap<>(entry.getValue()).entrySet()) {
                LanguageStats stats = langEntry.getValue();
                JsonObject statsJson = new JsonObject();
                statsJson.addProperty("rows", stats.rows());
                statsJson.addProperty("bytes", stats.bytes());
                if (stats.sha256() != null) statsJson.addProperty("sha256", stats.sha256());
                if (stats.updatedAt() != null) statsJson.addProperty("updatedAt", stats.updatedAt());
                if (stats.license() != null) statsJson.addProperty("license", stats.license());
                byLanguage.add(langEntry.getKey(), statsJson);
            }
            object.add(entry.getKey(), byLanguage);
        }
        return object;
    }
}
