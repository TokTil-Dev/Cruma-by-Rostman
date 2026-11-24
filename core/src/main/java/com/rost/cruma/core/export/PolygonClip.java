package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationModel.Primitive;
import java.util.ArrayList;
import java.util.Arrays;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

final class PolygonClip {
    private static final int FLOATS = 17;

    private PolygonClip() {}

    static @Nullable Primitive clip(Primitive primitive, float[] bounds) {
        var positions = primitive.positions();
        var count = positions.length / 3;
        var tile = primitive.tileUvs() != null;
        var lit = primitive.light() != null;
        var lightmapped = primitive.lightmap() != null;
        var outPositions = new FloatArrayList();
        var outNormals = new FloatArrayList();
        var outUvs = new FloatArrayList();
        var outTileUvs = new FloatArrayList();
        var outLight = new FloatArrayList();
        var outLightmap = new FloatArrayList();
        var outIndices = new IntArrayList();
        var remap = new int[count];
        Arrays.fill(remap, -1);
        var indices = primitive.indices();
        for (var offset = 0; offset + 2 < indices.length; offset += 3) {
            var a = indices[offset];
            var b = indices[offset + 1];
            var c = indices[offset + 2];
            if (isInside(positions, a, bounds) && isInside(positions, b, bounds) && isInside(positions, c, bounds)) {
                outIndices.add(
                        keep(primitive, a, remap, outPositions, outNormals, outUvs, outTileUvs, outLight, outLightmap));
                outIndices.add(
                        keep(primitive, b, remap, outPositions, outNormals, outUvs, outTileUvs, outLight, outLightmap));
                outIndices.add(
                        keep(primitive, c, remap, outPositions, outNormals, outUvs, outTileUvs, outLight, outLightmap));
                continue;
            }
            var polygon = new float[][] {readVertex(primitive, a), readVertex(primitive, b), readVertex(primitive, c)};
            polygon = cut(polygon, 0, bounds[0], true);
            polygon = cut(polygon, 0, bounds[2], false);
            polygon = cut(polygon, 2, bounds[1], true);
            polygon = cut(polygon, 2, bounds[3], false);
            if (polygon.length < 3) {
                continue;
            }
            var first = outPositions.size() / 3;
            for (var point : polygon) {
                add(point, outPositions, outNormals, outUvs, outTileUvs, outLight, outLightmap);
            }
            for (var corner = 1; corner + 1 < polygon.length; corner++) {
                outIndices.add(first);
                outIndices.add(first + corner);
                outIndices.add(first + corner + 1);
            }
        }
        if (outIndices.isEmpty()) {
            return null;
        }
        return new Primitive(
                outPositions.toArray(),
                outNormals.toArray(),
                outUvs.toArray(),
                tile ? outTileUvs.toArray() : null,
                outIndices.toArray(),
                primitive.material(),
                lit ? outLight.toArray() : null,
                lightmapped ? outLightmap.toArray() : null);
    }

    private static boolean isInside(float[] positions, int vertex, float[] bounds) {
        var x = positions[vertex * 3];
        var z = positions[vertex * 3 + 2];
        return x >= bounds[0] && x <= bounds[2] && z >= bounds[1] && z <= bounds[3];
    }

    private static int keep(
            Primitive primitive,
            int vertex,
            int[] remap,
            FloatArrayList positions,
            FloatArrayList normals,
            FloatArrayList uvs,
            FloatArrayList tileUvs,
            FloatArrayList light,
            FloatArrayList lightmap) {
        if (remap[vertex] < 0) {
            remap[vertex] = positions.size() / 3;
            add(readVertex(primitive, vertex), positions, normals, uvs, tileUvs, light, lightmap);
        }
        return remap[vertex];
    }

    private static float[] readVertex(Primitive primitive, int vertex) {
        var result = new float[FLOATS];
        System.arraycopy(primitive.positions(), vertex * 3, result, 0, 3);
        System.arraycopy(primitive.normals(), vertex * 3, result, 3, 3);
        System.arraycopy(primitive.uvs(), vertex * 2, result, 6, 2);
        if (primitive.tileUvs() != null) {
            System.arraycopy(primitive.tileUvs(), vertex * 2, result, 8, 2);
        }
        if (primitive.light() != null) {
            System.arraycopy(primitive.light(), vertex * 4, result, 10, 4);
        }
        if (primitive.lightmap() != null) {
            System.arraycopy(primitive.lightmap(), vertex * 3, result, 14, 3);
        }
        return result;
    }

    private static void add(
            float[] point,
            FloatArrayList positions,
            FloatArrayList normals,
            FloatArrayList uvs,
            FloatArrayList tileUvs,
            FloatArrayList light,
            FloatArrayList lightmap) {
        positions.add(point[0]);
        positions.add(point[1]);
        positions.add(point[2]);
        var length = (float) Math.sqrt(point[3] * point[3] + point[4] * point[4] + point[5] * point[5]);
        var scale = length > 1e-12f ? 1 / length : 1;
        normals.add(point[3] * scale);
        normals.add(point[4] * scale);
        normals.add(point[5] * scale);
        uvs.add(point[6]);
        uvs.add(point[7]);
        tileUvs.add(point[8]);
        tileUvs.add(point[9]);
        for (var value = 10; value < 14; value++) {
            light.add(point[value]);
        }
        for (var value = 14; value < 17; value++) {
            lightmap.add(point[value]);
        }
    }

    private static float[][] cut(float[][] polygon, int axis, float limit, boolean keepAbove) {
        if (polygon.length == 0) {
            return polygon;
        }
        var result = new ArrayList<float[]>(polygon.length + 2);
        for (var index = 0; index < polygon.length; index++) {
            var current = polygon[index];
            var next = polygon[(index + 1) % polygon.length];
            var currentIn = keepAbove ? current[axis] >= limit : current[axis] <= limit;
            var nextIn = keepAbove ? next[axis] >= limit : next[axis] <= limit;
            if (currentIn) {
                result.add(current);
            }
            if (currentIn != nextIn) {
                var t = (limit - current[axis]) / (next[axis] - current[axis]);
                var point = new float[FLOATS];
                for (var value = 0; value < FLOATS; value++) {
                    point[value] = current[value] + (next[value] - current[value]) * t;
                }
                point[axis] = limit;
                result.add(point);
            }
        }
        return result.toArray(new float[0][]);
    }
}
