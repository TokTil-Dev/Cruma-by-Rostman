package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class TerrainDensityTexture {
    static final int P8 = TerrainAlphaTexture.P8;
    static final int DXT1 = TerrainAlphaTexture.DXT1;
    static final int RGBA8 = TerrainAlphaTexture.RGBA8;

    private final String objectPath;
    private final int exportIndex;
    private final int format;
    private final List<Mip> mips;
    private final int[] paletteRed;

    private TerrainDensityTexture(String objectPath, int exportIndex, int format, List<Mip> mips, int[] paletteRed) {
        this.objectPath = objectPath;
        this.exportIndex = exportIndex;
        this.format = format;
        this.mips = List.copyOf(mips);
        this.paletteRed = paletteRed;
    }

    record Mip(int width, int height, int offset, int length) {}

    static TerrainDensityTexture find(PackageBytes source, String objectPath) throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (source.getObjectPath(entry.index()).equalsIgnoreCase(objectPath)) {
                if (found != null) {
                    throw new IOException("The name of its density map is ambiguous");
                }
                found = entry;
            }
        }
        if (found == null) {
            throw new IOException("Its density map " + objectPath + " is missing from " + source.packageName);
        }
        if ((found.flags() & 0x02000000) != 0
                || found.superIndex() != 0
                || !source.getObjectPath(found.classIndex()).equalsIgnoreCase("Engine.Texture")) {
            throw new IOException("Its density map is not a plain Engine.Texture");
        }
        var format = TerrainPackageSnapshot.exportScalar(source, found, "Format", 1, P8);
        var width = TerrainPackageSnapshot.exportScalar(source, found, "USize", 2, -1);
        var height = TerrainPackageSnapshot.exportScalar(source, found, "VSize", 2, -1);
        if (format != DXT1 && format != P8 && format != RGBA8) {
            throw new IOException("Its density map uses format " + format + ", which Cruma cannot write");
        }
        if (width < 4
                || height < 4
                || width > 4096
                || height > 4096
                || Integer.bitCount(width) != 1
                || Integer.bitCount(height) != 1) {
            throw new IOException("Its density map has an unusual size");
        }
        var cursor = TerrainPackageSnapshot.textureMips(source, found);
        var count = cursor.readIndex();
        var most = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (count < 1 || count > most) {
            throw new IOException("Its density map has " + count + " mip levels, which does not fit its size");
        }
        var mips = new ArrayList<Mip>();
        var u = width;
        var v = height;
        for (var level = 0; level < count; level++) {
            var end = cursor.readI32();
            var length = cursor.readIndex();
            if (length != dataSize(format, u, v)
                    || cursor.position + length != end
                    || length > cursor.end - cursor.position) {
                throw new IOException("Mip level " + level + " of its density map does not match its size");
            }
            var offset = cursor.position;
            cursor.skip(length);
            if (cursor.readI32() != u || cursor.readI32() != v) {
                throw new IOException("Mip level " + level + " of its density map has another size");
            }
            cursor.readU8();
            cursor.readU8();
            mips.add(new Mip(u, v, offset, length));
            u = Math.max(1, u >> 1);
            v = Math.max(1, v >> 1);
        }
        int[] palette = null;
        if (format == P8) {
            palette = TerrainAlphaTexture.readPaletteRed(
                    source, TerrainPackageSnapshot.exportReference(source, found, "Palette"), "density map");
        }
        return new TerrainDensityTexture(source.getObjectPath(found.index()), found.index(), format, mips, palette);
    }

    public String getObjectPath() {
        return objectPath;
    }

    public String getPackageName() {
        return objectPath.substring(0, objectPath.indexOf('.'));
    }

    public int getWidth() {
        return mips.getFirst().width();
    }

    public int getHeight() {
        return mips.getFirst().height();
    }

    public int getMipCount() {
        return mips.size();
    }

    public String getFormatName() {
        return switch (format) {
            case DXT1 -> "DXT1";
            case P8 -> "P8";
            default -> "RGBA8";
        };
    }

    int getExportIndex() {
        return exportIndex;
    }

    List<Mip> getMips() {
        return mips;
    }

    byte[] read(byte[] body) {
        var first = mips.getFirst();
        var width = first.width();
        var height = first.height();
        var values = new byte[width * height];
        switch (format) {
            case DXT1 -> {
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        values[y * width + x] = (byte) NativeTerrainLayerPasses.dxt1(body, first.offset(), width, x, y);
                    }
                }
            }
            case P8 -> {
                for (var i = 0; i < values.length; i++) {
                    values[i] = (byte) paletteRed[body[first.offset() + i] & 255];
                }
            }
            default -> {
                for (var i = 0; i < values.length; i++) {
                    values[i] = body[first.offset() + i * 4 + 3];
                }
            }
        }
        return values;
    }

    void write(byte[] body, byte[] weights, int x0, int y0, int x1, int y1) {
        var width = getWidth();
        var height = getHeight();
        if (weights.length != width * height || x0 < 0 || y0 < 0 || x1 >= width || y1 >= height || x0 > x1 || y0 > y1) {
            throw new IllegalArgumentException("Density rectangle lies outside the texture");
        }
        var saved = read(body);
        var changed = false;
        for (var y = y0; y <= y1 && !changed; y++) {
            for (var x = x0; x <= x1; x++) {
                if (weights[y * width + x] != saved[y * width + x]) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return;
        }
        writeFirst(body, weights, saved, x0, y0, x1, y1);
        for (var level = 1; level < mips.size(); level++) {
            writeLower(body, weights, level, x0, y0, x1, y1);
        }
    }

    @Override
    public String toString() {
        return objectPath + " (" + getFormatName() + ' ' + getWidth() + 'x' + getHeight() + ", " + mips.size()
                + (mips.size() == 1 ? " mip)" : " mips)");
    }

    private void writeFirst(byte[] body, byte[] weights, byte[] saved, int x0, int y0, int x1, int y1) {
        var first = mips.getFirst();
        var width = first.width();
        switch (format) {
            case DXT1 -> {
                var encoder = new TerrainDensityDxt1();
                var wanted = new int[16];
                for (var by = y0 / 4; by <= y1 / 4; by++) {
                    for (var bx = x0 / 4; bx <= x1 / 4; bx++) {
                        var differs = false;
                        for (var i = 0; i < 16; i++) {
                            var at = (by * 4 + i / 4) * width + bx * 4 + i % 4;
                            wanted[i] = weights[at] & 255;
                            differs |= weights[at] != saved[at];
                        }
                        if (differs) {
                            encoder.encode(wanted, body, first.offset() + (by * (width / 4) + bx) * 8);
                        }
                    }
                }
            }
            case P8 -> {
                var nearest = TerrainAlphaTexture.nearestPalette(paletteRed);
                for (var y = y0; y <= y1; y++) {
                    for (var x = x0; x <= x1; x++) {
                        var at = y * width + x;
                        if (weights[at] != saved[at]) {
                            body[first.offset() + at] = (byte) nearest[weights[at] & 255];
                        }
                    }
                }
            }
            default -> {
                for (var y = y0; y <= y1; y++) {
                    for (var x = x0; x <= x1; x++) {
                        var at = y * width + x;
                        if (weights[at] != saved[at]) {
                            fill(body, first.offset() + at * 4, weights[at]);
                        }
                    }
                }
            }
        }
    }

    private void writeLower(byte[] body, byte[] weights, int level, int x0, int y0, int x1, int y1) {
        var mip = mips.get(level);
        var stepX = getWidth() / mip.width();
        var stepY = getHeight() / mip.height();
        var left = x0 / stepX;
        var top = y0 / stepY;
        var right = x1 / stepX;
        var bottom = y1 / stepY;
        switch (format) {
            case DXT1 -> {
                var block = new int[64];
                var previous = new byte[8];
                var blocksX = Math.max(1, (mip.width() + 3) / 4);
                for (var by = top / 4; by <= bottom / 4; by++) {
                    for (var bx = left / 4; bx <= right / 4; bx++) {
                        for (var i = 0; i < 16; i++) {
                            var x = Math.min(mip.width() - 1, bx * 4 + i % 4);
                            var y = Math.min(mip.height() - 1, by * 4 + i / 4);
                            var value = mean(weights, x * stepX, y * stepY, stepX, stepY);
                            block[i * 4] = value;
                            block[i * 4 + 1] = value;
                            block[i * 4 + 2] = value;
                            block[i * 4 + 3] = 255;
                        }
                        var offset = mip.offset() + (by * blocksX + bx) * 8;
                        System.arraycopy(body, offset, previous, 0, 8);
                        BspDxt1.encode(block, body, offset, previous, 0);
                    }
                }
            }
            case P8 -> {
                var nearest = TerrainAlphaTexture.nearestPalette(paletteRed);
                for (var y = top; y <= bottom; y++) {
                    for (var x = left; x <= right; x++) {
                        body[mip.offset() + y * mip.width() + x] =
                                (byte) nearest[mean(weights, x * stepX, y * stepY, stepX, stepY)];
                    }
                }
            }
            default -> {
                for (var y = top; y <= bottom; y++) {
                    for (var x = left; x <= right; x++) {
                        fill(body, mip.offset() + (y * mip.width() + x) * 4, (byte)
                                mean(weights, x * stepX, y * stepY, stepX, stepY));
                    }
                }
            }
        }
    }

    private int mean(byte[] weights, int left, int top, int boxWidth, int boxHeight) {
        var width = getWidth();
        long sum = 0;
        for (var y = top; y < top + boxHeight; y++) {
            for (var x = left; x < left + boxWidth; x++) {
                sum += weights[y * width + x] & 255;
            }
        }
        var count = (long) boxWidth * boxHeight;
        return (int) ((sum + count / 2) / count);
    }

    private static void fill(byte[] body, int at, byte value) {
        body[at] = value;
        body[at + 1] = value;
        body[at + 2] = value;
        body[at + 3] = value;
    }

    private static int dataSize(int format, int width, int height) {
        return switch (format) {
            case DXT1 -> Math.max(1, (width + 3) / 4) * Math.max(1, (height + 3) / 4) * 8;
            case P8 -> width * height;
            default -> width * height * 4;
        };
    }
}
