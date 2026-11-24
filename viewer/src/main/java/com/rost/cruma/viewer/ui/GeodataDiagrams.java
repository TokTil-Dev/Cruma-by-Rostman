package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.GuideDiagrams.labelWidth;

import imgui.ImDrawList;
import java.util.Map;

final class GeodataDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int OPEN = GeodataWidgets.OPEN;
    private static final int BLOCKED = GeodataWidgets.BLOCKED;
    private static final int BORDER = HudWidgets.BORDER;
    private static final int CARD = GeodataWidgets.CARD;
    private static final int SMALL = 12;
    private static final int LABEL = 13;
    private static final float LABEL_LINE = 16;

    private GeodataDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("geodata-client-server", new ClientServer());
        diagrams.put("geodata-cell", new OneCell());
        diagrams.put("geodata-layers", new Layers());
        diagrams.put("geodata-sizes", new Sizes());
        diagrams.put("geodata-blocks", new Blocks());
        diagrams.put("geodata-one-way", new OneWay());
        diagrams.put("geodata-floor", new FloorBeside());
    }

    private static final class ClientServer implements GuideDiagram {
        private static final float CARD_HEIGHT = 120;
        private static final float MIDDLE = 52;

        @Override
        public float getHeight(float width) {
            return CARD_HEIGHT + GuideDiagrams.LINE_GAP + 2 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var panel = (float) Math.floor((width - MIDDLE) / 2);
            var right = x + panel + MIDDLE;
            card(draw, x, y, panel, CARD_HEIGHT);
            card(draw, right, y, panel, CARD_HEIGHT);
            label(draw, x + 10, y + 8, SMALL, MUTED, "CLIENT");
            label(draw, right + 10, y + 8, SMALL, MUTED, "SERVER");
            var ground = y + CARD_HEIGHT - 22;
            var houseFrom = .58f;
            var houseTo = .78f;
            var inner = panel - 20;
            float lastX = x + 10;
            float lastY = ground - hill(0) * 44;
            for (var step = 1; step <= 40; step++) {
                var t = step / 40f;
                var px = x + 10 + inner * t;
                var py = ground - hill(t) * 44;
                draw.addQuadFilled(
                        lastX,
                        lastY,
                        px,
                        py,
                        px,
                        y + CARD_HEIGHT - 8,
                        lastX,
                        y + CARD_HEIGHT - 8,
                        Motion.alpha(BORDER, .55f));
                draw.addLine(lastX, lastY, px, py, MUTED, 1.5f);
                lastX = px;
                lastY = py;
            }
            var houseLeft = x + 10 + inner * houseFrom;
            var houseRight = x + 10 + inner * houseTo;
            var houseBottom = ground - hill((houseFrom + houseTo) / 2) * 44;
            var houseTop = houseBottom - 26;
            draw.addRectFilled(houseLeft, houseTop, houseRight, houseBottom, CARD, 0f);
            draw.addRect(houseLeft, houseTop, houseRight, houseBottom, MUTED, 0f, 0, 1.5f);
            draw.addTriangleFilled(
                    houseLeft - 4,
                    houseTop,
                    (houseLeft + houseRight) / 2,
                    houseTop - 14,
                    houseRight + 4,
                    houseTop,
                    CARD);
            draw.addTriangle(
                    houseLeft - 4,
                    houseTop,
                    (houseLeft + houseRight) / 2,
                    houseTop - 14,
                    houseRight + 4,
                    houseTop,
                    MUTED,
                    1.5f);
            var cells = 14;
            var cell = inner / cells;
            for (var index = 0; index < cells; index++) {
                var t = (index + .5f) / cells;
                var left = right + 10 + index * cell;
                var top = Math.round((ground - hill(t) * 44) / 6) * 6f;
                var blocked = t > houseFrom && t < houseTo;
                draw.addRectFilled(
                        left + 1,
                        top,
                        left + cell - 1,
                        y + CARD_HEIGHT - 8,
                        Motion.alpha(blocked ? BLOCKED : OPEN, .16f),
                        0f);
                draw.addLine(left + 1, top, left + cell - 1, top, blocked ? BLOCKED : OPEN, 2);
                if (blocked) {
                    cross(draw, left + cell / 2, top - 8, 3.5f, BLOCKED);
                }
            }
            var arrowY = y + CARD_HEIGHT / 2;
            arrow(draw, x + panel + 8, arrowY, right - 8, arrowY, ACCENT);
            centered(draw, x + panel + MIDDLE / 2, arrowY - 20, SMALL, ACCENT, "build");
            var text = y + CARD_HEIGHT + GuideDiagrams.LINE_GAP;
            centered(draw, x + panel / 2, text, SMALL, TEXT, "Meshes and terrain");
            centered(draw, x + panel / 2, text + LABEL_LINE, SMALL, MUTED, "what you see");
            centered(draw, right + panel / 2, text, SMALL, TEXT, "Cells of 16 units");
            centered(draw, right + panel / 2, text + LABEL_LINE, SMALL, MUTED, "walk or blocked ×");
        }

        private static float hill(float t) {
            return (float) (.35 + .3 * Math.sin(t * Math.PI * 1.4 + .2) + .15 * Math.sin(t * 9));
        }
    }

    private static final class OneCell implements GuideDiagram {
        private static final float SIDE = 96;
        private static final float NEIGHBOR = 34;
        private static final float LEGEND = 168;

        @Override
        public float getHeight(float width) {
            var grid = SIDE + 2 * NEIGHBOR + 26;
            return isStacked(width) ? grid + 12 + legendHeight() : grid;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var grid = SIDE + 2 * NEIGHBOR;
            var gx = x;
            var gy = y;
            for (var row = 0; row <= 3; row++) {
                var lineY = gy + (row == 0 ? 0 : row == 1 ? NEIGHBOR : row == 2 ? NEIGHBOR + SIDE : grid);
                draw.addLine(gx, lineY, gx + grid, lineY, Motion.alpha(BORDER, row == 0 || row == 3 ? .4f : 1f), 1);
            }
            for (var column = 0; column <= 3; column++) {
                var lineX = gx + (column == 0 ? 0 : column == 1 ? NEIGHBOR : column == 2 ? NEIGHBOR + SIDE : grid);
                draw.addLine(
                        lineX, gy, lineX, gy + grid, Motion.alpha(BORDER, column == 0 || column == 3 ? .4f : 1f), 1);
            }
            var left = gx + NEIGHBOR;
            var top = gy + NEIGHBOR;
            var rightEdge = left + SIDE;
            var bottom = top + SIDE;
            draw.addRectFilled(left, top, rightEdge, bottom, Motion.alpha(ACCENT, .12f), 0f);
            draw.addRectFilled(left + 4, top, rightEdge - 4, top + 3, OPEN, 1f);
            draw.addRectFilled(left, top + 4, left + 3, bottom - 4, OPEN, 1f);
            draw.addRectFilled(left + 4, bottom - 3, rightEdge - 4, bottom, OPEN, 1f);
            draw.addRectFilled(rightEdge - 6, top + 2, rightEdge, bottom - 2, BLOCKED, 1f);
            label(draw, left + SIDE / 2 - 14 - labelWidth(SMALL, "N"), top + 8, SMALL, MUTED, "N");
            centered(draw, left + SIDE / 2, bottom - 8 - SMALL, SMALL, MUTED, "S");
            label(draw, left + 9, top + SIDE / 2 - SMALL / 2f - 1, SMALL, MUTED, "W");
            label(draw, rightEdge - 14 - labelWidth(SMALL, "E"), top + SIDE / 2 - SMALL - 12, SMALL, MUTED, "E");
            var middleX = left + SIDE / 2;
            var middleY = top + SIDE / 2;
            draw.addCircleFilled(middleX, middleY, 4, TEXT, 16);
            arrow(draw, middleX, middleY - 8, middleX, gy + 8, OPEN);
            draw.addLine(middleX + 8, middleY, rightEdge - 14, middleY, BLOCKED, 2);
            cross(draw, rightEdge - 10, middleY, 4, BLOCKED);
            var measure = gy + grid + 8;
            draw.addLine(left, measure, rightEdge, measure, FAINT, 1);
            draw.addLine(left, measure - 3, left, measure + 3, FAINT, 1);
            draw.addLine(rightEdge, measure - 3, rightEdge, measure + 3, FAINT, 1);
            centered(draw, left + SIDE / 2, measure + 4, SMALL, MUTED, "16 units");
            var legendX = isStacked(width) ? x : gx + grid + 24;
            var legendY = isStacked(width) ? gy + grid + 38 : gy + 10;
            legendEntry(draw, legendX, legendY, OPEN, "Open side", "a step across goes on");
            legendEntry(draw, legendX, legendY + 2 * LABEL_LINE + 8, BLOCKED, "Closed side", "a wall: the step stops");
            draw.addRect(
                    legendX,
                    legendY + 4 * LABEL_LINE + 20,
                    legendX + 16,
                    legendY + 4 * LABEL_LINE + 30,
                    ACCENT,
                    0f,
                    0,
                    1);
            label(draw, legendX + 24, legendY + 4 * LABEL_LINE + 16, SMALL, TEXT, "One cell");
            label(draw, legendX + 24, legendY + 5 * LABEL_LINE + 16, SMALL, MUTED, "16 by 16 units");
        }

        private static boolean isStacked(float width) {
            return width < SIDE + 2 * NEIGHBOR + 24 + LEGEND;
        }

        private static float legendHeight() {
            return 3 * LABEL_LINE * 2 + 2 * 8;
        }

        private static void legendEntry(ImDrawList draw, float x, float y, int color, String title, String detail) {
            draw.addRectFilled(x, y + 6, x + 16, y + 10, color, 1f);
            label(draw, x + 24, y, SMALL, TEXT, title);
            label(draw, x + 24, y + LABEL_LINE, SMALL, MUTED, detail);
        }
    }

    private static final class Layers implements GuideDiagram {
        private static final float HEIGHT = 176;
        private static final float COLUMN = 26;

        @Override
        public float getHeight(float width) {
            return HEIGHT;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var axis = x + 14;
            var groundY = y + HEIGHT - 26;
            var deckY = y + 58;
            var labels = Math.max(150, width * .42f);
            var sceneRight = x + width - labels;
            var sceneLeft = axis + 14;
            arrow(draw, axis, groundY + 10, axis, y + 6, FAINT);
            label(draw, axis + 6, y + 2, SMALL, FAINT, "Z");
            for (var lineX = sceneLeft; lineX <= sceneRight; lineX += COLUMN) {
                draw.addLine(lineX, y + 24, lineX, groundY + 10, Motion.alpha(BORDER, .45f), 1);
            }
            draw.addRectFilled(sceneLeft, groundY, sceneRight, groundY + 10, Motion.alpha(BORDER, .6f), 0f);
            draw.addLine(sceneLeft, groundY, sceneRight, groundY, MUTED, 2);
            var bridgeLeft = sceneLeft + COLUMN;
            var bridgeRight = sceneRight - COLUMN;
            draw.addRectFilled(bridgeLeft, deckY, bridgeRight, deckY + 10, Motion.alpha(BORDER, .9f), 2f);
            draw.addLine(bridgeLeft, deckY, bridgeRight, deckY, MUTED, 2);
            draw.addRectFilled(bridgeLeft + 6, deckY + 10, bridgeLeft + 16, groundY, Motion.alpha(BORDER, .7f), 0f);
            draw.addRectFilled(bridgeRight - 16, deckY + 10, bridgeRight - 6, groundY, Motion.alpha(BORDER, .7f), 0f);
            var columns = (int) ((sceneRight - sceneLeft) / COLUMN);
            var cellLeft = sceneLeft + (columns / 2) * COLUMN;
            var cellRight = cellLeft + COLUMN;
            draw.addRectFilled(cellLeft, y + 24, cellRight, groundY + 10, Motion.alpha(ACCENT, .1f), 0f);
            draw.addRect(cellLeft, y + 24, cellRight, groundY + 10, Motion.alpha(ACCENT, .6f), 0f, 0, 1);
            draw.addLine(cellLeft + 2, deckY, cellRight - 2, deckY, ACCENT, 4);
            draw.addLine(cellLeft + 2, groundY, cellRight - 2, groundY, ACCENT, 4);
            figure(draw, cellLeft + COLUMN / 2, deckY);
            figure(draw, cellLeft + COLUMN / 2, groundY);
            var textX = sceneRight + 18;
            draw.addLine(cellRight + 4, deckY, textX - 6, deckY, Motion.alpha(ACCENT, .6f), 1);
            draw.addLine(cellRight + 4, groundY, textX - 6, groundY, Motion.alpha(ACCENT, .6f), 1);
            label(draw, textX, deckY - LABEL_LINE, SMALL, TEXT, "Layer 1 · Z 256");
            label(draw, textX, deckY, SMALL, MUTED, "on the bridge");
            label(draw, textX, groundY - LABEL_LINE, SMALL, TEXT, "Layer 2 · Z 0");
            label(draw, textX, groundY, SMALL, MUTED, "on the ground");
            centered(draw, (cellLeft + cellRight) / 2, y + 6, SMALL, ACCENT, "one cell");
        }

        private static void figure(ImDrawList draw, float x, float floor) {
            draw.addLine(x, floor - 4, x, floor - 16, TEXT, 2);
            draw.addCircleFilled(x, floor - 21, 4, TEXT, 16);
        }
    }

    private static final class Sizes implements GuideDiagram {
        private static final float REGION = 116;
        private static final float BLOCK = 92;
        private static final float CELL = 60;

        @Override
        public float getHeight(float width) {
            return REGION + 12 + 3 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var gap = (float) Math.floor((width - REGION - BLOCK - CELL) / 2);
            var regionX = x;
            var blockX = regionX + REGION + gap;
            var cellX = blockX + BLOCK + gap;
            var regionY = y;
            var blockY = y + (REGION - BLOCK) / 2;
            var cellY = y + (REGION - CELL) / 2;
            draw.addRectFilled(regionX, regionY, regionX + REGION, regionY + REGION, CARD, 0f);
            for (var line = 1; line < 16; line++) {
                var step = REGION / 16 * line;
                draw.addLine(regionX + step, regionY, regionX + step, regionY + REGION, Motion.alpha(BORDER, .5f), 1);
                draw.addLine(regionX, regionY + step, regionX + REGION, regionY + step, Motion.alpha(BORDER, .5f), 1);
            }
            draw.addRect(regionX, regionY, regionX + REGION, regionY + REGION, MUTED, 0f, 0, 1);
            var chosenX = regionX + REGION / 16 * 11;
            var chosenY = regionY + REGION / 16 * 4;
            var chosen = REGION / 16;
            draw.addRectFilled(chosenX, chosenY, chosenX + chosen, chosenY + chosen, ACCENT, 0f);
            zoom(draw, chosenX + chosen, chosenY, chosenY + chosen, blockX, blockY, blockY + BLOCK);
            draw.addRectFilled(blockX, blockY, blockX + BLOCK, blockY + BLOCK, CARD, 0f);
            for (var line = 1; line < 8; line++) {
                var step = BLOCK / 8 * line;
                draw.addLine(blockX + step, blockY, blockX + step, blockY + BLOCK, BORDER, 1);
                draw.addLine(blockX, blockY + step, blockX + BLOCK, blockY + step, BORDER, 1);
            }
            draw.addRect(blockX, blockY, blockX + BLOCK, blockY + BLOCK, ACCENT, 0f, 0, 1.5f);
            var cellStep = BLOCK / 8;
            var pickedX = blockX + cellStep * 5;
            var pickedY = blockY + cellStep * 2;
            draw.addRectFilled(pickedX, pickedY, pickedX + cellStep, pickedY + cellStep, ACCENT, 0f);
            zoom(draw, pickedX + cellStep, pickedY, pickedY + cellStep, cellX, cellY, cellY + CELL);
            draw.addRectFilled(cellX, cellY, cellX + CELL, cellY + CELL, Motion.alpha(ACCENT, .14f), 0f);
            draw.addRect(cellX, cellY, cellX + CELL, cellY + CELL, ACCENT, 0f, 0, 1.5f);
            var text = y + REGION + 12;
            names(draw, regionX + REGION / 2, text, "Region", "32768 units", "256 × 256 blocks");
            names(draw, blockX + BLOCK / 2, text, "Block", "128 units", "8 × 8 cells");
            names(draw, cellX + CELL / 2, text, "Cell", "16 units", "its floors");
        }

        private static void zoom(
                ImDrawList draw, float fromX, float fromTop, float fromBottom, float toX, float toTop, float toBottom) {
            draw.addLine(fromX, fromTop, toX, toTop, Motion.alpha(ACCENT, .45f), 1);
            draw.addLine(fromX, fromBottom, toX, toBottom, Motion.alpha(ACCENT, .45f), 1);
        }

        private static void names(ImDrawList draw, float middle, float top, String name, String size, String holds) {
            centered(draw, middle, top, LABEL, TEXT, name);
            centered(draw, middle, top + LABEL_LINE, SMALL, MUTED, size);
            centered(draw, middle, top + 2 * LABEL_LINE, SMALL, MUTED, holds);
        }
    }

    private static final class Blocks implements GuideDiagram {
        private static final float MOST = 96;

        @Override
        public float getHeight(float width) {
            return side(width) + 12 + 3 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var side = side(width);
            var gap = (float) Math.floor((width - 3 * side) / 2);
            var cell = side / 8;
            for (var kind = 0; kind < 3; kind++) {
                var left = x + kind * (side + gap);
                for (var column = 0; column < 8; column++) {
                    for (var row = 0; row < 8; row++) {
                        var cellX = left + column * cell;
                        var cellY = y + row * cell;
                        var shade = kind == 0 ? .5f : .25f + .5f * noise(column, row);
                        draw.addRectFilled(
                                cellX, cellY, cellX + cell, cellY + cell, Motion.mix(CARD, MUTED, shade * .5f), 0f);
                        if (kind == 2 && noise(row, column) > .8f) {
                            draw.addLine(
                                    cellX + 2, cellY + cell * .35f, cellX + cell - 2, cellY + cell * .35f, ACCENT, 2);
                            draw.addLine(
                                    cellX + 2, cellY + cell * .72f, cellX + cell - 2, cellY + cell * .72f, ACCENT, 2);
                        }
                    }
                }
                for (var line = 1; line < 8; line++) {
                    var step = line * cell;
                    var color = Motion.alpha(BORDER, kind == 0 ? .35f : .8f);
                    draw.addLine(left + step, y, left + step, y + side, color, 1);
                    draw.addLine(left, y + step, left + side, y + step, color, 1);
                }
                draw.addRect(left, y, left + side, y + side, MUTED, 0f, 0, 1);
                var middle = left + side / 2;
                var text = y + side + 12;
                switch (kind) {
                    case 0 -> {
                        centered(draw, middle, text, LABEL, TEXT, "Flat");
                        centered(draw, middle, text + LABEL_LINE, SMALL, MUTED, "one height");
                        centered(draw, middle, text + 2 * LABEL_LINE, SMALL, MUTED, "open all ways");
                    }
                    case 1 -> {
                        centered(draw, middle, text, LABEL, TEXT, "One floor each");
                        centered(draw, middle, text + LABEL_LINE, SMALL, MUTED, "64 heights");
                        centered(draw, middle, text + 2 * LABEL_LINE, SMALL, MUTED, "and sides");
                    }
                    default -> {
                        centered(draw, middle, text, LABEL, TEXT, "Several floors");
                        centered(draw, middle, text + LABEL_LINE, SMALL, MUTED, "a count in");
                        centered(draw, middle, text + 2 * LABEL_LINE, SMALL, MUTED, "each cell");
                    }
                }
            }
        }

        private static float side(float width) {
            return (float) Math.floor(Math.min(MOST, (width - 2 * 24) / 3) / 8) * 8;
        }

        private static float noise(int a, int b) {
            var value = (a * 73 + b * 151 + a * b * 17) % 97;
            return value / 96f;
        }
    }

    private static final class OneWay implements GuideDiagram {
        private static final float SIDE = 84;

        @Override
        public float getHeight(float width) {
            return SIDE + 12 + 2 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var pair = 2 * SIDE;
            var left = x + (float) Math.floor((width - pair) / 2);
            var middle = left + SIDE;
            var right = middle + SIDE;
            draw.addRectFilled(left, y, right, y + SIDE, Motion.alpha(ACCENT, .08f), 0f);
            draw.addRect(left, y, middle, y + SIDE, BORDER, 0f, 0, 1);
            draw.addRect(middle, y, right, y + SIDE, BORDER, 0f, 0, 1);
            label(draw, left + 8, y + 6, SMALL, MUTED, "Cell A");
            GuideDiagrams.rightAligned(draw, right - 8, y + 6, SMALL, MUTED, "Cell B");
            draw.addRectFilled(middle - 6, y + 4, middle - 1, y + SIDE - 4, BLOCKED, 1f);
            draw.addRectFilled(middle + 1, y + 4, middle + 4, y + SIDE - 4, OPEN, 1f);
            var upper = y + SIDE * .42f;
            var lower = y + SIDE * .72f;
            arrow(draw, right - 14, upper, left + 14, upper, OPEN);
            draw.addLine(left + 14, lower, middle - 16, lower, BLOCKED, 2);
            cross(draw, middle - 11, lower, 4, BLOCKED);
            var text = y + SIDE + 12;
            GuideDiagrams.rightAligned(draw, middle - 12, text, SMALL, TEXT, "A to B: stops");
            GuideDiagrams.rightAligned(draw, middle - 12, text + LABEL_LINE, SMALL, MUTED, "A's east side is closed");
            label(draw, middle + 12, text, SMALL, TEXT, "B to A: goes on");
            label(draw, middle + 12, text + LABEL_LINE, SMALL, MUTED, "B's west side is open");
        }
    }

    private static final class FloorBeside implements GuideDiagram {
        private static final float HEIGHT = 156;
        private static final float COLUMN = 18;

        @Override
        public float getHeight(float width) {
            return HEIGHT;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var axis = x + 14;
            var groundY = y + HEIGHT - 26;
            var deckY = y + 70;
            var labels = Math.max(150, width * .42f);
            var sceneLeft = axis + 14;
            var columns = Math.max(4, (int) ((x + width - labels - sceneLeft) / COLUMN));
            var sceneRight = sceneLeft + columns * COLUMN;
            var old = Math.max(2, Math.round(columns * .4f));
            var added = Math.min(3, columns - old);
            var oldRight = sceneLeft + old * COLUMN;
            var addedRight = oldRight + added * COLUMN;
            arrow(draw, axis, groundY + 10, axis, y + 6, FAINT);
            label(draw, axis + 6, y + 2, SMALL, FAINT, "Z");
            for (var column = 0; column <= columns; column++) {
                var lineX = sceneLeft + column * COLUMN;
                draw.addLine(lineX, y + 24, lineX, groundY + 10, Motion.alpha(BORDER, .45f), 1);
            }
            draw.addRectFilled(sceneLeft, groundY, sceneRight, groundY + 10, Motion.alpha(BORDER, .6f), 0f);
            draw.addLine(sceneLeft, groundY, sceneRight, groundY, MUTED, 2);
            draw.addRectFilled(sceneLeft, deckY, oldRight, groundY, Motion.alpha(BORDER, .7f), 0f);
            draw.addRectFilled(oldRight, deckY, addedRight, deckY + 8, Motion.alpha(BORDER, .9f), 0f);
            for (var cell = 0; cell < old + added; cell++) {
                var left = sceneLeft + cell * COLUMN;
                draw.addLine(left + 2, deckY, left + COLUMN - 2, deckY, cell < old ? OPEN : ACCENT, 4);
            }
            var pressX = sceneLeft + (old - .5f) * COLUMN;
            draw.addLine(pressX, deckY - 4, pressX, deckY - 16, TEXT, 2);
            draw.addCircleFilled(pressX, deckY - 21, 4, TEXT, 16);
            var dragY = deckY - 36;
            arrow(draw, pressX, dragY, addedRight - 2, dragY, ACCENT);
            centered(draw, (pressX + addedRight) / 2, dragY - LABEL_LINE - 2, SMALL, ACCENT, "press and drag");
            centered(draw, (sceneLeft + oldRight) / 2, deckY + 10, SMALL, MUTED, "floor");
            var textX = sceneRight + 18;
            draw.addLine(addedRight + 4, deckY, textX - 6, deckY, Motion.alpha(ACCENT, .6f), 1);
            draw.addLine(sceneRight + 4, groundY, textX - 6, groundY, Motion.alpha(MUTED, .6f), 1);
            label(draw, textX, deckY - LABEL_LINE, SMALL, TEXT, "New layers · Z 64");
            label(draw, textX, deckY, SMALL, MUTED, "at the floor's height");
            label(draw, textX, groundY - LABEL_LINE, SMALL, TEXT, "Old layers · Z 0");
            label(draw, textX, groundY, SMALL, MUTED, "stay on the ground");
        }
    }

    static void card(ImDrawList draw, float x, float y, float width, float height) {
        draw.addRectFilled(x, y, x + width, y + height, CARD, 8f);
        draw.addRect(x, y, x + width, y + height, GeodataWidgets.DIVIDER, 8f, 0, 1);
    }

    static void arrow(ImDrawList draw, float fromX, float fromY, float toX, float toY, int color) {
        var dx = toX - fromX;
        var dy = toY - fromY;
        var length = (float) Math.hypot(dx, dy);
        if (length < 1) {
            return;
        }
        var ux = dx / length;
        var uy = dy / length;
        var head = 7f;
        draw.addLine(fromX, fromY, toX - ux * head, toY - uy * head, color, 2);
        draw.addTriangleFilled(
                toX,
                toY,
                toX - ux * head - uy * head * .6f,
                toY - uy * head + ux * head * .6f,
                toX - ux * head + uy * head * .6f,
                toY - uy * head - ux * head * .6f,
                color);
    }

    static void cross(ImDrawList draw, float x, float y, float half, int color) {
        draw.addLine(x - half, y - half, x + half, y + half, color, 2);
        draw.addLine(x - half, y + half, x + half, y - half, color, 2);
    }
}
