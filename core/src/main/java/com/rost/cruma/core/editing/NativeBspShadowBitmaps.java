package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BspLightmapTail.Entry;
import com.rost.cruma.core.editing.BspLightmapTail.Lightmap;
import com.rost.cruma.core.editing.BspShadowScene.Filter;

final class NativeBspShadowBitmaps {
    static final float DEPTH_CLEAR = Float.intBitsToFloat(0x3f7fff00);

    static final double DEPTH_EPSILON = 1.0 - DEPTH_CLEAR;

    static final double SUN_CAMERA_HEIGHT = 65536.0;

    private NativeBspShadowBitmaps() {}

    record Rect(int minX, int minY, int maxX, int maxY) {}

    record Profile(int bspCull, boolean meshes) {}

    static Rect pointRect(Lightmap lightmap, float[] plane, float lx, float ly, float lz, float worldRadius) {
        var distance = (plane[1] * ly + lx * plane[0]) + plane[2] * lz - plane[3];
        var square = (worldRadius * worldRadius) * 1.05f - distance * distance;
        var projected = (float) Math.sqrt(Math.max(0f, square));
        var px = lx - plane[0] * distance;
        var py = ly - plane[1] * distance;
        var pz = lz - plane[2] * distance;
        var m = lightmap.matrix();
        var x = ((py * m[4] + px * m[0]) + pz * m[8]) + m[12];
        var y = ((py * m[5] + px * m[1]) + pz * m[9]) + m[13];
        var u = projected / length(lightmap.axisX());
        var v = projected / length(lightmap.axisY());
        return new Rect(
                crop(x - u, lightmap.sizeX()),
                crop(y - v, lightmap.sizeY()),
                crop(x + u, lightmap.sizeX()),
                crop(y + v, lightmap.sizeY()));
    }

    static Rect fullRect(Lightmap lightmap) {
        return new Rect(0, 0, lightmap.sizeX() - 1, lightmap.sizeY() - 1);
    }

    static Entry buildPointBitmap(
            Lightmap lightmap,
            float[] plane,
            int lightReference,
            float lx,
            float ly,
            float lz,
            Rect rect,
            BspShadowScene scene,
            Profile profile,
            Filter filter) {
        var width = rect.maxX() - rect.minX() + 1;
        var height = rect.maxY() - rect.minY() + 1;
        var stride = (width + 7) / 8;
        var bitmap = new byte[stride * height];
        var distance = (plane[1] * ly + lx * plane[0]) + plane[2] * lz - plane[3];
        double c = Math.abs(distance);
        if (c <= 1.0) {
            return new Entry(
                    lightReference, bitmap, width, height, stride, rect.minX(), rect.minY(), rect.maxX(), rect.maxY());
        }
        var minimumHeight = c - c / (1.0 + DEPTH_EPSILON * (c - 1.0));
        var tMin = 1.0 / c;
        var tMax = 1.0 - minimumHeight / c;
        var base = lightmap.base();
        var ax = lightmap.axisX();
        var ay = lightmap.axisY();
        for (var j = 0; j < height; j++) {
            for (var i = 0; i < width; i++) {
                double u = rect.minX() + i + 0.5;
                double v = rect.minY() + j + 0.5;
                var px = base[0] + ax[0] * u + ay[0] * v;
                var py = base[1] + ax[1] * u + ay[1] * v;
                var pz = base[2] + ax[2] * u + ay[2] * v;
                if (!isBlocked(scene, lx, ly, lz, px - lx, py - ly, pz - lz, tMin, tMax, profile, filter)) {
                    bitmap[j * stride + (i >> 3)] |= (byte) (1 << (i & 7));
                }
            }
        }
        return new Entry(
                lightReference, bitmap, width, height, stride, rect.minX(), rect.minY(), rect.maxX(), rect.maxY());
    }

    static Entry buildSunBitmap(
            Lightmap lightmap,
            float[] plane,
            float[] normal,
            int lightReference,
            float[] toward,
            BspShadowScene scene,
            Profile profile,
            Filter filter) {
        var rect = fullRect(lightmap);
        var width = lightmap.sizeX();
        var height = lightmap.sizeY();
        var stride = (width + 7) / 8;
        var bitmap = new byte[stride * height];
        double cos =
                Math.abs((double) plane[0] * toward[0] + (double) plane[1] * toward[1] + (double) plane[2] * toward[2]);
        if (cos < 1e-6) {
            return new Entry(lightReference, bitmap, width, height, stride, 0, 0, width - 1, height - 1);
        }
        var sMin = DEPTH_EPSILON * (SUN_CAMERA_HEIGHT - 1.0) / cos;
        var sMax = (SUN_CAMERA_HEIGHT - 1.0) / cos;
        var base = lightmap.base();
        var ax = lightmap.axisX();
        var ay = lightmap.axisY();
        var startX = ((base[0] + ax[0] * rect.minX()) + ay[0] * rect.minY()) + normal[0];
        var startY = ((base[1] + ax[1] * rect.minX()) + ay[1] * rect.minY()) + normal[1];
        var startZ = ((base[2] + ax[2] * rect.minX()) + ay[2] * rect.minY()) + normal[2];
        for (var j = 0; j < height; j++) {
            for (var i = 0; i < width; i++) {
                double u = i + 0.5;
                double v = j + 0.5;
                var px = base[0] + ax[0] * u + ay[0] * v;
                var py = base[1] + ax[1] * u + ay[1] * v;
                var pz = base[2] + ax[2] * u + ay[2] * v;
                if (isBlocked(scene, px, py, pz, toward[0], toward[1], toward[2], sMin, sMax, profile, filter)) {
                    continue;
                }
                var sx = (ax[0] * i + startX) + ay[0] * j;
                var sy = (ax[1] * i + startY) + ay[1] * j;
                var sz = (ax[2] * i + startZ) + ay[2] * j;
                if (scene.findZone(sx, sy, sz) != 0) {
                    bitmap[j * stride + (i >> 3)] |= (byte) (1 << (i & 7));
                }
            }
        }
        return new Entry(lightReference, bitmap, width, height, stride, 0, 0, width - 1, height - 1);
    }

    private static boolean isBlocked(
            BspShadowScene scene,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double tMin,
            double tMax,
            Profile profile,
            Filter filter) {
        return scene.isBlocked(ox, oy, oz, dx, dy, dz, tMin, tMax, profile.bspCull(), triangle -> {
            if (!profile.meshes() && (scene.getFlags(triangle) & BspShadowScene.MESH) != 0) {
                return true;
            }
            return filter != null && filter.skip(triangle);
        });
    }

    private static int crop(float value, int size) {
        var truncated = value >= 0x1p31f || value < -0x1p31f || Float.isNaN(value) ? Integer.MIN_VALUE : (int) value;
        return Math.clamp(truncated, 0, size - 1);
    }

    private static float length(float[] v) {
        return (float) Math.sqrt((v[0] * v[0] + v[1] * v[1]) + v[2] * v[2]);
    }
}
