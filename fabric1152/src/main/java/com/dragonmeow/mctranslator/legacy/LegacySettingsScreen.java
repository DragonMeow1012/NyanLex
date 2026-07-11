package com.dragonmeow.mctranslator.legacy;



import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

final class LegacySettingsScreen extends Screen {
    private final Screen parent;

    LegacySettingsScreen(Screen parent) {
        super(new TranslatableComponent("screen.mctranslator.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        String languageLabel = cfg.followGameLanguage
                ? new TranslatableComponent("config.mctranslator.language.follow",
                        LegacyTranslatorMod.currentTarget(minecraft)).getString()
                : cfg.targetLang;
        addButton(new Button(width / 2 - 155, 50, 310, 20,
                languageLabel,
                button -> minecraft.setScreen(new LegacyLanguageScreen(this))));
        addButton(new Button(width / 2 - 155, 76, 310, 20,
                new TranslatableComponent(cfg.enabled ? "config.mctranslator.enabled" : "config.mctranslator.disabled").getString(),
                button -> { cfg.enabled = !cfg.enabled; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 102, 310, 20,
                cfg.showOriginal ? "Original + Translation" : "Translation Only",
                button -> { cfg.showOriginal = !cfg.showOriginal; init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 100, height - 38, 200, 20,
                new TranslatableComponent("gui.done").getString(), button -> onClose()));
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        renderBackground();
        font.drawShadow(title.getString(), width / 2f - font.width(title.getString()) / 2f, 20, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        LegacyTranslatorMod.saveConfig();
        minecraft.setScreen(parent);
    }
}
