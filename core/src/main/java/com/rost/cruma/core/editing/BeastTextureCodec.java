package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;
import static com.rost.cruma.core.editing.PackageBytes.writeIndex;
import static com.rost.cruma.core.editing.PackageBytes.writeInt;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class BeastTextureCodec {
    static final int DXT1 = 3;

    public static final int MIP_LEVELS = 2;

    private BeastTextureCodec() {}

    public record Template(byte[] properties, int[] sizes, byte[] material) {}

    public record Saved(int width, int height, List<byte[]> mips) {}

    static Template readTemplate(PackageBytes source, ExportEntry entry) throws IOException {
        var start = entry.serialOffset();
        var end = start + entry.serialSize();
        var c = new Cursor(source.body, start, end);
        if ((entry.flags() & 0x02000000) != 0) {
            throw malformed("A Beast lightmap texture with a state frame is not supported");
        }
        var sizes = new int[] {-1, -1, -1, -1, -1, -1};
        var format = -1;
        for (var tags = 0; tags < 64; tags++) {
            var name = source.getName(c.readIndex());
            if (name.equalsIgnoreCase("None")) {
                break;
            }
            var info = c.readU8();
            var type = info & 15;
            if (type == 10) {
                c.readIndex();
            }
            var size = switch (info >> 4 & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> c.readU8();
                case 6 -> c.readU16();
                default -> c.readI32();
            };
            if (type != 3 && (info & 128) != 0) {
                c.readArrayIndex();
            }
            var slot = switch (name.toLowerCase(Locale.ROOT)) {
                case "ubits" -> 0;
                case "vbits" -> 1;
                case "usize" -> 2;
                case "vsize" -> 3;
                case "uclamp" -> 4;
                case "vclamp" -> 5;
                default -> -1;
            };
            if (slot >= 0) {
                var expected = slot < 2 ? 1 : 4;
                var expectedType = slot < 2 ? 1 : 2;
                if (type != expectedType || size != expected) {
                    throw malformed("Unexpected Beast lightmap property " + name);
                }
                sizes[slot] = c.position - start;
            }
            if (name.equalsIgnoreCase("Format")) {
                if (type != 1 || size != 1) {
                    throw malformed("Unexpected Beast lightmap Format property");
                }
                format = source.body[c.position] & 255;
            }
            c.skip(type == 3 ? 0 : size);
        }
        for (var offset : sizes) {
            if (offset < 0) {
                throw malformed("A Beast lightmap texture misses a size property");
            }
        }
        if (format != DXT1) {
            throw malformed("Only DXT1 Beast lightmaps are supported");
        }
        var propertiesEnd = c.position;
        var width = PackageBytes.littleEndian(source.body).getInt(start + sizes[2]);
        var height = PackageBytes.littleEndian(source.body).getInt(start + sizes[3]);
        var mips = findMips(source.body, propertiesEnd, end, width, height);
        if (mips < 0) {
            throw malformed("Cannot find the mips of the Beast lightmap texture");
        }
        var properties = Arrays.copyOfRange(source.body, start, propertiesEnd);
        var material = Arrays.copyOfRange(source.body, propertiesEnd, mips);
        var relative = new int[6];
        System.arraycopy(sizes, 0, relative, 0, 6);
        return new Template(properties, relative, material);
    }

    static Saved read(PackageBytes source, ExportEntry entry) throws IOException {
        var template = readTemplate(source, entry);
        var start = entry.serialOffset();
        var little = PackageBytes.littleEndian(source.body);
        var width = little.getInt(start + template.sizes()[2]);
        var height = little.getInt(start + template.sizes()[3]);
        var c = new Cursor(
                source.body,
                start + template.properties().length + template.material().length,
                start + entry.serialSize());
        var count = c.readIndex();
        var mips = new ArrayList<byte[]>();
        var w = width;
        var h = height;
        for (var level = 0; level < count; level++) {
            c.readI32();
            var length = c.readIndex();
            if (length != dxt1Size(w, h)) {
                throw malformed("Unexpected Beast lightmap mip size");
            }
            mips.add(Arrays.copyOfRange(source.body, c.position, c.position + length));
            c.skip(length);
            c.skip(10);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return new Saved(width, height, mips);
    }

    static int findMips(byte[] body, int from, int end, int width, int height) {
        var little = PackageBytes.littleEndian(body);
        for (var p = from; p < Math.min(end, from + 4096); p++) {
            try {
                var c = new Cursor(body, p, end);
                if (c.readIndex() != MIP_LEVELS) {
                    continue;
                }
                var w = width;
                var h = height;
                var valid = true;
                for (var level = 0; level < MIP_LEVELS && valid; level++) {
                    var stored = c.readI32();
                    var length = c.readIndex();
                    if (length != dxt1Size(w, h) || stored != c.position + length) {
                        valid = false;
                        break;
                    }
                    c.skip(length);
                    if (little.getInt(c.position) != w || little.getInt(c.position + 4) != h) {
                        valid = false;
                        break;
                    }
                    c.skip(10);
                    w = Math.max(1, w / 2);
                    h = Math.max(1, h / 2);
                }
                if (valid && c.position == end) {
                    return p;
                }
            } catch (IOException outside) {
            }
        }
        return -1;
    }

    static byte[] writeBody(Template template, int width, int height, List<byte[]> mips, int serialOffset) {
        if (mips.size() != MIP_LEVELS) {
            throw new IllegalArgumentException("A Beast lightmap needs " + MIP_LEVELS + " mips");
        }
        var out = new ByteArrayOutputStream();
        var properties = template.properties().clone();
        var little = PackageBytes.littleEndian(properties);
        properties[template.sizes()[0]] = (byte) Integer.numberOfTrailingZeros(width);
        properties[template.sizes()[1]] = (byte) Integer.numberOfTrailingZeros(height);
        little.putInt(template.sizes()[2], width);
        little.putInt(template.sizes()[3], height);
        little.putInt(template.sizes()[4], width);
        little.putInt(template.sizes()[5], height);
        out.writeBytes(properties);
        out.writeBytes(template.material());
        writeIndex(out, MIP_LEVELS);
        var w = width;
        var h = height;
        for (var mip : mips) {
            if (mip.length != dxt1Size(w, h)) {
                throw new IllegalArgumentException("A Beast lightmap mip has the wrong size");
            }
            var lengthBytes = new ByteArrayOutputStream();
            writeIndex(lengthBytes, mip.length);
            var endOffset = serialOffset + out.size() + 4 + lengthBytes.size() + mip.length;
            writeInt(out, endOffset);
            out.writeBytes(lengthBytes.toByteArray());
            out.writeBytes(mip);
            writeInt(out, w);
            writeInt(out, h);
            out.write(Integer.numberOfTrailingZeros(w));
            out.write(Integer.numberOfTrailingZeros(h));
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return out.toByteArray();
    }

    static byte[] relocate(Template template, byte[] body, int from, int to) throws IOException {
        var result = body.clone();
        var little = PackageBytes.littleEndian(result);
        var c = new Cursor(result, template.properties().length + template.material().length, result.length);
        var count = c.readIndex();
        for (var level = 0; level < count; level++) {
            var at = c.position;
            var stored = c.readI32();
            little.putInt(at, stored - from + to);
            var length = c.readIndex();
            c.skip(length + 10);
        }
        return result;
    }

    static int dxt1Size(int width, int height) {
        return Math.max(1, (width + 3) / 4) * Math.max(1, (height + 3) / 4) * 8;
    }

    public static boolean isValidSize(int width, int height) {
        return width >= 4
                && height >= 4
                && width <= 2048
                && height <= 2048
                && Integer.bitCount(width) == 1
                && Integer.bitCount(height) == 1;
    }

    public static float[] decode(byte[] dxt1, int width, int height) {
        var rgba = DxtMipCodec.decodeToRgba(TextureFormat.TEXTURE_DXT1, width, height, dxt1);
        var rgb = new float[width * height * 3];
        for (var i = 0; i < width * height; i++) {
            rgb[i * 3] = rgba[i * 4] & 255;
            rgb[i * 3 + 1] = rgba[i * 4 + 1] & 255;
            rgb[i * 3 + 2] = rgba[i * 4 + 2] & 255;
        }
        return rgb;
    }

    public static List<byte[]> encodeMips(float[] rgb, int width, int height) {
        var half = halve(rgb, width, height);
        return List.of(encode(rgb, width, height), encode(half, Math.max(1, width / 2), Math.max(1, height / 2)));
    }

    public static float[] halve(float[] rgb, int width, int height) {
        var w = Math.max(1, width / 2);
        var h = Math.max(1, height / 2);
        var result = new float[w * h * 3];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                for (var c = 0; c < 3; c++) {
                    var sum = 0f;
                    for (var dy = 0; dy < 2; dy++) {
                        for (var dx = 0; dx < 2; dx++) {
                            var sx = Math.min(width - 1, x * 2 + dx);
                            var sy = Math.min(height - 1, y * 2 + dy);
                            sum += rgb[(sy * width + sx) * 3 + c];
                        }
                    }
                    result[(y * w + x) * 3 + c] = sum / 4;
                }
            }
        }
        return result;
    }

    public static byte[] encode(float[] rgb, int width, int height) {
        var blocksX = Math.max(1, (width + 3) / 4);
        var blocksY = Math.max(1, (height + 3) / 4);
        var out = new byte[blocksX * blocksY * 8];
        var block = new float[48];
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                for (var ty = 0; ty < 4; ty++) {
                    for (var tx = 0; tx < 4; tx++) {
                        var x = Math.min(width - 1, bx * 4 + tx);
                        var y = Math.min(height - 1, by * 4 + ty);
                        for (var c = 0; c < 3; c++) {
                            block[(ty * 4 + tx) * 3 + c] = Math.clamp(rgb[(y * width + x) * 3 + c], 0f, 255f);
                        }
                    }
                }
                encodeBlock(block, out, (by * blocksX + bx) * 8);
            }
        }
        return out;
    }

    public static byte[] encodeChanged(byte[] saved, float[] rgb, boolean[] changed, int width, int height) {
        var blocksX = Math.max(1, (width + 3) / 4);
        var blocksY = Math.max(1, (height + 3) / 4);
        var out = saved.clone();
        var block = new float[48];
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                var touched = false;
                for (var ty = 0; ty < 4 && !touched; ty++) {
                    for (var tx = 0; tx < 4 && !touched; tx++) {
                        var x = bx * 4 + tx;
                        var y = by * 4 + ty;
                        touched = x < width && y < height && changed[y * width + x];
                    }
                }
                if (!touched) {
                    continue;
                }
                for (var ty = 0; ty < 4; ty++) {
                    for (var tx = 0; tx < 4; tx++) {
                        var x = Math.min(width - 1, bx * 4 + tx);
                        var y = Math.min(height - 1, by * 4 + ty);
                        for (var c = 0; c < 3; c++) {
                            block[(ty * 4 + tx) * 3 + c] = Math.clamp(rgb[(y * width + x) * 3 + c], 0f, 255f);
                        }
                    }
                }
                encodeBlock(block, out, (by * blocksX + bx) * 8);
            }
        }
        return out;
    }

    static void encodeBlock(float[] block, byte[] out, int offset) {
        float mr = 0, mg = 0, mb = 0;
        for (var i = 0; i < 16; i++) {
            mr += block[i * 3];
            mg += block[i * 3 + 1];
            mb += block[i * 3 + 2];
        }
        mr /= 16;
        mg /= 16;
        mb /= 16;
        double rr = 0, rg = 0, rb = 0, gg = 0, gb = 0, bb = 0;
        for (var i = 0; i < 16; i++) {
            var r = block[i * 3] - mr;
            var g = block[i * 3 + 1] - mg;
            var b = block[i * 3 + 2] - mb;
            rr += r * r;
            rg += r * g;
            rb += r * b;
            gg += g * g;
            gb += g * b;
            bb += b * b;
        }
        double ax = 1, ay = 1, az = 1;
        for (var iteration = 0; iteration < 8; iteration++) {
            var nx = rr * ax + rg * ay + rb * az;
            var ny = rg * ax + gg * ay + gb * az;
            var nz = rb * ax + gb * ay + bb * az;
            var length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-9) {
                break;
            }
            ax = nx / length;
            ay = ny / length;
            az = nz / length;
        }
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (var i = 0; i < 16; i++) {
            var t = (block[i * 3] - mr) * ax + (block[i * 3 + 1] - mg) * ay + (block[i * 3 + 2] - mb) * az;
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        var start = new float[] {(float) (mr + ax * max), (float) (mg + ay * max), (float) (mb + az * max)};
        var stop = new float[] {(float) (mr + ax * min), (float) (mg + ay * min), (float) (mb + az * min)};
        var bestError = Double.MAX_VALUE;
        var best = new byte[8];
        var candidate = new byte[8];
        var indices = new int[16];
        for (var round = 0; round < 3; round++) {
            var c0 = to565(start);
            var c1 = to565(stop);
            var error = assemble(block, c0, c1, candidate, indices);
            if (error < bestError) {
                bestError = error;
                System.arraycopy(candidate, 0, best, 0, 8);
            }
            if (!refine(block, indices, c0 < c1, start, stop)) {
                break;
            }
        }
        System.arraycopy(best, 0, out, offset, 8);
    }

    private static boolean refine(float[] block, int[] indices, boolean swapped, float[] start, float[] stop) {
        double aa = 0, ab = 0, bb = 0;
        var ax = new double[3];
        var bx = new double[3];
        for (var i = 0; i < 16; i++) {
            var index = indices[i];
            var alpha = switch (index) {
                case 0 -> 1.0;
                case 1 -> 0.0;
                case 2 -> 2.0 / 3;
                default -> 1.0 / 3;
            };
            if (swapped) {
                alpha = 1 - alpha;
            }
            var beta = 1 - alpha;
            aa += alpha * alpha;
            ab += alpha * beta;
            bb += beta * beta;
            for (var c = 0; c < 3; c++) {
                ax[c] += alpha * block[i * 3 + c];
                bx[c] += beta * block[i * 3 + c];
            }
        }
        var determinant = aa * bb - ab * ab;
        if (Math.abs(determinant) < 1e-9) {
            return false;
        }
        for (var c = 0; c < 3; c++) {
            start[c] = (float) Math.clamp((ax[c] * bb - bx[c] * ab) / determinant, 0, 255);
            stop[c] = (float) Math.clamp((bx[c] * aa - ax[c] * ab) / determinant, 0, 255);
        }
        return true;
    }

    private static double assemble(float[] block, int first, int second, byte[] out, int[] indices) {
        var c0 = Math.max(first, second);
        var c1 = Math.min(first, second);
        var palette = new float[12];
        unpack(c0, palette, 0);
        unpack(c1, palette, 3);
        var sameColor = c0 == c1;
        for (var c = 0; c < 3; c++) {
            palette[6 + c] = (2 * palette[c] + palette[3 + c]) / 3;
            palette[9 + c] = (palette[c] + 2 * palette[3 + c]) / 3;
        }
        var bits = 0;
        var error = 0.0;
        for (var i = 0; i < 16; i++) {
            var bestIndex = 0;
            var bestDistance = Double.MAX_VALUE;
            for (var p = 0; p < (sameColor ? 1 : 4); p++) {
                var distance = 0.0;
                for (var c = 0; c < 3; c++) {
                    var d = block[i * 3 + c] - palette[p * 3 + c];
                    distance += d * d;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = p;
                }
            }
            indices[i] = bestIndex;
            error += bestDistance;
            bits |= bestIndex << (i * 2);
        }
        out[0] = (byte) c0;
        out[1] = (byte) (c0 >>> 8);
        out[2] = (byte) c1;
        out[3] = (byte) (c1 >>> 8);
        out[4] = (byte) bits;
        out[5] = (byte) (bits >>> 8);
        out[6] = (byte) (bits >>> 16);
        out[7] = (byte) (bits >>> 24);
        return error;
    }

    private static int to565(float[] rgb) {
        var r = Math.clamp(Math.round(rgb[0] * 31f / 255f), 0, 31);
        var g = Math.clamp(Math.round(rgb[1] * 63f / 255f), 0, 63);
        var b = Math.clamp(Math.round(rgb[2] * 31f / 255f), 0, 31);
        return r << 11 | g << 5 | b;
    }

    private static void unpack(int value, float[] palette, int offset) {
        var r = value >>> 11 & 31;
        var g = value >>> 5 & 63;
        var b = value & 31;
        palette[offset] = r << 3 | r >>> 2;
        palette[offset + 1] = g << 2 | g >>> 4;
        palette[offset + 2] = b << 3 | b >>> 2;
    }
}
