package com.rost.cruma.core.export;

import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.ZoneStateChoice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class TerrainBake {
    static final int MAX_LAYERS = 32;

    private static final int BAND = 64;

    private final TerrainVertexBuffer vertices;
    private final int[] indices;
    private final int size;
    private final float minX;
    private final float minY;
    private final float spanX;
    private final float spanY;
    private final int layers;
    private final Layer[] layer;
    private final int base;

    private TerrainBake(TerrainMesh mesh, MultiTexture material, int size, Area area, ZoneStateChoice zoneState) {
        this.vertices = mesh.vertexBuffer();
        this.indices = mesh.indices();
        this.size = size;
        this.minX = area.minX();
        this.minY = area.minY();
        this.spanX = area.maxX() - area.minX();
        this.spanY = area.maxY() - area.minY();
        this.layers =
                Math.min(Math.min(MAX_LAYERS, material.entries().size()), vertices.getTextureCoordinateCount() / 2);
        this.layer = new Layer[layers];
        var zones = material.getZoneLayers();
        var hidden = zones.findHiddenLayers(zoneState);
        var first = zones.findBaseLayer(hidden);
        this.base = first < layers ? first : 0;
        for (var index = 0; index < layers; index++) {
            var entry = material.entries().get(index);
            layer[index] = index < base || index != base && (hidden >>> index & 1) != 0
                    ? new Layer(null, false, null, 0, 0)
                    : decodeLayer(entry.texture(), index == base ? null : entry.alphaMap());
        }
    }

    private record Layer(
            @Nullable RgbaImage[] mips, boolean diffuseAlpha, byte @Nullable [] mask, int maskWidth, int maskHeight) {}

    record Area(float minX, float minY, float maxX, float maxY) {
        float u(float x) {
            return (x - minX) / (maxX - minX);
        }

        float v(float y) {
            return (y - minY) / (maxY - minY);
        }
    }

    record Result(RgbaImage image, Area area) {
        float u(float x) {
            return area.u(x);
        }

        float v(float y) {
            return area.v(y);
        }
    }

    static Area findBakeArea(TerrainMesh mesh) {
        var vertices = mesh.vertexBuffer();
        var lowX = Float.MAX_VALUE;
        var lowY = Float.MAX_VALUE;
        var highX = -Float.MAX_VALUE;
        var highY = -Float.MAX_VALUE;
        for (var vertex = 0; vertex < vertices.size(); vertex++) {
            lowX = Math.min(lowX, vertices.getPositionX(vertex));
            lowY = Math.min(lowY, vertices.getPositionY(vertex));
            highX = Math.max(highX, vertices.getPositionX(vertex));
            highY = Math.max(highY, vertices.getPositionY(vertex));
        }
        return new Area(lowX, lowY, lowX + Math.max(1f, highX - lowX), lowY + Math.max(1f, highY - lowY));
    }

    static Result bake(
            TerrainMesh mesh, MultiTexture material, int size, ZoneStateChoice zoneState, BooleanSupplier cancelled) {
        return bake(mesh, material, size, findBakeArea(mesh), zoneState, cancelled);
    }

    static Result bake(
            TerrainMesh mesh,
            MultiTexture material,
            int size,
            Area area,
            ZoneStateChoice zoneState,
            BooleanSupplier cancelled) {
        if (size < 16 || size > 16384) {
            throw new IllegalArgumentException("terrain texture size must be 16..16384");
        }
        return new TerrainBake(mesh, material, size, area, zoneState).run(cancelled);
    }

    private Result run(BooleanSupplier cancelled) {
        var rgba = new byte[Math.multiplyExact(Math.multiplyExact(size, size), 4)];
        var covered = new boolean[size * size];
        var bands = (size + BAND - 1) / BAND;
        var triangles = bandTriangles(bands);
        var pool = ForkJoinPool.commonPool();
        var work = new ArrayList<Future<?>>(bands);
        for (var band = 0; band < bands; band++) {
            var first = band * BAND;
            var last = Math.min(size, first + BAND);
            var list = triangles.get(band);
            work.add(pool.submit(() -> {
                var painter = new Painter(rgba, covered);
                for (var i = 0; i < list.size(); i++) {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    painter.triangle(list.get(i), first, last);
                }
            }));
        }
        for (var future : work) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("terrain bake interrupted");
            } catch (ExecutionException failure) {
                throw new IllegalStateException("terrain bake failed", failure.getCause());
            }
        }
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("terrain bake cancelled");
        }
        fillHoles(rgba, covered);
        return new Result(new RgbaImage(size, size, rgba), new Area(minX, minY, minX + spanX, minY + spanY));
    }

    private List<IntList> bandTriangles(int bands) {
        var result = new ArrayList<IntList>(bands);
        for (var band = 0; band < bands; band++) {
            result.add(new IntList());
        }
        for (var offset = 0; offset + 2 < indices.length; offset += 3) {
            var low = Float.MAX_VALUE;
            var high = -Float.MAX_VALUE;
            for (var corner = 0; corner < 3; corner++) {
                var y = pixelY(indices[offset + corner]);
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
            if (high < 0 || low > size) {
                continue;
            }
            var firstBand = Math.clamp((int) Math.floor(low) / BAND, 0, bands - 1);
            var lastBand = Math.clamp((int) Math.ceil(high) / BAND, 0, bands - 1);
            for (var band = firstBand; band <= lastBand; band++) {
                result.get(band).add(offset);
            }
        }
        return result;
    }

    private float pixelX(int vertex) {
        return (vertices.getPositionX(vertex) - minX) / spanX * size;
    }

    private float pixelY(int vertex) {
        return (vertices.getPositionY(vertex) - minY) / spanY * size;
    }

    private final class Painter {
        private final byte[] rgba;
        private final boolean[] covered;
        private final float[] texel = new float[4];
        private final float[] other = new float[4];
        private final float[] u = new float[3 * MAX_LAYERS];
        private final float[] v = new float[3 * MAX_LAYERS];
        private final float[] level = new float[MAX_LAYERS];

        Painter(byte[] rgba, boolean[] covered) {
            this.rgba = rgba;
            this.covered = covered;
        }

        void triangle(int offset, int firstRow, int lastRow) {
            var a = indices[offset];
            var b = indices[offset + 1];
            var c = indices[offset + 2];
            var ax = pixelX(a);
            var ay = pixelY(a);
            var bx = pixelX(b);
            var by = pixelY(b);
            var cx = pixelX(c);
            var cy = pixelY(c);
            var area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (Math.abs(area) < 1e-6f) {
                return;
            }
            for (var index = 0; index < layers; index++) {
                u[index * 3] = vertices.getTerrainTextureCoordinate(a, index * 2);
                u[index * 3 + 1] = vertices.getTerrainTextureCoordinate(b, index * 2);
                u[index * 3 + 2] = vertices.getTerrainTextureCoordinate(c, index * 2);
                v[index * 3] = vertices.getTerrainTextureCoordinate(a, index * 2 + 1);
                v[index * 3 + 1] = vertices.getTerrainTextureCoordinate(b, index * 2 + 1);
                v[index * 3 + 2] = vertices.getTerrainTextureCoordinate(c, index * 2 + 1);
                level[index] = mipLevel(index, ax, ay, bx, by, cx, cy, area);
            }
            var maskU0 = vertices.getAlphaMapU(a);
            var maskU1 = vertices.getAlphaMapU(b);
            var maskU2 = vertices.getAlphaMapU(c);
            var maskV0 = vertices.getAlphaMapV(a);
            var maskV1 = vertices.getAlphaMapV(b);
            var maskV2 = vertices.getAlphaMapV(c);
            var left = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
            var right = Math.min(size - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
            var top = Math.max(firstRow, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
            var bottom = Math.min(lastRow - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
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
                    var maskU = w0 * maskU0 + w1 * maskU1 + w2 * maskU2;
                    var maskV = w0 * maskV0 + w1 * maskV1 + w2 * maskV2;
                    shade(w0, w1, w2, maskU, maskV, (y * size + x) * 4);
                    covered[y * size + x] = true;
                }
            }
        }

        private void shade(float w0, float w1, float w2, float maskU, float maskV, int target) {
            float r = 0;
            float g = 0;
            float b = 0;
            for (var index = 0; index < layers; index++) {
                var current = layer[index];
                if (current.mips() == null) {
                    continue;
                }
                var weight = 1f;
                if (index > base) {
                    weight = current.mask() == null ? 0f : sampleMask(current, maskU, maskV);
                    if (weight <= 0f) {
                        continue;
                    }
                }
                var layerU = w0 * u[index * 3] + w1 * u[index * 3 + 1] + w2 * u[index * 3 + 2];
                var layerV = w0 * v[index * 3] + w1 * v[index * 3 + 1] + w2 * v[index * 3 + 2];
                sample(current.mips(), level[index], layerU, layerV);
                if (index == base) {
                    r = texel[0];
                    g = texel[1];
                    b = texel[2];
                    continue;
                }
                if (current.diffuseAlpha()) {
                    weight *= texel[3];
                }
                r += (texel[0] - r) * weight;
                g += (texel[1] - g) * weight;
                b += (texel[2] - b) * weight;
            }
            rgba[target] = RgbaImage.toByte(r);
            rgba[target + 1] = RgbaImage.toByte(g);
            rgba[target + 2] = RgbaImage.toByte(b);
            rgba[target + 3] = (byte) 0xFF;
        }

        private void sample(RgbaImage[] mips, float lod, float s, float t) {
            var low = Math.clamp((int) Math.floor(lod), 0, mips.length - 1);
            var high = Math.min(mips.length - 1, low + 1);
            var fraction = Math.clamp(lod - low, 0f, 1f);
            mips[low].sample(s, t, true, texel);
            if (high == low || fraction <= 0f) {
                return;
            }
            mips[high].sample(s, t, true, other);
            for (var channel = 0; channel < 4; channel++) {
                texel[channel] += (other[channel] - texel[channel]) * fraction;
            }
        }

        private float mipLevel(int index, float ax, float ay, float bx, float by, float cx, float cy, float area) {
            var mips = layer[index].mips();
            if (mips == null) {
                return 0;
            }
            var u0 = u[index * 3];
            var u1 = u[index * 3 + 1];
            var u2 = u[index * 3 + 2];
            var v0 = v[index * 3];
            var v1 = v[index * 3 + 1];
            var v2 = v[index * 3 + 2];
            var dudx = ((u1 - u0) * (cy - ay) - (u2 - u0) * (by - ay)) / area;
            var dudy = ((u2 - u0) * (bx - ax) - (u1 - u0) * (cx - ax)) / area;
            var dvdx = ((v1 - v0) * (cy - ay) - (v2 - v0) * (by - ay)) / area;
            var dvdy = ((v2 - v0) * (bx - ax) - (v1 - v0) * (cx - ax)) / area;
            var width = mips[0].width();
            var height = mips[0].height();
            var alongX = Math.hypot(dudx * width, dvdx * height);
            var alongY = Math.hypot(dudy * width, dvdy * height);
            var footprint = Math.max(alongX, alongY);
            return footprint <= 1 ? 0 : (float) (Math.log(footprint) / Math.log(2));
        }
    }

    private void fillHoles(byte[] rgba, boolean[] covered) {
        var any = false;
        for (var flag : covered) {
            if (flag) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        var next = covered.clone();
        for (var pass = 0; pass < 8; pass++) {
            var changed = false;
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    var index = y * size + x;
                    if (covered[index]) {
                        continue;
                    }
                    int r = 0;
                    int g = 0;
                    int b = 0;
                    var count = 0;
                    for (var dy = -1; dy <= 1; dy++) {
                        for (var dx = -1; dx <= 1; dx++) {
                            var nx = x + dx;
                            var ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= size || ny >= size || !covered[ny * size + nx]) {
                                continue;
                            }
                            var neighbor = (ny * size + nx) * 4;
                            r += Byte.toUnsignedInt(rgba[neighbor]);
                            g += Byte.toUnsignedInt(rgba[neighbor + 1]);
                            b += Byte.toUnsignedInt(rgba[neighbor + 2]);
                            count++;
                        }
                    }
                    if (count > 0) {
                        rgba[index * 4] = (byte) (r / count);
                        rgba[index * 4 + 1] = (byte) (g / count);
                        rgba[index * 4 + 2] = (byte) (b / count);
                        rgba[index * 4 + 3] = (byte) 0xFF;
                        next[index] = true;
                        changed = true;
                    }
                }
            }
            System.arraycopy(next, 0, covered, 0, covered.length);
            if (!changed) {
                break;
            }
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        long painted = 0;
        for (var index = 0; index < covered.length; index++) {
            if (covered[index]) {
                red += Byte.toUnsignedInt(rgba[index * 4]);
                green += Byte.toUnsignedInt(rgba[index * 4 + 1]);
                blue += Byte.toUnsignedInt(rgba[index * 4 + 2]);
                painted++;
            }
        }
        for (var index = 0; index < covered.length; index++) {
            if (!covered[index]) {
                rgba[index * 4] = (byte) (red / painted);
                rgba[index * 4 + 1] = (byte) (green / painted);
                rgba[index * 4 + 2] = (byte) (blue / painted);
                rgba[index * 4 + 3] = (byte) 0xFF;
            }
        }
    }

    private static final class IntList {
        private int[] values = new int[64];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        int size() {
            return size;
        }
    }

    private static float sampleMask(Layer layer, float s, float t) {
        var x = s * layer.maskWidth() - .5f;
        var y = t * layer.maskHeight() - .5f;
        var x0 = (int) Math.floor(x);
        var y0 = (int) Math.floor(y);
        var fx = x - x0;
        var fy = y - y0;
        var left = Math.clamp(x0, 0, layer.maskWidth() - 1);
        var right = Math.clamp(x0 + 1, 0, layer.maskWidth() - 1);
        var top = Math.clamp(y0, 0, layer.maskHeight() - 1);
        var bottom = Math.clamp(y0 + 1, 0, layer.maskHeight() - 1);
        var mask = layer.mask();
        var a = Byte.toUnsignedInt(mask[top * layer.maskWidth() + left]);
        var b = Byte.toUnsignedInt(mask[top * layer.maskWidth() + right]);
        var c = Byte.toUnsignedInt(mask[bottom * layer.maskWidth() + left]);
        var d = Byte.toUnsignedInt(mask[bottom * layer.maskWidth() + right]);
        var upper = a + (b - a) * fx;
        var lower = c + (d - c) * fx;
        return (upper + (lower - upper) * fy) / 255f;
    }

    private static Layer decodeLayer(@Nullable Texture texture, @Nullable Texture alphaMap) {
        RgbaImage[] mips = null;
        if (texture != null && !texture.data().isEmpty()) {
            var decoded = new ArrayList<RgbaImage>();
            for (var level = 0; level < texture.data().size(); level++) {
                var width = Math.max(1, texture.width() >> level);
                var height = Math.max(1, texture.height() >> level);
                try {
                    var rgba = TexturePixels.decodeMipRgba(texture, level);
                    if (rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
                        break;
                    }
                    decoded.add(new RgbaImage(width, height, rgba));
                } catch (RuntimeException unreadable) {
                    break;
                }
            }
            mips = decoded.isEmpty() ? null : decoded.toArray(RgbaImage[]::new);
        }
        byte[] mask = null;
        var maskWidth = 0;
        var maskHeight = 0;
        if (alphaMap != null && !alphaMap.data().isEmpty() && alphaMap.width() > 0 && alphaMap.height() > 0) {
            try {
                mask = TexturePixels.decodeBaseRed(alphaMap);
                maskWidth = alphaMap.width();
                maskHeight = alphaMap.height();
                if (mask.length != maskWidth * maskHeight) {
                    mask = null;
                }
            } catch (RuntimeException unreadable) {
                mask = null;
            }
        }
        return new Layer(mips, texture != null && texture.alphaTexture(), mask, maskWidth, maskHeight);
    }
}
