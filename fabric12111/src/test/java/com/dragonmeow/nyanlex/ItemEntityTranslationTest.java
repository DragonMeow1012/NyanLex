package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item-name entity layer through the real {@link TranslationService} /
 * {@link TranslationCache} stack: a reforge split proven by the item's own modifier is
 * composed from the shipped reforge table plus ONE shared base-name unit, and a
 * registered item name inside any other line is replaced by that very same item
 * translation. Every translator and executor is an inline fake; nothing touches disk or
 * network.
 */
class ItemEntityTranslationTest {

    private static final Executor DIRECT = Runnable::run;

    /** Inline executor that holds tasks until the test runs them. */
    private static final class Deferred implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void run(int index) {
            tasks.remove(index).run();
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.remove(0).run();
        }
    }

    /** Inline translator: exact dictionary entries first, then phrase replacements that
     *  leave every ⟦…⟧ token alone. Records every text it is asked for. */
    private static final class FakeTranslator implements Translator {
        final List<String> requested = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> exact = new LinkedHashMap<>();
        final Map<String, String> phrases = new LinkedHashMap<>();

        FakeTranslator() {
            exact.put("Adaptive Belt", "適應腰帶");
            exact.put("Skeleton Master Chestplate", "骷髏大師胸甲");
            exact.put("Hyperion", "海柏利昂");
            exact.put("Giant's Sword", "巨人之劍");
            exact.put("Blended Adaptive Belt", "融合適應腰帶");
            exact.put("Ancient Skeleton Master Chestplate ⟦MT0⟧", "古代 Skeleton Master 胸甲 ⟦MT0⟧");
            exact.put("Wise Dragon Chestplate ⟦MT0⟧", "智慧龍胸甲 ⟦MT0⟧");
            phrases.put("Chestplate:", "胸甲：");
            phrases.put("Belt:", "腰帶：");
            phrases.put("bought", "購買了");
            phrases.put("coins, thanks", "硬幣，感謝");
            phrases.put("Sold", "售出");
            phrases.put("Use a", "使用");
            phrases.put("here", "這裡");
            phrases.put("is great", "很棒");
        }

        int count(String text) {
            synchronized (requested) {
                return Collections.frequency(requested, text);
            }
        }

        @Override
        public TranslationResult translate(String text, String targetLang) {
            requested.add(text);
            String value = exact.get(text);
            if (value == null) {
                value = text;
                for (Map.Entry<String, String> p : phrases.entrySet()) {
                    value = value.replace(p.getKey(), p.getValue());
                }
                if (value.equals(text)) value = "譯" + text;
            }
            return new TranslationResult(value, "en");
        }
    }

    private static TranslatorConfig zhTw() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.chatMode = DisplayMode.TRANSLATION;
        return cfg;
    }

    private static TranslationService service(TranslatorConfig cfg, Translator translator,
                                              Executor executor) {
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, executor, 1000);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, executor, 1000);
        return new TranslationService(cfg, gt, ai);
    }

    private static TranslationService service(TranslatorConfig cfg, Translator translator) {
        return service(cfg, translator, DIRECT);
    }

    /** Two client ticks: the coalescer holds one tick after growth, then sends. */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    private static String shown(TranslationService s, String line) {
        TranslationDecision d = s.translateItemLine(line);
        return d.changed() ? d.translated() : line;
    }

    private static final String ANCIENT = "Ancient Skeleton Master Chestplate ✪✪✪✪✪";
    private static final String LOADOUT = "Chestplate: Ancient Skeleton Master Chestplate ✪✪✪✪✪";

    // ---- split + composition ----

    @Test
    void blendedAdaptiveBeltComposesReforgePlusBaseWithoutEverBuyingTheWholeName() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");

        s.translateItemLine("Blended Adaptive Belt");
        assertEquals("混合適應腰帶", shown(s, "Blended Adaptive Belt"));
        assertEquals(1, t.count("Adaptive Belt"));
        assertEquals(0, t.count("Blended Adaptive Belt"), "the whole name is never requested");
        pump(s);
        assertEquals(0, t.count("Blended Adaptive Belt"));
    }

    @Test
    void baseNameIsRequestedOnceAndSharedByEveryReforgeVariant() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
        s.registerItemEntity("Fierce Adaptive Belt", "ADAPTIVE_BELT", "fierce");
        s.registerItemEntity("Adaptive Belt", "ADAPTIVE_BELT", null);

        s.translateItemLine("Blended Adaptive Belt");
        s.translateItemLine("Fierce Adaptive Belt");
        pump(s);
        assertEquals("混合適應腰帶", shown(s, "Blended Adaptive Belt"));
        assertEquals("兇猛適應腰帶", shown(s, "Fierce Adaptive Belt"));
        assertEquals("適應腰帶", shown(s, "Adaptive Belt"), "the unreforged item shares the unit");
        assertEquals(1, t.count("Adaptive Belt"));
        assertEquals(1, t.requested.size(), "one small unit for three items");
    }

    @Test
    void itemsWithoutAProvingModifierKeepTheirWholeNameKey() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Giant's Sword", "GIANTS_SWORD", null);
        s.translateItemLine("Giant's Sword");
        pump(s);
        assertEquals("巨人之劍", shown(s, "Giant's Sword"));

        FakeTranslator t2 = new FakeTranslator();
        TranslationService s2 = service(zhTw(), t2);
        s2.registerItemEntity("Giant's Sword", "GIANTS_SWORD", "fabled");
        s2.translateItemLine("Giant's Sword");
        pump(s2);
        assertEquals(List.of("Giant's Sword"), t2.requested, "fabled proves nothing here");

        FakeTranslator t3 = new FakeTranslator();
        TranslationService s3 = service(zhTw(), t3);
        s3.registerItemEntity("Wise Dragon Chestplate ✪✪✪", "WISE_DRAGON_CHESTPLATE", null);
        s3.translateItemLine("Wise Dragon Chestplate ✪✪✪");
        pump(s3);
        assertEquals(List.of("Wise Dragon Chestplate ⟦MT0⟧"), t3.requested,
                "an unsplit title keeps exactly its pre-entity key");
        assertEquals("智慧龍胸甲 ✪✪✪", shown(s3, "Wise Dragon Chestplate ✪✪✪"));
    }

    @Test
    void wiseWiseDragonChestplateStripsOnlyOneWise() {
        FakeTranslator t = new FakeTranslator();
        t.exact.put("Wise Dragon Chestplate", "智慧龍胸甲");
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Wise Wise Dragon Chestplate", "WISE_DRAGON_CHESTPLATE", "wise");
        s.translateItemLine("Wise Wise Dragon Chestplate");
        assertEquals("智慧智慧龍胸甲", shown(s, "Wise Wise Dragon Chestplate"));
        assertEquals(List.of("Wise Dragon Chestplate"), t.requested);
    }

    @Test
    void aBaseKeptInEnglishIsSeparatedFromTheReforgeByOneSpace() {
        FakeTranslator t = new FakeTranslator();
        t.exact.put("Soulweaver Gloves", "Soulweaver 手套");
        t.exact.put("Terminator", "Terminator"); // an echo: the backend keeps it original
        TranslatorConfig cfg = zhTw();
        // No retry backoff, so the cache's three-echo keep-original rule settles quickly.
        TranslationCache gt = new TranslationCache(t, cfg.targetLang, DIRECT, 1000, 0L,
                System::currentTimeMillis);
        TranslationCache ai = new TranslationCache(t, cfg.targetLang, DIRECT, 1000, 0L,
                System::currentTimeMillis);
        TranslationService s = new TranslationService(cfg, gt, ai);
        s.registerItemEntity("Blended Soulweaver Gloves", "SOULWEAVER_GLOVES", "blended");
        s.registerItemEntity("Withered Terminator ✪✪", "TERMINATOR", "withered");
        s.translateItemLine("Blended Soulweaver Gloves");
        assertEquals("混合 Soulweaver 手套", shown(s, "Blended Soulweaver Gloves"));

        for (int frame = 0; frame < 3; frame++) s.translateItemLine("Withered Terminator ✪✪");
        assertEquals(3, t.count("Terminator"), "three consecutive echoes decide keep-original");
        assertEquals("枯萎 Terminator ✪✪", shown(s, "Withered Terminator ✪✪"));
        for (int frame = 0; frame < 5; frame++) s.translateItemLine("Withered Terminator ✪✪");
        assertEquals(3, t.count("Terminator"), "a kept-original base is final, not re-asked");
        assertEquals(0, t.count("Withered Terminator ⟦MT0⟧"));
    }

    @Test
    void unknownModifierOrMismatchedFirstWordIsNotComposed() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "mystery_stone");
        s.translateItemLine("Blended Adaptive Belt");
        pump(s);
        assertEquals(List.of("Blended Adaptive Belt"), t.requested);
        assertEquals("融合適應腰帶", shown(s, "Blended Adaptive Belt"), "as before: whole-name row");
    }

    // ---- one item, one translation on every surface ----

    @Test
    void loadoutLineShowsExactlyTheItemsOwnTitleTranslation() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity(ANCIENT, "SKELETON_MASTER_CHESTPLATE", "ancient");

        s.translateItemLine(ANCIENT);
        String title = shown(s, ANCIENT);
        assertEquals("遠古骷髏大師胸甲 ✪✪✪✪✪", title);

        s.translateItemLine(LOADOUT);
        pump(s);
        String loadout = shown(s, LOADOUT);
        assertEquals("胸甲： 遠古骷髏大師胸甲 ✪✪✪✪✪", loadout);
        assertTrue(loadout.contains(title), "the Loadout row names the item exactly like its title");
        assertEquals(1, t.count("Chestplate: ⟦0⟧ ⟦MT0⟧"), "the row is one shared family key");
        assertEquals(1, t.count("Skeleton Master Chestplate"));
        assertEquals(0, t.count("Ancient Skeleton Master Chestplate ⟦MT0⟧"));

        // Held name and a colour-marked title compose to the same wording.
        TranslationDecision held = s.translateHeld(ANCIENT);
        assertEquals("遠古骷髏大師胸甲 ✪✪✪✪✪", held.translated());
        String marked = "⟦CS0⟧Ancient Skeleton Master Chestplate⟦/CS0⟧ ⟦CS1⟧✪✪✪✪✪⟦/CS1⟧";
        assertEquals("⟦CS0⟧遠古骷髏大師胸甲⟦/CS0⟧ ⟦CS1⟧✪✪✪✪✪⟦/CS1⟧", shown(s, marked));
    }

    @Test
    void unsplitItemInsideALineUsesItsOwnNameRow() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Wise Dragon Chestplate ✪✪✪", "WISE_DRAGON_CHESTPLATE", null);
        s.warmNamesBatch(List.of("Wise Dragon Chestplate ✪✪✪"));
        String title = shown(s, "Wise Dragon Chestplate ✪✪✪");
        assertEquals("智慧龍胸甲 ✪✪✪", title);

        s.translateItemLine("Chestplate: Wise Dragon Chestplate ✪✪✪");
        pump(s);
        assertEquals("胸甲： 智慧龍胸甲 ✪✪✪", shown(s, "Chestplate: Wise Dragon Chestplate ✪✪✪"));
        assertEquals(1, t.count("Wise Dragon Chestplate ⟦MT0⟧"), "no new unit for an unsplit item");
    }

    @Test
    void unregisteredShortAndColourSplitNamesAreNeverSubstituted() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Stick", "STICK", null);
        s.registerItemEntity("Withered Hyperion", "HYPERION", "withered");

        s.translateItemLine("Use a Stick here");
        s.translateItemLine("Sold Aspect of the Void");
        s.translateItemLine("⟦CS0⟧Withered⟦/CS0⟧ ⟦CS1⟧Hyperion⟦/CS1⟧ is great");
        pump(s);
        assertEquals(1, t.count("Use a Stick here"), "short name: key unchanged");
        assertEquals(1, t.count("Sold Aspect of the Void"), "unregistered: key unchanged");
        assertEquals(1, t.count("⟦CS0⟧Withered⟦/CS0⟧ ⟦CS1⟧Hyperion⟦/CS1⟧ is great")
                        + t.count("Withered Hyperion is great"),
                "a name split over two colour runs is not an item slot");
        synchronized (t.requested) {
            for (String sent : t.requested) assertFalse(sent.contains("⟦0⟧"), sent);
        }
    }

    @Test
    void itemSlotNeverTriggersTheR17InvalidationLoop() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity(ANCIENT, "SKELETON_MASTER_CHESTPLATE", "ancient");
        s.translateItemLine(ANCIENT);
        s.translateItemLine(LOADOUT);
        pump(s);
        String first = shown(s, LOADOUT);
        int sent = t.requested.size();
        for (int frame = 0; frame < 20; frame++) {
            assertEquals(first, shown(s, LOADOUT));
            pump(s);
        }
        assertEquals(sent, t.requested.size(), "the E slot's Chinese name is not a lost original");
        assertEquals("胸甲： 遠古骷髏大師胸甲 ✪✪✪✪✪", first);
    }

    @Test
    void itemNameShowsEnglishUntilItsUnitLandsThenUpdatesOnTheNextFrame() {
        FakeTranslator t = new FakeTranslator();
        Deferred executor = new Deferred();
        TranslationService s = service(zhTw(), t, executor);
        s.registerItemEntity(ANCIENT, "SKELETON_MASTER_CHESTPLATE", "ancient");

        s.translateItemLine(LOADOUT);          // queues the base unit (task 0)
        pump(s);                               // queues the row itself (task 1)
        assertEquals(2, executor.tasks.size());
        executor.run(1);                       // only the row lands
        assertEquals("胸甲： Ancient Skeleton Master Chestplate ✪✪✪✪✪", shown(s, LOADOUT));
        assertEquals(0, t.count("Skeleton Master Chestplate"));
        assertTrue(executor.tasks.size() == 1, "the in-flight unit is not queued twice");
        executor.runAll();                     // the unit lands
        assertEquals("胸甲： 遠古骷髏大師胸甲 ✪✪✪✪✪", shown(s, LOADOUT));
        assertEquals(1, t.count("Skeleton Master Chestplate"));
    }

    @Test
    void titleShowsItsOldWholeNameRowUntilTheBaseLandsAndNeverRebuysIt() {
        FakeTranslator t = new FakeTranslator();
        Deferred executor = new Deferred();
        TranslationService s = service(zhTw(), t, executor);
        // Before the entity layer knew the item: the whole title was bought once.
        s.translateItemLine(ANCIENT);
        pump(s);
        executor.runAll();
        assertEquals("古代 Skeleton Master 胸甲 ✪✪✪✪✪", shown(s, ANCIENT));

        s.registerItemEntity(ANCIENT, "SKELETON_MASTER_CHESTPLATE", "ancient");
        assertEquals("古代 Skeleton Master 胸甲 ✪✪✪✪✪", shown(s, ANCIENT), "as before meanwhile");
        pump(s);
        executor.runAll();
        assertEquals("遠古骷髏大師胸甲 ✪✪✪✪✪", shown(s, ANCIENT));
        assertEquals(1, t.count("Ancient Skeleton Master Chestplate ⟦MT0⟧"), "never re-bought");
        assertEquals(1, t.count("Skeleton Master Chestplate"));
    }

    @Test
    void doNotTranslateTermAndPlayerNamesKeepTheirIndicesBesideAnItemSlot() {
        FakeTranslator t = new FakeTranslator();
        TranslatorConfig cfg = zhTw();
        cfg.doNotTranslateTerms.add("SkyBlock");
        TranslationService s = service(cfg, t);
        s.setProtectedNames(() -> Set.of("Steve"));
        s.registerItemEntity("Withered Hyperion ✪✪✪✪✪", "HYPERION", "withered");

        String line = "[Auction] Bob_77 bought Withered Hyperion for 5 SkyBlock coins, thanks Steve";
        s.translateItemLine(line);
        pump(s);
        String sentRow = null;
        synchronized (t.requested) {
            for (String sent : t.requested) if (sent.contains("bought")) sentRow = sent;
        }
        assertTrue(sentRow != null && sentRow.contains("⟦0⟧ bought ⟦1⟧")
                && sentRow.contains("⟦2⟧ coins, thanks ⟦3⟧"), String.valueOf(sentRow));
        assertEquals("[Auction] Bob_77 購買了 枯萎海柏利昂 for 5 SkyBlock 硬幣，感謝 Steve",
                shown(s, line));
    }

    @Test
    void masterSwitchOffSendsNothingForAnyItemPath() {
        FakeTranslator t = new FakeTranslator();
        TranslatorConfig cfg = zhTw();
        cfg.translationRequestsEnabled = false;
        TranslationService s = service(cfg, t);
        s.registerItemEntity(ANCIENT, "SKELETON_MASTER_CHESTPLATE", "ancient");
        s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");

        s.warmNamesBatch(List.of(ANCIENT, "Blended Adaptive Belt"));
        s.warmTooltipBatch(List.of(ANCIENT, LOADOUT));
        s.translateItemLine(ANCIENT);
        s.translateItemLine(LOADOUT);
        s.translateHeld("Blended Adaptive Belt");
        s.isTooltipTranslationReady(LOADOUT);
        s.translateChatAsync("Sold Blended Adaptive Belt", ignored -> { });
        pump(s);
        assertEquals(List.of(), t.requested);
        assertFalse(s.translateItemLine(ANCIENT).changed());
    }

    @Test
    void nonTraditionalChineseTargetsAreNotComposed() {
        for (String target : List.of("fr-FR", "zh-CN")) {
            FakeTranslator t = new FakeTranslator();
            TranslatorConfig cfg = zhTw();
            cfg.targetLang = target;
            TranslationService s = service(cfg, t);
            s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
            s.translateItemLine("Blended Adaptive Belt");
            s.translateItemLine("Belt: Blended Adaptive Belt");
            pump(s);
            assertEquals(1, t.count("Blended Adaptive Belt"), target + ": whole name as before");
            assertEquals(1, t.count("Belt: Blended Adaptive Belt"), target + ": row key unchanged");
            assertEquals(0, t.count("Adaptive Belt"), target + ": no base unit");
        }
    }

    // ---- pre-warm, readiness, retranslate, debug log ----

    @Test
    void containerAndHotbarWarmQueueTheBaseUnitInsteadOfTheWholeName() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
        s.registerItemEntity("Fierce Adaptive Belt", "ADAPTIVE_BELT", "fierce");
        s.registerItemEntity("Wise Dragon Chestplate ✪✪✪", "WISE_DRAGON_CHESTPLATE", null);
        s.warmNamesBatch(List.of("Blended Adaptive Belt", "Fierce Adaptive Belt",
                "Wise Dragon Chestplate ✪✪✪"));
        assertEquals(1, t.count("Adaptive Belt"));
        assertEquals(1, t.count("Wise Dragon Chestplate ⟦MT0⟧"));
        assertEquals(0, t.count("Blended Adaptive Belt"));
        assertEquals(0, t.count("Fierce Adaptive Belt"));
        assertEquals(2, t.requested.size());
        assertTrue(s.isTooltipTranslationReady("Blended Adaptive Belt"));
    }

    @Test
    void tooltipReadinessOfAComposedTitleFollowsItsUnit() {
        FakeTranslator t = new FakeTranslator();
        Deferred executor = new Deferred();
        TranslationService s = service(zhTw(), t, executor);
        s.registerItemEntity(ANCIENT, "SKELETON_MASTER_CHESTPLATE", "ancient");
        String marked = "⟦CS0⟧Ancient Skeleton Master Chestplate⟦/CS0⟧ ⟦CS1⟧✪✪✪✪✪⟦/CS1⟧";
        s.warmTooltipBatch(List.of(ANCIENT));
        s.warmTooltipBatch(List.of(marked, "⟦CS0⟧Chestplate:⟦/CS0⟧ ⟦CS1⟧Ancient Skeleton Master Chestplate⟦/CS1⟧"));
        assertFalse(s.isTooltipTranslationReady(ANCIENT));
        assertFalse(s.isTooltipTranslationReady(marked));
        executor.runAll();
        assertTrue(s.isTooltipTranslationReady(ANCIENT));
        assertTrue(s.isTooltipTranslationReady(marked));
        synchronized (t.requested) {
            assertEquals(1, Collections.frequency(t.requested, "Skeleton Master Chestplate"));
            for (String sent : t.requested) {
                assertFalse(sent.startsWith("⟦CS0⟧⟦0⟧"), "a title of nothing but the item is never bought: " + sent);
            }
            assertEquals(2, t.requested.size(), String.valueOf(t.requested));
        }
    }

    @Test
    void retranslatingAComposedItemRebuysItsBaseUnitOnce() {
        FakeTranslator t = new FakeTranslator();
        TranslationService s = service(zhTw(), t);
        s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
        s.translateItemLine("Blended Adaptive Belt");
        assertEquals("混合適應腰帶", shown(s, "Blended Adaptive Belt"));
        t.exact.put("Adaptive Belt", "自適應腰帶");
        s.retranslate(List.of("Blended Adaptive Belt"));
        pump(s);
        assertEquals(2, t.count("Adaptive Belt"));
        assertEquals("混合自適應腰帶", shown(s, "Blended Adaptive Belt"));
        assertEquals(0, t.count("Blended Adaptive Belt"));
    }

    @Test
    void debugOverlayLogsEachDisplayNameOnce() {
        FakeTranslator t = new FakeTranslator();
        TranslatorConfig cfg = zhTw();
        TranslationService s = service(cfg, t);
        List<String> log = new ArrayList<>();
        s.setInfoLog(log::add);
        s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
        assertEquals(List.of(), log, "overlay off: silent");
        cfg.debugTranslationOverlay = true;
        for (int frame = 0; frame < 5; frame++) {
            s.registerItemEntity("Blended Adaptive Belt", "ADAPTIVE_BELT", "blended");
            s.registerItemEntity("Giant's Sword", "GIANTS_SWORD", null);
        }
        assertEquals(List.of(
                "[item-entity] name=\"Blended Adaptive Belt\" id=ADAPTIVE_BELT modifier=blended"
                        + " split=Blended + \"Adaptive Belt\"",
                "[item-entity] name=\"Giant's Sword\" id=GIANTS_SWORD modifier=null split=no"), log);
        assertEquals(List.of(), t.requested, "registration never sends a request");
    }
}
