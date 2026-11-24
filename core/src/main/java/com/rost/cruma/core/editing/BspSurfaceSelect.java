package com.rost.cruma.core.editing;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntPredicate;
import org.eclipse.collections.impl.list.mutable.primitive.DoubleArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

public final class BspSurfaceSelect {
    private static final double FLOOR = 0.85;
    private static final double WALL = 0.1;
    private static final double SAME_NORMAL = 0.02;
    private static final double IN_FRONT = 0.1;

    private final int surfaceCount;
    private final float[] points;
    private final float[] normals;
    private final int[] actors;
    private final boolean[] polygons;
    private final int[] pointStarts;
    private final int[] pointSurfaces;
    private final int[] surfaceStarts;
    private final int[] surfacePoints;
    private final int[] firstStarts;
    private final int[] firstPoints;
    private final IntObjectHashMap<int[]> actorSurfaces = new IntObjectHashMap<>();
    private final IntObjectHashMap<int[]> actorSides = new IntObjectHashMap<>();
    private final long[] surfaceZones;

    public enum Command {
        BRUSH,
        TEXTURE,
        ADJACENT,
        COPLANAR,
        WALLS,
        FLOORS,
        SLANTS,
        REVERSE,
        ALL,
        ZONE,
        ITEMS
    }

    BspSurfaceSelect(
            float[] points,
            int[] verts,
            int[] nodeSurfaces,
            int[] nodePools,
            int[] nodeCounts,
            int[] nodeZones,
            float[] normals,
            int[] actors,
            int[] brushPolygons) {
        surfaceCount = actors.length;
        this.points = points;
        this.normals = normals;
        this.actors = actors;
        polygons = new boolean[surfaceCount];
        var pointCount = points.length / 3;
        var lastSurface = new int[pointCount];
        Arrays.fill(lastSurface, -1);
        var nodesOf = new IntArrayList[surfaceCount];
        surfaceZones = new long[surfaceCount];
        for (var node = 0; node < nodeSurfaces.length; node++) {
            var surface = nodeSurfaces[node];
            if (surface < 0 || surface >= surfaceCount) {
                continue;
            }
            for (var side = 0; side < 2; side++) {
                var zone = nodeZones[node * 2 + side];
                if (zone > 0 && zone < Long.SIZE) {
                    surfaceZones[surface] |= 1L << zone;
                }
            }
            if (nodeCounts[node] <= 0) {
                continue;
            }
            if (nodesOf[surface] == null) {
                nodesOf[surface] = new IntArrayList(2);
            }
            nodesOf[surface].add(node);
            if (nodeCounts[node] >= 3) {
                polygons[surface] = true;
            }
        }
        var surfaceList = new IntArrayList();
        var firstList = new IntArrayList();
        surfaceStarts = new int[surfaceCount + 1];
        firstStarts = new int[surfaceCount + 1];
        var pointCounts = new int[pointCount + 1];
        for (var surface = 0; surface < surfaceCount; surface++) {
            surfaceStarts[surface] = surfaceList.size();
            firstStarts[surface] = firstList.size();
            var nodes = nodesOf[surface];
            if (nodes == null) {
                continue;
            }
            for (var n = 0; n < nodes.size(); n++) {
                var node = nodes.get(n);
                firstList.add(verts[nodePools[node]]);
                for (var k = 0; k < nodeCounts[node]; k++) {
                    var point = verts[nodePools[node] + k];
                    if (lastSurface[point] != surface) {
                        lastSurface[point] = surface;
                        surfaceList.add(point);
                        pointCounts[point]++;
                    }
                }
            }
        }
        surfaceStarts[surfaceCount] = surfaceList.size();
        firstStarts[surfaceCount] = firstList.size();
        surfacePoints = surfaceList.toArray();
        firstPoints = firstList.toArray();
        pointStarts = new int[pointCount + 1];
        for (var point = 0; point < pointCount; point++) {
            pointStarts[point + 1] = pointStarts[point] + pointCounts[point];
        }
        pointSurfaces = new int[surfacePoints.length];
        var filled = new int[pointCount];
        for (var surface = 0; surface < surfaceCount; surface++) {
            for (var i = surfaceStarts[surface]; i < surfaceStarts[surface + 1]; i++) {
                var point = surfacePoints[i];
                pointSurfaces[pointStarts[point] + filled[point]++] = surface;
            }
        }
        var byActor = new IntObjectHashMap<IntArrayList>();
        var sides = new IntObjectHashMap<IntArrayList>();
        for (var surface = 0; surface < surfaceCount; surface++) {
            if (actors[surface] == 0) {
                continue;
            }
            sides.getIfAbsentPut(actors[surface], IntArrayList::new).add(surface);
            if (polygons[surface] && brushPolygons[surface] >= 0) {
                byActor.getIfAbsentPut(actors[surface], IntArrayList::new).add(surface);
            }
        }
        byActor.forEachKeyValue((actor, list) -> actorSurfaces.put(actor, list.toArray()));
        sides.forEachKeyValue((actor, list) -> actorSides.put(actor, list.toArray()));
    }

