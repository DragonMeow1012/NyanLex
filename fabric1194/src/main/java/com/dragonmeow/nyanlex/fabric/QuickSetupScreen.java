package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.QuickSetupPanel;
import com.dragonmeow.nyanlex.config.TranslationLanguages;
import com.dragonmeow.nyanlex.config.TranslatorConfig;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.Map;

/**
 * 快速設定: the questionnaire. All pages, choices and the apply-on-完成 rule live in core's
 * {@link QuickSetupPanel}; this screen supplies the game side (AI settings, the language list,
 * key names, pack detection) and forwards input. Escape means 稍後再設定.
 */
public final class QuickSetupScreen extends Screen {
    private final Screen parent;
    private final QuickSetupPanel panel;
    private final Canvas canvas = new Canvas();
    private boolean returningFromAi;
    private boolean returningFromKeys;

    public QuickSetupScreen(Screen parent) {
        super(Component.translatable("nyanlex.setup.title"));
        this.parent = parent;
        this.panel = new QuickSetupPanel(new Host());
    }

    @Override
    protected void init() {
        panel.resize(this.width, this.height);
        // coming back from a screen this one opened (init runs again when it becomes current)
        if (returningFromAi) {
            returningFromAi = false;
            panel.aiSettingsClosed();
        }
        if (returningFromKeys) {
            returningFromKeys = false;
            panel.refresh();
        }
    }

    // ------------------------------------------------------------------ canvas / host

    private final class Canvas implements com.dragonmeow.nyanlex.config.UiCanvas {
        PoseStack g;

        @Override public void fill(int x, int y, int w, int h, int argb) { GuiComponent.fill(g, x, y, x + w, y + h, argb); }

        @Override public void text(String text, int x, int y, int argb) { font.draw(g, text, x, y, argb); }

        @Override public void pushClip(int x, int y, int w, int h) { GuiComponent.enableScissor(x, y, x + w, y + h); }

        @Override public void popClip() { GuiComponent.disableScissor(); }
    }

    private final class Host implements QuickSetupPanel.Host {
        @Override public TranslatorConfig config() { return NyanLexFabric.config(); }

        @Override public void saveConfig() { NyanLexFabric.saveConfig(); }

        @Override
        public String text(String key, Object... args) { return Component.translatable(key, args).getString(); }

        @Override public int textWidth(String text) { return font == null ? text.length() * 6 : font.width(text); }

        @Override public boolean aiConfigured() { return NyanLexFabric.aiConfigured(); }

        @Override
        public void openAiSettings() {
            returningFromAi = true;
            minecraft.setScreen(new AiConfigScreen(QuickSetupScreen.this));
        }

        @Override
        public void openLanguagePicker(boolean followGame, String tag) {
            minecraft.setScreen(new TranslationLanguageScreen(QuickSetupScreen.this, followGame, tag,
                    panel::languageChosen));
        }

        @Override
        public void openKeybinds() {
            returningFromKeys = true;
            minecraft.setScreen(new TranslationKeybindScreen(QuickSetupScreen.this));
        }

        @Override
        public String gameLanguageName() {
            return languageName(TranslationLanguages.fromMinecraftCode(minecraft.getLanguageManager().getSelected()));
        }

        @Override
        public String languageName(String tag) {
            if (tag == null) return "";
            for (Map.Entry<String, LanguageInfo> entry : minecraft.getLanguageManager().getLanguages().entrySet()) {
                if (TranslationLanguages.fromMinecraftCode(entry.getKey()).equalsIgnoreCase(tag)) {
                    return entry.getValue().toComponent().getString();
                }
            }
            return tag;
        }

        @Override
        public String keyName(QuickSetupPanel.KeyAction action) {
            KeyMapping key = switch (action) {
                case TRANSLATE_ITEM -> NyanLexFabric.retranslateKeyMapping();
                case TRANSLATE_SCREEN -> NyanLexFabric.screenScanKeyMapping();
                case TOGGLE -> NyanLexFabric.toggleKeyMapping();
            };
            if (key == null || key.isUnbound()) return text("nyanlex.setup.key.unset");
            return key.getTranslatedKeyMessage().getString();
        }

        @Override
        public void applyLanguage(boolean followGame, String tag) {
            TranslatorConfig cfg = NyanLexFabric.config();
            cfg.followGameLanguage = followGame;
            String target = followGame
                    ? TranslationLanguages.fromMinecraftCode(minecraft.getLanguageManager().getSelected())
                    : (tag == null ? cfg.targetLang : tag);
            if (NyanLexFabric.service() != null) NyanLexFabric.service().setTargetLang(target);
            else cfg.targetLang = target;
            FabricTextStyle.clearRenderMemo();
        }

        @Override public void onlineChanged() { NyanLexFabric.clearQuestWidgetPending(); }

        @Override public void startPackDetection() { NyanLexFabric.startPackDetection(); }

        @Override public QuickSetupPanel.PackState packState() { return NyanLexFabric.packState(); }

        @Override public QuickSetupPanel.PackInfo packInfo() { return NyanLexFabric.packInfo(); }

        @Override public boolean packDownloading() { return NyanLexFabric.packDownloading(); }

        @Override public void startPackDownload() { NyanLexFabric.startPackDownload(); }

        @Override public void close() { QuickSetupScreen.this.onClose(); }

        @Override public long nowMs() { return System.currentTimeMillis(); }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Escape is 稍後再設定 and goes through the panel
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        panel.mouseClicked((int) mouseX, (int) mouseY, button);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        panel.mouseDragged((int) mouseX, (int) mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        panel.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        panel.mouseScrolled((int) mouseX, (int) mouseY, scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        panel.keyPressed(keyCode, (modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
        if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
        return true; // every other key does nothing: no button is ever focused unless Tab put it there
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return true;
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        canvas.g = g;
        panel.render(canvas, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
