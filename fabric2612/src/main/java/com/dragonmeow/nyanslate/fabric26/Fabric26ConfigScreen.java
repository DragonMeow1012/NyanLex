package com.dragonmeow.nyanslate.fabric26;

import com.dragonmeow.nyanslate.config.SettingAction;
import com.dragonmeow.nyanslate.config.SettingEntry;
import com.dragonmeow.nyanslate.config.SettingsCatalog;
import com.dragonmeow.nyanslate.config.SettingsLayout;
import com.dragonmeow.nyanslate.config.SettingsPage;
import com.dragonmeow.nyanslate.config.SettingsRow;
import com.dragonmeow.nyanslate.config.StateText;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.hub.HubDownloadJob;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 翻譯設定 — tabbed settings screen (一般／顯示／AI／請求／倉庫／進階). The page content is
 * declared by core's {@link SettingsCatalog}; geometry comes from {@link SettingsLayout}.
 * This class only renders it: tab row, a list that scrolls by whole rows, a fixed help
 * line (hover or keyboard focus), native tooltips, a "?" button and a one-time hint.
 */
public final class Fabric26ConfigScreen extends Screen {

    private static final long STATUS_MS = 4_000L;

    // ------------------------------------------------------------------ warm-up wiring

    /**
     * "全物品預熱…" is only usable when item tooltips use the AI engine (machine translation
     * has nothing to pre-warm); otherwise the button is disabled with an explanatory note.
     */
    static boolean itemWarmupAvailable() {
        var service = NyanslateFabric26.service();
        return service != null && service.isItemWarmupEngine();
    }

    // ------------------------------------------------------------------ state

    private final class CellButton {
        final SettingEntry entry;
        final SettingsLayout.Cell cell;
        final Button button;
        final boolean baseActive;

        CellButton(SettingEntry entry, SettingsLayout.Cell cell, Button button, boolean baseActive) {
            this.entry = entry;
            this.cell = cell;
            this.button = button;
            this.baseActive = baseActive;
        }
    }

