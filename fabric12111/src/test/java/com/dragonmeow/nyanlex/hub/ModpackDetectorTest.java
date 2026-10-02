package com.dragonmeow.nyanlex.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModpackDetectorTest {

    @Test
    void detectsCurseForgePack(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("minecraftinstance.json"),
                "{\"name\":\"Example Pack\",\"baseModLoader\":{\"minecraftVersion\":\"1.20.1\"}}",
                StandardCharsets.UTF_8);

        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), List.of(), null);

        assertTrue(result.isPresent());
        assertEquals("example-pack", result.get().slug());
        assertEquals("Example Pack", result.get().displayName());
        assertEquals("1.20.1", result.get().gameVersion());
        assertEquals(ModpackIdentity.Source.CURSEFORGE, result.get().source());
    }

    @Test
    void detectsModrinthPack(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("modrinth.index.json"),
                "{\"name\":\"Example Pack\",\"dependencies\":{\"minecraft\":\"1.21.1\"}}",
                StandardCharsets.UTF_8);

        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), List.of(), null);

        assertTrue(result.isPresent());
        assertEquals("example-pack", result.get().slug());
        assertEquals("1.21.1", result.get().gameVersion());
        assertEquals(ModpackIdentity.Source.MODRINTH, result.get().source());
    }

    @Test
    void detectsPrismOrMultiMcPack(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("instance.cfg"),
                "[General]\nname=Example Pack\nlastLaunchTime=0\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mmc-pack.json"),
                "{\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"1.19.2\"},"
                        + "{\"uid\":\"net.fabricmc.fabric-loader\",\"version\":\"0.14.0\"}]}",
                StandardCharsets.UTF_8);

        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), List.of(), null);

        assertTrue(result.isPresent());
        assertEquals("example-pack", result.get().slug());
        assertEquals("1.19.2", result.get().gameVersion());
        assertEquals(ModpackIdentity.Source.PRISM_MULTIMC, result.get().source());
    }

    @Test
    void prismDetectionRequiresBothFiles(@TempDir Path root) throws IOException {
        // instance.cfg alone (no mmc-pack.json) must not be mistaken for a Prism/MultiMC
        // instance -- some other launcher's file could coincidentally share the name.
        Files.writeString(root.resolve("instance.cfg"), "name=Example Pack\n", StandardCharsets.UTF_8);

        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), List.of(), null);

        assertTrue(result.isEmpty());
    }

    @Test
    void detectsPackwizPack(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pack.toml"),
                "name = \"Example Pack\"\n\n[versions]\nminecraft = \"1.20.1\"\n",
                StandardCharsets.UTF_8);

        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), List.of(), null);

        assertTrue(result.isPresent());
        assertEquals("Example Pack", result.get().displayName());
        assertEquals("1.20.1", result.get().gameVersion());
        assertEquals(ModpackIdentity.Source.PACKWIZ, result.get().source());
    }

    @Test
    void detectsFtbInstance(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("instance.json"), "{\"name\":\"Example Pack\"}",
                StandardCharsets.UTF_8);

        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), List.of(), null);

        assertTrue(result.isPresent());
        assertEquals("Example Pack", result.get().displayName());
        assertEquals(ModpackIdentity.Source.FTB, result.get().source());
    }

    @Test
    void fallsBackToModListHashWhenNoLauncherFileExists(@TempDir Path root) {
        List<String> loadedModIds = List.of("minecraft", "java", "fabricloader", "fabric-api",
                "nyanlex", "somemod", "othermod", "thirdmod");

        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), loadedModIds, "1.20.1");

        assertTrue(result.isPresent());
        assertTrue(result.get().slug().startsWith("modset-"));
        assertEquals(ModpackIdentity.Source.MOD_LIST_FALLBACK, result.get().source());
        assertEquals("1.20.1", result.get().gameVersion());
    }

    @Test
    void modListFallbackIsStableRegardlessOfInputOrder(@TempDir Path root) {
        List<String> order1 = List.of("somemod", "othermod", "thirdmod");
        List<String> order2 = List.of("thirdmod", "somemod", "othermod");

        String slug1 = ModpackDetector.detect(List.of(root), order1, null).orElseThrow().slug();
        String slug2 = ModpackDetector.detect(List.of(root), order2, null).orElseThrow().slug();

        assertEquals(slug1, slug2);
    }

    @Test
    void modListFallbackRequiresAtLeastThreeNonVanillaMods(@TempDir Path root) {
        Optional<ModpackIdentity> result =
                ModpackDetector.detect(List.of(root), List.of("minecraft", "somemod"), null);

        assertTrue(result.isEmpty());
    }

    @Test
    void noFilesAndNoModsDetectsNothing(@TempDir Path root) {
        Optional<ModpackIdentity> result = ModpackDetector.detect(List.of(root), List.of(), null);

        assertTrue(result.isEmpty());
    }
}
