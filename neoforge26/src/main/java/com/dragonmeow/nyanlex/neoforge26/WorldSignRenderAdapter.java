package com.dragonmeow.nyanlex.neoforge26;

import com.dragonmeow.nyanlex.translate.WorldSignRenderScope;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Supplier;

/** Thin renderer boundary: complete translated rows in, vanilla-sized sign text out. */
public final class WorldSignRenderAdapter {
    private WorldSignRenderAdapter() {
    }

    public static FormattedCharSequence[] fit(int availableWidth, PoseStack pose, Font font,
                                               Supplier<FormattedCharSequence[]> rowsCall) {
        FormattedCharSequence[] rows;
        WorldSignRenderScope.begin();
        try {
            rows = rowsCall.get();
        } finally {
            WorldSignRenderScope.end();
        }

        int widest = 0;
        for (FormattedCharSequence row : rows) widest = Math.max(widest, font.width(row));
        float scale = WorldSignRenderScope.fitScale(availableWidth, widest);
        if (scale < 1.0F) pose.scale(scale, scale, 1.0F);
        return rows;
    }
}
