package com.dragonmeow.mctranslator.neoforge;

import com.dragonmeow.mctranslator.config.MachineTranslationProvider;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Searchable picker for the key-free machine-translation backend. */
public final class TranslationMachineProviderScreen extends OptionsSubScreen {
    private ProviderSelectionList providerList;
    private EditBox search;

    public TranslationMachineProviderScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.mctranslator.provider.title"));
        this.layout.setFooterHeight(48);
    }

    @Override protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 220, 15, Component.empty()));
        this.search.setHint(Component.translatable("screen.mctranslator.provider.search"));
        this.search.setResponder(value -> {
            if (this.providerList != null) this.providerList.filterEntries(value);
        });
        this.layout.setHeaderHeight(36);
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
        this.providerList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        ProviderSelectionList.Entry selected = this.providerList.getSelected();
        if (selected != null) choose(selected.provider);
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(MachineTranslationProvider provider) {
        TranslatorConfig cfg = MctranslatorNeoForge.config();
        String next = provider == null ? MachineTranslationProvider.GOOGLE.id() : provider.id();
        boolean changed = !next.equals(cfg.machineTranslationProvider);
        cfg.machineTranslationProvider = next;
        if (changed && MctranslatorNeoForge.service() != null) {
            MctranslatorNeoForge.service().reloadMachineProvider();
        }
        NeoTextStyle.clearRenderMemo();
        MctranslatorNeoForge.saveConfig();
        this.minecraft.setScreen(this.lastScreen);
    }

    private static Component providerName(MachineTranslationProvider provider) {
        Component name = Component.translatable("screen.mctranslator.provider." + provider.id());
        return provider.experimental()
                ? Component.translatable("screen.mctranslator.provider.experimental", name) : name;
    }

    private final class ProviderSelectionList extends ObjectSelectionList<ProviderSelectionList.Entry> {
        private ProviderSelectionList(Minecraft minecraft) {
            super(minecraft, TranslationMachineProviderScreen.this.width,
                    TranslationMachineProviderScreen.this.height - 33 - 48, 33, 22);
            replaceWith("");
        }

        private void filterEntries(String filter) {
            replaceWith(filter);
            this.setScrollAmount(0);
        }

        private void replaceWith(String filter) {
            String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
            List<Entry> entries = MachineTranslationProvider.selectable().stream()
                    .filter(provider -> needle.isEmpty() || provider.id().contains(needle)
                            || providerName(provider).getString().toLowerCase(Locale.ROOT).contains(needle))
                    .map(Entry::new).toList();
            this.replaceEntries(entries);
            selectCurrent();
        }

        private void selectCurrent() {
            MachineTranslationProvider current = MachineTranslationProvider.fromId(
                    MctranslatorNeoForge.config().machineTranslationProvider);
            for (Entry entry : this.children()) {
                if (entry.provider == current) {
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
            private long lastClickTime;

            private Entry(MachineTranslationProvider provider) {
                this.provider = provider;
                this.label = providerName(provider);
            }

            @Override public void render(GuiGraphics graphics, int index, int y, int x, int width,
                                         int height, int mouseX, int mouseY, boolean hovered, float delta) {
                graphics.drawCenteredString(TranslationMachineProviderScreen.this.font, this.label,
                        ProviderSelectionList.this.width / 2, y + height / 2 - 9 / 2,
                        provider.experimental() ? 0xFFD080 : 0xFFFFFF);
            }

            @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (CommonInputs.selected(keyCode)) {
                    select();
                    onDone();
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }

            @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
                select();
                if (Util.getMillis() - this.lastClickTime < 250L) onDone();
                this.lastClickTime = Util.getMillis();
                return super.mouseClicked(mouseX, mouseY, button);
            }

            private void select() { ProviderSelectionList.this.setSelected(this); }
            @Override public Component getNarration() {
                return Component.translatable("narrator.select", this.label);
            }
        }
    }
}
