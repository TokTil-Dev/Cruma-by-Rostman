package com.rost.cruma.core.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record EmitterTimeWindows(List<Window> windows) {
    public static final EmitterTimeWindows ALWAYS = new EmitterTimeWindows(List.of());
    private static final double FADE_PER_HOUR = 250.0;

    public EmitterTimeWindows {
        windows = List.copyOf(windows);
    }

    public record Window(float min, float max) {
        public float middle() {
            return (min + max) * .5f;
        }

        public String label() {
            return clock(min) + "-" + clock(max);
        }
    }

    public static EmitterTimeWindows ofAuthored(List<float[]> pairs) {
        if (pairs.isEmpty()) {
            return ALWAYS;
        }
        var windows = new ArrayList<Window>(pairs.size());
        for (var pair : pairs) {
            windows.add(new Window(hours(pair[0]), hours(pair[1])));
        }
        return new EmitterTimeWindows(windows);
    }

    public static float hours(float hhmm) {
        var hour = (int) (hhmm / 100.0);
        var rest = hhmm - (float) (hour * 100);
        var fraction = rest - (float) (int) rest;
        return (float) (int) rest * 0.016666668f
                + (float) hour
                + (float) (int) ((fraction * 100.0) / 60.0) * 0.00027777778f;
    }

    public static String clock(float hours) {
        var minutes = Math.round(hours * 60f);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    public boolean always() {
        return windows.isEmpty();
    }

    public float ratio(double hour) {
        if (windows.isEmpty()) {
            return 1f;
        }
        var best = 0f;
        for (var window : windows) {
            if (window.min() < hour && hour < window.max()) {
                var edge = Math.min(hour - window.min(), window.max() - hour);
                best = Math.max(best, (float) Math.min(1.0, edge * FADE_PER_HOUR));
            }
        }
        return best;
    }

    public boolean showsDuring(double from, double to) {
        if (windows.isEmpty()) {
            return true;
        }
        for (var window : windows) {
            if (window.min() < to && from < window.max() && window.min() < window.max()) {
                return true;
            }
        }
        return false;
    }

    public String label() {
        if (windows.isEmpty()) {
            return "Every hour";
        }
        var parts = new ArrayList<String>(windows.size());
        for (var window : windows) {
            parts.add(window.label());
        }
        return String.join(", ", parts);
    }
}
