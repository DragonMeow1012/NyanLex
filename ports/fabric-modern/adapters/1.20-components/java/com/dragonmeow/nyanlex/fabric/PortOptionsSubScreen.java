package com.dragonmeow.nyanlex.fabric;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The pre-1.21 options layout lifecycle, with the same content boundary used by our screens. */
abstract class PortOptionsSubScreen extends OptionsSubScreen {
    protected PortOptionsSubScreen(Screen parent, Options options, Component title) {
        super(parent, options, title);
    }

    @Override
    protected void init() {
        addTitle();
        addContents();
        addFooter();
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    protected abstract void addContents();
    protected abstract void addOptions();
}
