package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExchangeDumpWriter}'s 2026-10 belt-and-suspenders key redaction: per an explicit
 * user request, every body is compared against the ACTUAL configured API key values and
 * any match is replaced before the bytes ever reach disk, regardless of the pre-existing
 * design invariant that a key should never reach {@link ExchangeDumpWriter#record} at all.
 */
class ExchangeDumpWriterTest {

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("nyanslate-exchange-dump-test");
    }

    @Test
    void aKeyValueFoundInEitherBodyIsRedactedBeforeTheFileIsWritten() throws Exception {
        Path dir = tempDir().resolve("nyanslate-debug");
        String realKey = "sk-live-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        ExchangeDumpWriter writer = new ExchangeDumpWriter(dir, () -> true, 20,
                () -> List.of(realKey));

        String requestWithKey = "{\"model\":\"gpt-4o-mini\",\"authorization_echo\":\""
                + realKey + "\"}";
        String responseWithKey = "{\"error\":\"bad request for key " + realKey + "\"}";
        writer.record(requestWithKey, responseWithKey, List.of("Hello"),
                List.of(new TranslationResult("你好", null)));
        writer.awaitIdleForTest();

        String content = soleFileContent(dir);
        assertFalse(content.contains(realKey),
                "the real API key value must never reach disk: " + content);
        assertTrue(content.contains("***REDACTED-API-KEY***"),
                "a redaction marker must stand in for the key: " + content);
    }

    @Test
    void aShortSecretIsNeverBlanketRedactedToAvoidManglingOrdinaryText() throws Exception {
        Path dir = tempDir().resolve("nyanslate-debug");
        // Below MIN_REDACTABLE_SECRET_LENGTH: a short "key" like this could coincidentally
        // appear inside ordinary request text, so it is deliberately left alone.
        ExchangeDumpWriter writer = new ExchangeDumpWriter(dir, () -> true, 20,
                () -> List.of("ab12"));

        writer.record("plain request containing ab12 incidentally", "plain response", null, null);
        writer.awaitIdleForTest();

        String content = soleFileContent(dir);
        assertTrue(content.contains("ab12"),
                "a too-short secret must not blanket-redact ordinary text: " + content);
    }

    @Test
    void noSecretsConfiguredLeavesBodiesUntouched() throws Exception {
        Path dir = tempDir().resolve("nyanslate-debug");
        ExchangeDumpWriter writer = new ExchangeDumpWriter(dir, () -> true, 20);

        writer.record("request body", "response body", List.of("Hi"),
                List.of(new TranslationResult("嗨", null)));
        writer.awaitIdleForTest();

        String content = soleFileContent(dir);
        assertTrue(content.contains("request body"));
        assertTrue(content.contains("response body"));
    }

    private static String soleFileContent(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.toList();
            assertTrue(files.size() >= 1, "expected at least one dump file under " + dir);
            return Files.readString(files.get(0));
        }
    }
}
