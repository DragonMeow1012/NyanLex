package com.dragonmeow.nyanlex.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Opens the system file manager on a file (selecting it) or, when it does not exist yet,
 * on its folder. The command is always an argument array handed to {@link ProcessBuilder}
 * — never a shell string — so a path cannot inject extra commands.
 */
public final class FileOpener {
    private FileOpener() {}

    /**
     * The command for {@code osName} (the {@code os.name} property). A file that exists is
     * revealed with selection (Windows {@code explorer.exe /select,<path>}, macOS
     * {@code open -R}); an existing folder is opened itself; a missing file opens its
     * parent folder; Linux has no "select" so it always opens a folder.
     */
    public static List<String> command(String osName, Path path, boolean isFile, boolean isDirectory) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Path folder = isDirectory ? path : path.toAbsolutePath().getParent();
        String target = path.toAbsolutePath().toString();
        String folderText = folder == null ? target : folder.toString();
        if (os.contains("win")) {
            if (isFile) return List.of("explorer.exe", "/select," + target);
            return List.of("explorer.exe", folderText);
        }
        if (os.contains("mac")) {
            if (isFile) return List.of("open", "-R", target);
            return List.of("open", folderText);
        }
        return List.of("xdg-open", folderText);
    }

    /** Starts the file manager; {@code false} when it could not be started (the caller
     *  then falls back to the game's own folder opener). Windows explorer exits with a
     *  non-zero code even on success, so only a failure to start counts as failure. */
    public static boolean reveal(Path path) {
        try {
            boolean isFile = Files.isRegularFile(path);
            boolean isDir = Files.isDirectory(path);
            Path folder = isDir ? path : path.toAbsolutePath().getParent();
            if (folder == null || !Files.isDirectory(folder)) return false;
            new ProcessBuilder(command(System.getProperty("os.name"), path, isFile, isDir))
                    .redirectErrorStream(true).start();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
