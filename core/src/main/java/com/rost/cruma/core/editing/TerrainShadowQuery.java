package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeLevelShadowQuery.ActorDomain;
import com.rost.cruma.core.editing.NativeLevelShadowQuery.Result;
import com.rost.cruma.core.editing.NativeLevelShadowQuery.State;
import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.WorldQuery;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class TerrainShadowQuery implements WorldQuery {
    private static final Result CLEAR = new Result(State.CLEAR, "No shadow caster blocks the segment");
    private static final Result BLOCKED = new Result(State.BLOCKED, "A shadow caster blocks the segment");

    private final NativeBspTrace bsp;
    private final NativeLevelShadowQuery level;
    private final Caster[] casters;
    private final SegmentBvh bvh;
    private final AtomicLong casterFailures = new AtomicLong();

    public TerrainShadowQuery(NativeBspTrace bsp, List<? extends Caster> casters) {
        this.bsp = Objects.requireNonNull(bsp);
        this.casters = casters.toArray(new Caster[0]);
        var bounds = new float[this.casters.length * 6];
        for (var i = 0; i < this.casters.length; i++) {
            var box = this.casters[i].bounds();
            bounds[i * 6] = box.min().x();
            bounds[i * 6 + 1] = box.min().y();
            bounds[i * 6 + 2] = box.min().z();
            bounds[i * 6 + 3] = box.max().x();
            bounds[i * 6 + 4] = box.max().y();
            bounds[i * 6 + 5] = box.max().z();
        }
        bvh = new SegmentBvh(bounds);
        ActorDomain domain = (start, end, flags) -> isBlockedByCaster(start, end) ? BLOCKED : CLEAR;
        level = new NativeLevelShadowQuery(bsp, false, List.of(), List.of(domain));
    }

    public interface Caster {
        Box bounds();

        boolean blocks(Vector start, Vector end);
    }

    @Override
    public boolean clear(Vector start, Vector end, int traceFlags) {
        return level.clear(start, end, traceFlags);
    }

    public int getCasterCount() {
        return casters.length;
    }

    public long getCasterFailures() {
        return casterFailures.get();
    }

    public List<Box> getMeshBounds(Set<Integer> references) {
        var result = new ArrayList<Box>();
        for (var caster : casters) {
            if (caster instanceof MeshCaster mesh && references.contains(mesh.reference())) {
                result.add(mesh.bounds());
            }
        }
        return result;
    }

    public TerrainShadowQuery replacing(Set<Integer> references, List<? extends Caster> added) {
        var result = new ArrayList<Caster>();
        for (var caster : casters) {
            if (!(caster instanceof MeshCaster mesh && references.contains(mesh.reference()))) {
                result.add(caster);
            }
        }
        result.addAll(added);
        return new TerrainShadowQuery(bsp, result);
    }

    private boolean isBlockedByCaster(Vector start, Vector end) {
        return bvh.hasAnyHit(start.x(), start.y(), start.z(), end.x(), end.y(), end.z(), index -> {
            try {
                return casters[index].blocks(start, end);
            } catch (IllegalArgumentException | IllegalStateException failed) {
                casterFailures.incrementAndGet();
                return false;
            }
        });
    }

    public static final class MeshTriangles {
        private static final float PARALLEL = 1e-12f;

        private final float[] triangles;
        private final SegmentBvh bvh;

        public MeshTriangles(float[] triangles) {
            if (triangles.length % 9 != 0) {
                throw new IllegalArgumentException("Mesh triangles need nine values each");
            }
            this.triangles = triangles.clone();
            var count = triangles.length / 9;
            var bounds = new float[count * 6];
            for (var t = 0; t < count; t++) {
                for (var a = 0; a < 3; a++) {
                    var p0 = triangles[t * 9 + a];
                    var p1 = triangles[t * 9 + 3 + a];
                    var p2 = triangles[t * 9 + 6 + a];
                    bounds[t * 6 + a] = Math.min(p0, Math.min(p1, p2));
                    bounds[t * 6 + 3 + a] = Math.max(p0, Math.max(p1, p2));
                }
            }
            bvh = new SegmentBvh(bounds);
        }

        public int size() {
            return triangles.length / 9;
        }

        public boolean intersects(float sx, float sy, float sz, float ex, float ey, float ez) {
            var dx = ex - sx;
            var dy = ey - sy;
            var dz = ez - sz;
            return bvh.hasAnyHit(sx, sy, sz, ex, ey, ez, t -> hitsTriangle(t * 9, sx, sy, sz, dx, dy, dz));
        }

        private boolean hitsTriangle(int o, float sx, float sy, float sz, float dx, float dy, float dz) {
            var t = triangles;
            var e1x = t[o + 3] - t[o];
            var e1y = t[o + 4] - t[o + 1];
            var e1z = t[o + 5] - t[o + 2];
            var e2x = t[o + 6] - t[o];
            var e2y = t[o + 7] - t[o + 1];
            var e2z = t[o + 8] - t[o + 2];
            var px = dy * e2z - dz * e2y;
            var py = dz * e2x - dx * e2z;
            var pz = dx * e2y - dy * e2x;
            var det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < PARALLEL) {
                return false;
            }
            var inverse = 1f / det;
            var tx = sx - t[o];
            var ty = sy - t[o + 1];
            var tz = sz - t[o + 2];
            var u = (tx * px + ty * py + tz * pz) * inverse;
            if (u < 0 || u > 1) {
                return false;
            }
            var qx = ty * e1z - tz * e1y;
            var qy = tz * e1x - tx * e1z;
            var qz = tx * e1y - ty * e1x;
            var v = (dx * qx + dy * qy + dz * qz) * inverse;
            if (v < 0 || u + v > 1) {
                return false;
            }
            var distance = (e2x * qx + e2y * qy + e2z * qz) * inverse;
            return distance >= 0 && distance <= 1;
        }
    }

    public record MeshCaster(int reference, Box bounds, MeshTriangles triangles, float[] worldToLocal)
            implements Caster {
        public MeshCaster {
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(triangles);
            if (worldToLocal.length != 16) {
                throw new IllegalArgumentException("A mesh caster needs a 4x4 matrix");
            }
            worldToLocal = worldToLocal.clone();
        }

        @Override
        public boolean blocks(Vector start, Vector end) {
            var m = worldToLocal;
            return triangles.intersects(
                    m[0] * start.x() + m[4] * start.y() + m[8] * start.z() + m[12],
                    m[1] * start.x() + m[5] * start.y() + m[9] * start.z() + m[13],
                    m[2] * start.x() + m[6] * start.y() + m[10] * start.z() + m[14],
                    m[0] * end.x() + m[4] * end.y() + m[8] * end.z() + m[12],
                    m[1] * end.x() + m[5] * end.y() + m[9] * end.z() + m[13],
                    m[2] * end.x() + m[6] * end.y() + m[10] * end.z() + m[14]);
        }
    }

    public record TerrainCaster(Box bounds, NativeTerrainTrace trace) implements Caster {
        @Override
        public boolean blocks(Vector start, Vector end) {
            return !trace.clear(start, end);
        }
    }
}
