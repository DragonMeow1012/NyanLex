package com.dragonmeow.mctranslator.translate;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native file selection and file work stay off Minecraft's render/tick thread. */
public final class TranslationFileDialog {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    public interface Importer { int merge(TranslationFile file) throws Exception; }
    private TranslationFileDialog() { }

    public static void open(boolean importing, Supplier<TranslationFile> exporter,
                            Importer importer, Consumer<String> result) {
        if (!BUSY.compareAndSet(false, true)) { result.accept("A translation file operation is already running."); return; }
        Thread worker = new Thread(() -> {
            try {
                String selected = selectFile(importing);
                if (selected == null) return;
                Path path = Paths.get(selected);
                if (importing) {
                    int count = importer.merge(TranslationFile.read(path));
                    result.accept("Imported " + count + " translations (existing translations kept).");
                } else {
                    exporter.get().write(path);
                    result.accept("Translations exported: " + path.getFileName());
                }
            } catch (Exception error) {
                result.accept("Translation file: " + (error.getMessage() == null ? "operation failed" : error.getMessage()));
            } finally {
                BUSY.set(false);
            }
        }, "mctranslator-translation-files");
        worker.setDaemon(true);
        worker.start();
    }

    private static String selectFile(boolean importing) throws Exception {
        String title = importing ? "Import translations" : "Export translations";
        String defaultPath = importing ? "" : "translations-" + System.currentTimeMillis() + ".json";
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointers = Class.forName("org.lwjgl.PointerBuffer");
            if (importing) return (String) dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointers, CharSequence.class, boolean.class)
                    .invoke(null, title, defaultPath, null, "Translation JSON", false);
            return (String) dialogs.getMethod("tinyfd_saveFileDialog",
                    CharSequence.class, CharSequence.class, pointers, CharSequence.class)
                    .invoke(null, title, defaultPath, null, "Translation JSON");
        } catch (ClassNotFoundException absentOnLwjgl2) {
            // Minecraft sets java.awt.headless=true. A separate small JVM isolates AWT
            // from the game's graphics settings on the older LWJGL 2 clients.
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String classes = Paths.get(TranslationFileDialog.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            Process picker = new ProcessBuilder(java, "-Djava.awt.headless=false", "-cp", classes,
                    Picker.class.getName(), importing ? "import" : "export", defaultPath)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    picker.getInputStream(), StandardCharsets.UTF_8))) { output = reader.readLine(); }
            if (picker.waitFor() != 0) throw new java.io.IOException("Could not open file picker");
            return output == null || output.isEmpty() ? null
                    : new String(Base64.getDecoder().decode(output), StandardCharsets.UTF_8);
        }
    }

    /** Runs only in the isolated legacy file-picker JVM; requires no Minecraft classes. */
    public static final class Picker {
        public static void main(String[] args) throws Exception {
            java.awt.EventQueue.invokeAndWait(() -> {
                boolean importing = "import".equals(args[0]);
                FileDialog dialog = new FileDialog((Frame) null,
                        importing ? "Import translations" : "Export translations",
                        importing ? FileDialog.LOAD : FileDialog.SAVE);
                try {
                    dialog.setFile(importing ? "*.json" : args[1]);
                    dialog.setVisible(true);
                    if (dialog.getFile() != null) {
                        String path = Paths.get(dialog.getDirectory(), dialog.getFile()).toString();
                        System.out.println(Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)));
                    }
                } finally { dialog.dispose(); }
            });
        }
    }
}
