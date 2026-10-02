package com.dragonmeow.nyanslate.fabric26;

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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
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
public final class Fabric26ConfigScreen extends Screen {

    private static final long STATUS_MS = 4_000L;

    private final Screen parent;
    private final boolean showIntro;
    private final SettingsPanel panel;
    private final Host host = new Host();
    private final Canvas canvas = new Canvas();
    private String status;
    private long statusUntilMs;

    public Fabric26ConfigScreen(Screen parent) {
        super(Component.translatable(SettingsCatalog.KEY_TITLE));
        this.parent = parent;
        TranslatorConfig cfg = NyanslateFabric26.config();
        this.showIntro = !cfg.settingsIntroSeen;
        if (showIntro) {
            cfg.settingsIntroSeen = true;
            NyanslateFabric26.saveConfig();
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
        @Override public TranslatorConfig config() { return NyanslateFabric26.config(); }

        @Override public void saveConfig() { NyanslateFabric26.saveConfig(); }

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
                case CLEAR_PENDING -> NyanslateFabric26.clearFtbPending();
                case CLEAR_DEBUG_LOG_WHEN_OFF -> {
                    if (!NyanslateFabric26.config().debugTranslationOverlay) NyanslateFabric26.clearDebugLog();
                }
                default -> { }
            }
        }

        @Override
        public boolean beforeToggle(SettingEntry entry) {
            TranslatorConfig cfg = NyanslateFabric26.config();
            if (entry.id().equals("share") && !cfg.hubShareConsent && !cfg.hubIntroSeen) {
                confirmShareConsent(); // first time: explain the hub and ask before sharing anything
                return false;
            }
            return true;
        }

        @Override
        public String buttonLabel(SettingEntry entry) {
            if (entry.action() != SettingAction.HUB_DOWNLOAD) return null;
            HubDownloadJob job = NyanslateFabric26.hubDownloadJob();
            if (job == null || !job.isRunning()) return null;
            long total = job.totalBytes();
            long done = job.downloadedBytes();
            int percent = total > 0 ? (int) Math.min(100L, done * 100L / total)
                    : (job.totalFiles() > 0 ? job.completedFiles() * 100 / job.totalFiles() : 0);
            return Component.translatable("config.nyanslate.hub.identify.downloading", percent + "%").getString();
        }

        @Override
        public boolean enabled(SettingEntry entry) {
            return entry.action() != SettingAction.HUB_DOWNLOAD || !NyanslateFabric26.hubPlanning();
        }

        @Override
        public WarmupStatus warmupStatus() {
            var service = NyanslateFabric26.service();
            boolean available = service != null && service.isItemWarmupEngine();
            return WarmupStatus.of(available, NyanslateFabric26.itemWarmupDriver().progress());
        }

        @Override
        public void warmupCommand(WarmupCommand command) {
            var driver = NyanslateFabric26.itemWarmupDriver();
            switch (command) {
                case START, OPEN_PROGRESS -> NyanslateFabric26.openItemWarmupScreen(Fabric26ConfigScreen.this);
                case PAUSE -> driver.pause();
                case RESUME -> driver.resume();
                case STOP -> {
                    driver.stop();
                    NyanslateFabric26.config().itemWarmupEnabled = false;
                    NyanslateFabric26.saveConfig();
                }
            }
        }

        @Override public boolean showIntro() { return showIntro; }

        @Override public String modVersion() { return NyanslateFabric26.modVersion(); }

        @Override
        public String statusText() {
            return status != null && System.currentTimeMillis() < statusUntilMs ? status : null;
        }

        @Override
        public int translatedCount() {
            return NyanslateFabric26.service() == null ? 0 : NyanslateFabric26.service().translatedCount();
        }

