package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.GuideDiagrams.labelWidth;

import imgui.ImDrawList;
import java.util.Map;

final class StartDiagrams {
    static final int TEXT = HudWidgets.TEXT;
    static final int MUTED = HudWidgets.MUTED;
    static final int FAINT = GeodataWidgets.FAINT;
    static final int ACCENT = HudWidgets.ACCENT;
    static final int BORDER = HudWidgets.BORDER;
    static final int SMALL = 12;
    static final int LABEL = 13;
    static final float LINE = 16;
    static final float PAD = 10;

    private StartDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("start-reads-writes", new ReadsWrites());
        diagrams.put("start-tile-grid", new TileGrid());
        diagrams.put("start-map-layers", new MapLayers());
        diagrams.put("start-client-server", new ClientServer());
    }

    private static final class ReadsWrites implements GuideDiagram {
        private static final float SOURCE = PAD + LINE + 4 * LINE + 6;
        private static final float GAP = 40;
        private static final float MIDDLE = PAD * 2 + LINE * 2;
        private static final float OUT = PAD * 2 + LINE * 2;

        @Override
        public float getHeight(float width) {
            return SOURCE + GAP + MIDDLE + GAP + OUT;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var half = (float) Math.floor((width - 12) / 2);
            var right = x + width - half;
            titled(draw, x, y, half, SOURCE, "CLIENT FOLDER", false);
            lines(draw, x, y, "Maps", "Textures", "StaticMeshes", "system");
            titled(draw, right, y, half, SOURCE, "SERVER FILES", false);
            lines(draw, right, y, "geodata", "npcpos.txt", "npcdata.txt", "and more");
            var middleTop = y + SOURCE + GAP;
            var middleWidth = (float) Math.min(width, 260);
            var middleX = x + (float) Math.floor((width - middleWidth) / 2);
            GeodataDiagrams.arrow(
                    draw, x + half / 2, y + SOURCE + 4, middleX + middleWidth * .3f, middleTop - 4, MUTED);
            GeodataDiagrams.arrow(
                    draw, right + half / 2, y + SOURCE + 4, middleX + middleWidth * .7f, middleTop - 4, MUTED);
            centered(draw, x + width / 2, y + SOURCE + GAP / 2 - LINE / 2, SMALL, MUTED, "reads");
            card(draw, middleX, middleTop, middleWidth, MIDDLE);
            draw.addRect(middleX, middleTop, middleX + middleWidth, middleTop + MIDDLE, ACCENT, 8f, 0, 1.5f);
            centered(draw, x + width / 2, middleTop + PAD, LABEL, TEXT, "Cruma");
            centered(draw, x + width / 2, middleTop + PAD + LINE, SMALL, MUTED, "draws, checks, changes");
            var outTop = middleTop + MIDDLE + GAP;
            GeodataDiagrams.arrow(draw, x + width / 2, middleTop + MIDDLE + 4, x + width / 2, outTop - 4, ACCENT);
            label(draw, x + width / 2 + 10, middleTop + MIDDLE + GAP / 2 - LINE / 2, SMALL, ACCENT, "Save, Export");
            card(draw, middleX, outTop, middleWidth, OUT);
            centered(draw, x + width / 2, outTop + PAD, LABEL, TEXT, "A folder you choose");
            centered(draw, x + width / 2, outTop + PAD + LINE, SMALL, MUTED, "saves, exports, pictures");
        }

        private static void lines(ImDrawList draw, float x, float y, String... lines) {
            for (var index = 0; index < lines.length; index++) {
                label(
                        draw,
                        x + PAD,
                        y + PAD + LINE + 6 + index * LINE,
                        SMALL,
                        index == lines.length - 1 && lines[index].startsWith("and") ? FAINT : MUTED,
                        lines[index]);
            }
        }
    }

    private static final class TileGrid implements GuideDiagram {
        private static final int FIRST_COLUMN = 20;
        private static final int COLUMNS = 5;
        private static final int FIRST_ROW = 18;
        private static final int ROWS = 4;
        private static final float AXIS = LINE + 12;
        private static final float LEGEND = 2 * LINE + 8;

        @Override
        public float getHeight(float width) {
            return AXIS + ROWS * cell(width) + LINE + GuideDiagrams.LINE_GAP + LEGEND;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var cell = cell(width);
            var left = x + AXIS;
            var top = y + AXIS;
            var right = left + COLUMNS * cell;
            var bottom = top + ROWS * cell;
            GeodataDiagrams.arrow(draw, left, y + LINE + 4, right, y + LINE + 4, FAINT);
            label(draw, left, y, SMALL, MUTED, "east");
            GeodataDiagrams.arrow(draw, x + 8, top, x + 8, bottom, FAINT);
            for (var row = 0; row < ROWS; row++) {
                for (var column = 0; column < COLUMNS; column++) {
                    var cx = left + column * cell;
                    var cy = top + row * cell;
                    var tileX = FIRST_COLUMN + column;
                    var tileY = FIRST_ROW + row;
                    var chosen = tileX >= 22 && tileX <= 23 && tileY >= 19 && tileY <= 20;
                    var sea = tileX == 24 && tileY >= 20;
                    if (chosen) {
                        draw.addRectFilled(
                                cx + 1, cy + 1, cx + cell - 1, cy + cell - 1, Motion.alpha(ACCENT, .22f), 0f);
                    }
                    if (sea) {
                        dashed(draw, cx + 4, cy + 4, cx + cell - 4, cy + cell - 4);
                        continue;
                    }
                    draw.addRect(cx, cy, cx + cell, cy + cell, chosen ? ACCENT : BORDER, 0f, 0, chosen ? 1.5f : 1f);
                    centered(
                            draw,
                            cx + cell / 2,
                            cy + cell / 2 - SMALL / 2f - 1,
                            SMALL,
                            chosen ? TEXT : MUTED,
                            tileX + "_" + tileY);
                }
            }
            draw.addCircleFilled(left, top, 4, ACCENT, 12);
            label(draw, left + 6, top + 4, SMALL, ACCENT, "0, 0");
            label(draw, x + 2, bottom + 2, SMALL, MUTED, "south");
            var legend = bottom + LINE + GuideDiagrams.LINE_GAP;
            draw.addRectFilled(x, legend + 2, x + 12, legend + 14, Motion.alpha(ACCENT, .22f), 0f);
            draw.addRect(x, legend + 2, x + 12, legend + 14, ACCENT, 0f, 0, 1.5f);
            label(draw, x + 20, legend, SMALL, MUTED, "tiles you choose to load");
            dashed(draw, x, legend + LINE + 2, x + 12, legend + LINE + 14);
            label(draw, x + 20, legend + LINE, SMALL, MUTED, "no map here, such as the sea");
        }

        private static float cell(float width) {
            return (float) Math.floor(Math.min(64, (width - AXIS) / COLUMNS));
        }
    }

    private static final class MapLayers implements GuideDiagram {
        private static final float BAND = PAD + 2 * LINE + 4;
        private static final float GAP = 6;
        private static final float SIDE = 76;
        private static final String[][] LAYERS = {
            {"Lights, effects, sound", "lamps, fire, sounds · .u .uax"},
            {"Static meshes", "trees, rocks, fences · .usx"},
            {"BSP", "walls, floors, buildings · .unr"},
            {"Terrain", "the open ground · .unr"},
        };

        @Override
        public float getHeight(float width) {
            return LAYERS.length * BAND + (LAYERS.length - 1) * GAP;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var bands = width - SIDE - GAP;
            for (var index = 0; index < LAYERS.length; index++) {
                var top = y + index * (BAND + GAP);
                card(draw, x, top, bands, BAND);
                draw.addRectFilled(x, top + 6, x + 3, top + BAND - 6, ACCENT, 2f);
                label(draw, x + PAD + 2, top + PAD - 2, LABEL, TEXT, LAYERS[index][0]);
                label(
                        draw,
                        x + PAD + 2,
                        top + PAD - 2 + LINE + 2,
                        SMALL,
                        MUTED,
                        fit(LAYERS[index][1], bands - PAD * 2));
            }
            var sideX = x + width - SIDE;
            var total = getHeight(width);
            card(draw, sideX, y, SIDE, total);
            centered(draw, sideX + SIDE / 2, y + total / 2 - LINE * 1.5f, LABEL, TEXT, "Textures");
            centered(draw, sideX + SIDE / 2, y + total / 2 - LINE / 2, SMALL, MUTED, ".utx");
            centered(draw, sideX + SIDE / 2, y + total / 2 + LINE / 2 + 4, SMALL, FAINT, "on every");
            centered(draw, sideX + SIDE / 2, y + total / 2 + LINE * 1.5f + 4, SMALL, FAINT, "surface");
            for (var index = 0; index < LAYERS.length; index++) {
                var middle = y + index * (BAND + GAP) + BAND / 2;
                draw.addLine(x + bands + 1, middle, sideX - 1, middle, BORDER, 1);
            }
        }
    }

    private static final class ClientServer implements GuideDiagram {
        private static final float SIDE = PAD + LINE + 4 * LINE + 6;
        private static final float GAP = 36;
        private static final float BOTTOM = PAD * 2 + LINE * 2;

        @Override
        public float getHeight(float width) {
            return SIDE + GAP + BOTTOM;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var half = (float) Math.floor((width - 12) / 2);
            var right = x + width - half;
            titled(draw, x, y, half, SIDE, "CLIENT", false);
            list(draw, x, y, half, "maps and terrain", "meshes, textures", "effects, music", "game screens");
            titled(draw, right, y, half, SIDE, "SERVER", false);
            list(draw, right, y, half, "where to walk", "where NPCs stand", "what NPCs are", "doors, events");
            var bottomTop = y + SIDE + GAP;
            GeodataDiagrams.arrow(draw, x + half / 2, y + SIDE + 4, x + half / 2, bottomTop - 4, MUTED);
            GeodataDiagrams.arrow(draw, right + half / 2, y + SIDE + 4, right + half / 2, bottomTop - 4, MUTED);
            card(draw, x, bottomTop, width, BOTTOM);
            draw.addRect(x, bottomTop, x + width, bottomTop + BOTTOM, ACCENT, 8f, 0, 1.5f);
            centered(draw, x + width / 2, bottomTop + PAD, LABEL, TEXT, "Cruma");
            centered(draw, x + width / 2, bottomTop + PAD + LINE, SMALL, MUTED, "reads both, draws one over the other");
        }

        private static void list(ImDrawList draw, float x, float y, float width, String... lines) {
            for (var index = 0; index < lines.length; index++) {
                var top = y + PAD + LINE + 6 + index * LINE;
                draw.addCircleFilled(x + PAD + 2, top + SMALL / 2f + 1, 2, MUTED, 8);
                label(draw, x + PAD + 10, top, SMALL, MUTED, fit(lines[index], width - PAD * 2 - 10));
            }
        }
    }

    static void card(ImDrawList draw, float x, float y, float width, float height) {
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 8f);
        draw.addRect(x, y, x + width, y + height, GeodataWidgets.DIVIDER, 8f, 0, 1);
    }

    static void titled(ImDrawList draw, float x, float y, float width, float height, String title, boolean subject) {
        card(draw, x, y, width, height);
        if (subject) {
            draw.addRect(x, y, x + width, y + height, ACCENT, 8f, 0, 1.5f);
        }
        label(draw, x + PAD, y + PAD - 2, SMALL, subject ? ACCENT : FAINT, fit(title, width - PAD * 2));
    }

    static void dashed(ImDrawList draw, float x0, float y0, float x1, float y1) {
        var dash = 4f;
        for (var at = x0; at < x1; at += dash * 2) {
            var end = Math.min(x1, at + dash);
            draw.addLine(at, y0, end, y0, FAINT, 1);
            draw.addLine(at, y1, end, y1, FAINT, 1);
        }
        for (var at = y0; at < y1; at += dash * 2) {
            var end = Math.min(y1, at + dash);
            draw.addLine(x0, at, x0, end, FAINT, 1);
            draw.addLine(x1, at, x1, end, FAINT, 1);
        }
    }

    static String fit(String text, float room) {
        if (labelWidth(SMALL, text) <= room) {
            return text;
        }
        var cut = text;
        while (cut.length() > 1 && labelWidth(SMALL, cut + "…") > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.strip() + "…";
    }
}
