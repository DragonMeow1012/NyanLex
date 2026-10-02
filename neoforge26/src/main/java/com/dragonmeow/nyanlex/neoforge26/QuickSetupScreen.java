package com.dragonmeow.nyanlex.neoforge26;

import com.dragonmeow.nyanlex.config.QuickSetupPanel;
import com.dragonmeow.nyanlex.config.TranslationLanguages;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.config.UiCanvas;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.Component;


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

    private final class Canvas implements UiCanvas {
        GuiGraphicsExtractor g;

        @Override public void fill(int x, int y, int w, int h, int argb) { g.fill(x, y, x + w, y + h, argb); }

        @Override public void text(String text, int x, int y, int argb) { g.text(font, text, x, y, argb, false); }

        @Override public void pushClip(int x, int y, int w, int h) { g.enableScissor(x, y, x + w, y + h); }

        @Override public void popClip() { g.disableScissor(); }
    }

    private final class Host implements QuickSetupPanel.Host {
        @Override public TranslatorConfig config() { return NyanLexNeoForge26.config(); }

        @Override public void saveConfig() { NyanLexNeoForge26.saveConfig(); }

        @Override
        public String text(String key, Object... args) { return Component.translatable(key, args).getString(); }

        @Override public int textWidth(String text) { return font == null ? text.length() * 6 : font.width(text); }

        @Override public boolean aiConfigured() { return NyanLexNeoForge26.aiConfigured(); }

        @Override
        public void openAiSettings() {
            returningFromAi = true;
            minecraft.setScreenAndShow(new Neo26AiScreen(QuickSetupScreen.this));
        }

        @Override
        public void openLanguagePicker(boolean followGame, String tag) {
            minecraft.setScreenAndShow(new Neo26LanguageScreen(QuickSetupScreen.this, followGame, tag,
                    panel::languageChosen));
        }

        @Override
        public void openKeybinds() {
            returningFromKeys = true;
            minecraft.setScreenAndShow(new Neo26KeybindScreen(QuickSetupScreen.this));
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
                case TRANSLATE_ITEM -> NyanLexNeoForge26.retranslateKeyMapping();
                case TRANSLATE_SCREEN -> NyanLexNeoForge26.screenScanKeyMapping();
                case TOGGLE -> NyanLexNeoForge26.toggleKeyMapping();
            };
            if (key == null || key.isUnbound()) return text("nyanlex.setup.key.unset");
            return key.getTranslatedKeyMessage().getString();
        }

        @Override
        public void applyLanguage(boolean followGame, String tag) {
            TranslatorConfig cfg = NyanLexNeoForge26.config();
            cfg.followGameLanguage = followGame;
            String target = followGame
                    ? TranslationLanguages.fromMinecraftCode(minecraft.getLanguageManager().getSelected())
                    : (tag == null ? cfg.targetLang : tag);
            if (NyanLexNeoForge26.service() != null) NyanLexNeoForge26.service().setTargetLang(target);
            else cfg.targetLang = target;
            Neo26TextStyle.clearRenderMemo();
        }

        @Override public void onlineChanged() { NyanLexNeoForge26.clearQuestWidgetPending(); }

        @Override public void startPackDetection() { NyanLexNeoForge26.startPackDetection(); }

        @Override public QuickSetupPanel.PackState packState() { return NyanLexNeoForge26.packState(); }

        @Override public QuickSetupPanel.PackInfo packInfo() { return NyanLexNeoForge26.packInfo(); }

        @Override public boolean packDownloading() { return NyanLexNeoForge26.packDownloading(); }

        @Override public void startPackDownload() { NyanLexNeoForge26.startPackDownload(); }

        @Override public void close() { QuickSetupScreen.this.onClose(); }

        @Override public long nowMs() { return System.currentTimeMillis(); }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Escape is 稍後再設定 and goes through the panel
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        panel.mouseClicked((int) event.x(), (int) event.y(), event.button());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        panel.mouseDragged((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.mouseScrolled((int) mouseX, (int) mouseY, scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        panel.keyPressed(event.key(), event.hasShiftDown());
        if (panel.consumeNarrationRequest()) this.triggerImmediateNarration(true);
        return true; // every other key does nothing: no button is ever focused unless Tab put it there
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return true;
    }

    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(panel.narration()));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        canvas.g = g;
        panel.render(canvas, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
