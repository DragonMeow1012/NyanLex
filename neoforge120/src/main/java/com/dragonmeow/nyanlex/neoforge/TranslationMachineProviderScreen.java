package com.dragonmeow.nyanlex.neoforge;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Searchable picker for the key-free machine-translation backend. */
public final class TranslationMachineProviderScreen extends OptionsSubScreen {
    private static final Component WARNING = Component.translatable(
            "screen.nyanlex.provider.experimental_warning").withStyle(ChatFormatting.GOLD);
    private ProviderSelectionList providerList;
    private EditBox search;

    public TranslationMachineProviderScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanlex.provider.title"));
    }

    @Override protected void init() {
        this.providerList = new ProviderSelectionList(this.minecraft);
        this.addWidget(this.providerList);
        this.search = new EditBox(this.font, this.width / 2 - 110, 24, 220, 20, Component.empty());
        this.search.setHint(Component.translatable("screen.nyanlex.provider.search"));
        this.search.setResponder(this.providerList::filterEntries);
        this.addRenderableWidget(this.search);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onDone())
                .bounds(this.width / 2 - 100, this.height - 38, 200, 20).build());
        super.init();
        this.setFocused(this.search);
    }

    private void onDone() {
        ProviderSelectionList.Entry selected = this.providerList.getSelected();
        if (selected != null) choose(selected.provider);
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(MachineTranslationProvider provider) {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        String next = provider == null ? MachineTranslationProvider.GOOGLE.id() : provider.id();
        boolean changed = !next.equals(cfg.machineTranslationProvider);
        cfg.machineTranslationProvider = next;
        if (changed && NyanLexNeoForge.service() != null) {
            NyanLexNeoForge.service().reloadMachineProvider();
        }
        NeoTextStyle.clearRenderMemo();
        NyanLexNeoForge.saveConfig();
        this.minecraft.setScreen(this.lastScreen);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CommonInputs.selected(keyCode) && this.providerList.getSelected() != null) {
            onDone();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.providerList.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(this.font, WARNING, this.width / 2, this.height - 56, 0xFFD080);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private static Component providerName(MachineTranslationProvider provider) {
        Component name = Component.translatable("screen.nyanlex.provider." + provider.id());
        return provider.experimental()
                ? Component.translatable("screen.nyanlex.provider.experimental", name) : name;
    }

    private final class ProviderSelectionList extends ObjectSelectionList<ProviderSelectionList.Entry> {
        private ProviderSelectionList(Minecraft minecraft) {
            super(minecraft, TranslationMachineProviderScreen.this.width,
                    TranslationMachineProviderScreen.this.height, 48,
                    TranslationMachineProviderScreen.this.height - 61, 22);
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
            this.setScrollAmount(0);
        }

        private void selectCurrent() {
            MachineTranslationProvider current = MachineTranslationProvider.fromId(
                    NyanLexNeoForge.config().machineTranslationProvider);
            for (Entry entry : this.children()) {
                if (entry.provider == current) {
                    this.setSelected(entry);
                    this.centerScrollOn(entry);
                    return;
                }
            }
        }

        @Override protected int getScrollbarPosition() { return super.getScrollbarPosition() + 20; }
        @Override public int getRowWidth() { return super.getRowWidth() + 50; }
        @Override protected void renderBackground(GuiGraphics graphics) {
            TranslationMachineProviderScreen.this.renderBackground(graphics);
            graphics.fill(0, this.y0, this.width, this.y1, 0x20204A60);
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
                        ProviderSelectionList.this.width / 2, y + 3,
                        provider.experimental() ? 0xFFD080 : 0xFFFFFF);
            }

            @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button != 0) return false;
                ProviderSelectionList.this.setSelected(this);
                if (Util.getMillis() - this.lastClickTime < 250L) onDone();
                this.lastClickTime = Util.getMillis();
                return true;
            }

            @Override public Component getNarration() {
                return Component.translatable("narrator.select", this.label);
            }
        }
    }
}
