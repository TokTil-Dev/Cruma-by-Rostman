package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;

public final class TerrainAlphaTexture {
    static final int P8 = 0;
    static final int DXT1 = 3;
    static final int RGBA8 = 5;

    private final String objectPath;
    private final int exportIndex;
    private final int format;
    private final int width;
    private final int height;
    private final int dataOffset;
    private final int dataLength;
    private final int[] paletteRed;

    private TerrainAlphaTexture(
            String objectPath,
            int exportIndex,
            int format,
            int width,
            int height,
            int dataOffset,
            int dataLength,
            int[] paletteRed) {
        this.objectPath = objectPath;
        this.exportIndex = exportIndex;
        this.format = format;
        this.width = width;
        this.height = height;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
        this.paletteRed = paletteRed;
    }

    static TerrainAlphaTexture find(PackageBytes source, String objectPath) throws IOException {
        return find(source, objectPath, false);
    }

    static TerrainAlphaTexture findFirstMip(PackageBytes source, String objectPath) throws IOException {
        return find(source, objectPath, true);
    }

    static int[] readPaletteRed(PackageBytes source, int reference, String what) throws IOException {
        var red = new int[256];
        if (reference == 0) {
            for (var i = 0; i < red.length; i++) {
                red[i] = i;
            }
            return red;
        }
        if (reference < 0) {
            throw new IOException("Its " + what + " uses a palette from another package");
        }
        var entry = source.exports.get(reference - 1);
        var cursor = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        NativeLevelEditor.skipProperties(source, cursor, entry.flags());
        if (cursor.readIndex() != 256) {
            throw new IOException("Its " + what + " palette does not have 256 colours");
        }
        for (var i = 0; i < 256; i++) {
            var first = cursor.readU8();
            cursor.readU8();
            var third = cursor.readU8();
            cursor.readU8();
            red[i] = source.fileVersion > 100 ? third : first;
        }
        return red;
    }

    static int[] nearestPalette(int[] paletteRed) {
        var nearest = new int[256];
        for (var value = 0; value < 256; value++) {
            var best = 0;
            for (var i = 1; i < 256; i++) {
                if (Math.abs(paletteRed[i] - value) < Math.abs(paletteRed[best] - value)) {
                    best = i;
                }
            }
            nearest[value] = best;
        }
        return nearest;
    }

    public String getObjectPath() {
        return objectPath;
    }

    public String getPackageName() {
        return objectPath.substring(0, objectPath.indexOf('.'));
    }

