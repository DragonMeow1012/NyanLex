package com.dragonmeow.nyanlex.translate;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native file selection and file work stay off Minecraft's render/tick thread. */
public final class TranslationFileDialog {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    public interface Importer { int merge(TranslationFile file) throws Exception; }
    private TranslationFileDialog() { }

    /**
     * What an export or import ended with, as data, so each loader can word it in the player's language.
     * (A plain class, not a record: this file is shared with the Java 8 loader trees.)
     */
    public static final class Outcome {
        public enum Kind { BUSY, EXPORTED, IMPORTED, FAILED }

        private final Kind kind;
        private final int count;
        private final int files;
        private final int totalFiles;
        private final int failedFiles;
        private final String first;
        private final String last;

        public Outcome(Kind kind, int count, int files, int totalFiles, int failedFiles, String first, String last) {
            this.kind = kind;
            this.count = count;
            this.files = files;
            this.totalFiles = totalFiles;
            this.failedFiles = failedFiles;
            this.first = first;
            this.last = last;
        }

        public Kind kind() { return kind; }
        public int count() { return count; }
        public int files() { return files; }
        public int totalFiles() { return totalFiles; }
        public int failedFiles() { return failedFiles; }
        public String first() { return first; }
        public String last() { return last; }

        /** The English sentence (older loader glue shows this one as it is). */
        public String english() {
            if (kind == Kind.BUSY) return "A translation file operation is already running.";
            if (kind == Kind.EXPORTED) {
                return "Exported " + files + " translation file(s): " + first + (files > 1 ? " ... " + last : "");
            }
            if (kind == Kind.IMPORTED) {
                return "Imported " + count + " translations from " + files + "/" + totalFiles
                        + " files (existing translations kept)."
                        + (failedFiles == 0 ? "" : " Failed files: " + failedFiles
                        + ". Successful files remain imported. " + first);
            }
            return "Translation file: " + (first == null || first.isEmpty() ? "operation failed" : first);
        }
    }

    public static void open(boolean importing, Supplier<TranslationFile> exporter,
                            Importer importer, Consumer<String> result) {
        openOutcome(importing, exporter, importer, outcome -> result.accept(outcome.english()));
    }

    /** Same as {@link #open} but reports an {@link Outcome} instead of an English sentence. */
    public static void openOutcome(boolean importing, Supplier<TranslationFile> exporter,
                                   Importer importer, Consumer<Outcome> result) {
        if (!BUSY.compareAndSet(false, true)) {
            result.accept(new Outcome(Outcome.Kind.BUSY, 0, 0, 0, 0, null, null));
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                List<Path> selected = selectFiles(importing);
                if (selected.isEmpty()) return;
                if (importing) {
                    result.accept(importOutcome(selected, importer));
                } else {
                    List<Path> parts = exporter.get().writeParts(selected.get(0));
                    result.accept(new Outcome(Outcome.Kind.EXPORTED, 0, parts.size(), parts.size(), 0,
                            parts.get(0).getFileName().toString(),
                            parts.get(parts.size() - 1).getFileName().toString()));
                }
            } catch (Exception error) {
                result.accept(new Outcome(Outcome.Kind.FAILED, 0, 0, 0, 0,
                        error.getMessage() == null ? "" : error.getMessage(), null));
            } finally {
                BUSY.set(false);
            }
        }, "nyanlex-translation-files");
        worker.setDaemon(true);
        worker.start();
    }

    static String importFiles(List<Path> selected, Importer importer) {
        return importOutcome(selected, importer).english();
    }

    static Outcome importOutcome(List<Path> selected, Importer importer) {
        Set<Path> paths = new TreeSet<>();
        for (Path path : selected) paths.add(path.toAbsolutePath().normalize());
        long count = 0;
        int imported = 0, failed = 0;
        String firstFailure = "";
        for (Path path : paths) {
            try {
                count += importer.merge(TranslationFile.read(path));
                imported++;
            } catch (Exception error) {
                failed++;
                if (firstFailure.isEmpty()) firstFailure = path.getFileName() + ": "
                        + (error.getMessage() == null ? "operation failed" : error.getMessage());
            }
        }
        return new Outcome(Outcome.Kind.IMPORTED, (int) count, imported, paths.size(), failed, firstFailure, null);
    }

    private static List<Path> selectFiles(boolean importing) throws Exception {
        String title = importing ? "Import translations (select one or more JSON files)" : "Export translations (automatic parts)";
        String defaultPath = importing ? "" : "translations-" + System.currentTimeMillis() + ".json";
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointers = Class.forName("org.lwjgl.PointerBuffer");
            String selected;
            if (importing) selected = (String) dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointers, CharSequence.class, boolean.class)
                    .invoke(null, title, defaultPath, null, "Translation JSON", true);
            else selected = (String) dialogs.getMethod("tinyfd_saveFileDialog",
                    CharSequence.class, CharSequence.class, pointers, CharSequence.class)
                    .invoke(null, title, defaultPath, null, "Translation JSON");
            List<Path> paths = new ArrayList<>();
            if (selected != null && !selected.isEmpty()) {
                for (String value : importing ? selected.split("\\|") : new String[]{selected}) paths.add(Paths.get(value));
            }
            return paths;
        } catch (ClassNotFoundException absentOnLwjgl2) {
            // Minecraft sets java.awt.headless=true. A separate small JVM isolates AWT
            // from the game's graphics settings on the older LWJGL 2 clients.
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String classes = Paths.get(TranslationFileDialog.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            Process picker = new ProcessBuilder(java, "-Djava.awt.headless=false", "-cp", classes,
                    Picker.class.getName(), importing ? "import" : "export", defaultPath)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            List<Path> paths = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    picker.getInputStream(), StandardCharsets.UTF_8))) {
                String output;
                while ((output = reader.readLine()) != null) {
                    if (!output.isEmpty()) paths.add(Paths.get(new String(Base64.getDecoder().decode(output), StandardCharsets.UTF_8)));
                }
            }
            if (picker.waitFor() != 0) throw new java.io.IOException("Could not open file picker");
            return paths;
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
                    dialog.setMultipleMode(importing);
                    dialog.setVisible(true);
                    for (java.io.File file : dialog.getFiles()) {
                        String path = file.getAbsolutePath();
                        System.out.println(Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)));
                    }
                } finally { dialog.dispose(); }
            });
        }
    }
}
