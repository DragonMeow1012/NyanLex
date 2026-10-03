package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiTranslatorTest {

    /** Inline fake transport: returns canned chat-completions JSON; records the request. */
    private static String chatJson(String content) {
        return rawChatJson(anchorNumberedFixture(content));
    }

    private static String rawChatJson(String content) {
        // content is embedded with escaped newlines
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + content.replace("\n", "\\n") + "\"}}]}";
    }

    /** Keep legacy fixture strings readable while exercising the real strict protocol. */
    private static String anchorNumberedFixture(String content) {
        java.util.regex.Pattern numbered = java.util.regex.Pattern.compile(
                "^(\\d+)\\s*[.)、]\\s*(.*)$");
        StringBuilder out = new StringBuilder();
        int current = -1;
        StringBuilder value = null;
        for (String raw : content.split("\\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            java.util.regex.Matcher matcher = numbered.matcher(line);
            if (matcher.matches()) {
                if (current >= 0) appendAnchoredFixture(out, current, value.toString());
                current = Integer.parseInt(matcher.group(1));
                value = new StringBuilder(matcher.group(2).strip());
            } else if (value != null) {
                if (value.length() > 0) value.append(' ');
                value.append(line);
            } else {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        }
        if (current >= 0) appendAnchoredFixture(out, current, value.toString());
        return out.toString();
    }

    private static void appendAnchoredFixture(StringBuilder out, int number, String value) {
        if (out.length() > 0) out.append('\n');
        int open = OpenAiTranslator.BATCH_ANCHOR_BASE + (number - 1) * 2;
        out.append(open).append(value).append(open + 1);
    }

    @Test
    void translateBatchSendsStrictAnchoredPromptAndParsesAnchoredReply() throws Exception {
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
        // Both complete items share one request and each owns an immutable boundary pair.
        assertTrue(sentBodies.get(0).contains("86001 Hello 86002"), sentBodies.get(0));
        assertTrue(sentBodies.get(0).contains("86003 World 86004"), sentBodies.get(0));
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
                () -> new AiSettings("https://x/v1", "", List.of())); // missing model
        assertThrows(TranslationException.class, () -> t.translate("Hi", "zh-TW"));
    }

    @Test
    void keylessEndpointSendsExactlyOnceWithoutAuthorizationHeader() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiSettings keyless = new AiSettings("http://127.0.0.1:11434/v1", "local-model", null);
        assertTrue(keyless.isConfigured());
        assertEquals(List.of(), keyless.apiKeys(), "null keys must normalize to an empty list");
        assertTrue(new AiSettings("https://x/v1", "m", List.of()).isConfigured());
        assertTrue(!new AiSettings("", "m", List.of()).isConfigured());

        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                calls.incrementAndGet();
                assertTrue(headers.isEmpty(), "a keyless endpoint must receive no Authorization header");
                assertNull(headers.get("Authorization"));
                return chatJson("1. 本地翻譯");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake, () -> keyless);

        assertEquals("本地翻譯", t.translate("Local", "zh-TW").translatedText());
        assertEquals(1, calls.get());
    }

    @Test
    void missingAnchoredItemIsBisectedWithoutShiftingTheFollowingCacheKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                calls.incrementAndGet();
                if (body.contains("86003 b 86004")) return chatJson("1. 只有一行");
                if (body.contains("86001 a 86002")) return chatJson("1. 只有一行");
                return rawChatJson("missing anchors");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("a", "b"), "zh-TW");
        assertEquals(2, r.size());
        assertEquals("只有一行", r.get(0).translatedText());
        assertEquals("", r.get(1).translatedText(), "missing item padded to empty, not whole-batch failure");
        assertEquals("anchor/order damaged", r.get(1).failureReason());
        assertEquals(3, calls.get(), "damaged pair must bisect before accepting either sibling");
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
    void hardNewlineStaysInsideOneAnchoredItemAndIsRestoredExactly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> bodySeen = new AtomicReference<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                calls.incrementAndGet();
                bodySeen.set(body);
                return chatJson("1. 第一段⟦AI_LINE_0_0⟧第二段");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));

        List<TranslationResult> result = t.translateBatch(
                List.of("First paragraph\nSecond paragraph"), "zh-TW");

        assertEquals(1, calls.get());
        // P1.5/wire codec: the REQUEST now carries the AI_LINE slot in its ASCII wire
        // form ({ai_line_0_0}), never the raw ⟦AI_LINE_0_0⟧ internal/cache format — see
        // AiWireCodec. restoreHardLines still restores the real newline correctly below,
        // because OpenAiTranslator decodes the response back to ⟦...⟧ before it gets there.
        assertTrue(bodySeen.get().contains("{ai_line_0_0}"), bodySeen.get());
        assertFalse(bodySeen.get().contains("⟦"), "the request body must be pure ASCII wire text");
        assertEquals("第一段\n第二段", result.get(0).translatedText());
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
        // Q2 (1.0.7): the placeholder clause is sent only when the request carries a ⟦…⟧
        // token, so this request carries one (a token-free request is covered by
        // placeholderClausesAppearOnlyForTokensPresentInTheRequest). The PROMPT now
        // describes the wire codec's ASCII {tag} form (never raw ⟦…⟧) — see AiWireCodec.
        t.translate("Hi ⟦MT0⟧", "zh-TW");
        assertTrue(sentBodies.get(0).contains("{tag}") && sentBodies.get(0).contains("verbatim"),
                "prompt must tell the model to keep {tag} placeholders verbatim");
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
    void systemPromptAddsMinecraftContextAndOnlyRelevantUserTerms() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 技能書");
            }
        };
        // User-pinned terms are retained, but only when present in this request.
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k"), List.of("Warden=伏守者")));
        // Q2 (1.0.7): the CS and PB clauses asserted below are sent only when the request
        // carries CS pairs / PB breaks, so this request carries both.
        t.translate("⟦CS0⟧Melon⟦/CS0⟧ guarded by Warden⟦PB0⟧at night", "zh-TW");

        String body = sentBodies.get(0);
        assertTrue(body.contains("Minecraft"), "prompt must state this is Minecraft text: " + body);
        assertTrue(body.contains("Enchant") && body.contains("附魔"),
                "prompt should give one compact Minecraft terminology example: " + body);
        assertTrue(body.contains("RPG/MMO") && body.contains("entire visible-block context"),
                "prompt must disambiguate server RPG tooltips as one visible block: " + body);
        assertTrue(body.contains("BBCode style tag") && body.contains("same semantic phrase"),
                "colour tags must follow their translated meaning, not their old position: " + body);
        assertTrue(body.contains("official Minecraft translations")
                        && body.contains("not as a rigid word-for-word template")
                        && body.contains("natural Taiwan player-facing"),
                "official terms are a baseline while mod/RPG wording stays natural: " + body);
        assertTrue(body.contains("Damage") && body.contains("傷害") && body.contains("損壞"),
                "Traditional-Chinese RPG stats must distinguish damage from item damage: " + body);
        assertTrue(body.contains("{pbn}") && body.contains("immutable line break"),
                "paragraph line-break placeholders must be preserved in order: " + body);
        assertTrue(!body.contains("Melon → 西瓜"),
                "the removed built-in glossary must not be shipped with every request: " + body);
        assertTrue(body.contains("Warden") && body.contains("伏守者"),
                "user aiGlossary entry (Warden→伏守者) must be merged into the prompt: " + body);
    }

    @Test
    void chineseTerminologyExampleIsOmittedForNonChineseTargets() throws Exception {
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
        assertTrue(!body.contains("附魔"), "the 繁中 example must NOT be sent for a non-Chinese target: " + body);
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
        assertEquals("format/token lost", r.get(0).failureReason());
        assertEquals("你好", r.get(1).translatedText());
    }

    @Test
    void missingDynamicTokenFailsTheLineInsteadOfDeletingLiveData() throws Exception {
        // A fluent-looking response is still unusable if it ate MT2: otherwise the
        // current date/value disappears and that poisoned template persists forever.
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 創建日期：⟦MT1⟧年6月⟦MT0⟧日");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("Creation Date: Jun ⟦MT0⟧, ⟦MT1⟧ ⟦MT2⟧"), "zh-TW");
        assertEquals("", r.get(0).translatedText());
        assertEquals("format/token lost", r.get(0).failureReason());
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
    void reshapedTokenIsRejectedInsteadOfLeakingProtocolDebrisIntoTheTranslation() throws Exception {
        // The model kept the real ⟦PB0⟧ boundary intact (so the plain multiset/sequence
        // checks alone see nothing wrong) but ALSO echoed the token's bare name using a
        // look-alike CJK quotation bracket ("『PB0』"), as if quoting a label — a real
        // user-observed 1.0.7 failure. Neither ANY_TOKEN nor CS_TOKEN matches 『…』, so
        // this extra protocol-shaped debris was invisible to tokensMatch before this fix.
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 哈囉『PB0』世界⟦PB0⟧");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("Hello⟦PB0⟧World"), "zh-TW");
        assertEquals("", r.get(0).translatedText(),
                "a reshaped 『PB0』 echo must fail the line, not leak into the translation");
        assertEquals("format/token lost", r.get(0).failureReason());
    }

    @Test
    void reshapedCsTokenFailsAtTheTokensMatchLayer() throws Exception {
        // Unlike TranslationCache#usable() (see TranslationCacheTest's sibling test),
        // OpenAiTranslator#tokensMatch has no pre-existing bare-"CS<digits>"-residue
        // net (that check lives only in matchingCsShape/csShape, cache-side): without
        // the new hasReshapedProtocolToken() guard this layer alone would accept a
        // reshaped 『CS0』 echo as a well-formed line.
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. ⟦CS0⟧紅⟦/CS0⟧色的『CS0』骷髏");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("⟦CS0⟧Red⟦/CS0⟧ Skull"), "zh-TW");
        assertEquals("", r.get(0).translatedText());
        assertEquals("format/token lost", r.get(0).failureReason());
    }

    // ---- 2026-10 CS-orphan request-loop fix: retryIsolatedFailures ----

    @Test
    void isolatedRetryStillRecoversAnOrdinaryContentLevelFailureInABatch() throws Exception {
        // Baseline (unchanged behaviour): a unit whose SOURCE is perfectly well-formed
        // but whose first batched answer dropped a live value still gets exactly one
        // isolated follow-up request, and a good isolated answer replaces the failure.
        AtomicInteger postCalls = new AtomicInteger();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                int call = postCalls.incrementAndGet();
                if (call == 1) {
                    // Batch of 2: unit 2 comes back missing its ⟦MT0⟧ value.
                    return chatJson("1. 你好\n2. 連勝");
                }
                return chatJson("1. 連勝 ⟦MT0⟧"); // isolated retry of unit 2 alone
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("Hello", "Streak ⟦MT0⟧"), "zh-TW");

        assertEquals(2, postCalls.get(), "one batch call plus one isolated retry call");
        assertEquals("你好", r.get(0).translatedText());
        assertEquals("連勝 ⟦MT0⟧", r.get(1).translatedText());
    }

    @Test
    void isolatedRetryIsSkippedWhenTheSourceItselfHasUnbalancedCsMarkers() throws Exception {
        // Live bug (scratchpad/live-ai exchange dump): a composer-carved segment's own
        // source carries an orphan ⟦CS6⟧ opener with no matching closer anywhere in this
        // unit (the colour run only closes on a row this unit does not own -- see
        // TradeLineComposer#trimmedSpan's 2026-10 fix for where this is now prevented at
        // the source). TranslationTemplate#styleSlotShapeMatches rejects such a source's
        // OWN shape unconditionally, so even a perfect verbatim echo can never pass --
        // retrying it in isolation would only buy a second guaranteed-identical HTTP call
        // for nothing. Confirmed here with a transport that echoes the anchored unit back
        // byte-for-byte (the best possible answer) and still only sees ONE POST.
        AtomicInteger postCalls = new AtomicInteger();
        String brokenSource = "⟦CS5⟧Requires⟦/CS5⟧ ⟦CS6⟧Spider Slayer ⟦MT0⟧";
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                postCalls.incrementAndGet();
                return chatJson("1. 你好\n2. " + brokenSource);
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("Hello", brokenSource), "zh-TW");

        assertEquals(1, postCalls.get(),
                "a guaranteed-unwinnable unit must not buy a second isolated-retry HTTP call");
        assertEquals("", r.get(1).translatedText());
        assertEquals("format/token lost", r.get(1).failureReason());
    }

    @Test
    void droppedParagraphBreakStillFailsAtTheOrdinaryTokenMultisetCheck() throws Exception {
        // Confirms the existing (unchanged) safety net for a token that is simply GONE,
        // with nothing put in its place — as opposed to the reshaped-but-present case
        // above, which needed the new check. 2026-10-02: a dropped ⟦PBn⟧ specifically is
        // now classified "paragraph lost" (checked BEFORE the general token multiset, see
        // OpenAiTranslator#paragraphBreakSequenceMatches) instead of the generic "format/
        // token lost" — a real-traffic finding (scratchpad/live-ai/LIVE-RESULT.md) showed
        // the old generic label pre-empted TranslationCache#failureReasonFor's own more
        // precise paragraph-break diagnosis, which otherwise never even ran.
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 哈囉世界");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<TranslationResult> r = t.translateBatch(List.of("Hello⟦PB0⟧World"), "zh-TW");
        assertEquals("", r.get(0).translatedText());
        assertEquals("paragraph lost", r.get(0).failureReason());
    }

    @Test
    void layoutSlotCannotCrossLiveValuesInFixedColumnRows() throws Exception {
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. 夏季商店促銷 - ⟦WS0⟧ 至⟦MT0⟧折 - ⟦MT1⟧");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));

        List<TranslationResult> r = t.translateBatch(List.of(
                "SUMMER STORE SALE - UP TO ⟦MT0⟧ OFF - ⟦WS0⟧ ⟦MT1⟧"), "zh-TW");

        assertEquals("", r.get(0).translatedText(),
                "moving a fixed gap across MT0 would put the sale value in the wrong HUD column");
    }

    @Test
    void dynamicValueCannotEscapeItsColourSegment() throws Exception {
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                return chatJson("1. ⟦MT0⟧⟦CS0⟧傷害⟦/CS0⟧⟦CS1⟧⟦/CS1⟧");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));

        List<TranslationResult> r = t.translateBatch(List.of(
                "⟦CS0⟧Damage⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧"), "zh-TW");

        assertEquals("", r.get(0).translatedText(),
                "MT0 leaving CS1 would recolour the live damage value");
    }

    // ---- 429 global backoff gate ----

    @Test
    void rateLimitProbeSeesAModelChangeWithoutSendingAnotherRequest() {
        AtomicReference<AiSettings> settings = new AtomicReference<>(
                new AiSettings("https://x/v1", "old-model", List.of("same-key")));
        AtomicInteger calls = new AtomicInteger();
        HttpTransport transport = new HttpTransport() {
            public String get(String url) { throw new UnsupportedOperationException(); }
            public String post(String url, String body, Map<String, String> headers) throws IOException {
                calls.incrementAndGet();
                throw new IOException("HTTP 429");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(transport, settings::get, () -> 0L);
        assertThrows(TranslationException.class, () -> t.translate("Hi", "zh-TW"));
        assertTrue(t.isRateLimited());
        settings.set(new AiSettings("https://x/v1", "old-model", List.of("same-key"), List.of("Aether=天堂")));
        assertTrue(t.isRateLimited(), "a glossary edit must not reset the same model's limit");
        settings.set(new AiSettings("https://x/v1", "new-model", List.of("same-key")));
        assertFalse(t.isRateLimited(), "warm-up only probes the gate while paused; no request can trigger refresh");
        assertEquals(1, calls.get(), "checking the new model must not send a probe request");
    }

    @Test
    void anOldModelsLateResponseCannotChangeTheNewModelsGate() throws Exception {
        for (boolean oldFails : List.of(true, false)) {
            AtomicReference<AiSettings> settings = new AtomicReference<>(
                    new AiSettings("https://x/v1", "old-model", List.of("same-key")));
            AtomicReference<OpenAiTranslator> translator = new AtomicReference<>();
            HttpTransport transport = new HttpTransport() {
                public String get(String url) { throw new UnsupportedOperationException(); }
                public String post(String url, String body, Map<String, String> headers) throws IOException {
                    if (body.contains("\"model\":\"old-model\"")) {
                        settings.set(new AiSettings("https://x/v1", "new-model", List.of("same-key")));
                        assertFalse(translator.get().isRateLimited());
                        if (oldFails) throw new IOException("HTTP 429 from old model");
                        assertThrows(TranslationException.class,
                                () -> translator.get().translate("New request", "zh-TW"));
                        return chatJson("1. 嗨");
                    }
                    throw new IOException("HTTP 429 from new model");
                }
            };
            OpenAiTranslator t = new OpenAiTranslator(transport, settings::get, () -> 0L);
            translator.set(t);
            if (oldFails) assertThrows(TranslationException.class, () -> t.translate("Hi", "zh-TW"));
            else assertEquals("嗨", t.translate("Hi", "zh-TW").translatedText());
            assertEquals(!oldFails, t.isRateLimited(),
                    "the old response must neither close nor reopen the new model's gate");
        }
    }

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
    void rateLimitedKeyIsQuarantinedWhileHealthyKeyContinues() throws Exception {
        List<String> used = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                String auth = headers.get("Authorization");
                used.add(auth);
                if ("Bearer limited".equals(auth)) throw new IOException("HTTP 429");
                return chatJson("1. ok");
            }
        };
        long[] now = {0L};
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("limited", "healthy")), () -> now[0]);

        t.translate("one", "zh-TW");   // limited -> healthy
        t.translate("two", "zh-TW");   // healthy starts
        t.translate("three", "zh-TW"); // limited would start, but remains quarantined

        assertEquals(1, used.stream().filter("Bearer limited"::equals).count(),
                "a 429 key must not be retried on every rotation during its cooldown");
        assertEquals(3, used.stream().filter("Bearer healthy"::equals).count());
    }

    @Test
    void changingProviderSettingsImmediatelyClearsOldAllKeyGate() throws Exception {
        AtomicReference<AiSettings> settings = new AtomicReference<>(
                new AiSettings("https://old/v1", "old", List.of("old-key")));
        AtomicInteger calls = new AtomicInteger();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                calls.incrementAndGet();
                if ("Bearer old-key".equals(headers.get("Authorization"))) throw new IOException("HTTP 429");
                return chatJson("1. new-key-works");
            }
        };
        long[] now = {0L};
        OpenAiTranslator t = new OpenAiTranslator(fake, settings::get, () -> now[0]);

        assertThrows(TranslationException.class, () -> t.translate("first", "zh-TW"));
        assertTrue(t.isRateLimited());
        settings.set(new AiSettings("https://new/v1", "new", List.of("new-key")));

        assertEquals("new-key-works", t.translate("second", "zh-TW").translatedText(),
                "a newly configured endpoint/key must not inherit the old provider's gate");
        assertEquals(2, calls.get());
    }

    @Test
    void strictAnchorsExtractEveryCompleteItemInOrder() {
        List<String> r = OpenAiTranslator.extractAnchoredBatch(
                " 86001A86002\n86003B86004\n86005C86006 ", 3,
                OpenAiTranslator.BATCH_ANCHOR_BASE);
        assertEquals(List.of("A", "B", "C"), r);
    }

    @Test
    void strictAnchorsRejectMissingCrossedRepeatedAndExteriorText() {
        int base = OpenAiTranslator.BATCH_ANCHOR_BASE;
        assertNull(OpenAiTranslator.extractAnchoredBatch("86001A86002\n86005C86006", 3, base));
        assertNull(OpenAiTranslator.extractAnchoredBatch(
                "86001A8600386002B86004", 2, base));
        assertNull(OpenAiTranslator.extractAnchoredBatch(
                "86001A8600286001duplicate86002", 1, base));
        assertNull(OpenAiTranslator.extractAnchoredBatch(
                "commentary\n86001A86002", 1, base));
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
                List.of("Iron Pickaxe", "", "Recipes", "Gear Score: 558", "Used in smelting"));

        assertEquals(1, r.size());
        assertEquals("配方", r.get(0).translatedText());
        String body = sentBodies.get(0);
        assertTrue(body.contains("[L0:TEXT] Iron Pickaxe"),
                "context must not invent a title role for the first visible row: " + body);
        assertTrue(body.contains("Used in smelting"), "context must carry the WHOLE tooltip: " + body);
        assertTrue(body.contains("[SECTION]"), "blank tooltip rows must preserve section boundaries: " + body);
        assertTrue(body.contains("[L3:STAT] Gear Score: 558"),
                "RPG equipment scores must be classified as stats: " + body);
        assertTrue(body.contains("visible block"), "prompt must identify the shared visible block: " + body);
        assertTrue(body.contains("ONLY the strictly anchored units"),
                "prompt must forbid translating the context block itself: " + body);
        assertTrue(body.contains("86001 Recipes 86002"),
                "the todo line must be bounded as one atomic item: " + body);
        assertTrue(!body.contains("86003"),
                "context lines must never receive item boundaries: " + body);
        assertTrue(!body.contains("86001 Iron Pickaxe"),
                "context text must stay outside the translatable pair: " + body);
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
    void removedBuiltInGlossaryIsNotSentForRecipeTerms() throws Exception {
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
        t.translate("Skill Book, Recipe Book, Recipes", "zh-TW");

        String body = sentBodies.get(0);
        assertTrue(body.contains("86001 Skill Book, Recipe Book, Recipes 86002"), body);
        assertTrue(!body.contains("Skill Book → 技能書"), body);
        assertTrue(!body.contains("Recipe / Recipes → 配方"), body);
        assertTrue(!body.contains("Recipe Book → 配方書"), body);
    }

    @Test
    void promptSendsOnlyGlossaryEntriesRelevantToThisRequest() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 魔力消耗");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k"),
                        List.of("Warden=獄卒", "Mana=魔力")));

        t.translate("Mana Cost", "zh-TW");

        String body = sentBodies.get(0);
        assertTrue(body.contains("Mana → 魔力"), body);
        assertTrue(!body.contains("Warden → 獄卒"), body);
        assertTrue(!body.contains("Melon / Watermelon"), body);
    }

    @Test
    void tooltipContextIsBoundedBeforeSending() throws Exception {
        List<String> sentBodies = new ArrayList<>();
        HttpTransport fake = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) {
                sentBodies.add(body);
                return chatJson("1. 翻譯");
            }
        };
        OpenAiTranslator t = new OpenAiTranslator(fake,
                () -> new AiSettings("https://x/v1", "m", List.of("k")));
        List<String> context = new ArrayList<>();
        context.add("Very Long Item Title");
        for (int i = 0; i < 80; i++) context.add("tooltip context line " + i + " xxxxxxxxxxxxxxxxxxxx");

        t.translateBatch(List.of("Translate me"), "zh-TW", context);

        String body = sentBodies.get(0);
        assertTrue(body.contains("[L0:TEXT] Very Long Item Title"), body);
        assertTrue(body.contains("[remaining context omitted]"), body);
        assertTrue(!body.contains("tooltip context line 79"), body);
    }
}
