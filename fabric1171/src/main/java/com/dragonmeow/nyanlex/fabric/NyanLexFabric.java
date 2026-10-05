package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.cache.DynamicNamespacedStore;
import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.cache.NamespacedStore;
import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.ProviderLanguageFileStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.KeybindMigration;
import com.dragonmeow.nyanlex.config.LegacyDataMigration;
import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubDownloadJob;
import com.dragonmeow.nyanlex.hub.HubDownloadState;
import com.dragonmeow.nyanlex.hub.HubDownloader;
import com.dragonmeow.nyanlex.hub.HubLocalCache;
import com.dragonmeow.nyanlex.hub.HubPaths;
import com.dragonmeow.nyanlex.hub.HubPlan;
import com.dragonmeow.nyanlex.hub.HubRepository;
import com.dragonmeow.nyanlex.hub.ModpackDetector;
import com.dragonmeow.nyanlex.hub.ModpackIdentity;
import com.dragonmeow.nyanlex.hub.ServerHostNormalizer;
import com.dragonmeow.nyanlex.service.ChatDeliverySession;
import com.dragonmeow.nyanlex.service.ChatRequestProfile;
import com.dragonmeow.nyanlex.service.RecoveryAssembly;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.AntigravityCliClient;
import com.dragonmeow.nyanlex.translate.AntigravityCliTransport;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.SessionTokenUsage;
import com.dragonmeow.nyanlex.translate.SwitchingAiTranslator;
import com.dragonmeow.nyanlex.translate.SwitchingMachineTranslator;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.UrlHttpTransport;

import com.dragonmeow.nyanlex.fabric.mixin.AbstractContainerScreenAccessor;
import com.dragonmeow.nyanlex.fabric.mixin.ChatComponentMixin;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fabric (Mojang-mapped) client entry point. Shares the loader-agnostic core and the
 * Minecraft glue (mixins, {@link FabricTextStyle}, config screens) with the other loader builds;
 * only the bootstrap + event wiring differs.
 */
public final class NyanLexFabric implements ClientModInitializer {

    public static final String MOD_ID = "nyanlex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TranslatorConfig config;
    public static com.dragonmeow.nyanlex.service.OutgoingChatTranslator outgoingChat;
    private static TranslationService service;
    private static TranslationDebugLog debugLog;
    private static Path configPath;
    private static UrlHttpTransport transport;
    private static CodexAppServerClient codexClient;
    private static CodexAppServerTransport codexTransport;
    private static AntigravityCliClient antigravityClient;
    private static AntigravityCliTransport antigravityTransport;
    private static final SessionTokenUsage tokenUsage = new SessionTokenUsage();

    private static HubLocalCache hubLocalCache;
    private static HubDownloadState hubDownloadState;
    private static HubDownloader hubDownloader;
    private static final HubDownloadJob hubDownloadJob = new HubDownloadJob();
    private static boolean firstStartTried;
    private static volatile com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState packState =
            com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState.IDLE;
    private static volatile HubPlan packPlan;
    private static volatile boolean keybindMigrationChecked;
    private static NyanLexFabric instance;
    private static final ThreadLocal<Boolean> INTERNAL_CHAT =
            ThreadLocal.withInitial(() -> false);

    private static KeyMapping modeKey;
    private static volatile boolean settingsScreenRequested;
    private static KeyMapping retranslateKey;
    private static KeyMapping screenScanKey;
    private static final com.dragonmeow.nyanlex.translate.ScreenTranslationCapture SCREEN_CAPTURE =
            new com.dragonmeow.nyanlex.translate.ScreenTranslationCapture();
    private static final com.dragonmeow.nyanlex.translate.ScreenTranslationCapture TOOLTIP_CAPTURE =
            new com.dragonmeow.nyanlex.translate.ScreenTranslationCapture();
    private static net.minecraft.client.gui.screens.Screen screenRefreshRequested;

    private static KeyMapping toggleKey;
    private long actionBarSequence;

    /** One pending rich-text request per live quest-book text widget. Weak keys ensure
     *  closing a quest screen can never retain its widget tree. */
    private static final java.util.Map<Object, String> QUEST_WIDGET_PENDING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final String QUEST_UI_PACKAGE = "dev.ftb.";
    private static final ThreadLocal<Integer> tooltipProbeDepth =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<java.util.ArrayDeque<net.minecraft.client.gui.screens.Screen>>
            SCREEN_RENDER_STACK = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private static final long ITEM_WARM_SCAN_INTERVAL_NANOS = 350_000_000L;
    private net.minecraft.client.gui.screens.Screen lastContainerScreen;
    /** Previous scan's distinct names; replaced after every scan so it stays menu-bounded. */
    private final java.util.Set<String> warmedContainerNames = new java.util.HashSet<>();
    private long nextContainerWarmScanAtNanos;
    /** Previous hotbar/off-hand scan; at most ten distinct names. */
    private final java.util.Set<String> warmedHudNames = new java.util.HashSet<>();
    private long nextHudWarmScanAtNanos;
    /** Throttle for the player-inventory/armor item-entity registration scan (Loadout coverage). */
    private long nextLoadoutScanAtNanos;
    /** Last late tooltip snapshot, including lines appended by other tooltip callbacks. */
    private ItemStack lastTooltipStack;
    private List<String> lastTooltipParagraphSources;
    private net.minecraft.client.gui.screens.Screen lastTooltipScreen;
    private long lastTooltipAtMs;

    /** TAB-listed player names, refreshed once per second on the tick thread; read by the
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
            String name = info == null || info.getProfile() == null
                    ? null : info.getProfile().getName();
            if (name != null && PLAYER_NAME.matcher(name).matches()) names.add(name);
        }
        onlineNames = names;
    }

    private static final java.util.regex.Pattern PLAYER_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final ChatDeliverySession<PendingChat> chatDelivery =
            new ChatDeliverySession<>(pending -> pending.id,
                    pending -> pending.displayedMessage != null, 512);
    private final ChatDeliverySession.BatchBudget announcementBudget =
            new ChatDeliverySession.BatchBudget(BLOCK_MAX_LINES, BLOCK_MAX_CHARS);
    private long nextChatId = 1L;

    private static final class PendingChat {
        final long id;
        final Component message;
        final Object params;
        final long queuedAtNanos = System.nanoTime();
        long epoch;
        DisplayMode mode;
        java.util.function.Supplier<Component> builder;
        boolean framedByServer;  // inside a server ────── announcement frame: skip our magenta wrap
        Component displayedMessage;
        PendingBlock block;
        private final RecoveryAssembly.ResultProgress<java.util.function.Supplier<Component>>
                recoveryProgress = new RecoveryAssembly.ResultProgress<>();

        PendingChat(long id, Component message, Object params) {
            this.id = id;
            this.message = message;
            this.params = params;
        }

        void configureRecovery(int requestCount, boolean recoveryPossible) {
            recoveryProgress.configure(requestCount, recoveryPossible);
        }

        boolean acceptResult(int requestSlot, boolean finalResult) {
            return recoveryProgress.accept(requestSlot, finalResult);
        }

        java.util.function.Supplier<Component> retainBuilder(
                java.util.function.Supplier<Component> candidate) {
            builder = recoveryProgress.retainNonNull(candidate);
            return builder;
        }

        boolean mayReceiveRecovery() { return recoveryProgress.mayReceiveRecovery(); }
    }

    /** How long a chat line may wait for its translation before the original is shown anyway. */
    private static final long CHAT_MAX_WAIT_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long DISPLAYED_CHAT_RETENTION_NANOS = TimeUnit.MINUTES.toNanos(5L);

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
        final DisplayMode mode;
        final List<Component> lines = new ArrayList<>();
        final ChatDeliverySession.BatchBudget budget;
        int reservedItems;
        int reservedChars;
        boolean budgetReleased;
        final long openedAtMs = System.currentTimeMillis();
        List<Integer> paragraphStarts = List.of();
        RecoveryAssembly<List<Component>> recovery;
        boolean closed;
        boolean retired;

        PendingBlock(PendingChat holder, DisplayMode mode,
                     ChatDeliverySession.BatchBudget budget) {
            this.holder = holder;
            this.mode = mode;
            this.budget = budget;
        }

        boolean addLine(Component line) {
            if (line == null) return false;
            int chars = line.getString().length();
            if (!budget.tryReserve(chars)) return false;
            lines.add(line);
            reservedItems++;
            reservedChars += chars;
            return true;
        }

        void addReservedLine(Component line, int chars) {
            lines.add(line);
            reservedItems++;
            reservedChars += chars;
        }

