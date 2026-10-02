package com.dragonmeow.nyanlex.neoforge;

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

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import org.lwjgl.glfw.GLFW;

/**
 * 翻譯設定 — card-style settings screen (category sidebar, search box, scrolling cards).
 * All layout, painting and input logic lives in core's {@link SettingsPanel}; this class
 * only supplies the canvas, the text/config/action host and forwards input events.
 */
public final class TranslationConfigScreen extends Screen {

    private static final long STATUS_MS = 4_000L;

    private final Screen parent;
    private final SettingsPanel panel;
    private final Host host = new Host();
    private final Canvas canvas = new Canvas();
    private String status;
    private long statusUntilMs;

    public TranslationConfigScreen(Screen parent) {
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
        GuiGraphics g;

        @Override
        public void fill(int x, int y, int w, int h, int argb) {
            g.fill(x, y, x + w, y + h, argb);
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            g.drawString(font, text, x, y, argb, false);
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
        @Override public TranslatorConfig config() { return NyanLexNeoForge.config(); }

        @Override public void saveConfig() { NyanLexNeoForge.saveConfig(); }

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
                case CLEAR_PENDING -> NyanLexNeoForge.clearQuestWidgetPending();
                case CLEAR_DEBUG_LOG_WHEN_OFF -> {
                    if (!NyanLexNeoForge.config().debugTranslationOverlay) NyanLexNeoForge.clearDebugLog();
                }
                default -> { }
            }
        }

        @Override
        public String buttonLabel(SettingEntry entry) {
            if (entry.action() != SettingAction.HUB_DOWNLOAD) return null;
            HubDownloadJob job = NyanLexNeoForge.hubDownloadJob();
            if (job == null || !job.isRunning()) return null;
            long total = job.totalBytes();
            long done = job.downloadedBytes();
            int percent = total > 0 ? (int) Math.min(100L, done * 100L / total)
                    : (job.totalFiles() > 0 ? job.completedFiles() * 100 / job.totalFiles() : 0);
            return Component.translatable("config.nyanlex.hub.identify.downloading", percent + "%").getString();
        }

        @Override
        public boolean enabled(SettingEntry entry) {
            return entry.action() != SettingAction.HUB_DOWNLOAD || !NyanLexNeoForge.hubPlanning();
        }

        @Override
        public WarmupStatus warmupStatus() {
            var service = NyanLexNeoForge.service();
            boolean available = service != null && service.isItemWarmupEngine();
            return WarmupStatus.of(available, NyanLexNeoForge.itemWarmupDriver().progress());
        }

        @Override
        public void warmupCommand(WarmupCommand command) {
            var driver = NyanLexNeoForge.itemWarmupDriver();
            switch (command) {
                case START, OPEN_PROGRESS -> NyanLexNeoForge.openItemWarmupScreen(TranslationConfigScreen.this);
                case PAUSE -> driver.pause();
                case RESUME -> driver.resume();
                case STOP -> {
                    driver.stop();
                    NyanLexNeoForge.saveConfig();
                }
            }
        }

        @Override
        public java.util.List<FileLocations.Entry> fileLocations() {
            return FileLocations.entries(NyanLexNeoForge.configDirectory(), NyanLexNeoForge.config().targetLang);
        }

