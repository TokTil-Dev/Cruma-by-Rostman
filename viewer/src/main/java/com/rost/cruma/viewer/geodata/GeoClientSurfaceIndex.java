package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataScanner.ClientSurface;
import com.rost.cruma.core.geodata.GeoDataScanner.SurfaceKind;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.UMap;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

public final class GeoClientSurfaceIndex {
    public static final String ALGORITHM = "client-static-triangles-v3";

    static final long NOT_CLIENT_GEOMETRY = SurfaceType.PASSABLE.getId()
            | SurfaceType.FAKE_BACKDROP.getId()
            | SurfaceType.BOUNDING_BOX.getId()
            | SurfaceType.IMPORTED_GEODATA.getId()
            | SurfaceType.GENERATED_GEODATA.getId();

    private static final int GRID = 256;
    private static final byte WALL = 2;

    private final String owner;
    private final Bounds bounds;
    private final float[] triangles;
    private final byte[] kinds;
    private final Map<Long, int[]> buckets;
    private final Map<Long, int[]> walls;
    private final long retainedBytes;
    private final boolean truncated;
    private final String fingerprint;
    private final String failure;

    private GeoClientSurfaceIndex(
            String owner,
            Bounds bounds,
            float[] triangles,
            byte[] kinds,
            Map<Long, int[]> buckets,
            Map<Long, int[]> walls,
            long retainedBytes,
            boolean truncated) {
        this(owner, bounds, triangles, kinds, buckets, walls, retainedBytes, truncated, "");
    }

    private GeoClientSurfaceIndex(
            String owner,
            Bounds bounds,
            float[] triangles,
            byte[] kinds,
            Map<Long, int[]> buckets,
            Map<Long, int[]> walls,
            long retainedBytes,
            boolean truncated,
            String failure) {
        this.owner = owner;
        this.bounds = bounds;
        this.triangles = triangles;
        this.kinds = kinds;
        this.buckets = Map.copyOf(buckets);
        this.walls = Map.copyOf(walls);
        this.retainedBytes = retainedBytes;
        this.truncated = truncated;
        this.failure = failure;
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(ALGORITHM.getBytes(StandardCharsets.UTF_8));
            var number = ByteBuffer.allocate(4);
            for (var value : triangles) {
                number.clear();
                hash.update(number.putFloat(value).array());
            }
            hash.update(kinds);
            hash.update((byte) (truncated ? 1 : 0));
            hash.update((byte) (failure.isBlank() ? 0 : 1));
            fingerprint = HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Limits(int maxTriangles, int maxReferences, long maxBytes) {
        public Limits {
            if (maxTriangles < 1
                    || maxTriangles > 1_000_000
                    || maxReferences < 1
                    || maxReferences > 4_000_000
                    || maxBytes < 1024) {
                throw new IllegalArgumentException("Invalid client surface index limits");
            }
        }
    }

    public static GeoClientSurfaceIndex build(UMap map, Limits limits, BooleanSupplier cancelled) {
        var x = GeoQueryEngine.serverInt(map.position().getX());
        var y = GeoQueryEngine.serverInt(map.position().getY());
        return build(map, new Bounds(x, y, Math.addExact(x, 32768), Math.addExact(y, 32768)), limits, cancelled);
    }

    public static GeoClientSurfaceIndex build(UMap map, Bounds bounds, Limits limits, BooleanSupplier cancelled) {
        var builder = new Builder(map, bounds, limits, cancelled);
        builder.build();
        return builder.finish();
    }

    static GeoClientSurfaceIndex unavailable(String owner, Bounds bounds, RuntimeException failure) {
        return new GeoClientSurfaceIndex(
                owner,
                bounds,
                new float[0],
                new byte[0],
                Map.of(),
                Map.of(),
                0,
                false,
                "Client triangle reference could not be prepared: "
                        + failure.getClass().getSimpleName());
    }

    static double orientation(float[] m, double x, double y, double z, double nx, double ny, double nz) {
        var a = (double) m[0];
        var b = (double) m[4];
        var c = (double) m[8];
        var d = (double) m[1];
        var e = (double) m[5];
        var f = (double) m[9];
        var g = (double) m[2];
        var h = (double) m[6];
        var i = (double) m[10];
        var determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(determinant) < 1e-12) {
            return 0;
        }
        var tx = ((e * i - f * h) * x + (f * g - d * i) * y + (d * h - e * g) * z) / determinant;
        var ty = ((c * h - b * i) * x + (a * i - c * g) * y + (b * g - a * h) * z) / determinant;
        var tz = ((b * f - c * e) * x + (c * d - a * f) * y + (a * e - b * d) * z) / determinant;
        return Math.signum(tx * nx + ty * ny + tz * nz);
    }

    private static final class Stop extends RuntimeException {
        private static final Stop INSTANCE = new Stop();

