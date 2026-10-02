package com.dragonmeow.nyanlex.legacy;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * API-key entry for the official machine-translation APIs (DeepL, Microsoft Translator),
 * shown after such a source is picked. The key box masks what is typed; the key stays in
 * the local config file and is sent only to the chosen provider.
 */
final class LegacyMachineKeyScreen extends Screen {
    private static final int FIELD_W = 260;

    private final Screen parent;
    private final String provider;
    private EditBox keyBox;
    private EditBox regionBox;

    LegacyMachineKeyScreen(Screen parent, String provider) {
        super(new TranslatableComponent("screen.nyanlex.provider." + provider));
        this.parent = parent;
        this.provider = provider;
    }

    private boolean isMicrosoft() { return "microsoft_api".equals(provider); }

    private static String tr(String key) { return new TranslatableComponent(key).getString(); }

    @Override protected void init() {
        LegacyConfig config = LegacyTranslatorMod.config();
        int x = width / 2 - FIELD_W / 2;
        int y = height / 2 - 40;
        keyBox = new EditBox(font, x, y, FIELD_W, 20, "");
        keyBox.setMaxLength(256);
        keyBox.setValue(isMicrosoft() ? config.microsoftApiKey : config.deeplApiKey);
        keyBox.setFormatter((text, offset) -> {
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < text.length(); i++) masked.append('*');
            return masked.toString();
        });
        addButton(keyBox);
        if (isMicrosoft()) {
            regionBox = new EditBox(font, x, y + 44, FIELD_W, 20, "");
            regionBox.setMaxLength(64);
            regionBox.setValue(config.microsoftApiRegion);
            addButton(regionBox);
        }
        addButton(new Button(width / 2 - 100, y + (isMicrosoft() ? 84 : 40), 200, 20,
                tr("gui.done"), button -> onClose()));
        setFocused(keyBox);
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        renderBackground();
        int x = width / 2 - FIELD_W / 2;
        int y = height / 2 - 40;
        String title = this.title.getString();
        font.drawShadow(title, width / 2f - font.width(title) / 2f, y - 40, 0xFFFFFF);
        font.draw(tr(isMicrosoft() ? "screen.nyanlex.provider.key_microsoft"
                : "screen.nyanlex.provider.key_deepl"), x, y - 12, 0xA0A0A0);
        if (isMicrosoft()) font.draw(tr("screen.nyanlex.provider.region"), x, y + 32, 0xA0A0A0);
        String notice = tr("screen.nyanlex.provider.key_notice");
        font.draw(notice, width / 2f - font.width(notice) / 2f, y + (isMicrosoft() ? 112 : 68), 0x909090);
        super.render(mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        LegacyConfig config = LegacyTranslatorMod.config();
        String key = keyBox.getValue().trim();
        if (isMicrosoft()) {
            config.microsoftApiKey = key;
            config.microsoftApiRegion = regionBox == null ? "" : regionBox.getValue().trim();
        } else {
            config.deeplApiKey = key;
        }
        LegacyTranslatorMod.saveConfig();
        minecraft.setScreen(parent);
    }
}