        @Override
        public void openFileLocation(FileLocations.Entry entry) {
            if (FileOpener.reveal(entry.path())) return;
            // Fall back to the game's own folder opener on the closest existing folder.
            java.nio.file.Path folder = entry.path().toAbsolutePath().getParent();
            if (folder != null && java.nio.file.Files.isDirectory(folder)) {
                Util.getPlatform().openFile(folder.toFile());
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

        @Override public String modVersion() { return NyanLexNeoForge.modVersion(); }

        @Override
        public String statusText() {
            return status != null && System.currentTimeMillis() < statusUntilMs ? status : null;
        }

        @Override
        public int translatedCount() {
            return NyanLexNeoForge.service() == null ? 0 : NyanLexNeoForge.service().translatedCount();
        }

        @Override
        public int pendingCount() {
            return NyanLexNeoForge.service() == null ? 0 : NyanLexNeoForge.service().pendingCount();
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
        if (this.minecraft != null && next != null) this.minecraft.setScreen(next);
    }

    private void run(SettingAction action) {
        switch (action) {
            case OPEN_LANGUAGE -> open(new TranslationLanguageScreen(this));
            case OPEN_KEYBINDS -> open(new TranslationKeybindScreen(this));
            case OPEN_QUICK_SETUP -> open(new QuickSetupScreen(this));
            case OPEN_MANUAL -> open(new TranslationManualScreen(this));
            case OPEN_GITHUB -> openGithub();
            case OPEN_PRIVACY -> open(new TranslationManualScreen(this, SettingsModel.MANUAL_PRIVACY_SECTION - 1));
            case OPEN_AI -> open(new AiConfigScreen(this));
            case OPEN_DO_NOT_TRANSLATE -> open(new TranslationRequestsScreen(this));
            case OPEN_ITEM_WARMUP -> NyanLexNeoForge.openItemWarmupScreen(this);
            case HUB_DOWNLOAD -> NyanLexNeoForge.startHubIdentifyAndPlan(this);
            case HUB_CLEAR -> confirmClearPacks();
            case EXPORT_TRANSLATIONS -> NyanLexNeoForge.translationFile(false);
            case IMPORT_TRANSLATIONS -> NyanLexNeoForge.translationFile(true);
            case CLEAR_CACHE -> confirmClearCache();
        }
    }

    /** The game's own "open this link?" question first, then the system browser. */
    private void openGithub() {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(confirmed -> {
            if (confirmed) Util.getPlatform().openUri(ProjectLinks.GITHUB_URL);
            if (this.minecraft != null) this.minecraft.setScreen(this);
        }, ProjectLinks.GITHUB_URL, true));
    }

    private void confirm(Component title, Component message, Runnable onYes) {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new ConfirmDialogScreen(this, title, message,
                Component.translatable(SettingsCatalog.KEY_CONFIRM_YES), onYes));
    }

    private void confirmClearCache() {
        int count = NyanLexNeoForge.service() == null ? 0 : NyanLexNeoForge.service().translatedCount();
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_MESSAGE, count), () -> {
                    if (NyanLexNeoForge.service() != null) NyanLexNeoForge.service().clearTranslations();
                    NeoTextStyle.clearRenderMemo();
                    setStatus(Component.translatable("config.nyanlex.cache.cleared"));
                });
    }

    private void confirmClearPacks() {
        if (com.dragonmeow.nyanlex.hub.HubPackCleaner.downloadedCount(NyanLexNeoForge.hubLocalCache()) == 0) {
            NyanLexNeoForge.toast(Component.translatable("message.nyanlex.hub.toast_title"),
                    Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_NONE));
            return;
        }
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_CONFIRM_MESSAGE), () -> {
                    int removed = com.dragonmeow.nyanlex.hub.HubPackCleaner.clear(
                            NyanLexNeoForge.hubLocalCache(), NyanLexNeoForge.hubDownloadState());
                    NeoTextStyle.clearRenderMemo();
                    NyanLexNeoForge.toast(Component.translatable("message.nyanlex.hub.toast_title"),
                            Component.translatable(SettingsCatalog.KEY_CLEAR_PACKS_DONE, removed));
                });
    }

    private void setStatus(Component message) {
        status = message.getString();
        statusUntilMs = System.currentTimeMillis() + STATUS_MS;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (panel.mouseClicked((int) mouseX, (int) mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (panel.mouseDragged((int) mouseX, (int) mouseY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        panel.mouseReleased((int) mouseX, (int) mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (panel.mouseScrolled((int) mouseX, (int) mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (panel.keyPressed(keyCode, ctrl, shift)) {
            if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (panel.charTyped(codePoint)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // dims the world behind the panel
        canvas.g = g;
        panel.render(canvas, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        NyanLexNeoForge.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