    private final Screen parent;
    private final boolean showIntro;
    private SettingsPage page = SettingsPage.GENERAL;
    private int firstRow;
    /** Scroll position remembered per page, restored when the player returns to a tab. */
    private final java.util.EnumMap<SettingsPage, Integer> pageScroll = new java.util.EnumMap<>(SettingsPage.class);
    /** Widget (tab "tab:N" or entry id) to refocus after the screen is rebuilt / re-entered. */
    private String pendingFocusId;
    private final java.util.Map<String, Button> focusTargets = new java.util.HashMap<>();
    private boolean hubIntroShown;
    private String helpCacheKey;
    private List<FormattedCharSequence> helpCacheLines = List.of();
    private SettingsLayout layout;
    private List<SettingsRow> rows = List.of();
    private final List<CellButton> cellButtons = new ArrayList<>();
    private Button helpButton;
    private boolean draggingScrollbar;
    private Component status;
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
    }

    @Override
    protected void init() {
        cellButtons.clear();
        focusTargets.clear();
        layout = SettingsLayout.of(this.width, this.height, showIntro);
        rows = layout.rowsFor(page);
        firstRow = layout.clampFirstRow(firstRow, rows.size());

        // Tabs.
        List<SettingsPage> pages = SettingsCatalog.pages();
        for (int i = 0; i < pages.size(); i++) {
            SettingsPage p = pages.get(i);
            MutableComponent label = Component.translatable(p.tabKey());
            if (p == page) label = label.withStyle(ChatFormatting.YELLOW);
            final String tabId = "tab:" + i;
            Button tab = this.addRenderableWidget(Button.builder(fit(label, layout.tabW - 6), b -> {
                pendingFocusId = tabId;
                if (p != page) {
                    pageScroll.put(page, firstRow);
                    page = p;
                    firstRow = pageScroll.getOrDefault(p, 0);
                    if (p == SettingsPage.HUB && !hubIntroShown && !NyanslateFabric26.config().hubIntroSeen) {
                        hubIntroShown = true;
                        setStatus(Component.translatable("screen.nyanslate.hub.intro"));
                    }
                    this.rebuildWidgets();
                }
            }).bounds(layout.tabsX + i * (layout.tabW + layout.tabGap), layout.tabsY,
                    layout.tabW, SettingsLayout.TAB_H).build());
            focusTargets.put(tabId, tab);
        }

        helpButton = this.addRenderableWidget(Button.builder(
                        Component.translatable(SettingsCatalog.KEY_HELP_BUTTON).withStyle(ChatFormatting.YELLOW),
                        b -> open(new Fabric26HelpScreen(this)))
                .bounds(layout.helpBtnX, layout.helpBtnY, layout.helpBtnW, layout.helpBtnH)
                .tooltip(Tooltip.create(Component.translatable(SettingsCatalog.KEY_HELP_BUTTON_TIP)))
                .build());

        // Entry buttons (positioned by positionRows()).
        for (SettingsLayout.Cell cell : layout.place(rows)) {
            SettingEntry entry = cell.entry();
            boolean warmup = entry.action() == SettingAction.OPEN_ITEM_WARMUP;
            boolean baseActive = !warmup || itemWarmupAvailable();
            Button button = Button.builder(label(entry, cell), b -> onPress(entry, cell, b))
                    .bounds(cell.x(), layout.listTop, cell.width(), SettingsLayout.BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable(entry.tipKey())))
                    .build();
            this.addRenderableWidget(button);
            cellButtons.add(new CellButton(entry, cell, button, baseActive));
            focusTargets.put(entry.id(), button);
            if (entry.id().equals(pendingFocusId)) {
                // Keep the refocused row inside the visible window.
                if (cell.row() < firstRow) firstRow = cell.row();
                else if (cell.row() >= firstRow + layout.visibleRows) firstRow = cell.row() - layout.visibleRows + 1;
                firstRow = layout.clampFirstRow(firstRow, rows.size());
            }
        }
        positionRows();

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(layout.doneX, layout.doneY, layout.doneW, SettingsLayout.BUTTON_H).build());

        Button refocus = pendingFocusId == null ? null : focusTargets.get(pendingFocusId);
        if (refocus != null && refocus.visible && refocus.active) this.setInitialFocus(refocus);
        else if (showIntro) this.setInitialFocus(helpButton);
    }

    private void positionRows() {
        for (CellButton cb : cellButtons) {
            boolean visible = layout.rowVisible(cb.cell.row(), firstRow);
            cb.button.visible = visible;
            cb.button.active = visible && cb.baseActive;
            cb.button.setY(layout.rowY(cb.cell.row(), firstRow));
        }
    }

    private void scrollTo(int newFirstRow) {
        int clamped = layout.clampFirstRow(newFirstRow, rows.size());
        if (clamped == firstRow) return;
        firstRow = clamped;
        positionRows();
    }

    // ------------------------------------------------------------------ labels

    private static Component toComponent(StateText text) {
        if (text.isLiteral()) return Component.literal(text.literalText());
        Object[] args = new Object[text.args().size()];
        for (int i = 0; i < args.length; i++) {
            Object a = text.args().get(i);
            args[i] = a instanceof StateText nested ? toComponent(nested) : a;
        }
        return Component.translatable(text.key(), args);
    }

    /** Shortens {@code text} with an ellipsis when it would overflow {@code maxWidth} pixels. */
    private Component fit(Component text, int maxWidth) {
        if (this.font == null || this.font.width(text) <= maxWidth) return text;
        String cut = this.font.plainSubstrByWidth(text.getString(),
                Math.max(4, maxWidth - this.font.width("…"))).stripTrailing();
        return Component.literal(cut + "…").withStyle(text.getStyle());
    }

    private Component label(SettingEntry entry, SettingsLayout.Cell cell) {
        return fit(labelRaw(entry, cell.compactLabel()), cell.width() - 8);
    }

    private Component labelRaw(SettingEntry entry, boolean compact) {
        TranslatorConfig cfg = NyanslateFabric26.config();
        if (entry.action() == SettingAction.OPEN_ITEM_WARMUP && !itemWarmupAvailable()) {
            return Component.translatable(entry.labelKey()).append(" ")
                    .append(Component.translatable(SettingsCatalog.KEY_NEEDS_AI).withStyle(ChatFormatting.GRAY));
        }
        if (entry.action() == SettingAction.HUB_DOWNLOAD) {
            Component progress = downloadProgressLabel();
            if (progress != null) return progress;
        }
        StateText state = entry.state(cfg);
        if (state == null) return Component.translatable(entry.labelKey());
        return compact ? toComponent(state) : Component.translatable(entry.labelKey(), toComponent(state));
    }

    /** While a hub download runs the button doubles as its progress readout. */
    private static Component downloadProgressLabel() {
        HubDownloadJob job = NyanslateFabric26.hubDownloadJob();
        if (job == null || !job.isRunning()) return null;
        long total = job.totalBytes();
        long done = job.downloadedBytes();
        int percent = total > 0 ? (int) Math.min(100L, done * 100L / total)
                : (job.totalFiles() > 0 ? job.completedFiles() * 100 / job.totalFiles() : 0);
        return Component.translatable("config.nyanslate.hub.identify.downloading", percent + "%");
    }

    // ------------------------------------------------------------------ actions

    private void onPress(SettingEntry entry, SettingsLayout.Cell cell, Button button) {
        pendingFocusId = entry.id();
        switch (entry.type()) {
            case TOGGLE, CYCLE -> {
                TranslatorConfig cfg = NyanslateFabric26.config();
                if (entry.id().equals("share") && !cfg.hubShareConsent && !cfg.hubIntroSeen) {
                    confirmShareConsent(); // first time: explain the hub and ask before sharing anything
                    return;
                }
                entry.press(cfg);
                switch (entry.sideEffect()) {
                    case CLEAR_PENDING -> NyanslateFabric26.clearFtbPending();
                    case CLEAR_DEBUG_LOG_WHEN_OFF -> {
                        if (!cfg.debugTranslationOverlay) NyanslateFabric26.clearDebugLog();
                    }
                    default -> { }
                }
                NyanslateFabric26.saveConfig(); // also drops the render memo so new engines/modes apply
                button.setMessage(label(entry, cell));
            }
            case SUBSCREEN, ACTION -> run(entry.action());
        }
    }

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
            case OPEN_ITEM_WARMUP -> {
                if (itemWarmupAvailable()) NyanslateFabric26.openItemWarmupScreen(this);
            }
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
        status = message;
        statusUntilMs = System.currentTimeMillis() + STATUS_MS;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (layout != null && layout.needsScrollbar(rows.size()) && scrollY != 0) {
            scrollTo(firstRow + (scrollY > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean overScrollbar(double mx, double my) {
        return layout.needsScrollbar(rows.size())
                && mx >= layout.scrollbarX - 2 && mx <= layout.scrollbarX + SettingsLayout.SCROLLBAR_W + 2
                && my >= layout.listTop && my < layout.listTop + layout.trackHeight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && overScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            scrollTo(layout.firstRowForTrackY(event.y(), rows.size()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar) {
            scrollTo(layout.firstRowForTrackY(event.y(), rows.size()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN) { scrollTo(firstRow + layout.visibleRows); return true; }
        if (event.key() == GLFW.GLFW_KEY_PAGE_UP) { scrollTo(firstRow - layout.visibleRows); return true; }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ render

    private Component currentTip(int mouseX, int mouseY) {
        CellButton focused = null;
        for (CellButton cb : cellButtons) {
            if (!cb.button.visible) continue;
            if (cb.button.isMouseOver(mouseX, mouseY)) return tipOf(cb.entry);
            if (cb.button.isFocused()) focused = cb;
        }
        if (focused != null) return tipOf(focused.entry);
        if (helpButton != null && (helpButton.isMouseOver(mouseX, mouseY) || helpButton.isFocused())) {
            return Component.translatable(SettingsCatalog.KEY_HELP_BUTTON_TIP);
        }
        return null;
    }

    private static Component tipOf(SettingEntry entry) {
        return Component.translatable(SettingsCatalog.KEY_TIP_PREFIX, Component.translatable(entry.tipKey()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Live labels: the hub download button shows its progress while a job runs.
        for (CellButton cb : cellButtons) {
            if (cb.entry.action() == SettingAction.HUB_DOWNLOAD) {
                cb.button.setMessage(label(cb.entry, cb.cell));
                cb.button.active = cb.button.visible && cb.baseActive && !NyanslateFabric26.hubPlanning();
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (layout.titleY >= 0) {
            g.text(this.font, this.title, layout.titleX, layout.titleY, 0xFFFFFFFF);
            drawProgress(g);
        }
        if (showIntro) {
            g.centeredText(this.font, Component.translatable(SettingsCatalog.KEY_INTRO),
                    this.width / 2, layout.introY, 0xFFFFFF55);
        }

        // Scrollbar.
        if (layout.needsScrollbar(rows.size())) {
            int x = layout.scrollbarX;
            g.fill(x, layout.listTop, x + SettingsLayout.SCROLLBAR_W, layout.listTop + layout.trackHeight(), 0x80000000);
            int ty = layout.thumbY(firstRow, rows.size());
            g.fill(x, ty, x + SettingsLayout.SCROLLBAR_W, ty + layout.thumbHeight(rows.size()),
                    draggingScrollbar ? 0xFFFFFFFF : 0xFFA0A0A0);
        }

        // Help line: status message > hovered/focused tip > default tip.
        long now = System.currentTimeMillis();
        Component text;
        int color;
        if (status != null && now < statusUntilMs) {
            text = status;
            color = 0xFF80FF80;
        } else {
            Component tip = currentTip(mouseX, mouseY);
            text = tip != null ? tip : Component.translatable(SettingsCatalog.KEY_DEFAULT_TIP);
            color = tip != null ? 0xFFFFFFFF : 0xFF909090;
        }
        int bx = layout.contentX;
        int bw = layout.contentW;
        g.fill(bx - 2, layout.helpY - 1, bx + bw + 2, layout.helpY + layout.helpLines * SettingsLayout.LINE_H + 1, 0x70000000);
        List<FormattedCharSequence> lines = helpLines(text, bw - 4);
        for (int i = 0; i < Math.min(lines.size(), layout.helpLines); i++) {
            g.text(this.font, lines.get(i), bx + 2, layout.helpY + i * SettingsLayout.LINE_H, color, false);
        }
    }

    /**
     * Wraps the help text into at most {@code layout.helpLines} lines; when it is longer the last
     * line ends with an ellipsis (the full text is still in the button's native tooltip).
     * Cached, because the wrap search is not free and the text rarely changes.
     */
    private List<FormattedCharSequence> helpLines(Component text, int width) {
        String key = text.getString() + '\u0000' + width + '\u0000' + layout.helpLines;
        if (key.equals(helpCacheKey)) return helpCacheLines;
        List<FormattedCharSequence> lines = this.font.split(text, width);
        if (lines.size() > layout.helpLines) {
            String shown = text.getString();
            while (shown.length() > 1) {
                shown = shown.substring(0, shown.length() - 1).stripTrailing();
                lines = this.font.split(Component.literal(shown + "…").withStyle(text.getStyle()), width);
                if (lines.size() <= layout.helpLines) break;
            }
        }
        helpCacheKey = key;
        helpCacheLines = lines;
        return lines;
    }

    /** Single-line "已翻譯：N 進行中：M" to the left of the "?" button, when it fits. */
    private void drawProgress(GuiGraphicsExtractor g) {
        if (NyanslateFabric26.service() == null) return;
        int done = NyanslateFabric26.service().translatedCount();
        int pending = NyanslateFabric26.service().pendingCount();
        Component a = Component.translatable("config.nyanslate.progress.done", done);
        Component b = Component.translatable("config.nyanslate.progress.pending", pending);
        int right = layout.helpBtnX - 6;
        int wb = this.font.width(b);
        int wa = this.font.width(a);
        int xb = right - wb;
        int xa = xb - 8 - wa;
        if (xa < layout.titleX + this.font.width(this.title) + 8) return;
        g.text(this.font, a, xa, 8, 0xFF80FF80, false);
        g.text(this.font, b, xb, 8, pending > 0 ? 0xFFFFD080 : 0xFF808080, false);
    }

    @Override
    public void onClose() {
        NyanslateFabric26.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(parent);
        }
    }
}
