package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.StartDiagrams.fit;

import imgui.ImDrawList;
import java.util.Map;

final class SceneDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int BORDER = HudWidgets.BORDER;
    private static final int SMALL = 12;
    private static final int LABEL = 13;
    private static final float LABEL_LINE = 16;
    private static final float PAD = 10;

    private SceneDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("scene-timeline", new Timeline());
        diagrams.put("director-parts", new Parts());
        diagrams.put("director-ease", new Ease());
        diagrams.put("director-shots", new Shots());
        diagrams.put("scene-outputs", new Outputs());
    }

    private static void track(ImDrawList draw, float left, float top, float right, float height) {
        draw.addRectFilled(left, top, right, top + height, Motion.alpha(BORDER, .4f), 2f);
    }

    private static final class Timeline implements GuideDiagram {
        private static final float NAMES = 64;
        private static final float ROW = 30;
        private static final float BAR = 12;
        private static final float END = 10;
        private static final float[] ITEMS = {0, 2.5f, 5, 7.5f};

        @Override
        public float getHeight(float width) {
            return LABEL_LINE + 6 + 4 * ROW + 4 + LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var left = x + NAMES;
            var span = x + width - left - 2;
            var top = y + LABEL_LINE + 6;
            var bottom = top + 4 * ROW;
            for (var item : ITEMS) {
                var at = at(left, span, item);
                draw.addLine(at, top - 2, at, bottom, FAINT, 1);
                draw.addQuadFilled(at, top - 8, at + 4, top - 4, at, top, at - 4, top - 4, TEXT);
            }
            label(draw, x, y, SMALL, MUTED, "ITEMS");
            String[] lanes = {"Camera", "Actor", "Screen", "Sound"};
            for (var lane = 0; lane < lanes.length; lane++) {
                var rowTop = top + lane * ROW;
                label(draw, x, rowTop + (ROW - SMALL) / 2 - 1, SMALL, TEXT, lanes[lane]);
                track(draw, left, rowTop + (ROW - BAR) / 2, left + span, BAR);
            }
            var bar = (ROW - BAR) / 2;
            bar(draw, left, span, top + bar, 0, 5, Motion.alpha(ACCENT, .45f), "Shot 1");
            bar(draw, left, span, top + bar, 5, END, Motion.alpha(ACCENT, .8f), "Shot 2");
            var actorTop = top + ROW + bar;
            bar(draw, left, span, actorTop, 0, 7.5f, Motion.alpha(MUTED, .35f), "NPC");
            GeodataDiagrams.arrow(
                    draw, at(left, span, 2.5f), actorTop + BAR / 2, at(left, span, 5) - 2, actorTop + BAR / 2, TEXT);
            var screenTop = top + 2 * ROW + bar;
            bar(draw, left, span, screenTop, 0, 1.6f, Motion.alpha(TEXT, .55f), "fade");
            bar(draw, left, span, screenTop, 5, 7.5f, Motion.alpha(TEXT, .3f), "line");
            bar(draw, left, span, top + 3 * ROW + bar, 0, END, Motion.alpha(MUTED, .3f), "music");
            label(draw, left, bottom + 4, SMALL, MUTED, "0 s");
            GuideDiagrams.rightAligned(draw, left + span, bottom + 4, SMALL, MUTED, "10 s");
        }

        private static float at(float left, float span, float seconds) {
            return left + (float) Math.floor(seconds / END * span);
        }

        private static void bar(
                ImDrawList draw, float left, float span, float top, float from, float to, int color, String name) {
            var x0 = at(left, span, from);
            var x1 = at(left, span, to);
            draw.addRectFilled(x0 + 1, top, x1 - 1, top + BAR, color, 2f);
            var room = x1 - x0 - 8;
            if (GuideDiagrams.labelWidth(SMALL, name) <= room) {
                label(draw, x0 + 5, top + (BAR - SMALL) / 2 - 1, SMALL, TEXT, name);
            }
        }
    }

    private static final class Parts implements GuideDiagram {
        private static final float WORLD = 132;
        private static final float TRAY = 76;
        private static final float GAP = 6;

        @Override
        public float getHeight(float width) {
            return WORLD + GAP + TRAY + GAP + LABEL_LINE * 2;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var panelWidth = (float) Math.floor(width * .3f);
            var worldWidth = width - panelWidth - GAP;
            draw.addRect(x, y, x + worldWidth, y + WORLD, BORDER, 8f, 0, 1);
            label(draw, x + PAD, y + PAD - 2, SMALL, FAINT, fit("WORLD", worldWidth - 2 * PAD));
            float[][] keys = {{.14f, .78f}, {.5f, .42f}, {.86f, .62f}};
            var previousX = 0f;
            var previousY = 0f;
            for (var index = 0; index < keys.length; index++) {
                var keyX = x + (float) Math.floor(worldWidth * keys[index][0]);
                var keyY = y + (float) Math.floor(WORLD * keys[index][1]);
                if (index > 0) {
                    draw.addBezierCubic(
                            previousX,
                            previousY,
                            previousX + (keyX - previousX) * .5f,
                            previousY - 18,
                            keyX - (keyX - previousX) * .5f,
                            keyY + 18,
                            keyX,
                            keyY,
                            ACCENT,
                            2,
                            24);
                }
                camera(draw, keyX, keyY, index == 1);
                previousX = keyX;
                previousY = keyY;
            }
            label(
                    draw,
                    x + PAD,
                    y + WORLD - PAD - SMALL,
                    SMALL,
                    MUTED,
                    fit("the path and the cameras of its keys", worldWidth - 2 * PAD));
            var panelLeft = x + worldWidth + GAP;
            var panelHeight = WORLD + GAP + TRAY;
            StartDiagrams.card(draw, panelLeft, y, panelWidth, panelHeight);
            label(draw, panelLeft + PAD, y + PAD - 2, SMALL, FAINT, fit("PANEL", panelWidth - 2 * PAD));
            label(draw, panelLeft + PAD, y + PAD - 2 + LABEL_LINE + 4, SMALL, TEXT, fit("KEY 2", panelWidth - 2 * PAD));
            for (var row = 0; row < 4; row++) {
                var rowTop = y + PAD + 2 * LABEL_LINE + 10 + row * 18;
                draw.addRectFilled(
                        panelLeft + PAD,
                        rowTop,
                        panelLeft + panelWidth - PAD,
                        rowTop + 10,
                        Motion.alpha(BORDER, .5f),
                        3f);
            }
            label(
                    draw,
                    panelLeft + PAD,
                    y + panelHeight - PAD - SMALL,
                    SMALL,
                    MUTED,
                    fit("the chosen key", panelWidth - 2 * PAD));
            var trayTop = y + WORLD + GAP;
            StartDiagrams.card(draw, x, trayTop, worldWidth, TRAY);
            var names = 52f;
            var laneLeft = x + PAD + names;
            var laneRight = x + worldWidth - PAD;
            var rulerY = trayTop + PAD + 4;
            for (var tick = 0; tick <= 10; tick++) {
                var tickX = laneLeft + (float) Math.floor((laneRight - laneLeft) * tick / 10f);
                draw.addLine(tickX, rulerY, tickX, rulerY + (tick % 5 == 0 ? 6 : 3), FAINT, 1);
            }
            label(draw, x + PAD, rulerY - 3, SMALL, FAINT, "TRAY");
            String[] lanes = {"Camera", "Actor"};
            for (var lane = 0; lane < lanes.length; lane++) {
                var laneTop = rulerY + 14 + lane * 18;
                label(draw, x + PAD, laneTop, SMALL, TEXT, lanes[lane]);
                track(draw, laneLeft, laneTop + 2, laneRight, 10);
                for (var key = 0; key < 3; key++) {
                    var keyX = laneLeft
                            + (float) Math.floor(
                                    (laneRight - laneLeft) * (lane == 0 ? .1f + key * .38f : .25f + key * .3f));
                    draw.addCircleFilled(keyX, laneTop + 7, 4, lane == 0 && key == 1 ? TEXT : MUTED, 12);
                }
            }
            var playhead = laneLeft + (float) Math.floor((laneRight - laneLeft) * .62f);
            draw.addLine(playhead, rulerY - 2, playhead, trayTop + TRAY - 6, ACCENT, 1.5f);
            draw.addTriangleFilled(playhead - 4, rulerY - 4, playhead + 4, rulerY - 4, playhead, rulerY + 2, ACCENT);
            var under = y + panelHeight + GAP;
            label(draw, x, under, SMALL, MUTED, fit("Tray: the ruler, the playhead and the tracks", width));
            label(draw, x, under + LABEL_LINE, SMALL, MUTED, fit("Panel: the scene and what is chosen", width));
        }

        private static void camera(ImDrawList draw, float x, float y, boolean chosen) {
            var color = chosen ? TEXT : MUTED;
            var size = chosen ? 9f : 7f;
            draw.addLine(x, y, x + size * 1.6f, y - size, color, 1);
            draw.addLine(x, y, x + size * 1.6f, y + size, color, 1);
            draw.addLine(x + size * 1.6f, y - size, x + size * 1.6f, y + size, color, 1.5f);
            draw.addCircleFilled(x, y, 3, color, 10);
        }
    }

    private static final class Ease implements GuideDiagram {
        private static final float CARD = 104;
        private static final float GAP = 12;

        @Override
        public float getHeight(float width) {
            return isStacked(width) ? 2 * CARD + GAP : CARD;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var stacked = isStacked(width);
            var cardWidth = stacked ? width : (float) Math.floor((width - GAP) / 2);
            drawCard(draw, x, y, cardWidth, true);
            if (stacked) {
                drawCard(draw, x, y + CARD + GAP, cardWidth, false);
            } else {
                drawCard(draw, x + cardWidth + GAP, y, cardWidth, false);
            }
        }

        private static boolean isStacked(float width) {
            return width < 300;
        }

        private static void drawCard(ImDrawList draw, float x, float y, float width, boolean smooth) {
            StartDiagrams.titled(draw, x, y, width, CARD, smooth ? "SMOOTH" : "CORNER", false);
            var left = x + PAD + 8;
            var right = x + width - PAD - 8;
            var lowY = y + CARD - PAD - LABEL_LINE - 10;
            var highY = y + PAD + LABEL_LINE + 8;
            var middleX = (left + right) / 2;
            if (smooth) {
                draw.addBezierQuadratic(left, lowY, middleX, highY - (lowY - highY) * .9f, right, lowY, ACCENT, 2, 32);
            } else {
                draw.addLine(left, lowY, middleX, highY, ACCENT, 2);
                draw.addLine(middleX, highY, right, lowY, ACCENT, 2);
            }
            draw.addCircleFilled(left, lowY, 4, MUTED, 12);
            draw.addCircleFilled(right, lowY, 4, MUTED, 12);
            if (smooth) {
                draw.addCircleFilled(middleX, highY, 5, TEXT, 12);
            } else {
                draw.addRectFilled(middleX - 4, highY - 4, middleX + 4, highY + 4, TEXT);
            }
            label(
                    draw,
                    x + PAD,
                    y + CARD - PAD - SMALL,
                    SMALL,
                    MUTED,
                    fit(smooth ? "bends through the key" : "straight lines meet", width - 2 * PAD));
        }
    }

    private static final class Shots implements GuideDiagram {
        private static final float NAMES = 60;
        private static final float END = 10;
        private static final float[] KEYS = {0, 2, 4.5f, 4.5f, 7, 10};

        @Override
        public float getHeight(float width) {
            return LABEL_LINE + 8 + 22 + 6 + 14 + 6 + LABEL_LINE * 2;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var left = x + NAMES + 6;
            var span = x + width - left - 6;
            var rulerY = y + LABEL_LINE + 4;
            for (var tick = 0; tick <= 10; tick++) {
                var tickX = at(left, span, tick);
                draw.addLine(tickX, rulerY, tickX, rulerY + (tick % 5 == 0 ? 6 : 3), FAINT, 1);
            }
            var laneTop = rulerY + 8;
            label(draw, x, laneTop + 4, SMALL, TEXT, "Camera");
            track(draw, left - 6, laneTop, left + span + 6, 22);
            var cut = at(left, span, 4.5f);
            draw.addLine(cut, laneTop - 2, cut, laneTop + 24, TEXT, 1);
            for (var index = 0; index < KEYS.length; index++) {
                var keyX = at(left, span, KEYS[index]);
                if (index == 2) {
                    keyX -= 6;
                } else if (index == 3) {
                    keyX += 6;
                }
                draw.addCircleFilled(keyX, laneTop + 11, 4.5f, MUTED, 12);
            }
            var spanTop = laneTop + 22 + 6;
            shot(draw, at(left, span, 0) - 6, cut - 2, spanTop, Motion.alpha(ACCENT, .45f), "Shot 1");
            shot(draw, cut + 2, at(left, span, END) + 6, spanTop, Motion.alpha(ACCENT, .8f), "Shot 2");
            var playhead = at(left, span, 6);
            draw.addLine(playhead, rulerY - 2, playhead, spanTop + 14, ACCENT, 1.5f);
            draw.addTriangleFilled(playhead - 4, rulerY - 4, playhead + 4, rulerY - 4, playhead, rulerY + 2, ACCENT);
            label(draw, playhead + 6, y, SMALL, ACCENT, "playhead");
            centered(draw, cut, y, SMALL, TEXT, "cut");
            label(draw, x, rulerY - 4, SMALL, FAINT, "0 to 10 s");
            var under = spanTop + 14 + 6;
            label(draw, x, under, SMALL, MUTED, fit("A cut: two keys at one moment, and the camera jumps.", width));
            label(
                    draw,
                    x,
                    under + LABEL_LINE,
                    SMALL,
                    MUTED,
                    fit("A press on the span of a shot chooses all its keys.", width));
        }

        private static float at(float left, float span, float seconds) {
            return left + (float) Math.floor(seconds / END * span);
        }

        private static void shot(ImDrawList draw, float x0, float x1, float top, int color, String name) {
            draw.addRectFilled(x0, top, x1, top + 14, color, 3f);
            if (GuideDiagrams.labelWidth(SMALL, name) <= x1 - x0 - 10) {
                label(draw, x0 + 5, top + 1, SMALL, TEXT, name);
            }
        }
    }

    private static final class Outputs implements GuideDiagram {
        private static final float BOX = 50;
        private static final float GAP = 10;
        private static final float ARROWS = 34;

        @Override
        public float getHeight(float width) {
            return 3 * BOX + 2 * GAP;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var sourceWidth = (float) Math.floor(Math.min(120, width * .3f));
            var height = getHeight(width);
            var sourceTop = y + (float) Math.floor((height - BOX - LABEL_LINE) / 2);
            StartDiagrams.titled(draw, x, sourceTop, sourceWidth, BOX + LABEL_LINE, "SCENE", true);
            label(
                    draw,
                    x + PAD,
                    sourceTop + PAD - 2 + LABEL_LINE + 2,
                    SMALL,
                    TEXT,
                    fit("in the tray,", sourceWidth - 2 * PAD));
            label(
                    draw,
                    x + PAD,
                    sourceTop + PAD - 2 + 2 * LABEL_LINE + 2,
                    SMALL,
                    MUTED,
                    fit("a cut scene", sourceWidth - 2 * PAD));
            var outLeft = x + sourceWidth + ARROWS;
            var outWidth = x + width - outLeft;
            String[][] outs = {
                {"MP4 VIDEO", "the film with its sound"},
                {"BLENDER FOLDER", "tiles, NPCs, camera, sound"},
                {"INTO A CLIENT", "over one of its cut scenes"}
            };
            for (var index = 0; index < outs.length; index++) {
                var top = y + index * (BOX + GAP);
                StartDiagrams.titled(draw, outLeft, top, outWidth, BOX, outs[index][0], false);
                label(
                        draw,
                        outLeft + PAD,
                        top + PAD - 2 + LABEL_LINE + 2,
                        LABEL,
                        index == 2 ? MUTED : TEXT,
                        fitLabel(outs[index][1], outWidth - 2 * PAD));
                GeodataDiagrams.arrow(
                        draw,
                        x + sourceWidth + 4,
                        sourceTop + (BOX + LABEL_LINE) / 2,
                        outLeft - 4,
                        top + BOX / 2,
                        index == 2 ? FAINT : MUTED);
            }
        }

        private static String fitLabel(String text, float room) {
            if (GuideDiagrams.labelWidth(LABEL, text) <= room) {
                return text;
            }
            var cut = text;
            while (cut.length() > 1 && GuideDiagrams.labelWidth(LABEL, cut + "…") > room) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut.strip() + "…";
        }
    }
}
