package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class ShadowMaskReadback {
    private ShadowMaskReadback() {}

    record SunSamples(int minX, int minY, Vector surfaceNormal, Vector origin, Vector axisU, Vector axisV) {
        SunSamples {
            if (minX < 0 || minY < 0 || minX >= 65536 || minY >= 65536) {
                throw new IllegalArgumentException("Invalid sunlight readback crop");
            }
            requireFinite(surfaceNormal);
            requireFinite(origin);
            requireFinite(axisU);
            requireFinite(axisV);
        }

        private Vector cropOrigin() {
            var result = new Vector(
                    ((origin.x() + axisU.x() * minX) + axisV.x() * minY) + surfaceNormal.x(),
                    ((origin.y() + axisU.y() * minX) + axisV.y() * minY) + surfaceNormal.y(),
                    ((origin.z() + axisU.z() * minX) + axisV.z() * minY) + surfaceNormal.z());
            requireFinite(result);
            return result;
        }
    }

    static byte[] readSunMask(
            int width,
            int height,
            int offsetX,
            int offsetY,
            int sourceStride,
            int[] argb,
            byte[] initial,
            SunSamples samples,
            NativeBspRegions regions,
            BooleanSupplier cancelled) {
        var visible = readPointMask(width, height, offsetX, offsetY, sourceStride, argb, initial, cancelled);
        return retainSunRegions(width, height, visible, samples, regions, cancelled);
    }

    static byte[] retainSunRegions(
            int width,
            int height,
            byte[] whitePixels,
            SunSamples samples,
            NativeBspRegions regions,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(samples);
        Objects.requireNonNull(regions);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (width < 1
                || height < 1
                || (long) width * height > 65536
                || (long) samples.minX + width > 65536
                || (long) samples.minY + height > 65536) {
            throw new IllegalArgumentException("Sunlight readback exceeds bounded dimensions");
        }
        int stride = (width + 7) / 8;
        if (whitePixels.length != stride * height) {
            throw new IllegalArgumentException("Sunlight readback mask size differs");
        }
        var visible = whitePixels.clone();
        var start = samples.cropOrigin();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                P152FreshHashQuery.checkCancelled(cancelled);
                int index = y * stride + x / 8, bit = 1 << (x & 7);
                if ((visible[index] & bit) == 0) {
                    continue;
                }
                var point = new Vector(
                        (samples.axisU.x() * x + start.x()) + samples.axisV.x() * y,
                        (samples.axisU.y() * x + start.y()) + samples.axisV.y() * y,
                        (samples.axisU.z() * x + start.z()) + samples.axisV.z() * y);
                if (regions.pointRegion(point).zone() == 0) {
                    visible[index] &= (byte) ~bit;
                }
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return visible;
    }

    static byte[] readPointMask(
            int width,
            int height,
            int offsetX,
            int offsetY,
            int sourceStride,
            int[] argb,
            byte[] initial,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (width < 1
                || height < 1
                || (long) width * height > 65536
                || offsetX < 0
                || offsetY < 0
                || sourceStride < 1
                || (long) offsetX + width > sourceStride
                || ((long) offsetY + height - 1) * sourceStride + offsetX + width > argb.length
                || argb.length > 1024 * 1024) {
            throw new IllegalArgumentException("Shadow readback exceeds bounded source dimensions");
        }
        int stride = (width + 7) / 8;
        if (initial.length != stride * height) {
            throw new IllegalArgumentException("Shadow readback mask size differs");
        }
        var colors = argb.clone();
        var visible = initial.clone();
        for (int y = 0; y < height; y++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int source = (offsetY + y) * sourceStride + offsetX;
            for (int x = 0; x < width; x++) {
                int index = y * stride + x / 8, bit = 1 << (x & 7);
                if ((colors[source + x] & 0xffffff) == 0xffffff) {
                    visible[index] |= (byte) bit;
                } else {
                    visible[index] &= (byte) ~bit;
                }
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return visible;
    }

    private static void requireFinite(Vector value) {
        Objects.requireNonNull(value);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException("Non-finite sunlight readback vector");
        }
    }
}
