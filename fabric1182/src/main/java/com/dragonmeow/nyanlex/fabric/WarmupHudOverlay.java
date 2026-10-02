package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.config.WarmupHud;
import com.dragonmeow.nyanlex.config.WarmupStatus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.Component;

/** Small progress readout of the item warm-up in the top-left HUD corner. */
final class WarmupHudOverlay {

    private WarmupHudOverlay() {}

    /** In-game HUD: only while no screen is open (a screen draws it itself, see {@link #renderOnScreen}). */
    static void render(PoseStack g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null) return;
        draw(g, false);
    }

    /** Title screen, menus and every other screen: the warm-up keeps running behind them, so show it there too. */
    static void renderOnScreen(net.minecraft.client.gui.screens.Screen screen, PoseStack g) {
        // the settings and warm-up screens show the progress themselves
        if (screen instanceof TranslationConfigScreen || screen instanceof ItemWarmupConfirmScreen
                || screen instanceof ItemWarmupProgressScreen || screen instanceof QuickSetupScreen) return;
        draw(g, true);
    }

    /** In the world the readout sits at the top left; on a menu screen it moves to the top right, clear of the buttons there. */
    private static void draw(PoseStack g, boolean onMenu) {
        TranslatorConfig cfg = NyanLexFabric.config();
        Minecraft mc = Minecraft.getInstance();
        if (cfg == null || mc == null || mc.options.hideGui) return;
        WarmupStatus status = WarmupStatus.of(true, NyanLexFabric.itemWarmupDriver().progress());
        WarmupHud.View view = WarmupHud.view(status, cfg.itemWarmupHud, NyanLexFabric.warmupMsSinceDone(),
                (key, args) -> new net.minecraft.network.chat.TranslatableComponent(key, args).getString());
        if (view == null) return;
        Font font = mc.font;
        int textW = font.width(view.text());
        int w = Math.max(72, textW + 10);
        int x = onMenu ? Minecraft.getInstance().getWindow().getGuiScaledWidth() - w - 4 : 4;
        int y = 4;
        GuiComponent.fill(g, x, y, x + w, y + 20, 0xA0000000);
        GuiComponent.fill(g, x, y, x + w, y + 1, view.color());
        font.drawShadow(g, view.text(), x + 5, y + 4, 0xFFFFFFFF);
        int barW = w - 10;
        GuiComponent.fill(g, x + 5, y + 14, x + 5 + barW, y + 17, 0xFF2A2E3C);
        int fill = (int) (barW * view.fraction());
        if (fill > 0) GuiComponent.fill(g, x + 5, y + 14, x + 5 + fill, y + 17, view.color());
    }
}
