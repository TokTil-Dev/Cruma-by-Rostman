package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BspLightmapTail.Entry;
import com.rost.cruma.core.editing.BspLightmapTail.Lightmap;
import java.util.Arrays;
import java.util.List;

final class NativeBspLightmapTexels {
    static final int LE_SPOTLIGHT = 12;

    static final int LE_STATIC_SPOT = 8;

    static final int LE_SUNLIGHT = 19;

    private static final float[] FALLOFF = new float[4096];

    private static final int[] FILTER = new int[128 * 3];

    private static final int[] KERNEL = {0, 24, 40, 24, 0, 0, 0, 0, 0, 40, 64, 40, 0, 0, 0, 0, 0, 24, 40, 24, 0, 0, 0, 0
    };

    private static final float[] INVERSE_ROOT_MANTISSA = new float[1024];

    private static final float[] INVERSE_ROOT_EXPONENT = new float[512];

    static {
        for (var i = 0; i < FALLOFF.length; i++) {
            var f = (float) Math.sqrt((i + 1) / 4096.0);
            FALLOFF[i] = ((f * 2f * f * f - f * 3f * f) + 1f) / f;
        }
        var sum = 0;
        for (var value : KERNEL) {
            sum += value;
        }
        for (var window = 0; window < 128; window++) {
            for (var row = 0; row < 3; row++) {
                var packed = 0;
                for (var lane = 0; lane < 4; lane++) {
                    var total = 0;
                    for (var bit = 0; bit < 8; bit++) {
                        if ((window & 1 << bit + lane) != 0) {
                            total += KERNEL[row * 8 + bit];
                        }
                    }
                    packed += (total * 255) / sum << lane * 8;
                }
                FILTER[window * 3 + row] = packed;
            }
        }
        for (var i = 0; i < 512; i++) {
            var value = (float) Math.pow(Math.abs(Float.intBitsToFloat(i << 23)), -0.5);
            INVERSE_ROOT_EXPONENT[i] = Float.isNaN(value) ? 0f : value;
        }
        for (var i = 0; i < 1024; i++) {
            var value = (float) Math.pow(Float.intBitsToFloat(0x3f800000 + (i << 13)), -0.5);
            INVERSE_ROOT_MANTISSA[i] = Float.isNaN(value) ? 0f : value;
        }
    }

    private NativeBspLightmapTexels() {}

    record Light(
            int effect,
            float x,
            float y,
            float z,
            float radius,
            float red,
            float green,
            float blue,
            float directionX,
            float directionY,
            float directionZ,
            int cone) {}

    record Surface(float normalX, float normalY, float normalZ, float distance, boolean twoSided) {}

    record Lit(Entry entry, Light light) {}

    static byte[] computeTexels(Lightmap lightmap, Surface surface, int base, List<Lit> lit) {
        var width = lightmap.sizeX();
        var height = lightmap.sizeY();
        var texels = new byte[width * height * 4];
        for (var i = 0; i < width * height; i++) {
            texels[i * 4] = (byte) base;
            texels[i * 4 + 1] = (byte) (base >> 8);
            texels[i * 4 + 2] = (byte) (base >> 16);
            texels[i * 4 + 3] = (byte) (base >>> 24);
        }
        for (var item : lit) {
            add(texels, lightmap, surface, item.entry(), item.light());
        }
        return texels;
    }

    static void add(byte[] texels, Lightmap lightmap, Surface surface, Entry entry, Light light) {
        var sun = light.effect() == LE_SUNLIGHT;
        int minX, minY, maxX, maxY;
        if (sun) {
            minX = 0;
            minY = 0;
            maxX = lightmap.sizeX() - 1;
            maxY = lightmap.sizeY() - 1;
        } else {
            minX = entry.minX();
            minY = entry.minY();
            maxX = entry.maxX();
            maxY = entry.maxY();
        }
        if (maxX < minX
                || maxY < minY
                || maxX >= lightmap.sizeX()
                || maxY >= lightmap.sizeY()
                || minX < 0
                || minY < 0) {
            return;
        }
        var shadow = filter(entry);
        var pitch = entry.minY() == entry.maxY() ? entry.maxX() - entry.minX() + 1 : entry.stride() * 8;
        var intensity = computeIntensity(lightmap, surface, light, shadow, pitch, minX, minY, maxX, maxY);
        var table = colorTable(light.red(), light.green(), light.blue());
        var width = maxX - minX + 1;
        for (var y = minY; y <= maxY; y++) {
            for (var x = minX; x <= maxX; x++) {
                var value = table[intensity[(y - minY) * width + (x - minX)] & 0xff];
                var target = (y * lightmap.sizeX() + x) * 4;
                for (var channel = 0; channel < 4; channel++) {
                    var sum = (texels[target + channel] & 0xff) + (value >>> channel * 8 & 0xff);
                    texels[target + channel] = (byte) Math.min(255, sum);
                }
            }
        }
    }

