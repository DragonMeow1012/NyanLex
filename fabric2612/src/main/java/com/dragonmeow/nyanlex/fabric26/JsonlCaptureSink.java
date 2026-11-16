package com.dragonmeow.nyanlex.fabric26;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * A JSON Lines file with a per-launch line cap, written by one background thread only (no locking
 * inside; only {@link #isClosed()} may be read from other threads). Nothing touches the disk until
 * the first {@link #append}: that call renames an older file to {@code <name>.prev} (replacing a
 * previous {@code .prev}) and starts a fresh file. Every line is flushed on write so a crash loses
 * nothing. Reaching the cap or any I/O failure closes the sink for the rest of the launch and logs
 * exactly one line; failures never propagate.
 *
 * <p>Also holds the pure line-building helpers (no Minecraft types) so they can be exercised
 * without the game.</p>
 */
final class JsonlCaptureSink {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final Path previous;
    private final int maxLines;
    private final String label;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private volatile boolean closed;
    private int lines;
    private BufferedWriter out;

    JsonlCaptureSink(Path file, int maxLines, String label, Consumer<String> info, Consumer<String> warn) {
        this.file = file;
        this.previous = file.resolveSibling(file.getFileName() + ".prev");
        this.maxLines = Math.max(1, maxLines);
        this.label = label;
        this.info = info;
        this.warn = warn;
    }

    /** True once the cap was reached or a write failed; safe to read from any thread. */
    boolean isClosed() {
        return closed;
    }

    /** Lines written so far this launch (writer thread). */
    int lineCount() {
        return lines;
    }

    Path file() {
        return file;
    }

    /** Appends one line (without its terminator). Writer thread only. */
    void append(String line) {
        if (closed) return;
        try {
            if (out == null) open();
            out.write(line);
            out.write('\n');
            out.flush();
            lines++;
            if (lines >= maxLines) {
                shut();
                info.accept("[NyanLex] " + label + " capture reached " + maxLines
                        + " lines for this launch; nothing more is recorded until the next game start ("
                        + file.getFileName() + ")");
            }
        } catch (Throwable e) {
            shut();
            warn.accept("[NyanLex] " + label + " capture stopped after a write failure: " + e);
        }
    }

    private void open() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        if (Files.exists(file)) Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        info.accept("[NyanLex] " + label + " capture is writing to " + file.toAbsolutePath()
                + " (at most " + maxLines + " lines this launch; the older file is kept as "
                + previous.getFileName() + ")");
    }

    private void shut() {
        closed = true;
        BufferedWriter w = out;
        out = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // nothing left to do
            }
        }
    }

    // ------------------------------------------------------------------ line building

    /** Overlay line: {@code {"t","kind","visible","json"}}. */
    static String overlayLine(long time, String visible, JsonElement json) {
        JsonObject o = new JsonObject();
        o.addProperty("t", time);
        o.addProperty("kind", "overlay");
        o.addProperty("visible", visible);
        o.add("json", json);
        return escapeNonText(GSON.toJson(o));
    }

    /** Entity line: {@code {"t","kind","entity","uuid","pos","visible","json"}}. */
    static String entityLine(long time, String kind, String entity, String uuid,
                             double x, double y, double z, String visible, JsonElement json) {
        JsonObject o = new JsonObject();
        o.addProperty("t", time);
        o.addProperty("kind", kind);
        o.addProperty("entity", entity);
        o.addProperty("uuid", uuid);
        JsonArray pos = new JsonArray();
        pos.add(round2(x));
        pos.add(round2(y));
        pos.add(round2(z));
        o.add("pos", pos);
        o.addProperty("visible", visible);
        o.add("json", json);
        return escapeNonText(GSON.toJson(o));
    }

    /**
     * Tooltip / held-name / title line: {@code {"t","kind","group","line","visible","json"}};
     * {@code group} and {@code line} (the row of a tooltip, from 0) are left out when negative.
     */
    static String tooltipLine(long time, String kind, long group, int line, String visible, JsonElement json) {
        JsonObject o = new JsonObject();
        o.addProperty("t", time);
        o.addProperty("kind", kind);
        if (group >= 0) o.addProperty("group", group);
        if (line >= 0) o.addProperty("line", line);
        o.addProperty("visible", visible);
        o.add("json", json);
        return escapeNonText(GSON.toJson(o));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Rewrites every UTF-16 surrogate (U+D800-U+DFFF, i.e. both halves of any code point above
     * U+FFFF such as the supplementary private-use planes) and every BMP private-use character
     * (U+E000-U+F8FF) as a JSON {@code \}{@code uXXXX} escape. Those characters can only occur
     * inside JSON strings, where the escape is valid and decodes back to the same character, so the
     * line stays valid JSON while being readable and safe in any editor.
     */
    static String escapeNonText(String json) {
        StringBuilder sb = null;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            boolean special = (c >= '\uD800' && c <= '\uDFFF') || (c >= '' && c <= '');
            if (special) {
                if (sb == null) {
                    sb = new StringBuilder(json.length() + 16);
                    sb.append(json, 0, i);
                }
                sb.append("\\u");
                String hex = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
                for (int pad = hex.length(); pad < 4; pad++) sb.append('0');
                sb.append(hex);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? json : sb.toString();
    }
}
