package com.rost.cruma.core.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record AmbientSoundHours(List<Window> windows) {
    public static final AmbientSoundHours NONE = new AmbientSoundHours(List.of());

    private static final Map<List<Window>, String> LABELS = new ConcurrentHashMap<>();

    public AmbientSoundHours {
        windows = List.copyOf(windows);
    }

    public record Window(float start, float end) {}

    public static AmbientSoundHours ofAuthored(List<float[]> pairs) {
        if (pairs.isEmpty()) {
            return NONE;
        }
        var windows = new ArrayList<Window>(pairs.size());
        for (var pair : pairs) {
            windows.add(new Window(pair[0], pair[1]));
        }
        return new AmbientSoundHours(windows);
    }

    public static boolean isSoundDay(double hour) {
        return hour >= 5.999 || hour <= 0.001;
    }

    public static boolean isSoundNight(double hour) {
        return hour < 6.001 || hour > 23.999;
    }

    public static float clockOf(double hour) {
        var hours = (int) hour;
        var fraction = hour - hours;
        var minutes = (int) (fraction * 60.0);
        var seconds = (int) ((fraction - minutes / 60.0) * 3600.0);
        return (float) hours * 100f + (float) minutes + (float) seconds / 100f;
    }

    public boolean contains(double hour) {
        var clock = clockOf(hour);
        for (var window : windows) {
            if (window.start() <= clock && clock <= window.end()) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return windows.isEmpty();
    }

    public EmitterTimeWindows timeWindows() {
        var pairs = new ArrayList<float[]>(windows.size());
        for (var window : windows) {
            pairs.add(new float[] {window.start(), window.end()});
        }
        return EmitterTimeWindows.ofAuthored(pairs);
    }

    public String label() {
        if (windows.isEmpty()) {
            return "";
        }
        return LABELS.computeIfAbsent(windows, ignored -> timeWindows().label());
    }
}
