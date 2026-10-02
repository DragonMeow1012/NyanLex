package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * API-key entry for the official machine-translation APIs (DeepL, Microsoft Translator).
 * Shown only after such a source is picked. The key box masks what is typed; the key is
 * stored in the local config file only and is sent only to the chosen provider.
 */
public final class MachineKeyScreen extends Screen {
    private static final int FIELD_W = 260;

    private final Screen parent;
    private final MachineTranslationProvider provider;
    private EditBox keyBox;
    private EditBox regionBox;

    public MachineKeyScreen(Screen parent, MachineTranslationProvider provider) {
        super(Component.translatable("screen.nyanlex.provider." + provider.id()));
        this.parent = parent;
        this.provider = provider;
    }

    private boolean isMicrosoft() { return provider == MachineTranslationProvider.MICROSOFT_API; }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanLexFabric26.config();
        int x = this.width / 2 - FIELD_W / 2;
        int y = this.height / 2 - 40;
        this.keyBox = new EditBox(this.font, x, y, FIELD_W, 20,
                Component.translatable(isMicrosoft() ? "screen.nyanlex.provider.key_microsoft" : "screen.nyanlex.provider.key_deepl"));
        this.keyBox.setMaxLength(256);
        this.keyBox.setValue(isMicrosoft() ? cfg.microsoftApiKey : cfg.deeplApiKey);
        // Show only bullets, never the key itself.
        this.keyBox.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        this.addRenderableWidget(this.keyBox);
        if (isMicrosoft()) {
            this.regionBox = new EditBox(this.font, x, y + 44, FIELD_W, 20,
                    Component.translatable("screen.nyanlex.provider.region"));
            this.regionBox.setMaxLength(64);
            this.regionBox.setValue(cfg.microsoftApiRegion);
            this.addRenderableWidget(this.regionBox);
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        b -> this.onClose())
                .bounds(this.width / 2 - 100, y + (isMicrosoft() ? 84 : 40), 200, 20).build());
        this.setInitialFocus(this.keyBox);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int x = this.width / 2 - FIELD_W / 2;
        int y = this.height / 2 - 40;
        graphics.centeredText(this.font, this.title, this.width / 2, y - 40, 0xFFFFFFFF);
        graphics.text(this.font, this.keyBox.getMessage(), x, y - 12, 0xFFA0A0A0, false);
        if (isMicrosoft()) {
            graphics.text(this.font, this.regionBox.getMessage(), x, y + 32, 0xFFA0A0A0, false);
        }
        graphics.centeredText(this.font,
                Component.translatable("screen.nyanlex.provider.key_notice"),
                this.width / 2, y + (isMicrosoft() ? 112 : 68), 0xFF909090);
    }

    @Override
    public void onClose() {
        TranslatorConfig cfg = NyanLexFabric26.config();
        String key = this.keyBox.getValue().strip();
        String region = this.regionBox == null ? cfg.microsoftApiRegion : this.regionBox.getValue().strip();
        boolean changed;
        if (isMicrosoft()) {
            changed = !key.equals(cfg.microsoftApiKey) || !region.equals(cfg.microsoftApiRegion);
            cfg.microsoftApiKey = key;
            cfg.microsoftApiRegion = region;
        } else {
            changed = !key.equals(cfg.deeplApiKey);
            cfg.deeplApiKey = key;
        }
        NyanLexFabric26.saveConfig();
        if (changed) Fabric26TextStyle.clearRenderMemo();
        if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent);
    }
}
