package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ParagraphRecoveryTest {
    // The long Feast description from the reported tooltip, with live values masked.
    private static final List<String> FEAST = List.of(
            "Tiered Bonus: Feast (⟦MT0⟧/⟦MT1⟧)",
            "Farming Wild Rose, Sunflower, and",
            "Moonflower have a ⟦MT2⟧ chance of",
            "dropping a Helianthus. Inside the",
            "Greenhouse gives a ⟦MT3⟧ chance to",
            "drop one from any crop grown",
            "inside. Also combines the Tiered",
            "Bonuses of wearing ⟦MT4⟧ pieces of the",
            "Tater Armor, Cropie Armor, and",
            "Squash Armor. Grants ⟦MT5⟧ Farming",
            "Fortune.");
    private static final List<String> CHINESE = List.of(
            "套裝加成：盛宴（{mt0}/{mt1}）", "採收野玫瑰、向日葵與",
            "月光花時，有 {mt2} 機率", "掉落 Helianthus。在",
            "溫室內則有 {mt3} 機率", "從其中種植的任何作物",
            "獲得掉落物。同時享有穿著", "{mt4} 件下列裝備的套裝加成：",
            "馬鈴薯盔甲、Cropie 盔甲與", "Squash 盔甲。獲得 {mt5} 農耕", "幸運。");
    private static final Pattern ANCHOR = Pattern.compile("(?m)^(\\d{5}) (.*) (\\d{5})$");

    @Test
    void feastDisplaysBeforeOneIsolatedReviewThenAcceptsTheCompleteReplacement() {
        FeastTransport transport = new FeastTransport(false);
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(translator(transport), "zh-TW", tasks::add, 100);
        String source = ParagraphModel.join(FEAST);
        cache.warmBatchAsync(List.of(source), List.of("Feast Armor", source));
        tasks.remove().run();
        String readable = cache.getCached(source);
        assertNotNull(readable, "publish the first answer before waiting for another HTTP call");
        assertTrue(readable.contains("套裝加成：盛宴"));
        assertTrue(ParagraphModel.canReflowBreakLoss(source, readable));
        assertEquals(1, transport.prompts.size());
        assertEquals(1, tasks.size());
        tasks.remove().run();
        String reviewed = cache.getCached(source);
        assertTrue(TranslationCache.usableForBulkTransfer(source, reviewed));
        assertEquals(FEAST.size(), ParagraphModel.split(reviewed).size());
        assertTrue(reviewed.contains("⟦MT5⟧ 農耕"));
        assertEquals(2, transport.prompts.size());
        assertTrue(transport.prompts.get(1).contains("Feast Armor"), "retain surface context");
        assertEquals(1, transport.reviewUnits, "retry the whole paragraph alone");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void brokenReviewKeepsReadableTextWithoutLedgerRetriesEvenAfterRestart() {
        FeastTransport transport = new FeastTransport(true);
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        MemoryStore store = new MemoryStore();
        MemoryStore failures = new MemoryStore();
        TranslationCache cache = new TranslationCache(translator(transport), "zh-TW", tasks::add,
                100, 0, () -> Long.MAX_VALUE / 2, store);
        cache.setFailureStore(failures);
        String source = ParagraphModel.join(FEAST);
        cache.requestAsync(source);
        tasks.remove().run();
        String first = cache.getCached(source);
        assertNotNull(first);
        tasks.remove().run();
        assertEquals(first, cache.getCached(source));
        for (int i = 0; i < 20; i++) {
            cache.requestAsync(source);
            cache.flushBatch();
        }
        assertTrue(tasks.isEmpty());
        assertTrue(failures.values.isEmpty());
        assertEquals(2, transport.prompts.size());
        TranslationCache reopened = new TranslationCache(translator(transport), "zh-TW", tasks::add,
                100, 0, () -> Long.MAX_VALUE / 2, store);
        assertEquals(first, reopened.getCached(source));
        reopened.requestAsync(source);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void correctlyTranslatedFeastNeedsNoRecovery() throws Exception {
        FeastTransport transport = new FeastTransport(false) {
            @Override String firstReply() {
                String translated = ParagraphModel.join(CHINESE).replaceAll("⟦PB(\\d+)⟧", "{pb$1}");
                return "86001 " + translated + " 86002";
            }
        };
        TranslationResult result = translator(transport).translate(ParagraphModel.join(FEAST), "zh-TW");
        assertNull(result.failureReason());
        assertEquals(1, transport.prompts.size());
    }

    @Test
    void missingValueIsStillRejectedEvenWhenTheBreakLossWouldBeReadable() throws Exception {
        FeastTransport transport = new FeastTransport(false) {
            @Override String firstReply() {
                return super.firstReply().replace("{mt2}", "");
            }
        };
        TranslationResult result = translator(transport).translate(ParagraphModel.join(FEAST), "zh-TW");
        assertNotNull(result.failureReason());
        assertEquals("", result.translatedText());
        assertEquals(1, transport.prompts.size());
    }

    @Test
    void reflowDoesNotRelaxHardNewlinesColumnsOrInventedBreaks() {
        assertFalse(ParagraphModel.canReflowBreakLoss("A⟦PB0⟧B\nC", "甲乙丙"));
        assertFalse(ParagraphModel.canReflowBreakLoss("A⟦WS0⟧B⟦PB0⟧C", "甲⟦WS0⟧乙丙"));
        assertFalse(ParagraphModel.canReflowBreakLoss("A⟦PB0⟧B⟦PB1⟧C", "甲⟦PB2⟧乙丙"));
        assertFalse(ParagraphModel.canReflowBreakLoss("A⟦PB0⟧B⟦PB1⟧C", "甲⟦PB1⟧乙⟦PB0⟧丙"));
    }

    @Test
    void invalidationWhileReviewIsQueuedCannotRestoreTheOldParagraph() {
        FeastTransport transport = new FeastTransport(false);
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(translator(transport), "zh-TW", tasks::add, 100);
        String source = ParagraphModel.join(FEAST);
        cache.requestAsync(source);
        tasks.remove().run();
        cache.invalidate(source);
        tasks.remove().run();
        assertNull(cache.getCached(source));
        assertEquals(1, transport.prompts.size());
    }

    @Test
    void reviewNetworkFailureKeepsTheFirstAnswerAndDoesNotRetry() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        int[] calls = {0};
        Translator backend = (source, language) -> {
            if (++calls[0] == 2) throw new TranslationException("network unavailable");
            return new TranslationResult("第一行第二行", null);
        };
        TranslationCache cache = new TranslationCache(backend, "zh-TW", tasks::add, 100);
        String source = "First line⟦PB0⟧Second line";
        cache.requestAsync(source);
        tasks.remove().run();
        assertEquals("第一行第二行", cache.getCached(source));
        tasks.remove().run();
        for (int i = 0; i < 20; i++) {
            cache.requestAsync(source);
            cache.flushBatch();
        }
        assertEquals("第一行第二行", cache.getCached(source));
        assertEquals(2, calls[0]);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void lostProtectedNameOrColourPairCannotUseReadableReflow() throws Exception {
        for (String source : List.of("Hello ⟦0⟧⟦PB0⟧World",
                "⟦CS0⟧Hello⟦/CS0⟧⟦PB0⟧World")) {
            HttpTransport backend = new HttpTransport() {
                @Override public String get(String url) { throw new UnsupportedOperationException(); }
                @Override public String post(String url, String body, Map<String, String> headers) {
                    return "{\"choices\":[{\"message\":{\"content\":\"86001 哈囉世界 86002\"}}]}";
                }
            };
            TranslationResult result = translator(backend).translate(source, "zh-TW");
            assertNotNull(result.failureReason());
            assertEquals("", result.translatedText());
        }
    }

    private static OpenAiTranslator translator(HttpTransport transport) {
        return new OpenAiTranslator(transport,
                () -> new AiSettings("https://example.test/v1", "test", List.of("test-key")),
                RequestPacer.disabled());
    }

    private static class FeastTransport implements HttpTransport {
        final List<String> prompts = new ArrayList<>();
        int reviewUnits;
        private final boolean breakRecovery;

        FeastTransport(boolean breakRecovery) { this.breakRecovery = breakRecovery; }

        String firstReply() {
            // Reproduce the reported failure: the model merges the last wrapped phrase.
            String text = ParagraphModel.join(CHINESE).replaceAll("⟦PB(\\d+)⟧", "{pb$1}");
            return "86001 " + text.replace("{pb9}", "") + " 86002";
        }

        @Override public String get(String url) { throw new UnsupportedOperationException(); }

        @Override public String post(String url, String body, Map<String, String> headers) {
            JsonArray messages = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages");
            String prompt = messages.get(1).getAsJsonObject().get("content").getAsString();
            prompts.add(prompt);
            String reply;
            if (prompts.size() == 1) reply = firstReply();
            else {
                Matcher matcher = ANCHOR.matcher(prompt);
                StringBuilder out = new StringBuilder();
                while (matcher.find()) {
                    reviewUnits++;
                    String text = ParagraphModel.join(CHINESE).replaceAll("⟦PB(\\d+)⟧", "{pb$1}");
                    if (breakRecovery) text = text.replace("{pb9}", "");
                    out.append(matcher.group(1)).append(' ').append(text)
                            .append(' ').append(matcher.group(3));
                }
                reply = out.toString();
            }
            JsonObject message = new JsonObject();
            message.addProperty("content", reply);
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject response = new JsonObject();
            response.add("choices", choices);
            return response.toString();
        }
    }

    private static final class MemoryStore implements PersistentStore {
        final Map<String, String> values = new HashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void clear() { values.clear(); }
        @Override public void remove(String key) { values.remove(key); }
    }
}
