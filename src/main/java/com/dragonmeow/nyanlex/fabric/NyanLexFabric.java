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
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerTransport;
import com.dragonmeow.nyanlex.translate.ExchangeDumpWriter;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.SessionTokenUsage;
import com.dragonmeow.nyanlex.translate.SwitchingAiTranslator;
import com.dragonmeow.nyanlex.translate.SwitchingMachineTranslator;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
import com.dragonmeow.nyanlex.translate.UrlHttpTransport;

import com.dragonmeow.nyanlex.fabric.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
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






public final class NyanLexFabric implements ClientModInitializer {

    public static final String MOD_ID = "nyanlex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TranslatorConfig config;
    private static TranslationService service;
    private static TranslationDebugLog debugLog;
    private static Path configPath;
    private static UrlHttpTransport transport;
    private static CodexAppServerClient codexClient;
    private static CodexAppServerTransport codexTransport;
    private static final SessionTokenUsage tokenUsage = new SessionTokenUsage();

    private static HubLocalCache hubLocalCache;
    private static HubDownloadState hubDownloadState;
    private static HubDownloader hubDownloader;
    private static final HubDownloadJob hubDownloadJob = new HubDownloadJob();
    private static volatile boolean hubStartupChecked;
    private static volatile boolean keybindMigrationChecked;

    private static KeyMapping modeKey;
    private static KeyMapping retranslateKey;
    private static KeyMapping screenScanKey;
    private static final com.dragonmeow.nyanlex.translate.ScreenTranslationCapture SCREEN_CAPTURE =
            new com.dragonmeow.nyanlex.translate.ScreenTranslationCapture();
    private static final com.dragonmeow.nyanlex.translate.ScreenTranslationCapture TOOLTIP_CAPTURE =
            new com.dragonmeow.nyanlex.translate.ScreenTranslationCapture();
    private static net.minecraft.client.gui.screens.Screen screenRefreshRequested;

