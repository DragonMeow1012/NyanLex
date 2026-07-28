package com.dragonmeow.mctranslator.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

final class LegacySettingsScreen extends Screen {
    private final Screen parent;

    LegacySettingsScreen(Screen parent) {
        super(new TranslatableComponent("screen.mctranslator.config.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        addButton(new Button(width / 2 - 155, 30, 310, 20,
                new TranslatableComponent("config.mctranslator.language",
                        cfg.followGameLanguage ? new TranslatableComponent("config.mctranslator.language.follow",
                                LegacyTranslatorMod.currentTarget(minecraft)) : new TextComponent(cfg.targetLang)),
                button -> minecraft.setScreen(new LegacyLanguageScreen(this))));
        addButton(new Button(width / 2 - 155, 54, 310, 20,
                new TranslatableComponent(cfg.enabled ? "config.mctranslator.enabled" : "config.mctranslator.disabled"),
                button -> { cfg.enabled = !cfg.enabled; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 78, 310, 20,
                new TextComponent(cfg.showOriginal ? "Original + Translation" : "Translation Only"),
                button -> { cfg.showOriginal = !cfg.showOriginal; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 102, 152, 20,
                new TextComponent("Engine: " + (cfg.aiEnabled ? "AI" : "GT")),
                button -> { cfg.aiEnabled = !cfg.aiEnabled; init(minecraft, width, height); }));
        addButton(new Button(width / 2 + 3, 102, 152, 20,
                new TextComponent("AI fallback: " + (cfg.disableGoogleFallbackForAi ? "OFF" : "ON")),
                button -> { cfg.disableGoogleFallbackForAi = !cfg.disableGoogleFallbackForAi; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 126, 310, 20,
                new TranslatableComponent("screen.mctranslator.ai.title"),
                button -> minecraft.setScreen(new LegacyAiConfigScreen(this))));
        addButton(new Button(width / 2 - 155, 150, 152, 20, cooldownLabel(cfg),
                button -> { cfg.requestCooldownMs = nextCooldown(cfg.requestCooldownMs); init(minecraft, width, height); }));
        addButton(new Button(width / 2 + 3, 150, 152, 20, batchWindowLabel(cfg),
                button -> { cfg.batchWindowMs = nextBatchWindow(cfg.batchWindowMs); init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 174, 310, 20, providerLabel(cfg),
                button -> minecraft.setScreen(new LegacyProviderScreen(this))));
        addButton(new Button(width / 2 - 155, 198, 310, 20,
                new TextComponent("Debug overlay: " + (cfg.debugTranslationOverlay ? "ON" : "OFF")),
                button -> { cfg.debugTranslationOverlay = !cfg.debugTranslationOverlay;
                    if (!cfg.debugTranslationOverlay) LegacyTranslatorMod.TRANSLATOR.clearDebug();
                    init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 100, height - 22, 200, 20,
                new TranslatableComponent("gui.done"), button -> onClose()));
    }
    private static int nextCooldown(int current) {
        int[] values = {0, 1000, 2000, 4000, 6000, 8000, 10000};
        for (int value : values) if (value > current) return value;
        return 0;
    }

    private static int nextBatchWindow(int current) {
        int[] values = {0, 1000, 2000, 3000, 5000, 8000, 10000};
        for (int value : values) if (value > current) return value;
        return 0;
    }

    private static Component cooldownLabel(LegacyConfig cfg) {
        Component state = cfg.requestCooldownMs <= 0
                ? new TranslatableComponent("config.mctranslator.request_cooldown.off")
                : new TextComponent(cfg.requestCooldownMs + " ms");
        return new TranslatableComponent("config.mctranslator.request_cooldown", state);
    }

    private static Component batchWindowLabel(LegacyConfig cfg) {
        Component state = cfg.batchWindowMs <= 0
                ? new TranslatableComponent("config.mctranslator.batch_window.off")
                : new TextComponent(cfg.batchWindowMs / 1000F + " s");
        return new TranslatableComponent("config.mctranslator.batch_window", state);
    }

    private static Component providerLabel(LegacyConfig cfg) {
        String provider = LegacyConfig.normalizeMachineProvider(cfg.machineTranslationProvider);
        return new TranslatableComponent("config.mctranslator.provider",
                new TranslatableComponent("screen.mctranslator.provider." + provider));
    }

    @Override public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        renderBackground(pose);
        GuiComponent.drawCenteredString(pose, font, title, width / 2, 20, 0xFFFFFF);
        super.render(pose, mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        LegacyTranslatorMod.saveConfig();
        minecraft.setScreen(parent);
    }
}
