package com.dragonmeow.mctranslator.neoforge26;

import com.dragonmeow.mctranslator.config.TranslationLanguages;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Neo26LanguageScreen extends Screen {
    private final Screen parent;
    private List<Map.Entry<String, LanguageInfo>> languages = List.of();
    private int page;

    public Neo26LanguageScreen(Screen parent) {
        super(Component.literal("選擇翻譯語言"));
        this.parent = parent;
    }

    @Override protected void init() {
        languages = new ArrayList<>(minecraft.getLanguageManager().getLanguages().entrySet());
        int rows = Math.max(4, Math.min(10, (height - 105) / 22));
        int pages = Math.max(1, (languages.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        int listWidth = Math.max(80, Math.min(300, width - 20));
        int x = width / 2 - listWidth / 2;
        int y = 30;
        addRenderableWidget(Button.builder(Component.literal("跟隨 Minecraft 目前語言"), b -> choose(null))
                .bounds(x, y, listWidth, 20).build());
        y += 24;
        int from = page * rows;
        for (int i = from; i < Math.min(languages.size(), from + rows); i++) {
            Map.Entry<String, LanguageInfo> entry = languages.get(i);
            Component label = Component.empty().append(entry.getValue().toComponent())
                    .append(Component.literal(" (" + entry.getKey() + ")"));
            addRenderableWidget(Button.builder(label, b -> choose(entry.getKey()))
                    .bounds(x, y, listWidth, 20).build());
            y += 22;
        }
        int navWidth = (listWidth - 12) / 3;
        Button previous = Button.builder(Component.literal("< 上一頁"), b -> { page--; rebuildWidgets(); })
                .bounds(x, height - 48, navWidth, 20).build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        addRenderableWidget(Button.builder(Component.literal((page + 1) + " / " + pages), b -> {})
                .bounds(x + navWidth + 6, height - 48, navWidth, 20).build());
        Button next = Button.builder(Component.literal("下一頁 >"), b -> { page++; rebuildWidgets(); })
                .bounds(x + (navWidth + 6) * 2, height - 48, navWidth, 20).build();
        next.active = page + 1 < pages;
        addRenderableWidget(next);
        int cancelWidth = Math.min(200, listWidth);
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(width / 2 - cancelWidth / 2, height - 24, cancelWidth, 20).build());
    }

    private void choose(String minecraftCode) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        cfg.followGameLanguage = minecraftCode == null;
        String selected = minecraftCode == null
                ? minecraft.getLanguageManager().getSelected() : minecraftCode;
        String nextTarget = TranslationLanguages.fromMinecraftCode(selected);
        if (MctranslatorNeoForge26.service() != null) {
            MctranslatorNeoForge26.service().setTargetLang(nextTarget);
        } else {
            cfg.targetLang = nextTarget;
        }
        Neo26TextStyle.clearRenderMemo();
        MctranslatorNeoForge26.saveConfig();
        minecraft.setScreenAndShow(parent);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
    }
}
