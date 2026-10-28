package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps every Fabric 26.1.2 mixin boundary and its generated registry in sync. */
class HookGuardCoverageTest {
    private static final Path GLUE = Path.of("src/main/java/com/dragonmeow/nyanlex/fabric26");
    private static final Pattern HANDLER = Pattern.compile(
            "^\\s*@(Inject|Redirect|ModifyVariable|ModifyArg)\\b", Pattern.MULTILINE);
    private static final Pattern GUARD = Pattern.compile(
            "HookGuard\\.(?:enter|enterSticky|run|runSticky|call)\\(\\s*\"([^\"]+)\"");
    private static final Pattern INLINE = Pattern.compile(
            "HookGuard\\.(?:enter|enterSticky)\\(\\s*\"([^\"]+)\"");
    private static final Pattern FAIL = Pattern.compile(
            "HookGuard\\.fail\\(\\s*\"([^\"]+)\"");

    @Test
    void everyMixinHandlerIsGuardedAndCatchesFailures() throws IOException {
        List<Path> files = mixinFiles();
        assertFalse(files.isEmpty(), "Fabric 26.1.2 mixin sources were not found");
        int handlers = 0;
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            int annotated = occurrences(HANDLER, source);
            int guarded = occurrences(GUARD, source);
            int inline = occurrences(INLINE, source);
            int failed = occurrences(FAIL, source);
            assertEquals(annotated, guarded, file.getFileName() + ": unguarded handlers");
            assertEquals(inline, failed, file.getFileName() + ": guard without failure isolation");
            handlers += annotated;
        }
        assertTrue(handlers >= 20, "suspiciously few guarded handlers: " + handlers);
    }

    @Test
    void registryExactlyCoversGuardIds() throws IOException {
        Set<String> used = new HashSet<>();
        try (Stream<Path> stream = Files.walk(GLUE)) {
            for (Path file : (Iterable<Path>) stream.filter(path -> path.toString().endsWith(".java")
                    && !path.getFileName().toString().equals("NyanLexHooks.java"))::iterator) {
                Matcher matcher = GUARD.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) used.add(matcher.group(1));
            }
        }
        String registry = Files.readString(GLUE.resolve("NyanLexHooks.java"), StandardCharsets.UTF_8);
        for (String id : used) {
            assertTrue(registry.contains("\"" + id + "\""), "registry is missing " + id);
        }
        Matcher listed = Pattern.compile("\"([A-Za-z]+\\.[A-Za-z]+)\"").matcher(registry);
        while (listed.find()) {
            assertTrue(used.contains(listed.group(1)), "registry contains unused " + listed.group(1));
        }
    }

    private static List<Path> mixinFiles() throws IOException {
        try (Stream<Path> stream = Files.list(GLUE.resolve("mixin"))) {
            List<Path> files = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith("Mixin.java"))
                    .forEach(files::add);
            return files;
        }
    }

    private static int occurrences(Pattern pattern, String source) {
        int count = 0;
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) count++;
        return count;
    }
}
