package com.rost.cruma.viewer.editing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class ClickCycle {
    static final float PIXELS = 4;

    static final double SECONDS = 1.5;

    private float lastX = Float.NaN;
    private float lastY = Float.NaN;
    private double lastTime = Double.NEGATIVE_INFINITY;

    record Hit(String actor, float distance) {}

    static List<String> order(List<Hit> hits, @Nullable String visible) {
        var sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingDouble(Hit::distance));
        var names = new ArrayList<String>(sorted.size());
        for (var hit : sorted) {
            if (!names.contains(hit.actor())) {
                names.add(hit.actor());
            }
        }
        if (visible == null) {
            return names;
        }
        var at = names.indexOf(visible);
        if (at < 0) {
            names.addFirst(visible);
            return names;
        }
        var result = new ArrayList<String>(names.size());
        result.addAll(names.subList(at, names.size()));
        result.addAll(names.subList(0, at));
        return result;
    }

    static @Nullable String next(List<String> order, String current) {
        var at = order.indexOf(current);
        if (at < 0 || order.size() < 2) {
            return null;
        }
        return order.get((at + 1) % order.size());
    }

    boolean isRepeatClick(float x, float y, double time) {
        var same = Math.hypot(x - lastX, y - lastY) <= PIXELS && time - lastTime <= SECONDS && time >= lastTime;
        lastX = x;
        lastY = y;
        lastTime = time;
        return same;
    }

    void reset() {
        lastX = Float.NaN;
        lastY = Float.NaN;
        lastTime = Double.NEGATIVE_INFINITY;
    }
}
