package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;

import com.rost.cruma.viewer.spawn.SpawnStyle;
import imgui.ImDrawList;
import java.util.Map;

final class SpawnDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int OPEN = GeodataWidgets.OPEN;
    private static final int BLOCKED = GeodataWidgets.BLOCKED;
    private static final int SMALL = 12;
    private static final float LABEL_LINE = 16;

    private SpawnDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("spawn-territory", new Territory());
        diagrams.put("spawn-respawn", new Respawn());
    }

    private static final class Territory implements GuideDiagram {
        private static final float PICTURE = 176;
        private static final float WIDE_PICTURE = 196;
        private static final float GAP = 20;
        private static final float ENTRY = 2 * LABEL_LINE + 8;
        private static final int ENTRIES = 4;
        private static final float[][] CORNERS = {{.08f, .2f}, {.6f, .06f}, {.94f, .36f}, {.82f, .94f}, {.16f, .86f}};
        private static final float[][] RANDOM = {
            {.26f, .3f},
            {.42f, .22f},
            {.6f, .24f},
            {.76f, .36f},
            {.38f, .44f},
            {.26f, .66f},
            {.44f, .76f},
            {.62f, .84f},
            {.8f, .72f},
            {.5f, .58f}
        };

        @Override
        public float getHeight(float width) {
            return isStacked(width) ? PICTURE + GAP + ENTRIES * ENTRY - 8 : PICTURE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var stacked = isStacked(width);
            var pictureWidth = stacked ? width : WIDE_PICTURE;
            zone(draw, x, y, pictureWidth, PICTURE);
            var legendX = stacked ? x : x + WIDE_PICTURE + GAP;
            var legendY = stacked ? y + PICTURE + GAP : y + (PICTURE - (ENTRIES * ENTRY - 8)) / 2;
            outlineMark(draw, legendX, legendY);
            entry(draw, legendX, legendY, "Territory", "its corners and heights");
            legendY += ENTRY;
            draw.addCircleFilled(legendX + 8, legendY + 8, 4, monster(), 12);
            entry(draw, legendX, legendY, "Random NPCs", "anywhere on its ground");
            legendY += ENTRY;
            fixedNpc(draw, legendX + 8, legendY + 8, 0);
            entry(draw, legendX, legendY, "Fixed point", "X, Y, Z and a heading");
            legendY += ENTRY;
            leftOutMark(draw, legendX, legendY);
            entry(draw, legendX, legendY, "Left out", "nothing spawns here");
        }

        private static boolean isStacked(float width) {
            return width < 380;
        }

        private static void zone(ImDrawList draw, float x, float y, float width, float height) {
            var side = Math.min(width, height);
            var left = x + (float) Math.floor((width - side) / 2);
            var points = new float[CORNERS.length * 2];
            for (var i = 0; i < CORNERS.length; i++) {
                points[2 * i] = Math.round(left + CORNERS[i][0] * side);
                points[2 * i + 1] = Math.round(y + CORNERS[i][1] * side);
            }
            for (var i = 1; i + 1 < CORNERS.length; i++) {
                draw.addTriangleFilled(
                        points[0],
                        points[1],
                        points[2 * i],
                        points[2 * i + 1],
                        points[2 * i + 2],
                        points[2 * i + 3],
                        Motion.alpha(ACCENT, .1f));
            }
            for (var i = 0; i < CORNERS.length; i++) {
                var next = (i + 1) % CORNERS.length;
                draw.addLine(points[2 * i], points[2 * i + 1], points[2 * next], points[2 * next + 1], ACCENT, 2);
            }
            var outLeft = Math.round(left + .6f * side);
            var outTop = Math.round(y + .5f * side);
            var outSide = Math.round(.18f * side);
            draw.addRectFilled(outLeft, outTop, outLeft + outSide, outTop + outSide, Motion.alpha(BLOCKED, .18f), 0f);
            draw.addRect(outLeft, outTop, outLeft + outSide, outTop + outSide, BLOCKED, 0f, 0, 1.5f);
            GeodataDiagrams.cross(draw, outLeft + outSide / 2f, outTop + outSide / 2f, 4, BLOCKED);
            for (var point : RANDOM) {
                draw.addCircleFilled(
                        Math.round(left + point[0] * side), Math.round(y + point[1] * side), 4, monster(), 12);
            }
            fixedNpc(draw, Math.round(left + .24f * side), Math.round(y + .5f * side), 1);
            for (var i = 0; i < CORNERS.length; i++) {
                draw.addRectFilled(
                        points[2 * i] - 3, points[2 * i + 1] - 3, points[2 * i] + 3, points[2 * i + 1] + 3, TEXT, 1f);
            }
        }

        private static void outlineMark(ImDrawList draw, float x, float y) {
            draw.addRectFilled(x, y + 2, x + 16, y + 14, Motion.alpha(ACCENT, .1f), 2f);
            draw.addRect(x, y + 2, x + 16, y + 14, ACCENT, 2f, 0, 2);
        }

        private static void leftOutMark(ImDrawList draw, float x, float y) {
            draw.addRectFilled(x + 1, y + 1, x + 15, y + 15, Motion.alpha(BLOCKED, .18f), 0f);
            draw.addRect(x + 1, y + 1, x + 15, y + 15, BLOCKED, 0f, 0, 1.5f);
            GeodataDiagrams.cross(draw, x + 8, y + 8, 3, BLOCKED);
        }

        private static void entry(ImDrawList draw, float x, float y, String title, String detail) {
            label(draw, x + 26, y, SMALL, TEXT, title);
            label(draw, x + 26, y + LABEL_LINE, SMALL, MUTED, detail);
        }
    }

    private static void fixedNpc(ImDrawList draw, float x, float y, int turn) {
        var color = SpawnStyle.CITIZEN.getImguiColor(1f);
        draw.addCircleFilled(x, y, 6, Motion.alpha(color, .3f), 16);
        draw.addCircle(x, y, 6, color, 16, 2);
        var dx = turn % 2 == 0 ? 1 : 0;
        var dy = turn % 2 == 0 ? 0 : 1;
        draw.addLine(x + dx * 6, y + dy * 6, x + dx * 12, y + dy * 12, color, 2);
    }

    private static int monster() {
        return SpawnStyle.MONSTER.getImguiColor(1f);
    }

    private static final class Respawn implements GuideDiagram {
        private static final float BAR = 12;

        @Override
        public float getHeight(float width) {
            return LABEL_LINE + 6 + BAR + 12 + 2 * LABEL_LINE + 16;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var death = x + (float) Math.floor(width * .3f);
            var back = x + (float) Math.floor(width * .72f);
            var spread = (float) Math.floor(width * .06f);
            var right = x + width;
            var barTop = y + LABEL_LINE + 6;
            var barBottom = barTop + BAR;
            centered(draw, (x + death) / 2, y, SMALL, TEXT, "Alive");
            centered(draw, death, y, SMALL, BLOCKED, "Killed");
            centered(draw, (back + right) / 2 + spread / 2, y, SMALL, TEXT, "Back");
            draw.addRectFilled(x, barTop, death - 6, barBottom, Motion.alpha(OPEN, .3f), 3f);
            draw.addRect(x, barTop, death - 6, barBottom, OPEN, 3f, 0, 1.5f);
            GeodataDiagrams.cross(draw, death, barTop + BAR / 2, 5, BLOCKED);
            for (var dot = death + 10; dot < back - spread - 2; dot += 6) {
                draw.addCircleFilled(dot, barTop + BAR / 2, 1.5f, FAINT, 8);
            }
            draw.addRectFilled(back - spread, barTop - 3, back + spread, barBottom + 3, Motion.alpha(ACCENT, .16f), 2f);
            draw.addLine(back, barTop - 3, back, barBottom + 3, ACCENT, 1.5f);
            draw.addRectFilled(back + spread + 4, barTop, right - 8, barBottom, Motion.alpha(OPEN, .3f), 3f);
            draw.addRect(back + spread + 4, barTop, right - 8, barBottom, OPEN, 3f, 0, 1.5f);
            GeodataDiagrams.arrow(draw, right - 12, barTop + BAR / 2, right, barTop + BAR / 2, FAINT);
            var measure = barBottom + 12;
            draw.addLine(death, measure, back, measure, MUTED, 1);
            draw.addLine(death, measure - 4, death, measure + 4, MUTED, 1);
            draw.addLine(back, measure - 4, back, measure + 4, MUTED, 1);
            var middle = (death + back) / 2;
            centered(draw, middle, measure + 6, SMALL, TEXT, "respawn 30sec");
            centered(draw, middle, measure + 6 + LABEL_LINE, SMALL, MUTED, "± 5sec at random");
            GuideDiagrams.rightAligned(draw, right, measure + 6 + LABEL_LINE, SMALL, FAINT, "time");
        }
    }
}
