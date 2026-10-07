package com.dragonmeow.nyanlex;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mod reports through the action bar and system notifications only: no mod message is ever written
 * into the chat. (Translated chat lines are the translation output, not a mod message, and are added from
 * variables, never from a fresh Component.)
 */
class NoChatMessagesTest {

    private static final Path GLUE = Path.of("src/main/java/com/dragonmeow/nyanlex/fabric");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(GLUE)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void noGlueSourceWritesAModMessageIntoTheChat() throws IOException {
        List<Path> files = sources();
        assertTrue(files.size() > 10, "found the glue sources from " + Path.of("").toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        Pattern freshComponent = Pattern.compile("addMessage\\(\\s*(net\\.minecraft\\.network\\.chat\\.)?Component\\.");
        Pattern directChat = Pattern.compile("sendSystemMessage\\(|displayClientMessage\\(|sendCommand\\(|sendChat\\(");
        for (Path file : files) {
            String text = read(file);
            if (freshComponent.matcher(text).find()) offenders.add(file + ": addMessage(Component...)");
            if (directChat.matcher(text).find()) offenders.add(file + ": direct chat call");
            if (text.contains("message.nyanlex.prefix")) offenders.add(file + ": uses the chat prefix");
            if (text.contains("private static void status(")) offenders.add(file + ": still has the chat writer");
            if (Pattern.compile("\\bstatus\\(Component").matcher(text).find()) offenders.add(file + ": calls status(Component)");
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void theOnlyChatWritesAreTheTranslatedLinesThemselves() throws IOException {
        String text = read(GLUE.resolve("NyanLexFabric.java"));
        Pattern call = Pattern.compile("getChat\\(\\)\\.addMessage\\(([^;]*)\\);");
        var matcher = call.matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
            String arg = matcher.group(1).trim();
            assertTrue(arg.equals("shown") || arg.equals("decorated") || arg.startsWith("decorate(") || arg.equals("original"),
                    "chat only ever gets translated or original chat lines, not mod text: " + arg);
        }
        assertTrue(found >= 3, "the translated chat lines are still added");
    }

    @Test
    void feedbackGoesToTheActionBarAndNotificationsAndNoHintIsShownWherePressingDoesNothing() throws IOException {
        String text = read(GLUE.resolve("NyanLexFabric.java"));
        assertTrue(text.contains("private static void feedback("), "R, P and G report through the action bar");
        assertTrue(text.contains("public static void toast("), "menu and background results report through a notification");
        assertFalse(text.contains("tooltip_hint_pending"), "no hint where pressing the key would do nothing");
    }
}
