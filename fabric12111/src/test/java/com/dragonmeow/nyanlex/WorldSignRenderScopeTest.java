package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.WorldSignRenderScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSignRenderScopeTest {
    @AfterEach
    void clearScope() {
        WorldSignRenderScope.end();
    }

    @Test
    void scopeIsExplicitAndClearsCompletely() {
        assertFalse(WorldSignRenderScope.active());
        WorldSignRenderScope.begin();
        assertTrue(WorldSignRenderScope.active());
        WorldSignRenderScope.end();
        assertFalse(WorldSignRenderScope.active());
    }

    @Test
    void fitScaleNeverExpandsAndPreservesTheWholeRequestedWidth() {
        assertEquals(1.0F, WorldSignRenderScope.fitScale(90, 70));
        assertEquals(1.0F, WorldSignRenderScope.fitScale(90, 90));
        assertEquals(0.5F, WorldSignRenderScope.fitScale(90, 180));
        assertEquals(1.0F, WorldSignRenderScope.fitScale(0, 180));
    }
}
