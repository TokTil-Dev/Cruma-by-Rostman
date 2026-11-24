package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.viewer.render.GameScenePlayer.Message;
import java.util.ArrayList;
import java.util.List;

final class GameLines {
    private static final int FIRST_WINDOW = 1;
    private static final int LAST_WINDOW = 9;
    private static final int OPAQUE = 255;
    private static final double TICK = .03;

    private final Line[] lines = new Line[LAST_WINDOW + 1];
    private double now;

    static int alpha(double seconds, double hold, int step) {
        var rising = ticks(step) * TICK;
        if (seconds < rising) {
            return Math.min(OPAQUE, (int) (seconds / TICK) * step);
        }
        var falling = seconds - rising - hold;
        return falling < 0 ? OPAQUE : Math.max(0, OPAQUE - (int) (falling / TICK) * step);
    }

    static int step(int animation) {
        return switch (animation) {
            case 1, 12 -> 25;
            case 11 -> 15;
            case 13 -> 35;
            default -> OPAQUE;
        };
    }

    void show(Screen screen, double at) {
        var window = screen.window();
        if (window >= FIRST_WINDOW && window <= LAST_WINDOW) {
            lines[window] = new Line(screen, at, step(screen.animation()));
        }
    }

    void advance(double seconds) {
        now = seconds;
        for (var window = FIRST_WINDOW; window <= LAST_WINDOW; window++) {
            var line = lines[window];
            if (line != null && line.gone(now)) {
                lines[window] = null;
            }
        }
    }

    void clear() {
        now = 0;
        for (var window = FIRST_WINDOW; window <= LAST_WINDOW; window++) {
            lines[window] = null;
        }
    }

    List<Message> getShown() {
        var shown = new ArrayList<Message>(2);
        for (var window = FIRST_WINDOW; window <= LAST_WINDOW; window++) {
            var line = lines[window];
            if (line != null) {
                shown.add(new Message(line.screen.message(), line.screen.text(), window, line.alpha(now)));
            }
        }
        return shown;
    }

    private static int ticks(int step) {
        return (OPAQUE + step - 1) / step;
    }

    private record Line(Screen screen, double start, int step) {
        int alpha(double seconds) {
            return GameLines.alpha(seconds - start, screen.hold(), step);
        }

        boolean gone(double seconds) {
            return seconds - start >= ticks(step) * 2 * TICK + screen.hold();
        }
    }
}