    int getExportIndex() {
        return exportIndex;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    int getDataOffset() {
        return dataOffset;
    }

    int getDataLength() {
        return dataLength;
    }

    public String getFormatName() {
        return switch (format) {
            case DXT1 -> "DXT1";
            case P8 -> "P8";
            default -> "RGBA8";
        };
    }

    byte[] read(byte[] body) {
        var weights = new byte[width * height];
        switch (format) {
            case DXT1 -> {
                var block = new int[64];
                for (var by = 0; by < height / 4; by++) {
                    for (var bx = 0; bx < width / 4; bx++) {
                        BspDxt1.decode(body, dataOffset + (by * (width / 4) + bx) * 8, block);
                        for (var i = 0; i < 16; i++) {
                            weights[(by * 4 + i / 4) * width + bx * 4 + i % 4] = (byte) block[i * 4];
                        }
                    }
                }
            }
            case P8 -> {
                for (var i = 0; i < weights.length; i++) {
                    weights[i] = (byte) paletteRed[body[dataOffset + i] & 255];
                }
            }
            default -> {
                for (var i = 0; i < weights.length; i++) {
                    weights[i] = body[dataOffset + i * 4 + 2];
                }
            }
        }
        return weights;
    }

    byte[] readAsGame(byte[] body) {
        var weights = new byte[width * height];
        switch (format) {
            case DXT1 -> {
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        weights[y * width + x] = (byte) NativeTerrainLayerPasses.dxt1(body, dataOffset, width, x, y);
                    }
                }
            }
            case P8 -> {
                for (var i = 0; i < weights.length; i++) {
                    weights[i] = (byte) paletteRed[body[dataOffset + i] & 255];
                }
            }
            default -> {
                for (var i = 0; i < weights.length; i++) {
                    weights[i] = body[dataOffset + i * 4 + 3];
                }
            }
        }
        return weights;
    }

    void write(byte[] body, byte[] weights, int x0, int y0, int x1, int y1) {
        if (weights.length != width * height || x0 < 0 || y0 < 0 || x1 >= width || y1 >= height || x0 > x1 || y0 > y1) {
            throw new IllegalArgumentException("Weight rectangle lies outside the texture");
        }
        switch (format) {
            case DXT1 -> {
                var block = new int[64];
                var saved = new byte[8];
                for (var by = y0 / 4; by <= y1 / 4; by++) {
                    for (var bx = x0 / 4; bx <= x1 / 4; bx++) {
                        for (var i = 0; i < 16; i++) {
                            var value = weights[(by * 4 + i / 4) * width + bx * 4 + i % 4] & 255;
                            block[i * 4] = value;
                            block[i * 4 + 1] = value;
                            block[i * 4 + 2] = value;
                            block[i * 4 + 3] = 255;
                        }
                        var offset = dataOffset + (by * (width / 4) + bx) * 8;
                        System.arraycopy(body, offset, saved, 0, 8);
                        BspDxt1.encode(block, body, offset, saved, 0);
                    }
                }
            }
            case P8 -> {
                var nearest = nearestPalette(paletteRed);
                for (var y = y0; y <= y1; y++) {
                    for (var x = x0; x <= x1; x++) {
                        body[dataOffset + y * width + x] = (byte) nearest[weights[y * width + x] & 255];
                    }
                }
            }
            default -> {
                for (var y = y0; y <= y1; y++) {
                    for (var x = x0; x <= x1; x++) {
                        var at = dataOffset + (y * width + x) * 4;
                        var value = weights[y * width + x];
                        body[at] = value;
                        body[at + 1] = value;
                        body[at + 2] = value;
                        body[at + 3] = value;
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return objectPath + " (" + getFormatName() + ' ' + width + 'x' + height + ')';
    }

    private static TerrainAlphaTexture find(PackageBytes source, String objectPath, boolean anyMips)
            throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (source.getObjectPath(entry.index()).equalsIgnoreCase(objectPath)) {
                if (found != null) {
                    throw new IOException("Its weight texture name is ambiguous");
                }
                found = entry;
            }
        }
        if (found == null) {
            throw new IOException("Its weight texture " + objectPath + " is missing from " + source.packageName);
        }
        if ((found.flags() & 0x02000000) != 0
                || found.superIndex() != 0
                || !source.getObjectPath(found.classIndex()).equalsIgnoreCase("Engine.Texture")) {
            throw new IOException("Its weight texture is not a plain Engine.Texture");
        }
        var format = TerrainPackageSnapshot.exportScalar(source, found, "Format", 1, P8);
        var width = TerrainPackageSnapshot.exportScalar(source, found, "USize", 2, -1);
        var height = TerrainPackageSnapshot.exportScalar(source, found, "VSize", 2, -1);
        if (format != DXT1 && format != P8 && format != RGBA8) {
            throw new IOException("Its weight texture uses format " + format + ", which Cruma cannot write");
        }
        if (width < 4
                || height < 4
                || width > 4096
                || height > 4096
                || Integer.bitCount(width) != 1
                || Integer.bitCount(height) != 1) {
            throw new IOException("Its weight texture has an unusual size");
        }
        var mips = TerrainPackageSnapshot.textureMips(source, found);
        var count = mips.readIndex();
        if (count < 1) {
            throw new IOException("Its weight texture has no mip level");
        }
        if (count != 1 && !anyMips) {
            throw new IOException("Its weight texture has " + count + " mip levels; Cruma writes only one");
        }
        var end = mips.readI32();
        var length = mips.readIndex();
        var expected = switch (format) {
            case DXT1 -> width / 4 * (height / 4) * 8;
            case P8 -> width * height;
            default -> width * height * 4;
        };
        if (length != expected || mips.position + length != end || length > mips.end - mips.position) {
            throw new IOException("Its weight texture data does not match its size");
        }
        var offset = mips.position;
        mips.skip(length);
        if (mips.readI32() != width || mips.readI32() != height) {
            throw new IOException("Its weight texture mip size differs");
        }
        int[] palette = null;
        if (format == P8) {
            palette = readPaletteRed(
                    source, TerrainPackageSnapshot.exportReference(source, found, "Palette"), "weight texture");
        }
        return new TerrainAlphaTexture(
                source.getObjectPath(found.index()), found.index(), format, width, height, offset, length, palette);
    }
}
