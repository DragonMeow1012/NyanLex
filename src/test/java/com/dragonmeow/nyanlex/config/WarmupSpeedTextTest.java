package com.dragonmeow.nyanlex.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pre-translation speed sentence reads right at exactly one item or one minute, in every language. */
class WarmupSpeedTextTest {

    private static final List<String> LANGS = List.of("en_us", "zh_tw", "zh_cn", "zh_hk");

    private static String render(String code, String key, Object... args) {
        try (InputStream in = WarmupSpeedTextTest.class.getResourceAsStream("/assets/nyanlex/lang/" + code + ".json")) {
            assertNotNull(in, "missing lang file " + code);
            JsonObject json = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            assertTrue(json.has(key), code + " has " + key);
            return String.format(Locale.ROOT, json.get(key).getAsString(), args);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void keyFollowsWhichOfTheTwoNumbersIsExactlyOne() {
        assertEquals("screen.nyanlex.warmup.progress.speed", WarmupSpeedText.progressKey(42, 3));
        assertEquals("screen.nyanlex.warmup.progress.speed.minute_one", WarmupSpeedText.progressKey(42, 1));
        assertEquals("screen.nyanlex.warmup.progress.speed.item_one", WarmupSpeedText.progressKey(1, 3));
        assertEquals("screen.nyanlex.warmup.progress.speed.item_one_minute_one", WarmupSpeedText.progressKey(1, 1));
        assertEquals("screen.nyanlex.warmup.state.running.speed", WarmupSpeedText.stateKey(42, 3));
        assertEquals("screen.nyanlex.warmup.state.running.speed.minute_one", WarmupSpeedText.stateKey(42, 1));
        assertEquals("screen.nyanlex.warmup.state.running.speed.item_one", WarmupSpeedText.stateKey(1, 3));
        assertEquals("screen.nyanlex.warmup.state.running.speed.item_one_minute_one", WarmupSpeedText.stateKey(1, 1));
        // 0, 11 and 21 are plural; only exactly 1 is singular
        assertEquals("screen.nyanlex.warmup.progress.speed", WarmupSpeedText.progressKey(11, 21));
    }

    @Test
    void englishUsesTheSingularOnlyForExactlyOne() {
        assertEquals("About 42 entries per minute, about 3 minutes left",
                render("en_us", WarmupSpeedText.progressKey(42, 3), 42, 3));
        assertEquals("About 42 entries per minute, about 1 minute left",
                render("en_us", WarmupSpeedText.progressKey(42, 1), 42, 1));
        assertEquals("About 1 entry per minute, about 3 minutes left",
                render("en_us", WarmupSpeedText.progressKey(1, 3), 1, 3));
        assertEquals("About 1 entry per minute, about 1 minute left",
                render("en_us", WarmupSpeedText.progressKey(1, 1), 1, 1));
        assertEquals("Running, about 42 entries per minute, about 1 minute left",
                render("en_us", WarmupSpeedText.stateKey(42, 1), 42, 1));
        assertEquals("Running, about 1 entry per minute, about 1 minute left",
                render("en_us", WarmupSpeedText.stateKey(1, 1), 1, 1));
        assertEquals("Running, about 1 entry per minute, about 12 minutes left",
                render("en_us", WarmupSpeedText.stateKey(1, 12), 1, 12));
    }

    @Test
    void noLanguageEverPrintsOneFollowedByAPluralNoun() {
        for (String code : LANGS) {
            for (long rate : new long[] {1, 2, 40}) {
                for (long eta : new long[] {1, 2, 40}) {
                    String progress = render(code, WarmupSpeedText.progressKey(rate, eta), rate, eta);
                    String state = render(code, WarmupSpeedText.stateKey(rate, eta), rate, eta);
                    for (String text : new String[] {progress, state}) {
                        assertFalse(text.contains(" 1 items"), code + ": " + text);
                        assertFalse(text.contains(" 1 minutes"), code + ": " + text);
                        assertFalse(text.matches(".* (about )?1 entry per minute.*") && rate != 1, code + ": " + text);
                    }
                }
            }
        }
    }

    @Test
    void chineseReadsTheSameWhateverTheNumbersAre() {
        for (String code : List.of("zh_tw", "zh_cn", "zh_hk")) {
            String progress = render(code, "screen.nyanlex.warmup.progress.speed", 7, 9);
            String state = render(code, "screen.nyanlex.warmup.state.running.speed", 7, 9);
            for (long[] n : new long[][] {{1, 1}, {1, 9}, {7, 1}}) {
                String p = render(code, WarmupSpeedText.progressKey(n[0], n[1]), n[0], n[1]);
                String s = render(code, WarmupSpeedText.stateKey(n[0], n[1]), n[0], n[1]);
                assertEquals(String.format(Locale.ROOT, progress.replace("7", "%d").replace("9", "%d"), n[0], n[1]), p, code);
                assertEquals(String.format(Locale.ROOT, state.replace("7", "%d").replace("9", "%d"), n[0], n[1]), s, code);
            }
        }
    }
}
