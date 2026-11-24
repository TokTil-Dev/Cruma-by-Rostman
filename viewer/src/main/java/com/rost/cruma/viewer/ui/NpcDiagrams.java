package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GuideDiagrams.centered;
import static com.rost.cruma.viewer.ui.GuideDiagrams.label;
import static com.rost.cruma.viewer.ui.GuideDiagrams.labelWidth;

import imgui.ImDrawList;
import java.util.Map;

final class NpcDiagrams {
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int FAINT = GeodataWidgets.FAINT;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int BORDER = HudWidgets.BORDER;
    private static final int SMALL = 12;
    private static final int LABEL = 13;
    private static final float LABEL_LINE = 16;

    private NpcDiagrams() {}

    static void addTo(Map<String, GuideDiagram> diagrams) {
        diagrams.put("npc-server-client", new ServerClient());
        diagrams.put("npc-drop-group", new DropGroup());
    }

    private static final class ServerClient implements GuideDiagram {
        private static final float MIDDLE = 40;
        private static final float PADDING = 10;
        private static final String[] SERVER = {
            "level and HP", "attack, defence", "skills and AI", "what it drops", "collision size"
        };
        private static final String[] CLIENT = {"mesh", "textures", "pawn class", "draw scale"};

        @Override
        public float getHeight(float width) {
            return cardHeight() + GuideDiagrams.LINE_GAP + 2 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            var panel = (float) Math.floor((width - MIDDLE) / 2);
            var right = x + panel + MIDDLE;
            var height = cardHeight();
            var idY = side(draw, x, y, panel, height, "SERVER", "npcdata.txt", SERVER);
            side(draw, right, y, panel, height, "CLIENT", "npcgrp.dat", CLIENT);
            draw.addLine(x + panel - PADDING + 4, idY, right + PADDING - 4, idY, ACCENT, 2);
            centered(draw, x + panel + MIDDLE / 2, idY - LABEL_LINE - 6, SMALL, ACCENT, "same");
            var text = y + height + GuideDiagrams.LINE_GAP;
            centered(draw, x + panel / 2, text, SMALL, TEXT, "How strong it is");
            centered(draw, x + panel / 2, text + LABEL_LINE, SMALL, MUTED, "the server counts");
            centered(draw, right + panel / 2, text, SMALL, TEXT, "How it looks");
            centered(draw, right + panel / 2, text + LABEL_LINE, SMALL, MUTED, "the client draws");
        }

        private static float cardHeight() {
            return PADDING + LABEL_LINE + LABEL_LINE + 8 + 22 + 8 + SERVER.length * LABEL_LINE + PADDING;
        }

        private static float side(
                ImDrawList draw,
                float x,
                float y,
                float width,
                float height,
                String place,
                String file,
                String[] rows) {
            GeodataDiagrams.card(draw, x, y, width, height);
            var inner = x + PADDING;
            var top = y + PADDING;
            label(draw, inner, top, SMALL, MUTED, place);
            label(draw, inner, top + LABEL_LINE, LABEL, TEXT, file);
            var idTop = top + 2 * LABEL_LINE + 8;
            draw.addRectFilled(inner - 4, idTop, x + width - PADDING + 4, idTop + 22, Motion.alpha(ACCENT, .16f), 4f);
            label(draw, inner, idTop + 3, SMALL, ACCENT, "id 20003");
            var row = idTop + 22 + 8;
            for (var text : rows) {
                draw.addCircleFilled(inner + 2, row + 8, 2, FAINT, 8);
                label(draw, inner + 10, row, SMALL, MUTED, text);
                row += LABEL_LINE;
            }
            return idTop + 11;
        }
    }

    private static final class DropGroup implements GuideDiagram {
        private static final float BAR = 22;

        @Override
        public float getHeight(float width) {
            return LABEL_LINE + 6 + BAR + 6 + LABEL_LINE + 6 + 10 + LABEL_LINE + 14 + 2 * LABEL_LINE;
        }

        @Override
        public void draw(ImDrawList draw, float x, float y, float width) {
            label(draw, x, y, SMALL, TEXT, "Group 1 · chance 70%");
            GuideDiagrams.rightAligned(draw, x + width, y, SMALL, MUTED, "its items share 100%");
            var barTop = y + LABEL_LINE + 6;
            var split = x + (float) Math.floor(width * .8f);
            draw.addRectFilled(x, barTop, split - 1, barTop + BAR, Motion.alpha(ACCENT, .45f), 3f);
            draw.addRectFilled(split + 1, barTop, x + width, barTop + BAR, Motion.alpha(MUTED, .45f), 3f);
            label(draw, x + 8, barTop + (BAR - SMALL) / 2 - 1, SMALL, TEXT, "Adena 80%");
            var thread = "Thread 20%";
            var threadWidth = labelWidth(SMALL, thread);
            if (threadWidth + 12 <= x + width - split) {
                label(draw, split + 7, barTop + (BAR - SMALL) / 2 - 1, SMALL, TEXT, thread);
            } else {
                GuideDiagrams.rightAligned(draw, x + width, barTop + BAR + 4, SMALL, TEXT, thread);
            }
            var lineY = barTop + BAR + 6 + LABEL_LINE + 6;
            draw.addRectFilled(x, lineY, x + width, lineY + 4, Motion.alpha(BORDER, .8f), 2f);
            draw.addRectFilled(x, lineY, x + (float) Math.floor(width * .7f), lineY + 4, ACCENT, 2f);
            label(draw, x, lineY + 10, SMALL, MUTED, "The group drops in 70% of kills");
            var result = lineY + 10 + LABEL_LINE + 14;
            label(draw, x, result, SMALL, TEXT, "Adena: 70% × 80% = 56% a kill");
            label(draw, x, result + LABEL_LINE, SMALL, TEXT, "Thread: 70% × 20% = 14% a kill");
        }
    }
}
