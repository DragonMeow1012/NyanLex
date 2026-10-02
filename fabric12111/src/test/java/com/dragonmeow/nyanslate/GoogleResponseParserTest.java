package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.GoogleResponseParser;
import com.dragonmeow.nyanslate.translate.TranslationException;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleResponseParserTest {

    @Test
    void parsesSingleSentence() throws Exception {
        // Real-shape response from translate_a/single for q="Hello", tl=zh-TW
        String body = "[[[\"你好\",\"Hello\",null,null,10]],null,\"en\",null,null,null,1.0,[],[[\"en\"],null,[1.0],[\"en\"]]]";
        TranslationResult r = GoogleResponseParser.parse(body);
        assertEquals("你好", r.translatedText());
        assertEquals("en", r.detectedSourceLang());
    }

    @Test
    void concatenatesMultipleSentenceChunks() throws Exception {
        String body = "[[[\"你好\",\"Hello \",null,null],[\"世界\",\"world\",null,null]],null,\"en\"]";
        TranslationResult r = GoogleResponseParser.parse(body);
        assertEquals("你好世界", r.translatedText());
        assertEquals("en", r.detectedSourceLang());
    }

    @Test
    void handlesUnicodeEscapesViaGson() throws Exception {
        // Gson must decode the JSON backslash-u escapes, not pass them through literally.
        String body = "[[[\"\\u4f60\\u597d\",\"hi\",null,null]],null,\"en\"]";
        TranslationResult r = GoogleResponseParser.parse(body);
        assertEquals("你好", r.translatedText());
    }

    @Test
    void emptyBodyThrows() {
        assertThrows(TranslationException.class, () -> GoogleResponseParser.parse(""));
        assertThrows(TranslationException.class, () -> GoogleResponseParser.parse(null));
    }

    @Test
    void malformedJsonThrows() {
        TranslationException ex = assertThrows(TranslationException.class,
                () -> GoogleResponseParser.parse("not-json"));
        assertTrue(ex.getMessage().toLowerCase().contains("parse")
                || ex.getMessage().toLowerCase().contains("array"));
    }

    @Test
    void nullSentenceArrayThrows() {
        assertThrows(TranslationException.class, () -> GoogleResponseParser.parse("[null,null,\"en\"]"));
    }
}
