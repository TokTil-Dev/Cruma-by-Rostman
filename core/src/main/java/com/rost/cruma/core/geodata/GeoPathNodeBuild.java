package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoPathNodeGenerator.Control;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class GeoPathNodeBuild {
    private static final Pattern REGION_FILE = Pattern.compile("([12][0-9])_([12][0-9])_conv\\.dat");
    private static final long EXTRA_INDEX_BYTES = 64L * 1024 * 1024;

    final List<Region> regions;
    final Nodes nodes = new Nodes();
    private final Region[] regionsBySlot;
    private final BitSet[][] visited = new BitSet[340][];
    private final Control control;

    private GeoPathNodeBuild(Region[] loaded, Control control) {
        this.control = control;
        regionsBySlot = loaded;
        var ordered = new ArrayList<Region>();
        for (var slot = 0; slot < loaded.length; slot++) {
            var region = loaded[slot];
            if (region != null) {
                ordered.add(region);
                visited[slot] = new BitSet[] {new BitSet(), new BitSet()};
            }
        }
        regions = List.copyOf(ordered);
    }

    record Region(int tileX, int tileY, GeoDataRegion data, GeoPathNodeRegionIndex index) {
        int minX() {
            return (tileX - 20) * 32768;
        }

        int minY() {
            return (tileY - 18) * 32768;
        }
    }

    static GeoPathNodeBuild load(Path directory, Control control) throws IOException {
        return load(directory, control, EXTRA_INDEX_BYTES);
    }

    static GeoPathNodeBuild load(Path directory, Control control, long extraIndexBytes) throws IOException {
        return load(directory, null, control, extraIndexBytes);
    }

    static GeoPathNodeBuild load(Path directory, @Nullable List<String> regions, Control control) throws IOException {
        return load(directory, regions, control, EXTRA_INDEX_BYTES);
    }

    void checkpoint(String detail) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Path-node generation interrupted");
        }
        control.checkpoint(nodes.size(), 0, detail);
    }

    void checkpoint(long completed, long total, String detail) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Path-node generation interrupted");
        }
        control.checkpoint(completed, total, detail);
    }

    long getLayer(int x, int y, int layerIndex) {
        var slot = regionSlot(x, y);
        if (slot < 0 || regionsBySlot[slot] == null) {
            return 0;
        }
        var region = regionsBySlot[slot].index();
        var cx = (x & 32767) / 16;
        var cy = (y & 32767) / 16;
        var id = region.getLayerId(cx, cy, layerIndex);
        return id == 0 ? 0 : ((long) (slot + 1) << 32) | Integer.toUnsignedLong(id);
    }

    long getBase(int x, int y, int height) {
        var slot = regionSlot(x, y);
        if (slot < 0 || regionsBySlot[slot] == null) {
            return 0;
        }
        var region = regionsBySlot[slot].index();
        var cx = (x & 32767) / 16;
        var cy = (y & 32767) / 16;
        var id = region.getBaseId(cx, cy, height);
        return id == 0 ? 0 : ((long) (slot + 1) << 32) | Integer.toUnsignedLong(id);
    }

    int getHeight(long ref) {
        return (short) (packedLayer(ref) & 0xfff0) / 2;
    }

    int getNswe(long ref) {
        return packedLayer(ref) & 15;
    }

    long step(long ref, int direction, int destinationX, int destinationY) {
        return ref != 0 && (getNswe(ref) & (1 << direction)) != 0
                ? getBase(destinationX, destinationY, getHeight(ref))
                : 0;
    }

    boolean isMarked(long ref, int bit) {
        return visited[(int) (ref >>> 32) - 1][bit].get(((int) ref & Integer.MAX_VALUE) / 2);
    }

    void mark(long ref, int bit) {
        visited[(int) (ref >>> 32) - 1][bit].set(((int) ref & Integer.MAX_VALUE) / 2);
    }

    boolean canMove(int ax, int ay, int az, int bx, int by, int bz) {
        return GeoPathNodeMovement.canMove(this, ax, ay, az, bx, by, bz);
    }

    private int packedLayer(long ref) {
        if (ref == 0) {
            throw new IllegalArgumentException("Missing geodata layer");
        }
        return regionsBySlot[(int) (ref >>> 32) - 1].data().getGenerationPacked((int) ref);
    }

    private static GeoPathNodeBuild load(
            Path directory, @Nullable List<String> regions, Control control, long extraIndexBytes) throws IOException {
        if (extraIndexBytes < 0 || extraIndexBytes > EXTRA_INDEX_BYTES) {
            throw new IllegalArgumentException("Path-node extra index budget must be between 0 and 64 MiB");
        }
        var names = regions != null ? regions : GeoPathNodeFolder.indexedRegions(directory);
        var count = names.size();
        if (count < 1 || count > 340) {
            throw new IOException("A path-node graph needs between 1 and 340 regions, not " + count);
        }
        var loaded = new Region[340];
        for (var position = 0; position < count; position++) {
            control.checkpoint(position, count, "Loading geodata for path-node generation");
            var matcher = REGION_FILE.matcher(names.get(position));
            if (!matcher.matches()) {
                throw new IOException("Invalid region file name: " + names.get(position));
            }
            var tileX = Integer.parseInt(matcher.group(1));
            var tileY = Integer.parseInt(matcher.group(2));
            if (tileX < 10 || tileX > 29 || tileY < 10 || tileY > 26) {
                throw new IOException("Region lies outside the server world");
            }
            var slot = (tileX - 10) * 17 + tileY - 10;
            if (loaded[slot] != null) {
                throw new IOException("Region listed twice: " + names.get(position));
            }
            var file = directory.resolve(names.get(position));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing regular geodata file: " + file.getFileName());
            }
            var region = GeoDataReader.read(file);
            if (region.getTileX() != tileX || region.getTileY() != tileY) {
                throw new IOException("Region header does not match its indexed filename");
            }
            loaded[slot] = new Region(
                    tileX, tileY, region, new GeoPathNodeRegionIndex(region, control, extraIndexBytes / count));
        }
        redistributeIndexBudget(loaded, extraIndexBytes, control);
        return new GeoPathNodeBuild(loaded, control);
    }

    private static void redistributeIndexBudget(Region[] loaded, long budget, Control control) throws IOException {
        var remaining = budget;
        for (var region : loaded) {
            if (region != null) {
                remaining -= region.index().getExtraBytes();
            }
        }
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Path-node indexing interrupted");
            }
            control.checkpoint(budget - remaining, budget, "Redistributing geodata index budget");
            var selected = -1;
            var selectedStride = 0;
            var selectedBytes = Long.MAX_VALUE;
            for (var slot = 0; slot < loaded.length; slot++) {
                var region = loaded[slot];
                if (region == null) {
                    continue;
                }
                var index = region.index();
                var bytes = index.nextUpgradeBytes();
                if (bytes < 0 || bytes > remaining) {
                    continue;
                }
                if (index.getStride() > selectedStride
                        || (index.getStride() == selectedStride && bytes < selectedBytes)) {
                    selected = slot;
                    selectedStride = index.getStride();
                    selectedBytes = bytes;
                }
            }
            if (selected < 0) {
                return;
            }
            var previous = loaded[selected];
            var upgraded = new GeoPathNodeRegionIndex(
                    previous.data(), control, previous.index().getExtraBytes() + selectedBytes);
            loaded[selected] = new Region(previous.tileX(), previous.tileY(), previous.data(), upgraded);
            remaining -= selectedBytes;
        }
    }

    private static int regionSlot(int x, int y) {
        var tileX = Math.floorDiv(x, 32768) + 20;
        var tileY = Math.floorDiv(y, 32768) + 18;
        return tileX < 10 || tileX > 29 || tileY < 10 || tileY > 26 ? -1 : (tileX - 10) * 17 + tileY - 10;
    }

    static final class Nodes {
        private static final int SECTION_BITS = 14;
        private static final int SECTION_COUNT = 1 << SECTION_BITS;
        private static final int SECTION_MASK = SECTION_COUNT - 1;
        private static final int BLOCK_BITS = 12;
        private static final int BLOCK_SIZE = 1 << BLOCK_BITS;
        private static final int BLOCK_MASK = BLOCK_SIZE - 1;
        private static final int COLUMNS = 14;
        private final ArrayList<int[]> blocks = new ArrayList<>();
        private final int[][] heads = new int[340][];
        private final int[][] counts = new int[340][];
        private int allocated;
        private int free;
        private long size;

        int add(int x, int y, int z, int type) throws IOException {
            var section = sectionOf(x, y);
            if (section < 0) {
                throw new IOException("Generated node lies outside the server world");
            }
            var region = section >>> SECTION_BITS;
            var local = section & SECTION_MASK;
            var regionHeads = heads[region];
            if (regionHeads == null) {
                regionHeads = new int[SECTION_COUNT];
                heads[region] = regionHeads;
                counts[region] = new int[SECTION_COUNT];
            }
            for (var id = regionHeads[local]; id != 0; id = next(id)) {
                var dx = (double) x - getX(id);
                var dy = (double) y - getY(id);
                var dz = (double) z - getZ(id);
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) < 7) {
                    set(id, 0, x);
                    set(id, 1, y);
                    set(id, 2, z);
                    set(id, 4, type);
                    return -id;
                }
            }
            int id;
            if (free != 0) {
                id = free;
                free = next(free);
            } else {
                if (allocated == 0xffffff) {
                    throw new IOException("Path-node count exceeds the server's 24-bit index limit");
                }
                id = ++allocated;
                if (((id - 1) & BLOCK_MASK) == 0) {
                    blocks.add(new int[BLOCK_SIZE * COLUMNS]);
                }
            }
            set(id, 0, x);
            set(id, 1, y);
            set(id, 2, z);
            set(id, 3, regionHeads[local]);
            set(id, 4, type);
            set(id, 5, 0);
            for (var slot = 0; slot < 8; slot++) {
                setLink(id, slot, 0);
            }
            regionHeads[local] = id;
            counts[region][local]++;
            size++;
            return id;
        }

        void remove(int x, int y, int z) {
            var section = sectionOf(x, y);
            if (section < 0) {
                return;
            }
            var region = section >>> SECTION_BITS;
            var local = section & SECTION_MASK;
            var regionHeads = heads[region];
            if (regionHeads == null) {
                return;
            }
            var previous = 0;
            for (var id = regionHeads[local]; id != 0; id = next(id)) {
                if (getX(id) == x && getY(id) == y && getZ(id) == z) {
                    if (previous == 0) {
                        regionHeads[local] = next(id);
                    } else {
                        set(previous, 3, next(id));
                    }
                    set(id, 3, free);
                    free = id;
                    counts[region][local]--;
                    size--;
                    return;
                }
                previous = id;
            }
        }

        int getHead(int x, int y) {
            var section = sectionOf(x, y);
            if (section < 0) {
                return 0;
            }
            var regionHeads = heads[section >>> SECTION_BITS];
            return regionHeads == null ? 0 : regionHeads[section & SECTION_MASK];
        }

        int count(int x, int y) {
            var section = sectionOf(x, y);
            if (section < 0) {
                return 0;
            }
            var regionCounts = counts[section >>> SECTION_BITS];
            return regionCounts == null ? 0 : regionCounts[section & SECTION_MASK];
        }

        int next(int id) {
            return get(id, 3);
        }

        int getX(int id) {
            return get(id, 0);
        }

        int getY(int id) {
            return get(id, 1);
        }

        int getZ(int id) {
            return get(id, 2);
        }

        int type(int id) {
            return get(id, 4);
        }

        int getIndex(int id) {
            return get(id, 5);
        }

        void setIndex(int id, int value) {
            set(id, 5, value);
        }

        int getLink(int id, int slot) {
            return get(id, 6 + slot);
        }

        void setLink(int id, int slot, int neighbor) {
            set(id, 6 + slot, neighbor);
        }

        long size() {
            return size;
        }

        private int get(int id, int column) {
            return blocks.get((id - 1) >>> BLOCK_BITS)[((id - 1) & BLOCK_MASK) * COLUMNS + column];
        }

        private void set(int id, int column, int value) {
            blocks.get((id - 1) >>> BLOCK_BITS)[((id - 1) & BLOCK_MASK) * COLUMNS + column] = value;
        }

        private static int sectionOf(int x, int y) {
            var region = regionSlot(x, y);
            return region < 0 ? -1 : region * SECTION_COUNT + ((x & 32767) / 256) * 128 + (y & 32767) / 256;
        }
    }
}
