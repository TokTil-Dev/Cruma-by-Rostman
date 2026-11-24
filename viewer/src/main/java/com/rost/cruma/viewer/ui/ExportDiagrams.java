package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.StartDiagrams.ACCENT;
import static com.rost.cruma.viewer.ui.StartDiagrams.BORDER;
import static com.rost.cruma.viewer.ui.StartDiagrams.FAINT;
import static com.rost.cruma.viewer.ui.StartDiagrams.LABEL;
import static com.rost.cruma.viewer.ui.StartDiagrams.LINE;
import static com.rost.cruma.viewer.ui.StartDiagrams.MUTED;
import static com.rost.cruma.viewer.ui.StartDiagrams.SMALL;
import static com.rost.cruma.viewer.ui.StartDiagrams.TEXT;
import static com.rost.cruma.viewer.ui.StartDiagrams.dashed;

import imgui.ImDrawList;
import java.util.Map;

final class ExportDiagrams {
    private ExportDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("export-tile-operations", new TileOperations());
    }

    private static final class TileOperations implements GuideDiagram {
        private static final float TILE = 34;
        private static final float ROW = TILE + 14;
        private static final float NAME = 56;
        private static final float ARROW = 40;
        private static final String[][] ROWS = {
            {"Copy", "A ", "AA"},
            {"Move", "A ", " A"},
            {"Swap", "AB", "BA"},
        };

        @Override
        public float getHeight(float width) {
            return LINE + 4 + ROWS.length * ROW;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var pair = TILE * 2 + 4;
            var used = NAME + pair + ARROW + pair;
            var left = x + (float) Math.floor(Math.max(0, (width - used) / 2));
            var before = left + NAME;
            var after = before + pair + ARROW;
            centered(draw, before + pair / 2, y, SMALL, FAINT, "before");
            centered(draw, after + pair / 2, y, SMALL, FAINT, "after");
            for (var index = 0; index < ROWS.length; index++) {
                var top = y + LINE + 4 + index * ROW;
                label(draw, left, top + TILE / 2 - LABEL / 2f - 1, LABEL, TEXT, ROWS[index][0]);
                pair(draw, before, top, ROWS[index][1]);
                GeodataDiagrams.arrow(draw, before + pair + 8, top + TILE / 2, after - 8, top + TILE / 2, MUTED);
                pair(draw, after, top, ROWS[index][2]);
            }
        }

        private static void pair(ImDrawList draw, float x, float y, String maps) {
            for (var index = 0; index < 2; index++) {
                var tx = x + index * (TILE + 4);
                var map = maps.charAt(index);
                if (map == ' ') {
                    dashed(draw, tx + 2, y + 2, tx + TILE - 2, y + TILE - 2);
                    continue;
                }
                var source = map == 'A';
                draw.addRectFilled(
                        tx, y, tx + TILE, y + TILE, Motion.alpha(source ? ACCENT : BORDER, source ? .22f : .5f), 3f);
                draw.addRect(tx, y, tx + TILE, y + TILE, source ? ACCENT : MUTED, 3f, 0, 1.5f);
                centered(draw, tx + TILE / 2, y + TILE / 2 - LABEL / 2f - 1, LABEL, TEXT, String.valueOf(map));
            }
        }
    }
}
