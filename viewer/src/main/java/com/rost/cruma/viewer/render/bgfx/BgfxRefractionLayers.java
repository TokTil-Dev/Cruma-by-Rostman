package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Box;
import java.nio.FloatBuffer;
import java.util.Arrays;

final class BgfxRefractionLayers {
    static final int MARGIN = 2;

    private int count;
    private int[] quads = new int[64];
    private int[] reaches = new int[64];
    private int[] layers = new int[16];
    private int width;
    private int height;
    private int layerCount;
    private boolean merged;

    static boolean project(float[] viewProjection, Box box, int width, int height, int[] out) {
        var minimumX = Float.POSITIVE_INFINITY;
        var minimumY = Float.POSITIVE_INFINITY;
        var maximumX = Float.NEGATIVE_INFINITY;
        var maximumY = Float.NEGATIVE_INFINITY;
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? box.getMinX() : box.getMaxX();
            var y = (corner & 2) == 0 ? box.getMinY() : box.getMaxY();
            var z = (corner & 4) == 0 ? box.getMinZ() : box.getMaxZ();
            var clipW = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15];
            if (!(clipW > 1e-4f)) {
                return false;
            }
            var clipX = viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12];
            var clipY = viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13];
            minimumX = Math.min(minimumX, clipX / clipW);
            minimumY = Math.min(minimumY, clipY / clipW);
            maximumX = Math.max(maximumX, clipX / clipW);
            maximumY = Math.max(maximumY, clipY / clipW);
        }
        if (!Float.isFinite(minimumX + minimumY + maximumX + maximumY)) {
            return false;
        }
        out[0] = (int) Math.clamp((long) Math.floor((minimumX * .5f + .5f) * width) - 1, -1L, width + 1L);
        out[1] = (int) Math.clamp((long) Math.floor((.5f - maximumY * .5f) * height) - 1, -1L, height + 1L);
        out[2] = (int) Math.clamp((long) Math.ceil((maximumX * .5f + .5f) * width) + 1, -1L, width + 1L);
        out[3] = (int) Math.clamp((long) Math.ceil((.5f - minimumY * .5f) * height) + 1, -1L, height + 1L);
        return true;
    }

    static void multiply(FloatBuffer projection, FloatBuffer view, float[] out) {
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var sum = 0f;
                for (var index = 0; index < 4; index++) {
                    sum += projection.get(index * 4 + row) * view.get(column * 4 + index);
                }
                out[column * 4 + row] = sum;
            }
        }
    }

    void begin(int width, int height) {
        this.width = width;
        this.height = height;
        count = 0;
        layerCount = 0;
        merged = false;
    }

    void add(int[] quad, float scaleU, float scaleV) {
        if (count == layers.length) {
            layers = Arrays.copyOf(layers, count * 2);
            quads = Arrays.copyOf(quads, count * 8);
            reaches = Arrays.copyOf(reaches, count * 8);
        }
        var offset = count * 4;
        if (quad == null) {
            quads[offset] = 0;
            quads[offset + 1] = 0;
            quads[offset + 2] = width;
            quads[offset + 3] = height;
        } else {
            quads[offset] = Math.clamp(quad[0], 0, width);
            quads[offset + 1] = Math.clamp(quad[1], 0, height);
            quads[offset + 2] = Math.clamp(quad[2], 0, width);
            quads[offset + 3] = Math.clamp(quad[3], 0, height);
        }
        var reachX = reach(scaleU, width);
        var reachY = reach(scaleV, height);
        reaches[offset] = quads[offset] - reachX;
        reaches[offset + 1] = quads[offset + 1] - reachY;
        reaches[offset + 2] = quads[offset + 2] + reachX;
        reaches[offset + 3] = quads[offset + 3] + reachY;
        count++;
    }

    int computePasses(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a frame needs room for one pass");
        }
        layerCount = 0;
        merged = false;
        for (var effect = 0; effect < count; effect++) {
            var layer = 0;
            for (var earlier = 0; earlier < effect; earlier++) {
                if (layers[earlier] >= layer && touches(effect, earlier)) {
                    layer = layers[earlier] + 1;
                }
            }
            if (layer >= capacity) {
                layer = capacity - 1;
                merged = true;
            }
            layers[effect] = layer;
            layerCount = Math.max(layerCount, layer + 1);
        }
        return layerCount;
    }

    int size() {
        return count;
    }

    int getLayer(int effect) {
        return layers[effect];
    }

    boolean isMerged() {
        return merged;
    }

    void getScissor(int effect, int[] out) {
        var offset = effect * 4;
        if (quads[offset] >= quads[offset + 2] || quads[offset + 1] >= quads[offset + 3]) {
            Arrays.fill(out, 0, 4, 0);
            return;
        }
        out[0] = Math.max(0, reaches[offset]);
        out[1] = Math.max(0, reaches[offset + 1]);
        out[2] = Math.min(width, reaches[offset + 2]);
        out[3] = Math.min(height, reaches[offset + 3]);
    }

    private boolean touches(int first, int second) {
        return isOnPicture(first)
                && isOnPicture(second)
                && (overlaps(reaches, first, quads, second) || overlaps(reaches, second, quads, first));
    }

    private boolean isOnPicture(int effect) {
        var offset = effect * 4;
        return quads[offset] < quads[offset + 2] && quads[offset + 1] < quads[offset + 3];
    }

    private static boolean overlaps(int[] grown, int first, int[] quads, int second) {
        var a = first * 4;
        var b = second * 4;
        return grown[a] < quads[b + 2]
                && quads[b] < grown[a + 2]
                && grown[a + 1] < quads[b + 3]
                && quads[b + 1] < grown[a + 3];
    }

    private static int reach(float scale, int size) {
        if (!Float.isFinite(scale)) {
            return size;
        }
        return (int) Math.min(size, (long) Math.ceil(Math.abs(scale) * .5f * size)) + MARGIN;
    }
}
