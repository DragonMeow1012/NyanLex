package com.dragonmeow.nyanlex.fabric;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Small adapter that keeps the newer screen source readable on Minecraft 1.18.2. */
public final class LegacyButton {
    private LegacyButton() {}

    public static Builder builder(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {
        private final Component message;
        private final Button.OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Button build() {
            return new Button(x, y, width, height, message, onPress);
        }
    }
}
