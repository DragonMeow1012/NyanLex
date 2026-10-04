package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.AntigravityCliClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
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

import java.util.List;
import java.util.Locale;

/** Searchable model picker shared by the ChatGPT and Google sign-in modes. */
public final class LocalAiModelScreen extends OptionsSubScreen {
    private final boolean antigravity;
    private ModelSelectionList modelList;
    private EditBox search;

    public LocalAiModelScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable(
                NyanLexFabric.config().usesAntigravity()
                        ? "screen.nyanlex.ai.antigravity.model_picker.title"
                        : "screen.nyanlex.ai.codex.model_picker.title"));
        this.antigravity = NyanLexFabric.config().usesAntigravity();
    }

    @Override
    protected void init() {
        this.modelList = new ModelSelectionList(this.minecraft);
        this.addWidget(this.modelList);
        this.search = new EditBox(this.font, this.width / 2 - 120, 24, 240, 20, Component.empty());
        this.search.setHint(Component.translatable(this.antigravity
                ? "screen.nyanlex.ai.antigravity.model_picker.search"
                : "screen.nyanlex.ai.codex.model_picker.search"));
        this.search.setResponder(this.modelList::filterEntries);
        this.addRenderableWidget(this.search);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onDone())
                .bounds(this.width / 2 - 100, this.height - 38, 200, 20).build());
        super.init();
        this.setFocused(this.search);
    }

    private void onDone() {
        ModelSelectionList.Entry selected = this.modelList.getSelected();
        if (selected != null) choose(selected.option);
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(ModelChoice option) {
        TranslatorConfig cfg = NyanLexFabric.config();
        if (this.antigravity) {
            cfg.antigravityModel = option.model();
        } else {
            cfg.codexModel = option.model();
            CodexAppServerClient client = NyanLexFabric.codexClient();
            if (client != null) {
                client.cachedModels().stream()
                        .filter(candidate -> candidate.model().equals(option.model()))
                        .findFirst()
                        .ifPresent(candidate -> AiConfigScreen.normalizeEffort(cfg, candidate));
            }
        }
        NyanLexFabric.saveConfig();
        FabricTextStyle.clearRenderMemo();
        this.minecraft.setScreen(this.lastScreen);
    }

    private List<ModelChoice> models() {
        if (this.antigravity) {
            AntigravityCliClient client = NyanLexFabric.antigravityClient();
            return client == null ? List.of() : client.cachedModels().stream()
                    .map(option -> new ModelChoice(option.model(), option.displayName()))
                    .toList();
        }
        CodexAppServerClient client = NyanLexFabric.codexClient();
        return client == null ? List.of() : client.cachedModels().stream()
                .map(option -> new ModelChoice(option.model(), option.displayName()))
                .toList();
    }

    private String activeModel() {
        TranslatorConfig cfg = NyanLexFabric.config();
        return this.antigravity ? cfg.antigravityModel : cfg.codexModel;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            if (this.modelList.getSelected() != null) {
                onDone();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.modelList.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private record ModelChoice(String model, String displayName) {
    }

    private final class ModelSelectionList extends ObjectSelectionList<ModelSelectionList.Entry> {
        private ModelSelectionList(Minecraft minecraft) {
            super(minecraft, LocalAiModelScreen.this.width, LocalAiModelScreen.this.height,
                    48, LocalAiModelScreen.this.height - 48, 18);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
            this.replaceEntries(models().stream()
                    .filter(option -> needle.isEmpty()
                            || option.model().toLowerCase(Locale.ROOT).contains(needle)
                            || option.displayName().toLowerCase(Locale.ROOT).contains(needle))
                    .map(Entry::new).toList());
            selectCurrent();
            this.setScrollAmount(0);
        }

        private void selectCurrent() {
            String active = activeModel();
            for (Entry entry : this.children()) {
                if (entry.option.model().equals(active)) {
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
            LocalAiModelScreen.this.renderBackground(graphics);
            graphics.fill(0, this.y0, this.width, this.y1, 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final ModelChoice option;
            private final Component label;
            private long lastClickTime;

            private Entry(ModelChoice option) {
                this.option = option;
                this.label = Component.literal(option.displayName().equals(option.model())
                        ? option.displayName() : option.displayName() + " (" + option.model() + ")");
            }

            @Override
            public void render(GuiGraphics graphics, int index, int y, int x, int width,
                               int height, int mouseX, int mouseY, boolean hovered, float delta) {
                graphics.drawCenteredString(LocalAiModelScreen.this.font, this.label,
                        ModelSelectionList.this.width / 2, y + 1, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ModelSelectionList.this.setSelected(this);
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
