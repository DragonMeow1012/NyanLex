package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.hub.HubFile;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.hub.HubLocalCache;
import com.dragonmeow.nyanlex.hub.HubSource;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository rows are keyed like the translation cache's own rows: number-normalized
 * ({@code ⟦MT#⟧}) and, for a string drawn with legacy colour codes, by the plain text. Every
 * translator here is an inline fake; no network is touched.
 */
class HubNumberAndColourLookupTest {
    // Expected strings are the DISPLAYED text: the display pass tightens spaces next to CJK.
    private static final Executor DIRECT = Runnable::run;
    private static final String S = "§";

    private final AtomicInteger requests = new AtomicInteger();

    private TranslationService service(Map<String, String> hub) {
        Translator counting = (text, target) -> {
            requests.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiScreenText = true;
        cfg.aiTooltip = true;
        TranslationCache gt = new TranslationCache(counting, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(counting, cfg.targetLang, DIRECT, 1000);
        TranslationService service = new TranslationService(cfg, gt, ai);
        service.setHubLookup(hub::get);
        return service;
    }

    @Test
    void numberRowHitsAndFillsTheNumbersOnScreen() {
        TranslationService service = service(Map.of("Render distance: ⟦MT0⟧ chunks", "渲染距離：⟦MT0⟧ 區塊"));
        assertEquals("渲染距離：12區塊", service.translateScreenText("Render distance: 12 chunks").translated());
        assertEquals("渲染距離：7區塊", service.translateScreenText("Render distance: 7 chunks").translated());
        assertEquals("渲染距離：1,500區塊", service.translateScreenText("Render distance: 1,500 chunks").translated());
        assertEquals(0, requests.get(), "a repository hit never sends a request");
    }

    @Test
    void reorderedNumbersAreFilledIntoTheRightSlots() {
        TranslationService service = service(Map.of(
                "Use ⟦MT0⟧ of ⟦MT1⟧ slots", "共 ⟦MT1⟧ 個欄位中使用 ⟦MT0⟧ 個"));
        assertEquals("共9個欄位中使用3個", service.translateScreenText("Use 3 of 9 slots").translated());
        assertEquals("共64個欄位中使用12個", service.translateScreenText("Use 12 of 64 slots").translated());
    }

    @Test
    void serverStyleRowKeepsItsOwnNumbersAndPercent() {
        TranslationService service = service(Map.of(
                "Starting bid: ⟦MT0⟧ coins", "起標價：⟦MT0⟧ 金幣",
                "Brightness: ⟦MT0⟧", "亮度：⟦MT0⟧"));
        assertEquals("起標價：2,450金幣", service.translateItemLine("Starting bid: 2,450 coins").translated());
        assertEquals("亮度：80%", service.translateScreenText("Brightness: 80%").translated());
    }

    @Test
    void exactRowStillWinsAndAMissStaysUnchanged() {
        Map<String, String> hub = new LinkedHashMap<>();
        hub.put("Render distance: 12 chunks", "精確列");
        hub.put("Render distance: ⟦MT0⟧ chunks", "渲染距離：⟦MT0⟧ 區塊");
        TranslationService service = service(hub);
        assertEquals("精確列", service.translateScreenText("Render distance: 12 chunks").translated());
        TranslationDecision miss = service.translateScreenText("Unrelated sentence about nothing");
        assertFalse(miss.changed());
    }

    @Test
    void legacyColourStringHitsByPlainTextAndKeepsItsCode() {
        TranslationService service = service(Map.of("Use Fog Occlusion", "使用霧氣遮蔽"));
        TranslationDecision decision = service.translateScreenString(S + "fUse Fog Occlusion");
        assertTrue(decision.changed());
        assertEquals(S + "f使用霧氣遮蔽", decision.translated());
        assertEquals(S + "l" + S + "6使用霧氣遮蔽" + S + "r",
                service.translateScreenString(S + "l" + S + "6Use Fog Occlusion" + S + "r").translated());
        assertEquals("使用霧氣遮蔽", service.translateScreenString("Use Fog Occlusion").translated());
        assertFalse(service.translateScreenString(S + "fUnknown option label").changed());
    }

    @Test
    void stringWithSeveralColourRunsStillGoesThroughTheOrdinaryPath() {
        TranslationService service = service(Map.of("Use Fog Occlusion", "使用霧氣遮蔽"));
        assertFalse(service.translateScreenString(S + "fUse " + S + "cFog Occlusion").changed(),
                "mixed runs are not rewritten by the single-run shortcut");
    }

    @Test
    void multiColourComponentHitsAndEveryRunKeepsItsColour() {
        TranslationService service = service(Map.of(
                "⟦CS0⟧Red⟦/CS0⟧ ⟦CS1⟧Green text here⟦/CS1⟧",
                "⟦CS0⟧紅色⟦/CS0⟧ ⟦CS1⟧綠色的文字⟦/CS1⟧"));
        Component shown = FabricTextStyle.renderTranslated("screenText",
                Component.literal(S + "cRed " + S + "aGreen text here"), service::translateScreenText);
        assertEquals("紅色 綠色的文字", shown.getString());
        List<String> runs = new ArrayList<>();
        shown.visit((style, text) -> {
            if (!text.isBlank()) {
                runs.add(text.strip() + "=" + (style.getColor() == null ? "none" : style.getColor().serialize()));
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        assertEquals(List.of("紅色=#FF5555", "綠色的文字=#55FF55"), runs);
    }

    @Test
    void numberRowThroughTheLocalCacheFile(@TempDir Path dir) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(HubKeyHash.of("Chunks: ⟦MT0⟧"), "區塊：⟦MT0⟧");
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        cache.mergeFromFile(new HubFile("zh-TW", entries), HubSource.mod("examplemod"));
        TranslationService service = service(Map.of());
        service.setHubLookup(cache::get);
        assertEquals("區塊：32", service.translateScreenText("Chunks: 32").translated());
    }
}
