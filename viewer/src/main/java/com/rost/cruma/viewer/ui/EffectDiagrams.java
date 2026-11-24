package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;

import imgui.ImDrawList;
import java.util.Map;

final class EffectDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int BORDER = HudWidgets.BORDER;
    private static final int SMALL = 12;
    private static final int LABEL = 13;
    private static final float LABEL_LINE = 16;

    private EffectDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("effect-emitters", new Emitters());
        diagrams.put("effect-cast", new Cast());
    }

    private static final class Emitters implements GuideDiagram {
        private static final float CARD_WIDTH = 176;
        private static final float PADDING = 10;
        private static final float ROW = 22;
        private static final float BURST = 152;
        private static final float PARTICLES = BURST - LABEL_LINE - 4;
        private static final float GAP = 16;

        @Override
        public float getHeight(float width) {
            return isStacked(width) ? cardHeight() + GAP + BURST : Math.max(cardHeight(), BURST);
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var stacked = isStacked(width);
            var cardWidth = stacked ? width : CARD_WIDTH;
            var cardTop = stacked ? y : y + (float) Math.floor((getHeight(width) - cardHeight()) / 2);
            GeodataDiagrams.card(draw, x, cardTop, cardWidth, cardHeight());
            var inner = x + PADDING;
            label(draw, inner, cardTop + PADDING, SMALL, MUTED, "EFFECT CLASS");
            label(draw, inner, cardTop + PADDING + LABEL_LINE, LABEL, TEXT, "at_shock_blast_ta");
            var row = cardTop + PADDING + 2 * LABEL_LINE + 8;
            String[] names = {"Emitter 1 · sprites", "Emitter 2 · meshes", "Emitter 3 · a beam"};
            for (var i = 0; i < 3; i++) {
                var middle = row + ROW / 2;
                mark(draw, i, inner + 6, middle);
                label(draw, inner + 20, middle - SMALL / 2f - 1, SMALL, TEXT, names[i]);
                row += ROW;
            }
            var burstLeft = stacked ? x : x + CARD_WIDTH + GAP;
            var burstTop = stacked ? y + cardHeight() + GAP : y;
            burst(draw, burstLeft, burstTop, x + width - burstLeft);
        }

        private static boolean isStacked(float width) {
            return width < 380;
        }

        private static float cardHeight() {
            return PADDING + 2 * LABEL_LINE + 8 + 3 * ROW + PADDING - 4;
        }

        private static void mark(ImDrawList draw, int emitter, float x, float y) {
            switch (emitter) {
                case 0 -> draw.addCircleFilled(x, y, 4, ACCENT, 12);
                case 1 -> draw.addTriangleFilled(x, y - 5, x + 5, y + 4, x - 5, y + 4, TEXT);
                default -> draw.addLine(x - 6, y, x + 6, y, MUTED, 3);
            }
        }

        private static void burst(ImDrawList draw, float x, float y, float width) {
            var centerX = x + (float) Math.floor(width * .42f);
            var centerY = y + PARTICLES / 2;
            var reach = Math.min(PARTICLES / 2 - 6, width * .42f - 4);
            draw.addLine(centerX, centerY, x + width - 4, centerY, MUTED, 3);
            for (var ray = 0; ray < 10; ray++) {
                var angle = Math.PI * 2 * ray / 10 + .3;
                var dx = (float) Math.cos(angle);
                var dy = (float) Math.sin(angle);
                for (var step = 1; step <= 3; step++) {
                    var share = step / 3f;
                    draw.addCircleFilled(
                            centerX + dx * reach * share,
                            centerY + dy * reach * share,
                            4.5f - step,
                            Motion.alpha(ACCENT, 1.1f - share * .75f),
                            12);
                }
                if (ray % 2 == 1) {
                    var meshX = centerX + dx * reach * .55f;
                    var meshY = centerY + dy * reach * .55f;
                    draw.addTriangleFilled(
                            meshX - dy * 4,
                            meshY + dx * 4,
                            meshX + dx * 6,
                            meshY + dy * 6,
                            meshX + dy * 4,
                            meshY - dx * 4,
                            Motion.alpha(TEXT, .85f));
                }
            }
            draw.addCircleFilled(centerX, centerY, 3, TEXT, 12);
            label(draw, x, y + BURST - LABEL_LINE, SMALL, MUTED, "born in the middle");
            GuideDiagrams.rightAligned(draw, x + width, y + BURST - LABEL_LINE, SMALL, MUTED, "fade and die");
        }
    }

    private static final class Cast implements GuideDiagram {
        private static final float NAMES = 92;
        private static final float ROW = 34;
        private static final float TRACK = 10;
        private static final float HIT = 4f;
        private static final float SHOT = 3.4f;
        private static final float END = 5f;

        @Override
        public float getHeight(float width) {
            return LABEL_LINE + 4 + 3 * ROW + 4 + LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var left = x + NAMES;
            var span = x + width - left;
            var top = y + LABEL_LINE + 4;
            var shot = left + (float) Math.floor(SHOT / END * span);
            var hit = left + (float) Math.floor(HIT / END * span);
            String[][] rows = {{"Cast", "on the caster"}, {"Shot", "flies over"}, {"Hit", "on the target"}};
            float[][] bars = {{0, HIT}, {SHOT, HIT}, {HIT, END - .2f}};
            for (var i = 0; i < 3; i++) {
                var rowTop = top + i * ROW;
                label(draw, x, rowTop + 1, SMALL, TEXT, rows[i][0]);
                label(draw, x, rowTop + 1 + LABEL_LINE, SMALL, MUTED, rows[i][1]);
                var trackTop = rowTop + (ROW - TRACK) / 2;
                draw.addRectFilled(left, trackTop, left + span, trackTop + TRACK, Motion.alpha(BORDER, .4f), 2f);
                var from = left + (float) Math.floor(bars[i][0] / END * span);
                var to = left + (float) Math.floor(bars[i][1] / END * span);
                if (i == 1) {
                    GeodataDiagrams.arrow(draw, from, trackTop + TRACK / 2, to, trackTop + TRACK / 2, ACCENT);
                } else {
                    draw.addRectFilled(
                            from, trackTop, to, trackTop + TRACK, i == 0 ? Motion.alpha(ACCENT, .55f) : ACCENT, 2f);
                }
            }
            var bottom = top + 3 * ROW;
            draw.addLine(shot, top - 2, shot, bottom, FAINT, 1);
            draw.addLine(hit, top - 2, hit, bottom, TEXT, 1.5f);
            GuideDiagrams.rightAligned(draw, shot - 3, y, SMALL, MUTED, "shot");
            label(draw, hit + 3, y, SMALL, TEXT, "hit");
            label(draw, left, bottom + 4, SMALL, MUTED, "0 s");
            centered(draw, hit, bottom + 4, SMALL, TEXT, "4.0 s");
        }
    }
}
