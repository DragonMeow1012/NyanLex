package com.dragonmeow.nyanlex.translate;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Local CLI IO boundaries. Contains no credentials or translation policy. Java 8 compatible. */
public final class CliProcessSupport {
    private CliProcessSupport() { }

    /** A real Git root prevents agent runtimes from discovering a parent repository. */
    public static void prepareWorkspace(Path workspace) throws IOException {
        requirePolicyFile(workspace.resolve(".git/HEAD"), "ref: refs/heads/translation\n");
        requirePolicyFile(workspace.resolve(".git/config"), "[core]\nrepositoryformatversion = 0\nbare = false\n");
        Files.createDirectories(workspace.resolve(".git/objects"));
        Files.createDirectories(workspace.resolve(".git/refs/heads"));
        requirePolicyFile(workspace.resolve(".nyanlex-translation"), "Text-only translation workspace.\n");
    }

    /** Forward only OS/session variables needed to run the official CLI and its login UI. */
    public static void restrictEnvironment(ProcessBuilder builder) {
        Map<String, String> original = new HashMap<>(builder.environment());
        builder.environment().clear();
        for (Map.Entry<String, String> entry : original.entrySet()) {
            String key = entry.getKey().toUpperCase(Locale.ROOT);
            if (key.equals("PATH") || key.equals("PATHEXT") || key.equals("SYSTEMROOT")
                    || key.equals("WINDIR") || key.equals("COMSPEC") || key.equals("TEMP")
                    || key.equals("TMP") || key.equals("TMPDIR") || key.equals("HOME")
                    || key.equals("USERPROFILE") || key.equals("HOMEDRIVE") || key.equals("HOMEPATH")
                    || key.equals("APPDATA") || key.equals("LOCALAPPDATA") || key.equals("LANG")
                    || key.startsWith("LC_") || key.equals("DISPLAY") || key.equals("WAYLAND_DISPLAY")
                    || key.equals("XDG_RUNTIME_DIR") || key.equals("DBUS_SESSION_BUS_ADDRESS")) {
                builder.environment().put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Runtime configuration lives in the mod's profile, not the player's normal agent profile. */
    public static void isolateHome(ProcessBuilder builder, Path profile) {
        String home = profile.toAbsolutePath().normalize().toString();
        builder.environment().put("HOME", home);
        builder.environment().put("USERPROFILE", home);
        builder.environment().put("XDG_CONFIG_HOME", profile.resolve(".config").toString());
        builder.environment().put("APPDATA", profile.resolve("AppData/Roaming").toString());
        builder.environment().put("LOCALAPPDATA", profile.resolve("AppData/Local").toString());
    }

    /** Create a mod-owned policy once; refuse edited or redirected policy files. */
    public static void requirePolicyFile(Path file, String expected) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        for (Path path = parent; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("CLI policy directory is a symbolic link");
        }
        Files.createDirectories(parent);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Files.write(file, expected.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65_536
                || !new String(Files.readAllBytes(file), StandardCharsets.UTF_8).equals(expected)) {
            throw new IOException("CLI translation policy was changed; restore the mod-owned policy: " + file);
        }
    }

    public static String readBoundedLine(BufferedReader reader, int maximum) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(4096, maximum));
        for (;;) {
            int value = reader.read();
            if (value < 0) return line.length() == 0 ? null : line.toString();
            if (value == '\n') return line.toString();
            if (value == '\r') {
                reader.mark(1);
                int next = reader.read();
                if (next >= 0 && next != '\n') reader.reset();
                return line.toString();
            }
            if (line.length() >= maximum) throw new IOException("CLI output line exceeded the safety limit");
            line.append((char) value);
        }
    }

    /** Java 9+ stops descendants too; reflection keeps the legacy Java 8 ports loadable. */
    public static void destroyTree(Process process) {
        if (process == null) return;
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object handle = Process.class.getMethod("toHandle").invoke(process);
            try (Stream<?> children = (Stream<?>) handleType.getMethod("descendants").invoke(handle)) {
                children.forEach(child -> {
                    try { handleType.getMethod("destroyForcibly").invoke(child); }
                    catch (ReflectiveOperationException | RuntimeException ignored) { }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Java 8 has no ProcessHandle. Its OS-level child cleanup remains best effort.
        } finally {
            process.destroyForcibly();
            // Termination is asynchronous on Windows; reap before releasing its workspace.
            try { process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
