package com.dragonmeow.nyanlex.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort modpack identification from launcher instance files, tried in a fixed
 * order against every candidate root (the game directory and its parents): {@code minecraftinstance.json},
 * {@code modrinth.index.json}, {@code instance.cfg} + {@code mmc-pack.json},
 * {@code pack.toml}
 * and a launcher manifest ({@code instance.json}). When none of those files are found, falls back
 * to hashing the loaded mod-id set into a stable {@code modset-<12 hex>} slug, so even
 * an unrecognised launcher still groups identical mod sets together.
 */
public final class ModpackDetector {
    private static final Set<String> DEFAULT_EXCLUDED_MOD_IDS = Set.of(
            "minecraft", "java", "fabricloader", "fabric-api", "forge", "neoforge", "nyanlex");
    private static final int MIN_MODSET_SIZE = 3;
    private static final Pattern PACK_TOML_NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern PACK_TOML_VERSION =
            Pattern.compile("(?m)^\\s*minecraft\\s*=\\s*\"([^\"]*)\"");

    private ModpackDetector() {
    }

    public static Optional<ModpackIdentity> detect(List<Path> candidateRoots,
            List<String> loadedModIds, String gameVersion) {
        if (candidateRoots != null) {
            for (Path root : candidateRoots) {
                if (root == null) continue;
                ModpackIdentity found = tryInstanceManifest(root);
                if (found == null) found = tryPackIndex(root);
                if (found == null) found = tryInstanceCfg(root);
                if (found == null) found = tryPackToml(root);
                if (found == null) found = tryInstanceJson(root);
                if (found != null) return Optional.of(found);
            }
        }
        return modListFallback(loadedModIds, gameVersion);
    }

    private static ModpackIdentity tryInstanceManifest(Path root) {
        Path file = root.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = parseJson(file);
            String name = stringField(json, "name");
            if (name == null || name.isBlank()) return null;
            String version = null;
            if (json.has("baseModLoader") && json.get("baseModLoader").isJsonObject()) {
                version = stringField(json.getAsJsonObject("baseModLoader"), "minecraftVersion");
            }
            return new ModpackIdentity(HubSlug.of(name), name, version, ModpackIdentity.Source.INSTANCE_MANIFEST);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static ModpackIdentity tryPackIndex(Path root) {
        Path file = root.resolve("modrinth.index.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = parseJson(file);
            String name = stringField(json, "name");
            if (name == null || name.isBlank()) return null;
            String version = null;
            if (json.has("dependencies") && json.get("dependencies").isJsonObject()) {
                version = stringField(json.getAsJsonObject("dependencies"), "minecraft");
            }
            return new ModpackIdentity(HubSlug.of(name), name, version, ModpackIdentity.Source.PACK_INDEX);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static ModpackIdentity tryInstanceCfg(Path root) {
        Path cfg = root.resolve("instance.cfg");
        Path pack = root.resolve("mmc-pack.json");
        if (!Files.isRegularFile(cfg) || !Files.isRegularFile(pack)) return null;
        try {
            String name = null;
            for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                if (line.startsWith("name=")) {
                    name = line.substring("name=".length()).strip();
                    break;
                }
            }
            if (name == null || name.isBlank()) return null;
            String version = null;
            JsonObject json = parseJson(pack);
            if (json.has("components") && json.get("components").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("components")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject component = element.getAsJsonObject();
                    if ("net.minecraft".equals(stringField(component, "uid"))) {
                        version = stringField(component, "version");
                        break;
                    }
                }
            }
            return new ModpackIdentity(HubSlug.of(name), name, version, ModpackIdentity.Source.INSTANCE_CFG);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static ModpackIdentity tryPackToml(Path root) {
        Path file = root.resolve("pack.toml");
        if (!Files.isRegularFile(file)) return null;
        try {
            String content = readAll(file);
            Matcher nameMatcher = PACK_TOML_NAME.matcher(content);
            String name = nameMatcher.find() ? nameMatcher.group(1) : null;
            String folderName = folderDisplayName(root);
            String displayName = !isBlank(name) ? name : folderName;
            if (isBlank(displayName)) return null;
            Matcher versionMatcher = PACK_TOML_VERSION.matcher(content);
            String version = versionMatcher.find() ? versionMatcher.group(1) : null;
            String slug = HubSlug.of(!isBlank(folderName) ? folderName : displayName);
            return new ModpackIdentity(slug, displayName, version, ModpackIdentity.Source.PACK_TOML);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static ModpackIdentity tryInstanceJson(Path root) {
        Path file = root.resolve("instance.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = parseJson(file);
            String name = stringField(json, "name");
            String folderName = folderDisplayName(root);
            String displayName = !isBlank(name) ? name : folderName;
            if (isBlank(displayName)) return null;
            String slug = HubSlug.of(!isBlank(folderName) ? folderName : displayName);
            return new ModpackIdentity(slug, displayName, null, ModpackIdentity.Source.INSTANCE_JSON);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String folderDisplayName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? null : fileName.toString();
    }

    private static Optional<ModpackIdentity> modListFallback(List<String> loadedModIds, String gameVersion) {
        if (loadedModIds == null) return Optional.empty();
        List<String> filtered = new ArrayList<>();
        for (String id : loadedModIds) {
            if (id == null) continue;
            String normalized = id.toLowerCase(Locale.ROOT);
            if (!DEFAULT_EXCLUDED_MOD_IDS.contains(normalized)) filtered.add(normalized);
        }
        if (filtered.size() < MIN_MODSET_SIZE) return Optional.empty();
        Collections.sort(filtered);
        String joined = String.join(",", filtered);
        String slug = "modset-" + sha256Hex12(joined);
        return Optional.of(new ModpackIdentity(slug, null, gameVersion, ModpackIdentity.Source.MOD_LIST_FALLBACK));
    }

    private static String sha256Hex12(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) hex.append(String.format(Locale.ROOT, "%02x", hash[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String readAll(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static JsonObject parseJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new JsonParser().parse(reader).getAsJsonObject();
        }
    }

    private static String stringField(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
