package com.dragonmeow.nyanslate.legacy;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * 1.0.7 UI round 3: "翻譯請求冷卻設定…" submenu. Holds the two cyclers that used to share
 * a row on the main settings screen (request cooldown, batch collection window). Save
 * behaviour is unchanged; only the layout moved.
 */
final class LegacyCooldownScreen extends Screen {
    private final Screen parent;

    LegacyCooldownScreen(Screen parent) {
        super(new TranslatableComponent("screen.nyanslate.cooldown.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        addButton(new Button(width / 2 - 155, 50, 310, 20,
                LegacySettingsScreen.cooldownLabel(cfg),
                button -> { cfg.requestCooldownMs = LegacySettingsScreen.nextCooldown(cfg.requestCooldownMs);
                    init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 155, 74, 310, 20,
                LegacySettingsScreen.batchWindowLabel(cfg),
                button -> { cfg.batchWindowMs = LegacySettingsScreen.nextBatchWindow(cfg.batchWindowMs);
                    init(minecraft, width, height); }));
        addButton(new Button(width / 2 - 100, height - 26, 200, 20,
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
