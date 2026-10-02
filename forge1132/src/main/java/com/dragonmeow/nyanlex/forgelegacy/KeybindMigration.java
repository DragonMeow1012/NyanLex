package com.dragonmeow.nyanlex.forgelegacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses Minecraft's {@code options.txt} for keybind lines saved under the mod's
 * two earlier identities ("nyanslate", and before that "mctranslator") so a
 * caller can carry their saved value over to the equivalent current "nyanlex"
 * {@code KeyMapping} / {@code KeyBinding}, once - only when the player had an old
 * binding and has not already set (or had migrated) the new one. When both
 * earlier generations hold a line for the same keybind the newer one wins.
 *
 * <p>Pure string/{@code java.nio} parsing, no Minecraft/loader API, Java 8
 * source compatible, so it is reused unmodified by every loader (modern
 * {@code KeyMapping} and legacy 1.12-1.16 {@code KeyBinding} both key their
 * options.txt line the same way: {@code key_<translation key>:<value>}).</p>
 */
public final class KeybindMigration {
    /** Earlier options.txt key prefixes, newest first. Defined only here. */
    public static final String[] OLD_PREFIXES = {"key_key.nyanslate.", "key_key.mctranslator."};
    public static final String NEW_PREFIX = "key_key.nyanlex.";

    private KeybindMigration() {
    }

    /**
     * Parses raw {@code options.txt} content and returns, keyed by keybind
     * suffix (e.g. {@code "mode"} for {@code key.nyanlex.mode}), the old saved
     * value for every suffix in {@code suffixes} that has an old-prefixed line
     * but no new-prefixed line ({@code key_key.nyanlex.<suffix>:...}) anywhere
     * in the file. Iteration order of the result follows first appearance of
     * an old line.
     */
    public static Map<String, String> findUnmigratedBindings(String optionsTxtContent, Collection<String> suffixes) {
        Map<String, String> oldValues = new LinkedHashMap<>();
        if (optionsTxtContent == null || suffixes == null || suffixes.isEmpty()) return oldValues;
        Set<String> wanted = new HashSet<>(suffixes);
        Set<String> newPresent = new HashSet<>();
        Map<String, Integer> generation = new LinkedHashMap<>();
        String[] lines = optionsTxtContent.split("\r\n|\n|\r");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon);
            if (key.startsWith(NEW_PREFIX)) {
                newPresent.add(key.substring(NEW_PREFIX.length()));
                continue;
            }
            for (int gen = 0; gen < OLD_PREFIXES.length; gen++) {
                String prefix = OLD_PREFIXES[gen];
                if (!key.startsWith(prefix)) continue;
                String suffix = key.substring(prefix.length());
                if (!wanted.contains(suffix)) break;
                Integer have = generation.get(suffix);
                if (have == null || gen <= have) {
                    oldValues.put(suffix, line.substring(colon + 1));
                    generation.put(suffix, gen);
                }
                break;
            }
        }
        if (newPresent.isEmpty()) return oldValues;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : oldValues.entrySet()) {
            if (!newPresent.contains(e.getKey())) result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    /** Convenience wrapper: reads {@code optionsTxt} (if it exists and is a
     *  regular file) and delegates to {@link #findUnmigratedBindings(String, Collection)}.
     *  Never throws; an unreadable or missing file yields an empty map. */
    public static Map<String, String> findUnmigratedBindings(Path optionsTxt, Collection<String> suffixes) {
        if (optionsTxt == null || !Files.isRegularFile(optionsTxt)) return Collections.emptyMap();
        try {
            String content = new String(Files.readAllBytes(optionsTxt), StandardCharsets.UTF_8);
            return findUnmigratedBindings(content, suffixes);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }
}
