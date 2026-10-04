package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntigravityCliClientTest {

    @TempDir
    Path tempDir;

    @Test
    void persistentStreamReturnsOneResultPerTurnAndDeduplicatesCumulativeUsage() throws Exception {
        SessionTokenUsage usage = new SessionTokenUsage();
        try (AntigravityCliClient client = client()) {
            client.setTokenUsage(usage);
            assertTrue(client.isInstalled());

            String first = client.complete("model-a", "SYSTEM_MARK", "USER_MARK");
            String second = client.complete("model-a", "SYSTEM_MARK", "USER_MARK");

            assertEquals("turn=1,boundary=true,system=true,user=true\n", first);
            assertEquals("turn=2,boundary=true,system=true,user=true\n", second);
            SessionTokenUsage.Snapshot snapshot = usage.snapshot();
            assertEquals(20, snapshot.inputTokens());
            assertEquals(6, snapshot.cachedInputTokens());
            assertEquals(4, snapshot.outputTokens());
            assertEquals(2, snapshot.reasoningOutputTokens());
            assertEquals(24, snapshot.totalTokens());
            assertEquals(1, snapshot.requests());
        }
    }

    @Test
    void changingModelRestartsTheStreamAndErrorsRemainActionable() throws Exception {
        try (AntigravityCliClient client = client()) {
            assertTrue(client.complete("model-a", "", "hello").startsWith("turn=1,"));
            assertTrue(client.complete("model-b", "", "hello").startsWith("turn=1,"));
        }

        try (AntigravityCliClient client = client()) {
            Exception error = assertThrows(Exception.class,
                    () -> client.complete("error", "", "hello"));
            assertTrue(error.getMessage().contains("fake authentication required"));
        }
    }

    @Test
    void listsAndCachesSignedInModels() throws Exception {
        try (AntigravityCliClient client = client()) {
            List<AntigravityCliClient.ModelOption> models = client.listModels();

            assertEquals(List.of(
                    new AntigravityCliClient.ModelOption(
                            "gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"),
                    new AntigravityCliClient.ModelOption(
                            "claude-sonnet-5.5-thinking", "Claude Sonnet 5.5 (Thinking)")), models);
            assertEquals(models, client.cachedModels());
        }
    }

    @Test
    void connectionTestUsesBoundedOneShotJsonMode() throws Exception {
        SessionTokenUsage usage = new SessionTokenUsage();
        try (AntigravityCliClient client = client()) {
            client.setTokenUsage(usage);
            assertEquals("你好，世界", client.testConnection("model-a"));
            assertTrue(client.hasAuthenticatedSessionCached());
            assertEquals(1, usage.snapshot().requests());
        }
    }

    @Test
    void openingTheInteractiveLogoutTerminalClearsCachedAccountState() throws Exception {
        try (AntigravityCliClient client = client()) {
            client.listModels();
            client.openLogoutTerminal();

            assertTrue(client.cachedModels().isEmpty());
            assertEquals("", client.cachedAccountEmail());
        }
    }

    @Test
    void extractsOnlyTheCliOAuthAccountLine() {
        String log = "ordinary text somebody@example.com\n"
                + "applyAuthResult: email=first@example.com, authMethod=consumer\n"
                + "OAuth: authenticated successfully as latest@example.com\n";

        assertEquals("latest@example.com", AntigravityCliClient.parseAccountEmail(log));
        assertEquals("", AntigravityCliClient.parseAccountEmail(
                "ordinary text somebody@example.com"));
    }

    private AntigravityCliClient client() {
        return new AntigravityCliClient(tempDir, Duration.ofSeconds(10), new FakeCommands());
    }

    private static final class FakeCommands implements AntigravityCliClient.CommandFactory {
        @Override
        public List<String> probeCommand() {
            return command("--version", "", "");
        }

        @Override
        public List<String> modelsCommand() {
            return command("models", "", "");
        }

        @Override
        public List<String> streamCommand(String model) {
            return command("stream", model, "");
        }

        @Override
        public List<String> loginCommand() {
            return command("--version", "", "");
        }

        @Override
        public List<String> testCommand(String model) {
            return command("test", model, "");
        }

        private static List<String> command(String mode, String model, String effort) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = new ArrayList<>();
            command.add(java);
            command.add("-cp");
            String separator = System.getProperty("path.separator");
            String testClasses = location(FakeCli.class);
            String gsonJar = location(JsonObject.class);
            command.add(testClasses + separator + gsonJar);
            command.add(FakeCli.class.getName());
            command.add(mode);
            command.add(model);
            command.add(effort);
            return command;
        }

        private static String location(Class<?> type) {
            try {
                return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            } catch (Exception e) {
                throw new IllegalStateException("Could not locate fake CLI dependency", e);
            }
        }
    }

    public static final class FakeCli {
        private FakeCli() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length > 0 && "--version".equals(args[0])) {
                System.out.println("agy fake 1.0");
                return;
            }
            if (args.length > 0 && "models".equals(args[0])) {
                System.out.println("Model ID                      Display Name");
                System.out.println("gemini-3.8-flash-high         Gemini 3.8 Flash (High)");
                System.out.println("claude-sonnet-5.5-thinking\tClaude Sonnet 5.5 (Thinking)");
                System.out.println("gemini-3.8-flash-high         Duplicate should be ignored");
                return;
            }
            if (args.length > 0 && "test".equals(args[0])) {
                String response = "{\"conversation_id\":\"test-session\",\"status\":\"SUCCESS\","
                        + "\"response\":\"你好，世界\",\"usage\":{\"input_tokens\":10,"
                        + "\"cache_read_tokens\":3,\"output_tokens\":2,"
                        + "\"thinking_tokens\":1,\"total_tokens\":12}}";
                System.out.write((response + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                System.out.flush();
                return;
            }
            String model = args.length > 1 ? args[1] : "";
            System.out.println("{\"event\":\"init\",\"conversation_id\":\"fake-session\",\"init\":{}}");
            System.out.flush();
            int turns = 0;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    turns++;
                    JsonObject request = new JsonParser().parse(line).getAsJsonObject();
                    String prompt = request.getAsJsonObject("message").get("content").getAsString();
                    JsonObject usage = new JsonObject();
                    usage.addProperty("input_tokens", turns * 10L);
                    usage.addProperty("cache_read_tokens", turns * 3L);
                    usage.addProperty("output_tokens", turns * 2L);
                    usage.addProperty("thinking_tokens", turns);
                    usage.addProperty("total_tokens", turns * 12L);
                    JsonObject result = new JsonObject();
                    result.addProperty("conversation_id", "fake-session");
                    result.addProperty("status", "error".equals(model) ? "ERROR" : "SUCCESS");
                    result.addProperty("response", "turn=" + turns
                            + ",boundary=" + prompt.contains("Do not inspect files")
                            + ",system=" + prompt.contains("SYSTEM_MARK")
                            + ",user=" + prompt.contains("USER_MARK") + "\n");
                    if ("error".equals(model)) {
                        result.addProperty("error", "fake authentication required");
                    }
                    result.add("usage", usage);
                    JsonObject event = new JsonObject();
                    event.addProperty("event", "result");
                    event.add("result", result);
                    System.out.println(event);
                    System.out.flush();
                    if ("error".equals(model)) return;
                }
            }
        }
    }
}
