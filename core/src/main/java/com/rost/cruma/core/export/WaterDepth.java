package com.rost.cruma.core.export;

import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.UMap;
import java.util.Arrays;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class WaterDepth {
    static final int RESOLUTION = 1024;

    private static final float UNKNOWN = 180 / 255f;

    private static final double[] FADE = {.01, .001, .001};

    private static final int[] REFERENCE = {38, 56, 64};

    private static final float[] FLOOR = {.45f, .42f, .38f};

    private final float minX;
    private final float minY;
    private final float size;
    private final int resolution;

    private WaterDepth(float minX, float minY, float size, int resolution) {
        this.minX = minX;
        this.minY = minY;
        this.size = size;
        this.resolution = resolution;
    }

    record Result(String tile, RgbaImage image, float minX, float minY, float size) {
        float u(float x) {
            return (x - minX) / size;
        }

        float v(float y) {
            return (y - minY) / size;
        }
    }

    static @Nullable Result bake(
            UMap map,
            float minX,
            float minY,
            float size,
            int resolution,
            Predicate<Surface> water,
            TerrainBake.@Nullable Result floor) {
        return new WaterDepth(minX, minY, size, resolution).run(map, water, floor);
    }

    static float opacity(float depth) {
        return depth <= 0 ? 0 : (float) (1 - keptShare(depth, 0));
    }

    static float[] color(float depth, float[] floor) {
        var alpha = opacity(depth);
        var result = new float[3];
        if (alpha <= 0) {
            return result;
        }
        for (var channel = 0; channel < 3; channel++) {
            var keptHere = keptShare(depth, channel);
            result[channel] = (float) Math.clamp(floor[channel] * (keptHere - (1 - alpha)) / alpha, 0, 1);
        }
        return result;
    }

    private @Nullable Result run(UMap map, Predicate<Surface> water, TerrainBake.@Nullable Result floor) {
        var ground = grid();
        var surface = grid();
        var any = false;
        var corner = new float[9];
        for (var entity : map.entities()) {
            var matrix = entity.getModelMatrix().to(new float[16]);
            switch (entity.getMesh()) {
                case TerrainMesh terrain -> {
                    var vertices = terrain.vertexBuffer();
                    var indices = terrain.indices();
                    for (var offset = 0; offset + 2 < indices.length; offset += 3) {
                        for (var point = 0; point < 3; point++) {
                            var index = indices[offset + point];
                            transform(
                                    matrix,
                                    vertices.getPositionX(index),
                                    vertices.getPositionY(index),
                                    vertices.getPositionZ(index),
                                    corner,
                                    point);
                        }
                        rasterizeTriangle(ground, corner);
                    }
                }
                case EntityMesh mesh -> {
                    if (entity.isWireframe() || entity.isZoneLookCopy()) {
                        continue;
                    }
                    var vertices = mesh.vertexBuffer();
                    var indices = mesh.indices();
                    for (var surfaceOf : mesh.surfaces()) {
                        if (!water.test(surfaceOf)) {
                            continue;
                        }
                        any = true;
                        for (var instance : entity.getInstanceMatrices()) {
                            var placed = entity.getModelMatrix().times(instance).to(new float[16]);
                            var end = Math.min(indices.length, surfaceOf.indexOffset() + surfaceOf.indexCount());
                            for (var offset = surfaceOf.indexOffset(); offset + 2 < end; offset += 3) {
                                for (var point = 0; point < 3; point++) {
                                    var index = indices[offset + point];
                                    transform(
                                            placed,
                                            vertices.getPositionX(index),
                                            vertices.getPositionY(index),
                                            vertices.getPositionZ(index),
                                            corner,
                                            point);
                                }
                                rasterizeTriangle(surface, corner);
                            }
                        }
                    }
                }
            }
        }
        if (!any) {
            return null;
        }
        var rgba = new byte[Math.multiplyExact(Math.multiplyExact(resolution, resolution), 4)];
        var sample = new float[4];
        for (var texel = 0; texel < surface.length; texel++) {
            var offset = texel * 4;
            if (Float.isNaN(surface[texel]) || Float.isNaN(ground[texel])) {
                for (var channel = 0; channel < 3; channel++) {
                    rgba[offset + channel] = (byte) REFERENCE[channel];
                }
                rgba[offset + 3] = RgbaImage.toByte(UNKNOWN);
                continue;
            }
            var floorColor = FLOOR;
            if (floor != null) {
                var x = minX + (texel % resolution + .5f) * size / resolution;
                var y = minY + (texel / resolution + .5f) * size / resolution;
                floor.image().sample(floor.u(x), floor.v(y), false, sample);
                floorColor = sample;
            }
            var depth = surface[texel] - ground[texel];
            var color = color(depth, floorColor);
            for (var channel = 0; channel < 3; channel++) {
                rgba[offset + channel] = RgbaImage.toByte(color[channel]);
            }
            rgba[offset + 3] = RgbaImage.toByte(opacity(depth));
        }
        return new Result(map.name(), new RgbaImage(resolution, resolution, rgba), minX, minY, size);
    }

    private float[] grid() {
        var grid = new float[resolution * resolution];
        Arrays.fill(grid, Float.NaN);
        return grid;
    }

    private void rasterizeTriangle(float[] grid, float[] corner) {
        var scale = resolution / size;
        var ax = (corner[0] - minX) * scale;
        var ay = (corner[1] - minY) * scale;
        var bx = (corner[3] - minX) * scale;
        var by = (corner[4] - minY) * scale;
        var cx = (corner[6] - minX) * scale;
        var cy = (corner[7] - minY) * scale;
        var area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1e-9f) {
            return;
        }
        var left = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        var right = Math.min(resolution - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        var top = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        var bottom = Math.min(resolution - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        for (var y = top; y <= bottom; y++) {
            var py = y + .5f;
            for (var x = left; x <= right; x++) {
                var px = x + .5f;
                var w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
                var w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
                var w2 = 1f - w0 - w1;
                if (w0 < -1e-5f || w1 < -1e-5f || w2 < -1e-5f) {
                    continue;
                }
                var height = w0 * corner[2] + w1 * corner[5] + w2 * corner[8];
                var index = y * resolution + x;
                if (Float.isNaN(grid[index]) || height > grid[index]) {
                    grid[index] = height;
                }
            }
        }
    }

    private static double keptShare(float depth, int channel) {
        return Math.pow(2, -FADE[channel] * depth);
    }

    private static void transform(float[] m, float x, float y, float z, float[] out, int point) {
        out[point * 3] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[point * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[point * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }
}
