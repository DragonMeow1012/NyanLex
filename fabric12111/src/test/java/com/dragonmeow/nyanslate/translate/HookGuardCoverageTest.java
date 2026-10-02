package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level guard for the canonical (root) tree: every mixin handler must go through
 * HookGuard (hit + exception isolation) and every guarded id must be listed in the generated
 * NyanslateHooks registry. Other trees are verified by {@code verification/hook-guards.py check}.
 */
class HookGuardCoverageTest {
    private static final Path GLUE = Paths.get("src/main/java/com/dragonmeow/nyanslate/fabric");
    private static final Pattern HANDLER_ANNOTATION =
            Pattern.compile("^\\s*@(Inject|Redirect|ModifyVariable|ModifyArg)\\b", Pattern.MULTILINE);
    private static final Pattern GUARD_ID =
            Pattern.compile("HookGuard\\.(?:enter|enterSticky|run|runSticky|call)\\(\\s*\"([^\"]+)\"");
    private static final Pattern FAIL_ID = Pattern.compile("HookGuard\\.fail\\(\\s*\"([^\"]+)\"");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<Path> mixinFiles() throws IOException {
        try (Stream<Path> s = Files.list(GLUE.resolve("mixin"))) {
            List<Path> out = new ArrayList<>();
            s.filter(p -> p.getFileName().toString().endsWith("Mixin.java")).forEach(out::add);
            return out;
        }
    }

    @Test
    void everyMixinHandlerIsGuardedAndCatchesFailures() throws IOException {
        List<Path> files = mixinFiles();
        assertFalse(files.isEmpty(), "mixin sources not found from " + Paths.get("").toAbsolutePath());
        int handlers = 0;
        for (Path file : files) {
            String src = read(file);
            int annotated = 0;
            Matcher m = HANDLER_ANNOTATION.matcher(src);
            while (m.find()) annotated++;
            Matcher enters = GUARD_ID.matcher(src);
            int guarded = 0;
            while (enters.find()) guarded++;
            Matcher fails = FAIL_ID.matcher(src);
            int caught = 0;
            while (fails.find()) caught++;
            assertEquals(annotated, guarded, file.getFileName() + ": handlers without HookGuard.enter");
            assertEquals(annotated, caught, file.getFileName() + ": handlers without HookGuard.fail");
            handlers += annotated;
        }
        assertTrue(handlers >= 20, "suspiciously few handlers found: " + handlers);
    }

    @Test
    void generatedHookRegistryListsEveryGuardedId() throws IOException {
        Set<String> used = new HashSet<>();
        try (Stream<Path> s = Files.walk(GLUE)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java")
                    && !f.getFileName().toString().equals("NyanslateHooks.java")
                    && f.toString().contains("fabric"))::iterator) {
                Matcher m = GUARD_ID.matcher(read(p));
                while (m.find()) used.add(m.group(1));
            }
        }
        String registry = read(GLUE.resolve("NyanslateHooks.java"));
        for (String id : used) {
            assertTrue(registry.contains("\"" + id + "\""), "hook id missing from NyanslateHooks: " + id);
        }
        Matcher listed = Pattern.compile("\"([A-Za-z]+\\.[A-Za-z]+)\"").matcher(registry);
        while (listed.find()) {
            assertTrue(used.contains(listed.group(1)), "NyanslateHooks lists an unused id: " + listed.group(1));
        }
    }
}
