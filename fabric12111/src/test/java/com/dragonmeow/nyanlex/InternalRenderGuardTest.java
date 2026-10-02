package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.InternalRenderGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalRenderGuardTest {
    @Test
    void nestedScopesAndExceptionsAlwaysRestoreTheDepth() {
        assertFalse(InternalRenderGuard.active());
        InternalRenderGuard.run(() -> {
            assertTrue(InternalRenderGuard.active());
            assertThrows(IllegalStateException.class, () -> InternalRenderGuard.run(() -> {
                assertTrue(InternalRenderGuard.active());
                throw new IllegalStateException("test");
            }));
            assertTrue(InternalRenderGuard.active());
        });
        assertFalse(InternalRenderGuard.active());
    }
}
