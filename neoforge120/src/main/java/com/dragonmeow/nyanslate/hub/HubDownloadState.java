package com.dragonmeow.nyanslate.hub;

import com.dragonmeow.nyanslate.cache.LanguageFileStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable "last known sha256 per source+language" ledger, so planning a download
 * without any repository change reports "already up to date" instead of re-fetching
 * every file every time the player presses the download button.
 */
public final class HubDownloadState {
    private static final int SCHEMA = 1;
    private static final Gson GSON = new Gson();

    private final Path file;
    private final Map<String, String> shaByKey;

    public HubDownloadState(Path file) {
        this.file = file;
        this.shaByKey = load();
    }

    private static String key(HubSource source, String language) {
        // Normalized the same way as every other hub file/path, so "zh-TW" and "zh_tw"
        // (whatever casing a caller happens to pass) always hit the same ledger entry.
        return source.encode() + '\u0000' + LanguageFileStore.languageTag(language);
    }

    public synchronized String sha256(HubSource source, String language) {
        return shaByKey.get(key(source, language));
    }

    public synchronized void record(HubSource source, String language, String sha256) {
        if (sha256 == null) return;
        shaByKey.put(key(source, language), sha256);
        persist();
    }

    public synchronized void forget(HubSource source, String language) {
        if (shaByKey.remove(key(source, language)) != null) persist();
    }

    /** Drops every recorded sha256 for {@code language} (any source), so a planning pass
     *  right after clearing that language's {@link HubLocalCache} re-detects every file as
     *  downloadable instead of reporting "already up to date" for content whose upstream sha
     *  never changed. Other languages' throttling ledgers are left untouched. */
    public synchronized void forgetLanguage(String language) {
        String suffix = '\u0000' + LanguageFileStore.languageTag(language);
        boolean changed = shaByKey.keySet().removeIf(k -> k.endsWith(suffix));
        if (changed) persist();
    }

    private Map<String, String> load() {
        Map<String, String> loaded = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return loaded;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            if (json.has("sha") && json.get("sha").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("sha").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        loaded.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Best-effort: a damaged state file simply re-downloads everything once.
        }
        return loaded;
    }

    private void persist() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".nyanslate-hub-state-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                JsonObject json = new JsonObject();
                json.addProperty("schema", SCHEMA);
                JsonObject sha = new JsonObject();
                for (Map.Entry<String, String> entry : shaByKey.entrySet()) {
                    sha.addProperty(entry.getKey(), entry.getValue());
                }
                json.add("sha", sha);
                GSON.toJson(json, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best-effort; the in-memory state still serves this session correctly.
        }
    }
}
