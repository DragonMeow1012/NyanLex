package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerClientStateTest {

    @Test
    void earlyNotificationsAreConsumedAtomicallyAndUnknownFloodStaysBounded()
            throws Exception {
        CodexAppServerClient client = client();

        notifyItem(client, "turn-early", "translated");
        notifyCompleted(client, "turn-early");
        assertEquals(1, retainedSize(client, "earlyTurns"));
        assertEquals(0, retainedSize(client, "turnResults"));

        setReady(client, true);
        CompletableFuture<JsonObject> completed = registerTurn(client, "turn-early");
        assertEquals("turn-early", completed.get(1, TimeUnit.SECONDS)
                .getAsJsonObject("turn").get("id").getAsString());
        assertEquals("translated", awaitTurnMessage(client, "turn-early"));
        assertEquals(0, retainedSize(client, "earlyTurns"));
        assertEquals(1, retainedSize(client, "turnResults"));

        closeTurn(client, "turn-early");
        notifyItem(client, "turn-early", "late");
        notifyCompleted(client, "turn-early");
        assertEquals(0, retainedSize(client, "earlyTurns"));
        assertEquals(0, retainedSize(client, "turnResults"));

        for (int i = 0; i < 1_000; i++) {
            String turnId = "unknown-" + i;
            notifyItem(client, turnId, "message-" + i);
            notifyCompleted(client, turnId);
        }
        assertEquals(512, retainedSize(client, "earlyTurns"));
        assertEquals(0, retainedSize(client, "turnResults"));

        client.close();
    }

    @Test
    void messageAndCompletionOrderingIsLosslessBeforeAndAfterRegistration()
            throws Exception {
        CodexAppServerClient client = client(Duration.ofSeconds(1));
        setReady(client, true);

        CompletableFuture<JsonObject> messageFirst = registerTurn(client, "registered-message-first");
        notifyItem(client, "registered-message-first", "message first");
        notifyCompleted(client, "registered-message-first");
        messageFirst.get(1, TimeUnit.SECONDS);
        assertEquals("message first", awaitTurnMessage(client, "registered-message-first"));

        CompletableFuture<JsonObject> completedFirst = registerTurn(client, "registered-completed-first");
        notifyCompleted(client, "registered-completed-first");
        completedFirst.get(1, TimeUnit.SECONDS);
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();
        Thread lateMessage = new Thread(() -> {
            try {
                Thread.sleep(50L);
                notifyItem(client, "registered-completed-first", "completed first");
            } catch (Throwable failure) {
                lateFailure.set(failure);
            }
        }, "codex-state-test-late-message");
        lateMessage.start();
        assertEquals("completed first", awaitTurnMessage(client, "registered-completed-first"));
        lateMessage.join(1_000L);
        assertTrue(!lateMessage.isAlive(), "late-message test thread did not finish");
        if (lateFailure.get() != null) {
            throw new AssertionError("late message delivery failed", lateFailure.get());
        }

        notifyItem(client, "early-message-first", "early message first");
        notifyCompleted(client, "early-message-first");
        CompletableFuture<JsonObject> earlyMessageFirst = registerTurn(client, "early-message-first");
        earlyMessageFirst.get(1, TimeUnit.SECONDS);
        assertEquals("early message first", awaitTurnMessage(client, "early-message-first"));

        notifyCompleted(client, "early-completed-first");
        notifyItem(client, "early-completed-first", "early completed first");
        CompletableFuture<JsonObject> earlyCompletedFirst = registerTurn(client, "early-completed-first");
        earlyCompletedFirst.get(1, TimeUnit.SECONDS);
        assertEquals("early completed first", awaitTurnMessage(client, "early-completed-first"));

        for (String turnId : List.of(
                "registered-message-first",
                "registered-completed-first",
                "early-message-first",
                "early-completed-first")) {
            closeTurn(client, turnId);
        }
        client.close();
    }

    @Test
    void completedTurnWithoutMessageFailsAfterBoundedGrace() throws Exception {
        CodexAppServerClient client = client(Duration.ofMillis(500));
        setReady(client, true);
        CompletableFuture<JsonObject> completed = registerTurn(client, "missing-message");
        notifyCompleted(client, "missing-message");
        completed.get(1, TimeUnit.SECONDS);

        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class,
                () -> awaitTurnMessage(client, "missing-message"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(failure.getMessage().contains("within 500 ms"), failure::getMessage);
        assertTrue(elapsedMillis >= 350L, "message grace ended too early: " + elapsedMillis + " ms");
        assertTrue(elapsedMillis < 2_000L, "message grace was not bounded: " + elapsedMillis + " ms");

        client.close();
    }

    @Test
    void oversizedTurnMessageAndJsonlInputFailWithoutRetainingPayloads() throws Exception {
        CodexAppServerClient client = client(Duration.ofSeconds(1));
        setReady(client, true);
        registerTurn(client, "oversized-message");
        notifyItem(client, "oversized-message", "x".repeat(65_537));
        notifyCompleted(client, "oversized-message");

        IOException messageFailure = assertThrows(IOException.class,
                () -> awaitTurnMessage(client, "oversized-message"));
        assertTrue(messageFailure.getMessage().contains("exceeds 65536 characters"),
                messageFailure::getMessage);

        IOException jsonlFailure = assertThrows(IOException.class, () -> invoke(
                client, "handleLine", new Class<?>[]{String.class}, "x".repeat(1_000_001)));
        assertTrue(jsonlFailure.getMessage().contains("exceeds 1000000 characters"),
                jsonlFailure::getMessage);

        BufferedReader reader = new BufferedReader(new StringReader("12345\n"));
        IOException lineFailure = assertThrows(IOException.class, () -> invoke(
                client, "readBoundedLine", new Class<?>[]{BufferedReader.class, int.class},
                reader, 4));
        assertTrue(lineFailure.getMessage().contains("exceeds 4 characters"),
                lineFailure::getMessage);

        client.close();
    }

    @Test
    void pendingRequestsAndLoginStateHaveIndependentHardCaps() throws Exception {
        CodexAppServerClient client = client();
        StubProcess process = new StubProcess();
        field(client, "process").set(client, process);
        field(client, "writer").set(client, new BufferedWriter(Writer.nullWriter()));
        List<CompletableFuture<JsonElement>> pendingFutures = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            CompletableFuture<JsonElement> future = new CompletableFuture<>();
            pendingFutures.add(future);
            registerPending(client, "pending-" + i, future);
        }
        assertEquals(512, retainedSize(client, "pending"));
        IOException pendingFailure = assertThrows(IOException.class, () ->
                registerPending(client, "pending-overflow", new CompletableFuture<>()));
        assertTrue(pendingFailure.getMessage().contains("Too many pending"),
                pendingFailure::getMessage);

        setReady(client, true);
        for (int i = 0; i < 1_000; i++) {
            notifyLoginCompleted(client, "early-login-" + i, true);
        }
        assertEquals(0, retainedSize(client, "loginResults"));
        assertEquals(128, retainedSize(client, "completedLoginResults"));
        CompletableFuture<Boolean> latestEarly = registerLogin(client, "early-login-999");
        assertTrue(latestEarly.get(1, TimeUnit.SECONDS));
        assertEquals(1, retainedSize(client, "loginResults"));
        assertEquals(127, retainedSize(client, "completedLoginResults"));
        invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                "early-login-999", latestEarly);

        List<CompletableFuture<Boolean>> activeLogins = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            CompletableFuture<Boolean> login = registerLogin(client, "active-login-" + i);
            activeLogins.add(login);
        }
        assertEquals(128, retainedSize(client, "loginResults"));
        IOException loginFailure = assertThrows(IOException.class, () ->
                registerLogin(client, "active-login-overflow"));
        assertTrue(loginFailure.getMessage().contains("Too many active"),
                loginFailure::getMessage);

        client.close();
        assertTrue(pendingFutures.stream().allMatch(CompletableFuture::isCompletedExceptionally));
        assertTrue(activeLogins.stream().allMatch(CompletableFuture::isCompletedExceptionally));
        assertEquals(0, retainedSize(client, "pending"));
        assertEquals(0, retainedSize(client, "loginResults"));
        assertEquals(0, retainedSize(client, "completedLoginResults"));
    }

    @Test
    void oversizedProcessStreamsStopTheChildClearStateAndRejectLateRequests()
            throws Exception {
        for (boolean stderr : List.of(false, true)) {
            int limit = stderr ? 16_384 : 1_000_000;
            byte[] oversized = "x".repeat(limit + 1).getBytes(StandardCharsets.UTF_8);
            InputStream stdout = stderr
                    ? InputStream.nullInputStream()
                    : new ByteArrayInputStream(oversized);
            InputStream error = stderr
                    ? new ByteArrayInputStream(oversized)
                    : InputStream.nullInputStream();
            StubProcess process = new StubProcess(stdout, error);
            CodexAppServerClient client = client();
            field(client, "process").set(client, process);
            field(client, "writer").set(client, new BufferedWriter(Writer.nullWriter()));
            setReady(client, true);

            CompletableFuture<JsonElement> pending = new CompletableFuture<>();
            registerPending(client, "pending-before-" + stderr, pending);
            CompletableFuture<JsonObject> turn = registerTurn(client, "turn-before-" + stderr);
            CompletableFuture<Boolean> login = registerLogin(client, "login-before-" + stderr);
            openThread(client, "thread-before-" + stderr);
            notifyItem(client, "early-before-" + stderr, "early message");
            notifyLoginCompleted(client, "early-login-before-" + stderr, true);

            if (stderr) {
                invoke(client, "startStderrReader", new Class<?>[]{Process.class}, process);
            } else {
                invoke(client, "startReader",
                        new Class<?>[]{Process.class, BufferedWriter.class},
                        process, field(client, "writer").get(client));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!pending.isDone() || !turn.isDone() || !login.isDone())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }

            assertTrue(pending.isCompletedExceptionally(), "pending future survived " + stream(stderr));
            assertTrue(turn.isCompletedExceptionally(), "turn future survived " + stream(stderr));
            assertTrue(login.isCompletedExceptionally(), "login future survived " + stream(stderr));
            assertTrue(!process.isAlive(), stream(stderr) + " failure left the child alive");
            assertEquals(null, field(client, "process").get(client));
            assertEquals(null, field(client, "writer").get(client));
            assertEquals(0, retainedSize(client, "pending"));
            assertEquals(0, retainedSize(client, "turnResults"));
            assertEquals(0, retainedSize(client, "earlyTurns"));
            assertEquals(0, retainedSize(client, "loginResults"));
            assertEquals(0, retainedSize(client, "completedLoginResults"));
            assertEquals(0, retainedSize(client, "activeThreads"));
            assertTrue(client.lastError().contains("line exceeds " + limit + " characters"),
                    client::lastError);

            IOException lateRegistration = assertThrows(IOException.class, () ->
                    registerPending(client, "pending-after-" + stderr,
                            new CompletableFuture<>()));
            assertTrue(lateRegistration.getMessage().contains("line exceeds " + limit + " characters"),
                    lateRegistration::getMessage);
            assertEquals(0, retainedSize(client, "pending"));
            client.close();
        }
    }

    @Test
    void requestRegistrationAndWriteAreAtomicAgainstProcessFailure() throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        Process generation = currentProcess(client);
        BlockingWriter sink = new BlockingWriter();
        field(client, "writer").set(client, new BufferedWriter(sink));
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        AtomicReference<Throwable> requestFailure = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                registerPending(client, "atomic", future);
            } catch (Throwable failure) {
                requestFailure.set(failure);
            }
        }, "codex-atomic-request");
        request.start();
        assertTrue(sink.entered.await(1, TimeUnit.SECONDS), "request never reached its writer");

        AtomicReference<Throwable> failureThreadError = new AtomicReference<>();
        Thread failure = new Thread(() -> {
            try {
                invoke(client, "failIfCurrent",
                        new Class<?>[]{Process.class, IOException.class},
                        generation, new IOException("forced generation failure"));
            } catch (Throwable problem) {
                failureThreadError.set(problem);
            }
        }, "codex-generation-failure");
        failure.start();
        Thread.sleep(50L);
        assertTrue(failure.isAlive(),
                "process failure interleaved between pending registration and write");
        assertEquals(1, retainedSize(client, "pending"));

        sink.release.countDown();
        request.join(1_000L);
        failure.join(1_000L);
        assertTrue(!request.isAlive() && !failure.isAlive(), "atomic race threads did not finish");
        assertEquals(null, requestFailure.get());
        assertEquals(null, failureThreadError.get());
        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, retainedSize(client, "pending"));
        assertEquals(null, field(client, "process").get(client));
        client.close();
    }

    @Test
    void staleGenerationCannotRegisterStateOrWriteToReplacementWriter() throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        StubProcess oldProcess = (StubProcess) currentProcess(client);
        StubProcess newProcess = new StubProcess();
        RecordingWriter replacementSink = new RecordingWriter();
        field(client, "process").set(client, newProcess);
        field(client, "writer").set(client, new BufferedWriter(replacementSink));

        assertThrows(IOException.class, () -> invoke(client, "registerTurn",
                new Class<?>[]{String.class, Process.class}, "same-turn", oldProcess));
        assertThrows(IOException.class, () -> invoke(client, "registerLogin",
                new Class<?>[]{String.class, Process.class}, "same-login", oldProcess));
        assertThrows(IOException.class, () -> invoke(client, "openThread",
                new Class<?>[]{String.class, Process.class}, "same-thread", oldProcess));
        assertThrows(IOException.class, () -> invoke(client, "registerAndSendRequest",
                new Class<?>[]{Process.class, String.class, CompletableFuture.class,
                        JsonObject.class},
                oldProcess, "stale-request", new CompletableFuture<JsonElement>(),
                request("stale-request")));

        JsonObject unsubscribe = new JsonObject();
        unsubscribe.addProperty("threadId", "old-thread");
        invoke(client, "sendBestEffortRequest",
                new Class<?>[]{Process.class, String.class, JsonObject.class},
                oldProcess, "thread/unsubscribe", unsubscribe);
        assertEquals("", replacementSink.payload.toString(),
                "old generation wrote into the replacement writer");
        assertEquals(0, retainedSize(client, "pending"));
        oldProcess.destroy();
        client.close();
    }

    @Test
    void mainRequestWriteFailureStopsAndFailsTheWholeGeneration() throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        StubProcess generation = (StubProcess) currentProcess(client);
        field(client, "writer").set(client, new BufferedWriter(new FailingWriter()));
        CompletableFuture<JsonElement> future = new CompletableFuture<>();

        IOException failure = assertThrows(IOException.class,
                () -> registerPending(client, "write-failure", future));

        assertTrue(failure.getMessage().contains("forced writer failure"), failure::getMessage);
        assertTrue(!generation.isAlive(), "failed request writer left its process alive");
        assertTrue(future.isCompletedExceptionally(), "failed request future was orphaned");
        assertEquals(null, field(client, "process").get(client));
        assertEquals(null, field(client, "writer").get(client));
        assertEquals(0, retainedSize(client, "pending"));
        client.close();
    }

    @Test
    void localOutgoingOversizeDoesNotKillHealthyGenerationOrOtherRequests()
            throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        Process generation = currentProcess(client);
        CompletableFuture<JsonElement> existing = new CompletableFuture<>();
        registerPending(client, "existing", existing);

        JsonObject oversized = request("oversized");
        oversized.addProperty("payload", "x".repeat(1_000_001));
        CompletableFuture<JsonElement> rejected = new CompletableFuture<>();
        IOException failure = assertThrows(IOException.class, () -> invoke(
                client, "registerAndSendRequest",
                new Class<?>[]{Process.class, String.class, CompletableFuture.class,
                        JsonObject.class},
                generation, "oversized", rejected, oversized));

        assertTrue(failure.getMessage().contains("outgoing message exceeds 1000000"),
                failure::getMessage);
        assertSame(generation, currentProcess(client));
        assertTrue(generation.isAlive());
        assertFalse(existing.isDone(), "local invalid input failed an unrelated request");
        assertFalse(rejected.isDone(), "unregistered local input was mutated");
        assertEquals(1, retainedSize(client, "pending"));
        client.close();
    }

    @Test
    void earlyTurnMessageAndCompletionAreFirstWinsIncludingOversize()
            throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);

        notifyItem(client, "early-oversized-first", "x".repeat(65_537));
        notifyItem(client, "early-oversized-first", "must-not-recover");
        notifyCompletedStatus(client, "early-oversized-first", "failed");
        notifyCompletedStatus(client, "early-oversized-first", "completed");
        CompletableFuture<JsonObject> completed =
                registerTurn(client, "early-oversized-first");
        assertEquals("failed", completed.get(1, TimeUnit.SECONDS)
                .getAsJsonObject("turn").get("status").getAsString());
        IOException failure = assertThrows(IOException.class,
                () -> awaitTurnMessage(client, "early-oversized-first"));
        assertTrue(failure.getMessage().contains("exceeds 65536"), failure::getMessage);

        notifyItem(client, "early-valid-first", "first");
        notifyItem(client, "early-valid-first", "second");
        notifyCompleted(client, "early-valid-first");
        registerTurn(client, "early-valid-first").get(1, TimeUnit.SECONDS);
        assertEquals("first", awaitTurnMessage(client, "early-valid-first"));
        client.close();
    }

    @Test
    void taggedProcessErrorsAndEarlyLoginNeverWaitForLifecycleMonitor()
            throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        Process old = currentProcess(client);
        field(client, "lastError").set(client, "baseline");
        invoke(client, "recordProcessError",
                new Class<?>[]{Process.class, String.class}, old, "old stderr");
        assertEquals("old stderr", client.lastError());

        StubProcess replacement = new StubProcess();
        field(client, "process").set(client, replacement);
        assertEquals("baseline", client.lastError(), "old tagged stderr leaked generations");
        invoke(client, "recordProcessError",
                new Class<?>[]{Process.class, String.class}, old, "stale stderr");
        assertEquals("baseline", client.lastError());

        Object lifecycle = field(client, "lifecycleLock").get(client);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        synchronized (lifecycle) {
            Thread errorReader = new Thread(() -> {
                try {
                    invoke(client, "recordProcessError",
                            new Class<?>[]{Process.class, String.class},
                            replacement, "replacement stderr");
                    assertEquals("replacement stderr", client.lastError());
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "codex-lock-free-stderr");
            errorReader.start();
            errorReader.join(500L);
            assertFalse(errorReader.isAlive(), "stderr/lastError waited for lifecycleLock");

            setReady(client, false); // initialize is still in progress
            Thread loginReader = new Thread(() -> {
                try {
                    notifyLoginCompleted(client, "early-during-init", true);
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "codex-lock-free-login");
            loginReader.start();
            loginReader.join(500L);
            assertFalse(loginReader.isAlive(), "early login notification deadlocked initialize");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, retainedSize(client, "completedLoginResults"));
        client.close();
    }

    @Test
    void pausedOldProcessErrorCannotEraseReplacementDiagnostic() throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        Process old = currentProcess(client);
        CountDownLatch oldObserved = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        AtomicBoolean pauseFirst = new AtomicBoolean(true);
        AtomicReference<Throwable> oldFailure = new AtomicReference<>();
        client.setProcessErrorHookForTests(() -> {
            if (!pauseFirst.compareAndSet(true, false)) return;
            oldObserved.countDown();
            try {
                if (!releaseOld.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release stale error writer");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("stale error writer interrupted", interrupted);
            }
        });

        Thread oldReader = new Thread(() -> {
            try {
                invoke(client, "recordProcessError",
                        new Class<?>[]{Process.class, String.class}, old, "stale stderr");
            } catch (Throwable failure) {
                oldFailure.set(failure);
            }
        }, "codex-stale-error-writer");
        oldReader.start();
        assertTrue(oldObserved.await(1, TimeUnit.SECONDS),
                "old reader did not pause after observing its error slot");

        StubProcess replacement = new StubProcess();
        field(client, "process").set(client, replacement);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> errorSlot =
                (AtomicReference<Object>) field(client, "processError").get(client);
        errorSlot.set(null); // production generation activation clears the prior tag
        invoke(client, "recordProcessError",
                new Class<?>[]{Process.class, String.class}, replacement, "replacement stderr");
        assertEquals("replacement stderr", client.lastError());

        releaseOld.countDown();
        oldReader.join(1_000L);
        assertFalse(oldReader.isAlive());
        if (oldFailure.get() != null) throw new AssertionError(oldFailure.get());
        assertEquals("replacement stderr", client.lastError(),
                "paused old reader overwrote the replacement generation's error tag");
        client.setProcessErrorHookForTests(null);
        old.destroy();
        client.close();
    }

    @Test
    void readerFailureDuringInitializeWakesPendingWithoutLifecycleMonitor()
            throws Exception {
        CodexAppServerClient client = client();
        setReady(client, false);
        StubProcess generation = (StubProcess) currentProcess(client);
        field(client, "initializingProcess").set(client, generation);
        CompletableFuture<JsonElement> initialize = new CompletableFuture<>();
        registerPending(client, "initialize", initialize);
        Object lifecycle = field(client, "lifecycleLock").get(client);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        synchronized (lifecycle) {
            Thread reader = new Thread(() -> {
                try {
                    invoke(client, "failIfCurrent",
                            new Class<?>[]{Process.class, IOException.class},
                            generation, new IOException("stdout ended during initialize"));
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "codex-initialize-reader-failure");
            reader.start();
            reader.join(500L);
            assertFalse(reader.isAlive(),
                    "reader failure waited for lifecycleLock and left initialize to time out");
            assertTrue(initialize.isCompletedExceptionally(),
                    "initialize future was not failed directly by its reader generation");
            assertFalse(generation.isAlive(),
                    "failed initialization child was left alive while cleanup was pending");
            assertEquals(0, retainedSize(client, "pending"));
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        client.close();

        InputStream brokenStderr = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stderr failed after child exit");
            }
        };
        CodexAppServerClient deadChildClient = client();
        StubProcess deadChild = new StubProcess(InputStream.nullInputStream(), brokenStderr);
        field(deadChildClient, "process").set(deadChildClient, deadChild);
        field(deadChildClient, "writer").set(deadChildClient,
                new BufferedWriter(Writer.nullWriter()));
        setReady(deadChildClient, false);
        field(deadChildClient, "initializingProcess").set(deadChildClient, deadChild);
        CompletableFuture<JsonElement> deadChildInitialize = new CompletableFuture<>();
        registerPending(deadChildClient, "initialize-dead-child", deadChildInitialize);
        deadChild.destroy();
        Object deadChildLifecycle = field(deadChildClient, "lifecycleLock").get(deadChildClient);
        synchronized (deadChildLifecycle) {
            invoke(deadChildClient, "startStderrReader",
                    new Class<?>[]{Process.class}, deadChild);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L);
            while (!deadChildInitialize.isDone() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(deadChildInitialize.isCompletedExceptionally(),
                    "dead-child stderr failure waited for stdout or initialize timeout");
        }
        deadChildClient.close();
    }

    @Test
    void stateOnlyGenerationIsClearedBeforeTakeover() throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        CompletableFuture<Boolean> stale = registerLogin(client, "state-only-login");
        ((StubProcess) currentProcess(client)).destroy();
        field(client, "process").set(client, null);
        field(client, "writer").set(client, null);
        setReady(client, false);

        invoke(client, "clearStaleGenerationState", new Class<?>[0]);
        assertTrue(stale.isCompletedExceptionally());
        assertEquals(0, retainedSize(client, "loginResults"));
        assertEquals(0, retainedSize(client, "completedLoginResults"));
        client.close();
    }

    @Test
    void identifierAndThreadCapsRejectFloodAndStillUnsubscribeCreatedThread()
            throws Exception {
        CodexAppServerClient client = client();
        StubProcess process = (StubProcess) currentProcess(client);
        ProtocolWriter protocol = new ProtocolWriter(client);
        field(client, "writer").set(client, new BufferedWriter(protocol));
        setReady(client, true);

        String hugeId = "i".repeat(4_097);
        assertThrows(IOException.class, () -> registerTurn(client, hugeId));
        assertThrows(IOException.class, () -> registerLogin(client, hugeId));
        assertThrows(IOException.class, () -> openThread(client, hugeId));
        notifyItem(client, hugeId, "ignored");
        notifyCompleted(client, hugeId);
        assertEquals(0, retainedSize(client, "earlyTurns"));

        for (int i = 0; i < 512; i++) openThread(client, "active-" + i);
        assertThrows(IOException.class, () -> openThread(client, "active-overflow"));
        IOException capFailure = assertThrows(IOException.class, () -> client.complete(
                "gpt-test", "medium", "Translate only.", "Oak Chest"));
        assertTrue(capFailure.getMessage().contains("Too many active Codex threads"),
                capFailure::getMessage);
        assertEquals(List.of("thread/start", "thread/unsubscribe"), protocol.methods,
                "server-created ephemeral thread leaked when local cap rejected it");
        assertTrue(process.isAlive());
        client.close();
    }

    @Test
    void readerServerRequestReplyDoesNotWaitForLifecycleMonitor() throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        Process generation = currentProcess(client);
        RecordingWriter sink = new RecordingWriter();
        BufferedWriter generationWriter = new BufferedWriter(sink);
        field(client, "writer").set(client, generationWriter);
        Object lifecycle = field(client, "lifecycleLock").get(client);
        AtomicReference<Throwable> replyFailure = new AtomicReference<>();

        synchronized (lifecycle) {
            Thread reply = new Thread(() -> {
                try {
                    invoke(client, "rejectServerRequest",
                            new Class<?>[]{Process.class, BufferedWriter.class,
                                    JsonElement.class, String.class},
                            generation, generationWriter, JsonParser.parseString("7"), "tool/call");
                } catch (Throwable failure) {
                    replyFailure.set(failure);
                }
            }, "codex-server-request-reply");
            reply.start();
            reply.join(1_000L);
            assertTrue(!reply.isAlive(),
                    "reader response waited on lifecycleLock during initialization");
        }
        assertEquals(null, replyFailure.get());
        assertTrue(sink.payload.toString().contains("does not allow app-server request"));
        client.close();
    }

    @Test
    void unknownLoginIdFailsImmediatelyAndOldCleanupCannotRemoveNewIdentity()
            throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        long started = System.nanoTime();
        IOException unknown = assertThrows(IOException.class,
                () -> client.awaitLogin("unknown", Duration.ofSeconds(5)));
        assertTrue(unknown.getMessage().contains("Unknown or expired"), unknown::getMessage);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500L,
                "unknown login id waited for a future generation");

        CompletableFuture<Boolean> old = registerLogin(client, "reused-login");
        invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                "reused-login", old);
        CompletableFuture<Boolean> replacement = registerLogin(client, "reused-login");
        invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                "reused-login", old);
        assertEquals(1, retainedSize(client, "loginResults"));
        invoke(client, "removeLogin", new Class<?>[]{String.class, CompletableFuture.class},
                "reused-login", replacement);
        client.close();
    }

    private static String stream(boolean stderr) {
        return stderr ? "stderr" : "stdout";
    }

    @Test
    void closedThreadsAcceptLateUsageUntilEvictionAndReuseStartsANewBaseline()
            throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        SessionTokenUsage usage = new SessionTokenUsage();
        client.setTokenUsage(usage);

        openThread(client, "thread-one");
        notifyTokenUsage(client, "thread-one", 10, 2, 4, 1, 14);
        closeThread(client, "thread-one");
        notifyTokenUsage(client, "thread-one", 15, 3, 6, 2, 21);
        notifyTokenUsage(client, "never-seen", 100, 0, 100, 0, 200);

        assertEquals(15, usage.snapshot().inputTokens());
        assertEquals(6, usage.snapshot().outputTokens());
        assertEquals(21, usage.snapshot().totalTokens());
        assertEquals(1, usage.snapshot().requests());
        assertEquals(1, usage.activeCumulativeSources());

        // A reused server id represents a new thread, not another cumulative update
        // for the previous one.
        openThread(client, "thread-one");
        notifyTokenUsage(client, "thread-one", 3, 0, 1, 0, 4);
        closeThread(client, "thread-one");
        assertEquals(18, usage.snapshot().inputTokens());
        assertEquals(7, usage.snapshot().outputTokens());
        assertEquals(25, usage.snapshot().totalTokens());
        assertEquals(2, usage.snapshot().requests());

        // The oldest closed id is now evicted and its SessionTokenUsage baseline is
        // released. Notifications after that point are unknown and ignored.
        for (int i = 0; i < 512; i++) {
            String threadId = "closed-" + i;
            openThread(client, threadId);
            closeThread(client, threadId);
        }
        assertEquals(0, usage.activeCumulativeSources());
        notifyTokenUsage(client, "thread-one", 50, 0, 50, 0, 100);
        assertEquals(25, usage.snapshot().totalTokens());
        assertEquals(2, usage.snapshot().requests());

        client.close();
    }

    @Test
    void processFailureClearsActiveTurnsAndReadyGuardRejectsLateRegistration()
            throws Exception {
        CodexAppServerClient client = client();
        setReady(client, true);
        CompletableFuture<JsonObject> active = registerTurn(client, "turn-active");
        openThread(client, "thread-active");

        invoke(client, "failAll", new Class<?>[]{IOException.class},
                new IOException("test process failure"));

        assertTrue(active.isCompletedExceptionally());
        assertEquals(0, retainedSize(client, "turnResults"));
        assertEquals(0, retainedSize(client, "activeThreads"));
        assertEquals(0, retainedSize(client, "recentlyClosedThreads"));
        assertThrows(IOException.class, () -> registerTurn(client, "turn-too-late"));

        client.close();
    }

    @Test
    void turnStartFailureAlwaysClosesKnownThreadAndUnsubscribes() throws Exception {
        CodexAppServerClient client = client();
        StubProcess process = new StubProcess();
        ProtocolWriter protocol = new ProtocolWriter(client);
        field(client, "process").set(client, process);
        field(client, "writer").set(client, new BufferedWriter(protocol));
        setReady(client, true);

        IOException failure = assertThrows(IOException.class, () -> client.complete(
                "gpt-test", "medium", "Translate only.", "Oak Chest"));

        assertEquals("forced turn/start failure", failure.getMessage());
        assertEquals(List.of("thread/start", "turn/start", "thread/unsubscribe"),
                protocol.methods);
        assertEquals(0, retainedSize(client, "activeThreads"));
        assertEquals(1, retainedSize(client, "recentlyClosedThreads"));
        assertEquals(0, retainedSize(client, "turnResults"));

        client.close();
    }

    private static CodexAppServerClient client() {
        return client(Duration.ofSeconds(10));
    }

    private static CodexAppServerClient client(Duration turnMessageGrace) {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "nyanlex-codex-state-test");
        CodexAppServerClient client = new CodexAppServerClient(
                root.resolve("home"), root.resolve("workspace"), turnMessageGrace);
        try {
            field(client, "process").set(client, new StubProcess());
            field(client, "writer").set(client, new BufferedWriter(Writer.nullWriter()));
        } catch (Exception failure) {
            throw new AssertionError("Unable to attach a test process generation", failure);
        }
        return client;
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<JsonObject> registerTurn(
            CodexAppServerClient client, String turnId) throws Exception {
        return (CompletableFuture<JsonObject>) invoke(
                client, "registerTurn", new Class<?>[]{String.class, Process.class},
                turnId, currentProcess(client));
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Boolean> registerLogin(
            CodexAppServerClient client, String loginId) throws Exception {
        return (CompletableFuture<Boolean>) invoke(
                client, "registerLogin", new Class<?>[]{String.class, Process.class},
                loginId, currentProcess(client));
    }

    private static void registerPending(CodexAppServerClient client, String id,
                                        CompletableFuture<JsonElement> future) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("method", "test/pending");
        request.addProperty("id", id);
        invoke(client, "registerAndSendRequest",
                new Class<?>[]{Process.class, String.class, CompletableFuture.class,
                        JsonObject.class},
                currentProcess(client), id, future, request);
    }

    private static String awaitTurnMessage(CodexAppServerClient client, String turnId)
            throws Exception {
        return (String) invoke(
                client, "awaitTurnMessage", new Class<?>[]{String.class}, turnId);
    }

    private static void closeTurn(CodexAppServerClient client, String turnId) throws Exception {
        invoke(client, "closeTurn", new Class<?>[]{String.class, Process.class},
                turnId, currentProcess(client));
    }

    private static void openThread(CodexAppServerClient client, String threadId) throws Exception {
        invoke(client, "openThread", new Class<?>[]{String.class, Process.class},
                threadId, currentProcess(client));
    }

    private static void closeThread(CodexAppServerClient client, String threadId) throws Exception {
        invoke(client, "closeThread", new Class<?>[]{String.class, Process.class},
                threadId, currentProcess(client));
    }

    private static Process currentProcess(CodexAppServerClient client) throws Exception {
        return (Process) field(client, "process").get(client);
    }

    private static void notifyItem(CodexAppServerClient client, String turnId, String text)
            throws Exception {
        JsonObject item = new JsonObject();
        item.addProperty("type", "agentMessage");
        item.addProperty("text", text);
        JsonObject params = new JsonObject();
        params.addProperty("turnId", turnId);
        params.add("item", item);
        notify(client, "item/completed", params);
    }

    private static void notifyCompleted(CodexAppServerClient client, String turnId)
            throws Exception {
        notifyCompletedStatus(client, turnId, "completed");
    }

    private static void notifyCompletedStatus(
            CodexAppServerClient client, String turnId, String status) throws Exception {
        JsonObject turn = new JsonObject();
        turn.addProperty("id", turnId);
        turn.addProperty("status", status);
        JsonObject params = new JsonObject();
        params.add("turn", turn);
        notify(client, "turn/completed", params);
    }

    private static void notifyLoginCompleted(
            CodexAppServerClient client, String loginId, boolean success) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("loginId", loginId);
        params.addProperty("success", success);
        notify(client, "account/login/completed", params);
    }

    private static void notifyTokenUsage(CodexAppServerClient client, String threadId,
                                         long input, long cachedInput, long output,
                                         long reasoningOutput, long total) throws Exception {
        JsonObject totals = new JsonObject();
        totals.addProperty("inputTokens", input);
        totals.addProperty("cachedInputTokens", cachedInput);
        totals.addProperty("outputTokens", output);
        totals.addProperty("reasoningOutputTokens", reasoningOutput);
        totals.addProperty("totalTokens", total);
        JsonObject tokenUsage = new JsonObject();
        tokenUsage.add("total", totals);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("tokenUsage", tokenUsage);
        notify(client, "thread/tokenUsage/updated", params);
    }

    private static void notify(CodexAppServerClient client, String method, JsonObject params)
            throws Exception {
        JsonObject notification = new JsonObject();
        notification.addProperty("method", method);
        notification.add("params", params);
        invoke(client, "handleLine", new Class<?>[]{String.class}, notification.toString());
    }

    private static int retainedSize(CodexAppServerClient client, String fieldName)
            throws Exception {
        Object retained = field(client, fieldName).get(client);
        if (retained instanceof Map<?, ?> map) return map.size();
        if (retained instanceof Collection<?> collection) return collection.size();
        throw new AssertionError(fieldName + " is not a retained-state container");
    }

    private static void setReady(CodexAppServerClient client, boolean ready) throws Exception {
        field(client, "ready").setBoolean(client, ready);
    }

    private static Field field(CodexAppServerClient client, String name) throws Exception {
        Field field = client.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(CodexAppServerClient client, String name,
                                 Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = client.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(client, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }

    private static JsonObject request(String id) {
        JsonObject request = new JsonObject();
        request.addProperty("method", "test/request");
        request.addProperty("id", id);
        return request;
    }

    private static class RecordingWriter extends Writer {
        final StringBuilder payload = new StringBuilder();

        @Override
        public void write(char[] chars, int offset, int length) {
            payload.append(chars, offset, length);
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingWriter extends RecordingWriter {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void flush() throws IOException {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release test writer");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("test writer interrupted", e);
            }
        }
    }

    private static final class FailingWriter extends Writer {
        @Override
        public void write(char[] chars, int offset, int length) {
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("forced writer failure");
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static final class ProtocolWriter extends Writer {
        private final CodexAppServerClient client;
        private final StringBuilder buffer = new StringBuilder();
        private final List<String> methods = new ArrayList<>();

        private ProtocolWriter(CodexAppServerClient client) {
            this.client = client;
        }

        @Override
        public void write(char[] chars, int offset, int length) {
            buffer.append(chars, offset, length);
        }

        @Override
        public void flush() throws IOException {
            String payload = buffer.toString();
            buffer.setLength(0);
            for (String line : payload.lines().toList()) {
                if (line.isBlank()) continue;
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                String method = request.get("method").getAsString();
                methods.add(method);
                if ("thread/unsubscribe".equals(method)) continue;

                JsonObject response = new JsonObject();
                response.add("id", request.get("id"));
                if ("thread/start".equals(method)) {
                    JsonObject thread = new JsonObject();
                    thread.addProperty("id", "thread-cleanup");
                    JsonObject result = new JsonObject();
                    result.add("thread", thread);
                    response.add("result", result);
                } else if ("turn/start".equals(method)) {
                    JsonObject error = new JsonObject();
                    error.addProperty("message", "forced turn/start failure");
                    response.add("error", error);
                } else {
                    response.add("result", new JsonObject());
                }
                try {
                    invoke(client, "handleLine", new Class<?>[]{String.class}, response.toString());
                } catch (Exception e) {
                    throw new IOException("Unable to deliver fake app-server response", e);
                }
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static final class StubProcess extends Process {
        private final InputStream input;
        private final InputStream error;
        private boolean alive = true;

        private StubProcess() {
            this(InputStream.nullInputStream(), InputStream.nullInputStream());
        }

        private StubProcess(InputStream input, InputStream error) {
            this.input = input;
            this.error = error;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return error;
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException("stub process is alive");
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
