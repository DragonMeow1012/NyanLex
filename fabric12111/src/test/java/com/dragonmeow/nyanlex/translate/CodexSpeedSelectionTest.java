package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodexSpeedSelectionTest {
    @TempDir Path temp;

    @Test void premiumCatalogNeverEnablesFastWithoutConsent() throws Exception {
        try (var client = new CodexAppServerClient(temp.resolve("home"), temp.resolve("work"))) {
            var field = CodexAppServerClient.class.getDeclaredField("cachedModels");
            field.setAccessible(true);
            var method = CodexAppServerClient.class.getDeclaredMethod("requestedServiceTier", String.class, boolean.class);
            method.setAccessible(true);
            field.set(client, List.of(new CodexAppServerClient.ModelOption("model", "Model", List.of("low"),
                    List.of("default", "priority"), "low", true)));
            assertNull(method.invoke(client, "model", false));
            assertEquals("priority", method.invoke(client, "model", true));
            field.set(client, List.of(new CodexAppServerClient.ModelOption("model", "Model", List.of("low"),
                    List.of("default", "fast"), "low", true)));
            assertEquals("fast", method.invoke(client, "model", true));
            field.set(client, List.of(new CodexAppServerClient.ModelOption("model", "Model", List.of("low"),
                    List.of("default"), "low", true)));
            assertNull(method.invoke(client, "model", false));
            assertInstanceOf(java.io.IOException.class, assertThrows(InvocationTargetException.class,
                    () -> method.invoke(client, "model", true)).getCause());
        }
    }

    @Test void sharedTranslationEnvelopePreservesTheExplicitSpeedSelection() throws Exception {
        String messages = ",\"messages\":[{\"role\":\"system\",\"content\":\"Translate\"},{\"role\":\"user\",\"content\":\"Hello\"}]}";
        assertFalse(LocalAiTransportSupport.parseRequest("{\"model\":\"model\"" + messages).fastMode());
        assertFalse(LocalAiTransportSupport.parseRequest("{\"model\":\"model\",\"service_tier\":\"default\"" + messages).fastMode());
        assertTrue(LocalAiTransportSupport.parseRequest("{\"model\":\"model\",\"service_tier\":\"priority\"" + messages).fastMode());
    }
}
