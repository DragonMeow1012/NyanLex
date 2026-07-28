package com.dragonmeow.mctranslator.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal client for the official {@code codex app-server} JSONL protocol.
 *
 * <p>The process receives a dedicated {@code CODEX_HOME}; consequently its OAuth
 * tokens, account logout and Codex configuration never touch the user's normal
 * Codex App/CLI data. Threads used for translation are ephemeral, read-only and
 * configured to never request command approval.</p>
 */
final class LegacyCodexClient implements AutoCloseable {

    private static final long REQUEST_TIMEOUT = 20_000L;
    private static final long TURN_TIMEOUT = 180_000L;
    private static final String CLIENT_VERSION = "1.0.3";
    private static final List<String> DISABLED_TRANSLATION_FEATURES = Collections.unmodifiableList(Arrays.asList(
            "apps",
            "auth_elicitation",
            "browser_use",
            "browser_use_external",
            "browser_use_full_cdp_access",
            "code_mode",
            "code_mode_host",
            "code_mode_only",
            "computer_use",
            "goals",
            "guardian_approval",
            "hooks",
            "image_generation",
            "in_app_browser",
            "memories",
            "mentions_v2",
            "multi_agent",
            "multi_agent_v2",
            "personality",
            "plugin_sharing",
            "plugins",
            "remote_compaction_v2",
            "remote_plugin",
            "shell_snapshot",
            "shell_tool",
            "skill_mcp_dependency_install",
            "skill_search",
            "tool_call_mcp_elicitation",
            "tool_suggest",
            "workspace_dependencies"));


    private final Path codexHome;
    private final Path workspace;
    private final Object lifecycleLock = new Object();
    private final Object executableLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> loginResults = new ConcurrentHashMap<>();
    private final Map<String, Boolean> completedLoginResults = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonObject>> turnResults = new ConcurrentHashMap<>();
    private final Map<String, String> turnMessages = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String lastError = "";
    private volatile String resolvedExecutable;
    private volatile AccountSnapshot cachedAccount = AccountSnapshot.signedOut();
    private volatile List<ModelOption> cachedModels = Collections.emptyList();
    private volatile LegacySessionTokenUsage tokenUsage;

    LegacyCodexClient(Path codexHome, Path workspace) {
        this.codexHome = Objects.requireNonNull(codexHome, "codexHome").toAbsolutePath().normalize();
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
    }

    public Path codexHome() {
        return codexHome;
    }

    public AccountSnapshot cachedAccount() {
        return cachedAccount;
    }

    public List<ModelOption> cachedModels() {
        return cachedModels;
    }
    public void setTokenUsage(LegacySessionTokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
    }


    public String lastError() {
        return lastError;
    }

    public boolean isSignedInCached() {
        return cachedAccount.signedIn();
    }

    /**
     * Probe the executable without starting app-server. The optional
     * MCTRANSLATOR_CODEX_PATH environment variable is useful for portable/manual
     * Codex installations; otherwise the normal {@code codex} command is used.
     */
    public boolean isInstalled() {
        String executable = resolveExecutable();
        if (executable == null || executable.trim().isEmpty()) {
            lastError = "Codex executable was not found";
            return false;
        }
        Process probe = null;
        try {
            probe = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean exited = probe.waitFor(5, TimeUnit.SECONDS);
            if (!exited) probe.destroyForcibly();
            boolean available = exited && probe.exitValue() == 0;
            if (!available) lastError = "Codex executable did not start successfully";
            else lastError = "";
            return available;
        } catch (IOException e) {
            lastError = e.getMessage() == null ? "Codex executable could not be started" : e.getMessage();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "Codex executable check was interrupted";
            return false;
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
        }
    }

