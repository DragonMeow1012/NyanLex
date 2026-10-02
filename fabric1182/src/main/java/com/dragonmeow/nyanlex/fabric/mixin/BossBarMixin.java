package com.dragonmeow.nyanlex.fabric.mixin;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.fabric.NyanLexFabric;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.InternalRenderGuard;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BossHealthOverlay.class)
public abstract class BossBarMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"), require = 0)
    private int nyanlex$boss(Font font, PoseStack pose, Component text, float x, float y, int color) {
        if (!HookGuard.enter("BossBar.boss")) return font.drawShadow(pose, text, x, y, color);
        try {
            TranslationService service = NyanLexFabric.service();
            Component translated = service == null || text == null ? null
                    : FabricTextStyle.renderTranslated("bossBar", text, service::translateBossBar);
            if (translated == null) return InternalRenderGuard.call(() -> font.drawShadow(pose, text, x, y, color));
            float center = x + font.width(text) / 2f;
            java.util.List<Component> lines = FabricTextStyle.splitLines(translated);
            int ret = 0;
            for (int i = 0; i < lines.size(); i++) {
                Component line = lines.get(i);
                float ly = y - (lines.size() - 1 - i) * FabricTextStyle.STACK_LINE_GAP;
                ret = InternalRenderGuard.call(() -> font.drawShadow(pose, line,
                        center - font.width(line) / 2f, ly, color));
            }
            return ret;
        } catch (Throwable guardError) {
            HookGuard.fail("BossBar.boss", guardError);
            return font.drawShadow(pose, text, x, y, color);
        }
    }
}
