package com.rost.cruma.viewer.ui;

import imgui.ImDrawList;
import imgui.ImGui;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class TrackView {
    static final float LANE = 22;

    static final float BAR_WIDTH = 12;

    private static final float DEAD_ZONE = 2;
    private static final float MOST_PER_PIXEL = .05f;
    private static final float LANE_GAP = 2;
    private static final float TRACK_GAP = 6;
    private static final float TICK = 4;
    private static final float TICK_ROOM = 48;
    private static final float[] TICK_STEPS = {.1f, .2f, .5f, 1, 2, 5, 10, 20, 30, 60};
    private static final float LABEL_PAD = 6;
    private static final float BAR_FILL = .18f;
    private static final float CHOSEN_FILL = .34f;
    private static final float RADIUS = 4;

    private final String id;
    private final Map<String, float[]> drawn = new HashMap<>();
    private final Hold hold = new Hold();

    public TrackView(String id) {
        this.id = id;
    }

    public record Bar(
            String id, float start, float seconds, String label, String tip, boolean selected, boolean unknown) {}

    public record Track(String label, List<Bar> bars) {}

    public enum Kind {
        PRESSED,
        DRAGGED,
        RELEASED
    }

    public record Gesture(Kind kind, String bar, float seconds) {}

    record LabelRoom(int side, float room) {}

    static float axisLength(List<Track> tracks, float length, float width) {
        var axis = Math.max(length, .1f);
        var room = Math.max(1f, width - BAR_WIDTH);
        for (var track : tracks) {
            for (var bar : track.bars()) {
                axis = Math.max(axis, bar.start() + Math.max(0f, bar.seconds()));
                axis = Math.max(axis, bar.start() * width / room);
            }
        }
        return axis;
    }

    static float place(float seconds, float axis, float width) {
        return seconds / axis * width;
    }

    static float barWidth(float seconds, float axis, float width) {
        return Math.max(BAR_WIDTH, place(seconds, axis, width));
    }

    static float dragSeconds(float pixels, float axis, float width, float start) {
        return Math.min(pixels * Math.min(axis / width, MOST_PER_PIXEL), axis - start);
    }

    public static float moved(float value, float seconds) {
        return Math.max(0f, Math.round((value + seconds) * 100f) / 100f);
    }

    static float tickStep(float axis, float width) {
        for (var step : TICK_STEPS) {
            if (place(step, axis, width) >= TICK_ROOM) {
                return step;
            }
        }
        return TICK_STEPS[TICK_STEPS.length - 1];
    }

    static LabelRoom labelRoom(float left, float right, float laneLeft, float laneRight, float text) {
        var inside = right - left - 2 * LABEL_PAD;
        var after = laneRight - right - 2 * LABEL_PAD;
        var before = left - laneLeft - 2 * LABEL_PAD;
        if (text <= inside) {
            return new LabelRoom(0, inside);
        }
        if (text <= after) {
            return new LabelRoom(1, after);
        }
        if (text <= before) {
            return new LabelRoom(-1, before);
        }
        if (inside >= after && inside >= before) {
            return new LabelRoom(0, inside);
        }
        return after >= before ? new LabelRoom(1, after) : new LabelRoom(-1, before);
    }

    public @Nullable Gesture draw(List<Track> tracks, float length, float time, float width) {
        var x = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        var axis = hold.getBar() == null ? axisLength(tracks, length, width) : hold.getAxis();
        var draw = ImGui.getWindowDrawList();
        var y = drawAxis(draw, x, top, axis, width);
        var line = x + Math.clamp(place(time, axis, width), 0, width);
        Gesture gesture = null;
        for (var track : tracks) {
            draw.addText(x, y, HudWidgets.MUTED, HudWidgets.truncateToWidth(track.label(), width));
            y += ImGui.getFontSize() + LANE_GAP;
            var first = y;
            for (var bar : track.bars()) {
                var done = lane(draw, bar, x, y, axis, width);
                gesture = done == null ? gesture : done;
                y += LANE + LANE_GAP;
            }
            draw.addLine(line, first, line, y - LANE_GAP, HudWidgets.TEXT, 1f);
            y += TRACK_GAP;
        }
        hold.endFrame(ImGui.isMouseDown(0));
        var bottom = tracks.isEmpty() ? y : y - TRACK_GAP - LANE_GAP;
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        return gesture;
    }

    private float drawAxis(ImDrawList draw, float x, float top, float axis, float width) {
        var base = top + ImGui.getFontSize() + TICK;
        draw.addLine(x, base, x + width, base, HudWidgets.BORDER, 1f);
        var step = tickStep(axis, width);
        for (var tick = 0; tick * step <= axis; tick++) {
            var seconds = tick * step;
            var at = x + place(seconds, axis, width);
            draw.addLine(at, base - TICK, at, base, HudWidgets.BORDER, 1f);
            var text = tickLabel(seconds, step);
            var textWidth = ImGui.calcTextSizeX(text);
            var left = tick == 0 ? at : at - textWidth * .5f;
            if (left + textWidth <= x + width) {
                draw.addText(left, top, GeodataWidgets.FAINT, text);
            }
        }
        return base + TICK;
    }

    private @Nullable Gesture lane(ImDrawList draw, Bar bar, float x, float y, float axis, float width) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton(id + "/" + bar.id(), width, LANE);
        var gesture =
                hold.follow(bar, axis, width, ImGui.isItemActivated(), ImGui.isItemDeactivated(), ImGui.getMousePosX());
        var hovered = ImGui.isItemHovered() || bar.id().equals(hold.getBar());
        var left = x + place(bar.start(), axis, width);
        var right = Math.min(x + width, left + barWidth(Math.max(0f, bar.seconds()), axis, width));
        drawn.put(bar.id(), new float[] {x, y, width, left});
        draw.addRectFilled(x, y, x + width, y + LANE, hovered ? GeodataWidgets.HOVER : GeodataWidgets.CARD, RADIUS);
        if (bar.unknown()) {
            draw.addRect(left, y + 1, right, y + LANE - 1, GeodataWidgets.FAINT, RADIUS, 0, 1f);
        } else {
            draw.addRectFilled(
                    left,
                    y + 1,
                    right,
                    y + LANE - 1,
                    Motion.alpha(HudWidgets.ACCENT, bar.selected() ? CHOSEN_FILL : BAR_FILL),
                    RADIUS);
            draw.addRectFilled(left, y + 1, left + 2, y + LANE - 1, HudWidgets.ACCENT, 1f);
        }
        if (bar.selected()) {
            draw.addRect(left, y + 1, right, y + LANE - 1, HudWidgets.ACCENT, RADIUS, 0, 1f);
        }
        drawLabel(draw, bar, left, right, x, x + width, y);
        GeodataWidgets.tip(bar.tip());
        return gesture;
    }

    private void drawLabel(
            ImDrawList draw, Bar bar, float left, float right, float laneLeft, float laneRight, float y) {
        var room = labelRoom(left, right, laneLeft, laneRight, ImGui.calcTextSizeX(bar.label()));
        var text = HudWidgets.truncateToWidth(bar.label(), room.room());
        var textWidth = ImGui.calcTextSizeX(text);
        var at = switch (room.side()) {
            case 0 -> left + LABEL_PAD;
            case 1 -> right + LABEL_PAD;
            default -> left - LABEL_PAD - textWidth;
        };
        var color = bar.unknown() ? GeodataWidgets.FAINT : bar.selected() ? HudWidgets.TEXT : HudWidgets.MUTED;
        draw.addText(at, y + (LANE - ImGui.getFontSize()) * .5f - 1, color, text);
    }

    static final class Hold {
        private @Nullable String bar;
        private float x;
        private float start;
        private float axis;
        private boolean moved;

        @Nullable
        String getBar() {
            return bar;
        }

        float getAxis() {
            return axis;
        }

        void endFrame(boolean mouseDown) {
            if (bar != null && !mouseDown) {
                bar = null;
            }
        }

        @Nullable
        Gesture follow(Bar row, float rowAxis, float width, boolean activated, boolean deactivated, float mouseX) {
            if (activated) {
                bar = row.id();
                x = mouseX;
                start = row.start();
                axis = rowAxis;
                moved = false;
                return new Gesture(Kind.PRESSED, row.id(), 0f);
            }
            if (!row.id().equals(bar)) {
                return null;
            }
            if (deactivated) {
                bar = null;
                return new Gesture(Kind.RELEASED, row.id(), 0f);
            }
            var pixels = mouseX - x;
            if (!moved) {
                if (Math.abs(pixels) < DEAD_ZONE) {
                    return null;
                }
                moved = true;
                x += Math.copySign(DEAD_ZONE, pixels);
                pixels = mouseX - x;
            }
            return new Gesture(Kind.DRAGGED, row.id(), dragSeconds(pixels, rowAxis, width, start));
        }
    }

    private static String tickLabel(float seconds, float step) {
        if (seconds == 0f) {
            return "0";
        }
        return step >= 1f
                ? String.format(Locale.ROOT, "%d s", Math.round(seconds))
                : String.format(Locale.ROOT, "%.1f s", seconds);
    }
}
