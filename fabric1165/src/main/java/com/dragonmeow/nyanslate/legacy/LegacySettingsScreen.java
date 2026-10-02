package com.dragonmeow.nyanslate.legacy;

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
        super(new TranslatableComponent("screen.nyanslate.config.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        // Prominent (but non-blocking) entry point to the help screen, top-left corner.
        addButton(new Button(6, 4, 70, 14,
                new TextComponent("§e").append(new TranslatableComponent("config.nyanslate.help.open")),
                button -> minecraft.setScreen(new LegacyHelpScreen(this))));
        addButton(new Button(width / 2 - 155, 30, 310, 20,
                new TranslatableComponent("config.nyanslate.language",
                        cfg.followGameLanguage ? new TranslatableComponent("config.nyanslate.language.follow",
                                LegacyTranslatorMod.currentTarget(minecraft)) : new TextComponent(cfg.targetLang)),
                button -> minecraft.setScreen(new LegacyLanguageScreen(this))));
        addButton(new Button(width / 2 - 155, 50, 152, 20,
                new TranslatableComponent(cfg.enabled ? "config.nyanslate.enabled" : "config.nyanslate.disabled"),
                button -> { cfg.enabled = !cfg.enabled; init(minecraft, width, height); }));
        addButton(new Button(width / 2 + 3, 50, 152, 20,
                new TranslatableComponent("config.nyanslate.requests.open"),
                button -> minecraft.setScreen(new LegacyRequestsScreen(this))));
        addButton(new Button(width / 2 - 155, 70, 310, 20,
                new TextComponent(cfg.showOriginal ? "Original + Translation" : "Translation Only"),
                button -> { cfg.showOriginal = !cfg.showOriginal; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 90, 152, 20,
                new TextComponent("Engine: " + (cfg.aiEnabled ? "AI" : "GT")),
                button -> { cfg.aiEnabled = !cfg.aiEnabled; init(minecraft, width, height); }));
        addButton(new Button(width / 2 + 3, 90, 152, 20,
                machineFallbackLabel(cfg),
                button -> { cfg.disableGoogleFallbackForAi = !cfg.disableGoogleFallbackForAi; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 110, 310, 20,
                new TranslatableComponent("screen.nyanslate.ai.title"),
                button -> minecraft.setScreen(new LegacyAiConfigScreen(this))));
        // Request cooldown + batch window merged into one submenu (was two half-width cyclers).
        addButton(new Button(width / 2 - 155, 130, 310, 20,
                new TranslatableComponent("config.nyanslate.request_cooldown.open"),
                button -> minecraft.setScreen(new LegacyCooldownScreen(this))));
        addButton(new Button(width / 2 - 155, 150, 310, 20, providerLabel(cfg),
                button -> minecraft.setScreen(new LegacyProviderScreen(this))));
        addButton(new Button(width / 2 - 155, 170, 152, 20,
                new TranslatableComponent("config.nyanslate.debug.short",
                        cfg.debugTranslationOverlay ? "ON" : "OFF"),
                button -> { cfg.debugTranslationOverlay = !cfg.debugTranslationOverlay;
                    if (!cfg.debugTranslationOverlay) LegacyTranslatorMod.TRANSLATOR.clearDebug();
                    init(minecraft, width, height); }));
        addButton(new Button(width / 2 + 3, 170, 152, 20,
                chatDeliveryLabel(cfg),
                button -> { cfg.deliverChatTranslationsInOrder =
                        !cfg.deliverChatTranslationsInOrder;
                    init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, height - 46, 310, 20,
                requestsToggleLabel(cfg),
                button -> { cfg.translationRequestsEnabled = !cfg.translationRequestsEnabled; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, height - 22, 100, 20, new TranslatableComponent("config.nyanslate.translations.export"),
                button -> LegacyTranslatorMod.translationFile(false)));
        addButton(new Button(width / 2 - 50, height - 22, 100, 20, new TranslatableComponent("config.nyanslate.translations.import"),
                button -> LegacyTranslatorMod.translationFile(true)));
        addButton(new Button(width / 2 + 55, height - 22, 100, 20,
                new TranslatableComponent("gui.done"), button -> onClose()));
    }
    static int nextCooldown(int current) {
        int[] values = {0, 1000, 2000, 4000, 6000, 8000, 10000};
        for (int value : values) if (value > current) return value;
        return 0;
    }

    static int nextBatchWindow(int current) {
        int[] values = {0, 1000, 2000, 3000, 5000, 8000, 10000};
        for (int value : values) if (value > current) return value;
        return 0;
    }

    static Component cooldownLabel(LegacyConfig cfg) {
        Component state = cfg.requestCooldownMs <= 0
                ? new TranslatableComponent("config.nyanslate.request_cooldown.off")
                : new TextComponent(cfg.requestCooldownMs + " ms");
        return new TranslatableComponent("config.nyanslate.request_cooldown", state);
    }

    static Component batchWindowLabel(LegacyConfig cfg) {
        Component state = cfg.batchWindowMs <= 0
                ? new TranslatableComponent("config.nyanslate.batch_window.off")
                : new TextComponent(cfg.batchWindowMs / 1000F + " s");
        return new TranslatableComponent("config.nyanslate.batch_window", state);
    }

    /** Same field as before; label text now explains the effect instead of naming "GT". */
    private static Component machineFallbackLabel(LegacyConfig cfg) {
        return new TranslatableComponent("config.nyanslate.ai.machine_fallback",
                cfg.disableGoogleFallbackForAi ? "OFF" : "ON");
    }

    private static Component providerLabel(LegacyConfig cfg) {
        String provider = LegacyConfig.normalizeMachineProvider(cfg.machineTranslationProvider);
        return new TranslatableComponent("config.nyanslate.provider",
                new TranslatableComponent("screen.nyanslate.provider." + provider));
    }

    private static Component chatDeliveryLabel(LegacyConfig cfg) {
        Component mode = new TranslatableComponent(cfg.deliverChatTranslationsInOrder
                ? "config.nyanslate.chat_delivery.ordered"
                : "config.nyanslate.chat_delivery.ready_first");
        return new TranslatableComponent("config.nyanslate.chat_delivery.short", mode);
    }

    private static Component requestsToggleLabel(LegacyConfig cfg) {
        return new TranslatableComponent("screen.nyanslate.requests.toggle",
                cfg.translationRequestsEnabled ? "OFF" : "ON");
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
