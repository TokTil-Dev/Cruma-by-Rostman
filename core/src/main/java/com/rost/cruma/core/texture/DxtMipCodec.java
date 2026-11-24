package com.rost.cruma.core.texture;

import com.rost.cruma.core.world.Entity.TextureFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DxtMipCodec {
    private static final int DXT1_BLOCK = 8;
    private static final int DXT35_BLOCK = 16;

    private DxtMipCodec() {}

    public record Options(boolean enabled, int minSize) {}

    public record AuthoredChain(int expectedLevels, int validLevels) {
        public AuthoredChain {
            if (expectedLevels < 1 || validLevels < 0 || validLevels > expectedLevels) {
                throw new IllegalArgumentException("invalid authored DXT chain bounds");
            }
        }

        public boolean isComplete() {
            return validLevels == expectedLevels;
        }
    }

    public record CompletedChain(List<byte[]> levels, int authoredLevels, int generatedLevels, long generatedBytes) {
        public CompletedChain {
            levels = List.copyOf(levels);
            if (authoredLevels < 1
                    || generatedLevels < 0
                    || authoredLevels + generatedLevels != levels.size()
                    || generatedBytes < 0) {
                throw new IllegalArgumentException("invalid completed DXT chain accounting");
            }
        }
    }

    public static boolean isEligible(Options options, TextureFormat format, int width, int height, int mipCount) {
        return options != null
                && options.enabled()
                && mipCount == 1
                && Math.min(width, height) >= options.minSize()
                && (format == TextureFormat.TEXTURE_DXT1
                        || format == TextureFormat.TEXTURE_DXT3
                        || format == TextureFormat.TEXTURE_DXT5);
    }

    public static AuthoredChain inspectAuthoredChain(
            TextureFormat format, int width, int height, List<byte[]> authored) {
        requireCompressed(format, width, height);
        var expectedLevels = mipCount(width, height);
        var validLevels = 0;
        if (authored != null) {
            var candidates = Math.min(expectedLevels, authored.size());
            for (var level = 0; level < candidates; level++) {
                var data = authored.get(level);
                if (data == null || data.length < compressedLevelBytes(format, width, height, level)) {
                    break;
                }
                validLevels++;
            }
        }
        return new AuthoredChain(expectedLevels, validLevels);
    }

    public static CompletedChain completeAuthoredChain(
            TextureFormat format, int width, int height, List<byte[]> authored) {
        var inspection = inspectAuthoredChain(format, width, height, authored);
        if (inspection.validLevels() == 0) {
            throw new IllegalArgumentException("compressed texture has no valid authored base level");
        }
        var levels = new ArrayList<byte[]>(inspection.expectedLevels());
        levels.addAll(authored.subList(0, inspection.validLevels()));
        if (inspection.isComplete()) {
            return new CompletedChain(levels, inspection.validLevels(), 0, 0);
        }

        var lastAuthoredLevel = inspection.validLevels() - 1;
        var sourceWidth = Math.max(1, width >> lastAuthoredLevel);
        var sourceHeight = Math.max(1, height >> lastAuthoredLevel);
        var generated = buildChain(format, sourceWidth, sourceHeight, authored.get(lastAuthoredLevel));
        long generatedBytes = 0;
        for (var level = 1; level < generated.size(); level++) {
            var data = generated.get(level);
            levels.add(data);
            generatedBytes = Math.addExact(generatedBytes, data.length);
        }
        return new CompletedChain(levels, inspection.validLevels(), generated.size() - 1, generatedBytes);
    }

    public static int mipCount(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
        var levels = 1;
        while (width > 1 || height > 1) {
            width = Math.max(1, width >> 1);
            height = Math.max(1, height >> 1);
            levels++;
        }
        return levels;
    }

    public static List<byte[]> buildChain(TextureFormat format, int width, int height, byte[] base) {
        var out = new ArrayList<byte[]>();
        out.add(base);
        var sw = width;
        var sh = height;
        if (sw <= 1 && sh <= 1) {
            return out;
        }
        var dw = Math.max(1, sw >> 1);
        var dh = Math.max(1, sh >> 1);
        var block = new int[16];
        var colors = new int[4];
        var alpha = new int[16];
        var palette = new int[8];
        var src = decodeAndDownsamplePacked(format, sw, sh, base, dw, dh, block, colors, alpha, palette);
        out.add(encodePacked(format, dw, dh, src, block, palette));
        sw = dw;
        sh = dh;
        while (sw > 1 || sh > 1) {
            dw = Math.max(1, sw >> 1);
            dh = Math.max(1, sh >> 1);
            boxDownsamplePackedInPlace(src, sw, sh, dw, dh);
            out.add(encodePacked(format, dw, dh, src, block, palette));
            sw = dw;
            sh = dh;
        }
        return out;
    }

    public static float averageAlpha(TextureFormat format, int width, int height, byte[] base) {
        var texels = width * height;
        if (texels == 0) {
            return 1f;
        }
        return channelSums(format, width, height, base)[3] / (255f * texels);
    }

    public static float[] averageRgb(TextureFormat format, int width, int height, byte[] base) {
        var texels = width * height;
        if (texels == 0) {
            return new float[] {0f, 0f, 0f};
        }
        var sums = channelSums(format, width, height, base);
        var d = 255f * texels;
        return new float[] {sums[0] / d, sums[1] / d, sums[2] / d};
    }

    public static byte[] decodeToRgba(TextureFormat format, int width, int height, byte[] src) {
        var out = new byte[width * height * 4];
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var blockSize = dxt1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        var color = new int[16 * 4];
        var alpha = new int[16];
        var palette = new int[12];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                var off = (by * blocksX + bx) * blockSize;
                if (dxt1) {
                    decodeColorBlock(src, off, true, color, palette);
                } else if (format == TextureFormat.TEXTURE_DXT3) {
                    decodeDxt3Alpha(src, off, alpha);
                    decodeColorBlock(src, off + 8, false, color, palette);
                } else {
                    decodeDxt5Alpha(src, off, alpha, palette);
                    decodeColorBlock(src, off + 8, false, color, palette);
                }
                for (int ty = 0; ty < 4; ty++) {
                    var y = by * 4 + ty;
                    if (y >= height) {
                        break;
                    }
                    for (int tx = 0; tx < 4; tx++) {
                        var x = bx * 4 + tx;
                        if (x >= width) {
                            continue;
                        }
                        var t = ty * 4 + tx;
                        var o = (y * width + x) * 4;
                        out[o] = (byte) color[t * 4];
                        out[o + 1] = (byte) color[t * 4 + 1];
                        out[o + 2] = (byte) color[t * 4 + 2];
                        out[o + 3] = (byte) (dxt1 ? color[t * 4 + 3] : alpha[t]);
                    }
                }
            }
        }
        return out;
    }

    public static byte[] decodeToRgbaSubsampled(TextureFormat format, int width, int height, byte[] src, int step) {
        if (step < 4 || step % 4 != 0) {
            throw new IllegalArgumentException("subsample step must be a positive multiple of 4, got " + step);
        }
        var outWidth = (width + step - 1) / step;
        var outHeight = (height + step - 1) / step;
        var out = new byte[outWidth * outHeight * 4];
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var blockSize = dxt1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var color = new int[16 * 4];
        var alpha = new int[16];
        var palette = new int[12];
        var blockStep = step / 4;
        for (int oy = 0; oy < outHeight; oy++) {
            var by = oy * blockStep;
            for (int ox = 0; ox < outWidth; ox++) {
                var bx = ox * blockStep;
                var off = (by * blocksX + bx) * blockSize;
                if (dxt1) {
                    decodeColorBlock(src, off, true, color, palette);
                } else if (format == TextureFormat.TEXTURE_DXT3) {
                    decodeDxt3Alpha(src, off, alpha);
                    decodeColorBlock(src, off + 8, false, color, palette);
                } else {
                    decodeDxt5Alpha(src, off, alpha, palette);
                    decodeColorBlock(src, off + 8, false, color, palette);
                }
                var o = (oy * outWidth + ox) * 4;
                out[o] = (byte) color[0];
                out[o + 1] = (byte) color[1];
                out[o + 2] = (byte) color[2];
                out[o + 3] = (byte) (dxt1 ? color[3] : alpha[0]);
            }
        }
        return out;
    }

    public static byte[] decodeRed(TextureFormat format, int width, int height, byte[] src) {
        var out = new byte[Math.multiplyExact(width, height)];
        decodeRedInto(format, width, height, src, out, 0, 1);
        return out;
    }

    public static void decodeRedInto(
            TextureFormat format,
            int width,
            int height,
            byte[] src,
            byte[] out,
            int targetChannel,
            int targetPixelStride) {
        if (targetChannel < 0 || targetPixelStride <= targetChannel) {
            throw new IllegalArgumentException("invalid interleaved red destination");
        }
        var pixels = Math.multiplyExact(width, height);
        if (pixels > 0 && (long) (pixels - 1) * targetPixelStride + targetChannel >= out.length) {
            throw new IllegalArgumentException("short interleaved red destination");
        }
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var blockSize = dxt1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                var blockOffset = (by * blocksX + bx) * blockSize;
                var colorOffset = dxt1 ? blockOffset : blockOffset + 8;
                var c0 = (src[colorOffset] & 0xff) | (src[colorOffset + 1] & 0xff) << 8;
                var c1 = (src[colorOffset + 2] & 0xff) | (src[colorOffset + 3] & 0xff) << 8;
                var r0Bits = c0 >> 11 & 0x1f;
                var r1Bits = c1 >> 11 & 0x1f;
                var r0 = r0Bits << 3 | r0Bits >> 2;
                var r1 = r1Bits << 3 | r1Bits >> 2;
                var threeColor = dxt1 && c0 <= c1;
                var r2 = threeColor ? (r0 + r1) / 2 : (2 * r0 + r1) / 3;
                var r3 = threeColor ? 0 : (r0 + 2 * r1) / 3;
                var redPalette = r0 | r1 << 8 | r2 << 16 | r3 << 24;
                for (var ty = 0; ty < 4; ty++) {
                    var y = by * 4 + ty;
                    if (y >= height) {
                        break;
                    }
                    var bits = src[colorOffset + 4 + ty] & 0xff;
                    var rowOffset = (y * width + bx * 4) * targetPixelStride + targetChannel;
                    var copy = Math.min(4, width - bx * 4);
                    if (copy == 4) {
                        out[rowOffset] = (byte) (redPalette >>> ((bits & 3) << 3));
                        out[rowOffset + targetPixelStride] = (byte) (redPalette >>> ((bits >>> 2 & 3) << 3));
                        out[rowOffset + targetPixelStride * 2] = (byte) (redPalette >>> ((bits >>> 4 & 3) << 3));
                        out[rowOffset + targetPixelStride * 3] = (byte) (redPalette >>> ((bits >>> 6) << 3));
                    } else {
                        for (var tx = 0; tx < copy; tx++) {
                            out[rowOffset + tx * targetPixelStride] =
                                    (byte) (redPalette >>> (((bits >>> (2 * tx)) & 3) << 3));
                        }
                    }
                }
            }
        }
    }

    static byte[] boxDownsample(byte[] src, int sw, int sh, int dw, int dh) {
        var dst = new byte[dw * dh * 4];
        for (int y = 0; y < dh; y++) {
            var y0 = Math.min(2 * y, sh - 1);
            var y1 = Math.min(2 * y + 1, sh - 1);
            for (int x = 0; x < dw; x++) {
                var x0 = Math.min(2 * x, sw - 1);
                var x1 = Math.min(2 * x + 1, sw - 1);
                var o00 = (y0 * sw + x0) * 4;
                var o01 = (y0 * sw + x1) * 4;
                var o10 = (y1 * sw + x0) * 4;
                var o11 = (y1 * sw + x1) * 4;
                var o = (y * dw + x) * 4;
                for (int c = 0; c < 4; c++) {
                    var sum = (src[o00 + c] & 0xFF)
                            + (src[o01 + c] & 0xFF)
                            + (src[o10 + c] & 0xFF)
                            + (src[o11 + c] & 0xFF);
                    dst[o + c] = (byte) ((sum + 2) >> 2);
                }
            }
        }
        return dst;
    }

    static byte[] encode(TextureFormat format, int width, int height, byte[] rgba) {
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var blockSize = dxt1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        var out = new byte[blocksX * blocksY * blockSize];
        var block = new int[16 * 4];
        var palette = new int[12];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                gatherBlock(rgba, width, height, bx, by, block);
                var off = (by * blocksX + bx) * blockSize;
                if (dxt1) {
                    encodeColorBlock(block, true, out, off, palette);
                } else if (format == TextureFormat.TEXTURE_DXT3) {
                    encodeDxt3Alpha(block, out, off);
                    encodeColorBlock(block, false, out, off + 8, palette);
                } else {
                    encodeDxt5Alpha(block, out, off, palette);
                    encodeColorBlock(block, false, out, off + 8, palette);
                }
            }
        }
        return out;
    }

    static void encodeDxt5Alpha(int[] block, byte[] out, int off) {
        encodeDxt5Alpha(block, out, off, new int[8]);
    }

    static void encodeDxt5Alpha(int[] block, byte[] out, int off, int[] palette) {
        var minA = 255;
        var maxA = 0;
        for (int t = 0; t < 16; t++) {
            var a = block[t * 4 + 3];
            if (a < minA) {
                minA = a;
            }
            if (a > maxA) {
                maxA = a;
            }
        }
        out[off] = (byte) maxA;
        out[off + 1] = (byte) minA;
        if (maxA == minA) {
            for (int i = 0; i < 6; i++) {
                out[off + 2 + i] = 0;
            }
            return;
        }
        palette[0] = maxA;
        palette[1] = minA;
        for (int i = 1; i <= 6; i++) {
            palette[i + 1] = ((7 - i) * maxA + i * minA) / 7;
        }
        long bits = 0;
        for (int t = 0; t < 16; t++) {
            var a = block[t * 4 + 3];
            var idx = 0;
            var best = Integer.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                var d = Math.abs(a - palette[k]);
                if (d < best) {
                    best = d;
                    idx = k;
                }
            }
            bits |= (long) idx << (3 * t);
        }
        for (int i = 0; i < 6; i++) {
            out[off + 2 + i] = (byte) ((bits >> (8 * i)) & 0xFF);
        }
    }

    private static int compressedLevelBytes(TextureFormat format, int width, int height, int level) {
        var levelWidth = Math.max(1, width >> level);
        var levelHeight = Math.max(1, height >> level);
        var blockBytes = format == TextureFormat.TEXTURE_DXT1 ? DXT1_BLOCK : DXT35_BLOCK;
        return Math.multiplyExact(Math.multiplyExact((levelWidth + 3) / 4, (levelHeight + 3) / 4), blockBytes);
    }

    private static void requireCompressed(TextureFormat format, int width, int height) {
        if (format != TextureFormat.TEXTURE_DXT1
                && format != TextureFormat.TEXTURE_DXT3
                && format != TextureFormat.TEXTURE_DXT5) {
            throw new IllegalArgumentException("mip completion requires a DXT texture");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
    }

    private static int[] decodeToPackedRgba(TextureFormat format, int width, int height, byte[] src) {
        var out = new int[width * height];
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var blockSize = dxt1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        var colors = new int[4];
        var alpha = new int[16];
        var alphaPalette = new int[8];
        var block = new int[16];
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                var off = (by * blocksX + bx) * blockSize;
                decodePackedBlock(format, src, off, block, colors, alpha, alphaPalette);
                for (var ty = 0; ty < 4; ty++) {
                    var y = by * 4 + ty;
                    if (y >= height) {
                        break;
                    }
                    var copy = Math.min(4, width - bx * 4);
                    System.arraycopy(block, ty * 4, out, y * width + bx * 4, copy);
                }
            }
        }
        return out;
    }

    private static int[] decodeAndDownsamplePacked(
            TextureFormat format,
            int width,
            int height,
            byte[] src,
            int dstWidth,
            int dstHeight,
            int[] block,
            int[] colors,
            int[] alpha,
            int[] alphaPalette) {
        var out = new int[dstWidth * dstHeight];
        var blockSize = format == TextureFormat.TEXTURE_DXT1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        for (var by = 0; by < blocksY; by++) {
            if (by * 2 >= dstHeight) {
                break;
            }
            for (var bx = 0; bx < blocksX; bx++) {
                if (bx * 2 >= dstWidth) {
                    break;
                }
                var off = (by * blocksX + bx) * blockSize;
                decodePackedBlock(format, src, off, block, colors, alpha, alphaPalette);
                var dstX = bx * 2;
                var dstY = by * 2;
                if (dstX + 1 < dstWidth && dstY + 1 < dstHeight && bx * 4 + 3 < width && by * 4 + 3 < height) {
                    var row0 = dstY * dstWidth + dstX;
                    var row1 = row0 + dstWidth;
                    out[row0] = averagePacked(block[0], block[1], block[4], block[5]);
                    out[row0 + 1] = averagePacked(block[2], block[3], block[6], block[7]);
                    out[row1] = averagePacked(block[8], block[9], block[12], block[13]);
                    out[row1 + 1] = averagePacked(block[10], block[11], block[14], block[15]);
                    continue;
                }
                for (var blockY = 0; blockY < 2; blockY++) {
                    var y = dstY + blockY;
                    if (y >= dstHeight) {
                        break;
                    }
                    var sourceY0 = blockY * 2;
                    var sourceY1 = Math.min(by * 4 + sourceY0 + 1, height - 1) - by * 4;
                    for (var blockX = 0; blockX < 2; blockX++) {
                        var x = dstX + blockX;
                        if (x >= dstWidth) {
                            break;
                        }
                        var sourceX0 = blockX * 2;
                        var sourceX1 = Math.min(bx * 4 + sourceX0 + 1, width - 1) - bx * 4;
                        out[y * dstWidth + x] = averagePacked(
                                block[sourceY0 * 4 + sourceX0], block[sourceY0 * 4 + sourceX1],
                                block[sourceY1 * 4 + sourceX0], block[sourceY1 * 4 + sourceX1]);
                    }
                }
            }
        }
        return out;
    }

    private static void decodePackedBlock(
            TextureFormat format, byte[] src, int off, int[] block, int[] colors, int[] alpha, int[] alphaPalette) {
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var colorOffset = off;
        if (format == TextureFormat.TEXTURE_DXT3) {
            decodeDxt3Alpha(src, off, alpha);
            colorOffset += 8;
        } else if (format == TextureFormat.TEXTURE_DXT5) {
            decodeDxt5Alpha(src, off, alpha, alphaPalette);
            colorOffset += 8;
        }
        var c0 = (src[colorOffset] & 0xff) | (src[colorOffset + 1] & 0xff) << 8;
        var c1 = (src[colorOffset + 2] & 0xff) | (src[colorOffset + 3] & 0xff) << 8;
        colors[0] = unpack565Packed(c0);
        colors[1] = unpack565Packed(c1);
        var threeColor = dxt1 && c0 <= c1;
        colors[2] = mixPacked(colors[0], colors[1], threeColor ? 1 : 2, 1, threeColor ? 2 : 3);
        colors[3] = threeColor ? 0 : mixPacked(colors[0], colors[1], 1, 2, 3);
        for (var ty = 0; ty < 4; ty++) {
            var bits = src[colorOffset + 4 + ty] & 0xff;
            var texel = ty * 4;
            var i0 = bits & 3;
            var i1 = bits >>> 2 & 3;
            var i2 = bits >>> 4 & 3;
            var i3 = bits >>> 6;
            if (!dxt1) {
                block[texel] = colors[i0] | alpha[texel] << 24;
                block[texel + 1] = colors[i1] | alpha[texel + 1] << 24;
                block[texel + 2] = colors[i2] | alpha[texel + 2] << 24;
                block[texel + 3] = colors[i3] | alpha[texel + 3] << 24;
            } else if (!threeColor) {
                block[texel] = colors[i0] | 0xff000000;
                block[texel + 1] = colors[i1] | 0xff000000;
                block[texel + 2] = colors[i2] | 0xff000000;
                block[texel + 3] = colors[i3] | 0xff000000;
            } else {
                block[texel] = colors[i0] | (i0 == 3 ? 0 : 0xff000000);
                block[texel + 1] = colors[i1] | (i1 == 3 ? 0 : 0xff000000);
                block[texel + 2] = colors[i2] | (i2 == 3 ? 0 : 0xff000000);
                block[texel + 3] = colors[i3] | (i3 == 3 ? 0 : 0xff000000);
            }
        }
    }

    private static void boxDownsamplePackedInPlace(int[] pixels, int sw, int sh, int dw, int dh) {
        for (var y = 0; y < dh; y++) {
            var y0 = Math.min(2 * y, sh - 1) * sw;
            var y1 = Math.min(2 * y + 1, sh - 1) * sw;
            for (var x = 0; x < dw; x++) {
                var x0 = Math.min(2 * x, sw - 1);
                var x1 = Math.min(2 * x + 1, sw - 1);
                var p00 = pixels[y0 + x0];
                var p01 = pixels[y0 + x1];
                var p10 = pixels[y1 + x0];
                var p11 = pixels[y1 + x1];
                pixels[y * dw + x] = averagePacked(p00, p01, p10, p11);
            }
        }
    }

    private static int averagePacked(int p00, int p01, int p10, int p11) {
        var r = ((p00 & 0xff) + (p01 & 0xff) + (p10 & 0xff) + (p11 & 0xff) + 2) >> 2;
        var g = ((p00 >>> 8 & 0xff) + (p01 >>> 8 & 0xff) + (p10 >>> 8 & 0xff) + (p11 >>> 8 & 0xff) + 2) >> 2;
        var b = ((p00 >>> 16 & 0xff) + (p01 >>> 16 & 0xff) + (p10 >>> 16 & 0xff) + (p11 >>> 16 & 0xff) + 2) >> 2;
        var a = ((p00 >>> 24) + (p01 >>> 24) + (p10 >>> 24) + (p11 >>> 24) + 2) >> 2;
        return r | g << 8 | b << 16 | a << 24;
    }

    private static byte[] encodePacked(
            TextureFormat format, int width, int height, int[] rgba, int[] block, int[] palette) {
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var blockSize = dxt1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        var out = new byte[blocksX * blocksY * blockSize];
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                gatherPackedBlock(rgba, width, height, bx, by, block);
                var off = (by * blocksX + bx) * blockSize;
                if (dxt1) {
                    encodePackedColorBlock(block, true, out, off, palette);
                } else if (format == TextureFormat.TEXTURE_DXT3) {
                    encodePackedDxt3Alpha(block, out, off);
                    encodePackedColorBlock(block, false, out, off + 8, palette);
                } else {
                    encodePackedDxt5Alpha(block, out, off, palette);
                    encodePackedColorBlock(block, false, out, off + 8, palette);
                }
            }
        }
        return out;
    }

    private static void gatherPackedBlock(int[] rgba, int width, int height, int bx, int by, int[] block) {
        for (var ty = 0; ty < 4; ty++) {
            var y = Math.min(by * 4 + ty, height - 1);
            for (var tx = 0; tx < 4; tx++) {
                var x = Math.min(bx * 4 + tx, width - 1);
                block[ty * 4 + tx] = rgba[y * width + x];
            }
        }
    }

    private static void encodePackedColorBlock(int[] block, boolean allowAlpha, byte[] out, int off, int[] palette) {
        var punch = false;
        if (allowAlpha) {
            for (var pixel : block) {
                if ((pixel >>> 24) < 128) {
                    punch = true;
                    break;
                }
            }
        }
        var minR = 255;
        var minG = 255;
        var minB = 255;
        var maxR = 0;
        var maxG = 0;
        var maxB = 0;
        var anyOpaque = false;
        for (var pixel : block) {
            if (punch && (pixel >>> 24) < 128) {
                continue;
            }
            anyOpaque = true;
            var r = pixel & 0xff;
            var g = pixel >>> 8 & 0xff;
            var b = pixel >>> 16 & 0xff;
            minR = Math.min(minR, r);
            minG = Math.min(minG, g);
            minB = Math.min(minB, b);
            maxR = Math.max(maxR, r);
            maxG = Math.max(maxG, g);
            maxB = Math.max(maxB, b);
        }
        if (!anyOpaque) {
            minR = minG = minB = 0;
            maxR = maxG = maxB = 0;
        }
        var hi = to565(maxR, maxG, maxB);
        var lo = to565(minR, minG, minB);
        int c0;
        int c1;
        if (punch) {
            c0 = Math.min(hi, lo);
            c1 = Math.max(hi, lo);
        } else {
            c0 = Math.max(hi, lo);
            c1 = Math.min(hi, lo);
            if (c0 <= c1) {
                if (c1 > 0) {
                    c1--;
                } else {
                    c0 = 1;
                }
            }
        }
        palette[0] = unpack565Packed(c0);
        palette[1] = unpack565Packed(c1);
        var threeColor = punch && c0 <= c1;
        palette[2] = mixPacked(palette[0], palette[1], threeColor ? 1 : 2, 1, threeColor ? 2 : 3);
        if (!threeColor) {
            palette[3] = mixPacked(palette[0], palette[1], 1, 2, 3);
        }
        var usable = threeColor ? 3 : 4;
        var p0 = palette[0];
        var p1 = palette[1];
        var p2 = palette[2];
        var p3 = palette[3];
        var p0r = p0 & 0xff;
        var p0g = p0 >>> 8 & 0xff;
        var p0b = p0 >>> 16 & 0xff;
        var p1r = p1 & 0xff;
        var p1g = p1 >>> 8 & 0xff;
        var p1b = p1 >>> 16 & 0xff;
        var p2r = p2 & 0xff;
        var p2g = p2 >>> 8 & 0xff;
        var p2b = p2 >>> 16 & 0xff;
        var p3r = p3 & 0xff;
        var p3g = p3 >>> 8 & 0xff;
        var p3b = p3 >>> 16 & 0xff;
        var indexBits = 0;
        for (var texel = 0; texel < 16; texel++) {
            var pixel = block[texel];
            int index;
            if (threeColor && (pixel >>> 24) < 128) {
                index = 3;
            } else {
                var r = pixel & 0xff;
                var g = pixel >>> 8 & 0xff;
                var b = pixel >>> 16 & 0xff;
                index = 0;
                var dr = r - p0r;
                var dg = g - p0g;
                var db = b - p0b;
                var best = dr * dr + dg * dg + db * db;
                dr = r - p1r;
                dg = g - p1g;
                db = b - p1b;
                var distance = dr * dr + dg * dg + db * db;
                if (distance < best) {
                    best = distance;
                    index = 1;
                }
                dr = r - p2r;
                dg = g - p2g;
                db = b - p2b;
                distance = dr * dr + dg * dg + db * db;
                if (distance < best) {
                    best = distance;
                    index = 2;
                }
                if (usable == 4) {
                    dr = r - p3r;
                    dg = g - p3g;
                    db = b - p3b;
                    if (dr * dr + dg * dg + db * db < best) {
                        index = 3;
                    }
                }
            }
            indexBits |= index << (2 * texel);
        }
        out[off] = (byte) c0;
        out[off + 1] = (byte) (c0 >>> 8);
        out[off + 2] = (byte) c1;
        out[off + 3] = (byte) (c1 >>> 8);
        out[off + 4] = (byte) indexBits;
        out[off + 5] = (byte) (indexBits >>> 8);
        out[off + 6] = (byte) (indexBits >>> 16);
        out[off + 7] = (byte) (indexBits >>> 24);
    }

    private static void encodePackedDxt3Alpha(int[] block, byte[] out, int off) {
        for (var index = 0; index < 8; index++) {
            var a0 = quant4(block[index * 2] >>> 24);
            var a1 = quant4(block[index * 2 + 1] >>> 24);
            out[off + index] = (byte) (a1 << 4 | a0);
        }
    }

    private static void encodePackedDxt5Alpha(int[] block, byte[] out, int off, int[] palette) {
        var minA = 255;
        var maxA = 0;
        for (var pixel : block) {
            var alpha = pixel >>> 24;
            minA = Math.min(minA, alpha);
            maxA = Math.max(maxA, alpha);
        }
        out[off] = (byte) maxA;
        out[off + 1] = (byte) minA;
        if (maxA == minA) {
            Arrays.fill(out, off + 2, off + 8, (byte) 0);
            return;
        }
        palette[0] = maxA;
        palette[1] = minA;
        for (var index = 1; index <= 6; index++) {
            palette[index + 1] = ((7 - index) * maxA + index * minA) / 7;
        }
        long bits = 0;
        for (var texel = 0; texel < 16; texel++) {
            var alpha = block[texel] >>> 24;
            var selected = 0;
            var best = Integer.MAX_VALUE;
            for (var candidate = 0; candidate < 8; candidate++) {
                var distance = Math.abs(alpha - palette[candidate]);
                if (distance < best) {
                    best = distance;
                    selected = candidate;
                }
            }
            bits |= (long) selected << (3 * texel);
        }
        for (var index = 0; index < 6; index++) {
            out[off + 2 + index] = (byte) (bits >> (8 * index));
        }
    }

    private static int unpack565Packed(int value) {
        var red = value >> 11 & 0x1f;
        var green = value >> 5 & 0x3f;
        var blue = value & 0x1f;
        return (red << 3 | red >> 2) | (green << 2 | green >> 4) << 8 | (blue << 3 | blue >> 2) << 16;
    }

    private static int mixPacked(int first, int second, int firstWeight, int secondWeight, int divisor) {
        var r = (firstWeight * (first & 0xff) + secondWeight * (second & 0xff)) / divisor;
        var g = (firstWeight * (first >>> 8 & 0xff) + secondWeight * (second >>> 8 & 0xff)) / divisor;
        var b = (firstWeight * (first >>> 16 & 0xff) + secondWeight * (second >>> 16 & 0xff)) / divisor;
        return r | g << 8 | b << 16;
    }

    private static long[] channelSums(TextureFormat format, int width, int height, byte[] src) {
        var sums = new long[4];
        var block = new int[16];
        var colors = new int[4];
        var alpha = new int[16];
        var palette = new int[8];
        var blockSize = format == TextureFormat.TEXTURE_DXT1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                decodePackedBlock(format, src, (by * blocksX + bx) * blockSize, block, colors, alpha, palette);
                var copyWidth = Math.min(4, width - bx * 4);
                var copyHeight = Math.min(4, height - by * 4);
                for (var y = 0; y < copyHeight; y++) {
                    for (var x = 0; x < copyWidth; x++) {
                        var pixel = block[y * 4 + x];
                        sums[0] += pixel & 0xff;
                        sums[1] += pixel >>> 8 & 0xff;
                        sums[2] += pixel >>> 16 & 0xff;
                        sums[3] += pixel >>> 24;
                    }
                }
            }
        }
        return sums;
    }

    private static void decodeColorBlock(byte[] src, int off, boolean dxt1, int[] color, int[] palette) {
        var c0 = (src[off] & 0xFF) | ((src[off + 1] & 0xFF) << 8);
        var c1 = (src[off + 2] & 0xFF) | ((src[off + 3] & 0xFF) << 8);
        unpack565(c0, palette, 0);
        unpack565(c1, palette, 1);
        if (dxt1 && c0 <= c1) {
            palette[2] = (palette[0] + palette[1]) / 2;
            palette[6] = (palette[4] + palette[5]) / 2;
            palette[10] = (palette[8] + palette[9]) / 2;
            palette[3] = 0;
            palette[7] = 0;
            palette[11] = 0;
        } else {
            palette[2] = (2 * palette[0] + palette[1]) / 3;
            palette[6] = (2 * palette[4] + palette[5]) / 3;
            palette[10] = (2 * palette[8] + palette[9]) / 3;
            palette[3] = (palette[0] + 2 * palette[1]) / 3;
            palette[7] = (palette[4] + 2 * palette[5]) / 3;
            palette[11] = (palette[8] + 2 * palette[9]) / 3;
        }
        for (int i = 0; i < 4; i++) {
            var bits = src[off + 4 + i] & 0xFF;
            for (int j = 0; j < 4; j++) {
                var idx = (bits >> (2 * j)) & 0x3;
                var t = (i * 4 + j) * 4;
                color[t] = palette[idx];
                color[t + 1] = palette[4 + idx];
                color[t + 2] = palette[8 + idx];
                color[t + 3] = dxt1 && c0 <= c1 && idx == 3 ? 0 : 255;
            }
        }
    }

    private static void decodeDxt3Alpha(byte[] src, int off, int[] alpha) {
        for (int i = 0; i < 8; i++) {
            var v = src[off + i] & 0xFF;
            var lo = v & 0x0F;
            var hi = (v >> 4) & 0x0F;
            alpha[i * 2] = (lo << 4) | lo;
            alpha[i * 2 + 1] = (hi << 4) | hi;
        }
    }

    private static void decodeDxt5Alpha(byte[] src, int off, int[] alpha, int[] palette) {
        var a0 = src[off] & 0xFF;
        var a1 = src[off + 1] & 0xFF;
        palette[0] = a0;
        palette[1] = a1;
        if (a0 > a1) {
            for (int i = 1; i <= 6; i++) {
                palette[i + 1] = ((7 - i) * a0 + i * a1) / 7;
            }
        } else {
            for (int i = 1; i <= 4; i++) {
                palette[i + 1] = ((5 - i) * a0 + i * a1) / 5;
            }
            palette[6] = 0;
            palette[7] = 255;
        }
        long bits = 0;
        for (int i = 0; i < 6; i++) {
            bits |= (long) (src[off + 2 + i] & 0xFF) << (8 * i);
        }
        for (int t = 0; t < 16; t++) {
            alpha[t] = palette[(int) ((bits >> (3 * t)) & 0x7)];
        }
    }

    private static void unpack565(int value, int[] palette, int idx) {
        var red = (value >> 11) & 0x1f;
        var green = (value >> 5) & 0x3f;
        var blue = value & 0x1f;
        palette[idx] = (red << 3) | (red >> 2);
        palette[4 + idx] = (green << 2) | (green >> 4);
        palette[8 + idx] = (blue << 3) | (blue >> 2);
    }

    private static void gatherBlock(byte[] rgba, int width, int height, int bx, int by, int[] block) {
        for (int ty = 0; ty < 4; ty++) {
            var y = Math.min(by * 4 + ty, height - 1);
            for (int tx = 0; tx < 4; tx++) {
                var x = Math.min(bx * 4 + tx, width - 1);
                var s = (y * width + x) * 4;
                var t = (ty * 4 + tx) * 4;
                block[t] = rgba[s] & 0xFF;
                block[t + 1] = rgba[s + 1] & 0xFF;
                block[t + 2] = rgba[s + 2] & 0xFF;
                block[t + 3] = rgba[s + 3] & 0xFF;
            }
        }
    }

    private static void encodeColorBlock(int[] block, boolean allowAlpha, byte[] out, int off, int[] palette) {
        var punch = false;
        if (allowAlpha) {
            for (int t = 0; t < 16; t++) {
                if (block[t * 4 + 3] < 128) {
                    punch = true;
                    break;
                }
            }
        }
        var minR = 255;
        var minG = 255;
        var minB = 255;
        var maxR = 0;
        var maxG = 0;
        var maxB = 0;
        var anyOpaque = false;
        for (int t = 0; t < 16; t++) {
            if (punch && block[t * 4 + 3] < 128) {
                continue;
            }
            anyOpaque = true;
            var r = block[t * 4];
            var g = block[t * 4 + 1];
            var b = block[t * 4 + 2];
            if (r < minR) {
                minR = r;
            }
            if (r > maxR) {
                maxR = r;
            }
            if (g < minG) {
                minG = g;
            }
            if (g > maxG) {
                maxG = g;
            }
            if (b < minB) {
                minB = b;
            }
            if (b > maxB) {
                maxB = b;
            }
        }
        if (!anyOpaque) {
            minR = minG = minB = 0;
            maxR = maxG = maxB = 0;
        }
        var hi = to565(maxR, maxG, maxB);
        var lo = to565(minR, minG, minB);
        int c0;
        int c1;
        if (punch) {
            c0 = Math.min(hi, lo);
            c1 = Math.max(hi, lo);
        } else {
            c0 = Math.max(hi, lo);
            c1 = Math.min(hi, lo);
            if (c0 <= c1) {
                if (c1 > 0) {
                    c1 -= 1;
                } else {
                    c0 = 1;
                }
            }
        }
        unpack565(c0, palette, 0);
        unpack565(c1, palette, 1);
        var threeColor = punch && c0 <= c1;
        if (threeColor) {
            palette[2] = (palette[0] + palette[1]) / 2;
            palette[6] = (palette[4] + palette[5]) / 2;
            palette[10] = (palette[8] + palette[9]) / 2;
        } else {
            palette[2] = (2 * palette[0] + palette[1]) / 3;
            palette[6] = (2 * palette[4] + palette[5]) / 3;
            palette[10] = (2 * palette[8] + palette[9]) / 3;
            palette[3] = (palette[0] + 2 * palette[1]) / 3;
            palette[7] = (palette[4] + 2 * palette[5]) / 3;
            palette[11] = (palette[8] + 2 * palette[9]) / 3;
        }
        var usable = threeColor ? 3 : 4;
        var idxBits = 0;
        for (int t = 0; t < 16; t++) {
            int idx;
            if (threeColor && block[t * 4 + 3] < 128) {
                idx = 3;
            } else {
                idx = 0;
                var best = Integer.MAX_VALUE;
                for (int k = 0; k < usable; k++) {
                    var dr = block[t * 4] - palette[k];
                    var dg = block[t * 4 + 1] - palette[4 + k];
                    var db = block[t * 4 + 2] - palette[8 + k];
                    var d = dr * dr + dg * dg + db * db;
                    if (d < best) {
                        best = d;
                        idx = k;
                    }
                }
            }
            idxBits |= idx << (2 * t);
        }
        out[off] = (byte) (c0 & 0xFF);
        out[off + 1] = (byte) ((c0 >> 8) & 0xFF);
        out[off + 2] = (byte) (c1 & 0xFF);
        out[off + 3] = (byte) ((c1 >> 8) & 0xFF);
        out[off + 4] = (byte) (idxBits & 0xFF);
        out[off + 5] = (byte) ((idxBits >> 8) & 0xFF);
        out[off + 6] = (byte) ((idxBits >> 16) & 0xFF);
        out[off + 7] = (byte) ((idxBits >> 24) & 0xFF);
    }

    private static void encodeDxt3Alpha(int[] block, byte[] out, int off) {
        for (int i = 0; i < 8; i++) {
            var a0 = quant4(block[(i * 2) * 4 + 3]);
            var a1 = quant4(block[(i * 2 + 1) * 4 + 3]);
            out[off + i] = (byte) ((a1 << 4) | a0);
        }
    }

    private static int quant4(int a) {
        return (a * 15 + 127) / 255;
    }

    private static int to565(int r, int g, int b) {
        return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
    }
}
