package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainBrushes.Changes;
import com.rost.cruma.core.editing.TerrainBrushes.Shape;
import com.rost.cruma.core.editing.TerrainG16Codec.Mip;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class TerrainHeightField {
    private final int width, height;
    private final short[] samples;
    private final Coords toWorld;
    private @Nullable BitSet quadVisibility;
    private @Nullable BitSet quadTurns;
    private OutsideHeights outside = OutsideHeights.NONE;

    public TerrainHeightField(Mip mip, Coords toWorld) {
        width = mip.width();
        height = mip.height();
        if (width < 4
                || height < 4
                || width > 4096
                || height > 4096
                || mip.heights().size() != width * height) {
            throw new IllegalArgumentException("Invalid terrain heightfield");
        }
        samples = new short[width * height];
        for (var i = 0; i < samples.length; i++) {
            var value = mip.heights().get(i);
            if (value < 0 || value > 65535) {
                throw new IllegalArgumentException("Terrain height is outside uint16");
            }
            samples[i] = (short) (int) value;
        }
        this.toWorld = Objects.requireNonNull(toWorld);
    }

    public TerrainHeightField(int width, int height, short[] samples, Coords toWorld) {
        if (width < 4 || height < 4 || width > 4096 || height > 4096 || samples.length != width * height) {
            throw new IllegalArgumentException("Invalid terrain heightfield");
        }
        this.width = width;
        this.height = height;
        this.samples = samples.clone();
        this.toWorld = Objects.requireNonNull(toWorld);
    }

    public TerrainHeightField withQuads(BitSet visible, BitSet turns) {
        var copy = new TerrainHeightField(width, height, samples, toWorld);
        copy.quadVisibility = (BitSet) visible.clone();
        copy.quadTurns = (BitSet) turns.clone();
        copy.outside = outside;
        return copy;
    }

    @FunctionalInterface
    public interface OutsideHeights {
        OutsideHeights NONE = (x, y) -> Double.NaN;

        static OutsideHeights firstKnown(List<OutsideHeights> sources) {
            var copy = List.copyOf(sources);
            return (x, y) -> {
                for (var source : copy) {
                    var value = source.getRawHeight(x, y);
                    if (!Double.isNaN(value)) {
                        return value;
                    }
                }
                return Double.NaN;
            };
        }

        double getRawHeight(int x, int y);
    }

    public void setOutsideHeights(@Nullable OutsideHeights heights) {
        outside = heights == null ? OutsideHeights.NONE : heights;
    }

    @Nullable
    Vector getOutsideWorldAt(int x, int y) {
        var raw = outside.getRawHeight(x, y);
        if (Double.isNaN(raw)) {
            return null;
        }
        return toWorld.transform(x, y, (float) raw);
    }

    public @Nullable BitSet getQuadVisibility() {
        return quadVisibility == null ? null : (BitSet) quadVisibility.clone();
    }

    public @Nullable BitSet getQuadTurns() {
        return quadTurns == null ? null : (BitSet) quadTurns.clone();
    }

    public enum Brush {
        RAISE,
        LOWER,
        FLATTEN,
        SMOOTH,
        NOISE,
        SET_HEIGHT
    }

    public record SectorCoordinate(int x, int y) {}

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getHeightAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException();
        }
        return Short.toUnsignedInt(samples[y * width + x]);
    }

    public Vector getWorldAt(int x, int y) {
        return toWorld.transform(x, y, getHeightAt(x, y));
    }

    public short[] copySamples() {
        return samples.clone();
    }

    public TerrainHeightPatch getChangesSince(short[] baseline) {
        if (baseline.length != samples.length) {
            throw new IllegalArgumentException("Height checkpoint grid differs");
        }
        var count = 0;
        for (var i = 0; i < samples.length; i++) {
            if (baseline[i] != samples[i]) {
                count++;
            }
        }
        var indices = new int[count];
        var before = new short[count];
        var after = new short[count];
        for (int i = 0, next = 0; i < samples.length; i++) {
            if (baseline[i] != samples[i]) {
                indices[next] = i;
                before[next] = baseline[i];
                after[next++] = samples[i];
            }
        }
        return new TerrainHeightPatch(width, height, indices, before, after);
    }

    public void apply(TerrainHeightPatch patch, boolean undo) {
        if (patch.getWidth() != width || patch.getHeight() != height) {
            throw new IllegalArgumentException("Height patch grid differs");
        }
        patch.apply(samples, undo);
    }

    public TerrainHeightPatch computeBrushPatch(
            Brush brush, float centerX, float centerY, float radius, float strength, int targetHeight) {
        return computeBrushPatch(Shape.of(brush, radius, targetHeight), centerX, centerY, strength, null);
    }

    public TerrainHeightPatch computeBrushPatch(
            Shape shape, float centerX, float centerY, float strength, short @Nullable [] base) {
        Objects.requireNonNull(shape);
        var brush = shape.brush();
        var radius = shape.radius();
        var targetHeight = shape.target();
        if (!Float.isFinite(centerX)
                || !Float.isFinite(centerY)
                || !Float.isFinite(radius)
                || !Float.isFinite(strength)
                || radius <= 0
                || radius > 256
                || strength < 0
                || strength > 65535
                || brush != Brush.RAISE && brush != Brush.LOWER && strength > 1
                || base != null && base.length != samples.length) {
            throw new IllegalArgumentException("Invalid terrain brush");
        }
        var changes = new Changes();
        var minX = Math.max(0, (int) Math.floor(centerX - radius));
        var maxX = Math.min(width - 1, (int) Math.ceil(centerX + radius));
        var minY = Math.max(0, (int) Math.floor(centerY - radius));
        var maxY = Math.min(height - 1, (int) Math.ceil(centerY + radius));
        for (var y = minY; y <= maxY; y++) {
            for (var x = minX; x <= maxX; x++) {
                var dx = x - centerX;
                var dy = y - centerY;
                var distance = Math.sqrt(dx * dx + dy * dy);
                if (distance >= radius) {
                    continue;
                }
                var weight = shape.falloff().getWeight(1 - distance / radius);
                var old = getHeightAt(x, y);
                var value = switch (brush) {
                    case RAISE -> old + strength * weight;
                    case LOWER -> old - strength * weight;
                    case FLATTEN -> old + (targetHeight - old) * strength * weight;
                    case SET_HEIGHT -> targetHeight;
                    case SMOOTH -> old + (averageAround(x, y) - old) * strength * weight;
                    case NOISE -> {
                        var start = base == null ? old : Short.toUnsignedInt(base[y * width + x]);
                        var aim = start
                                + shape.amplitude()
                                        * TerrainBrushes.valueNoise(
                                                shape.seed(),
                                                (x + shape.noiseX()) / shape.scale(),
                                                (y + shape.noiseY()) / shape.scale());
                        yield old + (aim - old) * strength * weight;
                    }
                };
                changes.add(this, x, y, old, value);
            }
        }
        return changes.createPatch(this);
    }

    public Vector intersect(Vector origin, Vector direction) {
        var dx = direction.x();
        var dy = direction.y();
        var dz = direction.z();
        var near = 0.0;
        var far = Double.MAX_VALUE;
        var low = new double[] {0, 0, 0};
        var high = new double[] {width - 1, height - 1, 65535};
        var start = new double[] {origin.x(), origin.y(), origin.z()};
        var step = new double[] {dx, dy, dz};
        for (var axis = 0; axis < 3; axis++) {
            if (Math.abs(step[axis]) < 1e-12) {
                if (start[axis] < low[axis] || start[axis] > high[axis]) {
                    return null;
                }
                continue;
            }
            var a = (low[axis] - start[axis]) / step[axis];
            var b = (high[axis] - start[axis]) / step[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
        }
        if (!(near <= far) || far == Double.MAX_VALUE) {
            return null;
        }
        var planar = Math.max(Math.abs(dx), Math.abs(dy));
        var dt = planar < 1e-9 ? far - near : Math.max(1e-6, .25 / planar);
        var previous = near;
        var above = isOriginAbove(origin, direction, near);
        for (var t = near; t <= far + dt; t += dt) {
            var clamped = Math.min(t, far);
            var nowAbove = isOriginAbove(origin, direction, clamped);
            if (above && !nowAbove) {
                var lowT = previous;
                var highT = clamped;
                for (var i = 0; i < 24; i++) {
                    var middle = (lowT + highT) / 2;
                    if (isOriginAbove(origin, direction, middle)) {
                        lowT = middle;
                    } else {
                        highT = middle;
                    }
                }
                return new Vector((float) (origin.x() + dx * highT), (float) (origin.y() + dy * highT), (float)
                        (origin.z() + dz * highT));
            }
            if (!above && nowAbove) {
                above = true;
            }
            previous = clamped;
            if (clamped == far) {
                break;
            }
        }
        return null;
    }

    public float getWorldZ(double x, double y) {
        return toWorld.transform((float) x, (float) y, (float) getSurfaceAt(x, y))
                .z();
    }

    public double getSurfaceAt(double x, double y) {
        var x0 = (int) Math.min(width - 2, Math.max(0, Math.floor(x)));
        var y0 = (int) Math.min(height - 2, Math.max(0, Math.floor(y)));
        var fx = x - x0;
        var fy = y - y0;
        var a = getHeightAt(x0, y0);
        var b = getHeightAt(x0 + 1, y0);
        var c = getHeightAt(x0, y0 + 1);
        var d = getHeightAt(x0 + 1, y0 + 1);
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    public Set<SectorCoordinate> getAffectedSectors(TerrainHeightPatch patch) {
        if (patch.getWidth() != width || patch.getHeight() != height) {
            throw new IllegalArgumentException("Height patch grid differs");
        }
        var sectors = new LinkedHashSet<SectorCoordinate>();
        for (var i = 0; i < patch.size(); i++) {
            var x = patch.getIndex(i) % width;
            var y = patch.getIndex(i) / width;
            for (var qy = Math.max(0, y - 2); qy <= Math.min(height - 2, y + 1); qy++) {
                for (var qx = Math.max(0, x - 2); qx <= Math.min(width - 2, x + 1); qx++) {
                    sectors.add(new SectorCoordinate(qx / 16, qy / 16));
                }
            }
        }
        return Set.copyOf(sectors);
    }

    public Box getBounds(int offsetX, int offsetY, int sizeX, int sizeY) {
        if (offsetX < 0
                || offsetY < 0
                || sizeX < 1
                || sizeY < 1
                || offsetX + sizeX >= width
                || offsetY + sizeY >= height) {
            throw new IllegalArgumentException("Sector exceeds terrain grid");
        }
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var y = offsetY; y <= offsetY + sizeY; y++) {
            for (var x = offsetX; x <= offsetX + sizeX; x++) {
                var p = getWorldAt(x, y);
                if (!Float.isFinite(p.x()) || !Float.isFinite(p.y()) || !Float.isFinite(p.z())) {
                    throw new IllegalStateException("Non-finite terrain world bounds");
                }
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
                maxZ = Math.max(maxZ, p.z());
            }
        }
        return new Box(new Vector(minX, minY, minZ), new Vector(maxX, maxY, maxZ), 1);
    }

    public Vector getNormalAt(int x, int y, BitSet edgeTurns, boolean inverted) {
        getHeightAt(x, y);
        var sum = new float[3];
        var pair = new float[6];
        addQuadNormals(sum, pair, x, y, edgeTurns);
        addQuadNormals(sum, pair, x - 1, y, edgeTurns);
        addQuadNormals(sum, pair, x, y - 1, edgeTurns);
        addQuadNormals(sum, pair, x - 1, y - 1, edgeTurns);
        safeNormal(sum, 0);
        return inverted ? new Vector(-sum[0], -sum[1], -sum[2]) : new Vector(sum[0], sum[1], sum[2]);
    }

    public float[] getFaceNormals(int x, int y, BitSet edgeTurns) {
        if (x < 0 || y < 0 || x >= width - 1 || y >= height - 1) {
            throw new IndexOutOfBoundsException("Quad lies outside the terrain grid");
        }
        var pair = new float[6];
        computeQuadNormals(pair, x, y, edgeTurns);
        return pair;
    }

    private double averageAround(int x, int y) {
        if (x > 0 && y > 0 && x < width - 1 && y < height - 1) {
            long sum = 0;
            for (var sy = y - 1; sy <= y + 1; sy++) {
                for (var sx = x - 1; sx <= x + 1; sx++) {
                    sum += getHeightAt(sx, sy);
                }
            }
            return sum / 9.0;
        }
        var sum = 0.0;
        var count = 0;
        for (var sy = y - 1; sy <= y + 1; sy++) {
            for (var sx = x - 1; sx <= x + 1; sx++) {
                if (sx >= 0 && sy >= 0 && sx < width && sy < height) {
                    sum += getHeightAt(sx, sy);
                    count++;
                    continue;
                }
                var raw = outside.getRawHeight(sx, sy);
                if (!Double.isNaN(raw)) {
                    sum += raw;
                    count++;
                }
            }
        }
        return sum / count;
    }

    private boolean isOriginAbove(Vector origin, Vector direction, double t) {
        var x = Math.max(0, Math.min(width - 1, origin.x() + direction.x() * t));
        var y = Math.max(0, Math.min(height - 1, origin.y() + direction.y() * t));
        var z = origin.z() + direction.z() * t;
        return z > getSurfaceAt(x, y);
    }

    private void addQuadNormals(float[] sum, float[] pair, int x, int y, BitSet edgeTurns) {
        if (x < 0 || y < 0 || x >= width - 1 || y >= height - 1) {
            return;
        }
        computeQuadNormals(pair, x, y, edgeTurns);
        sum[0] += pair[0] + pair[3];
        sum[1] += pair[1] + pair[4];
        sum[2] += pair[2] + pair[5];
    }

    private void computeQuadNormals(float[] pair, int x, int y, BitSet edgeTurns) {
        var a = getWorldAt(x, y);
        var b = getWorldAt(x + 1, y);
        var c = getWorldAt(x, y + 1);
        var d = getWorldAt(x + 1, y + 1);
        if (edgeTurns.get(y * width + x)) {
            computeTriangleNormal(pair, 0, a, b, c);
            computeTriangleNormal(pair, 3, c, b, d);
        } else {
            computeTriangleNormal(pair, 0, a, b, d);
            computeTriangleNormal(pair, 3, a, d, c);
        }
    }

    private static void computeTriangleNormal(float[] target, int offset, Vector a, Vector b, Vector c) {
        var abx = b.x() - a.x();
        var aby = b.y() - a.y();
        var abz = b.z() - a.z();
        var acx = c.x() - a.x();
        var acy = c.y() - a.y();
        var acz = c.z() - a.z();
        target[offset] = aby * acz - abz * acy;
        target[offset + 1] = abz * acx - abx * acz;
        target[offset + 2] = abx * acy - aby * acx;
        safeNormal(target, offset);
        safeNormal(target, offset);
    }

    private static void safeNormal(float[] value, int offset) {
        var x = value[offset];
        var y = value[offset + 1];
        var z = value[offset + 2];
        var square = x * x + y * y + z * z;
        if (square < 1.0e-8) {
            value[offset] = value[offset + 1] = value[offset + 2] = 0f;
        } else {
            var reciprocal = 1f / (float) Math.sqrt(square);
            value[offset] = x * reciprocal;
            value[offset + 1] = y * reciprocal;
            value[offset + 2] = z * reciprocal;
        }
    }
}
