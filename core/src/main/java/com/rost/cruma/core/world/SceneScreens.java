package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneItems.Part;
import com.rost.cruma.core.world.SceneOrders.Fix;
import com.rost.cruma.core.world.SceneOrders.Problem;
import com.rost.cruma.core.world.SceneOrders.Warning;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

public final class SceneScreens {
    public static final DoubleUnaryOperator NO_RUN = moment -> moment;

    private static final int COARSEST_RATE = 30;

    private static final int FIRST_WINDOW = 1;
    private static final int LAST_WINDOW = 9;
    private static final int LINE_ANIMATION = 1;
    private static final double LINE_SECONDS = 3;

    private SceneScreens() {}

    public static Screen fade() {
        return screen(ScreenKind.FADE, 1, 1, 1, 0, 0, 0, "", "", 0, 0, 0, 0, 0);
    }

    public static Screen fade(double to, double hold, double back) {
        return screen(ScreenKind.FADE, to, back, hold, 0, 0, 0, "", "", 0, 0, 0, 0, 0);
    }

    public static Screen line(String text) {
        return screen(ScreenKind.MESSAGE, 0, 0, LINE_SECONDS, LAST_WINDOW, LINE_ANIMATION, 0, text, "", 0, 0, 0, 0, 0);
    }

    public static Screen slideStart() {
        return screen(ScreenKind.SLIDE_START, 0, 0, 0, 0, 0, 0, "", "", 0, 0, 0, 0, 0);
    }

    public static Screen slideEnd() {
        return screen(ScreenKind.SLIDE_END, 0, 0, 0, 0, 0, 0, "", "", 0, 0, 0, 0, 0);
    }

    public static Screen slide(String picture, int width, int height) {
        return screen(ScreenKind.SLIDE_CHANGE, 0, 0, 0, 0, 0, 0, "", picture, 1, width, height, 0, 0);
    }

    public static Screen postEffect(int row) {
        return screen(ScreenKind.POST_EFFECT, 0, 0, 0, 0, 0, 0, "", "", 0, 0, 0, row, 0);
    }

    public static List<Warning> warnings(List<Item> items, int rate, DoubleUnaryOperator nextMark) {
        var warnings = new ArrayList<Warning>();
        var waiting = new ArrayList<Integer>();
        var step = 1.0 / rate;
        var seconds = 0.0;
        var next = 0;
        var fadeAt = 0.0;
        var fadeLength = 0.0;
        var fadeStart = 0.0;
        var fading = false;
        var holds = false;
        var markAt = Double.POSITIVE_INFINITY;
        while (next < items.size()) {
            seconds += step;
            while (next < items.size() && items.get(next).seconds() <= seconds) {
                var screen = items.get(next).screen();
                if (screen != null && screen.kind() == ScreenKind.MESSAGE && !hasWindow(screen)) {
                    warnings.add(new Warning(new Key(Part.SCREEN, next, 0), Problem.NO_WINDOW, Fix.NONE, 0, 0));
                }
                if (screen != null && screen.kind() == ScreenKind.FADE) {
                    if (fading) {
                        waiting.add(next);
                    } else {
                        fading = true;
                        fadeAt = 0;
                        fadeLength = screen.fadeOut() + screen.hold() + screen.fadeIn();
                        fadeStart = seconds;
                    }
                    if (screen.waits() && !holds) {
                        holds = true;
                        markAt = nextMark.applyAsDouble(seconds);
                    }
                }
                next++;
            }
            holds &= seconds < markAt;
            if (fading) {
                fadeAt += step;
                if (!holds && fadeAt >= fadeLength) {
                    fading = false;
                    var free = free(fadeStart + fadeLength, seconds + step, rate);
                    for (var index : waiting) {
                        warnings.add(new Warning(
                                new Key(Part.SCREEN, index, 0), Problem.FADE_DROPPED, Fix.MOVE_ORDER, free, 0));
                    }
                    waiting.clear();
                }
            }
        }
        for (var index : waiting) {
            warnings.add(new Warning(new Key(Part.SCREEN, index, 0), Problem.FADE_DROPPED, Fix.REMOVE_ORDER, 0, 0));
        }
        return warnings;
    }

    public static boolean hasWindow(Screen line) {
        return line.window() >= FIRST_WINDOW && line.window() <= LAST_WINDOW;
    }

    private static Screen screen(
            ScreenKind kind,
            double fadeOut,
            double fadeIn,
            double hold,
            int window,
            int animation,
            int message,
            String text,
            String slide,
            double slideSeconds,
            int slideWidth,
            int slideHeight,
            int postEffect,
            int slideMove) {
        return new Screen(
                kind,
                fadeOut,
                fadeIn,
                hold,
                0,
                window,
                animation,
                message,
                text,
                slide,
                0,
                slideSeconds,
                slideWidth,
                slideHeight,
                slideMove,
                0,
                postEffect,
                false);
    }

    private static double free(double end, double nextStep, int rate) {
        var coarsest = end + 1.0 / COARSEST_RATE;
        return Math.max(nextStep, Math.ceil(coarsest * rate - 1e-9) / rate);
    }
}
