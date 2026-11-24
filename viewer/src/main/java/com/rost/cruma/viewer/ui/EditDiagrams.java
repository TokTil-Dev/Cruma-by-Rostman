package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.StartDiagrams.fit;

import imgui.ImDrawList;
import java.util.Map;

final class EditDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int BORDER = HudWidgets.BORDER;
    private static final int OPEN = GeodataWidgets.OPEN;
    private static final int WARNING = GeodataWidgets.WARNING;
    private static final int SMALL = 12;
    private static final float LINE = 16;
    private static final float PAD = 10;

    private EditDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("edit-tile-parts", new TileParts());
        diagrams.put("edit-gizmo", new Gizmo());
        diagrams.put("edit-heightmap", new Heightmap());
        diagrams.put("edit-terrain-layers", new TerrainLayers());
        diagrams.put("edit-light-reach", new LightReach());
        diagrams.put("edit-texture-mapping", new TextureMapping());
        diagrams.put("edit-save-path", new SavePath());
    }

    private static boolean narrow(float width) {
        return width < 380;
    }

    private static final class TileParts implements GuideDiagram {
        private static final String[][] ROWS = {
            {"Terrain", "sculpt heights, paint layers, holes", "yes"},
            {"Static meshes", "move, place, delete, new mesh", "yes"},
            {"Lights", "add, move, colour, delete", "yes"},
            {"BSP walls and floors", "texture, hide, delete some", "yes"},
            {"Doors, lifts, effects, sounds", "read-only", "no"}
        };
        private static final float NAME = 176;
        private static final float OUTSIDE = PAD * 2 + LINE * 2;

        @Override
        public float getHeight(float width) {
            return card(width) + 12 + OUTSIDE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var height = card(width);
            StartDiagrams.titled(draw, x, y, width, height, "ONE TILE · 20_18.unr", true);
            var inner = width - 2 * PAD - 22;
            var top = y + PAD + LINE + 8;
            for (var part : ROWS) {
                var changes = part[2].equals("yes");
                var markY = top + 7;
                if (changes) {
                    draw.addCircleFilled(x + PAD + 6, markY, 5, ACCENT, 12);
                } else {
                    draw.addCircle(x + PAD + 6, markY, 5, FAINT, 12, 1.5f);
                    draw.addLine(x + PAD + 2, markY + 4, x + PAD + 10, markY - 4, FAINT, 1.5f);
                }
                var textX = x + PAD + 22;
                if (narrow(width)) {
                    label(draw, textX, top, SMALL, changes ? TEXT : MUTED, fit(part[0], inner));
                    label(draw, textX, top + LINE, SMALL, changes ? MUTED : FAINT, fit(part[1], inner));
                } else {
                    label(draw, textX, top, SMALL, changes ? TEXT : MUTED, fit(part[0], NAME - 8));
                    label(draw, textX + NAME, top, SMALL, changes ? MUTED : FAINT, fit(part[1], inner - NAME));
                }
                top += row(width);
            }
            var boxTop = y + height + 12;
            StartDiagrams.dashed(draw, x, boxTop, x + width, boxTop + OUTSIDE);
            label(
                    draw,
                    x + PAD,
                    boxTop + PAD - 2,
                    SMALL,
                    MUTED,
                    fit("Not in the map: geodata and NPC spawns.", width - 2 * PAD));
            label(
                    draw,
                    x + PAD,
                    boxTop + PAD - 2 + LINE,
                    SMALL,
                    MUTED,
                    fit("They are the server's, and Edit leaves them.", width - 2 * PAD));
        }

        private static float row(float width) {
            return narrow(width) ? 2 * LINE + 6 : LINE + 8;
        }

        private static float card(float width) {
            return PAD + LINE + 8 + ROWS.length * row(width) + PAD - 6;
        }
    }

    private static final class Gizmo implements GuideDiagram {
        private static final float FIGURE = 92;
        private static final float RULER = 54;

        @Override
        public float getHeight(float width) {
            return FIGURE + LINE + 4 + 20 + RULER;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var column = width / 3;
            var middleY = y + FIGURE / 2;
            move(draw, x + column / 2, middleY);
            rotate(draw, x + column * 1.5f, middleY);
            scale(draw, x + column * 2.5f, middleY);
            var names = new String[] {"Move · W", "Rotate · E", "Scale · R"};
            for (var index = 0; index < 3; index++) {
                centered(draw, x + column * (index + .5f), y + FIGURE + 4, SMALL, TEXT, names[index]);
            }
            snapping(draw, x, y + FIGURE + LINE + 24, width);
        }

        private static void pivot(ImDrawList draw, float x, float y) {
            draw.addCircleFilled(x, y, 4, ACCENT, 12);
        }

        private static void move(ImDrawList draw, float x, float y) {
            var arm = 34f;
            GeodataDiagrams.arrow(draw, x, y, x + arm, y, MUTED);
            GeodataDiagrams.arrow(draw, x, y, x, y - arm, MUTED);
            GeodataDiagrams.arrow(draw, x, y, x - arm * .7f, y + arm * .7f, MUTED);
            label(draw, x + arm + 3, y - 7, SMALL, MUTED, "X");
            label(draw, x + 5, y - arm - 6, SMALL, MUTED, "Z");
            label(draw, x - arm * .7f - 12, y + arm * .7f - 4, SMALL, MUTED, "Y");
            pivot(draw, x, y);
            label(draw, x + 8, y + 6, SMALL, ACCENT, "pivot");
        }

        private static void rotate(ImDrawList draw, float x, float y) {
            var radius = 30f;
            draw.addCircle(x, y, radius, MUTED, 40, 2);
            var segments = 40;
            for (var index = 0; index < segments; index++) {
                var from = index * 2 * Math.PI / segments;
                var to = (index + 1) * 2 * Math.PI / segments;
                draw.addLine(
                        x + radius * (float) Math.cos(from),
                        y + radius * .38f * (float) Math.sin(from),
                        x + radius * (float) Math.cos(to),
                        y + radius * .38f * (float) Math.sin(to),
                        MUTED,
                        1);
            }
            GeodataDiagrams.arrow(
                    draw, x + radius * .5f, y - radius * .87f, x + radius * .87f, y - radius * .5f, ACCENT);
            pivot(draw, x, y);
        }

        private static void scale(ImDrawList draw, float x, float y) {
            var arm = 30f;
            draw.addLine(x, y, x + arm, y, MUTED, 2);
            draw.addLine(x, y, x, y - arm, MUTED, 2);
            draw.addLine(x, y, x - arm * .7f, y + arm * .7f, MUTED, 2);
            for (var end : new float[][] {{x + arm, y}, {x, y - arm}, {x - arm * .7f, y + arm * .7f}}) {
                draw.addRectFilled(end[0] - 4, end[1] - 4, end[0] + 4, end[1] + 4, MUTED, 1f);
            }
            draw.addRect(x - 7, y - 7, x + 7, y + 7, ACCENT, 1, 0, 1.5f);
            pivot(draw, x, y);
        }

        private static void snapping(ImDrawList draw, float x, float y, float width) {
            var step = Math.max(24, Math.min(36, (float) Math.floor((width - 16) / 9)));
            var steps = (int) Math.floor((width - 16) / step);
            var lineY = y + 22;
            draw.addLine(x, lineY, x + steps * step, lineY, BORDER, 1);
            for (var tick = 0; tick <= steps; tick++) {
                var tx = Math.round(x + tick * step);
                draw.addLine(tx, lineY - 4, tx, lineY + 4, FAINT, 1);
            }
            var box = 14f;
            for (var at = 1; at <= 3; at++) {
                var bx = Math.round(x + at * step - box / 2);
                if (at < 3) {
                    draw.addRect(bx, lineY - box - 6, bx + box, lineY - 6, FAINT, 2, 0, 1);
                } else {
                    draw.addRectFilled(bx, lineY - box - 6, bx + box, lineY - 6, Motion.alpha(ACCENT, .25f), 2f);
                    draw.addRect(bx, lineY - box - 6, bx + box, lineY - 6, ACCENT, 2, 0, 1.5f);
                }
            }
            label(draw, x, lineY + 8, SMALL, MUTED, fit("Snapping: a drag jumps from step to step.", width));
            label(draw, x, lineY + 8 + LINE, SMALL, MUTED, fit("Grid 16 units, Angle 15°, Scale 0.1 at first.", width));
        }
    }

    private static final class Heightmap implements GuideDiagram {
        private static final float PROFILE = 64;

        @Override
        public float getHeight(float width) {
            return grid(width) + 2 * LINE + 16 + PROFILE + LINE + 4;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var columns = columns(width);
            var gap = spacing(width);
            var left = x + 4;
            var top = y + 4;
            var rows = 7;
            var cx = left + gap * (columns / 2 + 1);
            var cy = top + gap * 3;
            var outer = gap * 2.6f;
            var inner = outer * .55f;
            for (var row = 0; row < rows; row++) {
                for (var column = 0; column < columns; column++) {
                    var px = left + column * gap;
                    var py = top + row * gap;
                    var distance = (float) Math.hypot(px - cx, py - cy);
                    var effect =
                            distance <= inner ? 1 : distance >= outer ? 0 : 1 - (distance - inner) / (outer - inner);
                    var color = effect > 0 ? Motion.alpha(ACCENT, .3f + .7f * effect) : FAINT;
                    draw.addCircleFilled(
                            Math.round(px), Math.round(py), effect > 0 ? 1.5f + 2.5f * effect : 2, color, 8);
                }
            }
            draw.addCircle(cx, cy, outer, ACCENT, 48, 2);
            draw.addCircle(cx, cy, inner, Motion.alpha(ACCENT, .5f), 40, 1);
            var labelsY = y + grid(width) + 10;
            label(draw, x, labelsY, SMALL, MUTED, fit("A point every 128 units. The brush works fully", width));
            label(draw, x, labelsY + LINE, SMALL, MUTED, fit("inside its inner ring, then fades to its edge.", width));
            profile(draw, x, labelsY + 2 * LINE + 10, width, columns, gap, cx - x, outer, inner);
        }

        private static int columns(float width) {
            return narrow(width) ? 11 : 15;
        }

        private static float spacing(float width) {
            return (float) Math.floor((width - 8) / (columns(width) - 1));
        }

        private static float grid(float width) {
            return spacing(width) * 6 + 8;
        }

        private static void profile(
                ImDrawList draw,
                float x,
                float y,
                float width,
                int columns,
                float gap,
                float center,
                float outer,
                float inner) {
            var base = y + PROFILE;
            draw.addLine(x, base, x + width, base, BORDER, 1);
            float lastX = 0;
            float lastY = 0;
            for (var column = 0; column < columns; column++) {
                var px = x + 4 + column * gap;
                var distance = Math.abs(px - x - center);
                var effect = distance <= inner ? 1 : distance >= outer ? 0 : 1 - (distance - inner) / (outer - inner);
                effect = effect * effect * (3 - 2 * effect);
                var ground = 14 + 6 * (float) Math.sin(column * .9f);
                var height = ground + 30 * effect;
                var top = base - height;
                draw.addLine(px, base, px, top, effect > 0 ? Motion.alpha(ACCENT, .6f) : FAINT, 1);
                draw.addCircleFilled(px, top, 2.5f, effect > 0 ? ACCENT : MUTED, 8);
                if (column > 0) {
                    draw.addLine(lastX, lastY, px, top, MUTED, 1.5f);
                }
                lastX = px;
                lastY = top;
            }
            label(draw, x, base + 4, SMALL, MUTED, fit("From the side: Raise lifts the points under it.", width));
        }
    }

    private static final class TerrainLayers implements GuideDiagram {
        private static final float GAP = 18;
        private static final String[][] NAMES = {
            {"1 · Sand", "everywhere"},
            {"2 · Grass", "its mask"},
            {"3 · Rock", "its mask"},
            {"Ground", "what you see"}
        };

        @Override
        public float getHeight(float width) {
            return side(width) + 6 + 2 * LINE + 10 + LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var side = side(width);
            var gap = (width - 4 * side) / 3;
            for (var square = 0; square < 4; square++) {
                var left = Math.round(x + square * (side + gap));
                StartDiagrams.card(draw, left, y, side, side);
                if (square == 3) {
                    draw.addRect(left, y, left + side, y + side, ACCENT, 8f, 0, 1.5f);
                }
                var cells = 8;
                var cell = side / cells;
                for (var row = 0; row < cells; row++) {
                    for (var column = 0; column < cells; column++) {
                        var u = (column + .5f) / cells;
                        var v = (row + .5f) / cells;
                        var layer = square < 3 ? (covers(square, u, v) ? square : -1) : top(u, v);
                        mark(draw, left + (column + .5f) * cell, y + (row + .5f) * cell, layer);
                    }
                }
                var middle = left + side / 2;
                centered(
                        draw,
                        middle,
                        y + side + 6,
                        SMALL,
                        square == 3 ? ACCENT : TEXT,
                        fit(NAMES[square][0], side + gap - 4));
                centered(draw, middle, y + side + 6 + LINE, SMALL, MUTED, fit(NAMES[square][1], side + gap - 4));
                if (square < 3) {
                    centered(draw, left + side + gap / 2, y + side / 2 - 8, 13, MUTED, square < 2 ? "+" : "=");
                }
            }
            label(
                    draw,
                    x,
                    y + side + 2 * LINE + 16,
                    SMALL,
                    MUTED,
                    fit("Later layers are drawn over earlier ones.", width));
        }

        private static float side(float width) {
            return Math.min(72, (float) Math.floor((width - 3 * GAP) / 4));
        }

        private static boolean covers(int layer, float u, float v) {
            return switch (layer) {
                case 0 -> true;
                case 1 -> Math.hypot(u - .38f, v - .6f) < .34f;
                default -> u + (1 - v) > 1.25f;
            };
        }

        private static int top(float u, float v) {
            for (var layer = 2; layer >= 0; layer--) {
                if (covers(layer, u, v)) {
                    return layer;
                }
            }
            return 0;
        }

        private static void mark(ImDrawList draw, float x, float y, int layer) {
            switch (layer) {
                case 0 -> draw.addCircleFilled(x, y, 1.5f, WARNING, 6);
                case 1 -> draw.addLine(x - 1.5f, y + 2.5f, x + 1.5f, y - 2.5f, OPEN, 1.5f);
                case 2 -> draw.addRectFilled(x - 2, y - 2, x + 2, y + 2, MUTED, 0f);
                default -> draw.addCircleFilled(x, y, 1, Motion.alpha(FAINT, .5f), 4);
            }
        }
    }

    private static final class LightReach implements GuideDiagram {
        private static final String[][] LEGEND = {
            {"Mesh", "keeps the light in its points"},
            {"Wall (BSP)", "keeps it in a lightmap"},
            {"Ground", "keeps it at its points"}
        };

        @Override
        public float getHeight(float width) {
            return picture(width) + 8 + LEGEND.length * (narrow(width) ? 2 * LINE + 4 : LINE + 4) + LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var reach = reach(width);
            var lightX = Math.round(x + width * .48f);
            var lightY = y + reach + 4;
            var ground = Math.round(lightY + reach * .7f);
            var wallLeft = Math.round(lightX + reach * .75f);
            var wallRight = Math.min(x + width, wallLeft + 40);
            draw.addLine(x, ground, wallLeft, ground, BORDER, 2);
            var half = (float) Math.sqrt(Math.max(0, reach * reach - (ground - lightY) * (ground - lightY)));
            draw.addLine(lightX - half, ground, Math.min(wallLeft, lightX + half), ground, ACCENT, 3);
            draw.addRectFilled(wallLeft, y + 4, wallRight, ground, Motion.alpha(BORDER, .5f), 0f);
            draw.addRect(wallLeft, y + 4, wallRight, ground, BORDER, 0, 0, 1);
            var wallReach = (float) Math.sqrt(Math.max(0, reach * reach - (wallLeft - lightX) * (wallLeft - lightX)));
            draw.addLine(wallLeft, lightY - wallReach, wallLeft, Math.min(ground, lightY + wallReach), ACCENT, 3);
            var meshLeft = lightX - reach * .62f;
            box(draw, meshLeft, ground - 30, 22, 30, true);
            box(draw, x + 8, ground - 24, 18, 24, false);
            label(draw, x + 4, ground + 2, SMALL, FAINT, "out of reach");
            draw.addCircle(lightX, lightY, reach, ACCENT, 64, 1.5f);
            draw.addCircle(lightX, lightY, reach * .5f, Motion.alpha(ACCENT, .45f), 48, 1);
            draw.addCircleFilled(lightX, lightY, 6, ACCENT, 16);
            for (var ray = 0; ray < 8; ray++) {
                var angle = ray * Math.PI / 4;
                var cos = (float) Math.cos(angle);
                var sin = (float) Math.sin(angle);
                draw.addLine(lightX + cos * 9, lightY + sin * 9, lightX + cos * 13, lightY + sin * 13, ACCENT, 1.5f);
            }
            GuideDiagrams.rightAligned(
                    draw, lightX - reach * .72f - 2, lightY - reach * .72f - 14, SMALL, ACCENT, "reach");
            var top = y + picture(width) + 8;
            var inner = width - 22;
            for (var entry : LEGEND) {
                draw.addRectFilled(x + 2, top + 5, x + 14, top + 9, ACCENT, 1f);
                if (narrow(width)) {
                    label(draw, x + 22, top, SMALL, TEXT, fit(entry[0], inner));
                    label(draw, x + 22, top + LINE, SMALL, MUTED, fit(entry[1], inner));
                    top += 2 * LINE + 4;
                } else {
                    label(draw, x + 22, top, SMALL, TEXT, entry[0]);
                    label(draw, x + 112, top, SMALL, MUTED, fit(entry[1], width - 112));
                    top += LINE + 4;
                }
            }
            label(draw, x, top, SMALL, MUTED, fit("Reach: 25 × (Radius + 1) units.", width));
        }

        private static float reach(float width) {
            return (float) Math.floor(Math.min(64, width * .22f));
        }

        private static float picture(float width) {
            return 2 * reach(width) + 10;
        }

        private static void box(ImDrawList draw, float x, float y, float width, float height, boolean lit) {
            draw.addRectFilled(x, y, x + width, y + height, Motion.alpha(BORDER, .6f), 2f);
            draw.addRect(x, y, x + width, y + height, lit ? MUTED : FAINT, 2, 0, 1);
            if (lit) {
                draw.addLine(x + width, y + 2, x + width, y + height - 1, ACCENT, 3);
                draw.addLine(x + 2, y, x + width - 1, y, ACCENT, 3);
            }
        }
    }

    private static final class TextureMapping implements GuideDiagram {
        private static final String[] NAMES = {"As saved", "Pan U", "Scale 2"};
        private static final float GAP = 16;

        @Override
        public float getHeight(float width) {
            return side(width) + 6 + LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var side = side(width);
            var gap = (width - 3 * side) / 2;
            for (var panel = 0; panel < 3; panel++) {
                var left = Math.round(x + panel * (side + gap));
                StartDiagrams.card(draw, left, y, side, side);
                var brick = panel == 2 ? side / 2 : side / 4;
                var shift = panel == 1 ? side / 8 : 0;
                bricks(draw, left, y, side, brick, shift);
                if (panel == 0) {
                    var ox = left + 8;
                    var oy = y + 8;
                    GeodataDiagrams.arrow(draw, ox, oy, ox + side * .45f, oy, ACCENT);
                    GeodataDiagrams.arrow(draw, ox, oy, ox, oy + side * .45f, ACCENT);
                    label(draw, ox + side * .45f + 2, oy - 8, SMALL, ACCENT, "U");
                    label(draw, ox + 4, oy + side * .45f - 4, SMALL, ACCENT, "V");
                } else if (panel == 1) {
                    GeodataDiagrams.arrow(
                            draw, left + 8, y + side - 10, left + 8 + side / 8 + 10, y + side - 10, ACCENT);
                }
                centered(draw, left + side / 2, y + side + 6, SMALL, TEXT, fit(NAMES[panel], side + gap - 4));
            }
        }

        private static float side(float width) {
            return Math.min(112, (float) Math.floor((width - 2 * GAP) / 3));
        }

        private static void bricks(ImDrawList draw, float x, float y, float side, float brick, float shift) {
            var height = brick / 2;
            draw.pushClipRect(x + 1, y + 1, x + side - 1, y + side - 1, true);
            var row = 0;
            for (var top = y; top < y + side; top += height) {
                draw.addLine(x, top, x + side, top, BORDER, 1);
                var offset = (row % 2 == 0 ? 0 : brick / 2) + shift;
                for (var at = x - brick + offset % brick; at < x + side; at += brick) {
                    draw.addLine(at, top, at, top + height, BORDER, 1);
                }
                row++;
            }
            draw.popClipRect();
        }
    }

    private static final class SavePath implements GuideDiagram {
        private static final float ARROW = 40;

        @Override
        public float getHeight(float width) {
            return box(1) + ARROW + box(3) + ARROW + box(2);
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var top = y;
            boxed(draw, x, top, width, "EDIT", true, "Your changes wait in Undo and a draft.");
            top += box(1);
            step(draw, x, top, width, "Save… checks, then Save writes");
            top += ARROW;
            boxed(
                    draw,
                    x,
                    top,
                    width,
                    "SAVED FOLDER · Documents/Cruma/Exports",
                    false,
                    "Maps, Textures, StaticMeshes",
                    "manifest.json",
                    "client-backup, after an install");
            var backupY = top + PAD + LINE + 6 + 2 * LINE + 7;
            top += box(3);
            step(draw, x, top, width, "Also install into the client after Save");
            top += ARROW;
            boxed(
                    draw,
                    x,
                    top,
                    width,
                    "CLIENT",
                    false,
                    "Its files are replaced by the saved ones.",
                    "Restore client puts the old ones back.");
            var right = x + width - 14;
            var clientY = top + PAD + LINE + 6 + 7;
            draw.addLine(right, clientY, right + 8, clientY, WARNING, 1.5f);
            draw.addLine(right + 8, clientY, right + 8, backupY, WARNING, 1.5f);
            GeodataDiagrams.arrow(draw, right + 8, backupY, right - 2, backupY, WARNING);
        }

        private static float box(int lines) {
            return PAD + LINE + 6 + lines * LINE + PAD - 4;
        }

        private static void boxed(
                ImDrawList draw, float x, float y, float width, String title, boolean subject, String... lines) {
            var room = width - 2 * PAD - 18;
            StartDiagrams.titled(draw, x, y, width - 18, box(lines.length), title, subject);
            for (var index = 0; index < lines.length; index++) {
                label(draw, x + PAD, y + PAD + LINE + 6 + index * LINE - 2, SMALL, MUTED, fit(lines[index], room));
            }
        }

        private static void step(ImDrawList draw, float x, float y, float width, String text) {
            GeodataDiagrams.arrow(draw, x + 20, y + 4, x + 20, y + ARROW - 4, MUTED);
            label(draw, x + 34, y + ARROW / 2 - 8, SMALL, MUTED, fit(text, width - 34 - 18));
        }
    }
}
