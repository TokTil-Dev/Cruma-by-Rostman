package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Storage;
import com.rost.cruma.core.geodata.GeoPathNodeGenerator.Control;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;

final class GeoPathNodeRegionIndex {
    private static final int SECTORS = 256 * 256;
    private static final int SINGLE = 1 << 30;
    private final GeoDataRegion region;
    private final int[] sectors;
    private final int[] origins;
    private final char[] anchors;
    private final int anchorShift;
    private final int anchorsPerSector;
    private final int anchorMask;

    GeoPathNodeRegionIndex(GeoDataRegion region, Control control) throws IOException {
        this(region, control, 0);
    }

    GeoPathNodeRegionIndex(GeoDataRegion region, Control control, long extraBytesBudget) throws IOException {
        this.region = Objects.requireNonNull(region, "region");
        Objects.requireNonNull(control, "control");
        if (extraBytesBudget < 0) {
            throw new IllegalArgumentException("Negative path-node index budget");
        }
        var multiCount = 0;
        for (var sector = 0; sector < SECTORS; sector++) {
            reportProgress(control, sector);
            var count = region.getGenerationWord(region.getGenerationSectorOffset(sector));
            if (count != 0 && count != 64) {
                multiCount++;
            }
        }
        if (multiCount == 0) {
            sectors = null;
            origins = null;
            anchors = null;
            anchorShift = 3;
            anchorsPerSector = 8;
            anchorMask = 7;
            reportProgress(control, SECTORS * 2);
            return;
        }
        anchorShift = chooseAnchorShift(multiCount, extraBytesBudget);
        anchorsPerSector = 64 >> anchorShift;
        anchorMask = (1 << anchorShift) - 1;
        sectors = new int[SECTORS];
        origins = new int[multiCount];
        anchors = new char[multiCount * anchorsPerSector];
        var multi = 0;
        for (var sector = 0; sector < SECTORS; sector++) {
            reportProgress(control, SECTORS + sector);
            var origin = region.getGenerationSectorOffset(sector);
            var count = region.getGenerationWord(origin);
            origin += 2;
            if (count == 0) {
                sectors[sector] = origin | Integer.MIN_VALUE;
            } else if (count == 64) {
                sectors[sector] = origin | SINGLE;
            } else {
                sectors[sector] = multi;
                origins[multi] = origin;
                var cursor = origin;
                for (var anchor = 0; anchor < anchorsPerSector; anchor++) {
                    anchors[multi * anchorsPerSector + anchor] = (char) ((cursor - origin) / 2);
                    for (var cell = 0; cell <= anchorMask; cell++) {
                        cursor += 2 + region.getGenerationWord(cursor) * 2;
                    }
                }
                multi++;
            }
        }
        reportProgress(control, SECTORS * 2);
    }

    int getLayerId(int cellX, int cellY, int layer) {
        if (layer < 0) {
            return 0;
        }
        if (sectors == null) {
            return layer == 0 ? region.getGenerationLayerId(cellX, cellY, 0) : 0;
        }
        var descriptor = sectorDescriptor(cellX, cellY);
        if (descriptor < 0) {
            return layer == 0 ? descriptor : 0;
        }
        if ((descriptor & SINGLE) != 0) {
            return layer == 0 ? singleId(descriptor, cellX, cellY) : 0;
        }
        var cell = cellOffset(descriptor, cellX, cellY);
        return layer < region.getGenerationWord(cell) ? cell + 2 + layer * 2 : 0;
    }

    int getBaseId(int cellX, int cellY, int height) {
        if (sectors == null) {
            return region.getGenerationLayerId(cellX, cellY, 0);
        }
        var descriptor = sectorDescriptor(cellX, cellY);
        if (descriptor < 0) {
            return descriptor;
        }
        if ((descriptor & SINGLE) != 0) {
            return singleId(descriptor, cellX, cellY);
        }
        var cell = cellOffset(descriptor, cellX, cellY);
        var count = region.getGenerationWord(cell);
        if (count == 0) {
            return 0;
        }
        var limit = height + 26;
        var layer = cell + 2;
        var last = layer + (count - 1) * 2;
        while (layer < last) {
            if ((short) (region.getGenerationWord(layer) & 0xfff0) / 2 <= limit) {
                return layer;
            }
            layer += 2;
        }
        return last;
    }

    int getLayerCount(int cellX, int cellY) {
        if (sectors == null) {
            return 1;
        }
        var descriptor = sectorDescriptor(cellX, cellY);
        return descriptor < 0 || (descriptor & SINGLE) != 0
                ? 1
                : region.getGenerationWord(cellOffset(descriptor, cellX, cellY));
    }

    Storage getStorage(int cellX, int cellY) {
        if (sectors == null) {
            return region.getStorage(cellX, cellY);
        }
        var descriptor = sectorDescriptor(cellX, cellY);
        return descriptor < 0 ? Storage.FLAT : (descriptor & SINGLE) != 0 ? Storage.SINGLE : Storage.MULTI;
    }

    long getEstimatedBytes() {
        return sectors == null
                ? 0
                : (long) sectors.length * Integer.BYTES
                        + (long) origins.length * Integer.BYTES
                        + (long) anchors.length * Character.BYTES;
    }

    int getStride() {
        return 1 << anchorShift;
    }

    long getExtraBytes() {
        return anchors == null ? 0 : ((long) anchors.length - (long) origins.length * 8) * Character.BYTES;
    }

    long nextUpgradeBytes() {
        return anchors == null || anchorShift == 0 ? -1 : (long) anchors.length * Character.BYTES;
    }

    private int sectorDescriptor(int cellX, int cellY) {
        return sectors[(cellX >>> 3) * 256 + (cellY >>> 3)];
    }

    private int cellOffset(int multi, int cellX, int cellY) {
        var cell = (cellX & 7) * 8 + (cellY & 7);
        var cursor = origins[multi] + anchors[multi * anchorsPerSector + (cell >> anchorShift)] * 2;
        for (var previous = 0; previous < (cell & anchorMask); previous++) {
            cursor += 2 + region.getGenerationWord(cursor) * 2;
        }
        return cursor;
    }

    private static int singleId(int descriptor, int cellX, int cellY) {
        return (descriptor & ~SINGLE) + ((cellX & 7) * 8 + (cellY & 7)) * 2;
    }

    private static int chooseAnchorShift(int multiCount, long extraBytesBudget) {
        for (var shift = 0; shift < 3; shift++) {
            var extraBytes = ((64 >> shift) - 8) * (long) Character.BYTES * multiCount;
            if (extraBytes <= extraBytesBudget) {
                return shift;
            }
        }
        return 3;
    }

    private static void reportProgress(Control control, int completed) throws IOException {
        if ((completed & 4095) == 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Path-node indexing interrupted");
            }
            control.checkpoint(completed, SECTORS * 2, "Indexing geodata rows");
        }
    }
}
