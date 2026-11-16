package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.translate.TypedFrameCollapser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 偵錯模式 raw component capture. While 偵錯模式 is on it records, untranslated and with the full
 * style tree (font, colour, shadow, extra children ...), the text components that reach the
 * overlay (action bar) and the text of nearby entities, as JSON Lines under the config folder:
 * <ul>
 *   <li>{@code nyanlex-overlay-dump.jsonl}: every overlay component, one per change (a line equal to
 *       the previous one is skipped); at most {@value #OVERLAY_MAX_LINES} lines per launch.</li>
 *   <li>{@code nyanlex-entity-dump.jsonl}: every 40 ticks, text displays and custom names of
 *       entities within {@value #SCAN_RADIUS} blocks; one line per entity and distinct text; at most
 *       {@value #ENTITY_MAX_LINES} lines per launch.</li>
 * </ul>
 * Switched off, every entry point returns after one cheap check: no thread, no file, no allocation.
 * The game thread only collects references to immutable components; encoding, de-duplication and
 * file I/O run on one daemon thread. All failures are absorbed through {@link HookGuard}.
 */
public final class ComponentCapture {
    static final String OVERLAY_FILE = NyanLexFabric26.MOD_ID + "-overlay-dump.jsonl";
    static final String ENTITY_FILE = NyanLexFabric26.MOD_ID + "-entity-dump.jsonl";
    static final String TOOLTIP_FILE = NyanLexFabric26.MOD_ID + "-tooltip-dump.jsonl";
    static final int TOOLTIP_MAX_LINES = 600;
    /** A typed-out dialogue frame with no newer frame for this long is written as it is. */
    static final long HELD_OVERLAY_IDLE_MS = 800L;
    static final int OVERLAY_MAX_LINES = 6000;
    static final int ENTITY_MAX_LINES = 800;
    static final int SCAN_INTERVAL_TICKS = 40;
    static final int SCAN_RADIUS = 24;
    /** Entities examined per scan, a guard against a crowded area. */
    private static final int MAX_ENTITIES_PER_SCAN = 256;
    /** Queued jobs beyond this are dropped instead of piling up behind a stalled disk. */
    private static final int MAX_PENDING = 512;
    private static final int MAX_TRACKED = 4096;

    private static final String HOOK_OVERLAY = "ComponentCapture.overlay";
    private static final String HOOK_ENTITY = "ComponentCapture.entities";
    private static final String HOOK_TOOLTIP = "ComponentCapture.tooltip";

    private static volatile BooleanSupplier enabled = () -> false;
    private static volatile JsonlCaptureSink overlaySink;
    private static volatile JsonlCaptureSink entitySink;
    private static volatile JsonlCaptureSink tooltipSink;

    private static final AtomicInteger pending = new AtomicInteger();
    private static ExecutorService writer; // created on the first queued job
    /** Depth of the mod's own overlay writes (client thread only). */
    private static int ownOverlayDepth;
    private static int tickCounter;

    // Writer-thread state (only touched from the writer thread).
    private static String lastOverlayJson;
    private static final Map<String, Component> LAST_ENTITY_TEXT = new HashMap<>();
    private static final Set<String> SEEN_ENTITY_JSON = new HashSet<>();

    private ComponentCapture() {
    }

    /** Wires the two files (no I/O yet) and the 偵錯模式 switch. Called once at startup. */
    static void install(Path configDir, BooleanSupplier debugMode) {
        enabled = debugMode == null ? () -> false : debugMode;
        overlaySink = new JsonlCaptureSink(configDir.resolve(OVERLAY_FILE), OVERLAY_MAX_LINES,
                "overlay", NyanLexFabric26.LOGGER::info, NyanLexFabric26.LOGGER::warn);
        entitySink = new JsonlCaptureSink(configDir.resolve(ENTITY_FILE), ENTITY_MAX_LINES,
                "entity text", NyanLexFabric26.LOGGER::info, NyanLexFabric26.LOGGER::warn);
        tooltipSink = new JsonlCaptureSink(configDir.resolve(TOOLTIP_FILE), TOOLTIP_MAX_LINES,
                "tooltip and title", NyanLexFabric26.LOGGER::info, NyanLexFabric26.LOGGER::warn);
    }

    private static boolean active() {
        return overlaySink != null && enabled.getAsBoolean();
    }

    // ------------------------------------------------------------------ overlay

    /** The mod's own translated overlay write is about to run: the Gui hook must not record it. */
    static void beginOwnOverlay() {
        ownOverlayDepth++;
    }

    static void endOwnOverlay() {
        if (ownOverlayDepth > 0) ownOverlayDepth--;
    }

    /** Overlay component as the network delivered it, before any translation (client thread). */
    static void captureOverlay(Component message) {
        if (message == null || !active()) return;
        JsonlCaptureSink sink = overlaySink;
        if (sink == null || sink.isClosed()) return;
        if (!HookGuard.enter(HOOK_OVERLAY)) return;
        try {
            long time = System.currentTimeMillis();
            DynamicOps<JsonElement> ops = ops(Minecraft.getInstance());
            submit(HOOK_OVERLAY, () -> writeOverlay(sink, time, message, ops));
        } catch (Throwable error) {
            HookGuard.fail(HOOK_OVERLAY, error);
        }
    }

    /**
     * Every component handed to the HUD overlay, including the ones that bypass the chat event
     * (the dedicated action bar packet). The mod's own writes and notices are skipped; the same
     * component already recorded by {@link #captureOverlay} is dropped as an adjacent duplicate.
     */
    public static void captureOverlayFromGui(Component message) {
        try {
            if (message == null || ownOverlayDepth > 0 || !active()) return;
            if (NyanLexFabric26.isOwnFeedback(message)) return;
            captureOverlay(message);
        } catch (Throwable error) {
            HookGuard.fail(HOOK_OVERLAY, error);
        }
    }

    /** One overlay frame ready to be written (writer thread). */
    private record OverlayFrame(JsonlCaptureSink sink, long time, String visible, JsonElement json) {
    }

    /** A typed-out dialogue is one frame, the last: see {@link TypedFrameCollapser}. */
    private static final TypedFrameCollapser<OverlayFrame> COLLAPSER = new TypedFrameCollapser<>();

    private static void writeOverlay(JsonlCaptureSink sink, long time, Component message,
                                     DynamicOps<JsonElement> ops) {
        JsonElement json = encode(message, ops);
        String canonical = JsonlCaptureSink.GSON.toJson(json);
        if (canonical.equals(lastOverlayJson)) return;
        lastOverlayJson = canonical;
        OverlayFrame frame = new OverlayFrame(sink, time, message.getString(), json);
        writeFrames(COLLAPSER.offer(frame, dialogueText(message), System.currentTimeMillis()));
    }

    private static void flushIdleOverlay(long now) {
        writeFrames(COLLAPSER.idle(now, HELD_OVERLAY_IDLE_MS));
    }

    private static void writeFrames(List<OverlayFrame> frames) {
        for (OverlayFrame frame : frames) {
            if (!frame.sink().isClosed()) {
                frame.sink().append(JsonlCaptureSink.overlayLine(frame.time(), frame.visible(), frame.json()));
            }
        }
    }

    /**
     * The words of a dialogue box (every dialogue text font, without icons and layout moves), or
     * {@code null} when the component is not a dialogue box. Typing makes this string grow.
     */
    private static String dialogueText(Component message) {
        boolean[] dialogue = {false};
        StringBuilder words = new StringBuilder();
        message.visit((style, text) -> {
            net.minecraft.network.chat.FontDescription font = style.getFont();
            String id = font instanceof net.minecraft.network.chat.FontDescription.Resource resource
                    ? resource.id().toString() : "minecraft:default";
            if (!com.dragonmeow.nyanlex.translate.OverlayDialogueFonts.isDialogue(id)) return Optional.empty();
            dialogue[0] = true;
            if (com.dragonmeow.nyanlex.translate.OverlayDialogueFonts.kind(id)
                    == com.dragonmeow.nyanlex.translate.OverlayDialogueFonts.Kind.OTHER) return Optional.empty();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (cp >= 0x20 && !com.dragonmeow.nyanlex.translate.DecorStyleMatcher.isDecor(cp)) words.appendCodePoint(cp);
            }
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return dialogue[0] ? words.toString() : null;
    }

    // ------------------------------------------------------------------ entities

    private record EntityText(String kind, String entity, String uuid, double x, double y, double z,
                              Component text) {
    }

    /** Client tick: every {@value #SCAN_INTERVAL_TICKS} ticks look at nearby entities. */
    static void tick(Minecraft mc) {
        if (!active()) {
            tickCounter = 0;
            return;
        }
        JsonlCaptureSink sink = entitySink;
        if (sink == null || sink.isClosed()) return;
        if (++tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;
        JsonlCaptureSink overlay = overlaySink;
        if (overlay != null && !overlay.isClosed()) {
            submit(HOOK_OVERLAY, () -> flushIdleOverlay(System.currentTimeMillis()));
        }
        if (mc == null || mc.level == null || mc.player == null) return;
        if (!HookGuard.enter(HOOK_ENTITY)) return;
        try {
            List<EntityText> batch = scan(mc);
            if (batch.isEmpty()) return;
            long time = System.currentTimeMillis();
            DynamicOps<JsonElement> ops = ops(mc);
            submit(HOOK_ENTITY, () -> writeEntities(sink, time, batch, ops));
        } catch (Throwable error) {
            HookGuard.fail(HOOK_ENTITY, error);
        }
    }

    private static List<EntityText> scan(Minecraft mc) {
        List<EntityText> found = new ArrayList<>();
        double limit = (double) SCAN_RADIUS * SCAN_RADIUS;
        int examined = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity.distanceToSqr(mc.player) > limit) continue;
            if (++examined > MAX_ENTITIES_PER_SCAN) break;
            if (entity instanceof Display.TextDisplay display) {
                Display.TextDisplay.TextRenderState state = display.textRenderState();
                if (state != null && state.text() != null) add(found, "text_display", entity, state.text());
            }
            Component custom = entity.getCustomName();
            if (custom != null) add(found, "custom_name", entity, custom);
        }
        return found;
    }

    private static void add(List<EntityText> out, String kind, Entity entity, Component text) {
        out.add(new EntityText(kind, String.valueOf(EntityType.getKey(entity.getType())),
                entity.getUUID().toString(), entity.getX(), entity.getY(), entity.getZ(), text));
    }

    private static void writeEntities(JsonlCaptureSink sink, long time, List<EntityText> batch,
                                      DynamicOps<JsonElement> ops) {
        for (EntityText item : batch) {
            if (sink.isClosed()) return;
            String trackKey = item.uuid() + "|" + item.kind();
            Component before = LAST_ENTITY_TEXT.get(trackKey);
            if (before != null && before.equals(item.text())) continue; // unchanged since the last scan
            if (LAST_ENTITY_TEXT.size() >= MAX_TRACKED) LAST_ENTITY_TEXT.clear();
            LAST_ENTITY_TEXT.put(trackKey, item.text());
            JsonElement json = encode(item.text(), ops);
            String canonical = JsonlCaptureSink.GSON.toJson(json);
            if (SEEN_ENTITY_JSON.size() >= MAX_TRACKED * 4) SEEN_ENTITY_JSON.clear();
            if (!SEEN_ENTITY_JSON.add(item.uuid() + '\u0000' + canonical)) continue; // same entity, same text
            sink.append(JsonlCaptureSink.entityLine(time, item.kind(), item.entity(), item.uuid(),
                    item.x(), item.y(), item.z(), item.text().getString(), json));
        }
    }

    // ------------------------------------------------------------------ tooltip, held name, titles

    private static int lastTooltipHash;
    private static int lastHeldHash;
    private static int lastTitleHash;
    private static int lastSubtitleHash;
    /** Writer thread: contents already written, and the id of the next tooltip. */
    private static final Set<String> SEEN_TOOLTIP = new HashSet<>();
    private static long tooltipGroup;

    /** The raw lines of the tooltip being hovered, before any translation (client thread). */
    public static void captureTooltip(List<Component> lines) {
        if (lines == null || lines.isEmpty() || !active()) return;
        JsonlCaptureSink sink = tooltipSink;
        if (sink == null || sink.isClosed() || !HookGuard.enter(HOOK_TOOLTIP)) return;
        try {
            int hash = lines.hashCode();
            if (hash == lastTooltipHash) return;
            lastTooltipHash = hash;
            List<Component> copy = new ArrayList<>(lines);
            long time = System.currentTimeMillis();
            DynamicOps<JsonElement> ops = ops(Minecraft.getInstance());
            submit(HOOK_TOOLTIP, () -> writeTooltip(sink, time, copy, ops));
        } catch (Throwable error) {
            HookGuard.fail(HOOK_TOOLTIP, error);
        }
    }

    /** The held item's name above the hotbar, before any translation (client thread). */
    public static void captureHeldName(Component name) {
        captureSingle("held_name", name);
    }

    /** A title or subtitle as drawn, before any translation (client thread). */
    public static void captureTitle(Component text, boolean subtitle) {
        captureSingle(subtitle ? "subtitle" : "title", text);
    }

    private static void captureSingle(String kind, Component text) {
        if (text == null || !active()) return;
        JsonlCaptureSink sink = tooltipSink;
        if (sink == null || sink.isClosed() || !HookGuard.enter(HOOK_TOOLTIP)) return;
        try {
            int hash = text.hashCode();
            switch (kind) {
                case "held_name" -> {
                    if (hash == lastHeldHash) return;
                    lastHeldHash = hash;
                }
                case "subtitle" -> {
                    if (hash == lastSubtitleHash) return;
                    lastSubtitleHash = hash;
                }
                default -> {
                    if (hash == lastTitleHash) return;
                    lastTitleHash = hash;
                }
            }
            long time = System.currentTimeMillis();
            DynamicOps<JsonElement> ops = ops(Minecraft.getInstance());
            submit(HOOK_TOOLTIP, () -> writeSingle(sink, time, kind, text, ops));
        } catch (Throwable error) {
            HookGuard.fail(HOOK_TOOLTIP, error);
        }
    }

    private static void writeTooltip(JsonlCaptureSink sink, long time, List<Component> lines,
                                     DynamicOps<JsonElement> ops) {
        List<JsonElement> encoded = new ArrayList<>(lines.size());
        StringBuilder canonical = new StringBuilder("tooltip");
        for (Component line : lines) {
            JsonElement json = line == null ? null : encode(line, ops);
            encoded.add(json);
            canonical.append('\n').append(json == null ? "null" : JsonlCaptureSink.GSON.toJson(json));
        }
        if (SEEN_TOOLTIP.size() >= MAX_TRACKED * 4) SEEN_TOOLTIP.clear();
        if (!SEEN_TOOLTIP.add(canonical.toString())) return; // the same tooltip again
        long group = ++tooltipGroup;
        for (int i = 0; i < lines.size(); i++) {
            if (sink.isClosed()) return;
            if (encoded.get(i) == null) continue;
            sink.append(JsonlCaptureSink.tooltipLine(time, "tooltip_line", group, i,
                    lines.get(i).getString(), encoded.get(i)));
        }
    }

    private static void writeSingle(JsonlCaptureSink sink, long time, String kind, Component text,
                                    DynamicOps<JsonElement> ops) {
        JsonElement json = encode(text, ops);
        if (SEEN_TOOLTIP.size() >= MAX_TRACKED * 4) SEEN_TOOLTIP.clear();
        if (!SEEN_TOOLTIP.add(kind + '\n' + JsonlCaptureSink.GSON.toJson(json))) return;
        sink.append(JsonlCaptureSink.tooltipLine(time, kind, -1, -1, text.getString(), json));
    }

    // ------------------------------------------------------------------ shared

    private static DynamicOps<JsonElement> ops(Minecraft mc) {
        ClientLevel level = mc == null ? null : mc.level;
        return level == null ? JsonOps.INSTANCE : RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
    }

    /** The vanilla component codec's JSON form; a failed encode becomes a marker object, never an exception. */
    private static JsonElement encode(Component component, DynamicOps<JsonElement> ops) {
        DataResult<JsonElement> result = ComponentSerialization.CODEC.encodeStart(ops, component);
        String[] problem = new String[1];
        Optional<JsonElement> value = result.resultOrPartial(message -> problem[0] = message);
        if (value.isPresent() && problem[0] == null) return value.get();
        JsonObject marker = new JsonObject();
        marker.addProperty("_encodeError", problem[0] == null ? "unknown" : problem[0]);
        value.ifPresent(partial -> marker.add("_partial", partial));
        return marker;
    }

    private static void submit(String hookId, Runnable job) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            return;
        }
        executor().execute(() -> {
            try {
                job.run();
            } catch (Throwable error) {
                HookGuard.fail(hookId, error);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private static synchronized ExecutorService executor() {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "nyanlex-capture-writer");
                thread.setDaemon(true);
                return thread;
            });
        }
        return writer;
    }
}
