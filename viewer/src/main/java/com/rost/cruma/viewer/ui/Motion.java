package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.render.api.UiFade;
import imgui.ImGui;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

public final class Motion {
    public static final float QUICK = .1f;

    public static final float OPEN = .22f;

    public static final float SWAP = .18f;

    private static final float MAX_STEP = 1 / 20f;
    private static final float SETTLED = .002f;
    private static final int FORGET_FRAMES = 240;
    private static final IntObjectHashMap<Track> TRACKS = new IntObjectHashMap<>();
    private static final float SPRING_HERTZ = 3.2f;
    private static final float SPRING_DAMPING = .72f;
    private static final float[] SPRING = new float[2];
    private static final boolean HELD = "false".equals(System.getProperty("cruma.viewer.motion"));
    private static boolean enabled = !HELD;
    private static int frame = Integer.MIN_VALUE;
    private static float step;
    private static double now;
    private static boolean moving;
    private static boolean movedLastFrame;

    private Motion() {}

    private static final class Track {
        float value;
        float speed;
        double start;
        int seen = Integer.MIN_VALUE;
        float goal = Float.NaN;
        float pull;
        float give;
    }

    public static void setEnabled(boolean on) {
        enabled = on && !HELD;
        if (!enabled) {
            TRACKS.clear();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isAnimating() {
        return enabled && (moving || movedLastFrame);
    }

    public static float follow(String id, float target, float seconds) {
        return follow(ImGui.getID(id), target, seconds);
    }

    public static float follow(int id, float target, float seconds) {
        if (!enabled) {
            return target;
        }
        var track = track(id);
        if (track.seen < frame - 1) {
            track.value = target;
        } else if (track.seen != frame) {
            track.value = approach(track.value, target, step, seconds);
        }
        track.seen = frame;
        if (track.value != target) {
            moving = true;
        }
        return track.value;
    }

    public static float toggle(String id, boolean on, float seconds) {
        return follow(id, on ? 1 : 0, seconds);
    }

    public static float toggle(int id, boolean on, float seconds) {
        return follow(id, on ? 1 : 0, seconds);
    }

    public static float peek(int id, float fallback) {
        var track = enabled ? TRACKS.get(id) : null;
        return track == null || track.seen == Integer.MIN_VALUE ? fallback : track.value;
    }

    public static void jump(int id, float value) {
        if (enabled) {
            var track = track(id);
            track.value = value;
            track.speed = 0;
            track.seen = frame - 1;
        }
    }

    public static float spring(String id, float target) {
        return spring(ImGui.getID(id), target, SPRING_HERTZ, SPRING_DAMPING);
    }

    public static float spring(int id, float target, float hertz, float damping) {
        if (!enabled) {
            return target;
        }
        var track = track(id);
        if (track.seen < frame - 1 || Math.abs(target - track.value) > 4000) {
            track.value = target;
            track.speed = 0;
        } else if (track.seen != frame) {
            SPRING[0] = track.value;
            SPRING[1] = track.speed;
            spring(SPRING, target, step, hertz, damping);
            track.value = SPRING[0];
            track.speed = SPRING[1];
        }
        track.goal = Float.NaN;
        track.seen = frame;
        if (track.value != target) {
            moving = true;
        }
        return track.value;
    }

    public static float drop(int id, float target, float seconds, float rebound) {
        if (!enabled) {
            return target;
        }
        var track = track(id);
        if (track.seen < frame - 1) {
            track.value = target;
            track.speed = 0;
            track.goal = target;
            track.pull = 0;
        } else if (track.seen != frame) {
            if (track.goal != target) {
                var distance = Math.max(1, Math.abs(target - track.value));
                track.goal = target;
                track.pull = Math.signum(target - track.value) * 2 * distance / (seconds * seconds);
                track.give = (float) Math.min(.6, Math.sqrt(Math.max(0, rebound) / distance));
            }
            SPRING[0] = track.value;
            SPRING[1] = track.speed;
            drop(SPRING, target, track.pull, track.give, step);
            track.value = SPRING[0];
            track.speed = SPRING[1];
        }
        track.seen = frame;
        if (track.value != target || track.speed != 0) {
            moving = true;
        }
        return track.value;
    }

    public static float appear(String id, float seconds) {
        return appear(ImGui.getID(id), seconds);
    }

    public static float appear(int id, float seconds) {
        if (!enabled) {
            return 1;
        }
        var track = track(id);
        if (track.seen < frame - 1) {
            track.start = now;
        }
        track.seen = frame;
        var progress = (float) Math.clamp((now - track.start) / seconds, 0, 1);
        if (progress < 1) {
            moving = true;
        }
        return progress;
    }

    public static void restart(String id) {
        restart(ImGui.getID(id));
    }

    public static void restart(int id) {
        if (enabled) {
            var track = track(id);
            track.start = now;
            track.seen = frame;
        }
    }

    public static float since(int id, float seconds) {
        var track = enabled ? TRACKS.get(id) : null;
        if (track == null) {
            return 1;
        }
        tick();
        track.seen = frame;
        var progress = (float) Math.clamp((now - track.start) / seconds, 0, 1);
        if (progress < 1) {
            moving = true;
        }
        return progress;
    }

    public static void open(float progress, float offsetX, float offsetY, float scale) {
        if (progress >= 1) {
            return;
        }
        var eased = easeOut(progress);
        var rest = 1 - eased;
        fade(Math.clamp(progress * 1.6f, 0, 1), 1, offsetX * rest, offsetY * rest, 1 - (1 - scale) * rest, 0);
    }

    public static void dialog() {
        open(appear(ImGui.getID("##dialogOpen"), OPEN), 0, 12, .97f);
    }

    public static void swap(float progress, float lift) {
        if (progress >= 1) {
            return;
        }
        fade(1, easeOut(progress), 0, 0, 1, lift * (1 - easeOut(progress)));
    }

    public static void fade(
            float frameAlpha, float contentAlpha, float offsetX, float offsetY, float scale, float contentOffsetY) {
        if (!enabled
                || frameAlpha >= 1
                        && contentAlpha >= 1
                        && offsetX == 0
                        && offsetY == 0
                        && scale == 1
                        && contentOffsetY == 0) {
            return;
        }
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getWindowPosX();
        var y = ImGui.getWindowPosY();
        draw.primReserve(3, UiFade.MARK_VERTICES);
        var first = draw.getVtxCurrentIdx();
        draw.primWriteIdx(first);
        draw.primWriteIdx(first);
        draw.primWriteIdx(first);
        draw.primWriteVtx(x, y, UiFade.MARK, frameAlpha, 0);
        draw.primWriteVtx(x + ImGui.getWindowWidth(), y + ImGui.getWindowHeight(), UiFade.MARK, contentAlpha, 0);
        draw.primWriteVtx(offsetX, offsetY, UiFade.MARK, 0, 0);
        draw.primWriteVtx(scale, contentOffsetY, UiFade.MARK, 0, 0);
        UiFade.marked();
    }

    public static int mix(int from, int to, float t) {
        if (t <= 0) {
            return from;
        }
        if (t >= 1) {
            return to;
        }
        var result = 0;
        for (var shift = 0; shift < 32; shift += 8) {
            var a = (from >>> shift) & 255;
            var b = (to >>> shift) & 255;
            result |= Math.round(a + (b - a) * t) << shift;
        }
        return result;
    }

    public static int alpha(int color, float alpha) {
        var a = Math.round(((color >>> 24) & 255) * Math.clamp(alpha, 0, 1));
        return (color & 0x00ffffff) | a << 24;
    }

    public static float easeOut(float t) {
        var rest = 1 - Math.clamp(t, 0, 1);
        return 1 - rest * rest * rest;
    }

    public static float easeOutBack(float t) {
        var x = Math.clamp(t, 0, 1) - 1;
        var overshoot = 1.2f;
        return 1 + (overshoot + 1) * x * x * x + overshoot * x * x;
    }

    public static float easeInOut(float t) {
        var x = Math.clamp(t, 0, 1);
        return x < .5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    static float approach(float value, float target, float dt, float seconds) {
        var next = target + (value - target) * (float) Math.exp(-5 * dt / seconds);
        return Math.abs(target - next) < SETTLED ? target : next;
    }

    static void spring(float[] state, float target, float dt) {
        spring(state, target, dt, SPRING_HERTZ, SPRING_DAMPING);
    }

    static void spring(float[] state, float target, float dt, float hertz, float damping) {
        var omega = (float) (2 * Math.PI * hertz);
        var steps = Math.max(1, (int) Math.ceil(dt * 240));
        var h = dt / steps;
        for (var i = 0; i < steps; i++) {
            var force = omega * omega * (target - state[0]) - 2 * damping * omega * state[1];
            state[1] += force * h;
            state[0] += state[1] * h;
        }
        if (Math.abs(target - state[0]) < .05f && Math.abs(state[1]) < .5f) {
            state[0] = target;
            state[1] = 0;
        }
    }

    static void drop(float[] state, float target, float pull, float give, float dt) {
        if (pull == 0) {
            state[0] = target;
            state[1] = 0;
            return;
        }
        var steps = Math.max(1, (int) Math.ceil(dt * 240));
        var h = dt / steps;
        for (var i = 0; i < steps; i++) {
            state[1] += pull * h;
            state[0] += state[1] * h;
            if ((state[0] - target) * pull >= 0) {
                state[0] = target;
                var bounce = state[1] * give;
                state[1] = bounce * bounce < .4f * Math.abs(pull) ? 0 : -bounce;
            }
        }
    }

    private static Track track(int id) {
        tick();
        var track = TRACKS.get(id);
        if (track == null) {
            track = new Track();
            TRACKS.put(id, track);
        }
        return track;
    }

    private static void tick() {
        var current = ImGui.getFrameCount();
        if (current == frame) {
            return;
        }
        if (current < frame) {
            TRACKS.clear();
        }
        step = frame == Integer.MIN_VALUE ? 0 : Math.clamp(ImGui.getIO().getDeltaTime(), 0, MAX_STEP);
        now += step;
        frame = current;
        movedLastFrame = moving;
        moving = false;
        if (current % 60 == 0) {
            TRACKS.removeIf((id, track) -> track.seen < current - FORGET_FRAMES);
        }
    }
}
