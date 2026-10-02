package com.dragonmeow.nyanslate;
import com.dragonmeow.nyanslate.translate.TranslationFile;
import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class TranslationFileTest {
    @TempDir Path dir;
    @Test void roundTripIncludesOnlyTranslationDataAndNeverOverwritesFiles() throws Exception {
        var original=new TranslationFile("modern-template-v1","zh-TW","google",Map.of("Hello","你好"),Map.of("World","世界"));
        Path path=dir.resolve("share.json"); original.write(path);
        var read=TranslationFile.read(path);
        read.requireCompatible("modern-template-v1","zh_tw","google");
        assertEquals(original.machine,read.machine); assertEquals(original.ai,read.ai);
        assertFalse(Files.readString(path).contains("apiKey"));
        assertThrows(java.io.IOException.class,()->original.write(path));
        assertThrows(java.io.IOException.class,()->read.requireCompatible("modern-template-v1","ja","google"));
        assertThrows(java.io.IOException.class,()->read.requireCompatible("legacy-template-v1","zh-TW","google"));
    }
    @Test void invalidSchemaOrRowsCannotBeImported() throws Exception {
        Path path=dir.resolve("bad.json");Files.writeString(path,"{\"schema\":99}");
        assertThrows(java.io.IOException.class,()->TranslationFile.read(path));
        Files.writeString(path,"{\"schema\":1,\"machine\":{\"hello\":null},\"ai\":{}}");
        assertThrows(java.io.IOException.class,()->TranslationFile.read(path));
    }
    @Test void mergeRetainsLocalFinalRowsRejectsBrokenTokensAndDoesNotRequestBackend() {
        TranslationCache cache=new TranslationCache((source,target)->{throw new AssertionError("network");},"zh-TW",Runnable::run,100);
        assertEquals(1,cache.importTranslations(Map.of("Hello","你好")));
        assertEquals(1,cache.importTranslations(Map.of("Hello","哈囉","World","世界","Coins ⟦MT0⟧","硬幣")));
        assertEquals("你好",cache.getCached("Hello"));
        assertEquals("世界",cache.getCached("World"));
        assertEquals(2,cache.exportTranslations().size());
    }

    // P1.1 B8: importTranslations()/exportTranslations() are named directly in the
    // design finding ("F-1 轉存、遷移、importTranslations、F5 重建 只經過 usable()") as the
    // paths that must reject a row whose masked-name/term placeholder (⟦n⟧) count no
    // longer matches between key and value — an import file's row could otherwise merge
    // a name-dropping translation straight into the shared template, with no live
    // request or TranslationService-level self-heal ever getting a chance to catch it.
    @Test void importRejectsARowThatDroppedItsMaskedPlaceholder() {
        TranslationCache cache = new TranslationCache(
                (source, target) -> { throw new AssertionError("network"); }, "zh-TW", Runnable::run, 100);
        // Before P1.1: this row had the same shape as every other usable() check
        // (no CS/MT mismatch, no transliteration residue) and was silently imported.
        assertEquals(0, cache.importTranslations(Map.of("⟦0⟧'s Sword", "某人的劍")),
                "a value that lost its ⟦0⟧ placeholder must not be learned as the family template");
        assertNull(cache.getCached("⟦0⟧'s Sword"));

        // A row that keeps the SAME placeholder is imported normally.
        assertEquals(1, cache.importTranslations(Map.of("⟦0⟧'s Sword", "⟦0⟧的劍")));
        assertEquals("⟦0⟧的劍", cache.getCached("⟦0⟧'s Sword"));
    }

    @Test void exportDropsAStoredRowThatNoLongerMatchesItsPlaceholderCount() {
        // A damaged row that reached the disk some other way (hand-edited file, an older
        // build's write, direct disk migration) must not be re-exported either — export
        // only ever reads {@code store.entries()} directly, bypassing importTranslations'
        // own check entirely, so it needs the very same guard.
        Map<String, String> disk = new java.util.LinkedHashMap<>();
        disk.put("⟦0⟧'s Sword", "⟦0⟧的劍");   // good: placeholder preserved
        disk.put("⟦1⟧'s Bow", "某人的弓");      // bad: placeholder silently lost
        com.dragonmeow.nyanslate.cache.PersistentStore store =
                new com.dragonmeow.nyanslate.cache.PersistentStore() {
                    @Override public String get(String key) { return disk.get(key); }
                    @Override public void put(String key, String value) { disk.put(key, value); }
                    @Override public void clear() { disk.clear(); }
                    @Override public Map<String, String> entries() { return new java.util.LinkedHashMap<>(disk); }
                };
        TranslationCache cache = new TranslationCache(
                (source, target) -> { throw new AssertionError("network"); }, "zh-TW", Runnable::run, 100,
                10_000L, () -> 0L, store);

        Map<String, String> exported = cache.exportTranslations();
        assertTrue(exported.containsKey("⟦0⟧'s Sword"));
        assertFalse(exported.containsKey("⟦1⟧'s Bow"));
    }
}
