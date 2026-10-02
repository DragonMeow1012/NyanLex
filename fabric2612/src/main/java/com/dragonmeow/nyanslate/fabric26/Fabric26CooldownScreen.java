package com.dragonmeow.nyanslate.fabric26;

import com.dragonmeow.nyanslate.config.TranslatorConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 翻譯請求冷卻設定 (MC 26.x) — the two outbound-pacing cycles ({@link TranslatorConfig#requestCooldownMs}
 * and {@link TranslatorConfig#batchWindowMs}) moved off the crowded main settings screen.
 * Values and cycling behaviour are unchanged; only the buttons' home moved.
 */
public final class Fabric26CooldownScreen extends Screen {

    private static final int W = 280;
    private final Screen parent;

    public Fabric26CooldownScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.cooldown.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanslateFabric26.config();
        int rowWidth = Math.max(120, Math.min(W, this.width - 20));
        int x = this.width / 2 - rowWidth / 2;
        int y = 50;

        this.addRenderableWidget(Button.builder(Fabric26ConfigScreen.cooldownLabel(cfg), b -> {
            cfg.requestCooldownMs = Fabric26ConfigScreen.nextCooldown(cfg.requestCooldownMs);
            NyanslateFabric26.saveConfig();
            b.setMessage(Fabric26ConfigScreen.cooldownLabel(cfg));
        }).bounds(x, y, rowWidth, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Fabric26ConfigScreen.batchWindowLabel(cfg), b -> {
            cfg.batchWindowMs = Fabric26ConfigScreen.nextBatchWindow(cfg.batchWindowMs);
            NyanslateFabric26.saveConfig();
            b.setMessage(Fabric26ConfigScreen.batchWindowLabel(cfg));
        }).bounds(x, y, rowWidth, 20).build());
        y += 32;

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x, y, rowWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        NyanslateFabric26.saveConfig();
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
