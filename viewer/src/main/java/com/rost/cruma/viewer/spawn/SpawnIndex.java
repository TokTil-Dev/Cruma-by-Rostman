package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import com.rost.cruma.core.spawn.SpawnTerritory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

public final class SpawnIndex {
    public static final int BUCKET = 4096;

    public static final int BOX_FLOATS = 8;

    private final State state;
    private final LongObjectHashMap<int[]> territoryBuckets;
    private final LongObjectHashMap<List<Placement>> placementBuckets;
    private final Map<String, Integer> territoryByName;
    private final int[][] makersByTerritory;

    private final SpawnTerritory[] measured;
    private final Extent[] extents;
    private final float[] boxes;

    private volatile Near lastNear;

    private SpawnIndex(
            State state,
            LongObjectHashMap<int[]> territoryBuckets,
            LongObjectHashMap<List<Placement>> placementBuckets,
            Map<String, Integer> territoryByName,
            int[][] makersByTerritory,
            SpawnTerritory[] measured,
            Extent[] extents,
            float[] boxes) {
        this.state = state;
        this.territoryBuckets = territoryBuckets;
        this.placementBuckets = placementBuckets;
        this.territoryByName = territoryByName;
        this.makersByTerritory = makersByTerritory;
        this.measured = measured;
        this.extents = extents;
        this.boxes = boxes;
    }

    public record Placement(int makerIndex, int slot, int x, int y, int z, int heading, int percent) {}

    public record Extent(int minX, int minY, int maxX, int maxY, int bottom, int top, double centerX, double centerY) {
        public static Extent of(SpawnTerritory territory) {
            return new Extent(
                    territory.minX(),
                    territory.minY(),
                    territory.maxX(),
                    territory.maxY(),
                    territory.bottom(),
                    territory.top(),
                    territory.centerX(),
                    territory.centerY());
        }
    }

    public static SpawnIndex build(State state) {
        var territories = state.territories();
        var byName = new HashMap<String, Integer>();
        var buckets = new LongObjectHashMap<List<Integer>>();
        var measured = new SpawnTerritory[territories.size()];
        var extents = new Extent[territories.size()];
        var boxes = new float[territories.size() * BOX_FLOATS];
        Arrays.fill(boxes, Float.NaN);
        for (var index = 0; index < territories.size(); index++) {
            var territory = territories.get(index);
            if (territory == null) {
                continue;
            }
            measured[index] = territory;
            extents[index] = Extent.of(territory);
            writeBox(extents[index], boxes, index * BOX_FLOATS);
            if (territory.points().isEmpty()) {
                continue;
            }
            if (!territory.domain()) {
                byName.putIfAbsent(territory.name(), index);
            }
            var at = index;
            forEachBucket(
                    territory.minX(),
                    territory.minY(),
                    territory.maxX(),
                    territory.maxY(),
                    key -> buckets.getIfAbsentPut(key, ArrayList::new).add(at));
        }
        var makerLists = new HashMap<Integer, List<Integer>>();
        var placements = new LongObjectHashMap<List<Placement>>();
        var makers = state.makers();
        for (var makerIndex = 0; makerIndex < makers.size(); makerIndex++) {
            var maker = makers.get(makerIndex);
            if (maker == null) {
                continue;
            }
            for (var name : maker.territoryNames()) {
                var territory = byName.get(name);
                if (territory != null) {
                    makerLists
                            .computeIfAbsent(territory, ignored -> new ArrayList<>())
                            .add(makerIndex);
                }
            }
            for (var slot = 0; slot < maker.npcs().size(); slot++) {
                var position = maker.npcs().get(slot).position();
                if (position instanceof Fixed fixed) {
                    add(
                            placements,
                            new Placement(makerIndex, slot, fixed.x(), fixed.y(), fixed.z(), fixed.heading(), 100));
                } else if (position instanceof Weighted weighted) {
                    for (var point : weighted.points()) {
                        add(
                                placements,
                                new Placement(
                                        makerIndex,
                                        slot,
                                        point.x(),
                                        point.y(),
                                        point.z(),
                                        point.heading(),
                                        point.percent()));
                    }
                }
            }
        }
        var territoryBuckets = new LongObjectHashMap<int[]>(buckets.size());
        buckets.forEachKeyValue((key, values) -> territoryBuckets.put(
                key, values.stream().mapToInt(Integer::intValue).toArray()));
        var makersByTerritory = new int[territories.size()][];
        makerLists.forEach((territory, values) -> makersByTerritory[territory] =
                values.stream().mapToInt(Integer::intValue).toArray());
        return new SpawnIndex(
                state, territoryBuckets, placements, Map.copyOf(byName), makersByTerritory, measured, extents, boxes);
    }

