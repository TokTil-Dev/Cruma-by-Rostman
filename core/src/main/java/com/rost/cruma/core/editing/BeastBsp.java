package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BspLightmapTail.Lightmap;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.Bsp.UModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

final class BeastBsp {
    static final int COVER_RINGS = 1;

    static final float CENTER = .5f;

    private BeastBsp() {}

    record Record(
            int index,
            int texture,
            int surface,
            int offsetX,
            int offsetY,
            int width,
            int height,
            float[] base,
            float[] axisX,
            float[] axisY,
            float[] normal,
            boolean[] covered) {
        boolean world(int x, int y, float center, float[] position, float[] normalOut) {
            if (x < 0 || y < 0 || x >= width || y >= height || !covered[y * width + x]) {
                return false;
            }
            var u = x + center;
            var v = y + center;
            for (var axis = 0; axis < 3; axis++) {
                position[axis] = base[axis] + axisX[axis] * u + axisY[axis] * v;
                normalOut[axis] = normal[axis];
            }
            return true;
        }

        int coveredCount() {
            var count = 0;
            for (var value : covered) {
                count += value ? 1 : 0;
            }
            return count;
        }
    }

    static List<Record> findRecords(BspLightmapTail tail, UModel model, BooleanSupplier cancelled) {
        var triangles = new HashMap<Integer, List<Triangle>>();
        for (var triangle : P152BspTriangles.snapshot(model, cancelled)) {
            triangles.computeIfAbsent(triangle.part(), key -> new ArrayList<>()).add(triangle);
        }
        var surfaces = model.getSurfaces();
        var vectors = model.getVectors();
        var result = new ArrayList<Record>();
        for (var index = 0; index < tail.lightmaps.size(); index++) {
            var lightmap = tail.lightmaps.get(index);
            if (!isUsable(tail, lightmap) || lightmap.surface() < 0 || lightmap.surface() >= surfaces.length) {
                continue;
            }
            var surface = surfaces[lightmap.surface()];
            var plane = surface.getPlane();
            var normalIndex = surface.getNormalIndex();
            var normal = vectors != null && normalIndex >= 0 && normalIndex < vectors.length
                    ? new float[] {vectors[normalIndex].getX(), vectors[normalIndex].getY(), vectors[normalIndex].getZ()
                    }
                    : new float[] {plane.getX(), plane.getY(), plane.getZ()};
            var length = (float) Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            if (!(length > 1e-6f)) {
                continue;
            }
            for (var axis = 0; axis < 3; axis++) {
                normal[axis] /= length;
            }
            result.add(new Record(
                    index,
                    lightmap.texture(),
                    lightmap.surface(),
                    lightmap.offsetX(),
                    lightmap.offsetY(),
                    lightmap.sizeX(),
                    lightmap.sizeY(),
                    lightmap.base().clone(),
                    lightmap.axisX().clone(),
                    lightmap.axisY().clone(),
                    normal,
                    computeCoverage(lightmap, triangles.getOrDefault(lightmap.surface(), List.of()))));
        }
        return result;
    }

    static boolean isUsable(BspLightmapTail tail, Lightmap lightmap) {
        if (lightmap.texture() < 0
                || lightmap.texture() >= tail.textures.size()
                || lightmap.sizeX() <= 0
                || lightmap.sizeY() <= 0) {
            return false;
        }
        var variants = tail.textures.get(lightmap.texture()).variants();
        if (variants.size() < 2) {
            return false;
        }
        var first = variants.get(0);
        var second = variants.get(1);
        return first.format() == 3
                && second.format() == 3
                && first.width() == second.width()
                && first.height() == second.height()
                && first.width() > 0
                && first.full().length == BeastTextureCodec.dxt1Size(first.width(), first.height())
                && second.full().length == first.full().length
                && lightmap.offsetX() + lightmap.sizeX() <= first.width()
                && lightmap.offsetY() + lightmap.sizeY() <= first.height();
    }

    static boolean hasTexels(BspLightmapTail tail) {
        for (var lightmap : tail.lightmaps) {
            if (isUsable(tail, lightmap)) {
                return true;
            }
        }
        return false;
    }

    private static boolean[] computeCoverage(Lightmap lightmap, List<Triangle> triangles) {
        var width = lightmap.sizeX();
        var height = lightmap.sizeY();
        var covered = new boolean[width * height];
        var base = lightmap.base();
        var ax = lightmap.axisX();
        var ay = lightmap.axisY();
        var aa = dot(ax, ax);
        var ab = dot(ax, ay);
        var bb = dot(ay, ay);
        var det = aa * bb - ab * ab;
        if (!(Math.abs(det) > 1e-12)) {
            Arrays.fill(covered, true);
            return covered;
        }
        var corners = new double[6];
        for (var triangle : triangles) {
            var points = new Vector[] {triangle.a(), triangle.b(), triangle.c()};
            for (var k = 0; k < 3; k++) {
                var d = new double[] {points[k].x() - base[0], points[k].y() - base[1], points[k].z() - base[2]};
                var da = d[0] * ax[0] + d[1] * ax[1] + d[2] * ax[2];
                var db = d[0] * ay[0] + d[1] * ay[1] + d[2] * ay[2];
                corners[k * 2] = (da * bb - db * ab) / det;
                corners[k * 2 + 1] = (db * aa - da * ab) / det;
            }
            var minX = Math.max(0, (int) Math.floor(Math.min(corners[0], Math.min(corners[2], corners[4]))) - 1);
            var maxX = Math.min(width - 1, (int) Math.ceil(Math.max(corners[0], Math.max(corners[2], corners[4]))) + 1);
            var minY = Math.max(0, (int) Math.floor(Math.min(corners[1], Math.min(corners[3], corners[5]))) - 1);
            var maxY =
                    Math.min(height - 1, (int) Math.ceil(Math.max(corners[1], Math.max(corners[3], corners[5]))) + 1);
            for (var y = minY; y <= maxY; y++) {
                for (var x = minX; x <= maxX; x++) {
                    if (isInsideTriangle(corners, x + .5, y + .5)) {
                        covered[y * width + x] = true;
                    }
                }
            }
        }
        var grown = covered.clone();
        for (var ring = 0; ring < COVER_RINGS; ring++) {
            var next = grown.clone();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    if (grown[y * width + x]) {
                        continue;
                    }
                    for (var dy = -1; dy <= 1 && !next[y * width + x]; dy++) {
                        for (var dx = -1; dx <= 1; dx++) {
                            var nx = x + dx;
                            var ny = y + dy;
                            if (nx >= 0 && ny >= 0 && nx < width && ny < height && grown[ny * width + nx]) {
                                next[y * width + x] = true;
                                break;
                            }
                        }
                    }
                }
            }
            grown = next;
        }
        return grown;
    }

    private static boolean isInsideTriangle(double[] c, double x, double y) {
        var d1 = (x - c[2]) * (c[1] - c[3]) - (c[0] - c[2]) * (y - c[3]);
        var d2 = (x - c[4]) * (c[3] - c[5]) - (c[2] - c[4]) * (y - c[5]);
        var d3 = (x - c[0]) * (c[5] - c[1]) - (c[4] - c[0]) * (y - c[1]);
        var negative = d1 < 0 || d2 < 0 || d3 < 0;
        var positive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(negative && positive);
    }

    private static double dot(float[] a, float[] b) {
        return (double) a[0] * b[0] + (double) a[1] * b[1] + (double) a[2] * b[2];
    }
}
