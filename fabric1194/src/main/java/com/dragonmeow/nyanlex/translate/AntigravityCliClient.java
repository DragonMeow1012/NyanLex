package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent client for Antigravity CLI's official stream-json headless protocol.
 *
 * <p>The CLI owns Google authentication in the operating system keyring. NyanLex never
 * reads or stores those credentials; it only sends translation prompts over stdin and
 * consumes one terminal {@code result} event per request.</p>
 */
public final class AntigravityCliClient implements AutoCloseable {

    private static final Duration RESULT_TIMEOUT = Duration.ofMinutes(6);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECTION_TEST_TIMEOUT = Duration.ofSeconds(40);
    private static final int MAX_STREAM_LINE_CHARS = 1_000_000;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 262_144;
    private static final int MAX_ACCOUNT_LOG_BYTES = 262_144;
    private static final int MAX_STDERR_CHARS = 8_192;
    private static final int MAX_TURNS_PER_SESSION = 32;
    private static final int MAX_PROMPT_CHARS_PER_SESSION = 250_000;
    private static final int EVENT_QUEUE_CAPACITY = 2_048;
    private static final String TRANSLATION_BOUNDARY =
            "You are a text-translation backend. Do not inspect files, run commands, use tools, "
                    + "or modify the workspace. Return only the response format requested by the prompt.";
    private static final Pattern ACCOUNT_EMAIL_LINE = Pattern.compile(
            "(?:applyAuthResult:\\s*email=|OAuth:\\s*authenticated successfully as\\s+)"
                    + "([A-Z0-9._%+\\-]{1,64}@[A-Z0-9.\\-]{1,253}\\.[A-Z]{2,63})",
            Pattern.CASE_INSENSITIVE);

    private final Path workspace;
    private final Duration resultTimeout;
    private final CommandFactory commands;
    private Generation generation;
    private volatile boolean installedCached;
    private volatile List<ModelOption> cachedModels = List.of();
    private volatile String cachedAccountEmail = "";
    private volatile boolean authenticatedCached;
    private volatile String lastError = "";
    private volatile SessionTokenUsage tokenUsage;

    public AntigravityCliClient(Path workspace) {
        this(workspace, RESULT_TIMEOUT, new DefaultCommandFactory());
    }

