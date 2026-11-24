package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Light;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class NativeTerrainSteadyLighting {
    public static final int TERRAIN_SHADOW_TRACE_FLAGS = 0x197;

    private NativeTerrainSteadyLighting() {}

    @FunctionalInterface
    public interface WorldQuery {
        boolean clear(Vector start, Vector end, int traceFlags);
    }

    public record Owner(int actorFlags, int zoneNumber, boolean staticLighting) {
        public Owner {
            requireZone(zoneNumber);
        }
    }

    public record Source(
            int reference,
            int lightType,
            int actorFlags,
            int zoneNumber,
            boolean skyZone,
            boolean movableSun,
            int effect,
            Vector position,
            Vector direction,
            float radius,
            int cone,
            Vector color) {
        public Source {
            if (reference == 0
                    || lightType < 0
                    || lightType > 255
                    || effect < 0
                    || effect > 255
                    || cone < 0
                    || cone > 255
                    || !Float.isFinite(radius)
                    || radius < 0) {
                throw new IllegalArgumentException("Invalid native light");
            }
            requireZone(zoneNumber);
            requireFinite(position);
            requireFinite(direction);
            requireFinite(color);
            if (color.x() < 0 || color.y() < 0 || color.z() < 0) {
                throw new IllegalArgumentException("Negative native light color");
            }
        }
    }

    public record Sphere(Vector center, float radius) {
        public Sphere {
            requireFinite(center);
            if (!Float.isFinite(radius) || radius < 0) {
                throw new IllegalArgumentException("Invalid native sector sphere");
            }
        }
    }

    public record Sector(int offsetX, int offsetY, int sizeX, int sizeY, Sphere sphere) {
        public Sector {
            Objects.requireNonNull(sphere);
            if (offsetX < 0 || offsetY < 0 || sizeX < 1 || sizeX > 16 || sizeY < 1 || sizeY > 16) {
                throw new IllegalArgumentException("Invalid native terrain sector");
            }
        }
    }

    public record BaseColor(int red, int green, int blue) {
        public static final BaseColor BLACK = new BaseColor(0, 0, 0);

        public BaseColor {
            if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) {
                throw new IllegalArgumentException("Invalid base colour");
            }
        }
    }

    public record Result(List<Light> lights, List<Integer> colors, long tracedRays) {
        public Result {
            lights = List.copyOf(lights);
            colors = List.copyOf(colors);
        }
    }

    public static final class Grid {
        private final int width, height;
        private final Vector[] positions, normals;
        private final boolean[] eligible;

        private Grid(int width, int height, Vector[] positions, Vector[] normals, boolean[] eligible) {
            this.width = width;
            this.height = height;
            this.positions = positions;
            this.normals = normals;
            this.eligible = eligible;
        }

        public Grid(
                TerrainHeightField field,
                BitSet edgeTurns,
                boolean inverted,
                BitSet visibleQuads,
                BitSet ownerZoneSamples) {
            Objects.requireNonNull(field);
            Objects.requireNonNull(edgeTurns);
            Objects.requireNonNull(visibleQuads);
            Objects.requireNonNull(ownerZoneSamples);
            width = field.getWidth();
            height = field.getHeight();
            var count = Math.multiplyExact(width, height);
            if (count > 256 * 256
                    || edgeTurns.length() > count
                    || visibleQuads.length() > count
                    || ownerZoneSamples.length() > count) {
                throw new IllegalArgumentException("Unqualified terrain grid dimensions");
            }
            positions = new Vector[count];
            normals = new Vector[count];
            eligible = new boolean[count];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var index = y * width + x;
                    positions[index] = field.getWorldAt(x, y);
                    normals[index] = field.getNormalAt(x, y, edgeTurns, inverted);
                    requireFinite(positions[index]);
                    requireFinite(normals[index]);
                    var region = false;
                    for (var ny = Math.max(0, y - 1); ny <= Math.min(height - 1, y + 1); ny++) {
                        for (var nx = Math.max(0, x - 1); nx <= Math.min(width - 1, x + 1); nx++) {
                            region |= ownerZoneSamples.get(ny * width + nx);
                        }
                    }
                    eligible[index] = region
                            && (x == 0
                                    || y == 0
                                    || x == width - 1
                                    || y == height - 1
                                    || visibleQuads.get(index)
                                    || visibleQuads.get(index - 1)
                                    || visibleQuads.get(index - width)
                                    || visibleQuads.get(index - width - 1));
                }
            }
        }

        Grid shiftedFrom(int column) {
            var shiftedPositions = positions.clone();
            var shiftedNormals = normals.clone();
            for (var y = 0; y < height; y++) {
                for (var x = Math.max(1, column); x < width; x++) {
                    if (x - column + y % 16 >= 16) {
                        shiftedPositions[y * width + x] = positions[y * width + x - 1];
                        shiftedNormals[y * width + x] = normals[y * width + x - 1];
                    }
                }
            }
            return new Grid(width, height, shiftedPositions, shiftedNormals, eligible);
        }
    }

    public static boolean isAdmitted(Owner owner, Source light) {
        return isAdmitted(
                owner.actorFlags(),
                owner.zoneNumber(),
                light.lightType(),
                light.actorFlags(),
                light.zoneNumber(),
                light.skyZone(),
                light.movableSun(),
                light.effect());
    }

    static boolean isAdmitted(
            int ownerFlags,
            int ownerZone,
            int type,
            int flags,
            int zone,
            boolean skyZone,
            boolean movableSun,
            int effect) {
        return type == 1
                && (flags & 0x80000) != 0
                && (flags & 0x200000) != 0
                && (zone == ownerZone || skyZone)
                && ((flags ^ ownerFlags) & 0x20) == 0
                && !movableSun
                && effect != 19;
    }

    public static Result rebuild(
            Grid grid,
            Owner owner,
            Sector sector,
            List<Source> authoredLights,
            WorldQuery query,
            BooleanSupplier cancelled) {
        return rebuild(grid, owner, sector, authoredLights, query, BaseColor.BLACK, cancelled);
    }

    public static Result rebuild(
            Grid grid,
            Owner owner,
            Sector sector,
            List<Source> authoredLights,
            WorldQuery query,
            BaseColor base,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(base);
        Objects.requireNonNull(grid);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(query);
        Objects.requireNonNull(cancelled);
        if (sector.offsetX() + sector.sizeX() >= grid.width || sector.offsetY() + sector.sizeY() >= grid.height) {
            throw new IllegalArgumentException("Sector exceeds terrain grid");
        }
        var lights = new ArrayList<Source>();
        var identities = new HashSet<Integer>();
        for (var source : authoredLights) {
            if (!identities.add(source.reference())) {
                throw new IllegalArgumentException("Duplicate authored light reference");
            }
            if (!isAdmitted(owner, source)) {
                continue;
            }
            requireSupportedEffect(source.effect());
            var distance = subtract(source.position(), sector.sphere().center());
            var radius = source.radius() + sector.sphere().radius();
            if (dot(distance, distance) < radius * radius) {
                lights.add(source);
            }
        }
        var vertexCount = (sector.sizeX() + 1) * (sector.sizeY() + 1);
        var colors = new int[vertexCount * 4];
        for (var i = 0; i < vertexCount; i++) {
            colors[i * 4] = base.red();
            colors[i * 4 + 1] = base.green();
            colors[i * 4 + 2] = base.blue();
            colors[i * 4 + 3] = 255;
        }
        var result = new ArrayList<Light>();
        long rays = 0;
        for (var light : lights) {
            var mask = new int[(vertexCount + 7) / 8];
            var anyVisible = false;
            for (var localY = 0; localY <= sector.sizeY(); localY++) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Native terrain lighting cancelled");
                }
                for (var localX = 0; localX <= sector.sizeX(); localX++) {
                    var index = (sector.offsetY() + localY) * grid.width + sector.offsetX() + localX;
                    if (!grid.eligible[index]) {
                        continue;
                    }
                    var point = grid.positions[index];
                    var normal = grid.normals[index];
                    var start = add(point, normal);
                    var difference = subtract(light.position(), start);
                    if (!(dot(difference, difference) < light.radius() * light.radius())
                            || !(dot(normal, difference) > 0)) {
                        continue;
                    }
                    if (owner.staticLighting()) {
                        rays++;
                        if (!query.clear(start, light.position(), TERRAIN_SHADOW_TRACE_FLAGS)) {
                            continue;
                        }
                    }
                    var vertex = localY * (sector.sizeX() + 1) + localX;
                    mask[vertex >>> 3] |= 1 << (vertex & 7);
                    anyVisible = true;
                    var intensity = sampleIntensity(light, point, normal) * 0.5f;
                    var color = light.color();
                    var target = vertex * 4;
                    colors[target] = Math.min(255, colors[target] + toChannel(color.x() * intensity));
                    colors[target + 1] = Math.min(255, colors[target + 1] + toChannel(color.y() * intensity));
                    colors[target + 2] = Math.min(255, colors[target + 2] + toChannel(color.z() * intensity));
                }
            }
            if (anyVisible) {
                result.add(new Light(light.reference(), toIntegerList(mask)));
            }
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Native terrain lighting cancelled");
        }
        return new Result(result, toIntegerList(colors), rays);
    }

    static float sampleIntensity(Source light, Vector point, Vector normal) {
        requireSupportedEffect(light.effect());
        var d = subtract(light.position(), point);
        var squared = dot(d, d);
        var distance = (float) Math.sqrt(squared);
        var radius = light.radius();
        var incidence = dot(normal, d);
        if (light.effect() == 17) {
            return distance < radius ? Math.max(0f, 1f - (d.x() * d.x() + d.y() * d.y()) / (radius * radius)) * 2f : 0f;
        }
        if (!(incidence > 0) || radius <= 0 || distance > radius) {
            return 0f;
        }
        if (light.effect() == 13) {
            return distance < radius ? (float) Math.sqrt(1.02 - distance / radius) * 2f : 0f;
        }
        if (light.effect() == 20) {
            return squared < radius * radius ? (float) ((1.02 - squared / (radius * radius)) * 2.0) : 0f;
        }
        var a = distance / radius;
        var base = Math.abs(incidence / radius) * ((a * 2f * a * a - a * 3f * a + 1f) / a) * 2f;
        if (!(base > 0) || light.effect() != 8 && light.effect() != 12) {
            return Math.max(0f, base);
        }
        var sine = 1f - light.cone() / 256f;
        var reciprocal = 1f / (1f - sine);
        var directionDot = -dot(d, light.direction());
        if (!(directionDot > 0) || !(directionDot * directionDot > sine * sine * squared)) {
            return 0f;
        }
        var cone = directionDot * reciprocal / distance - reciprocal * sine;
        return cone * cone * base;
    }

    private static void requireSupportedEffect(int effect) {
        if (effect == 19) {
            throw new IllegalArgumentException("Native steady light effect is not qualified: " + effect);
        }
    }

    private static int toChannel(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite native lighting contribution");
        }
        return (int) Math.max(0, Math.min(255, Math.floor(value * 255f)));
    }

    private static List<Integer> toIntegerList(int[] values) {
        var result = new ArrayList<Integer>(values.length);
        for (var value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static Vector add(Vector a, Vector b) {
        return new Vector(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    private static Vector subtract(Vector a, Vector b) {
        return new Vector(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static float dot(Vector a, Vector b) {
        return a.y() * b.y() + a.x() * b.x() + a.z() * b.z();
    }

    private static void requireFinite(Vector vector) {
        Objects.requireNonNull(vector);
        if (!Float.isFinite(vector.x()) || !Float.isFinite(vector.y()) || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException("Non-finite native lighting vector");
        }
    }

    private static void requireZone(int zone) {
        if (zone < 0 || zone > 63) {
            throw new IllegalArgumentException("Unqualified native BSP zone");
        }
    }
}
