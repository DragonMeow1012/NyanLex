package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TranslationPartsTest {
    @TempDir Path dir;
    private TranslationFile file(Map<String,String> machine, Map<String,String> ai) {
        return new TranslationFile("modern-template-v1", "zh-TW", "google", machine, ai);
    }
    @Test void byteBoundariesCountUtf8AndJsonEscapesAndPreserveBothGroups() throws Exception {
        Map<String,String> machine = new LinkedHashMap<>(), ai = new LinkedHashMap<>();
        for (int i=0; i<30; i++) {
            machine.put("source \"\\\n"+i, "中文<&> 😀 "+i);
            ai.put("AI "+i, "翻譯\t\"\\ "+i);
        }
        var parts = file(machine,ai).writeParts(dir.resolve("share.json"),100,300);
        assertTrue(parts.size()>2);
        Map<String,String> restoredMachine=new LinkedHashMap<>(), restoredAi=new LinkedHashMap<>();
        for (Path part:parts) {
            assertTrue(Files.size(part)<=300);
            var read=TranslationFile.read(part);
            read.requireCompatible("modern-template-v1","zh-TW","google");
            restoredMachine.putAll(read.machine); restoredAi.putAll(read.ai);
        }
        assertEquals(machine,restoredMachine); assertEquals(ai,restoredAi);
        assertFalse(Files.exists(dir.resolve("share.json")));
    }
    @Test void exactSizeFitsOneFileAndOneByteLessSplits() throws Exception {
        var source=file(Map.of("a","中文字","b","quotes \"\\"),Map.of());
        Path original=dir.resolve("exact.json"); source.write(original);
        long bytes=Files.size(original);
        assertEquals(1,source.writeParts(dir.resolve("fits.json"),100,bytes).size());
        assertEquals(2,source.writeParts(dir.resolve("split.json"),100,bytes-1).size());
    }
    @Test void rowLimitSpansMachineAndAiAndCollisionLeavesNoPartialOutputs() throws Exception {
        var source=file(Map.of("a","甲","b","乙"),Map.of("c","丙"));
        var parts=source.writeParts(dir.resolve("rows.json"),2,1024);
        assertEquals(2,parts.size());
        assertEquals(2,TranslationFile.read(parts.get(0)).machine.size());
        Path collision=dir.resolve("blocked.part-0002.json");
        Files.writeString(collision,"keep me");
        assertThrows(java.io.IOException.class,()->source.writeParts(dir.resolve("blocked.json"),2,1024));
        assertFalse(Files.exists(dir.resolve("blocked.part-0001.json")));
        assertEquals("keep me",Files.readString(collision));
        try(var paths=Files.list(dir)) {assertFalse(paths.anyMatch(p->p.getFileName().toString().endsWith(".tmp")));}
    }
    @Test void emptyExportWorksAndOversizedRowCleansStaging() throws Exception {
        var empty=file(Map.of(),Map.of());
        assertEquals(List.of(dir.resolve("empty.json")),empty.writeParts(dir.resolve("empty.json")));
        Map<String,String> rows=new LinkedHashMap<>(); rows.put("a","small"); rows.put("b","x".repeat(300));
        assertThrows(java.io.IOException.class,()->file(rows,Map.of()).writeParts(dir.resolve("bad.json"),1,200));
        try(var paths=Files.list(dir)) {assertEquals(1,paths.count());}
    }
    @Test void batchDeduplicatesPathsKeepsExistingAndReportsPartialFailure() throws Exception {
        Path a=dir.resolve("a.json"), b=dir.resolve("b.json"), bad=dir.resolve("broken.json");
        file(Map.of("same","first","new","新增"),Map.of()).write(a);
        file(Map.of("same","second","other","其他"),Map.of()).write(b);
        Files.writeString(bad,"invalid");
        Map<String,String> cache=new HashMap<>(); cache.put("same","local");
        String result=TranslationFileDialog.importFiles(List.of(b,bad,a,a), f->{
            f.requireCompatible("modern-template-v1","zh-TW","google");
            int before=cache.size(); f.machine.forEach(cache::putIfAbsent); return cache.size()-before;
        });
        assertEquals(Map.of("same","local","new","新增","other","其他"),cache);
        assertTrue(result.contains("Imported 2 translations from 2/3 files"));
        assertTrue(result.contains("Failed files: 1"));
        assertTrue(result.contains("broken.json"));
    }
    @Test void productionLimitsSplitOver32MiBAndOver100000Rows() throws Exception {
        Map<String,String> rows=new LinkedHashMap<>();
        String text="中".repeat(12000);
        for(int i=0;i<1000;i++) rows.put("source-"+i,text);
        var byteParts=file(rows,Map.of()).writeParts(dir.resolve("large.json"));
        assertEquals(2,byteParts.size());
        int restored=0;
        for(Path part:byteParts) {
            assertTrue(Files.size(part)<=32L*1024*1024);
            restored+=TranslationFile.read(part).machine.size();
        }
        assertEquals(1000,restored);
        rows.clear();
        for(int i=0;i<100001;i++) rows.put("row-"+i,"譯文");
        var rowParts=file(rows,Map.of()).writeParts(dir.resolve("many.json"));
        assertEquals(2,rowParts.size());
        assertEquals(100000,TranslationFile.read(rowParts.get(0)).machine.size());
        assertEquals(1,TranslationFile.read(rowParts.get(1)).machine.size());
    }
}
