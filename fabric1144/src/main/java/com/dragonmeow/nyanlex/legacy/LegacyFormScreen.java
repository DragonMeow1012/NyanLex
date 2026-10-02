package com.dragonmeow.nyanlex.legacy;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays the rows of {@link LegacyUiModel} out as one column (titles, wrapped text, full-width and
 * half-width buttons). The settings categories and the quick setup are both just a row list on
 * top of this screen.
 */
abstract class LegacyFormScreen extends Screen {
    static final LegacyUiModel.Text TEXT = new LegacyUiModel.Text() {
        @Override public String get(String key, Object... args) {
            return new TranslatableComponent(key, args).getString();
        }
    };

    private static final int COLUMN_W = 310;
    private static final int LINE_H = 10;
    private static final int TOP = 34;

    private static final class Placed {
        final List<String> lines;
        final int y;
        final int color;
        final boolean hintArea;
        final LegacyUiModel.Row row;

        Placed(List<String> lines, int y, int color, boolean hintArea, LegacyUiModel.Row row) {
            this.lines = lines;
            this.y = y;
            this.color = color;
            this.hintArea = hintArea;
            this.row = row;
        }
    }

    private final List<Placed> placed = new ArrayList<Placed>();
    private final Map<Button, LegacyUiModel.Row> buttonRows = new IdentityHashMap<Button, LegacyUiModel.Row>();
    private List<LegacyUiModel.Row> allRows = new ArrayList<LegacyUiModel.Row>();

    protected LegacyFormScreen(Component title) {
        super(title);
    }

    protected abstract List<LegacyUiModel.Row> rows();

    protected abstract String heading();

    protected abstract void onAction(int action);

    /** True when a full-width Done button belongs at the bottom (settings pages). */
    protected boolean hasDone() {
        return true;
    }

    /** Rebuilds the rows after a state change. */
    protected final void refresh() {
        init(minecraft, width, height);
    }

    @Override protected void init() {
        placed.clear();
        buttonRows.clear();
        allRows = rows();
        int x = width / 2 - COLUMN_W / 2;
        int y = TOP;
        int pendingLeftY = -1;
        for (int i = 0; i < allRows.size(); i++) {
            final LegacyUiModel.Row row = allRows.get(i);
            switch (row.kind) {
                case LegacyUiModel.TEXT: {
                    List<String> lines = font.split(row.label, COLUMN_W);
                    placed.add(new Placed(lines, y, row.color, false, row));
                    y += lines.size() * LINE_H + 6;
                    break;
                }
                case LegacyUiModel.HINT: {
                    int reserve = font.split(row.label, COLUMN_W).size();
                    for (LegacyUiModel.Row other : allRows) {
                        if (other.hint != null) reserve = Math.max(reserve, font.split(other.hint, COLUMN_W).size());
                    }
                    placed.add(new Placed(new ArrayList<String>(), y, row.color, true, row));
                    y += reserve * LINE_H + 6;
                    break;
                }
                case LegacyUiModel.GAP:
                    y += 8;
                    break;
                case LegacyUiModel.HALF_LEFT:
                    addRowButton(row, x, y, 152);
                    pendingLeftY = y;
                    break;
                case LegacyUiModel.HALF_RIGHT:
                    addRowButton(row, x + 158, pendingLeftY >= 0 ? pendingLeftY : y, 152);
                    y = (pendingLeftY >= 0 ? pendingLeftY : y) + 24;
                    pendingLeftY = -1;
                    break;
                default:
                    addRowButton(row, x, y, COLUMN_W);
                    y += 24;
                    break;
            }
        }
        if (hasDone()) {
            addButton(new Button(width / 2 - 100, height - 26, 200, 20,
                    TEXT.get("gui.done"), button -> onAction(LegacyUiModel.A_DONE)));
        }
    }

    private void addRowButton(final LegacyUiModel.Row row, int x, int y, int w) {
        Button button = new Button(x, y, w, 20, row.label, b -> onAction(row.action));
        button.active = row.enabled;
        addButton(button);
        buttonRows.put(button, row);
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        renderBackground();
        String heading = heading();
        font.drawShadow(heading, width / 2f - font.width(heading) / 2f, 14, 0xFFFFFF);
        String hovered = null;
        for (Map.Entry<Button, LegacyUiModel.Row> e : buttonRows.entrySet()) {
            Button b = e.getKey();
            if (e.getValue().hint != null && mouseX >= b.x && mouseX < b.x + b.getWidth()
                    && mouseY >= b.y && mouseY < b.y + 20) hovered = e.getValue().hint;
        }
        for (Placed p : placed) {
            List<String> lines = p.lines;
            int color = p.color;
            if (p.hintArea) {
                String text = hovered != null ? hovered : p.row.hint != null ? p.row.hint : p.row.label;
                lines = font.split(text, COLUMN_W);
            }
            int y = p.y;
            for (String line : lines) {
                font.drawShadow(line, width / 2f - COLUMN_W / 2f, y, color);
                y += LINE_H;
            }
        }
        super.render(mouseX, mouseY, delta);
    }
}
