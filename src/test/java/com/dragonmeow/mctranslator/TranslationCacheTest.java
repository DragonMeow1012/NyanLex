package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.PersistentStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import com.dragonmeow.mctranslator.translate.TextFilter;
import com.dragonmeow.mctranslator.translate.TemplateText;
import com.dragonmeow.mctranslator.translate.TranslationTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationCacheTest {


    private static final Executor DIRECT = Runnable::run;


    private static Translator countingUpper(AtomicInteger counter) {
        return (text, target) -> {
            counter.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
    }

    /** Test translator that keeps its synthetic prefix inside the first CS pair. */
    private static Translator countingStyleSafeUpper(AtomicInteger counter) {
        return (text, target) -> {
            counter.incrementAndGet();
            int firstMarkerEnd = text == null ? -1 : text.indexOf('\u27E7');
            String translated = firstMarkerEnd < 0
                    ? "T:" + text
                    : text.substring(0, firstMarkerEnd + 1) + "T:" + text.substring(firstMarkerEnd + 1);
            return new TranslationResult(translated, "en");
        };
    }

    @Test
    void warmBatchAsyncSendsFullSurfaceContextButOnlyUncachedTodoLines() {
        List<List<String>> seenTexts = new ArrayList<>();
        List<List<String>> seenContexts = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("T:" + text, null);
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenTexts.add(new ArrayList<>(texts));
                seenContexts.add(surfaceContext == null ? null : new ArrayList<>(surfaceContext));
                List<TranslationResult> out = new ArrayList<>();
                for (String t : texts) out.add(new TranslationResult("T:" + t, null));
                return out;
            }
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);

        // The tooltip TITLE got translated on its own earlier and is already cached.
        cache.requestAsync("Iron Pickaxe");
        assertEquals("T:Iron Pickaxe", cache.getCached("Iron Pickaxe"));

        List<String> tooltip = List.of("Iron Pickaxe", "", "Recipes", "Used in smelting");
        cache.warmBatchAsync(tooltip, tooltip);

        assertEquals(1, seenTexts.size(), "one batched request for the tooltip");
        assertEquals(List.of("Recipes", "Used in smelting"), seenTexts.get(0),
                "the already-cached title must not be re-requested");
        assertEquals(List.of("Iron Pickaxe", "", "Recipes", "Used in smelting"), seenContexts.get(0),
                "surface context must carry cached rows and blank section boundaries");
        assertEquals("T:Recipes", cache.getCached("Recipes"));
    }

    @Test
    void warmBatchAsyncWithoutSurfaceLinesPassesNullContext() {
        List<List<String>> seenContexts = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("T:" + text, null);
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenContexts.add(surfaceContext);
                List<TranslationResult> out = new ArrayList<>();
                for (String t : texts) out.add(new TranslationResult("T:" + t, null));
                return out;
            }
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);
        cache.warmBatchAsync(List.of("Hello"));
        assertEquals(1, seenContexts.size());
        assertNull(seenContexts.get(0), "the old signature must keep sending NO context");
    }

    @Test
    void reorderedParagraphBreaksAreRejectedInsteadOfFillingWrongRows() {
        Translator bad = (text, target) -> new TranslationResult(
                text.replace("⟦PB0⟧", "⟦TMP⟧")
                        .replace("⟦PB1⟧", "⟦PB0⟧")
                        .replace("⟦TMP⟧", "⟦PB1⟧")
                        .replace("First", "第一").replace("Second", "第二")
                        .replace("Third", "第三"), "en");
        TranslationCache cache = new TranslationCache(bad, "zh-TW", DIRECT, 100);
        String paragraph = "First ⟦PB0⟧ Second ⟦PB1⟧ Third";

        cache.requestAsync(paragraph);

        assertNull(cache.getCached(paragraph),
                "PB order is fixed layout; a reordered response must never enter the cache");
    }

    @Test
    void serverCardCachesIndependentLineTemplatesAndSendsOnlyNewFormats() {
        List<List<String>> submitted = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return translated(text);
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> context) {
                submitted.add(new ArrayList<>(texts));
                return texts.stream().map(this::translated).toList();
            }
            private TranslationResult translated(String text) {
                return new TranslationResult(text
                        .replace("SkyBlock Hub", "SkyBlock 中心")
                        .replace("Players", "玩家")
                        .replace("Server", "伺服器")
                        .replace("Capacity", "容量")
                        .replace("Click", "點擊"), "en");
            }
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);
        List<String> first = List.of(
                "SkyBlock Hub #11", "Players: 48/60", "Server: mega33A", "Click");
        List<String> second = List.of(
                "SkyBlock Hub #13", "Players: 44/60", "Server: alphaShard", "Click");

        cache.warmBatchAsync(first, first);
        assertEquals(1, submitted.size());
        assertEquals(List.of(
                "SkyBlock Hub #⟦MT0⟧",
                "Players: ⟦MT0⟧/⟦MT1⟧",
                "Server: ⟦MT0⟧",
                "Click"), submitted.get(0));

        cache.warmBatchAsync(second, second);
        assertEquals(1, submitted.size(),
                "hub number, player count, and opaque shard id are live slots, not new text");
        assertEquals("SkyBlock中心 #13", cache.getCached(second.get(0)));
        assertEquals("玩家: 44/60", cache.getCached(second.get(1)));
        assertEquals("伺服器: alphaShard", cache.getCached(second.get(2)));
        assertEquals("點擊", cache.getCached(second.get(3)));

        List<String> changedFormat = List.of(
                second.get(0), "Capacity: 44/60", second.get(2), second.get(3));
        cache.warmBatchAsync(changedFormat, changedFormat);
        assertEquals(2, submitted.size());
        assertEquals(List.of("Capacity: ⟦MT0⟧/⟦MT1⟧"), submitted.get(1),
                "only the one unseen semantic format should cross the backend boundary");
    }

    @Test
    void invalidationRejectsAnOlderInFlightResponseAndStartsFreshWork() {
        Deque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> new TranslationResult(
                calls.incrementAndGet() == 1 ? "舊飛行結果" : "全新結果", "en");
        TranslationCache cache = new TranslationCache(translator, "zh-TW", tasks::addLast, 100);

        cache.requestAsync("Hello");                 // old request is queued but not run
        cache.invalidate("Hello");                   // hard-delete + detach that flight
        cache.requestAsync("Hello");                 // a genuinely new request may start now
        assertEquals(2, tasks.size());

        tasks.removeFirst().run();
        assertNull(cache.getCached("Hello"),
                "the response started before invalidate must never refill the cache");

        tasks.removeFirst().run();
        assertEquals("全新結果", cache.getCached("Hello"));
        assertEquals(2, calls.get());
    }

    @Test
    void globalClearDetachesFlightsAndCannotBeRefilledByTheirOldResponses() {
        Deque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> new TranslationResult(
                calls.incrementAndGet() == 1 ? "清除前舊結果" : "清除後新結果", "en");
        TranslationCache cache = new TranslationCache(translator, "zh-TW", tasks::addLast, 100);

        cache.requestAsync("Hello");
        cache.clear();
        cache.requestAsync("Hello");
        assertEquals(2, tasks.size(), "clear must let a fresh request start immediately");

        tasks.removeFirst().run();
        assertNull(cache.getCached("Hello"));
        tasks.removeFirst().run();
        assertEquals("清除後新結果", cache.getCached("Hello"));
    }

    @Test
    void aiTierDoesNotReadExistingGtBeforeItsFirstAiAttempt() {
        TranslationCache gt = new TranslationCache(
                (text, target) -> new TranslationResult("GT譯文", "en"),
                "zh-TW", DIRECT, 100);
        assertEquals("GT譯文", gt.translateBlocking("Hello"));

        Deque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger aiCalls = new AtomicInteger();
        TranslationCache ai = new TranslationCache((text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult("AI精翻", "en");
        }, "zh-TW", tasks::addLast, 100);
        ai.setFallback(gt, true);
        ai.setProvisionalRetryGate(() -> true);

        assertNull(ai.getCached("Hello"),
                "a cold AI miss must not expose even an already-cached GT value");
        assertEquals(0, tasks.size());
        ai.requestAsync("Hello");
        assertEquals(1, tasks.size(), "the first request belongs to AI, not GT");
        assertNull(ai.getCached("Hello"));

        tasks.removeFirst().run();
        assertEquals("AI精翻", ai.getCached("Hello"), "AI replaces the provisional GT value");
        assertEquals(1, aiCalls.get());
    }

    @Test
    void batchPersistsAllCanonicalEntriesInOneStoreTransaction() {
        AtomicInteger singleWrites = new AtomicInteger();
        AtomicInteger batchWrites = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        PersistentStore store = new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) {
                singleWrites.incrementAndGet();
                disk.put(key, value);
            }
            @Override public void putBatch(Map<String, String> entries, java.util.Set<String> provisional) {
                batchWrites.incrementAndGet();
                disk.putAll(entries);
            }
            @Override public void clear() { disk.clear(); }
        };
        Translator translator = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("T:" + text, "en");
            }
            @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
                return texts.stream().map(text -> new TranslationResult("T:" + text, "en")).toList();
            }
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, store);

        assertTrue(cache.warmBatch(List.of("Alpha", "Beta", "Mana Cost: 99")));

        assertEquals(1, batchWrites.get());
        assertEquals(0, singleWrites.get(), "a batch must not rewrite the v2 snapshot per line");
        assertEquals(3, disk.size());
    }

    @Test
    void blockingTranslatesThenCaches() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        assertEquals("T:hi", cache.translateBlocking("hi"));
        assertEquals("T:hi", cache.translateBlocking("hi")); 
        assertEquals(1, calls.get(), "second call must hit cache, not the translator");
        assertEquals("T:hi", cache.getCached("hi"));
    }

    @Test
    void blockingReturnsNullOnFailureAndDoesNotCache() {
        AtomicInteger calls = new AtomicInteger();

        Translator failing = (text, target) -> {
            calls.incrementAndGet();
            throw new TranslationException("boom");
        };
        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100);

        assertNull(cache.translateBlocking("hi"));
        assertNull(cache.getCached("hi"));

        assertNull(cache.translateBlocking("hi"));
        assertEquals(2, calls.get());
    }

    @Test
    void asyncWithDirectExecutorFillsCacheImmediately() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        assertNull(cache.getCached("hi"));
        cache.requestAsync("hi");
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(1, calls.get());
    }

    @Test
    void asyncIsDeduplicatedWhileInFlight() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();

        Executor manual = queue::add;
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", manual, 100);

        cache.requestAsync("hi");
        cache.requestAsync("hi"); 
        assertTrue(cache.isPending("hi"));
        assertEquals(1, queue.size(), "duplicate request must not enqueue a second task");

        queue.poll().run(); 
        assertEquals("T:hi", cache.getCached("hi"));
        assertFalse(cache.isPending("hi"));
        assertEquals(1, calls.get());
    }

    @Test
    void blockingDoesNotCorruptAsyncPending() {


        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);

        cache.requestAsync("hi");                 
        assertTrue(cache.isPending("hi"));

        cache.translateBlocking("hi");            
        assertTrue(cache.isPending("hi"),
                "blocking translate must leave the async pending entry intact");
    }

    @Test
    void failureBackoffSuppressesPerFrameRetries() {

        AtomicInteger calls = new AtomicInteger();
        Translator failing = (text, target) -> {
            calls.incrementAndGet();
            throw new TranslationException("boom");
        };
        long[] now = {1_000L};
        LongSupplier clock = () -> now[0];

        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100, 5_000L, clock);

        cache.requestAsync("hi");                 
        assertEquals(1, calls.get());
        cache.requestAsync("hi");                 
        cache.requestAsync("hi");
        assertEquals(1, calls.get(), "retries during backoff must be suppressed");

        now[0] = 6_000L;                          
        cache.requestAsync("hi");                 
        assertEquals(2, calls.get());
    }

    @Test
    void successClearsPreviousFailureBackoff() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] failNext = {true};
        Translator flaky = (text, target) -> {
            calls.incrementAndGet();
            if (failNext[0]) throw new TranslationException("boom");
            return new TranslationResult("T:" + text, "en");
        };
        long[] now = {0L};
        TranslationCache cache = new TranslationCache(flaky, "zh-TW", DIRECT, 100, 5_000L, () -> now[0]);

        cache.requestAsync("hi");                 
        failNext[0] = false;
        now[0] = 10_000L;                         
        cache.requestAsync("hi");                 
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(2, calls.get());
    }


    private static PersistentStore inlineStore(Map<String, String> backing) {
        return new PersistentStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void clear() { backing.clear(); }
            @Override public void remove(String key) { backing.remove(key); }
        };
    }

    private static String hudLine(String place, String health, String maxHealth, String mana, String maxMana) {
        return "\u00a76" + health + "/" + maxHealth + "\ue010     \u00a77\ue067 \u00a7b" + place
                + "     \u00a7b" + mana + "/" + maxMana + "\ue003 Mana";
    }

    private static String hudLine(String firstColor, String iconColor, String placeColor, String place,
                                  String health, String maxHealth, String mana, String maxMana) {
        return "\u00a7" + firstColor + health + "/" + maxHealth + "\ue010     \u00a7" + iconColor
                + "\ue067 \u00a7" + placeColor + place + "     \u00a7" + placeColor
                + mana + "/" + maxMana + "\ue003 Mana";
    }

    @Test
    void diskTierRecoversEntriesEvictedFromMemory() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();

        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", DIRECT, 1, 10_000L, () -> 0L, inlineStore(disk));

        assertEquals("T:a", cache.translateBlocking("a")); 
        assertEquals("T:b", cache.translateBlocking("b")); 
        assertEquals(2, calls.get());


        assertEquals("T:a", cache.getCached("a"));
        assertEquals(2, calls.get(), "disk hit must not call the translator again");
        assertTrue(disk.containsKey("a") && disk.containsKey("b"));
    }

    @Test
    void diskHitPreventsRedundantAsyncTranslation() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("hi", "T:hi"); 
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", queue::add, 100, 10_000L, () -> 0L, inlineStore(disk));

        cache.requestAsync("hi");                 
        assertEquals(0, queue.size());
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(0, calls.get());
    }

    @Test
    void manaCostNumberVariantsUseOneTemplateRequest() {
        List<List<String>> seenBatches = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                throw new AssertionError("single request not expected");
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenBatches.add(new ArrayList<>(texts));
                List<TranslationResult> out = new ArrayList<>();
                for (String ignored : texts) out.add(new TranslationResult("魔力消耗：⟦MT0⟧", "en"));
                return out;
            }
        };
        Map<String, String> disk = new java.util.HashMap<>();
        TranslationCache cache = new TranslationCache(
                fake, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        cache.requestBatched("Mana Cost: 99");
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(1, seenBatches.size());
        assertEquals(List.of("Mana Cost: ⟦MT0⟧"), seenBatches.get(0));
        assertEquals("魔力消耗：99", cache.getCached("Mana Cost: 99"));
        assertEquals("魔力消耗：124", cache.getCached("Mana Cost: 124"));

        cache.requestBatched("Mana Cost: 124");
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(1, seenBatches.size(), "number-only variants must not issue another request");
        assertTrue(disk.containsKey("Mana Cost: ⟦MT0⟧"));
        assertFalse(disk.containsKey("Mana Cost: 99"), "raw numeric aliases must not be persisted");
        assertFalse(disk.containsKey("Mana Cost: 124"), "raw numeric aliases must not be persisted");
    }

    @Test
    void unlistedLobbyNamesUseDistinctCacheKeys() {
        AtomicInteger calls = new AtomicInteger();
        List<String> submitted = new ArrayList<>();
        Translator fake = (text, targetLang) -> {
            calls.incrementAndGet();
            submitted.add(text);
            return new TranslationResult(text.replace("joined the lobby!", "加入了大廳！"), "en");
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);
        String first = "[MVP+] Life joined the lobby!";
        String second = "[VIP] DashieBrot joined the lobby!";

        cache.requestAsync(first);
        cache.requestAsync(second);

        assertEquals(2, calls.get(), "unlisted names must not be merged by a name heuristic");
        assertEquals(2, submitted.size());
        assertTrue(submitted.stream().anyMatch(text -> text.contains("Life")));
        assertTrue(submitted.stream().anyMatch(text -> text.contains("DashieBrot")));
        assertNotEquals(TemplateText.prepare(first).text(), TemplateText.prepare(second).text());
        assertEquals("[MVP+] Life 加入了大廳！", cache.getCached(first));
        assertEquals("[VIP] DashieBrot 加入了大廳！", cache.getCached(second));
    }

    @Test
    void actionBarAmountsShareOneTemplateRequest() {
        AtomicInteger calls = new AtomicInteger();
        Translator fake = (text, targetLang) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("You received", "你獲得了")
                    .replace("coins!", "硬幣！"), "en");
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);

        cache.requestAsync("You received 10,518.2 coins!");
        assertEquals("你獲得了10,518.2硬幣！",
                cache.getCached("You received 10,518.2 coins!"));
        assertEquals("你獲得了26.2硬幣！",
                cache.getCached("You received 26.2 coins!"));
        assertEquals(1, calls.get(), "a new coin amount must not submit another request");
    }

    @Test
    void malformedLegacyStyleMarkersArePurgedInsteadOfRendered() {
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("Daily Reward", "每日獎勵⟦/CS2⟧");
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));

        assertNull(cache.getCached("Daily Reward"));
        assertFalse(disk.containsKey("Daily Reward"),
                "unbalanced CS poison must be removed from the permanent cache");
    }

    @Test
    void eachHudLocationTranslatesOnceWhileNumberVariantsReuseTheCache() {
        List<List<String>> seenBatches = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                throw new AssertionError("single request not expected");
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenBatches.add(new ArrayList<>(texts));
                List<TranslationResult> out = new ArrayList<>();
                for (String text : texts) out.add(new TranslationResult(text
                        .replace("Village", "村莊")
                        .replace("Forest", "森林")
                        .replace("Hub", "樞紐")
                        .replace("Park", "公園")
                        .replace("Mana", "魔力"), "en"));
                return out;
            }
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);
        String village = "§62,525/2,150     §7 §bVillage     §b1,605/1,605 Mana";
        String forest = "§61,900/2,150     §7 §bForest     §b1,100/1,605 Mana";
        String hub = "§61,700/2,150     §7 §bHub     §b900/1,605 Mana";
        String park = "§61,500/2,150     §7 §bPark     §b800/1,605 Mana";

        cache.requestBatched(village);
        cache.flushBatch();
        cache.flushBatch();
        cache.requestBatched(forest);
        cache.flushBatch();
        cache.flushBatch();
        cache.requestBatched(hub);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(3, seenBatches.size());
        assertTrue(seenBatches.get(0).get(0).contains("Village"));
        assertTrue(seenBatches.get(1).get(0).contains("Forest"));
        assertTrue(seenBatches.get(2).get(0).contains("Hub"));
        assertTrue(seenBatches.get(2).get(0).contains("Mana"), "constant word must stay translatable");

        assertNull(cache.getCached(park), "a new location must not be filled back as English");
        cache.requestBatched(park);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(4, seenBatches.size(), "each distinct location costs one translation");
        assertTrue(cache.getCached(park).contains("公園"));
        String changedNumbers = park.replace("1,500", "1,499").replace("800", "799");
        cache.requestBatched(changedNumbers);
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(4, seenBatches.size(), "number-only changes still cost zero requests");
    }

    @Test
    void locationOnlyHudTranslatesEveryDistinctLocationExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        Translator fake = (text, targetLang) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("Village", "村莊")
                    .replace("Forest", "森林")
                    .replace("Hub", "樞紐")
                    .replace("Park", "公園"), "en");
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);

        String village = "§7 §bVillage§u";
        String forest = "§7 §2Forest§u";
        String hub = "§7 §eHub§u";
        String park = "§7 §aPark§u";

        cache.requestAsync(village);
        cache.requestAsync(forest);
        cache.requestAsync(hub);

        assertEquals(3, calls.get());
        assertTrue(cache.getCached(village).contains("村莊"),
                "known locations keep their individual translated static template");
        assertTrue(cache.getCached(forest).contains("森林"));
        assertNull(cache.getCached(park));

        cache.requestAsync(park);
        assertEquals(4, calls.get());
        assertTrue(cache.getCached(park).contains("公園"));
        cache.requestAsync(park);
        assertEquals(4, calls.get(), "the same location must never be bought twice");
    }

    @Test
    void semanticLocationKeyWinsOverAConflictingStyledAlias() {
        Map<String, String> disk = new java.util.HashMap<>();
        String source = "§7\ue067 §2Forest §b100 Mana";
        disk.put("⟦MT0⟧ Forest ⟦MT1⟧ Mana", "⟦MT0⟧ 森林 ⟦MT1⟧ 魔力");
        disk.put("§7⟦MT0⟧ §2Forest §b⟦MT1⟧ Mana", "§7⟦MT0⟧ §2錯誤地名 §b⟦MT1⟧ 魔力");
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));

        String displayed = cache.getCached(source);
        assertTrue(displayed.contains("森林"), displayed);
        assertFalse(displayed.contains("錯誤地名"), displayed);
    }

    @Test
    void incompleteEnglishLocationCacheIsPurgedAndTranslatedAgain() {
        Map<String, String> disk = new java.util.HashMap<>();
        String source = "§7\ue067 §2Forest §b100 Mana";
        String semanticKey = "⟦MT0⟧ Forest ⟦MT1⟧ Mana";
        disk.put(semanticKey, "⟦MT0⟧ Forest ⟦MT1⟧ 魔力");
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Forest", "森林").replace("Mana", "魔力"), "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        assertNull(cache.getCached(source));
        assertFalse(disk.containsKey(semanticKey), "poisoned half-translation is removed on read");
        cache.requestAsync(source);
        assertEquals(1, calls.get());
        assertTrue(cache.getCached(source).contains("森林"));
    }

    @Test
    void changingStatusesRemainSeparateTranslatableKeys() {
        AtomicInteger calls = new AtomicInteger();
        Translator fake = (text, targetLang) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("Overflow", "溢流")
                    .replace("Charging", "充能中")
                    .replace("Ready", "就緒"), "en");
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);

        cache.requestAsync("§b300/400 Overflow");
        cache.requestAsync("§b301/400 Charging");
        cache.requestAsync("§b302/400 Ready");

        assertEquals(3, calls.get(), "statuses/skills are content, not interchangeable location data");
        assertTrue(cache.getCached("§b399/400 Overflow").contains("溢流"));
        assertTrue(cache.getCached("§b399/400 Charging").contains("充能中"));
        assertTrue(cache.getCached("§b399/400 Ready").contains("就緒"));
    }

    @Test
    void pureClockChangesNeverReachTheTranslator() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync(" §711:20pm §b☽§v");
        cache.requestAsync(" §711:30pm §b☽§v");
        cache.requestAsync(" §712:00am §b☽§v");

        assertEquals(0, calls.get(), "time/style/icon-only lines contain nothing translatable");
        assertEquals(0, cache.pendingCount());
    }

    @Test
    void debugLogShowsOnlyCanonicalRequestsThatActuallyReachedTheBackend() {
        AtomicInteger calls = new AtomicInteger();
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);
        cache.setDebugLog("Google", debug);

        cache.requestAsync("Mana Cost: 99");
        cache.requestAsync("Mana Cost: 124"); // canonical cache hit
        cache.requestAsync(" §711:20pm §b☽§v"); // untranslatable clock

        assertEquals(1, calls.get());
        List<TranslationDebugLog.Entry> entries = debug.snapshot(10);
        assertEquals(1, entries.size(), "cache hits and filtered clocks must not appear");
        assertEquals("Mana Cost: ⟦MT0⟧", entries.get(0).text());
        assertEquals("T:Mana Cost: ⟦MT0⟧", entries.get(0).translation(),
                "completed debug rows should show the backend result beside the source");
        assertEquals(TranslationDebugLog.Status.SUCCESS, entries.get(0).status());
        assertEquals("Google", entries.get(0).engine());
    }

    @Test
    void debugLogPairsEveryBatchedSourceWithItsOwnTranslation() {
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        long id = debug.submitted("AI", List.of("One", "Two"));
        debug.completed(id, List.of("一", "二"), List.of(
                TranslationDebugLog.Status.SUCCESS,
                TranslationDebugLog.Status.FALLBACK));

        List<TranslationDebugLog.Entry> entries = debug.snapshot(10);
        assertEquals("Two", entries.get(0).text());
        assertEquals("二", entries.get(0).translation());
        assertEquals(TranslationDebugLog.Status.FALLBACK, entries.get(0).status());
        assertEquals("One", entries.get(1).text());
        assertEquals("一", entries.get(1).translation());
        assertEquals(TranslationDebugLog.Status.SUCCESS, entries.get(1).status());
    }

    @Test
    void rateLimitedBatchMarksEveryDebugRowAs429() {
        Translator rateLimited = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang)
                    throws TranslationException {
                throw new TranslationException("provider wrapper",
                        new java.io.IOException("HTTP 429: too many requests"));
            }

            @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
                    throws TranslationException {
                throw new TranslationException("provider wrapper",
                        new java.io.IOException("HTTP 429: too many requests"));
            }
        };
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        TranslationCache cache = new TranslationCache(rateLimited, "zh-TW", DIRECT, 100);
        cache.setDebugLog("AI", debug);

        cache.warmBatch(List.of("First item", "Second item"));

        List<TranslationDebugLog.Entry> entries = debug.snapshot(10);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry ->
                entry.status() == TranslationDebugLog.Status.RATE_LIMITED));
        assertTrue(entries.stream().allMatch(entry ->
                "429 rate limit".equals(entry.failureReason())));
    }

    @Test
    void debugFailurePrefersBackendReasonThenInfersShapeDamage() {
        Translator backendReason = (text, targetLang) ->
                new TranslationResult("", null, false, "anchor/order damaged");
        TranslationDebugLog explicitDebug = new TranslationDebugLog(() -> true);
        TranslationCache explicit = new TranslationCache(backendReason, "zh-TW", DIRECT, 100);
        explicit.setDebugLog("AI", explicitDebug);
        explicit.translateBlocking("Visible item");
        assertEquals("anchor/order damaged", explicitDebug.snapshot(1).get(0).failureReason());

        Translator damagedParagraph = (text, targetLang) ->
                new TranslationResult("翻譯成同一行", null);
        TranslationDebugLog inferredDebug = new TranslationDebugLog(() -> true);
        TranslationCache inferred = new TranslationCache(damagedParagraph, "zh-TW", DIRECT, 100);
        inferred.setDebugLog("AI", inferredDebug);
        inferred.translateBlocking("First paragraph\nSecond paragraph");
        assertEquals("paragraph lost", inferredDebug.snapshot(1).get(0).failureReason());
    }

    @Test
    void inFlightLocationRequestsStayBoundToTheirOwnSemanticKeys() {
        List<String> sent = new ArrayList<>();
        Translator fake = (text, targetLang) -> {
            sent.add(text);
            return new TranslationResult("\u8b6f:" + text
                    .replace("Village", "\u6751\u838a")
                    .replace("Forest", "\u68ee\u6797")
                    .replace("Hub", "\u6a1e\u7d10")
                    .replace("Mana", "\u9b54\u529b"), "en");
        };
        Map<String, String> disk = new java.util.HashMap<>();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(
                fake, "zh-TW", queue::add, 100, 10_000L, () -> 0L, inlineStore(disk));

        String village = hudLine("Village", "2,525", "2,150", "1,605", "1,605");
        String forest = hudLine("Forest", "1,900", "2,150", "1,100", "1,605");
        String hub = hudLine("Hub", "1,700", "2,150", "900", "1,605");
        String park = hudLine("Park", "1,500", "2,150", "800", "1,605");

        cache.requestAsync(village);
        cache.requestAsync(forest);
        cache.requestAsync(hub);
        assertEquals(3, queue.size());

        queue.removeFirst().run();

        assertEquals(1, sent.size());
        assertTrue(cache.getCached(village).contains("\u6751\u838a"));
        assertNull(cache.getCached(park),
                "one location response must never populate a different location key");
    }

    @Test
    void locationTierTwoCopyUsesTheSameStaticSlotLayout() {
        AtomicInteger calls = new AtomicInteger();
        Translator fake = (text, targetLang) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("Village", "\u6751\u838a")
                    .replace("Forest", "\u68ee\u6797")
                    .replace("Hub", "\u6a1e\u7d10")
                    .replace("Mana", "\u9b54\u529b"), "en");
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);

        String village = hudLine("Village", "2,525", "2,150", "1,605", "1,605");
        String forest = hudLine("Forest", "1,900", "2,150", "1,100", "1,605");
        String hub = hudLine("Hub", "1,700", "2,150", "900", "1,605");
        String recoloredHub = hudLine("a", "8", "e", "Hub", "1,234", "2,150", "777", "1,605");

        cache.requestAsync(village);
        cache.requestAsync(forest);
        cache.requestAsync(hub);

        String served = cache.getCached(recoloredHub);
        assertTrue(served != null, "the recoloured variant should hit the de-styled tier");
        assertFalse(served.contains("\u27e6MT"),
                "tier-2 values must not leak bare placeholder tokens to the screen: " + served);
        assertTrue(served.contains("\u6a1e\u7d10"), served);
        assertTrue(served.contains("1,234"), served);
        assertTrue(served.contains("\u9b54\u529b"), served);
        assertEquals(3, calls.get(), "the recoloured variant must not issue a fourth request");
    }

    @Test
    void mojibakeDiskHitIsDiscardedAndRetranslated() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("hi", "\u83F4\uF8F0\u8782\uFF7D");
        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        assertNull(cache.getCached("hi"));
        assertFalse(disk.containsKey("hi"));

        assertEquals("T:hi", cache.translateBlocking("hi"));
        assertEquals(1, calls.get());
        assertEquals("T:hi", disk.get("hi"));
    }

    @Test
    void asyncCallbackFiresWithTranslation() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertEquals(List.of("T:hi"), got);
    }

    @Test
    void asyncCallbackFiresImmediatelyWhenAlreadyCached() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);
        cache.translateBlocking("hi"); 
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertEquals(List.of("T:hi"), got);
        assertEquals(1, calls.get(), "cached callback must not re-translate");
    }

    @Test
    void duplicateAlwaysAsyncRequestsShareOneBackendCallAndBothCallbacksFire() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);
        List<String> got = new ArrayList<>();

        cache.translateAsyncAlways("hi", got::add);
        cache.translateAsyncAlways("hi", got::add);

        assertTrue(cache.isPending("hi"));
        assertEquals(1, queue.size());
        queue.poll().run();

        assertEquals(List.of("T:hi", "T:hi"), got);
        assertEquals(1, calls.get());
        assertFalse(cache.isPending("hi"));
    }

    @Test
    void cachedAlwaysAsyncReturnsImmediatelyWithoutQueueing() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);
        cache.translateBlocking("hi");
        List<String> got = new ArrayList<>();

        cache.translateAsyncAlways("hi", got::add);

        assertEquals(List.of("T:hi"), got);
        assertEquals(0, queue.size());
        assertEquals(1, calls.get());
    }

    @Test
    void asyncCallbackNotFiredOnFailure() {
        Translator failing = (text, target) -> {
            throw new TranslationException("boom");
        };
        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertTrue(got.isEmpty(), "callback must not fire when translation fails");
    }

    // ---- colour-stripped second tier (⟦CS#⟧-marked multi-colour lines) ----

    @Test
    void colourStrippedTierServesRecolouredVariantAsPlainText() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        TranslationCache cache = new TranslationCache(
                countingStyleSafeUpper(calls), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        cache.requestAsync("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧");
        assertEquals(1, calls.get());

        // Same words, colour-run structure shifted (gradient/animation frame): new marked key.
        String variant = "⟦CS1⟧Hello⟦/CS1⟧ ⟦CS0⟧World⟦/CS0⟧";
        String served = cache.getCached(variant);
        assertTrue(TextFilter.isStyleFallback(served));
        assertEquals("T:Hello World", TextFilter.stripFormatting(served));
        assertEquals(1, calls.get(), "style topology must not duplicate semantic AI work");
    }

    @Test
    void alternatingScoreboardStyleTopologiesNeitherRequestAgainNorRewriteBitsWording() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache((text, target) -> {
            int call = calls.incrementAndGet();
            String bits = call == 1 ? "比特" : call % 2 == 0 ? "碎幣" : "點數";
            return new TranslationResult(text.replace("Purse", "錢包").replace("Bits", bits), "en");
        }, "zh-TW", DIRECT, 100);
        // Production AI caches have this gate. Before the regression fix it made every
        // missing CS topology launch a presentation-only request that bypassed ChurnGuard.
        cache.setProvisionalRetryGate(() -> true);

        String plain = "Purse: 12,988 ⟦PB0⟧ Bits: 450";
        assertEquals("錢包: 12,988 ⟦PB0⟧ 比特: 450", cache.translateBlocking(plain));

        String first = "⟦CS0⟧Purse: ⟦/CS0⟧⟦CS1⟧12,988⟦/CS1⟧"
                + " ⟦PB0⟧ ⟦CS2⟧Bits: ⟦/CS2⟧⟦CS3⟧450⟦/CS3⟧";
        String second = "⟦CS4⟧Purse: 12,988⟦/CS4⟧"
                + " ⟦PB0⟧ ⟦CS5⟧Bits: 450⟦/CS5⟧";
        for (int i = 0; i < 20; i++) {
            String source = i % 2 == 0 ? first : second;
            String rendered = cache.getCached(source);
            assertTrue(TextFilter.isStyleFallback(rendered));
            assertEquals("錢包: 12,988 ⟦PB0⟧ 比特: 450",
                    TextFilter.stripFormatting(rendered));
        }

        assertEquals(1, calls.get(),
                "animated colour topology is presentation state, not new AI work");
        assertEquals("錢包: 99 ⟦PB0⟧ 比特: 7",
                cache.getCached("Purse: 99 ⟦PB0⟧ Bits: 7"),
                "presentation variants must never overwrite the canonical semantic wording");
    }

    @Test
    void explicitStyleResponseCannotOverwriteAnExistingFinalSemanticTranslation() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache((text, target) -> {
            int call = calls.incrementAndGet();
            return new TranslationResult(text.replace("Bits", call == 1 ? "比特" : "點數"), "en");
        }, "zh-TW", DIRECT, 100);
        cache.setProvisionalRetryGate(() -> true);

        assertEquals("比特: 450", cache.translateBlocking("Bits: 450"));
        String marked = "⟦CS0⟧Bits: ⟦/CS0⟧⟦CS1⟧450⟦/CS1⟧";
        cache.requestCoalescedExactStyle(marked, ignored -> { }, true);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(2, calls.get(), "the explicit exact-style request did reach the backend");
        assertEquals("比特: 99", cache.getCached("Bits: 99"),
                "a style-only answer with different wording cannot replace final meaning");
    }

    @Test
    void colourStrippedTierSubstitutesCurrentNumbers() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingStyleSafeUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧Kill streak⟦/CS0⟧ ⟦CS1⟧5⟦/CS1⟧");
        assertEquals(1, calls.get());

        // Number changed AND the colour boundaries moved: still one cached translation.
        String variant = "⟦CS0⟧Kill⟦/CS0⟧ ⟦CS1⟧streak 10⟦/CS1⟧";
        String served = cache.getCached(variant);
        assertTrue(TextFilter.isStyleFallback(served));
        assertEquals("T:Kill streak 10", TextFilter.stripFormatting(served));
        assertEquals(1, calls.get(), "current values restore without buying a style-only request");
    }

    @Test
    void bareCsResidueInValueSkipsColourStrippedCopy() {
        // A translator ate the ⟦⟧ brackets but kept the "CS1" body: poison for a plain entry.
        Translator markerEater = (text, target) -> new TranslationResult("你好 CS1 世界", null);
        TranslationCache cache = new TranslationCache(markerEater, "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧");
        assertNull(cache.getCached("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧"),
                "a marker-damaged response is never cached or displayed");
        assertNull(cache.getCached("⟦CS0⟧Hello World⟦/CS0⟧"),
                "a residue-carrying value must not seed the stripped tier");
    }

    @Test
    void semanticTextOutsideCompleteCsPairsIsRejected() {
        String marked = "\u27E6CS0\u27E7Hello\u27E6/CS0\u27E7 \u27E6CS1\u27E7World\u27E6/CS1\u27E7";
        Translator boundaryDrift = (text, target) -> new TranslationResult(
                "\u27E6CS0\u27E7\u4F60\u597D\u27E6/CS0\u27E7\u8DD1\u51FA\u6A19\u8A18"
                        + "\u27E6CS1\u27E7\u4E16\u754C\u27E6/CS1\u27E7", "en");
        TranslationCache cache = new TranslationCache(boundaryDrift, "zh-TW", DIRECT, 100);

        cache.requestAsync(marked);

        assertNull(cache.getCached(marked),
                "semantic prose outside a complete CS pair must never enter the style cache");
    }

    @Test
    void letterlessStrippedSourceSkipsColourStrippedCopy() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧123⟦/CS0⟧ ⟦CS1⟧456⟦/CS1⟧");
        assertEquals(1, calls.get());

        assertNull(cache.getCached("⟦CS0⟧123 456⟦/CS0⟧"),
                "a pure-number skeleton must not be stored as a stripped copy");
    }

    @Test
    void colourStrippedCopyIsReachableThroughFallbackChain() {
        AtomicInteger aiCalls = new AtomicInteger();
        TranslationCache ai = new TranslationCache(countingStyleSafeUpper(aiCalls), "zh-TW", DIRECT, 100);
        AtomicInteger googleCalls = new AtomicInteger();
        TranslationCache google = new TranslationCache(countingUpper(googleCalls), "zh-TW", DIRECT, 100);
        google.setFallback(ai); // the Google cache consults the AI cache on miss

        ai.requestAsync("⟦CS0⟧Kill streak⟦/CS0⟧ ⟦CS1⟧5⟦/CS1⟧");
        assertEquals(1, aiCalls.get());

        String served = google.getCached(
                "⟦CS0⟧Kill⟦/CS0⟧ ⟦CS1⟧streak 10⟦/CS1⟧");
        assertTrue(TextFilter.isStyleFallback(served));
        assertEquals("T:Kill streak 10", TextFilter.stripFormatting(served));
        assertEquals(0, googleCalls.get());
    }

    // ---- de-styled tier: literal § colour codes and centring padding ----

    @Test
    void csProjectionKeepsSegmentAlignmentWithoutPersistingLiteralColours() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Hello", "你好").replace("World", "世界"), "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        String first = "⟦CS0⟧§eHello⟦/CS0⟧ ⟦CS1⟧§aWorld⟦/CS1⟧";
        String recoloured = "⟦CS0⟧§bHello⟦/CS0⟧ ⟦CS1⟧§cWorld⟦/CS1⟧";

        cache.requestAsync(first);

        assertEquals("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", cache.getCached(recoloured),
                "the current component receives marker alignment without the first writer's colours");
        assertEquals("你好 世界", cache.getCached("Hello World"),
                "the cross-surface semantic row remains marker- and colour-free");
        assertEquals(1, calls.get(), "a pure recolour reuses the same style topology");
    }

    @Test
    void semanticOnlyLegacyHitFillsStyleTopologyWithoutAnotherRequest() {
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("Hello World", "你好 世界");
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Hello", "你好").replace("World", "世界"), "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";

        String served = cache.getCached(marked);
        assertTrue(TextFilter.isStyleFallback(served));
        assertEquals("你好 世界", TextFilter.stripFormatting(served));
        assertEquals(0, calls.get());
    }

    @Test
    void sectionCodeVariantHitsTheSharedSemanticTranslation() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("§e連續擊殺 §65 §e隻！");
        assertEquals(1, calls.get());

        // Same words and number, every literal § colour code changed by the server.
        String served = cache.getCached("§a連續擊殺 §b5 §a隻！");
        assertEquals("T:連續擊殺5隻！", served,
                "the semantic tier must serve the recoloured line without leaking old colours");
        assertFalse(served.contains("§"),
                "render styling comes from the current component, never the first cache writer");
        assertEquals(1, calls.get(), "a § recolour must cost zero translator calls");
    }

    @Test
    void innerPaddingVariantHitsDeStyledTier() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("§eWeather:  §aSunny");
        assertEquals(1, calls.get());

        // The server re-centred the line: only the inner padding run changed.
        assertEquals("T:Weather:      Sunny", cache.getCached("§eWeather:      §aSunny"));
        assertEquals(1, calls.get(), "a padding shift must cost zero translator calls");
    }

    @Test
    void wideHudColumnGapAndLiveNumberVariantsReuseOneSemanticTranslation() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("Defense", "防禦")
                    .replace("Mana", "魔力"), "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        String wide = "2,556/2,131❤          Defense 1,042          Mana 1,707/1,707";
        String compact = "3,000/3,000❤  Defense 1,500  Mana 2,000/2,000";

        cache.requestAsync(wide);
        assertEquals(1, calls.get());

        String served = cache.getCached(compact);
        assertTrue(served.contains("3,000/3,000❤"));
        assertTrue(served.contains("防禦1,500"));
        assertTrue(served.contains("魔力2,000/2,000"),
                "the semantic hit must restore this frame's live values, not the first HUD frame's");
        assertTrue(java.util.regex.Pattern.compile("1,500\\s+魔力").matcher(served).find(),
                "a long HUD column gap must not be tightened into a visually joined label");
        assertFalse(served.contains("2,556"));
        assertFalse(served.contains("1,042"));
        assertEquals(1, calls.get(),
                "column padding and live numbers are presentation variants, not new requests");
    }

    @Test
    void movedLayoutSlotIsRejectedBeforeItCanPoisonTheSharedTemplate() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(
                    "夏季商店促銷 - ⟦WS0⟧ 至⟦MT0⟧折 - ⟦MT1⟧", "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        String source = "SUMMER STORE SALE - UP TO 25% OFF -     3h";

        cache.requestAsync(source);

        assertEquals(1, calls.get());
        assertNull(cache.getCached(source),
                "a result that crosses a fixed WS column boundary must never be cached");
    }

    @Test
    void serverShardVariantsReuseOneTranslationAndRestoreCurrentInstance() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("SkyBlock Hub", "SkyBlock中心")
                    .replace("Players", "玩家")
                    .replace("Server", "伺服器"), "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);

        String first = "SkyBlock Hub #11  Players: 48/60  Server: mega33A";
        String second = "SkyBlock Hub #13  Players: 44/60  Server: mega4E";
        cache.requestAsync(first);
        assertEquals("SkyBlock中心 #13  玩家: 44/60  伺服器: mega4E",
                cache.getCached(second));
        assertEquals(1, calls.get(), "different shard ids must not buy another translation");
    }

    @Test
    void reorderedDynamicTokensBuildAReusablePlainRowFromStyledText() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(
                    "⟦CS1⟧魔力 ⟦MT1⟧⟦/CS1⟧ "
                            + "⟦CS0⟧傷害 ⟦MT0⟧⟦/CS0⟧", "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        String styled = "⟦CS0⟧Damage 10⟦/CS0⟧ ⟦CS1⟧Mana 20⟦/CS1⟧";

        cache.requestAsync(styled);
        assertEquals("魔力40傷害30", cache.getCached("Damage 30 Mana 40"));
        assertEquals(1, calls.get(),
                "a legal target-language reorder must still seed the style-independent cache");
    }

    @Test
    void dynamicValueLeavingItsStyleSegmentIsRejected() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(
                    "⟦MT0⟧⟦CS0⟧傷害⟦/CS0⟧⟦CS1⟧⟦/CS1⟧", "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        String source = "⟦CS0⟧Damage⟦/CS0⟧ ⟦CS1⟧10⟦/CS1⟧";

        cache.requestAsync(source);

        assertEquals(1, calls.get());
        assertNull(cache.getCached(source));
    }

    @Test
    void damagedResponsesNeverLearnKeepOriginalAndRetryAfterExponentialBackoff() {
        AtomicInteger calls = new AtomicInteger();
        Translator broken = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("傷害 CS0 十", "en"); // markers eaten: damaged CS shape
        };
        long[] now = {0L};
        TranslationCache cache = new TranslationCache(
                broken, "zh-TW", DIRECT, 100, 1_000L, () -> now[0], null);
        String marked = "⟦CS0⟧Damage 10⟦/CS0⟧";

        cache.requestAsync(marked);                    // attempt 1 → 1s backoff
        cache.requestAsync(marked);                    // inside backoff: suppressed
        assertEquals(1, calls.get());
        now[0] = 1_000L;
        cache.requestAsync(marked);                    // attempt 2 → 2s backoff
        now[0] = 3_000L;
        cache.requestAsync(marked);                    // attempt 3 → 4s backoff
        assertEquals(3, calls.get());
        assertNull(cache.getCached(marked),
                "three damaged responses must NOT become a durable keep-original");

        now[0] = 7_000L;
        cache.requestAsync(marked);                    // attempt 4: never permanently poisoned
        assertEquals(4, calls.get(), "an expired backoff must buy a fresh retry forever");
        now[0] = 8_000L;                               // attempt-4 backoff (8s) still active
        cache.requestAsync(marked);
        assertEquals(4, calls.get(), "retries stay exponentially spaced, not per-frame");
    }

    @Test
    void emptyResponsesAreTrueFailuresAndKeepRetryingWithoutSentinel() {
        AtomicInteger calls = new AtomicInteger();
        Translator broken = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("", "en");
        };
        long[] now = {0L};
        Map<String, String> disk = new java.util.HashMap<>();
        TranslationCache cache = new TranslationCache(
                broken, "zh-TW", DIRECT, 100, 1_000L, () -> now[0], inlineStore(disk));

        for (int i = 0; i < 5; i++) {
            cache.requestAsync("Mystic Blade");
            now[0] += 600_000L;                        // beyond any capped backoff window
        }
        assertEquals(5, calls.get(), "every expired window earns a real retry");
        assertNull(cache.getCached("Mystic Blade"));
        assertTrue(disk.isEmpty(), "no sentinel or value may be persisted for true failures");
    }

    @Test
    void mixedCsAndSectionCodeVariantHits() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingStyleSafeUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧§eHello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧");
        assertEquals(1, calls.get());

        // CS run structure AND the baked-in § code both changed.
        String variant = "⟦CS0⟧§bHello World⟦/CS0⟧";
        String served = cache.getCached(variant);
        assertEquals("T:Hello World", TextFilter.stripFormatting(served),
                "literal colour codes stay excluded during semantic style projection");
        assertTrue(TextFilter.isStyleFallback(served));
        assertEquals(1, calls.get());
    }

    @Test
    void sectionCodeAndNumberVariantSubstitutesCurrentNumber() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("§e連續擊殺 §65 §e隻！");
        assertEquals(1, calls.get());

        // Colours changed AND the count went 5 → 10. The semantic value must restore
        // the current number without retaining any colour code from the first writer.
        assertEquals("T:連續擊殺10隻！", cache.getCached("§a連續擊殺 §b10 §a隻！"));
        assertEquals(1, calls.get(), "a colour+number variant must not re-buy the translation");
    }

    // ---- discard→rematerialise disk-append loop (the 30,892-line tab-header incident) ----

    @Test
    void sectionCodeGluedCjkValueIsServedNotDiscarded() {
        Map<String, String> disk = new java.util.HashMap<>();
        String key = "§f   §d§lSB YEAR 500 §8| §b§lLOADOUTS";
        String value = "§d§lSB年500 §8| §b§l裝備包";
        disk.put(key, value);
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        assertEquals(value, cache.getCached(key),
                "the §l code letter must not fake a half-transliteration verdict");
        assertTrue(disk.containsKey(key), "the disk entry must survive the read");
    }

    @Test
    void readRejectedValueIsNeverRematerialisedToDisk() {
        // The d141b32 live loop: the read side discards a poison raw entry, then the
        // templated tier restores + rematerialises it through the WEAKER one-arg gate —
        // one disk append per frame, forever. The symmetric write gate must keep the
        // disk write count at ZERO while still returning the restored value upstream.
        Map<String, String> disk = new java.util.HashMap<>();
        AtomicInteger diskWrites = new AtomicInteger();
        PersistentStore countingStore = new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) { diskWrites.incrementAndGet(); disk.put(key, value); }
            @Override public void clear() { disk.clear(); }
            @Override public void remove(String key) { disk.remove(key); }
        };
        // Raw entry the read side genuinely rejects ("mb", a proper suffix of CLIMB, glued to 年)…
        disk.put("CLIMB 500", "爬升 mb年500");
        // …and a templated sibling that PASSES the read check ("mb" not glued), but whose
        // restore re-glues it (tightenCjkSpacing treats 'b' as a number-suffix before CJK).
        disk.put("CLIMB ⟦MT0⟧", "爬升 mb 年 ⟦MT0⟧");
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, countingStore);

        for (int frame = 0; frame < 2; frame++) {
            assertEquals("爬升 mb年500", cache.getCached("CLIMB 500"),
                    "the restored value is still returned (upstream decide() re-judges it)");
        }
        assertEquals(0, diskWrites.get(),
                "a value the read side rejects must never be written back by the lookup path");
    }

    @Test
    void identityEchoValueDoesNotSeedDeStyledCopy() {
        // GoogleFreeTranslator's preservesTokens guard returns its INPUT on failure: an
        // untranslated echo with pristine markers. De-styled it is clean English (no CS
        // residue to catch), so without the echo guard it would seed a copy whose value
        // IS the source — every styled variant would then "hit" fake English forever.
        Translator echoing = (text, target) -> new TranslationResult(text, null);
        TranslationCache cache = new TranslationCache(echoing, "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧"); // CS-marked echo
        cache.requestAsync("§eKill streak §65 §emobs!");          // §-literal echo, with number

        assertNull(cache.getCached("⟦CS1⟧Hello⟦/CS1⟧ ⟦CS0⟧World⟦/CS0⟧"),
                "a recoloured CS variant must MISS: the echo built no de-styled copy");
        assertNull(cache.getCached("§aKill streak §b5 §amobs!"),
                "a §-recoloured variant must MISS: the echo built no de-styled copy");
    }

    @Test
    void threeContentEchoesLearnDurableKeepOriginalAndManualRetranslateUnlocks() {
        AtomicInteger calls = new AtomicInteger();
        Translator echoing = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text, "en");
        };
        Map<String, String> disk = new java.util.HashMap<>();
        PersistentStore store = inlineStore(disk);
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        TranslationCache cache = new TranslationCache(
                echoing, "zh-TW", DIRECT, 100, 0L, () -> 0L, store);
        cache.setDebugLog("AI", debug);

        cache.requestAsync("Rezzus");
        cache.requestAsync("Rezzus");
        cache.requestAsync("Rezzus");
        assertEquals(3, calls.get());
        assertEquals("Rezzus", cache.getCached("Rezzus"),
                "the third identity echo becomes a negative-cache hit");
        assertEquals(TranslationDebugLog.Status.KEEP_ORIGINAL,
                debug.snapshot(1).get(0).status());

        cache.requestAsync("Rezzus");
        assertEquals(3, calls.get(), "learned NPC names never cross the backend again");

        TranslationCache restarted = new TranslationCache(
                echoing, "zh-TW", DIRECT, 100, 0L, () -> 0L, store);
        assertEquals("Rezzus", restarted.getCached("Rezzus"),
                "keep-original decisions survive a restart");
        restarted.requestAsync("Rezzus");
        assertEquals(3, calls.get());

        for (int i = 0; i < 5; i++) restarted.requestAsync("Welcome to the server");
        assertEquals("Welcome to the server", restarted.getCached("Welcome to the server"),
                "the third unusable content response must stop a sentence retry loop too");
        assertEquals(6, calls.get(), "attempts after the third echo stay local");

        restarted.invalidate("Welcome to the server");
        assertNull(restarted.getCached("Welcome to the server"),
                "individual retranslation removes a learned sentence decision");

        restarted.invalidate("Rezzus");
        assertNull(restarted.getCached("Rezzus"),
                "manual retranslation removes the learned keep-original decision");
    }

    // ---- R9: GT 暫代 (provisional) entries + AI redo on hit ----

    /** Inline provisional-aware store fake: records values AND the provisional flag. */
    private static PersistentStore provisionalStore(Map<String, String> disk, java.util.Set<String> prov) {
        return new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) { put(key, value, false); }
            @Override public void put(String key, String value, boolean provisional) {
                disk.put(key, value);
                if (provisional) prov.add(key); else prov.remove(key);
            }
            @Override public boolean isProvisional(String key) { return prov.contains(key); }
            @Override public void clear() { disk.clear(); prov.clear(); }
            @Override public void remove(String key) { disk.remove(key); prov.remove(key); }
            @Override public Map<String, String> provisionalEntries() {
                Map<String, String> out = new java.util.LinkedHashMap<>();
                for (String key : prov) {
                    String value = disk.get(key);
                    if (value != null) out.put(key, value);
                }
                return out;
            }
        };
    }

    @Test
    void fallbackResultsAreStoredProvisionalAndPlainResultsAreNot() {
        Map<String, String> disk = new java.util.HashMap<>();
        java.util.Set<String> prov = new java.util.HashSet<>();
        Translator mixed = (text, target) -> text.startsWith("A")
                ? new TranslationResult("GT:" + text, "en", true)  // dispatcher fell back
                : new TranslationResult("T:" + text, "en");         // plain backend (google keyspace)
        TranslationCache cache = new TranslationCache(
                mixed, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, provisionalStore(disk, prov));

        cache.requestAsync("Apple pie");
        cache.requestAsync("Berry pie");

        assertEquals("GT:Apple pie", cache.getCached("Apple pie"));
        assertTrue(prov.contains("Apple pie"), "a fallback product is stored PROVISIONAL");
        assertEquals("T:Berry pie", cache.getCached("Berry pie"));
        assertFalse(prov.contains("Berry pie"), "a plain backend product is stored FINAL");
    }

    @Test
    void provisionalHitTriggersAiRedoThatOverwritesUnmarksAndRebuildsCopies() {
        boolean[] aiUp = {false};
        AtomicInteger calls = new AtomicInteger();
        Translator dispatcher = (text, target) -> {
            calls.incrementAndGet();
            return aiUp[0] ? new TranslationResult("AI:" + text, "en")
                           : new TranslationResult("GT:" + text, "en", true);
        };
        Map<String, String> disk = new java.util.HashMap<>();
        java.util.Set<String> prov = new java.util.HashSet<>();
        TranslationCache cache = new TranslationCache(
                dispatcher, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, provisionalStore(disk, prov));
        cache.setProvisionalRetryGate(() -> aiUp[0]);

        cache.requestAsync("§eHello §aWorld");                    // AI down: GT stands in
        assertEquals(1, calls.get());
        assertEquals("GT:Hello World", cache.getCached("§eHello §aWorld"));
        assertEquals(1, calls.get(), "gate closed: a hit must not schedule a redo");
        assertTrue(prov.contains("Hello World"));

        aiUp[0] = true;                                           // AI recovered: gate open
        assertEquals("GT:Hello World", cache.getCached("§eHello §aWorld"),
                "the hit itself still serves the stand-in; the redo runs off the hit");
        assertEquals(2, calls.get(), "exactly ONE AI redo was scheduled by the hit");
        assertEquals("AI:Hello World", cache.getCached("§eHello §aWorld"), "AI overwrote");
        assertFalse(prov.contains("Hello World"), "the provisional mark is cleared");
        assertEquals("AI:Hello World", cache.getCached("§bHello §cWorld"),
                "the de-styled copy was rebuilt from the AI value (recoloured variant hit)");
        assertEquals(2, calls.get(), "no further redos once the value is final");
    }

    @Test
    void provisionalRedoBacksOffWhileTheAiStillFallsBack() {
        AtomicInteger calls = new AtomicInteger();
        Translator alwaysFallback = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("GT:" + text, "en", true);
        };
        long[] now = {0L};
        TranslationCache cache = new TranslationCache(
                alwaysFallback, "zh-TW", DIRECT, 100, 10_000L, () -> now[0], null);
        cache.setProvisionalRetryGate(() -> true); // gate open, but the dispatcher STILL falls back

        cache.requestAsync("Hello world");
        assertEquals(1, calls.get());

        cache.getCached("Hello world");            // hit → one redo attempt → still fallback → backoff
        assertEquals(2, calls.get());
        cache.getCached("Hello world");            // inside failedUntil: quiet
        cache.getCached("Hello world");
        assertEquals(2, calls.get(), "redo attempts must respect the failure backoff");
        assertEquals("GT:Hello world", cache.getCached("Hello world"), "the stand-in keeps serving");

        now[0] = 20_000L;                          // backoff expired: the next hit retries once
        cache.getCached("Hello world");
        assertEquals(3, calls.get());
    }

    @Test
    void provisionalAiSupplementKeepsRetryingAfterBackoffInsteadOfLockingProvisional() {
        AtomicInteger calls = new AtomicInteger();
        Translator alwaysFallback = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("GT:" + text, "en", true);
        };
        long[] now = {0L};
        TranslationCache cache = new TranslationCache(
                alwaysFallback, "zh-TW", DIRECT, 100, 1_000L, () -> now[0], null);
        cache.setProvisionalRetryGate(() -> true);

        cache.requestAsync("Summer Store Sale");       // initial fallback stand-in
        assertEquals(1, calls.get());

        long[] expiries = {1_000L, 3_000L, 7_000L, 15_000L}; // 1s+2s+4s+8s exponential
        for (int attempt = 0; attempt < expiries.length; attempt++) {
            cache.getCached("Summer Store Sale");      // hit schedules one supplement
            assertEquals(attempt + 2, calls.get());
            cache.getCached("Summer Store Sale");      // inside backoff: quiet
            assertEquals(attempt + 2, calls.get(), "backoff must throttle supplements");
            now[0] = expiries[attempt];
        }
        assertEquals(5, calls.get(),
                "the 4th supplement proves there is no permanent provisional lock");
        assertEquals("GT:Summer Store Sale", cache.getCached("Summer Store Sale"),
                "the stand-in keeps serving throughout");
    }

    @Test
    void provisionalStyledHitRetriesOnlyTheSemanticRow() {
        AtomicInteger calls = new AtomicInteger();
        Translator alwaysFallback = (text, target) -> {
            calls.incrementAndGet();
            int firstMarkerEnd = text == null ? -1 : text.indexOf('\u27E7');
            String translated = firstMarkerEnd < 0
                    ? "GT:" + text
                    : text.substring(0, firstMarkerEnd + 1) + "GT:" + text.substring(firstMarkerEnd + 1);
            return new TranslationResult(translated, "en", true);
        };
        TranslationCache cache = new TranslationCache(
                alwaysFallback, "zh-TW", DIRECT, 100, 0L, () -> 0L, null);
        cache.setProvisionalRetryGate(() -> true);
        String marked = "⟦CS0⟧Summer Store⟦/CS0⟧ ⟦CS1⟧Sale⟦/CS1⟧";

        cache.requestAsync(marked);
        assertEquals(1, calls.get());
        assertEquals("GT:Summer Store Sale", TextFilter.stripFormatting(cache.getCached(marked)));
        assertEquals(2, calls.get(),
                "the style projection must not launch a second supplement beside the semantic key");
    }

    @Test
    void finalSemanticIsNotDowngradedByProvisionalStyleResponse() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] fallback = {false};
        TranslationCache cache = new TranslationCache((text, target) -> {
            calls.incrementAndGet();
            return fallback[0]
                    ? new TranslationResult(text.replace("Summer", "GT Summer"), "en", true)
                    : new TranslationResult(text.replace("Summer", "AI Summer"), "en");
        },
                "zh-TW", DIRECT, 100, 10_000L, () -> 0L, null);
        cache.setProvisionalRetryGate(() -> true);

        cache.requestAsync("Summer Store Sale");
        assertEquals("AI Summer Store Sale", cache.getCached("Summer Store Sale"));

        fallback[0] = true;
        String marked = "⟦CS0⟧Summer Store⟦/CS0⟧ ⟦CS1⟧Sale⟦/CS1⟧";
        cache.requestCoalescedExactStyle(marked, ignored -> { }, true);
        cache.flushBatch();
        cache.flushBatch();
        for (int i = 0; i < 20; i++) assertNotNull(cache.getCached(marked));

        assertEquals("AI Summer Store Sale", cache.getCached("Summer Store Sale"),
                "a provisional styled fallback may not overwrite the final AI semantic row");
        assertEquals(2, calls.get(),
                "the explicitly requested GT style answer was discarded; ordinary render"
                        + " lookups must not launch any more presentation requests");
    }

    @Test
    void invalidateEvictsDeStyledCopiesAndProvisionalMarks() {
        // The R hotkey path: after invalidate NOTHING may keep serving the old value —
        // including the de-styled tier-2 copy (else the re-warm sees "cached" and never
        // re-buys, making the hotkey feel like a no-op) — and no provisional residue stays.
        Map<String, String> disk = new java.util.HashMap<>();
        java.util.Set<String> prov = new java.util.HashSet<>();
        Translator gt = (text, target) -> new TranslationResult("GT:" + text, "en", true);
        TranslationCache cache = new TranslationCache(
                gt, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, provisionalStore(disk, prov));

        cache.requestAsync("§eHello §aWorld");
        assertEquals("GT:Hello World", cache.getCached("§eHello §aWorld"));
        assertEquals("GT:Hello World", cache.getCached("§bHello §cWorld"), "semantic copy exists");
        assertTrue(prov.contains("Hello World"));
        assertFalse(prov.contains("§eHello §aWorld"), "styled aliases are never persisted");

        cache.invalidate("§eHello §aWorld");
        assertNull(cache.getCached("§eHello §aWorld"), "primary keys evicted");
        assertNull(cache.getCached("§bHello §cWorld"), "the de-styled tier-2 copy is evicted too");
        assertFalse(prov.contains("§eHello §aWorld"), "no provisional residue after invalidate");
        assertFalse(prov.contains("Hello World"), "the copy's mark is gone as well");
    }

    @Test
    void lruEvictsOldestBeyondMaxSize() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 2);

        cache.translateBlocking("a");
        cache.translateBlocking("b");

        cache.getCached("a");
        cache.translateBlocking("c"); 

        assertEquals(2, cache.size());
        assertEquals("T:a", cache.getCached("a"));
        assertNull(cache.getCached("b"), "b should have been evicted as least-recently-used");
        assertEquals("T:c", cache.getCached("c"));
    }

    @Test
    void plainCsProductNameIsNotMistakenForLegacyStyleMarkerResidue() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("今天玩 CS2", "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);

        cache.requestAsync("Play CS2 today");
        assertEquals("今天玩 CS2", cache.getCached("Play CS2 today"));
        cache.requestAsync("Play CS2 today");
        assertEquals(1, calls.get(), "a legitimate CS2 name must be cached, not retried forever");
    }

    @Test
    void gtFallbackBecomesVisibleOnlyAfterAiFailureAndRemainsNonFinal() {
        TranslationCache gt = new TranslationCache(
                (text, target) -> new TranslationResult(text.replace("Damage: ", "損壞："), "en"),
                "zh-TW", DIRECT, 100);
        assertEquals("損壞：209", gt.translateBlocking("Damage: 209"));

        TranslationCache ai = new TranslationCache(
                (text, target) -> { throw new TranslationException("AI unavailable"); },
                "zh-TW", DIRECT, 100, 1_000L, () -> 0L);
        ai.setFallback(gt, true);
        ai.setProvisionalRetryGate(() -> false);

        assertNull(ai.getCached("Damage: 209"));
        ai.requestAsync("Damage: 209");
        assertEquals("損壞：209", ai.getCached("Damage: 209"),
                "the configured GT fallback becomes visible after AI actually fails");
        assertNull(ai.getCachedFinal("Damage: 209"),
                "context-rich AI surfaces must keep the original until AI is final");
    }

    @Test
    void validatedAiContentFailureAlsoStartsGtFallback() {
        TranslationCache gt = new TranslationCache(
                (text, target) -> new TranslationResult("可見物品", "en"),
                "zh-TW", DIRECT, 100);
        TranslationCache ai = new TranslationCache(
                (text, target) -> new TranslationResult(
                        "", null, false, "anchor/order damaged"),
                "zh-TW", DIRECT, 100, 1_000L, () -> 0L);
        ai.setFallback(gt, true);
        ai.setProvisionalRetryGate(() -> false);

        ai.requestAsync("Visible item");
        gt.flushBatch();
        gt.flushBatch();

        assertTrue(ai.hasFailureState("Visible item"));
        assertTrue(ai.mayUseFallback("Visible item"));
        assertEquals("可見物品", ai.getCached("Visible item"),
                "a rejected AI payload must start the same GT fallback as a transport failure");
        assertNull(ai.getCachedFinal("Visible item"),
                "GT remains provisional while the AI result is structurally invalid");
    }

    @Test
    void dynamicSlotsCannotCrossProtectedParagraphRows() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache((text, target) -> {
            calls.incrementAndGet();
            assertTrue(text.contains("⟦MT0⟧"), text);
            assertTrue(text.contains("⟦MT1⟧"), text);
            return new TranslationResult(
                    "錢包：⟦MT1⟧ ⟦PB0⟧ 位元：⟦MT0⟧", "en");
        }, "zh-TW", DIRECT, 100);

        String source = "Purse: 690,364 ⟦PB0⟧ Bits: 450";
        assertNull(cache.translateBlocking(source));
        assertNull(cache.getCached(source));
        assertEquals(1, calls.get());
    }

    @Test
    void finalCoalescedCallbackSkipsFallbackAndDeliversAiSupplement() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache((text, target) -> {
            int call = calls.incrementAndGet();
            String translated = text.replace("Damage: ",
                    call == 1 ? "損壞：" : "傷害：");
            return new TranslationResult(translated, "en", call == 1);
        }, "zh-TW", DIRECT, 100);
        cache.setProvisionalRetryGate(() -> true);

        List<String> delivered = new ArrayList<>();
        cache.requestCoalescedFinal("Damage: 209", delivered::add);
        cache.flushBatch();
        cache.flushBatch();

        assertEquals(2, calls.get());
        assertEquals(List.of("傷害：209"), delivered,
                "the widget callback must never observe provisional GT wording");
    }

    // ---- v2 keep-original sentinel, failure ledger, and three-file separation ----

    @Test
    void threeEchoesMoveTheDurableIdentityDecisionIntoTheEngineCache() {
        AtomicInteger calls = new AtomicInteger();
        Translator echoing = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text, "en");
        };
        Map<String, String> aiDisk = new java.util.HashMap<>();
        Map<String, String> failures = new java.util.HashMap<>();
        PersistentStore aiStore = inlineStore(aiDisk);
        PersistentStore failureStore = inlineStore(failures);
        TranslationCache cache = new TranslationCache(
                echoing, "zh-TW", DIRECT, 100, 0L, () -> 0L, aiStore);
        cache.setFailureStore(failureStore);

        cache.requestAsync("Rezzus");
        cache.requestAsync("Rezzus");
        cache.requestAsync("Rezzus");
        assertEquals(3, calls.get());
        assertFalse(failures.containsKey("Rezzus"),
                "a confirmed identity is no longer a failure");
        assertEquals("\0MT_KEEP_ORIGINAL2", aiDisk.get("Rezzus"),
                "the terminal identity belongs to the engine's own cache");
        assertEquals("Rezzus", cache.getCached("Rezzus"));
        cache.requestAsync("Rezzus");
        assertEquals(3, calls.get(), "a confirmed identity cache hit makes no request");

        TranslationCache restarted = new TranslationCache(
                echoing, "zh-TW", DIRECT, 100, 0L, () -> 0L, aiStore);
        restarted.setFailureStore(failureStore);
        assertEquals("Rezzus", restarted.getCached("Rezzus"), "identity decisions survive a restart");
        restarted.requestAsync("Rezzus");
        assertEquals(3, calls.get());

        restarted.invalidate("Rezzus");
        assertNull(restarted.getCached("Rezzus"), "manual retranslation removes the identity entry");
        assertFalse(failures.containsKey("Rezzus"));
    }

    @Test
    void legacyKeepOriginalSentinelIsDroppedOnReadAndRetranslated() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("Iron Pickaxe", "\0MT_KEEP_ORIGINAL"); // v1 sentinel: may be old poison
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        assertNull(cache.getCached("Iron Pickaxe"),
                "a v1 sentinel is a miss, never a keep-original verdict");
        assertFalse(disk.containsKey("Iron Pickaxe"),
                "the possibly-poisoned v1 row is deleted the first time it is read");
        cache.requestAsync("Iron Pickaxe");
        assertEquals(1, calls.get(), "the line is bought again after the one-time unlock");
        assertEquals("T:Iron Pickaxe", cache.getCached("Iron Pickaxe"));
    }

    @Test
    void styleFallbackHitDoesNotSchedulePresentationOnlyWork() {
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("Hello World", "你好 世界");           // final semantic row, no projection yet
        AtomicInteger calls = new AtomicInteger();
        List<String> sent = new ArrayList<>();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            sent.add(text);
            return new TranslationResult(text.replace("Hello", "你好").replace("World", "世界"), "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));
        cache.setProvisionalRetryGate(() -> true);
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";

        String served = cache.getCached(marked);
        assertTrue(TextFilter.isStyleFallback(served),
                "the first frame still serves the semantic row as a style fallback");
        assertTrue(sent.isEmpty(),
                "generic render lookups must not buy an animated CS topology");

        String next = cache.getCached(marked);
        assertTrue(TextFilter.isStyleFallback(next));
        assertEquals("你好 世界", TextFilter.stripFormatting(next));
        assertEquals(0, calls.get(), "later frames keep reusing the semantic cache");
    }

    @Test
    void exactStyleCoalescingWaitsForProjectionInsteadOfCompletingWithPlainHit() {
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("Hello World", "你好 世界");
        AtomicInteger calls = new AtomicInteger();
        List<String> sent = new ArrayList<>();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            sent.add(text);
            return new TranslationResult(
                    text.replace("Hello", "你好").replace("World", "世界"), "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        java.util.concurrent.atomic.AtomicReference<String> delivered =
                new java.util.concurrent.atomic.AtomicReference<>();

        cache.requestCoalescedExactStyle(marked, delivered::set, true);

        assertNull(delivered.get(),
                "one-shot chat must not terminally consume the marker-free semantic hit");
        assertEquals(0, calls.get(), "the exact request remains coalesced until the settle queue flushes");
        for (int i = 0; i < 4; i++) cache.flushBatch();

        assertEquals(List.of(marked), sent, "the missing CS projection is translated exactly once");
        assertEquals("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", delivered.get());
        assertFalse(TextFilter.isStyleFallback(delivered.get()));
        assertEquals(1, calls.get());
    }

    @Test
    void gtStandInsPersistToTheGtFileAndAiFinalsToTheAiFile() {
        boolean[] aiUp = {false};
        AtomicInteger calls = new AtomicInteger();
        Translator dispatcher = (text, target) -> {
            calls.incrementAndGet();
            return aiUp[0] ? new TranslationResult("AI:" + text, "en")
                           : new TranslationResult("GT:" + text, "en", true);
        };
        Map<String, String> aiDisk = new java.util.HashMap<>();
        Map<String, String> gtDisk = new java.util.HashMap<>();
        TranslationCache cache = new TranslationCache(
                dispatcher, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(aiDisk));
        cache.setProvisionalStore(inlineStore(gtDisk));
        cache.setProvisionalRetryGate(() -> aiUp[0]);

        cache.requestAsync("Hello world");             // AI down: GT stands in
        assertEquals("GT:Hello world", cache.getCached("Hello world"),
                "the GT stand-in is displayed immediately");
        assertEquals("GT:Hello world", gtDisk.get("Hello world"),
                "the stand-in is persisted into the GT file");
        assertFalse(aiDisk.containsKey("Hello world"),
                "the ai-cache file carries only final AI wording");

        aiUp[0] = true;                                // AI recovered: hit schedules the redo
        cache.getCached("Hello world");
        assertEquals("AI:Hello world", cache.getCached("Hello world"),
                "the landed AI wording overrides the GT stand-in on screen");
        assertEquals("AI:Hello world", aiDisk.get("Hello world"));
        assertEquals(2, calls.get());
    }

    @Test
    void legacyMixedProvisionalRowsMigrateToTheGtFileOnWiring() {
        Map<String, String> aiDisk = new java.util.HashMap<>();
        java.util.Set<String> prov = new java.util.HashSet<>();
        aiDisk.put("Hello World", "GT:你好世界");       // old build: GT stand-in in ai-cache
        prov.add("Hello World");
        aiDisk.put("Iron Pickaxe", "鐵鎬");             // final AI row stays put
        Map<String, String> gtDisk = new java.util.HashMap<>();
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, provisionalStore(aiDisk, prov));

        cache.setProvisionalStore(inlineStore(gtDisk));

        assertEquals("GT:你好世界", gtDisk.get("Hello World"),
                "mixed-in provisional rows move to the GT file once");
        assertFalse(aiDisk.containsKey("Hello World"));
        assertEquals("鐵鎬", aiDisk.get("Iron Pickaxe"), "final rows are not touched");
    }

    @Test
    void temporaryFailureMarksSurviveRestartAndRetryAfterExpiry() {
        AtomicInteger calls = new AtomicInteger();
        Translator broken = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("", "en");
        };
        long[] now = {0L};
        Map<String, String> failures = new java.util.HashMap<>();
        PersistentStore failureStore = inlineStore(failures);
        TranslationCache first = new TranslationCache(
                broken, "zh-TW", DIRECT, 100, 1_000L, () -> now[0], null);
        first.setFailureStore(failureStore);

        first.requestAsync("Hello world");             // attempt 1 → mark temporary:1:1000
        assertEquals(1, calls.get());
        assertEquals("temporary:1:1000", failures.get("Hello world"));

        now[0] = 500L;                                  // restart inside the backoff window
        TranslationCache restarted = new TranslationCache(
                broken, "zh-TW", DIRECT, 100, 1_000L, () -> now[0], null);
        restarted.setFailureStore(failureStore);
        restarted.requestAsync("Hello world");
        assertEquals(1, calls.get(), "the rehydrated mark keeps throttling after a restart");

        now[0] = 1_000L;                                // window expired: retry and escalate
        restarted.requestAsync("Hello world");
        assertEquals(2, calls.get());
        assertEquals("temporary:2:3000", failures.get("Hello world"),
                "the attempt count survives the restart and keeps escalating");
    }

    @Test
    void restoredItemFailureDoesNotSendUntilTheItemIsVisibleAgain() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> failures = new java.util.HashMap<>();
        failures.put("Old tooltip item", "temporary:3:0");
        TranslationCache restarted = new TranslationCache((text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("已修復", "en");
        }, "zh-TW", DIRECT, 100, 1_000L, () -> 10_000L, null);
        restarted.setFailureStore(inlineStore(failures));
        restarted.setBatchWindowMs(() -> 0);

        restarted.flushBatch();
        restarted.flushBatch();
        assertEquals(0, calls.get(),
                "world ticks must not revive historical tooltip debt in the background");

        restarted.requestBatchedPassive("Old tooltip item");
        restarted.flushBatch();
        assertEquals(1, calls.get(), "re-observing the item unlocks exactly one retry");
    }

    @Test
    void zeroBackoffFailuresAreStillDurableAndQueuedUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] available = {false};
        Translator flaky = (text, target) -> {
            calls.incrementAndGet();
            if (!available[0]) throw new TranslationException("offline");
            return new TranslationResult("修復完成", "en");
        };
        Map<String, String> failures = new java.util.HashMap<>();
        TranslationCache cache = new TranslationCache(
                flaky, "zh-TW", DIRECT, 100, 0L, () -> 25L, null);
        cache.setFailureStore(inlineStore(failures));

        cache.requestAsync("Hello world");
        assertEquals("temporary:1:25", failures.get("Hello world"),
                "zero delay disables throttling, not persistence");

        available[0] = true;
        cache.flushBatch();
        cache.flushBatch();
        assertEquals("修復完成", cache.getCached("Hello world"));
        assertFalse(failures.containsKey("Hello world"));
        assertEquals(2, calls.get());
    }

    @Test
    void engineSuccessClearsOnlyItsOwnNamespacedFailure() {
        Map<String, String> shared = new java.util.HashMap<>();
        PersistentStore ledger = inlineStore(shared);
        PersistentStore aiFailures = new com.dragonmeow.mctranslator.cache.NamespacedStore(ledger, "ai");
        PersistentStore gtFailures = new com.dragonmeow.mctranslator.cache.NamespacedStore(ledger, "gt");
        aiFailures.put("Hello world", "temporary:1:0");
        gtFailures.put("Hello world", "temporary:2:0");

        TranslationCache ai = new TranslationCache(
                (text, target) -> new TranslationResult("人工翻譯", "en"),
                "zh-TW", DIRECT, 100, 0L, () -> 0L, null);
        ai.setFailureStore(aiFailures);
        ai.requestAsync("Hello world");

        assertNull(aiFailures.get("Hello world"));
        assertEquals("temporary:2:0", gtFailures.get("Hello world"),
                "an AI success must not erase a GT failure for the same source");
    }

    @Test
    void confirmedAiIdentityDoesNotSuppressGtForTheSameSource() {
        AtomicInteger gtCalls = new AtomicInteger();
        Map<String, String> shared = new java.util.HashMap<>();
        PersistentStore ledger = inlineStore(shared);
        Map<String, String> aiDisk = new java.util.HashMap<>();
        Map<String, String> gtDisk = new java.util.HashMap<>();

        TranslationCache ai = new TranslationCache(
                (text, target) -> new TranslationResult(text, "en"),
                "zh-TW", DIRECT, 100, 0L, () -> 0L, inlineStore(aiDisk));
        ai.setFailureStore(new com.dragonmeow.mctranslator.cache.NamespacedStore(ledger, "ai"));
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            return new TranslationResult("機器翻譯", "en");
        }, "zh-TW", DIRECT, 100, 0L, () -> 0L, inlineStore(gtDisk));
        gt.setFailureStore(new com.dragonmeow.mctranslator.cache.NamespacedStore(ledger, "gt"));

        ai.requestAsync("Rezzus");
        ai.requestAsync("Rezzus");
        ai.requestAsync("Rezzus");
        assertEquals("Rezzus", ai.getCached("Rezzus"));

        gt.requestAsync("Rezzus");
        assertEquals("機器翻譯", gt.getCached("Rezzus"));
        assertEquals(1, gtCalls.get(), "GT owns an independent terminal decision");
    }

    @Test
    void repeatedStyleProjectionEchoCannotPoisonSuccessfulAiMeaningOrStartGt() {
        AtomicInteger gtCalls = new AtomicInteger();
        TranslationCache gt = new TranslationCache((text, target) -> {
            gtCalls.incrementAndGet();
            return new TranslationResult("錯誤候補", "en");
        }, "zh-TW", DIRECT, 100);
        Map<String, String> aiDisk = new java.util.HashMap<>();
        TranslationCache ai = new TranslationCache((text, target) ->
                text.contains("⟦CS") ? new TranslationResult(text, "en")
                        : new TranslationResult("你好世界", "en"),
                "zh-TW", DIRECT, 100, 0L, () -> 0L, inlineStore(aiDisk));
        ai.setFallback(gt, true);
        ai.setProvisionalRetryGate(() -> true);

        ai.requestAsync("Hello world");
        String marked = "⟦CS0⟧Hello world⟦/CS0⟧";
        for (int i = 0; i < 5; i++) {
            ai.getCached(marked);
            ai.flushBatch();
            ai.flushBatch();
        }

        assertEquals("你好世界", ai.getCached("Hello world"),
                "presentation failures cannot replace good semantic AI wording with KEEP_ORIGINAL");
        assertEquals("你好世界", TextFilter.stripFormatting(ai.getCached(marked)));
        assertEquals(0, gtCalls.get(),
                "a CS projection failure is not an AI semantic failure and must not start GT");
    }

    @Test
    void lateGtReadThroughCannotOverwriteConcurrentlyLandedAiFinal() throws Exception {
        Map<String, String> gtRows = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch enteredGtRead = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseGtRead = new java.util.concurrent.CountDownLatch(1);
        boolean[] blockReads = {false};
        PersistentStore gtStore = new PersistentStore() {
            @Override public String get(String key) {
                if (blockReads[0] && "Hello world".equals(key)) {
                    enteredGtRead.countDown();
                    try {
                        releaseGtRead.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return gtRows.get(key);
            }
            @Override public void put(String key, String value) { gtRows.put(key, value); }
            @Override public void clear() { gtRows.clear(); }
        };
        TranslationCache gt = new TranslationCache(countingUpper(new AtomicInteger()),
                "zh-TW", DIRECT, 100, 1_000L, () -> 0L, gtStore);
        TranslationCache ai = new TranslationCache((text, target) -> {
            throw new TranslationException("AI offline");
        }, "zh-TW", DIRECT, 100, 1_000L, () -> 0L);
        ai.requestAsync("Hello world");
        ai.setFallback(gt, true);
        gtRows.put("Hello world", "機器候補");
        blockReads[0] = true;

        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> observed = worker.submit(() -> ai.getCached("Hello world"));
            assertTrue(enteredGtRead.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ai.replaceFinal("Hello world", "人工最終"));
            releaseGtRead.countDown();

            assertEquals("人工最終", observed.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "the own-tier recheck linearises AI ahead of the late GT read");
            assertEquals("人工最終", ai.getCached("Hello world"));
            assertEquals("人工最終", ai.getCachedFinal("Hello world"));
        } finally {
            releaseGtRead.countDown();
            worker.shutdownNow();
        }
    }

    // ---- chat back-fill: style debts are passive and heal on a later observation ----

    /** A plain semantic success transfers the missing CS projection to a passive style
     *  ledger. Tick flushes must not spend requests; a later occurrence may retry it. */
    @Test
    void plainSemanticSuccessLeavesPassiveStyleDebtUntilObservedAgain() {
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        long[] now = {0L};
        List<String> sent = new ArrayList<>();
        AtomicInteger markedRound = new AtomicInteger();
        Translator translator = (text, target) -> {
            sent.add(text);
            if (text.contains("⟦CS")) {
                return markedRound.incrementAndGet() == 1
                        ? new TranslationResult("你好 世界", "en")   // markers eaten: unusable
                        : new TranslationResult("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", "en");
            }
            return new TranslationResult("你好 世界", "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 1_000L, () -> now[0]);

        cache.requestAsync(marked);                    // damaged shape -> family failure
        assertEquals(List.of(marked), sent);

        now[0] = 1_000L;                               // family backoff expired
        cache.requestAsync("Hello World");             // plain semantics land as final
        assertEquals("你好 世界", cache.getCached("Hello World"));

        now[0] = 5_000L;                               // style-ledger backoff expired
        for (int i = 0; i < 4; i++) cache.flushBatch();
        assertEquals(1, sent.stream().filter(marked::equals).count(),
                "tick flushes must not re-buy a presentation-only debt");

        java.util.concurrent.atomic.AtomicReference<String> exact =
                new java.util.concurrent.atomic.AtomicReference<>();
        cache.requestCoalescedExactStyle(marked, exact::set, false);
        for (int i = 0; i < 4; i++) cache.flushBatch();
        assertEquals(2, sent.stream().filter(marked::equals).count(),
                "seeing the same rich text again may buy one exact projection");
        assertEquals("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", exact.get(),
                "the newly observed retry must materialise the exact projection");
    }

    /** Root cause #2B: writeStyleProjection can silently skip its row (here: stripping
     *  §-codes destroys every §-adjacent marker, leaving bare residue). The semantic
     *  store records a passive debt instead of starting an unbounded retry loop. */
    @Test
    void unwritableStyleProjectionStaysPassiveUntilObservedAgain() {
        String marked = "⟦CS0⟧Hello world⟦/CS0⟧";
        long[] now = {0L};
        List<String> sent = new ArrayList<>();
        Translator translator = (text, target) -> {
            sent.add(text);
            // usable() passes (marker topology intact), but stripSectionCodes("§⟦")
            // later destroys both markers, so the projection row cannot be written.
            return new TranslationResult("§⟦CS0⟧你好世界§⟦/CS0⟧", "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 1_000L, () -> now[0]);

        cache.requestAsync(marked);
        assertEquals(1, sent.size());

        now[0] = 1_000L;
        for (int i = 0; i < 4; i++) cache.flushBatch();
        assertEquals(1, sent.stream().filter(marked::equals).count(),
                "an unwritable colour projection must not retry itself");

        cache.requestCoalescedExactStyle(marked, ignored -> { }, true);
        for (int i = 0; i < 4; i++) cache.flushBatch();
        assertEquals(2, sent.stream().filter(marked::equals).count(),
                "a later observation gets one new attempt");

        now[0] = 10_000L;
        for (int i = 0; i < 6; i++) cache.flushBatch();
        assertEquals(2, sent.stream().filter(marked::equals).count(),
                "the second failure must return to passive state");
    }

    /** A passive debt whose key carries §-codes can still reuse a compatible projection
     *  produced by a later real observation. */
    @Test
    void passiveStyleDebtUsesLaterSharedProjectionIncludingSectionCodeKeys() {
        String sectionMarked = "⟦CS0⟧§eHello⟦/CS0⟧ ⟦CS1⟧§aWorld⟦/CS1⟧";
        String cleanMarked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        long[] now = {0L};
        List<String> sent = new ArrayList<>();
        Translator translator = (text, target) -> {
            sent.add(text);
            if (text.contains("§")) {
                return new TranslationResult("你好 世界", "en");    // markers eaten: unusable
            }
            if (text.contains("⟦CS")) {
                return new TranslationResult("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", "en");
            }
            return new TranslationResult("你好 世界", "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 1_000L, () -> now[0]);

        cache.requestAsync(sectionMarked);             // damaged -> passive marked debt
        now[0] = 1_000L;
        cache.requestAsync("Hello World");             // semantic wording becomes final
        cache.requestCoalescedExactStyle(cleanMarked, ignored -> { }, true);
        for (int i = 0; i < 4; i++) cache.flushBatch(); // writes the shared projection row

        now[0] = 10_000L;
        for (int i = 0; i < 6; i++) cache.flushBatch();
        assertEquals(1, sent.stream().filter(sectionMarked::equals).count(),
                "an already-satisfied projection must not be bought again");

        java.util.concurrent.atomic.AtomicReference<String> exact =
                new java.util.concurrent.atomic.AtomicReference<>();
        cache.requestCoalescedExactStyle(sectionMarked, exact::set, false);
        assertEquals("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", exact.get(),
                "the §-code variant is served from the shared projection row");
    }

    /** An exact-style final waiter remains eligible for notification when a later
     *  occurrence explicitly retries and lands the projection. */
    @Test
    void exactStyleFinalWaiterIsNotifiedByLaterObservedSuccess() {
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        long[] now = {0L};
        AtomicInteger markedRound = new AtomicInteger();
        Translator translator = (text, target) -> {
            if (text.contains("⟦CS")) {
                return markedRound.incrementAndGet() == 1
                        ? new TranslationResult("你好 世界", "en")
                        : new TranslationResult("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", "en");
            }
            return new TranslationResult("你好 世界", "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", DIRECT, 100, 1_000L, () -> now[0]);
        java.util.concurrent.atomic.AtomicReference<String> delivered =
                new java.util.concurrent.atomic.AtomicReference<>();

        cache.requestAsync(marked);                    // damaged -> family failure
        cache.requestCoalescedExactStyleFinal(marked, delivered::set);
        assertNull(delivered.get());

        now[0] = 1_000L;
        cache.requestAsync("Hello World");             // plain final; projection still missing
        assertNull(delivered.get(),
                "a style-fallback state must not satisfy an exact-style final waiter");

        now[0] = 5_000L;
        for (int i = 0; i < 4; i++) cache.flushBatch();
        assertNull(delivered.get(), "tick flushes alone must not retry colour debt");

        cache.requestCoalescedExactStyle(marked, ignored -> { }, true);
        for (int i = 0; i < 4; i++) cache.flushBatch();
        assertEquals("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", delivered.get(),
                "the later observed success must notify the exact-style waiter");
    }

    /** Regression lock: waiter families stay LRU-bounded at 512. */
    @Test
    void finalWaiterFamiliesStayBoundedAt512() {
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 4096);
        List<String> delivered = new ArrayList<>();
        int families = 600;
        for (int i = 0; i < families; i++) {
            cache.requestCoalescedFinal(waiterKey(i), delivered::add);
        }
        for (int i = 0; i < families; i++) cache.translateBlocking(waiterKey(i));
        assertEquals(512, delivered.size(),
                "registering 600 families must keep only 512 live waiters");
    }

    /** Digit-free distinct keys: numbers would template every key into ONE family. */
    private static String waiterKey(int i) {
        StringBuilder out = new StringBuilder("Waiter key ");
        for (char digit : Integer.toString(i).toCharArray()) out.append((char) ('a' + digit - '0'));
        return out.toString();
    }

    /** Regression lock: a projection supplement can never rewrite the final semantic row. */
    @Test
    void styleProjectionSupplementNeverRewritesFinalSemanticRow() {
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return text.contains("⟦CS")
                    ? new TranslationResult("⟦CS0⟧嗨呀⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", "en")
                    : new TranslationResult("你好 世界", "en");
        };
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 100);

        cache.requestAsync("Hello World");
        assertEquals("你好 世界", cache.getCached("Hello World"));

        java.util.concurrent.atomic.AtomicReference<String> exact =
                new java.util.concurrent.atomic.AtomicReference<>();
        cache.requestCoalescedExactStyle(marked, exact::set, true);
        for (int i = 0; i < 4; i++) cache.flushBatch(); // buys the differently worded projection
        assertEquals("你好 世界", cache.getCached("Hello World"),
                "first final semantic wording wins; a style supplement adds rows only");
        assertEquals("⟦CS0⟧嗨呀⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", exact.get(),
                "the exact rich consumer receives its verified colour projection");

        int callsAfterProjection = calls.get();
        java.util.concurrent.atomic.AtomicReference<String> cachedExact =
                new java.util.concurrent.atomic.AtomicReference<>();
        cache.requestCoalescedExactStyle(marked, cachedExact::set, true);
        assertEquals(exact.get(), cachedExact.get(),
                "a differently worded final projection remains available from cache");
        assertEquals(callsAfterProjection, calls.get(),
                "reading the exact projection again must not buy another request");
    }
}
