package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.ScreenTranslationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenTranslationPolicyTest {

    @Test
    void blocksKnownVanillaOptionsPages() {
        assertFalse(ScreenTranslationPolicy.allowsTranslation("options.language"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("options.language.title"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("options.skinCustomisation.title"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("options.sounds.title"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("controls.title"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("controls.keybinds.title"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("options.chat.title"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("resourcePack.title"));
        assertFalse(ScreenTranslationPolicy.allowsTranslation("options.accessibility.title"));
    }

    @Test
    void allowsVanillaVideoSettings() {
        assertTrue(ScreenTranslationPolicy.allowsTranslation("options.videoTitle"));
        assertTrue(ScreenTranslationPolicy.allowsTranslation("options.video.title"));
    }

    @Test
    void unknownAndModdedScreensDefaultToAllowed() {
        assertTrue(ScreenTranslationPolicy.allowsTranslation("iris.options.title"));
        assertTrue(ScreenTranslationPolicy.allowsTranslation("menu.custom_options.title"));
        assertTrue(ScreenTranslationPolicy.allowsTranslation(null));
        assertTrue(ScreenTranslationPolicy.allowsTranslation(""));
    }

    @Test
    void repeatedLanguageRowsNeverEnterTheCollector() {
        List<String> repeatedRows = List.of("English", "English", "English", "English");
        List<String> collected = repeatedRows.stream()
                .filter(ignored -> ScreenTranslationPolicy.allowsTranslation("options.language"))
                .toList();
        assertTrue(collected.isEmpty());
    }
}