    AntigravityCliClient(Path workspace, Duration resultTimeout, CommandFactory commands) {
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        this.resultTimeout = Objects.requireNonNull(resultTimeout, "resultTimeout");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public void setTokenUsage(SessionTokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public boolean isInstalledCached() {
        return installedCached;
    }

    public String lastError() {
        return lastError;
    }

    public List<ModelOption> cachedModels() {
        return cachedModels;
    }

    /** Account identity reported by the CLI's own OAuth diagnostic line; never persisted by NyanLex. */
    public String cachedAccountEmail() {
        return cachedAccountEmail;
    }

    /** Last authentication state confirmed from the CLI's own OAuth log or a successful
     * headless request. Model discovery alone is not proof of sign-in. */
    public boolean hasAuthenticatedSessionCached() {
        return authenticatedCached;
    }

    /** Probe the official CLI without invoking authentication or submitting a model request. */
    public boolean isInstalled() {
        Process probe = null;
        try {
            List<String> command = commands.probeCommand();
            probe = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean exited = probe.waitFor(5, TimeUnit.SECONDS);
            if (!exited) probe.destroyForcibly();
            installedCached = exited && probe.exitValue() == 0;
            lastError = installedCached ? "" : "Antigravity CLI did not start successfully";
            return installedCached;
        } catch (IOException e) {
            installedCached = false;
            lastError = message(e, "Antigravity CLI was not found");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            installedCached = false;
            lastError = "Antigravity CLI check was interrupted";
            return false;
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
        }
    }

    /** Open an interactive terminal; the CLI itself handles Google sign-in and its keyring. */
    public void openLoginTerminal() throws IOException {
        if (!isInstalled()) throw new IOException(lastError);
        Files.createDirectories(workspace);
        new ProcessBuilder(commands.loginCommand()).directory(workspace.toFile()).start();
    }

    /** Read the live model catalog exposed by the signed-in Antigravity CLI. */
    public synchronized List<ModelOption> listModels() throws IOException {
        String commandOutput;
        try {
            commandOutput = runBoundedCommand(commands.modelsCommand(), "model discovery");
        } catch (IOException e) {
            clearAccountCache();
            throw e;
        }
        List<ModelOption> models = parseModels(commandOutput);
        if (models.isEmpty()) {
            clearAccountCache();
            lastError = "Antigravity CLI returned no available models";
            throw new IOException(lastError);
        }
        cachedModels = models;
        cachedAccountEmail = readLatestAccountEmail();
        authenticatedCached = !cachedAccountEmail.isBlank();
        lastError = "";
        return models;
    }

    /**
     * Bounded one-shot connection test. Unlike the persistent translation stream, print
     * mode exits immediately with an authentication error when no Google session exists;
     * the settings button therefore cannot sit in a six-minute result wait.
     */
    public synchronized String testConnection(String model) throws IOException {
        close();
        String output = runBoundedCommand(commands.testCommand(text(model)),
                "connection test", CONNECTION_TEST_TIMEOUT);
        JsonObject result = lastJsonObject(output);
        if (result == null) {
            lastError = "Antigravity connection test returned invalid JSON";
            throw new IOException(lastError);
        }
        String status = string(result, "status").toUpperCase(Locale.ROOT);
        if (!"SUCCESS".equals(status)) {
            lastError = nonBlank(string(result, "error"),
                    "Antigravity connection test ended with status " + status);
            throw new IOException(lastError);
        }
        String response = string(result, "response").trim();
        if (response.isBlank()) {
            lastError = "Antigravity connection test returned an empty response";
            throw new IOException(lastError);
        }
        recordOneShotUsage(result);
        cachedAccountEmail = readLatestAccountEmail();
        authenticatedCached = true;
        lastError = "";
        return response;
    }

    /**
     * Open the official interactive account screen. Antigravity only supports {@code /logout}
     * inside its TUI, so the terminal remains visible for the user to confirm the account change.
     */
    public synchronized void openLogoutTerminal() throws IOException {
        close();
        if (!isInstalled()) throw new IOException(lastError);
        Files.createDirectories(workspace);
        new ProcessBuilder(commands.loginCommand()).directory(workspace.toFile()).start();
        clearAccountCache();
        lastError = "";
    }

    private void clearAccountCache() {
        cachedModels = List.of();
        cachedAccountEmail = "";
        authenticatedCached = false;
    }

    private String runBoundedCommand(List<String> commandLine, String action) throws IOException {
        return runBoundedCommand(commandLine, action, COMMAND_TIMEOUT);
    }

    private String runBoundedCommand(List<String> commandLine, String action, Duration timeout)
            throws IOException {
        Files.createDirectories(workspace);
        Process command;
        try {
            command = new ProcessBuilder(commandLine)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            installedCached = false;
            lastError = message(e, "Antigravity CLI was not found");
            throw new IOException(lastError, e);
        }

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> readCommandOutput(command, output),
                "nyanlex-antigravity-command");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!command.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                command.destroyForcibly();
                lastError = "Antigravity " + action + " timed out";
                throw new IOException(lastError);
            }
            reader.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            command.destroyForcibly();
            lastError = "Antigravity " + action + " was interrupted";
            throw new IOException(lastError, e);
        }

