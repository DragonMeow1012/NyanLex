package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.translate.TextFilter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One repository translation file, schema 2. It carries {@code sha256(key) -> translation}
 * only: no source text, player name or example ever appears in it. Schema 1 files (which
 * mapped the source text itself) are rejected outright, never read, so this client can
 * not re-distribute source text through a stale file.
 *
 * <pre>
 * {"schema":2,"format":"hub-hash-v1","hash":"sha256","language":"zh-tw","rows":N,
 *  "entries":{"&lt;64 hex&gt;":"translation", ...}}
 * </pre>
 *
 * <p>A file whose rows derive from a project under a copyleft license also carries an optional
 * {@code "license"} (an SPDX expression, written between {@code language} and {@code rows}). It is
 * information for people only: this client reads it leniently (a missing, empty, over-long or
 * non-string value is simply no license) and never decides anything from it.</p>
 */
public final class HubFile {
    public static final int SCHEMA = 2;
    public static final String FORMAT = "hub-hash-v1";
    static final int MAX_ROWS = HubPaths.MAX_FILE_ROWS;
    static final int MAX_VALUE_LENGTH = 16384;
    static final int MAX_LICENSE_LENGTH = 256;
    private static final Logger LOG = Logger.getLogger("nyanlex");

    private final String language;
    private final Map<String, String> entries;
    private final String license;

    public HubFile(String language, Map<String, String> entries) {
        this(language, entries, null);
    }

    /** @param license SPDX expression of the source license the rows must keep, or null for the repository default */
    public HubFile(String language, Map<String, String> entries, String license) {
        this.language = language.toLowerCase(Locale.ROOT).replace('_', '-');
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.license = license == null || license.isBlank() ? null : license;
    }

    public String language() {
        return language;
    }

    /** The license the file declares for itself, or null when it declares none (repository default). */
    public String license() {
        return license;
    }

    /** hash -> translation. */
    public Map<String, String> entries() {
        return entries;
    }

    public static HubFile read(String json) throws IOException {
        if (json == null) throw new IOException("Invalid hub file");
        if (json.getBytes(StandardCharsets.UTF_8).length > HubPaths.MAX_FILE_BYTES) {
            throw new IOException("Hub file exceeds " + HubPaths.MAX_FILE_BYTES + " bytes");
        }
        try {
            JsonObject root = new JsonParser().parse(new StringReader(json)).getAsJsonObject();
            JsonElement schema = root.get("schema");
            int version = schema != null && schema.isJsonPrimitive() ? schema.getAsInt() : -1;
            if (version != SCHEMA) {
                LOG.warning("[nyanlex hub] rejected repository file with schema " + version
                        + " (only schema " + SCHEMA + " hashed files are accepted)");
                throw new IOException("Unsupported hub file schema: " + version);
            }
            if (!FORMAT.equals(string(root, "format"))) throw new IOException("Unsupported hub file format");
            String language = string(root, "language");
            JsonObject entriesJson = root.getAsJsonObject("entries");
            if (entriesJson == null || entriesJson.size() > MAX_ROWS) throw new IOException("Invalid hub rows");
            Map<String, String> entries = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> row : entriesJson.entrySet()) {
                JsonElement value = row.getValue();
                if (!HubKeyHash.isHash(row.getKey()) || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()) {
                    throw new IOException("Invalid hub row");
                }
                entries.put(row.getKey(), value.getAsString());
            }
            return new HubFile(language, entries, optionalString(root, "license"));
        } catch (RuntimeException e) {
            throw new IOException("Invalid hub file", e);
        }
    }

    private static String string(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Invalid hub field: " + key);
        }
        String text = value.getAsString();
        if (text.isEmpty() || text.length() > 256) throw new IOException("Invalid hub field: " + key);
        return text;
    }

    /** A purely informational field: anything that is not a short non-empty string counts as absent. */
    private static String optionalString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String text = value.getAsString();
        return text.isBlank() || text.length() > MAX_LICENSE_LENGTH ? null : text;
    }

    /** Row-level checks that need no source text (the file has none): the value itself
     *  must be non-empty, bounded and carry no URL/domain at all. The source-aware checks
     *  run on a hit instead, see {@link HubImportValidator#acceptsOnHit}. */
    public static boolean plausibleValue(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_VALUE_LENGTH
                && !TextFilter.hasForeignUrl("", value);
    }

    /** Deterministic output (rows sorted by hash, one per line) so repository diffs stay
     *  reviewable. */
    public void write(Path path) throws IOException {
        try (Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writeTo(out);
        }
    }

    public void writeTo(Writer out) throws IOException {
        List<String> hashes = new ArrayList<>(entries.keySet());
        Collections.sort(hashes);
        JsonWriter json = new JsonWriter(out);
        json.setHtmlSafe(false);
        json.beginObject();
        json.name("schema").value(SCHEMA);
        json.name("format").value(FORMAT);
        json.name("hash").value("sha256");
        json.name("language").value(language);
        if (license != null) json.name("license").value(license);
        json.name("rows").value(hashes.size());
        json.name("entries").beginObject();
        for (String hash : hashes) {
            out.write('\n');
            json.name(hash).value(entries.get(hash));
        }
        json.endObject();
        json.endObject();
        json.flush();
    }
}
