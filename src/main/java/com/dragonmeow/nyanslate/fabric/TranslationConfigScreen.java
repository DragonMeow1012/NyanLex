package com.dragonmeow.nyanslate.fabric;

import com.dragonmeow.nyanslate.config.SettingAction;
import com.dragonmeow.nyanslate.config.SettingEntry;
import com.dragonmeow.nyanslate.config.SettingsCatalog;
import com.dragonmeow.nyanslate.config.SettingsPanel;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.config.UiCanvas;
import com.dragonmeow.nyanslate.config.UiHost;
import com.dragonmeow.nyanslate.config.WarmupCommand;
import com.dragonmeow.nyanslate.config.WarmupStatus;
import com.dragonmeow.nyanslate.hub.HubDownloadJob;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
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
    private final boolean showIntro;
    private final SettingsPanel panel;
    private final Host host = new Host();
    private final Canvas canvas = new Canvas();
    private String status;
    private long statusUntilMs;

    public TranslationConfigScreen(Screen parent) {
        super(Component.translatable(SettingsCatalog.KEY_TITLE));
        this.parent = parent;
        TranslatorConfig cfg = NyanslateFabric.config();
        this.showIntro = !cfg.settingsIntroSeen;
        if (showIntro) {
            cfg.settingsIntroSeen = true;
            NyanslateFabric.saveConfig();
        }
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
        @Override public TranslatorConfig config() { return NyanslateFabric.config(); }

        @Override public void saveConfig() { NyanslateFabric.saveConfig(); }

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
                case CLEAR_PENDING -> NyanslateFabric.clearFtbPending();
                case CLEAR_DEBUG_LOG_WHEN_OFF -> {
                    if (!NyanslateFabric.config().debugTranslationOverlay) NyanslateFabric.clearDebugLog();
                }
                default -> { }
            }
        }

        @Override
        public boolean beforeToggle(SettingEntry entry) {
            TranslatorConfig cfg = NyanslateFabric.config();
            if (entry.id().equals("share") && !cfg.hubShareConsent && !cfg.hubIntroSeen) {
                confirmShareConsent(); // first time: explain the hub and ask before sharing anything
                return false;
            }
            return true;
        }

        @Override
        public String buttonLabel(SettingEntry entry) {
            if (entry.action() != SettingAction.HUB_DOWNLOAD) return null;
            HubDownloadJob job = NyanslateFabric.hubDownloadJob();
            if (job == null || !job.isRunning()) return null;
            long total = job.totalBytes();
            long done = job.downloadedBytes();
            int percent = total > 0 ? (int) Math.min(100L, done * 100L / total)
                    : (job.totalFiles() > 0 ? job.completedFiles() * 100 / job.totalFiles() : 0);
            return Component.translatable("config.nyanslate.hub.identify.downloading", percent + "%").getString();
        }

        @Override
        public boolean enabled(SettingEntry entry) {
            return entry.action() != SettingAction.HUB_DOWNLOAD || !NyanslateFabric.hubPlanning();
        }

        @Override
        public WarmupStatus warmupStatus() {
            var service = NyanslateFabric.service();
            boolean available = service != null && service.isItemWarmupEngine();
            return WarmupStatus.of(available, NyanslateFabric.itemWarmupDriver().progress());
        }

        @Override
        public void warmupCommand(WarmupCommand command) {
            var driver = NyanslateFabric.itemWarmupDriver();
            switch (command) {
                case START, OPEN_PROGRESS -> NyanslateFabric.openItemWarmupScreen(TranslationConfigScreen.this);
                case PAUSE -> driver.pause();
                case RESUME -> driver.resume();
                case STOP -> {
                    driver.stop();
                    NyanslateFabric.config().itemWarmupEnabled = false;
                    NyanslateFabric.saveConfig();
                }
            }
        }

        @Override public boolean showIntro() { return showIntro; }

        @Override public String modVersion() { return NyanslateFabric.modVersion(); }

        @Override
        public String statusText() {
            return status != null && System.currentTimeMillis() < statusUntilMs ? status : null;
        }

        @Override
        public int translatedCount() {
            return NyanslateFabric.service() == null ? 0 : NyanslateFabric.service().translatedCount();
        }

        @Override
        public int pendingCount() {
            return NyanslateFabric.service() == null ? 0 : NyanslateFabric.service().pendingCount();
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
            case OPEN_HELP -> open(new TranslationHelpScreen(this));
            case OPEN_AI -> open(new AiConfigScreen(this));
            case OPEN_PROVIDER -> open(new TranslationMachineProviderScreen(this));
            case OPEN_DO_NOT_TRANSLATE -> open(new TranslationRequestsScreen(this));
            case OPEN_ITEM_WARMUP -> NyanslateFabric.openItemWarmupScreen(this);
            case HUB_DOWNLOAD -> NyanslateFabric.startHubIdentifyAndPlan(this);
            case HUB_OPEN_REPO -> confirmOpenRepo();
            case HUB_CLEAR -> confirmClearHub();
            case EXPORT_TRANSLATIONS -> NyanslateFabric.translationFile(false);
            case IMPORT_TRANSLATIONS -> NyanslateFabric.translationFile(true);
            case CLEAR_CACHE -> confirmClearCache();
        }
    }

    private void confirm(Component title, Component message, Runnable onYes) {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) onYes.run();
            if (this.minecraft != null) this.minecraft.setScreen(this);
        }, title, message, Component.translatable(SettingsCatalog.KEY_CONFIRM_YES),
                Component.translatable("gui.cancel")));
    }

    /** First enabling of "分享翻譯": show the hub explanation + consent question once. */
    private void confirmShareConsent() {
        confirm(Component.translatable("screen.nyanslate.hub.title"),
                Component.translatable("screen.nyanslate.hub.intro").append("\n\n")
                        .append(Component.translatable("screen.nyanslate.hub.consent.question")), () -> {
                    TranslatorConfig cfg = NyanslateFabric.config();
                    cfg.hubShareConsent = true;
                    cfg.hubIntroSeen = true;
                    NyanslateFabric.saveConfig();
                    panel.invalidate();
                });
    }

    private void confirmClearCache() {
        int count = NyanslateFabric.service() == null ? 0 : NyanslateFabric.service().translatedCount();
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_MESSAGE, count), () -> {
                    if (NyanslateFabric.service() != null) NyanslateFabric.service().clearTranslations();
                    FabricTextStyle.clearRenderMemo();
                    setStatus(Component.translatable("config.nyanslate.cache.cleared"));
                });
    }

    private void confirmClearHub() {
        int count = NyanslateFabric.hubLocalCache().size();
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_HUB_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_HUB_CONFIRM_MESSAGE, count), () -> {
                    int removed = NyanslateFabric.hubLocalCache().size();
                    String language = NyanslateFabric.hubLocalCache().language();
                    NyanslateFabric.hubLocalCache().clearAll();
                    // Also drop this language's sha256 throttling ledger, or the next
                    // identify/download pass would report "already up to date".
                    NyanslateFabric.hubDownloadState().forgetLanguage(language);
                    Component done = Component.translatable("message.nyanslate.hub.cleared", removed);
                    NyanslateFabric.postHubStatus(done.getString());
                    setStatus(done);
                });
    }

    private void confirmOpenRepo() {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) Util.getPlatform().openUri(TranslationHubScreen.HUB_URL);
            if (this.minecraft != null) this.minecraft.setScreen(this);
        }, Component.translatable("screen.nyanslate.hub.open_repo.title"),
                Component.translatable("screen.nyanslate.hub.open_repo.message"),
                Component.translatable("screen.nyanslate.hub.open_repo.confirm"),
                Component.translatable("gui.cancel")));
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
        if (panel.keyPressed(keyCode, ctrl, shift)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        NyanslateFabric.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
