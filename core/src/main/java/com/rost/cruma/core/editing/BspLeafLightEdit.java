package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.BspLightmapTail.LeafLights;
import com.rost.cruma.core.editing.NativeBspLeafLights.Flood;
import com.rost.cruma.core.editing.NativeBspLeafLights.Geometry;
import com.rost.cruma.core.editing.NativeBspLeafLights.Light;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.ULevel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

final class BspLeafLightEdit {
    final int modelExport;

    private final @Nullable BspLightmapTail tail;
    private final Flood flood;
    private final Map<Integer, Integer> order;
    private final int actorCount;
    private final @Nullable NativeBspRegions regions;
    private final int[] zoneActors;

    private BspLeafLightEdit(
            int modelExport,
            @Nullable BspLightmapTail tail,
            Flood flood,
            Map<Integer, Integer> order,
            int actorCount,
            @Nullable NativeBspRegions regions,
            int[] zoneActors) {
        this.modelExport = modelExport;
        this.tail = tail;
        this.flood = flood;
        this.order = Map.copyOf(order);
        this.actorCount = actorCount;
        this.regions = regions;
        this.zoneActors = zoneActors.clone();
    }

    static BspLeafLightEdit open(Path map, PackageBytes source, List<Integer> actors) throws IOException {
        var directory = map.toAbsolutePath().getParent();
        try (var archive = new UArchive(
                source.packageName,
                ByteBuffer.wrap(source.body.clone()).order(ByteOrder.LITTLE_ENDIAN),
                new UArchiveLoader(directory, List.of()))) {
            var levels = new ArrayList<ULevel>();
            archive.loadObjects("Level", levels);
            if (levels.isEmpty()) {
                throw new IOException("The map has no Level");
            }
            if (levels.getFirst().getModel().isNull()) {
                return new BspLeafLightEdit(0, null, null, Map.of(), actors.size(), null, new int[0]);
            }
            var level = levels.getFirst();
            var model = level.getModel().get();
            if (model == null || model.getNodes() == null || model.getLeaves() == null) {
                throw new IOException("The level model has no BSP leaves");
            }
            var modelExport = level.getModel().getIndex();
            var tail = BspLightmapTail.read(source, modelExport);
            if (tail.getLeafLights().permeating().length != model.getLeaves().length) {
                throw new IOException("The level model's leaves do not read the same twice");
            }
            var flood = Flood.of(Geometry.of(model), () -> false);
            var order = new HashMap<Integer, Integer>();
            for (var i = 0; i < actors.size(); i++) {
                order.putIfAbsent(actors.get(i), i);
            }
            NativeBspRegions regions = null;
            var zoneActors = new int[0];
            if (model.getZones() != null && model.getNodes().length > 0) {
                try {
                    regions = NativeBspRegions.shadowSnapshot(model);
                } catch (IllegalArgumentException unqualified) {
                }
                var levelInfo = actors.isEmpty() ? 0 : actors.getFirst();
                zoneActors = new int[model.getZones().length];
                for (var i = 0; i < zoneActors.length; i++) {
                    var zone = model.getZones()[i].getZoneActor().getIndex();
                    zoneActors[i] = zone != 0 ? zone : levelInfo;
                }
            }
            return new BspLeafLightEdit(modelExport, tail, flood, order, actors.size(), regions, zoneActors);
        } catch (RuntimeException unreadable) {
            throw new IOException(
                    unreadable.getMessage() == null ? unreadable.toString() : unreadable.getMessage(), unreadable);
        }
    }

    static LeafLights read(PackageBytes source, byte[] payload, int serialOffset, int flags) throws IOException {
        return BspLightmapTail.read(source, payload, serialOffset, flags).getLeafLights();
    }

    static int[] editRun(int[] run, int leaf, Map<Integer, BitSet> reached, Map<Integer, Integer> places) {
        var result = new IntArrayList(run.length + 1);
        for (var reference : run) {
            if (!reached.containsKey(reference)) {
                result.add(reference);
            }
        }
        for (var entry : reached.entrySet()) {
            if (entry.getValue().get(leaf)) {
                insert(result, entry.getKey(), places);
            }
        }
        return result.toArray();
    }

    static int[] formRuns(int[][] runs, IntArrayList slots) {
        var starts = new int[runs.length];
        Arrays.fill(starts, -1);
        for (var leaf = 0; leaf < runs.length; leaf++) {
            if (runs[leaf].length == 0) {
                continue;
            }
            starts[leaf] = slots.size();
            slots.addAll(runs[leaf]);
            slots.add(0);
        }
        return starts;
    }

