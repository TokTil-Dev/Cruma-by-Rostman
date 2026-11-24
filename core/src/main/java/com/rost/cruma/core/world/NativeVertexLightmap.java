package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.Nullable;

public final class NativeVertexLightmap {
    private static final float[] BASIS = {
        0,
        (float) (Math.sqrt(6) / 3),
        (float) (1 / Math.sqrt(3)),
        (float) (-1 / Math.sqrt(2)),
        (float) (-1 / Math.sqrt(6)),
        (float) (1 / Math.sqrt(3)),
        (float) (1 / Math.sqrt(2)),
        (float) (-1 / Math.sqrt(6)),
        (float) (1 / Math.sqrt(3))
    };

    private NativeVertexLightmap() {}

    public record Source(Vec3 position, Vec3 direction, Vec3 color, float radius, int effect, int cone) {}

    public record SceneLight(boolean actorPresent, @Nullable Source cached, byte[] vertexMask, int applied) {}

    public record EnvironmentSource(Vec3 direction, boolean dynamic, boolean changed) {}

    public record EnvironmentLight(@Nullable EnvironmentSource cached, byte[] vertexMask, int applied) {}

    public record Result(float[] coefficients, int[] applied) {}

    public static Result rebuild(
            float[] vertices,
            float[] tangents,
            float[] localToWorld,
            float[] worldToLocal,
            Vec3 drawScale,
            float actorIntensity,
            List<SceneLight> lights) {
        return rebuild(
                vertices,
                tangents,
                localToWorld,
                worldToLocal,
                drawScale,
                actorIntensity,
                lights,
                index -> lights.get(index).cached());
    }

    public static Result rebuild(
            float[] vertices,
            float[] tangents,
            float[] localToWorld,
            float[] worldToLocal,
            Vec3 drawScale,
            float actorIntensity,
            List<SceneLight> lights,
            IntFunction<@Nullable Source> cachedSource) {
        var count = vertices.length / 6;
        if (vertices.length % 6 != 0
                || tangents.length != count * 4
                || localToWorld.length != 16
                || worldToLocal.length != 16) {
            throw new IllegalArgumentException("vertex-lightmap stream or transform dimensions");
        }
        var output = new float[count * 9];
        var applied = new int[lights.size()];
        for (var index = 0; index < lights.size(); index++) {
            var record = lights.get(index);
            applied[index] = record.applied();
            if (!record.actorPresent()) {
                continue;
            }
            applied[index] = 0;
            var source = cachedSource.apply(index);
            if (source == null) {
                continue;
            }
            if (record.vertexMask().length < (count + 7) / 8) {
                throw new IllegalArgumentException("vertex-lightmap visibility bitmap is shorter than the mesh");
            }
            var localLight = transform(
                    worldToLocal,
                    source.position().getX(),
                    source.position().getY(),
                    source.position().getZ());
            for (var vertex = 0; vertex < count; vertex++) {
                if ((record.vertexMask()[vertex / 8] & 1 << (vertex % 8)) == 0) {
                    continue;
                }
                var at = vertex * 6;
                var px = vertices[at];
                var py = vertices[at + 1];
                var pz = vertices[at + 2];
                var nx = vertices[at + 3];
                var ny = vertices[at + 4];
                var nz = vertices[at + 5];
                var dx = (localLight[0] - px) * Math.abs(drawScale.getX());
                var dy = (localLight[1] - py) * Math.abs(drawScale.getY());
                var dz = (localLight[2] - pz) * Math.abs(drawScale.getZ());
                if (!(ny * dy + nx * dx + nz * dz > 0)) {
                    continue;
                }
                var q = project(vertices, tangents, vertex, dx, dy, dz);
                var world = transform(localToWorld, px, py, pz);
                var intensity = actorIntensity
                        * .5f
                        * directIntensity(
                                source,
                                source.position().getX() - world[0],
                                source.position().getY() - world[1],
                                source.position().getZ() - world[2]);
                var red = source.color().getX() * intensity;
                var green = source.color().getY() * intensity;
                var blue = source.color().getZ() * intensity;
                accumulate(output, vertex, q, red, green, blue);
            }
            applied[index] = 1;
        }
        return new Result(output, applied);
    }

    public static @Nullable Result updateEnvironment(
            float[] vertices,
            float[] tangents,
            float[] base,
            Vec3 managerColor,
            List<EnvironmentLight> lights,
            IntSupplier appRand) {
        return updateEnvironment(
                vertices,
                tangents,
                base,
                managerColor,
                lights,
                appRand,
                index -> lights.get(index).cached());
    }

