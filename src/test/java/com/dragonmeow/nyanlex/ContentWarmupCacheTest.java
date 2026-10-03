package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.warmup.WarmupCategory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContentWarmupCacheTest {
    @Test
    void screenWarmupReusesHubAndFeedsTheLiveQuestLookupWithoutNewRequests() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiScreenText = true;
        cfg.aiTooltip = false;
        cfg.warmupItems = false;
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> answers = Map.of("Into the sky", "飛向天空", "Find the portal", "尋找傳送門");
        TranslationCache ai = new TranslationCache((text, lang) -> {
            calls.incrementAndGet();
            return new TranslationResult(answers.get(text), "en");
        }, cfg.targetLang, Runnable::run, 1000);
        TranslationCache machine = new TranslationCache((text, lang) -> {
            fail("screen warm-up must use the selected screen engine");
            return new TranslationResult(text, "en");
        }, cfg.targetLang, Runnable::run, 1000);
        TranslationService s = new TranslationService(cfg, machine, ai);
        s.setHubLookup(key -> "Find the portal".equals(key) ? "尋找傳送門" : null);
        assertTrue(s.isContentWarmupEngine());
        List<String> sources = List.of("Into the sky", "Find the portal");
        assertTrue(s.isWarmupTranslationReady(WarmupCategory.SCREEN, sources.get(1)));
        s.warmContentBatchBackground(WarmupCategory.SCREEN, sources);
        s.flushBatches();
        assertEquals(1, calls.get());
        for (String source : sources) {
            assertTrue(s.isWarmupTranslationReady(WarmupCategory.SCREEN, source));
            assertFalse(s.isWarmupTranslationPending(WarmupCategory.SCREEN, source));
            assertEquals(answers.get(source), s.translateScreenText(source).translated());
            s.requestLiveScreenTextAsync(source, ignored -> {});
        }
        s.warmContentBatchBackground(WarmupCategory.SCREEN, sources);
        s.flushBatches();
        assertEquals(1, calls.get(), "opening or warming the same quest uses the existing translations");
        cfg.warmupItems = true;
        assertFalse(s.isContentWarmupEngine(), "selected categories keep their engine setting");
    }
}