    public static void writeBox(Extent extent, float[] out, int offset) {
        out[offset] = extent.minX();
        out[offset + 1] = extent.minY();
        out[offset + 2] = extent.bottom();
        out[offset + 3] = extent.maxX();
        out[offset + 4] = extent.maxY();
        out[offset + 5] = extent.top();
        out[offset + 6] = (float) extent.centerX();
        out[offset + 7] = (float) extent.centerY();
    }

    public State getState() {
        return state;
    }

    public int[] findTerritoriesNear(double minX, double minY, double maxX, double maxY) {
        var fromX = Math.floorDiv((int) Math.floor(minX), BUCKET);
        var toX = Math.floorDiv((int) Math.ceil(maxX), BUCKET);
        var fromY = Math.floorDiv((int) Math.floor(minY), BUCKET);
        var toY = Math.floorDiv((int) Math.ceil(maxY), BUCKET);
        var last = lastNear;
        if (last != null && last.fromX == fromX && last.toX == toX && last.fromY == fromY && last.toY == toY) {
            return last.territories;
        }
        var seen = new BitSet();
        var result = new int[16];
        var count = 0;
        for (var x = fromX; x <= toX; x++) {
            for (var y = fromY; y <= toY; y++) {
                var bucket = territoryBuckets.get(key(x, y));
                if (bucket == null) {
                    continue;
                }
                for (var index : bucket) {
                    if (!seen.get(index)) {
                        seen.set(index);
                        if (count == result.length) {
                            result = Arrays.copyOf(result, count * 2);
                        }
                        result[count++] = index;
                    }
                }
            }
        }
        result = Arrays.copyOf(result, count);
        Arrays.sort(result);
        lastNear = new Near(fromX, toX, fromY, toY, result);
        return result;
    }

    private record Near(int fromX, int toX, int fromY, int toY, int[] territories) {}

    public List<Placement> findPlacementsNear(double minX, double minY, double maxX, double maxY) {
        var result = new ArrayList<Placement>();
        var fromX = Math.floorDiv((int) Math.floor(minX), BUCKET);
        var toX = Math.floorDiv((int) Math.ceil(maxX), BUCKET);
        var fromY = Math.floorDiv((int) Math.floor(minY), BUCKET);
        var toY = Math.floorDiv((int) Math.ceil(maxY), BUCKET);
        for (var x = fromX; x <= toX; x++) {
            for (var y = fromY; y <= toY; y++) {
                var bucket = placementBuckets.get(key(x, y));
                if (bucket != null) {
                    result.addAll(bucket);
                }
            }
        }
        return result;
    }

    public int[] getMakersOf(int territoryIndex) {
        if (territoryIndex < 0 || territoryIndex >= makersByTerritory.length) {
            return new int[0];
        }
        var makers = makersByTerritory[territoryIndex];
        return makers == null ? new int[0] : makers;
    }

    public Extent getExtent(int territoryIndex, SpawnTerritory territory) {
        return isMeasured(territoryIndex, territory) ? extents[territoryIndex] : Extent.of(territory);
    }

    public boolean isMeasured(int territoryIndex, SpawnTerritory territory) {
        return territoryIndex >= 0 && territoryIndex < measured.length && measured[territoryIndex] == territory;
    }

    public float[] getBoxes() {
        return boxes;
    }

    public int getTerritoryIndex(String name) {
        var index = territoryByName.get(name);
        return index == null ? -1 : index;
    }

    public SpawnTerritory getTerritory(State current, String name) {
        var index = getTerritoryIndex(name);
        if (index >= 0 && index < current.territories().size()) {
            var territory = current.territories().get(index);
            if (territory != null && territory.name().equals(name)) {
                return territory;
            }
        }
        return current.territory(name);
    }

    private static void add(LongObjectHashMap<List<Placement>> placements, Placement placement) {
        placements
                .getIfAbsentPut(
                        key(Math.floorDiv(placement.x(), BUCKET), Math.floorDiv(placement.y(), BUCKET)), ArrayList::new)
                .add(placement);
    }

    private interface BucketVisitor {
        void visit(long key);
    }

    private static void forEachBucket(int minX, int minY, int maxX, int maxY, BucketVisitor visitor) {
        var fromX = Math.floorDiv(minX, BUCKET);
        var toX = Math.floorDiv(maxX, BUCKET);
        var fromY = Math.floorDiv(minY, BUCKET);
        var toY = Math.floorDiv(maxY, BUCKET);
        if ((long) (toX - fromX + 1) * (toY - fromY + 1) > 4096) {
            toX = Math.min(toX, fromX + 63);
            toY = Math.min(toY, fromY + 63);
        }
        for (var x = fromX; x <= toX; x++) {
            for (var y = fromY; y <= toY; y++) {
                visitor.visit(key(x, y));
            }
        }
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }
}
