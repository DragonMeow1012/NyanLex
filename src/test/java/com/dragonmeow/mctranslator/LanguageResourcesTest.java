package com.dragonmeow.mctranslator;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageResourcesTest {

    private static final Path LANGUAGE_DIRECTORY =
            Path.of("src/main/resources/assets/mctranslator/lang");

    @Test
    void everyCanonicalLanguageFileIsValidUtf8Json() throws IOException {
        List<Path> languageFiles;
        try (var paths = Files.list(LANGUAGE_DIRECTORY)) {
            languageFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }

        assertFalse(languageFiles.isEmpty(), "canonical language directory must not be empty");
        for (Path languageFile : languageFiles) {
            try (Reader reader = Files.newBufferedReader(languageFile, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                assertTrue(root.isJsonObject(), languageFile + " must contain a JSON object");
            }
        }
    }
}
