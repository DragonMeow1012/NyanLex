package com.dragonmeow.mctranslator.fabric26;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;
import com.dragonmeow.mctranslator.translate.ChatSegmenter;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ModifyGame;
import net.minecraft.network.chat.Component;

public final class ChatHandler {
   private ChatHandler() {
   }

   public static void register(TranslationService service) {
      ClientReceiveMessageEvents.MODIFY_GAME.register((ModifyGame)(message, overlay) -> {
         if (!overlay && service != null && message != null) {
            if (service.chatMode() == DisplayMode.ORIGINAL_ONLY) {
               return message;
            }

            String full = message.getString();
            int cs = ChatSegmenter.contentStart(full);
            boolean hasPrefix = cs > 0 && cs < full.length();
            String content = hasPrefix ? full.substring(cs) : full;
            if (!service.wantsChatTranslation(content)) {
               return message;
            }

            TranslationDecision d = service.translateChat(content);
            return !d.changed() ? message : compose(message, hasPrefix, cs, d);
         } else {
            return message;
         }
      });
   }

   private static Component compose(Component message, boolean hasPrefix, int cs, TranslationDecision d) {
      Component styled = Fabric26TextStyle.styled(d.translated(), Fabric26TextStyle.extract(message), message, cs);
      Component translatedLine = (Component)(hasPrefix ? Component.empty().append(Fabric26TextStyle.takePrefix(message, cs)).append(styled) : styled);
      if (d.mode() == DisplayMode.BOTH) {
         // 原文＋翻譯: clean two-line stack — original on top, translation directly below.
         return Component.empty()
            .append(message)
            .append(Component.literal("\n"))
            .append(translatedLine);
      } else {
         return translatedLine;
      }
   }
}
