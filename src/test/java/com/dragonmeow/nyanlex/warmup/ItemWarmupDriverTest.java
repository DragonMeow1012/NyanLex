package com.dragonmeow.nyanlex.warmup;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.TestConfigs;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.PriorityTranslationExecutor;
import com.dragonmeow.nyanlex.translate.SessionTokenUsage;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All-item warm-up: driver, estimator and background-priority behaviour (inline fakes). */
class ItemWarmupDriverTest {

    private static final class FakeSource implements ItemWarmupSource {
        final List<ItemWarmupTarget> items;
        int cursor;
        boolean available = true;
        int maxProbeSeen;
        int probeCalls;

        FakeSource(List<ItemWarmupTarget> items) {
            this.items = items;
        }

        public int totalItemCount() { return items.size(); }
        public boolean isAvailable() { return available; }
        public boolean isExhausted() { return cursor >= items.size(); }
        public void reset() { cursor = 0; }
        public List<ItemWarmupTarget> probeNext(int max) {
            probeCalls++;
            maxProbeSeen = Math.max(maxProbeSeen, max);
            int end = Math.min(items.size(), cursor + max);
            List<ItemWarmupTarget> out = new ArrayList<>(items.subList(cursor, end));
            cursor = end;
            return out;
        }
    }

    private static final class FakeBackend implements ItemWarmupBackend {
        boolean ai = true;
        boolean requests = true;
        boolean limited;
        final Set<String> ready = new HashSet<>();
        final Set<String> pending = new HashSet<>();
        final List<List<String>> warmed = new ArrayList<>();
        boolean readyAfterWarm = true;

        public boolean isAiEngine() { return ai; }
        public boolean requestsEnabled() { return requests; }
        public boolean isRateLimited() { return limited; }
        public boolean isReady(String s) { return ready.contains(s); }
        public boolean isPending(String s) { return pending.contains(s); }
        public void warm(List<String> sources) {
            warmed.add(new ArrayList<>(sources));
            if (readyAfterWarm) ready.addAll(sources);
        }
        int warmedUnits() {
            int n = 0;
            for (List<String> w : warmed) n += w.size();
            return n;
        }
    }

    private static ItemWarmupTarget item(int i, String... extra) {
        List<String> units = new ArrayList<>();
        units.add("Item " + i);
        units.add("Body of " + i);
        units.addAll(List.of(extra));
        return new ItemWarmupTarget("mod:item" + i, "mod", units);
    }

    private static List<ItemWarmupTarget> items(int n) {
        List<ItemWarmupTarget> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(item(i));
        return list;
    }

    private static TranslatorConfig cfg() {
        TranslatorConfig c = TestConfigs.translating();
        c.itemWarmupEnabled = true;
        c.itemWarmupChunkDelayMs = 3000;
        c.itemWarmupMaxItemsPerSession = 3000;
        return c;
    }

    private static ItemWarmupDriver driver(FakeSource s, FakeBackend b, TranslatorConfig c,
                                           AtomicLong clock) {
        return new ItemWarmupDriver(s, b, () -> c, clock::get);
    }

    @Test
    void itemsThatCannotBeProbedAreSkippedCountedAndFilledByTheNextRun() {
        // Title screen: no world, so a mod item whose tooltip needs world data comes back "failed".
        List<ItemWarmupTarget> list = new ArrayList<>(items(10));
        list.set(3, ItemWarmupTarget.failed("mod:item3", "mod"));
        list.set(7, ItemWarmupTarget.failed("mod:item7", "mod"));
        FakeSource source = new FakeSource(list);
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong();
        TranslatorConfig c = cfg();
        ItemWarmupDriver d = driver(source, backend, c, clock);
        assertTrue(d.start());
        for (int i = 0; i < 20 && d.state() == ItemWarmupDriver.State.RUNNING; i++) {
            d.tick();
            clock.addAndGet(60_000);
        }
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(2, d.progress().skippedFailed());
        assertEquals(8, d.progress().submittedItems());
        assertFalse(backend.ready.contains("Item 3"), "a skipped item is never submitted");

        // Inside a world the same items now build: only the missing ones are sent, cached ones are skipped.
        source.items.set(3, item(3));
        source.items.set(7, item(7));
        d = driver(source, backend, c, clock);
        assertTrue(d.start());
        for (int i = 0; i < 20 && d.state() == ItemWarmupDriver.State.RUNNING; i++) {
            d.tick();
            clock.addAndGet(60_000);
        }
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(0, d.progress().skippedFailed());
        assertEquals(2, d.progress().submittedItems());
        assertEquals(8, d.progress().skippedCached());
        assertTrue(backend.ready.contains("Item 3") && backend.ready.contains("Item 7"));
    }

    @Test
    void runsWithoutAWorldBecauseTheSourceOnlyNeedsTheRegistry() {
        FakeSource source = new FakeSource(items(5));
        source.available = true; // registry ready at the title screen; no level or player involved
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        assertTrue(d.start());
        d.tick();
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(5, d.progress().submittedItems());
    }

