package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.GoogleFreeTranslator;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleFreeTranslatorTest {

    @Test
    void buildsUrlWithEncodedQueryAndLangs() {
        // Inline fake transport (records nothing) — we only test URL building here.
        GoogleFreeTranslator t = new GoogleFreeTranslator(url -> "[]", "auto");
        String url = t.buildUrl("Hello world & co", "zh-TW");
        assertTrue(url.startsWith("https://translate.googleapis.com/translate_a/single?"), url);
        assertTrue(url.contains("client=gtx"), url);
        assertTrue(url.contains("sl=auto"), url);
        assertTrue(url.contains("tl=zh-TW"), url);
        assertTrue(url.contains("dt=t"), url);
        // space -> '+', '&' -> %26 (URL form encoding)
        assertTrue(url.contains("q=Hello+world+%26+co"), url);
    }

    @Test
    void translatesUsingInlineTransport() throws Exception {
        // The transport is an INLINE mock: it captures the requested URL and
        // returns a canned Google-shaped response — no real network involved.
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        HttpTransport inlineTransport = url -> {
            requestedUrl.set(url);
            return "[[[\"你好世界\",\"Hello world\",null,null]],null,\"en\"]";
        };

        GoogleFreeTranslator t = new GoogleFreeTranslator(inlineTransport, "auto");
        TranslationResult r = t.translate("Hello world", "zh-TW");

        assertEquals("你好世界", r.translatedText());
        assertEquals("en", r.detectedSourceLang());
        assertNotNull(requestedUrl.get());
        assertTrue(requestedUrl.get().contains("tl=zh-TW"));
    }

    @Test
    void wrapsIoExceptionAsTranslationException() {
        HttpTransport failing = url -> {
            throw new IOException("HTTP 503");
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(failing, "auto");
        TranslationException ex = assertThrows(TranslationException.class,
                () -> t.translate("anything", "zh-TW"));
        assertTrue(ex.getMessage().contains("503"));
    }

    @Test
    void rateLimitedPrimarySwitchesToFallbackAndKeepsUsingIt() throws Exception {
        List<String> requested = new java.util.ArrayList<>();
        HttpTransport transport = url -> {
            requested.add(url);
            if (url.startsWith("https://translate.googleapis.com/")) {
                throw new IOException("HTTP 429");
            }
            return googleResponse(qOf(url).replace("Hello", "你好").replace("World", "世界"));
        };
        GoogleFreeTranslator translator = new GoogleFreeTranslator(transport, "auto");

        assertEquals("你好", translator.translate("Hello", "zh-TW").translatedText());
        assertEquals("世界", translator.translate("World", "zh-TW").translatedText());

        assertEquals(3, requested.size());
        assertTrue(requested.get(0).startsWith("https://translate.googleapis.com/"));
        assertTrue(requested.get(1).startsWith("https://translate.google.com/"));
        assertTrue(requested.get(2).startsWith("https://translate.google.com/"),
                "a successful fallback becomes the session endpoint");
    }

    @Test
    void blankSourceLangDefaultsToAuto() {
        GoogleFreeTranslator t = new GoogleFreeTranslator(url -> "[]", "   ");
        assertTrue(t.buildUrl("x", "zh-TW").contains("sl=auto"));
    }

    @Test
    void batchUsesPerLineAnchorsInsteadOfTrustingNewlinePositions() throws Exception {
        HttpTransport inline = url -> {
            String q = qOf(url);
            return googleResponse(q.replace("a", "甲").replace("b", "乙").replace("c", "丙"));
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        List<TranslationResult> out = t.translateBatch(List.of("a", "b", "c"), "zh-TW");
        assertEquals(3, out.size());
        assertEquals("甲", out.get(0).translatedText());
        assertEquals("乙", out.get(1).translatedText());
        assertEquals("丙", out.get(2).translatedText());
    }

    @Test
    void misalignedBatchIsBisectedNotSentPerItem() {
        // Damage the initial three-item anchor protocol; [a] then goes alone and [b,c]
        // succeeds as an anchored pair. Total 3 requests, not one request per item.
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        HttpTransport inline = url -> {
            calls.set(calls.get() + 1);
            String q = qOf(url);
            if (q.contains("a") && q.contains("b") && q.contains("c")) {
                return googleResponse("壞掉錨點");
            }
            if (q.equals("a")) return googleResponse("X");
            return googleResponse(q.replace("b", "甲").replace("c", "乙"));
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");
        List<TranslationResult> out = assertDoesNotThrowResult(() -> t.translateBatch(List.of("a", "b", "c"), "zh-TW"));
        assertEquals(3, out.size());
        assertEquals("X", out.get(0).translatedText());  // [a] retried alone
        assertEquals("甲", out.get(1).translatedText()); // [b,c] re-aligned as a pair
        assertEquals("乙", out.get(2).translatedText());
        assertEquals(3, calls.get());
    }

    @Test
    void interleavedOuterAnchorsAreRejectedAndBisectedWithoutCrossItemLeakage() {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        HttpTransport inline = url -> {
            calls.set(calls.get() + 1);
            String q = qOf(url);
            if (q.contains("a") && q.contains("b")) {
                // Both pairs occur exactly once, but they overlap. The old extractor
                // accepted this and wrote the neighbouring anchor/text into each key.
                return googleResponse("70001甲70003乙7000270004");
            }
            return googleResponse(q.equals("a") ? "甲" : "乙");
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        List<TranslationResult> out = assertDoesNotThrowResult(
                () -> t.translateBatch(List.of("a", "b"), "zh-TW"));

        assertEquals(List.of("甲", "乙"), out.stream().map(TranslationResult::translatedText).toList());
        assertEquals(3, calls.get(), "an overlapping batch must retry each isolated item");
    }

    @Test
    void hugeBatchIsChunkedByCharacterBudget() {
        // Each request must stay within the char budget; alignment is echoed back
        // by translating each joined chunk into the same number of lines.
        List<String> urls = new java.util.ArrayList<>();
        HttpTransport inline = url -> {
            urls.add(url);
            String q = java.net.URLDecoder.decode(url.substring(url.indexOf("&q=") + 3),
                    java.nio.charset.StandardCharsets.UTF_8);
            return googleResponse(q); // preserve every line's numeric anchors
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            texts.add("This is a fairly long line of user interface text number " + i + " padded a bit");
        }
        List<TranslationResult> out = assertDoesNotThrowResult(() -> t.translateBatch(texts, "zh-TW"));
        assertEquals(40, out.size());
        assertTrue(urls.size() > 1, "40 long lines must be split into multiple requests");
        assertTrue(urls.size() <= 5, "but only a handful, not one per item: " + urls.size());
    }

    // ---- whole-line sentinel mode: one request per sentence, no ⟦⟧ on the wire (R14b) ----

    /** Decoded q= payload of a request URL (q is the last parameter buildUrl emits). */
    private static String qOf(String url) {
        return java.net.URLDecoder.decode(url.substring(url.indexOf("&q=") + 3),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String googleResponse(String translated) {
        String escaped = translated.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "[[[\"" + escaped + "\",\"src\",null,null]],null,\"en\"]";
    }

    @Test
    void tokenLineGoesAsOneRequestWithSentinelsAndNoBrackets() throws Exception {
        // R14b (user decision:「一句一句翻」): the whole sentence travels as ONE request
        // with full context — an isolated "won" no longer reads as a currency. Every
        // internal token rides as a numeric sentinel, so exact style boundaries survive.
        List<String> sent = new java.util.ArrayList<>();
        HttpTransport inline = url -> {
            String q = qOf(url);
            sent.add(q);
            return "[[[\"70001你領取了70002 70003日之水晶70004 70005來自70006 "
                    + "7000770008 Aand_ 的拍賣！70009\",\"" + q + "\",null,null]],null,\"en\"]";
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        TranslationResult r = t.translate(
                "⟦CS0⟧You claimed⟦/CS0⟧ ⟦CS1⟧Day Crystal⟦/CS1⟧ "
                        + "⟦CS0⟧from⟦/CS0⟧ ⟦CS2⟧⟦MT0⟧ Aand_'s auction!⟦/CS2⟧", "zh-TW");

        assertEquals(1, sent.size(), "the whole sentence is ONE request");
        assertEquals("70001You claimed70002 70003Day Crystal70004 70005from70006 "
                        + "7000770008 Aand_'s auction!70009", sent.get(0),
                "CS and MT become numeric sentinels; no ⟦⟧ reaches the endpoint");
        assertEquals("⟦CS0⟧你領取了⟦/CS0⟧ ⟦CS1⟧日之水晶⟦/CS1⟧ "
                        + "⟦CS0⟧來自⟦/CS0⟧ ⟦CS2⟧⟦MT0⟧ Aand_ 的拍賣！⟦/CS2⟧",
                r.translatedText());
        assertEquals("en", r.detectedSourceLang());
    }

    @Test
    void semanticStyleSpansMayReorderWhileTheWholeSentenceUsesOneGtRequest() throws Exception {
        List<String> sent = new java.util.ArrayList<>();
        HttpTransport inline = url -> {
            sent.add(qOf(url));
            // Target grammar moves the item span before the action span. The boundary
            // sentinels move with their semantic phrase instead of being split by length.
            return googleResponse("70003白色禮物護符70004，70001已出售70002");
        };
        GoogleFreeTranslator gt = new GoogleFreeTranslator(inline, "auto");

        TranslationResult result = gt.translate(
                "⟦CS0⟧sold⟦/CS0⟧ ⟦CS1⟧White Gift Talisman⟦/CS1⟧", "zh-TW");

        assertEquals(List.of("70001sold70002 70003White Gift Talisman70004"), sent,
                "GT receives one complete sentence, never one request per colour run");
        assertEquals("⟦CS1⟧白色禮物護符⟦/CS1⟧，⟦CS0⟧已出售⟦/CS0⟧",
                result.translatedText(),
                "the same semantic span IDs survive target-language word-order changes");
    }

    @Test
    void numberSlotRestoresIntoWholeSentenceTranslation() throws Exception {
        List<String> sent = new java.util.ArrayList<>();
        HttpTransport inline = url -> {
            sent.add(qOf(url));
            return "[[[\"你得到 70001 金幣\",\"src\",null,null]],null,\"en\"]";
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        TranslationResult r = t.translate("You got ⟦MT0⟧ coins", "zh-TW");
        assertEquals(List.of("You got 70001 coins"), sent);
        assertEquals("你得到 ⟦MT0⟧ 金幣", r.translatedText());
    }

    @Test
    void lostOrMutatedSentinelReturnsAnExplicitFailureInsteadOfAnIdentityEcho() throws Exception {
        // Google added a thousands separator to the sentinel: slot mapping would lie, so
        // the whole line is rejected. Returning the source here would be indistinguishable
        // from a legitimate provider echo and could poison the durable identity cache.
        HttpTransport inline = url -> "[[[\"你贏得了 70,001 金幣\",\"src\",null,null]],null,\"en\"]";
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        TranslationResult r = t.translate("You won ⟦MT0⟧ coins", "zh-TW");
        assertEquals("", r.translatedText());
        assertEquals("format/token lost", r.failureReason());
    }

    @Test
    void markerDigitsEmbeddedInsideALargerNumberAreRejected() throws Exception {
        HttpTransport inline = url -> googleResponse("你贏得了 170001 金幣");
        GoogleFreeTranslator translator = new GoogleFreeTranslator(inline, "auto");

        TranslationResult result = translator.translate("You won ⟦MT0⟧ coins", "zh-TW");

        assertEquals("", result.translatedText());
        assertEquals("format/token lost", result.failureReason());
    }

    @Test
    void httpFailureOnTokenLineStaysATransportFailure() {
        HttpTransport failing = url -> {
            throw new IOException("HTTP 503");
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(failing, "auto");

        assertThrows(TranslationException.class,
                () -> t.translate("⟦CS0⟧Hello ⟦CS1⟧world⟦/CS1⟧", "zh-TW"),
                "network failures must back off, not count toward durable content strikes");
    }

    @Test
    void tokenFreeInputStillUsesExactlyOneRequest() throws Exception {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        HttpTransport inline = url -> {
            calls.set(calls.get() + 1);
            return "[[[\"你好世界\",\"Hello world\",null,null]],null,\"en\"]";
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        TranslationResult r = t.translate("Hello world", "zh-TW");
        assertEquals("你好世界", r.translatedText());
        assertEquals(1, calls.get(), "no-token lines keep the single-request path");
    }

    @Test
    void batchKeepsTokenLinesInOneRequestAndKeepsTokensOffTheWire() throws Exception {
        // Outer sentinels isolate complete cache items; inner sentinels protect each CS
        // token. The provider receives one joined request and no private marker glyphs.
        List<String> sent = new java.util.ArrayList<>();
        HttpTransport inline = url -> {
            String q = qOf(url);
            sent.add(q);
            String translated = q.replace("red", "譯red")
                    .replace("blue", "譯blue").replace("plain", "譯plain");
            return "[[[\"" + translated + "\",\"" + q + "\",null,null]],null,\"en\"]";
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        List<TranslationResult> out = t.translateBatch(
                List.of("⟦CS0⟧red", "⟦CS1⟧blue", "plain"), "zh-TW");
        assertEquals(3, out.size());
        assertEquals("⟦CS0⟧譯red", out.get(0).translatedText());
        assertEquals("⟦CS1⟧譯blue", out.get(1).translatedText());
        assertEquals("譯plain", out.get(2).translatedText());
        assertEquals(1, sent.size(), "the whole timed batch must use one GT HTTP request");
        for (String q : sent) {
            assertFalse(q.contains("⟦") || q.contains("⟧"), "the endpoint must never see ⟦⟧: " + q);
        }
    }

    @Test
    void batchProtectsHardParagraphBreaksWithoutSplittingRequests() throws Exception {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        HttpTransport inline = url -> {
            calls.set(calls.get() + 1);
            String q = qOf(url);
            return googleResponse(q.replace("First paragraph", "第一段")
                    .replace("Second paragraph", "第二段")
                    .replace("Another item", "另一項"));
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        List<TranslationResult> out = t.translateBatch(
                List.of("First paragraph\nSecond paragraph", "Another item"), "zh-TW");

        assertEquals(1, calls.get());
        assertEquals("第一段\n第二段", out.get(0).translatedText());
        assertEquals("另一項", out.get(1).translatedText());
    }

    // ---- S4 (P1.2): a masked player name inside its own colour run glues sentinels ----

    @Test
    void gluedSentinelsAroundAMaskedNameRestoreExactly() throws Exception {
        List<String> sent = new java.util.ArrayList<>();
        HttpTransport inline = url -> {
            String q = qOf(url);
            sent.add(q);
            return googleResponse(q.replace("Seller: ", "賣家："));
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        TranslationResult r = t.translate("⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧⟦0⟧⟦/CS1⟧", "zh-TW");

        assertEquals(List.of("70001Seller: 70002700037000470005"), sent,
                "the wire is unchanged: adjacent tokens travel as one glued digit run");
        assertEquals("⟦CS0⟧賣家：⟦/CS0⟧⟦CS1⟧⟦0⟧⟦/CS1⟧", r.translatedText());
    }

    @Test
    void gluedSentinelsAroundAMaskedNameRestoreInsideABatch() throws Exception {
        HttpTransport inline = url -> googleResponse(qOf(url)
                .replace("Seller: ", "賣家：").replace("joined the lobby!", "加入了大廳！"));
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        List<TranslationResult> out = t.translateBatch(List.of(
                "⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧⟦0⟧⟦/CS1⟧",
                "⟦CS0⟧⟦MT0⟧ ⟦0⟧⟦/CS0⟧⟦CS1⟧ joined the lobby!⟦/CS1⟧"), "zh-TW");

        assertEquals("⟦CS0⟧賣家：⟦/CS0⟧⟦CS1⟧⟦0⟧⟦/CS1⟧", out.get(0).translatedText());
        assertEquals("⟦CS0⟧⟦MT0⟧ ⟦0⟧⟦/CS0⟧⟦CS1⟧ 加入了大廳！⟦/CS1⟧", out.get(1).translatedText());
    }

    @Test
    void thousandsSeparatorsInsideAGluedRunFailTheLineAsTokenLoss() throws Exception {
        // A number-localising endpoint that formats the glued run as one big number must
        // never be half-restored: the whole line fails as a retryable token loss.
        HttpTransport inline = url -> googleResponse("70001賣家：70,002,700,037,000,470,005");
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        TranslationResult r = t.translate("⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧⟦0⟧⟦/CS1⟧", "zh-TW");

        assertEquals("", r.translatedText());
        assertEquals("format/token lost", r.failureReason());
    }

    private interface ResultSupplier {
        List<TranslationResult> get() throws Exception;
    }

    private static List<TranslationResult> assertDoesNotThrowResult(ResultSupplier s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new AssertionError("unexpected exception", e);
        }
    }
}
