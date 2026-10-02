package com.dragonmeow.nyanlex.warmup;

import com.dragonmeow.nyanlex.cache.BatchBudget;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All-item warm-up: driver, estimator and background-lane behaviour (inline fakes, fake clock). */
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
        boolean interactive;
        boolean codex;
        final Set<String> ready = new HashSet<>();
        final Set<String> pending = new HashSet<>();
        final Set<String> nativeUnits = new HashSet<>();
        final List<List<String>> warmed = new ArrayList<>();
        /** true: the request is answered at once; false: it stays pending until {@link #answer}. */
        boolean readyAfterWarm = true;
        int maxPendingBatches;

        public boolean isAiEngine() { return ai; }
        public boolean requestsEnabled() { return requests; }
        public boolean isRateLimited() { return limited; }
        public boolean isReady(String s) { return ready.contains(s) || nativeUnits.contains(s); }
        public boolean isPending(String s) { return pending.contains(s); }
        public boolean interactiveBusy() { return interactive; }
        public boolean usesCodex() { return codex; }
        public boolean needsNoTranslation(String s) { return nativeUnits.contains(s); }
        public void warm(List<String> sources) {
            warmed.add(new ArrayList<>(sources));
            if (readyAfterWarm) ready.addAll(sources);
            else pending.addAll(sources);
        }

        /** The request {@code index} (0-based) is answered: its units are stored and no longer pending. */
        void answer(int index) {
            ready.addAll(warmed.get(index));
            pending.removeAll(warmed.get(index));
        }

        /** The request {@code index} failed (nothing stored). */
        void fail(int index) {
            pending.removeAll(warmed.get(index));
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

    private static String filler(String label, int chars) {
        StringBuilder sb = new StringBuilder(label);
        while (sb.length() < chars) sb.append('x');
        return sb.toString();
    }

    /** Two units of 450 characters: 932 budget points per item, so exactly 4 items fill one request. */
    private static ItemWarmupTarget fat(int i) {
        return new ItemWarmupTarget("mod:fat" + i, "mod",
                List.of(filler("Fat name " + i + " ", 450), filler("Fat body " + i + " ", 450)));
    }

    private static List<ItemWarmupTarget> fatItems(int n) {
        List<ItemWarmupTarget> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(fat(i));
        return list;
    }

    private static TranslatorConfig cfg() {
        TranslatorConfig c = TestConfigs.translating();
        c.itemWarmupEnabled = true;
        return c;
    }

    private static ItemWarmupDriver driver(FakeSource s, FakeBackend b, TranslatorConfig c,
                                           AtomicLong clock) {
        return new ItemWarmupDriver(s, b, () -> c, clock::get);
    }

    private static void runToEnd(ItemWarmupDriver d, AtomicLong clock, int maxTicks) {
        for (int i = 0; i < maxTicks && d.state() == ItemWarmupDriver.State.RUNNING; i++) {
            d.tick();
            clock.addAndGet(60_000);
        }
    }

    // ---------------------------------------------------------------- defaults

    @Test
    void newDefaultsArePacedAndUnlimitedAndOldDefaultsMigrate() {
        TranslatorConfig fresh = new TranslatorConfig().normalized();
        assertEquals(1500, fresh.itemWarmupChunkDelayMs);
        assertEquals(0, fresh.itemWarmupMaxItemsPerSession, "0 means no per-launch limit");

        TranslatorConfig old = new TranslatorConfig();
        old.itemWarmupChunkDelayMs = 3000;     // the previous defaults
        old.itemWarmupMaxItemsPerSession = 3000;
        old.normalized();
        assertEquals(1500, old.itemWarmupChunkDelayMs);
        assertEquals(0, old.itemWarmupMaxItemsPerSession);

        TranslatorConfig chosen = new TranslatorConfig();
        chosen.itemWarmupChunkDelayMs = 2500;   // a deliberate choice survives
        chosen.itemWarmupMaxItemsPerSession = 500;
        chosen.normalized();
        assertEquals(2500, chosen.itemWarmupChunkDelayMs);
        assertEquals(500, chosen.itemWarmupMaxItemsPerSession);
    }

    // ---------------------------------------------------------------- batch size

    @Test
    void aBatchIsFilledUpToTheSharedCharacterBudgetAndTheRestWaits() {
        FakeSource source = new FakeSource(fatItems(10));
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        d.tick();

        assertEquals(1, backend.warmed.size());
        assertEquals(4 * 2, backend.warmed.get(0).size(), "4 items x 2 units fill the budget");
        int chars = 0;
        for (String unit : backend.warmed.get(0)) chars += BatchBudget.unitChars(unit);
        assertTrue(chars <= BatchBudget.WINDOWED_CHARS, "never above the shared budget: " + chars);
        assertTrue(chars + 932 > BatchBudget.WINDOWED_CHARS, "and one more item would not have fit");
        assertEquals(4, d.progress().submittedItems());

        // the item that did not fit leads the next batch (nothing is lost or reordered)
        clock.set(10_000);
        d.tick();
        d.tick();
        assertEquals(2, backend.warmed.size());
        assertTrue(backend.warmed.get(1).get(0).startsWith("Fat name 4 "), backend.warmed.get(1).get(0));
    }

    @Test
    void smallItemsShareOneRequestInsteadOfAFixedCount() {
        FakeSource source = new FakeSource(items(60)); // 47 budget points each: 60 fit in one request
        FakeBackend backend = new FakeBackend();
        ItemWarmupDriver d = driver(source, backend, cfg(), new AtomicLong(1000));
        d.start();
        for (int i = 0; i < 5; i++) d.tick();

        assertEquals(1, backend.warmed.size(), "everything fits one request, so it is one request");
        assertEquals(120, backend.warmed.get(0).size());
    }

    @Test
    void anItemLargerThanTheWholeBudgetTravelsAlone() {
        List<ItemWarmupTarget> list = new ArrayList<>();
        list.add(item(0));
        list.add(new ItemWarmupTarget("mod:huge", "mod",
                List.of(filler("Huge name ", 6_000), "Huge body")));
        list.add(item(2));
        FakeSource source = new FakeSource(list);
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        for (int i = 0; i < 12; i++) {
            d.tick();
            clock.addAndGet(2000);
        }

        assertEquals(3, backend.warmed.size(), backend.warmed.toString());
        assertEquals(List.of("Item 0", "Body of 0"), backend.warmed.get(0));
        assertEquals(2, backend.warmed.get(1).size(), "the huge item is alone: its two units, nothing else");
        assertTrue(backend.warmed.get(1).get(0).startsWith("Huge name "));
        assertEquals(List.of("Item 2", "Body of 2"), backend.warmed.get(2));
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
    }

    @Test
    void theItemCountSafetyNetStopsAPathologicalBatchOfTinyItems() {
        List<ItemWarmupTarget> list = new ArrayList<>();
        for (int i = 0; i < 450; i++) list.add(new ItemWarmupTarget("m:i" + i, "m", List.of("N" + i)));
        FakeSource source = new FakeSource(list);
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        for (int i = 0; i < 40 && d.state() == ItemWarmupDriver.State.RUNNING; i++) {
            d.tick();
            clock.addAndGet(2000);
        }
        for (List<String> sent : backend.warmed) {
            assertTrue(sent.size() <= ItemWarmupDriver.MAX_ITEMS_PER_BATCH, "batch of " + sent.size());
        }
        assertEquals(450, backend.warmedUnits());
    }

    // ---------------------------------------------------------------- pacing

    @Test
    void dispatchesAreSpacedByTheWarmupIntervalNotTheInteractiveCooldown() {
        FakeSource source = new FakeSource(fatItems(20));
        FakeBackend backend = new FakeBackend();
        TranslatorConfig c = cfg();
        c.requestCooldownMs = 10_000; // the interactive cooldown must play no part
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, c, clock);
        d.start();

        d.tick();
        assertEquals(1, backend.warmed.size());

        clock.set(2499);
        d.tick();
        assertEquals(1, backend.warmed.size(), "the second dispatch waits for the interval");

        clock.set(2500);
        d.tick();
        assertEquals(2, backend.warmed.size(), "1.5 s later, long before the 10 s cooldown");
    }

    @Test
    void concurrencyStartsAtOneRampsToThreeAndFallsBackToOneOnA429() {
        FakeSource source = new FakeSource(fatItems(400));
        FakeBackend backend = new FakeBackend();
        backend.readyAfterWarm = false;
        AtomicLong clock = new AtomicLong(0);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();

        d.tick(); // request 0 goes out alone
        assertEquals(1, d.progress().concurrency());
        assertEquals(1, d.progress().inflightRequests());

        clock.addAndGet(1500);
        d.tick();
        assertEquals(1, backend.warmed.size(), "one at a time until two requests succeeded in a row");

        backend.answer(0);
        clock.addAndGet(1500);
        d.tick(); // request 0 settles (1 success), request 1 goes out
        assertEquals(2, backend.warmed.size());
        assertEquals(1, d.progress().concurrency());

        backend.answer(1);
        clock.addAndGet(1500);
        d.tick(); // 2nd success in a row: concurrency 2; request 2 goes out
        assertEquals(2, d.progress().concurrency());
        assertEquals(3, backend.warmed.size());

        clock.addAndGet(1500);
        d.tick(); // room for a second request in flight
        assertEquals(4, backend.warmed.size());
        assertEquals(2, d.progress().inflightRequests());

        clock.addAndGet(1500);
        d.tick();
        assertEquals(4, backend.warmed.size(), "two in flight is the limit at concurrency 2");

        backend.answer(2);
        backend.answer(3);
        clock.addAndGet(1500);
        d.tick();
        assertEquals(3, d.progress().concurrency(), "two more successes: 3 is the ceiling");

        int most = 0;
        for (int round = 0; round < 12; round++) {
            clock.addAndGet(1500);
            d.tick();
            most = Math.max(most, d.progress().inflightRequests());
            for (int i = 0; i < backend.warmed.size(); i++) {
                if (backend.pending.containsAll(backend.warmed.get(i))) {
                    backend.answer(i);
                    break;
                }
            }
        }
        assertTrue(most <= ItemWarmupDriver.MAX_CONCURRENCY, "never above 3, saw " + most);
        assertEquals(3, d.progress().concurrency());

        // 429: back to one request at a time, paused until the shared gate reopens
        backend.limited = true;
        clock.addAndGet(1500);
        d.tick();
        assertEquals(1, d.progress().concurrency());
        assertEquals(ItemWarmupDriver.PauseReason.RATE_LIMITED, d.progress().pauseReason());
        backend.limited = false;
        d.tick();
        assertEquals(ItemWarmupDriver.State.RUNNING, d.state());
        assertEquals(1, d.progress().concurrency(), "the climb starts again from one");
    }

    @Test
    void aFailedRequestStopsTheClimbButDoesNotDropTheLevel() {
        FakeSource source = new FakeSource(fatItems(200));
        FakeBackend backend = new FakeBackend();
        backend.readyAfterWarm = false;
        AtomicLong clock = new AtomicLong(0);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        d.tick();
        backend.answer(0);
        clock.addAndGet(1500);
        d.tick();
        backend.fail(1); // a plain failure (no 429): nothing stored
        clock.addAndGet(1500);
        d.tick();
        backend.answer(2);
        clock.addAndGet(1500);
        d.tick();
        assertEquals(1, d.progress().concurrency(), "success, failure, success is not two in a row");
        assertEquals(4, d.progress().failedItems(), "the failed request's items are counted as failed");
    }

    @Test
    void chatGptSignInIsFixedAtOneRequestAtATime() {
        FakeSource source = new FakeSource(fatItems(200));
        FakeBackend backend = new FakeBackend();
        backend.readyAfterWarm = false;
        backend.codex = true;
        AtomicLong clock = new AtomicLong(0);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        int most = 0;
        for (int round = 0; round < 30; round++) {
            d.tick();
            most = Math.max(most, d.progress().inflightRequests());
            if (round % 2 == 1) {
                for (int i = 0; i < backend.warmed.size(); i++) {
                    if (backend.pending.containsAll(backend.warmed.get(i))) backend.answer(i);
                }
            }
            clock.addAndGet(1500);
        }
        assertEquals(1, most, "never two requests together with ChatGPT sign-in");
        assertEquals(1, d.progress().concurrency());
        assertTrue(backend.warmed.size() > 6, "it still makes steady progress");
    }

    // ---------------------------------------------------------------- yielding

    @Test
    void holdsBackWhileAnInteractiveTranslationIsWorkingAndResumesAfterwards() {
        FakeSource source = new FakeSource(fatItems(20));
        FakeBackend backend = new FakeBackend();
        backend.interactive = true;
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();

        for (int i = 0; i < 5; i++) {
            d.tick();
            clock.addAndGet(1500);
        }
        assertEquals(0, backend.warmed.size(), "nothing is sent while chat/tooltip work is running");
        assertTrue(d.progress().yielding());
        assertEquals(ItemWarmupDriver.State.RUNNING, d.state(), "yielding is not a pause");
        assertEquals(0, source.probeCalls, "no tooltip is even built while yielding");

        backend.interactive = false;
        d.tick();
        assertEquals(1, backend.warmed.size());
        assertFalse(d.progress().yielding());
    }

    @Test
    void aSurfaceThatIsNeverIdleCannotStarveTheRunForever() {
        FakeSource source = new FakeSource(fatItems(20));
        FakeBackend backend = new FakeBackend();
        backend.interactive = true;
        AtomicLong clock = new AtomicLong(0);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        d.tick();
        clock.set(ItemWarmupDriver.YIELD_CAP_MS - 1);
        d.tick();
        assertEquals(0, backend.warmed.size());
        clock.set(ItemWarmupDriver.YIELD_CAP_MS);
        d.tick();
        assertEquals(1, backend.warmed.size(), "after the cap one request goes out anyway");
        clock.addAndGet(1500);
        d.tick();
        assertEquals(1, backend.warmed.size(), "and then it yields again");
    }

    // ---------------------------------------------------------------- skipping

    @Test
    void itemsAlreadyInTheTargetLanguageAreNeverSent() {
        List<ItemWarmupTarget> list = new ArrayList<>();
        list.add(new ItemWarmupTarget("mod:zh0", "mod", List.of("鋼鐵劍", "一把很鋒利的劍")));
        list.add(item(1));
        list.add(new ItemWarmupTarget("mod:zh2", "mod", List.of("魔法杖", "Magic wand")));
        FakeSource source = new FakeSource(list);
        FakeBackend backend = new FakeBackend();
        backend.nativeUnits.addAll(List.of("鋼鐵劍", "一把很鋒利的劍", "魔法杖"));
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        runToEnd(d, clock, 10);

        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(1, d.progress().skippedNative(), "the fully native item is skipped and counted apart");
        assertEquals(0, d.progress().skippedCached());
        assertEquals(2, d.progress().submittedItems());
        Set<String> sent = new HashSet<>();
        backend.warmed.forEach(sent::addAll);
        assertFalse(sent.contains("鋼鐵劍"));
        assertFalse(sent.contains("一把很鋒利的劍"));
        assertTrue(sent.contains("Magic wand"), "a half-translated item still needs its English line");
        assertTrue(sent.contains("Item 1"));

        FakeBackend fresh = new FakeBackend();
        fresh.nativeUnits.addAll(backend.nativeUnits);
        ItemWarmupEstimator estimator = new ItemWarmupEstimator(fresh);
        for (ItemWarmupTarget t : list) estimator.add(t);
        ItemWarmupPlan plan = estimator.build(0, null);
        assertEquals(1, plan.nativeItems());
        assertEquals(2, plan.missingItems());
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
        runToEnd(d, clock, 20);
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(2, d.progress().skippedFailed());
        assertEquals(8, d.progress().submittedItems());
        assertEquals(8, d.progress().translatedItems());
        assertFalse(backend.ready.contains("Item 3"), "a skipped item is never submitted");

        // Inside a world the same items now build: only the missing ones are sent, cached ones are skipped.
        source.items.set(3, item(3));
        source.items.set(7, item(7));
        d = driver(source, backend, c, clock);
        assertTrue(d.start());
        runToEnd(d, clock, 20);
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

    // ---------------------------------------------------------------- limits and switches

    @Test
    void noPerLaunchLimitByDefaultTheWholeRegistryIsSent() {
        FakeSource source = new FakeSource(fatItems(500));
        FakeBackend backend = new FakeBackend();
        TranslatorConfig c = cfg();
        assertEquals(0, c.itemWarmupMaxItemsPerSession);
        AtomicLong clock = new AtomicLong(1000);
        ItemWarmupDriver d = driver(source, backend, c, clock);
        d.start();
        for (int i = 0; i < 600 && d.state() == ItemWarmupDriver.State.RUNNING; i++) {
            d.tick();
            clock.addAndGet(1500);
        }
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertEquals(500, d.progress().submittedItems(), "far beyond the old 3000-per-launch idea of a cap");
        assertEquals(500, d.progress().translatedItems());
        assertEquals(0, d.progress().sessionLimit());
        assertFalse(d.progress().limitReached());
        assertEquals(1000, backend.warmedUnits());
    }

    @Test
    void anExplicitSessionLimitStillCapsTheRun() {
        FakeSource source = new FakeSource(fatItems(50));
        FakeBackend backend = new FakeBackend();
        TranslatorConfig c = cfg();
        c.itemWarmupMaxItemsPerSession = 10;
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, c, clock);
        d.start();
        for (int i = 0; i < 20 && d.state() == ItemWarmupDriver.State.RUNNING; i++) {
            clock.addAndGet(5000);
            d.tick();
        }
        assertEquals(10, d.progress().submittedItems());
        assertEquals(20, backend.warmedUnits());
        assertEquals(ItemWarmupDriver.State.DONE, d.state());
        assertTrue(d.progress().limitReached());
    }

    @Test
    void masterSwitchOffSendsNothingAndResumesWhenOn() {
        FakeSource source = new FakeSource(fatItems(20));
        FakeBackend backend = new FakeBackend();
        backend.requests = false;
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver d = driver(source, backend, cfg(), clock);
        d.start();
        for (int i = 0; i < 5; i++) {
            d.tick();
            clock.addAndGet(60_000);
        }
        assertEquals(0, backend.warmed.size(), "online translation off: zero requests");
        assertEquals(0, source.probeCalls);
        assertEquals(ItemWarmupDriver.State.PAUSED, d.state());
        assertEquals(ItemWarmupDriver.PauseReason.REQUESTS_OFF, d.progress().pauseReason());

        backend.requests = true;
        d.tick();
        assertEquals(1, backend.warmed.size());
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
        FakeSource source = new FakeSource(fatItems(20));
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
        FakeSource source = new FakeSource(fatItems(40));
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
        FakeSource source = new FakeSource(fatItems(20));
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong();
        ItemWarmupDriver first = driver(source, backend, cfg(), clock);
        first.start();
        first.tick();
        first.stop();
        int sentFirst = backend.warmedUnits();
        assertEquals(4 * 2, sentFirst);

        ItemWarmupDriver second = driver(source, backend, cfg(), clock);
        assertTrue(second.start());
        for (int i = 0; i < 30; i++) {
            clock.addAndGet(5000);
            second.tick();
        }
        assertEquals(ItemWarmupDriver.State.DONE, second.state());
        assertEquals(4, second.progress().skippedCached());
        assertEquals(20 * 2, backend.warmedUnits(), "each unit sent exactly once overall");
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

    // ---------------------------------------------------------------- progress figures

    @Test
    void scannedAndTranslatedAreSeparateNumbersAndTheSpeedAndEtaFollowFinishedItems() {
        FakeSource source = new FakeSource(fatItems(400)); // 100 requests of 4 items
        FakeBackend backend = new FakeBackend();
        AtomicLong clock = new AtomicLong(0);
        TranslatorConfig c = cfg();
        // the first 40 items are already stored from an earlier run: scanned, never sent
        for (int i = 0; i < 40; i++) backend.ready.addAll(source.items.get(i).sources());
        ItemWarmupDriver d = driver(source, backend, c, clock);
        d.start();
        assertEquals(0, d.progress().itemsPerMinute(), "no speed before anything finished");
        assertEquals(-1, d.progress().etaMinutes());

        for (int i = 0; i < 40; i++) {
            d.tick();
            clock.addAndGet(1500);
        }
        ItemWarmupDriver.Progress p = d.progress();
        assertTrue(p.scanned() > p.submittedItems(), "scanned counts the 40 stored items too");
        assertEquals(40, p.skippedCached());
        assertEquals(p.submittedItems() - 4 * p.inflightRequests(), p.translatedItems()
                + p.failedItems(), "every sent item is either still in flight, translated or failed");
        assertTrue(p.itemsPerMinute() > 0, "speed " + p.itemsPerMinute());
        assertTrue(p.etaMinutes() > 0, "eta " + p.etaMinutes());
        // about 4 items per 3 s (answer + 1.5 s spacing) is on the order of 80 items a minute
        assertTrue(p.itemsPerMinute() >= 40 && p.itemsPerMinute() <= 200, "speed " + p.itemsPerMinute());
    }

    @Test
    void estimatorCountsCachedAndCalibratesFromSessionUsage() {
        FakeBackend backend = new FakeBackend();
        List<ItemWarmupTarget> list = items(100);
        for (int i = 0; i < 40; i++) backend.ready.addAll(list.get(i).sources());
        ItemWarmupEstimator e = new ItemWarmupEstimator(backend);
        for (ItemWarmupTarget t : list) e.add(t);

        ItemWarmupPlan plan = e.build(0, null);
        assertEquals(100, plan.totalItems());
        assertEquals(40, plan.cachedItems());
        assertEquals(60, plan.missingItems());
        assertEquals(60, plan.willSubmitItems(), "0 means no per-launch limit");
        assertEquals(1, plan.estimatedRequests(), "60 small items fit one request of the shared budget");
        assertFalse(plan.calibrated());

        assertEquals(10, e.build(10, null).willSubmitItems());

        SessionTokenUsage.Snapshot heavy = new SessionTokenUsage.Snapshot(0, 0, 0, 0, 5 * 100_000, 5);
        ItemWarmupPlan scaled = e.build(0, heavy);
        assertTrue(scaled.calibrated());
        assertTrue(scaled.estimatedTokens() > plan.estimatedTokens());
    }

    @Test
    void estimatorCountsRequestsWithTheSameBatchingAsTheRunAndTheTimeFollowsConcurrency() {
        FakeBackend backend = new FakeBackend();
        ItemWarmupEstimator e = new ItemWarmupEstimator(backend);
        for (ItemWarmupTarget t : fatItems(14_000)) e.add(t);
        ItemWarmupPlan plan = e.build(0, null);
        assertEquals(14_000, plan.willSubmitItems());
        assertEquals(3_500, plan.estimatedRequests(), "4 items per request, like the run");
        assertEquals(3_500 * 10 / 3 / 60 + 1, plan.estimatedMinutes(), 1, "three at a time at ~10 s each");

        backend.codex = true;
        assertTrue(e.build(0, null).estimatedMinutes() > plan.estimatedMinutes() * 2,
                "ChatGPT sign-in is one at a time, so it takes longer");
    }

    // ---------------------------------------------------------------- background lane in the service

    @Test
    void backgroundWarmRunsOnItsOwnLaneAndNeverCountsAsInteractiveWork() throws Exception {
        PriorityTranslationExecutor executor = new PriorityTranslationExecutor(1, r -> {
            Thread t = new Thread(r, "warmup-test");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch backgroundDone = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        try {
            TranslatorConfig c = TestConfigs.translating();
            c.aiTooltip = true;
            TranslationCache gt = new TranslationCache((text, target) -> new TranslationResult("G:" + text, "en"),
                    c.targetLang, Runnable::run, 1000);
            TranslationCache ai = new TranslationCache((text, target) -> {
                requests.incrementAndGet();
                order.add(text);
                if (text.contains("Occupier")) {
                    occupied.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                }
                if (text.contains("Background")) backgroundDone.countDown();
                return new TranslationResult("T:" + text, "en");
            }, c.targetLang, executor, 1000);
            TranslationService s = new TranslationService(c, gt, ai);

            assertFalse(s.isInteractiveTranslationBusy());
            s.warmTooltipBatch(List.of("Occupier sword"));
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            assertTrue(s.isInteractiveTranslationBusy(), "a tooltip request in flight is interactive work");

            // the only worker is stuck in the interactive request; the warm lane has its own thread
            s.warmTooltipBatchBackground(List.of("Background pickaxe"));
            assertTrue(backgroundDone.await(3, TimeUnit.SECONDS), "the lane did not wait behind the busy worker");
            release.countDown();
            for (int i = 0; i < 100 && s.isInteractiveTranslationBusy(); i++) Thread.sleep(20);
            assertFalse(s.isInteractiveTranslationBusy());
            assertEquals("T:Background pickaxe", ai.getCached("Background pickaxe"));

            // while only the lane is working it is not interactive work
            CountDownLatch laneBusy = new CountDownLatch(1);
            CountDownLatch laneRelease = new CountDownLatch(1);
            TranslationCache ai2 = new TranslationCache((text, target) -> {
                laneBusy.countDown();
                try { laneRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                return new TranslationResult("T:" + text, "en");
            }, c.targetLang, executor, 1000);
            TranslationService s2 = new TranslationService(c, gt, ai2);
            s2.warmTooltipBatchBackground(List.of("Lane only axe"));
            assertTrue(laneBusy.await(3, TimeUnit.SECONDS));
            assertTrue(ai2.isPending("Lane only axe"), "in flight, so the driver can see it is pending");
            assertFalse(s2.isInteractiveTranslationBusy());
            laneRelease.countDown();

            assertTrue(s.isItemWarmupEngine());

            c.aiTooltip = false;
            int before = requests.get();
            s.warmTooltipBatchBackground(List.of("Machine mode line"));
            Thread.sleep(100);
            assertEquals(before, requests.get());
            assertFalse(s.isItemWarmupEngine());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void withOnlineTranslationOffTheLaneSendsNothing() throws Exception {
        TranslatorConfig c = TestConfigs.translating();
        c.aiTooltip = true;
        c.translationRequestsEnabled = false;
        AtomicInteger calls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("G:" + text, "en");
        }, c.targetLang, Runnable::run, 1000);
        TranslationCache ai = new TranslationCache((text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        }, c.targetLang, Runnable::run, 1000);
        TranslationService s = new TranslationService(c, gt, ai);
        s.warmTooltipBatchBackground(List.of("Iron sword", "A very sharp blade"));
        s.flushBatches();
        assertEquals(0, calls.get(), "online translation off: zero requests");
    }
}