        installedCached = true;
        String commandOutput = output.toString().trim();
        if (command.exitValue() != 0) {
            JsonObject error = lastJsonObject(commandOutput);
            lastError = error == null
                    ? nonBlank(commandOutput, "Antigravity " + action + " failed")
                    : nonBlank(string(error, "error"),
                            "Antigravity " + action + " failed");
            throw new IOException(lastError);
        }
        return commandOutput;
    }

    /** Last complete JSON object in a command's mixed stdout/stderr capture. */
    private static JsonObject lastJsonObject(String output) {
        if (output == null || output.isBlank()) return null;
        String[] lines = output.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.startsWith("{") || !line.endsWith("}")) continue;
            try {
                JsonElement parsed = new JsonParser().parse(line);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (RuntimeException ignored) {
                // Diagnostics can contain braces; keep looking for the result envelope.
            }
        }
        return null;
    }

    private void recordOneShotUsage(JsonObject result) {
        JsonElement usageElement = result.get("usage");
        SessionTokenUsage totals = tokenUsage;
        if (totals == null || usageElement == null || !usageElement.isJsonObject()) return;
        String conversationId = string(result, "conversation_id");
        String source = "antigravity-test:"
                + (conversationId.isBlank() ? System.nanoTime() : conversationId);
        JsonObject usage = usageElement.getAsJsonObject();
        totals.recordCumulative(source,
                number(usage, "input_tokens"),
                number(usage, "cache_read_tokens"),
                number(usage, "output_tokens"),
                number(usage, "thinking_tokens"),
                number(usage, "total_tokens"));
        totals.finishCumulative(source);
    }

    private static void readCommandOutput(Process command, StringBuilder output) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                command.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                synchronized (output) {
                    if (!output.isEmpty()) output.append('\n');
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (remaining <= 0) continue;
                    output.append(line, 0, Math.min(line.length(), remaining));
                }
            }
        } catch (IOException ignored) {
            // The process exit code and any captured text produce the user-facing error.
        }
    }

    static List<ModelOption> parseModels(String output) {
        if (output == null || output.isBlank()) return List.of();
        Map<String, ModelOption> models = new LinkedHashMap<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
            line = line.replaceFirst("^[*+>•]\\s+", "");
            String[] columns = line.split("(?:\\t+| {2,})", 2);
            if (columns.length != 2) continue;
            String model = columns[0].trim();
            String displayName = columns[1].trim();
            if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/+\\-]*")
                    || model.equalsIgnoreCase("model")
                    || model.equalsIgnoreCase("model-id")
                    || displayName.isBlank()) {
                continue;
            }
            models.putIfAbsent(model, new ModelOption(model, displayName));
        }
        return List.copyOf(models.values());
    }

    static String parseAccountEmail(String logText) {
        if (logText == null || logText.isBlank()) return "";
        Matcher matcher = ACCOUNT_EMAIL_LINE.matcher(logText);
        String email = "";
        while (matcher.find()) email = matcher.group(1);
        return email;
    }

    private static String readLatestAccountEmail() {
        String userHome = System.getProperty("user.home", "").trim();
        if (userHome.isBlank()) return "";
        Path base;
        try {
            base = Path.of(userHome, ".gemini", "antigravity-cli");
        } catch (RuntimeException ignored) {
            return "";
        }

        List<Path> logs = new ArrayList<>();
        Path current = base.resolve("cli.log");
        if (Files.isRegularFile(current)) logs.add(current);
        Path logDirectory = base.resolve("log");
        if (Files.isDirectory(logDirectory)) {
            try (var paths = Files.list(logDirectory)) {
                paths.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().startsWith("cli-")
                                && path.getFileName().toString().endsWith(".log"))
                        .forEach(logs::add);
            } catch (IOException | RuntimeException ignored) {
                // The account label is optional; model discovery remains authoritative.
            }
        }
        logs.sort(Comparator.comparingLong(AntigravityCliClient::lastModified).reversed());
        for (int index = 0; index < Math.min(6, logs.size()); index++) {
            String email = readAccountEmail(logs.get(index));
            if (!email.isBlank()) return email;
        }
        return "";
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static String readAccountEmail(Path log) {
        try {
            long size = Files.size(log);
            int length = (int) Math.min(size, MAX_ACCOUNT_LOG_BYTES);
            ByteBuffer tail = ByteBuffer.allocate(length);
            try (SeekableByteChannel channel = Files.newByteChannel(log, StandardOpenOption.READ)) {
                channel.position(Math.max(0L, size - length));
                while (tail.hasRemaining() && channel.read(tail) >= 0) {
                    // Read the bounded tail completely.
                }
            }
            tail.flip();
            return parseAccountEmail(StandardCharsets.UTF_8.decode(tail).toString());
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    /** Start the persistent stream in the background so the first real request avoids startup cost. */
    public void warmUpAsync(String model) {
        Thread thread = new Thread(() -> {
            synchronized (AntigravityCliClient.this) {
                try {
                    ensureStarted(model, 0);
                } catch (IOException ignored) {
                    // The settings test or first translation reports the actionable error.
                }
            }
        }, "nyanlex-antigravity-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /** Submit one turn. Calls are serialized because one stream emits one ordered result sequence. */
    public synchronized String complete(String model, String system, String user)
            throws IOException {
        String prompt = buildPrompt(system, user);
        Generation active = ensureStarted(model, prompt.length());

        JsonObject message = new JsonObject();
        message.addProperty("content", prompt);
        JsonObject event = new JsonObject();
        event.addProperty("event", "user");
        event.add("message", message);
        try {
            active.writer.write(event.toString());
            active.writer.newLine();
            active.writer.flush();
        } catch (IOException e) {
            closeGeneration(active);
            throw new IOException("Could not send a prompt to Antigravity CLI", e);
        }

        long deadline = System.nanoTime() + resultTimeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                closeGeneration(active);
                lastError = "Antigravity CLI response timed out";
                throw new IOException(lastError);
            }
            StreamItem item;
            try {
                item = active.events.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeGeneration(active);
                throw new IOException("Antigravity CLI request was interrupted", e);
            }
            if (item == null) continue;
            if (item.error != null) {
                closeGeneration(active);
                lastError = message(item.error, "Antigravity CLI output could not be read");
                throw new IOException(lastError, item.error);
            }
            if (item.eof) {
                String detail = nonBlank(active.stderr(), "Antigravity CLI exited before returning a result");
                closeGeneration(active);
                lastError = detail;
                throw new IOException(detail);
            }

            JsonObject envelope;
            try {
                JsonElement parsed = new JsonParser().parse(item.line);
                if (!parsed.isJsonObject()) throw new IllegalStateException("event is not an object");
                envelope = parsed.getAsJsonObject();
            } catch (RuntimeException e) {
                closeGeneration(active);
                lastError = "Antigravity CLI returned invalid stream JSON";
                throw new IOException(lastError, e);
            }
            if (!"result".equals(string(envelope, "event"))) continue;
            JsonElement resultElement = envelope.get("result");
            if (resultElement == null || !resultElement.isJsonObject()) {
                closeGeneration(active);
                lastError = "Antigravity CLI result was missing its payload";
                throw new IOException(lastError);
            }
            JsonObject result = resultElement.getAsJsonObject();
            recordUsage(active, result);
            active.turns++;
            active.promptChars += prompt.length();
            String status = string(result, "status").toUpperCase(Locale.ROOT);
            if (!"SUCCESS".equals(status)) {
                String detail = nonBlank(string(result, "error"),
                        nonBlank(active.stderr(), "Antigravity CLI ended with status " + status));
                lastError = detail;
                if (!active.process.isAlive()) closeGeneration(active);
                throw new IOException(detail);
            }
            String response = string(result, "response");
            if (response.isBlank()) {
                lastError = "Antigravity CLI returned an empty response";
                throw new IOException(lastError);
            }
            lastError = "";
            return response;
        }
    }

    private Generation ensureStarted(String model, int nextPromptChars) throws IOException {
        String normalizedModel = text(model);
        Generation active = generation;
        boolean expired = active != null && (active.turns >= MAX_TURNS_PER_SESSION
                || active.promptChars + nextPromptChars > MAX_PROMPT_CHARS_PER_SESSION);
        if (active != null && active.process.isAlive() && !expired
                && active.model.equals(normalizedModel)) {
            return active;
        }
        if (active != null) closeGeneration(active);

        Files.createDirectories(workspace);
        Process process;
        try {
            process = new ProcessBuilder(commands.streamCommand(normalizedModel))
                    .directory(workspace.toFile())
                    .start();
        } catch (IOException e) {
            installedCached = false;
            lastError = message(e, "Antigravity CLI could not be started");
            throw new IOException(lastError, e);
        }
        Generation created = new Generation(process, normalizedModel);
        generation = created;
        installedCached = true;
        startReaders(created);
        return created;
    }

    private void startReaders(Generation active) {
        Thread stdout = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    active.process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > MAX_STREAM_LINE_CHARS) {
                        put(active, StreamItem.error(new IOException(
                                "Antigravity CLI stream line exceeded the safety limit")));
                        return;
                    }
                    put(active, StreamItem.line(line));
                }
                put(active, StreamItem.eof());
            } catch (IOException e) {
                put(active, StreamItem.error(e));
            }
        }, "nyanlex-antigravity-stdout");
        stdout.setDaemon(true);
        stdout.start();

        Thread stderr = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    active.process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) active.appendStderr(line);
            } catch (IOException ignored) {
                // stdout/exit state owns request failure; stderr is diagnostic only.
            }
        }, "nyanlex-antigravity-stderr");
        stderr.setDaemon(true);
        stderr.start();
    }

    private static void put(Generation generation, StreamItem item) {
        try {
            generation.events.put(item);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordUsage(Generation active, JsonObject result) {
        JsonElement usageElement = result.get("usage");
        if (usageElement == null || !usageElement.isJsonObject()) return;
        String conversationId = string(result, "conversation_id");
        if (conversationId.isBlank()) conversationId = "process-" + active.process.pid();
        String source = "antigravity:" + conversationId;
        if (active.tokenSource != null && !active.tokenSource.equals(source)) finishUsage(active);
        active.tokenSource = source;
        JsonObject usage = usageElement.getAsJsonObject();
        SessionTokenUsage totals = tokenUsage;
        if (totals != null) {
            totals.recordCumulative(source,
                    number(usage, "input_tokens"),
                    number(usage, "cache_read_tokens"),
                    number(usage, "output_tokens"),
                    number(usage, "thinking_tokens"),
                    number(usage, "total_tokens"));
        }
    }

    @Override
    public synchronized void close() {
        if (generation != null) closeGeneration(generation);
    }

    private void closeGeneration(Generation target) {
        if (target == null) return;
        if (generation == target) generation = null;
        finishUsage(target);
        try {
            target.writer.close();
        } catch (IOException ignored) {
        }
        try {
            if (!target.process.waitFor(2, TimeUnit.SECONDS)) target.process.destroy();
            if (target.process.isAlive() && !target.process.waitFor(1, TimeUnit.SECONDS)) {
                target.process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            target.process.destroyForcibly();
        }
    }

    private void finishUsage(Generation target) {
        SessionTokenUsage totals = tokenUsage;
        if (totals != null && target.tokenSource != null) totals.finishCumulative(target.tokenSource);
        target.tokenSource = null;
    }

    private static String buildPrompt(String system, String user) {
        StringBuilder prompt = new StringBuilder(TRANSLATION_BOUNDARY);
        if (system != null && !system.isBlank()) prompt.append("\n\n").append(system.trim());
        if (user != null && !user.isBlank()) prompt.append("\n\n").append(user.trim());
        return prompt.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static long number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return 0L;
        try {
            return Math.max(0L, value.getAsLong());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String message(Throwable error, String fallback) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    interface CommandFactory {
        List<String> probeCommand();

        List<String> modelsCommand();

        List<String> streamCommand(String model);

        List<String> loginCommand();

        List<String> testCommand(String model);

    }

    private static final class DefaultCommandFactory implements CommandFactory {
        @Override
        public List<String> probeCommand() {
            return List.of(resolveExecutable(), "--version");
        }

        @Override
        public List<String> modelsCommand() {
            return List.of(resolveExecutable(), "models");
        }

        @Override
        public List<String> streamCommand(String model) {
            List<String> command = new ArrayList<>();
            command.add(resolveExecutable());
            command.add("--input-format");
            command.add("stream-json");
            command.add("--output-format");
            command.add("stream-json");
            command.add("--print-timeout");
            command.add("5m");
            if (!model.isBlank()) {
                command.add("--model");
                command.add(model);
            }
            return command;
        }

        @Override
        public List<String> loginCommand() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String executable = resolveExecutable();
            if (os.contains("win")) {
                return List.of("cmd.exe", "/d", "/c", "start", "", executable);
            }
            if (os.contains("mac")) {
                return List.of("open", "-a", "Terminal", executable);
            }
            return List.of("x-terminal-emulator", "-e", executable);
        }

        @Override
        public List<String> testCommand(String model) {
            List<String> command = new ArrayList<>();
            command.add(resolveExecutable());
            command.add("-p");
            command.add("Do not use tools. Translate 'Hello, world' to Traditional Chinese. Return only the translation.");
            command.add("--output-format");
            command.add("json");
            command.add("--print-timeout");
            command.add("30s");
            if (!model.isBlank()) {
                command.add("--model");
                command.add(model);
            }
            return command;
        }

        private static String resolveExecutable() {
            String override = System.getenv("NYANLEX_ANTIGRAVITY_PATH");
            if (override != null && !override.isBlank()) return override.trim();
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                Path windowsDefault = Path.of(local, "agy", "bin", "agy.exe");
                if (Files.isRegularFile(windowsDefault)) return windowsDefault.toString();
            }
            Path windowsProgramFiles = Path.of("C:\\Program Files\\Google\\antigravity-cli\\agy.exe");
            if (Files.isRegularFile(windowsProgramFiles)) return windowsProgramFiles.toString();
            return "agy";
        }
    }

    public record ModelOption(String model, String displayName) {
        public ModelOption {
            model = text(model);
            displayName = text(displayName);
            if (model.isBlank()) throw new IllegalArgumentException("model must not be blank");
            if (displayName.isBlank()) displayName = model;
        }
    }

    private static final class Generation {
        final Process process;
        final BufferedWriter writer;
        final BlockingQueue<StreamItem> events = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
        final String model;
        final StringBuilder stderr = new StringBuilder();
        int turns;
        int promptChars;
        String tokenSource;

        Generation(Process process, String model) {
            this.process = process;
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            this.model = model;
        }

        synchronized void appendStderr(String line) {
            if (line == null || line.isBlank()) return;
            if (!stderr.isEmpty()) stderr.append('\n');
            stderr.append(line);
            if (stderr.length() > MAX_STDERR_CHARS) {
                stderr.delete(0, stderr.length() - MAX_STDERR_CHARS);
            }
        }

        synchronized String stderr() {
            return stderr.toString().trim();
        }
    }

    private static final class StreamItem {
        final String line;
        final IOException error;
        final boolean eof;

        private StreamItem(String line, IOException error, boolean eof) {
            this.line = line;
            this.error = error;
            this.eof = eof;
        }

        static StreamItem line(String line) {
            return new StreamItem(line, null, false);
        }

        static StreamItem error(IOException error) {
            return new StreamItem(null, error, false);
        }

        static StreamItem eof() {
            return new StreamItem(null, null, true);
        }
    }
}
