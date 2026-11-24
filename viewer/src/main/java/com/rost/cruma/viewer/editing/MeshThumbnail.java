package com.rost.cruma.viewer.editing;

import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

final class MeshThumbnail {
    private static final int SAMPLES = 2;
    private static final double AZIMUTH = Math.toRadians(35);
    private static final double ELEVATION = Math.toRadians(28);

    private MeshThumbnail() {}

    record Cutout(int width, int height, byte[] alpha) {
        boolean isHole(float u, float v) {
            var x = Math.floorMod((int) Math.floor(u * width), width);
            var y = Math.floorMod((int) Math.floor(v * height), height);
            return (alpha[y * width + x] & 0xFF) < 128;
        }
    }

    static byte[] render(float[] positions, int[] indices, int size) {
        return render(positions, indices, null, null, size);
    }

    static byte[] render(
            float[] positions, int[] indices, float @Nullable [] uvs, Cutout @Nullable [] cutouts, int size) {
        var w = size * SAMPLES;
        var color = new float[w * w * 4];
        var depth = new float[w * w];
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
        var count = positions.length / 3;
        if (count == 0) {
            return new byte[size * size * 4];
        }
        var cosE = Math.cos(ELEVATION);
        var fx = (float) (-cosE * Math.cos(AZIMUTH));
        var fy = (float) (-cosE * Math.sin(AZIMUTH));
        var fz = (float) -Math.sin(ELEVATION);
        var rLength = (float) Math.hypot(fy, fx);
        var rx = fy / rLength;
        var ry = -fx / rLength;
        var ux = ry * fz;
        var uy = -rx * fz;
        var uz = rx * fy - ry * fx;
        var sx = new float[count];
        var sy = new float[count];
        var sz = new float[count];
        var minX = Float.MAX_VALUE;
        var maxX = -Float.MAX_VALUE;
        var minY = Float.MAX_VALUE;
        var maxY = -Float.MAX_VALUE;
        for (var vertex = 0; vertex < count; vertex++) {
            var x = positions[vertex * 3];
            var y = -positions[vertex * 3 + 1];
            var z = positions[vertex * 3 + 2];
            sx[vertex] = x * rx + y * ry;
            sy[vertex] = -(x * ux + y * uy + z * uz);
            sz[vertex] = x * fx + y * fy + z * fz;
            minX = Math.min(minX, sx[vertex]);
            maxX = Math.max(maxX, sx[vertex]);
            minY = Math.min(minY, sy[vertex]);
            maxY = Math.max(maxY, sy[vertex]);
        }
        var scale = w * .86f / Math.max(1e-3f, Math.max(maxX - minX, maxY - minY));
        var middleX = (minX + maxX) * .5f;
        var middleY = (minY + maxY) * .5f;
        for (var vertex = 0; vertex < count; vertex++) {
            sx[vertex] = w * .5f + (sx[vertex] - middleX) * scale;
            sy[vertex] = w * .5f + (sy[vertex] - middleY) * scale;
        }
        var lx = -fx * .5f - rx * .45f + ux * .7f;
        var ly = -fy * .5f - ry * .45f + uy * .7f;
        var lz = -fz * .5f + uz * .7f;
        var lLength = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= lLength;
        ly /= lLength;
        lz /= lLength;
        for (var offset = 0; offset + 2 < indices.length; offset += 3) {
            var a = indices[offset];
            var b = indices[offset + 1];
            var c = indices[offset + 2];
            if (a < 0 || b < 0 || c < 0 || a >= count || b >= count || c >= count) {
                continue;
            }
            var ax = positions[a * 3];
            var ay = -positions[a * 3 + 1];
            var az = positions[a * 3 + 2];
            var e1x = positions[b * 3] - ax;
            var e1y = -positions[b * 3 + 1] - ay;
            var e1z = positions[b * 3 + 2] - az;
            var e2x = positions[c * 3] - ax;
            var e2y = -positions[c * 3 + 1] - ay;
            var e2z = positions[c * 3 + 2] - az;
            var nx = e1y * e2z - e1z * e2y;
            var ny = e1z * e2x - e1x * e2z;
            var nz = e1x * e2y - e1y * e2x;
            var length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            var lit = length > 1e-9f ? Math.abs(nx * lx + ny * ly + nz * lz) / length : 1;
            var shade = Math.min(1, .38f + .62f * lit);
            var cutout = cutouts == null || uvs == null || offset / 3 >= cutouts.length ? null : cutouts[offset / 3];
            fill(sx, sy, sz, a, b, c, shade, w, color, depth, cutout == null ? null : uvs, cutout);
        }
        var result = new byte[size * size * 4];
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                float r = 0, g = 0, bl = 0, al = 0;
                for (var dy = 0; dy < SAMPLES; dy++) {
                    for (var dx = 0; dx < SAMPLES; dx++) {
                        var at = ((y * SAMPLES + dy) * w + x * SAMPLES + dx) * 4;
                        r += color[at];
                        g += color[at + 1];
                        bl += color[at + 2];
                        al += color[at + 3];
                    }
                }
                var out = (y * size + x) * 4;
                var alpha = al / (SAMPLES * SAMPLES);
                result[out] = (byte) Math.round(al > 0 ? Math.min(1, r / al) * 255 : 0);
                result[out + 1] = (byte) Math.round(al > 0 ? Math.min(1, g / al) * 255 : 0);
                result[out + 2] = (byte) Math.round(al > 0 ? Math.min(1, bl / al) * 255 : 0);
                result[out + 3] = (byte) Math.round(Math.min(1, alpha) * 255);
            }
        }
        return result;
    }

    private static void fill(
            float[] sx,
            float[] sy,
            float[] sz,
            int a,
            int b,
            int c,
            float shade,
            int w,
            float[] color,
            float[] depth,
            float @Nullable [] uvs,
            @Nullable Cutout cutout) {
        var area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
        if (Math.abs(area) < 1e-6f) {
            return;
        }
        var left = Math.max(0, (int) Math.floor(Math.min(sx[a], Math.min(sx[b], sx[c]))));
        var right = Math.min(w - 1, (int) Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[c]))));
        var top = Math.max(0, (int) Math.floor(Math.min(sy[a], Math.min(sy[b], sy[c]))));
        var bottom = Math.min(w - 1, (int) Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[c]))));
        var red = .78f * shade;
        var green = .83f * shade;
        var blue = .88f * shade;
        for (var y = top; y <= bottom; y++) {
            var py = y + .5f;
            for (var x = left; x <= right; x++) {
                var px = x + .5f;
                var wa = ((sx[b] - px) * (sy[c] - py) - (sx[c] - px) * (sy[b] - py)) / area;
                var wb = ((sx[c] - px) * (sy[a] - py) - (sx[a] - px) * (sy[c] - py)) / area;
                var wc = 1 - wa - wb;
                if (wa < 0 || wb < 0 || wc < 0) {
                    continue;
                }
                if (cutout != null
                        && uvs != null
                        && cutout.isHole(
                                wa * uvs[a * 2] + wb * uvs[b * 2] + wc * uvs[c * 2],
                                wa * uvs[a * 2 + 1] + wb * uvs[b * 2 + 1] + wc * uvs[c * 2 + 1])) {
                    continue;
                }
                var z = wa * sz[a] + wb * sz[b] + wc * sz[c];
                var pixel = y * w + x;
                if (z >= depth[pixel]) {
                    continue;
                }
                depth[pixel] = z;
                var at = pixel * 4;
                color[at] = red;
                color[at + 1] = green;
                color[at + 2] = blue;
                color[at + 3] = 1;
            }
        }
    }
}
