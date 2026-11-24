package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.Objects;

public final class NativeTerrainSectorBounds {
    private NativeTerrainSectorBounds() {}

    public record Sphere(Vector center, float radius) {
        public Sphere {
            requireFinite(center);
            if (!Float.isFinite(radius) || radius < 0) {
                throw new IllegalArgumentException("Invalid native sector sphere");
            }
        }
    }

    public record Result(
            Box bounds, Sphere locationSphere, Sphere lightingSphere, int effectiveSizeX, int effectiveSizeY) {}

    public static Result compute(TerrainHeightField field, Sector sector) {
        Objects.requireNonNull(sector);
        return compute(field, sector.offsetX(), sector.offsetY(), sector.sizeX(), sector.sizeY());
    }

    public static Result compute(TerrainHeightField field, int offsetX, int offsetY, int sizeX, int sizeY) {
        Objects.requireNonNull(field);
        if (field.getWidth() != 256 || field.getHeight() != 256) {
            throw new IllegalArgumentException("Unqualified native terrain grid dimensions");
        }
        var sx = effectiveSize(offsetX, sizeX);
        var sy = effectiveSize(offsetY, sizeY);
        return computeCornerBounds(
                field.getWorldAt(offsetX, offsetY),
                field.getWorldAt(offsetX, offsetY + sy),
                field.getWorldAt(offsetX + sx, offsetY + sy),
                field.getWorldAt(offsetX + sx, offsetY),
                sx,
                sy);
    }

    public static Result fromCorners(Vector a, Vector b, Vector c, Vector d) {
        return computeCornerBounds(a, b, c, d, 0, 0);
    }

    public static Sphere lightingSphere(Box bounds) {
        Objects.requireNonNull(bounds);
        requireFinite(bounds.min());
        requireFinite(bounds.max());
        if (bounds.valid() != 1
                || bounds.min().x() > bounds.max().x()
                || bounds.min().y() > bounds.max().y()
                || bounds.min().z() > bounds.max().z()) {
            throw new IllegalArgumentException("Invalid native sector bounds");
        }
        var center = new Vector(
                (bounds.min().x() + bounds.max().x()) * 0.5f,
                (bounds.min().y() + bounds.max().y()) * 0.5f,
                (bounds.min().z() + bounds.max().z()) * 0.5f);
        requireFinite(center);
        var squared = Math.max(distanceSquared(center, bounds.min()), distanceSquared(center, bounds.max()));
        var radius = (float) (Math.sqrt((double) squared) * (double) 1.001f);
        return new Sphere(center, radius);
    }

    private static int effectiveSize(int offset, int size) {
        if (offset < 0 || offset > 240 || offset % 16 != 0 || (size != 16 && !(offset == 240 && size == 15))) {
            throw new IllegalArgumentException("Unqualified native terrain sector dimensions");
        }
        return offset == 240 ? 15 : size;
    }

    private static Result computeCornerBounds(Vector a, Vector b, Vector c, Vector d, int sx, int sy) {
        requireFinite(a);
        requireFinite(b);
        requireFinite(c);
        requireFinite(d);
        var center =
                new Vector(midpoint(a.x(), b.x(), d.x()), midpoint(a.y(), b.y(), d.y()), midpoint(a.z(), b.z(), d.z()));
        requireFinite(center);
        var location = new Sphere(center, (float) Math.sqrt((double) distanceSquared(a, center)));
        var min = a;
        var max = a;
        for (var next : new Vector[] {b, c, d}) {
            min = new Vector(
                    next.x() < min.x() ? next.x() : min.x(),
                    next.y() < min.y() ? next.y() : min.y(),
                    next.z() < min.z() ? next.z() : min.z());
            max = new Vector(
                    next.x() > max.x() ? next.x() : max.x(),
                    next.y() > max.y() ? next.y() : max.y(),
                    next.z() > max.z() ? next.z() : max.z());
        }
        var bounds = new Box(min, max, 1);
        return new Result(bounds, location, lightingSphere(bounds), sx, sy);
    }

    private static float midpoint(float a, float b, float d) {
        return (a + (b - a) * 0.5f) + (d - a) * 0.5f;
    }

    private static float distanceSquared(Vector a, Vector b) {
        var x = a.x() - b.x();
        var y = a.y() - b.y();
        var z = a.z() - b.z();
        var value = (y * y + x * x) + z * z;
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite native sector distance");
        }
        return value;
    }

    private static void requireFinite(Vector value) {
        Objects.requireNonNull(value);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException("Non-finite native sector coordinate");
        }
    }
}
