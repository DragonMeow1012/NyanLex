package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item warm-up fills each request up to the shared budget, so requests carry many more
 * units than before. Token protection (placeholders, colour pairs) is validated per unit, and a
 * unit that fails is re-asked alone: a big batch does not make a failure cost more than a small one.
 */
class LargeBatchTokenGuardTest {

    private static final int UNITS = 30;
    private static final Pattern TOKEN = Pattern.compile("⟦[^⟦⟧]*⟧");

    private static String source(int i) {
        return "Item " + i + " ⟦CS0⟧Rare⟦/CS0⟧ gives ⟦MT0⟧ health and ⟦MT1⟧ armor";
    }

    private static String translated(int i, boolean dropValue) {
        return "物品 " + i + " ⟦CS0⟧稀有⟦/CS0⟧ 提供 " + (dropValue ? "" : "⟦MT0⟧ ") + "生命與 ⟦MT1⟧ 護甲";
    }

    private static String reply(List<String> unitTexts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < unitTexts.size(); i++) {
            int open = OpenAiTranslator.BATCH_ANCHOR_BASE + i * 2;
            if (i > 0) out.append("\\n");
            out.append(open).append(unitTexts.get(i)).append(open + 1);
        }
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + out + "\"}}]}";
    }

    private static OpenAiTranslator translator(HttpTransport transport) {
        return new OpenAiTranslator(transport, () -> new AiSettings("https://x/v1", "m", List.of("k")));
    }

    private static List<String> sources() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < UNITS; i++) list.add(source(i));
        return list;
    }

    private static int count(String text) {
        Matcher m = TOKEN.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void aFullBatchOfProtectedUnitsComesBackIntactInOneRequest() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                posts.incrementAndGet();
                List<String> all = new ArrayList<>();
                for (int i = 0; i < UNITS; i++) all.add(translated(i, false));
                return reply(all);
            }
        };
        List<TranslationResult> results = translator(fake).translateBatch(sources(), "zh-TW");

        assertEquals(1, posts.get(), UNITS + " units are one physical request");
        assertEquals(UNITS, results.size());
        for (int i = 0; i < UNITS; i++) {
            TranslationResult r = results.get(i);
            assertNull(r.failureReason(), "unit " + i);
            assertEquals(count(source(i)), count(r.translatedText()), "every token of unit " + i + " kept");
            assertTrue(r.translatedText().contains("⟦CS0⟧") && r.translatedText().contains("⟦/CS0⟧"));
        }
    }

    @Test
    void oneUnitThatLosesAPlaceholderIsRetriedAloneAndTheRestOfTheBatchIsUntouched() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                bodies.add(body);
                if (posts.incrementAndGet() == 1) {
                    List<String> all = new ArrayList<>();
                    for (int i = 0; i < UNITS; i++) all.add(translated(i, i == 17)); // unit 17 drops its first value
                    return reply(all);
                }
                return reply(List.of(translated(17, false))); // the isolated retry of unit 17 alone
            }
        };
        List<TranslationResult> results = translator(fake).translateBatch(sources(), "zh-TW");

        assertEquals(2, posts.get(), "one batch request plus exactly one single-unit retry, not a resend of " + UNITS);
        for (int i = 0; i < UNITS; i++) {
            assertNull(results.get(i).failureReason(), "unit " + i);
            assertEquals(count(source(i)), count(results.get(i).translatedText()), "tokens of unit " + i);
        }
        assertEquals(translated(17, false), results.get(17).translatedText(), "recovered by the retry");
        String retryBody = bodies.get(1);
        assertTrue(retryBody.contains("Item 17"), "the retry carries the broken unit");
        assertFalse(retryBody.contains("Item 16") || retryBody.contains("Item 18"),
                "and nothing else of the batch");
    }

    @Test
    void aUnitThatStaysBrokenFailsAloneWithoutSpoilingItsNeighbours() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                if (posts.incrementAndGet() == 1) {
                    List<String> all = new ArrayList<>();
                    for (int i = 0; i < UNITS; i++) all.add(translated(i, i == 5));
                    return reply(all);
                }
                return reply(List.of(translated(5, true))); // the retry is still wrong
            }
        };
        List<TranslationResult> results = translator(fake).translateBatch(sources(), "zh-TW");

        assertEquals(2, posts.get());
        int failed = 0;
        for (int i = 0; i < UNITS; i++) {
            if (results.get(i).failureReason() != null) {
                failed++;
                assertEquals(5, i, "only the broken unit fails");
            }
        }
        assertEquals(1, failed);
    }
}
