package com.dragonmeow.nyanlex;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract for the version-specific BookViewScreen cache hooks. Minecraft owns the cached
 * line list, so the ordinary Minecraft-free service tests cannot observe whether a G-mode change
 * actually causes the book body to be split again.
 */
class BookPageModeCacheContractTest {
    private static final List<String> BOOK_MIXINS = List.of(
            "src/main/java/com/dragonmeow/nyanlex/fabric/mixin/BookPageMixin.java",
            "fabric1171/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/BookPageMixin.java",
            "fabric1182/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/BookPageMixin.java",
            "fabric1194/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/BookPageMixin.java",
            "fabric120/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/BookPageMixin.java",
            "fabric12111/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/BookPageMixin.java",
            "fabric2612/src/main/java/com/dragonmeow/nyanlex/fabric26/mixin/BookPageMixin.java",
            "fabric26/src/main/java/com/dragonmeow/nyanlex/fabric26/mixin/BookPageMixin.java",
            "neoforge120/src/main/java/com/dragonmeow/nyanlex/neoforge/mixin/BookPageMixin.java",
            "neoforge/src/main/java/com/dragonmeow/nyanlex/neoforge/mixin/BookPageMixin.java",
            "neoforge26/src/main/java/com/dragonmeow/nyanlex/neoforge26/mixin/BookPageMixin.java");

    private static final List<String> GENERIC_SCREEN_PLATFORMS = List.of(
            "src/main/java/com/dragonmeow/nyanlex/fabric/NyanLexFabric.java",
            "fabric120/src/main/java/com/dragonmeow/nyanlex/fabric/NyanLexFabric.java",
            "fabric12111/src/main/java/com/dragonmeow/nyanlex/fabric/NyanLexFabric.java",
            "fabric2612/src/main/java/com/dragonmeow/nyanlex/fabric26/NyanLexFabric26.java",
            "fabric26/src/main/java/com/dragonmeow/nyanlex/fabric26/NyanLexFabric26.java",
            "neoforge120/src/main/java/com/dragonmeow/nyanlex/neoforge/NyanLexNeoForge.java",
            "neoforge/src/main/java/com/dragonmeow/nyanlex/neoforge/NyanLexNeoForge.java",
            "neoforge26/src/main/java/com/dragonmeow/nyanlex/neoforge26/NyanLexNeoForge26.java");

    @Test
    void everyBookMixinResplitsForAsyncTranslationAndEffectiveModeTransitions() throws IOException {
        assertEquals(11, BOOK_MIXINS.size());
        Path root = repositoryRoot();
        for (String relative : BOOK_MIXINS) {
            Path file = root.resolve(relative);
            assertTrue(Files.isRegularFile(file), "missing book mixin: " + file);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String compact = source.replaceAll("\\s+", " ").replace("this.", "");

            assertTrue(compact.contains(
                            "@Unique private DisplayMode nyanlex$lastBookMode;"),
                    relative + ": nullable per-screen state makes the first render a safe transition");
            assertTrue(compact.contains(
                            "DisplayMode currentMode = service == null ? DisplayMode.ORIGINAL_ONLY : service.bookMode();"),
                    relative + ": a missing service must behave like the original-only view");

            String condition = "if (currentMode != DisplayMode.ORIGINAL_ONLY "
                    + "|| currentMode != nyanlex$lastBookMode)";
            int conditionAt = compact.indexOf(condition);
            int invalidateAt = compact.indexOf("cachedPage = -1;", conditionAt);
            int rememberAt = compact.indexOf("nyanlex$lastBookMode = currentMode;", invalidateAt);
            assertTrue(conditionAt >= 0 && invalidateAt > conditionAt && rememberAt > invalidateAt,
                    relative + ": translated frames and G-mode transitions must invalidate before remembering the mode");
            assertTrue(compact.contains("service.bookMode() != DisplayMode.ORIGINAL_ONLY"),
                    relative + ": the split redirect must still render the original body while G is active");
        }
    }

    @Test
    void orderedBookRowsCannotReenterTheGenericScreenSurface() throws IOException {
        Path root = repositoryRoot();
        for (String relative : GENERIC_SCREEN_PLATFORMS) {
            String source = Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
            int methodAt = source.indexOf("FormattedCharSequence screenText(");
            int nextOverloadAt = source.indexOf("FormattedText screenText(", methodAt);
            String method = source.substring(methodAt,
                    nextOverloadAt >= 0 ? nextOverloadAt : source.length());

            int bookGateAt = method.indexOf("instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen");
            int captureAt = method.indexOf("captureScreenText(");
            int translateAt = method.indexOf("\"screenTextFcs\"");
            assertTrue(bookGateAt >= 0 && captureAt > bookGateAt && translateAt > captureAt,
                    relative + ": book rows must stay owned by BookPageMixin before screen capture or translation");
        }
    }

    private static Path repositoryRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("sync-core.ps1"))) return cwd;
        Path parent = cwd.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("sync-core.ps1"))) return parent;
        throw new IllegalStateException("Cannot find repository root from " + cwd);
    }
}
