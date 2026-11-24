package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.GameScenes.Actor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class SceneDoors {
    private static final int MATRIX = 16;
    private static final int TRANSLATION = 12;
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private final Map<String, Door> doors = new HashMap<>();
    private final Set<String> changed = new HashSet<>();
    private final Set<String> restored = new HashSet<>();

    public record Moved(Map<String, float[]> moved, Set<String> restored) {}

    private static final class Door {
        private Actor order;
        private double open;
        private double wanted;

        private Door(Actor order) {
            this.order = order;
        }
    }

    void order(Actor order) {
        var door = doors.computeIfAbsent(order.animation(), _ -> new Door(order));
        door.order = order;
        door.wanted = order.run() ? 1 : 0;
        if (order.skill() == 0) {
            door.open = door.wanted;
            changed.add(order.animation());
        }
    }

    void advance(double seconds) {
        for (var entry : doors.entrySet()) {
            var door = entry.getValue();
            if (door.open != door.wanted) {
                var step = seconds * 1000 / door.order.skill();
                door.open = door.open < door.wanted
                        ? Math.min(door.wanted, door.open + step)
                        : Math.max(door.wanted, door.open - step);
                changed.add(entry.getKey());
            }
        }
    }

    void clear() {
        restored.addAll(doors.keySet());
        doors.clear();
        changed.clear();
    }

    @Nullable
    Moved take() {
        if (changed.isEmpty() && restored.isEmpty()) {
            return null;
        }
        var moved = new HashMap<String, float[]>();
        for (var actor : changed) {
            moved.put(actor, matrix(doors.get(actor)));
        }
        var back = new HashSet<>(restored);
        back.removeAll(moved.keySet());
        changed.clear();
        restored.clear();
        return new Moved(moved, back);
    }

    private static float[] matrix(Door door) {
        var matrix = new float[MATRIX];
        System.arraycopy(IDENTITY, 0, matrix, 0, MATRIX);
        matrix[TRANSLATION] = (float) (door.order.x() * door.open);
        matrix[TRANSLATION + 1] = (float) (door.order.y() * door.open);
        matrix[TRANSLATION + 2] = (float) (door.order.z() * door.open);
        return matrix;
    }
}