    public static @Nullable Result updateEnvironment(
            float[] vertices,
            float[] tangents,
            float[] base,
            Vec3 managerColor,
            List<EnvironmentLight> lights,
            IntSupplier appRand,
            IntFunction<@Nullable EnvironmentSource> cachedSource) {
        if (lights.isEmpty()) {
            return null;
        }
        var count = vertices.length / 6;
        if (vertices.length % 6 != 0 || tangents.length != count * 4 || base.length != count * 9) {
            throw new IllegalArgumentException("environment vertex-lightmap stream dimensions");
        }
        var random = appRand.getAsInt();
        if (random < 0 || random > 32767) {
            throw new IllegalArgumentException("appRand result outside 15-bit range");
        }
        var first = random % lights.size();
        var output = base.clone();
        var applied = new int[lights.size()];
        for (var index = 0; index < lights.size(); index++) {
            applied[index] = lights.get(index).applied();
        }
        for (var slot = 0; slot < 2; slot++) {
            var index = (first + slot) % lights.size();
            var record = lights.get(index);
            var source = cachedSource.apply(index);
            if (source == null || source.dynamic() || source.changed()) {
                applied[index] = 0;
                continue;
            }
            if (record.vertexMask().length < (count + 7) / 8) {
                throw new IllegalArgumentException("environment visibility bitmap is shorter than the mesh");
            }
            var weight = slot == 0 ? 1f : 0f;
            var red = managerColor.getX() * weight;
            var green = managerColor.getY() * weight;
            var blue = managerColor.getZ() * weight;
            for (var vertex = 0; vertex < count; vertex++) {
                if ((record.vertexMask()[vertex / 8] & 1 << (vertex % 8)) == 0) {
                    continue;
                }
                var q = project(
                        vertices,
                        tangents,
                        vertex,
                        -source.direction().getX(),
                        -source.direction().getY(),
                        -source.direction().getZ());
                accumulate(output, vertex, q, red, green, blue);
            }
            applied[index] = 1;
        }
        return new Result(output, applied);
    }

    public static float directIntensity(Source light, float x, float y, float z) {
        if (light.effect() == 19) {
            return 0;
        }
        var square = (x * x + y * y) + z * z;
        var radius = light.radius();
        if (light.effect() == 20) {
            var radiusSquare = radius * radius;
            return square < radiusSquare ? (1.02f - square / radiusSquare) * 2f : 0f;
        }
        var distance = (float) Math.sqrt(square);
        if (light.effect() == 17) {
            if (!(distance < radius)) {
                return 0;
            }
            var value = (float) (1.0 - (x * x + y * y) / (radius * radius));
            return (value > 0 ? value : 0f) * 2f;
        }
        if (light.effect() == 13) {
            return distance < radius ? (float) (Math.sqrt(1.02f - distance / radius) * 2) : 0f;
        }
        if (radius < distance) {
            return 0;
        }
        var ratio = distance / radius;
        var radial = ((ratio * 2f * ratio * ratio) - (ratio * 3f * ratio) + 1f) * 2f;
        if (light.effect() != 8 && light.effect() != 12) {
            return radial;
        }
        if (!(radial > 0)) {
            return 0;
        }
        var threshold = (float) (1.0 - light.cone() * .00390625);
        var reciprocal = (float) (1.0 / (1.0 - threshold));
        var dot = light.direction().getY() * -y
                + light.direction().getX() * -x
                + light.direction().getZ() * -z;
        if (!(dot > 0 && threshold * threshold * square < dot * dot)) {
            return 0;
        }
        var cone = dot * reciprocal / distance - reciprocal * threshold;
        return cone * cone * radial;
    }

    private static float[] project(float[] vertices, float[] tangents, int vertex, float dx, float dy, float dz) {
        var at = vertex * 6;
        var nx = vertices[at + 3];
        var ny = vertices[at + 4];
        var nz = vertices[at + 5];
        var normal = safeNormal(nx, ny, nz);
        var tx = tangents[vertex * 4];
        var ty = tangents[vertex * 4 + 1];
        var tz = tangents[vertex * 4 + 2];
        var bx = tz * ny - ty * nz;
        var by = tx * nz - nx * tz;
        var bz = nx * ty - tx * ny;
        var q = new float[] {
            ty * dy + tx * dx + dz * tz, by * dy + bx * dx + bz * dz, normal[1] * dy + normal[0] * dx + normal[2] * dz
        };
        normalize(q);
        return q;
    }

    private static void accumulate(float[] output, int vertex, float[] q, float red, float green, float blue) {
        for (var basis = 0; basis < 3; basis++) {
            var b = basis * 3;
            var dot = BASIS[b + 1] * q[1] + BASIS[b] * q[0] + BASIS[b + 2] * q[2];
            var weight = dot > 0 ? dot : 0f;
            var out = vertex * 9 + b;
            output[out] += red * weight * .75f;
            output[out + 1] += green * weight * .75f;
            output[out + 2] += blue * weight * .75f;
        }
    }

    private static float[] transform(float[] matrix, float x, float y, float z) {
        return new float[] {
            matrix[4] * y + x * matrix[0] + matrix[8] * z + matrix[12],
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        };
    }

    private static float[] safeNormal(float x, float y, float z) {
        var square = (x * x + y * y) + z * z;
        if (square == 1) {
            return new float[] {x, y, z};
        }
        if (square < 1e-8) {
            return new float[3];
        }
        var inverse = (float) (1.0 / Math.sqrt(square));
        return new float[] {x * inverse, y * inverse, z * inverse};
    }

    private static void normalize(float[] vector) {
        var square = (vector[0] * vector[0] + vector[1] * vector[1]) + vector[2] * vector[2];
        if ((double) square < 1e-8 || Float.isNaN(square)) {
            return;
        }
        var inverse = (float) (1.0 / Math.sqrt(square));
        for (var index = 0; index < 3; index++) {
            vector[index] *= inverse;
        }
    }
}
