package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.config.CodexAccountDisplay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexAccountDisplayTest {

    @Test
    void emailAlwaysHidesMostOfTheLocalPart() {
        assertEquals("*****", CodexAccountDisplay.maskEmail(null));
        assertEquals("a***@example.com", CodexAccountDisplay.maskEmail("ab@example.com"));
        assertEquals("dr***n@example.com",
                CodexAccountDisplay.maskEmail("dragon@example.com"));
    }

    @Test
    void planNamesUseOneConsistentChatGptLabel() {
        assertEquals("ChatGPT", CodexAccountDisplay.formatPlan(""));
        assertEquals("ChatGPT Plus", CodexAccountDisplay.formatPlan("plus"));
        assertEquals("ChatGPT Team Plus", CodexAccountDisplay.formatPlan("TEAM_PLUS"));
    }
}
