package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnModelData.Kind;
import com.rost.cruma.viewer.spawn.SpawnModelData.Part;
import java.util.Arrays;

public final class NpcPreviewImage {
    public static final double AZIMUTH = Math.toRadians(28);

    private static final int SAMPLES = 2;
    private static final double ELEVATION = Math.toRadians(12);

    private NpcPreviewImage() {}

    public static byte[] render(SpawnModelData model, int width, int height) {
        return render(model, width, height, AZIMUTH);
    }

    public static byte[] render(SpawnModelData model, int width, int height, double azimuth) {
        var w = width * SAMPLES;
        var h = height * SAMPLES;
        var color = new float[w * h * 4];
        var depth = new float[w * h];
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
        var cosE = Math.cos(ELEVATION);
        var fx = (float) (-cosE * Math.cos(azimuth));
        var fy = (float) (-cosE * Math.sin(azimuth));
        var fz = (float) -Math.sin(ELEVATION);
        var rLength = (float) Math.hypot(fy, fx);
        var rx = fy / rLength;
        var ry = -fx / rLength;
        var ux = ry * fz;
        var uy = -rx * fz;
        var uz = rx * fy - ry * fx;
        var vertices = model.vertices();
        var count = vertices.length / SpawnModelData.FLOATS;
        var minX = Float.MAX_VALUE;
        var maxX = -Float.MAX_VALUE;
        var minY = Float.MAX_VALUE;
        var maxY = -Float.MAX_VALUE;
        var minZ = Float.MAX_VALUE;
        var maxZ = -Float.MAX_VALUE;
        for (var vertex = 0; vertex < count; vertex++) {
            var at = vertex * SpawnModelData.FLOATS;
            minX = Math.min(minX, vertices[at]);
            maxX = Math.max(maxX, vertices[at]);
            minY = Math.min(minY, -vertices[at + 1]);
            maxY = Math.max(maxY, -vertices[at + 1]);
            minZ = Math.min(minZ, vertices[at + 2]);
            maxZ = Math.max(maxZ, vertices[at + 2]);
        }
        var middleX = (minX + maxX) * .5f;
        var middleY = (minY + maxY) * .5f;
        var middleZ = (minZ + maxZ) * .5f;
        var radius = 1e-3f;
        for (var vertex = 0; vertex < count; vertex++) {
            var at = vertex * SpawnModelData.FLOATS;
            radius = Math.max(radius, (float) Math.hypot(vertices[at] - middleX, -vertices[at + 1] - middleY));
        }
        var tall = (maxZ - minZ) * (float) cosE + 2 * radius * (float) Math.sin(ELEVATION);
        var scale = Math.min(w * .9f / (2 * radius), h * .9f / Math.max(1e-3f, tall));
        var centerX = middleX * rx + middleY * ry;
        var centerY = middleX * ux + middleY * uy + middleZ * uz;
        var sx = new float[count];
        var sy = new float[count];
        var sz = new float[count];
        for (var vertex = 0; vertex < count; vertex++) {
            var at = vertex * SpawnModelData.FLOATS;
            var x = vertices[at];
            var y = -vertices[at + 1];
            var z = vertices[at + 2];
            sx[vertex] = w * .5f + (x * rx + y * ry - centerX) * scale;
            sy[vertex] = h * .5f - (x * ux + y * uy + z * uz - centerY) * scale;
            sz[vertex] = x * fx + y * fy + z * fz;
        }
        var lx = -fx * .5f - rx * .45f + ux * .7f;
        var ly = -fy * .5f - ry * .45f + uy * .7f;
        var lz = -fz * .5f + uz * .7f;
        var lLength = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        var light = new float[] {lx / lLength, ly / lLength, lz / lLength};
        for (var pass = 0; pass < 2; pass++) {
            for (var part : model.parts()) {
                if ((part.kind() == Kind.BLENDED) != (pass == 1)) {
                    continue;
                }
                var indices = model.indices();
                var end = Math.min(indices.length, part.indexOffset() + part.indexCount());
                for (var offset = part.indexOffset(); offset + 2 < end; offset += 3) {
                    rasterizeTriangle(
                            model,
                            part,
                            indices[offset],
                            indices[offset + 1],
                            indices[offset + 2],
                            sx,
                            sy,
                            sz,
                            light,
                            w,
                            h,
                            color,
                            depth);
                }
            }
        }
        var result = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                float r = 0;
                float g = 0;
                float b = 0;
                float a = 0;
                for (var dy = 0; dy < SAMPLES; dy++) {
                    for (var dx = 0; dx < SAMPLES; dx++) {
                        var at = ((y * SAMPLES + dy) * w + x * SAMPLES + dx) * 4;
                        r += color[at];
                        g += color[at + 1];
                        b += color[at + 2];
                        a += color[at + 3];
                    }
                }
                var out = (y * width + x) * 4;
                var alpha = a / (SAMPLES * SAMPLES);
                result[out] = (byte) Math.round(alpha > 0 ? Math.min(1, r / a) * 255 : 0);
                result[out + 1] = (byte) Math.round(alpha > 0 ? Math.min(1, g / a) * 255 : 0);
                result[out + 2] = (byte) Math.round(alpha > 0 ? Math.min(1, b / a) * 255 : 0);
                result[out + 3] = (byte) Math.round(Math.min(1, alpha) * 255);
            }
        }
        return result;
    }

    private static void rasterizeTriangle(
            SpawnModelData model,
            Part part,
            int a,
            int b,
            int c,
            float[] sx,
            float[] sy,
            float[] sz,
            float[] light,
            int w,
            int h,
            float[] color,
            float[] depth) {
        var area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
        if (Math.abs(area) < 1e-6f) {
            return;
        }
        var left = Math.max(0, (int) Math.floor(Math.min(sx[a], Math.min(sx[b], sx[c]))));
        var right = Math.min(w - 1, (int) Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[c]))));
        var top = Math.max(0, (int) Math.floor(Math.min(sy[a], Math.min(sy[b], sy[c]))));
        var bottom = Math.min(h - 1, (int) Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[c]))));
        var vertices = model.vertices();
        var blended = part.kind() == Kind.BLENDED;
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
                var z = wa * sz[a] + wb * sz[b] + wc * sz[c];
                var pixel = y * w + x;
                if (z >= depth[pixel]) {
                    continue;
                }
                var u = interpolate(vertices, a, b, c, 6, wa, wb, wc);
                var v = interpolate(vertices, a, b, c, 7, wa, wb, wc);
                float red;
                float green;
                float blue;
                float alpha;
                if (part.rgba() == null) {
                    red = .6f;
                    green = .6f;
                    blue = .6f;
                    alpha = 1;
                } else {
                    var tx = Math.floorMod((int) Math.floor(u * part.textureWidth()), part.textureWidth());
                    var ty = Math.floorMod((int) Math.floor(v * part.textureHeight()), part.textureHeight());
                    var at = (ty * part.textureWidth() + tx) * 4;
                    red = Byte.toUnsignedInt(part.rgba()[at]) / 255f;
                    green = Byte.toUnsignedInt(part.rgba()[at + 1]) / 255f;
                    blue = Byte.toUnsignedInt(part.rgba()[at + 2]) / 255f;
                    alpha = part.kind() == Kind.OPAQUE ? 1 : Byte.toUnsignedInt(part.rgba()[at + 3]) / 255f;
                }
                if (part.kind() == Kind.MASKED && alpha < .5f) {
                    continue;
                }
                if (part.kind() == Kind.MASKED) {
                    alpha = 1;
                } else if (blended && alpha < part.alphaRef()) {
                    continue;
                }
                var nx = interpolate(vertices, a, b, c, 3, wa, wb, wc);
                var ny = -interpolate(vertices, a, b, c, 4, wa, wb, wc);
                var nz = interpolate(vertices, a, b, c, 5, wa, wb, wc);
                var length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                var lit = length > 1e-6f ? Math.abs(nx * light[0] + ny * light[1] + nz * light[2]) / length : 1;
                var shade = .42f + .7f * lit;
                var at = pixel * 4;
                var kept = 1 - alpha;
                color[at] = red * shade * alpha + color[at] * kept;
                color[at + 1] = green * shade * alpha + color[at + 1] * kept;
                color[at + 2] = blue * shade * alpha + color[at + 2] * kept;
                color[at + 3] = alpha + color[at + 3] * kept;
                if (part.depthWrite()) {
                    depth[pixel] = z;
                }
            }
        }
    }

    private static float interpolate(float[] vertices, int a, int b, int c, int field, float wa, float wb, float wc) {
        return vertices[a * SpawnModelData.FLOATS + field] * wa
                + vertices[b * SpawnModelData.FLOATS + field] * wb
                + vertices[c * SpawnModelData.FLOATS + field] * wc;
    }
}
