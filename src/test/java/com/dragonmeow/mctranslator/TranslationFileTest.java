package com.dragonmeow.mctranslator;
import com.dragonmeow.mctranslator.translate.TranslationFile;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.TranslationResult;
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
}