    @Test
    void cachedItemsAreNeverSent() {
        FakeSource source = new FakeSource(items(20));
        FakeBackend backend = new FakeBackend();
        for (ItemWarmupTarget t : source.items) backend.ready.addAll(t.sources());
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);

        assertTrue(d.start());
        for (int i = 0; i < 10; i++) d.tick();

        assertEquals(0, backend.warmed.size());
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(20, d.progress().skippedCached());
        assertEquals(0, d.progress().submittedItems());
    }

    @Test
    void chunksAreThrottledByDelay() {
        FakeSource source = new FakeSource(items(20));
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();

        d.tick();
        assertEquals(1, backend.warmed.size());
        assertEquals(ItemWarmupDriver.CHUNK_ITEMS * 2, backend.warmed.get(0).size());

        clock.set(3999);
        d.tick();
        assertEquals(1, backend.warmed.size(), "second chunk must wait for the delay");

        clock.set(4000);
        d.tick();
        assertEquals(2, backend.warmed.size());

        clock.set(7000);
        d.tick();
        assertEquals(3, backend.warmed.size());
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(20, d.progress().submittedItems());
    }

    @Test
    void masterSwitchOffStopsSubmittingAndResumesWhenOn() {
        FakeSource source = new FakeSource(items(20));
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        d.tick();
        assertEquals(1, backend.warmed.size());

        backend.requests = false;
        clock.set(100_000);
        d.tick();
        d.tick();
        assertEquals(1, backend.warmed.size());
        assertEquals(ItemWarmupDriver.State.PAUSED, d.state());
        assertEquals(ItemWarmupDriver.PauseReason.REQUESTS_OFF, d.progress().pauseReason());

        backend.requests = true;
        d.tick();
        assertEquals(2, backend.warmed.size());
        assertEquals(ItemWarmupDriver.PauseReason.NONE, d.progress().pauseReason());
    }

    @Test
    void sharedUnitsAreSentOnlyOnce() {
        List<ItemWarmupTarget> list = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            list.add(new ItemWarmupTarget("mod:i" + i, "mod",
                    List.of("Name " + i, "Shared lore line")));
        }
        FakeSource source = new FakeSource(list);
        FakeBackend backend = new FakeBackend();
        ItemWarmupDriver d = driver(source, backend, cfg(), new AtomicLong());
        d.start();
        d.tick();

        assertEquals(1, backend.warmed.size());
        List<String> sent = backend.warmed.get(0);
        assertEquals(5, sent.size());
        assertEquals(1, sent.stream().filter("Shared lore line"::equals).count());

        ItemWarmupEstimator estimator = new ItemWarmupEstimator(new FakeBackend());
        for (ItemWarmupTarget t : list) estimator.add(t);
        ItemWarmupPlan plan = estimator.build(3000, null);
        assertEquals(5, plan.missingUnits(), "shared unit counts once in the estimate");
    }

    @Test
    void rateLimitPausesAndResumes() {
        FakeSource source = new FakeSource(items(20));
        FakeBackend backend = new FakeBackend();
        backend.limited = true;
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        d.tick();
        assertEquals(0, backend.warmed.size());
        assertEquals(ItemWarmupDriver.PauseReason.RATE_LIMITED, d.progress().pauseReason());

        backend.limited = false;
        d.tick();
        assertEquals(1, backend.warmed.size());
        assertEquals(ItemWarmupDriver.State.RUNNING, d.state());
    }

    @Test
    void noWorldPausesAndUserPauseNeedsResume() {
        FakeSource source = new FakeSource(items(40));
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        source.available = false;
        d.tick();
        assertEquals(ItemWarmupDriver.PauseReason.NO_WORLD, d.progress().pauseReason());
        source.available = true;
        d.tick();
        assertEquals(1, backend.warmed.size());

        d.pause();
        clock.set(50_000);
        d.tick();
        assertEquals(1, backend.warmed.size());
        d.resume();
        d.tick();
        assertEquals(2, backend.warmed.size());

        d.stop();
        clock.set(90_000);
        d.tick();
        assertEquals(2, backend.warmed.size());
        assertEquals(ItemWarmupDriver.State.STOPPED, d.state());
    }

    @Test
    void restartSkipsAlreadyCachedItemsWithoutACursor() {
        FakeSource source = new FakeSource(items(20));
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver first = driver(source, backend, cfg(), clock);
        first.start();
        first.tick();
        first.stop();
        int sentFirst = backend.warmedUnits();
        assertEquals(ItemWarmupDriver.CHUNK_ITEMS * 2, sentFirst);

        ItemWarmupDriver second = driver(source, backend, cfg(), clock);
        assertTrue(second.start());
        for (int i = 0; i < 6; i++) {
            clock.addAndGet(5000);
            second.tick();
        }
        assertEquals(ItemWarmupDriver.State.DONE, second.state());
        assertEquals(ItemWarmupDriver.CHUNK_ITEMS, second.progress().skippedCached());
        assertEquals(20 * 2, backend.warmedUnits(), "each unit sent exactly once overall");
    }

    @Test
    void sessionLimitCapsSubmittedItems() {
        FakeSource source = new FakeSource(items(50));
        FakeBackend backend = new FakeBackend();
        TranslatorConfig c = cfg();
        c.itemWarmupMaxItemsPerSession = 10;
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, c, clock);
        d.start();
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(5000);
            d.tick();
        }
        assertEquals(10, d.progress().submittedItems());
        assertEquals(20, backend.warmedUnits());
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertTrue(d.progress().limitReached());
    }

    @Test
    void pendingChunkBackPressuresNextChunk() {
        FakeSource source = new FakeSource(items(30));
        FakeBackend backend = new FakeBackend();
        backend.readyAfterWarm = false;
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        d.tick();
        backend.pending.addAll(backend.warmed.get(0));

        clock.set(10_000);
        d.tick();
        assertEquals(1, backend.warmed.size(), "still pending: wait");

        backend.pending.clear();
        d.tick();
        assertEquals(2, backend.warmed.size());
    }

    @Test
    void probeBudgetBoundsClientThreadWorkPerTick() {
        FakeSource source = new FakeSource(items(5000));
        FakeBackend backend = new FakeBackend();
        for (ItemWarmupTarget t : source.items) backend.ready.addAll(t.sources());
        ItemWarmupDriver d = driver(source, backend, cfg(), new AtomicLong());
        d.start();
        d.tick();
        assertTrue(d.progress().scanned() <= ItemWarmupDriver.PROBE_BUDGET_PER_TICK);
        assertEquals(ItemWarmupDriver.State.RUNNING, d.state());
    }

    @Test
    void refusesToStartWhenDisabledOrMachineTranslation() {
        FakeSource source = new FakeSource(items(3));
        FakeBackend backend = new FakeBackend();
        TranslatorConfig c = cfg();
        c.itemWarmupEnabled = false;
        assertFalse(driver(source, backend, c, new AtomicLong()).start());

        backend.ai = false;
        assertFalse(driver(source, backend, cfg(), new AtomicLong()).start());
    }

    @Test
    void estimatorCountsCachedAndCalibratesFromSessionUsage() {
        FakeBackend backend = new FakeBackend();
        List<ItemWarmupTarget> list = items(100);
        for (int i = 0; i < 40; i++) backend.ready.addAll(list.get(i).sources());
        ItemWarmupEstimator e = new ItemWarmupEstimator(backend);
        for (ItemWarmupTarget t : list) e.add(t);

        ItemWarmupPlan plan = e.build(3000, null);
        assertEquals(100, plan.totalItems());
        assertEquals(40, plan.cachedItems());
        assertEquals(60, plan.missingItems());
        assertEquals(60, plan.willSubmitItems());
        assertEquals(8, plan.estimatedRequests());
        assertFalse(plan.calibrated());

        assertEquals(10, e.build(10, null).willSubmitItems());

        SessionTokenUsage.Snapshot heavy = new SessionTokenUsage.Snapshot(0, 0, 0, 0, 5 * 100_000, 5);
        ItemWarmupPlan scaled = e.build(3000, heavy);
        assertTrue(scaled.calibrated());
        assertTrue(scaled.estimatedTokens() > plan.estimatedTokens());
    }

    @Test
    void backgroundWarmYieldsToForegroundAndIsInertUnderMachineTranslation() throws Exception {
        PriorityTranslationExecutor executor = new PriorityTranslationExecutor(1, r -> {
            Thread t = new Thread(r, "warmup-test");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(3);
        List<String> order = new CopyOnWriteArrayList<>();
        try {
            TranslatorConfig c = TestConfigs.translating();
            c.aiTooltip = true;
            TranslationCache gt = new TranslationCache((text, target) -> new TranslationResult("G:" + text, "en"),
                    c.targetLang, Runnable::run, 1000);
            TranslationCache ai = new TranslationCache((text, target) -> {
                order.add(text);
                occupied.countDown();
                if (text.contains("Occupier")) {
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                }
                finished.countDown();
                return new TranslationResult("T:" + text, "en");
            }, c.targetLang, executor, 1000);
            TranslationService s = new TranslationService(c, gt, ai);

            s.warmTooltipBatch(List.of("Occupier sword"));
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            s.warmTooltipBatchBackground(List.of("Background pickaxe"));
            s.warmTooltipBatch(List.of("Foreground axe"));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));

            assertEquals(3, order.size());
            assertTrue(order.get(1).contains("Foreground"), "foreground first: " + order);
            assertTrue(order.get(2).contains("Background"), "background last: " + order);
            assertTrue(s.isItemWarmupEngine());

            c.aiTooltip = false;
            int before = order.size();
            s.warmTooltipBatchBackground(List.of("Machine mode line"));
            Thread.sleep(100);
            assertEquals(before, order.size());
            assertFalse(s.isItemWarmupEngine());
        } finally {
            executor.shutdownNow();
        }
    }
}
