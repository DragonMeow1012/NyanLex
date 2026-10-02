package com.dragonmeow.nyanslate.fabric;

import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.translate.CodexAppServerClient;
import com.dragonmeow.nyanslate.translate.CodexAppServerClient.ModelOption;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Reasoning-effort picker backed by the selected model's advertised efforts. */
public final class CodexEffortScreen extends OptionsSubScreen {
    private EffortSelectionList effortList;

    public CodexEffortScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanslate.ai.codex.effort_picker.title"));
    }

    @Override protected void init() {
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
        TranslatorConfig cfg = NyanslateFabric.config();
        cfg.codexReasoningEffort = effort;
        NyanslateFabric.saveConfig();
        FabricTextStyle.clearRenderMemo();
        this.minecraft.setScreen(this.lastScreen);
    }

    private List<String> supportedEfforts() {
        CodexAppServerClient client = NyanslateFabric.codexClient();
        if (client == null) return List.of();
        String model = NyanslateFabric.config().codexModel;
        return client.cachedModels().stream()
                .filter(option -> option.model().equals(model))
                .findFirst().map(ModelOption::reasoningEfforts).orElse(List.of());
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            if (this.effortList.getSelected() != null) { onDone(); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void render(PoseStack graphics, int mouseX, int mouseY, float delta) {
        this.effortList.render(graphics, mouseX, mouseY, delta);
        GuiComponent.drawCenteredString(graphics, this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private final class EffortSelectionList extends ObjectSelectionList<EffortSelectionList.Entry> {
        private EffortSelectionList(Minecraft minecraft) {
            super(minecraft, CodexEffortScreen.this.width, CodexEffortScreen.this.height,
                    28, CodexEffortScreen.this.height - 48, 18);
            this.replaceEntries(supportedEfforts().stream().map(Entry::new).toList());
            selectCurrent();
        }

        private void selectCurrent() {
            String active = NyanslateFabric.config().codexReasoningEffort;
            for (Entry entry : this.children()) {
                if (entry.effort.equals(active)) {
                    this.setSelected(entry);
                    this.centerScrollOn(entry);
                    return;
                }
            }
        }

        @Override protected int getScrollbarPosition() { return super.getScrollbarPosition() + 20; }
        @Override public int getRowWidth() { return super.getRowWidth() + 50; }
        @Override protected void renderBackground(PoseStack graphics) {
            CodexEffortScreen.this.renderBackground(graphics);
            GuiComponent.fill(graphics, 0, this.y0, this.width, this.y1, 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String effort;
            private final Component label;
            private long lastClickTime;

            private Entry(String effort) {
                this.effort = effort;
                this.label = Component.literal(effort);
            }

            @Override public void render(PoseStack graphics, int index, int y, int x, int width,
                                         int height, int mouseX, int mouseY, boolean hovered, float delta) {
                GuiComponent.drawCenteredString(graphics, CodexEffortScreen.this.font, this.label,
                        EffortSelectionList.this.width / 2, y + 1, 0xFFFFFF);
            }

            @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
                EffortSelectionList.this.setSelected(this);
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