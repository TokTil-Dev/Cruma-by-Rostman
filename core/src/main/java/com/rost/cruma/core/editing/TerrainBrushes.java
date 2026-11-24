package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainHeightField.Brush;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Objects;

public final class TerrainBrushes {
    private static final long MIX_X = 0x9E3779B97F4A7C15L;
    private static final long MIX_Y = 0xC2B2AE3D27D4EB4FL;

    private TerrainBrushes() {}

    public enum Falloff {
        SMOOTH,
        LINEAR,
        SHARP;

        public double getWeight(double t) {
            if (t <= 0) {
                return 0;
            }
            if (t >= 1) {
                return 1;
            }
            return switch (this) {
                case SMOOTH -> t * t * (3 - 2 * t);
                case LINEAR -> t;
                case SHARP -> t * t;
            };
        }
    }

    public record Shape(
            Brush brush,
            float radius,
            int target,
            Falloff falloff,
            long seed,
            float amplitude,
            float scale,
            float noiseX,
            float noiseY) {
        public Shape {
            Objects.requireNonNull(brush);
            Objects.requireNonNull(falloff);
            if (!Float.isFinite(amplitude)
                    || !Float.isFinite(scale)
                    || scale <= 0
                    || !Float.isFinite(noiseX)
                    || !Float.isFinite(noiseY)) {
                throw new IllegalArgumentException("Invalid Terrain noise");
            }
        }

        public Shape(Brush brush, float radius, int target, Falloff falloff, long seed, float amplitude, float scale) {
            this(brush, radius, target, falloff, seed, amplitude, scale, 0, 0);
        }

        public static Shape of(Brush brush, float radius, int target) {
            return new Shape(brush, radius, target, Falloff.SMOOTH, 0, 0, 1);
        }

        public Shape withNoiseOffset(float noiseX, float noiseY) {
            return new Shape(brush, radius, target, falloff, seed, amplitude, scale, noiseX, noiseY);
        }
    }

    public record RampPoint(float x, float y, int height) {}

    public static double valueNoise(long seed, double x, double y) {
        var coarse = lattice(seed, x, y);
        var fine = lattice(seed ^ 0x5DEECE66DL, x * 2 + 17.3, y * 2 - 9.1);
        return (coarse + fine * .5) / 1.5;
    }

    public static TerrainHeightPatch ramp(
            TerrainHeightField field, RampPoint a, RampPoint b, float width, Falloff falloff) {
        Objects.requireNonNull(falloff);
        if (!Float.isFinite(width)
                || width <= 0
                || width > 512
                || !Float.isFinite(a.x())
                || !Float.isFinite(a.y())
                || !Float.isFinite(b.x())
                || !Float.isFinite(b.y())) {
            throw new IllegalArgumentException("Invalid Terrain ramp");
        }
        var half = width * .5;
        var edge = Math.max(1, width * .25);
        var reach = half + edge;
        var minX = Math.max(0, (int) Math.floor(Math.min(a.x(), b.x()) - reach));
        var maxX = Math.min(field.getWidth() - 1, (int) Math.ceil(Math.max(a.x(), b.x()) + reach));
        var minY = Math.max(0, (int) Math.floor(Math.min(a.y(), b.y()) - reach));
        var maxY = Math.min(field.getHeight() - 1, (int) Math.ceil(Math.max(a.y(), b.y()) + reach));
        var dx = b.x() - a.x();
        var dy = b.y() - a.y();
        var length = dx * dx + dy * dy;
        var changes = new Changes();
        for (var y = minY; y <= maxY; y++) {
            for (var x = minX; x <= maxX; x++) {
                var t = length < 1e-9 ? 0 : Math.clamp(((x - a.x()) * dx + (y - a.y()) * dy) / length, 0, 1);
                var px = a.x() + dx * t;
                var py = a.y() + dy * t;
                var distance = Math.hypot(x - px, y - py);
                if (distance >= reach) {
                    continue;
                }
                var weight = distance <= half ? 1 : falloff.getWeight(1 - (distance - half) / edge);
                var old = field.getHeightAt(x, y);
                var line = a.height() + (b.height() - a.height()) * t;
                changes.add(field, x, y, old, old + (line - old) * weight);
            }
        }
        return changes.createPatch(field);
    }

