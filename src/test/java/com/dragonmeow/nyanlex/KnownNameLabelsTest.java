package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.KnownNameLabels;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.warmup.WarmupCategory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class KnownNameLabelsTest {
    private static final List<String> MODS = List.of("Sodium", "Iris", "XaeroPlus", "Continuity", "Aether");
    private static final List<String> LABELS = List.of("Sodium", "XaeroPlus", "Continuity",
            "Euphoria Patches 1.8.6", "labPBR/seuspbr 材質",
            "Complementary Shaders r5.7.1 + Euphoria Patches 1.8.6",
            "Iris 1.8.12-snapshot+mc1.21.1-local",
            "§aIris §f1.8.12-snapshot+mc1.21.1-local",
            "⟦CS0⟧Sodium⟦/CS0⟧", "Euphoria Patches ⟦MT0⟧");

    @Test void onlyKnownLabelsAndTheirVersionSuffixesAreExempt() {
        KnownNameLabels labels = new KnownNameLabels(MODS);
        for (String label : LABELS) assertTrue(labels.keepsOriginal(label, "zh-tw"), label);
        for (String sentence : List.of("Iron Sword", "Sodium settings", "Iris is disabled",
                "Aether Portal", "Sodiumized", "Unknown Brand", "Complementary Shaders are enabled")) {
            assertFalse(labels.keepsOriginal(sentence, "zh-tw"), sentence);
        }
        assertFalse(labels.keepsOriginal("labPBR 材質", "fr"), "localized residue still follows the target language");
    }

    @Test void renderingAsyncAndWarmupNeverSendKnownLabelsOrCreateFailedDebugRows() {
        for (boolean useAi : new boolean[] {true, false}) {
            AtomicInteger calls = new AtomicInteger();
            TranslatorConfig cfg = TestConfigs.translating();
            cfg.aiScreenText = cfg.aiTooltip = useAi;
            TranslationCache ai = cache(cfg, calls);
            TranslationCache machine = cache(cfg, calls);
            TranslationDebugLog log = new TranslationDebugLog(() -> true);
            ai.setDebugLog("AI", log);
            machine.setDebugLog("GT", log);
            TranslationService service = new TranslationService(cfg, machine, ai);
            service.setKnownNames(MODS);
            for (int scan = 0; scan < 4; scan++) {
                for (String label : LABELS) {
                    assertFalse(service.translateScreenText(label).changed());
                    service.requestScreenTextAsync(label, ignored -> {});
                    service.requestLiveScreenTextAsync(label, ignored -> {});
                    assertTrue(service.isWarmupTranslationReady(WarmupCategory.SCREEN, label), label);
                }
                service.warmContentBatchBackground(WarmupCategory.SCREEN, LABELS);
                service.warmNamesBatch(LABELS);
                service.flushBatches();
            }
            assertEquals(0, calls.get());
            assertEquals(0, service.pendingCount());
            assertTrue(log.snapshot(100).isEmpty());
            assertTrue(ai.exportTranslations().isEmpty());
            assertTrue(machine.exportTranslations().isEmpty());
        }
    }

    @Test void cachedAndDownloadedChineseNamesWinOverKeepingTheOriginal() {
        for (boolean useAi : new boolean[] {true, false}) {
            for (String tier : List.of("hub", "ai", "machine")) {
                AtomicInteger calls = new AtomicInteger();
                TranslatorConfig cfg = TestConfigs.translating();
                cfg.aiScreenText = cfg.aiTooltip = useAi;
                TranslationCache ai = cache(cfg, calls);
                TranslationCache machine = cache(cfg, calls);
                if (tier.equals("ai")) ai.translateBlocking("Aether");
                if (tier.equals("machine")) machine.translateBlocking("Aether");
                TranslationService service = new TranslationService(cfg, machine, ai);
                service.setKnownNames(MODS);
                if (tier.equals("hub")) service.setHubLookup(key -> "Aether".equals(key) ? "天堂" : null);
                calls.set(0);
                assertEquals("天堂", service.translateScreenText("Aether").translated());
                AtomicReference<String> shown = new AtomicReference<>();
                service.requestScreenTextAsync("Aether", shown::set);
                assertEquals("天堂", shown.get());
                service.warmContentBatchBackground(WarmupCategory.SCREEN, List.of("Aether"));
                service.flushBatches();
                assertEquals(0, calls.get());
            }
        }
    }

    @Test void explicitlyRetranslatingAKnownNameStillRequestsFreshWording() {
        AtomicInteger calls = new AtomicInteger();
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true;
        TranslationService service = new TranslationService(cfg, cache(cfg, calls), cache(cfg, calls));
        service.setKnownNames(MODS);
        service.setHubLookup(key -> "Aether".equals(key) ? "天堂" : null);
        service.retranslate(List.of("Aether"));
        service.flushBatches();
        service.flushBatches();
        assertEquals(1, calls.get());
    }

    private static TranslationCache cache(TranslatorConfig cfg, AtomicInteger calls) {
        return new TranslationCache((text, lang) -> {
            calls.incrementAndGet();
            return new TranslationResult("Aether".equals(text) ? "天堂" : "翻譯", "en");
        }, cfg.targetLang, Runnable::run, 1000);
    }
}
