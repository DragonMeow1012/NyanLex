package com.dragonmeow.mctranslator.neoforge;

import com.dragonmeow.mctranslator.cache.FileStore;
import com.dragonmeow.mctranslator.cache.PersistentStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;
import com.dragonmeow.mctranslator.style.ColorProfile;
import com.dragonmeow.mctranslator.translate.AiSettings;
import com.dragonmeow.mctranslator.translate.DispatchingTranslator;
import com.dragonmeow.mctranslator.translate.GoogleFreeTranslator;
import com.dragonmeow.mctranslator.translate.OpenAiTranslator;
import com.dragonmeow.mctranslator.translate.Translator;
import com.dragonmeow.mctranslator.translate.UrlHttpTransport;

import com.dragonmeow.mctranslator.neoforge.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NeoForge (Mojang-mapped) client entry point. Reuses the loader-agnostic core
 * (config / translate / cache / service / style) and provides NeoForge glue:
 * chat + item-tooltip translation via events, plus toggle key binds and a
 * background item pre-translation pass.
 */
@Mod(value = MctranslatorNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class MctranslatorNeoForge {

    public static final String MOD_ID = "mctranslator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TranslatorConfig config;
    private static TranslationService service;
    private static Path configPath;
    private static UrlHttpTransport transport;

    private static KeyMapping modeKey;
    private static KeyMapping clearKey;
    private static KeyMapping retranslateKey;
    private static KeyMapping screenScanKey;
    private static KeyMapping toggleKey;
    private boolean pretranslateStarted = false;
    private boolean selfTested = false;

    // Pre-translate items in an open container screen: track the open screen and the
    // item names already warmed, so each distinct item is warmed once (not per tick).
    private net.minecraft.client.gui.screens.Screen lastContainerScreen;
    private final java.util.Set<String> warmedContainerNames = new java.util.HashSet<>();

    /** Online player names, refreshed once per second on the tick thread; read by the
     *  service to mask names in chat and to skip "translating" name tags / scoreboards. */
    private static volatile java.util.Set<String> onlineNames = java.util.Set.of();
    private long lastNameRefreshMs;

    private void refreshOnlineNames(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastNameRefreshMs < 1_000L) return;
        lastNameRefreshMs = now;
        if (mc == null || mc.getConnection() == null) {
            if (!onlineNames.isEmpty()) onlineNames = java.util.Set.of();
            return;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var info : mc.getConnection().getOnlinePlayers()) {
            if (info != null && info.getProfile() != null && isRealPlayer(info.getProfile())) {
                names.add(info.getProfile().getName());
            }
        }
        onlineNames = names;
    }

    private static final java.util.regex.Pattern PLAYER_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** Only REAL players belong in the protected-name set. Servers like Hypixel stuff
     *  fake tab-list entries (NPC/mob skins, info rows) whose names would otherwise be
     *  treated as player IDs and never translated ("Seer"). Real accounts have random
     *  (version 4) UUIDs; fake profiles are offline-style v3 or synthetic. */
    private static boolean isRealPlayer(com.mojang.authlib.GameProfile profile) {
        String n = profile.getName();
        if (n == null || !PLAYER_NAME.matcher(n).matches()) return false;
        java.util.UUID id = profile.getId();
        return id != null && id.version() == 4;
    }
    private final java.util.ArrayDeque<PendingChat> pendingChats = new java.util.ArrayDeque<>();
    private final java.util.Map<Long, PendingChat> pendingChatById = new java.util.HashMap<>();
    private long nextChatId = 1L;

    private static final class PendingChat {
        final long id;
        final Component message;
        final long queuedAtMs = System.currentTimeMillis();
        DisplayMode mode;
        java.util.function.Supplier<Component> builder;
        boolean ready;
        boolean flushedOriginal; // original already shown (slow translation); append it alone later
        boolean framedByServer;  // inside a server ────── announcement frame: skip our magenta wrap

        PendingChat(long id, Component message) {
            this.id = id;
            this.message = message;
        }
    }

    /** How long a chat line may wait for its translation before the original is shown anyway. */
    private static final long CHAT_MAX_WAIT_MS = 15_000L; // safety net only: 原文＋翻譯 output together

    private boolean insideServerFrame;
    private long frameOpenedAtMs;
    private int separatorSalt;

    /**
     * A framed server announcement being collected:
     * <pre>----- / lines… / -----</pre>
     * The whole block is translated together and emitted as ONE chat message, so
     * ordering can't break and compact-chat mods can't merge the two frame lines.
     */
    private static final class PendingBlock {
        final PendingChat holder;                     // the queue slot keeping chat order
        final List<Component> lines = new ArrayList<>();
        final java.util.Map<Integer, Component> translations = new java.util.HashMap<>();
        final long openedAtMs = System.currentTimeMillis();
        int awaiting;
        boolean closed;

        PendingBlock(PendingChat holder) {
            this.holder = holder;
        }
    }

    private PendingBlock activeBlock;
    /** An announcement's lines arrive within a tick or two; a frame still open after
     *  this long is treated as a decorative lone separator and closed as-is. */
    private static final long BLOCK_MAX_OPEN_MS = 3_000L;

    /** Collect framed SYSTEM announcements into one combined message. Returns true if
     *  the line was absorbed into a block (vanilla display must be cancelled). */
    private boolean handleAnnouncementBlock(Component message, boolean isSystem,
                                            DisplayMode mode, String full) {
        boolean isSep = NeoTextStyle.isSeparatorText(full);
        if (activeBlock == null) {
            if (!isSep || !isSystem) return false; // only system messages open a frame
            PendingChat holder = queueChat(message);
            holder.mode = DisplayMode.TRANSLATION;   // builder emits the whole block verbatim
            activeBlock = new PendingBlock(holder);
            activeBlock.lines.add(message);
            return true;
        }
        PendingBlock block = activeBlock;
        int index = block.lines.size();
        block.lines.add(message);
        if (isSep) {
            block.closed = true;
            activeBlock = null;
            maybeFinishBlock(block);
            return true;
        }
        int contentStart = com.dragonmeow.mctranslator.translate.ChatSegmenter.contentStart(full);
        String content = (contentStart > 0 && contentStart < full.length()) ? full.substring(contentStart) : full;
        if (service.wantsChatTranslation(content)) {
            block.awaiting++;
            ColorProfile profile = NeoTextStyle.extractFrom(message, 0);
            NeoTextStyle.MarkedChat marked = (profile.distinctColorCount() >= 2)
                    ? NeoTextStyle.markChatContent(message, 0) : null;
            DisplayMode lineMode = mode;
            service.translateChatAsync(marked != null ? marked.text() : full, translated -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> {
                    if (translated != null) {
                        Component line = (marked != null)
                                ? NeoTextStyle.markedChat(message, 0, translated, marked)
                                : NeoTextStyle.styled(translated, profile, message, 0);
                        block.translations.put(index, lineMode == DisplayMode.BOTH
                                ? Component.empty().append(message).append(Component.literal("\n")).append(line)
                                : line);
                    }
                    block.awaiting--;
                    maybeFinishBlock(block);
                });
            });
        }
        return true;
    }

    private void maybeFinishBlock(PendingBlock block) {
        if (!block.closed || block.awaiting > 0 || block.holder.ready) return;
        block.holder.builder = () -> {
            net.minecraft.network.chat.MutableComponent out = Component.empty();
            for (int i = 0; i < block.lines.size(); i++) {
                if (i > 0) out.append(Component.literal("\n"));
                Component translated = block.translations.get(i);
                out.append(translated != null ? translated : block.lines.get(i));
            }
            return out;
        };
        block.holder.ready = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) flushReadyChats(mc);
    }

    /** Close a frame the server never closed (lone decorative separator). */
    private void expireStaleBlock() {
        if (activeBlock != null && System.currentTimeMillis() - activeBlock.openedAtMs > BLOCK_MAX_OPEN_MS) {
            PendingBlock block = activeBlock;
            activeBlock = null;
            block.closed = true;
            maybeFinishBlock(block);
        }
    }

    /** Track the server's own ────── announcement frames: content between a frame's two
     *  separator lines is already visually boxed, so our magenta wrap is suppressed there.
     *  A frame left open (unpaired decorative separator) expires after 3s. */
    private boolean trackServerFrame(String full) {
        long now = System.currentTimeMillis();
        if (insideServerFrame && now - frameOpenedAtMs > 3_000L) insideServerFrame = false;
        if (NeoTextStyle.isSeparatorText(full)) {
            insideServerFrame = !insideServerFrame;
            frameOpenedAtMs = now;
            return false;
        }
        return insideServerFrame;
    }

    /** The "re-translate pointed item" key binding (rebindable from the 翻譯設定 screen). */
    public static KeyMapping retranslateKeyMapping() {
        return retranslateKey;
    }

    /** Accessor for the Options-screen button Mixin. */
    public static TranslationService service() {
        return service;
    }

    /** Accessor for Mixins (e.g. the scoreboard toggle). */
    public static TranslatorConfig config() {
        return config;
    }

    /**
     * Translate arbitrary GUI text drawn via {@code GuiGraphics} (custom mod screens such as
     * shader-pack settings). Gated by {@code screenTextMode} and only while a screen is open
     * (so the in-world HUD is untouched). Non-blocking + memoised. Called by GuiGraphicsTextMixin.
     */
    public static Component screenText(Component c) {
        TranslationService s = service;
        if (s == null || c == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return c;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null) return c;
        Component t = NeoTextStyle.renderTranslated("screenText", c, s::translateScreenText);
        return t != null ? t : c;
    }

    /** String overload of {@link #screenText(Component)}. */
    public static String screenText(String str) {
        TranslationService s = service;
        if (s == null || str == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return str;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null) return str;
        TranslationDecision d = s.translateScreenText(str);
        return d.changed() ? d.translated() : str;
    }

    /**
     * FormattedCharSequence overload: catches text that mod GUIs draw as already-laid-out
     * ordered text (e.g. FTB Quests descriptions, wrapped/centred lines). The line is
     * flattened to plain text, translated, and rebuilt. Component-originated text is already
     * translated by {@link #screenText(Component)} upstream, so here it is Chinese and skipped.
     */
    public static net.minecraft.util.FormattedCharSequence screenText(net.minecraft.util.FormattedCharSequence fcs) {
        TranslationService s = service;
        if (s == null || fcs == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return fcs;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null) return fcs;
        String plain = NeoTextStyle.plainText(fcs);
        if (plain.isBlank()) return fcs;
        TranslationDecision d = s.translateScreenText(plain);
        if (!d.changed()) return fcs;
        // Keep the line's colours/format (FTB quest text is often coloured) and its
        // click/hover events; and never let a wider translation overflow the widget
        // that laid the original line out — keep the original for that line instead.
        var styled = NeoTextStyle.withInteractive(
                NeoTextStyle.styled(d.translated(), NeoTextStyle.extract(fcs)),
                NeoTextStyle.interactiveStyle(fcs));
        Font font = mc.font;
        if (font != null) {
            int originalWidth = font.width(fcs);
            int budget = Math.max(originalWidth + 24, (int) (originalWidth * 1.25f));
            if (font.width(styled) > budget) return fcs;
        }
        return styled.getVisualOrderText();
    }

    /**
     * FormattedText overload, hooked at {@code Font.split(FormattedText,width)} — the point a
     * GUI wraps a WHOLE block of text into lines (FTB quest descriptions, multi-line tooltips).
     * Translating the whole block here (then letting Minecraft re-wrap the translation) keeps it
     * coherent, instead of translating each already-wrapped line separately (which reads choppy).
     */
    public static net.minecraft.network.chat.FormattedText screenText(net.minecraft.network.chat.FormattedText text) {
        TranslationService s = service;
        if (s == null || text == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return text;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null) return text;
        String plain = NeoTextStyle.plainText(text);
        if (plain.isBlank()) return text;
        TranslationDecision d = s.translateScreenText(plain);
        if (!d.changed()) return text;
        // Pre-wrap block (Font.split input): keep the block's base style so colours
        // survive; Minecraft re-wraps the translation to the same width afterwards.
        net.minecraft.network.chat.Style base = firstStyle(text);
        return base == null ? Component.literal(d.translated())
                : Component.literal(d.translated()).setStyle(base);
    }

    private static net.minecraft.network.chat.Style firstStyle(net.minecraft.network.chat.FormattedText text) {
        net.minecraft.network.chat.Style[] found = {null};
        text.visit((style, str) -> {
            if (!str.isEmpty()) {
                found[0] = style;
                return java.util.Optional.of(true); // stop at the first styled run
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found[0];
    }

    /** Persist config (used by the config screen). Also drops the render cache so a
     *  surface turned off / mode switched takes effect immediately. */
    public static void saveConfig() {
        if (config != null && configPath != null) {
            config.save(configPath);
        }
        NeoTextStyle.clearRenderMemo();
    }

    public MctranslatorNeoForge(IEventBus modBus, ModContainer container) {
        configPath = FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".json");
        config = TranslatorConfig.load(configPath);

        int workers = Math.max(1, config.workerThreads);
        java.util.concurrent.ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "mctranslator-worker");
            t.setDaemon(true);
            return t;
        };
        // LIFO work queue: the most-recently-requested translations — i.e. the page / interface
        // the player is looking at RIGHT NOW — are taken first, jumping ahead of any older
        // backlog (a previous screen, or the background pre-translation). So the current screen
        // is never stuck waiting behind a long queue.
        java.util.concurrent.LinkedBlockingDeque<Runnable> workQueue =
                new java.util.concurrent.LinkedBlockingDeque<>() {
                    @Override
                    public boolean offer(Runnable r) {
                        return super.offerFirst(r); // enqueue at the front => LIFO
                    }
                };
        ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
                workers, workers, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, workQueue, threadFactory);

        transport = new UrlHttpTransport(Duration.ofMillis(config.httpTimeoutMs));
        GoogleFreeTranslator google = new GoogleFreeTranslator(transport, config.sourceLang);
        OpenAiTranslator ai = new OpenAiTranslator(transport,
                () -> new AiSettings(config.aiBaseUrl, config.aiModel, config.aiApiKeys));
        // The AI cache tries AI when a key is configured, else falls back to Google.
        Translator aiTranslator = new DispatchingTranslator(ai, google,
                () -> config.aiApiKeys != null && !config.aiApiKeys.isEmpty());

        PersistentStore googleStore = config.diskCache
                ? new FileStore(FMLPaths.CONFIGDIR.get().resolve(MOD_ID + "-cache.json"), config.clearDiskCacheOnStart)
                : null;
        PersistentStore aiStore = config.diskCache
                ? new FileStore(FMLPaths.CONFIGDIR.get().resolve(MOD_ID + "-ai-cache.json"), config.clearDiskCacheOnStart)
                : null;
        // Separate caches per engine so a 機翻 and an AI result for the same string never collide.
        TranslationCache cache = new TranslationCache(google, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, googleStore);
        TranslationCache aiCache = new TranslationCache(aiTranslator, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, aiStore);
        service = new TranslationService(config, cache, aiCache);
        service.setProtectedNames(() -> onlineNames);

        modBus.addListener(this::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("[{}] (NeoForge) initialized (target={}, chat={}, tooltip={})",
                MOD_ID, config.targetLang, config.chatMode, config.tooltipMode);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        // Cycle the display mode. Unbound by default (use the Options-screen button,
        // or bind a free key in Controls).
        modeKey = new KeyMapping("key.mctranslator.mode", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "category.mctranslator");
        event.register(modeKey);
        // Clear ALL cached translations and re-translate. Unbound by default.
        clearKey = new KeyMapping("key.mctranslator.clear", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "category.mctranslator");
        event.register(clearKey);
        // Re-translate the item you are pointing at / holding. Default: R.
        retranslateKey = new KeyMapping("key.mctranslator.retranslate", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R, "category.mctranslator");
        event.register(retranslateKey);
        // Capture & translate all buttons/options of the open screen (e.g. a modpack
        // quest book). Default: P. Handled in onScreenKeyPressed (key binds don't tick
        // while a screen is open).
        screenScanKey = new KeyMapping("key.mctranslator.screenscan", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P, "category.mctranslator");
        event.register(screenScanKey);
        // Quick 原文/翻譯 master toggle. Default: G.
        toggleKey = new KeyMapping("key.mctranslator.toggle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, "category.mctranslator");
        event.register(toggleKey);
    }

    /** Flip the master 原文/翻譯 switch, bust the render memo so persistent surfaces flip at once, and report. */
    private void flipShowOriginal() {
        if (service == null) return;
        boolean originalsNow = service.toggleShowOriginal();
        NeoTextStyle.clearRenderMemo();
        if (originalsNow) flushPendingChatOriginals();
        status(originalsNow ? "顯示原文（翻譯已暫停）" : "顯示翻譯");
    }

    /** When 跟隨遊戲 is on, keep the translation target language synced to Minecraft's own (繁/簡). */
    private void syncGameLanguage(Minecraft mc) {
        if (service == null || config == null || !config.followGameLanguage || mc == null || mc.options == null) return;
        String desired = mapGameLang(mc.options.languageCode);
        if (!desired.equals(config.targetLang)) {
            service.setTargetLang(desired);
            NeoTextStyle.clearRenderMemo();
        }
    }

    /** Map Minecraft's language code (zh_cn / zh_tw / en_us …) to 繁/簡 target. */
    static String mapGameLang(String gameLang) {
        String t = gameLang == null ? "" : gameLang.toLowerCase().replace('-', '_');
        if (t.startsWith("zh_cn") || t.startsWith("zh_sg") || t.contains("hans")) return "zh-CN";
        return "zh-TW";
    }

    /** The "translate current screen" key binding (rebindable from the 翻譯設定 screen). */
    public static KeyMapping screenScanKeyMapping() {
        return screenScanKey;
    }

    public static KeyMapping toggleKeyMapping() {
        return toggleKey;
    }

    @SubscribeEvent
    public void onRenderNameTag(net.neoforged.neoforge.client.event.RenderNameTagEvent event) {
        if (service == null) return;
        Component current = event.getContent();
        if (current == null) return;
        Component translated = NeoTextStyle.renderTranslated("nameTag", current, service::translateUi);
        if (translated != null) event.setContent(translated);
    }

    @SubscribeEvent
    public void onClientChat(ClientChatReceivedEvent event) {
        if (service == null) return;
        // Overlay (action-bar) system messages are governed by actionBarMode via the
        // renderOverlayMessage redirect — never reroute/cancel them through the chat path.
        if (event instanceof ClientChatReceivedEvent.System sys && sys.isOverlay()) return;
        DisplayMode mode = service.chatMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return;
        Component message = event.getMessage();
        if (message == null) return;
        String full = message.getString();
        // Framed system announcements (----- / lines… / -----) are collected into ONE
        // combined message; only non-player (System) chat may open a frame.
        boolean isSystem = event instanceof ClientChatReceivedEvent.System;
        if (handleAnnouncementBlock(message, isSystem, mode, full)) {
            event.setCanceled(true);
            return;
        }
        boolean framedByServer = trackServerFrame(full);

        // Translate only the content after the rank/name separator (» etc.); keep the prefix.
        int contentStart = com.dragonmeow.mctranslator.translate.ChatSegmenter.contentStart(full);
        boolean hasPrefix = contentStart > 0 && contentStart < full.length();
        String content = hasPrefix ? full.substring(contentStart) : full;
        if (!service.wantsChatTranslation(content)) {
            // Untranslatable line (e.g. the "-----" frame of a Hypixel announcement): if
            // translatable lines are still queued ahead of it, it must WAIT IN LINE as a
            // ready pass-through — otherwise the frame prints before its framed content.
            if (pendingChats.isEmpty()) return;
            event.setCanceled(true);
            Component reinjected = message;
            if (NeoTextStyle.isSeparatorText(full)) {
                // Compact-chat mods merge identical frame lines and delete the earlier one;
                // alternate an invisible trailing space so the two frames never compare equal.
                separatorSalt = (separatorSalt + 1) & 3;
                if (separatorSalt > 0) {
                    reinjected = message.copy().append(Component.literal(" ".repeat(separatorSalt)));
                }
            }
            PendingChat passThrough = queueChat(reinjected);
            passThrough.mode = DisplayMode.ORIGINAL_ONLY;
            passThrough.ready = true;
            return;
        }

        final int cs = contentStart;
        final boolean prefix = hasPrefix;
        // Cancel the vanilla line; we re-inject the (translated) line once ready, falling
        // back to the original if it can't be translated so nothing is ever lost.
        event.setCanceled(true);

        // Colour ONLY from the message content (after any rank/name prefix), so the prefix's
        // colours are never smeared onto the translation.
        ColorProfile contentProfile = NeoTextStyle.extractFrom(message, cs);
        PendingChat pending = queueChat(message);
        pending.framedByServer = framedByServer;

        if (contentProfile.distinctColorCount() >= 2) {
            // Word-level colour preservation: wrap each style run in an invisible ⟦CS#⟧
            // marker, translate the WHOLE line in one request (better grammar, fewer
            // requests than per-segment), then map every marker region back to its style
            // — a red word stays red on its translated word. Click/hover ride along on
            // the segment styles.
            NeoTextStyle.MarkedChat marked = NeoTextStyle.markChatContent(message, cs);
            service.translateChatAsync(marked.text(), translated ->
                    completeChat(pending.id, mode, translated == null ? null : () -> {
                        Font font = Minecraft.getInstance().font;
                        var core = NeoTextStyle.markedChat(message, cs, translated, marked);
                        if (prefix) {
                            return Component.empty()
                                    .append(NeoTextStyle.takePrefix(message, cs))
                                    .append(core);
                        }
                        return core; // core keeps the original's leading whitespace: starts aligned
                    }));
            return;
        }
        service.translateChatAsync(content, translated ->
                completeChat(pending.id, mode, translated == null ? null
                        : () -> chatLine(Minecraft.getInstance().font, message, prefix, cs, contentProfile, translated)));
    }

    private PendingChat queueChat(Component message) {
        PendingChat pending = new PendingChat(nextChatId++, message);
        pendingChats.addLast(pending);
        pendingChatById.put(pending.id, pending);
        return pending;
    }

    private void completeChat(long id, DisplayMode mode, java.util.function.Supplier<Component> builder) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.gui == null) return;
            PendingChat pending = pendingChatById.get(id);
            if (pending == null) return;
            if (pending.flushedOriginal) {
                // The original was already shown by the timeout: append just the translation.
                pendingChatById.remove(id);
                if (builder != null && mode != DisplayMode.ORIGINAL_ONLY) {
                    Component translated = builder.get();
                    if (translated != null) {
                        mc.gui.getChat().addMessage(translated);
                    }
                }
                return;
            }
            pending.mode = mode;
            pending.builder = builder;
            pending.ready = true;
            flushReadyChats(mc);
        });
    }

    /** Never hold chat hostage: after {@link #CHAT_MAX_WAIT_MS} the original is shown and the
     *  translation (when it eventually lands) is appended as its own line. */
    private void flushStaleChats(Minecraft mc) {
        if (mc == null || mc.gui == null) return;
        while (!pendingChats.isEmpty()) {
            PendingChat head = pendingChats.peekFirst();
            if (head.ready) {
                flushReadyChats(mc);
                continue;
            }
            if (System.currentTimeMillis() - head.queuedAtMs < CHAT_MAX_WAIT_MS) break;
            pendingChats.removeFirst();
            head.flushedOriginal = true; // stays in pendingChatById for the late translation
            mc.gui.getChat().addMessage(head.message);
        }
    }

    private void flushReadyChats(Minecraft mc) {
        while (!pendingChats.isEmpty() && pendingChats.peekFirst().ready) {
            PendingChat pending = pendingChats.removeFirst();
            pendingChatById.remove(pending.id);
            addPendingChat(mc, pending);
        }
    }

    private void flushPendingChatOriginals() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.gui == null) return;
            while (!pendingChats.isEmpty()) {
                PendingChat pending = pendingChats.removeFirst();
                pendingChatById.remove(pending.id);
                mc.gui.getChat().addMessage(pending.message);
            }
        });
    }

    private void addPendingChat(Minecraft mc, PendingChat pending) {
        Component translatedLine = (pending.builder != null) ? pending.builder.get() : null;
        if (pending.mode == DisplayMode.TRANSLATION) {
            mc.gui.getChat().addMessage(translatedLine != null ? translatedLine : pending.message);
            return;
        }
        if (translatedLine == null) {
            mc.gui.getChat().addMessage(pending.message);
            return;
        }
        // 原文＋翻譯 as ONE message. Wrapped in magenta separator lines so each block
        // reads as a unit — EXCEPT inside a server ────── announcement frame, which is
        // already boxed (double frames would be noise).
        if (pending.framedByServer) {
            mc.gui.getChat().addMessage(Component.empty()
                    .append(pending.message)
                    .append(Component.literal("\n"))
                    .append(translatedLine));
            return;
        }
        int len = NeoTextStyle.maxLineLength(pending.message.getString(), translatedLine.getString());
        mc.gui.getChat().addMessage(Component.empty()
                .append(NeoTextStyle.separatorLine(len))
                .append(Component.literal("\n")).append(pending.message)
                .append(Component.literal("\n")).append(translatedLine)
                .append(Component.literal("\n")).append(NeoTextStyle.separatorLine(len)));
    }
    /** Builds a single-colour translated chat line: prefix kept verbatim, translation coloured
     *  from the CONTENT's profile and (when unprefixed) re-centred. */
    private static Component chatLine(Font font, Component message, boolean hasPrefix, int contentStart,
                                      ColorProfile contentProfile, String translated) {
        if (hasPrefix) {
            // styled(..., message, contentStart) inherits the content's click/hover events,
            // so a clickable plugin message stays clickable after translation.
            Component styled = NeoTextStyle.styled(translated, contentProfile, message, contentStart);
            return Component.empty().append(NeoTextStyle.takePrefix(message, contentStart)).append(styled);
        }
        // translated already carries the original's leading whitespace (LayoutPreserver).
        return NeoTextStyle.styled(translated, contentProfile, message, contentStart);
    }

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (service == null) return;
        DisplayMode mode = service.tooltipMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return;
        List<Component> lines = event.getToolTip();
        if (lines.isEmpty()) return;

        // The server pre-wraps lore text: detect sentences split across lines and treat
        // each run as ONE translation unit (whole-sentence grammar + colours), re-wrapped
        // to the tooltip width afterwards. groupEnd[i] = last line index of the group
        // starting at i (== i for standalone lines).
        int n = lines.size();
        int[] groupEnd = new int[n];
        for (int i = 0; i < n; i++) groupEnd[i] = i;
        String lang = config.targetLang;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && lines.get(j) != null && lines.get(j + 1) != null
                    && com.dragonmeow.mctranslator.translate.TextFilter.shouldTranslate(lines.get(j).getString(), lang)
                    && com.dragonmeow.mctranslator.translate.TextFilter.shouldTranslate(lines.get(j + 1).getString(), lang)
                    && NeoTextStyle.continuesSentence(lines.get(j).getString(), lines.get(j + 1).getString())) {
                j++;
            }
            groupEnd[i] = j;
            i = j + 1;
        }

        // Warm the WHOLE tooltip in one batch so the AI backend gets shared context
        // (e.g. "EV Yields" disambiguated by the rest of the tooltip). Non-blocking.
        List<String> sources = new ArrayList<>(n);
        for (int i = 0; i < n; i = groupEnd[i] + 1) {
            if (lines.get(i) == null) continue;
            sources.add(groupEnd[i] > i
                    ? NeoTextStyle.groupRequestText(lines.subList(i, groupEnd[i] + 1))
                    : lines.get(i).getString());
        }
        service.warmTooltipBatch(sources);

        Font font = Minecraft.getInstance().font;
        List<Component> out = new ArrayList<>(n);
        List<Component> appended = (mode == DisplayMode.BOTH) ? new ArrayList<>() : null;
        int maxLen = 0;
        boolean originalEndsWithSeparator = false;
        for (int i = 0; i < n; ) {
            int end = groupEnd[i];
            Component line = lines.get(i);
            if (line == null) {
                out.add(line); // keep the list shape other mods may rely on
                i = end + 1;
                continue;
            }
            if (mode == DisplayMode.BOTH) {
                for (int k = i; k <= end; k++) {
                    String t = lines.get(k) == null ? "" : lines.get(k).getString();
                    maxLen = Math.max(maxLen, t.length());
                    if (!t.isBlank()) originalEndsWithSeparator = NeoTextStyle.isSeparatorText(t);
                }
            }
            if (end > i) {
                // Wrapped sentence: translate the whole run, re-wrap to its original width.
                List<Component> group = new ArrayList<>(lines.subList(i, end + 1));
                List<Component> translated = NeoTextStyle.renderTranslatedGroup(
                        group, service::translateItemLine, font);
                if (translated == null) {
                    out.addAll(group);
                } else if (mode == DisplayMode.BOTH) {
                    out.addAll(group);
                    for (Component t : translated) {
                        appended.add(t);
                        maxLen = Math.max(maxLen, t.getString().length());
                    }
                } else {
                    out.addAll(translated);
                }
                i = end + 1;
                continue;
            }
            Component translated = NeoTextStyle.renderTranslated("tooltip", line, service::translateItemLine);
            if (translated == null) {
                out.add(line);
            } else if (mode == DisplayMode.BOTH) {
                out.add(line);
                appended.add(translated);
                maxLen = Math.max(maxLen, translated.getString().length());
            } else {
                out.add(translated);
            }
            i = end + 1;
        }
        if (appended != null && !appended.isEmpty()) {
            if (!originalEndsWithSeparator) {
                out.add(NeoTextStyle.separatorLine(maxLen));   // top divider
            }
            out.addAll(appended);
            out.add(NeoTextStyle.separatorLine(maxLen));   // bottom divider
        }
        lines.clear();
        lines.addAll(out);
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (modeKey != null) {
            while (modeKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) mc.setScreen(new TranslationConfigScreen(mc.screen));
            }
        }
        if (clearKey != null && service != null) {
            while (clearKey.consumeClick()) {
                service.clearTranslations();
                NeoTextStyle.clearRenderMemo();
                pretranslateStarted = false; // let the background pass re-run
                status("已清除翻譯，重新翻譯中…");
            }
        }
        if (retranslateKey != null && service != null) {
            // In-world (no screen open): re-translate the held item. The container-screen
            // case is handled by onScreenKeyPressed (key binds don't tick while a screen is open).
            while (retranslateKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) retranslateItem(mc.player.getMainHandItem());
            }
        }
        if (toggleKey != null && service != null) {
            while (toggleKey.consumeClick()) flipShowOriginal();
        }
        syncGameLanguage(Minecraft.getInstance());
        if (!pretranslateStarted && config.pretranslateItemsOnLoad && Minecraft.getInstance() != null) {
            pretranslateStarted = true;
            startPretranslate();
        }
        // Flush the coalesced per-frame translation buffer once per tick: a screen full of
        // text becomes ONE batched request rather than dozens (fewer AI / Google requests).
        if (service != null) service.flushBatches();
        refreshOnlineNames(Minecraft.getInstance());
        expireStaleBlock();
        flushStaleChats(Minecraft.getInstance());
        // Pre-translate every item in an open container/inventory screen so the names are
        // ready the instant you hover (not popped-in a frame later, and not only on hover).
        warmOpenContainerItems(Minecraft.getInstance());
        // One-time self-test once the player is in-world: prints backend status to chat.
        if (!selfTested && Minecraft.getInstance() != null && Minecraft.getInstance().player != null) {
            selfTested = true;
            Thread t = new Thread(() -> {
                String result = service.selfTest();
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.gui.getChat().addMessage(Component.literal("[翻譯自測] " + result));
                        }
                    });
                }
            }, "mctranslator-selftest");
            t.setDaemon(true);
            t.start();
        }
    }

    @SubscribeEvent
    public void onScreenKeyPressed(net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (service == null) return;
        // Key binds don't tick while a screen is showing, so handle our screen hotkeys here.
        if (toggleKey != null && toggleKey.matches(event.getKeyCode(), event.getScanCode())) {
            flipShowOriginal();
            return;
        }
        if (retranslateKey != null && retranslateKey.matches(event.getKeyCode(), event.getScanCode())) {
            // Re-translate the item under the mouse in a container screen.
            if (event.getScreen() instanceof AbstractContainerScreen<?> screen
                    && screen instanceof AbstractContainerScreenAccessor accessor) {
                Slot slot = accessor.mctranslator$hoveredSlot();
                if (slot != null && slot.hasItem()) {
                    retranslateItem(slot.getItem());
                }
            }
            return;
        }
        if (screenScanKey != null && screenScanKey.matches(event.getKeyCode(), event.getScanCode())) {
            // Translate every button/option of the open screen (e.g. a quest book).
            scanAndTranslateScreen(event.getScreen());
        }
    }

    /**
     * Capture and translate the text of every button / option widget on {@code screen}
     * (recursing into nested widget containers — "含分支"), replacing each widget's label
     * with its translation in place. Async &amp; non-blocking; an explicit user action, so
     * it ignores the per-surface on/off toggles. Best-effort: catches standard
     * {@link net.minecraft.client.gui.components.AbstractWidget} labels (vanilla-style
     * buttons/options); screens that draw raw text without widgets are not covered.
     */
    private void scanAndTranslateScreen(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null || service == null) return;
        List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();
        collectWidgets(screen.children(), widgets, 0);
        int requested = 0;
        for (net.minecraft.client.gui.components.AbstractWidget widget : widgets) {
            Component msg = widget.getMessage();
            if (msg == null) continue;
            String src = msg.getString();
            if (src == null || src.isBlank()) continue;
            final com.dragonmeow.mctranslator.style.ColorProfile profile = NeoTextStyle.extract(msg);
            service.requestScreenTextAsync(src, translated -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> widget.setMessage(NeoTextStyle.styled(translated, profile)));
            });
            requested++;
        }
        status("擷取介面文字翻譯中…（" + requested + " 項）");
    }

    /** Depth-bounded recursive collect of all widgets, descending into nested containers. */
    private static void collectWidgets(
            List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children,
            List<net.minecraft.client.gui.components.AbstractWidget> out, int depth) {
        if (children == null || depth > 8) return;
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                out.add(w);
            }
            if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                collectWidgets(c.children(), out, depth + 1);
            }
        }
    }

    /**
     * Warm the names of every item in the currently-open container/inventory screen, so
     * they are translated and ready the instant the player hovers (rather than only on
     * hover, popping in a frame later). Each distinct item name is warmed once per screen;
     * the per-tick scan only adds newly-arrived items (servers sync container contents a
     * tick or two after the screen opens), so it is cheap. The full tooltip (lore) still
     * warms on hover via {@link #onItemTooltip}.
     */
    private void warmOpenContainerItems(Minecraft mc) {
        if (mc == null || service == null) return;
        if (service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) return; // nothing to warm
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            // Left the container: reset so the next one warms fresh.
            if (lastContainerScreen != null) {
                lastContainerScreen = null;
                warmedContainerNames.clear();
            }
            return;
        }
        if (screen != lastContainerScreen) {
            lastContainerScreen = screen;
            warmedContainerNames.clear();
        }
        List<String> newNames = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot == null || !slot.hasItem()) continue;
            String name = slot.getItem().getHoverName().getString();
            if (name != null && !name.isBlank() && warmedContainerNames.add(name)) {
                newNames.add(name);
            }
        }
        if (!newNames.isEmpty()) service.warmTooltipBatch(newNames);
    }

    /** Evict + re-translate one item's tooltip lines (the "re-translate pointed item" hotkey). */
    private void retranslateItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || service == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        List<String> sources = new ArrayList<>();
        try {
            Item.TooltipContext ctx = Item.TooltipContext.of(mc.level);
            for (Component c : stack.getTooltipLines(ctx, mc.player, TooltipFlag.Default.NORMAL)) {
                if (c != null) sources.add(c.getString());
            }
        } catch (RuntimeException e) {
            return;
        }
        service.retranslate(sources);
        NeoTextStyle.clearRenderMemo();
        status("重新翻譯：" + stack.getHoverName().getString());
    }

    /** Background AI connection test used by the AI settings screen; result delivered on the client thread. */
    public static void testAi(String baseUrl, String model, List<String> keys, java.util.function.Consumer<String> onResult) {
        if (transport == null) {
            onResult.accept("§c尚未初始化");
            return;
        }
        Thread t = new Thread(() -> {
            String msg;
            try {
                OpenAiTranslator ai = new OpenAiTranslator(transport, () -> new AiSettings(baseUrl, model, keys));
                String out = ai.translate("Hello, world", "zh-TW").translatedText();
                msg = "§a成功：Hello, world → " + out;
            } catch (Exception e) {
                msg = "§c失敗：" + e.getMessage();
            }
            final String result = msg;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> onResult.accept(result));
            else onResult.accept(result);
        }, "mctranslator-aitest");
        t.setDaemon(true);
        t.start();
    }

    private void startPretranslate() {
        List<String> names = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                String name = item.getDescription().getString();
                if (name != null && !name.isBlank()) names.add(name);
            } catch (RuntimeException ignored) {
                // skip
            }
        }
        int batch = Math.max(1, config.pretranslateBatchSize);
        long delay = Math.max(0, config.pretranslateDelayMs);
        Thread worker = new Thread(() -> {
            int consecutiveFailures = 0;
            for (int from = 0; from < names.size(); from += batch) {
                if (Thread.currentThread().isInterrupted()) return;
                boolean ok = true;
                try {
                    ok = service.warmUpBatch(names.subList(from, Math.min(from + batch, names.size())));
                } catch (RuntimeException e) {
                    ok = false;
                }
                consecutiveFailures = ok ? 0 : consecutiveFailures + 1;
                if (consecutiveFailures > 5) {
                    LOGGER.warn("[{}] pre-translation aborted (backend unreachable / rate-limited)", MOD_ID);
                    return;
                }
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            LOGGER.info("[{}] item pre-translation pass complete ({} names)", MOD_ID, names.size());
        }, "mctranslator-pretranslate");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void status(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.gui.getChat().addMessage(Component.literal("[翻譯] " + msg));
        }
    }
}
