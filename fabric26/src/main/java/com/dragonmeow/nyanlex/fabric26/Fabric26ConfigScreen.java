package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.FileLocations;
import com.dragonmeow.nyanlex.config.FileOpener;
import com.dragonmeow.nyanlex.config.ProjectLinks;
import com.dragonmeow.nyanlex.config.SettingAction;
import com.dragonmeow.nyanlex.config.SettingEntry;
import com.dragonmeow.nyanlex.config.SettingsCatalog;
import com.dragonmeow.nyanlex.config.SettingsModel;
import com.dragonmeow.nyanlex.config.SettingsPanel;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.config.UiCanvas;
import com.dragonmeow.nyanlex.config.UiHost;
import com.dragonmeow.nyanlex.config.WarmupCommand;
import com.dragonmeow.nyanlex.config.WarmupStatus;
import com.dragonmeow.nyanlex.hub.HubDownloadJob;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;


/**
 * 翻譯設定 — card-style settings screen (category sidebar, search box, scrolling cards).
 * All layout, painting and input logic lives in core's {@link SettingsPanel}; this class
 * only supplies the canvas, the text/config/action host and forwards input events.
 */
public final class Fabric26ConfigScreen extends Screen {

    private static final long STATUS_MS = 4_000L;

    private final Screen parent;
    private final SettingsPanel panel;
    private final Host host = new Host();
    private final Canvas canvas = new Canvas();
    private String status;
    private long statusUntilMs;

    public Fabric26ConfigScreen(Screen parent) {
        super(Component.translatable(SettingsCatalog.KEY_TITLE));
        this.parent = parent;
        this.panel = new SettingsPanel(host);
    }

    /** True while the search box has focus; the mod hotkeys must not fire then. */
    public boolean isTyping() {
        return panel.isTyping();
    }

    @Override
    protected void init() {
        panel.resize(this.width, this.height);
    }

    // ------------------------------------------------------------------ canvas / host

    private final class Canvas implements UiCanvas {
        GuiGraphicsExtractor g;

        @Override
        public void fill(int x, int y, int w, int h, int argb) {
            g.fill(x, y, x + w, y + h, argb);
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            g.text(font, text, x, y, argb, false);
        }

        @Override
        public void pushClip(int x, int y, int w, int h) {
            g.enableScissor(x, y, x + w, y + h);
        }

        @Override
        public void popClip() {
            g.disableScissor();
        }
    }

    private final class Host implements UiHost {
        @Override public TranslatorConfig config() { return NyanLexFabric26.config(); }

        @Override public void saveConfig() { NyanLexFabric26.saveConfig(); }

        @Override
        public String text(String key, Object... args) {
            return Component.translatable(key, args).getString();
        }

        @Override public int textWidth(String text) { return font.width(text); }

        @Override
        public void runAction(SettingAction action) { run(action); }

        @Override
        public void sideEffect(SettingEntry.SideEffect effect) {
            switch (effect) {
                case CLEAR_PENDING -> NyanLexFabric26.clearQuestWidgetPending();
                case CLEAR_DEBUG_LOG_WHEN_OFF -> {
                    if (!NyanLexFabric26.config().debugTranslationOverlay) NyanLexFabric26.clearDebugLog();
                }
                default -> { }
            }
        }

        @Override
        public String buttonLabel(SettingEntry entry) {
            if (entry.action() != SettingAction.HUB_DOWNLOAD) return null;
            HubDownloadJob job = NyanLexFabric26.hubDownloadJob();
            if (job == null || !job.isRunning()) return null;
            long total = job.totalBytes();
            long done = job.downloadedBytes();
            int percent = total > 0 ? (int) Math.min(100L, done * 100L / total)
                    : (job.totalFiles() > 0 ? job.completedFiles() * 100 / job.totalFiles() : 0);
            return Component.translatable("config.nyanlex.hub.identify.downloading", percent + "%").getString();
        }

        @Override
        public boolean enabled(SettingEntry entry) {
            return entry.action() != SettingAction.HUB_DOWNLOAD || !NyanLexFabric26.hubPlanning();
        }

        @Override
        public WarmupStatus warmupStatus() {
            var service = NyanLexFabric26.service();
            boolean available = service != null && service.isItemWarmupEngine();
            return WarmupStatus.of(available, NyanLexFabric26.itemWarmupDriver().progress());
        }

        @Override
        public void warmupCommand(WarmupCommand command) {
            var driver = NyanLexFabric26.itemWarmupDriver();
            switch (command) {
                case START, OPEN_PROGRESS -> NyanLexFabric26.openItemWarmupScreen(Fabric26ConfigScreen.this);
                case PAUSE -> driver.pause();
                case RESUME -> driver.resume();
                case STOP -> {
                    driver.stop();
                    NyanLexFabric26.config().itemWarmupEnabled = false;
                    NyanLexFabric26.saveConfig();
                }
            }
        }

        @Override
        public java.util.List<FileLocations.Entry> fileLocations() {
            return FileLocations.entries(NyanLexFabric26.configDirectory(), NyanLexFabric26.config().targetLang);
        }

        @Override
        public void openFileLocation(FileLocations.Entry entry) {
            if (FileOpener.reveal(entry.path())) return;
            // Fall back to the game's own opener on the closest existing folder.
            java.nio.file.Path folder = entry.path().toAbsolutePath().getParent();
            if (folder != null && java.nio.file.Files.isDirectory(folder)) {
                com.dragonmeow.nyanlex.platform.BrowserLinks.open(folder.toUri().toString());
            }
        }