    public AccountSnapshot readAccount(boolean refreshToken) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("refreshToken", refreshToken);
        JsonObject result = requestObject("account/read", params, REQUEST_TIMEOUT);
        JsonElement value = result.get("account");
        AccountSnapshot account = AccountSnapshot.signedOut();
        if (value != null && value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if ("chatgpt".equals(string(object, "type"))) {
                account = new AccountSnapshot(
                        true,
                        nullableString(object, "email"),
                        nullableString(object, "planType")
                );
            }
        }
        cachedAccount = account;
        if (!account.signedIn()) cachedModels = Collections.emptyList();
        return account;
    }

    public LoginStart startLogin() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("type", "chatgpt");
        params.addProperty("useHostedLoginSuccessPage", true);
        params.addProperty("appBrand", "chatgpt");
        JsonObject result = requestObject("account/login/start", params, REQUEST_TIMEOUT);
        String loginId = string(result, "loginId");
        String authUrl = string(result, "authUrl");
        if (loginId.trim().isEmpty() || authUrl.trim().isEmpty()) {
            throw new IOException("Codex login did not return a browser URL");
        }
        loginResults.putIfAbsent(loginId, new CompletableFuture<>());
        return new LoginStart(loginId, authUrl);
    }

    public boolean awaitLogin(String loginId, long timeoutMillis) throws IOException {
        Boolean already = completedLoginResults.remove(loginId);
        if (already != null) {
            loginResults.remove(loginId);
            if (already) readAccount(true);
            return already;
        }
        CompletableFuture<Boolean> future =
                loginResults.computeIfAbsent(loginId, ignored -> new CompletableFuture<>());
        try {
            boolean success = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (success) readAccount(true);
            return success;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Codex login interrupted", e);
        } catch (ExecutionException e) {
            throw io("Codex login failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Codex login timed out", e);
        } finally {
            loginResults.remove(loginId);
            completedLoginResults.remove(loginId);
        }
    }

    public void logout() throws IOException {
        requestObject("account/logout", new JsonObject(), REQUEST_TIMEOUT);
        cachedAccount = AccountSnapshot.signedOut();
        cachedModels = Collections.emptyList();
    }

    /** Fetch every visible catalog page and preserve the server's model/effort order. */
    public List<ModelOption> listModels() throws IOException {
        List<ModelOption> models = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            JsonObject params = new JsonObject();
            params.addProperty("includeHidden", false);
            params.addProperty("limit", 100);
            if (cursor != null) params.addProperty("cursor", cursor);
            JsonObject result = requestObject("model/list", params, REQUEST_TIMEOUT);
            JsonArray data = array(result, "data");
            for (JsonElement element : data) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String model = string(object, "model");
                if (model.trim().isEmpty()) model = string(object, "id");
                if (model.trim().isEmpty()) continue;
                List<String> efforts = new ArrayList<>();
                JsonArray supported = array(object, "supportedReasoningEfforts");
                for (JsonElement effortElement : supported) {
                    if (!effortElement.isJsonObject()) continue;
                    String effort = string(effortElement.getAsJsonObject(), "reasoningEffort");
                    if (!effort.trim().isEmpty()) efforts.add(effort);
                }
                String defaultEffort = string(object, "defaultReasoningEffort");
                if (efforts.isEmpty() && !defaultEffort.trim().isEmpty()) efforts.add(defaultEffort);
                List<String> serviceTiers = new ArrayList<>();
                for (JsonElement tierElement : array(object, "serviceTiers")) {
                    if (!tierElement.isJsonObject()) continue;
                    String id = string(tierElement.getAsJsonObject(), "id");
                    if (!id.trim().isEmpty()) serviceTiers.add(id);
                }
                models.add(new ModelOption(
                        model,
                        nonBlank(string(object, "displayName"), model),
                        immutableList(efforts),
                        immutableList(serviceTiers),
                        defaultEffort,
                        bool(object, "isDefault")
                ));
            }
            cursor = nullableString(result, "nextCursor");
            if (cursor == null || cursor.trim().isEmpty()) break;
        }
        cachedModels = immutableList(models);
        return cachedModels;
    }

    /**
     * Run one text-only Codex turn. The final output is constrained to a JSON object
     * so the OpenAI-compatible adapter can return exactly the translated anchor text.
     */
    public String complete(String model, String effort, String systemPrompt, String userPrompt)
            throws IOException {
        if (model == null || model.trim().isEmpty()) throw new IOException("No Codex model selected");
        String serviceTier = preferredServiceTier(model);

        JsonObject threadParams = new JsonObject();
        threadParams.addProperty("model", model);
        threadParams.addProperty("cwd", workspace.toString());
        threadParams.addProperty("approvalPolicy", "never");
        threadParams.addProperty("sandbox", "read-only");
        threadParams.addProperty("ephemeral", true);
        threadParams.addProperty("personality", "none");
        threadParams.addProperty("serviceName", "minecraft_translator");
        if (serviceTier != null) threadParams.addProperty("serviceTier", serviceTier);
        threadParams.addProperty("baseInstructions",
                nonBlank(systemPrompt, "Translate the supplied Minecraft text."));
        threadParams.addProperty("developerInstructions",
                "Act only as a text translation engine. Never call tools, inspect files, run commands, "
                        + "browse, edit, or ask questions. Return only the requested translation payload.");

        JsonObject threadResult = requestObject("thread/start", threadParams, REQUEST_TIMEOUT);
        JsonObject thread = object(threadResult, "thread");
        String threadId = string(thread, "id");
        if (threadId.trim().isEmpty()) throw new IOException("Codex did not create a translation thread");

        JsonObject turnParams = new JsonObject();
        turnParams.addProperty("threadId", threadId);
        JsonArray input = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", userPrompt == null ? "" : userPrompt);
        input.add(text);
        turnParams.add("input", input);
        turnParams.addProperty("model", model);
        if (effort != null && !effort.trim().isEmpty()) turnParams.addProperty("effort", effort);
        turnParams.addProperty("summary", "none");
        if (serviceTier != null) turnParams.addProperty("serviceTier", serviceTier);
        turnParams.add("outputSchema", translationOutputSchema());

        JsonObject turnResult = requestObject("turn/start", turnParams, REQUEST_TIMEOUT);
        JsonObject turn = object(turnResult, "turn");
        String turnId = string(turn, "id");
        if (turnId.trim().isEmpty()) throw new IOException("Codex did not start a translation turn");

        CompletableFuture<JsonObject> completed =
                turnResults.computeIfAbsent(turnId, ignored -> new CompletableFuture<>());
        try {
            JsonObject completedParams = completed.get(TURN_TIMEOUT, TimeUnit.MILLISECONDS);
            JsonObject completedTurn = object(completedParams, "turn");
            String status = string(completedTurn, "status");
            if (!status.trim().isEmpty() && !"completed".equalsIgnoreCase(status)) {
                throw new IOException("Codex turn ended with status: " + status);
            }
            String message = turnMessages.get(turnId);
            if (message == null || message.trim().isEmpty()) {
                throw new IOException("Codex returned no translation");
            }
            return extractTranslation(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Codex translation interrupted", e);
        } catch (ExecutionException e) {
            throw io("Codex translation failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Codex translation timed out", e);
        } finally {
            turnResults.remove(turnId);
            turnMessages.remove(turnId);
            JsonObject unsubscribe = new JsonObject();
            unsubscribe.addProperty("threadId", threadId);
            sendBestEffortRequest("thread/unsubscribe", unsubscribe);
        }
    }

    private static JsonObject translationOutputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject translation = new JsonObject();
        translation.addProperty("type", "string");
        properties.add("translation", translation);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("translation");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static String extractTranslation(String message) throws IOException {
        String value = message.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int firstBreak = value.indexOf('\n');
            value = firstBreak >= 0 ? value.substring(firstBreak + 1, value.length() - 3).trim() : value;
        }
        try {
            JsonElement parsed = new JsonParser().parse(value);
            if (parsed.isJsonObject()) {
                JsonElement translation = parsed.getAsJsonObject().get("translation");
                if (translation != null && translation.isJsonPrimitive()) {
                    return translation.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Older app-server/model combinations may return raw constrained text.
        }
        if (!value.trim().isEmpty()) return value;
        throw new IOException("Codex returned an empty translation payload");
    }

    private JsonObject requestObject(String method, JsonObject params, long timeoutMillis) throws IOException {
        ensureStarted();
        JsonElement result = requestOnRunning(method, params, timeoutMillis);
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    private JsonElement requestOnRunning(String method, JsonObject params, long timeoutMillis)
            throws IOException {
        String id = Long.toString(nextId.getAndIncrement());
        JsonObject request = new JsonObject();
        request.addProperty("method", method);
        request.addProperty("id", Long.parseLong(id));
        if (params != null) request.add("params", params);
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            send(request);
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(method + " interrupted", e);
        } catch (ExecutionException e) {
            throw io(method + " failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(method + " timed out", e);
        } finally {
            pending.remove(id);
        }
    }

    private void sendBestEffortRequest(String method, JsonObject params) {
        try {
            JsonObject request = new JsonObject();
            request.addProperty("method", method);
            request.addProperty("id", nextId.getAndIncrement());
            if (params != null) request.add("params", params);
            send(request);
        } catch (IOException ignored) {
            // Ephemeral threads are also released when app-server stops.
        }
    }

    private void ensureStarted() throws IOException {
        if (ready && process != null && process.isAlive()) return;
        synchronized (lifecycleLock) {
            if (ready && process != null && process.isAlive()) return;
            if (closed) throw new IOException("Codex app-server client is closed");
            stopProcess();
            Files.createDirectories(codexHome);
            Files.createDirectories(workspace);
            String executable = resolveExecutable();
            if (executable == null || executable.trim().isEmpty()) {
                throw new IOException("Codex executable was not found");
            }
            ProcessBuilder builder = new ProcessBuilder(minimalAppServerCommand(executable));
            builder.directory(workspace.toFile());
            builder.environment().put("CODEX_HOME", codexHome.toString());
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            startReader(process);
            startStderrReader(process);

            try {
                JsonObject initialize = new JsonObject();
                JsonObject clientInfo = new JsonObject();
                clientInfo.addProperty("name", "minecraft_translator");
                clientInfo.addProperty("title", "Minecraft Translator");
                clientInfo.addProperty("version", CLIENT_VERSION);
                initialize.add("clientInfo", clientInfo);
                requestOnRunning("initialize", initialize, REQUEST_TIMEOUT);
                JsonObject initialized = new JsonObject();
                initialized.addProperty("method", "initialized");
                send(initialized);
                ready = true;
                lastError = "";
            } catch (IOException e) {
                lastError = e.getMessage() == null ? "Unable to start Codex app-server" : e.getMessage();
                stopProcess();
                throw e;
            }
        }
    }

    private void startReader(Process runningProcess) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
                }
                failIfCurrent(runningProcess, new IOException("Codex app-server stopped"));
            } catch (IOException e) {
                failIfCurrent(runningProcess, e);
            }
        }, "mctranslator-codex-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private void startStderrReader(Process runningProcess) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    runningProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) lastError = line;
                }
            } catch (IOException ignored) {
                // Process exit closes this stream.
            }
        }, "mctranslator-codex-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleLine(String line) {
        final JsonObject message;
        try {
            JsonElement parsed = new JsonParser().parse(line);
            if (!parsed.isJsonObject()) return;
            message = parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }

        JsonElement idElement = message.get("id");
        if (idElement != null && (message.has("result") || message.has("error"))) {
            String id = idElement.getAsString();
            CompletableFuture<JsonElement> future = pending.get(id);
            if (future == null) return;
            if (message.has("error")) {
                JsonObject error = object(message, "error");
                future.completeExceptionally(new IOException(nonBlank(
                        nullableString(error, "message"), "Codex app-server error")));
            } else {
                future.complete(message.get("result"));
            }
            return;
        }

        String method = string(message, "method");
        JsonObject params = object(message, "params");
        if ("account/login/completed".equals(method)) {
            String loginId = nullableString(params, "loginId");
            boolean success = bool(params, "success");
            if (loginId != null) {
                completedLoginResults.put(loginId, success);
                loginResults.computeIfAbsent(loginId, ignored -> new CompletableFuture<Boolean>())
                        .complete(success);
            }
            String error = nullableString(params, "error");
            if (!success && error != null) lastError = error;
        } else if ("item/completed".equals(method)) {
            JsonObject item = object(params, "item");
            if ("agentMessage".equals(string(item, "type"))) {
                String turnId = string(params, "turnId");
                String itemText = nullableString(item, "text");
                if (!turnId.trim().isEmpty() && itemText != null) turnMessages.put(turnId, itemText);
            }
        } else if ("turn/completed".equals(method)) {
            JsonObject turn = object(params, "turn");
            String turnId = string(turn, "id");
            if (turnId.trim().isEmpty()) turnId = string(params, "turnId");
            if (!turnId.trim().isEmpty()) {
                turnResults.computeIfAbsent(turnId, ignored -> new CompletableFuture<JsonObject>())
                        .complete(params);
            }
        } else if ("thread/tokenUsage/updated".equals(method)) {
            recordTokenUsage(params);
        } else if (idElement != null) {
            rejectServerRequest(idElement, method);
        }    }

    private void recordTokenUsage(JsonObject params) {
        LegacySessionTokenUsage counter = tokenUsage;
        if (counter == null) return;
        String threadId = string(params, "threadId");
        JsonObject total = object(object(params, "tokenUsage"), "total");
        if (threadId.trim().isEmpty() || total.size() == 0) return;
        counter.recordCumulative(
                "codex:" + threadId,
                longValue(total, "inputTokens"),
                longValue(total, "cachedInputTokens"),
                longValue(total, "outputTokens"),
                longValue(total, "reasoningOutputTokens"),
                longValue(total, "totalTokens"));
    }

    private void rejectServerRequest(JsonElement id, String method) {
        JsonObject response = new JsonObject();
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", -32601);
        error.addProperty("message", "Minecraft Translator does not allow app-server request: " + method);
        response.add("error", error);
        try {
            send(response);
        } catch (IOException ignored) {
            // The process may already be exiting.
        }
    }

    private void send(JsonObject message) throws IOException {
        BufferedWriter current = writer;
        if (current == null) throw new IOException("Codex app-server is not running");
        synchronized (writeLock) {
            current.write(message.toString());
            current.newLine();
            current.flush();
        }
    }

    private void failIfCurrent(Process runningProcess, IOException error) {
        synchronized (lifecycleLock) {
            if (process != runningProcess) return;
            process = null;
            writer = null;
            failAll(error);
        }
    }

    private void failAll(IOException error) {
        ready = false;
        lastError = error.getMessage() == null ? "Codex app-server stopped" : error.getMessage();
        pending.values().forEach(future -> future.completeExceptionally(error));
        turnResults.values().forEach(future -> future.completeExceptionally(error));
        loginResults.values().forEach(future -> future.completeExceptionally(error));
    }

    private String resolveExecutable() {
        String override = System.getenv("MCTRANSLATOR_CODEX_PATH");
        if (override != null && !override.trim().isEmpty()) return override.trim();

        String cached = resolvedExecutable;
        if (cached != null && (!isWindows() || isAbsoluteFile(cached))) return cached;
        synchronized (executableLock) {
            cached = resolvedExecutable;
            if (cached != null && (!isWindows() || isAbsoluteFile(cached))) return cached;
            if (!isWindows()) {
                resolvedExecutable = "codex";
                return resolvedExecutable;
            }

            String found = findCodexAppUserExecutable();
            if (found == null) found = findWindowsExecutableOnPath();
            resolvedExecutable = found;
            return found;
        }
    }

    private static String findWindowsExecutableOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.trim().isEmpty()) return null;
        for (String rawEntry : path.split(java.util.regex.Pattern.quote(
                System.getProperty("path.separator", ";")))) {
            String entry = rawEntry == null ? "" : rawEntry.trim();
            if (entry.length() >= 2 && entry.startsWith("\"") && entry.endsWith("\"")) {
                entry = entry.substring(1, entry.length() - 1);
            }
            if (entry.trim().isEmpty()) continue;
            try {
                Path candidate = Paths.get(entry).resolve("codex.exe");
                if (Files.isRegularFile(candidate)) return candidate.toString();
            } catch (RuntimeException ignored) {
                // Ignore malformed PATH entries.
            }
        }
        return null;
    }

    /**
     * The Microsoft Store package's executable under Program Files/WindowsApps
     * cannot be launched by an unrelated desktop process such as Minecraft.
     * Codex App also maintains an executable copy under the current user's
     * LocalAppData; that is the supported launchable copy we can reuse without
     * downloading or installing anything.
     */
    private static String findCodexAppUserExecutable() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            Path binRoot = Paths.get(localAppData, "OpenAI", "Codex", "bin");
            if (Files.isDirectory(binRoot)) {
                try (java.util.stream.Stream<Path> candidates = Files.find(binRoot, 3,
                        (path, attributes) -> attributes.isRegularFile()
                                && path.getFileName().toString().equalsIgnoreCase("codex.exe"))) {
                    Path newest = candidates.max((left, right) -> Long.compare(
                            lastModifiedMillis(left), lastModifiedMillis(right))).orElse(null);
                    if (newest != null) return newest.toString();
                } catch (IOException | RuntimeException ignored) {
                    // Continue with a standalone CLI installation.
                }
            }
        }

        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.trim().isEmpty()) {
            try {
                Path standalone = Paths.get(userProfile, ".local", "bin", "codex.exe");
                if (Files.isRegularFile(standalone)) return standalone.toString();
            } catch (RuntimeException ignored) {
                // Continue with PATH discovery.
            }
        }
        return null;
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean isAbsoluteFile(String value) {
        try {
            Path path = Paths.get(value);
            return path.isAbsolute() && Files.isRegularFile(path);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private String preferredServiceTier(String model) {
        for (ModelOption option : cachedModels) {
            if (option.model().equals(model) && option.serviceTiers().contains("priority")) {
                return "priority";
            }
        }
        return null;
    }

    private static List<String> minimalAppServerCommand(String executable) {
        Objects.requireNonNull(executable, "executable");
        List<String> command = new ArrayList<>(4 + DISABLED_TRANSLATION_FEATURES.size() * 2);
        command.add(executable);
        command.add("-c");
        command.add("web_search=\"disabled\"");
        for (String feature : DISABLED_TRANSLATION_FEATURES) {
            command.add("-c");
            command.add("features." + feature + "=false");
        }
        command.add("app-server");
        return immutableList(command);
    }

    private void stopProcess() {
        ready = false;
        BufferedWriter oldWriter = writer;
        writer = null;
        if (oldWriter != null) {
            try {
                oldWriter.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
        Process oldProcess = process;
        process = null;
        if (oldProcess != null && oldProcess.isAlive()) {
            oldProcess.destroy();
            try {
                if (!oldProcess.waitFor(2, TimeUnit.SECONDS)) oldProcess.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                oldProcess.destroyForcibly();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            stopProcess();
            failAll(new IOException("Codex app-server client closed"));
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        String value = nullableString(object, key);
        return value == null ? "" : value;
    }

    private static String nullableString(JsonObject object, String key) {
        if (object == null) return null;
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        if (object == null) return false;
        JsonElement value = object.get(key);
        try {
            return value != null && !value.isJsonNull() && value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long longValue(JsonObject object, String key) {
        if (object == null) return 0L;
        JsonElement value = object.get(key);
        try {
            return value != null && !value.isJsonNull()
                    ? Math.max(0L, value.getAsLong())
                    : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static IOException io(String prefix, Throwable cause) {
        if (cause instanceof IOException) return (IOException) cause;
        String message = cause == null || cause.getMessage() == null
                ? prefix : prefix + ": " + cause.getMessage();
        return new IOException(message, cause);
    }

    static final class AccountSnapshot {
        private final boolean signedIn;
        private final String email;
        private final String planType;
        AccountSnapshot(boolean signedIn, String email, String planType) {
            this.signedIn = signedIn;
            this.email = email;
            this.planType = planType;
        }
        boolean signedIn() { return signedIn; }
        String email() { return email; }
        String planType() { return planType; }
        static AccountSnapshot signedOut() { return new AccountSnapshot(false, null, null); }
    }

    static final class LoginStart {
        private final String loginId;
        private final String authUrl;
        LoginStart(String loginId, String authUrl) { this.loginId = loginId; this.authUrl = authUrl; }
        String loginId() { return loginId; }
        String authUrl() { return authUrl; }
    }

    static final class ModelOption {
        private final String model;
        private final String displayName;
        private final List<String> reasoningEfforts;
        private final List<String> serviceTiers;
        private final String defaultReasoningEffort;
        private final boolean defaultModel;
        ModelOption(String model, String displayName, List<String> reasoningEfforts,
                    List<String> serviceTiers, String defaultReasoningEffort, boolean defaultModel) {
            this.model = model;
            this.displayName = displayName;
            this.reasoningEfforts = reasoningEfforts;
            this.serviceTiers = serviceTiers;
            this.defaultReasoningEffort = defaultReasoningEffort;
            this.defaultModel = defaultModel;
        }
        String model() { return model; }
        String displayName() { return displayName; }
        List<String> reasoningEfforts() { return reasoningEfforts; }
        List<String> serviceTiers() { return serviceTiers; }
        String defaultReasoningEffort() { return defaultReasoningEffort; }
        boolean isDefault() { return defaultModel; }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