    static byte[] filter(Entry entry) {
        var rows = entry.sizeY();
        var stride = entry.stride();
        if (entry.minY() == entry.maxY()) {
            var width = entry.maxX() - entry.minX() + 1;
            var lit = new byte[Math.max(0, width) * Math.max(0, entry.maxY() - entry.minY() + 1)];
            Arrays.fill(lit, (byte) 0xff);
            return lit;
        }
        var bitmap = entry.bitmap();
        var out = new int[rows * stride * 2];
        if (rows <= 0 || stride <= 0 || bitmap.length < rows * stride) {
            return new byte[out.length * 4];
        }
        var above = 0;
        var current = 0;
        var below = stride * 2;
        var source = 0;
        for (var row = 0; row < rows; row++) {
            var bits = (bitmap[source] & 0xff) << 10;
            var next = source + 1;
            if ((bits & 0x400) != 0) {
                bits |= 0x300;
            }
            for (var b = 0; b < stride; b++) {
                int incoming;
                if (b < stride - 1) {
                    incoming = (bitmap[next++] & 0xff) << 10;
                } else {
                    incoming = (bits >>> 8 & 0x200) != 0 ? 0xc00 : 0;
                }
                bits = (bits >>> 8) + incoming;
                var w = bits & 0x7f;
                out[above] += FILTER[w * 3];
                out[current] += FILTER[w * 3 + 1];
                if (below < out.length) {
                    out[below] += FILTER[w * 3 + 2];
                }
                w = bits >>> 4 & 0x7f;
                out[above + 1] += FILTER[w * 3];
                out[current + 1] += FILTER[w * 3 + 1];
                if (below + 1 < out.length) {
                    out[below + 1] += FILTER[w * 3 + 2];
                }
                above += 2;
                current += 2;
                below += 2;
            }
            source += stride;
            if (row == 0) {
                above -= stride * 2;
            }
            if (row == rows - 2) {
                below -= stride * 2;
            }
        }
        var result = new byte[out.length * 4];
        for (var i = 0; i < out.length; i++) {
            result[i * 4] = (byte) out[i];
            result[i * 4 + 1] = (byte) (out[i] >> 8);
            result[i * 4 + 2] = (byte) (out[i] >> 16);
            result[i * 4 + 3] = (byte) (out[i] >>> 24);
        }
        return result;
    }

