package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.AiSettings;
import com.dragonmeow.mctranslator.translate.HttpTransport;
import com.dragonmeow.mctranslator.translate.OpenAiTranslator;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiTranslatorTest {

    /** Inline fake transport: returns canned chat-completions JSON; records the request. */
    private static String chatJson(String content) {
        // content is embedded with escaped newlines
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + content.replace("\n", "\\n") + "\"}}]}";
    }

    @Test
    void translateBatchSendsNumberedPromptAndParsesNumberedReply() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                assertTrue(url.endsWith("/chat/completions"), "url: " + url);
                assertEquals("Bearer key-1", headers.get("Authorization"));
                return chatJson("1. 你好\n2. 世界");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));

        List<TranslationResult> out = t.translateBatch(List.of("Hello", "World"), "zh-TW");

        assertEquals(2, out.size());
        assertEquals("你好", out.get(0).translatedText());
        assertEquals("世界", out.get(1).translatedText());
        // the request carried both lines (context) numbered
        assertTrue(sentBodies.get(0).contains("1. Hello"), sentBodies.get(0));
        assertTrue(sentBodies.get(0).contains("2. World"), sentBodies.get(0));
        assertTrue(sentBodies.get(0).contains("gpt-4o-mini"));
    }

    @Test
    void rotatesToNextKeyWhenFirstFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                calls.incrementAndGet();
                if ("Bearer bad".equals(headers.get("Authorization"))) throw new IOException("HTTP 401");
                return chatJson("1. 哈囉");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://api.openai.com/v1", "m", List.of("bad", "good")));

        TranslationResult r = t.translate("Hi", "zh-TW");
        assertEquals("哈囉", r.translatedText());
        assertEquals(2, calls.get(), "should have tried the bad key then the good key");
    }

    @Test
    void throwsWhenAllKeysFail() {
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                throw new IOException("HTTP 429");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("a", "b")));
        assertThrows(TranslationException.class, () -> t.translate("Hi", "zh-TW"));
    }

    @Test
    void throwsWhenNotConfigured() {
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "", List.of())); // no model, no keys
        assertThrows(TranslationException.class, () -> t.translate("Hi", "zh-TW"));
    }

    @Test
    void tooFewLinesArePaddedNotThrown() throws Exception {
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 只有一行"); // only one line for a two-line request
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("a", "b"), "zh-TW");
        assertEquals(2, r.size());
        assertEquals("只有一行", r.get(0).translatedText());
        assertEquals("", r.get(1).translatedText(), "missing item padded to empty, not whole-batch failure");
    }

    @Test
    void wrappedLinesAreReassembledToTheirNumber() throws Exception {
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                // item 1's translation wraps across two physical lines, with a blank line before item 2
                return chatJson("1. 第一段\n續行\n\n2. 第二段");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("a", "b"), "zh-TW");
        assertEquals("第一段 續行", r.get(0).translatedText());
        assertEquals("第二段", r.get(1).translatedText());
    }

    @Test
    void urlJoinToleratesTrailingSlashAndFullPath() {
        assertEquals("https://x/v1/chat/completions", OpenAiTranslator.chatCompletionsUrl("https://x/v1"));
        assertEquals("https://x/v1/chat/completions", OpenAiTranslator.chatCompletionsUrl("https://x/v1/"));
        assertEquals("https://x/v1/chat/completions",
                OpenAiTranslator.chatCompletionsUrl("https://x/v1/chat/completions"));
    }

    @Test
    void geminiFlashDisablesReasoningTokens() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 嗨");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://g/v1beta/openai", "gemini-2.5-flash-lite", List.of("k")));
        t.translate("Hi", "zh-TW");
        assertTrue(sentBodies.get(0).contains("\"reasoning_effort\":\"none\""), sentBodies.get(0));
    }

    @Test
    void nonGeminiModelsDoNotSendReasoningEffort() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 嗨");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("k")));
        t.translate("Hi", "zh-TW");
        assertTrue(!sentBodies.get(0).contains("reasoning_effort"), sentBodies.get(0));
    }

    @Test
    void reasoningEffortRejectionFallsBackToPlainBody() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                sentBodies.add(body);
                if (body.contains("reasoning_effort")) throw new IOException("HTTP 400 unknown field");
                return chatJson("1. 嗨");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://proxy/v1", "gemini-2.5-flash", List.of("k")));
        TranslationResult r = t.translate("Hi", "zh-TW");
        assertEquals("嗨", r.translatedText());
        assertEquals(2, sentBodies.size(), "must retry once without the reasoning override");
        assertTrue(!sentBodies.get(1).contains("reasoning_effort"));
    }

    @Test
    void systemPromptProtectsTemplatePlaceholders() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 嗨");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        t.translate("Hi", "zh-TW");
        assertTrue(sentBodies.get(0).contains("⟦") && sentBodies.get(0).contains("verbatim"),
                "prompt must tell the model to keep ⟦…⟧ tokens verbatim");
    }

    @Test
    void systemPromptForbidsMixedScriptWords() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 嗨");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        t.translate("Hi", "zh-TW");

        String body = sentBodies.get(0);
        assertTrue(body.contains("as a WHOLE") && body.contains("NEVER mix"),
                "prompt must forbid mixing the original and target scripts inside one word: " + body);
        // The concrete "jacob" -> "傑cob" counter-example must be spelled out.
        assertTrue(body.contains("jacob") && body.contains("傑cob"),
                "prompt must give the jacob->傑cob counter-example: " + body);
    }

    @Test
    void systemPromptAddsMinecraftContextAndGlossaryForChinese() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 技能書");
            }
        };
        // User pins a term NOT in the curated defaults, to prove the user glossary is merged.
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k"), List.of("Warden=伏守者")));
        t.translate("Skill Book", "zh-TW");

        String body = sentBodies.get(0);
        assertTrue(body.contains("Minecraft"), "prompt must state this is Minecraft text: " + body);
        assertTrue(body.contains("西瓜"), "curated glossary term (Melon→西瓜) must be present: " + body);
        assertTrue(body.contains("Warden") && body.contains("伏守者"),
                "user aiGlossary entry (Warden→伏守者) must be merged into the prompt: " + body);
    }

    @Test
    void curatedGlossaryIsOmittedForNonChineseTargets() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. Melon");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k"))); // 3-arg: no user glossary
        t.translate("Melon", "fr"); // French target

        String body = sentBodies.get(0);
        assertTrue(body.contains("Minecraft"), "Minecraft context is unconditional: " + body);
        assertTrue(!body.contains("西瓜"), "the 繁體 glossary must NOT be sent for a non-Chinese target: " + body);
    }

    @Test
    void shiftedTokensFailTheLineInsteadOfPoisoningTheCache() throws Exception {
        // The model answered line 1 with line 2's tokens (merged/shifted lines): that
        // line must come back EMPTY (per-line failure), never as a wrong translation.
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 進度到 ⟦MT0⟧ ⟦MT1⟧\n2. 你好");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("Progress to Level ⟦MT0⟧:", "Hello"), "zh-TW");
        assertEquals("", r.get(0).translatedText(), "token-mismatched line must fail, not poison the cache");
        assertEquals("你好", r.get(1).translatedText());
    }

    @Test
    void absorbedTokensAreAcceptedNotFailed() throws Exception {
        // "Creation Date: Jun ⟦MT0⟧, ⟦MT1⟧ ⟦MT2⟧" often comes back as a native date with
        // fewer tokens — that must still be accepted (only ALIEN tokens are dangerous).
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 創建日期：⟦MT1⟧年6月⟦MT0⟧日");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("Creation Date: Jun ⟦MT0⟧, ⟦MT1⟧ ⟦MT2⟧"), "zh-TW");
        assertEquals("創建日期：⟦MT1⟧年6月⟦MT0⟧日", r.get(0).translatedText());
    }

    @Test
    void matchingTokensPassEvenWhenReordered() throws Exception {
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 在⟦MT1⟧中擊中⟦MT0⟧"); // reordered but same token set
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("hit ⟦MT0⟧ in ⟦MT1⟧"), "zh-TW");
        assertEquals("在⟦MT1⟧中擊中⟦MT0⟧", r.get(0).translatedText());
    }

    // ---- 429 global backoff gate ----

    /** Transport that 429s every key until told otherwise; counts calls; fake clock. */
    private static final class RateLimitRig {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean rateLimited = true;
        final long[] now = {0L};
        final OpenAiTranslator translator;

        RateLimitRig(List<String> keys) {
            HttpTransport fake = new HttpTransport() {
                @Override public String get(String url) { throw new UnsupportedOperationException(); }
                @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                    calls.incrementAndGet();
                    if (rateLimited) throw new IOException("HTTP 429: quota exceeded");
                    return chatJson("1. 嗨");
                }
            };
            translator = new OpenAiTranslator(fake,
                    () -> new AiSettings("https://x/v1", "m", keys), () -> now[0]);
        }
    }

    @Test
    void allKeys429TripsGateWithoutPerKeyRetries() {
        RateLimitRig rig = new RateLimitRig(List.of("a", "b"));

        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW"));
        assertEquals(2, rig.calls.get(), "429 must NOT be retried on the same key: one call per key");

        // Gate is up: further requests fail fast without touching the transport.
        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi again", "zh-TW"));
        assertEquals(2, rig.calls.get(), "gated requests must never reach the transport");
    }

    @Test
    void gateExpiresAfterBasePenalty() throws Exception {
        RateLimitRig rig = new RateLimitRig(List.of("a"));
        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW"));
        assertEquals(1, rig.calls.get());

        rig.now[0] = 59_999L; // still inside the 60s base penalty
        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW"));
        assertEquals(1, rig.calls.get());

        rig.now[0] = 60_000L; // penalty over: HTTP flows again
        rig.rateLimited = false;
        assertEquals("嗨", rig.translator.translate("Hi", "zh-TW").translatedText());
        assertEquals(2, rig.calls.get());
    }

    @Test
    void consecutive429RoundsDoubleThePenaltyUpToTheCap() {
        RateLimitRig rig = new RateLimitRig(List.of("a"));
        // Trip repeatedly, each time right after the previous penalty expires. Expected
        // penalties: 60s, 120s, 240s, 480s, 600s (capped), 600s…
        long[] expected = {60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L};
        long trippedAt = 0L;
        for (long penalty : expected) {
            assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW"));
            int callsAfterTrip = rig.calls.get();

            rig.now[0] = trippedAt + penalty - 1; // one ms before expiry: still gated
            assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW"));
            assertEquals(callsAfterTrip, rig.calls.get(), "gated at +" + (penalty - 1));

            rig.now[0] = trippedAt + penalty;     // expiry: next (still-429) round re-trips
            trippedAt = rig.now[0];
        }
    }

    @Test
    void anySuccessResetsThePenaltyToBase() throws Exception {
        RateLimitRig rig = new RateLimitRig(List.of("a"));
        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW")); // 60s
        rig.now[0] = 60_000L;
        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW")); // 120s
        rig.now[0] = 180_000L;

        rig.rateLimited = false; // quota is back: one success must reset everything
        assertEquals("嗨", rig.translator.translate("Hi", "zh-TW").translatedText());

        rig.rateLimited = true;  // next all-429 round starts from the BASE penalty again
        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW"));
        int calls = rig.calls.get();
        rig.now[0] = 180_000L + 59_999L;
        assertThrows(TranslationException.class, () -> rig.translator.translate("Hi", "zh-TW"));
        assertEquals(calls, rig.calls.get(), "must still be gated inside the reset 60s penalty");
        rig.now[0] = 180_000L + 60_000L;
        rig.rateLimited = false;
        assertEquals("嗨", rig.translator.translate("Hi", "zh-TW").translatedText(),
                "the reset base penalty (not a doubled one) must gate only 60s");
    }

    @Test
    void partial429DoesNotTripTheGate() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                calls.incrementAndGet();
                if ("Bearer limited".equals(headers.get("Authorization"))) throw new IOException("HTTP 429");
                return chatJson("1. 嗨");
            }
        };
        long[] now = {0L};
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("limited", "healthy")), () -> now[0]);

        assertEquals("嗨", t.translate("Hi", "zh-TW").translatedText());
        assertEquals(2, calls.get(), "the 429 key is skipped once (no same-key retry), the next key answers");

        // A round that SUCCEEDED must not gate the following request.
        t.translate("Hi again", "zh-TW");
        assertTrue(calls.get() > 2, "no gate may be up after a successful round");
    }

    @Test
    void parseNumberedStripsVariousNumberFormats() {
        List<String> r = OpenAiTranslator.parseNumbered("1. A\n2) B\n3、C\n\n", 3);
        assertEquals(List.of("A", "B", "C"), r);
    }

    @Test
    void surfaceContextAddsTooltipBlockButNumbersOnlyTheTodoLines() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 配方");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));

        // Whole tooltip is the context; only the not-yet-cached line is sent for translation.
        List<TranslationResult> r = t.translateBatch(List.of("Recipes"), "zh-TW",
                List.of("Iron Pickaxe", "Recipes", "Used in smelting"));

        assertEquals(1, r.size());
        assertEquals("配方", r.get(0).translatedText());
        String body = sentBodies.get(0);
        assertTrue(body.contains("[TITLE] Iron Pickaxe"),
                "context block must list the tooltip with the first line marked as TITLE: " + body);
        assertTrue(body.contains("Used in smelting"), "context must carry the WHOLE tooltip: " + body);
        assertTrue(body.contains("tooltip"), "prompt must say the lines share one tooltip: " + body);
        assertTrue(body.contains("ONLY the numbered lines"),
                "prompt must forbid translating the context block itself: " + body);
        assertTrue(body.contains("1. Recipes"), "the todo line must still be numbered: " + body);
        assertTrue(!body.contains("2. "), "ONLY todo lines may be numbered, never context lines: " + body);
        assertTrue(!body.contains("1. Iron Pickaxe"), "context lines must not be numbered: " + body);
    }

    @Test
    void nullOrEmptySurfaceContextKeepsRequestIdenticalToPlainBatch() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 你好\n2. 世界");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));

        t.translateBatch(List.of("Hello", "World"), "zh-TW");                 // old two-arg path
        t.translateBatch(List.of("Hello", "World"), "zh-TW", null);           // null context
        t.translateBatch(List.of("Hello", "World"), "zh-TW", List.of());      // empty context

        assertEquals(3, sentBodies.size());
        assertEquals(sentBodies.get(0), sentBodies.get(1), "null context must not change the request");
        assertEquals(sentBodies.get(0), sentBodies.get(2), "empty context must not change the request");
        assertTrue(!sentBodies.get(0).contains("[TITLE]"), sentBodies.get(0));
    }

    @Test
    void glossarySeparatesRecipesFromSkillBook() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 配方");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        t.translate("Recipes", "zh-TW");

        String body = sentBodies.get(0);
        assertTrue(body.contains("Skill Book → 技能書"), body);
        assertTrue(body.contains("Recipe / Recipes → 配方"), body);
        assertTrue(body.contains("Recipe Book → 配方書"), body);
        assertTrue(!body.contains("Skill Book / Recipe Book"),
                "the old merged entry (Recipe Book → 技能書) must be gone: " + body);
    }
}
