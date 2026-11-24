package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Surface;
import glm_.vec3.Vec3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class MeshPickData {
    private final float[] positions;
    private final int[] triangles;
    private final Box bounds = new Box();

    MeshPickData(EntityMesh mesh) {
        this(mesh, null);
    }

    private MeshPickData(EntityMesh mesh, int[] sharedTriangles) {
        var vertices = mesh.vertexBuffer();
        positions = new float[vertices.size() * 3];
        for (var i = 0; i < vertices.size(); i++) {
            positions[i * 3] = vertices.getPositionX(i);
            positions[i * 3 + 1] = vertices.getPositionY(i);
            positions[i * 3 + 2] = vertices.getPositionZ(i);
        }
        var count = mesh.surfaces().stream()
                .filter(s -> s.blendMode() != BlendMode.INVISIBLE)
                .mapToInt(Surface::indexCount)
                .sum();
        var built = new int[count];
        var next = 0;
        for (var surface : mesh.surfaces()) {
            if (surface.blendMode() == BlendMode.INVISIBLE) {
                continue;
            }
            System.arraycopy(mesh.indices(), surface.indexOffset(), built, next, surface.indexCount());
            next += surface.indexCount();
        }
        triangles = Arrays.equals(built, sharedTriangles) ? sharedTriangles : built;
        for (var index : triangles) {
            bounds.add(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2]);
        }
    }

    static List<MeshPickData> ofFrames(List<EntityMesh> frames) {
        var result = new ArrayList<MeshPickData>(frames.size());
        int[] shared = null;
        for (var frame : frames) {
            var pick = new MeshPickData(frame, shared);
            shared = pick.triangles;
            result.add(pick);
        }
        return List.copyOf(result);
    }

    long getRetainedBytes() {
        return (long) positions.length * Float.BYTES + (long) triangles.length * Integer.BYTES + 24;
    }

    void appendWorldBounds(ByteBuffer model, Box target) {
        if (!bounds.isValid()) {
            return;
        }
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? bounds.getMinX() : bounds.getMaxX();
            var y = (corner & 2) == 0 ? bounds.getMinY() : bounds.getMaxY();
            var z = (corner & 4) == 0 ? bounds.getMinZ() : bounds.getMaxZ();
            var worldX = model.getFloat(0) * x + model.getFloat(16) * y + model.getFloat(32) * z + model.getFloat(48);
            var worldY = model.getFloat(4) * x + model.getFloat(20) * y + model.getFloat(36) * z + model.getFloat(52);
            var worldZ = model.getFloat(8) * x + model.getFloat(24) * y + model.getFloat(40) * z + model.getFloat(56);
            target.add(worldX, worldY, worldZ);
        }
    }

    float computeDistance(Vec3 origin, Vec3 direction, ByteBuffer model, float maximum) {
        double a = model.getFloat(0), b = model.getFloat(16), c = model.getFloat(32);
        double d = model.getFloat(4), e = model.getFloat(20), f = model.getFloat(36);
        double g = model.getFloat(8), h = model.getFloat(24), i = model.getFloat(40);
        var aa = e * i - f * h;
        var ab = c * h - b * i;
        var ac = b * f - c * e;
        var ba = f * g - d * i;
        var bb = a * i - c * g;
        var bc = c * d - a * f;
        var ca = d * h - e * g;
        var cb = b * g - a * h;
        var cc = a * e - b * d;
        var determinant = a * aa + b * ba + c * ca;
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-20) {
            return -1;
        }
        var inverse = 1d / determinant;
        var x = origin.getX() - model.getFloat(48);
        var y = origin.getY() - model.getFloat(52);
        var z = origin.getZ() - model.getFloat(56);
        var ox = (aa * x + ab * y + ac * z) * inverse;
        var oy = (ba * x + bb * y + bc * z) * inverse;
        var oz = (ca * x + cb * y + cc * z) * inverse;
        var dx = (aa * direction.getX() + ab * direction.getY() + ac * direction.getZ()) * inverse;
        var dy = (ba * direction.getX() + bb * direction.getY() + bc * direction.getZ()) * inverse;
        var dz = (ca * direction.getX() + cb * direction.getY() + cc * direction.getZ()) * inverse;
        var nearest = (double) maximum;
        var found = false;
        for (var t = 0; t + 2 < triangles.length; t += 3) {
            var av = triangles[t] * 3;
            var bv = triangles[t + 1] * 3;
            var cv = triangles[t + 2] * 3;
            var e1x = positions[bv] - positions[av];
            var e1y = positions[bv + 1] - positions[av + 1];
            var e1z = positions[bv + 2] - positions[av + 2];
            var e2x = positions[cv] - positions[av];
            var e2y = positions[cv + 1] - positions[av + 1];
            var e2z = positions[cv + 2] - positions[av + 2];
            var px = dy * e2z - dz * e2y;
            var py = dz * e2x - dx * e2z;
            var pz = dx * e2y - dy * e2x;
            var det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-15) {
                continue;
            }
            var inv = 1d / det;
            var tx = ox - positions[av];
            var ty = oy - positions[av + 1];
            var tz = oz - positions[av + 2];
            var u = (tx * px + ty * py + tz * pz) * inv;
            if (u < 0 || u > 1) {
                continue;
            }
            var qx = ty * e1z - tz * e1y;
            var qy = tz * e1x - tx * e1z;
            var qz = tx * e1y - ty * e1x;
            var v = (dx * qx + dy * qy + dz * qz) * inv;
            if (v < 0 || u + v > 1) {
                continue;
            }
            var hit = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (hit >= 0 && hit < nearest) {
                nearest = hit;
                found = true;
            }
        }
        return found ? (float) nearest : -1;
    }
}
