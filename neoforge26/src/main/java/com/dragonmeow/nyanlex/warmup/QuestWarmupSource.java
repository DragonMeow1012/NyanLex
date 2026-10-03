package com.dragonmeow.nyanlex.warmup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Optional FTB client-data integration. No files, server calls or UI widgets are opened.
 * Text conversion is provided by the loader's existing screen-text request planner. */
public final class QuestWarmupSource implements ItemWarmupSource {
    private final Supplier<Object> loadedFile;
    private final Function<Object, List<String>> requestLines;
    private Object file;
    private List<Object> objects = List.of();
    private int cursor;

    public QuestWarmupSource(Supplier<Object> loadedFile, Function<Object, List<String>> requestLines) {
        this.loadedFile = loadedFile;
        this.requestLines = requestLines;
    }

    public static Object loadedClientFile() {
        try {
            Class<?> type = Class.forName("dev.ftb.mods.ftbquests.client.ClientQuestFile");
            return type.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null; // the optional integration is absent
        }
    }

    public void reset() {
        file = loadedFile.get();
        List<Object> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> pending = new ArrayDeque<>();
        if (file != null) pending.add(file);
        while (!pending.isEmpty()) {
            Object object = pending.removeFirst();
            if (!seen.add(object)) continue;
            result.add(object);
            Object children = invoke(object, "getChildren");
            if (children instanceof Iterable<?> values) {
                for (Object child : values) if (child != null) pending.addLast(child);
            }
        }
        objects = List.copyOf(result);
        cursor = 0;
    }

    public int totalItemCount() { return objects.size(); }
    public boolean isExhausted() { return cursor >= objects.size(); }
    public boolean isAvailable() { return file == null || file == loadedFile.get(); }

    public List<ItemWarmupTarget> probeNext(int maxItems) {
        List<ItemWarmupTarget> out = new ArrayList<>();
        if (!isAvailable()) return out;
        int end = Math.min(objects.size(), cursor + Math.max(1, maxItems));
        while (cursor < end) {
            Object object = objects.get(cursor++);
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            try {
                add(sources, invoke(object, "getTitle"));
                add(sources, invoke(object, "getSubtitle"));
                add(sources, invoke(object, "getDescription"));
                out.add(new ItemWarmupTarget("quest:" + cursor, "ftbquests", List.copyOf(sources),
                        false, WarmupCategory.SCREEN));
            } catch (RuntimeException ignored) {
                out.add(new ItemWarmupTarget("quest:" + cursor, "ftbquests", List.of(),
                        true, WarmupCategory.SCREEN));
            }
        }
        return out;
    }

    private void add(Set<String> out, Object value) {
        if (value == null) return;
        if (value instanceof Iterable<?> values) {
            for (Object part : values) add(out, part);
        } else {
            for (String line : requestLines.apply(value)) {
                if (line != null && !line.isBlank()) out.add(line);
            }
        }
    }

    private static Object invoke(Object object, String name) {
        try {
            return object.getClass().getMethod(name).invoke(object);
        } catch (NoSuchMethodException ignored) {
            return null; // not every kind of quest object has subtitles or descriptions
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read loaded quest text", e);
        }
    }
}
