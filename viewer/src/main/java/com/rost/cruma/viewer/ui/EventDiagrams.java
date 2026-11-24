package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.GuideDiagrams.rightAligned;

import imgui.ImDrawList;
import java.util.Locale;
import java.util.Map;

final class EventDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int BORDER = HudWidgets.BORDER;
    private static final int CARD = GeodataWidgets.CARD;
    private static final int SMALL = 12;
    private static final float LABEL_LINE = 16;

    private EventDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("event-switch", new Switch());
        diagrams.put("event-day", new Day());
    }

    private static final class Switch implements GuideDiagram {
        private static final float CARD_HEIGHT = 112;
        private static final float MIDDLE = 48;

        @Override
        public float getHeight(float width) {
            return CARD_HEIGHT + GuideDiagrams.LINE_GAP + 2 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var panel = (float) Math.floor((width - MIDDLE) / 2);
            var right = x + panel + MIDDLE;
            place(draw, x, y, panel, false);
            place(draw, right, y, panel, true);
            var middle = x + panel + MIDDLE / 2;
            var switchY = y + CARD_HEIGHT / 2;
            draw.addRectFilled(middle - 14, switchY - 8, middle + 14, switchY + 8, Motion.alpha(ACCENT, .35f), 8f);
            draw.addCircleFilled(middle + 6, switchY, 6, ACCENT, 16);
            centered(draw, middle, switchY - 8 - LABEL_LINE - 2, SMALL, ACCENT, "server");
            var text = y + CARD_HEIGHT + GuideDiagrams.LINE_GAP;
            centered(draw, x + panel / 2, text, SMALL, TEXT, "Event off");
            centered(draw, x + panel / 2, text + LABEL_LINE, SMALL, MUTED, "as the game starts");
            centered(draw, right + panel / 2, text, SMALL, TEXT, "Event on");
            centered(draw, right + panel / 2, text + LABEL_LINE, SMALL, MUTED, "the server sent it");
        }

        private static void place(ImDrawList draw, float x, float y, float width, boolean on) {
            GeodataDiagrams.card(draw, x, y, width, CARD_HEIGHT);
            var ground = y + CARD_HEIGHT - 18;
            draw.addLine(x + 10, ground, x + width - 10, ground, MUTED, 1.5f);
            var middle = x + (float) Math.floor(width / 2);
            var towerLeft = middle - 14;
            var towerRight = middle + 14;
            var towerTop = ground - 44;
            draw.addRectFilled(towerLeft, towerTop, towerRight, ground, CARD, 0f);
            draw.addRect(towerLeft, towerTop, towerRight, ground, MUTED, 0f, 0, 1.5f);
            draw.addTriangle(towerLeft - 4, towerTop, middle, towerTop - 16, towerRight + 4, towerTop, MUTED, 1.5f);
            var effectY = towerTop - 30;
            if (on) {
                sparkle(draw, middle, effectY, 13);
                sparkle(draw, middle - 24, effectY + 8, 7);
                sparkle(draw, middle + 24, effectY + 6, 7);
                draw.addCircleFilled(middle, effectY, 4, TEXT, 16);
            } else {
                draw.addCircle(middle, effectY, 5, ACCENT, 16, 1.5f);
            }
        }

        private static void sparkle(ImDrawList draw, float x, float y, float size) {
            draw.addTriangleFilled(x - size * .3f, y, x, y - size, x + size * .3f, y, ACCENT);
            draw.addTriangleFilled(x - size * .3f, y, x, y + size, x + size * .3f, y, ACCENT);
            draw.addTriangleFilled(x, y - size * .3f, x - size, y, x, y + size * .3f, ACCENT);
            draw.addTriangleFilled(x, y - size * .3f, x + size, y, x, y + size * .3f, ACCENT);
        }
    }

    private static final class Day implements GuideDiagram {
        private static final float NAMES = 68;
        private static final float ROW = 24;
        private static final float TRACK = 10;
        private static final float[][] WINDOWS = {{5, 6}, {13, 15}};
        private static final float NOW = 14;

        @Override
        public float getHeight(float width) {
            return LABEL_LINE + 6 + 3 * ROW + 4 + LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var left = x + NAMES;
            var span = x + width - left;
            var top = y + LABEL_LINE + 6;
            String[] names = {"By clock", "Always", "Off"};
            for (var row = 0; row < 3; row++) {
                var rowTop = top + row * ROW;
                var trackTop = rowTop + (ROW - TRACK) / 2;
                label(draw, x, rowTop + (ROW - SMALL) / 2 - 1, SMALL, TEXT, names[row]);
                draw.addRectFilled(left, trackTop, left + span, trackTop + TRACK, Motion.alpha(BORDER, .5f), 2f);
                switch (row) {
                    case 0 -> {
                        for (var window : WINDOWS) {
                            var from = left + (float) Math.floor(window[0] / 24 * span);
                            var to = left + (float) Math.ceil(window[1] / 24 * span);
                            draw.addRectFilled(from, trackTop, Math.max(to, from + 3), trackTop + TRACK, ACCENT, 2f);
                        }
                    }
                    case 1 -> draw.addRectFilled(left, trackTop, left + span, trackTop + TRACK, ACCENT, 2f);
                    default ->
                        rightAligned(draw, left + span - 4, rowTop + (ROW - SMALL) / 2 - 1, SMALL, FAINT, "never");
                }
            }
            var axis = top + 3 * ROW + 4;
            for (var hour = 0; hour <= 24; hour += 6) {
                var tick = left + (float) Math.floor(hour / 24f * span);
                draw.addLine(tick, axis - 3, tick, axis, FAINT, 1);
                var text = String.format(Locale.ROOT, "%02d:00", hour);
                if (hour == 0) {
                    label(draw, tick, axis + 2, SMALL, MUTED, text);
                } else if (hour == 24) {
                    rightAligned(draw, tick, axis + 2, SMALL, MUTED, text);
                } else {
                    centered(draw, tick, axis + 2, SMALL, MUTED, text);
                }
            }
            var now = left + (float) Math.floor(NOW / 24 * span);
            draw.addLine(now, top - 2, now, axis - 4, TEXT, 1.5f);
            centered(draw, now, y, SMALL, TEXT, "Time of day");
        }
    }
}