    boolean hasPolygon(int surface) {
        return surface >= 0 && surface < surfaceCount && polygons[surface];
    }

    int getSurfaceCount() {
        return surfaceCount;
    }

    int[] getSides(int actor) {
        var sides = actorSides.get(actor);
        return sides == null ? new int[0] : sides;
    }

    BitSet select(Command command, BitSet chosen, IntPredicate brush, int[] keys, IntPredicate wanted) {
        var result = (BitSet) chosen.clone();
        switch (command) {
            case BRUSH -> addBrushes(chosen, brush, result);
            case TEXTURE -> addMaterials(chosen, keys, wanted, result);
            case ITEMS -> addItems(chosen, brush, keys, result);
            case ZONE -> addZones(chosen, result);
            case REVERSE -> {
                result.clear();
                for (var surface = 0; surface < surfaceCount; surface++) {
                    if (polygons[surface] && !chosen.get(surface)) {
                        result.set(surface);
                    }
                }
            }
            case ALL -> {
                for (var surface = 0; surface < surfaceCount; surface++) {
                    if (polygons[surface]) {
                        result.set(surface);
                    }
                }
            }
            default -> flood(command, chosen, result);
        }
        return result;
    }

    private void addBrushes(BitSet chosen, IntPredicate brush, BitSet result) {
        var done = new IntHashSet();
        for (var surface = chosen.nextSetBit(0);
                surface >= 0 && surface < surfaceCount;
                surface = chosen.nextSetBit(surface + 1)) {
            var actor = actors[surface];
            if (actor == 0 || !done.add(actor) || !brush.test(actor)) {
                continue;
            }
            var sides = actorSurfaces.get(actor);
            if (sides != null) {
                for (var side : sides) {
                    result.set(side);
                }
            }
        }
    }

    private void addMaterials(BitSet chosen, int[] materials, IntPredicate wanted, BitSet result) {
        var keys = new IntHashSet();
        for (var surface = chosen.nextSetBit(0);
                surface >= 0 && surface < surfaceCount;
                surface = chosen.nextSetBit(surface + 1)) {
            keys.add(materials[surface]);
        }
        for (var surface = 0; surface < surfaceCount; surface++) {
            if (polygons[surface] && (keys.contains(materials[surface]) || wanted.test(materials[surface]))) {
                result.set(surface);
            }
        }
    }

    private void addItems(BitSet chosen, IntPredicate brush, int[] items, BitSet result) {
        var names = new IntHashSet();
        var brushes = new IntHashSet();
        for (var surface = chosen.nextSetBit(0);
                surface >= 0 && surface < surfaceCount;
                surface = chosen.nextSetBit(surface + 1)) {
            if (items[surface] >= 0) {
                names.add(items[surface]);
            }
            var actor = actors[surface];
            if (actor != 0 && !brushes.contains(actor) && brush.test(actor)) {
                brushes.add(actor);
            }
        }
        brushes.forEach(actor -> {
            var sides = actorSurfaces.get(actor);
            if (sides != null) {
                for (var side : sides) {
                    if (names.contains(items[side])) {
                        result.set(side);
                    }
                }
            }
        });
    }

