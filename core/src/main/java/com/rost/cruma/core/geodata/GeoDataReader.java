package com.rost.cruma.core.geodata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GeoDataReader {
    public static final long MAX_FILE_BYTES = 128L * 1024 * 1024;

    private static final int HEADER_BYTES = 18;
    private static final int SECTOR_COUNT = 256 * 256;
    private static final int C4_LAYOUT_TAG = 0xe3aedc5c;

    private GeoDataReader() {}

    public static GeoDataRegion read(Path path) throws IOException {
        var named = GeoFormat.parse(String.valueOf(path.getFileName()));
        var format = named == null ? GeoFormat.L2OFF : named.format();
        var length = Files.size(path);
        var smallest = format == GeoFormat.L2OFF ? HEADER_BYTES + SECTOR_COUNT * 2L : SECTOR_COUNT * 3L;
        if (length < smallest || length > MAX_FILE_BYTES) {
            throw invalidFile(path, "unsupported file size " + length);
        }
        byte[] data;
        try (var input = Files.newInputStream(path)) {
            data = input.readNBytes((int) length + 1);
        }
        if (data.length != length) {
            throw invalidFile(path, "file size changed while reading");
        }
        if (format == GeoFormat.L2OFF) {
            return parse(path, data, format, null);
        }
        byte[] converted;
        try {
            converted = format.toL2off(data, named.tileX(), named.tileY());
        } catch (IOException failure) {
            throw invalidFile(path, failure.getMessage());
        }
        return parse(path, converted, format, GeoDataWriter.fileFingerprint(data));
    }

    public static GeoDataRegion read(byte[] bytes) throws IOException {
        if (bytes.length < HEADER_BYTES + SECTOR_COUNT * 2L || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Unsupported geodata byte length " + bytes.length);
        }
        return parse(Path.of("memory_conv.dat"), bytes.clone(), GeoFormat.L2OFF, null);
    }

    private static GeoDataRegion parse(Path path, byte[] data, GeoFormat format, String fileFingerprint)
            throws IOException {
        var input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var tileX = Byte.toUnsignedInt(input.get());
        var tileY = Byte.toUnsignedInt(input.get());
        var layout = input.getInt();
        var declaredLayers = input.getInt();
        var declaredSingleSectors = input.getInt();
        var declaredFlatSectors = input.getInt();
        if (tileX < 10
                || tileX > 29
                || tileY < 10
                || tileY > 26
                || (layout != (GeoDataRegion.CELL_SIZE << 16 | 128) && layout != C4_LAYOUT_TAG)
                || declaredLayers < 0
                || declaredLayers > input.remaining() / 2
                || declaredSingleSectors < 0
                || declaredSingleSectors > SECTOR_COUNT
                || declaredFlatSectors < 0
                || declaredFlatSectors > declaredSingleSectors) {
            throw invalidFile(path, "invalid L2OFF header");
        }
        var offsets = new int[SECTOR_COUNT];
        var actualLayers = 0;
        var actualSingleSectors = 0;
        var actualFlatSectors = 0;
        for (var sector = 0; sector < SECTOR_COUNT; sector++) {
            offsets[sector] = input.position();
            require(path, input, 2, sector);
            var count = input.getShort();
            if (count < 0) {
                throw invalidFile(path, "negative layer total at sector " + sector);
            }
            if (count == 0) {
                skip(path, input, 4, sector);
                actualFlatSectors++;
                actualSingleSectors++;
            } else if (count == 64) {
                skip(path, input, 128, sector);
                actualLayers += 64;
                actualSingleSectors++;
            } else {
                var sectorLayers = 0;
                for (var cell = 0; cell < 64; cell++) {
                    require(path, input, 2, sector);
                    var layers = input.getShort();
                    if (layers < 0 || layers > count - sectorLayers) {
                        throw invalidFile(path, "invalid layer count at sector " + sector + ", cell " + cell);
                    }
                    skip(path, input, layers * 2, sector);
                    sectorLayers += layers;
                }
                if (sectorLayers != count) {
                    throw invalidFile(path, "layer total mismatch at sector " + sector);
                }
                actualLayers += sectorLayers;
            }
            if (actualLayers > declaredLayers) {
                throw invalidFile(path, "layer total exceeds the header at sector " + sector);
            }
        }
        if (input.hasRemaining()
                || actualLayers != declaredLayers
                || actualSingleSectors != declaredSingleSectors
                || actualFlatSectors != declaredFlatSectors) {
            throw invalidFile(path, "sector totals or file length do not match the header");
        }
        return new GeoDataRegion(tileX, tileY, data, offsets, format, fileFingerprint);
    }

    private static void skip(Path path, ByteBuffer input, int bytes, int sector) throws IOException {
        require(path, input, bytes, sector);
        input.position(input.position() + bytes);
    }

    private static void require(Path path, ByteBuffer input, int bytes, int sector) throws IOException {
        if (input.remaining() < bytes) {
            throw invalidFile(path, "truncated sector " + sector + " at byte " + input.position());
        }
    }

    private static IOException invalidFile(Path path, String detail) {
        return new IOException("Cannot read geodata " + path.getFileName() + ": " + detail);
    }
}
