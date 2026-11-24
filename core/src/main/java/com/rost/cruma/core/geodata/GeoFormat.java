package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Storage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public enum GeoFormat {
    L2OFF("_conv.dat"),
    L2J(".l2j");
    private static final Pattern NAME = Pattern.compile("(\\d{2})_(\\d{2})(_conv\\.dat|\\.l2j)");
    private static final int SECTORS = 256;
    private static final int HEADER_BYTES = 18;

    private static final int MAX_LAYERS = 125;

    private static final int EMPTY_HEIGHT = -16384;

    private final String suffix;

    GeoFormat(String suffix) {
        this.suffix = suffix;
    }

    public record Named(GeoFormat format, int tileX, int tileY) {}

    @Nullable
    public static Named parse(String fileName) {
        var matcher = NAME.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        var format = matcher.group(3).equals(L2J.suffix) ? L2J : L2OFF;
        return new Named(format, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    @Nullable
    public static Path find(Path folder, GeoTile tile) {
        for (var format : values()) {
            var file = folder.resolve(format.getFileName(tile));
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    public String getFileName(GeoTile tile) {
        return tile.x() + "_" + tile.y() + suffix;
    }

    public byte[] encode(GeoDataRegion region) throws IOException {
        if (this == L2OFF) {
            return region.getEncodedBytes();
        }
        var output = new ByteArrayOutputStream(4 << 20);
        for (var sx = 0; sx < SECTORS; sx++) {
            for (var sy = 0; sy < SECTORS; sy++) {
                var storage = region.getStorage(sx * 8, sy * 8);
                output.write(l2jCellType(storage));
                if (storage == Storage.FLAT) {
                    writeShort(output, region.getHeight(sx * 8, sy * 8, 0));
                    continue;
                }
                for (var cell = 0; cell < 64; cell++) {
                    var x = sx * 8 + cell / 8;
                    var y = sy * 8 + cell % 8;
                    if (storage == Storage.SINGLE) {
                        writeLayer(output, region.getHeight(x, y, 0), region.getNswe(x, y, 0));
                        continue;
                    }
                    writeCell(output, region, x, y);
                }
            }
        }
        return output.toByteArray();
    }

    byte[] toL2off(byte[] bytes, int tileX, int tileY) throws IOException {
        if (this == L2OFF) {
            return bytes;
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var output = new ByteArrayOutputStream(bytes.length + SECTORS * SECTORS * 4 + HEADER_BYTES);
        output.writeBytes(new byte[HEADER_BYTES]);
        var counts = new int[64];
        var words = new short[64 * MAX_LAYERS];
        var layers = 0;
        var singles = 0;
        var flats = 0;
        for (var sector = 0; sector < SECTORS * SECTORS; sector++) {
            require(input, 1, sector);
            var type = Byte.toUnsignedInt(input.get());
            if (type == l2jCellType(Storage.FLAT)) {
                require(input, 2, sector);
                var height = input.getShort();
                writeShort(output, 0);
                writeShort(output, height);
                writeShort(output, height);
                singles++;
                flats++;
            } else if (type == l2jCellType(Storage.SINGLE)) {
                writeShort(output, 64);
                for (var cell = 0; cell < 64; cell++) {
                    writeShort(output, readLayer(input, sector));
                }
                layers += 64;
                singles++;
            } else if (type == l2jCellType(Storage.MULTI)) {
                var total = 0;
                for (var cell = 0; cell < 64; cell++) {
                    require(input, 1, sector);
                    var count = Byte.toUnsignedInt(input.get());
                    if (count < 1 || count > MAX_LAYERS) {
                        throw new IOException(
                                "invalid layer count " + count + " at block " + sector + ", cell " + cell);
                    }
                    for (var layer = 0; layer < count; layer++) {
                        words[cell * MAX_LAYERS + layer] = readLayer(input, sector);
                    }
                    counts[cell] = count;
                    total += count;
                }
                writeShort(output, total);
                for (var cell = 0; cell < 64; cell++) {
                    if (total != 64) {
                        writeShort(output, counts[cell]);
                    }
                    for (var layer = 0; layer < counts[cell]; layer++) {
                        writeShort(output, words[cell * MAX_LAYERS + layer]);
                    }
                }
                layers += total;
                if (total == 64) {
                    singles++;
                }
            } else {
                throw new IOException("unknown block type " + type + " at block " + sector);
            }
        }
        if (input.hasRemaining()) {
            throw new IOException(input.remaining() + " bytes after the last block");
        }
        var result = output.toByteArray();
        ByteBuffer.wrap(result)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) tileX)
                .put((byte) tileY)
                .putInt(GeoDataRegion.CELL_SIZE << 16 | 128)
                .putInt(layers)
                .putInt(singles)
                .putInt(flats);
        return result;
    }

    private void writeCell(ByteArrayOutputStream output, GeoDataRegion region, int x, int y) throws IOException {
        var count = region.getLayerCount(x, y);
        if (count > MAX_LAYERS) {
            throw new IOException(
                    name() + " stores at most " + MAX_LAYERS + " layers in a cell, " + x + ", " + y + " has " + count);
        }
        if (count == 0) {
            output.write(1);
            writeLayer(output, EMPTY_HEIGHT, 0);
            return;
        }
        output.write(count);
        for (var layer = 0; layer < count; layer++) {
            writeLayer(output, region.getHeight(x, y, layer), region.getNswe(x, y, layer));
        }
    }

    private static int l2jCellType(Storage storage) {
        return storage.ordinal();
    }

    private static void writeLayer(ByteArrayOutputStream output, int height, int nswe) {
        writeShort(output, (height * 2) & 0xfff0 | nswe);
    }

    private static short readLayer(ByteBuffer input, int sector) throws IOException {
        require(input, 2, sector);
        return input.getShort();
    }

    private static void require(ByteBuffer input, int bytes, int sector) throws IOException {
        if (input.remaining() < bytes) {
            throw new IOException("truncated block " + sector + " at byte " + input.position());
        }
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 255);
        output.write(value >> 8 & 255);
    }
}
