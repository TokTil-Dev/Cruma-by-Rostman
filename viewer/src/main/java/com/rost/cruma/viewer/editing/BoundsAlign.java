package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.geometry.Bounds3f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public final class BoundsAlign {
    private BoundsAlign() {}

    public enum Edge {
        MIN,
        CENTER,
        MAX
    }

    public static <K> Map<K, Float> align(Map<K, Bounds3f> bounds, int axis, Edge edge) {
        var low = Float.POSITIVE_INFINITY;
        var high = Float.NEGATIVE_INFINITY;
        for (var box : bounds.values()) {
            low = Math.min(low, min(box, axis));
            high = Math.max(high, max(box, axis));
        }
        var result = new LinkedHashMap<K, Float>();
        for (var entry : bounds.entrySet()) {
            var box = entry.getValue();
            var shift = switch (edge) {
                case MIN -> low - min(box, axis);
                case MAX -> high - max(box, axis);
                case CENTER -> (low + high) * .5f - (min(box, axis) + max(box, axis)) * .5f;
            };
            result.put(entry.getKey(), shift);
        }
        return result;
    }

    public static <K> Map<K, Float> sameHeight(Map<K, Bounds3f> bounds) {
        var lowest = Float.POSITIVE_INFINITY;
        for (var box : bounds.values()) {
            lowest = Math.min(lowest, box.minZ());
        }
        var result = new LinkedHashMap<K, Float>();
        for (var entry : bounds.entrySet()) {
            result.put(entry.getKey(), lowest - entry.getValue().minZ());
        }
        return result;
    }

    public static <K> Map<K, Float> distribute(Map<K, Bounds3f> bounds, int axis) {
        var order = new ArrayList<>(bounds.entrySet());
        order.sort(
                Comparator.comparingDouble(entry -> (min(entry.getValue(), axis) + max(entry.getValue(), axis)) * .5f));
        var result = new LinkedHashMap<K, Float>();
        if (order.size() < 3) {
            order.forEach(entry -> result.put(entry.getKey(), 0f));
            return result;
        }
        var start = min(order.getFirst().getValue(), axis);
        var end = max(order.getLast().getValue(), axis);
        var sizes = 0f;
        for (var entry : order) {
            sizes += max(entry.getValue(), axis) - min(entry.getValue(), axis);
        }
        var gap = (end - start - sizes) / (order.size() - 1);
        var at = start;
        for (var index = 0; index < order.size(); index++) {
            var box = order.get(index).getValue();
            var shift = index == order.size() - 1 ? 0 : at - min(box, axis);
            result.put(order.get(index).getKey(), shift);
            at += max(box, axis) - min(box, axis) + gap;
        }
        return result;
    }

    public static <K> Map<K, ActorTransform> moved(Map<K, Float> shifts, int axis, Function<K, ActorTransform> now) {
        var result = new LinkedHashMap<K, ActorTransform>();
        shifts.forEach((key, shift) -> {
            if (Math.abs(shift) < .001f) {
                return;
            }
            var t = now.apply(key);
            var at = t.location();
            var location = new Vector3(
                    at.x() + (axis == 0 ? shift : 0),
                    at.y() + (axis == 1 ? shift : 0),
                    at.z() + (axis == 2 ? shift : 0));
            result.put(key, new ActorTransform(location, t.rotation(), t.drawScale(), t.drawScale3d(), t.prePivot()));
        });
        return result;
    }

    private static float min(Bounds3f box, int axis) {
        return switch (axis) {
            case 0 -> box.minX();
            case 1 -> box.minY();
            default -> box.minZ();
        };
    }

    private static float max(Bounds3f box, int axis) {
        return switch (axis) {
            case 0 -> box.maxX();
            case 1 -> box.maxY();
            default -> box.maxZ();
        };
    }
}
