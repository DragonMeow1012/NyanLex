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

    @Test
    void parseNumberedStripsVariousNumberFormats() {
        List<String> r = OpenAiTranslator.parseNumbered("1. A\n2) B\n3、C\n\n", 3);
        assertEquals(List.of("A", "B", "C"), r);
    }
}
