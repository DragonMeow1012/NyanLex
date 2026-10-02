package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.GoogleFreeTranslator;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.UrlHttpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in smoke test for an actual OpenAI-compatible endpoint. It is skipped in
 * normal builds and never reads or stores a credential file itself.
 */
class LiveGeminiSmokeTest {

    @Test
    void translatesOneStrictGoogleBatchAndRestoresCompleteItems() {
        assumeTrue("1".equals(System.getenv("NYANLEX_LIVE_GOOGLE_GT")),
                "live Google GT test is intentionally opt-in");
        List<String> source = List.of(
                "Quest Title: The First Workshop",
                "Collect 3 Iron Ingots\nReward: 100 coins"
        );

        List<TranslationResult> translated = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> new GoogleFreeTranslator(
                        new UrlHttpTransport(Duration.ofSeconds(15)), "auto")
                        .translateBatch(source, "zh-TW"));

        assertCompleteBatch(source, translated);
    }

    @Test
    void translatesOneStrictBatchAndRestoresCompleteItems() {
        String baseUrl = System.getenv("NYANLEX_LIVE_BASE_URL");
        String model = System.getenv("NYANLEX_LIVE_MODEL");
        String apiKey = System.getenv("NYANLEX_LIVE_API_KEY");
        assumeTrue(baseUrl != null && !baseUrl.isBlank()
                        && model != null && !model.isBlank()
                        && apiKey != null && !apiKey.isBlank(),
                "live endpoint variables are intentionally absent");

        List<String> source = List.of(
                "Quest Title: The First Workshop",
                "Collect 3 Iron Ingots\nReward: 100 coins"
        );

        List<TranslationResult> translated = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> new OpenAiTranslator(
                        new UrlHttpTransport(Duration.ofSeconds(15)),
                        () -> new AiSettings(baseUrl, model, List.of(apiKey)))
                        .translateBatch(source, "zh-TW"));

        assertCompleteBatch(source, translated);
    }

    private static void assertCompleteBatch(List<String> source,
                                            List<TranslationResult> translated) {
        assertEquals(source.size(), translated.size(), "one result must be restored per input item");
        for (int i = 0; i < source.size(); i++) {
            String actual = translated.get(i).translatedText();
            assertFalse(actual == null || actual.isBlank(), "translated item " + i + " is blank");
            assertNotEquals(source.get(i), actual, "translated item " + i + " stayed unchanged");
            assertEquals(newlineCount(source.get(i)), newlineCount(actual),
                    "paragraph boundaries changed for item " + i);
            assertFalse(actual.matches("(?s).*86\\d{3}.*"), "batch anchors leaked into item " + i);
        }
    }

    private static long newlineCount(String value) {
        return value.chars().filter(ch -> ch == '\n').count();
    }
}
