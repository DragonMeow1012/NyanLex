package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.config.CodexModelCatalog;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient.ModelOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexModelCatalogTest {

    private static final ModelOption TERRA = new ModelOption(
            "gpt-5.6-terra", "GPT-5.6 Terra",
            List.of("low", "medium", "high"), "low", true);
    private static final ModelOption SOL = new ModelOption(
            "gpt-5.6-sol", "GPT-5.6 Sol",
            List.of("medium", "high", "xhigh"), "medium", false);

    @Test
    void unavailableSelectionFallsBackToServerDefaultAndNormalizesEffort() {
        TranslatorConfig config = new TranslatorConfig();
        config.codexModel = "missing";
        config.codexReasoningEffort = "ultra";

        CodexModelCatalog.normalizeSelection(config, List.of(SOL, TERRA));

        assertEquals("gpt-5.6-terra", config.codexModel);
        assertEquals("low", config.codexReasoningEffort);
    }

    @Test
    void filterMatchesModelIdAndDisplayName() {
        assertEquals(List.of(SOL), CodexModelCatalog.filter(List.of(TERRA, SOL), "sol"));
        assertEquals(List.of(TERRA), CodexModelCatalog.filter(List.of(TERRA, SOL), "Terra"));
    }

    @Test
    void supportedEffortsComeFromTheSelectedModel() {
        TranslatorConfig config = new TranslatorConfig();
        config.codexModel = SOL.model();

        assertEquals(SOL.reasoningEfforts(),
                CodexModelCatalog.supportedEfforts(config, List.of(TERRA, SOL)));
    }
}
