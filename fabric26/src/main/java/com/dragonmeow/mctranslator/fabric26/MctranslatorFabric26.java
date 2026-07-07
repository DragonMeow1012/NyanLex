package com.dragonmeow.mctranslator.fabric26;

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

import com.dragonmeow.mctranslator.fabric26.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.input.KeyEvent;
import net.fabricmc.loader.api.FabricLoader;
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
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;






public final class MctranslatorFabric26 implements ClientModInitializer {

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
                names.add(info.getProfile().name());
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
        String n = profile.name();
        if (n == null || !PLAYER_NAME.matcher(n).matches()) return false;
        java.util.UUID id = profile.id();
        return id != null && id.version() == 4;
    }
    private final java.util.ArrayDeque<PendingChat> pendingChats = new java.util.ArrayDeque<>();
    private final java.util.Map<Long, PendingChat> pendingChatById = new java.util.HashMap<>();
    private long nextChatId = 1L;

    private static final class PendingChat {
        final long id;
        final Component message;
        final net.minecraft.network.chat.ChatType.Bound params;
        final long queuedAtMs = System.currentTimeMillis();
        DisplayMode mode;
        java.util.function.Supplier<Component> builder;
        boolean ready;
        boolean flushedOriginal; // original already shown (slow translation); append it alone later
        boolean framedByServer;  // inside a server ────── announcement frame: skip our magenta wrap

        PendingChat(long id, Component message, net.minecraft.network.chat.ChatType.Bound params) {
            this.id = id;
            this.message = message;
            this.params = params;
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
    private boolean handleAnnouncementBlock(Component message, net.minecraft.network.chat.ChatType.Bound params,
                                            DisplayMode mode, String full) {
        boolean isSep = Fabric26TextStyle.isSeparatorText(full);
        if (activeBlock == null) {
            if (!isSep || params != null) return false; // only system messages open a frame
            PendingChat holder = queueChat(message, params);
            holder.mode = DisplayMode.TRANSLATION;      // builder emits the whole block verbatim
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
            ColorProfile profile = Fabric26TextStyle.extractFrom(message, 0);
            Fabric26TextStyle.MarkedChat marked = (profile.distinctColorCount() >= 2)
                    ? Fabric26TextStyle.markChatContent(message, 0) : null;
            DisplayMode lineMode = mode;
            service.translateChatAsync(marked != null ? marked.text() : full, translated -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> {
                    if (translated != null) {
                        Component line = (marked != null)
                                ? Fabric26TextStyle.markedChat(message, 0, translated, marked)
                                : Fabric26TextStyle.styled(translated, profile, message, 0);
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
        if (Fabric26TextStyle.isSeparatorText(full)) {
            insideServerFrame = !insideServerFrame;
            frameOpenedAtMs = now;
            return false;
        }
        return insideServerFrame;
    }

    public static TranslationService service() {
        return service;
    }

    public static TranslatorConfig config() {
        return config;
    }

    public static KeyMapping retranslateKeyMapping() {
        return retranslateKey;
    }

    public static KeyMapping screenScanKeyMapping() {
        return screenScanKey;
    }

    public static KeyMapping modeKeyMapping() {
        return modeKey;
    }

    public static KeyMapping clearKeyMapping() {
        return clearKey;
    }

    public static KeyMapping toggleKeyMapping() {
        return toggleKey;
    }

    public static void saveConfig() {
        if (config != null && configPath != null) {
            config.save(configPath);
        }
        Fabric26TextStyle.clearRenderMemo();
    }

    

    public static Component screenText(Component c) {
        TranslationService s = service;
        if (s == null || c == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return c;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui.screen() == null) return c;
        Component t = Fabric26TextStyle.renderTranslated("screenText", c, s::translateScreenText);
        return t != null ? t : c;
    }

    public static String screenText(String str) {
        TranslationService s = service;
        if (s == null || str == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return str;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui.screen() == null) return str;
        TranslationDecision d = s.translateScreenText(str);
        return d.changed() ? d.translated() : str;
    }

    public static net.minecraft.util.FormattedCharSequence screenText(net.minecraft.util.FormattedCharSequence fcs) {
        TranslationService s = service;
        if (s == null || fcs == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return fcs;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui.screen() == null) return fcs;
        String plain = Fabric26TextStyle.plainText(fcs);
        if (plain.isBlank()) return fcs;
        TranslationDecision d = s.translateScreenText(plain);
        if (!d.changed()) return fcs;
        // Keep the line's colours/format (FTB quest text is often coloured) and its
        // click/hover events; and never let a wider translation overflow the widget
        // that laid the original line out — keep the original for that line instead.
        var styled = Fabric26TextStyle.withInteractive(
                Fabric26TextStyle.styled(d.translated(), Fabric26TextStyle.extract(fcs)),
                Fabric26TextStyle.interactiveStyle(fcs));
        Font font = mc.font;
        if (font != null) {
            int originalWidth = font.width(fcs);
            int budget = Math.max(originalWidth + 24, (int) (originalWidth * 1.25f));
            if (font.width(styled) > budget) return fcs;
        }
        return styled.getVisualOrderText();
    }

    public static net.minecraft.network.chat.FormattedText screenText(net.minecraft.network.chat.FormattedText text) {
        TranslationService s = service;
        if (s == null || text == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return text;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui.screen() == null) return text;
        String plain = Fabric26TextStyle.plainText(text);
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

    
    public static Component nameTag(Component c) {
        TranslationService s = service;
        if (s == null || c == null) return c;
        Component t = Fabric26TextStyle.renderTranslated("nameTag", c, s::translateUi);
        return t != null ? t : c;
    }

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
        config = TranslatorConfig.load(configPath);

        int workers = Math.max(1, config.workerThreads);
        java.util.concurrent.ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "mctranslator-worker");
            t.setDaemon(true);
            return t;
        };
        
        java.util.concurrent.LinkedBlockingDeque<Runnable> workQueue =
                new java.util.concurrent.LinkedBlockingDeque<>() {
                    @Override
                    public boolean offer(Runnable r) {
                        return super.offerFirst(r);
                    }
                };
        ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
                workers, workers, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, workQueue, threadFactory);

        transport = new UrlHttpTransport(Duration.ofMillis(config.httpTimeoutMs));
        GoogleFreeTranslator google = new GoogleFreeTranslator(transport, config.sourceLang);
        OpenAiTranslator ai = new OpenAiTranslator(transport,
                () -> new AiSettings(config.aiBaseUrl, config.aiModel, config.aiApiKeys, config.aiGlossary));
        Translator aiTranslator = new DispatchingTranslator(ai, google,
                () -> config.aiApiKeys != null && !config.aiApiKeys.isEmpty());

        PersistentStore googleStore = config.diskCache
                ? new FileStore(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + "-cache.json"), config.clearDiskCacheOnStart)
                : null;
        PersistentStore aiStore = config.diskCache
                ? new FileStore(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + "-ai-cache.json"), config.clearDiskCacheOnStart)
                : null;
        TranslationCache cache = new TranslationCache(google, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, googleStore);
        TranslationCache aiCache = new TranslationCache(aiTranslator, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, aiStore);
        service = new TranslationService(config, cache, aiCache);
        service.setProtectedNames(() -> onlineNames);

        registerKeyBinds();
        registerEvents();

        LOGGER.info("[{}] (Fabric) initialized (target={}, chat={}, tooltip={})",
                MOD_ID, config.targetLang, config.chatMode, config.tooltipMode);
    }

    private void registerKeyBinds() {
        modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mctranslator.mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC));
        clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mctranslator.clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC));
        retranslateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mctranslator.retranslate", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KeyMapping.Category.MISC));
        screenScanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mctranslator.screenscan", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, KeyMapping.Category.MISC));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mctranslator.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));
    }

    




    private void syncGameLanguage(Minecraft mc) {
        if (service == null || config == null || !config.followGameLanguage || mc == null || mc.options == null) return;
        String desired = mapGameLang(mc.options.languageCode);
        if (!desired.equals(config.targetLang)) {
            service.setTargetLang(desired);
            Fabric26TextStyle.clearRenderMemo();
        }
    }

    
    static String mapGameLang(String gameLang) {
        String t = gameLang == null ? "" : gameLang.toLowerCase().replace('-', '_');
        if (t.startsWith("zh_cn") || t.startsWith("zh_sg") || t.contains("hans")) return "zh-CN";
        return "zh-TW"; 
    }

    
    private void flipShowOriginal() {
        if (service == null) return;
        boolean originalsNow = service.toggleShowOriginal();
        Fabric26TextStyle.clearRenderMemo();
        if (originalsNow) flushPendingChatOriginals();
        status(originalsNow ? "顯示原文（翻譯已暫停）" : "顯示翻譯");
    }

    private void registerEvents() {
        
        
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            return !translateAndInject(message, null);
        });
        
        
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                !translateAndInject(message, params));

        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> onItemTooltip(lines));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        
        
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenKeyboardEvents.afterKeyPress(screen).register((scr, keyEvent) -> onScreenKey(scr, keyEvent)));
    }

    

    





    private boolean translateAndInject(Component message, net.minecraft.network.chat.ChatType.Bound params) {
        if (service == null || message == null) return false;
        DisplayMode mode = service.chatMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return false;
        String full = message.getString();
        if (handleAnnouncementBlock(message, params, mode, full)) return true;
        boolean framedByServer = trackServerFrame(full);
        int contentStart = com.dragonmeow.mctranslator.translate.ChatSegmenter.contentStart(full);
        boolean hasPrefix = contentStart > 0 && contentStart < full.length();
        String content = hasPrefix ? full.substring(contentStart) : full;
        if (!service.wantsChatTranslation(content)) {
            // Untranslatable line (e.g. the "-----" frame of a Hypixel announcement): if
            // translatable lines are still queued ahead of it, it must WAIT IN LINE as a
            // ready pass-through — otherwise the frame prints before its framed content.
            if (pendingChats.isEmpty()) return false;
            Component reinjected = message;
            if (Fabric26TextStyle.isSeparatorText(full)) {
                // Compact-chat mods merge identical frame lines and delete the earlier one;
                // alternate an invisible trailing space so the two frames never compare equal.
                separatorSalt = (separatorSalt + 1) & 3;
                if (separatorSalt > 0) {
                    reinjected = message.copy().append(Component.literal(" ".repeat(separatorSalt)));
                }
            }
            PendingChat passThrough = queueChat(reinjected, params);
            passThrough.mode = DisplayMode.ORIGINAL_ONLY;
            passThrough.ready = true;
            return true;
        }

        final int cs = contentStart;
        final boolean prefix = hasPrefix;
        ColorProfile contentProfile = Fabric26TextStyle.extractFrom(message, cs);
        PendingChat pending = queueChat(message, params);
        pending.framedByServer = framedByServer;

        if (contentProfile.distinctColorCount() >= 2) {
            Fabric26TextStyle.MarkedChat marked = Fabric26TextStyle.markChatContent(message, cs);
            service.translateChatAsync(marked.text(), translated ->
                    completeChat(pending.id, mode, translated == null ? null
                            : () -> markedChatLine(Minecraft.getInstance().font, message, prefix, cs, marked, translated)));
            return true;
        }

        service.translateChatAsync(content, translated ->
                completeChat(pending.id, mode, translated == null ? null
                        : () -> chatLine(Minecraft.getInstance().font, message, prefix, cs, translated)));
        return true;
    }

    private PendingChat queueChat(Component message, net.minecraft.network.chat.ChatType.Bound params) {
        PendingChat pending = new PendingChat(nextChatId++, message, params);
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
                        mc.gui.hud.getChat().addClientSystemMessage(decorate(pending.params, translated));
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
            mc.gui.hud.getChat().addClientSystemMessage(decorate(head.params, head.message));
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
                mc.gui.hud.getChat().addClientSystemMessage(decorate(pending.params, pending.message));
            }
        });
    }

    private void addPendingChat(Minecraft mc, PendingChat pending) {
        Component translatedLine = (pending.builder != null) ? pending.builder.get() : null;
        if (pending.mode == DisplayMode.TRANSLATION) {
            mc.gui.hud.getChat().addClientSystemMessage(decorate(pending.params,
                    translatedLine != null ? translatedLine : pending.message));
            return;
        }
        if (translatedLine == null) {
            mc.gui.hud.getChat().addClientSystemMessage(decorate(pending.params, pending.message));
            return;
        }
        // 原文＋翻譯 as ONE message. Wrapped in magenta separator lines so each block
        // reads as a unit — EXCEPT inside a server ────── announcement frame, which is
        // already boxed (double frames would be noise).
        if (pending.framedByServer) {
            mc.gui.hud.getChat().addClientSystemMessage(decorate(pending.params, Component.empty()
                    .append(pending.message)
                    .append(Component.literal("\n"))
                    .append(translatedLine)));
            return;
        }
        int len = Fabric26TextStyle.maxLineLength(pending.message.getString(), translatedLine.getString());
        mc.gui.hud.getChat().addClientSystemMessage(decorate(pending.params, Component.empty()
                .append(Fabric26TextStyle.separatorLine(len))
                .append(Component.literal("\n")).append(pending.message)
                .append(Component.literal("\n")).append(translatedLine)
                .append(Component.literal("\n")).append(Fabric26TextStyle.separatorLine(len))));
    }
    
    private static Component decorate(net.minecraft.network.chat.ChatType.Bound params, Component line) {
        return params != null ? params.decorate(line) : line;
    }

    private static Component chatLine(Font font, Component message, boolean hasPrefix, int contentStart,
                                      String translated) {
        // withInteractive(...) inherits the content's click/hover events, so a clickable
        // plugin message stays clickable after translation.
        net.minecraft.network.chat.Style interactive = Fabric26TextStyle.interactiveStyle(message, contentStart);
        if (hasPrefix) {
            Component styled = Fabric26TextStyle.withInteractive(
                    Fabric26TextStyle.styledChatContent(message, contentStart, translated), interactive);
            return Component.empty().append(Fabric26TextStyle.takePrefix(message, contentStart)).append(styled);
        }
        // translated already carries the original's leading whitespace (LayoutPreserver).
        return Fabric26TextStyle.withInteractive(
                Fabric26TextStyle.styledChatContent(message, contentStart, translated), interactive);
    }

    private static Component markedChatLine(Font font, Component message, boolean hasPrefix, int contentStart,
                                            Fabric26TextStyle.MarkedChat marked, String translated) {
        var core = Fabric26TextStyle.markedChat(message, contentStart, translated, marked);
        if (hasPrefix) {
            return Component.empty().append(Fabric26TextStyle.takePrefix(message, contentStart)).append(core);
        }
        return core; // core keeps the original's leading whitespace: starts aligned
    }

    

    private void onItemTooltip(List<Component> lines) {
        if (service == null) return;
        DisplayMode mode = service.tooltipMode();
        if (mode == DisplayMode.ORIGINAL_ONLY || lines.isEmpty()) return;

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
                    && Fabric26TextStyle.continuesSentence(lines.get(j).getString(), lines.get(j + 1).getString())) {
                j++;
            }
            groupEnd[i] = j;
            i = j + 1;
        }

        List<String> sources = new ArrayList<>(n);
        for (int i = 0; i < n; i = groupEnd[i] + 1) {
            if (lines.get(i) == null) continue;
            sources.add(groupEnd[i] > i
                    ? Fabric26TextStyle.groupRequestText(lines.subList(i, groupEnd[i] + 1))
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
                    if (!t.isBlank()) originalEndsWithSeparator = Fabric26TextStyle.isSeparatorText(t);
                }
            }
            if (end > i) {
                // Wrapped sentence: translate the whole run, re-wrap to its original width.
                List<Component> group = new ArrayList<>(lines.subList(i, end + 1));
                List<Component> translated = Fabric26TextStyle.renderTranslatedGroup(
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
            Component translated = Fabric26TextStyle.renderTranslated("tooltip", line, service::translateItemLine);
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
                out.add(Fabric26TextStyle.separatorLine(maxLen));
            }
            out.addAll(appended);
            out.add(Fabric26TextStyle.separatorLine(maxLen));
        }
        lines.clear();
        lines.addAll(out);
    }

    

    private void onClientTick(Minecraft mc) {
        if (modeKey != null) {
            while (modeKey.consumeClick()) {
                if (mc != null) mc.setScreenAndShow(new Fabric26ConfigScreen(mc.gui.screen()));
            }
        }
        if (clearKey != null && service != null) {
            while (clearKey.consumeClick()) {
                service.clearTranslations();
                Fabric26TextStyle.clearRenderMemo();
                pretranslateStarted = false;
                status("已清除翻譯，重新翻譯中…");
            }
        }
        if (retranslateKey != null && service != null) {
            while (retranslateKey.consumeClick()) {
                if (mc != null && mc.player != null) retranslateItem(mc.player.getMainHandItem());
            }
        }
        if (toggleKey != null && service != null) {
            while (toggleKey.consumeClick()) flipShowOriginal();
        }
        syncGameLanguage(mc);
        if (!pretranslateStarted && config.pretranslateItemsOnLoad && mc != null) {
            pretranslateStarted = true;
            startPretranslate();
        }
        if (service != null) service.flushBatches();
        refreshOnlineNames(mc);
        expireStaleBlock();
        flushStaleChats(mc);
        warmOpenContainerItems(mc);
        if (!selfTested && mc != null && mc.player != null) {
            selfTested = true;
            Thread t = new Thread(() -> {
                String result = service.selfTest();
                Minecraft m = Minecraft.getInstance();
                if (m != null) {
                    m.execute(() -> {
                        if (m.player != null) m.gui.hud.getChat().addClientSystemMessage(Component.literal("[翻譯自測] " + result));
                    });
                }
            }, "mctranslator-selftest");
            t.setDaemon(true);
            t.start();
        }
    }





    private void onScreenKey(net.minecraft.client.gui.screens.Screen screen, KeyEvent keyEvent) {
        if (service == null) return;
        
        
        if (screen instanceof Fabric26ConfigScreen || screen instanceof Fabric26AiScreen
                || screen instanceof Fabric26KeybindScreen) return;
        if (toggleKey != null && toggleKey.matches(keyEvent)) {
            flipShowOriginal();
            return;
        }
        if (retranslateKey != null && retranslateKey.matches(keyEvent)) {
            if (screen instanceof AbstractContainerScreen<?> cs && cs instanceof AbstractContainerScreenAccessor accessor) {
                Slot slot = accessor.mctranslator$hoveredSlot();
                if (slot != null && slot.hasItem()) retranslateItem(slot.getItem());
            }
            return;
        }
        if (screenScanKey != null && screenScanKey.matches(keyEvent)) {
            scanAndTranslateScreen(screen);
        }
    }

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
            final ColorProfile profile = Fabric26TextStyle.extract(msg);
            service.requestScreenTextAsync(src, translated -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> widget.setMessage(Fabric26TextStyle.styled(translated, profile)));
            });
            requested++;
        }
        status("擷取介面文字翻譯中…（" + requested + " 項）");
    }

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

    private void warmOpenContainerItems(Minecraft mc) {
        if (mc == null || service == null) return;
        if (service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
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
        Fabric26TextStyle.clearRenderMemo();
        status("重新翻譯：" + stack.getHoverName().getString());
    }

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
                String name = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
                if (name != null && !name.isBlank()) names.add(name);
            } catch (RuntimeException ignored) {
                
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
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[翻譯] " + msg));
        }
    }
}
