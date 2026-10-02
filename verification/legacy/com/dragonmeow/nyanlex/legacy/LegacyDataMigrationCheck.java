package com.dragonmeow.nyanlex.legacy;

import com.dragonmeow.nyanlex.translate.TranslationFile;
import java.nio.file.*;
import java.util.*;

/**
 * Java 8 check of the legacy trees' LegacyDataMigration (copy, merge with the current snapshot
 * winning, once-only marker, unreadable files, the 8192-row snapshot capacity).
 * Run with verification/check-legacy-migration.ps1; the legacy trees have no JUnit source set.
 */
public class LegacyDataMigrationCheck {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); System.out.println("ok: " + what); }
    static Map<String,String> m(String... kv) { Map<String,String> r = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) r.put(kv[i], kv[i+1]); return r; }
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("legacy8");
        List<String> log = new ArrayList<>();
        new TranslationFile("legacy-template-v1", "zh-TW", "google", m("m-old","舊機"), m("a-old","舊AI","shared","舊共用")).write(dir.resolve("nyanslate-imported-zh-tw.json"));
        new TranslationFile("legacy-template-v1", "zh-TW", "google", m(), m("a-new","新AI","shared","新共用")).write(dir.resolve("nyanlex-imported-zh-tw.json"));
        Files.write(dir.resolve("nyanslate-legacy.json"), "{\"old\":1}".getBytes("UTF-8"));
        byte[] legacyBefore = Files.readAllBytes(dir.resolve("nyanslate-imported-zh-tw.json"));
        byte[] currentBefore = Files.readAllBytes(dir.resolve("nyanlex-imported-zh-tw.json"));
        int n = LegacyDataMigration.migrate(dir, log::add);
        System.out.println(log);
        check(n == 2, "one merge + one copy = 2, got " + n);
        TranslationFile merged = TranslationFile.read(dir.resolve("nyanlex-imported-zh-tw.json"));
        check(merged.ai.get("shared").equals("新共用"), "current wins");
        check(merged.ai.containsKey("a-old") && merged.ai.containsKey("a-new"), "union ai");
        check(merged.machine.containsKey("m-old"), "machine rows merged for same provider");
        check(Arrays.equals(legacyBefore, Files.readAllBytes(dir.resolve("nyanslate-imported-zh-tw.json"))), "legacy untouched");
        check(Arrays.equals(currentBefore, Files.readAllBytes(dir.resolve("nyanlex-imported-zh-tw.json.pre-merge.bak"))), "backup is verbatim");
        check(Files.exists(dir.resolve("nyanlex-legacy.json")), "settings copied");
        // once only
        Files.delete(dir.resolve("nyanlex-legacy.json"));
        Files.delete(dir.resolve("nyanlex-imported-zh-tw.json"));
        log.clear();
        check(LegacyDataMigration.migrate(dir, log::add) == 0, "second run does nothing");
        check(!Files.exists(dir.resolve("nyanlex-imported-zh-tw.json")) && !Files.exists(dir.resolve("nyanlex-legacy.json")), "deleted files are not resurrected");
        // unreadable
        Path d2 = Files.createTempDirectory("legacy8b");
        Files.write(d2.resolve("nyanslate-imported-zh-tw.json"), "garbage".getBytes("UTF-8"));
        new TranslationFile("legacy-template-v1", "zh-TW", "google", m(), m("x","y")).write(d2.resolve("nyanlex-imported-zh-tw.json"));
        byte[] before = Files.readAllBytes(d2.resolve("nyanlex-imported-zh-tw.json"));
        log.clear();
        check(LegacyDataMigration.migrate(d2, log::add) == 0, "unreadable legacy: nothing merged");
        check(Arrays.equals(before, Files.readAllBytes(d2.resolve("nyanlex-imported-zh-tw.json"))), "current untouched");
        System.out.println(log);
        // capacity
        Path d3 = Files.createTempDirectory("legacy8c");
        Map<String,String> big = new LinkedHashMap<>();
        for (int i = 0; i < 8000; i++) big.put("old " + i, "舊" + i);
        Map<String,String> cur = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) cur.put("cur " + i, "新" + i);
        new TranslationFile("legacy-template-v1", "zh-TW", "google", m(), big).write(d3.resolve("nyanslate-imported-zh-tw.json"));
        new TranslationFile("legacy-template-v1", "zh-TW", "google", m(), cur).write(d3.resolve("nyanlex-imported-zh-tw.json"));
        check(LegacyDataMigration.migrate(d3, null) == 1, "capacity merge ran");
        TranslationFile capped = TranslationFile.read(d3.resolve("nyanlex-imported-zh-tw.json"));
        check(capped.ai.size() == 8192, "merged snapshot capped at 8192, got " + capped.ai.size());
        check(capped.ai.containsKey("cur 499"), "all current rows kept");
    }
}
