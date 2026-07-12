package com.dragonmeow.mctranslator.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable key-free machine-provider picker for MC 1.14-1.15. */
final class LegacyProviderScreen extends Screen {
    private static final String[] PROVIDERS = {
            "google", "youdao", "deepl", "microsoft"
    };

    private final Screen parent;
    private ProviderList providers;
    private EditBox search;

    LegacyProviderScreen(Screen parent) {
        super(new TranslatableComponent("screen.mctranslator.provider.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        providers = new ProviderList(minecraft);
        this.children.add(providers);
        search = new EditBox(font, width / 2 - 100, 24, 200, 20, "");
        search.setResponder(providers::filter);
        addButton(search);
        addButton(new Button(width / 2 - 100, height - 38, 200, 20,
                new TranslatableComponent("gui.done").getString(), button -> applyAndClose()));
        setFocused(search);
    }

    private void applyAndClose() {
        ProviderEntry selected = providers.getSelected();
        if (selected != null) {
            LegacyConfig config = LegacyTranslatorMod.config();
            config.machineTranslationProvider =
                    LegacyConfig.normalizeMachineProvider(selected.provider);
            LegacyTranslatorMod.saveConfig();
        }
        minecraft.setScreen(parent);
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        providers.render(mouseX, mouseY, delta);
        font.drawShadow(title.getString(), width / 2f - font.width(title.getString()) / 2f,
                8, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    private Component providerLabel(String provider) {
        return new TranslatableComponent("screen.mctranslator.provider." + provider);
    }

    private final class ProviderList extends ObjectSelectionList<ProviderEntry> {
        ProviderList(Minecraft minecraft) {
            super(minecraft, LegacyProviderScreen.this.width,
                    LegacyProviderScreen.this.height, 48,
                    LegacyProviderScreen.this.height - 61, 20);
            filter("");
        }

        void filter(String value) {
            String needle = value == null ? "" : value.toLowerCase(Locale.ROOT);
            String current = LegacyConfig.normalizeMachineProvider(
                    LegacyTranslatorMod.config().machineTranslationProvider);
            List<ProviderEntry> entries = new ArrayList<ProviderEntry>();
            ProviderEntry selected = null;
            for (String provider : PROVIDERS) {
                Component label = providerLabel(provider);
                if (!needle.isEmpty()
                        && !provider.toLowerCase(Locale.ROOT).contains(needle)
                        && !label.getString().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                ProviderEntry entry = new ProviderEntry(provider, label);
                entries.add(entry);
                if (provider.equals(current)) selected = entry;
            }
            replaceEntries(entries);
            setSelected(selected);
            setScrollAmount(0);
        }

        @Override public int getRowWidth() { return super.getRowWidth() + 50; }
    }

    private final class ProviderEntry extends ObjectSelectionList.Entry<ProviderEntry> {
        final String provider;
        final Component label;

        ProviderEntry(String provider, Component label) {
            this.provider = provider;
            this.label = label;
        }

        @Override public void render(int index, int y, int x, int rowWidth,
                                     int rowHeight, int mouseX, int mouseY,
                                     boolean hovered, float delta) {
            font.drawShadow(label.getString(),
                    width / 2f - font.width(label.getString()) / 2f,
                    y + 2, 0xFFFFFF);
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            providers.setSelected(this);
            return true;
        }
    }
}
