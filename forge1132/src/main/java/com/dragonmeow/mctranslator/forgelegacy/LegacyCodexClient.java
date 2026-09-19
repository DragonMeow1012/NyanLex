package com.dragonmeow.mctranslator.forgelegacy;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final long COMPLETED_MESSAGE_GRACE_MILLIS = 500L;
    private static final int MAX_JSONL_LINE_CHARS = 2_000_000;
    private static final int MAX_TURN_MESSAGE_CHARS = 2_000_000;
    private static final int MAX_STDERR_LINE_CHARS = 16_384;
    private static final int MAX_IDENTIFIER_CHARS = 4_096;
    private static final int MAX_PENDING_REQUESTS = 512;
    private static final int MAX_LOGIN_RESULTS = 128;
    private static final int MAX_COMPLETED_LOGIN_RESULTS = 512;
    private static final int MAX_ACTIVE_TURNS = 512;
    private static final int MAX_ACTIVE_THREADS = 512;
    private static final String CLIENT_VERSION = "1.0.5";
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
    private final Object requestStateLock = new Object();
    private final Object loginStateLock = new Object();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, CompletableFuture<JsonElement>> pending =
            new LinkedHashMap<String, CompletableFuture<JsonElement>>();
    private final Map<String, CompletableFuture<Boolean>> loginResults =
            new LinkedHashMap<String, CompletableFuture<Boolean>>();
    private final LinkedHashMap<String, Boolean> completedLoginResults =
            new LinkedHashMap<String, Boolean>();
    private static final int MAX_EARLY_TURNS = 512;
    private static final int MAX_RECENTLY_CLOSED_TURNS = 512;
    private static final int MAX_RECENTLY_CLOSED_THREADS = 512;
    private final Object notificationStateLock = new Object();
    private final Map<String, ActiveTurnState> turnResults =
            new HashMap<String, ActiveTurnState>();
    private final LinkedHashMap<String, EarlyTurnState> earlyTurns =
            new LinkedHashMap<String, EarlyTurnState>();
    private final LinkedHashSet<String> recentlyClosedTurns = new LinkedHashSet<String>();
    private final Set<String> activeThreads = new HashSet<String>();
    private final LinkedHashSet<String> recentlyClosedThreads = new LinkedHashSet<String>();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String lastError = "";
    /**
     * Reader-owned diagnostics are one immutable generation-tagged value.  Keeping the
     * pair atomic lets stderr/login readers continue draining while ensureStarted waits
     * for initialize under lifecycleLock, without allowing an old process to publish an
     * error as if it belonged to its replacement.
     */
    private final AtomicReference<ProcessError> processError =
            new AtomicReference<ProcessError>();
    /** The initialize waiter is owned by lifecycleLock, but reader failure must wake it
     * without taking that lock or the only stdout/stderr drain can deadlock behind it. */
    private final AtomicReference<InitializationState> initializationState =
            new AtomicReference<InitializationState>();
    private volatile String resolvedExecutable;
    private volatile AccountSnapshot cachedAccount = AccountSnapshot.signedOut();
    private volatile List<ModelOption> cachedModels = Collections.emptyList();
    private volatile LegacySessionTokenUsage tokenUsage;
    private volatile Runnable requestRegistrationHookForTests;
    private volatile Runnable loginRegistrationHookForTests;
    private volatile Runnable loginAwaitWaitHookForTests;
    private volatile Runnable loginAwaitCleanupHookForTests;
    private volatile Runnable processErrorHookForTests;

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
        synchronized (notificationStateLock) {
            LegacySessionTokenUsage previous = this.tokenUsage;
            if (previous != null && previous != tokenUsage) {
                for (String id : activeThreads) previous.finishCumulative(tokenSource(id));
                for (String id : recentlyClosedThreads) previous.finishCumulative(tokenSource(id));
            }
            this.tokenUsage = tokenUsage;
        }
    }

    void setRequestRegistrationHookForTests(Runnable hook) {
        requestRegistrationHookForTests = hook;
    }

    void setLoginRegistrationHookForTests(Runnable hook) {
        loginRegistrationHookForTests = hook;
    }

    void setLoginAwaitCleanupHookForTests(Runnable hook) {
        loginAwaitCleanupHookForTests = hook;
    }

    void setLoginAwaitWaitHookForTests(Runnable hook) {
        loginAwaitWaitHookForTests = hook;
    }

    void setProcessErrorHookForTests(Runnable hook) {
        processErrorHookForTests = hook;
    }


    public String lastError() {
        while (true) {
            Process before = process;
            ProcessError tagged = processError.get();
            Process after = process;
            if (before != after) continue;
            if (tagged != null && tagged.generation == after) return tagged.message;
            return lastError;
        }
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
        Process loginProcess = captureRunningProcess();
        JsonObject result = requestObjectOnProcess(
                loginProcess, "account/login/start", params, REQUEST_TIMEOUT);
        String loginId = string(result, "loginId");
        String authUrl = string(result, "authUrl");
        if (loginId.trim().isEmpty() || authUrl.trim().isEmpty()) {
            throw new IOException("Codex login did not return a browser URL");
        }
        if (!validIdentifier(loginId)) throw new IOException("Codex returned an invalid login id");
        registerLogin(loginProcess, loginId);
        return new LoginStart(loginId, authUrl);
    }

    public boolean awaitLogin(String loginId, long timeoutMillis) throws IOException {
        if (!validIdentifier(loginId)) throw new IOException("Invalid Codex login id");
        Boolean already;
        CompletableFuture<Boolean> future;
        synchronized (lifecycleLock) {
            synchronized (loginStateLock) {
                already = completedLoginResults.remove(loginId);
                if (already == null) {
                    future = loginResults.get(loginId);
                    if (future == null) {
                        throw new IOException("Unknown or expired Codex login id");
                    }
                } else {
                    future = null;
                    loginResults.remove(loginId);
                }
            }
        }
        if (already != null) {
            if (already) readAccount(true);
            return already;
        }
        Runnable waitHook = loginAwaitWaitHookForTests;
        if (waitHook != null) waitHook.run();
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
            Runnable hook = loginAwaitCleanupHookForTests;
            if (hook != null) hook.run();
            synchronized (loginStateLock) {
                if (loginResults.get(loginId) == future) {
                    loginResults.remove(loginId);
                    completedLoginResults.remove(loginId);
                }
            }
        }
    }

    private void registerLogin(String loginId) throws IOException {
        registerLogin(null, loginId);
    }

    private void registerLogin(Process expectedProcess, String loginId) throws IOException {
        synchronized (lifecycleLock) {
            requireRunningLocked();
            if (expectedProcess != null && process != expectedProcess) {
                throw new IOException("Codex app-server process changed before login registration");
            }
            Runnable hook = loginRegistrationHookForTests;
            if (hook != null) hook.run();
            synchronized (loginStateLock) {
                if (completedLoginResults.containsKey(loginId)
                        || loginResults.containsKey(loginId)) return;
                if (loginResults.size() >= MAX_LOGIN_RESULTS) {
                    throw new IOException("Too many pending Codex logins");
                }
                loginResults.put(loginId, new CompletableFuture<Boolean>());
            }
        }
    }

    private void requireRunningLocked() throws IOException {
        Process running = process;
        if (!ready || running == null || !running.isAlive()) {
            throw new IOException(nonBlank(lastError(), "Codex app-server is not running"));
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

        Process threadProcess = captureRunningProcess();
        JsonObject threadResult = requestObjectOnProcess(
                threadProcess, "thread/start", threadParams, REQUEST_TIMEOUT);
        JsonObject thread = object(threadResult, "thread");
        String threadId = string(thread, "id");
        if (!validIdentifier(threadId)) throw new IOException("Codex returned an invalid thread id");
        String turnId = "";
        boolean trackedThread = false;
        try {
            openThread(threadProcess, threadId);
            trackedThread = true;
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

            JsonObject turnResult = requestObjectOnProcess(
                    threadProcess, "turn/start", turnParams, REQUEST_TIMEOUT);
            JsonObject turn = object(turnResult, "turn");
            turnId = string(turn, "id");
            if (!validIdentifier(turnId)) throw new IOException("Codex returned an invalid turn id");

            CompletableFuture<JsonObject> completed = registerTurn(threadProcess, turnId);
            JsonObject completedParams = completed.get(TURN_TIMEOUT, TimeUnit.MILLISECONDS);
            JsonObject completedTurn = object(completedParams, "turn");
            String status = string(completedTurn, "status");
            if (!status.trim().isEmpty() && !"completed".equalsIgnoreCase(status)) {
                throw new IOException("Codex turn ended with status: " + status);
            }
            String message = awaitTurnMessage(
                    threadProcess, turnId, COMPLETED_MESSAGE_GRACE_MILLIS);
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
            closeTurn(threadProcess, turnId);
            if (trackedThread) closeThread(threadProcess, threadId);
            JsonObject unsubscribe = new JsonObject();
            unsubscribe.addProperty("threadId", threadId);
            sendBestEffortRequest(threadProcess, "thread/unsubscribe", unsubscribe);
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

    private Process captureRunningProcess() throws IOException {
        ensureStarted();
        synchronized (lifecycleLock) {
            requireRunningLocked();
            if (writer == null) {
                throw new IOException(nonBlank(lastError(), "Codex app-server is not running"));
            }
            return process;
        }
    }

    private JsonObject requestObjectOnProcess(Process expectedProcess, String method,
                                              JsonObject params, long timeoutMillis)
            throws IOException {
        JsonElement result = requestOnRunning(
                expectedProcess, method, params, timeoutMillis);
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    private JsonElement requestOnRunning(String method, JsonObject params, long timeoutMillis)
            throws IOException {
        return requestOnRunning(null, method, params, timeoutMillis);
    }

    private JsonElement requestOnRunning(Process expectedProcess, String method,
                                         JsonObject params, long timeoutMillis)
            throws IOException {
        String id = Long.toString(nextId.getAndIncrement());
        JsonObject request = new JsonObject();
        request.addProperty("method", method);
        request.addProperty("id", Long.parseLong(id));
        if (params != null) request.add("params", params);
        String encodedRequest = encodeMessage(request);
        CompletableFuture<JsonElement> future = new CompletableFuture<JsonElement>();
        boolean registered = false;
        try {
            synchronized (lifecycleLock) {
                Process running = process;
                BufferedWriter runningWriter = writer;
                if (running == null || runningWriter == null || !running.isAlive()
                        || expectedProcess != null && running != expectedProcess
                        || !ready && !"initialize".equals(method)) {
                    throw new IOException(nonBlank(lastError(), "Codex app-server is not running"));
                }
                /* lifecycleLock -> requestStateLock is also the failAll lock order. */
                synchronized (requestStateLock) {
                    if (pending.size() >= MAX_PENDING_REQUESTS) {
                        throw new IOException("Too many pending Codex requests");
                    }
                    pending.put(id, future);
                    registered = true;
                }
                Runnable hook = requestRegistrationHookForTests;
                if (hook != null) hook.run();
                /*
                 * Registration and the corresponding write are one process-generation
                 * transaction.  A reader failure cannot clear this future and restart
                 * app-server between the two operations, causing the old request to be
                 * written to a new process with no matching future.
                 */
                try {
                    sendEncodedToWriter(runningWriter, encodedRequest);
                } catch (IOException writeFailure) {
                    if (process == running && writer == runningWriter) {
                        stopProcess();
                        failAll(writeFailure);
                    }
                    throw writeFailure;
                }
            }
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(method + " interrupted", e);
        } catch (ExecutionException e) {
            throw io(method + " failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(method + " timed out", e);
        } finally {
            if (registered) {
                synchronized (requestStateLock) {
                    if (pending.get(id) == future) pending.remove(id);
                }
            }
        }
    }

    private void sendBestEffortRequest(Process expectedProcess, String method, JsonObject params) {
        try {
            JsonObject request = new JsonObject();
            request.addProperty("method", method);
            request.addProperty("id", nextId.getAndIncrement());
            if (params != null) request.add("params", params);
            sendToProcess(expectedProcess, request);
        } catch (IOException ignored) {
            // Ephemeral threads are also released when app-server stops.
        }
    }

    private void ensureStarted() throws IOException {
        if (ready && process != null && process.isAlive()) return;
        synchronized (lifecycleLock) {
            if (ready && process != null && process.isAlive()) return;
            if (closed) throw new IOException("Codex app-server client is closed");
            retireGenerationBeforeStart();
            Files.createDirectories(codexHome);
            Files.createDirectories(workspace);
            String executable = resolveExecutable();
            if (executable == null || executable.trim().isEmpty()) {
                throw new IOException("Codex executable was not found");
            }
            ProcessBuilder builder = new ProcessBuilder(minimalAppServerCommand(executable));
            builder.directory(workspace.toFile());
            builder.environment().put("CODEX_HOME", codexHome.toString());
            Process startingProcess = builder.start();
            process = startingProcess;
            writer = new BufferedWriter(new OutputStreamWriter(
                    startingProcess.getOutputStream(), StandardCharsets.UTF_8));
            beginInitialization(startingProcess);
            startReader(startingProcess, writer);
            startStderrReader(startingProcess);

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
                finishInitialization(startingProcess);
                ready = true;
                lastError = "";
                processError.set(null);
            } catch (IOException e) {
                failInitialization(e);
                throw e;
            }
        }
    }

    private void failInitialization(IOException failure) {
        synchronized (lifecycleLock) {
            String failureMessage = failure.getMessage() == null
                    ? "Unable to start Codex app-server" : failure.getMessage();
            stopProcess();
            failAll(new IOException(failureMessage, failure));
        }
    }

    private void retireGenerationBeforeStart() {
        synchronized (lifecycleLock) {
            boolean replacingGeneration = process != null || writer != null || ready
                    || hasProtocolState();
            String replacedError = lastError();
            stopProcess();
            if (replacingGeneration) {
                failAll(new IOException(nonBlank(
                        replacedError, "Codex app-server generation was replaced")));
            }
        }
    }

    private boolean hasProtocolState() {
        synchronized (notificationStateLock) {
            if (!turnResults.isEmpty() || !earlyTurns.isEmpty() || !activeThreads.isEmpty()) {
                return true;
            }
        }
        synchronized (requestStateLock) {
            if (!pending.isEmpty()) return true;
        }
        synchronized (loginStateLock) {
            return !loginResults.isEmpty() || !completedLoginResults.isEmpty();
        }
    }

    private void startReader(Process runningProcess, BufferedWriter runningWriter) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(reader, MAX_JSONL_LINE_CHARS)) != null) {
                    handleLine(runningProcess, runningWriter, line);
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
                while ((line = readBoundedLine(reader, MAX_STDERR_LINE_CHARS)) != null) {
                    recordStderr(runningProcess, line);
                }
            } catch (IOException failure) {
                if (runningProcess.isAlive()) failIfCurrent(runningProcess, failure);
            }
        }, "mctranslator-codex-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void recordStderr(Process runningProcess, String line) {
        if (line == null || line.trim().isEmpty()) return;
        recordProcessError(runningProcess, line);
    }

    private void recordProcessError(Process sourceProcess, String message) {
        if (message == null) return;
        if (sourceProcess == null) {
            // Reflection-only/direct parser tests have no child generation. Preserve
            // their historical observable error channel without retaining a stale tag.
            lastError = message;
            processError.set(null);
            return;
        }
        while (process == sourceProcess) {
            ProcessError observed = processError.get();
            // Read the tag while this generation is current, then verify ownership
            // again before CAS.  Without the second check an old reader can pause
            // after the loop condition, observe a replacement generation's tag, and
            // successfully swap that current diagnostic back to an old-generation
            // value.  The hook deliberately sits in this exact test-only race window.
            Runnable hook = processErrorHookForTests;
            if (hook != null) hook.run();
            if (process != sourceProcess) return;
            ProcessError replacement = new ProcessError(sourceProcess, message);
            if (processError.compareAndSet(observed, replacement)) return;
        }
    }

    private void finishInitialization(Process expectedProcess) throws IOException {
        while (true) {
            InitializationState state = initializationState.get();
            if (state == null || state.generation != expectedProcess) {
                throw new IOException("Codex app-server initialization generation changed");
            }
            if (state.failure != null) throw state.failure;
            if (initializationState.compareAndSet(state, null)) return;
        }
    }

    private void beginInitialization(Process expectedProcess) {
        initializationState.set(new InitializationState(expectedProcess, null));
    }

    /**
     * Claim an initializing generation and wake its request future without lifecycleLock.
     * At this point ensureStarted owns that monitor while blocked in Future.get(), so a
     * normal failIfCurrent teardown would be unable to run until the 20-second timeout.
     */
    private boolean signalInitializationFailure(Process expectedProcess, IOException failure) {
        while (true) {
            InitializationState state = initializationState.get();
            if (state == null || state.generation != expectedProcess) return false;
            if (state.failure != null) return true;
            if (process != expectedProcess) return false;
            InitializationState failed = new InitializationState(expectedProcess, failure);
            if (!initializationState.compareAndSet(state, failed)) continue;

            List<CompletableFuture<JsonElement>> requests;
            synchronized (requestStateLock) {
                requests = new ArrayList<CompletableFuture<JsonElement>>(pending.values());
            }
            if (expectedProcess.isAlive()) expectedProcess.destroy();
            for (CompletableFuture<JsonElement> request : requests) {
                request.completeExceptionally(failure);
            }
            return true;
        }
    }

    /** Test-only entry point for state-machine tests without a child process. */
    private void handleLine(String line) {
        handleLine(null, null, line);
    }

    private void handleLine(Process sourceProcess, String line) {
        BufferedWriter sourceWriter = sourceProcess != null && process == sourceProcess
                ? writer : null;
        handleLine(sourceProcess, sourceWriter, line);
    }

    private void handleLine(Process sourceProcess, BufferedWriter sourceWriter, String line) {
        if (sourceProcess != null && process != sourceProcess) return;
        if (line == null || line.length() > MAX_JSONL_LINE_CHARS) {
            recordProcessError(sourceProcess, "Codex JSONL line too large");
            return;
        }
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
            if (!idElement.isJsonPrimitive()) return;
            String id = idElement.getAsString();
            if (!validIdentifier(id)) return;
            CompletableFuture<JsonElement> future;
            synchronized (requestStateLock) {
                if (sourceProcess != null && process != sourceProcess) return;
                future = pending.remove(id);
            }
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
            if (validIdentifier(loginId)) {
                CompletableFuture<Boolean> future;
                synchronized (loginStateLock) {
                    if (sourceProcess != null && process != sourceProcess) return;
                    putCompletedLogin(loginId, Boolean.valueOf(success));
                    future = loginResults.get(loginId);
                }
                if (future != null) future.complete(Boolean.valueOf(success));
            }
            String error = nullableString(params, "error");
            if (!success && error != null) recordProcessError(sourceProcess, error);
        } else if ("item/completed".equals(method)) {
            JsonObject item = object(params, "item");
            if ("agentMessage".equals(string(item, "type"))) {
                String turnId = string(params, "turnId");
                String itemText = nullableString(item, "text");
                if (validIdentifier(turnId) && itemText != null) {
                    recordTurnMessage(sourceProcess, turnId, itemText);
                }
            }
        } else if ("turn/completed".equals(method)) {
            JsonObject turn = object(params, "turn");
            String turnId = string(turn, "id");
            if (turnId.trim().isEmpty()) turnId = string(params, "turnId");
            if (validIdentifier(turnId)) completeTurn(sourceProcess, turnId, params);
        } else if ("thread/tokenUsage/updated".equals(method)) {
            recordTokenUsage(sourceProcess, params);
        } else if (idElement != null) {
            rejectServerRequest(sourceProcess, sourceWriter, idElement, method);
        }    }

    private void recordTurnMessage(String turnId, String message) {
        recordTurnMessage(null, turnId, message);
    }

    private void recordTurnMessage(Process sourceProcess, String turnId, String message) {
        if (!validIdentifier(turnId) || message == null) return;
        synchronized (notificationStateLock) {
            if (sourceProcess != null && process != sourceProcess) return;
            if (recentlyClosedTurns.contains(turnId)) return;
            ActiveTurnState active = turnResults.get(turnId);
            if (active != null) {
                if (active.message.isDone()) return;
                if (message.length() > MAX_TURN_MESSAGE_CHARS) {
                    active.message.completeExceptionally(
                            new IOException("Codex turn message too large"));
                } else active.message.complete(message);
                return;
            }
            EarlyTurnState early = earlyTurns.remove(turnId);
            if (early != null && (early.message != null || early.messageFailure != null)) {
                earlyTurns.put(turnId, early);
                return;
            }
            earlyTurns.put(turnId, new EarlyTurnState(
                    message.length() > MAX_TURN_MESSAGE_CHARS ? null : message,
                    early == null ? null : early.completedParams,
                    message.length() > MAX_TURN_MESSAGE_CHARS
                            ? new IOException("Codex turn message too large") : null));
            trimEarlyTurns();
        }
    }

    private void completeTurn(String turnId, JsonObject params) {
        completeTurn(null, turnId, params);
    }

    private void completeTurn(Process sourceProcess, String turnId, JsonObject params) {
        if (!validIdentifier(turnId)) return;
        synchronized (notificationStateLock) {
            if (sourceProcess != null && process != sourceProcess) return;
            if (recentlyClosedTurns.contains(turnId)) return;
            ActiveTurnState active = turnResults.get(turnId);
            if (active != null) {
                active.completed.complete(params);
                return;
            }
            EarlyTurnState early = earlyTurns.remove(turnId);
            earlyTurns.put(turnId, new EarlyTurnState(
                    early == null ? null : early.message, params,
                    early == null ? null : early.messageFailure));
            trimEarlyTurns();
        }
    }

    private CompletableFuture<JsonObject> registerTurn(String turnId) throws IOException {
        return registerTurn(null, turnId);
    }

    private CompletableFuture<JsonObject> registerTurn(Process expectedProcess, String turnId)
            throws IOException {
        if (!validIdentifier(turnId)) throw new IOException("Invalid Codex turn id");
        synchronized (lifecycleLock) {
            if (expectedProcess != null) requireRunningLocked();
            if (!ready || expectedProcess != null && process != expectedProcess) {
                throw new IOException(nonBlank(lastError(),
                        "Codex app-server stopped before turn registration"));
            }
            synchronized (notificationStateLock) {
                recentlyClosedTurns.remove(turnId);
                ActiveTurnState existing = turnResults.get(turnId);
                if (existing != null) return existing.completed;
                if (turnResults.size() >= MAX_ACTIVE_TURNS) {
                    throw new IOException("Too many active Codex turns");
                }
                ActiveTurnState registered = new ActiveTurnState();
                turnResults.put(turnId, registered);
                EarlyTurnState early = earlyTurns.remove(turnId);
                if (early != null) {
                    if (early.messageFailure != null) {
                        registered.message.completeExceptionally(early.messageFailure);
                    } else if (early.message != null) {
                        registered.message.complete(early.message);
                    }
                    if (early.completedParams != null) {
                        registered.completed.complete(early.completedParams);
                    }
                }
                return registered.completed;
            }
        }
    }

    private String awaitTurnMessage(String turnId, long timeoutMillis)
            throws InterruptedException, ExecutionException {
        return awaitTurnMessage(null, turnId, timeoutMillis);
    }

    private String awaitTurnMessage(Process expectedProcess, String turnId, long timeoutMillis)
            throws InterruptedException, ExecutionException {
        ActiveTurnState state;
        synchronized (lifecycleLock) {
            if (expectedProcess != null && process != expectedProcess) return null;
            synchronized (notificationStateLock) { state = turnResults.get(turnId); }
        }
        if (state == null) return null;
        try {
            return state.message.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException noMessage) {
            return null;
        }
    }

    private void closeTurn(String turnId) {
        closeTurn(null, turnId);
    }

    private void closeTurn(Process expectedProcess, String turnId) {
        if (turnId == null || turnId.trim().isEmpty()) return;
        synchronized (lifecycleLock) {
            if (expectedProcess != null && process != expectedProcess) return;
            synchronized (notificationStateLock) {
                turnResults.remove(turnId);
                earlyTurns.remove(turnId);
                rememberClosed(recentlyClosedTurns, turnId, MAX_RECENTLY_CLOSED_TURNS);
            }
        }
    }

    private void trimEarlyTurns() {
        while (earlyTurns.size() > MAX_EARLY_TURNS) {
            java.util.Iterator<Map.Entry<String, EarlyTurnState>> oldest =
                    earlyTurns.entrySet().iterator();
            if (!oldest.hasNext()) return;
            oldest.next();
            oldest.remove();
        }
    }

    private void openThread(String threadId) throws IOException {
        openThread(null, threadId);
    }

    private void openThread(Process expectedProcess, String threadId) throws IOException {
        if (!validIdentifier(threadId)) throw new IOException("Invalid Codex thread id");
        synchronized (lifecycleLock) {
            if (expectedProcess != null) requireRunningLocked();
            if (expectedProcess != null && (!ready || process != expectedProcess)) {
                throw new IOException("Codex app-server process changed before thread registration");
            }
            synchronized (notificationStateLock) {
                boolean reused = activeThreads.remove(threadId);
                reused |= recentlyClosedThreads.remove(threadId);
                if (reused) {
                    LegacySessionTokenUsage counter = tokenUsage;
                    if (counter != null) counter.finishCumulative(tokenSource(threadId));
                }
                if (activeThreads.size() >= MAX_ACTIVE_THREADS) {
                    throw new IOException("Too many active Codex threads");
                }
                activeThreads.add(threadId);
            }
        }
    }

    private void closeThread(String threadId) {
        closeThread(null, threadId);
    }

    private void closeThread(Process expectedProcess, String threadId) {
        if (threadId == null || threadId.trim().isEmpty()) return;
        synchronized (lifecycleLock) {
            if (expectedProcess != null && process != expectedProcess) return;
            synchronized (notificationStateLock) {
                activeThreads.remove(threadId);
                String evicted = rememberClosed(
                        recentlyClosedThreads, threadId, MAX_RECENTLY_CLOSED_THREADS);
                LegacySessionTokenUsage counter = tokenUsage;
                if (counter != null && evicted != null) {
                    counter.finishCumulative(tokenSource(evicted));
                }
            }
        }
    }

    private void releaseThreadBaselines() {
        synchronized (notificationStateLock) {
            LegacySessionTokenUsage counter = tokenUsage;
            if (counter != null) {
                for (String id : activeThreads) counter.finishCumulative(tokenSource(id));
                for (String id : recentlyClosedThreads) counter.finishCumulative(tokenSource(id));
            }
            activeThreads.clear();
            recentlyClosedThreads.clear();
        }
    }

    private static String rememberClosed(LinkedHashSet<String> closed, String id, int limit) {
        closed.remove(id);
        closed.add(id);
        if (closed.size() <= limit) return null;
        java.util.Iterator<String> oldest = closed.iterator();
        if (!oldest.hasNext()) return null;
        String evicted = oldest.next();
        oldest.remove();
        return evicted;
    }

    private void recordTokenUsage(JsonObject params) {
        recordTokenUsage(null, params);
    }

    private void recordTokenUsage(Process sourceProcess, JsonObject params) {
        String threadId = string(params, "threadId");
        JsonObject total = object(object(params, "tokenUsage"), "total");
        if (!validIdentifier(threadId) || total.size() == 0) return;
        synchronized (notificationStateLock) {
            if (sourceProcess != null && process != sourceProcess) return;
            if (!activeThreads.contains(threadId)
                    && !recentlyClosedThreads.contains(threadId)) return;
            LegacySessionTokenUsage counter = tokenUsage;
            if (counter == null) return;
            counter.recordCumulative(
                    tokenSource(threadId),
                    longValue(total, "inputTokens"),
                    longValue(total, "cachedInputTokens"),
                    longValue(total, "outputTokens"),
                    longValue(total, "reasoningOutputTokens"),
                    longValue(total, "totalTokens"));
        }
    }

    private static String tokenSource(String threadId) { return "codex:" + threadId; }

    private void rejectServerRequest(Process sourceProcess, BufferedWriter sourceWriter,
                                     JsonElement id, String method) {
        JsonObject response = new JsonObject();
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", -32601);
        error.addProperty("message", "Minecraft Translator does not allow app-server request: " + method);
        response.add("error", error);
        try {
            if (sourceProcess == null) send(response);
            else sendToReaderProcess(sourceProcess, sourceWriter, response);
        } catch (IOException ignored) {
            // The process may already be exiting.
        }
    }

    private void send(JsonObject message) throws IOException {
        synchronized (lifecycleLock) {
            Process currentProcess = process;
            BufferedWriter currentWriter = writer;
            if (currentProcess == null || currentWriter == null || !currentProcess.isAlive()) {
                throw new IOException("Codex app-server is not running");
            }
            sendToWriter(currentWriter, message);
        }
    }

    private void sendToProcess(Process expectedProcess, JsonObject message) throws IOException {
        synchronized (lifecycleLock) {
            BufferedWriter currentWriter = writer;
            if (expectedProcess == null || process != expectedProcess
                    || currentWriter == null || !expectedProcess.isAlive()) {
                throw new IOException("Codex app-server process changed");
            }
            sendToWriter(currentWriter, message);
        }
    }

    private void sendToReaderProcess(Process expectedProcess, BufferedWriter expectedWriter,
                                     JsonObject message) throws IOException {
        if (process != expectedProcess || expectedWriter == null) {
            throw new IOException("Codex app-server process changed");
        }
        /*
         * The reader owns this immutable writer binding.  Even if the process
         * changes immediately after the volatile identity check, it can only
         * write to (or fail on) the old writer, never the replacement writer.
         * Avoiding lifecycleLock here is essential while initialize waits for
         * its response under that lock.
         */
        sendToWriter(expectedWriter, message);
    }

    private void sendToWriter(BufferedWriter current, JsonObject message) throws IOException {
        sendEncodedToWriter(current, encodeMessage(message));
    }

    private static String encodeMessage(JsonObject message) throws IOException {
        String encoded = message.toString();
        if (encoded.length() > MAX_JSONL_LINE_CHARS) {
            throw new IOException("Codex JSONL request too large");
        }
        return encoded;
    }

    private void sendEncodedToWriter(BufferedWriter current, String encoded) throws IOException {
        synchronized (writeLock) {
            current.write(encoded);
            current.newLine();
            current.flush();
        }
    }

    private static String readBoundedLine(BufferedReader reader, int maximumChars)
            throws IOException {
        StringBuilder line = new StringBuilder(Math.min(4096, maximumChars));
        boolean sawAny = false;
        int value;
        while ((value = reader.read()) >= 0) {
            sawAny = true;
            if (value == '\n') break;
            if (line.length() >= maximumChars) {
                while (value >= 0 && value != '\n') value = reader.read();
                throw new IOException("Codex JSONL line too large");
            }
            line.append((char) value);
        }
        if (!sawAny && value < 0) return null;
        int length = line.length();
        if (length > 0 && line.charAt(length - 1) == '\r') line.setLength(length - 1);
        return line.toString();
    }

    private void putCompletedLogin(String loginId, Boolean success) {
        completedLoginResults.remove(loginId);
        completedLoginResults.put(loginId, success);
        while (completedLoginResults.size() > MAX_COMPLETED_LOGIN_RESULTS) {
            java.util.Iterator<Map.Entry<String, Boolean>> oldest =
                    completedLoginResults.entrySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    private static boolean validIdentifier(String value) {
        return value != null && !value.trim().isEmpty()
                && value.length() <= MAX_IDENTIFIER_CHARS;
    }

    private void failIfCurrent(Process runningProcess, IOException error) {
        if (signalInitializationFailure(runningProcess, error)) return;
        synchronized (lifecycleLock) {
            if (process != runningProcess) return;
            stopProcess();
            failAll(error);
        }
    }

    private void failAll(IOException error) {
        synchronized (lifecycleLock) {
            ready = false;
            lastError = error.getMessage() == null ? "Codex app-server stopped" : error.getMessage();
            List<CompletableFuture<JsonObject>> failedTurns;
            List<CompletableFuture<String>> failedMessages;
            synchronized (notificationStateLock) {
                for (String id : turnResults.keySet()) {
                    rememberClosed(recentlyClosedTurns, id, MAX_RECENTLY_CLOSED_TURNS);
                }
                failedTurns = new ArrayList<CompletableFuture<JsonObject>>(turnResults.size());
                failedMessages = new ArrayList<CompletableFuture<String>>(turnResults.size());
                for (ActiveTurnState state : turnResults.values()) {
                    failedTurns.add(state.completed);
                    failedMessages.add(state.message);
                }
                turnResults.clear();
                earlyTurns.clear();

                LegacySessionTokenUsage counter = tokenUsage;
                List<String> threads = new ArrayList<String>(activeThreads);
                for (String threadId : threads) {
                    String evicted = rememberClosed(recentlyClosedThreads, threadId,
                            MAX_RECENTLY_CLOSED_THREADS);
                    if (counter != null && evicted != null) {
                        counter.finishCumulative(tokenSource(evicted));
                    }
                }
                activeThreads.clear();
            }
            List<CompletableFuture<JsonElement>> failedRequests;
            synchronized (requestStateLock) {
                failedRequests = new ArrayList<CompletableFuture<JsonElement>>(pending.values());
                pending.clear();
            }
            for (CompletableFuture<JsonElement> future : failedRequests) {
                future.completeExceptionally(error);
            }
            for (CompletableFuture<JsonObject> future : failedTurns) {
                future.completeExceptionally(error);
            }
            for (CompletableFuture<String> future : failedMessages) {
                future.completeExceptionally(error);
            }
            List<CompletableFuture<Boolean>> failedLogins;
            synchronized (loginStateLock) {
                failedLogins = new ArrayList<CompletableFuture<Boolean>>(loginResults.values());
                loginResults.clear();
                completedLoginResults.clear();
            }
            for (CompletableFuture<Boolean> future : failedLogins) {
                future.completeExceptionally(error);
            }
        }
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
            synchronized (writeLock) {
                try {
                    oldWriter.close();
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }
        Process oldProcess = process;
        process = null;
        while (true) {
            InitializationState state = initializationState.get();
            if (state == null || oldProcess != null && state.generation != oldProcess) break;
            if (initializationState.compareAndSet(state, null)) break;
        }
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
            releaseThreadBaselines();
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

    private static final class ActiveTurnState {
        final CompletableFuture<JsonObject> completed = new CompletableFuture<JsonObject>();
        final CompletableFuture<String> message = new CompletableFuture<String>();
    }

    private static final class EarlyTurnState {
        final String message;
        final JsonObject completedParams;
        final IOException messageFailure;
        EarlyTurnState(String message, JsonObject completedParams, IOException messageFailure) {
            this.message = message;
            this.completedParams = completedParams;
            this.messageFailure = messageFailure;
        }
    }

    private static final class ProcessError {
        final Process generation;
        final String message;
        ProcessError(Process generation, String message) {
            this.generation = generation;
            this.message = message;
        }
    }

    private static final class InitializationState {
        final Process generation;
        final IOException failure;
        InitializationState(Process generation, IOException failure) {
            this.generation = generation;
            this.failure = failure;
        }
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
