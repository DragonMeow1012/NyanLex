package com.dragonmeow.nyanlex.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The four hand-written language files stay in step, and no lang key is left without a user. */
class LangFilesTest {

    private static final List<String> HAND_WRITTEN = List.of("zh_tw", "zh_hk", "zh_cn", "en_us");
    private static final Path JAVA = Path.of("src/main/java");

    private static JsonObject lang(String code) {
        try (InputStream in = LangFilesTest.class.getResourceAsStream("/assets/nyanlex/lang/" + code + ".json")) {
            assertNotNull(in, "missing lang file " + code);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int placeholders(String text) {
        Matcher m = Pattern.compile("%(?:\\d+\\$)?s").matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void theFourHandWrittenLanguagesHaveExactlyTheSameKeys() {
        Set<String> reference = new TreeSet<>(lang("zh_tw").keySet());
        assertTrue(reference.size() > 300, "zh_tw is the full set");
        for (String code : HAND_WRITTEN) {
            Set<String> keys = new TreeSet<>(lang(code).keySet());
            Set<String> missing = new TreeSet<>(reference);
            missing.removeAll(keys);
            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(reference);
            assertEquals(Set.of(), missing, code + " is missing keys");
            assertEquals(Set.of(), extra, code + " has keys zh_tw does not");
        }
    }

    @Test
    void everyKeyHasTheSamePlaceholdersInEveryHandWrittenLanguageAndNoValueIsBlankExceptTheEmptyReason() {
        JsonObject tw = lang("zh_tw");
        for (String code : HAND_WRITTEN) {
            JsonObject json = lang(code);
            for (String key : tw.keySet()) {
                String value = json.get(key).getAsString();
                if (!key.equals("screen.nyanlex.warmup.reason.none")) assertFalse(value.isBlank(), code + " blank " + key);
                assertEquals(placeholders(tw.get(key).getAsString()), placeholders(value), code + " placeholders of " + key);
            }
        }
    }

    @Test
    void noHandWrittenLangTextContainsAnEmojiOrTheOldWords() {
        Pattern emoji = Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F000}-\\x{1F2FF}]");
        for (String code : HAND_WRITTEN) {
            JsonObject json = lang(code);
            for (String key : json.keySet()) {
                String value = json.get(key).getAsString();
                assertFalse(emoji.matcher(value).find(), code + " has an emoji in " + key);
                assertFalse(value.contains("喵"), code + " keeps the old cat-speak in " + key);
            }
        }
    }

    // ------------------------------------------------------------------ every key has a user

    private static final Pattern KEY_LITERAL = Pattern.compile("\"([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)\"");

    private static String allSource() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    private static final String[] DYNAMIC_FAMILIES = {
            "screen.nyanlex.warmup.warn.", "screen.nyanlex.warmup.state.", "screen.nyanlex.warmup.reason.",
            "screen.nyanlex.provider."};

    @Test
    void noLangKeyIsLeftWithoutAUserAndEveryUsedKeyExists() throws IOException {
        String source = allSource();
        Set<String> literals = new HashSet<>();
        Matcher m = KEY_LITERAL.matcher(source);
        while (m.find()) literals.add(m.group(1));
        Set<String> listed = new HashSet<>(SettingsCatalog.allLangKeys());
        listed.addAll(SettingsModel.allLangKeys());
        JsonObject tw = lang("zh_tw");
        List<String> orphans = new ArrayList<>();
        outer:
        for (String key : tw.keySet()) {
            if (literals.contains(key) || listed.contains(key)) continue;
            for (String family : DYNAMIC_FAMILIES) if (key.startsWith(family)) continue outer;
            orphans.add(key);
        }
        assertEquals(List.of(), orphans, "lang keys nothing refers to");
        // the other direction: every key the screens ask for exists (checked for the listed ones)
        List<String> unknown = new ArrayList<>();
        for (String key : listed) if (!tw.has(key)) unknown.add(key);
        assertEquals(List.of(), unknown);
        // literal keys of the mod's own namespaces
        List<String> missing = new ArrayList<>();
        for (String lit : literals) {
            boolean ours = lit.startsWith("nyanlex.") || lit.startsWith("screen.nyanlex.") || lit.startsWith("message.nyanlex.")
                    || lit.startsWith("config.nyanlex.") || lit.startsWith("key.nyanlex.");
            if (ours && !tw.has(lit) && !lit.endsWith(".json")) {
                if (!isPrefixOfDynamicKey(lit, tw)) missing.add(lit);
            }
        }
        assertEquals(List.of(), missing, "keys used in code but absent from zh_tw");
    }

    private static boolean isPrefixOfDynamicKey(String literal, JsonObject tw) {
        for (String key : tw.keySet()) if (key.startsWith(literal)) return true;
        return false;
    }

    // ------------------------------------------------------------------ wording rules of this round

    private static List<Path> allLangFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/assets/nyanlex/lang"))) {
            return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void noLangValueNamesAServerOrTheGameModeOfOne() throws IOException {
        for (Path file : allLangFiles()) {
            JsonObject json = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            for (String key : json.keySet()) {
                String value = json.get(key).getAsString().toLowerCase(java.util.Locale.ROOT);
                assertFalse(value.contains("hypixel") || value.contains("skyblock"), file.getFileName() + " " + key);
            }
        }
    }

    @Test
    void theRemovedKeysAreGoneFromEveryLangFile() throws IOException {
        List<String> gone = List.of("nyanlex.setup.display.chat", "nyanlex.setup.display.other",
                "nyanlex.setup.mode.both_recommended", "nyanlex.setup.mode.translation", "nyanlex.setup.mode.both",
                "nyanlex.setup.mode.off", "message.nyanlex.hub.detect_failed", "nyanlex.files.debug_dir",
                "nyanlex.files.debug_dir.desc", "nyanlex.files.lang_probe", "nyanlex.files.lang_probe.desc");
        for (Path file : allLangFiles()) {
            JsonObject json = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            for (String key : gone) assertFalse(json.has(key), file.getFileName() + " still has " + key);
        }
    }

    @Test
    void theDisplayModesHaveOneNameEverywhere() {
        JsonObject tw = lang("zh_tw");
        assertEquals("不翻譯", tw.get("nyanlex.settings.state.original").getAsString());
        assertEquals("雙語", tw.get("nyanlex.settings.state.both").getAsString());
        assertEquals("譯文", tw.get("nyanlex.settings.state.translation").getAsString());
        for (String code : List.of("zh_tw", "zh_hk")) {
            JsonObject json = lang(code);
            for (String key : json.keySet()) {
                String v = json.get(key).getAsString();
                assertFalse(v.contains("只有翻譯") || v.contains("原文＋譯文（推薦）") || v.contains("只顯示譯文"), code + " " + key);
            }
            // the first mention explains the short names
            String intro = json.get("nyanlex.manual.s2.body").getAsString();
            assertTrue(intro.contains("雙語（原文＋譯文）") && intro.contains("譯文（只看譯文）"), code + ": " + intro);
        }
    }

    @Test
    void noScreenTextCallsAMessageToTheServiceARequestAndDebugModeHasItsName() {
        JsonObject tw = lang("zh_tw");
        for (String key : List.of("nyanlex.settings.cooldown", "nyanlex.settings.debug.tip", "screen.nyanlex.warmup.estimate")) {
            assertFalse(tw.get(key).getAsString().contains("請求"), key);
        }
        assertEquals("偵錯模式：%s", tw.get("nyanlex.settings.debug").getAsString());
        assertTrue(tw.get("nyanlex.settings.debug.tip").getAsString().contains("1000"));
        assertTrue(tw.get("nyanlex.manual.s9.body").getAsString().contains("偵錯模式"));
        assertEquals("偵錯紀錄", tw.get("nyanlex.files.debug_log").getAsString());
        for (String code : HAND_WRITTEN) {
            assertFalse(lang(code).get("nyanlex.manual.s10.body").getAsString().matches("(?s).*(AI 輔助|AI 辅助|AI assistance).*"), code);
            assertTrue(lang(code).get("nyanlex.manual.s10.body").getAsString().contains(ProjectLinks.GITHUB_TOKEN), code);
        }
    }
}
