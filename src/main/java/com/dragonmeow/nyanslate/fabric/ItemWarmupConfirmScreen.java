package com.dragonmeow.nyanslate.fabric;

import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.service.TranslationService;
import com.dragonmeow.nyanslate.warmup.ItemWarmupPlan;
import com.dragonmeow.nyanslate.warmup.ItemWarmupScanner;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;

/**
 * "全物品預熱" scan-and-confirm screen. It never sends anything itself: it dry-runs the
 * item registry (a few tooltips per tick) to count items, cached items and the estimated
 * requests/tokens, shows the cost/429/Hypixel warning and only then lets the player
 * start the background run ({@link ItemWarmupProgressScreen}). Under machine translation
 * it only explains why the feature is unavailable.
 */
public final class ItemWarmupConfirmScreen extends Screen {
    private final Screen parent;
    private ItemWarmupScanner scanner;
    private ItemWarmupPlan plan;
    private Button startButton;

    public ItemWarmupConfirmScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.warmup.title"));
        this.parent = parent;
    }

    private static boolean eligibleEngine() {
        TranslationService s = NyanslateFabric.service();
        return s != null && s.isItemWarmupEngine();
    }

    private boolean inWorld() {
        return this.minecraft != null && this.minecraft.level != null && this.minecraft.player != null;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height - 28;
        startButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanslate.warmup.start"), b -> onStart())
                .bounds(centerX - 125, y, 120, 20).build());
        startButton.active = false;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanslate.warmup.back"), b -> onClose())
                .bounds(centerX + 5, y, 120, 20).build());
        if (eligibleEngine() && inWorld() && scanner == null) {
            scanner = new ItemWarmupScanner(new FabricItemWarmupSource(),
                    new FabricItemWarmupSource.Backend(() -> false));
        }
    }

    @Override
    public void tick() {
        if (scanner != null && plan == null) {
            if (scanner.tick(ItemWarmupScanner.DEFAULT_ITEMS_PER_TICK)) {
                TranslatorConfig cfg = NyanslateFabric.config();
                plan = scanner.plan(cfg.itemWarmupMaxItemsPerSession,
                        NyanslateFabric.tokenUsageSnapshot());
            }
        }
        startButton.active = plan != null && !plan.nothingToDo() && eligibleEngine() && inWorld();
    }

    private void onStart() {
        TranslatorConfig cfg = NyanslateFabric.config();
        cfg.itemWarmupEnabled = true;
        cfg.itemWarmupWarningAcknowledged = true;
        NyanslateFabric.saveConfig();
        NyanslateFabric.itemWarmupDriver().start();
        if (this.minecraft != null) this.minecraft.setScreen(new ItemWarmupProgressScreen(parent));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        g.drawCenteredString(this.font, this.title, centerX, 12, 0xFFFFFFFF);
        int wrap = Math.min(340, this.width - 30);
        int left = centerX - wrap / 2;
        int y = 30;

        if (!eligibleEngine()) {
            y = paragraph(g, Component.translatable("screen.nyanslate.warmup.unavailable.engine"),
                    left, y, wrap, 0xFFFFD700);
            return;
        }
        if (!inWorld()) {
            paragraph(g, Component.translatable("screen.nyanslate.warmup.unavailable.world"),
                    left, y, wrap, 0xFFFFD700);
            return;
        }
        if (plan == null) {
            g.drawCenteredString(this.font,
                    Component.translatable("screen.nyanslate.warmup.scanning",
                            scanner == null ? 0 : scanner.scanned(), scanner == null ? 0 : scanner.total()),
                    centerX, y + 20, 0xFFE0E0E0);
            return;
        }
        g.drawCenteredString(this.font,
                Component.translatable("screen.nyanslate.warmup.summary",
                        plan.totalItems(), plan.cachedItems(), plan.missingItems()),
                centerX, y, 0xFFE0E0E0);
        y += 14;
        if (plan.nothingToDo()) {
            g.drawCenteredString(this.font,
                    Component.translatable("screen.nyanslate.warmup.nothing"), centerX, y, 0xFF80FF80);
            return;
        }
        y = paragraph(g, Component.translatable("screen.nyanslate.warmup.estimate",
                plan.willSubmitItems(), plan.estimatedRequests(), formatTokens(plan.estimatedTokens())),
                left, y, wrap, 0xFFFFD700);
        y += 6;
        for (int i = 1; i <= 4; i++) {
            if (y > this.height - 40) break;
            y = paragraph(g, Component.translatable("screen.nyanslate.warmup.warn." + i),
                    left, y, wrap, i == 3 ? 0xFFFF9090 : 0xFFC0C0C0);
            y += 3;
        }
    }

    private int paragraph(GuiGraphics g, Component text, int x, int y, int width, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, width);
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, x, y, color);
            y += 10;
        }
        return y;
    }

    static String formatTokens(long tokens) {
        if (tokens >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000L) return String.format(Locale.ROOT, "%.0fK", tokens / 1_000.0);
        return Long.toString(tokens);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