    static byte[] computeIntensity(
            Lightmap lightmap,
            Surface surface,
            Light light,
            byte[] shadow,
            int pitch,
            int minX,
            int minY,
            int maxX,
            int maxY) {
        var width = maxX - minX + 1;
        var height = maxY - minY + 1;
        var result = new byte[width * height];
        var base = lightmap.base();
        var ax = lightmap.axisX();
        var ay = lightmap.axisY();
        if (light.effect() == LE_SUNLIGHT) {
            var dx = -light.directionX();
            var dy = -light.directionY();
            var dz = -light.directionZ();
            float factor;
            if (surface.twoSided()) {
                factor = Math.abs((surface.normalY() * dy + surface.normalX() * dx) + surface.normalZ() * dz);
            } else {
                var dot = (surface.normalY() * dy + surface.normalX() * dx) + surface.normalZ() * dz;
                factor = 0f >= dot ? 0f : dot;
            }
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var s = index(shadow, y * pitch + x);
                    result[y * width + x] = (byte) floor(s * factor);
                }
            }
            return result;
        }
        var radius = light.radius();
        var clamped = 1f >= radius ? 1f : radius;
        var inverse = (float) (1.0 / clamped);
        var k = (float) ((double) inverse * 4093.0 * (double) inverse);
        var lx = light.x() - base[0];
        var ly = light.y() - base[1];
        var lz = light.z() - base[2];
        var planeFactor =
                Math.abs(((surface.normalY() * ly + lx * surface.normalX()) + surface.normalZ() * lz) / radius);
        var y0 = (float) minY + .5f;
        var x0 = (float) minX + .5f;
        var dx = ((base[0] - light.x()) + x0 * ax[0]) + ay[0] * y0;
        var dy = ((base[1] - light.y()) + ax[1] * x0) + ay[1] * y0;
        var dz = ((base[2] - light.z()) + ax[2] * x0) + ay[2] * y0;
        if (light.effect() == LE_SPOTLIGHT || light.effect() == LE_STATIC_SPOT) {
            var cone = (float) (1.0 - light.cone() * 0.00390625);
            var reciprocal = 1.0 / (1.0 - (double) cone);
            var offset = (float) reciprocal * cone;
            var coneSquare = cone * cone;
            var scale = (float) reciprocal;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var square = (dx * dx + dy * dy) + dz * dz;
                    var step = round(square * k);
                    var s = index(shadow, y * pitch + x);
                    var value = 0;
                    if (s != 0 && Integer.compareUnsigned(step, 0x1000) < 0) {
                        var dot = (light.directionX() * dx + light.directionY() * dy) + light.directionZ() * dz;
                        if (dot > 0f && dot * dot > square * coneSquare) {
                            var f = inverseRoot(square) * (dot * scale) - offset;
                            value = floor(s * (f * f) * planeFactor * FALLOFF[step]);
                        }
                    }
                    result[y * width + x] = (byte) value;
                    dx = ax[0] + dx;
                    dy = ax[1] + dy;
                    dz = ax[2] + dz;
                }
                var widthFloat = (float) width;
                dx = ay[0] + (dx - ax[0] * widthFloat);
                dy = ay[1] + (dy - ax[1] * widthFloat);
                dz = ay[2] + (dz - ax[2] * widthFloat);
            }
            return result;
        }
        var scaled = k * 4096f;
        var a = round(scaled * ((dx * dx + dy * dy) + dz * dz));
        var bx = round(scaled * ((ax[1] * dy + dx * ax[0]) + ax[2] * dz));
        var by = round(scaled * ((ay[1] * dy + dx * ay[0]) + ay[2] * dz));
        var cxx = round(scaled * ((ax[1] * ax[1] + ax[0] * ax[0]) + ax[2] * ax[2]));
        var cyy = round(scaled * ((ay[1] * ay[1] + ay[0] * ay[0]) + ay[2] * ay[2]));
        var cxy = round(scaled * ((ay[1] * ax[1] + ay[0] * ax[0]) + ay[2] * ax[2]));
        var q = a;
        var dqy = truncate((float) by * 2f + (float) cyy);
        var ddqy = truncate((float) cyy * 2f);
        var dqx = truncate((float) bx * 2f + (float) cxx);
        var dqxy = truncate((float) cxy * 2f);
        var ddqx = truncate((float) cxx * 2f);
        for (var y = 0; y < height; y++) {
            var value = q;
            var delta = dqx;
            for (var x = 0; x < width; x++) {
                var s = index(shadow, y * pitch + x);
                if (s != 0 && Integer.compareUnsigned(value, 0x1000000) < 0) {
                    var sum = (float) s * planeFactor * FALLOFF[value >>> 12] + 8388608f;
                    result[y * width + x] = (byte) Float.floatToRawIntBits(sum);
                }
                value += delta;
                delta += ddqx;
            }
            q += dqy;
            dqy += ddqy;
            dqx += dqxy;
        }
        return result;
    }

    static int[] colorTable(float red, float green, float blue) {
        var shiftR = 32 - ceilLogTwo((int) (long) (red * 256f)) & 0xff;
        var shiftG = 32 - ceilLogTwo((int) (long) (green * 256f)) & 0xff;
        var shiftB = 32 - ceilLogTwo((int) (long) (blue * 256f)) & 0xff;
        var fixedR = floor((float) (1 << (shiftR & 31)) * red);
        var fixedG = floor((float) (1 << (shiftG & 31)) * green);
        var fixedB = floor((float) (1 << (shiftB & 31)) * blue);
        var table = new int[256];
        int accR = 0, accG = 0, accB = 0;
        for (var v = 0; v < 256; v++) {
            var r = Math.min(255L, Integer.toUnsignedLong(accR >>> (shiftR & 31)));
            var g = Math.min(255L, Integer.toUnsignedLong(accG >>> (shiftG & 31)));
            var b = Math.min(255L, Integer.toUnsignedLong(accB >>> (shiftB & 31)));
            table[v] = (int) b | (int) g << 8 | (int) r << 16 | 0xff << 24;
            accR += fixedR;
            accG += fixedG;
            accB += fixedB;
        }
        return table;
    }

    static int toColor(float red, float green, float blue, float alpha) {
        return toChannel(blue) | toChannel(green) << 8 | toChannel(red) << 16 | toChannel(alpha) << 24;
    }

    static float inverseRoot(float value) {
        var bits = Float.floatToRawIntBits(value);
        return (float)
                ((double) INVERSE_ROOT_MANTISSA[bits >>> 13 & 0x3ff] * (double) INVERSE_ROOT_EXPONENT[bits >>> 23]);
    }

    static int floor(float value) {
        var floored = Math.floor(value);
        return floored >= 0x1p31 || floored < -0x1p31 || Double.isNaN(floored) ? Integer.MIN_VALUE : (int) floored;
    }

    static int round(float value) {
        var floored = Math.floor((double) value + .5);
        return floored >= 0x1p31 || floored < -0x1p31 || Double.isNaN(floored) ? Integer.MIN_VALUE : (int) floored;
    }

    static int ceilLogTwo(int value) {
        if (value == 0) {
            return 0;
        }
        var arg = value - 1;
        return arg == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(arg);
    }

    private static int toChannel(float value) {
        return Math.clamp(floor(value * 255f), 0, 255);
    }

    private static int index(byte[] shadow, int position) {
        return position >= 0 && position < shadow.length ? shadow[position] & 0xff : 0;
    }

    private static int truncate(float value) {
        return value >= 0x1p31f || value < -0x1p31f || Float.isNaN(value) ? Integer.MIN_VALUE : (int) value;
    }
}
