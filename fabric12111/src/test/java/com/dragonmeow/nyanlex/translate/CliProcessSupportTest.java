package com.dragonmeow.nyanlex.translate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CliProcessSupportTest {
    @TempDir Path directory;

    @Test
    void processEnvironmentDoesNotInheritCredentialsRuntimeInjectionOrProviderOverrides() {
        ProcessBuilder builder = new ProcessBuilder("unused");
        for (String key : new String[] {"OPENAI_API_KEY", "GEMINI_API_KEY", "NODE_OPTIONS",
                "JAVA_TOOL_OPTIONS", "CODEX_HOME", "OPENAI_BASE_URL", "HTTPS_PROXY"}) {
            builder.environment().put(key, "must-not-reach-child");
        }
        builder.environment().put("PATH", "required-os-path");
        CliProcessSupport.restrictEnvironment(builder);
        assertFalse(builder.environment().containsValue("must-not-reach-child"));
        assertEquals("required-os-path", builder.environment().get("PATH"));
        CliProcessSupport.isolateHome(builder, directory);
        assertEquals(directory.toAbsolutePath().toString(), builder.environment().get("HOME"));
    }

    @Test
    void lineLimitIsEnforcedBeforeAnUnterminatedLineCanGrowWithoutBound() throws Exception {
        try (BufferedReader reader = new BufferedReader(new StringReader("123456789"))) {
            assertThrows(IOException.class, () -> CliProcessSupport.readBoundedLine(reader, 8));
        }
        try (BufferedReader reader = new BufferedReader(new StringReader("123\r\n456\n"))) {
            assertEquals("123", CliProcessSupport.readBoundedLine(reader, 8));
            assertEquals("456", CliProcessSupport.readBoundedLine(reader, 8));
            assertNull(CliProcessSupport.readBoundedLine(reader, 8));
        }
    }

    @Test
    void editedPolicyIsRejectedInsteadOfSilentlyBroadeningPermissions() throws Exception {
        Path policy = directory.resolve("settings.json");
        CliProcessSupport.requirePolicyFile(policy, "deny all");
        Files.writeString(policy, "allow all");
        assertThrows(IOException.class, () -> CliProcessSupport.requirePolicyFile(policy, "deny all"));
        assertEquals("allow all", Files.readString(policy));
    }

    @Test
    void workspaceIsItsOwnGitRootAndReusesOnlyTheKnownPolicy() throws Exception {
        CliProcessSupport.prepareWorkspace(directory);
        assertTrue(Files.isDirectory(directory.resolve(".git/objects")));
        assertTrue(Files.isDirectory(directory.resolve(".git/refs/heads")));
        assertEquals("ref: refs/heads/translation\n", Files.readString(directory.resolve(".git/HEAD")));
        CliProcessSupport.prepareWorkspace(directory);
        Files.writeString(directory.resolve(".git/config"), "[include]\npath = ../../config\n");
        assertThrows(IOException.class, () -> CliProcessSupport.prepareWorkspace(directory));
    }
}
