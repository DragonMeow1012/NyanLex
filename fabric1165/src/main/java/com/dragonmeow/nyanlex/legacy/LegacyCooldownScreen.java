package com.dragonmeow.nyanlex.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
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
        super(new TranslatableComponent("screen.nyanlex.cooldown.title"));
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
                new TranslatableComponent("gui.done"), button -> onClose()));
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
