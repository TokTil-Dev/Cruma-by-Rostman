package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.StartDiagrams.ACCENT;
import static com.rost.cruma.viewer.ui.StartDiagrams.BORDER;
import static com.rost.cruma.viewer.ui.StartDiagrams.FAINT;
import static com.rost.cruma.viewer.ui.StartDiagrams.LABEL;
import static com.rost.cruma.viewer.ui.StartDiagrams.LINE;
import static com.rost.cruma.viewer.ui.StartDiagrams.MUTED;
import static com.rost.cruma.viewer.ui.StartDiagrams.PAD;
import static com.rost.cruma.viewer.ui.StartDiagrams.SMALL;
import static com.rost.cruma.viewer.ui.StartDiagrams.TEXT;
import static com.rost.cruma.viewer.ui.StartDiagrams.card;
import static com.rost.cruma.viewer.ui.StartDiagrams.fit;

import imgui.ImDrawList;
import java.util.Map;

final class ViewDiagrams {
    private ViewDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("view-object-parts", new ObjectParts());
    }

    private static final class ObjectParts implements GuideDiagram {
        private static final float BOX = PAD * 2 + LINE * 2;
        private static final float GAP = 28;
        private static final float SLOT = PAD * 2 + LINE * 2 + 8 + 30;
        private static final float SWATCH = 30;

        @Override
        public float getHeight(float width) {
            return BOX + GAP + BOX + GAP + SLOT;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var boxWidth = (float) Math.min(width, 260);
            var boxX = x + (float) Math.floor((width - boxWidth) / 2);
            var middle = x + width / 2;
            card(draw, boxX, y, boxWidth, BOX);
            draw.addRect(boxX, y, boxX + boxWidth, y + BOX, ACCENT, 8f, 0, 1.5f);
            centered(draw, middle, y + PAD, LABEL, TEXT, "Actor");
            centered(draw, middle, y + PAD + LINE, SMALL, MUTED, "a place, a turn and a size");
            var meshTop = y + BOX + GAP;
            GeodataDiagrams.arrow(draw, middle, y + BOX + 4, middle, meshTop - 4, MUTED);
            label(draw, middle + 8, y + BOX + GAP / 2 - LINE / 2, SMALL, MUTED, "shows");
            card(draw, boxX, meshTop, boxWidth, BOX);
            centered(draw, middle, meshTop + PAD, LABEL, TEXT, "Mesh");
            centered(draw, middle, meshTop + PAD + LINE, SMALL, MUTED, "triangles, from a .usx package");
            var slotsTop = meshTop + BOX + GAP;
            var slotWidth = (float) Math.floor((width - 12) / 2);
            var slots = new String[][] {{"SLOT 0", "bark"}, {"SLOT 1", "leaves"}};
            for (var index = 0; index < slots.length; index++) {
                var slotX = index == 0 ? x : x + width - slotWidth;
                GeodataDiagrams.arrow(
                        draw,
                        middle + (index == 0 ? -20 : 20),
                        meshTop + BOX + 4,
                        slotX + slotWidth / 2,
                        slotsTop - 4,
                        MUTED);
                card(draw, slotX, slotsTop, slotWidth, SLOT);
                label(draw, slotX + PAD, slotsTop + PAD - 2, SMALL, FAINT, slots[index][0]);
                label(
                        draw,
                        slotX + PAD,
                        slotsTop + PAD - 2 + LINE,
                        LABEL,
                        TEXT,
                        fit("Material: " + slots[index][1], slotWidth - PAD * 2));
                var swatchTop = slotsTop + PAD + LINE * 2 + 6;
                for (var swatch = 0; swatch < 2; swatch++) {
                    var sx = slotX + PAD + swatch * (SWATCH + 6);
                    pattern(draw, sx, swatchTop, index * 2 + swatch);
                }
                label(
                        draw,
                        slotX + PAD + 2 * (SWATCH + 6) + 2,
                        swatchTop + SWATCH / 2 - SMALL / 2f - 1,
                        SMALL,
                        MUTED,
                        fit("textures", slotWidth - PAD * 2 - 2 * (SWATCH + 6) - 2));
            }
        }

        private static void pattern(ImDrawList draw, float x, float y, int kind) {
            draw.addRectFilled(x, y, x + SWATCH, y + SWATCH, Motion.alpha(BORDER, .6f), 3f);
            var step = SWATCH / 5;
            for (var row = 0; row < 5; row++) {
                for (var column = 0; column < 5; column++) {
                    var on = switch (kind % 4) {
                        case 0 -> row % 2 == 0;
                        case 1 -> (row + column) % 2 == 0;
                        case 2 -> column % 2 == 0;
                        default -> (row + column) % 3 == 0;
                    };
                    if (on) {
                        draw.addRectFilled(
                                x + column * step,
                                y + row * step,
                                x + (column + 1) * step,
                                y + (row + 1) * step,
                                Motion.alpha(MUTED, .35f),
                                0f);
                    }
                }
            }
            draw.addRect(x, y, x + SWATCH, y + SWATCH, BORDER, 3f, 0, 1);
        }
    }
}
