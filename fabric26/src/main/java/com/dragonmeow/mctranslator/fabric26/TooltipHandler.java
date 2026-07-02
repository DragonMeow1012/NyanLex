package com.dragonmeow.mctranslator.fabric26;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.service.TranslationService;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

public final class TooltipHandler {
   private TooltipHandler() {
   }

   public static void register(TranslationService service) {
      ItemTooltipCallback.EVENT.register((ItemTooltipCallback)(stack, tooltipContext, tooltipType, lines) -> {
         if (service != null && lines != null && !lines.isEmpty()) {
            DisplayMode mode = service.tooltipMode();
            if (mode != DisplayMode.ORIGINAL_ONLY) {
               List<String> sources = new ArrayList<>(lines.size());

               for (Component l : lines) {
                  if (l != null) {
                     sources.add(l.getString());
                  }
               }

               service.warmTooltipBatch(sources);
               List<Component> appended = mode == DisplayMode.BOTH ? new ArrayList<>() : null;
               int maxLen = 0;

               for (int i = 0; i < lines.size(); i++) {
                  Component line = (Component)lines.get(i);
                  if (line != null) {
                     if (mode == DisplayMode.BOTH) {
                        maxLen = Math.max(maxLen, line.getString().length());
                     }

                     Component translated = Fabric26TextStyle.renderTranslated("tooltip", line, service::translateItemLine);
                     if (translated != null) {
                        if (mode == DisplayMode.BOTH) {
                           appended.add(translated);
                        } else {
                           lines.set(i, translated);
                        }
                     }
                  }
               }

               if (appended != null && !appended.isEmpty()) {
                  lines.add(Fabric26TextStyle.separatorLine(maxLen));
                  lines.addAll(appended);
                  lines.add(Fabric26TextStyle.separatorLine(maxLen));
               }
            }
         }
      });
   }
}
