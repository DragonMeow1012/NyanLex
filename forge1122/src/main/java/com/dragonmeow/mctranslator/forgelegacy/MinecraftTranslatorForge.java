package com.dragonmeow.mctranslator.forgelegacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;

import java.util.List;

@Mod(modid = "mctranslator", name = "Minecraft Translator", version = "1.0.2", clientSideOnly = true)
public final class MinecraftTranslatorForge {
    private final LegacyTranslator translator = new LegacyTranslator();
    private final KeyBinding toggle = new KeyBinding("key.mctranslator.toggle", Keyboard.KEY_G,
            "category.mctranslator");
    private volatile boolean enabled = true;
    private volatile boolean internal;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(toggle);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent public void onInput(InputUpdateEvent event) {
        if (toggle.isPressed()) enabled = !enabled;
    }

    @SubscribeEvent public void onChat(ClientChatReceivedEvent event) {
        if (!enabled || internal || event.getMessage() == null) return;
        final ITextComponent source = event.getMessage();
        final String text = source.getUnformattedText();
        if (!hasLetters(text)) return;
        event.setCanceled(true);
        final Minecraft minecraft = Minecraft.getMinecraft();
        translator.translate(text, target(minecraft), translated -> minecraft.addScheduledTask(() -> {
            internal = true;
            try {
                minecraft.ingameGUI.getChatGUI().printChatMessage(
                        new TextComponentString(text + "\n" + translated));
            } finally { internal = false; }
        }));
    }

    @SubscribeEvent public void onTooltip(ItemTooltipEvent event) {
        if (!enabled) return;
        List<String> lines = event.getToolTip();
        String target = target(Minecraft.getMinecraft());
        for (int i = 0; i < lines.size(); i++) {
            String source = lines.get(i);
            if (!hasLetters(source)) continue;
            String translated = translator.cached(source, target);
            if (translated == null) translator.translate(source, target, ignored -> {});
            else if (!translated.equals(source)) lines.set(i, translated);
        }
    }

    private static String target(Minecraft minecraft) {
        String code = minecraft.gameSettings.language;
        if ("zh_tw".equals(code) || "zh_hk".equals(code)) return "zh-TW";
        if ("zh_cn".equals(code)) return "zh-CN";
        int split = code.indexOf('_');
        return split > 0 ? code.substring(0, split) : code;
    }

    private static boolean hasLetters(String text) {
        if (text == null || text.trim().length() < 2) return false;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) return true;
        return false;
    }
}
