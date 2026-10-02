package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.config.TranslatorConfig;

/**
 * Test configs. A fresh {@link TranslatorConfig} has the 送出翻譯請求 master switch OFF; the
 * service tests that exercise translation start from this one instead, which turns it on
 * (display modes keep their defaults: chat 雙語, the rest 譯文, 介面 不翻譯).
 */
public final class TestConfigs {
    private TestConfigs() {}

    public static TranslatorConfig translating() {
        TranslatorConfig c = new TranslatorConfig();
        c.translationRequestsEnabled = true;
        return c;
    }
}
