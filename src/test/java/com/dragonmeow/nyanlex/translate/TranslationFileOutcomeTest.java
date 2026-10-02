package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export and import results are data, so each loader words them in the player's language. */
class TranslationFileOutcomeTest {

    @Test
    void theOutcomesCarryTheirNumbersAndKeepTheOldEnglishSentences() {
        var exported = new TranslationFileDialog.Outcome(TranslationFileDialog.Outcome.Kind.EXPORTED, 0, 3, 3, 0, "a.json", "c.json");
        assertEquals("Exported 3 translation file(s): a.json ... c.json", exported.english());
        var single = new TranslationFileDialog.Outcome(TranslationFileDialog.Outcome.Kind.EXPORTED, 0, 1, 1, 0, "a.json", "a.json");
        assertEquals("Exported 1 translation file(s): a.json", single.english());
        var imported = new TranslationFileDialog.Outcome(TranslationFileDialog.Outcome.Kind.IMPORTED, 12, 2, 3, 1, "b.json: broken", null);
        assertTrue(imported.english().startsWith("Imported 12 translations from 2/3 files"));
        assertTrue(imported.english().contains("Failed files: 1"));
        assertTrue(imported.english().contains("b.json: broken"));
        assertEquals("A translation file operation is already running.",
                new TranslationFileDialog.Outcome(TranslationFileDialog.Outcome.Kind.BUSY, 0, 0, 0, 0, null, null).english());
        assertEquals("Translation file: operation failed",
                new TranslationFileDialog.Outcome(TranslationFileDialog.Outcome.Kind.FAILED, 0, 0, 0, 0, "", null).english());
        assertEquals("Translation file: disk full",
                new TranslationFileDialog.Outcome(TranslationFileDialog.Outcome.Kind.FAILED, 0, 0, 0, 0, "disk full", null).english());
    }
}
