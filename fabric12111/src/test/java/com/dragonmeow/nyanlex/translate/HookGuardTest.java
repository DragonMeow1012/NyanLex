package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HookHealth (first-hit / miss report) and HookGuard (exception isolation, throttle, auto-disable). */
class HookGuardTest {
    private final List<String> info = new ArrayList<>();
    private final List<String> warn = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    @BeforeEach
    void setUp() {
        HookHealth.resetForTest();
        HookHealth.setSinks(s -> { synchronized (info) { info.add(s); } },
                s -> { synchronized (warn) { warn.add(s); } });
        HookHealth.setClock(clock::get);
    }

    @AfterEach
    void tearDown() {
        HookHealth.resetForTest();
        HookHealth.setClock(System::nanoTime);
    }

    private void advanceSeconds(long s) {
        clock.addAndGet(s * 1_000_000_000L);
    }

    @Test
    void firstHitLogsOnceAndLaterHitsAreSilent() {
        HookHealth.expect("a.one");
        assertFalse(HookHealth.isHit("a.one"));
        for (int i = 0; i < 1000; i++) HookHealth.hit("a.one");
        assertTrue(HookHealth.isHit("a.one"));
        assertEquals(1, info.size());
        assertTrue(info.get(0).contains("a.one"));
    }

    @Test
    void reportListsOnlyHooksThatNeverFiredAndRunsOnce() {
        HookHealth.expectAmbient("ambient.hit", "ambient.dead");
        HookHealth.expect("ctx.dead", "ctx.hit");
        HookGuard.enter("ambient.hit");
        HookGuard.enter("ctx.hit");
        info.clear();

        advanceSeconds(HookHealth.REPORT_DELAY_SECONDS - 1);
        HookHealth.reportIfDue();
        assertTrue(info.isEmpty() && warn.isEmpty(), "not due yet");

        advanceSeconds(2);
        HookHealth.reportIfDue();
        HookHealth.reportIfDue();
        HookGuard.enter("ambient.hit");
        assertEquals(1, info.size(), "report emitted exactly once");
        assertTrue(info.get(0).contains("2/4"));
        assertTrue(info.get(0).contains("ctx.dead"));
        assertEquals(1, warn.size());
        assertTrue(warn.get(0).contains("ambient.dead"));
        assertFalse(warn.get(0).contains("ctx.dead"));
        assertEquals(2, HookHealth.unhitCount());
    }

    @Test
    void menuHitsAloneDoNotStartThe30SecondClock() {
        HookHealth.expectAmbient("hud");
        HookHealth.expect("menu");
        HookGuard.enter("menu");
        advanceSeconds(60);             // a minute in the menu: shorter than the no-ambient fallback
        HookGuard.enter("menu");
        assertEquals(1, info.size());   // only "hook active"; no report yet
        assertEquals(0, warn.size());
        HookGuard.enter("hud");         // entering the world starts the 30 s clock
        advanceSeconds(HookHealth.REPORT_DELAY_SECONDS - 1);
        HookGuard.enter("hud");
        assertEquals(0, warn.size());
        advanceSeconds(2);
        HookGuard.enter("hud");
        assertEquals(0, warn.size(), "hud itself was hit, so nothing in-world is missing");
        assertEquals(3, info.size());   // menu active, hud active, report
    }

    @Test
    void reportWithoutAnyAmbientHitFiresFallbackLater() {
        HookHealth.expectAmbient("hud");
        HookHealth.expect("menu");
        HookGuard.enter("menu");
        info.clear();
        advanceSeconds(HookHealth.NO_AMBIENT_DELAY_SECONDS - 1);
        HookHealth.reportIfDue();
        assertTrue(info.isEmpty());
        advanceSeconds(2);
        HookHealth.reportIfDue();
        assertEquals(1, warn.size());
        assertTrue(warn.get(0).contains("hud"));
    }

    @Test
    void summaryAndCounters() {
        HookHealth.expect("a", "b", "c");
        HookGuard.enter("a");
        assertEquals(3, HookHealth.totalCount());
        assertEquals(2, HookHealth.unhitCount());
        assertEquals("HOOKS hit 1/3 | miss 2 | disabled 0", HookHealth.summary());
        assertEquals("HOOKS miss 2/3", HookHealth.shortSummary());
    }