        void releaseBudget() {
            if (budgetReleased) return;
            budgetReleased = true;
            budget.release(reservedItems, reservedChars);
        }
    }

    private PendingBlock activeBlock;
    /** An announcement's lines arrive within a tick or two; a frame still open after
     *  this long is treated as a decorative lone separator and closed as-is. */
    private static final long BLOCK_MAX_OPEN_MS = 3_000L;
    private static final int BLOCK_MAX_LINES = 512;
    private static final int BLOCK_MAX_CHARS = 1_000_000;

    /** Collect framed SYSTEM announcements into one combined message. Returns true if
     *  the line was absorbed into a block (vanilla display must be cancelled). */
    private boolean handleAnnouncementBlock(Component message, Object params,
                                            boolean isSystem, DisplayMode mode, String full) {
        boolean isSep = FabricTextStyle.isSeparatorText(full);
        if (activeBlock == null) {
            int openerChars = full.length();
            if (!isSep || !isSystem || !announcementBudget.tryReserve(openerChars)) return false;
            PendingChat holder = queueChat(message, params);
            holder.mode = DisplayMode.TRANSLATION;      // builder emits the whole block verbatim
            activeBlock = new PendingBlock(holder, mode, announcementBudget);
            holder.block = activeBlock;
            activeBlock.addReservedLine(message, openerChars);
            return true;
        }
        PendingBlock block = activeBlock;
        if (!isSystem || !block.addLine(message)) {
            closeAnnouncementBlock(block);
            return false;
        }
        if (isSep) {
            block.closed = true;
            activeBlock = null;
            translateBlockParagraphs(block);
            return true;
        }
        return true;
    }

    private void closeAnnouncementBlock(PendingBlock block) {
        if (block == null || block.retired) return;
        if (activeBlock == block) activeBlock = null;
        block.closed = true;
        translateBlockParagraphs(block);
    }

    /** Translate a collected frame only after its closing separator has arrived. */
    private void translateBlockParagraphs(PendingBlock block) {
        int first = !block.lines.isEmpty() && FabricTextStyle.isSeparatorText(block.lines.get(0).getString()) ? 1 : 0;
        int end = block.lines.size();
        if (end > first && FabricTextStyle.isSeparatorText(block.lines.get(end - 1).getString())) end--;

        List<String> visible = new ArrayList<>(Math.max(0, end - first));
        List<FabricTextStyle.ChatLinePlan> prepared = new ArrayList<>(Math.max(0, end - first));
        for (int i = first; i < end; i++) {
            FabricTextStyle.ChatLinePlan plan = FabricTextStyle.prepareChatLine(block.lines.get(i));
            prepared.add(plan);
            visible.add(plan.content());
        }
        List<Integer> starts = new ArrayList<>();
        List<List<FabricTextStyle.ChatLinePlan>> groups = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (ParagraphModel.Range range : ParagraphModel.ranges(visible)) {
            if (range.size() == 1 && ParagraphModel.isBlank(visible.get(range.start()))) continue;
            List<FabricTextStyle.ChatLinePlan> plans = new ArrayList<>(range.size());
            List<String> rows = new ArrayList<>(range.size());
            boolean wanted = false;
            for (int row = range.start(); row <= range.end(); row++) {
                FabricTextStyle.ChatLinePlan plan = prepared.get(row);
                plans.add(plan);
                rows.add(plan.request());
                wanted |= !plan.request().isBlank() && service.wantsChatTranslation(plan.content());
            }
            if (!wanted) continue;
            starts.add(first + range.start());
            groups.add(plans);
            requests.add(ParagraphModel.join(rows));
        }

        block.holder.configureRecovery(groups.size(), config.aiChat);
        if (groups.isEmpty()) {
            maybeFinishBlock(block, -1, true, List.of());
            return;
        }
        block.paragraphStarts = List.copyOf(starts);
        block.recovery = new RecoveryAssembly<>(groups.size());
        for (int paragraph = 0; paragraph < groups.size(); paragraph++) {
            int start = starts.get(paragraph);
            List<FabricTextStyle.ChatLinePlan> plans = groups.get(paragraph);
            String request = requests.get(paragraph);
            final int requestSlot = paragraph;
            service.translateChatAsyncDetailed(request, result -> {
                String translated = result.text();
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> {
                    if (!noteChatResultOnClient(mc, block.holder,
                            requestSlot, result.finalResult())) return;
                    List<String> rows = validatedParagraphRows(translated, plans.size());
                    List<Component> paragraphLines = null;
                    if (!rows.isEmpty()) {
                        paragraphLines = new ArrayList<>(plans.size());
                        for (int row = 0; row < plans.size(); row++) {
                            Component rebuilt = FabricTextStyle.rebuildChatLine(plans.get(row), rows.get(row));
                            Component source = block.lines.get(start + row);
                            paragraphLines.add(block.mode == DisplayMode.BOTH
                                    ? new net.minecraft.network.chat.TextComponent("").append(source).append(new net.minecraft.network.chat.TextComponent("\n")).append(rebuilt)
                                    : rebuilt);
                        }
                    }
                    RecoveryAssembly.Update<List<Component>> update = block.recovery.accept(
                            requestSlot,
                            paragraphLines == null ? null : List.copyOf(paragraphLines),
                            result.finalResult());
                    if (update.accepted() && update.ready()) {
                        maybeFinishBlock(block, requestSlot, result.finalResult(), update.values());
                    }
                });
            });
        }
    }

    private void maybeFinishBlock(PendingBlock block, int requestSlot, boolean finalResult,
                                  List<List<Component>> snapshot) {
        if (!block.closed || block.retired) return;
        List<Component> assembledLines = new ArrayList<>(block.lines);
        for (int slot = 0; slot < snapshot.size(); slot++) {
            List<Component> translated = snapshot.get(slot);
            if (translated == null) continue;
            int start = block.paragraphStarts.get(slot);
            for (int row = 0; row < translated.size(); row++) {
                assembledLines.set(start + row, translated.get(row));
            }
        }
        Component assembled = FabricTextStyle.joinStyledLines(assembledLines);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            completeChatOnClient(mc, block.holder.id, block.holder.epoch,
                    DisplayMode.TRANSLATION, () -> assembled, requestSlot, finalResult,
                    requestSlot >= 0);
        }
    }

    /** Close a frame the server never closed (lone decorative separator). */
    private void expireStaleBlock() {
        if (activeBlock != null && System.currentTimeMillis() - activeBlock.openedAtMs > BLOCK_MAX_OPEN_MS) {
            PendingBlock block = activeBlock;
            activeBlock = null;
            block.closed = true;
            translateBlockParagraphs(block);
        }
    }

    /** Track the server's own ────── announcement frames: content between a frame's two
     *  separator lines is already visually boxed, so our magenta wrap is suppressed there.
     *  A frame left open (unpaired decorative separator) expires after 3s. */
    private boolean trackServerFrame(String full) {
        long now = System.currentTimeMillis();
        if (insideServerFrame && now - frameOpenedAtMs > 3_000L) insideServerFrame = false;
        if (FabricTextStyle.isSeparatorText(full)) {
            insideServerFrame = !insideServerFrame;
            frameOpenedAtMs = now;
            return false;
        }
        return insideServerFrame;
    }

    public static TranslationService service() {
        return service;
    }

    /**
     * Translate only Jade's block/entity title. Other overlay providers (mod name,
     * health, icons and numbers) never enter this boundary and remain untouched.
     */
    public static Component jadeObjectName(Component source) {
        TranslationService current = service;
        if (source == null || current == null
                || current.tooltipMode() == DisplayMode.ORIGINAL_ONLY) return source;
        Component translated = FabricTextStyle.renderTranslated(
                "jadeObjectName", source, current::translateItemLine);
        return translated == null ? source : translated;
    }

    /** Advancement toast title, governed by the normal title surface settings. */
    public static Component advancementText(Component source) {
        TranslationService current = service;
        if (source == null || current == null
                || current.titleMode() == DisplayMode.ORIGINAL_ONLY) return source;
        Component translated = FabricTextStyle.renderTranslated(
                "advancementToast", source, current::translateTitle);
        return translated == null ? source : translated;
    }

    public static TranslatorConfig config() {
        return config;
    }

    /** The game's config directory (where every mod file lives). */
    public static Path configDirectory() {
        return configPath.getParent();
    }

    public static HubLocalCache hubLocalCache() {
        return hubLocalCache;
    }

    public static HubDownloader hubDownloader() {
        return hubDownloader;
    }

    public static HubDownloadState hubDownloadState() {
        return hubDownloadState;
    }

    public static HubDownloadJob hubDownloadJob() {
        return hubDownloadJob;
    }

    // ---- All-item warm-up (AI engine only; see com.dragonmeow.nyanlex.warmup) ----

    private static volatile java.util.function.BooleanSupplier aiRateLimitedProbe = () -> false;
    private static com.dragonmeow.nyanlex.warmup.ItemWarmupDriver itemWarmupDriver;

    /** The single per-launch warm-up driver (also keeps the per-launch item budget). */
    public static synchronized com.dragonmeow.nyanlex.warmup.ItemWarmupDriver itemWarmupDriver() {
        if (itemWarmupDriver == null) {
            itemWarmupDriver = new com.dragonmeow.nyanlex.warmup.ItemWarmupDriver(
                    FabricItemWarmupSource.contentSource(),
                    new FabricItemWarmupSource.Backend(() -> aiRateLimitedProbe.getAsBoolean()),
                    NyanLexFabric::config, System::currentTimeMillis);
            itemWarmupDriver.setProgressSaver(NyanLexFabric::saveConfig);
        }
        return itemWarmupDriver;
    }

    /**
     * Public entry point for the settings UI ("全物品預熱…"): opens the progress screen
     * when a run is active, otherwise the scan-and-confirm screen (which explains why
     * it cannot run under machine translation).
     */
    public static void openItemWarmupScreen(net.minecraft.client.gui.screens.Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        var state = itemWarmupDriver().state();
        if (state == com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.RUNNING
                || state == com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.PAUSED) {
            mc.setScreen(new ItemWarmupProgressScreen(parent));
        } else {
            mc.setScreen(new ItemWarmupConfirmScreen(parent));
        }
    }

    /**
     * Build one item's tooltip translation units exactly as the hover path would. Works without
     * a world (player may be null); an item whose tooltip cannot be built (some mods need world
     * data) comes back {@code failed} so the run can skip and count it.
     */
    static com.dragonmeow.nyanlex.warmup.ItemWarmupTarget itemWarmupTarget(Item item, Minecraft mc) {
        ResourceLocation id = net.minecraft.core.Registry.ITEM.getKey(item);
        String itemId = id == null ? "" : id.toString();
        String namespace = id == null ? "" : id.getNamespace();
        int depth = tooltipProbeDepth.get();
        tooltipProbeDepth.set(depth + 1);
        try {
            ItemStack stack = item.getDefaultInstance();
            if (stack.isEmpty()) {
                return new com.dragonmeow.nyanlex.warmup.ItemWarmupTarget(itemId, namespace, List.of());
            }
            List<Component> lines = stack.getTooltipLines(mc.player, TooltipFlag.Default.NORMAL);
            if (lines == null || lines.isEmpty()) {
                return new com.dragonmeow.nyanlex.warmup.ItemWarmupTarget(itemId, namespace, List.of());
            }
            TooltipParagraphPlan plan = tooltipParagraphPlan(
                    stack, lines, FabricTextStyle::paragraphRequestText);
            return new com.dragonmeow.nyanlex.warmup.ItemWarmupTarget(itemId, namespace, plan.sources());
        } catch (RuntimeException | LinkageError e) {
            return com.dragonmeow.nyanlex.warmup.ItemWarmupTarget.failed(itemId, namespace);
        } finally {
            if (depth == 0) tooltipProbeDepth.remove();
            else tooltipProbeDepth.set(depth);
        }
    }

    private static com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State warmupPrevState =
            com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.IDLE;
    private static volatile long warmupDoneAtMs = -1L;

    /** Milliseconds since the warm-up last finished, or -1 when it has not finished this launch. */
    public static long warmupMsSinceDone() {
        long at = warmupDoneAtMs;
        return at < 0 ? -1L : System.currentTimeMillis() - at;
    }

    /** Mod version for the settings "About" card. */
    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    /** Remembers state changes for the HUD ("done" timer) and tells the player when a paused run continues. */
    private static void trackWarmupTransitions(com.dragonmeow.nyanlex.warmup.ItemWarmupDriver driver) {
        var progress = driver.progress();
        var state = progress.state();
        if (state == com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.DONE
                && warmupPrevState != com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.DONE) {
            warmupDoneAtMs = System.currentTimeMillis();
        }
        warmupPrevState = state;
    }

    /**
     * Per client tick: drive the warm-up. It never starts or restarts a run by itself (not at
     * launch, not when a world loads, not after a run ended): only the player's Start / Continue
     * button does, through the confirm screen.
     */
    private static void tickItemWarmup(Minecraft mc) {
        if (config == null || service == null) return;
        var driver = itemWarmupDriver();
        driver.tick();
        trackWarmupTransitions(driver);
    }

    /** Spawns background hub work (identify/plan/download) as a daemon thread; also
     *  usable directly as the {@link Executor} {@link HubDownloadJob#start} wants. */
    public static Executor hubExecutor() {
        return NyanLexFabric::runHubBackground;
    }

    private static void runHubBackground(Runnable task) {
        Thread thread = new Thread(task, MOD_ID + "-hub");
        thread.setDaemon(true);
        thread.start();
    }

    private static List<String> loadedModNames() {
        List<String> names = new ArrayList<>();
        for (net.fabricmc.loader.api.ModContainer container : FabricLoader.getInstance().getAllMods()) {
            names.add(container.getMetadata().getName());
            names.add(container.getMetadata().getId());
        }
        return names;
    }

    private static List<String> loadedModIds() {
        List<String> ids = new ArrayList<>();
        for (net.fabricmc.loader.api.ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ids.add(container.getMetadata().getId());
        }
        return ids;
    }

    private static List<Path> hubCandidateRoots() {
        List<Path> roots = new ArrayList<>();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        roots.add(gameDir);
        Path parent = gameDir.getParent();
        if (parent != null) {
            roots.add(parent);
            Path grandparent = parent.getParent();
            if (grandparent != null) roots.add(grandparent);
        }
        return roots;
    }

    /** Realms detection via reflection: the {@code isRealm()} accessor's presence/shape
     *  on {@code ServerData} has shifted across the Minecraft versions this mod ports
     *  to, so this avoids a hard compile-time dependency on any one of them. Any failure
     *  (method missing, etc.) conservatively reports "not a Realm". */
    private static boolean isRealmServer(net.minecraft.client.multiplayer.ServerData serverData) {
        try {
            Object result = serverData.getClass().getMethod("isRealm").invoke(serverData);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Runs the identify+plan step (current server, modpack, installed mods -> one
     * {@code index.json} fetch) on a background thread, then opens
     * {@link HubDownloadConfirmScreen} on the render thread. If a download is already
     * running, skips straight to {@link HubDownloadProgressScreen} instead -- this is
     * the "識別當前伺服器/MOD下載並匯入翻譯檔" button's click handler.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean HUB_PLANNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** True while a hub identify/plan pass runs in the background (settings UI disables its button). */
    public static boolean hubPlanning() {
        return HUB_PLANNING.get();
    }

    private static Runnable clearPlanningOnCrash(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                HUB_PLANNING.set(false);
                throw e;
            }
        };
    }

    public static void startHubIdentifyAndPlan(net.minecraft.client.gui.screens.Screen hubScreen) {
        if (hubDownloader == null) return;
        if (hubDownloadJob.isRunning()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(new HubDownloadProgressScreen(hubScreen));
            return;
        }
        if (!HUB_PLANNING.compareAndSet(false, true)) return; // a plan is already being built: ignore double clicks
        runHubBackground(clearPlanningOnCrash(() -> {
            String host = null;
            String modpackLabel = null;
            List<String> modIds = loadedModIds();
            HubPlan plan = null;
            String error = null;
            try {
                Minecraft mcOffThread = Minecraft.getInstance();
                net.minecraft.client.multiplayer.ServerData serverData =
                        mcOffThread == null ? null : mcOffThread.getCurrentServer();
                if (serverData != null && !isRealmServer(serverData)) {
                    host = ServerHostNormalizer.normalize(serverData.ip).orElse(null);
                }
                Optional<ModpackIdentity> modpack =
                        ModpackDetector.detect(hubCandidateRoots(), modIds, null);
                modpackLabel = modpack.map(m -> m.displayName() != null ? m.displayName() : m.slug())
                        .orElse(null);
                plan = hubDownloader.plan(host, modpack.orElse(null), modIds, config.targetLang, hubDownloadState);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String finalHost = host;
            final String finalModpackLabel = modpackLabel;
            final int finalModCount = modIds.size();
            final HubPlan finalPlan = plan;
            final String finalError = error;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                HUB_PLANNING.set(false);
                return;
            }
            mc.execute(() -> {
                HUB_PLANNING.set(false);
                if (finalPlan == null) {
                    toast(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hub.toast_title"),
                            new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hub.unreachable"));
                    return;
                }
                if (!finalPlan.hasAnyContent()) {
                    toast(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hub.toast_title"),
                            new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hub.no_packs"));
                    return;
                }
                mc.setScreen(new HubDownloadConfirmScreen(hubScreen, finalPlan, finalHost,
                        finalModpackLabel, finalModCount));
            });
        }));
    }

    /** Fired on every {@link HubDownloadJob} state change; only terminal states matter
     *  here (progress ticks are read live by whichever screen is open, if any). */
    private static void onHubDownloadJobChanged(HubDownloadJob job) {
        HubDownloadJob.State state = job.state();
        // A6: CANCELLED must invalidate the render memo too — HubDownloader#download keeps
        // every file already merged before the cancellation point, so a cancelled job can
        // still have added rows a currently-open screen already rendered without.
        if (state != HubDownloadJob.State.DONE && state != HubDownloadJob.State.FAILED
                && state != HubDownloadJob.State.CANCELLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            FabricTextStyle.clearRenderMemo();
            if (state == HubDownloadJob.State.DONE) {
                var result = job.result();
                if (result != null) {
                    toast(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hub.download_done",
                            result.added(), hubLocalCache.activeFile().toAbsolutePath().toString()));
                }
            } else if (state == HubDownloadJob.State.FAILED) {
                String reason = job.failureMessage();
                toast(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hub.download_failed",
                        reason == null ? "" : reason));
            }
        });
    }

        /** Once per launch, the first time the title screen appears: carry any
     *  saved pre-rename ("mctranslator") keybinding over to its "nyanlex"
     *  equivalent, when the player has not already set (or had migrated)
     *  the new one. */
    private void maybeMigrateKeybinds(Minecraft mc) {
        if (keybindMigrationChecked || mc == null || mc.screen == null) return;
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
        keybindMigrationChecked = true;
        java.util.Map<String, KeyMapping> bySuffix = new java.util.LinkedHashMap<>();
        if (modeKey != null) bySuffix.put("mode", modeKey);
        if (retranslateKey != null) bySuffix.put("retranslate", retranslateKey);
        if (screenScanKey != null) bySuffix.put("screenscan", screenScanKey);
        if (toggleKey != null) bySuffix.put("toggle", toggleKey);
        if (bySuffix.isEmpty()) return;
        java.nio.file.Path optionsTxt = FabricLoader.getInstance().getGameDir().resolve("options.txt");
        java.util.Map<String, String> legacy =
                KeybindMigration.findUnmigratedBindings(optionsTxt, bySuffix.keySet());
        if (legacy.isEmpty()) return;
        boolean changed = false;
        for (java.util.Map.Entry<String, String> e : legacy.entrySet()) {
            KeyMapping mapping = bySuffix.get(e.getKey());
            if (mapping == null) continue;
            try {
                mc.options.setKey(mapping, InputConstants.getKey(e.getValue()));
                changed = true;
            } catch (RuntimeException ignored) {
                // Malformed/unknown stored key name: leave the default binding alone.
            }
        }
        if (changed) {
            KeyMapping.resetMapping();
            mc.options.save();
            LOGGER.info("[{}] migrated {} legacy keybind(s) from mctranslator options", MOD_ID, legacy.size());
        }
    }


    // ---- translation packs found for the installed mods (快速設定 and 翻譯包)

    /** State of the background pack check started by the questionnaire. */
    public static com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState packState() {
        return packState;
    }

    /** The mods that have a pack worth downloading, as one text line each, with the total size. */
    public static com.dragonmeow.nyanlex.config.QuickSetupPanel.PackInfo packInfo() {
        HubPlan plan = packPlan;
        if (plan == null) return new com.dragonmeow.nyanlex.config.QuickSetupPanel.PackInfo(0, "", List.of());
        List<String> lines = new ArrayList<>();
        for (var item : plan.downloadable()) {
            lines.add(item.label() + "\u3000" + HubDownloadConfirmScreen.formatBytes(item.bytes()));
        }
        return new com.dragonmeow.nyanlex.config.QuickSetupPanel.PackInfo(lines.size(),
                HubDownloadConfirmScreen.formatBytes(plan.totalDownloadBytes()), lines);
    }

    /**
     * Looks, in the background, for translation packs for the installed mods. This only reads the
     * public pack index (a plain GET); no player text and no list of mods is sent anywhere.
     */
    public static void startPackDetection() {
        if (hubDownloader == null || config == null
                || packState == com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState.DETECTING) return;
        packState = com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState.DETECTING;
        packPlan = null;
        runHubBackground(() -> {
            try {
                List<String> modIds = loadedModIds();
                Optional<ModpackIdentity> modpack = ModpackDetector.detect(hubCandidateRoots(), modIds, null);
                HubPlan plan = hubDownloader.planStartupMods(false, modpack.orElse(null), modIds,
                        config.targetLang, hubDownloadState);
                if (plan.downloadable().isEmpty()) {
                    packState = com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState.NONE;
                } else {
                    packPlan = plan;
                    packState = com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState.FOUND;
                }
            } catch (IOException | RuntimeException e) {
                packState = com.dragonmeow.nyanlex.config.QuickSetupPanel.PackState.FAILED;
            }
        });
    }

    /** Downloads the packs found by {@link #startPackDetection()} in the background. */
    public static void startPackDownload() {
        HubPlan plan = packPlan;
        if (plan == null || hubDownloader == null || hubDownloadJob.isRunning()) return;
        hubDownloadJob.start(plan, hubDownloader, hubLocalCache, hubDownloadState, hubExecutor());
    }

    public static boolean packDownloading() {
        return hubDownloadJob.isRunning();
    }

    /** An API key or authenticated local account provider makes the AI service usable. */
    public static boolean aiConfigured() {
        if (config == null) return false;
        if (config.usesCodex()) {
            return codexClient != null && codexClient.isSignedInCached();
        }
        if (config.usesAntigravity()) {
            return antigravityClient != null && antigravityClient.isInstalledCached();
        }
        if (config.aiApiKeys == null) return false;
        for (String key : config.aiApiKeys) if (key != null && !key.isBlank()) return true;
        return false;
    }

    /** The settings file (holds the API keys), for the AI settings notice. */
    public static Path configFilePath() {
        return configPath;
    }

    /**
     * Once, on the title screen of a fresh install: the 快速設定 questionnaire. It is the only
     * window that ever opens by itself, and only on the first start.
     */
    private void maybeStartFirstRun(Minecraft mc) {
        if (firstStartTried || mc == null || config == null || mc.getOverlay() != null) return;
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
        firstStartTried = true;
        if (!com.dragonmeow.nyanlex.config.QuickSetupPanel.firstStartDue(config)) return;
        mc.setScreen(new QuickSetupScreen(mc.screen));
    }

    public static CodexAppServerClient codexClient() {
        return codexClient;
    }

    public static AntigravityCliClient antigravityClient() {
        return antigravityClient;
    }

    public static SessionTokenUsage.Snapshot tokenUsageSnapshot() {
        return tokenUsage.snapshot();
    }

    public static TranslationDebugLog debugLog() { return debugLog; }
    public static void clearDebugLog() { if (debugLog != null) debugLog.clear(); }

    public static KeyMapping modeKeyMapping() {
        return modeKey;
    }

    public static KeyMapping retranslateKeyMapping() {
        return retranslateKey;
    }

    public static KeyMapping screenScanKeyMapping() {
        return screenScanKey;
    }

    public static KeyMapping toggleKeyMapping() {
        return toggleKey;
    }

    public static void saveConfig() {
        if (config != null && configPath != null) {
            config.save(configPath);
        }
        FabricTextStyle.clearRenderMemo();
    }

    /** Forget pending quest widget requests so live quest text is requested again after the
     *  request switch or the do-not-translate terms change. */
    public static void clearQuestWidgetPending() {
        synchronized (QUEST_WIDGET_PENDING) { QUEST_WIDGET_PENDING.clear(); }
    }

    // ---- GUI-text translation helpers (called by the shared mixins) ----

    public static Component screenText(Component c) {
        if (com.dragonmeow.nyanlex.translate.InternalRenderGuard.active()) return c;
        if (c != null && captureScreenText(c)) return c;
        TranslationService s = service;
        if (s == null || c == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return c;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return c;
        if (!s.wantsScreenTextTranslation(c.getString())) return c;
        Component t = FabricTextStyle.renderTranslated("screenText", c, s::translateScreenText);
        return t != null ? t : c;
    }

    /** Translate an optional quest-book text widget before it measures and wraps. */
    public static Component questWidgetText(Object widget, Component source) {
        if (com.dragonmeow.nyanlex.translate.InternalRenderGuard.active()) return source;
        TranslationService s = service;
        if (widget == null || source == null || s == null) return source;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc) && !questWidgetOnCurrentScreen(widget, mc)) return source;
        if (captureScreenText(source, true)) return source;
        if (s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return source;
        Component resolved = FabricTextStyle.resolveLegacyCodes(source);
        Component rendered = FabricTextStyle.renderTranslated("questText", resolved, s::translateScreenText);
        if (rendered != null) {
            QUEST_WIDGET_PENDING.remove(widget);
            return rendered;
        }
        List<String> requests = FabricTextStyle.requestLines(resolved).stream()
                .filter(s::wantsScreenTextTranslation).toList();
        if (requests.isEmpty()) return source;
        String request = String.join("\u0000", requests);
        boolean submit;
        synchronized (QUEST_WIDGET_PENDING) {
            submit = !request.equals(QUEST_WIDGET_PENDING.get(widget));
            if (submit) QUEST_WIDGET_PENDING.put(widget, request);
        }
        if (submit) for (String lineRequest : requests) {
            s.requestLiveScreenTextAsync(lineRequest, translated -> {
                synchronized (QUEST_WIDGET_PENDING) {
                    if (!request.equals(QUEST_WIDGET_PENDING.get(widget))) return;
                }
                Component ready = FabricTextStyle.renderTranslated(
                        "questText", resolved, s::translateScreenText);
                if (ready == null) return;
                Minecraft client = Minecraft.getInstance();
                if (client != null) {
                    client.execute(() -> {
                            synchronized (QUEST_WIDGET_PENDING) {
                                if (!request.equals(QUEST_WIDGET_PENDING.get(widget))) return;
                                QUEST_WIDGET_PENDING.remove(widget);
                            }
                            if (questWidgetOnCurrentScreen(widget, client)) refreshCurrentQuestScreen();
                        });
                }
            });
        }
        return source;
    }

    /** The quest widget populates its content while the new screen is being initialized,
     * before the first Render.Pre event. Accept that call only when the widget's own
     * GUI is the screen wrapped by Minecraft's current screen;
     * unrelated/background widgets remain outside the translation scope. */
    private static boolean questWidgetOnCurrentScreen(Object widget, Minecraft mc) {
        if (widget == null || mc == null || mc.screen == null) return false;
        try {
            java.lang.reflect.Method getter = widget.getClass().getMethod("getGui");
            Object widgetGui = getter.invoke(widget);
            if (widgetGui == mc.screen) return true;
            java.lang.reflect.Method screenGetter = mc.screen.getClass().getMethod("getGui");
            return screenGetter.invoke(mc.screen) == widgetGui;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static String screenText(String str) {
        if (com.dragonmeow.nyanlex.translate.InternalRenderGuard.active()) return str;
        if (str != null && captureScreenText(new net.minecraft.network.chat.TextComponent(str))) return str;
        TranslationService s = service;
        if (s == null || str == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return str;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return str;
        if (str.indexOf('\n') >= 0 || str.indexOf('\r') >= 0) {
            String normalized = str.replace("\r\n", "\n").replace('\r', '\n');
            Component translated = FabricTextStyle.renderTranslated(
                    "screenText", new net.minecraft.network.chat.TextComponent(normalized), s::translateScreenText);
            return translated != null ? translated.getString() : str;
        }
        TranslationDecision d = s.translateScreenText(str);
        return d.changed() ? d.translated() : str;
    }

    /** Interface text that is about to be cut to a width by the vanilla trim helper; see
     *  {@link com.dragonmeow.nyanlex.translate.TrimTranslation}. */
    public static String screenTextBeforeTrim(String str) {
        return com.dragonmeow.nyanlex.translate.TrimTranslation.resolve(
                str, NyanLexFabric::screenText, NyanLexFabric::trimCallerIsTextInput);
    }

    private static final com.dragonmeow.nyanlex.translate.TrimTranslation.TextInputGate TRIM_INPUT_GATE =
            new com.dragonmeow.nyanlex.translate.TrimTranslation.TextInputGate();

    /** True when the trim was requested by a text input (edit box / multi-line field), whose
     *  contents must never be replaced. Cheap on the common screen with no text input at all;
     *  the stack is walked only when the open screen owns one. */
    private static boolean trimCallerIsTextInput() {
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
        if (screen == null || !TRIM_INPUT_GATE.hasTextInput(screen, screen.children())) return false;
        return com.dragonmeow.nyanlex.translate.TrimTranslation.callerIsTextInput(
                c -> c == NyanLexFabric.class || c == net.minecraft.client.gui.Font.class);
    }

    public static net.minecraft.util.FormattedCharSequence screenText(net.minecraft.util.FormattedCharSequence fcs) {
        if (com.dragonmeow.nyanlex.translate.InternalRenderGuard.active()) return fcs;
        if (fcs != null && Minecraft.getInstance().screen != null
                && Minecraft.getInstance().screen.getClass().getName().startsWith(QUEST_UI_PACKAGE)) return fcs;
        if (fcs != null && captureScreenText(FabricTextStyle.toComponent(fcs))) return fcs;
        TranslationService s = service;
        if (s == null || fcs == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return fcs;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return fcs;
        if (mc.screen.getClass().getName().startsWith(QUEST_UI_PACKAGE)) return fcs;
        Component source = FabricTextStyle.toComponent(fcs);
        Component styled = FabricTextStyle.renderTranslated("screenTextFcs", source, s::translateScreenText);
        if (styled == null) return fcs;
        Font font = mc.font;
        if (font != null) {
            int originalWidth = font.width(fcs);
            int budget = Math.max(originalWidth + 24, (int) (originalWidth * 1.25f));
            if (font.width(styled) > budget) return fcs;
        }
        return styled.getVisualOrderText();
    }

    public static net.minecraft.network.chat.FormattedText screenText(net.minecraft.network.chat.FormattedText text) {
        if (com.dragonmeow.nyanlex.translate.InternalRenderGuard.active()) return text;
        if (text != null && captureScreenText(FabricTextStyle.toComponent(text))) return text;
        TranslationService s = service;
        if (s == null || text == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return text;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return text;
        if (mc.screen.getClass().getName().startsWith(QUEST_UI_PACKAGE)) return text;
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen) return text;
        Component source = FabricTextStyle.toComponent(text);
        Component translated = FabricTextStyle.renderTranslated("screenTextBlock", source, s::translateScreenText);
        return translated == null ? text : translated;
    }

    private static boolean renderingCurrentScreen(Minecraft mc) {
        return mc != null && mc.screen != null
                && screenTranslationAllowed(mc.screen)
                && SCREEN_RENDER_STACK.get().peek() == mc.screen;
    }

    private static boolean screenTranslationAllowed(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null
                || screen.getClass().getName().startsWith("com.dragonmeow.nyanlex.")) return false;
        Component title = screen.getTitle();
        String key = title instanceof net.minecraft.network.chat.TranslatableComponent translatable
                ? translatable.getKey() : null;
        return com.dragonmeow.nyanlex.translate.ScreenTranslationPolicy.allowsTranslation(key);
    }

    /**
     * Name-tag entry with the renderer's entity: a REAL online player — one the TAB player
     * list shows, i.e. in {@code getListedOnlinePlayers()} — keeps the ORIGINAL name tag
     * (player IDs are names, not text; "最偉大的迪加" must never happen). NPCs still
     * translate: Hypixel-style fake players have a profile but are NOT listed, so the
     * LISTED set is the discriminator (never {@code getPlayerInfo != null}). Only the
     * nameTag surface is guarded — chat keeps its NameMasker, scoreboards are untouched.
     */
    public static Component nameTag(net.minecraft.world.entity.Entity entity, Component c) {
        if (c == null) return null;
        // Real-player guard runs BEFORE any memo/cache (translateNameTag holds the memo):
        // (a) the entity is a TAB-listed Player, OR (b) — Hypixel renders player name tags
        // via invisible ArmorStand/TextDisplay entities, which are NOT Player, so the
        // entity check alone is blind there — the tag TEXT contains any listed player's
        // name as a whole token. Either way the tag stays verbatim.
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            return c; // real player: no cache lookup, no request, verbatim tag
        }
        if (nameTagMatchesListedPlayer(c.getString())) return c;
        return translateNameTag(c);
    }

    /** Entity-less name-tag entry: string fallback — a tag containing any LISTED player's
     *  name as a whole token is a real player's tag and stays verbatim. */
    public static Component nameTag(Component c) {
        return nameTag(null, c);
    }

    private static Component translateNameTag(Component c) {
        TranslationService s = service;
        if (s == null || c == null) return c;
        Component t = FabricTextStyle.renderTranslated("nameTag", c, s::translateUi);
        return t != null ? t : c;
    }

    private static final java.util.Map<String, Boolean> NAME_TAG_MEMO = new java.util.HashMap<>();
    private static final java.util.List<String> NAME_TAG_MEMO_ROSTER = new java.util.ArrayList<>();

    /** Whole-token match (name chars = [A-Za-z0-9_], so "Steve" never matches inside
     *  "Steves") of any LISTED player name inside a name tag's plain text. */
    private static boolean nameTagMatchesListedPlayer(String plain) {
        if (plain == null || plain.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientPacketListener conn =
                (mc == null) ? null : mc.getConnection();
        if (conn == null) return false;
        // Memo is valid only while the live roster's names are unchanged (same size, same
        // names in order); any drift rebuilds the snapshot and drops every memoised answer.
        java.util.Collection<net.minecraft.client.multiplayer.PlayerInfo> roster = conn.getOnlinePlayers();
        boolean rosterChanged = roster.size() != NAME_TAG_MEMO_ROSTER.size();
        if (!rosterChanged) {
            int i = 0;
            for (net.minecraft.client.multiplayer.PlayerInfo info : roster) {
                String name = (info == null || info.getProfile() == null) ? null : info.getProfile().getName();
                if (!java.util.Objects.equals(name, NAME_TAG_MEMO_ROSTER.get(i++))) {
                    rosterChanged = true;
                    break;
                }
            }
        }
        if (rosterChanged) {
            NAME_TAG_MEMO_ROSTER.clear();
            for (net.minecraft.client.multiplayer.PlayerInfo info : roster) {
                NAME_TAG_MEMO_ROSTER.add((info == null || info.getProfile() == null) ? null : info.getProfile().getName());
            }
            NAME_TAG_MEMO.clear();
        }
        Boolean hit = NAME_TAG_MEMO.get(plain);
        if (hit != null) return hit;
        boolean result = nameTagMatchesListedPlayerUncached(plain, conn);
        if (NAME_TAG_MEMO.size() >= 2048) NAME_TAG_MEMO.clear();
        NAME_TAG_MEMO.put(plain, result);
        return result;
    }

    private static boolean nameTagMatchesListedPlayerUncached(
            String plain, net.minecraft.client.multiplayer.ClientPacketListener conn) {
        for (net.minecraft.client.multiplayer.PlayerInfo info : conn.getOnlinePlayers()) {
            String name = (info == null || info.getProfile() == null) ? null : info.getProfile().getName();
            if (name == null || name.isEmpty()) continue;
            int at = plain.indexOf(name);
            while (at >= 0) {
                boolean leftEdge = at == 0 || !isNameTokenChar(plain.charAt(at - 1));
                int end = at + name.length();
                boolean rightEdge = end >= plain.length() || !isNameTokenChar(plain.charAt(end));
                if (leftEdge && rightEdge) return true;
                at = plain.indexOf(name, at + 1);
            }
        }
        return false;
    }

    private static boolean isNameTokenChar(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
    }

    @Override
    public void onInitializeClient() {
        NyanLexHooks.register(LOGGER::info, LOGGER::warn);
        instance = this;
        configPath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
        LegacyDataMigration.migrate(configPath.getParent(), LOGGER::info);
        config = TranslatorConfig.load(configPath);

        int workers = Math.max(1, config.workerThreads);
        java.util.concurrent.ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "nyanlex-worker");
            t.setDaemon(true);
            return t;
        };
        // LIFO work queue so the screen/interface in view RIGHT NOW is translated first.
        ExecutorService executor = new com.dragonmeow.nyanlex.translate.PriorityTranslationExecutor(
                workers, threadFactory);

        transport = new UrlHttpTransport(Duration.ofMillis(config.httpTimeoutMs));
        RequestPacer machinePacer = new RequestPacer(() -> config.requestCooldownMs);
        SwitchingMachineTranslator google = new SwitchingMachineTranslator(
                transport, () -> config.sourceLang, () -> config.machineTranslationProvider,
                machinePacer);
        OpenAiTranslator apiAi = new OpenAiTranslator(transport,
                () -> new AiSettings(config.aiBaseUrl, config.aiModel, config.aiApiKeys, config.aiGlossary),
                new RequestPacer(() -> config.requestCooldownMs));
        apiAi.setTokenUsage(tokenUsage);
        Path codexRoot = configPath.getParent();
        codexClient = new CodexAppServerClient(
                codexRoot.resolve(MOD_ID + "-codex-home"),
                codexRoot.resolve(MOD_ID + "-codex-workspace"));
        codexClient.setTokenUsage(tokenUsage);
        codexClient.setRequestCooldown(() -> config.requestCooldownMs);
        // Spawn + initialize app-server in the background when Codex mode is the active
        // engine, so the first translation does not wait for process start.
        if (config.usesCodex()) codexClient.warmUpAsync();
        codexTransport = new CodexAppServerTransport(codexClient,
                () -> config.codexReasoningEffort);
        OpenAiTranslator codexAi = new OpenAiTranslator(codexTransport,
                () -> new AiSettings("codex://app-server", config.codexModel,
                        java.util.Collections.emptyList(), config.aiGlossary),
                RequestPacer.disabled());
        antigravityClient = new AntigravityCliClient(
                codexRoot.resolve(MOD_ID + "-antigravity-workspace"));
        antigravityClient.setTokenUsage(tokenUsage);
        antigravityClient.setRequestCooldown(() -> config.requestCooldownMs);
        if (config.usesAntigravity()) {
            antigravityClient.warmUpAsync(config.antigravityModel);
        }
        antigravityTransport = new AntigravityCliTransport(antigravityClient,
                () -> config.antigravityModel);
        OpenAiTranslator antigravityAi = new OpenAiTranslator(antigravityTransport,
                () -> new AiSettings("antigravity://cli",
                        config.antigravityModel == null || config.antigravityModel.isBlank()
                                ? "default" : config.antigravityModel,
                        java.util.Collections.emptyList(), config.aiGlossary),
                RequestPacer.disabled());
        // 偵錯模式: the one local error log (newest 1000 entries, API keys masked). It is written only
        // when something goes wrong while the mode is on; nothing else is ever dumped.
        com.dragonmeow.nyanlex.translate.DebugErrorLog errorLog = new com.dragonmeow.nyanlex.translate.DebugErrorLog(
                configPath.getParent().resolve(com.dragonmeow.nyanlex.config.FileLocations.DEBUG_LOG_FILE),
                () -> config != null && config.debugTranslationOverlay,
                com.dragonmeow.nyanlex.translate.DebugErrorLog.DEFAULT_MAX_ENTRIES,
                () -> config == null ? java.util.List.of() : config.secretValues());
        com.dragonmeow.nyanlex.translate.DebugErrorLog.install(errorLog);
        apiAi.setExchangeDumpSink(errorLog.exchangeSink());
        codexAi.setExchangeDumpSink(errorLog.exchangeSink());
        antigravityAi.setExchangeDumpSink(errorLog.exchangeSink());
        SwitchingAiTranslator ai = new SwitchingAiTranslator(
                apiAi, codexAi, antigravityAi, () -> config.aiProvider);
        aiRateLimitedProbe = ai::isRateLimited;
        // The hook below can run after the mod class loader is closed (NeoForge); load what it needs now.
        CodexAppServerClient.preloadForShutdown();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CodexAppServerClient client = codexClient;
            if (client != null) client.close();
            AntigravityCliClient googleClient = antigravityClient;
            if (googleClient != null) googleClient.close();
        }, "nyanlex-codex-shutdown"));
        PersistentStore googleStore = new ProviderLanguageFileStore(
                FabricLoader.getInstance().getConfigDir(), MOD_ID + "-cache", config.targetLang,
                () -> config.machineTranslationProvider, config.persistentCacheMaxEntries);
        PersistentStore aiStore = new LanguageFileStore(
                FabricLoader.getInstance().getConfigDir(), MOD_ID + "-ai-cache", config.targetLang,
                config.persistentCacheMaxEntries);
        PersistentStore failureStore = new LanguageFileStore(
                FabricLoader.getInstance().getConfigDir(), MOD_ID + "-failures", config.targetLang,
                config.persistentCacheMaxEntries);
        TranslationCache cache = new TranslationCache(google, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, googleStore);
        TranslationCache aiCache = new TranslationCache(ai, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, aiStore);
        cache.setFailureStore(new DynamicNamespacedStore(failureStore, () -> {
            String provider = MachineTranslationProvider.normalize(config.machineTranslationProvider);
            return MachineTranslationProvider.GOOGLE.id().equals(provider)
                    ? "gt" : "gt-" + provider;
        }));
        aiCache.setFailureStore(new NamespacedStore(failureStore, "ai"));
        aiCache.setProvisionalStore(googleStore);
        debugLog = new TranslationDebugLog(() -> config != null && config.debugTranslationOverlay);
        cache.setDebugLog("Google", debugLog);
        aiCache.setDebugLog("AI", debugLog); // migrate legacy dispatcher stand-ins once
        aiCache.setProvisionalRetryGate(() ->
                (config.usesCodex()
                        ? codexClient != null && codexClient.isSignedInCached()
                                && config.codexModel != null && !config.codexModel.isBlank()
                        : config.usesAntigravity()
                                ? antigravityClient != null && antigravityClient.isInstalledCached()
                        : config.aiApiKeys != null && !config.aiApiKeys.isEmpty())
                        && !ai.isRateLimited());
        service = new TranslationService(config, cache, aiCache);
        outgoingChat = new com.dragonmeow.nyanlex.service.OutgoingChatTranslator(config,
                new com.dragonmeow.nyanlex.translate.GoogleFreeTranslator(transport, "auto",
                        machinePacer, com.dragonmeow.nyanlex.translate.MachineTranslationGate.shared()),
                ai, executor, () -> onlineNames);
        com.dragonmeow.nyanlex.translate.MachineGateGuard.install(com.dragonmeow.nyanlex.translate.MachineTranslationGate.shared(), GATE_FEEDBACK);
        // 2026-10-02: manual (cache-only, translate-key-driven) item/screen-text mode is
        // no longer a global startup flag -- TranslationService now judges it live, per
        // surface, from that surface's CURRENTLY CONFIGURED engine (config.aiTooltip /
        // config.aiScreenText): the AI engine auto-translates (the pre-1.0.8 behaviour),
        // the machine-translation engine is cache-only. See
        // TranslationService#isManualItemTranslation()/#isManualScreenTranslation().
        service.setInfoLog(LOGGER::info);
        service.setTargetLangChangeListener(this::onTargetLanguageChanged);
        service.setBatchWindowMs(() -> config.batchWindowMs);
        service.setItemSourceLanguage(() -> {
            if (config.sourceLang != null && !config.sourceLang.isBlank()
                    && !"auto".equalsIgnoreCase(config.sourceLang)) return config.sourceLang;
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.options == null ? null : mc.options.languageCode;
        });
        service.setProtectedNames(() -> onlineNames);
        service.setKnownNames(loadedModNames());

        Path hubDir = FabricLoader.getInstance().getConfigDir();
        hubLocalCache = new HubLocalCache(hubDir, config.targetLang);
        hubDownloadState = new HubDownloadState(hubDir.resolve(HubPaths.stateFileName()));
        hubDownloader = new HubDownloader(new HubRepository(
                new UrlHttpTransport(Duration.ofSeconds(8), HubPaths.downloadResponseByteCap())));
        service.setHubLookup(hubLocalCache::get);
        hubDownloadJob.addListener(NyanLexFabric::onHubDownloadJobChanged);

        registerKeyBinds();
        registerCommands();
        registerEvents();

        LOGGER.info("[{}] (Fabric) initialized (target={}, chat={}, tooltip={})",
                MOD_ID, config.targetLang, config.chatMode, config.tooltipMode);
    }

    private void registerKeyBinds() {
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nyanlex.mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.nyanlex"));
        retranslateKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nyanlex.retranslate", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.nyanlex"));
        screenScanKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nyanlex.screenscan", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.nyanlex"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nyanlex.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.nyanlex"));
    }

    /** Flip the master 原文/翻譯 switch, bust the render memo so persistent surfaces flip at once, and report. */
    private void flipShowOriginal() {
        if (service == null) return;
        boolean originalsNow = service.toggleShowOriginal();
        FabricTextStyle.clearRenderMemo();
        if (originalsNow) flushPendingChatOriginals();
        synchronized (QUEST_WIDGET_PENDING) { QUEST_WIDGET_PENDING.clear(); }
        refreshCurrentQuestScreen();
        feedback(new net.minecraft.network.chat.TranslatableComponent(originalsNow ? "message.nyanlex.show_original" : "message.nyanlex.show_translation"));
    }

    /** When 跟隨遊戲 is on, keep the translation target language synced to Minecraft's own (繁/簡). */
    private void syncGameLanguage(net.minecraft.client.Minecraft mc) {
        if (service == null || config == null || !config.followGameLanguage || mc == null || mc.options == null) return;
        String desired = mapGameLang(mc.options.languageCode);
        if (!desired.equals(config.targetLang)) {
            service.setTargetLang(desired);
        }
    }

    private void onTargetLanguageChanged() {
        FabricTextStyle.clearRenderMemo();
        if (hubLocalCache != null) hubLocalCache.setLanguage(config.targetLang);
        lastContainerScreen = null;
        warmedContainerNames.clear();
        nextContainerWarmScanAtNanos = 0L;
        warmedHudNames.clear();
        nextHudWarmScanAtNanos = 0L;
        nextLoadoutScanAtNanos = 0L;
        lastTooltipStack = null;
        lastTooltipParagraphSources = null;
        lastTooltipScreen = null;
        lastTooltipAtMs = 0L;
        synchronized (QUEST_WIDGET_PENDING) { QUEST_WIDGET_PENDING.clear(); }
        refreshCurrentQuestScreen();
    }

    private static void refreshCurrentQuestScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null) return;
        try {
            Object gui = mc.screen.getClass().getMethod("getGui").invoke(mc.screen);
            if (gui != null) gui.getClass().getMethod("refreshWidgets").invoke(gui);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    /** Map Minecraft's language code (zh_cn / zh_tw / en_us …) to 繁/簡 target. */
    static String mapGameLang(String gameLang) {
        return com.dragonmeow.nyanlex.config.TranslationLanguages.fromMinecraftCode(gameLang);
    }

    private void registerEvents() {
        ResourceLocation tooltipPhase = ResourceLocation.tryParse(MOD_ID + ":tooltip_translation");
        ItemTooltipCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, tooltipPhase);
        ItemTooltipCallback.EVENT.register(tooltipPhase,
                (stack, type, lines) -> HookGuard.run("event.itemTooltip", () -> onItemTooltip(stack, lines)));

        ClientTickEvents.END_CLIENT_TICK.register(
                tickClient -> HookGuard.run("event.clientTick", () -> onClientTick(tickClient)));
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (poseStack, tickDelta) -> HookGuard.run("event.warmupHud", () -> WarmupHudOverlay.render(poseStack)));

        // Screen-open hotkeys (R re-translate hovered item, P scan screen) — key binds don't
        // tick while a screen is open, so handle them per-screen.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            // The consent box (and nothing else) swallows input while it is up; it never closes the screen.
            ScreenMouseEvents.allowMouseClick(screen).register(
                    (scr, mx, my, button) -> !ConsentOverlay.mouseClicked(scr, mx, my, button));
            ScreenMouseEvents.allowMouseRelease(screen).register((scr, mx, my, button) -> !ConsentOverlay.covers(scr));
            ScreenMouseEvents.allowMouseScroll(screen).register((scr, mx, my, h2, v2) -> !ConsentOverlay.mouseScrolled(scr, mx, my, v2));
            ScreenKeyboardEvents.allowKeyPress(screen).register(
                    (scr, key, scancode, mods) -> !ConsentOverlay.keyPressed(scr, key, mods));
            ScreenKeyboardEvents.allowKeyRelease(screen).register((scr, key, scancode, mods) -> !ConsentOverlay.covers(scr));
            ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, delta) -> HookGuard.run("event.warmupHud", () -> {
                WarmupHudOverlay.renderOnScreen(scr, graphics);
                ConsentOverlay.render(scr, graphics, mouseX, mouseY);
            }));
            ScreenKeyboardEvents.afterKeyPress(screen).register(
                    (scr, key, scancode, mods) ->
                            HookGuard.run("event.screenKey", () -> onScreenKey(scr, key, scancode)));
            ScreenEvents.beforeRender(screen).register((scr, graphics, mouseX, mouseY, delta) -> HookGuard.runSticky("event.screenBeforeRender",
                    () -> SCREEN_RENDER_STACK.get().push(scr)));
            ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, delta) -> HookGuard.runSticky("event.screenAfterRender", () -> {
                finishScreenCapture(scr);
                java.util.ArrayDeque<net.minecraft.client.gui.screens.Screen> stack =
                        SCREEN_RENDER_STACK.get();
                if (!stack.isEmpty() && stack.peek() == scr) stack.pop();
                else stack.removeFirstOccurrence(scr);
                if (stack.isEmpty()) SCREEN_RENDER_STACK.remove();
            }));
        });
    }

    /** 1.18 has no client message event; ChatComponentMixin forwards incoming rows here. */
    public static boolean interceptLegacyChat(Component message) {
        return !INTERNAL_CHAT.get() && instance != null && instance.translateAndInject(message, null);
    }

    private static void addChatMessage(Minecraft minecraft, Component message) {
        boolean previous = INTERNAL_CHAT.get();
        INTERNAL_CHAT.set(true);
        try {
            minecraft.gui.getChat().addMessage(message);
        } finally {
            INTERNAL_CHAT.set(previous);
        }
    }

    private boolean handleOverlayMessage(Component message) {
        if (service == null || message == null) return true;
        long sequence = ++actionBarSequence;
        Component source = FabricTextStyle.resolveLegacyCodes(message);
        DisplayMode mode = service.actionBarMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return true;
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        String request = marked.marked() ? marked.text() : source.getString();
        if (!service.wantsActionBarTranslation(request)) return true;
        TranslationDecision cached = service.translateActionBar(request);
        if (cached.changed()) {
            showActionBar(source, cached.translated(), marked, cached.mode());
            return false;
        }
        service.requestActionBarAsync(request, translated -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                if (sequence != actionBarSequence) return;
                showActionBar(source, translated, marked, service.actionBarMode());
            });
        });
        return true;
    }

    private static void showActionBar(Component source, String translated,
                                      FabricTextStyle.MarkedChat marked, DisplayMode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || translated == null || mode == DisplayMode.ORIGINAL_ONLY) return;
        Component rich = FabricTextStyle.rebuildRich(source, translated, marked);
        Component shown = mode == DisplayMode.BOTH
                ? source.copy().append(new net.minecraft.network.chat.TextComponent("　")).append(rich)
                : rich;
        mc.gui.setOverlayMessage(shown, false);
    }

    // ---- chat ----

    /**
     * Translate an incoming chat line and inject the result asynchronously.
     *
     * @param params chat decoration for signed player chat (re-applies the sender prefix), or null for game messages
     * @return {@code true} if we took over (caller should cancel vanilla); {@code false} to let vanilla show it
     */
    private static Component advancementAnnouncementTitle(Component message) {
        if (!(message instanceof net.minecraft.network.chat.TranslatableComponent contents)
                || !contents.getKey().startsWith("chat.type.advancement.")) return null;
        Object[] args = contents.getArgs();
        return args.length > 1 && args[1] instanceof Component ? (Component) args[1] : null;
    }

    private void registerCommands() {
        ClientCommandManager.DISPATCHER.register(ClientCommandManager.literal(MOD_ID)
                .executes(context -> requestSettingsScreen())
                .then(ClientCommandManager.literal("config")
                        .executes(context -> requestSettingsScreen())));
    }

    private static int requestSettingsScreen() {
        settingsScreenRequested = true;
        return 1;
    }

    private static void openSettings(Minecraft mc) {
        if (mc != null) mc.setScreen(new TranslationConfigScreen(mc.screen));
    }

    private static Component withAdvancementAnnouncementTitle(Component source, Component title) {
        net.minecraft.network.chat.TranslatableComponent contents =
                (net.minecraft.network.chat.TranslatableComponent) source;
        Object[] args = contents.getArgs().clone();
        args[1] = title;
        net.minecraft.network.chat.MutableComponent rebuilt =
                new net.minecraft.network.chat.TranslatableComponent(contents.getKey(), args)
                        .setStyle(source.getStyle());
        for (Component sibling : source.getSiblings()) rebuilt.append(sibling.copy());
        return rebuilt;
    }

    /** Translate the semantic advancement title, not the localized wrapper or player name. */
    private boolean translateAdvancementAnnouncement(
            Component message, Component renderedMessage, DisplayMode mode) {
        Component title = advancementAnnouncementTitle(message);
        if (title == null || !service.wantsChatTranslation(title.getString())) return false;
        PendingChat pending = queueChat(renderedMessage, null);
        pending.mode = mode;
        pending.configureRecovery(1, config.aiChat);
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(title, 0);
        String request = marked.marked() ? marked.text() : title.getString();
        service.translateChatAsyncDetailed(request, result -> {
            String translated = result.text();
            completeChat(pending.id, pending.epoch, mode, translated == null ? null : () -> {
                Component translatedTitle = FabricTextStyle.rebuildRich(title, translated, marked);
                return FabricTextStyle.resolveLegacyCodes(
                        withAdvancementAnnouncementTitle(message, translatedTitle));
            }, 0, result.finalResult());
        });
        return true;
    }

    private boolean translateAndInject(Component message, Object params) {
        if (service == null || message == null) return false;
        observeChatDeliveryContext(Minecraft.getInstance());
        final boolean systemMessage = params == null;
        final Component renderedMessage = FabricTextStyle.resolveLegacyCodes(
                params == null ? message : decorate(params, message));
        if (params != null) params = null;
        DisplayMode mode = service.chatMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return false;
        if (params == null && translateAdvancementAnnouncement(message, renderedMessage, mode)) {
            return true;
        }
        List<Component> hardLines = FabricTextStyle.splitStyledLines(renderedMessage);
        if (hardLines.size() > 1) {
            if (activeBlock != null || FabricTextStyle.isSeparatorText(hardLines.get(0).getString())) {
                List<Component> remainder = new ArrayList<>();
                for (Component line : hardLines) {
                    if (!handleAnnouncementBlock(line, params, systemMessage, mode, line.getString())) {
                        remainder.add(line);
                    }
                }
                if (!remainder.isEmpty()) {
                    translateHardLineMessage(FabricTextStyle.joinStyledLines(remainder), params, mode, remainder);
                }
                return true;
            }
            return translateHardLineMessage(renderedMessage, params, mode, hardLines);
        }
        String full = renderedMessage.getString();
        if (handleAnnouncementBlock(renderedMessage, params, systemMessage, mode, full)) return true;
        boolean framedByServer = trackServerFrame(full);
        int contentStart = com.dragonmeow.nyanlex.translate.ChatSegmenter.contentStart(full);
        boolean hasPrefix = contentStart > 0 && contentStart < full.length();
        String content = hasPrefix ? full.substring(contentStart) : full;
        if (!service.wantsChatTranslation(content)) {
            // Untranslatable line (e.g. the "-----" frame of a Hypixel announcement): if
            // translatable lines are still queued ahead of it, it must WAIT IN LINE as a
            // ready pass-through — otherwise the frame prints before its framed content.
            if (chatDelivery.isQueueEmpty()) return false;
            Component reinjected = renderedMessage;
            if (FabricTextStyle.isSeparatorText(full)) {
                // Compact-chat mods merge identical frame lines and delete the earlier one;
                // alternate an invisible trailing space so the two frames never compare equal.
                separatorSalt = (separatorSalt + 1) & 3;
                if (separatorSalt > 0) {
                    reinjected = renderedMessage.copy().append(new net.minecraft.network.chat.TextComponent(" ".repeat(separatorSalt)));
                }
            }
            PendingChat passThrough = queueChat(reinjected, params);
            passThrough.mode = DisplayMode.ORIGINAL_ONLY;
            chatDelivery.markReady(passThrough);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gui != null) flushReadyChats(mc);
            return true;
        }

        final int cs = contentStart;
        final boolean prefix = hasPrefix;
        PendingChat pending = queueChat(renderedMessage, params);
        pending.mode = mode;
        pending.framedByServer = framedByServer;
        pending.configureRecovery(1, config.aiChat);

        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(renderedMessage, cs);
        if (marked.marked()) {
            // Word-level colour preservation: wrap each style run in an invisible ⟦CS#⟧
            // marker, translate the WHOLE line in one request (better grammar, fewer
            // requests than per-segment), then map every marker region back to its style
            // — a red word stays red on its translated word. Click/hover ride along on
            // the segment styles.
            service.translateChatAsyncDetailed(marked.text(), result -> {
                String translated = result.text();
                    completeChat(pending.id, pending.epoch, mode, translated == null ? null : () -> {
                        Font font = Minecraft.getInstance().font;
                        var core = FabricTextStyle.markedChat(renderedMessage, cs, translated, marked);
                        if (prefix) {
                            return new net.minecraft.network.chat.TextComponent("")
                                    .append(FabricTextStyle.takePrefix(renderedMessage, cs))
                                    .append(core);
                        }
                        return core; // core keeps the original's leading whitespace: starts aligned
                    }, 0, result.finalResult());
            });
            return true;
        }
        service.translateChatAsyncDetailed(content, result -> {
            String translated = result.text();
                completeChat(pending.id, pending.epoch, mode, translated == null ? null
                        : () -> chatLine(Minecraft.getInstance().font, renderedMessage, prefix, cs, translated),
                        0, result.finalResult());
        });
        return true;
    }

    private boolean translateHardLineMessage(Component original,
                                             Object params,
                                             DisplayMode mode, List<Component> hardLines) {
        List<FabricTextStyle.ChatLinePlan> plans = new ArrayList<>(hardLines.size());
        List<String> visible = new ArrayList<>(hardLines.size());
        for (int i = 0; i < hardLines.size(); i++) {
            FabricTextStyle.ChatLinePlan plan = FabricTextStyle.prepareChatLine(hardLines.get(i));
            plans.add(plan);
            visible.add(plan.content());
        }
        List<ParagraphModel.Range> requested = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (ParagraphModel.Range range : ParagraphModel.ranges(visible)) {
            if (range.size() == 1 && ParagraphModel.isBlank(visible.get(range.start()))) continue;
            List<String> rows = new ArrayList<>(range.size());
            boolean wanted = false;
            for (int row = range.start(); row <= range.end(); row++) {
                FabricTextStyle.ChatLinePlan plan = plans.get(row);
                rows.add(plan.request());
                wanted |= !plan.request().isBlank() && service.wantsChatTranslation(plan.content());
            }
            if (wanted) {
                requested.add(range);
                requests.add(ParagraphModel.join(rows));
            }
        }
        if (requested.isEmpty()) {
            if (chatDelivery.isQueueEmpty()) return false;
            PendingChat passThrough = queueChat(original, params);
            passThrough.mode = DisplayMode.ORIGINAL_ONLY;
            chatDelivery.markReady(passThrough);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gui != null) flushReadyChats(mc);
            return true;
        }
        PendingChat pending = queueChat(original, params);
        pending.mode = mode;
        pending.configureRecovery(requested.size(), config.aiChat);
        RecoveryAssembly<List<Component>> recovery = new RecoveryAssembly<>(requested.size());
        for (int paragraph = 0; paragraph < requested.size(); paragraph++) {
            ParagraphModel.Range range = requested.get(paragraph);
            String request = requests.get(paragraph);
            final int requestSlot = paragraph;
            service.translateChatAsyncDetailed(request, result -> {
                String translated = result.text();
                // A style-fallback paragraph arrives as ONE marked string. Strip the
                // prefix before the row split, then re-mark EVERY row: otherwise only
                // row 0 carries the prefix and the remaining rows fail
                // validMarkedResponse and silently fall back to the original text.
                boolean styleFallback = TextFilter.isStyleFallback(translated);
                String semantic = TextFilter.stripStyleFallback(translated);
                List<String> rows = validatedParagraphRows(semantic, range.size());
                List<Component> paragraphLines = null;
                if (!rows.isEmpty()) {
                    paragraphLines = new ArrayList<>(range.size());
                    for (int row = range.start(); row <= range.end(); row++) {
                        String rowText = rows.get(row - range.start());
                        // Only marked rows understand the prefix (markedChat strips it);
                        // an unmarked row would render the NUL prefix as literal text.
                        if (styleFallback && plans.get(row).marked().marked()) {
                            rowText = TextFilter.markStyleFallback(rowText);
                        }
                        paragraphLines.add(FabricTextStyle.rebuildChatLine(
                                plans.get(row), rowText));
                    }
                }
                List<Component> immutableParagraph = paragraphLines == null
                        ? null : List.copyOf(paragraphLines);
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> {
                    if (!noteChatResultOnClient(mc, pending,
                            requestSlot, result.finalResult())) return;
                    RecoveryAssembly.Update<List<Component>> update = recovery.accept(
                            requestSlot, immutableParagraph, result.finalResult());
                    if (!update.accepted() || !update.ready()) return;
                    List<Component> ready = new ArrayList<>(hardLines.size());
                    for (Component line : hardLines) ready.add(line.copy());
                    for (int slot = 0; slot < update.values().size(); slot++) {
                        List<Component> translatedLines = update.values().get(slot);
                        if (translatedLines == null) continue;
                        ParagraphModel.Range translatedRange = requested.get(slot);
                        for (int row = translatedRange.start(); row <= translatedRange.end(); row++) {
                            ready.set(row, translatedLines.get(row - translatedRange.start()));
                        }
                    }
                    Component assembled = FabricTextStyle.joinStyledLines(ready);
                    completeChatOnClient(mc, pending.id, pending.epoch, mode,
                            () -> assembled, requestSlot, result.finalResult(), true);
                });
            });
        }
        return true;
    }

    /** Google/AI fallback is accepted only when every immutable PB anchor survived in order. */
    private static List<String> validatedParagraphRows(String translated, int expectedRows) {
        if (translated == null || expectedRows < 1) return List.of();
        java.util.regex.Matcher matcher = ParagraphModel.BREAK_TOKEN_PATTERN.matcher(translated);
        int token = 0;
        while (matcher.find()) {
            int found;
            try {
                found = Integer.parseInt(matcher.group(1));
            } catch (RuntimeException malformed) {
                return List.of();
            }
            if (found != token++) return List.of();
        }
        if (token != expectedRows - 1) return List.of();
        List<String> rows = ParagraphModel.split(translated);
        return rows.size() == expectedRows ? rows : List.of();
    }

    private PendingChat queueChat(Component message, Object params) {
        PendingChat pending = new PendingChat(nextChatId++, message, params);
        ChatDeliverySession.Admission<PendingChat> admission = chatDelivery.add(pending);
        pending.epoch = admission.epoch();
        PendingChat evicted = admission.evicted();
        if (evicted != null) {
            Component original = admission.evictedWasDisplayed() ? null : pendingOriginal(evicted);
            retireAnnouncement(evicted);
            if (original != null) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.gui != null) {
                    addChatMessage(mc, decorate(evicted.params, original));
                }
            }
        }
        return pending;
    }

    private void completeChat(long id, long epoch, DisplayMode mode,
                              java.util.function.Supplier<Component> builder,
                              int requestSlot, boolean finalResult) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> completeChatOnClient(
                mc, id, epoch, mode, builder, requestSlot, finalResult));
    }

    private void completeChatOnClient(Minecraft mc, long id, long epoch, DisplayMode mode,
                                      java.util.function.Supplier<Component> builder,
                                      int requestSlot, boolean finalResult) {
        completeChatOnClient(mc, id, epoch, mode, builder, requestSlot, finalResult, false);
    }

    private void completeChatOnClient(Minecraft mc, long id, long epoch, DisplayMode mode,
                                      java.util.function.Supplier<Component> builder,
                                      int requestSlot, boolean finalResult,
                                      boolean resultAlreadyAccepted) {
        if (mc.gui == null) return;
        observeChatDeliveryContext(mc);
        PendingChat pending = chatDelivery.get(id, epoch);
        if (pending == null) return;
        if (!resultAlreadyAccepted && !pending.acceptResult(requestSlot, finalResult)) return;
        builder = pending.retainBuilder(builder);
        if (pending.displayedMessage != null) {
            Component translated = builder == null ? null : builder.get();
            Component shown = pendingChatDisplay(pending, mode, translated);
            Component decorated = decorate(pending.params, shown);
            if (replaceChatMessage(mc.gui.getChat(), pending.displayedMessage, decorated)) {
                pending.displayedMessage = decorated;
            } else {
                // The prior line was cleared/trimmed; do not resurrect it at the tail.
                retirePending(pending);
                return;
            }
            if (!pending.mayReceiveRecovery()) retirePending(pending);
            return;
        }
        pending.mode = mode;
        pending.builder = builder;
        chatDelivery.markReady(pending);
        flushReadyChats(mc);
    }

    /** Validate the callback's session/profile epoch before mutating recovery state. */
    private boolean noteChatResultOnClient(Minecraft mc, PendingChat expected,
                                           int requestSlot, boolean finalResult) {
        if (mc == null || mc.gui == null) return false;
        observeChatDeliveryContext(mc);
        PendingChat live = chatDelivery.get(expected.id, expected.epoch);
        if (live != expected) return false;
        return live.acceptResult(requestSlot, finalResult);
    }

    /** Never hold chat hostage: after the bounded wait the original is shown and the
     *  late translation replaces or updates the shown original when supported. */
    private void flushStaleChats(Minecraft mc) {
        if (mc == null || mc.gui == null) return;
        long now = System.nanoTime();
        for (PendingChat retired : chatDelivery.retireIf(p -> p.displayedMessage != null
                && now - p.queuedAtNanos > DISPLAYED_CHAT_RETENTION_NANOS)) {
            retireAnnouncement(retired);
        }
        while (true) {
            flushReadyChats(mc);
            if (chatDelivery.isQueueEmpty()) break;
            PendingChat head = chatDelivery.peekFirstQueued();
            if (System.nanoTime() - head.queuedAtNanos < CHAT_MAX_WAIT_NANOS) break;
            chatDelivery.timeoutFirstQueued();
            Component original = pendingOriginal(head);
            Component shown = head.mode == DisplayMode.BOTH
                    ? FabricTextStyle.chatBlock(original, null) : original;
            Component decorated = decorate(head.params, shown);
            head.displayedMessage = decorated;
            addChatMessage(mc, decorated);
            if (!head.mayReceiveRecovery()) retirePending(head);
        }
    }

    private void flushReadyChats(Minecraft mc) {
        for (PendingChat pending : chatDelivery.drainReady(config.deliverChatTranslationsInOrder)) {
            addPendingChat(mc, pending);
            if (!pending.mayReceiveRecovery()) retirePending(pending);
        }
    }

    private void flushPendingChatOriginals() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            applyChatTransition(mc, chatDelivery.forceOriginalOnly());
        });
    }

    private void observeChatDeliveryContext(Minecraft mc) {
        if (config == null || service == null) return;
        Object connection = mc == null ? null : mc.getConnection();
        Object world = mc == null ? null : mc.level;
        boolean originalOnly = config.chatMode == DisplayMode.ORIGINAL_ONLY
                || service.isShowOriginalOnly();
        applyChatTransition(mc, chatDelivery.observe(connection, world,
                ChatRequestProfile.capture(config, service.targetLang()), originalOnly));
    }

    private void applyChatTransition(Minecraft mc,
                                     ChatDeliverySession.Transition<PendingChat> transition) {
        if (transition.kind() == ChatDeliverySession.TransitionKind.NONE) return;
        if (transition.kind() == ChatDeliverySession.TransitionKind.FLUSH_ORIGINALS
                && mc != null && mc.gui != null) {
            for (PendingChat pending : transition.originals()) {
                Component original = pendingOriginal(pending);
                retireAnnouncement(pending);
                addChatMessage(mc, decorate(pending.params, original));
            }
        }
        for (PendingChat pending : transition.retired()) {
            if (!transition.originals().contains(pending)) retireAnnouncement(pending);
        }
        activeBlock = null;
        insideServerFrame = false;
        frameOpenedAtMs = 0L;
    }

    private Component pendingOriginal(PendingChat pending) {
        PendingBlock block = pending.block;
        if (block == null || block.lines.isEmpty()) return pending.message;
        return FabricTextStyle.joinStyledLines(new ArrayList<>(block.lines));
    }

    private void retirePending(PendingChat pending) {
        PendingChat retired = chatDelivery.retire(pending.id, pending.epoch);
        if (retired != null) retireAnnouncement(retired);
    }

    private void retireAnnouncement(PendingChat pending) {
        PendingBlock block = pending.block;
        if (block == null) return;
        block.releaseBudget();
        block.closed = true;
        block.retired = true;
        if (activeBlock == block) activeBlock = null;
    }

    private void addPendingChat(Minecraft mc, PendingChat pending) {
        Component decorated = decorate(pending.params,
                pendingChatDisplay(pending, pending.mode, pending.builder));
        pending.displayedMessage = decorated;
        addChatMessage(mc, decorated);
    }

    private static Component pendingChatDisplay(PendingChat pending, DisplayMode mode,
                                                java.util.function.Supplier<Component> builder) {
        return pendingChatDisplay(pending, mode, builder == null ? null : builder.get());
    }

    private static Component pendingChatDisplay(PendingChat pending, DisplayMode mode,
                                                Component translated) {
        if (mode == DisplayMode.TRANSLATION) return translated != null ? translated : pending.message;
        if (mode == DisplayMode.BOTH) return FabricTextStyle.chatBlock(pending.message, translated);
        return pending.message;
    }

    private static boolean chatRescaleQueued;

    /** Coalesce every late-translation backfill of one task-loop pass into a single
     *  vanilla chat rescale: {@code tell} always enqueues (unlike execute, which runs
     *  inline on the render thread), and runAllTasks drains it before this frame renders. */
    private static void requestChatRescale(Minecraft mc) {
        if (chatRescaleQueued) return;
        chatRescaleQueued = true;
        mc.tell(() -> {
            chatRescaleQueued = false;
            try {
                mc.gui.getChat().rescaleChat();
            } catch (RuntimeException ignored) {
                // Same tolerance as replaceChatMessage: foreign chat internals never crash the client.
            }
        });
    }

    private static boolean replaceChatMessage(
            net.minecraft.client.gui.components.ChatComponent chat,
            Component previous, Component replacement) {
        try {
            java.util.List<net.minecraft.client.GuiMessage<Component>> messages =
                    ((ChatComponentMixin) (Object) chat).nyanlex$getAllMessages();
            for (int i = 0; i < messages.size(); i++) {
                net.minecraft.client.GuiMessage<Component> old = messages.get(i);
                Component content = old.getMessage();
                if (content != previous) continue;
                messages.set(i, new net.minecraft.client.GuiMessage<>(
                        old.getAddedTime(), replacement, old.getId()));
                requestChatRescale(Minecraft.getInstance());
                return true;
            }
        } catch (RuntimeException ignored) {
            // A foreign chat-history implementation keeps the already shown line.
        }
        return false;
    }

    /** Re-apply the signed-chat sender decoration (e.g. {@code <name> ...}); no-op for game messages. */
    private static Component decorate(Object params, Component line) {
        return line;
    }

    private static Component chatLine(Font font, Component message, boolean hasPrefix, int contentStart,
                                      String translated) {
        net.minecraft.network.chat.Style interactive =
                FabricTextStyle.interactiveStyle(message, contentStart);
        if (hasPrefix) {
            Component styled = FabricTextStyle.withInteractive(
                    FabricTextStyle.styledChatContent(message, contentStart, translated), interactive);
            return new net.minecraft.network.chat.TextComponent("").append(FabricTextStyle.takePrefix(message, contentStart)).append(styled);
        }
        return FabricTextStyle.withInteractive(
                FabricTextStyle.styledChatContent(message, contentStart, translated), interactive);
    }

    private void onItemTooltip(ItemStack stack, List<Component> lines) {
        Minecraft captureClient = Minecraft.getInstance();
        if (captureClient != null && lines != null && TOOLTIP_CAPTURE.active(captureClient.screen)) {
            for (String source : tooltipParagraphPlan(stack, lines, FabricTextStyle::paragraphRequestText).sources()) {
                TOOLTIP_CAPTURE.record(captureClient.screen, source);
            }
            return;
        }
        if (service == null) return;
        if (tooltipProbeDepth.get() > 0) return;
        Minecraft tooltipClient = Minecraft.getInstance();
        if (tooltipClient == null || tooltipClient.player == null
                || !tooltipClient.isSameThread()
                || !renderingCurrentScreen(tooltipClient)) return;
        DisplayMode mode = service.tooltipMode();
        if (mode == DisplayMode.ORIGINAL_ONLY || lines.isEmpty()) return;

        int n = lines.size();
        TooltipParagraphPlan plan = tooltipParagraphPlan(
                stack, lines, FabricTextStyle::paragraphRequestText);
        lastTooltipStack = stack;
        lastTooltipParagraphSources = plan.sources();
        lastTooltipScreen = tooltipClient.screen;
        lastTooltipAtMs = System.currentTimeMillis();
        registerItemEntity(stack);
        service.warmTooltipBatch(plan.sources());
        boolean[] paragraphReady = tooltipParagraphReadiness(lines, plan);
        if (stack != null && !stack.isEmpty()) {
            service.reconcileItemNameWithTooltip(
                    stack.getHoverName().getString(), plan.plainSources());
        }

        Font font = Minecraft.getInstance().font;
        List<Component> out = new ArrayList<>(n);
        boolean completeBothBlock = mode == DisplayMode.BOTH
                && tooltipTranslationRegionReady(lines, plan, paragraphReady);
        List<Component> appended = completeBothBlock ? new ArrayList<>() : null;
        boolean anyTranslated = false;
        int maxLen = 0;
        boolean originalEndsWithSeparator = false;
        for (int i = 0; i < n; ) {
            int end = plan.groupEnd()[i];
            Component line = lines.get(i);
            if (line == null) {
                out.add(line); // keep the list shape other mods may rely on
                if (appended != null) appended.add(new net.minecraft.network.chat.TextComponent(""));
                i = end + 1;
                continue;
            }
            if (mode == DisplayMode.BOTH) {
                for (int k = i; k <= end; k++) {
                    String text = lines.get(k) == null ? "" : lines.get(k).getString();
                    maxLen = Math.max(maxLen, text.length());
                    if (!text.isBlank()) originalEndsWithSeparator = FabricTextStyle.isSeparatorText(text);
                }
            }
            if (!paragraphReady[i]) {
                out.addAll(lines.subList(i, end + 1));
                i = end + 1;
                continue;
            }
            List<Component> group = new ArrayList<>(lines.subList(i, end + 1));
            if (end > i) {
                List<Component> translated = FabricTextStyle.renderTranslatedParagraph(group, service::translateItemLine, font);
                if (translated == null) out.addAll(group);
                else if (mode == DisplayMode.BOTH) {
                    out.addAll(group);
                    if (appended != null) {
                        anyTranslated = true;
                        appended.addAll(translated);
                        for (Component t : translated) maxLen = Math.max(maxLen, t.getString().length());
                    }
                } else out.addAll(translated);
                if (mode == DisplayMode.BOTH && appended != null && translated == null) {
                    appendTooltipShape(appended, group);
                }
                i = end + 1;
                continue;
            }
            Component translated = FabricTextStyle.renderTranslated("tooltip", line, service::translateItemLine);
            if (translated == null) {
                out.add(line);
                if (appended != null) appendTooltipShape(appended, group);
            } else if (mode == DisplayMode.BOTH) {
                out.add(line);
                if (appended != null) {
                    anyTranslated = true;
                    appended.add(translated);
                    maxLen = Math.max(maxLen, translated.getString().length());
                }
            } else {
                out.add(translated);
            }
            i = end + 1;
        }
        if (appended != null && anyTranslated) {
            if (!originalEndsWithSeparator) {
                out.add(FabricTextStyle.separatorLine(maxLen));
            }
            out.addAll(appended);
            out.add(FabricTextStyle.separatorLine(maxLen));
        }
        Component hint = translationHintLine(plan.sources());
        if (hint != null) out.add(hint);
        lines.clear();
        lines.addAll(out);
    }

    /** A trailing grey italic hint line for a tooltip with a line nothing has requested yet, and which key
     *  sends that request. Online translation off: pressing the key asks to start it, so say that. Machine
     *  translation with online translation on: pressing the key translates what is still missing. Nothing else
     *  gets a hint (the AI service translates by itself, and a line already on its way gains nothing from a
     *  key press). */
    private Component translationHintLine(List<String> requests) {
        if (config == null) return null;
        boolean requestsOff = !config.translationRequestsEnabled;
        if (!requestsOff && !service.isManualItemTranslation()) return null;
        boolean missing = false;
        for (String request : requests) {
            if (request == null || request.isBlank()) continue;
            if (!service.isTooltipTranslationPending(request) && !service.isTooltipTranslationReady(request)) missing = true;
        }
        if (!missing) return null;
        Component message = new net.minecraft.network.chat.TranslatableComponent(requestsOff
                        ? "message.nyanlex.tooltip_hint_start" : "message.nyanlex.tooltip_hint",
                retranslateKey == null ? "R" : retranslateKey.getTranslatedKeyMessage().getString());
        return message.copy().setStyle(Style.EMPTY
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(0xAAAAAA)).withItalic(true));
    }

    private boolean[] tooltipParagraphReadiness(
            List<Component> lines, TooltipParagraphPlan plan) {
        int n = lines.size();
        boolean[] readyByLine = new boolean[n];
        for (int start = 0; start < n; ) {
            Component first = lines.get(start);
            int end = plan.groupEnd()[start];
            String request = plan.requests()[start];
            boolean ready = first == null || first.getString().isBlank()
                    || request == null || service.isTooltipTranslationReady(request);
            for (int i = start; i <= end; i++) readyByLine[i] = ready;
            start = end + 1;
        }
        return readyByLine;
    }

    private static boolean tooltipTranslationRegionReady(
            List<Component> lines, TooltipParagraphPlan plan, boolean[] readyByLine) {
        for (int start = 0; start < lines.size(); start = plan.groupEnd()[start] + 1) {
            Component first = lines.get(start);
            if (first != null && !first.getString().isBlank() && !readyByLine[start]) return false;
        }
        return true;
    }

    private static void appendTooltipShape(List<Component> out, List<Component> paragraph) {
        for (Component line : paragraph) out.add(line == null ? new net.minecraft.network.chat.TextComponent("") : line);
    }

    private static TooltipParagraphPlan tooltipParagraphPlan(
            ItemStack stack, List<Component> lines,
            java.util.function.Function<List<Component>, String> paragraphRequestText) {
        int n = lines.size();
        int[] groupEnd = new int[n];
        for (int i = 0; i < n; i++) groupEnd[i] = i;
        int bodyStart = hasVerifiedItemTitle(stack, lines) ? 1 : 0;
        List<String> paragraphLines = new ArrayList<>(n - bodyStart);
        for (int i = bodyStart; i < n; i++) {
            Component line = lines.get(i);
            paragraphLines.add(line == null ? null : line.getString());
        }
        for (com.dragonmeow.nyanlex.translate.ParagraphModel.Range range
                : com.dragonmeow.nyanlex.translate.ParagraphModel.ranges(paragraphLines)) {
            groupEnd[bodyStart + range.start()] = bodyStart + range.end();
        }
        String[] requests = new String[n];
        List<String> sources = new ArrayList<>(n);
        List<String> plainSources = new ArrayList<>(n);
        for (Component line : lines) if (line != null) plainSources.add(line.getString());
        for (int start = 0; start < n; start = groupEnd[start] + 1) {
            Component first = lines.get(start);
            if (first == null || first.getString().isBlank()) {
                sources.add("");
                continue;
            }
            String request = paragraphRequestText.apply(
                    lines.subList(start, groupEnd[start] + 1));
            requests[start] = request;
            sources.add(request);
        }
        return new TooltipParagraphPlan(groupEnd, requests,
                List.copyOf(sources), List.copyOf(plainSources));
    }

    private record TooltipParagraphPlan(
            int[] groupEnd, String[] requests,
            List<String> sources, List<String> plainSources) {
    }

    private static final int MAX_LOGGED_TITLE_MISMATCHES = 50;
    private static final java.util.Set<String> LOGGED_TITLE_MISMATCHES = new java.util.HashSet<>();

    private static boolean hasVerifiedItemTitle(ItemStack stack, List<Component> lines) {
        if (stack == null || stack.isEmpty() || lines.isEmpty() || lines.get(0) == null) return false;
        String line0 = lines.get(0).getString();
        String hoverName = stack.getHoverName().getString();
        boolean same = com.dragonmeow.nyanlex.translate.ParagraphModel.sameItemTitle(line0, hoverName);
        if (!same && config != null && config.debugTranslationOverlay) {
            logTitleMismatchOnce(line0, hoverName);
        }
        return same;
    }

    /** Logs a tooltip title mismatch once per (line0, hoverName) pair per session, capped so a
     *  noisy screen cannot flood the log. Only runs when debugTranslationOverlay is enabled. */
    private static void logTitleMismatchOnce(String line0, String hoverName) {
        String key = line0 + "\u0000" + hoverName;
        synchronized (LOGGED_TITLE_MISMATCHES) {
            if (LOGGED_TITLE_MISMATCHES.size() >= MAX_LOGGED_TITLE_MISMATCHES) return;
            if (!LOGGED_TITLE_MISMATCHES.add(key)) return;
        }
        LOGGER.info("Tooltip title mismatch: line0=\"{}\" hoverName=\"{}\"", line0, hoverName);
    }

    // ---- tick ----

    private void onClientTick(Minecraft mc) {
        ConsentOverlay.tick();
        maybeStartFirstRun(mc);
        maybeMigrateKeybinds(mc);
        SCREEN_RENDER_STACK.remove();
        refreshScannedScreen();
        if (settingsScreenRequested) {
            settingsScreenRequested = false;
            openSettings(mc);
        }
        if (modeKey != null) {
            while (modeKey.consumeClick()) {
                openSettings(mc);
            }
        }
        if (retranslateKey != null && service != null) {
            while (retranslateKey.consumeClick()) {
                if (ScreenTextInput.isTyping(mc == null ? null : mc.screen)) continue;
                if (mc != null && mc.player != null) retranslateItem(mc.player.getMainHandItem());
            }
        }
        if (screenScanKey != null && service != null) {
            // P in the world (no screen open): translate the HUD text. With a screen open the
            // key goes to the screen handler (the scan of that screen).
            while (screenScanKey.consumeClick()) {
                if (mc == null || mc.level == null || mc.screen != null) continue;
                scanHud();
            }
        }
        if (toggleKey != null && service != null) {
            while (toggleKey.consumeClick()) {
                if (ScreenTextInput.isTyping(mc == null ? null : mc.screen)) continue;
                flipShowOriginal();
            }
        }
        syncGameLanguage(mc);
        observeChatDeliveryContext(mc);
        refreshOnlineNames(mc);
        tickItemWarmup(mc);
        if (service != null) service.flushBatches();
        expireStaleBlock();
        flushStaleChats(mc);
        warmVisibleHudItems(mc);
        // R12 (user clarification of R10): the OPEN container is "the current page" — its
        // slots pre-translate; queued batches are kept even if the screen closes ("排隊項
        // 不要丟棄，有看到的都加入排隊，沒看到的先不管"). Only never-seen text stays unbought.
        warmOpenContainerItems(mc);
        warmLoadoutItems(mc);
    }

    // ---- screen-open hotkeys ----

    private void onScreenKey(net.minecraft.client.gui.screens.Screen screen, int key, int scancode) {
        if (service == null) return;
        // Keys typed into a text input (chat, signs, books, search and settings boxes) are
        // text, not hotkeys.
        if (ScreenTextInput.isTyping(screen)) return;
        if (toggleKey != null && toggleKey.matches(key, scancode)) {
            flipShowOriginal();
            return;
        }
        if (retranslateKey != null && retranslateKey.matches(key, scancode)) {
            retranslatePointedItem(screen);
            return;
        }
        if (screenScanKey != null && screenScanKey.matches(key, scancode)) {
            scanAndTranslateScreen(screen);
        }
    }

    /**
     * P with no screen open: translate what the HUD is drawing right now -- every scoreboard row,
     * boss bar name, title and subtitle, action bar line and name tag shown during the next few
     * frames. It passes the same Google gate and consent box as R and the screen scan.
     */
    private void scanHud() {
        if (service == null) return;
        if (gateBlocksManual(service.usesMachineEngineForHud())) return;
        // 線上翻譯 off: ask on the spot; the scan then starts once.
        ConsentOverlay.ask(com.dragonmeow.nyanlex.config.ConsentGate.Kind.HUD, this::startHudScan);
    }

    private void startHudScan() {
        com.dragonmeow.nyanlex.service.TranslationService current = service;
        if (current == null) return;
        current.beginHudCapture(count -> onHudScanDone(count));
    }

    private static void onHudScanDone(int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> feedback(count > 0
                ? new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hud_scan", count)
                : new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.hud_scan_none")));
    }

    /** Rescan one render frame without replacing the widgets' original labels. */
    private void scanAndTranslateScreen(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null || service == null
                || screen instanceof net.minecraft.client.gui.screens.ChatScreen
                || screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox
                || !screenTranslationAllowed(screen)) return;
        if (gateBlocksManual(service.isManualScreenTranslation())) return;
        // 線上翻譯 off: ask on the spot; the scan then runs once for this very screen.
        ConsentOverlay.ask(com.dragonmeow.nyanlex.config.ConsentGate.Kind.SCREEN, () -> {
            if (Minecraft.getInstance().screen == screen) scanAndTranslateScreenNow(screen);
        });
    }

    private void scanAndTranslateScreenNow(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null || service == null
                || screen instanceof net.minecraft.client.gui.screens.ChatScreen
                || screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox
                || !screenTranslationAllowed(screen)) return;
        SCREEN_CAPTURE.begin(screen);
        TOOLTIP_CAPTURE.begin(screen);
        captureScreenText(screen.getTitle(), true);
        // Quest widgets cache laid-out paragraphs. Rebuild them while capturing their original input.
        synchronized (QUEST_WIDGET_PENDING) { QUEST_WIDGET_PENDING.clear(); }
        FabricTextStyle.clearRenderMemo();
        refreshCurrentQuestScreen();
    }

    private static boolean captureScreenText(Component source) {
        return captureScreenText(source, false);
    }

    private static boolean captureScreenText(Component source, boolean widgetInput) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || source == null || !SCREEN_CAPTURE.active(mc.screen)) return false;
        if (!widgetInput && !renderingCurrentScreen(mc)) return false;
        for (String request : FabricTextStyle.requestLines(source)) SCREEN_CAPTURE.record(mc.screen, request);
        return true;
    }

    private static void finishScreenCapture(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        SCREEN_CAPTURE.cancelUnless(mc.screen);
        TOOLTIP_CAPTURE.cancelUnless(mc.screen);
        List<String> sources = SCREEN_CAPTURE.finish(screen);
        if (sources == null || service == null) return;
        List<String> tooltips = TOOLTIP_CAPTURE.finish(screen);
        if (tooltips != null) sources.removeAll(tooltips);
        service.retranslateScreen(sources);
        if (tooltips != null && !tooltips.isEmpty()) service.retranslate(tooltips);
        FabricTextStyle.clearRenderMemo();
        synchronized (QUEST_WIDGET_PENDING) { QUEST_WIDGET_PENDING.clear(); }
        screenRefreshRequested = screen;
        feedback(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.screen_scan", sources.size()));
    }

    private static void refreshScannedScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        SCREEN_CAPTURE.cancelUnless(mc.screen);
        TOOLTIP_CAPTURE.cancelUnless(mc.screen);
        if (screenRefreshRequested == null) return;
        boolean current = screenRefreshRequested == mc.screen;
        screenRefreshRequested = null;
        if (current) refreshCurrentQuestScreen();
    }

    private void warmOpenContainerItems(Minecraft mc) {
        if (mc == null) return;
        net.minecraft.client.gui.screens.Screen currentScreen = mc.screen;
        clearStaleTooltipSnapshot(currentScreen);
        if (service == null || service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) {
            lastContainerScreen = null;
            warmedContainerNames.clear();
            nextContainerWarmScanAtNanos = 0L;
            return;
        }
        if (!(currentScreen instanceof AbstractContainerScreen<?> screen)) {
            if (lastContainerScreen != null) {
                lastContainerScreen = null;
                warmedContainerNames.clear();
                nextContainerWarmScanAtNanos = 0L;
            }
            return;
        }
        boolean screenChanged = screen != lastContainerScreen;
        if (screenChanged) {
            lastContainerScreen = screen;
            warmedContainerNames.clear();
        }
        long now = System.nanoTime();
        if (!screenChanged && now < nextContainerWarmScanAtNanos) return;
        nextContainerWarmScanAtNanos = now + ITEM_WARM_SCAN_INTERVAL_NANOS;

        java.util.Set<String> currentNames = new java.util.HashSet<>();
        List<String> newNames = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot == null || !slot.isActive() || !slot.hasItem()) continue;
            ItemStack slotStack = slot.getItem();
            // Registration is O(1)/deduped, so every visible slot registers even when its
            // name was already warmed for translation on a previous scan.
            registerItemEntity(slotStack);
            String name = slotStack.getHoverName().getString();
            if (name != null && !name.isBlank()
                    && currentNames.add(name) && !warmedContainerNames.contains(name)) {
                newNames.add(name);
            }
        }
        warmedContainerNames.clear();
        warmedContainerNames.addAll(currentNames);
        if (!newNames.isEmpty()) service.warmNamesBatch(newNames);
    }

    private void clearStaleTooltipSnapshot(
            net.minecraft.client.gui.screens.Screen currentScreen) {
        if (lastTooltipScreen == null) return;
        if (currentScreen == lastTooltipScreen
                && System.currentTimeMillis() - lastTooltipAtMs <= 1_500L) return;
        lastTooltipStack = null;
        lastTooltipParagraphSources = null;
        lastTooltipScreen = null;
        lastTooltipAtMs = 0L;
    }

    private void warmVisibleHudItems(Minecraft mc) {
        if (mc == null || mc.player == null || service == null
                || service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) {
            warmedHudNames.clear();
            nextHudWarmScanAtNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        if (now < nextHudWarmScanAtNanos) return;
        nextHudWarmScanAtNanos = now + ITEM_WARM_SCAN_INTERVAL_NANOS;

        java.util.Set<String> currentNames = new java.util.LinkedHashSet<>();
        List<String> newNames = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            registerItemEntity(stack);
            String name = stack.getHoverName().getString();
            if (name != null && !name.isBlank()
                    && currentNames.add(name) && !warmedHudNames.contains(name)) {
                newNames.add(name);
            }
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand != null && !offhand.isEmpty()) {
            registerItemEntity(offhand);
            String name = offhand.getHoverName().getString();
            if (name != null && !name.isBlank()
                    && currentNames.add(name) && !warmedHudNames.contains(name)) {
                newNames.add(name);
            }
        }
        warmedHudNames.clear();
        warmedHudNames.addAll(currentNames);
        if (!newNames.isEmpty()) service.warmNamesBatch(newNames);
    }

    /**
     * Registers the player's main inventory and armor slots every {@link
     * #ITEM_WARM_SCAN_INTERVAL_NANOS} so Loadout-menu lore (drawn from the player's actual
     * gear) has a matchable id/modifier before it is ever hovered or opened. The registry
     * itself is not persisted, so this must run again after every relog.
     */
    private void warmLoadoutItems(Minecraft mc) {
        if (mc == null || mc.player == null || service == null) {
            nextLoadoutScanAtNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        if (now < nextLoadoutScanAtNanos) return;
        nextLoadoutScanAtNanos = now + ITEM_WARM_SCAN_INTERVAL_NANOS;
        Inventory inventory = mc.player.getInventory();
        for (ItemStack stack : inventory.items) registerItemEntity(stack);
        for (ItemStack stack : inventory.armor) registerItemEntity(stack);
    }

    /**
     * Reads the Hypixel SkyBlock display name / id / reforge modifier off a stack and hands
     * them to the core registry. Registration is O(1) and deduped on the core side and never
     * sends a translation request, so this is cheap to call on every scan. Any failure to
     * read NBT data must never crash rendering, so all reads are best-effort.
     */
    public static void registerItemEntity(ItemStack stack) {
        if (service == null || stack == null || stack.isEmpty()) return;
        String displayName;
        try {
            displayName = stack.getHoverName().getString();
        } catch (Exception e) {
            return;
        }
        String id = null;
        String modifier = null;
        try {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                CompoundTag extra = tag.getCompound("ExtraAttributes");
                id = emptyToNull(extra.getString("id"));
                modifier = emptyToNull(extra.getString("modifier"));
            }
        } catch (Exception e) {
            id = null;
            modifier = null;
        }
        service.registerItemEntity(displayName, id, modifier);
    }

    /** Empty strings are treated the same as a missing/absent value. */
    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private void retranslatePointedItem(net.minecraft.client.gui.screens.Screen screen) {
        ItemStack target = null;
        if (screen instanceof AbstractContainerScreen<?> container
                && container instanceof AbstractContainerScreenAccessor accessor) {
            Slot slot = accessor.nyanlex$hoveredSlot();
            if (slot != null && slot.hasItem()) target = slot.getItem();
        }
        if ((target == null || target.isEmpty()) && lastTooltipScreen == screen
                && System.currentTimeMillis() - lastTooltipAtMs <= 1_500L) {
            target = lastTooltipStack;
        }
        if (target != null && !target.isEmpty()) retranslateItem(target);
    }

    /** Google 429 gate messages: action bar in a world, one system notification in a menu. */
    private static final com.dragonmeow.nyanlex.translate.MachineGateGuard.Feedback GATE_FEEDBACK =
            new com.dragonmeow.nyanlex.translate.MachineGateGuard.Feedback() {
        @Override public void gateClosed(int minutes) {
            gateMessage("message.nyanlex.gt_gate.closed", minutes);
        }

        @Override public void manualBlocked(int minutes) {
            gateMessage("message.nyanlex.gt_gate.blocked", minutes);
        }
    };

    /** Whether a manual R/P action must be dropped because the Google gate is closed. */
    private boolean gateBlocksManual(boolean machineEngine) {
        return com.dragonmeow.nyanlex.translate.MachineGateGuard.blocksManualAction(
                config != null && config.translationRequestsEnabled, machineEngine,
                com.dragonmeow.nyanlex.translate.MachineTranslationGate.shared(), GATE_FEEDBACK);
    }

    private static void gateMessage(String key, int minutes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> feedback(new net.minecraft.network.chat.TranslatableComponent(key, Integer.toString(minutes))));
    }

    private void retranslateItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || service == null) return;
        if (gateBlocksManual(service.isManualItemTranslation())) return;
        // 線上翻譯 off: ask on the spot; the request then goes out once for this very item.
        ConsentOverlay.ask(com.dragonmeow.nyanlex.config.ConsentGate.Kind.ITEM, () -> retranslateItemNow(stack));
    }

    private void retranslateItemNow(ItemStack stack) {
        if (stack == null || stack.isEmpty() || service == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        List<String> sources = lastTooltipStack == stack ? lastTooltipParagraphSources : null;
        if (sources == null) {
            List<Component> lines;
            int depth = tooltipProbeDepth.get();
            tooltipProbeDepth.set(depth + 1);
            try {
                lines = stack.getTooltipLines(mc.player, TooltipFlag.Default.NORMAL);
            } catch (RuntimeException e) {
                return;
            } finally {
                if (depth == 0) tooltipProbeDepth.remove();
                else tooltipProbeDepth.set(depth);
            }
            TooltipParagraphPlan plan = tooltipParagraphPlan(
                    stack, lines, FabricTextStyle::paragraphRequestText);
            sources = plan.sources();
        }
        java.util.LinkedHashSet<String> requests = new java.util.LinkedHashSet<>();
        String itemName = stack.getHoverName().getString();
        if (itemName != null && !itemName.isBlank()) requests.add(itemName);
        if (sources != null) requests.addAll(sources);
        List<String> requestList = List.copyOf(requests);
        // R follows the actual on-screen state of THIS tooltip (TranslationService#
        // isTooltipFullyDisplayedTranslated -- the ACTUAL composed display result of every
        // line, same as the renderer itself uses), not a per-line readiness flag: a line
        // still showing original text -> only buy what is missing (no invalidation, so an
        // already-correct segment shared with another item is never thrown away); every
        // line already translated (AI cache, hub, legacy lazy conversion, or
        // segment-composed) -> invalidate and resend the whole tooltip. See root
        // NyanLexFabric#retranslateItem / TranslationService#isTooltipFullyDisplayedTranslated.
        if (service.shouldFullyRetranslateOnKeyPress(requestList)) {
            service.retranslate(requestList);
        } else {
            service.requestItemLines(requestList);
        }
        FabricTextStyle.clearRenderMemo();
        feedback(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.retranslate", stack.getHoverName().getString()));
    }

    public static void testAi(String baseUrl, String model, List<String> keys, java.util.function.Consumer<String> onResult) {
        if (transport == null) {
            onResult.accept(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.not_initialized").getString());
            return;
        }
        Thread t = new Thread(() -> {
            String msg;
            try {
                OpenAiTranslator ai = new OpenAiTranslator(transport, () -> new AiSettings(baseUrl, model, keys));
                ai.setTokenUsage(tokenUsage);
                String out = ai.translate("Hello, world", "zh-TW").translatedText();
                msg = new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.success", "Hello, world → " + out).getString();
            } catch (Exception e) {
                msg = new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.failed", e.getMessage()).getString();
            }
            final String result = msg;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> onResult.accept(result));
            else onResult.accept(result);
        }, "nyanlex-aitest");
        t.setDaemon(true);
        t.start();
    }

    public static void testCodex(java.util.function.Consumer<String> onResult) {
        if (codexTransport == null || config == null) {
            onResult.accept(new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.not_initialized").getString());
            return;
        }
        Thread thread = new Thread(() -> {
            String result;
            try {
                OpenAiTranslator ai = new OpenAiTranslator(codexTransport,
                        () -> new AiSettings("codex://app-server", config.codexModel,
                                java.util.Collections.emptyList(), config.aiGlossary),
                        RequestPacer.disabled());
                String translated = ai.translate("Hello, world", "zh-TW").translatedText();
                result = "Hello, world -> " + translated;
            } catch (Exception error) {
                result = new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.failed",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()).getString();
            }
            final String message = result;
            Minecraft client = Minecraft.getInstance();
            if (client != null) client.execute(() -> onResult.accept(message));
            else onResult.accept(message);
        }, "nyanlex-codex-test");
        thread.setDaemon(true);
        thread.start();
    }

    public static void testAntigravity(java.util.function.Consumer<String> onResult) {
        if (antigravityClient == null || config == null) {
            onResult.accept(new net.minecraft.network.chat.TranslatableComponent(
                    "message.nyanlex.not_initialized").getString());
            return;
        }
        if (!antigravityClient.hasConnectedSessionCached()) {
            onResult.accept(new net.minecraft.network.chat.TranslatableComponent(
                    "screen.nyanlex.ai.antigravity.test_requires_login").getString());
            return;
        }
        Thread thread = new Thread(() -> {
            String result;
            try {
                String translated = antigravityClient.testConnection(
                        config.antigravityModel == null ? "" : config.antigravityModel);
                result = "Hello, world -> " + translated;
            } catch (Exception error) {
                result = new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.failed",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()).getString();
            }
            final String message = result;
            Minecraft client = Minecraft.getInstance();
            if (client != null) client.execute(() -> onResult.accept(message));
            else onResult.accept(message);
        }, "nyanlex-antigravity-test");
        thread.setDaemon(true);
        thread.start();
    }

    public static void translationFile(boolean importing) {
        TranslationService currentService = service;
        if (currentService == null) return;
        com.dragonmeow.nyanlex.translate.TranslationFileDialog.openOutcome(importing,
                currentService::exportTranslations, currentService::importTranslations, outcome -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) client.execute(() -> {
                        toast(fileOutcomeText(outcome));
                        refreshCurrentQuestScreen();
                    });
                });
    }

    private static Component fileOutcomeText(com.dragonmeow.nyanlex.translate.TranslationFileDialog.Outcome outcome) {
        return switch (outcome.kind()) {
            case BUSY -> new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.file.busy");
            case EXPORTED -> new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.file.exported", outcome.files(),
                    outcome.files() > 1 ? outcome.first() + " … " + outcome.last() : outcome.first());
            case IMPORTED -> outcome.failedFiles() == 0
                    ? new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.file.imported", outcome.count(), outcome.files(),
                            outcome.totalFiles())
                    : new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.file.imported_failed", outcome.count(), outcome.files(),
                            outcome.totalFiles(), outcome.failedFiles(), outcome.first());
            case FAILED -> new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.file.failed",
                    outcome.first() == null || outcome.first().isEmpty()
                            ? new net.minecraft.network.chat.TranslatableComponent("message.nyanlex.failed", "").getString() : outcome.first());
        };
    }

    /** Style insertion that marks a component as the mod's own action bar line (never sent for translation). */
    private static final String OWN_FEEDBACK_MARK = "nyanlex:feedback";

    /** True for the lines {@link #feedback} puts in the action bar: the translator hooks must leave them alone. */
    public static boolean isOwnFeedback(Component component) {
        return component != null && OWN_FEEDBACK_MARK.equals(component.getStyle().getInsertion());
    }

    /**
     * The result of R, P or G: one line in the action bar above the hotbar for a few seconds while a
     * world is open, a system notification otherwise. The mod never writes into the chat.
     */
    private static void feedback(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.level != null && mc.gui != null) {
            mc.gui.setOverlayMessage(message.copy().withStyle(style -> style.withInsertion(OWN_FEEDBACK_MARK)), false);
        } else {
            toast(message);
        }
    }

    /** A system notification at the top right: what happened in a menu, or in the background. */
    public static void toast(Component message) {
        toast(new net.minecraft.network.chat.TranslatableComponent("nyanlex.ui.about.title"), message);
    }

    /** A system notification with its own title. */
    public static void toast(Component title, Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        // multiline(): on this version a plain toast is one fixed-width line, so a long message would run off screen
        mc.execute(() -> {
            mc.getToasts().addToast(net.minecraft.client.gui.components.toasts.SystemToast.multiline(mc,
                    net.minecraft.client.gui.components.toasts.SystemToast.SystemToastIds.TUTORIAL_HINT,
                    title, message));
        });
    }
}
