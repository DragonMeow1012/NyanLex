package com.dragonmeow.nyanlex.neoforge26;

import com.dragonmeow.nyanlex.config.DialogContent;
import com.dragonmeow.nyanlex.config.DialogPanel;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.config.WarmupConfirmDialog;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.warmup.ItemWarmupPlan;
import com.dragonmeow.nyanlex.warmup.ItemWarmupScanner;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * "全物品預熱" scan-and-confirm screen. It never sends anything itself: it dry-runs the item
 * registry (a few tooltips per tick) to count items, cached items and the estimated requests and
 * tokens, shows the cost/429 warnings and only then lets the player start the background run
 * ({@link ItemWarmupProgressScreen}). Under machine translation it only explains why the feature
 * is unavailable. The text is one scrolling card (core's {@link WarmupConfirmDialog} on the shared
 * {@link DialogPanel}): every paragraph wraps, nothing is dropped on a small window, and the
 * [取消] / [開始預先翻譯] buttons stay pinned below it.
 */
public final class ItemWarmupConfirmScreen extends Screen {
    private static final DialogContent.Lang LANG = (key, args) -> Component.translatable(key, args).getString();

    private final Screen parent;
    private final DialogPanel panel;
    private ItemWarmupScanner scanner;
    private ItemWarmupPlan plan;
    private DialogPanel.Content shown;

    public ItemWarmupConfirmScreen(Screen parent) {
        super(Component.translatable("screen.nyanlex.warmup.title"));
        this.parent = parent;
        this.panel = new DialogPanel(text -> this.font == null ? text.length() * 6 : this.font.width(text));
    }

    private static boolean eligibleEngine() {
        TranslationService s = NyanLexNeoForge26.service();
        return s != null && s.isItemWarmupEngine();
    }

    private boolean inWorld() {
        return this.minecraft != null && this.minecraft.level != null && this.minecraft.player != null;
    }

    @Override
    protected void init() {
        if (eligibleEngine() && scanner == null) {
            scanner = new ItemWarmupScanner(new Neo26ItemWarmupSource(),
                    new Neo26ItemWarmupSource.Backend(() -> false));
        }
        panel.setNarration(DialogContent.narration(LANG));
        refresh();
        panel.resize(this.width, this.height);
    }

    @Override
    public void tick() {
        if (scanner != null && plan == null) {
            if (scanner.tick(ItemWarmupScanner.DEFAULT_ITEMS_PER_TICK)) {
                TranslatorConfig cfg = NyanLexNeoForge26.config();
                plan = scanner.plan(cfg.itemWarmupMaxItemsPerSession,
                        NyanLexNeoForge26.tokenUsageSnapshot());
            }
        }
        refresh();
    }

    /** Rebuilds the card only when what it shows changed (the scan counter, the finished plan). */
    private void refresh() {
        WarmupConfirmDialog.Scan scan = scanner == null ? null
                : new WarmupConfirmDialog.Scan(scanner.scanned(), scanner.total(), scanner.failed());
        DialogPanel.Content next = WarmupConfirmDialog.content(eligibleEngine(), scan, plan, inWorld(),
                WarmupConfirmDialog.Last.of(NyanLexNeoForge26.itemWarmupDriver().progress()), LANG);
        if (next.equals(shown)) return;
        shown = next;
        if (panel.hasContent()) panel.update(next);
        else panel.set(next);
    }

    private boolean canStart() {
        return plan != null && !plan.nothingToDo() && eligibleEngine();
    }

    private void handle(int id) {
        if (id == WarmupConfirmDialog.START) {
            if (canStart()) onStart();
        } else if (id == WarmupConfirmDialog.CANCEL) {
            onClose();
        }
    }

    /** 線上翻譯 off: the player is asked on the spot first; either way the run then starts in the background. */
    private void onStart() {
        ConsentOverlay.ask(com.dragonmeow.nyanlex.config.ConsentGate.Kind.WARMUP, this::startNow);
    }

    private void startNow() {
        TranslatorConfig cfg = NyanLexNeoForge26.config();
        cfg.itemWarmupWarningAcknowledged = true;
        NyanLexNeoForge26.saveConfig();
        // the player pressed Start / Continue (after the consent box when online translation was off)
        NyanLexNeoForge26.itemWarmupDriver().start(inWorld());
        // straight back to the screen the player came from; the run goes on in the background
        // (corner readout everywhere, details from the settings card)
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Escape is 取消 and goes through the panel
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        handle(panel.mouseClicked((int) event.x(), (int) event.y(), event.button()));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        panel.mouseDragged((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.mouseScrolled((int) mouseX, (int) mouseY, scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        handle(panel.keyPressed(event.key(), event.hasShiftDown()));
        if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
        return true; // every other key does nothing: no button is ever focused unless Tab put it there
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return true;
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