        private Stop() {
            super(null, null, false, false);
        }
    }

    public String getOwner() {
        return owner;
    }

    public Bounds getBounds() {
        return bounds;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getTriangleCount() {
        return kinds.length;
    }

    public long getRetainedBytes() {
        return retainedBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public boolean contains(int x, int y) {
        return x >= bounds.minX() && x < bounds.maxX() && y >= bounds.minY() && y < bounds.maxY();
    }

    Sample sample(int x, int y, int maximumTests) {
        if (!failure.isBlank()) {
            return new Sample(List.of(), 0, Completeness.DATA_UNAVAILABLE, failure);
        }
        if (truncated) {
            return new Sample(
                    List.of(),
                    0,
                    Completeness.LIMIT,
                    "Client triangle index was truncated by its memory or work budget");
        }
        var bucket = buckets.get(key(Math.floorDiv(x, GRID), Math.floorDiv(y, GRID)));
        if (bucket == null) {
            return new Sample(List.of(), 0, Completeness.COMPLETE, "");
        }
        if (bucket.length > maximumTests) {
            return new Sample(List.of(), 0, Completeness.LIMIT, "Client triangle query budget exhausted");
        }
        var surfaces = new ArrayList<ClientSurface>();
        var heights = new HashSet<Long>();
        for (var triangle : bucket) {
            var offset = triangle * 9;
            var ax = triangles[offset];
            var ay = triangles[offset + 1];
            var bx = triangles[offset + 3];
            var by = triangles[offset + 4];
            var cx = triangles[offset + 6];
            var cy = triangles[offset + 7];
            var determinant = ((double) by - cy) * (ax - cx) + ((double) cx - bx) * (ay - cy);
            if (Math.abs(determinant) < 1e-9) {
                continue;
            }
            var a = (((double) by - cy) * (x - cx) + ((double) cx - bx) * (y - cy)) / determinant;
            var b = (((double) cy - ay) * (x - cx) + ((double) ax - cx) * (y - cy)) / determinant;
            var c = 1 - a - b;
            if (a < -1e-6 || b < -1e-6 || c < -1e-6) {
                continue;
            }
            var height = a * triangles[offset + 2] + b * triangles[offset + 5] + c * triangles[offset + 8];
            var kind = kinds[triangle] == 1 ? SurfaceKind.WALKABLE : SurfaceKind.UNKNOWN;
            if (heights.add(Math.round(height * 100) * 2 + kinds[triangle])) {
                surfaces.add(new ClientSurface(height, kind, owner + "@" + fingerprint));
            }
        }
        surfaces.sort(Comparator.comparingDouble(ClientSurface::height));
        return new Sample(List.copyOf(surfaces), bucket.length, Completeness.COMPLETE, "");
    }

    record Sample(List<ClientSurface> surfaces, int testedTriangles, Completeness completeness, String detail) {}

    record Crossing(boolean blocked, int testedTriangles, Completeness completeness) {}

    Crossing findCrossing(double ax, double ay, double bx, double by, double z, int maximumTests) {
        if (!failure.isBlank()) {
            return new Crossing(false, 0, Completeness.DATA_UNAVAILABLE);
        }
        if (truncated) {
            return new Crossing(false, 0, Completeness.LIMIT);
        }
        var tested = 0;
        var seen = new HashSet<Integer>();
        var first = key(Math.floorDiv((int) Math.floor(ax), GRID), Math.floorDiv((int) Math.floor(ay), GRID));
        var second = key(Math.floorDiv((int) Math.floor(bx), GRID), Math.floorDiv((int) Math.floor(by), GRID));
        for (var bucketKey : first == second ? List.of(first) : List.of(first, second)) {
            for (var bucket : new int[][] {buckets.get(bucketKey), walls.get(bucketKey)}) {
                if (bucket == null) {
                    continue;
                }
                for (var triangle : bucket) {
                    if (!seen.add(triangle)) {
                        continue;
                    }
                    if (++tested > maximumTests) {
                        return new Crossing(false, tested, Completeness.LIMIT);
                    }
                    if (crosses(triangle, ax, ay, bx, by, z)) {
                        return new Crossing(true, tested, Completeness.COMPLETE);
                    }
                }
            }
        }
        return new Crossing(false, tested, Completeness.COMPLETE);
    }

    private boolean crosses(int triangle, double ax, double ay, double bx, double by, double z) {
        var offset = triangle * 9;
        var minZ = Math.min(triangles[offset + 2], Math.min(triangles[offset + 5], triangles[offset + 8]));
        var maxZ = Math.max(triangles[offset + 2], Math.max(triangles[offset + 5], triangles[offset + 8]));
        if (z < minZ || z > maxZ) {
            return false;
        }
        var dx = bx - ax;
        var dy = by - ay;
        var e1x = triangles[offset + 3] - triangles[offset];
        var e1y = triangles[offset + 4] - triangles[offset + 1];
        var e1z = triangles[offset + 5] - triangles[offset + 2];
        var e2x = triangles[offset + 6] - triangles[offset];
        var e2y = triangles[offset + 7] - triangles[offset + 1];
        var e2z = triangles[offset + 8] - triangles[offset + 2];
        var px = dy * e2z;
        var py = -dx * e2z;
        var pz = dx * e2y - dy * e2x;
        var determinant = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(determinant) < 1e-9) {
            return false;
        }
        var inverse = 1 / determinant;
        var tx = ax - triangles[offset];
        var ty = ay - triangles[offset + 1];
        var tz = z - triangles[offset + 2];
        var u = (tx * px + ty * py + tz * pz) * inverse;
        if (u < 0 || u > 1) {
            return false;
        }
        var qx = ty * e1z - tz * e1y;
        var qy = tz * e1x - tx * e1z;
        var qz = tx * e1y - ty * e1x;
        var v = (dx * qx + dy * qy) * inverse;
        if (v < 0 || u + v > 1) {
            return false;
        }
        var t = (e2x * qx + e2y * qy + e2z * qz) * inverse;
        return t >= 0 && t <= 1;
    }

    private static long key(int x, int y) {
        return (long) x << 32 | (y & 0xffffffffL);
    }

    @FunctionalInterface
    private interface Coordinates {
        float at(int vertex, int component);
    }

    private static final class Builder {
        private final UMap map;
        private final Limits limits;
        private final BooleanSupplier cancelled;
        private final Bounds bounds;
        private final LongObjectHashMap<IntArrayList> buckets = new LongObjectHashMap<>();
        private final LongObjectHashMap<IntArrayList> walls = new LongObjectHashMap<>();
        private float[] triangles = new float[9 * 1024];
        private byte[] kinds = new byte[1024];
        private int count;
        private int references;
        private long bytes;
        private long examined;
        private boolean truncated;

        Builder(UMap map, Bounds bounds, Limits limits, BooleanSupplier cancelled) {
            this.map = map;
            this.limits = limits;
            this.cancelled = cancelled;
            this.bounds = bounds;
        }

        void build() {
            try {
                for (var entity : map.entities()) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw Stop.INSTANCE;
                    }
                    if (entity.getMovement() != null || entity.isDoor() || entity.isZoneLookCopy()) {
                        continue;
                    }
                    switch (entity.getMesh()) {
                        case EntityMesh mesh -> {
                            if (mesh.billboard() || mesh.swayFrequency() != 0 || mesh.decorationFadeMax() > 0) {
                                continue;
                            }
                            var vertices = mesh.vertexBuffer();
                            Coordinates positions = (v, c) -> switch (c) {
                                case 0 -> vertices.getPositionX(v);
                                case 1 -> vertices.getPositionY(v);
                                default -> vertices.getPositionZ(v);
                            };
                            Coordinates normals = (v, c) -> switch (c) {
                                case 0 -> vertices.getNormalX(v);
                                case 1 -> vertices.getNormalY(v);
                                default -> vertices.getNormalZ(v);
                            };
                            for (var instance : entity.getInstanceMatrices()) {
                                var model =
                                        entity.getModelMatrix().times(instance).to(new float[16]);
                                for (var surface : mesh.surfaces()) {
                                    append(surface, mesh.indices(), model, positions, normals, false);
                                }
                            }
                        }
                        case TerrainMesh mesh -> {
                            var vertices = mesh.vertexBuffer();
                            Coordinates positions = (v, c) -> switch (c) {
                                case 0 -> vertices.getPositionX(v);
                                case 1 -> vertices.getPositionY(v);
                                default -> vertices.getPositionZ(v);
                            };
                            append(
                                    mesh.surface(),
                                    mesh.indices(),
                                    entity.getModelMatrix().to(new float[16]),
                                    positions,
                                    null,
                                    true);
                        }
                    }
                }
            } catch (Stop ignored) {
                truncated = true;
            }
        }

