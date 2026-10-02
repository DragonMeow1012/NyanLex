package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.config.TranslationLanguages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationLanguagesTest {
    @Test
    void convertsMinecraftLanguageIdsToApiTags() {
        assertEquals("zh-TW", TranslationLanguages.fromMinecraftCode("zh_tw"));
        assertEquals("zh-CN", TranslationLanguages.fromMinecraftCode("zh_cn"));
        assertEquals("ja-JP", TranslationLanguages.fromMinecraftCode("ja_jp"));
        assertEquals("en-US", TranslationLanguages.fromMinecraftCode("en_us"));
        assertEquals("pt-BR", TranslationLanguages.fromMinecraftCode("pt_br"));
        assertEquals("sr-Latn", TranslationLanguages.fromMinecraftCode("sr_latn"));
    }
}
