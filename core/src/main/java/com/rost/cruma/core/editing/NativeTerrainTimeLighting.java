package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.WorldQuery;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.env.EnvironmentSun;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class NativeTerrainTimeLighting {
    public static final int TERRAIN_SHADOW_TRACE_FLAGS = 0x197;
    static final float RAY_LENGTH = 720896f;

    private NativeTerrainTimeLighting() {}

    public record Owner(int actorFlags, int zoneNumber) {
        public Owner {
            requireZone(zoneNumber);
        }
    }

    public record Source(int reference, int lightType, int actorFlags, int zoneNumber, boolean movableSun, int effect) {
        public Source {
            if (reference <= 0 || lightType < 0 || lightType > 255 || effect < 0 || effect > 255) {
                throw new IllegalArgumentException("Invalid native sunlight source");
            }
            requireZone(zoneNumber);
        }
    }

    public record Slot(float time, Vector safeDirection) {
        public Slot {
            if (!Float.isFinite(time) || time < 0 || time >= 24) {
                throw new IllegalArgumentException("Invalid native sunlight time");
            }
            requireFinite(safeDirection);
            var square = safeDirection.x() * safeDirection.x()
                    + safeDirection.y() * safeDirection.y()
                    + safeDirection.z() * safeDirection.z();
            if (!Float.isFinite(square) || square == 0) {
                throw new IllegalArgumentException("Invalid native sunlight direction");
            }
        }
    }

    public record Window(int offsetX, int offsetY, int width, int height) {
        public Window {
            if (offsetX < 0 || offsetY < 0 || width < 1 || width > 17 || height < 1 || height > 17) {
                throw new IllegalArgumentException("Invalid native sunlight window");
            }
        }
    }

    public record Frame(float time, List<Integer> intensities, List<Integer> visibility) {
        public Frame {
            intensities = List.copyOf(intensities);
            visibility = List.copyOf(visibility);
        }
    }

    public record Result(Window window, int sunReference, List<Frame> frames, long tracedRays) {
        public Result {
            Objects.requireNonNull(window);
            frames = List.copyOf(frames);
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

        public Grid(TerrainHeightField field, BitSet edgeTurns, boolean inverted, BitSet visibleQuads) {
            this(field, edgeTurns, inverted, visibleQuads, () -> false);
        }

        public Grid(
                TerrainHeightField field,
                BitSet edgeTurns,
                boolean inverted,
                BitSet visibleQuads,
                BooleanSupplier cancelled) {
            Objects.requireNonNull(field);
            Objects.requireNonNull(edgeTurns);
            Objects.requireNonNull(visibleQuads);
            Objects.requireNonNull(cancelled);
            width = field.getWidth();
            height = field.getHeight();
            var count = Math.multiplyExact(width, height);
            if (width > 256 || height > 256 || edgeTurns.length() > count || visibleQuads.length() > count) {
                throw new IllegalArgumentException("Unqualified sunlight grid dimensions");
            }
            positions = new Vector[count];
            normals = new Vector[count];
            eligible = new boolean[count];
            for (var y = 0; y < height; y++) {
                checkCancelled(cancelled);
                for (var x = 0; x < width; x++) {
                    var index = y * width + x;
                    positions[index] = field.getWorldAt(x, y);
                    normals[index] = field.getNormalAt(x, y, edgeTurns, inverted);
                    requireFinite(positions[index]);
                    requireFinite(normals[index]);
                    eligible[index] = x == 0
                            || y == 0
                            || x == width - 1
                            || y == height - 1
                            || visibleQuads.get(index)
                            || visibleQuads.get(index - 1)
                            || visibleQuads.get(index - width)
                            || visibleQuads.get(index - width - 1);
                }
            }
            checkCancelled(cancelled);
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

    public static Vector safeDirection(float time, float longitude, float slope, boolean reverseSlope) {
        if (!Float.isFinite(time) || time < 0 || time >= 24 || !Float.isFinite(longitude) || !Float.isFinite(slope)) {
            throw new IllegalArgumentException("Invalid native sunlight environment input");
        }
        var environment = EnvironmentSun.direction(time, longitude, slope, reverseSlope);
        var x = -environment.getX();
        var y = -environment.getY();
        var z = -environment.getZ();
        if (z > 0) {
            z = .0001f;
        }
        var square = (x * x + y * y) + z * z;
        if (!Float.isFinite(square)) {
            throw new IllegalArgumentException("Non-finite native sunlight direction");
        }
        if ((double) square >= 1.0e-8) {
            var scale = (float) (1.0 / Math.sqrt((double) square));
            x *= scale;
            y *= scale;
            z *= scale;
        }
        var result = new Vector(x, y, z);
        requireFinite(result);
        return result;
    }

    public static boolean isAdmitted(Owner owner, Source source) {
        return source.lightType() == 1
                && source.zoneNumber() == owner.zoneNumber()
                && ((source.actorFlags() ^ owner.actorFlags()) & 0x20) == 0
                && source.movableSun();
    }

    public static Result rebuild(
            Grid grid,
            Owner owner,
            Window window,
            List<Source> authoredLights,
            List<Slot> slots,
            WorldQuery query,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(grid);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(window);
        Objects.requireNonNull(query);
        Objects.requireNonNull(cancelled);
        if ((long) window.offsetX() + window.width() > grid.width
                || (long) window.offsetY() + window.height() > grid.height) {
            throw new IllegalArgumentException("Sunlight window exceeds actual terrain vertices");
        }
        var sourceSnapshot = List.copyOf(authoredLights);
        var slotSnapshot = List.copyOf(slots);
        if (slotSnapshot.isEmpty() || slotSnapshot.size() > 64) {
            throw new IllegalArgumentException("Unqualified native sunlight frame count");
        }
        var previous = -1f;
        for (var slot : slotSnapshot) {
            if (slot.time() <= previous) {
                throw new IllegalArgumentException("Sunlight times must be strictly ordered");
            }
            previous = slot.time();
        }
        Source sun = null;
        var identities = new HashSet<Integer>();
        for (var source : sourceSnapshot) {
            if (!identities.add(source.reference())) {
                throw new IllegalArgumentException("Duplicate native sunlight source identity");
            }
            if (sun == null && isAdmitted(owner, source)) {
                sun = source;
            }
        }
        if (sun != null && sun.effect() != 19) {
            throw new IllegalArgumentException("Selected native sunlight effect is not qualified");
        }
        var frames = new ArrayList<Frame>(slotSnapshot.size());
        var count = window.width() * window.height();
        long rays = 0;
        for (var slot : slotSnapshot) {
            var values = new int[count];
            var mask = new int[(count + 7) / 8];
            for (var y = 0; y < window.height(); y++) {
                checkCancelled(cancelled);
                for (var x = 0; x < window.width(); x++) {
                    var index = (window.offsetY() + y) * grid.width + window.offsetX() + x;
                    if (sun == null || !grid.eligible[index]) {
                        continue;
                    }
                    var point = grid.positions[index];
                    var normal = grid.normals[index];
                    var direction = slot.safeDirection();
                    var start = new Vector(point.x() + normal.x(), point.y() + normal.y(), point.z() + normal.z());
                    var end = new Vector(
                            start.x() - direction.x() * RAY_LENGTH,
                            start.y() - direction.y() * RAY_LENGTH,
                            start.z() - direction.z() * RAY_LENGTH);
                    requireFinite(start);
                    requireFinite(end);
                    rays++;
                    if (!query.clear(start, end, TERRAIN_SHADOW_TRACE_FLAGS)) {
                        continue;
                    }
                    var vertex = y * window.width() + x;
                    mask[vertex >>> 3] |= 1 << (vertex & 7);
                    values[vertex] = computeIntensity(direction, normal);
                }
            }
            frames.add(new Frame(slot.time(), toIntegerList(values), toIntegerList(mask)));
        }
        checkCancelled(cancelled);
        return new Result(window, sun == null ? 0 : sun.reference(), frames, rays);
    }

    static int computeIntensity(Vector safeDirection, Vector normal) {
        requireFinite(safeDirection);
        requireFinite(normal);
        var dot = (safeDirection.y() * normal.y() + normal.x() * safeDirection.x()) + safeDirection.z() * normal.z();
        if (!Float.isFinite(dot)) {
            throw new IllegalArgumentException("Non-finite native sunlight dot product");
        }
        var sample = dot < 0 ? dot * -2f : 0f;
        var scaled = (sample * 255f) * .5f;
        if (!Float.isFinite(scaled)) {
            throw new IllegalArgumentException("Non-finite native sunlight intensity");
        }
        return scaled >= 255f ? 255 : (int) scaled;
    }

    private static List<Integer> toIntegerList(int[] values) {
        var result = new ArrayList<Integer>(values.length);
        for (var value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Native terrain sunlight cancelled");
        }
    }

    private static void requireFinite(Vector value) {
        Objects.requireNonNull(value);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException("Non-finite native sunlight vector");
        }
    }

    private static void requireZone(int zone) {
        if (zone < 0 || zone > 63) {
            throw new IllegalArgumentException("Unqualified native sunlight zone");
        }
    }
}
