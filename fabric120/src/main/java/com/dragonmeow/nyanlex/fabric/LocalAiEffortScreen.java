package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient.ModelOption;
import com.dragonmeow.nyanlex.translate.ReasoningEffort;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Direct reasoning-effort picker; clicking the current value no longer cycles it. */
public final class LocalAiEffortScreen extends OptionsSubScreen {
    private EffortSelectionList effortList;
    private List<String> customLevels;
    private String customActive;
    private java.util.function.Consumer<String> onCustomSelection;

    /** Pick from {@code levels} and report the choice to {@code selected} instead of the Codex setting. */
    public LocalAiEffortScreen withLevels(List<String> levels, String active,
                                          java.util.function.Consumer<String> selected) {
        this.customLevels = List.copyOf(levels);
        this.customActive = active;
        this.onCustomSelection = selected;
        return this;
    }

    /**
     * Display name of a thinking-strength setting: off is translated, and the five levels
     * keep their API names. The short form fits on a button.
     */
    static Component label(String effort, boolean shortForm) {

        if (ReasoningEffort.OFF.equals(effort)) {
            return Component.translatable(shortForm
                    ? "screen.nyanlex.ai.effort.off.short" : "screen.nyanlex.ai.effort.off");
        }
        return Component.literal(effort);
    }

    public LocalAiEffortScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanlex.ai.codex.effort_picker.title"));
    }

    @Override
    protected void init() {
        this.effortList = new EffortSelectionList(this.minecraft);
        this.addWidget(this.effortList);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onDone())
                .bounds(this.width / 2 - 100, this.height - 38, 200, 20).build());
        super.init();
    }

    private void onDone() {
        EffortSelectionList.Entry selected = this.effortList.getSelected();
        if (selected != null) choose(selected.effort);
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(String effort) {
        if (this.onCustomSelection != null) {
            this.onCustomSelection.accept(effort);
        } else {
            TranslatorConfig cfg = NyanLexFabric.config();
            cfg.codexReasoningEffort = effort;
        }
        NyanLexFabric.saveConfig();
        FabricTextStyle.clearRenderMemo();
        this.minecraft.setScreen(this.lastScreen);
    }

    private List<String> supportedEfforts() {
        if (this.customLevels != null) return this.customLevels;
        CodexAppServerClient client = NyanLexFabric.codexClient();
        if (client == null) return List.of();
        String model = NyanLexFabric.config().codexModel;
        return client.cachedModels().stream()
                .filter(option -> option.model().equals(model))
                .findFirst().map(ModelOption::reasoningEfforts).orElse(List.of());
    }

    private String activeEffort() {
        return NyanLexFabric.config().codexReasoningEffort;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            if (this.effortList.getSelected() != null) {
                onDone();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.effortList.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private final class EffortSelectionList extends ObjectSelectionList<EffortSelectionList.Entry> {
        private EffortSelectionList(Minecraft minecraft) {
            super(minecraft, LocalAiEffortScreen.this.width, LocalAiEffortScreen.this.height,
                    28, LocalAiEffortScreen.this.height - 48, 18);
            this.replaceEntries(supportedEfforts().stream().map(Entry::new).toList());
            selectCurrent();
        }

        private void selectCurrent() {
            String active = LocalAiEffortScreen.this.customLevels != null
                    ? LocalAiEffortScreen.this.customActive : activeEffort();
            for (Entry entry : this.children()) {
                if (entry.effort.equals(active)) {
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
            LocalAiEffortScreen.this.renderBackground(graphics);
            graphics.fill(0, this.y0, this.width, this.y1, 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String effort;
            private final Component label;
            private long lastClickTime;

            private Entry(String effort) {
                this.effort = effort;
                this.label = LocalAiEffortScreen.this.customLevels != null
                        ? LocalAiEffortScreen.label(effort, false) : Component.literal(effort);
            }

            @Override
            public void render(GuiGraphics graphics, int index, int y, int x, int width,
                               int height, int mouseX, int mouseY, boolean hovered, float delta) {
                graphics.drawCenteredString(LocalAiEffortScreen.this.font, this.label,
                        EffortSelectionList.this.width / 2, y + 1, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                EffortSelectionList.this.setSelected(this);
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
