package com.dragonmeow.nyanslate.fabric;

import com.dragonmeow.nyanslate.config.MachineTranslationProvider;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Locale;

/** Native-style searchable picker for the key-free machine translation source. */
public final class TranslationMachineProviderScreen extends OptionsSubScreen {
    private ProviderSelectionList providerList;
    private EditBox search;

    public TranslationMachineProviderScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanslate.machine_provider.title"));
    }

    @Override
    protected void init() {
        this.providerList = new ProviderSelectionList(this.minecraft);
        this.addWidget(this.providerList);
        this.search = new EditBox(this.font, this.width / 2 - 100, 24, 200, 20,
                Component.empty());
        this.search.setHint(Component.translatable(
                "screen.nyanslate.machine_provider.search"));
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
        TranslatorConfig cfg = NyanslateFabric.config();
        String previous = MachineTranslationProvider.normalize(cfg.machineTranslationProvider);
        cfg.machineTranslationProvider = provider.id();
        if (!provider.id().equals(previous) && NyanslateFabric.service() != null) {
            NyanslateFabric.service().reloadMachineProvider();
        }
        FabricTextStyle.clearRenderMemo();
        NyanslateFabric.saveConfig();
        this.minecraft.setScreen(this.lastScreen);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            ProviderSelectionList.Entry selected = this.providerList.getSelected();
            if (selected != null) {
                onDone();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.providerList.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title,
                this.width / 2, 8, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, delta);
    }

    static Component providerLabel(MachineTranslationProvider provider) {
        Component name = Component.translatable(
                "screen.nyanslate.machine_provider." + provider.id());
        return provider.experimental()
                ? Component.translatable(
                        "screen.nyanslate.machine_provider.experimental", name)
                : name;
    }

    private final class ProviderSelectionList
            extends ObjectSelectionList<ProviderSelectionList.Entry> {
        private ProviderSelectionList(Minecraft minecraft) {
            super(minecraft, TranslationMachineProviderScreen.this.width,
                    TranslationMachineProviderScreen.this.height, 48,
                    TranslationMachineProviderScreen.this.height - 61, 22);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
            ArrayList<Entry> entries = new ArrayList<>();
            for (MachineTranslationProvider provider : MachineTranslationProvider.selectable()) {
                Component label = providerLabel(provider);
                String searchable = provider.id() + " " + label.getString();
                if (needle.isEmpty()
                        || searchable.toLowerCase(Locale.ROOT).contains(needle)) {
                    entries.add(new Entry(provider, label));
                }
            }
            this.replaceEntries(entries);
            selectCurrent();
            this.setScrollAmount(0);
        }

        private void selectCurrent() {
            MachineTranslationProvider current = MachineTranslationProvider.fromId(
                    NyanslateFabric.config().machineTranslationProvider);
            for (Entry entry : this.children()) {
                if (entry.provider == current) {
                    this.setSelected(entry);
                    this.centerScrollOn(entry);
                    return;
                }
            }
        }

        @Override
        protected int getScrollbarPosition() {
            return super.getScrollbarPosition() + 20;
        }

        @Override
        public int getRowWidth() {
            return super.getRowWidth() + 50;
        }

        @Override
        protected void renderBackground(GuiGraphics graphics) {
            TranslationMachineProviderScreen.this.renderBackground(graphics);
            graphics.fill(0, this.y0, this.width, this.y1, 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final MachineTranslationProvider provider;
            private final Component label;
            private long lastClickTime;

            private Entry(MachineTranslationProvider provider, Component label) {
                this.provider = provider;
                this.label = label;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int y, int x, int width,
                               int height, int mouseX, int mouseY,
                               boolean hovered, float delta) {
                graphics.drawCenteredString(
                        TranslationMachineProviderScreen.this.font, this.label,
                        ProviderSelectionList.this.width / 2, y + 3, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button != 0) return false;
                ProviderSelectionList.this.setSelected(this);
                if (Util.getMillis() - this.lastClickTime < 250L) onDone();
                this.lastClickTime = Util.getMillis();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", this.label);
            }
        }
    }
}
