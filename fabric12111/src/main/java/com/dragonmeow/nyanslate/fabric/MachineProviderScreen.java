package com.dragonmeow.nyanslate.fabric;

import com.dragonmeow.nyanslate.config.MachineTranslationProvider;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
public final class MachineProviderScreen extends OptionsSubScreen {
    private ProviderSelectionList providerList;
    private EditBox search;

    public MachineProviderScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanslate.provider.title"));
        this.layout.setFooterHeight(53);
    }

    @Override protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 240, 15, Component.empty()));
        this.search.setHint(Component.translatable("screen.nyanslate.provider.search"));
        this.search.setResponder(value -> {
            if (this.providerList != null) this.providerList.filterEntries(value);
        });
        this.layout.setHeaderHeight(36);
    }

    @Override protected void addContents() {
        this.providerList = this.layout.addToContents(new ProviderSelectionList(this.minecraft));
    }

    @Override protected void addOptions() { }

    @Override protected void addFooter() {
        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical()).spacing(8);
        footer.defaultCellSetting().alignHorizontallyCenter();
        footer.addChild(new StringWidget(
                Component.translatable("screen.nyanslate.provider.warning"), this.font));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onDone()).build());
    }

    @Override protected void repositionElements() {
        super.repositionElements();
        if (this.providerList != null) this.providerList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        ProviderSelectionList.Entry selected = this.providerList == null ? null : this.providerList.getSelected();
        if (selected != null) choose(selected.provider);
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(MachineTranslationProvider provider) {
        TranslatorConfig cfg = NyanslateFabric.config();
        if (!provider.id().equals(MachineTranslationProvider.normalize(cfg.machineTranslationProvider))) {
            cfg.machineTranslationProvider = provider.id();
            if (NyanslateFabric.service() != null) {
                NyanslateFabric.service().reloadMachineProvider();
            }
        }
        FabricTextStyle.clearRenderMemo();
        NyanslateFabric.saveConfig();
        this.minecraft.setScreen(this.lastScreen);
    }

    static Component providerName(MachineTranslationProvider provider) {
        Component base = Component.translatable("screen.nyanslate.provider." + provider.id());
        return provider.experimental()
                ? Component.translatable("screen.nyanslate.provider.experimental_name", base)
                : base;
    }

    private final class ProviderSelectionList extends ObjectSelectionList<ProviderSelectionList.Entry> {
        private ProviderSelectionList(Minecraft minecraft) {
            super(minecraft, MachineProviderScreen.this.width,
                    MachineProviderScreen.this.height - 33 - 53, 33, 18);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
            List<Entry> entries = MachineTranslationProvider.selectable().stream()
                    .filter(provider -> needle.isEmpty()
                            || provider.id().contains(needle)
                            || providerName(provider).getString().toLowerCase(Locale.ROOT).contains(needle))
                    .map(Entry::new)
                    .toList();
            this.replaceEntries(entries);
            selectCurrent();
            this.setScrollAmount(0);
        }

        private void selectCurrent() {
            String active = MachineTranslationProvider.normalize(
                    NyanslateFabric.config().machineTranslationProvider);
            for (Entry entry : this.children()) {
                if (entry.provider.id().equals(active)) {
                    this.setSelected(entry);
                    this.centerScrollOn(entry);
                    return;
                }
            }
        }

        @Override public int getRowWidth() { return super.getRowWidth() + 50; }

        @Override protected void renderListBackground(GuiGraphics graphics) {
            super.renderListBackground(graphics);
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final MachineTranslationProvider provider;
            private final Component label;

            private Entry(MachineTranslationProvider provider) {
                this.provider = provider;
                this.label = providerName(provider);
            }

            @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY,
                                                boolean hovered, float delta) {
                graphics.drawCenteredString(MachineProviderScreen.this.font, this.label,
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
