package com.dragonmeow.nyanslate.config;

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
 * Parses Minecraft's {@code options.txt} for old "mctranslator" keybind
 * lines (pre-Nyanslate rename) so a caller can carry their saved value over
 * to the equivalent new "nyanslate" {@code KeyMapping} / {@code KeyBinding},
 * once - only when the player had an old binding and has not already set (or
 * had migrated) the new one.
 *
 * <p>Pure string/{@code java.nio} parsing, no Minecraft/loader API, so this
 * is reused unmodified by every loader (modern {@code KeyMapping} and legacy
 * 1.12-1.16 {@code KeyBinding} both key their options.txt line the same way:
 * {@code key_<translation key>:<value>}).</p>
 */
public final class KeybindMigration {
    public static final String OLD_PREFIX = "key_key.mctranslator.";
    public static final String NEW_PREFIX = "key_key.nyanslate.";

    private KeybindMigration() {
    }

    /**
     * Parses raw {@code options.txt} content and returns, keyed by keybind
     * suffix (e.g. {@code "mode"} for {@code key.nyanslate.mode}), the old
     * saved value for every suffix in {@code suffixes} that has an old-prefixed
     * line ({@code key_key.mctranslator.<suffix>:<value>}) but no new-prefixed
     * line ({@code key_key.nyanslate.<suffix>:...}) anywhere in the file.
     * Iteration order of the result follows first appearance of the old line.
     */
    public static Map<String, String> findUnmigratedBindings(String optionsTxtContent, Collection<String> suffixes) {
        Map<String, String> oldValues = new LinkedHashMap<>();
        if (optionsTxtContent == null || suffixes == null || suffixes.isEmpty()) return oldValues;
        Set<String> wanted = new HashSet<>(suffixes);
        Set<String> newPresent = new HashSet<>();
        String[] lines = optionsTxtContent.split("\r\n|\n|\r");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon);
            if (key.startsWith(NEW_PREFIX)) {
                newPresent.add(key.substring(NEW_PREFIX.length()));
            } else if (key.startsWith(OLD_PREFIX)) {
                String suffix = key.substring(OLD_PREFIX.length());
                if (wanted.contains(suffix)) {
                    oldValues.put(suffix, line.substring(colon + 1));
                }
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
