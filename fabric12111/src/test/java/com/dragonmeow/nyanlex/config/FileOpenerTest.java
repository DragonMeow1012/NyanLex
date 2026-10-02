package com.dragonmeow.nyanlex.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileOpenerTest {
    private static final Path FILE = Path.of("/home/me/my config; rm -rf x/nyanlex.json");

    @Test
    void windowsSelectsAnExistingFileWithExplorer() {
        List<String> cmd = FileOpener.command("Windows 11", FILE, true, false);
        assertEquals("explorer.exe", cmd.get(0));
        assertEquals(2, cmd.size());
        assertTrue(cmd.get(1).startsWith("/select,"));
        assertTrue(cmd.get(1).endsWith("nyanlex.json"));
    }

    @Test
    void windowsOpensTheParentFolderWhenTheFileIsMissingAndTheFolderItselfForADirectory() {
        List<String> missing = FileOpener.command("Windows 11", FILE, false, false);
        assertEquals("explorer.exe", missing.get(0));
        assertFalse(missing.get(1).startsWith("/select,"));
        assertFalse(missing.get(1).endsWith("nyanlex.json"));
        List<String> dir = FileOpener.command("Windows 11", FILE, false, true);
        assertTrue(dir.get(1).endsWith("nyanlex.json"));
    }

    @Test
    void macRevealsWithOpenDashR() {
        assertEquals(List.of("open", "-R"), FileOpener.command("Mac OS X", FILE, true, false).subList(0, 2));
        assertEquals("open", FileOpener.command("Mac OS X", FILE, false, false).get(0));
    }

    @Test
    void linuxOpensTheFolderWithXdgOpen() {
        List<String> cmd = FileOpener.command("Linux", FILE, true, false);
        assertEquals("xdg-open", cmd.get(0));
        assertFalse(cmd.get(1).endsWith("nyanlex.json"));
    }

    @Test
    void aPathWithShellMetacharactersStaysOneArgument() {
        for (String os : new String[] {"Windows 11", "Mac OS X", "Linux"}) {
            List<String> cmd = FileOpener.command(os, FILE, true, false);
            long withPath = cmd.stream().filter(a -> a.contains("rm -rf x")).count();
            assertTrue(withPath <= 1, "the path must never be split into several arguments");
        }
    }
}
