package com.dragonmeow.nyanslate.fabric26;

import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.config.WarmupHud;
import com.dragonmeow.nyanslate.config.WarmupStatus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Small progress readout of the item warm-up in the top-left HUD corner. */
final class WarmupHudOverlay {

    private WarmupHudOverlay() {}

    static void render(GuiGraphicsExtractor g) {
        TranslatorConfig cfg = NyanslateFabric26.config();
        Minecraft mc = Minecraft.getInstance();
        if (cfg == null || mc == null || mc.options.hideGui) return;
        WarmupStatus status = WarmupStatus.of(true, NyanslateFabric26.itemWarmupDriver().progress());
        WarmupHud.View view = WarmupHud.view(status, cfg.itemWarmupHud, NyanslateFabric26.warmupMsSinceDone(),
                (key, args) -> Component.translatable(key, args).getString());
        if (view == null) return;
        Font font = mc.font;
        int textW = font.width(view.text());
        int w = Math.max(72, textW + 10);
        int x = 4;
        int y = 4;
        g.fill(x, y, x + w, y + 20, 0xA0000000);
        g.fill(x, y, x + w, y + 1, view.color());
        g.text(font, view.text(), x + 5, y + 4, 0xFFFFFFFF, true);
        int barW = w - 10;
        g.fill(x + 5, y + 14, x + 5 + barW, y + 17, 0xFF2A2E3C);
        int fill = (int) (barW * view.fraction());
        if (fill > 0) g.fill(x + 5, y + 14, x + 5 + fill, y + 17, view.color());
    }
}