        @Override
        public String languageName(String tag) {
            if (tag == null || minecraft == null) return null;
            for (java.util.Map.Entry<String, net.minecraft.client.resources.language.LanguageInfo> entry
                    : minecraft.getLanguageManager().getLanguages().entrySet()) {
                if (com.dragonmeow.nyanlex.config.TranslationLanguages.fromMinecraftCode(entry.getKey())
                        .equalsIgnoreCase(tag)) {
                    return entry.getValue().toComponent().getString();
                }
            }
            return null;
        }

        @Override public String modVersion() { return NyanLexFabric26.modVersion(); }

        @Override
        public String statusText() {
            return status != null && System.currentTimeMillis() < statusUntilMs ? status : null;
        }

        @Override
        public int translatedCount() {
            return NyanLexFabric26.service() == null ? 0 : NyanLexFabric26.service().translatedCount();
        }

        @Override
        public int pendingCount() {
            return NyanLexFabric26.service() == null ? 0 : NyanLexFabric26.service().pendingCount();
        }

        @Override
        public String clipboard() {
            return minecraft == null ? "" : minecraft.keyboardHandler.getClipboard();
        }

        @Override
        public void playClick() {
            if (minecraft != null) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
        }

        @Override public void close() { onClose(); }
    }

    // ------------------------------------------------------------------ actions

    private void open(Screen next) {
        if (this.minecraft != null && next != null) this.minecraft.setScreenAndShow(next);
    }

    private void run(SettingAction action) {
        switch (action) {
            case OPEN_QUICK_SETUP -> open(new QuickSetupScreen(this));
            case OPEN_LANGUAGE -> open(new Fabric26LanguageScreen(this));
            case OPEN_KEYBINDS -> open(new Fabric26KeybindScreen(this));
            case OPEN_MANUAL -> open(new Fabric26ManualScreen(this));
            case OPEN_GITHUB -> openGithub();
            case OPEN_PRIVACY -> open(new Fabric26ManualScreen(this, SettingsModel.MANUAL_PRIVACY_SECTION - 1));
            case OPEN_AI -> open(new Fabric26AiScreen(this));
            case OPEN_DO_NOT_TRANSLATE -> open(new Fabric26RequestsScreen(this));
            case OPEN_ITEM_WARMUP -> NyanLexFabric26.openItemWarmupScreen(this);
            case HUB_DOWNLOAD -> NyanLexFabric26.startHubIdentifyAndPlan(this);
            case HUB_CLEAR -> confirmClearPacks();
            case EXPORT_TRANSLATIONS -> NyanLexFabric26.translationFile(false);
            case IMPORT_TRANSLATIONS -> NyanLexFabric26.translationFile(true);
            case CLEAR_CACHE -> confirmClearCache();
        }
    }

    /** The game's own "open this link?" question first, then the system browser. */
    private void openGithub() {
        if (this.minecraft == null) return;
        this.minecraft.setScreenAndShow(new net.minecraft.client.gui.screens.ConfirmLinkScreen(confirmed -> {
            if (confirmed) com.dragonmeow.nyanlex.platform.BrowserLinks.open(ProjectLinks.GITHUB_URL);
            if (this.minecraft != null) this.minecraft.setScreenAndShow(this);
        }, Component.translatable("chat.link.confirmTrusted"),
                java.net.URI.create(ProjectLinks.GITHUB_URL), true));
    }

    private void confirm(Component title, Component message, Runnable onYes) {
        if (this.minecraft == null) return;
        this.minecraft.setScreenAndShow(new ConfirmDialogScreen(this, title, message,
                Component.translatable(SettingsCatalog.KEY_CONFIRM_YES), onYes));
    }

    private void confirmClearCache() {
        int count = NyanLexFabric26.service() == null ? 0 : NyanLexFabric26.service().translatedCount();
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_MESSAGE, count), () -> {
                    if (NyanLexFabric26.service() != null) NyanLexFabric26.service().clearTranslations();
                    Fabric26TextStyle.clearRenderMemo();
                    setStatus(Component.translatable("config.nyanlex.cache.cleared"));
                });
    }

    private void confirmClearPacks() {
        if (com.dragonmeow.nyanlex.hub.HubPackCleaner.downloadedCount(NyanLexFabric26.hubLocalCache()) == 0) {
            NyanLexFabric26.toast(Component.translatable("message.nyanlex.hub.toast_title"),
                    Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_NONE));
            return;
        }
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_CONFIRM_MESSAGE), () -> {
                    int removed = com.dragonmeow.nyanlex.hub.HubPackCleaner.clear(
                            NyanLexFabric26.hubLocalCache(), NyanLexFabric26.hubDownloadState());
                    Fabric26TextStyle.clearRenderMemo();
                    NyanLexFabric26.toast(Component.translatable("message.nyanlex.hub.toast_title"),
                            Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_DONE, removed));
                });
    }

    private void setStatus(Component message) {
        status = message.getString();
        statusUntilMs = System.currentTimeMillis() + STATUS_MS;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (panel.mouseClicked((int) event.x(), (int) event.y(), event.button())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (panel.mouseDragged((int) event.x(), (int) event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.mouseReleased((int) event.x(), (int) event.y(), event.button());
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (panel.mouseScrolled((int) mouseX, (int) mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean ctrl = event.hasControlDown();
        boolean shift = event.hasShiftDown();
        if (panel.keyPressed(event.key(), ctrl, shift)) {
            if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        boolean used = false;
        if (Character.isBmpCodePoint(event.codepoint())) used = panel.charTyped((char) event.codepoint());
        else if (panel.isTyping()) used = true; // astral symbols are not supported in the search box
        if (used) return true;
        return super.charTyped(event);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick); // dims the world behind the panel
        canvas.g = g;
        panel.render(canvas, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        NyanLexFabric26.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(parent);
        }
    }
}
