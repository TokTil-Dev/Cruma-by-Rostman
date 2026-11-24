package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.geometry.Bounds3f;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class GroundStick {
    private final Terrain terrain;
    private final Map<String, Anchor> anchors;
    private final Map<String, Float> last = new HashMap<>();

    interface Terrain {
        @Nullable
        Float getCurrent(double x, double y);

        @Nullable
        Float getSaved(double x, double y);

        boolean isReady(double x, double y);
    }

    record Anchor(float x, float y, float bottom, float sourceX, float sourceY, float sourceBottom) {
        static Anchor of(Bounds3f bounds, Vector3 location, @Nullable Vector3 source) {
            var x = (bounds.minX() + bounds.maxX()) * .5f;
            var y = (bounds.minY() + bounds.maxY()) * .5f;
            if (source == null) {
                return new Anchor(x, y, bounds.minZ(), Float.NaN, Float.NaN, Float.NaN);
            }
            return new Anchor(
                    x,
                    y,
                    bounds.minZ(),
                    x - (location.x() - source.x()),
                    y - (location.y() - source.y()),
                    bounds.minZ() - (location.z() - source.z()));
        }
    }

    GroundStick(Terrain terrain, Map<String, Anchor> anchors) {
        this.terrain = terrain;
        this.anchors = Map.copyOf(anchors);
    }

    Map<String, Float> getHeights(float dx, float dy) {
        if (dx == 0 && dy == 0) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Float>();
        anchors.forEach((path, anchor) -> {
            var height = height(anchor, dx, dy);
            if (!Float.isNaN(height)) {
                last.put(path, height);
                result.put(path, height);
                return;
            }
            var previous = last.get(path);
            if (previous != null && isWaiting(anchor, dx, dy)) {
                result.put(path, previous);
            }
        });
        return result;
    }

    private boolean isWaiting(Anchor anchor, float dx, float dy) {
        return !terrain.isReady(anchor.x() + dx, anchor.y() + dy)
                || !Float.isNaN(anchor.sourceBottom()) && !terrain.isReady(anchor.sourceX(), anchor.sourceY());
    }

    private float height(Anchor anchor, float dx, float dy) {
        var ground = terrain.getCurrent(anchor.x() + dx, anchor.y() + dy);
        var clearance = clearance(anchor);
        if (ground == null || Float.isNaN(clearance) || clearance > EditingCheck.TOLERANCE) {
            return Float.NaN;
        }
        return ground + clearance - anchor.bottom();
    }

    private float clearance(Anchor anchor) {
        if (!Float.isNaN(anchor.sourceBottom())) {
            var then = terrain.getSaved(anchor.sourceX(), anchor.sourceY());
            if (then != null) {
                return anchor.sourceBottom() - then;
            }
            if (!terrain.isReady(anchor.sourceX(), anchor.sourceY())) {
                return Float.NaN;
            }
        }
        var now = terrain.getCurrent(anchor.x(), anchor.y());
        return now == null ? Float.NaN : anchor.bottom() - now;
    }
}
