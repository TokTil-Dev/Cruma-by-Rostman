package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TerrainG16Codec {
    private TerrainG16Codec() {}

    public record Mip(int width, int height, int dataOffset, List<Integer> heights) {
        public Mip {
            heights = List.copyOf(heights);
        }

        public int heightAt(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                throw new IndexOutOfBoundsException();
            }
            return heights.get(y * width + x);
        }
    }

    public static List<Mip> read(byte[] nativeMips, int absoluteOffset, int width, int height) throws IOException {
        if (width < 1
                || height < 1
                || width > 4096
                || height > 4096
                || Integer.bitCount(width) != 1
                || Integer.bitCount(height) != 1
                || absoluteOffset < 0) {
            throw new IOException("Invalid G16 dimensions/offset");
        }
        var c = new Cursor(nativeMips, 0, nativeMips.length);
        var count = c.readIndex();
        if (count < 1 || count > 32 - Integer.numberOfLeadingZeros(Math.max(width, height))) {
            throw new IOException("Invalid G16 mip count");
        }
        var result = new ArrayList<Mip>(count);
        for (var i = 0; i < count; i++) {
            var end = c.readI32();
            var length = c.readIndex();
            var expected = Math.multiplyExact(Math.multiplyExact(width, height), 2);
            if (length != expected
                    || (long) absoluteOffset + c.position + length != end
                    || length > c.end - c.position) {
                throw new IOException("G16 lazy array length or absolute end offset differs");
            }
            var dataOffset = absoluteOffset + c.position;
            var heights = new ArrayList<Integer>(width * height);
            for (var pixel = 0; pixel < width * height; pixel++) {
                heights.add(c.readU16());
            }
            if (c.readI32() != width
                    || c.readI32() != height
                    || c.readU8() != Integer.numberOfTrailingZeros(width)
                    || c.readU8() != Integer.numberOfTrailingZeros(height)) {
                throw new IOException("Invalid G16 mip trailer");
            }
            result.add(new Mip(width, height, dataOffset, heights));
            width = Math.max(1, width >> 1);
            height = Math.max(1, height >> 1);
        }
        if (c.position != c.end || !Arrays.equals(nativeMips, write(result, absoluteOffset))) {
            throw new IOException("Unknown G16 tail or noncanonical mip encoding");
        }
        return List.copyOf(result);
    }

    public static byte[] write(List<Mip> mips, int absoluteOffset) {
        var out = new ByteArrayOutputStream();
        PackageBytes.writeIndex(out, mips.size());
        for (var mip : mips) {
            if (mip.heights().size() != Math.multiplyExact(mip.width(), mip.height())) {
                throw new IllegalArgumentException("G16 height count differs from dimensions");
            }
            var count = new ByteArrayOutputStream();
            PackageBytes.writeIndex(count, Math.multiplyExact(mip.heights().size(), 2));
            PackageBytes.writeInt(
                    out,
                    Math.addExact(
                            absoluteOffset,
                            out.size() + 4 + count.size() + mip.heights().size() * 2));
            out.writeBytes(count.toByteArray());
            for (var value : mip.heights()) {
                if (value < 0 || value > 65535) {
                    throw new IllegalArgumentException("G16 height outside uint16");
                }
                out.write(value);
                out.write(value >>> 8);
            }
            PackageBytes.writeInt(out, mip.width());
            PackageBytes.writeInt(out, mip.height());
            out.write(Integer.numberOfTrailingZeros(mip.width()));
            out.write(Integer.numberOfTrailingZeros(mip.height()));
        }
        return out.toByteArray();
    }
}
