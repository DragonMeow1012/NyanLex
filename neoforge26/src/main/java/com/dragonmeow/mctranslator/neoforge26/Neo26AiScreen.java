package com.dragonmeow.mctranslator.neoforge26;

import com.dragonmeow.mctranslator.config.TranslatorConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class Neo26AiScreen extends Screen {
   private final Screen parent;
   private EditBox baseUrlBox;
   private EditBox modelBox;
   private EditBox keysBox;
   private static final int FIELD_W = 320;

   public Neo26AiScreen(Screen parent) {
      super(Component.translatable("screen.mctranslator.ai.title"));
      this.parent = parent;
   }

   protected void init() {
      TranslatorConfig cfg = MctranslatorNeoForge26.config();
      int x = this.width / 2 - 160;
      this.baseUrlBox = new EditBox(this.font, x, 44, 320, 20, Component.literal("Base URL"));
      this.baseUrlBox.setMaxLength(256);
      this.baseUrlBox.setValue(cfg.aiBaseUrl == null ? "" : cfg.aiBaseUrl);
      this.addRenderableWidget(this.baseUrlBox);
      this.modelBox = new EditBox(this.font, x, 86, 320, 20, Component.literal("Model"));
      this.modelBox.setMaxLength(128);
      this.modelBox.setValue(cfg.aiModel == null ? "" : cfg.aiModel);
      this.addRenderableWidget(this.modelBox);
      this.keysBox = new EditBox(this.font, x, 128, 320, 20,
         Component.translatable("screen.mctranslator.ai.keys"));
      this.keysBox.setMaxLength(8000);
      this.keysBox.setValue(keysForEndpoint(cfg, cfg.aiBaseUrl));
      this.addRenderableWidget(this.keysBox);
      int pw = (320 - 3 * 6) / 4;
      this.addPreset("Gemini", x, 158, pw, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.1-flash-lite");
      this.addPreset("OpenAI", x + pw + 6, 158, pw, "https://api.openai.com/v1", "gpt-5.4-mini");
      this.addPreset("DeepSeek", x + 2 * (pw + 6), 158, pw, "https://api.deepseek.com", "deepseek-chat");
      this.addPreset(Component.translatable("screen.mctranslator.ai.custom").getString(),
         x + 3 * (pw + 6), 158, pw, "http://127.0.0.1:11434/v1", "");
      Button[] testBtn = new Button[1];
      testBtn[0] = Button.builder(Component.translatable("screen.mctranslator.ai.test"), b -> {
         b.setMessage(Component.translatable("screen.mctranslator.ai.testing"));
         MctranslatorNeoForge26.testAi(this.baseUrlBox.getValue().trim(), this.modelBox.getValue().trim(), parseKeys(this.keysBox.getValue()), r -> {
            if (testBtn[0] != null) {
               testBtn[0].setMessage(Component.literal(r));
            }
         });
      }).bounds(x, 184, 320, 20).build();
      this.addRenderableWidget(testBtn[0]);
      this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).bounds(this.width / 2 - 100, 210, 200, 20).build());
   }

   private void addPreset(String label, int x, int y, int w, String url, String model) {
      this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
         TranslatorConfig cfg = MctranslatorNeoForge26.config();
         cfg.aiKeysByEndpoint.put(endpointKey(this.baseUrlBox.getValue()), this.keysBox.getValue());
         this.baseUrlBox.setValue(url);
         this.modelBox.setValue(model);
         this.keysBox.setValue(model.isEmpty() ? "" : keysForEndpoint(cfg, url));
      }).bounds(x, y, w, 20).build());
   }

   private static String keysForEndpoint(TranslatorConfig cfg, String url) {
      String stored = cfg.aiKeysByEndpoint.get(endpointKey(url));
      if (stored != null) {
         return stored;
      } else {
         return endpointKey(url).equals(endpointKey(cfg.aiBaseUrl)) && cfg.aiApiKeys != null && !cfg.aiApiKeys.isEmpty()
            ? String.join(", ", cfg.aiApiKeys)
            : "";
      }
   }

   private static String endpointKey(String url) {
      return url == null ? "" : url.trim().replaceAll("/+$", "");
   }

   public void onClose() {
      TranslatorConfig cfg = MctranslatorNeoForge26.config();
      String newUrl = this.baseUrlBox.getValue().trim();
      String newModel = this.modelBox.getValue().trim();
      List<String> newKeys = parseKeys(this.keysBox.getValue());
      boolean changed = !newUrl.equals(cfg.aiBaseUrl) || !newModel.equals(cfg.aiModel) || !newKeys.equals(cfg.aiApiKeys);
      cfg.aiBaseUrl = newUrl;
      cfg.aiModel = newModel;
      cfg.aiApiKeys = newKeys;
      cfg.aiKeysByEndpoint.put(endpointKey(newUrl), this.keysBox.getValue());
      MctranslatorNeoForge26.saveConfig();
      // Editing a provider/model/key must not delete permanent translations.
      if (changed) Neo26TextStyle.clearRenderMemo();

      if (this.minecraft != null) {
         this.minecraft.setScreenAndShow(this.parent);
      }
   }

   private static List<String> parseKeys(String raw) {
      List<String> keys = new ArrayList<>();
      if (raw != null) {
         for (String k : raw.split("[,\\n]")) {
            String t = k.trim();
            if (!t.isEmpty()) {
               keys.add(t);
            }
         }
      }

      return keys;
   }
}
