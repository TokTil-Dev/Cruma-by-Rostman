package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiMouseCursor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class KeyTimeline {
    static final float LANE = 22;

    static final float THIN_LANE = 16;

    static final float GUTTER = 64;

    private static final float KEY_RADIUS = 6;
    static final float CUT_NUDGE = KEY_RADIUS + 1;

    private static final float DEAD_ZONE = 2;
    private static final float SNAP_PIXELS = 6;
    private static final float KEY_REACH = 8;
    private static final float TICK = 4;
    private static final float TICK_ROOM = 56;
    private static final double[] TICK_STEPS = {.05, .1, .2, .5, 1, 2, 5, 10, 20, 30, 60, 120, 300, 600};
    private static final float LANE_GAP = 2;
    private static final float CUT_BAR_REACH = 3;
    private static final float CHOSEN_RADIUS = 7;
    private static final float THIN_KEY_RADIUS = 4;
    private static final float THIN_CHOSEN_RADIUS = 5;
    private static final float QUIET_RADIUS = 2;
    private static final float SPAN_INSET = 3;
    private static final float SPAN_REACH = 5;
    private static final float SPAN_GLYPH = 12;
    private static final float LABEL_INSET = CUT_NUDGE + KEY_RADIUS + 5;
    private static final float CHEVRON = 12;
    private static final float CHILD_INDENT = 14;
    private static final float SCROLL_BAR = 6;
    private static final float SCROLL_REACH = 10;
    private static final float LEAST_THUMB = 16;
    private static final float WHEEL_LANES = 2;
    private static final float PROGRESS_HEIGHT = 2;
    private static final float HEAD = 5;
    private static final float RADIUS = 4;
    private static final float FRAME_TICK_ROOM = 6;
    private static final double ZOOM_PER_NOTCH = 1.2;
    private static final float PAN_PER_NOTCH = 40;
    private static final double FINEST_FRAME_SHARE = 1.0 / 32;
    private static final double WIDEST_SHARE = 1.25;
    private static final float END_MARGIN = 12;
    private static final float GUESS_SOLID = .6f;

    private final String id;
    private final Scale scale = new Scale();
    private final Map<String, float[]> drawn = new HashMap<>();
    private final Map<String, float[]> lanesDrawn = new HashMap<>();
    private final Map<String, float[]> spansDrawn = new HashMap<>();
    private final float[] ruler = new float[3];
    private float[] laneTops = {};
    private float[] laneHeights = {};
    private float gutter = GUTTER;
    private float scroll;
    private float mostScroll;
    private @Nullable String revealing;
    private float lanesHeight;
    private double progress = -1;
    private Holding holding = Holding.NOTHING;
    private float pressX;
    private float pressY;
    private double grabbed;
    private double spanStays;
    private String spanId = "";
    private boolean moved;
    private boolean additive;
    private @Nullable String clicked;
    private boolean clickedSpan;
    private double[] targets = {};

    public KeyTimeline(String id) {
        this.id = id;
    }

    public record Mark(
            String id,
            double seconds,
            boolean selected,
            boolean movable,
            String tip,
            boolean quiet,
            boolean warned,
            float nudge) {
        public Mark(String id, double seconds, boolean selected, boolean movable, String tip) {
            this(id, seconds, selected, movable, tip, false, false, 0);
        }

        public static Mark quiet(String id, double seconds) {
            return new Mark(id, seconds, false, false, "", true, false, 0);
        }

        public Mark warning() {
            return new Mark(id, seconds, selected, movable, tip, quiet, true, nudge);
        }

        public Mark nudged(float pixels) {
            return new Mark(id, seconds, selected, movable, tip, quiet, warned, pixels);
        }
    }

    public record Span(
            double start,
            double end,
            int color,
            String id,
            String tip,
            @Nullable Glyph glyph,
            boolean chosen,
            boolean guess,
            int band,
            int bands,
            boolean moves,
            String label) {
        public Span(
                double start,
                double end,
                int color,
                String id,
                String tip,
                @Nullable Glyph glyph,
                boolean chosen,
                boolean guess,
                int band,
                int bands) {
            this(start, end, color, id, tip, glyph, chosen, guess, band, bands, false, "");
        }

        public Span(double start, double end, int color) {
            this(start, end, color, "", "", null, false, false);
        }

        public Span(
                double start,
                double end,
                int color,
                String id,
                String tip,
                @Nullable Glyph glyph,
                boolean chosen,
                boolean guess) {
            this(start, end, color, id, tip, glyph, chosen, guess, 0, 1);
        }

        public Span(double start, double end, int color, String id, String tip, @Nullable Glyph glyph, boolean chosen) {
            this(start, end, color, id, tip, glyph, chosen, false);
        }

        public Span(double start, double end, int color, String tip) {
            this(start, end, color, "", tip, null, false, false);
        }

        public static Span guess(double start, double end, int color, String tip) {
            return new Span(start, end, color, "", tip, null, false, true);
        }

        public Span inBand(int place, int count) {
            return new Span(start, end, color, id, tip, glyph, chosen, guess, place, count, moves, label);
        }

        public Span clip(String text) {
            return new Span(start, end, color, id, tip, glyph, chosen, guess, band, bands, true, text);
        }
    }

    public enum Fold {
        NONE,
        CLOSED,
        OPEN
    }

    public record Lane(
            String id,
            String label,
            List<Mark> marks,
            List<Span> spans,
            Fold fold,
            boolean thin,
            boolean child,
            boolean paints) {
        public Lane(String label, List<Mark> marks) {
            this(label, label, marks, List.of(), Fold.NONE, false, false);
        }

        public Lane(
                String id, String label, List<Mark> marks, List<Span> spans, Fold fold, boolean thin, boolean child) {
            this(id, label, marks, spans, fold, thin, child, false);
        }
    }

    public enum Kind {
        SEEK,
        PICKED,
        OPENED,
        DRAGGED,
        RELEASED,
        BOXED,
        CLEARED,
        FOLDED,
        SPANNED,
        SPAN_PICKED
    }

    public record Gesture(Kind kind, double seconds, List<String> marks, boolean add, double from) {
        public Gesture(Kind kind, double seconds, List<String> marks, boolean add) {
            this(kind, seconds, marks, add, 0);
        }
    }

    private enum Holding {
        NOTHING,
        RULER,
        KEY,
        BOX,
        VIEW,
        SCROLL,
        SPAN_END,
        SPAN_BODY
    }

    static final class Scale {
        private double start;
        private double perPixel = 1;
        private boolean fitted = true;

        double toSeconds(double pixel) {
            return start + pixel * perPixel;
        }

        float toPixel(double seconds) {
            return (float) ((seconds - start) / perPixel);
        }

        double getPerPixel() {
            return perPixel;
        }

        void fit(double length, float width) {
            perPixel = Math.max(length, 1e-3) / Math.max(1, width - 2 * END_MARGIN);
            start = -END_MARGIN * perPixel;
            fitted = true;
        }

        void follow(double length, float width) {
            if (fitted) {
                fit(length, width);
            }
        }

        void zoomAt(float pixel, double factor, double length, double frame, float width) {
            var widest = Math.max(length, 1e-3) * WIDEST_SHARE / Math.max(1, width);
            var finest = Math.min(frame * FINEST_FRAME_SHARE, widest);
            var under = toSeconds(pixel);
            perPixel = Math.clamp(perPixel * factor, finest, widest);
            if (perPixel >= widest) {
                fit(length, width);
                return;
            }
            fitted = false;
            start = under - pixel * perPixel;
            keepInView(length, width);
        }

        void pan(float pixels, double length, float width) {
            start += pixels * perPixel;
            fitted = false;
            keepInView(length, width);
        }

        private void keepInView(double length, float width) {
            var margin = END_MARGIN * perPixel;
            var shown = width * perPixel;
            if (shown >= length + 2 * margin) {
                start = -margin;
            } else {
                start = Math.clamp(start, -margin, length + margin - shown);
            }
        }
    }

    static double snap(double seconds, double frame, double[] targets, double reach) {
        var nearest = reach;
        var found = false;
        var best = seconds;
        for (var target : targets) {
            var distance = Math.abs(target - seconds);
            if (distance <= nearest) {
                nearest = distance;
                best = target;
                found = true;
            }
        }
        if (found) {
            return best;
        }
        return frame <= 0 ? seconds : Math.round(seconds / frame) * frame;
    }

    static double tickStep(double perPixel) {
        for (var step : TICK_STEPS) {
            if (step / perPixel >= TICK_ROOM) {
                return step;
            }
        }
        return TICK_STEPS[TICK_STEPS.length - 1];
    }

    static String tickLabel(double seconds, double step) {
        if (Math.abs(seconds) < 1e-9) {
            return "0";
        }
        if (step >= 1) {
            return String.format(Locale.ROOT, "%d s", Math.round(seconds));
        }
        return String.format(Locale.ROOT, step >= .1 ? "%.1f s" : "%.2f s", seconds);
    }

    static @Nullable Mark markAt(List<Mark> marks, float pixel, Scale scale, float reach) {
        Mark found = null;
        var nearest = reach;
        for (var mark : marks) {
            var distance = Math.abs(scale.toPixel(mark.seconds()) + mark.nudge() - pixel);
            if (!mark.quiet() && distance <= nearest) {
                nearest = distance;
                found = mark;
            }
        }
        return found;
    }

    static @Nullable Span spanAt(List<Span> spans, float pixel, Scale scale, float reach) {
        Span inside = null;
        Span near = null;
        var nearest = reach;
        for (var span : spans) {
            if (span.id().isEmpty() || span.moves()) {
                continue;
            }
            var from = scale.toPixel(span.start());
            var to = scale.toPixel(span.end());
            var distance = Math.min(Math.abs(pixel - from), Math.abs(pixel - to));
            if (distance <= nearest) {
                nearest = distance;
                near = span;
            }
            if (pixel > from && pixel < to) {
                inside = span;
            }
        }
        return near != null ? near : inside;
    }

    static @Nullable Span clipAt(List<Span> spans, float pixel, Scale scale) {
        Span found = null;
        for (var span : spans) {
            if (span.moves()
                    && !span.id().isEmpty()
                    && pixel >= scale.toPixel(span.start())
                    && pixel <= scale.toPixel(span.end())) {
                found = span;
            }
        }
        return found;
    }

    static boolean doesLabelFit(float from, float to, float text) {
        return text > 0 && to - from - 2 * LABEL_INSET >= text;
    }

    static boolean isLabelClear(float start, float end, float[] marks) {
        for (var mark : marks) {
            if (mark + KEY_RADIUS > start && mark - KEY_RADIUS < end) {
                return false;
            }
        }
        return true;
    }

    static boolean onSpanEnd(Span span, float pixel, Scale scale, float reach) {
        return Math.abs(pixel - scale.toPixel(span.start())) <= reach
                || Math.abs(pixel - scale.toPixel(span.end())) <= reach;
    }

    static List<String> marksBetween(List<Mark> marks, float from, float to, Scale scale) {
        var low = Math.min(from, to);
        var high = Math.max(from, to);
        var inside = new ArrayList<String>();
        for (var mark : marks) {
            var pixel = scale.toPixel(mark.seconds());
            if (!mark.quiet() && pixel >= low && pixel <= high) {
                inside.add(mark.id());
            }
        }
        return inside;
    }

    static double[] snapTargets(List<Lane> lanes, double playhead, double length) {
        return snapTargets(lanes, playhead, length, -1, null);
    }

    static double[] snapTargets(List<Lane> lanes, double playhead, double length, int clipLane, @Nullable Span clip) {
        var list = new ArrayList<Double>();
        list.add(0.0);
        list.add(length);
        list.add(playhead);
        for (var index = 0; index < lanes.size(); index++) {
            for (var mark : lanes.get(index).marks()) {
                var own = clip != null
                        && index == clipLane
                        && mark.seconds() >= clip.start()
                        && mark.seconds() <= clip.end();
                if (!mark.selected() && !mark.quiet() && !own) {
                    list.add(mark.seconds());
                }
            }
        }
        var targets = new double[list.size()];
        for (var i = 0; i < targets.length; i++) {
            targets[i] = list.get(i);
        }
        return targets;
    }

    static float height(int lanes) {
        return ImGui.getFontSize() + 2 * TICK + lanes * (LANE + LANE_GAP);
    }

    static float revealed(float top, float height, float room, float scroll, float most) {
        return top >= scroll && top + height <= scroll + room ? scroll : Math.clamp(top, 0, most);
    }

    public void setProgress(double done) {
        progress = done;
    }

    public void reveal(String laneId) {
        revealing = laneId;
    }

    public void gutter(float width) {
        gutter = Math.max(GUTTER, width);
    }

    public @Nullable Gesture draw(
            List<Lane> lanes, double length, double playhead, double frame, float width, float height) {
        var x = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        var left = x + gutter;
        var inner = Math.max(1, width - gutter);
        var rulerBottom = top + ImGui.getFontSize() + 2 * TICK;
        if (holding == Holding.NOTHING) {
            scale.follow(length, inner);
        }
        layOut(lanes, rulerBottom, top + height);
        ruler[0] = left;
        ruler[1] = top;
        ruler[2] = inner;
        ImGui.invisibleButton(id, width, height, ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonMiddle);
        var bottom = top + height;
        var gesture = pointer(lanes, x, left, rulerBottom, bottom, length, playhead, frame, inner);
        layOut(lanes, rulerBottom, bottom);
        var draw = ImGui.getWindowDrawList();
        draw.pushClipRect(x, rulerBottom, left + inner, bottom, true);
        drawLanes(draw, lanes, x, left, inner);
        draw.popClipRect();
        draw.pushClipRect(left, top, left + inner, bottom, true);
        drawRuler(draw, left, top, rulerBottom, inner, frame);
        drawProgress(draw, left, rulerBottom, inner);
        draw.pushClipRect(left, rulerBottom, left + inner, bottom, true);
        drawEnd(draw, left, rulerBottom, length, inner, bottom);
        drawSpans(draw, lanes, left, inner);
        drawKeys(draw, lanes, left);
        drawBox(draw, rulerBottom, bottom);
        draw.popClipRect();
        drawPlayhead(draw, left, top, rulerBottom, bottom, playhead);
        draw.popClipRect();
        drawScrollBar(draw, left + inner, rulerBottom, bottom);
        drawTip(lanes, left);
        ImGui.setCursorScreenPos(x, top + height);
        ImGui.dummy(width, 0);
        return gesture;
    }

    private void layOut(List<Lane> lanes, float rulerBottom, float bottom) {
        if (laneTops.length != lanes.size()) {
            laneTops = new float[lanes.size()];
            laneHeights = new float[lanes.size()];
        }
        var room = bottom - rulerBottom;
        var natural = 0f;
        for (var lane : lanes) {
            natural += (lane.thin() ? THIN_LANE : LANE) + LANE_GAP;
        }
        var stretch = natural <= 0 ? 1 : Math.max(1, room / natural);
        lanesHeight = natural * stretch;
        mostScroll = natural > room ? natural - room : 0;
        scroll = Math.clamp(scroll, 0, mostScroll);
        reveal(lanes, room);
        var y = rulerBottom - scroll;
        for (var index = 0; index < laneTops.length; index++) {
            var height = (lanes.get(index).thin() ? THIN_LANE : LANE) * stretch + (stretch - 1) * LANE_GAP;
            laneTops[index] = y;
            laneHeights[index] = height;
            y += height + LANE_GAP;
        }
    }

    private void reveal(List<Lane> lanes, float room) {
        var wanted = revealing;
        revealing = null;
        var top = 0f;
        for (var lane : lanes) {
            var height = lane.thin() ? THIN_LANE : LANE;
            if (lane.id().equals(wanted)) {
                scroll = revealed(top, height, room, scroll, mostScroll);
                return;
            }
            top += height + LANE_GAP;
        }
    }

    private @Nullable Gesture pointer(
            List<Lane> lanes,
            float x,
            float left,
            float rulerBottom,
            float bottom,
            double length,
            double playhead,
            double frame,
            float inner) {
        var io = ImGui.getIO();
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var free = io.getKeyCtrl() || io.getKeySuper();
        if (ImGui.isItemHovered() && holding == Holding.NOTHING) {
            var wheel = io.getMouseWheel();
            var across = io.getMouseWheelH();
            if (free && wheel != 0) {
                scale.zoomAt(mouseX - left, Math.pow(ZOOM_PER_NOTCH, -wheel), length, frame, inner);
            } else if (mostScroll > 0 && wheel != 0 && !io.getKeyShift() && mouseY >= rulerBottom) {
                scroll = Math.clamp(scroll - wheel * WHEEL_LANES * LANE, 0, mostScroll);
            } else if (wheel != 0 || across != 0) {
                scale.pan((across - wheel) * PAN_PER_NOTCH, length, inner);
            }
        }
        if (ImGui.isItemActivated()) {
            if (mostScroll > 0
                    && mouseY >= rulerBottom
                    && mouseX >= left + inner - SCROLL_REACH
                    && !ImGui.isMouseDown(2)) {
                holding = Holding.SCROLL;
                pressY = mouseY;
                return null;
            }
            return press(lanes, x, left, rulerBottom, length, playhead, frame, mouseX, mouseY);
        }
        if (ImGui.isItemDeactivated()) {
            return release(lanes, left);
        }
        if (!ImGui.isItemActive()) {
            holding = Holding.NOTHING;
            return null;
        }
        return switch (holding) {
            case RULER -> new Gesture(Kind.SEEK, seek(mouseX, left, frame, length), List.of(), false);
            case KEY -> dragKey(mouseX, left, frame, free);
            case SPAN_END -> dragSpan(mouseX, frame, free, length);
            case VIEW -> {
                scale.pan(pressX - mouseX, length, inner);
                pressX = mouseX;
                yield null;
            }
            case SCROLL -> {
                var room = bottom - rulerBottom;
                scroll = Math.clamp(scroll + (mouseY - pressY) * lanesHeight / Math.max(1, room), 0, mostScroll);
                pressY = mouseY;
                yield null;
            }
            default -> null;
        };
    }

    private @Nullable Gesture press(
            List<Lane> lanes,
            float x,
            float left,
            float rulerBottom,
            double length,
            double playhead,
            double frame,
            float mouseX,
            float mouseY) {
        pressX = mouseX;
        pressY = mouseY;
        moved = false;
        additive = ImGui.getIO().getKeyShift();
        clicked = null;
        clickedSpan = false;
        if (ImGui.isMouseDown(2)) {
            holding = Holding.VIEW;
            return null;
        }
        if (mouseY < rulerBottom) {
            holding = Holding.RULER;
            return new Gesture(Kind.SEEK, seek(mouseX, left, frame, length), List.of(), false);
        }
        if (mouseX < left) {
            var named = laneUnder(mouseY);
            if (named >= 0 && lanes.get(named).fold() != Fold.NONE) {
                holding = Holding.NOTHING;
                return new Gesture(Kind.FOLDED, 0, List.of(lanes.get(named).id()), false);
            }
        }
        var mark = markUnder(lanes, left, mouseX, mouseY);
        if (mark == null) {
            var lane = mouseX < left ? -1 : laneUnder(mouseY);
            var clip = lane < 0 ? null : clipAt(lanes.get(lane).spans(), mouseX - left, scale);
            if (clip != null) {
                return pressClip(lanes, lane, clip, playhead, length);
            }
            holding = lane >= 0 && pressSpan(lanes, lanes.get(lane), mouseX - left, playhead, length, frame)
                    ? holding
                    : Holding.BOX;
            return null;
        }
        if (ImGui.isMouseDoubleClicked(0) && !additive) {
            holding = Holding.NOTHING;
            return new Gesture(Kind.OPENED, mark.seconds(), List.of(mark.id()), false);
        }
        holding = Holding.KEY;
        grabbed = mark.seconds();
        targets = snapTargets(lanes, playhead, length);
        if (mark.selected() && !additive) {
            clicked = mark.id();
            return null;
        }
        return new Gesture(Kind.PICKED, 0, List.of(mark.id()), additive);
    }

    private @Nullable Gesture pressClip(List<Lane> lanes, int lane, Span clip, double playhead, double length) {
        holding = Holding.KEY;
        grabbed = clip.start();
        targets = snapTargets(lanes, playhead, length, lane, clip);
        if (clip.chosen() && !additive) {
            clicked = clip.id();
            clickedSpan = true;
            return null;
        }
        return new Gesture(Kind.SPAN_PICKED, 0, List.of(clip.id()), additive);
    }

    private boolean pressSpan(List<Lane> lanes, Lane lane, float pixel, double playhead, double length, double frame) {
        var span = spanAt(lane.spans(), pixel, scale, SPAN_REACH);
        if (span == null && !lane.paints()) {
            return false;
        }
        targets = snapTargets(lanes, playhead, length);
        if (span == null) {
            spanId = "";
            grabbed = snap(scale.toSeconds(pixel), frame, targets, SNAP_PIXELS * scale.getPerPixel());
            spanStays = grabbed;
            holding = Holding.SPAN_END;
            return true;
        }
        spanId = span.id();
        if (!onSpanEnd(span, pixel, scale, SPAN_REACH)) {
            holding = Holding.SPAN_BODY;
            return true;
        }
        var first = Math.abs(pixel - scale.toPixel(span.start())) <= Math.abs(pixel - scale.toPixel(span.end()));
        grabbed = first ? span.start() : span.end();
        spanStays = first ? span.end() : span.start();
        holding = Holding.SPAN_END;
        return true;
    }

    private @Nullable Gesture dragSpan(float mouseX, double frame, boolean free, double length) {
        var pixels = mouseX - pressX;
        if (!moved) {
            if (Math.abs(pixels) < DEAD_ZONE) {
                return null;
            }
            moved = true;
        }
        var wanted = grabbed + pixels * scale.getPerPixel();
        var landed = free ? wanted : snap(wanted, frame, targets, SNAP_PIXELS * scale.getPerPixel());
        return new Gesture(Kind.SPANNED, Math.clamp(landed, 0, length), List.of(spanId), false, spanStays);
    }

    private @Nullable Gesture release(List<Lane> lanes, float left) {
        var was = holding;
        holding = Holding.NOTHING;
        return switch (was) {
            case RULER -> new Gesture(Kind.RELEASED, 0, List.of(), false);
            case SPAN_BODY -> new Gesture(Kind.SPAN_PICKED, 0, List.of(spanId), additive);
            case SPAN_END -> {
                if (moved) {
                    yield new Gesture(Kind.RELEASED, 0, List.of(), false);
                }
                yield spanId.isEmpty()
                        ? new Gesture(Kind.CLEARED, 0, List.of(), false)
                        : new Gesture(Kind.SPAN_PICKED, 0, List.of(spanId), additive);
            }
            case KEY -> {
                if (moved) {
                    yield new Gesture(Kind.RELEASED, 0, List.of(), false);
                }
                yield clicked == null
                        ? null
                        : new Gesture(clickedSpan ? Kind.SPAN_PICKED : Kind.PICKED, 0, List.of(clicked), false);
            }
            case BOX -> {
                var mouseX = ImGui.getMousePosX();
                if (Math.abs(mouseX - pressX) < DEAD_ZONE && Math.abs(ImGui.getMousePosY() - pressY) < DEAD_ZONE) {
                    yield new Gesture(Kind.CLEARED, 0, List.of(), false);
                }
                var inside = new ArrayList<String>();
                var from = Math.min(pressY, ImGui.getMousePosY());
                var to = Math.max(pressY, ImGui.getMousePosY());
                for (var index = 0; index < laneTops.length; index++) {
                    if (laneTops[index] <= to && laneTops[index] + laneHeights[index] >= from) {
                        inside.addAll(marksBetween(lanes.get(index).marks(), pressX - left, mouseX - left, scale));
                    }
                }
                yield new Gesture(Kind.BOXED, 0, inside, additive);
            }
            default -> null;
        };
    }

    private double seek(float mouseX, float left, double frame, double length) {
        var seconds = scale.toSeconds(mouseX - left);
        return Math.clamp(frame <= 0 ? seconds : Math.round(seconds / frame) * frame, 0, length);
    }

    private @Nullable Gesture dragKey(float mouseX, float left, double frame, boolean free) {
        var pixels = mouseX - pressX;
        if (!moved) {
            if (Math.abs(pixels) < DEAD_ZONE) {
                return null;
            }
            moved = true;
        }
        var wanted = grabbed + pixels * scale.getPerPixel();
        var landed = free ? wanted : snap(wanted, frame, targets, SNAP_PIXELS * scale.getPerPixel());
        return new Gesture(Kind.DRAGGED, landed - grabbed, List.of(), false);
    }

    private @Nullable Mark markUnder(List<Lane> lanes, float left, float mouseX, float mouseY) {
        var lane = laneUnder(mouseY);
        return lane < 0 ? null : markAt(lanes.get(lane).marks(), mouseX - left, scale, KEY_REACH);
    }

    private int laneUnder(float mouseY) {
        for (var index = 0; index < laneTops.length; index++) {
            if (mouseY >= laneTops[index] && mouseY < laneTops[index] + laneHeights[index] + LANE_GAP) {
                return index;
            }
        }
        return -1;
    }

    private void drawLanes(ImDrawList draw, List<Lane> lanes, float x, float left, float inner) {
        lanesDrawn.clear();
        for (var index = 0; index < laneTops.length; index++) {
            var y = laneTops[index];
            var height = laneHeights[index];
            var lane = lanes.get(index);
            lanesDrawn.put(lane.id(), new float[] {x, y + height * .5f});
            var text = x + (lane.child() ? CHILD_INDENT : 0);
            if (lane.fold() != Fold.NONE) {
                HudWidgets.glyph(
                        draw,
                        lane.fold() == Fold.OPEN ? Glyph.CHEVRON : Glyph.CHEVRON_RIGHT,
                        text,
                        Math.round(y + (height - CHEVRON) * .5f),
                        CHEVRON,
                        HudWidgets.MUTED);
                text += CHEVRON + 2;
            }
            draw.addText(
                    text,
                    y + (height - ImGui.getFontSize()) * .5f - 1,
                    HudWidgets.MUTED,
                    HudWidgets.truncateToWidth(lane.label(), left - text - 8));
            draw.addRectFilled(left, y, left + inner, y + height, GeodataWidgets.CARD, RADIUS);
        }
    }

    private void drawSpans(ImDrawList draw, List<Lane> lanes, float left, float inner) {
        spansDrawn.clear();
        for (var index = 0; index < laneTops.length; index++) {
            for (var span : lanes.get(index).spans()) {
                var from = left + scale.toPixel(span.start());
                var to = left + scale.toPixel(span.end());
                var high = spanTop(index, span);
                var low = spanTop(index, span) + spanHeight(index, span);
                if (!span.id().isEmpty()) {
                    spansDrawn.put(span.id(), new float[] {from, high, to, low});
                }
                if (span.guess()) {
                    drawGuess(draw, from, to, high, low, span.color());
                } else {
                    draw.addRectFilled(from, high, to, low, span.color(), RADIUS);
                }
                if (span.moves()) {
                    draw.addRectFilled(from, high, from + 2, low, span.color() | 0xFF000000, 1f);
                }
                if (span.chosen()) {
                    draw.addRect(from, high, to, low, HudWidgets.ACCENT, RADIUS, 0, 1.5f);
                }
                if (span.glyph() != null && to - from >= SPAN_GLYPH + 2 * SPAN_INSET) {
                    HudWidgets.glyph(
                            draw,
                            span.glyph(),
                            Math.round(from + SPAN_INSET),
                            Math.round((high + low - SPAN_GLYPH) * .5f),
                            SPAN_GLYPH,
                            HudWidgets.MUTED);
                }
                drawLabel(
                        draw,
                        span,
                        Math.max(from, left),
                        Math.min(to, left + inner),
                        high,
                        low,
                        markPixels(lanes.get(index), left));
            }
        }
    }

    private float[] markPixels(Lane lane, float left) {
        var pixels = new float[lane.marks().size()];
        for (var index = 0; index < pixels.length; index++) {
            var mark = lane.marks().get(index);
            pixels[index] = left + scale.toPixel(mark.seconds()) + mark.nudge();
        }
        return pixels;
    }

    private float spanTop(int lane, Span span) {
        return laneTops[lane] + SPAN_INSET + spanHeight(lane, span) * span.band();
    }

    private float spanHeight(int lane, Span span) {
        return (laneHeights[lane] - 2 * SPAN_INSET) / span.bands();
    }

    private void drawProgress(ImDrawList draw, float left, float rulerBottom, float inner) {
        if (progress >= 0) {
            draw.addRectFilled(
                    left,
                    rulerBottom - PROGRESS_HEIGHT,
                    left + inner * (float) Math.min(1, progress),
                    rulerBottom,
                    HudWidgets.ACCENT);
        }
    }

    private void drawScrollBar(ImDrawList draw, float right, float rulerBottom, float bottom) {
        if (mostScroll <= 0) {
            return;
        }
        var room = bottom - rulerBottom;
        var thumb = Math.max(LEAST_THUMB, room * room / lanesHeight);
        var top = rulerBottom + (room - thumb) * scroll / mostScroll;
        draw.addRectFilled(
                right - SCROLL_BAR, top, right - 1, top + thumb, Motion.alpha(HudWidgets.TEXT, .25f), SCROLL_BAR * .5f);
    }

    private void drawRuler(ImDrawList draw, float left, float top, float bottom, float inner, double frame) {
        draw.addLine(left, bottom - 1, left + inner, bottom - 1, HudWidgets.BORDER, 1f);
        var first = scale.toSeconds(0);
        var last = scale.toSeconds(inner);
        if (frame > 0 && frame / scale.getPerPixel() >= FRAME_TICK_ROOM) {
            var tick = Math.ceil(first / frame);
            for (; tick * frame <= last; tick++) {
                var at = left + scale.toPixel(tick * frame);
                draw.addLine(at, bottom - 1 - TICK / 2, at, bottom - 1, HudWidgets.BORDER, 1f);
            }
        }
        var step = tickStep(scale.getPerPixel());
        for (var tick = Math.max(0, Math.ceil(first / step)); tick * step <= last; tick++) {
            var at = left + scale.toPixel(tick * step);
            draw.addLine(at, bottom - 1 - TICK, at, bottom - 1, HudWidgets.BORDER, 1f);
            var text = tickLabel(tick * step, step);
            draw.addText(tick == 0 ? at : at - ImGui.calcTextSizeX(text) * .5f, top, GeodataWidgets.FAINT, text);
        }
    }

    private void drawEnd(ImDrawList draw, float left, float rulerBottom, double length, float inner, float bottom) {
        var end = left + scale.toPixel(length);
        if (end < left + inner) {
            draw.addRectFilled(end, rulerBottom, left + inner, bottom, Motion.alpha(HudWidgets.BACKGROUND, .7f));
            draw.addLine(end, rulerBottom, end, bottom, HudWidgets.BORDER, 1f);
        }
    }

    private void drawKeys(ImDrawList draw, List<Lane> lanes, float left) {
        drawn.clear();
        for (var index = 0; index < laneTops.length; index++) {
            var lane = lanes.get(index);
            var y = laneTops[index] + laneHeights[index] * .5f;
            for (var mark : lane.marks()) {
                var cx = left + scale.toPixel(mark.seconds()) + mark.nudge();
                if (mark.quiet()) {
                    draw.addCircleFilled(cx, y, QUIET_RADIUS, GeodataWidgets.FAINT, 8);
                    continue;
                }
                drawn.put(mark.id(), new float[] {cx, y});
                var r = lane.thin()
                        ? mark.selected() ? THIN_CHOSEN_RADIUS : THIN_KEY_RADIUS
                        : mark.selected() ? CHOSEN_RADIUS : KEY_RADIUS;
                if (mark.nudge() > 0) {
                    draw.addLine(
                            cx - mark.nudge(),
                            y - KEY_RADIUS - CUT_BAR_REACH,
                            cx - mark.nudge(),
                            y + KEY_RADIUS + CUT_BAR_REACH,
                            HudWidgets.TEXT,
                            1.5f);
                }
                var color =
                        mark.selected() ? HudWidgets.ACCENT : mark.warned() ? GeodataWidgets.WARNING : HudWidgets.TEXT;
                if (mark.movable()) {
                    draw.addQuadFilled(cx, y - r, cx + r, y, cx, y + r, cx - r, y, color);
                } else {
                    draw.addQuad(cx, y - r, cx + r, y, cx, y + r, cx - r, y, color, 1.5f);
                }
            }
        }
    }

    private void drawBox(ImDrawList draw, float rulerBottom, float bottom) {
        if (holding != Holding.BOX) {
            return;
        }
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        var from = Math.min(pressX, x);
        var to = Math.max(pressX, x);
        var high = Math.max(rulerBottom, Math.min(pressY, y));
        var low = Math.min(bottom, Math.max(pressY, y));
        draw.addRectFilled(from, high, to, low, Motion.alpha(HudWidgets.ACCENT, .12f));
        draw.addRect(from, high, to, low, HudWidgets.ACCENT, 0f, 0, 1f);
    }

    private void drawPlayhead(
            ImDrawList draw, float left, float top, float rulerBottom, float bottom, double playhead) {
        var at = left + scale.toPixel(playhead);
        draw.addLine(at, top, at, Math.min(bottom, rulerBottom + lanesHeight - LANE_GAP), HudWidgets.ACCENT, 1f);
        draw.addTriangleFilled(
                at - HEAD,
                rulerBottom - HEAD - 1,
                at + HEAD,
                rulerBottom - HEAD - 1,
                at,
                rulerBottom - 1,
                HudWidgets.ACCENT);
    }

    private void drawTip(List<Lane> lanes, float left) {
        if (holding != Holding.NOTHING || !ImGui.isItemHovered()) {
            return;
        }
        var mark = markUnder(lanes, left, ImGui.getMousePosX(), ImGui.getMousePosY());
        if (mark != null && !mark.tip().isEmpty()) {
            HudWidgets.showTooltip(mark.tip());
        } else if (mark == null) {
            spanTip(lanes, left);
        }
    }

    private void spanTip(List<Lane> lanes, float left) {
        var lane = ImGui.getMousePosX() < left ? -1 : laneUnder(ImGui.getMousePosY());
        if (lane < 0) {
            return;
        }
        var pixel = ImGui.getMousePosX() - left;
        Span found = null;
        for (var span : lanes.get(lane).spans()) {
            var height = ImGui.getMousePosY() - spanTop(lane, span);
            var inBand = span.bands() == 1 || height >= 0 && height <= spanHeight(lane, span);
            if (!span.tip().isEmpty()
                    && inBand
                    && pixel >= scale.toPixel(span.start())
                    && pixel <= scale.toPixel(span.end())) {
                found = span;
            }
        }
        var taken = spanAt(lanes.get(lane).spans(), pixel, scale, SPAN_REACH);
        if (taken != null && onSpanEnd(taken, pixel, scale, SPAN_REACH)) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }
        if (found != null) {
            HudWidgets.showTooltip(found.tip());
        }
    }

    private static void drawLabel(
            ImDrawList draw, Span span, float from, float to, float high, float low, float[] marks) {
        if (span.label().isEmpty() || low - high < ImGui.getFontSize()) {
            return;
        }
        var start = span.glyph() == null ? from : from + SPAN_GLYPH + SPAN_INSET;
        var width = ImGui.calcTextSizeX(span.label());
        var textLeft = Math.round(start + LABEL_INSET);
        if (doesLabelFit(start, to, width) && isLabelClear(textLeft, textLeft + width, marks)) {
            draw.addText(
                    textLeft,
                    Math.round((high + low - ImGui.getFontSize()) * .5f - 1),
                    span.color() | 0xFF000000,
                    span.label());
        }
    }

    private static void drawGuess(ImDrawList draw, float from, float to, float high, float low, int color) {
        var solid = from + (to - from) * GUESS_SOLID;
        var clear = color & 0x00FFFFFF;
        draw.addRectFilled(from, high, solid, low, color, RADIUS, ImDrawFlags.RoundCornersLeft);
        draw.addRectFilledMultiColor(solid, high, to, low, color, clear, clear, color);
    }
}
