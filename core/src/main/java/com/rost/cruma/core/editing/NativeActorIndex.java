package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.Objects;

public final class NativeActorIndex {
    private static final int LAST_CELL = 0x3fff;
    private static final float WORLD_BOUND = 360448f;

    private NativeActorIndex() {}

    public record Cell(int x, int y, int z) {}

    public enum HashEnd {
        BOX_CELL,
        END_CELL,
        LEFT_GRID
    }

    public record HashVisit(HashEnd end, int visitedCells, Cell lastCell) {}

    public record OctreeBox(Box expanded, Vector center, Vector extent, boolean fullyInsideRoot) {}

    public static int toCell(float coordinate) {
        requireFinite(coordinate);
        var scaled = (float) (((double) coordinate + 360448d) * 0.00390625d);
        var rounded = Math.floor((double) scaled + 0.5d);
        if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Hash coordinate exceeds native integer conversion range");
        }
        return Math.clamp((int) rounded, 0, LAST_CELL);
    }

    public static Cell toCell(Vector position) {
        requireFinite(position);
        return new Cell(toCell(position.x()), toCell(position.y()), toCell(position.z()));
    }

    public static HashVisit hashVisit(Box primitiveBox, Vector start, Vector end) {
        requireValid(primitiveBox);
        requireFinite(start);
        requireFinite(end);
        var minimum = toCell(primitiveBox.min());
        var maximum = toCell(primitiveBox.max());
        var current = toCell(start);
        var finish = toCell(end);
        var direction = unitDirection(new Vector(end.x() - start.x(), end.y() - start.y(), end.z() - start.z()));
        var x = current.x();
        var y = current.y();
        var z = current.z();
        var visited = 0;
        while (x >= 0 && x <= LAST_CELL && y >= 0 && y <= LAST_CELL && z >= 0 && z <= LAST_CELL) {
            visited++;
            if (x >= minimum.x()
                    && x <= maximum.x()
                    && y >= minimum.y()
                    && y <= maximum.y()
                    && z >= minimum.z()
                    && z <= maximum.z()) {
                return new HashVisit(HashEnd.BOX_CELL, visited, new Cell(x, y, z));
            }
            if (x == finish.x() && y == finish.y() && z == finish.z()) {
                return new HashVisit(HashEnd.END_CELL, visited, new Cell(x, y, z));
            }
            var tx = nextBoundary(x, start.x(), direction.x());
            var ty = nextBoundary(y, start.y(), direction.y());
            var tz = nextBoundary(z, start.z(), direction.z());
            if (ty < tx || tz < tx) {
                if (tx < ty || tz < ty) {
                    z += direction.z() < 0 ? -1 : 1;
                } else {
                    y += direction.y() < 0 ? -1 : 1;
                }
            } else {
                x += direction.x() < 0 ? -1 : 1;
            }
        }
        return new HashVisit(HashEnd.LEFT_GRID, visited, new Cell(x, y, z));
    }

    public static OctreeBox octreeBox(Box primitiveBox) {
        requireValid(primitiveBox);
        var low = primitiveBox.min();
        var high = primitiveBox.max();
        var expanded = new Box(
                new Vector(low.x() - 4.2f, low.y() - 4.2f, low.z() - 4.2f),
                new Vector(high.x() + 4.2f, high.y() + 4.2f, high.z() + 4.2f),
                1);
        requireValid(expanded);
        low = expanded.min();
        high = expanded.max();
        var extent = new Vector((high.x() - low.x()) * 0.5f, (high.y() - low.y()) * 0.5f, (high.z() - low.z()) * 0.5f);
        var center = new Vector(low.x() + extent.x(), low.y() + extent.y(), low.z() + extent.z());
        requireFinite(extent);
        requireFinite(center);
        return new OctreeBox(expanded, center, extent, isInsideRoot(low) && isInsideRoot(high));
    }

    public static boolean doesOctreeBoxIntersect(Box primitiveBox, Vector start, Vector end) {
        var box = octreeBox(primitiveBox);
        requireFinite(start);
        requireFinite(end);
        var enter = 0f;
        var exit = 1f;
        for (var axis = 0; axis < 3; axis++) {
            var origin = component(start, axis);
            var delta = component(end, axis) - origin;
            requireFinite(delta);
            var relative = origin - component(box.center(), axis);
            var extent = component(box.extent(), axis);
            requireFinite(relative);
            if (delta == 0f) {
                if (extent < Math.abs(relative)) {
                    return false;
                }
            } else {
                var inverse = 1f / delta;
                var position = inverse * relative;
                var radius = Math.abs(inverse) * extent;
                requireFinite(position);
                requireFinite(radius);
                var low = -position - radius;
                var high = radius - position;
                if (low > enter) {
                    enter = low;
                }
                if (exit > high) {
                    exit = high;
                }
                if (exit < enter) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isInsideRoot(Vector position) {
        requireFinite(position);
        return position.x() >= -WORLD_BOUND
                && position.x() <= WORLD_BOUND
                && position.y() >= -WORLD_BOUND
                && position.y() <= WORLD_BOUND
                && position.z() >= -WORLD_BOUND
                && position.z() <= WORLD_BOUND;
    }

    static float nextBoundary(int cell, float origin, float direction) {
        requireFinite(origin);
        requireFinite(direction);
        if (cell < 0 || cell > LAST_CELL) {
            throw new IllegalArgumentException("Invalid native hash cell");
        }
        if (direction == 0f) {
            return 256000f;
        }
        var boundary = (cell + (direction > 0 ? 0.5f : -0.5f)) * 256f;
        boundary = (float) ((double) boundary - 360448d);
        return (boundary - origin) / direction;
    }

    static Vector unitDirection(Vector delta) {
        requireFinite(delta);
        var square = delta.x() * delta.x() + delta.y() * delta.y() + delta.z() * delta.z();
        requireFinite(square);
        if (square < 1.0e-8) {
            return new Vector(0, 0, 0);
        }
        var reciprocal = 1f / (float) Math.sqrt(square);
        return new Vector(delta.x() * reciprocal, delta.y() * reciprocal, delta.z() * reciprocal);
    }

    private static float component(Vector vector, int axis) {
        return axis == 0 ? vector.x() : axis == 1 ? vector.y() : vector.z();
    }

    private static void requireValid(Box box) {
        Objects.requireNonNull(box);
        requireFinite(box.min());
        requireFinite(box.max());
        if (box.valid() != 1
                || box.min().x() > box.max().x()
                || box.min().y() > box.max().y()
                || box.min().z() > box.max().z()) {
            throw new IllegalArgumentException("Invalid native actor index box");
        }
    }

    private static void requireFinite(Vector value) {
        Objects.requireNonNull(value);
        requireFinite(value.x());
        requireFinite(value.y());
        requireFinite(value.z());
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite native actor index input or arithmetic");
        }
    }
}
