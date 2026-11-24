package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;

import imgui.ImDrawList;
import java.util.Map;

final class AreaDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int BLOCKED = GeodataWidgets.BLOCKED;
    private static final int SMALL = 12;
    private static final float LABEL_LINE = 16;

    private AreaDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("area-shape", new Shape());
        diagrams.put("area-tiles", new Tiles());
    }

    private static final class Shape implements GuideDiagram {
        private static final float PICTURE = 176;
        private static final float WIDE_PICTURE = 196;
        private static final float GAP = 20;
        private static final float ENTRY = 2 * LABEL_LINE + 8;
        private static final int ENTRIES = 4;
        private static final float[][] CORNERS = {{.1f, .14f}, {.84f, .08f}, {.94f, .62f}, {.62f, .92f}, {.08f, .8f}};

        @Override
        public float getHeight(float width) {
            return isStacked(width) ? PICTURE + GAP + ENTRIES * ENTRY - 8 : PICTURE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var stacked = isStacked(width);
            outline(draw, x, y, stacked ? width : WIDE_PICTURE, PICTURE);
            var legendX = stacked ? x : x + WIDE_PICTURE + GAP;
            var legendY = stacked ? y + PICTURE + GAP : y + (PICTURE - (ENTRIES * ENTRY - 8)) / 2;
            draw.addRectFilled(legendX, legendY + 2, legendX + 16, legendY + 14, Motion.alpha(ACCENT, .1f), 2f);
            draw.addRect(legendX, legendY + 2, legendX + 16, legendY + 14, ACCENT, 2f, 0, 2);
            entry(draw, legendX, legendY, "Outline", "three corners or more");
            legendY += ENTRY;
            draw.addRectFilled(legendX + 1, legendY + 1, legendX + 15, legendY + 15, Motion.alpha(BLOCKED, .18f), 0f);
            draw.addRect(legendX + 1, legendY + 1, legendX + 15, legendY + 15, BLOCKED, 0f, 0, 1.5f);
            GeodataDiagrams.cross(draw, legendX + 8, legendY + 8, 3, BLOCKED);
            entry(draw, legendX, legendY, "Excluded part", "the area does not hold here");
            legendY += ENTRY;
            heightMark(draw, legendX, legendY);
            entry(draw, legendX, legendY, "Height range", "one bottom and one top");
            legendY += ENTRY;
            point(draw, legendX + 8, legendY + 8);
            entry(draw, legendX, legendY, "Teleport point", "where a teleport sends");
        }

        private static boolean isStacked(float width) {
            return width < 380;
        }

        private static void outline(ImDrawList draw, float x, float y, float width, float height) {
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
            var outLeft = Math.round(left + .4f * side);
            var outTop = Math.round(y + .34f * side);
            var outSide = Math.round(.22f * side);
            draw.addRectFilled(outLeft, outTop, outLeft + outSide, outTop + outSide, Motion.alpha(BLOCKED, .18f), 0f);
            draw.addRect(outLeft, outTop, outLeft + outSide, outTop + outSide, BLOCKED, 0f, 0, 1.5f);
            GeodataDiagrams.cross(draw, outLeft + outSide / 2f, outTop + outSide / 2f, 4, BLOCKED);
            point(draw, Math.round(left + .9f * side), Math.round(y + .88f * side));
            for (var i = 0; i < CORNERS.length; i++) {
                draw.addCircleFilled(points[2 * i], points[2 * i + 1], 4, TEXT, 12);
            }
        }

        private static void heightMark(ImDrawList draw, float x, float y) {
            draw.addRectFilled(x + 2, y + 1, x + 14, y + 15, Motion.alpha(ACCENT, .14f), 0f);
            draw.addLine(x, y + 1, x + 16, y + 1, ACCENT, 1.5f);
            draw.addLine(x, y + 15, x + 16, y + 15, ACCENT, 1.5f);
            draw.addLine(x, y + 10, x + 16, y + 10, MUTED, 1);
        }

        private static void point(ImDrawList draw, float x, float y) {
            draw.addCircleFilled(x, y, 6, Motion.alpha(ACCENT, .3f), 16);
            draw.addCircle(x, y, 6, ACCENT, 16, 2);
            draw.addCircleFilled(x, y, 2, ACCENT, 8);
        }

        private static void entry(ImDrawList draw, float x, float y, String title, String detail) {
            label(draw, x + 26, y, SMALL, TEXT, title);
            label(draw, x + 26, y + LABEL_LINE, SMALL, MUTED, detail);
        }
    }

    private static final class Tiles implements GuideDiagram {
        private static final float PICTURE = 140;

        @Override
        public float getHeight(float width) {
            return LABEL_LINE + 6 + PICTURE + 10 + 2 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var border = x + (float) Math.floor(width / 2);
            var top = y + LABEL_LINE + 6;
            var bottom = top + PICTURE;
            centered(draw, (x + border) / 2, y, SMALL, TEXT, "Tile 20_18");
            centered(draw, (border + x + width) / 2, y, SMALL, TEXT, "Tile 21_18");
            draw.addRect(x, top, border, bottom, FAINT, 0f, 0, 1);
            draw.addRect(border, top, x + width, bottom, FAINT, 0f, 0, 1);
            var left = x + (float) Math.floor(width * .14f);
            var right = border + (float) Math.floor(width * .2f);
            var areaTop = top + 30;
            var areaBottom = bottom - 30;
            draw.addRectFilled(left, areaTop, border, areaBottom, Motion.alpha(ACCENT, .14f), 0f);
            draw.addRectFilled(border, areaTop, right, areaBottom, Motion.alpha(BLOCKED, .14f), 0f);
            draw.addRect(left, areaTop, right, areaBottom, ACCENT, 0f, 0, 2);
            draw.addLine(border, areaTop, border, areaBottom, BLOCKED, 1.5f);
            GeodataDiagrams.cross(draw, (border + right) / 2, (areaTop + areaBottom) / 2, 5, BLOCKED);
            var middleX = (left + right) / 2;
            var middleY = (areaTop + areaBottom) / 2;
            draw.addCircleFilled(middleX, middleY, 4, TEXT, 12);
            centered(draw, middleX, middleY + 8, SMALL, MUTED, "middle");
            centered(draw, (x + border) / 2, bottom + 10, SMALL, TEXT, "files the area");
            centered(draw, (x + border) / 2, bottom + 10 + LABEL_LINE, SMALL, MUTED, "it works here");
            centered(draw, (border + x + width) / 2, bottom + 10, SMALL, BLOCKED, "does nothing here");
            centered(draw, (border + x + width) / 2, bottom + 10 + LABEL_LINE, SMALL, MUTED, "Split fixes it");
        }
    }
}
