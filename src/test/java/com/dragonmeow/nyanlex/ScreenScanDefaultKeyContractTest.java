package com.dragonmeow.nyanlex;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract for the default key of the "translate screen" action, one entry per source tree.
 *
 * <p>The vanilla social-interactions screen owns P from Minecraft 1.16.5 on. Up to and including 1.21.8
 * the game's key table hands a pressed key to ONE mapping (loaders that rewrite it give the key to both),
 * so a P default either never reached the mod while no screen was open or fired two actions at once:
 * those trees default to backslash. From 1.21.9 on the game hands the key to every mapping that uses
 * it, so those trees keep P. The engines older than 1.17 are not touched at all.
 *
 * <p>Only the default is pinned: a binding saved in options.txt overrides it (see KeybindMigrationTest).
 * The 26.3 variants compile the 26.2 sources and register nothing themselves, which the last test checks.
 * The file locations are resolved from the repository root, so the test gives the same result when it
 * runs from a copy that lives in another tree's test directory.
 */
class ScreenScanDefaultKeyContractTest {

    private static final String FABRIC = "src/main/java/com/dragonmeow/nyanlex/fabric/NyanLexFabric.java";
    private static final String FABRIC_26 = "src/main/java/com/dragonmeow/nyanlex/fabric26/NyanLexFabric26.java";
    private static final String NEOFORGE = "src/main/java/com/dragonmeow/nyanlex/neoforge/NyanLexNeoForge.java";
    private static final String NEOFORGE_26 = "src/main/java/com/dragonmeow/nyanlex/neoforge26/NyanLexNeoForge26.java";
    private static final String LEGACY_FABRIC = "src/main/java/com/dragonmeow/nyanlex/legacy/LegacyTranslatorMod.java";
    private static final String LEGACY_FORGE = "src/main/java/com/dragonmeow/nyanlex/forgelegacy/NyanLexForge.java";

    /** Minecraft 1.17 to 1.21.8: the key goes to one mapping, or the loader makes two actions share it. */
    private static final List<String> BACKSLASH_TREES = List.of(
            FABRIC,                                // 1.21.1
            "fabric1182/" + FABRIC,                // 1.18.2
            "fabric1171/" + FABRIC,                // 1.17.1
            "fabric1194/" + FABRIC,                // 1.19.4
            "fabric120/" + FABRIC,                 // 1.20.1
            "neoforge120/" + NEOFORGE,             // 1.20.1
            "neoforge/" + NEOFORGE);               // 1.21.1

    /** Minecraft 1.21.9 and later: every mapping that uses the key receives it. */
    private static final List<String> P_TREES = List.of(
            "fabric12111/" + FABRIC,               // 1.21.11
            "fabric2612/" + FABRIC_26,             // 26.1.2
            "fabric26/" + FABRIC_26,               // 26.2
            "neoforge26/" + NEOFORGE_26);          // 26.2

    /** Engines older than 1.17: unchanged. */
    private static final List<String> LEGACY_TREES = List.of(
            "fabric1144/" + LEGACY_FABRIC,
            "fabric1152/" + LEGACY_FABRIC,
            "fabric1165/" + LEGACY_FABRIC,
            "forge1122/" + LEGACY_FORGE,
            "forge1132/" + LEGACY_FORGE);

    /** Source directories of the 26.3 variants and of the shared 26.x platform code. */
    private static final List<String> SHARED_PLATFORM_DIRS = List.of(
            "fabric263/src/main/java", "neoforge263/src/main/java",
            "platform26/src/main/java", "platform263/src/main/java");

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("sync-core.ps1"))) dir = dir.getParent();
        assertTrue(dir != null, "the repository root (the directory holding sync-core.ps1) is above the test's working directory");
        return dir;
    }

    /** The key constant a tree registers for {@code key.nyanlex.<suffix>}, without its GLFW_/KEY_ prefix. */
    private static String registeredKey(String file, String suffix) throws IOException {
        String text = Files.readString(repositoryRoot().resolve(file), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"key\\.nyanlex\\." + suffix + "\",\\s*"
                + "(?:InputConstants\\.Type\\.KEYSYM,\\s*)?(?:GLFW\\.|InputConstants\\.|Keyboard\\.)(\\w+)(?:\\.getValue\\(\\))?[,)]")
                .matcher(text);
        assertTrue(m.find(), file + ": the " + suffix + " key is registered");
        String constant = m.group(1);
        assertFalse(m.find(), file + ": the " + suffix + " key is registered exactly once");
        return constant.replaceFirst("^GLFW_", "").replaceFirst("^KEY_", "");
    }

    private static void assertDefaults(List<String> files, String expected, String why) throws IOException {
        for (String file : files) {
            assertEquals(expected, registeredKey(file, "screenscan"), file + ": default key of the screen-scan action (" + why + ")");
        }
    }

    @Test
    void treesOnGamesThatGiveAKeyToOneMappingDefaultToBackslashNotP() throws IOException {
        assertDefaults(BACKSLASH_TREES, "BACKSLASH", "social interactions owns P on this game version");
    }

    @Test
    void treesOnGamesThatGiveAKeyToEveryMappingKeepP() throws IOException {
        assertDefaults(P_TREES, "P", "Minecraft 1.21.9 and later hand the key to every mapping");
    }

    @Test
    void enginesOlderThan117AreUntouched() throws IOException {
        assertDefaults(LEGACY_TREES, "P", "engines older than 1.17 keep their default");
    }

    @Test
    void theOtherDefaultsAreUnchangedInEveryModernTree() throws IOException {
        List<String> modern = Stream.of(BACKSLASH_TREES, P_TREES)
                .flatMap(List::stream).toList();
        for (String file : modern) {
            assertEquals("R", registeredKey(file, "retranslate"), file + ": retranslate stays on R");
            assertEquals("G", registeredKey(file, "toggle"), file + ": toggle stays on G");
            assertEquals("UNKNOWN", registeredKey(file, "mode"), file + ": settings key stays unbound");
        }
    }

    @Test
    void the263VariantsAndTheSharedPlatformCodeRegisterNoKeyOfTheirOwn() throws IOException {
        for (String dir : SHARED_PLATFORM_DIRS) {
            Path path = repositoryRoot().resolve(dir);
            if (!Files.isDirectory(path)) continue;
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("key.nyanlex.screenscan"),
                            file + ": the default key of the screen-scan action is registered in the 26.2 tree only");
                }
            }
        }
    }
}