    private void addZones(BitSet chosen, BitSet result) {
        var zones = 0L;
        for (var surface = chosen.nextSetBit(0);
                surface >= 0 && surface < surfaceCount;
                surface = chosen.nextSetBit(surface + 1)) {
            zones |= surfaceZones[surface];
        }
        for (var surface = 0; surface < surfaceCount; surface++) {
            if (polygons[surface] && (surfaceZones[surface] & zones) != 0) {
                result.set(surface);
            }
        }
    }

    private void flood(Command command, BitSet chosen, BitSet result) {
        var coplanar = command == Command.COPLANAR ? new Coplanar(chosen) : null;
        var reached = new boolean[surfaceCount];
        var visited = new boolean[pointStarts.length - 1];
        var queue = new IntArrayList();
        for (var surface = chosen.nextSetBit(0);
                surface >= 0 && surface < surfaceCount;
                surface = chosen.nextSetBit(surface + 1)) {
            reached[surface] = true;
            queue.add(surface);
        }
        for (var head = 0; head < queue.size(); head++) {
            var surface = queue.get(head);
            for (var i = surfaceStarts[surface]; i < surfaceStarts[surface + 1]; i++) {
                var point = surfacePoints[i];
                if (visited[point]) {
                    continue;
                }
                visited[point] = true;
                for (var j = pointStarts[point]; j < pointStarts[point + 1]; j++) {
                    var other = pointSurfaces[j];
                    if (reached[other] || !passes(command, other, coplanar)) {
                        continue;
                    }
                    reached[other] = true;
                    queue.add(other);
                    if (polygons[other]) {
                        result.set(other);
                    }
                }
            }
        }
    }

    private boolean passes(Command command, int surface, Coplanar coplanar) {
        var z = Math.abs((double) normals[surface * 3 + 2]);
        return switch (command) {
            case FLOORS -> z > FLOOR;
            case WALLS -> z < WALL;
            case SLANTS -> z >= WALL && z <= FLOOR;
            case COPLANAR -> coplanar.isTagged(surface);
            default -> true;
        };
    }

    private final class Coplanar {
        private final FloatArrayList directions = new FloatArrayList();
        private final DoubleArrayList reach = new DoubleArrayList();
        private final byte[] tags = new byte[surfaceCount];

        Coplanar(BitSet chosen) {
            var places = new HashMap<List<Float>, Integer>();
            for (var surface = chosen.nextSetBit(0);
                    surface >= 0 && surface < surfaceCount;
                    surface = chosen.nextSetBit(surface + 1)) {
                var x = normals[surface * 3];
                var y = normals[surface * 3 + 1];
                var z = normals[surface * 3 + 2];
                for (var i = firstStarts[surface]; i < firstStarts[surface + 1]; i++) {
                    var p = firstPoints[i] * 3;
                    var distance = points[p] * (double) x + points[p + 1] * (double) y + points[p + 2] * (double) z;
                    var at = places.get(List.of(x, y, z));
                    if (at == null) {
                        places.put(List.of(x, y, z), reach.size());
                        directions.addAll(x, y, z);
                        reach.add(distance);
                    } else if (distance > reach.get(at)) {
                        reach.set(at, distance);
                    }
                }
            }
        }

        boolean isTagged(int surface) {
            if (tags[surface] == 0) {
                tags[surface] = (byte) (computeTag(surface) ? 1 : 2);
            }
            return tags[surface] == 1;
        }

        private boolean computeTag(int surface) {
            var nx = (double) normals[surface * 3];
            var ny = (double) normals[surface * 3 + 1];
            var nz = (double) normals[surface * 3 + 2];
            for (var n = 0; n < reach.size(); n++) {
                var x = (double) directions.get(n * 3);
                var y = (double) directions.get(n * 3 + 1);
                var z = (double) directions.get(n * 3 + 2);
                if (Math.abs(x * nx + y * ny + z * nz - 1) > SAME_NORMAL) {
                    continue;
                }
                for (var i = firstStarts[surface]; i < firstStarts[surface + 1]; i++) {
                    var p = firstPoints[i] * 3;
                    if (points[p] * x + points[p + 1] * y + points[p + 2] * z - reach.get(n) <= IN_FRONT) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
