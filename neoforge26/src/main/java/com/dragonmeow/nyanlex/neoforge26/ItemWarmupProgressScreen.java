package com.dragonmeow.nyanlex.neoforge26;

import com.dragonmeow.nyanlex.warmup.ItemWarmupDriver;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Progress view of the single shared {@link ItemWarmupDriver}. The run lives on the
 * client tick, independent of this screen: leaving it (Esc / "返回") keeps the warm-up
 * going and reopening it just re-reads the driver's state.
 */
public final class ItemWarmupProgressScreen extends Screen {
    private final Screen parent;
    private Button pauseButton;
    private Button stopButton;

    public ItemWarmupProgressScreen(Screen parent) {
        super(Component.translatable("screen.nyanlex.warmup.progress.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 + 40;
        pauseButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.warmup.pause"), b -> togglePause())
                .bounds(centerX - 185, y, 120, 20).build());
        stopButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.warmup.stop"), b -> {
                    NyanLexNeoForge26.itemWarmupDriver().stop();
                    NyanLexNeoForge26.config().itemWarmupEnabled = false;
                    NyanLexNeoForge26.saveConfig();
                }).bounds(centerX - 60, y, 120, 20).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.warmup.back"), b -> onClose())
                .bounds(centerX + 65, y, 120, 20).build());
    }

    private void togglePause() {
        ItemWarmupDriver d = NyanLexNeoForge26.itemWarmupDriver();
        ItemWarmupDriver.Progress p = d.progress();
        if (p.state() == ItemWarmupDriver.State.PAUSED && p.pauseReason() == ItemWarmupDriver.PauseReason.USER) {
            d.resume();
        } else if (p.state() == ItemWarmupDriver.State.RUNNING) {
            d.pause();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        ItemWarmupDriver.Progress p = NyanLexNeoForge26.itemWarmupDriver().progress();
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        g.centeredText(this.font, this.title, centerX, 12, 0xFFFFFFFF);

        boolean userPaused = p.state() == ItemWarmupDriver.State.PAUSED
                && p.pauseReason() == ItemWarmupDriver.PauseReason.USER;
        pauseButton.setMessage(Component.translatable(
                userPaused ? "screen.nyanlex.warmup.resume" : "screen.nyanlex.warmup.pause"));
        pauseButton.active = p.state() == ItemWarmupDriver.State.RUNNING || userPaused;
        stopButton.active = p.state() == ItemWarmupDriver.State.RUNNING
                || p.state() == ItemWarmupDriver.State.PAUSED;

        int barWidth = Math.min(260, this.width - 40);
        int barX = centerX - barWidth / 2;
        int barY = centerY - 10;
        float fraction = p.totalItems() > 0 ? Math.min(1f, p.scanned() / (float) p.totalItems()) : 0f;
        if (p.state() == ItemWarmupDriver.State.DONE && !p.limitReached()) fraction = 1f;
        g.fill(barX, barY, barX + barWidth, barY + 14, 0xFF303030);
        g.fill(barX, barY, barX + (int) (barWidth * fraction), barY + 14, 0xFF4C8CFF);

        g.centeredText(this.font,
                Component.translatable("screen.nyanlex.warmup.progress.counts",
                        p.scanned(), p.totalItems()), centerX, barY + 20, 0xFFE0E0E0);
        g.centeredText(this.font,
                Component.translatable("screen.nyanlex.warmup.progress.detail",
                        p.submittedItems(), p.skippedCached(), p.sessionLimit()),
                centerX, barY + 32, 0xFFA0A0A0);
        if (p.skippedFailed() > 0) {
            boolean inWorld = this.minecraft != null && this.minecraft.level != null;
            g.centeredText(this.font, Component.translatable(inWorld
                            ? "screen.nyanlex.warmup.skipped.world" : "screen.nyanlex.warmup.skipped",
                            p.skippedFailed()), centerX, barY + 76, 0xFFC0C0C0);
        }

        Component state = Component.translatable("screen.nyanlex.warmup.state."
                + p.state().name().toLowerCase(java.util.Locale.ROOT));
        int color = switch (p.state()) {
            case DONE -> 0xFF80FF80;
            case PAUSED -> 0xFFFFD700;
            case STOPPED -> 0xFFFF9090;
            default -> 0xFFE0E0E0;
        };
        g.centeredText(this.font, state, centerX, barY - 14, color);
        if (p.state() == ItemWarmupDriver.State.PAUSED) {
            g.centeredText(this.font, Component.translatable("screen.nyanlex.warmup.reason."
                    + p.pauseReason().name().toLowerCase(java.util.Locale.ROOT)),
                    centerX, barY + 48, 0xFFFFD700);
        } else if (p.state() == ItemWarmupDriver.State.DONE && p.limitReached()) {
            g.centeredText(this.font,
                    Component.translatable("screen.nyanlex.warmup.progress.limit"),
                    centerX, barY + 48, 0xFFFFD700);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
