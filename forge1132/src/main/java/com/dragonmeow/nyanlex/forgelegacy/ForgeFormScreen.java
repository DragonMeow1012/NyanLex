package com.dragonmeow.nyanlex.forgelegacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays the rows of {@link LegacyUiModel} out as one column (titles, wrapped text, full-width and
 * half-width buttons). The settings categories and the quick setup are both just a row list on
 * top of this screen.
 */
abstract class ForgeFormScreen extends GuiScreen implements ForgeButton.Handler {
    static final LegacyUiModel.Text TEXT = new LegacyUiModel.Text() {
        @Override public String get(String key, Object... args) {
            return I18n.format(key, args);
        }
    };

    private static final int COLUMN_W = 310;
    private static final int LINE_H = 10;
    private static final int TOP = 34;

    private static final class Placed {
        final List<String> lines;
        final int y;
        final int color;
        final LegacyUiModel.Row hintRow;

        Placed(List<String> lines, int y, int color, LegacyUiModel.Row hintRow) {
            this.lines = lines;
            this.y = y;
            this.color = color;
            this.hintRow = hintRow;
        }
    }

    private final List<Placed> placed = new ArrayList<Placed>();
    private final List<GuiButton> rowButtons = new ArrayList<GuiButton>();
    private final List<LegacyUiModel.Row> rowOfButton = new ArrayList<LegacyUiModel.Row>();
    private List<LegacyUiModel.Row> allRows = new ArrayList<LegacyUiModel.Row>();

    protected abstract List<LegacyUiModel.Row> rows();

    protected abstract String heading();

    protected abstract void onAction(int action);

    /** Esc: the same as the Done / back action of the screen. */
    protected abstract void onEscape();

    /** True when a Done button belongs at the bottom (settings pages). */
    protected boolean hasDone() {
        return true;
    }

    /** Rebuilds the rows after a state change. */
    protected final void refresh() {
        buttons.clear();
        children.clear();
        initGui();
    }

    @Override protected void initGui() {
        placed.clear();
        rowButtons.clear();
        rowOfButton.clear();
        allRows = rows();
        int x = width / 2 - COLUMN_W / 2;
        int y = TOP;
        int pendingLeftY = -1;
        for (int i = 0; i < allRows.size(); i++) {
            LegacyUiModel.Row row = allRows.get(i);
            switch (row.kind) {
                case LegacyUiModel.TEXT: {
                    List<String> lines = fontRenderer.listFormattedStringToWidth(row.label, COLUMN_W);
                    placed.add(new Placed(lines, y, row.color, null));
                    y += lines.size() * LINE_H + 6;
                    break;
                }
                case LegacyUiModel.HINT: {
                    int reserve = fontRenderer.listFormattedStringToWidth(row.label, COLUMN_W).size();
                    for (LegacyUiModel.Row other : allRows) {
                        if (other.hint != null) reserve = Math.max(reserve,
                                fontRenderer.listFormattedStringToWidth(other.hint, COLUMN_W).size());
                    }
                    placed.add(new Placed(null, y, row.color, row));
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
            addButton(new ForgeButton(LegacyUiModel.A_DONE, width / 2 - 100, height - 26, 200, 20,
                    TEXT.get("gui.done"), this));
        }
    }

    private void addRowButton(LegacyUiModel.Row row, int x, int y, int w) {
        ForgeButton button = new ForgeButton(row.action, x, y, w, 20, row.label, this);
        button.enabled = row.enabled;
        addButton(button);
        rowButtons.add(button);
        rowOfButton.add(row);
    }

    @Override public void onForgeButton(GuiButton button) {
        onAction(button.id);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == 256) {
            onEscape();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        drawDefaultBackground();
        String heading = heading();
        fontRenderer.drawStringWithShadow(heading, width / 2f - fontRenderer.getStringWidth(heading) / 2f, 14, 0xFFFFFF);
        String hovered = null;
        for (int i = 0; i < rowButtons.size(); i++) {
            GuiButton b = rowButtons.get(i);
            String hint = rowOfButton.get(i).hint;
            if (hint != null && mouseX >= b.x && mouseX < b.x + b.getWidth()
                    && mouseY >= b.y && mouseY < b.y + 20) hovered = hint;
        }
        for (Placed p : placed) {
            List<String> lines = p.lines;
            if (p.hintRow != null) {
                String text = hovered != null ? hovered : p.hintRow.hint != null ? p.hintRow.hint : p.hintRow.label;
                lines = fontRenderer.listFormattedStringToWidth(text, COLUMN_W);
            }
            int y = p.y;
            for (String line : lines) {
                fontRenderer.drawStringWithShadow(line, width / 2f - COLUMN_W / 2f, y, p.color);
                y += LINE_H;
            }
        }
        super.render(mouseX, mouseY, delta);
    }
}
