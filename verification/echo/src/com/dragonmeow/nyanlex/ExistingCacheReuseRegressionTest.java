package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher;
import com.dragonmeow.nyanlex.translate.NameMasker;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatibility with real legacy cache rows, including rows loaded by a fresh cache instance.
 * Seeds go through the unchanged four-argument NameMasker and real TranslationCache, never a
 * hand-written approximation of a template key. All Translator calls use an in-process fake.
 */
class ExistingCacheReuseRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Seller: shedos", "Seller: [MVP+] shedos", "[MVP+] shedos joined the lobby!",
            "1,234 behind shedos [#12]", "Maintenance just started!"
    })
    void aLegacyDiskRowIsVisibleOnTheFirstChatRender(String source) throws Exception {
        Fixture f = new Fixture(source);
        TranslationDecision first = f.service.translateChat(source);
        f.assertHit(first, source);
        f.assertNoSendAndNoCachePending();
        assertEquals(0L, f.now.get(), "a cache hit needs neither a tick nor a clock advance");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scoreboard", "name_tag", "book", "screen", "item"})
    void existingRowsRemainImmediateAcrossRenderSurfaces(String surface) throws Exception {
        String source = "Seller: shedos";
        Fixture f = new Fixture(source);
        TranslationDecision first = switch (surface) {
            case "scoreboard" -> f.service.translateScoreboardLine(source);
            case "name_tag" -> f.service.translateUi(source);
            case "book" -> f.service.translateBook(source);
            case "screen" -> f.service.translateScreenText(source);
            case "item" -> f.service.translateItemLine(source);
            default -> throw new AssertionError(surface);
        };
        f.assertHit(first, source);
        f.assertNoSendAndNoCachePending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Seller: shedos", "[MVP+] shedos joined the lobby!", "Maintenance just started!"})
    void cachedAsyncChatCompletesImmediatelyWithoutBuyingAgain(String source) throws Exception {
        Fixture f = new Fixture(source);
        List<TranslationService.ChatTranslationResult> done = new ArrayList<>();
        f.service.translateChatAsyncDetailed(source, done::add);
        assertEquals(1, done.size(), "an existing final translation must complete on the first call");
        assertTrue(done.get(0).finalResult());
        assertEquals(visible(translateWords(source)), visible(done.get(0).text()));
        f.flushAt(750);
        f.flushAt(1_500);
        assertEquals(1, done.size(), "cache reuse must not schedule another callback later");
        f.assertNoSendAndNoCachePending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Seller: shedos", "Seller: [MVP+] shedos", "[MVP+] shedos joined the lobby!"})
    void repeatedObservationNeverRebuysTheExistingLegacyFamily(String source) throws Exception {
        Fixture f = new Fixture(source);
        // Do not stop at the initial render here: this catches the second .3 regression,
        // a changed key causing a new paid request after its artificial wait has elapsed.
        f.service.translateChat(source);
        for (long time : new long[] {0, 500, 749, 750, 751, 1_500}) {
            f.flushAt(time);
            f.service.translateChat(source);
        }
        f.assertNoSendAndNoCachePending();
        f.assertHit(f.service.translateChat(source), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"scoreboard", "book"})
    void prewarmingAnExistingLegacyRowNeverBuysItAgain(String surface) throws Exception {
        String source = "Seller: shedos";
        Fixture f = new Fixture(source);
        if (surface.equals("scoreboard")) f.service.warmScoreboardBatch(List.of(source));
        else f.service.warmBookBatch(List.of(source));
        for (long time : new long[] {0, 749, 750, 751, 1_500}) f.flushAt(time);
        f.assertNoSendAndNoCachePending();
        f.assertHit(f.service.translateChat(source), source);
    }

    @Test
    void aDifferentSellerReusesTheLegacySlotFamilyAndRestoresTheCurrentId() throws Exception {
        Fixture f = new Fixture("Seller: shedos");
        assertEquals(legacyMask("Seller: shedos").text(), legacyMask("Seller: zqdrla").text(),
                "fixture represents a pre-existing shared player-slot cache family");
        TranslationDecision first = f.service.translateChat("Seller: zqdrla");
        f.assertHit(first, "Seller: zqdrla");
        assertFalse(first.translated().contains("shedos"), "never restore the seller used while seeding");
        f.service.warmScoreboardBatch(List.of("Seller: zqdrla"));
        List<TranslationService.ChatTranslationResult> done = new ArrayList<>();
        f.service.translateChatAsyncDetailed("Seller: zqdrla", done::add);
        assertEquals(1, done.size());
        assertEquals("賣家： zqdrla", visible(done.get(0).text()));
        f.flushAt(750);
        f.assertNoSendAndNoCachePending();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧[MVP+]⟦/CS1⟧ ⟦CS2⟧shedos⟦/CS2⟧",
            "§aSeller: §b[MVP+] §cshedos"
    })
    void anExactLegacyStyledRowIsAlsoVisibleImmediately(String source) throws Exception {
        Fixture f = new Fixture(source);
        f.assertHit(f.service.translateChat(source), source);
        f.assertNoSendAndNoCachePending();
    }

    private static NameMasker.Masked legacyMask(String source) {
        // This four-argument API kept its legacy semantics in .2, .3 and the rollback.
        return NameMasker.mask(source, List.of(), DoNotTranslateMatcher.EMPTY, null);
    }

    private static String visible(String text) {
        return text == null ? null : TextFilter.stripSectionCodes(
                TextFilter.stripTranslationMarkers(TextFilter.stripStyleFallback(text))).strip();
    }

    private static String translateWords(String text) {
        return text.replace("Seller:", "賣家：").replace("joined the lobby!", "加入了大廳！")
                .replace("behind", "落後於").replace("Maintenance just started!", "維護剛開始了！");
    }

    /** Version adapter reproduces Fabric's shipped .3 opt-in via public APIs only.
     * A reverted service has no such API and uses its original immediate lookup path.
     * The test never edits fields, swaps implementations, or reconstructs key logic.
     */
    private static void applyShippedBootstrap(TranslationService service, LongSupplier clock) throws Exception {
        try {
            var setter = TranslationService.class.getMethod("setNameResolutionDelay", long.class, LongSupplier.class);
            setter.invoke(service, 750L, clock);
        } catch (NoSuchMethodException expectedForLegacyAndRollback) {
            // No delay facility existed in the accepted .2 release.
        } catch (InvocationTargetException failedPublicApi) {
            throw new AssertionError("shipped public bootstrap failed", failedPublicApi.getCause());
        }
    }

    private static final class MapStore implements PersistentStore {
        private final Map<String, String> rows = new LinkedHashMap<>();
        @Override public String get(String key) { return rows.get(key); }
        @Override public void put(String key, String value) { rows.put(key, value); }
        @Override public void clear() { rows.clear(); }
        @Override public void remove(String key) { rows.remove(key); }
        @Override public Map<String, String> entries() { return Map.copyOf(rows); }
    }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final List<String> sent = new ArrayList<>();
        final TranslationCache ai;
        final TranslationCache machine;
        final TranslationService service;

        Fixture(String seedSource) throws Exception {
            TranslatorConfig cfg = TestConfigs.translating();
            cfg.targetLang = "zh-TW";
            cfg.chatMode = cfg.tooltipMode = cfg.scoreboardMode = cfg.nameMode = cfg.bookMode
                    = cfg.screenTextMode = DisplayMode.TRANSLATION;
            cfg.aiChat = cfg.aiTooltip = cfg.aiScoreboard = cfg.aiName = cfg.aiBook = cfg.aiScreenText = true;
            cfg.protectPlayerNames = true;
            cfg.disableGoogleFallbackForAi = true;
            Translator fake = (text, language) -> {
                sent.add(text);
                return new TranslationResult(translateWords(text), "en");
            };
            MapStore oldDiskRows = new MapStore();
            TranslationCache seeder = new TranslationCache(fake, cfg.targetLang, Runnable::run,
                    100, 10_000L, now::get, oldDiskRows);
            String oldKey = legacyMask(seedSource).text();
            String seeded = seeder.translateBlocking(oldKey);
            assertNotNull(seeded, "fixture must be a valid real legacy cache translation: " + oldKey);
            assertNotEquals(oldKey, seeded, "fixture must seed translated wording, not KEEP/echo");
            assertFalse(oldDiskRows.entries().isEmpty(), "real cache must persist the seeded template");
            // A new instance has no prior LRU entries: the first service lookup must reuse disk rows.
            ai = new TranslationCache(fake, cfg.targetLang, Runnable::run, 100, 10_000L, now::get, oldDiskRows);
            machine = new TranslationCache(fake, cfg.targetLang, Runnable::run, 100, 10_000L, now::get);
            service = new TranslationService(cfg, machine, ai);
            service.setBatchWindowMs(() -> 0);
            service.setProtectedNames(List::of);
            applyShippedBootstrap(service, now::get);
            sent.clear(); // Seeding simulates an earlier paid session; only subsequent reuse is measured.
        }

        void flushAt(long time) {
            now.set(time);
            service.flushBatches();
            service.flushBatches(); // The ordinary collector's growth tick is unrelated to name resolution.
        }

        void assertHit(TranslationDecision decision, String source) {
            assertTrue(decision.changed(), "existing legacy row must be visible on the first lookup: " + source);
            // Existing Chinese display cleanup removes the number-to-CJK gap in this row.
            String expected = source.equals("1,234 behind shedos [#12]")
                    ? "1,234落後於 shedos [#12]" : visible(translateWords(source));
            assertEquals(expected, visible(decision.translated()));
        }

        void assertNoSendAndNoCachePending() {
            assertEquals(List.of(), sent, "existing cache rows must not enter the backend again");
            assertEquals(0, ai.pendingCount() + machine.pendingCount());
        }
    }
}
