package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.GoogleFreeTranslator;
import com.dragonmeow.mctranslator.translate.HttpTransport;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            throw new IOException("HTTP 429");
        };
        GoogleFreeTranslator t = new GoogleFreeTranslator(failing, "auto");
        TranslationException ex = assertThrows(TranslationException.class,
                () -> t.translate("anything", "zh-TW"));
        assertTrue(ex.getMessage().contains("429"));
    }

    @Test
    void blankSourceLangDefaultsToAuto() {
        GoogleFreeTranslator t = new GoogleFreeTranslator(url -> "[]", "   ");
        assertTrue(t.buildUrl("x", "zh-TW").contains("sl=auto"));
    }

    @Test
    void batchSplitsNewlineJoinedResultBackToInputs() throws Exception {
        // The endpoint preserves newlines, so a 3-line input returns a 3-line result.
        HttpTransport inline = url -> "[[[\"甲\\n乙\\n丙\",\"a\\nb\\nc\",null,null]],null,\"en\"]";
        GoogleFreeTranslator t = new GoogleFreeTranslator(inline, "auto");

        List<TranslationResult> out = t.translateBatch(List.of("a", "b", "c"), "zh-TW");
        assertEquals(3, out.size());
        assertEquals("甲", out.get(0).translatedText());
        assertEquals("乙", out.get(1).translatedText());
        assertEquals("丙", out.get(2).translatedText());
    }

    @Test
    void misalignedBatchIsBisectedNotSentPerItem() {
        // The fake always returns 2 lines for any joined request: the initial 3-line
        // batch misaligns, then bisecting isolates the problem — [a] goes alone and
        // [b,c] re-aligns as a pair. Total 3 requests, NOT one per item plus the batch.
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        HttpTransport inline = url -> {
            calls.set(calls.get() + 1);
            if (url.contains("%0A") || url.contains("\n")) {
                return "[[[\"甲\\n乙\",\"a\\nb\",null,null]],null,\"en\"]";
            }
            return "[[[\"X\",\"x\",null,null]],null,\"en\"]";
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
    void hugeBatchIsChunkedByCharacterBudget() {
        // Each request must stay within the char budget; alignment is echoed back
        // by translating each joined chunk into the same number of lines.
        List<String> urls = new java.util.ArrayList<>();
        HttpTransport inline = url -> {
            urls.add(url);
            // Decode the q= payload's line count and answer with that many lines.
            String q = java.net.URLDecoder.decode(url.substring(url.indexOf("&q=") + 3),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = q.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append("\\n");
                sb.append("譯").append(i);
            }
            return "[[[\"" + sb + "\",\"src\",null,null]],null,\"en\"]";
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
