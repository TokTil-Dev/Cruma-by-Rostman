package com.rost.cruma.viewer.editing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

final class LightChoice {
    private final LinkedHashSet<String> paths = new LinkedHashSet<>();
    private String active = "";

    String getActive() {
        return active;
    }

    List<String> getAll() {
        var result = new ArrayList<String>(paths.size());
        if (!active.isEmpty()) {
            result.add(active);
        }
        for (var path : paths) {
            if (!path.equals(active)) {
                result.add(path);
            }
        }
        return result;
    }

    int size() {
        return paths.size();
    }

    boolean contains(String path) {
        return paths.contains(path);
    }

    void replace(String path) {
        paths.clear();
        active = path;
        if (!path.isEmpty()) {
            paths.add(path);
        }
    }

    void toggle(String path) {
        if (paths.remove(path)) {
            if (path.equals(active)) {
                active = paths.isEmpty() ? "" : paths.getLast();
            }
            return;
        }
        add(path);
    }

    void add(String path) {
        if (path.isEmpty()) {
            return;
        }
        paths.add(path);
        active = path;
    }

    void choose(Collection<String> inside, boolean additive) {
        if (!additive) {
            paths.clear();
            active = "";
        }
        paths.addAll(inside);
        if (active.isEmpty() && !paths.isEmpty()) {
            active = paths.getFirst();
        }
    }

    void clear() {
        paths.clear();
        active = "";
    }

    void retain(Predicate<String> keep) {
        paths.removeIf(keep.negate());
        if (!paths.contains(active)) {
            active = paths.isEmpty() ? "" : paths.getLast();
        }
    }
}
