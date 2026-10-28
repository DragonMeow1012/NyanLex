package com.dragonmeow.nyanlex;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source contract for the client-only world-sign render projection in every modern tree. */
class WorldSignRenderContractTest {
    private record Target(String mixin, String renderer, String config, String owner) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target("src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignTextMixin.java",
                    "src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignRendererLayoutMixin.java",
                    "src/main/resources/nyanlex.mixins.json", "SignText.class"),
            new Target("fabric1171/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignTextMixin.java",
                    "fabric1171/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignRendererLayoutMixin.java",
                    "fabric1171/src/main/resources/nyanlex.mixins.json", "SignBlockEntity.class"),
            new Target("fabric1182/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignTextMixin.java",
                    "fabric1182/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignRendererLayoutMixin.java",
                    "fabric1182/src/main/resources/nyanlex.mixins.json", "SignBlockEntity.class"),
            new Target("fabric1194/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignTextMixin.java",
                    "fabric1194/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignRendererLayoutMixin.java",
                    "fabric1194/src/main/resources/nyanlex.mixins.json", "SignBlockEntity.class"),
            new Target("fabric120/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignTextMixin.java",
                    "fabric120/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignRendererLayoutMixin.java",
                    "fabric120/src/main/resources/nyanlex.mixins.json", "SignText.class"),
            new Target("fabric12111/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignTextMixin.java",
                    "fabric12111/src/main/java/com/dragonmeow/nyanlex/fabric/mixin/SignRendererLayoutMixin.java",
                    "fabric12111/src/main/resources/nyanlex.mixins.json", "SignText.class"),
            new Target("fabric2612/src/main/java/com/dragonmeow/nyanlex/fabric26/mixin/SignTextMixin.java",
                    "fabric2612/src/main/java/com/dragonmeow/nyanlex/fabric26/mixin/SignRendererLayoutMixin.java",
                    "fabric2612/src/main/resources/nyanlex.fabric26.mixins.json", "SignText.class"),
            new Target("fabric26/src/main/java/com/dragonmeow/nyanlex/fabric26/mixin/SignTextMixin.java",
                    "fabric26/src/main/java/com/dragonmeow/nyanlex/fabric26/mixin/SignRendererLayoutMixin.java",
                    "fabric26/src/main/resources/nyanlex.fabric26.mixins.json", "SignText.class"),
            new Target("neoforge120/src/main/java/com/dragonmeow/nyanlex/neoforge/mixin/SignTextMixin.java",
                    "neoforge120/src/main/java/com/dragonmeow/nyanlex/neoforge/mixin/SignRendererLayoutMixin.java",
                    "neoforge120/src/main/resources/nyanlex.neoforge.mixins.json", "SignText.class"),
            new Target("neoforge/src/main/java/com/dragonmeow/nyanlex/neoforge/mixin/SignTextMixin.java",
                    "neoforge/src/main/java/com/dragonmeow/nyanlex/neoforge/mixin/SignRendererLayoutMixin.java",
                    "neoforge/src/main/resources/nyanlex.neoforge.mixins.json", "SignText.class"),
            new Target("neoforge26/src/main/java/com/dragonmeow/nyanlex/neoforge26/mixin/SignTextMixin.java",
                    "neoforge26/src/main/java/com/dragonmeow/nyanlex/neoforge26/mixin/SignRendererLayoutMixin.java",
                    "neoforge26/src/main/resources/nyanlex.neoforge26.mixins.json", "SignText.class"));

    @Test
    void everyModernTreeProjectsOnlyTheFourRowRenderView() throws IOException {
        assertEquals(11, TARGETS.size());
        Path root = repositoryRoot();
        for (Target target : TARGETS) {
            String source = Files.readString(root.resolve(target.mixin()), StandardCharsets.UTF_8);
            String compact = source.replaceAll("\\s+", " ");

            assertTrue(compact.contains("@Mixin(" + target.owner() + ")"), target.mixin());
            assertTrue(compact.contains("getRenderMessages(ZLjava/util/function/Function;)"), target.mixin());
            assertTrue(compact.contains("cancellable = true, require = 1"), target.mixin());
            assertTrue(compact.indexOf("WorldSignRenderScope.active()")
                    < compact.indexOf("HookGuard.enter(\"WorldSign.renderMessages\")"), target.mixin());
            assertTrue(compact.contains("HookGuard.enter(\"WorldSign.renderMessages\")"), target.mixin());
            assertTrue(compact.contains("worldSignLines(source)"), target.mixin());
            assertTrue(compact.contains("int slot = filtered ? 1 : 0"), target.mixin());
            assertTrue(compact.contains("Arrays.equals(this.nyanlex$projectedLines[slot], projected)"),
                    target.mixin() + ": async wording/style changes must invalidate the prepared rows");
            assertTrue(compact.contains("this.nyanlex$projectedLines[slot] = projected.clone()"), target.mixin());
            assertTrue(compact.contains("prepared[i] = projected[i].getVisualOrderText()"), target.mixin());
            assertFalse(compact.contains("layout.apply(projected[i])"),
                    target.mixin() + ": vanilla would discard every wrapped fragment after the first");
            assertTrue(compact.indexOf("if (projected == source || Arrays.equals(projected, source)) return;")
                            < compact.indexOf("cir.setReturnValue"),
                    target.mixin() + ": G/original and cache misses must fall through to vanilla");

            assertFalse(compact.contains("setMessage("), target.mixin());
            assertFalse(compact.contains("setText("), target.mixin());
            assertFalse(compact.contains("CompoundTag"), target.mixin());
            String renderer = Files.readString(root.resolve(target.renderer()), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
            assertTrue(renderer.contains("WorldSignRenderAdapter.fit"), target.renderer());
            assertTrue(renderer.contains("HookGuard.call(\"SignRendererLayout.fitTranslatedRows\""),
                    target.renderer());
            assertTrue(renderer.contains("getRenderMessages(ZLjava/util/function/Function;)"),
                    target.renderer());
            assertTrue(renderer.contains("require = 1"), target.renderer());

            String config = Files.readString(root.resolve(target.config()), StandardCharsets.UTF_8);
            assertTrue(config.contains("\"SignRendererLayoutMixin\""), target.config());
            assertTrue(config.contains("\"SignTextMixin\""), target.config());
        }
    }

    @Test
    void projectionUsesNameSurfacePolicyWithoutThePlayerNameShortCircuit() throws IOException {
        Path root = repositoryRoot();
        String platform = Files.readString(root.resolve(
                "src/main/java/com/dragonmeow/nyanlex/fabric/NyanLexFabric.java"),
                StandardCharsets.UTF_8);
        String service = Files.readString(root.resolve(
                "src/main/java/com/dragonmeow/nyanlex/service/TranslationService.java"),
                StandardCharsets.UTF_8);

        int start = platform.indexOf("Component[] worldSignLines");
        int end = platform.indexOf("private static Component translateNameTag", start);
        String projection = platform.substring(start, end);
        assertTrue(projection.contains("s.nameMode() == DisplayMode.ORIGINAL_ONLY"));
        assertTrue(projection.contains("s.warmWorldTextBatch(requests)"));
        assertTrue(projection.contains("\"worldSign\", source[i], s::translateUi"));
        assertFalse(projection.contains("nameTag("),
                "sign prose must not be mistaken for a TAB-listed player name tag");
        assertTrue(service.contains("if (!config.aiName) return;"));
        assertTrue(service.contains("warmMasked(sources, true, config.nameMode, config.aiName);"));
    }

    private static Path repositoryRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("sync-core.ps1"))) return cwd;
        Path parent = cwd.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("sync-core.ps1"))) return parent;
        throw new IllegalStateException("Cannot find repository root from " + cwd);
    }
}