    public static BitSet slopeMask(TerrainHeightField field, int texelsWide, int texelsHigh, float degrees) {
        var slopes = slopes(field);
        var mask = new BitSet(texelsWide * texelsHigh);
        var scaleX = texelsWide / (double) field.getWidth();
        var scaleY = texelsHigh / (double) field.getHeight();
        for (var ty = 0; ty < texelsHigh; ty++) {
            var sy = (int) Math.clamp(Math.round((ty + .5) / scaleY - .5), 0, field.getHeight() - 1);
            for (var tx = 0; tx < texelsWide; tx++) {
                var sx = (int) Math.clamp(Math.round((tx + .5) / scaleX - .5), 0, field.getWidth() - 1);
                if (slopes[sy * field.getWidth() + sx] > degrees) {
                    mask.set(ty * texelsWide + tx);
                }
            }
        }
        return mask;
    }

    public static BitSet heightMask(TerrainHeightField field, int texelsWide, int texelsHigh, float fromZ, float toZ) {
        var low = Math.min(fromZ, toZ);
        var high = Math.max(fromZ, toZ);
        var mask = new BitSet(texelsWide * texelsHigh);
        var scaleX = texelsWide / (double) field.getWidth();
        var scaleY = texelsHigh / (double) field.getHeight();
        for (var ty = 0; ty < texelsHigh; ty++) {
            var sy = Math.clamp((ty + .5) / scaleY - .5, 0, field.getHeight() - 1);
            for (var tx = 0; tx < texelsWide; tx++) {
                var sx = Math.clamp((tx + .5) / scaleX - .5, 0, field.getWidth() - 1);
                var z = field.getWorldZ(sx, sy);
                if (z >= low && z <= high) {
                    mask.set(ty * texelsWide + tx);
                }
            }
        }
        return mask;
    }

    public static float[] slopes(TerrainHeightField field) {
        var width = field.getWidth();
        var height = field.getHeight();
        var result = new float[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var left = neighbor(field, x - 1, y, x, y);
                var right = neighbor(field, x + 1, y, x, y);
                var down = neighbor(field, x, y - 1, x, y);
                var up = neighbor(field, x, y + 1, x, y);
                double ax = right.x() - left.x(), ay = right.y() - left.y(), az = right.z() - left.z();
                double bx = up.x() - down.x(), by = up.y() - down.y(), bz = up.z() - down.z();
                var nx = ay * bz - az * by;
                var ny = az * bx - ax * bz;
                var nz = ax * by - ay * bx;
                var length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                result[y * width + x] =
                        length < 1e-9 ? 0 : (float) Math.toDegrees(Math.acos(Math.min(1, Math.abs(nz) / length)));
            }
        }
        return result;
    }

    private static Vector neighbor(TerrainHeightField field, int x, int y, int fromX, int fromY) {
        if (x >= 0 && y >= 0 && x < field.getWidth() && y < field.getHeight()) {
            return field.getWorldAt(x, y);
        }
        var outside = field.getOutsideWorldAt(x, y);
        return outside != null ? outside : field.getWorldAt(fromX, fromY);
    }

    private static double lattice(long seed, double x, double y) {
        var x0 = Math.floor(x);
        var y0 = Math.floor(y);
        var fx = x - x0;
        var fy = y - y0;
        var ix = (long) x0;
        var iy = (long) y0;
        var a = hash(seed, ix, iy);
        var b = hash(seed, ix + 1, iy);
        var c = hash(seed, ix, iy + 1);
        var d = hash(seed, ix + 1, iy + 1);
        var sx = fx * fx * (3 - 2 * fx);
        var sy = fy * fy * (3 - 2 * fy);
        return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy;
    }

    private static double hash(long seed, long x, long y) {
        var h = seed ^ x * MIX_X ^ y * MIX_Y;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) / (double) (1L << 53) * 2 - 1;
    }

    static final class Changes {
        private final ArrayList<Integer> indices = new ArrayList<>();
        private final ArrayList<Short> before = new ArrayList<>();
        private final ArrayList<Short> after = new ArrayList<>();

        void add(TerrainHeightField field, int x, int y, int old, double value) {
            var next = (int) Math.max(0, Math.min(65535, Math.round(value)));
            if (next != old) {
                indices.add(y * field.getWidth() + x);
                before.add((short) old);
                after.add((short) next);
            }
        }

        TerrainHeightPatch createPatch(TerrainHeightField field) {
            var packedIndices = new int[indices.size()];
            var packedBefore = new short[indices.size()];
            var packedAfter = new short[indices.size()];
            for (var i = 0; i < indices.size(); i++) {
                packedIndices[i] = indices.get(i);
                packedBefore[i] = before.get(i);
                packedAfter[i] = after.get(i);
            }
            return new TerrainHeightPatch(
                    field.getWidth(), field.getHeight(), packedIndices, packedBefore, packedAfter);
        }
    }
}
