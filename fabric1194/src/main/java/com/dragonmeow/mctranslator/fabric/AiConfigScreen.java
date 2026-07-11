package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.TranslatorConfig;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 翻譯設定 — configure the OpenAI-compatible endpoint used for 精翻: base URL,
 * model id, and one or more API keys (comma-separated, rotated on rate-limits).
 * Preset buttons fill known providers; 測試連接 verifies it works. Saved on close;
 * the AI cache is cleared only if the settings actually changed.
 */
public final class AiConfigScreen extends Screen {

    private final Screen parent;
    private EditBox baseUrlBox;
    private EditBox modelBox;
    private EditBox keysBox;
    private String testResult = "";

    private static final int FIELD_W = 320;
    private static final int Y_URL = 44;
    private static final int Y_MODEL = 86;
    private static final int Y_KEYS = 128;
    private static final int Y_PRESETS = 162;
    private static final int Y_TEST = 188;
    private static final int Y_RESULT = 212;
    private static final int Y_DONE = 226;

    public AiConfigScreen(Screen parent) {
        super(Component.translatable("screen.mctranslator.ai.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorFabric.config();
        int x = this.width / 2 - FIELD_W / 2;

        baseUrlBox = new EditBox(this.font, x, Y_URL, FIELD_W, 20, Component.literal("Base URL"));
        baseUrlBox.setMaxLength(256);
        baseUrlBox.setValue(cfg.aiBaseUrl == null ? "" : cfg.aiBaseUrl);
        addRenderableWidget(baseUrlBox);

        modelBox = new EditBox(this.font, x, Y_MODEL, FIELD_W, 20, Component.literal("Model"));
        modelBox.setMaxLength(128);
        modelBox.setValue(cfg.aiModel == null ? "" : cfg.aiModel);
        addRenderableWidget(modelBox);

        keysBox = new EditBox(this.font, x, Y_KEYS, FIELD_W, 20, Component.literal("API Keys"));
        keysBox.setMaxLength(8000);
        keysBox.setValue(keysForEndpoint(cfg, cfg.aiBaseUrl));
        addRenderableWidget(keysBox);

        int pw = (FIELD_W - 2 * 6) / 3;
        addPreset("Gemini", x, Y_PRESETS, pw,
                "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.1-flash-lite");
        addPreset("OpenAI", x + pw + 6, Y_PRESETS, pw, "https://api.openai.com/v1", "gpt-5.4-mini");
        addPreset("DeepSeek", x + 2 * (pw + 6), Y_PRESETS, pw, "https://api.deepseek.com", "deepseek-chat");

        addRenderableWidget(Button.builder(Component.translatable("screen.mctranslator.ai.test"), b -> {
            testResult = Component.translatable("screen.mctranslator.ai.testing").getString();
            MctranslatorFabric.testAi(baseUrlBox.getValue().trim(), modelBox.getValue().trim(),
                    parseKeys(keysBox.getValue()), r -> this.testResult = r);
        }).bounds(x, Y_TEST, FIELD_W, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(this.width / 2 - 100, Y_DONE, 200, 20).build());
    }

    private void addPreset(String label, int x, int y, int w, String url, String model) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
            TranslatorConfig cfg = MctranslatorFabric.config();
            // remember the key currently shown for the current endpoint, then switch
            cfg.aiKeysByEndpoint.put(endpointKey(baseUrlBox.getValue()), keysBox.getValue());
            baseUrlBox.setValue(url);
            modelBox.setValue(model);
            keysBox.setValue(keysForEndpoint(cfg, url)); // restore this provider's saved key
        }).bounds(x, y, w, 20).build());
    }

    /** The remembered key string for an endpoint (falls back to the active aiApiKeys for the current one). */
    private static String keysForEndpoint(TranslatorConfig cfg, String url) {
        String stored = cfg.aiKeysByEndpoint.get(endpointKey(url));
        if (stored != null) return stored;
        if (endpointKey(url).equals(endpointKey(cfg.aiBaseUrl)) && cfg.aiApiKeys != null && !cfg.aiApiKeys.isEmpty()) {
            return String.join(", ", cfg.aiApiKeys);
        }
        return "";
    }

    private static String endpointKey(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(g, this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        int lx = this.width / 2 - FIELD_W / 2;
        this.font.draw(g, Component.translatable("screen.mctranslator.ai.endpoint"), lx, Y_URL - 11, 0xA0A0A0);
        this.font.draw(g, Component.translatable("screen.mctranslator.ai.model"), lx, Y_MODEL - 11, 0xA0A0A0);
        this.font.draw(g, Component.translatable("screen.mctranslator.ai.keys"), lx, Y_KEYS - 11, 0xA0A0A0);
        this.font.draw(g, Component.translatable("screen.mctranslator.ai.presets"), lx, Y_PRESETS - 11, 0xA0A0A0);

        if (!testResult.isEmpty()) {
            String line = this.font.plainSubstrByWidth(testResult, FIELD_W);
            this.font.draw(g, line, lx, Y_RESULT, 0xFFFFFF);
        }

        String[] hints = {
                Component.translatable("screen.mctranslator.ai.hint.gemini").getString(),
                Component.translatable("screen.mctranslator.ai.hint.providers").getString(),
                Component.translatable("screen.mctranslator.ai.hint.enable").getString(),
        };
        g.pushPose();
        g.scale(0.5f, 0.5f, 1.0f);
        int hy = (Y_DONE + 26) * 2;
        for (String h : hints) {
            this.font.draw(g, h, lx * 2, hy, 0x909090);
            hy += 11;
        }
        g.popPose();
    }

    @Override
    public void onClose() {
        TranslatorConfig cfg = MctranslatorFabric.config();
        String newUrl = baseUrlBox.getValue().trim();
        String newModel = modelBox.getValue().trim();
        List<String> newKeys = parseKeys(keysBox.getValue());
        boolean changed = !newUrl.equals(cfg.aiBaseUrl) || !newModel.equals(cfg.aiModel)
                || !newKeys.equals(cfg.aiApiKeys);

        cfg.aiBaseUrl = newUrl;
        cfg.aiModel = newModel;
        cfg.aiApiKeys = newKeys;
        cfg.aiKeysByEndpoint.put(endpointKey(newUrl), keysBox.getValue()); // remember per provider
        MctranslatorFabric.saveConfig();
        // Provider/model/key changes affect future misses only. Cached translations are
        // permanent; deleting them here made an innocent API-key edit look like data loss.
        if (changed) FabricTextStyle.clearRenderMemo();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    private static List<String> parseKeys(String raw) {
        List<String> keys = new ArrayList<>();
        if (raw != null) {
            for (String k : raw.split("[,\\n]")) {
                String t = k.trim();
                if (!t.isEmpty()) keys.add(t);
            }
        }
        return keys;
    }
}
