package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.geometry.Bounds3f;
import java.util.ArrayList;
import java.util.List;

public final class BoundsSnap {
    public static final float REACH = 8;

    public static final float NEAR = 512;

    private BoundsSnap() {}

    public record Result(float dx, float dy, float dz, List<float[]> edges) {
        public static final Result NONE = new Result(0, 0, 0, List.of());

        public boolean snapped() {
            return !edges.isEmpty();
        }
    }

    public static boolean isNear(Bounds3f moved, Bounds3f other) {
        return other.maxX() >= moved.minX() - NEAR
                && other.minX() <= moved.maxX() + NEAR
                && other.maxY() >= moved.minY() - NEAR
                && other.minY() <= moved.maxY() + NEAR
                && other.maxZ() >= moved.minZ() - NEAR
                && other.minZ() <= moved.maxZ() + NEAR;
    }

    public static Result snap(Bounds3f moved, List<Bounds3f> neighbors, boolean[] axes) {
        var best = new float[] {Float.NaN, Float.NaN, Float.NaN};
        var edges = new float[3][];
        for (var other : neighbors) {
            for (var axis = 0; axis < 3; axis++) {
                if (!axes[axis] || !overlaps(moved, other, axis)) {
                    continue;
                }
                var shifts = axis == 2
                        ? new float[] {max(other, 2) - min(moved, 2)}
                        : new float[] {max(other, axis) - min(moved, axis), min(other, axis) - max(moved, axis)};
                for (var shift : shifts) {
                    if (Math.abs(shift) <= REACH
                            && (Float.isNaN(best[axis]) || Math.abs(shift) < Math.abs(best[axis]))) {
                        best[axis] = shift;
                        edges[axis] = edge(moved, other, axis, shift);
                    }
                }
            }
        }
        var found = new ArrayList<float[]>();
        for (var edge : edges) {
            if (edge != null) {
                found.add(edge);
            }
        }
        if (found.isEmpty()) {
            return Result.NONE;
        }
        return new Result(
                Float.isNaN(best[0]) ? 0 : best[0],
                Float.isNaN(best[1]) ? 0 : best[1],
                Float.isNaN(best[2]) ? 0 : best[2],
                List.copyOf(found));
    }

    private static boolean overlaps(Bounds3f a, Bounds3f b, int axis) {
        for (var other = 0; other < 3; other++) {
            if (other != axis && (min(a, other) >= max(b, other) || max(a, other) <= min(b, other))) {
                return false;
            }
        }
        return true;
    }

    private static float[] edge(Bounds3f moved, Bounds3f other, int axis, float shift) {
        if (axis == 2) {
            var z = max(other, 2);
            var y = (Math.max(moved.minY(), other.minY()) + Math.min(moved.maxY(), other.maxY())) * .5f;
            return new float[] {Math.max(moved.minX(), other.minX()), y, z, Math.min(moved.maxX(), other.maxX()), y, z};
        }
        var at = shift == max(other, axis) - min(moved, axis) ? max(other, axis) : min(other, axis);
        var across = axis == 0 ? 1 : 0;
        var z = Math.max(moved.minZ(), other.minZ());
        var from = new float[3];
        var to = new float[3];
        from[axis] = to[axis] = at;
        from[across] = Math.max(min(moved, across), min(other, across));
        to[across] = Math.min(max(moved, across), max(other, across));
        from[2] = to[2] = z;
        return new float[] {from[0], from[1], from[2], to[0], to[1], to[2]};
    }

    private static float min(Bounds3f b, int axis) {
        return switch (axis) {
            case 0 -> b.minX();
            case 1 -> b.minY();
            default -> b.minZ();
        };
    }

    private static float max(Bounds3f b, int axis) {
        return switch (axis) {
            case 0 -> b.maxX();
            case 1 -> b.maxY();
            default -> b.maxZ();
        };
    }
}