        void append(
                Surface surface,
                int[] indices,
                float[] matrix,
                Coordinates positions,
                Coordinates normals,
                boolean terrain) {
            if ((surface.type() & NOT_CLIENT_GEOMETRY) != 0
                    || surface.water()
                    || surface.blendMode() == BlendMode.INVISIBLE) {
                return;
            }
            var known = terrain || (surface.type() & (SurfaceType.STATIC_MESH.getId() | SurfaceType.CSG.getId())) != 0;
            var groundMaterial = surface.blendMode() == BlendMode.OPAQUE || surface.blendMode() == BlendMode.MASKED;
            var coordinates = new float[9];
            for (var offset = surface.indexOffset();
                    offset + 2 < surface.indexOffset() + surface.indexCount();
                    offset += 3) {
                if (++examined > limits.maxTriangles() * 16L
                        || cancelled.getAsBoolean()
                        || Thread.currentThread().isInterrupted()) {
                    throw Stop.INSTANCE;
                }
                var normalX = 0d;
                var normalY = 0d;
                var normalZ = 0d;
                for (var point = 0; point < 3; point++) {
                    var vertex = indices[offset + point];
                    var x = positions.at(vertex, 0);
                    var y = positions.at(vertex, 1);
                    var z = positions.at(vertex, 2);
                    coordinates[point * 3] = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
                    coordinates[point * 3 + 1] = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
                    coordinates[point * 3 + 2] = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14];
                    if (normals != null) {
                        normalX += normals.at(vertex, 0);
                        normalY += normals.at(vertex, 1);
                        normalZ += normals.at(vertex, 2);
                    }
                }
                var e1x = coordinates[3] - coordinates[0];
                var e1y = coordinates[4] - coordinates[1];
                var e1z = coordinates[5] - coordinates[2];
                var e2x = coordinates[6] - coordinates[0];
                var e2y = coordinates[7] - coordinates[1];
                var e2z = coordinates[8] - coordinates[2];
                var nx = (double) e1y * e2z - (double) e1z * e2y;
                var ny = (double) e1z * e2x - (double) e1x * e2z;
                var nz = (double) e1x * e2y - (double) e1y * e2x;
                var length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (!Double.isFinite(length) || length < 1e-8) {
                    continue;
                }
                if (Math.abs(nz) < 1e-8) {
                    add(coordinates, WALL);
                    continue;
                }
                var orientation =
                        terrain ? Math.signum(nz) : orientation(matrix, normalX, normalY, normalZ, nx, ny, nz);
                var walkable = known && groundMaterial && orientation * nz / length >= Math.sqrt(0.5);
                add(coordinates, (byte) (walkable ? 1 : 0));
            }
        }

        void add(float[] coordinates, byte kind) {
            var minX = Math.max(bounds.minX(), Math.min(coordinates[0], Math.min(coordinates[3], coordinates[6])));
            var minY = Math.max(bounds.minY(), Math.min(coordinates[1], Math.min(coordinates[4], coordinates[7])));
            var maxX = Math.min(bounds.maxX() - 1, Math.max(coordinates[0], Math.max(coordinates[3], coordinates[6])));
            var maxY = Math.min(bounds.maxY() - 1, Math.max(coordinates[1], Math.max(coordinates[4], coordinates[7])));
            if (minX > maxX || minY > maxY) {
                return;
            }
            var lowX = Math.floorDiv((int) Math.floor(minX), GRID);
            var lowY = Math.floorDiv((int) Math.floor(minY), GRID);
            var highX = Math.floorDiv((int) Math.floor(maxX), GRID);
            var highY = Math.floorDiv((int) Math.floor(maxY), GRID);
            var addedReferences = (highX - lowX + 1) * (highY - lowY + 1);
            var target = kind == WALL ? walls : buckets;
            var newBuckets = 0;
            for (var x = lowX; x <= highX; x++) {
                for (var y = lowY; y <= highY; y++) {
                    if (!target.containsKey(key(x, y))) {
                        newBuckets++;
                    }
                }
            }
            var required = 64L + addedReferences * 8L + newBuckets * 128L;
            if (count >= limits.maxTriangles()
                    || references + addedReferences > limits.maxReferences()
                    || bytes + required > limits.maxBytes()) {
                throw Stop.INSTANCE;
            }
            if (count == kinds.length) {
                var capacity = Math.min(limits.maxTriangles(), count * 2);
                triangles = Arrays.copyOf(triangles, capacity * 9);
                kinds = Arrays.copyOf(kinds, capacity);
            }
            System.arraycopy(coordinates, 0, triangles, count * 9, 9);
            kinds[count] = kind;
            for (var x = lowX; x <= highX; x++) {
                for (var y = lowY; y <= highY; y++) {
                    target.getIfAbsentPut(key(x, y), IntArrayList::new).add(count);
                }
            }
            references += addedReferences;
            bytes += required;
            count++;
        }

        GeoClientSurfaceIndex finish() {
            var frozen = new HashMap<Long, int[]>();
            buckets.forEachKeyValue((key, value) -> frozen.put(key, value.toArray()));
            var frozenWalls = new HashMap<Long, int[]>();
            walls.forEachKeyValue((key, value) -> frozenWalls.put(key, value.toArray()));
            return new GeoClientSurfaceIndex(
                    map.name(),
                    bounds,
                    Arrays.copyOf(triangles, count * 9),
                    Arrays.copyOf(kinds, count),
                    frozen,
                    frozenWalls,
                    bytes,
                    truncated);
        }
    }
}