        @Override
        public int pendingCount() {
            return NyanslateFabric26.service() == null ? 0 : NyanslateFabric26.service().pendingCount();
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
            case OPEN_LANGUAGE -> open(new Fabric26LanguageScreen(this));
            case OPEN_KEYBINDS -> open(new Fabric26KeybindScreen(this));
            case OPEN_HELP -> open(new Fabric26HelpScreen(this));
            case OPEN_AI -> open(new Fabric26AiScreen(this));
            case OPEN_PROVIDER -> open(new Fabric26ProviderScreen(this));
            case OPEN_DO_NOT_TRANSLATE -> open(new Fabric26RequestsScreen(this));
            case OPEN_ITEM_WARMUP -> NyanslateFabric26.openItemWarmupScreen(this);
            case HUB_DOWNLOAD -> NyanslateFabric26.startHubIdentifyAndPlan(this);
            case HUB_OPEN_REPO -> confirmOpenRepo();
            case HUB_CLEAR -> confirmClearHub();
            case EXPORT_TRANSLATIONS -> NyanslateFabric26.translationFile(false);
            case IMPORT_TRANSLATIONS -> NyanslateFabric26.translationFile(true);
            case CLEAR_CACHE -> confirmClearCache();
        }
    }

    private void confirm(Component title, Component message, Runnable onYes) {
        if (this.minecraft == null) return;
        this.minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
            if (yes) onYes.run();
            if (this.minecraft != null) this.minecraft.setScreenAndShow(this);
        }, title, message, Component.translatable(SettingsCatalog.KEY_CONFIRM_YES),
                Component.translatable("gui.cancel")));
    }

    /** First enabling of "分享翻譯": show the hub explanation + consent question once. */
    private void confirmShareConsent() {
        confirm(Component.translatable("screen.nyanslate.hub.title"),
                Component.translatable("screen.nyanslate.hub.intro").append("\n\n")
                        .append(Component.translatable("screen.nyanslate.hub.consent.question")), () -> {
                    TranslatorConfig cfg = NyanslateFabric26.config();
                    cfg.hubShareConsent = true;
                    cfg.hubIntroSeen = true;
                    NyanslateFabric26.saveConfig();
                    panel.invalidate();
                });
    }

    private void confirmClearCache() {
        int count = NyanslateFabric26.service() == null ? 0 : NyanslateFabric26.service().translatedCount();
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_CACHE_CONFIRM_MESSAGE, count), () -> {
                    if (NyanslateFabric26.service() != null) NyanslateFabric26.service().clearTranslations();
                    Fabric26TextStyle.clearRenderMemo();
                    setStatus(Component.translatable("config.nyanslate.cache.cleared"));
                });
    }

    private void confirmClearHub() {
        int count = NyanslateFabric26.hubLocalCache().size();
        confirm(Component.translatable(SettingsCatalog.KEY_CLEAR_HUB_CONFIRM_TITLE),
                Component.translatable(SettingsCatalog.KEY_CLEAR_HUB_CONFIRM_MESSAGE, count), () -> {
                    int removed = NyanslateFabric26.hubLocalCache().size();
                    String language = NyanslateFabric26.hubLocalCache().language();
                    NyanslateFabric26.hubLocalCache().clearAll();
                    // Also drop this language's sha256 throttling ledger, or the next
                    // identify/download pass would report "already up to date".
                    NyanslateFabric26.hubDownloadState().forgetLanguage(language);
                    Component done = Component.translatable("message.nyanslate.hub.cleared", removed);
                    NyanslateFabric26.postHubStatus(done.getString());
                    setStatus(done);
                });
    }

    private void confirmOpenRepo() {
        if (this.minecraft == null) return;
        this.minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
            if (yes) Util.getPlatform().openUri(Fabric26HubScreen.HUB_URL);
            if (this.minecraft != null) this.minecraft.setScreenAndShow(this);
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
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (panel.keyPressed(event.key(), ctrl, shift)) return true;
        return super.keyPressed(event);
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
        NyanslateFabric26.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(parent);
        }
    }
}
