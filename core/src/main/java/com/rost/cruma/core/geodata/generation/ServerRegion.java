package com.rost.cruma.core.geodata.generation;

import com.rost.cruma.core.geodata.GeoDataAccess.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ServerRegion {
    public static final int SECTOR = 8;
    public static final int SECTORS = BuiltRegion.CELLS / SECTOR;

    public static final int WRITTEN_CELLS = 2032;

    public static final int FLAT_SPREAD = 32;

    static final int EMPTY_HEIGHT = -16384;

    private static final int LAYOUT = 16 << 16 | 128;

    private static final int HEADER_BYTES = 18;

    private final int tileX;
    private final int tileY;
    private final int[][] layers;
    private final Storage[] storage = new Storage[SECTORS * SECTORS];
    private final int[] flatLow = new int[SECTORS * SECTORS];

    private ServerRegion(int tileX, int tileY, int[][] layers) {
        this.tileX = tileX;
        this.tileY = tileY;
        this.layers = layers;
    }

    public static ServerRegion convert(BuiltRegion region) {
        var cells = BuiltRegion.CELLS;
        var limit = Math.min(region.getCellLimit(), WRITTEN_CELLS);
        var layers = new int[cells * cells][];
        for (var x = 0; x < cells; x++) {
            for (var y = 0; y < cells; y++) {
                var sourceX = Math.min(x, limit - 1);
                var sourceY = Math.min(y, limit - 1);
                var count = region.getLayerCount(sourceX, sourceY);
                if (sourceX != x || sourceY != y) {
                    count = Math.min(count, 1);
                }
                var values = new int[count * 2];
                var i = 0;
                for (var layer = region.getFirstLayer(sourceX, sourceY);
                        layer >= 0 && i < values.length;
                        layer = region.nextLayer(layer)) {
                    values[i++] = region.getHeight(layer);
                    values[i++] = region.getNswe(layer);
                }
                layers[x * cells + y] = values;
            }
        }
        var converted = new ServerRegion(region.getTileX(), region.getTileY(), layers);
        for (var sx = 0; sx < SECTORS; sx++) {
            for (var sy = 0; sy < SECTORS; sy++) {
                converted.classifySector(sx, sy);
            }
        }
        return converted;
    }

    public int getTileX() {
        return tileX;
    }

    public int getTileY() {
        return tileY;
    }

    public Storage getStorage(int cellX, int cellY) {
        return storage[(cellX / SECTOR) * SECTORS + cellY / SECTOR];
    }

    public int getLayerCount(int cellX, int cellY) {
        return layers[cellX * BuiltRegion.CELLS + cellY].length / 2;
    }

    public int getHeight(int cellX, int cellY, int layer) {
        return layers[cellX * BuiltRegion.CELLS + cellY][layer * 2];
    }

    public int getNswe(int cellX, int cellY, int layer) {
        return layers[cellX * BuiltRegion.CELLS + cellY][layer * 2 + 1];
    }

    public byte[] encode() {
        var output = new ByteArrayOutputStream(4 << 20);
        output.writeBytes(new byte[HEADER_BYTES]);
        var totalLayers = 0;
        var singles = 0;
        var flats = 0;
        for (var sx = 0; sx < SECTORS; sx++) {
            for (var sy = 0; sy < SECTORS; sy++) {
                var index = sx * SECTORS + sy;
                switch (storage[index]) {
                    case FLAT -> {
                        writeShort(output, 0);
                        writeShort(output, getHeight(sx * SECTOR, sy * SECTOR, 0));
                        writeShort(output, flatLow[index]);
                        singles++;
                        flats++;
                    }
                    case SINGLE -> {
                        writeShort(output, 64);
                        for (var cell = 0; cell < 64; cell++) {
                            var x = sx * SECTOR + cell / SECTOR;
                            var y = sy * SECTOR + cell % SECTOR;
                            writeShort(output, packedLayer(x, y, 0));
                        }
                        totalLayers += 64;
                        singles++;
                    }
                    case MULTI -> {
                        var total = 0;
                        for (var cell = 0; cell < 64; cell++) {
                            total += getLayerCount(sx * SECTOR + cell / SECTOR, sy * SECTOR + cell % SECTOR);
                        }
                        if (total == 0 || total == 64 || total > Short.MAX_VALUE) {
                            throw new IllegalStateException("Sector "
                                    + sx
                                    + ","
                                    + sy
                                    + " of region "
                                    + tileX
                                    + "_"
                                    + tileY
                                    + " has "
                                    + total
                                    + " layers, which the server format cannot store as several layers");
                        }
                        writeShort(output, total);
                        for (var cell = 0; cell < 64; cell++) {
                            var x = sx * SECTOR + cell / SECTOR;
                            var y = sy * SECTOR + cell % SECTOR;
                            var count = getLayerCount(x, y);
                            writeShort(output, count);
                            for (var layer = 0; layer < count; layer++) {
                                writeShort(output, packedLayer(x, y, layer));
                            }
                        }
                        totalLayers += total;
                    }
                }
            }
        }
        var bytes = output.toByteArray();
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) tileX)
                .put((byte) tileY)
                .putInt(LAYOUT)
                .putInt(totalLayers)
                .putInt(singles)
                .putInt(flats);
        return bytes;
    }

    private int packedLayer(int x, int y, int layer) {
        return getHeight(x, y, layer) << 1 | getNswe(x, y, layer);
    }

    private void classifySector(int sectorX, int sectorY) {
        var single = true;
        var open = true;
        var low = Integer.MAX_VALUE;
        var high = Integer.MIN_VALUE;
        for (var x = sectorX * SECTOR; x < sectorX * SECTOR + SECTOR; x++) {
            for (var y = sectorY * SECTOR; y < sectorY * SECTOR + SECTOR; y++) {
                var cell = layers[x * BuiltRegion.CELLS + y];
                if (cell.length != 2) {
                    single = false;
                    continue;
                }
                open &= cell[1] == 15;
                low = Math.min(low, cell[0]);
                high = Math.max(high, cell[0]);
            }
        }
        var index = sectorX * SECTORS + sectorY;
        var total = 0;
        var firstEmpty = -1;
        for (var x = sectorX * SECTOR; x < sectorX * SECTOR + SECTOR; x++) {
            for (var y = sectorY * SECTOR; y < sectorY * SECTOR + SECTOR; y++) {
                var count = layers[x * BuiltRegion.CELLS + y].length / 2;
                total += count;
                if (count == 0 && firstEmpty < 0) {
                    firstEmpty = x * BuiltRegion.CELLS + y;
                }
            }
        }
        if (total == 0) {
            var bottom = new int[] {EMPTY_HEIGHT, 15};
            for (var x = sectorX * SECTOR; x < sectorX * SECTOR + SECTOR; x++) {
                for (var y = sectorY * SECTOR; y < sectorY * SECTOR + SECTOR; y++) {
                    layers[x * BuiltRegion.CELLS + y] = bottom;
                }
            }
            storage[index] = Storage.FLAT;
            flatLow[index] = EMPTY_HEIGHT;
            return;
        }
        if (!single && total == SECTOR * SECTOR) {
            layers[firstEmpty] = new int[] {EMPTY_HEIGHT, 0};
        }
        if (!single) {
            storage[index] = Storage.MULTI;
        } else if (open && high - low <= FLAT_SPREAD) {
            var flat = new int[] {high, 15};
            for (var x = sectorX * SECTOR; x < sectorX * SECTOR + SECTOR; x++) {
                for (var y = sectorY * SECTOR; y < sectorY * SECTOR + SECTOR; y++) {
                    layers[x * BuiltRegion.CELLS + y] = flat;
                }
            }
            storage[index] = Storage.FLAT;
            flatLow[index] = low;
        } else {
            storage[index] = Storage.SINGLE;
        }
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 255);
        output.write(value >>> 8 & 255);
    }
}
