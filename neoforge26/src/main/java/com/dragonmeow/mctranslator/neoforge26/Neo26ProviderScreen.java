package com.dragonmeow.mctranslator.neoforge26;

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

/** Searchable picker for the key-free machine-translation backend on MC 26.2. */
public final class Neo26ProviderScreen extends OptionsSubScreen {
    private static final Component SEARCH_HINT = Component.translatable(
            "screen.mctranslator.provider.search").withStyle(EditBox.SEARCH_HINT_STYLE);
    private ProviderSelectionList providerList;
    private EditBox search;

    public Neo26ProviderScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.mctranslator.provider.title"));
        this.layout.setFooterHeight(48);
    }

    @Override protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 220, 15, Component.empty()));
        this.search.setHint(SEARCH_HINT);
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

    @Override protected void addOptions() {}

    @Override protected void addFooter() {
        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(5));
        footer.defaultCellSetting().alignHorizontallyCenter();
        footer.addChild(new StringWidget(
                Component.translatable("screen.mctranslator.provider.experimental_warning")
                        .withColor(0xFFD080), this.font));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onDone()).width(200).build());
    }

    @Override protected void repositionElements() {
        super.repositionElements();
        if (this.providerList != null) this.providerList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        ProviderSelectionList.Entry selected = this.providerList == null
                ? null : this.providerList.getSelected();
        if (selected != null) choose(selected.provider);
        else this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private void choose(MachineTranslationProvider provider) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        String next = provider == null ? MachineTranslationProvider.GOOGLE.id() : provider.id();
        boolean changed = !next.equals(cfg.machineTranslationProvider);
        cfg.machineTranslationProvider = next;
        if (changed && MctranslatorNeoForge26.service() != null) {
            MctranslatorNeoForge26.service().reloadMachineProvider();
        }
        Neo26TextStyle.clearRenderMemo();
        MctranslatorNeoForge26.saveConfig();
        this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private static Component providerName(MachineTranslationProvider provider) {
        Component name = Component.translatable("screen.mctranslator.provider." + provider.id());
        return provider.experimental()
                ? Component.translatable("screen.mctranslator.provider.experimental", name) : name;
    }

    private final class ProviderSelectionList extends ObjectSelectionList<ProviderSelectionList.Entry> {
        private ProviderSelectionList(Minecraft minecraft) {
            super(minecraft, Neo26ProviderScreen.this.width,
                    Neo26ProviderScreen.this.height - 33 - 48, 33, 22);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
            List<Entry> entries = MachineTranslationProvider.selectable().stream()
                    .filter(provider -> needle.isEmpty() || provider.id().contains(needle)
                            || providerName(provider).getString().toLowerCase(Locale.ROOT).contains(needle))
                    .map(Entry::new).toList();
            this.replaceEntries(entries);
            selectCurrent();
            this.refreshScrollAmount();
        }

        private void selectCurrent() {
            MachineTranslationProvider current = MachineTranslationProvider.fromId(
                    MctranslatorNeoForge26.config().machineTranslationProvider);
            for (Entry entry : this.children()) {
                if (entry.provider == current) {
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
                graphics.centeredText(Neo26ProviderScreen.this.font, this.label,
                        ProviderSelectionList.this.width / 2, this.getContentYMiddle() - 9 / 2,
                        provider.experimental() ? 0xFFFFD080 : 0xFFFFFFFF);
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
