package com.dragonmeow.nyanlex.neoforge;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The 1.20.5/6 layout lifecycle, with the shared pickers' content extension point. */
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

    protected void addOptions() {
    }
}