    @Nullable
    LeafLights getSaved() {
        return tail == null ? null : tail.getLeafLights();
    }

    synchronized String getRestriction(Vector3 from, Vector3 to) {
        if (flood == null || flood.isEmpty()) {
            return "";
        }
        var leaf = flood.getLeafAt(to.x(), to.y(), to.z());
        if (leaf < 0) {
            return "The new place is inside solid BSP, where a light lights nothing";
        }
        var before = flood.getLeafAt(from.x(), from.y(), from.z());
        if (regions == null && before >= 0 && flood.getZone(before) != flood.getZone(leaf)) {
            return "A light can only move inside its own zone in this map";
        }
        return "";
    }

    synchronized float getSolidBelow(Vector3 point, float depth) {
        if (flood == null || flood.isEmpty()) {
            return Float.NaN;
        }
        if (flood.getLeafAt(point.x(), point.y(), point.z()) < 0) {
            return 0;
        }
        for (var down = 16f; down <= depth; down += 16) {
            if (flood.getLeafAt(point.x(), point.y(), point.z() - down) < 0) {
                var empty = down - 16;
                var solid = down;
                for (var i = 0; i < 5; i++) {
                    var middle = (empty + solid) / 2;
                    if (flood.getLeafAt(point.x(), point.y(), point.z() - middle) < 0) {
                        solid = middle;
                    } else {
                        empty = middle;
                    }
                }
                return solid;
            }
        }
        return Float.NaN;
    }

    synchronized @Nullable LightRegion getRegion(Vector3 location) {
        if (regions == null) {
            return null;
        }
        var region = regions.pointRegion(new Vector(location.x(), location.y(), location.z()));
        var zone = region.zone() < zoneActors.length ? zoneActors[region.zone()] : 0;
        return zone == 0 ? null : new LightRegion(zone, region.leaf(), region.zone());
    }

    @Nullable
    LeafLights buildLists(Map<Integer, Light> moved) {
        return buildLists(moved, Set.of(), List.of());
    }

    @Nullable
    LeafLights buildLists(Map<Integer, Light> moved, Set<Integer> removed, List<Integer> appended) {
        if (tail == null || flood == null) {
            return null;
        }
        var saved = tail.getLeafLights();
        if (moved.isEmpty() && removed.isEmpty()) {
            return saved;
        }
        var places = new HashMap<>(order);
        for (var i = 0; i < appended.size(); i++) {
            places.put(appended.get(i), actorCount + i);
        }
        var reached = new HashMap<Integer, BitSet>();
        synchronized (this) {
            for (var light : moved.values()) {
                var leaves = new BitSet();
                for (var leaf : flood.getLeaves(light)) {
                    leaves.set(leaf);
                }
                reached.put(light.reference(), leaves);
            }
        }
        for (var reference : removed) {
            reached.put(reference, new BitSet());
        }
        var permeating = saved.permeating();
        var volumetric = saved.volumetric();
        var leafCount = permeating.length;
        var permeatingRuns = new int[leafCount][];
        var volumetricRuns = new int[leafCount][];
        var changed = false;
        for (var leaf = 0; leaf < leafCount; leaf++) {
            var before = saved.run(permeating[leaf]);
            permeatingRuns[leaf] = editRun(before, leaf, reached, places);
            changed |= !Arrays.equals(before, permeatingRuns[leaf]);
            before = saved.run(volumetric[leaf]);
            volumetricRuns[leaf] = editRun(before, leaf, reached, places);
            changed |= !Arrays.equals(before, volumetricRuns[leaf]);
        }
        if (!changed) {
            return saved;
        }
        var slots = new IntArrayList();
        var permeatingStarts = formRuns(permeatingRuns, slots);
        var volumetricStarts = formRuns(volumetricRuns, slots);
        return new LeafLights(permeatingStarts, volumetricStarts, slots.toArray());
    }

    byte[] getPayload(LeafLights lists, int serialOffset) throws IOException {
        if (tail == null) {
            throw new IOException("The map has no level model");
        }
        return tail.encode(lists, Map.of(), Map.of(), serialOffset);
    }

    private static void insert(IntArrayList run, int reference, Map<Integer, Integer> places) {
        var position = places.getOrDefault(reference, Integer.MAX_VALUE);
        for (var i = 0; i < run.size(); i++) {
            var other = places.get(run.get(i));
            if (other != null && other < position) {
                run.addAtIndex(i, reference);
                return;
            }
        }
        run.add(reference);
    }
}