    @Test
    void failureIsAbsorbedLoggedOnceWithTraceAndRepeatsAreThrottled() {
        for (int i = 0; i < 15; i++) {
            assertTrue(HookGuard.enter("h"));
            HookGuard.fail("h", new IllegalStateException("boom " + i));
            advanceSeconds(20); // keep failures outside the disable window
        }
        // 1st occurrence (with trace) + the 10th-repeat note only.
        assertEquals(2, warn.size());
        assertTrue(warn.get(0).contains("IllegalStateException") && warn.get(0).contains("boom 0"));
        assertTrue(warn.get(0).contains("at "), "first failure carries a stack trace");
        assertTrue(warn.get(1).contains("10 times"));
        assertFalse(HookHealth.isDisabled("h"));
    }

    @Test
    void differentExceptionKindsAreLoggedSeparately() {
        HookGuard.fail("h", new IllegalStateException("a"));
        HookGuard.fail("h", new NullPointerException("b"));
        HookGuard.fail("h", new IllegalStateException("a"));
        assertEquals(2, warn.size());
    }

    @Test
    void burstOfFailuresDisablesHookUntilRestartAndLogsOnce() {
        for (int i = 0; i < HookGuard.DISABLE_THRESHOLD - 1; i++) {
            assertTrue(HookGuard.enter("hot"));
            HookGuard.fail("hot", new RuntimeException("x"));
        }
        assertTrue(HookGuard.enter("hot"), "still enabled just below the threshold");
        HookGuard.fail("hot", new RuntimeException("x"));
        assertTrue(HookHealth.isDisabled("hot"));
        assertFalse(HookGuard.enter("hot"));
        assertFalse(HookGuard.enter("hot"));
        long disabledLogs = warn.stream().filter(s -> s.contains("disabled until the next game start")).count();
        assertEquals(1, disabledLogs);
        assertEquals(1, HookHealth.disabledCount());
    }

    @Test
    void spacedOutFailuresDoNotDisable() {
        for (int i = 0; i < HookGuard.DISABLE_THRESHOLD * 3; i++) {
            HookGuard.fail("slow", new RuntimeException("x"));
            advanceSeconds(HookGuard.WINDOW_SECONDS + 1);
        }
        assertFalse(HookHealth.isDisabled("slow"));
    }

    @Test
    void stickyHooksAreNeverDisabled() {
        for (int i = 0; i < HookGuard.DISABLE_THRESHOLD * 2; i++) {
            assertTrue(HookGuard.enterSticky("pair.begin"));
            HookGuard.fail("pair.begin", new RuntimeException("x"));
        }
        assertFalse(HookHealth.isDisabled("pair.begin"));
        assertTrue(HookGuard.enterSticky("pair.begin"));
    }

    @Test
    void callReturnsFallbackOnExceptionAndWhenDisabled() {
        assertEquals("ok", HookGuard.call("c", () -> "ok", () -> "orig"));
        assertEquals("orig", HookGuard.call("c", () -> { throw new IllegalArgumentException("no"); }, () -> "orig"));
        for (int i = 0; i < HookGuard.DISABLE_THRESHOLD; i++) {
            HookGuard.call("c", () -> { throw new IllegalArgumentException("no"); }, () -> "orig");
        }
        assertTrue(HookHealth.isDisabled("c"));
        assertEquals("orig", HookGuard.call("c", () -> "ok", () -> "orig"), "disabled hook never runs its body");
    }

    @Test
    void runSwallowsExceptions() {
        HookGuard.run("r", () -> { throw new UnsupportedOperationException(); });
        HookGuard.runSticky("rs", () -> { throw new UnsupportedOperationException(); });
        assertTrue(HookHealth.isHit("r"));
        assertTrue(HookHealth.isHit("rs"));
    }

    @Test
    void stackOverflowIsAbsorbedButOutOfMemoryIsRethrown() {
        HookGuard.fail("so", new StackOverflowError());
        assertThrows(OutOfMemoryError.class, () -> HookGuard.fail("oom", new OutOfMemoryError()));
    }

    @Test
    void concurrentHitsAndFailuresAreSafe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 5000; i++) {
                    String id = "k" + (i % 10);
                    HookGuard.enter(id);
                    if (i % 500 == 0) HookGuard.fail(id, new RuntimeException("x"));
                }
                return null;
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(10, HookHealth.totalCount());
        assertEquals(0, HookHealth.unhitCount());
        long activeLogs = info.stream().filter(s -> s.contains("hook active")).count();
        assertEquals(10, activeLogs, "each hook logs its first hit exactly once even under races");
    }
}
