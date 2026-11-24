package com.rost.cruma.core.editing;

final class BspDxt1 {
    private BspDxt1() {}

    static void decode(byte[] data, int offset, int[] rgba) {
        var c0 = (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8;
        var c1 = (data[offset + 2] & 0xff) | (data[offset + 3] & 0xff) << 8;
        var palette = computePalette(c0, c1);
        for (var i = 0; i < 16; i++) {
            var index = (data[offset + 4 + (i >> 2)] & 0xff) >>> ((i & 3) * 2) & 3;
            System.arraycopy(palette, index * 4, rgba, i * 4, 4);
        }
    }

    static void encode(int[] rgba, byte[] out, int offset, byte[] previous, int previousOffset) {
        var transparent = false;
        for (var i = 0; i < 16; i++) {
            if (rgba[i * 4 + 3] < 128) {
                transparent = true;
                break;
            }
        }
        var bestError = Long.MAX_VALUE;
        var best = new byte[8];
        var candidate = new byte[8];
        if (previous != null) {
            var c0 = (previous[previousOffset] & 0xff) | (previous[previousOffset + 1] & 0xff) << 8;
            var c1 = (previous[previousOffset + 2] & 0xff) | (previous[previousOffset + 3] & 0xff) << 8;
            if ((c0 <= c1) == transparent || c0 == c1) {
                var error = build(rgba, c0, c1, transparent, candidate);
                if (error < bestError) {
                    bestError = error;
                    System.arraycopy(candidate, 0, best, 0, 8);
                }
            }
        }
        var ends = principalEnds(rgba);
        for (var round = 0; round < 3; round++) {
            var c0 = pack(ends[0], ends[1], ends[2]);
            var c1 = pack(ends[3], ends[4], ends[5]);
            if (transparent ? c0 > c1 : c0 < c1) {
                var swap = c0;
                c0 = c1;
                c1 = swap;
            }
            var error = build(rgba, c0, c1, transparent, candidate);
            if (error < bestError) {
                bestError = error;
                System.arraycopy(candidate, 0, best, 0, 8);
            }
            if (transparent || !refine(rgba, candidate, ends)) {
                break;
            }
        }
        System.arraycopy(best, 0, out, offset, 8);
    }

    static int[] computePalette(int c0, int c1) {
        var p = new int[16];
        unpack(c0, p, 0);
        unpack(c1, p, 4);
        if (c0 <= c1) {
            for (var c = 0; c < 3; c++) {
                p[8 + c] = (p[c] + p[4 + c]) / 2;
                p[12 + c] = 0;
            }
            p[8 + 3] = 255;
            p[12 + 3] = 0;
        } else {
            for (var c = 0; c < 3; c++) {
                p[8 + c] = (2 * p[c] + p[4 + c]) / 3;
                p[12 + c] = (p[c] + 2 * p[4 + c]) / 3;
            }
            p[8 + 3] = 255;
            p[12 + 3] = 255;
        }
        return p;
    }

    private static long build(int[] rgba, int c0, int c1, boolean transparent, byte[] out) {
        var palette = computePalette(c0, c1);
        var colors = c0 <= c1 ? 3 : 4;
        long total = 0;
        var indices = 0;
        for (var i = 0; i < 16; i++) {
            int index;
            if (transparent && rgba[i * 4 + 3] < 128) {
                index = 3;
            } else {
                index = 0;
                var bestError = Long.MAX_VALUE;
                for (var k = 0; k < colors; k++) {
                    long error = 0;
                    for (var c = 0; c < 3; c++) {
                        var d = rgba[i * 4 + c] - palette[k * 4 + c];
                        error += (long) d * d;
                    }
                    if (error < bestError) {
                        bestError = error;
                        index = k;
                    }
                }
                total += bestError;
            }
            indices |= index << (i * 2);
        }
        out[0] = (byte) c0;
        out[1] = (byte) (c0 >>> 8);
        out[2] = (byte) c1;
        out[3] = (byte) (c1 >>> 8);
        out[4] = (byte) indices;
        out[5] = (byte) (indices >>> 8);
        out[6] = (byte) (indices >>> 16);
        out[7] = (byte) (indices >>> 24);
        return total;
    }

    private static float[] principalEnds(int[] rgba) {
        var mean = new float[3];
        var count = 0;
        for (var i = 0; i < 16; i++) {
            if (rgba[i * 4 + 3] < 128) {
                continue;
            }
            for (var c = 0; c < 3; c++) {
                mean[c] += rgba[i * 4 + c];
            }
            count++;
        }
        if (count == 0) {
            return new float[6];
        }
        for (var c = 0; c < 3; c++) {
            mean[c] /= count;
        }
        var cov = new float[6];
        for (var i = 0; i < 16; i++) {
            if (rgba[i * 4 + 3] < 128) {
                continue;
            }
            var r = rgba[i * 4] - mean[0];
            var g = rgba[i * 4 + 1] - mean[1];
            var b = rgba[i * 4 + 2] - mean[2];
            cov[0] += r * r;
            cov[1] += r * g;
            cov[2] += r * b;
            cov[3] += g * g;
            cov[4] += g * b;
            cov[5] += b * b;
        }
        float ax = 1f, ay = 1f, az = 1f;
        for (var iteration = 0; iteration < 8; iteration++) {
            var x = cov[0] * ax + cov[1] * ay + cov[2] * az;
            var y = cov[1] * ax + cov[3] * ay + cov[4] * az;
            var z = cov[2] * ax + cov[4] * ay + cov[5] * az;
            var length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length < 1e-6f) {
                break;
            }
            ax = x / length;
            ay = y / length;
            az = z / length;
        }
        var min = Float.POSITIVE_INFINITY;
        var max = Float.NEGATIVE_INFINITY;
        for (var i = 0; i < 16; i++) {
            if (rgba[i * 4 + 3] < 128) {
                continue;
            }
            var t = (rgba[i * 4] - mean[0]) * ax + (rgba[i * 4 + 1] - mean[1]) * ay + (rgba[i * 4 + 2] - mean[2]) * az;
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        return new float[] {
            mean[0] + ax * max,
            mean[1] + ay * max,
            mean[2] + az * max,
            mean[0] + ax * min,
            mean[1] + ay * min,
            mean[2] + az * min
        };
    }

    private static boolean refine(int[] rgba, byte[] block, float[] ends) {
        var c0 = (block[0] & 0xff) | (block[1] & 0xff) << 8;
        var c1 = (block[2] & 0xff) | (block[3] & 0xff) << 8;
        if (c0 <= c1) {
            return false;
        }
        var indices = (block[4] & 0xff) | (block[5] & 0xff) << 8 | (block[6] & 0xff) << 16 | (block[7] & 0xff) << 24;
        float aa = 0, bb = 0, ab = 0;
        var ax = new float[3];
        var bx = new float[3];
        for (var i = 0; i < 16; i++) {
            var index = indices >>> (i * 2) & 3;
            var w = switch (index) {
                case 0 -> 1f;
                case 1 -> 0f;
                case 2 -> 2f / 3f;
                default -> 1f / 3f;
            };
            var v = 1f - w;
            aa += w * w;
            bb += v * v;
            ab += w * v;
            for (var c = 0; c < 3; c++) {
                ax[c] += w * rgba[i * 4 + c];
                bx[c] += v * rgba[i * 4 + c];
            }
        }
        var det = aa * bb - ab * ab;
        if (Math.abs(det) < 1e-6f) {
            return false;
        }
        for (var c = 0; c < 3; c++) {
            ends[c] = (ax[c] * bb - bx[c] * ab) / det;
            ends[3 + c] = (bx[c] * aa - ax[c] * ab) / det;
        }
        return true;
    }

    private static int pack(float r, float g, float b) {
        var red = Math.clamp(Math.round(r * 31f / 255f), 0, 31);
        var green = Math.clamp(Math.round(g * 63f / 255f), 0, 63);
        var blue = Math.clamp(Math.round(b * 31f / 255f), 0, 31);
        return red << 11 | green << 5 | blue;
    }

    private static void unpack(int value, int[] palette, int offset) {
        var red = value >> 11 & 0x1f;
        var green = value >> 5 & 0x3f;
        var blue = value & 0x1f;
        palette[offset] = red << 3 | red >> 2;
        palette[offset + 1] = green << 2 | green >> 4;
        palette[offset + 2] = blue << 3 | blue >> 2;
        palette[offset + 3] = 255;
    }
}
