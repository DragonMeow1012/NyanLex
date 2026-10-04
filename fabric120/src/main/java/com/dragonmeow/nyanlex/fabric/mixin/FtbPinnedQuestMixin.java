package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import com.dragonmeow.nyanlex.translate.HookGuard;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Optional FTB Quests pinned-tracker integration. FTB builds each title/task row as a whole
 * {@link FormattedText} and then asks Minecraft's font to wrap it. Translating that
 * argument preserves FTB's styling and lets its own layout code reflow the translated row.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.FTBQuestsClientEventHandler")
public abstract class FtbPinnedQuestMixin {
    @ModifyArg(
            method = "collectPinnedQuests",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            index = 0,
            require = 0)
    private FormattedText nyanlex$translatePinnedQuestRow(FormattedText text) {
        if (!HookGuard.enter("FtbPinnedQuest.translateRow")) return text;
        try {
            return NyanLexFabric.questHudText(text);
        } catch (Throwable guardError) {
            HookGuard.fail("FtbPinnedQuest.translateRow", guardError);
            return text;
        }
    }
}