    private static KeyMapping toggleKey;
    /** Invalidates late action-bar callbacks when the server has already sent a newer row. */
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
        for (var info : mc.getConnection().getListedOnlinePlayers()) {
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
        final net.minecraft.network.chat.ChatType.Bound params;
        final long queuedAtNanos = System.nanoTime();
        long epoch;
        DisplayMode mode;
        java.util.function.Supplier<Component> builder;
        boolean framedByServer;  // inside a server ────── announcement frame: skip our magenta wrap
        Component displayedMessage;
        PendingBlock block;
        private final RecoveryAssembly.ResultProgress<java.util.function.Supplier<Component>>
                recoveryProgress = new RecoveryAssembly.ResultProgress<>();

        PendingChat(long id, Component message, net.minecraft.network.chat.ChatType.Bound params) {
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

        boolean mayReceiveRecovery() {
            return recoveryProgress.mayReceiveRecovery();
        }
    }

    /** Pure safety net: 原文＋翻譯 always goes out TOGETHER as one message (like 1.0.0);
     *  only if a translation callback is somehow lost does this unblock the queue. */
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
    private boolean handleAnnouncementBlock(Component message,
                                            net.minecraft.network.chat.ChatType.Bound params,
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
                                    ? Component.empty().append(source).append(Component.literal("\n")).append(rebuilt)
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

    public static TranslatorConfig config() {
        return config;
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
    private static int warmupWorldTicks;
    private static boolean warmupAutoResumeTried;

    /** The single per-launch warm-up driver (also keeps the per-launch item budget). */
    public static synchronized com.dragonmeow.nyanlex.warmup.ItemWarmupDriver itemWarmupDriver() {
        if (itemWarmupDriver == null) {
            itemWarmupDriver = new com.dragonmeow.nyanlex.warmup.ItemWarmupDriver(
                    new FabricItemWarmupSource(),
                    new FabricItemWarmupSource.Backend(() -> aiRateLimitedProbe.getAsBoolean()),
                    NyanLexFabric::config, System::currentTimeMillis);
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

    /** Build one item's tooltip translation units exactly as the hover path would. */
    static com.dragonmeow.nyanlex.warmup.ItemWarmupTarget itemWarmupTarget(Item item, Minecraft mc) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        String itemId = id == null ? "" : id.toString();
        String namespace = id == null ? "" : id.getNamespace();
        ItemStack stack = item.getDefaultInstance();
        if (stack.isEmpty()) {
            return new com.dragonmeow.nyanlex.warmup.ItemWarmupTarget(itemId, namespace, List.of());
        }
        List<Component> lines;
        int depth = tooltipProbeDepth.get();
        tooltipProbeDepth.set(depth + 1);
        try {
            lines = stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player,
                    TooltipFlag.Default.NORMAL);
        } catch (RuntimeException | LinkageError e) {
            return new com.dragonmeow.nyanlex.warmup.ItemWarmupTarget(itemId, namespace, List.of());
        } finally {
            if (depth == 0) tooltipProbeDepth.remove();
            else tooltipProbeDepth.set(depth);
        }
        if (lines == null || lines.isEmpty()) {
            return new com.dragonmeow.nyanlex.warmup.ItemWarmupTarget(itemId, namespace, List.of());
        }
        TooltipParagraphPlan plan = tooltipParagraphPlan(
                stack, lines, FabricTextStyle::paragraphRequestText);
        return new com.dragonmeow.nyanlex.warmup.ItemWarmupTarget(itemId, namespace, plan.sources());
    }

    private static com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State warmupPrevState =
            com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.IDLE;
    private static com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.PauseReason warmupPrevReason =
            com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.PauseReason.NONE;
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
        if (state == com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.RUNNING
                && warmupPrevState == com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.State.PAUSED
                && warmupPrevReason == com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.PauseReason.NO_WORLD) {
            status(Component.translatable(com.dragonmeow.nyanlex.config.SettingsModel.KEY_WARMUP_RESUMED,
                    progress.scanned(), progress.totalItems()).getString());
        }
        warmupPrevState = state;
        warmupPrevReason = progress.pauseReason();
    }

    /** Per client tick: drive the warm-up, and resume it once per launch if the player opted in. */
    private static void tickItemWarmup(Minecraft mc) {
        if (config == null || service == null) return;
        var driver = itemWarmupDriver();
        if (mc != null && mc.player != null && mc.level != null) {
            if (!warmupAutoResumeTried && config.itemWarmupEnabled
                    && config.itemWarmupWarningAcknowledged && ++warmupWorldTicks >= 400) {
                warmupAutoResumeTried = true;
                driver.start();
            }
        } else {
            warmupWorldTicks = 0;
        }
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

    public static void postHubStatus(String msg) {
        status(msg);
    }

    private static List<String> loadedModIds() {
        List<String> ids = new ArrayList<>();
        for (net.fabricmc.loader.api.ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ids.add(container.getMetadata().getId());
        }
        return ids;
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
                    status(Component.translatable("screen.nyanlex.hub.progress.failed",
                            finalError == null ? "" : finalError).getString());
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
        // A6: CANCELLED must invalidate the render memo too. HubDownloader#download keeps
        // every file already merged before the cancellation point (see its javadoc), so a
        // cancelled job can still have added rows to HubLocalCache that the player's current
        // screen already rendered (and cached) WITHOUT them — without this, those newly
        // merged translations stay invisible until some unrelated later event happens to
        // clear the memo.
        if (state != HubDownloadJob.State.DONE && state != HubDownloadJob.State.FAILED
                && state != HubDownloadJob.State.CANCELLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            FabricTextStyle.clearRenderMemo();
            if (state == HubDownloadJob.State.DONE) {
                var result = job.result();
                if (result != null) {
                    status(Component.translatable("message.nyanlex.hub.download_done",
                            result.added()).getString());
                }
            } else if (state == HubDownloadJob.State.FAILED) {
                String reason = job.failureMessage();
                status(Component.translatable("message.nyanlex.hub.download_failed",
                        reason == null ? "" : reason).getString());
            }
            // CANCELLED: HubDownloadProgressScreen already shows its own cancelled state to
            // whichever screen is open; only the memo invalidation above is needed here.
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


/** Once per launch, the first time the title screen appears: a silent,
     *  index.json-only check for mod translations the player doesn't have yet. */
    private void maybeStartHubStartupCheck(Minecraft mc) {
        if (hubStartupChecked || mc == null || mc.screen == null) return;
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
        hubStartupChecked = true;
        if (hubDownloader == null || config.hubStartupPromptDisabled) return;
        runHubBackground(() -> {
            try {
                List<String> modIds = loadedModIds();
                Optional<ModpackIdentity> modpack = ModpackDetector.detect(hubCandidateRoots(), modIds, null);
                HubPlan plan = hubDownloader.planStartupMods(false, modpack.orElse(null), modIds,
                        config.targetLang, hubDownloadState);
                if (plan.downloadable().isEmpty()) return;
                Minecraft client = Minecraft.getInstance();
                if (client == null) return;
                client.execute(() -> {
                    if (client.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                        client.setScreen(new HubStartupPromptScreen(client.screen, plan));
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // Startup check is best-effort and silent on failure.
            }
        });
    }

    public static CodexAppServerClient codexClient() {
        return codexClient;
    }

    public static SessionTokenUsage.Snapshot tokenUsageSnapshot() {
        return tokenUsage.snapshot();
    }

    public static TranslationDebugLog debugLog() {
        return debugLog;
    }

    public static void clearDebugLog() {
        if (debugLog != null) debugLog.clear();
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

    /**
     * Optional quest-book widget integration.  TextField receives the whole Component before
     * it measures and wraps it, which is the only point where translated paragraphs can
     * retain their formatting and still be laid out to the real quest-panel width.
     */
    public static Component questWidgetText(Object widget, Component source) {
        if (com.dragonmeow.nyanlex.translate.InternalRenderGuard.active()) return source;
        TranslationService s = service;
        if (widget == null || source == null || s == null) return source;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc) && !questWidgetOnCurrentScreen(widget, mc)) return source;

        if (captureScreenText(source, true)) return source;
        if (s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return source;
        Component resolved = FabricTextStyle.resolveLegacyCodes(source);
        Component rendered = FabricTextStyle.renderTranslated(
                "questText", resolved, s::translateScreenText);
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
        if (submit) {
            for (String lineRequest : requests) {
                s.requestLiveScreenTextAsync(lineRequest, translated -> {
                    synchronized (QUEST_WIDGET_PENDING) {
                        if (!request.equals(QUEST_WIDGET_PENDING.get(widget))) return;
                    }
                    Component ready = FabricTextStyle.renderTranslated(
                            "questText", resolved, s::translateScreenText);
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        Component display = ready != null ? ready : resolved;
                        client.execute(() -> {
                            synchronized (QUEST_WIDGET_PENDING) {
                                if (!request.equals(QUEST_WIDGET_PENDING.get(widget))) return;
                            }
                            if (questWidgetOnCurrentScreen(widget, client)) applyQuestWidgetText(widget, display);
                        });
                    }
                });
            }
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

    private static void applyQuestWidgetText(Object widget, Component translated) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null
                || !mc.screen.getClass().getName().startsWith(QUEST_UI_PACKAGE)) return;
        try {
            Class<?> type = widget.getClass();
            java.lang.reflect.Method setter = null;
            while (type != null && setter == null) {
                try {
                    setter = type.getDeclaredMethod("setText", Component.class);
                } catch (NoSuchMethodException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (setter != null) {
                setter.setAccessible(true);
                com.dragonmeow.nyanlex.translate.InternalRenderGuard.enter();
                try {
                    setter.invoke(widget, translated);
                } finally {
                    com.dragonmeow.nyanlex.translate.InternalRenderGuard.exit();
                }
                Object gui = widget.getClass().getMethod("getGui").invoke(widget);
                if (gui != null) gui.getClass().getMethod("refreshWidgets").invoke(gui);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.debug("Unable to reflow translated quest text widget", error);
        }
    }

    public static String screenText(String str) {
        if (com.dragonmeow.nyanlex.translate.InternalRenderGuard.active()) return str;
        if (str != null && captureScreenText(Component.literal(str))) return str;
        TranslationService s = service;
        if (s == null || str == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return str;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return str;
        if (str.indexOf('\n') >= 0 || str.indexOf('\r') >= 0) {
            String normalized = str.replace("\r\n", "\n").replace('\r', '\n');
            Component translated = FabricTextStyle.renderTranslated(
                    "screenText", Component.literal(normalized), s::translateScreenText);
            return translated != null ? translated.getString() : str;
        }
        TranslationDecision d = s.translateScreenText(str);
        return d.changed() ? d.translated() : str;
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
        Component styled = FabricTextStyle.renderTranslated(
                "screenTextFcs", source, s::translateScreenText);
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
        Component translated = FabricTextStyle.renderTranslated(
                "screenTextBlock", source, s::translateScreenText);
        return translated == null ? text : translated;
    }

    /** Apply cached translations at the final visible-tooltip draw boundary. */
    public static List<Component> visibleTooltip(List<Component> lines) {
        TranslationService s = service;
        Minecraft mc = Minecraft.getInstance();
        if (s == null || lines == null || lines.isEmpty() || !renderingCurrentScreen(mc)) return lines;
        DisplayMode mode = s.tooltipMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return lines;
        for (Component line : lines) {
            if (line != null && FabricTextStyle.isSeparatorText(line.getString())) return lines;
        }
        List<String> requests = new ArrayList<>();
        for (Component line : lines) {
            if (line != null && !line.getString().isBlank()) requests.add(FabricTextStyle.requestText(line));
        }
        s.warmTooltipBatch(requests);
        List<Component> translatedLines = new ArrayList<>(lines.size());
        boolean changed = false;
        int maxLen = 0;
        for (Component line : lines) {
            if (line == null) { translatedLines.add(null); continue; }
            maxLen = Math.max(maxLen, line.getString().length());
            Component translated = FabricTextStyle.renderTranslated("visibleTooltip", line, s::translateItemLine);
            if (translated != null) {
                translatedLines.add(translated);
                maxLen = Math.max(maxLen, translated.getString().length());
                changed = true;
            } else translatedLines.add(line);
        }
        Component hint = translationHintLine(s, requests);
        if (!changed) return hint == null ? lines : appendLine(lines, hint);
        List<Component> out;
        if (mode == DisplayMode.TRANSLATION) {
            out = translatedLines;
        } else {
            List<Component> both = new ArrayList<>(lines.size() + translatedLines.size() + 2);
            both.addAll(lines);
            both.add(FabricTextStyle.separatorLine(maxLen));
            both.addAll(translatedLines);
            both.add(FabricTextStyle.separatorLine(maxLen));
            out = both;
        }
        return hint == null ? out : appendLine(out, hint);
    }

    private static List<Component> appendLine(List<Component> lines, Component extra) {
        List<Component> out = new ArrayList<>(lines.size() + 1);
        out.addAll(lines);
        out.add(extra);
        return out;
    }

    /**
     * Manual item mode only (see {@link TranslationService#isManualItemTranslation()}):
     * a trailing grey italic hint line telling the player this tooltip has a line
     * nothing has requested yet, and which key sends that request -- or that a prior
     * key press is still in flight. {@code requests} is the exact set fed to {@link
     * TranslationService#warmTooltipBatch}/{@code translateItemLine} above, so the
     * hint line itself is never part of what gets translated/requested.
     */
    private static Component translationHintLine(TranslationService s, List<String> requests) {
        if (!s.isManualItemTranslation() || config == null || !config.translationRequestsEnabled) return null;
        boolean pending = false;
        boolean missing = false;
        for (String request : requests) {
            if (request == null || request.isBlank()) continue;
            if (s.isTooltipTranslationPending(request)) pending = true;
            else if (!s.isTooltipTranslationReady(request)) missing = true;
        }
        if (!pending && !missing) return null;
        Component message = pending
                ? Component.translatable("message.nyanlex.tooltip_hint_pending")
                : Component.translatable("message.nyanlex.tooltip_hint",
                        retranslateKey == null ? "R" : retranslateKey.getTranslatedKeyMessage().getString());
        return message.copy().setStyle(Style.EMPTY
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(0xAAAAAA)).withItalic(true));
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
        String key = title != null
                && title.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable
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
     *
     * <p>Port note (six-tree sync): where the glue cannot reach the entity (fabric26's
     * {@code submitNameDisplay} {@code @ModifyArg}), call the one-arg {@link #nameTag(Component)}
     * instead — it falls back to whole-token matching of listed player names in the plain text.</p>
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
        java.util.Collection<net.minecraft.client.multiplayer.PlayerInfo> roster = conn.getListedOnlinePlayers();
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
        for (net.minecraft.client.multiplayer.PlayerInfo info : conn.getListedOnlinePlayers()) {
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
        configPath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
        LegacyDataMigration.migrate(configPath.getParent(), LOGGER::info);
        config = TranslatorConfig.load(configPath);

        int workers = Math.max(1, config.workerThreads);
        java.util.concurrent.ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "nyanlex-worker");
            t.setDaemon(true);
            return t;
        };

        ExecutorService executor = new com.dragonmeow.nyanlex.translate.PriorityTranslationExecutor(
                workers, threadFactory);

        transport = new UrlHttpTransport(Duration.ofMillis(config.httpTimeoutMs));
        // 事前冷卻節流：one pacer PER ENGINE so Google and AI space their own requests
        // without blocking each other. The cooldown is read live from config.
        SwitchingMachineTranslator google = new SwitchingMachineTranslator(
                transport,
                () -> config.sourceLang,
                () -> config.machineTranslationProvider,
                new RequestPacer(() -> config.requestCooldownMs));
        OpenAiTranslator apiAi = new OpenAiTranslator(transport,
                () -> new AiSettings(config.aiBaseUrl, config.aiModel, config.aiApiKeys, config.aiGlossary),
                new RequestPacer(() -> config.requestCooldownMs));
        apiAi.setTokenUsage(tokenUsage);
        Path codexRoot = configPath.getParent();
        codexClient = new CodexAppServerClient(
                codexRoot.resolve(MOD_ID + "-codex-home"),
                codexRoot.resolve(MOD_ID + "-codex-workspace"));
        codexClient.setTokenUsage(tokenUsage);
        // Spawn + initialize app-server in the background when Codex mode is the active
        // engine, so the first translation does not wait for process start.
        if (config.aiUseCodex) codexClient.warmUpAsync();
        codexTransport = new CodexAppServerTransport(codexClient,
                () -> config.codexReasoningEffort);
        OpenAiTranslator codexAi = new OpenAiTranslator(codexTransport,
                () -> new AiSettings("codex://app-server", config.codexModel,
                        java.util.Collections.emptyList(), config.aiGlossary),
                RequestPacer.disabled());
        // 2026-10-02: a bounded, rotating on-disk trace of real AI request/response
        // bodies (never headers/keys) plus each exchange's per-unit verdicts, active only
        // while the player has the debug overlay on -- lets a player who hits a
        // translation failure send the files under nyanlex-debug/ for offline
        // diagnosis, the same kind of evidence scratchpad/live-ai's manual probe had to
        // reconstruct by hand. See ExchangeDumpWriter's class doc.
        ExchangeDumpWriter exchangeDump = new ExchangeDumpWriter(
                configPath.getParent().resolve("nyanlex-debug"),
                () -> config != null && config.debugTranslationOverlay, 20,
                () -> config == null ? java.util.List.of() : config.aiApiKeys);
        apiAi.setExchangeDumpSink(exchangeDump);
        codexAi.setExchangeDumpSink(exchangeDump);
        SwitchingAiTranslator ai = new SwitchingAiTranslator(
                apiAi, codexAi, () -> config.aiUseCodex);
        aiRateLimitedProbe = ai::isRateLimited;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CodexAppServerClient client = codexClient;
            if (client != null) client.close();
        }, "nyanlex-codex-shutdown"));
        PersistentStore googleStore = new ProviderLanguageFileStore(
                FabricLoader.getInstance().getConfigDir(), MOD_ID + "-cache", config.targetLang,
                () -> config.machineTranslationProvider, config.persistentCacheMaxEntries);
        PersistentStore aiStore = new LanguageFileStore(
                FabricLoader.getInstance().getConfigDir(), MOD_ID + "-ai-cache", config.targetLang,
                config.persistentCacheMaxEntries);
        // Three files: AI and GT own their wording/confirmed-identity entries; one
        // failure ledger stores separately namespaced retry state for both engines.
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
        // One-time migration only: old builds mixed dispatcher-produced GT stand-ins
        // into ai-cache. New requests always go through the independently owned GT cache.
        aiCache.setProvisionalStore(googleStore);
        debugLog = new TranslationDebugLog(() -> config != null && config.debugTranslationOverlay);
        cache.setDebugLog("GT", debugLog);
        aiCache.setDebugLog("AI", debugLog);
        cache.setInfoLog(LOGGER::warn);
        aiCache.setInfoLog(LOGGER::warn);
        // A GT result shown after AI failure remains provisional from the AI cache's
        // perspective, so AI retries after its global 429 gate reopens.
        aiCache.setProvisionalRetryGate(() ->
                (config.aiUseCodex
                        ? codexClient != null && codexClient.isSignedInCached()
                                && config.codexModel != null && !config.codexModel.isBlank()
                        : config.aiApiKeys != null && !config.aiApiKeys.isEmpty())
                        && !ai.isRateLimited());
        service = new TranslationService(config, cache, aiCache);
        // 2026-10-02: manual (cache-only, translate-key-driven) item/screen-text mode is
        // no longer a global startup flag -- TranslationService now judges it live, per
        // surface, from that surface's CURRENTLY CONFIGURED engine (config.aiTooltip /
        // config.aiScreenText): the AI engine auto-translates (the pre-1.0.8 behaviour),
        // the machine-translation engine is cache-only. See
        // TranslationService#isManualItemTranslation()/#isManualScreenTranslation().
        service.setInfoLog(LOGGER::info);
        // 2026-10-02: per-segment tooltip trace, same gate/rotation discipline and dumpDir
        // as exchangeDump above -- captures the ONE layer an HTTP exchange dump cannot see
        // (which TooltipSegmentPlanner segment a row became, its LOCAL cache key, hit/
        // pending/missing) so a future "why did this ONE row never translate" investigation
        // reads it straight off disk. See TooltipTraceWriter's class doc.
        service.setTooltipTraceWriter(new com.dragonmeow.nyanlex.translate.TooltipTraceWriter(
                configPath.getParent().resolve("nyanlex-debug"),
                () -> config != null && config.debugTranslationOverlay, 20));
        // 2026-10-02: measurement-only lang-template probe (zero cost while the debug
        // overlay is off); writes config/nyanlex-debug/lang-probe.json. Never alters text.
        LangProbeGlue.install(new com.dragonmeow.nyanlex.translate.LangProbe(
                configPath.getParent().resolve("nyanlex-debug").resolve("lang-probe.json"),
                () -> config != null && config.debugTranslationOverlay, 60_000L));
        service.setTargetLangChangeListener(this::onTargetLanguageChanged);
        service.setBatchWindowMs(() -> config.batchWindowMs);
        service.setItemSourceLanguage(() -> {
            if (config.sourceLang != null && !config.sourceLang.isBlank()
                    && !"auto".equalsIgnoreCase(config.sourceLang)) return config.sourceLang;
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.options == null ? null : mc.options.languageCode;
        });
        service.setProtectedNames(() -> onlineNames);

        Path hubDir = FabricLoader.getInstance().getConfigDir();
        hubLocalCache = new HubLocalCache(hubDir, config.targetLang);
        hubDownloadState = new HubDownloadState(hubDir.resolve(HubPaths.stateFileName()));
        hubDownloader = new HubDownloader(new HubRepository(
                new UrlHttpTransport(Duration.ofSeconds(8), HubPaths.downloadResponseByteCap())));
        service.setHubLookup(hubLocalCache::get);
        hubDownloadJob.addListener(NyanLexFabric::onHubDownloadJobChanged);

        registerKeyBinds();
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


    private void flipShowOriginal() {
        if (service == null) return;
        boolean originalsNow = service.toggleShowOriginal();
        FabricTextStyle.clearRenderMemo();
        if (originalsNow) flushPendingChatOriginals();
        status(Component.translatable(originalsNow ? "message.nyanlex.show_original" : "message.nyanlex.show_translation").getString());
    }

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

    static String mapGameLang(String gameLang) {
        return com.dragonmeow.nyanlex.config.TranslationLanguages.fromMinecraftCode(gameLang);
    }

    private void registerEvents() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> HookGuard.call("event.allowGame",
                () -> overlay ? handleOverlayMessage(message) : !translateAndInject(message, null),
                () -> true));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                HookGuard.call("event.allowChat", () -> !translateAndInject(message, params), () -> true));

        ResourceLocation tooltipPhase = ResourceLocation.tryParse(MOD_ID + ":tooltip_translation");
        ItemTooltipCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, tooltipPhase);
        ItemTooltipCallback.EVENT.register(tooltipPhase,
                (stack, context, type, lines) -> HookGuard.run("event.itemTooltip", () -> onItemTooltip(stack, lines)));

        ClientTickEvents.END_CLIENT_TICK.register(
                tickClient -> HookGuard.run("event.clientTick", () -> onClientTick(tickClient)));
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (graphics, tickCounter) -> HookGuard.run("event.warmupHud", () -> WarmupHudOverlay.render(graphics)));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
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

    /**
     * Capture the action bar at receipt time. This is intentionally independent of the
     * render mixin: an overlay can be replaced before its next draw, which used to make
     * short messages such as "You received 10,518.2 coins!" miss translation entirely.
     */
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
                ? source.copy().append(Component.literal("　")).append(rich)
                : rich;
        mc.gui.setOverlayMessage(shown, false);
    }

    private boolean translateAndInject(Component message, net.minecraft.network.chat.ChatType.Bound params) {
        if (service == null || message == null) return false;
        observeChatDeliveryContext(Minecraft.getInstance());
        LangProbeGlue.observe("chat", message);
        final boolean systemMessage = params == null;
        final Component renderedMessage = FabricTextStyle.resolveLegacyCodes(
                params == null ? message : decorate(params, message));
        if (params != null) params = null;
        DisplayMode mode = service.chatMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return false;
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
                // Compact-chat mods merge identical frame
                // lines into one "[xN]" entry and delete the earlier ones. Cycle 0-3
                // invisible trailing spaces so nearby frames never compare equal.
                separatorSalt = (separatorSalt + 1) & 3;
                if (separatorSalt > 0) {
                    reinjected = renderedMessage.copy().append(Component.literal(" ".repeat(separatorSalt)));
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
                        var core = FabricTextStyle.markedChat(renderedMessage, cs, translated, marked);
                        if (prefix) {
                            return Component.empty()
                                    .append(FabricTextStyle.takePrefix(renderedMessage, cs))
                                    .append(core);
                        }
                        // core keeps the original's leading whitespace (markers preserve
                        // it), so the translation lines up with the original text start.
                        return core;
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

    /** Translate a real multi-line component as atomic semantic paragraphs. */
    private boolean translateHardLineMessage(Component original,
                                             net.minecraft.network.chat.ChatType.Bound params,
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

    private PendingChat queueChat(Component message, net.minecraft.network.chat.ChatType.Bound params) {
        observeChatDeliveryContext(Minecraft.getInstance());
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
                    mc.gui.getChat().addMessage(decorate(evicted.params, original));
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
                // The prior line was cleared/trimmed or chat internals changed. Never
                // resurrect stale text at the tail and break chat ordering.
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

    /** Never hold chat hostage: after the bounded wait the original is shown and a
     *  late translation replaces the shown original when supported. */
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
            mc.gui.getChat().addMessage(decorated);
            if (!head.mayReceiveRecovery()) retirePending(head);
        }
    }

    private void flushReadyChats(Minecraft mc) {
        for (PendingChat pending :
                chatDelivery.drainReady(config.deliverChatTranslationsInOrder)) {
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
                mc.gui.getChat().addMessage(decorate(pending.params, original));
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
        mc.gui.getChat().addMessage(decorated);
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
            java.util.List<net.minecraft.client.GuiMessage> messages =
                    ((ChatComponentAccess) (Object) chat).nyanlex$getAllMessages();
            for (int i = 0; i < messages.size(); i++) {
                net.minecraft.client.GuiMessage old = messages.get(i);
                if (old.content() != previous) continue;
                messages.set(i, new net.minecraft.client.GuiMessage(old.addedTime(), replacement,
                        old.signature(), old.tag()));
                requestChatRescale(Minecraft.getInstance());
                return true;
            }
        } catch (RuntimeException ignored) {
            // If another mod replaced chat history internals, keep the already shown line.
        }
        return false;
    }

    private static Component decorate(net.minecraft.network.chat.ChatType.Bound params, Component line) {
        return params != null ? params.decorate(line) : line;
    }

    private static Component chatLine(Font font, Component message, boolean hasPrefix, int contentStart,
                                      String translated) {
        net.minecraft.network.chat.Style interactive =
                FabricTextStyle.interactiveStyle(message, contentStart);
        if (hasPrefix) {
            Component styled = FabricTextStyle.withInteractive(
                    FabricTextStyle.styledChatContent(message, contentStart, translated), interactive);
            return Component.empty().append(FabricTextStyle.takePrefix(message, contentStart)).append(styled);
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
        // Search-tree tooltips are background work; only the client thread inside the
        // current screen's render scope may enter the urgent tooltip path.
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
        // A BOTH block is useful only as a complete mirror of the original tooltip.
        // Waiting for every paragraph also prevents a late paragraph from being inserted
        // into an already-visible, partially translated block on the next frame.
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
                if (appended != null) appended.add(Component.empty());
                i = end + 1;
                continue;
            }
            if (mode == DisplayMode.BOTH) {
                for (int k = i; k <= end; k++) {
                    String text = lines.get(k) == null ? "" : lines.get(k).getString();
                    maxLen = Math.max(maxLen, text.length());
                    if (!text.isBlank()) {
                        originalEndsWithSeparator = FabricTextStyle.isSeparatorText(text);
                    }
                }
            }
            if (!paragraphReady[i]) {
                out.addAll(lines.subList(i, end + 1));
                i = end + 1;
                continue;
            }
            List<Component> group = new ArrayList<>(lines.subList(i, end + 1));
            if (end > i) {
                List<Component> translated = FabricTextStyle.renderTranslatedParagraph(
                        group, service::translateItemLine, font);
                if (translated == null) {
                    out.addAll(group);
                } else if (mode == DisplayMode.BOTH) {
                    out.addAll(group);
                    if (appended != null) {
                        anyTranslated = true;
                        appended.addAll(translated);
                        for (Component t : translated) {
                            maxLen = Math.max(maxLen, t.getString().length());
                        }
                    }
                } else {
                    out.addAll(translated);
                }
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
        lines.clear();
        lines.addAll(out);
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
        for (Component line : paragraph) out.add(line == null ? Component.empty() : line);
    }

    private static TooltipParagraphPlan tooltipParagraphPlan(
            ItemStack stack, List<Component> lines,
            java.util.function.Function<List<Component>, String> paragraphRequestText) {
        int n = lines.size();
        int[] groupEnd = new int[n];
        for (int i = 0; i < n; i++) groupEnd[i] = i;

        // Only a first row verified against ItemStack#getHoverName is isolated here;
        // every remaining boundary comes from the project-wide paragraph model.
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
                // Keep blank rows in the batch context without creating a translation key.
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

    private void onClientTick(Minecraft mc) {
        maybeStartHubStartupCheck(mc);
        maybeMigrateKeybinds(mc);
        SCREEN_RENDER_STACK.remove();
        refreshScannedScreen();
        if (modeKey != null) {
            while (modeKey.consumeClick()) {
                if (mc != null) mc.setScreen(new TranslationConfigScreen(mc.screen));
            }
        }
        if (retranslateKey != null && service != null) {
            while (retranslateKey.consumeClick()) {
                if (ScreenTextInput.isTyping(mc == null ? null : mc.screen)) continue;
                if (mc != null && mc.player != null) retranslateItem(mc.player.getMainHandItem());
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

    /** Rescan one render frame without replacing the widgets' original labels. */
    private void scanAndTranslateScreen(net.minecraft.client.gui.screens.Screen screen) {
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
        status(Component.translatable("message.nyanlex.screen_scan", sources.size()).getString());
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
     * read NBT/component data must never crash rendering, so all reads are best-effort.
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
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data != null) {
                CompoundTag tag = data.copyTag();
                CompoundTag extra = tag.getCompound("ExtraAttributes");
                id = firstNonEmpty(tag.getString("id"), extra.getString("id"));
                modifier = firstNonEmpty(tag.getString("modifier"), extra.getString("modifier"));
            }
        } catch (Exception e) {
            id = null;
            modifier = null;
        }
        service.registerItemEntity(displayName, id, modifier);
    }

    /** Empty strings are treated the same as a missing/absent value. */
    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return null;
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

    private void retranslateItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || service == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        List<String> sources = lastTooltipStack == stack ? lastTooltipParagraphSources : null;
        if (sources == null) {
            List<Component> lines;
            int depth = tooltipProbeDepth.get();
            tooltipProbeDepth.set(depth + 1);
            try {
                Item.TooltipContext ctx = Item.TooltipContext.of(mc.level);
                lines = stack.getTooltipLines(ctx, mc.player, TooltipFlag.Default.NORMAL);
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
        // R/Y policy lives entirely in TranslationService#shouldFullyRetranslateOnKeyPress
        // (2026-10-02): the AI engine always does a full invalidate+resend (tooltips
        // already auto-translate on hover under AI, so a deliberate key press can only
        // mean "redo it"); the machine-translation engine follows the on-screen-state rule
        // (buy only what's missing while something is still in flight, full invalidate
        // once nothing is outstanding) — see that method's doc for the full rationale,
        // including the fix for R/Y being a permanent no-op on a persistently-failing line.
        if (service.shouldFullyRetranslateOnKeyPress(requestList)) {
            service.retranslate(requestList);
        } else {
            service.requestItemLines(requestList);
        }
        FabricTextStyle.clearRenderMemo();
        status(Component.translatable("message.nyanlex.retranslate", stack.getHoverName().getString()).getString());
    }

    public static void testAi(String baseUrl, String model, List<String> keys, java.util.function.Consumer<String> onResult) {
        if (transport == null) {
            onResult.accept(Component.translatable("message.nyanlex.not_initialized").getString());
            return;
        }
        Thread t = new Thread(() -> {
            String msg;
            try {
                OpenAiTranslator ai = new OpenAiTranslator(transport, () -> new AiSettings(baseUrl, model, keys),
                        new RequestPacer(() -> config == null ? 0L : config.requestCooldownMs));
                ai.setTokenUsage(tokenUsage);
                String out = ai.translate("Hello, world", "zh-TW").translatedText();
                msg = Component.translatable("message.nyanlex.success", "Hello, world -> " + out).getString();
            } catch (Exception e) {
                msg = Component.translatable("message.nyanlex.failed", e.getMessage()).getString();
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
            onResult.accept("Codex is not initialized");
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
                result = "Codex: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
            final String message = result;
            Minecraft client = Minecraft.getInstance();
            if (client != null) client.execute(() -> onResult.accept(message));
            else onResult.accept(message);
        }, "nyanlex-codex-test");
        thread.setDaemon(true);
        thread.start();
    }

    public static void translationFile(boolean importing) {
        TranslationService currentService = service;
        if (currentService == null) return;
        com.dragonmeow.nyanlex.translate.TranslationFileDialog.open(importing,
                currentService::exportTranslations, currentService::importTranslations, message -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) client.execute(() -> {
                        status(message);
                        refreshCurrentQuestScreen();
                    });
                });
    }

    private static void status(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.gui.getChat().addMessage(Component.translatable("message.nyanlex.prefix", msg));
        }
    }
}
