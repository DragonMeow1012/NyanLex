package com.dragonmeow.mctranslator.fabric26;

import com.dragonmeow.mctranslator.config.MachineTranslationProvider;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Searchable picker for key-free machine-translation providers. */
public final class Fabric26ProviderScreen extends OptionsSubScreen {
    private ProviderSelectionList providerList;
    private EditBox search;

    public Fabric26ProviderScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.mctranslator.provider.title"));
        this.layout.setFooterHeight(53);
    }

    @Override protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 240, 15, Component.empty()));
        this.search.setHint(Component.translatable("screen.mctranslator.provider.search")
                .withStyle(EditBox.SEARCH_HINT_STYLE));
        this.search.setResponder(value -> {
            if (this.providerList != null) this.providerList.filterEntries(value);
        });
        this.layout.setHeaderHeight(36);
    }

    @Override protected void setInitialFocus() {
        if (this.search != null) this.setInitialFocus(this.search);
        else super.setInitialFocus();
    }

    @Override protected void addContents() {
        this.providerList = this.layout.addToContents(new ProviderSelectionList(this.minecraft));
    }

    @Override protected void addOptions() { }

    @Override protected void addFooter() {
        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical()).spacing(8);
        footer.defaultCellSetting().alignHorizontallyCenter();
        footer.addChild(new StringWidget(
                Component.translatable("screen.mctranslator.provider.experimental_warning"), this.font));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onDone()).build());
    }

    @Override protected void repositionElements() {
        super.repositionElements();
        if (this.providerList != null) this.providerList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        ProviderSelectionList.Entry selected =
                this.providerList == null ? null : this.providerList.getSelected();
        if (selected != null) choose(selected.provider);
        else this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private void choose(MachineTranslationProvider provider) {
        TranslatorConfig cfg = MctranslatorFabric26.config();
        if (!provider.id().equals(MachineTranslationProvider.normalize(cfg.machineTranslationProvider))) {
            cfg.machineTranslationProvider = provider.id();
            if (MctranslatorFabric26.service() != null) {
                MctranslatorFabric26.service().reloadMachineProvider();
            }
        }
        Fabric26TextStyle.clearRenderMemo();
        MctranslatorFabric26.saveConfig();
        this.minecraft.setScreenAndShow(this.lastScreen);
    }

    static Component providerName(MachineTranslationProvider provider) {
        Component base = Component.translatable("screen.mctranslator.provider." + provider.id());
        return provider.experimental()
                ? Component.translatable("screen.mctranslator.provider.experimental", base)
                : base;
    }

    private final class ProviderSelectionList
            extends ObjectSelectionList<ProviderSelectionList.Entry> {
        private ProviderSelectionList(Minecraft minecraft) {
            super(minecraft, Fabric26ProviderScreen.this.width,
                    Fabric26ProviderScreen.this.height - 33 - 53, 33, 18);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
            List<Entry> entries = MachineTranslationProvider.selectable().stream()
                    .filter(provider -> needle.isEmpty()
                            || provider.id().contains(needle)
                            || providerName(provider).getString()
                                    .toLowerCase(Locale.ROOT).contains(needle))
                    .map(Entry::new)
                    .toList();
            this.replaceEntries(entries);
            selectCurrent();
            this.refreshScrollAmount();
        }

        private void selectCurrent() {
            String active = MachineTranslationProvider.normalize(
                    MctranslatorFabric26.config().machineTranslationProvider);
            for (Entry entry : this.children()) {
                if (entry.provider.id().equals(active)) {
                    this.setSelected(entry);
                    this.centerScrollOn(entry);
                    return;
                }
            }
        }

        @Override public int getRowWidth() { return super.getRowWidth() + 50; }

        @Override protected void extractListBackground(GuiGraphicsExtractor graphics) {
            super.extractListBackground(graphics);
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final MachineTranslationProvider provider;
            private final Component label;

            private Entry(MachineTranslationProvider provider) {
                this.provider = provider;
                this.label = providerName(provider);
            }

            @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                 boolean hovered, float delta) {
                graphics.centeredText(Fabric26ProviderScreen.this.font, this.label,
                        ProviderSelectionList.this.width / 2, this.getContentYMiddle() - 9 / 2, -1);
            }

            @Override public boolean keyPressed(KeyEvent event) {
                if (event.isSelection()) {
                    select();
                    onDone();
                    return true;
                }
                return super.keyPressed(event);
            }

            @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                select();
                if (doubleClick) onDone();
                return super.mouseClicked(event, doubleClick);
            }

            private void select() { ProviderSelectionList.this.setSelected(this); }
            @Override public Component getNarration() {
                return Component.translatable("narrator.select", this.label);
            }
        }
    }
}
