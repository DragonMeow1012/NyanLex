package com.dragonmeow.nyanlex.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeybindMigrationTest {

    private static final List<String> SUFFIXES = Arrays.asList("mode", "retranslate", "screenscan", "toggle");

    @Test
    void findsOldBindingWithNoNewOneYet() {
        String options = "version:1\n"
                + "key_key.mctranslator.mode:key.keyboard.g\n"
                + "key_key.attack:key.mouse.left\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals(1, result.size());
        assertEquals("key.keyboard.g", result.get("mode"));
    }

    @Test
    void skipsWhenNewBindingAlreadyPresent() {
        String options = "key_key.mctranslator.mode:key.keyboard.g\n"
                + "key_key.nyanlex.mode:key.keyboard.h\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertTrue(result.isEmpty());
    }

    @Test
    void migratesOnlyRequestedSuffixes() {
        String options = "key_key.mctranslator.mode:key.keyboard.g\n"
                + "key_key.mctranslator.retranslate:key.keyboard.r\n"
                + "key_key.mctranslator.screenscan:key.keyboard.p\n"
                + "key_key.mctranslator.toggle:key.keyboard.t\n"
                + "key_key.mctranslator.unrelated:key.keyboard.z\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals(4, result.size());
        assertEquals("key.keyboard.g", result.get("mode"));
        assertEquals("key.keyboard.r", result.get("retranslate"));
        assertEquals("key.keyboard.p", result.get("screenscan"));
        assertEquals("key.keyboard.t", result.get("toggle"));
        assertTrue(!result.containsKey("unrelated"));
    }

    @Test
    void handlesCrlfLineEndings() {
        String options = "key_key.mctranslator.mode:key.keyboard.g\r\n"
                + "key_key.other:1\r\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals("key.keyboard.g", result.get("mode"));
    }

    @Test
    void emptyOrNullContentYieldsEmptyMap() {
        assertTrue(KeybindMigration.findUnmigratedBindings("", SUFFIXES).isEmpty());
        assertTrue(KeybindMigration.findUnmigratedBindings((String) null, SUFFIXES).isEmpty());
    }

    @Test
    void noOldBindingsYieldsEmptyMap() {
        String options = "key_key.nyanlex.mode:key.keyboard.g\n";
        assertTrue(KeybindMigration.findUnmigratedBindings(options, SUFFIXES).isEmpty());
    }

    @Test
    void readsFromRealOptionsFile(@TempDir Path dir) throws IOException {
        Path optionsTxt = dir.resolve("options.txt");
        Files.writeString(optionsTxt,
                "key_key.mctranslator.toggle:key.keyboard.g\n",
                StandardCharsets.UTF_8);

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(optionsTxt, SUFFIXES);

        assertEquals(1, result.size());
        assertEquals("key.keyboard.g", result.get("toggle"));
    }

    @Test
    void missingOptionsFileYieldsEmptyMap(@TempDir Path dir) {
        Map<String, String> result = KeybindMigration.findUnmigratedBindings(
                dir.resolve("does-not-exist.txt"), SUFFIXES);
        assertTrue(result.isEmpty());
    }

    // --- 2026-10-01 audit finding (rename-review): the parser treats the stored value as an
    // opaque string — it must carry a mouse binding or the vanilla "unbound" sentinel through
    // unchanged, exactly like a keyboard key, since real decoding/tolerance for these values
    // happens downstream in each loader's own InputConstants.getKey/InputMappings.getInputByName
    // consumer, not in this parser. ---

    @Test
    void carriesAMouseBindingThrough() {
        String options = "key_key.mctranslator.retranslate:key.mouse.left\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals(1, result.size());
        assertEquals("key.mouse.left", result.get("retranslate"));
    }

    @Test
    void carriesAnyMouseButtonIndexThrough() {
        // Later mouse buttons (side buttons etc.) are plain "key.mouse.<n>" names.
        String options = "key_key.mctranslator.mode:key.mouse.middle\n"
                + "key_key.mctranslator.toggle:key.mouse.4\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals("key.mouse.middle", result.get("mode"));
        assertEquals("key.mouse.4", result.get("toggle"));
    }

    @Test
    void carriesTheUnboundSentinelThrough() {
        // "key.keyboard.unknown" is vanilla's own "this binding is unset" value; it must be
        // recognized as an unmigrated old binding and copied over like any other value — never
        // special-cased/dropped by this parser.
        String options = "key_key.mctranslator.screenscan:key.keyboard.unknown\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals(1, result.size());
        assertEquals("key.keyboard.unknown", result.get("screenscan"));
    }

    @Test
    void carriesALegacyNegativeForgeKeyCodeThrough() {
        // forge1122 (pre-1.13 Forge) stores raw LWJGL key codes, including negative mouse-button
        // codes (e.g. -100 + button index), as a plain integer string.
        String options = "key_key.mctranslator.mode:-99\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals("-99", result.get("mode"));
    }

    // --- two earlier generations ("nyanslate" newer, "mctranslator" older) ---

    @Test
    void newerGenerationBindingWinsOverOlderOne() {
        String options = "key_key.mctranslator.mode:key.keyboard.g\n"
                + "key_key.nyanslate.mode:key.keyboard.h\n"
                + "key_key.mctranslator.toggle:key.keyboard.t\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals(2, result.size());
        assertEquals("key.keyboard.h", result.get("mode"));
        assertEquals("key.keyboard.t", result.get("toggle"));
    }

    @Test
    void newerGenerationWinsRegardlessOfLineOrder() {
        String options = "key_key.nyanslate.mode:key.keyboard.h\n"
                + "key_key.mctranslator.mode:key.keyboard.g\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals("key.keyboard.h", result.get("mode"));
    }

    @Test
    void migratesFromOnlyTheNewerGeneration() {
        String options = "key_key.nyanslate.retranslate:key.mouse.left\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals(1, result.size());
        assertEquals("key.mouse.left", result.get("retranslate"));
    }

    @Test
    void currentBindingBlocksBothEarlierGenerations() {
        String options = "key_key.mctranslator.mode:key.keyboard.g\n"
                + "key_key.nyanslate.mode:key.keyboard.h\n"
                + "key_key.nyanlex.mode:key.keyboard.j\n"
                + "key_key.nyanslate.toggle:key.keyboard.t\n";

        Map<String, String> result = KeybindMigration.findUnmigratedBindings(options, SUFFIXES);

        assertEquals(1, result.size());
        assertEquals("key.keyboard.t", result.get("toggle"));
    }
}
