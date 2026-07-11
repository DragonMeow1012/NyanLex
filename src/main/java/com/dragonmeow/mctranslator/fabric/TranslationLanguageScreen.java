package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.TranslationLanguages;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Searchable drop-down picker backed by Minecraft's installed language list. */
public final class TranslationLanguageScreen extends Screen {
    private final Screen parent;
    private List<Map.Entry<String, LanguageInfo>> languages = List.of();
    private EditBox searchBox;
    private String query = "";
    private boolean expanded;
    private int page;

    public TranslationLanguageScreen(Screen parent) {
        super(Component.translatable("screen.mctranslator.language.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        languages = new ArrayList<>(minecraft.getLanguageManager().getLanguages().entrySet());
        languages.sort(Comparator.comparing(e -> e.getValue().toComponent().getString(),
                String.CASE_INSENSITIVE_ORDER));

        int listWidth = Math.max(120, Math.min(340, width - 24));
        int x = width / 2 - listWidth / 2;
        searchBox = new EditBox(font, x, 30, listWidth, 20,
                Component.translatable("screen.mctranslator.language.search"));
        searchBox.setHint(Component.translatable("screen.mctranslator.language.search"));
        searchBox.setMaxLength(80);
        searchBox.setValue(query);
        searchBox.setResponder(value -> {
            query = value;
            page = 0;
            expanded = true;
            rebuildWidgets();
        });
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(dropdownLabel(), b -> {
            query = searchBox.getValue();
            expanded = !expanded;
            page = 0;
            rebuildWidgets();
        }).bounds(x, 54, listWidth, 20).build());

        if (expanded) addDropDownEntries(x, listWidth);

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - Math.min(200, listWidth) / 2, height - 24,
                        Math.min(200, listWidth), 20).build());
        setFocused(searchBox);
    }

    private void addDropDownEntries(int x, int listWidth) {
        List<Map.Entry<String, LanguageInfo>> filtered = filteredLanguages();
        int rows = Math.max(3, Math.min(8, (height - 142) / 22));
        int pages = Math.max(1, (filtered.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        int y = 78;

        if (query.isBlank() || matchesFollow(query)) {
            addRenderableWidget(Button.builder(
                    Component.translatable("screen.mctranslator.language.follow"), b -> choose(null))
                    .bounds(x, y, listWidth, 20).build());
            y += 22;
        }

        int from = page * rows;
        for (int i = from; i < Math.min(filtered.size(), from + rows); i++) {
            Map.Entry<String, LanguageInfo> entry = filtered.get(i);
            Component label = Component.empty().append(entry.getValue().toComponent())
                    .append(Component.literal(" (" + entry.getKey() + ")"));
            addRenderableWidget(Button.builder(label, b -> choose(entry.getKey()))
                    .bounds(x, y, listWidth, 20).build());
            y += 22;
        }

        if (filtered.isEmpty() && !matchesFollow(query)) {
            addRenderableWidget(Button.builder(Component.translatable("screen.mctranslator.language.empty"), b -> {})
                    .bounds(x, y, listWidth, 20).build()).active = false;
            y += 22;
        }

        if (pages > 1) {
            int navWidth = (listWidth - 12) / 3;
            Button previous = Button.builder(Component.translatable("screen.mctranslator.language.previous"), b -> {
                query = searchBox.getValue(); page--; rebuildWidgets();
            }).bounds(x, y, navWidth, 20).build();
            previous.active = page > 0;
            addRenderableWidget(previous);
            addRenderableWidget(Button.builder(
                    Component.translatable("screen.mctranslator.language.page", page + 1, pages), b -> {})
                    .bounds(x + navWidth + 6, y, navWidth, 20).build());
            Button next = Button.builder(Component.translatable("screen.mctranslator.language.next"), b -> {
                query = searchBox.getValue(); page++; rebuildWidgets();
            }).bounds(x + (navWidth + 6) * 2, y, navWidth, 20).build();
            next.active = page + 1 < pages;
            addRenderableWidget(next);
        }
    }

    private List<Map.Entry<String, LanguageInfo>> filteredLanguages() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return languages;
        return languages.stream().filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains(needle)
                || entry.getValue().toComponent().getString().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    private static boolean matchesFollow(String value) {
        String needle = value.trim().toLowerCase(Locale.ROOT);
        return needle.isEmpty() || "minecraft".contains(needle) || "follow".contains(needle)
                || "跟隨遊戲".contains(needle) || "跟隨 minecraft".contains(needle);
    }

    private Component dropdownLabel() {
        TranslatorConfig cfg = MctranslatorFabric.config();
        Component selected = cfg.followGameLanguage
                ? Component.translatable("screen.mctranslator.language.follow")
                : Component.literal(cfg.targetLang);
        return Component.translatable(expanded
                ? "screen.mctranslator.language.dropdown.open"
                : "screen.mctranslator.language.dropdown.closed", selected);
    }

    private void choose(String minecraftCode) {
        TranslatorConfig cfg = MctranslatorFabric.config();
        cfg.followGameLanguage = minecraftCode == null;
        String selected = minecraftCode == null
                ? minecraft.getLanguageManager().getSelected() : minecraftCode;
        String nextTarget = TranslationLanguages.fromMinecraftCode(selected);
        if (MctranslatorFabric.service() != null) MctranslatorFabric.service().setTargetLang(nextTarget);
        else cfg.targetLang = nextTarget;
        FabricTextStyle.clearRenderMemo();
        MctranslatorFabric.saveConfig();
        minecraft.setScreen(parent);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
    }
}
