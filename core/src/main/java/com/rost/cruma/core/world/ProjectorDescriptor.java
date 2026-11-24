package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import glm_.vec3.Vec3;

public record ProjectorDescriptor(
        String name,
        Vec3 location,
        Basis basis,
        EntityMaterial material,
        String materialName,
        int materialBlendingOp,
        int frameBufferBlendingOp,
        float uSize,
        float vSize,
        int fovDegrees,
        float maxTraceDistance,
        boolean projectBsp,
        boolean projectTerrain,
        boolean projectStaticMesh,
        boolean projectParticles,
        boolean projectActor,
        boolean levelStatic,
        boolean clipBsp,
        boolean clipStaticMesh,
        boolean projectOnUnlit,
        boolean gradient,
        boolean projectOnBackfaces,
        boolean projectOnAlpha,
        boolean projectOnParallelBsp,
        String projectTag,
        boolean dynamicAttach,
        float projectorRadius) {
    private static final float EPSILON = 1.0e-6f;

    public ProjectorDescriptor {
        if (name == null || location == null || basis == null || material == null) {
            throw new IllegalArgumentException("projector identity, transform and material are required");
        }
        if (!(uSize > 0f) || !(vSize > 0f) || !Float.isFinite(uSize) || !Float.isFinite(vSize)) {
            throw new IllegalArgumentException("projector material extent must be finite and positive");
        }
        if (!(maxTraceDistance >= 0f) || !Float.isFinite(maxTraceDistance)) {
            throw new IllegalArgumentException("projector trace distance must be finite and non-negative");
        }
    }

    public static Basis basis(URotator rotation) {
        var pitch = URotator.toRadians(rotation.pitch());
        var yaw = URotator.toRadians(rotation.yaw());
        var roll = URotator.toRadians(rotation.roll());
        var sp = (float) Math.sin(pitch);
        var cp = (float) Math.cos(pitch);
        var sy = (float) Math.sin(yaw);
        var cy = (float) Math.cos(yaw);
        var sr = (float) Math.sin(roll);
        var cr = (float) Math.cos(roll);

        var direction = new Vec3(cp * cy, cp * sy, sp);
        var yAxis = new Vec3(sr * sp * cy - cr * sy, sr * sp * sy + cr * cy, -sr * cp);
        var up = new Vec3(-(cr * sp * cy + sr * sy), cy * sr - cr * sp * sy, cr * cp);
        return new Basis(direction, up, yAxis.times(-1f));
    }

    public Projection project(Vec3 worldPosition) {
        var delta = worldPosition.minus(location);
        var forward = delta.dot(basis.direction);
        final float u;
        final float v;
        if (fovDegrees == 0) {
            u = 0.5f - delta.dot(basis.left) / uSize;
            v = 0.5f - delta.dot(basis.up) / vSize;
        } else {
            var tanHalfFov = (float) Math.tan(Math.toRadians(fovDegrees) * 0.5);
            var nearDistance = 0.5f * uSize / tanHalfFov;
            var projectiveDepth = forward + nearDistance;
            if (!Float.isFinite(projectiveDepth) || Math.abs(projectiveDepth) <= EPSILON) {
                return new Projection(Float.NaN, Float.NaN, forward, false);
            }
            var scale = 0.5f / tanHalfFov / projectiveDepth;
            u = 0.5f - delta.dot(basis.left) * scale;
            v = 0.5f - delta.dot(basis.up) * scale;
        }
        var inside = Float.isFinite(u)
                && Float.isFinite(v)
                && forward >= 0f
                && forward <= maxTraceDistance
                && u >= 0f
                && u <= 1f
                && v >= 0f
                && v <= 1f;
        return new Projection(u, v, forward, inside);
    }

    public Bounds3f volumeBounds() {
        var halfU = uSize * 0.5f;
        var halfV = vSize * 0.5f;
        var farU = halfU;
        var farV = halfV;
        if (fovDegrees != 0) {
            var spread = (float) Math.tan(Math.toRadians(fovDegrees) * 0.5) * maxTraceDistance;
            farU += spread;
            farV += spread;
        }
        var minX = Float.POSITIVE_INFINITY;
        var minY = Float.POSITIVE_INFINITY;
        var minZ = Float.POSITIVE_INFINITY;
        var maxX = Float.NEGATIVE_INFINITY;
        var maxY = Float.NEGATIVE_INFINITY;
        var maxZ = Float.NEGATIVE_INFINITY;
        for (var corner = 0; corner < 8; corner++) {
            var depth = (corner & 4) == 0 ? 0f : maxTraceDistance;
            var u = ((corner & 1) == 0 ? -1f : 1f) * ((corner & 4) == 0 ? halfU : farU);
            var v = ((corner & 2) == 0 ? -1f : 1f) * ((corner & 4) == 0 ? halfV : farV);
            var x = location.getX() + basis.direction.getX() * depth + basis.left.getX() * u + basis.up.getX() * v;
            var y = location.getY() + basis.direction.getY() * depth + basis.left.getY() * u + basis.up.getY() * v;
            var z = location.getZ() + basis.direction.getZ() * depth + basis.left.getZ() * u + basis.up.getZ() * v;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new Bounds3f(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean intersects(Bounds3f bounds) {
        var cx = bounds.centerX();
        var cy = bounds.centerY();
        var cz = bounds.centerZ();
        var ex = (bounds.maxX() - bounds.minX()) * 0.5f;
        var ey = (bounds.maxY() - bounds.minY()) * 0.5f;
        var ez = (bounds.maxZ() - bounds.minZ()) * 0.5f;
        var dx = cx - location.getX();
        var dy = cy - location.getY();
        var dz = cz - location.getZ();

        var forward = dot(dx, dy, dz, basis.direction);
        var forwardRadius = support(ex, ey, ez, basis.direction);
        if (forward + forwardRadius < 0f || forward - forwardRadius > maxTraceDistance) {
            return false;
        }

        var left = dot(dx, dy, dz, basis.left);
        var up = dot(dx, dy, dz, basis.up);
        if (fovDegrees == 0) {
            return overlaps(left, support(ex, ey, ez, basis.left), uSize * 0.5f)
                    && overlaps(up, support(ex, ey, ez, basis.up), vSize * 0.5f);
        }

        var tanHalfFov = (float) Math.tan(Math.toRadians(fovDegrees) * 0.5);
        var nearDistance = 0.5f * uSize / tanHalfFov;
        return isInsidePerspectivePlane(
                        left - tanHalfFov * (forward + nearDistance),
                        ex,
                        ey,
                        ez,
                        basis.left,
                        basis.direction,
                        -tanHalfFov)
                && isInsidePerspectivePlane(
                        -left - tanHalfFov * (forward + nearDistance),
                        ex,
                        ey,
                        ez,
                        basis.left,
                        basis.direction,
                        tanHalfFov)
                && isInsidePerspectivePlane(
                        up - tanHalfFov * (forward + nearDistance), ex, ey, ez, basis.up, basis.direction, -tanHalfFov)
                && isInsidePerspectivePlane(
                        -up - tanHalfFov * (forward + nearDistance), ex, ey, ez, basis.up, basis.direction, tanHalfFov);
    }

    public int fusedBlendPair() {
        if (materialBlendingOp == 0 && frameBufferBlendingOp == 1) {
            return 0;
        }
        if (materialBlendingOp == 1 && frameBufferBlendingOp == 2) {
            return 1;
        }
        if (materialBlendingOp == 1 && frameBufferBlendingOp == 3) {
            return 2;
        }
        return -1;
    }

    private static boolean overlaps(float center, float radius, float halfExtent) {
        return center - radius <= halfExtent && center + radius >= -halfExtent;
    }

    private static boolean isInsidePerspectivePlane(
            float centerValue, float ex, float ey, float ez, Vec3 lateralAxis, Vec3 forwardAxis, float forwardScale) {
        var nx = lateralAxis.getX() + forwardAxis.getX() * forwardScale;
        var ny = lateralAxis.getY() + forwardAxis.getY() * forwardScale;
        var nz = lateralAxis.getZ() + forwardAxis.getZ() * forwardScale;
        var radius = Math.abs(nx) * ex + Math.abs(ny) * ey + Math.abs(nz) * ez;
        return centerValue - radius <= 0f;
    }

    private static float dot(float x, float y, float z, Vec3 axis) {
        return x * axis.getX() + y * axis.getY() + z * axis.getZ();
    }

    private static float support(float ex, float ey, float ez, Vec3 axis) {
        return Math.abs(axis.getX()) * ex + Math.abs(axis.getY()) * ey + Math.abs(axis.getZ()) * ez;
    }

    public record Basis(Vec3 direction, Vec3 up, Vec3 left) {
        public Basis {
            if (direction == null || up == null || left == null) {
                throw new IllegalArgumentException("projector basis axes are required");
            }
        }
    }

    public record Projection(float u, float v, float depth, boolean inside) {}
}
