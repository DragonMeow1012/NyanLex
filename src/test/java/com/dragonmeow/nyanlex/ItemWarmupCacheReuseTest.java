package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubFile;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.hub.HubLocalCache;
import com.dragonmeow.nyanlex.hub.HubSource;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ItemWarmupCacheReuseTest {
    @Test
    void openAiWarmupSurvivesLiveGeminiSwitchHubDownloadAndCacheReload(@TempDir Path dir) {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true;
        cfg.aiBaseUrl = "https://api.openai.com/v1";
        cfg.aiModel = "openai-test";
        Map<String, String> names = Map.of("Leafcutter Ant", "切葉蟻", "Cachalot Whale", "抹香鯨",
                "Birch Flower Box", "樺木花箱");
        List<String> requests = new ArrayList<>();
        HttpTransport transport = new HttpTransport() {
            @Override public String get(String url) { throw new AssertionError("unexpected GET"); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                requests.add(url);
                String prompt = JsonParser.parseString(body).getAsJsonObject()
                        .getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
                var matcher = Pattern.compile("(?m)^(\\d{5}) (.*) (\\d{5})$").matcher(prompt);
                StringBuilder reply = new StringBuilder();
                while (matcher.find()) {
                    String translated = names.get(matcher.group(2));
                    assertNotNull(translated, "unexpected translation unit: " + matcher.group(2));
                    reply.append(matcher.group(1)).append(translated).append(matcher.group(3)).append('\n');
                }
                JsonObject response = JsonParser.parseString(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}").getAsJsonObject();
                response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message")
                        .addProperty("content", reply.toString());
                return response.toString();
            }
        };
        OpenAiTranslator translator = new OpenAiTranslator(transport,
                () -> new AiSettings(cfg.aiBaseUrl, cfg.aiModel, List.of("test-key")));
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, Runnable::run, 1000,
                10_000, System::currentTimeMillis,
                new LanguageFileStore(dir, "nyanlex-ai-cache", cfg.targetLang));
        TranslationCache machine = new TranslationCache(translator, cfg.targetLang, Runnable::run, 1000);
        TranslationService service = new TranslationService(cfg, machine, ai);
        HubLocalCache hub = new HubLocalCache(dir, cfg.targetLang);
        service.setHubLookup(hub::get);
        List<String> sources = List.copyOf(names.keySet());
        service.warmTooltipBatchBackground(sources);
        assertEquals(1, requests.size(), "all-item warmup must finish one actual AI batch");
        assertTrue(requests.get(0).startsWith("https://api.openai.com/"));
        sources.forEach(source -> assertTrue(service.isTooltipTranslationReady(source)));
        assertEquals(names, ai.exportTranslations());

        cfg.aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
        cfg.aiModel = "gemini-test";
        // Download after warmup: an existing AI answer wins; a new hub-only name is ready too.
        hub.mergeFromFile(new HubFile(cfg.targetLang, Map.of(
                HubKeyHash.of("Birch Flower Box"), "倉庫花箱",
                HubKeyHash.of("Bamboo Shutter"), "竹百葉窗")), HubSource.mod("another_furniture"));
        List<String> visible = new ArrayList<>(sources);
        visible.add("Bamboo Shutter");
        TranslationDebugLog debug = new TranslationDebugLog(() -> true);
        ai.setDebugLog("AI", debug);
        visible.forEach(source -> service.registerItemEntity(source, null, null));
        for (int scan = 0; scan < 3; scan++) {
            service.warmNamesBatch(visible);
            service.warmTooltipBatch(visible);
            service.warmTooltipBatchBackground(visible);
            service.flushBatches();
            service.flushBatches();
            for (String source : visible) {
                assertEquals(names.getOrDefault(source, "竹百葉窗"), service.translateItemLine(source).translated());
                assertTrue(service.isTooltipTranslationReady(source));
            }
        }
        assertEquals(1, requests.size(), "switching AI provider or downloading the hub must not resend");
        assertEquals(0, service.pendingCount());
        assertTrue(debug.snapshot(100).isEmpty());
        assertEquals(names, ai.exportTranslations(), "hub rows never enter the AI export");

        TranslationCache reloaded = new TranslationCache(translator, cfg.targetLang, Runnable::run, 1000,
                10_000, System::currentTimeMillis,
                new LanguageFileStore(dir, "nyanlex-ai-cache", cfg.targetLang));
        sources.forEach(source -> assertEquals(names.get(source), reloaded.getCached(source)));
        reloaded.warmBatchAsync(sources);
        assertEquals(1, requests.size(), "the same provider-independent cache also survives restart");
    }
}
