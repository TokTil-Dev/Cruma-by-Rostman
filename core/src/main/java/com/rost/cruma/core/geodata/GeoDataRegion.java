package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Storage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public final class GeoDataRegion {
    public static final int CELL_SIZE = 16;
    public static final int CELLS_PER_AXIS = 2048;
    public static final int EAST = 1;
    public static final int WEST = 2;
    public static final int SOUTH = 4;
    public static final int NORTH = 8;

    private final int tileX;
    private final int tileY;
    private final byte[] data;
    private final int[] sectorOffsets;
    private final GeoFormat format;
    private final String fileFingerprint;
    private volatile String fingerprint;

    GeoDataRegion(int tileX, int tileY, byte[] data, int[] sectorOffsets) {
        this(tileX, tileY, data, sectorOffsets, GeoFormat.L2OFF, null);
    }

    GeoDataRegion(int tileX, int tileY, byte[] data, int[] sectorOffsets, GeoFormat format, String fileFingerprint) {
        this.tileX = tileX;
        this.tileY = tileY;
        this.data = data;
        this.sectorOffsets = sectorOffsets;
        this.format = format;
        this.fileFingerprint = fileFingerprint;
    }

    @FunctionalInterface
    public interface LayerConsumer {
        void accept(int worldX, int worldY, int height, int nswe);
    }

    public int getTileX() {
        return tileX;
    }

    public int getTileY() {
        return tileY;
    }

    public int getWorldMinX() {
        return (tileX - 20) * 32768;
    }

    public int getWorldMinY() {
        return (tileY - 18) * 32768;
    }

    public GeoFormat getFormat() {
        return format;
    }

    public String getFileFingerprint() {
        return fileFingerprint == null ? getFingerprint() : fileFingerprint;
    }

    public long getEstimatedBytes() {
        return data.length + (long) sectorOffsets.length * Integer.BYTES;
    }

    public String getFingerprint() {
        var value = fingerprint;
        if (value == null) {
            try {
                value = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                fingerprint = value;
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return value;
    }

    public int getLayerCount(int cellX, int cellY) {
        var sector = sectorOffset(cellX, cellY);
        var count = shortAt(sector);
        return count == 0 || count == 64 ? 1 : shortAt(complexCellOffset(sector, cellX, cellY));
    }

    public Storage getStorage(int cellX, int cellY) {
        var count = shortAt(sectorOffset(cellX, cellY));
        return count == 0 ? Storage.FLAT : count == 64 ? Storage.SINGLE : Storage.MULTI;
    }

    byte[] getEncodedBytes() {
        return data.clone();
    }

    byte[] getHeaderBytes() {
        return Arrays.copyOf(data, 18);
    }

    int getGenerationLayerId(int cellX, int cellY, int layer) {
        var sector = sectorOffset(cellX, cellY);
        var count = shortAt(sector);
        if (count == 0) {
            Objects.checkIndex(layer, 1);
            return (sector + 2) | Integer.MIN_VALUE;
        }
        if (count == 64) {
            Objects.checkIndex(layer, 1);
            return sector + 2 + ((cellX & 7) * 8 + (cellY & 7)) * 2;
        }
        var cell = complexCellOffset(sector, cellX, cellY);
        Objects.checkIndex(layer, shortAt(cell));
        return cell + 2 + layer * 2;
    }

    int getGenerationPacked(int layerId) {
        if (layerId < 0) {
            return (short) (((shortAt(layerId & Integer.MAX_VALUE) << 1) & 0xfff0) | 15);
        }
        return shortAt(layerId);
    }

    int getGenerationSectorOffset(int sectorIndex) {
        return sectorOffsets[sectorIndex];
    }

    int getGenerationWord(int offset) {
        return shortAt(offset) & 0xffff;
    }

    byte[] getSectorBytes(int sectorX, int sectorY) {
        Objects.checkIndex(sectorX, 256);
        Objects.checkIndex(sectorY, 256);
        var index = sectorX * 256 + sectorY;
        var end = index + 1 < sectorOffsets.length ? sectorOffsets[index + 1] : data.length;
        return Arrays.copyOfRange(data, sectorOffsets[index], end);
    }

    public int getHeight(int cellX, int cellY, int layer) {
        return decodeHeight(packedLayer(cellX, cellY, layer));
    }

    public int getNswe(int cellX, int cellY, int layer) {
        return packedLayer(cellX, cellY, layer) & 15;
    }

    public void forEachLayer(int minCellX, int minCellY, int maxCellX, int maxCellY, LayerConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (maxCellX < minCellX || maxCellY < minCellY) {
            throw new IllegalArgumentException("Reversed geodata cell bounds");
        }
        var minX = Math.max(0, minCellX);
        var minY = Math.max(0, minCellY);
        var maxX = Math.min(CELLS_PER_AXIS, maxCellX);
        var maxY = Math.min(CELLS_PER_AXIS, maxCellY);
        if (minX >= maxX || minY >= maxY) {
            return;
        }
        for (var sectorX = minX / 8; sectorX <= (maxX - 1) / 8; sectorX++) {
            for (var sectorY = minY / 8; sectorY <= (maxY - 1) / 8; sectorY++) {
                visitSector(sectorX, sectorY, minX, minY, maxX, maxY, consumer);
            }
        }
    }

    private void visitSector(int sectorX, int sectorY, int minX, int minY, int maxX, int maxY, LayerConsumer consumer) {
        var sector = sectorOffsets[sectorX * 256 + sectorY];
        var count = shortAt(sector);
        var cursor = sector + 2;
        for (var cell = 0; cell < 64; cell++) {
            var cellX = sectorX * 8 + cell / 8;
            var cellY = sectorY * 8 + cell % 8;
            var layers = 1;
            if (count != 0 && count != 64) {
                layers = shortAt(cursor);
                cursor += 2;
            }
            if (cellX >= minX && cellX < maxX && cellY >= minY && cellY < maxY) {
                var worldX = getWorldMinX() + cellX * CELL_SIZE;
                var worldY = getWorldMinY() + cellY * CELL_SIZE;
                for (var layer = 0; layer < layers; layer++) {
                    var packed = count == 0 ? flatPacked(sector) : shortAt(cursor + layer * 2);
                    consumer.accept(worldX, worldY, decodeHeight(packed), packed & 15);
                }
            }
            if (count != 0) {
                cursor += layers * 2;
            }
        }
    }

    private int packedLayer(int cellX, int cellY, int layer) {
        var sector = sectorOffset(cellX, cellY);
        var count = shortAt(sector);
        var cell = (cellX & 7) * 8 + (cellY & 7);
        if (count == 0 || count == 64) {
            Objects.checkIndex(layer, 1);
            return count == 0 ? flatPacked(sector) : shortAt(sector + 2 + cell * 2);
        }
        var offset = complexCellOffset(sector, cellX, cellY);
        Objects.checkIndex(layer, shortAt(offset));
        return shortAt(offset + 2 + layer * 2);
    }

    private int sectorOffset(int cellX, int cellY) {
        Objects.checkIndex(cellX, CELLS_PER_AXIS);
        Objects.checkIndex(cellY, CELLS_PER_AXIS);
        return sectorOffsets[(cellX / 8) * 256 + cellY / 8];
    }

    private int complexCellOffset(int sector, int cellX, int cellY) {
        var cell = (cellX & 7) * 8 + (cellY & 7);
        var offset = sector + 2;
        for (var index = 0; index < cell; index++) {
            offset += 2 + shortAt(offset) * 2;
        }
        return offset;
    }

    private int flatPacked(int sector) {
        return (short) (((shortAt(sector + 2) << 1) & 0xfff0) | 15);
    }

    private int shortAt(int offset) {
        return (short) ((data[offset] & 255) | ((data[offset + 1] & 255) << 8));
    }

    private static int decodeHeight(int packed) {
        return (short) (packed & 0xfff0) / 2;
    }
}
