package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.StartDiagrams.fit;

import imgui.ImDrawList;
import java.util.Map;

final class AgentDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int BORDER = HudWidgets.BORDER;
    private static final int WARNING = GeodataWidgets.WARNING;
    private static final int SMALL = 12;
    private static final int LABEL = 13;
    private static final float LABEL_LINE = 16;
    private static final float PAD = 10;

    private AgentDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("agent-connection", new Connection());
        diagrams.put("agent-access", new Levels());
    }

    private static final class Connection implements GuideDiagram {
        private static final float BOX = 46;
        private static final float STEP = 30;

        @Override
        public float getHeight(float width) {
            return 4 * BOX + 3 * STEP;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            String[][] boxes = {
                {"THE AGENT'S PROGRAM", "You ask it in words"},
                {"CRUMA MCP", "Started by that program, with no window"},
                {"A VIEWER", "Your window, or the agent's own"},
                {"YOUR FILES", "Exports, and with Write the client"}
            };
            String[] between = {"calls of tools", "Access is checked on each call", "saves and exports"};
            var boxWidth = (float) Math.floor(width);
            for (var index = 0; index < boxes.length; index++) {
                var top = y + index * (BOX + STEP);
                StartDiagrams.titled(draw, x, top, boxWidth, BOX, boxes[index][0], index == 1);
                label(
                        draw,
                        x + PAD,
                        top + PAD - 2 + LABEL_LINE + 2,
                        LABEL,
                        TEXT,
                        fitLabel(boxes[index][1], boxWidth - 2 * PAD));
                if (index < between.length) {
                    var arrowX = x + 28;
                    GeodataDiagrams.arrow(
                            draw, arrowX, top + BOX + 3, arrowX, top + BOX + STEP - 3, index == 1 ? ACCENT : MUTED);
                    label(
                            draw,
                            arrowX + 12,
                            top + BOX + (STEP - SMALL) / 2 - 1,
                            SMALL,
                            index == 1 ? ACCENT : MUTED,
                            fit(between[index], width - 40));
                }
            }
        }
    }

    private static final class Levels implements GuideDiagram {
        private static final float NAMES = 52;
        private static final float ROW = 30;
        private static final float BAR = 20;
        private static final float GAP = 3;

        @Override
        public float getHeight(float width) {
            return 3 * ROW + 4 + 2 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            String[] names = {"See", "Edit", "Write"};
            int[] colors = {Motion.alpha(MUTED, .45f), Motion.alpha(ACCENT, .6f), Motion.alpha(WARNING, .75f)};
            var left = x + NAMES;
            var step = (float) Math.floor((x + width - left - 2 * GAP) / 3);
            for (var row = 0; row < 3; row++) {
                var top = y + row * ROW;
                label(draw, x, top + (BAR - SMALL) / 2 - 1, SMALL, TEXT, names[row]);
                for (var part = 0; part < 3; part++) {
                    var x0 = left + part * (step + GAP);
                    if (part <= row) {
                        draw.addRectFilled(x0, top, x0 + step, top + BAR, colors[part], 3f);
                    } else {
                        draw.addRect(x0, top, x0 + step, top + BAR, Motion.alpha(BORDER, .8f), 3f, 0, 1);
                    }
                }
            }
            String[][] adds = {
                {"look, read,", "export"}, {"change in", "memory, Undo"}, {"save into the", "client, server"}
            };
            var under = y + 3 * ROW + 4;
            for (var part = 0; part < 3; part++) {
                var x0 = left + part * (step + GAP);
                for (var line = 0; line < 2; line++) {
                    label(
                            draw,
                            x0 + 2,
                            under + line * LABEL_LINE,
                            SMALL,
                            part == 2 ? TEXT : MUTED,
                            fit(adds[part][line], step - 4));
                }
            }
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
