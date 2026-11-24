package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BspLightmapTail.LeafLights;
import com.rost.cruma.core.editing.NativeBspLeafLights.Flood;
import com.rost.cruma.core.editing.NativeBspLeafLights.Geometry;
import com.rost.cruma.core.editing.NativeBspLeafLights.Light;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.BrushActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.ULevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

final class BspBrushDelete {
    static final int DELETED_FLAGS = 0x09;

    private static final int NOT_SOLID_NODE = 0x21;
    private static final int NOT_CSG = 0x01;
    private static final int SEMISOLID = 0x20;
    private static final int NOT_SOLID = 0x08;
    private static final int FAKE_BACKDROP = 0x80;
    private static final int OWN_BRANCH = 0x400;
    private static final int WATER = 0x00080000;
    private static final int PORTAL = 0x04000000;
    private static final int MIRRORED = 0x20000000;
    private static final int ANTI_PORTAL = 0x08000000;
    private static final int SECTION_FLAGS = 0x2400100;
    private static final int SECTION_VERTICES = 0xfffe;
    private static final int MAX_CAP_VERTICES = 16;
    private static final int ADD = 1;
    private static final int SUBTRACT = 2;
    private static final double WORLD = 1_048_576;
    private static final double SAMPLE = 2;
    private static final double ON_PLANE = 0.01;
    private static final float CAP_MARGIN = 0.5f;

    final int modelExport;

    private final PackageBytes source;
    private final Geometry geometry;
    private final int nodeCount;
    private final int[] zones;
    private final int[] surfaceActors;
    private final int[] surfaceBrushPolygons;
    private final int[] surfaceFlags;
    private final int[] surfaceMaterials;
    private final int[][] surfaceNodes;
    private final int[] leafZones;
    private final long[] leafVisibleZones;
    private final int zoneCount;
    private final int[] parents;
    private final int[] parentSides;
    private final byte[] statusA;
    private final byte[] statusB;
    private final boolean windsWithPlane;
    private final @Nullable NativeBspRegions regions;
    private final Set<Integer> skyZones;
    private final @Nullable String mapRestriction;
    private final Map<Integer, BrushInfo> brushes;
    private final List<Placed> placed;
    private final List<Light> lights;
    private final Map<Integer, Integer> places;
    private final LeafLights savedLists;
    private @Nullable List<int[]> savedReach;
    private final Map<Integer, Analysis> analyses = new HashMap<>();
    private @Nullable Map<Integer, double[]> solidCells;

    private record BrushInfo(
            int index,
            String name,
            int csgOper,
            int polyFlags,
            boolean inLevel,
            boolean deleteMe,
            List<double[][]> polygons,
            double[] box) {}

    private record Placed(String name, double x, double y, double z) {}

    record Cap(int node, int side, float[] plane, float[] polygon, int surface, int zone, long visibleZones) {}

    private record Analysis(String restriction, List<Cap> caps) {}

    private BspBrushDelete(
            int modelExport,
            PackageBytes source,
            UModel model,
            Geometry geometry,
            List<Light> lights,
            Map<Integer, Integer> places,
            LeafLights savedLists,
            Map<Integer, BrushInfo> brushes,
            List<Placed> placed,
            @Nullable NativeBspRegions regions,
            Set<Integer> skyZones) {
        this.modelExport = modelExport;
        this.source = source;
        this.geometry = geometry;
        this.lights = List.copyOf(lights);
        this.places = Map.copyOf(places);
        this.savedLists = savedLists;
        this.brushes = brushes;
        this.placed = List.copyOf(placed);
        this.regions = regions;
        this.skyZones = Set.copyOf(skyZones);
        var nodes = model.getNodes();
        nodeCount = nodes.length;
        zones = new int[nodeCount * 2];
        for (var i = 0; i < nodeCount; i++) {
            zones[i * 2] = nodes[i].getZone()[0];
            zones[i * 2 + 1] = nodes[i].getZone()[1];
        }
        var surfaces = model.getSurfaces();
        surfaceActors = new int[surfaces.length];
        surfaceBrushPolygons = new int[surfaces.length];
        surfaceFlags = new int[surfaces.length];
        surfaceMaterials = new int[surfaces.length];
        var polygons = new ArrayList<IntArrayList>();
        for (var i = 0; i < surfaces.length; i++) {
            surfaceActors[i] = surfaces[i].getActor().getIndex();
            surfaceBrushPolygons[i] = surfaces[i].getBrushPolygonIndex();
            surfaceFlags[i] = (int) surfaces[i].getPolygonFlags();
            surfaceMaterials[i] = surfaces[i].getMaterial().getIndex();
            polygons.add(new IntArrayList());
        }
        for (var i = 0; i < nodeCount; i++) {
            var surface = geometry.surface()[i];
            if (geometry.vertexCount()[i] > 0 && surface >= 0 && surface < surfaces.length) {
                polygons.get(surface).add(i);
            }
        }
        surfaceNodes = new int[surfaces.length][];
        for (var i = 0; i < surfaces.length; i++) {
            surfaceNodes[i] = polygons.get(i).toArray();
        }
        var leaves = model.getLeaves();
        leafZones = new int[leaves.length];
        leafVisibleZones = new long[leaves.length];
        for (var i = 0; i < leaves.length; i++) {
            leafZones[i] = leaves[i].zone();
            leafVisibleZones[i] = leaves[i].visibleZones();
        }
        zoneCount = model.getZones() == null ? 0 : model.getZones().length;
        parents = new int[nodeCount];
        parentSides = new int[nodeCount];
        Arrays.fill(parents, -1);
        var stack = new IntArrayList();
        if (nodeCount > 0) {
            stack.add(0);
        }
        var reached = new boolean[nodeCount];
        if (nodeCount > 0) {
            reached[0] = true;
        }
        while (!stack.isEmpty()) {
            var node = stack.removeAtIndex(stack.size() - 1);
            for (var side = 0; side < 2; side++) {
                var child = side == 0 ? geometry.back()[node] : geometry.front()[node];
                if (child >= 0 && child < nodeCount && !reached[child]) {
                    reached[child] = true;
                    parents[child] = node;
                    parentSides[child] = side;
                    stack.add(child);
                }
            }
        }
        var status = status(
                geometry.vertexCount(), geometry.flags(), geometry.rootOutside(), geometry.back(), geometry.front());
        statusA = status[0];
        statusB = status[1];
        windsWithPlane = measureWinding();
        mapRestriction = mapRestriction(model);
    }

    static BspBrushDelete open(Path map, PackageBytes source, List<Integer> actors) throws IOException {
        if (source.fileVersion < 123 || source.fileVersion > 133) {
            throw new IOException("BSP brushes of package version " + source.fileVersion + " cannot be deleted");
        }
        var directory = map.toAbsolutePath().getParent();
        try (var archive = new UArchive(
                source.packageName,
                ByteBuffer.wrap(source.body.clone()).order(ByteOrder.LITTLE_ENDIAN),
                new UArchiveLoader(directory, List.of()))) {
            var levels = new ArrayList<ULevel>();
            archive.loadObjects("Level", levels);
            if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
                throw new IOException("The map has no level model");
            }
            var level = levels.getFirst();
            var model = level.getModel().get();
            if (model == null
                    || model.getNodes() == null
                    || model.getNodes().length == 0
                    || model.getLeaves() == null
                    || model.getLeafHulls() == null
                    || model.getZones() == null) {
                throw new IOException("The level model has no BSP leaves, hulls or zones that Cruma can read");
            }
            var modelExport = level.getModel().getIndex();
            var tail = BspLightmapTail.read(source, modelExport);
            if (tail.getLeafLights().permeating().length != model.getLeaves().length) {
                throw new IOException("The level model's leaves do not read the same twice");
            }
            var input = NativeBspLeafLights.capture(archive, false);
            var places = new HashMap<Integer, Integer>();
            for (var i = 0; i < actors.size(); i++) {
                places.putIfAbsent(actors.get(i), i);
            }
            NativeBspRegions regions = null;
            try {
                regions = NativeBspRegions.shadowSnapshot(model);
            } catch (IllegalArgumentException unqualified) {
            }
            var inLevel = new HashSet<>(actors);
            return new BspBrushDelete(
                    modelExport,
                    source,
                    model,
                    input.geometry(),
                    input.lights(),
                    places,
                    tail.getLeafLights(),
                    readBrushes(archive, inLevel),
                    readPlaced(archive, actors),
                    regions,
                    readSkyZones(archive, model));
        } catch (RuntimeException unreadable) {
            throw new IOException(
                    unreadable.getMessage() == null ? unreadable.toString() : unreadable.getMessage(), unreadable);
        }
    }

    int getBrush(int surface) {
        if (surface < 0 || surface >= surfaceActors.length) {
            return 0;
        }
        var actor = surfaceActors[surface];
        return brushes.containsKey(actor) ? actor : 0;
    }

    String getBrushName(int brush) {
        var info = brushes.get(brush);
        return info == null ? "Brush " + brush : info.name();
    }

    int[] getSurfaces(int brush) {
        var result = new IntArrayList();
        for (var surface = 0; surface < surfaceActors.length; surface++) {
            if (surfaceActors[surface] == brush) {
                result.add(surface);
            }
        }
        return result.toArray();
    }

    boolean contains(int brush, double x, double y, double z) {
        var info = brushes.get(brush);
        if (info == null || info.polygons().isEmpty() || !isWithin(info.box(), x, y, z, 0)) {
            return false;
        }
        return isInside(triangles(info.polygons()), new double[] {x, y, z});
    }

    int getSurfaceCount() {
        return surfaceActors.length;
    }

    int getLeafCount() {
        return leafZones.length;
    }

    int getNodeCount() {
        return nodeCount;
    }

    synchronized String getRestriction(int brush) {
        return analysis(brush).restriction();
    }

    synchronized List<Cap> getCaps(int brush) {
        return analysis(brush).caps();
    }

    synchronized PackageBytes createRebased(Set<Integer> deleted, IntFunction<float @Nullable []> mappingSizes)
            throws IOException {
        var caps = new ArrayList<Cap>();
        for (var brush : new TreeSet<>(deleted)) {
            var restriction = getRestriction(brush);
            if (!restriction.isEmpty()) {
                throw new IOException(restriction);
            }
            caps.addAll(getCaps(brush));
        }
        var entry = source.exports.get(modelExport - 1);
        var payload = Arrays.copyOfRange(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        var target = source.body.length;
        var edited = write(payload, entry.serialOffset(), target, deleted, caps, lists(caps), mappingSizes);
        var out = new ByteArrayOutputStream(source.body.length + edited.length + source.exportEnd);
        out.writeBytes(source.body);
        out.writeBytes(edited);
        var exportOffset = out.size();
        for (var row : source.exports) {
            if (row.index() == modelExport) {
                out.write(source.body, row.rowStart(), row.sizeOffset() - row.rowStart());
                PackageBytes.writeIndex(out, edited.length);
                PackageBytes.writeIndex(out, target);
            } else {
                out.write(source.body, row.rowStart(), row.rowEnd() - row.rowStart());
            }
        }
        var body = out.toByteArray();
        PackageBytes.littleEndian(body).putInt(24, exportOffset);
        return new PackageBytes(Path.of(source.packageName + ".unr"), body);
    }

    String getWrittenProblem(Path map, PackageBytes written, Set<Integer> deleted) throws IOException {
        try (var archive = new UArchive(
                written.packageName,
                ByteBuffer.wrap(written.body.clone()).order(ByteOrder.LITTLE_ENDIAN),
                new UArchiveLoader(map.toAbsolutePath().getParent(), List.of()))) {
            var levels = new ArrayList<ULevel>();
            archive.loadObjects("Level", levels);
            if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
                return "The written map has no level model";
            }
            var model = levels.getFirst().getModel().get();
            return model == null ? "The written level model cannot be read" : getWrittenProblem(model, deleted);
        } catch (RuntimeException unreadable) {
            return unreadable.getMessage() == null ? unreadable.toString() : unreadable.getMessage();
        }
    }

    String getWrittenProblem(UModel written, Set<Integer> deleted) {
        var nodes = written.getNodes();
        var surfaces = written.getSurfaces();
        var caps = new ArrayList<Cap>();
        for (var brush : deleted) {
            caps.addAll(getCaps(brush));
        }
        if (nodes == null || surfaces == null || nodes.length != nodeCount + caps.size()) {
            return "The written model does not have one cap node for each solid leaf";
        }
        if (surfaces.length != surfaceActors.length + caps.size()) {
            return "The written model does not have one cap surface for each cap";
        }
        if (written.getLeaves() == null || written.getLeaves().length != leafZones.length + caps.size()) {
            return "The written model does not have one new leaf for each cap";
        }
        for (var brush : deleted) {
            for (var surface : getSurfaces(brush)) {
                if ((surfaces[surface].getPolygonFlags() & DELETED_FLAGS) != DELETED_FLAGS) {
                    return "A surface of " + getBrushName(brush) + " is not invisible and not solid";
                }
            }
        }
        var back = new int[nodes.length];
        var front = new int[nodes.length];
        var counts = new int[nodes.length];
        var flags = new int[nodes.length];
        for (var i = 0; i < nodes.length; i++) {
            back[i] = nodes[i].getBackIndex();
            front[i] = nodes[i].getFrontIndex();
            counts[i] = nodes[i].getVertexCount();
            flags[i] = nodes[i].getFlags();
            if (i >= nodeCount
                    && (nodes[i].getCollisionBound() != -1
                            || flags[i] != 0
                            || counts[i] < 3
                            || back[i] != -1
                            || front[i] != -1)) {
                return "Cap node " + i + " has a collision hull, flags, a child or no polygon";
            }
        }
        var after = status(counts, flags, written.isRootOutside(), back, front);
        for (var slot = 0; slot < nodeCount * 2; slot++) {
            if (statusA[slot] < 0 || isTarget(caps, slot)) {
                continue;
            }
            if (after[0][slot] != statusA[slot] || after[1][slot] != statusB[slot]) {
                return "Leaf slot " + slot + " changed its state";
            }
        }
        for (var k = 0; k < caps.size(); k++) {
            var slot = (nodeCount + k) * 2 + 1;
            if (after[0][slot] != 1 || after[1][slot] != 1) {
                return "The cell under cap " + k + " is still solid";
            }
        }
        return "";
    }

    private Analysis analysis(int brush) {
        var cached = analyses.get(brush);
        if (cached == null) {
            cached = analyze(brush);
            analyses.put(brush, cached);
        }
        return cached;
    }

    private Analysis analyze(int brush) {
        var info = brushes.get(brush);
        if (info == null) {
            return refused("This surface did not come from a brush of the map");
        }
        var surfaces = getSurfaces(brush);
        if (surfaces.length == 0) {
            return refused("This brush has no surfaces in the map's BSP");
        }
        if (info.csgOper() == SUBTRACT) {
            return refused("It is cut out of the solid (a subtractive brush): deleting it would fill its room with"
                    + " solid, so it stays");
        }
        if (info.csgOper() != ADD) {
            return refused("This brush does not add solid to the map");
        }
        for (var surface : surfaces) {
            var flags = surfaceFlags[surface];
            if ((flags & PORTAL) != 0) {
                return refused("A side of this brush is a zone portal, which cuts the level into zones, so it stays");
            }
            if ((flags & WATER) != 0) {
                return refused("A side of this brush is water, which the game draws by its own rules, so it stays");
            }
            if ((flags & FAKE_BACKDROP) != 0) {
                return refused("A side of this brush shows the sky, so it stays");
            }
            if ((flags & (ANTI_PORTAL | OWN_BRANCH)) != 0) {
                return refused("A side of this brush is also an antiportal: the game hides what lies behind it, also"
                        + " when it is invisible, so it stays");
            }
            if ((flags & MIRRORED) != 0) {
                return refused("A side of this brush is drawn by rules of its own that Cruma cannot turn off");
            }
        }
        var solidity = info.polyFlags();
        for (var surface : surfaces) {
            solidity |= surfaceFlags[surface];
            for (var node : surfaceNodes[surface]) {
                if ((geometry.flags()[node] & NOT_CSG) != 0) {
                    return refused("Part of this brush is a sheet, which never blocked: delete its sheets alone");
                }
            }
        }
        if ((solidity & NOT_SOLID) != 0) {
            return refused("This brush is not solid: delete its sheets alone");
        }
        if (!info.inLevel() || info.deleteMe()) {
            return refused("The brush of this surface is no longer in the map, so Cruma cannot tell what it fills");
        }
        if (!mapRestrictionText().isEmpty()) {
            return refused(mapRestrictionText());
        }
        var mismatch = getShapeProblem(info, surfaces);
        if (!mismatch.isEmpty()) {
            return refused(mismatch);
        }
        var triangles = triangles(info.polygons());
        for (var actor : placed) {
            if (isWithin(info.box(), actor.x(), actor.y(), actor.z(), 0)
                    && isInside(triangles, new double[] {actor.x(), actor.y(), actor.z()})) {
                return refused(actor.name() + " stands inside it, and its zone would change");
            }
        }
        for (var other : brushes.values()) {
            if (other.index() == brush || other.polygons().isEmpty() || other.csgOper() != ADD) {
                continue;
            }
            if (isEnclosed(other, info, triangles)) {
                return refused("Another brush lies inside it: " + other.name());
            }
        }
        var mine = new HashSet<Integer>();
        for (var surface : surfaces) {
            mine.add(surface);
        }
        if (hasOtherFaceInside(info, triangles, mine)) {
            return refused("Another surface lies inside it, so its solid is not its own");
        }
        if (hasSheetOnIt(info, triangles, mine)) {
            return refused("A sheet lies on it, such as a decal or a net, and would float in the air");
        }
        var targets = new TreeSet<Integer>();
        var box = info.box();
        for (var entry : solidCells().entrySet()) {
            var e = entry.getValue();
            if (e[6] < box[0] - 1
                    || e[3] > box[3] + 1
                    || e[7] < box[1] - 1
                    || e[4] > box[4] + 1
                    || e[8] < box[2] - 1
                    || e[5] > box[5] + 1) {
                continue;
            }
            if (!isInside(triangles, new double[] {e[0], e[1], e[2]})) {
                continue;
            }
            if (e[3] < box[0] - SAMPLE
                    || e[4] < box[1] - SAMPLE
                    || e[5] < box[2] - SAMPLE
                    || e[6] > box[3] + SAMPLE
                    || e[7] > box[4] + SAMPLE
                    || e[8] > box[5] + SAMPLE) {
                return refused(touching());
            }
            targets.add(entry.getKey());
        }
        if (targets.isEmpty()) {
            return refused("No solid lies inside this brush");
        }
        var zone = -1;
        for (var surface : surfaces) {
            for (var node : surfaceNodes[surface]) {
                var count = geometry.vertexCount()[node];
                if (count < 3) {
                    continue;
                }
                var center = nodeCenter(node);
                var p = node * 4;
                var nx = geometry.nodePlanes()[p];
                var ny = geometry.nodePlanes()[p + 1];
                var nz = geometry.nodePlanes()[p + 2];
                var inside = new double[] {center[0] - nx * SAMPLE, center[1] - ny * SAMPLE, center[2] - nz * SAMPLE};
                var outside = new double[] {center[0] + nx * SAMPLE, center[1] + ny * SAMPLE, center[2] + nz * SAMPLE};
                var within = locate(inside);
                if (isInside(triangles, inside)
                        && (statusA[within] == 0 || statusB[within] == 0)
                        && !targets.contains(within)) {
                    return refused(touching());
                }
                var slot = locate(outside);
                if (statusA[slot] != 1 || statusB[slot] != 1) {
                    return refused(touching());
                }
                if (regions == null) {
                    return refused("The zones of this map cannot be read, so the air around it has no known zone");
                }
                var region =
                        regions.pointRegion(new Vector((float) outside[0], (float) outside[1], (float) outside[2]));
                if (zone >= 0 && region.zone() != zone) {
                    return refused("It stands where two zones meet, so its space has no one zone");
                }
                zone = region.zone();
            }
        }
        if (skyZones.contains(zone)) {
            return refused("It stands in the sky zone, which the game draws by its own rules, so it stays");
        }
        if (zone <= 0) {
            return refused("The air around it belongs to no zone, so its space could get none");
        }
        var visibleZones = 0L;
        var found = false;
        for (var leaf = 0; leaf < leafZones.length; leaf++) {
            if (leafZones[leaf] == zone) {
                visibleZones = leafVisibleZones[leaf];
                found = true;
                break;
            }
        }
        if (!found) {
            return refused("The zone around it has no leaf, so its space could not join it");
        }
        for (var slot : targets) {
            if (geometry.savedNodeLeaves()[slot] >= 0 || zones[slot] != 0) {
                return refused("Part of its solid is saved as a leaf of air, as for an invisible brush, so it stays");
            }
        }
        var caps = new ArrayList<Cap>();
        var material = surfaces[0];
        for (var slot : targets) {
            var cap = cap(slot, material, zone, visibleZones);
            if (cap == null) {
                return refused("Its solid is cut into pieces that Cruma cannot close");
            }
            caps.add(cap);
        }
        var edited = edited(caps);
        if (!NativeBspLeafLights.isRenumbering(
                NativeBspLeafLights.assignLeaves(edited), edited.savedNodeLeaves(), edited.leafZones().length)) {
            return refused("The map's BSP leaves could not take the new ones, so it stays");
        }
        return new Analysis("", List.copyOf(caps));
    }

    private String mapRestrictionText() {
        return mapRestriction == null ? "" : mapRestriction;
    }

    private @Nullable String mapRestriction(UModel model) {
        if (model.isRootOutside()) {
            return "The BSP of this map starts outside the solid, which the census never saw, so its brushes stay";
        }
        var assigned = NativeBspLeafLights.assignLeaves(geometry);
        if (!NativeBspLeafLights.isRenumbering(assigned, geometry.savedNodeLeaves(), leafZones.length)) {
            return "The map's numbering of BSP leaves does not match the one the editor gives, so new leaves cannot be"
                    + " added";
        }
        if (zoneCount == 0) {
            return "The map has no zones, so the space of a brush could not join one";
        }
        return null;
    }

    private String getShapeProblem(BrushInfo info, int[] surfaces) {
        var polygonArea = new double[info.polygons().size()];
        var bspArea = new double[polygonArea.length];
        for (var i = 0; i < polygonArea.length; i++) {
            polygonArea[i] = area(info.polygons().get(i));
        }
        for (var surface : surfaces) {
            var index = surfaceBrushPolygons[surface];
            if (index < 0 || index >= polygonArea.length) {
                return "The BSP of this brush does not match its brush polygons, so Cruma cannot tell what it fills";
            }
            var plane = surfacePlane(surface);
            var polygon = info.polygons().get(index);
            var normal = normal(polygon);
            if (normal != null) {
                var dot = Math.abs(normal[0] * plane[0] + normal[1] * plane[1] + normal[2] * plane[2]);
                var distance = Math.abs(
                        plane[0] * polygon[0][0] + plane[1] * polygon[0][1] + plane[2] * polygon[0][2] - plane[3]);
                if (dot < 0.999 || distance > 2) {
                    return "The BSP of this brush does not match its brush polygons, so Cruma cannot tell what it"
                            + " fills";
                }
            }
            for (var node : surfaceNodes[surface]) {
                if (geometry.vertexCount()[node] >= 3) {
                    bspArea[index] += area(nodePolygon(node));
                }
            }
        }
        for (var i = 0; i < polygonArea.length; i++) {
            if (polygonArea[i] < 1) {
                continue;
            }
            var ratio = bspArea[i] / polygonArea[i];
            if (ratio < 0.99 || ratio > 1.01) {
                return touching();
            }
        }
        return "";
    }

    private boolean isEnclosed(BrushInfo other, BrushInfo info, List<double[][]> triangles) {
        var ob = other.box();
        var bb = info.box();
        if (ob[0] < bb[0] || ob[1] < bb[1] || ob[2] < bb[2] || ob[3] > bb[3] || ob[4] > bb[4] || ob[5] > bb[5]) {
            return false;
        }
        for (var polygon : other.polygons()) {
            for (var vertex : polygon) {
                if (!isInside(triangles, vertex)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasOtherFaceInside(BrushInfo info, List<double[][]> triangles, Set<Integer> mine) {
        var box = info.box();
        for (var node = 0; node < nodeCount; node++) {
            if (geometry.vertexCount()[node] < 3 || mine.contains(geometry.surface()[node])) {
                continue;
            }
            var center = nodeCenter(node);
            if (!isWithin(box, center[0], center[1], center[2], 0)) {
                continue;
            }
            var p = node * 4;
            var nx = geometry.nodePlanes()[p] * 0.5;
            var ny = geometry.nodePlanes()[p + 1] * 0.5;
            var nz = geometry.nodePlanes()[p + 2] * 0.5;
            if (isInside(triangles, new double[] {center[0] - nx, center[1] - ny, center[2] - nz})
                    && isInside(triangles, new double[] {center[0] + nx, center[1] + ny, center[2] + nz})) {
                return true;
            }
        }
        return false;
    }

    private @Nullable Cap cap(int slot, int surface, int zone, long visibleZones) {
        var node = slot / 2;
        var side = slot % 2;
        var faces = cell(slot);
        if (faces == null) {
            return null;
        }
        var p = node * 4;
        var planes = geometry.nodePlanes();
        var sign = side == 1 ? 1f : -1f;
        var plane = new float[] {
            planes[p] * sign, planes[p + 1] * sign, planes[p + 2] * sign, planes[p + 3] * sign - CAP_MARGIN
        };
        double[][] best = null;
        var bestArea = 0.0;
        for (var face : faces) {
            var onPlane = true;
            for (var vertex : face) {
                if (Math.abs(planes[p] * vertex[0]
                                + planes[p + 1] * vertex[1]
                                + planes[p + 2] * vertex[2]
                                - planes[p + 3])
                        > ON_PLANE) {
                    onPlane = false;
                    break;
                }
            }
            var faceArea = onPlane ? area(face) : 0;
            if (onPlane && faceArea > bestArea) {
                best = face;
                bestArea = faceArea;
            }
        }
        if (best == null || best.length < 3 || best.length > MAX_CAP_VERTICES) {
            return null;
        }
        var center = centroid(faces);
        if (plane[0] * center[0] + plane[1] * center[1] + plane[2] * center[2] - plane[3] <= 0) {
            return null;
        }
        var polygon = new float[best.length * 3];
        for (var i = 0; i < best.length; i++) {
            polygon[i * 3] = (float) (best[i][0] - plane[0] * CAP_MARGIN);
            polygon[i * 3 + 1] = (float) (best[i][1] - plane[1] * CAP_MARGIN);
            polygon[i * 3 + 2] = (float) (best[i][2] - plane[2] * CAP_MARGIN);
        }
        var winding = normal(best);
        if (winding == null) {
            return null;
        }
        var follows = winding[0] * plane[0] + winding[1] * plane[1] + winding[2] * plane[2] > 0;
        if (follows != windsWithPlane) {
            reverse(polygon);
        }
        return new Cap(node, side, plane, polygon, surface, zone, visibleZones);
    }

    private Map<Integer, double[]> solidCells() {
        if (solidCells != null) {
            return solidCells;
        }
        var result = new LinkedHashMap<Integer, double[]>();
        for (var slot = 0; slot < nodeCount * 2; slot++) {
            if (statusA[slot] != 0 && statusB[slot] != 0) {
                continue;
            }
            var faces = cell(slot);
            if (faces == null) {
                continue;
            }
            var center = centroid(faces);
            var e = new double[] {center[0], center[1], center[2], 1e30, 1e30, 1e30, -1e30, -1e30, -1e30};
            for (var face : faces) {
                for (var vertex : face) {
                    for (var axis = 0; axis < 3; axis++) {
                        e[3 + axis] = Math.min(e[3 + axis], vertex[axis]);
                        e[6 + axis] = Math.max(e[6 + axis], vertex[axis]);
                    }
                }
            }
            result.put(slot, e);
        }
        solidCells = result;
        return result;
    }

    private @Nullable List<double[][]> cell(int slot) {
        var path = new IntArrayList();
        path.add(slot);
        var node = slot / 2;
        while (parents[node] >= 0) {
            path.add(parents[node] * 2 + parentSides[node]);
            node = parents[node];
        }
        var faces = box();
        var planes = geometry.nodePlanes();
        for (var i = path.size() - 1; i >= 0; i--) {
            var step = path.get(i);
            var p = step / 2 * 4;
            faces = clip(faces, planes[p], planes[p + 1], planes[p + 2], planes[p + 3], step % 2 == 1);
            if (faces.isEmpty()) {
                return null;
            }
        }
        return faces;
    }

    private int locate(double[] point) {
        var planes = geometry.nodePlanes();
        var node = 0;
        for (var guard = 0; guard <= nodeCount; guard++) {
            var p = node * 4;
            var distance = planes[p] * point[0] + planes[p + 1] * point[1] + planes[p + 2] * point[2] - planes[p + 3];
            var side = distance >= 0 ? 1 : 0;
            var child = side == 1 ? geometry.front()[node] : geometry.back()[node];
            if (child < 0) {
                return node * 2 + side;
            }
            node = child;
        }
        throw new IllegalStateException("The BSP tree has a cycle");
    }

    private double[] nodeCenter(int node) {
        var polygon = nodePolygon(node);
        var center = new double[3];
        for (var vertex : polygon) {
            center[0] += vertex[0];
            center[1] += vertex[1];
            center[2] += vertex[2];
        }
        for (var axis = 0; axis < 3; axis++) {
            center[axis] /= polygon.length;
        }
        return center;
    }

    private double[][] nodePolygon(int node) {
        var count = geometry.vertexCount()[node];
        var pool = geometry.vertexPool()[node];
        var result = new double[count][];
        for (var k = 0; k < count; k++) {
            var point = geometry.vertexPoints()[pool + k] * 3;
            result[k] =
                    new double[] {geometry.points()[point], geometry.points()[point + 1], geometry.points()[point + 2]};
        }
        return result;
    }

    private float[] surfacePlane(int surface) {
        return Arrays.copyOfRange(geometry.surfacePlanes(), surface * 4, surface * 4 + 4);
    }

    private boolean measureWinding() {
        var with = 0;
        var against = 0;
        for (var node = 0; node < nodeCount && with + against < 4096; node++) {
            if (geometry.vertexCount()[node] < 3) {
                continue;
            }
            var winding = normal(nodePolygon(node));
            if (winding == null) {
                continue;
            }
            var p = node * 4;
            var dot = winding[0] * geometry.nodePlanes()[p]
                    + winding[1] * geometry.nodePlanes()[p + 1]
                    + winding[2] * geometry.nodePlanes()[p + 2];
            if (dot > 0) {
                with++;
            } else {
                against++;
            }
        }
        return with >= against;
    }

    private boolean isTarget(List<Cap> caps, int slot) {
        for (var cap : caps) {
            if (cap.node() * 2 + cap.side() == slot) {
                return true;
            }
        }
        return false;
    }

    private LeafLights lists(List<Cap> caps) {
        var saved = savedLists;
        if (caps.isEmpty()) {
            return saved;
        }
        var edited = Flood.of(edited(caps), () -> false);
        var beside = new BitSet();
        for (var leaf = leafZones.length; leaf < leafZones.length + caps.size(); leaf++) {
            for (var neighbor : edited.getNeighbors(leaf)) {
                beside.set(neighbor);
            }
        }
        var before = savedReach();
        var reached = new HashMap<Integer, BitSet>();
        for (var i = 0; i < lights.size(); i++) {
            var light = lights.get(i);
            var old = before.get(i);
            if (!light.sunlight() && old.length > 0 && Arrays.stream(old).noneMatch(beside::get)) {
                continue;
            }
            var now = edited.getLeaves(light);
            if (!Arrays.equals(old, now)) {
                var leaves = new BitSet();
                for (var leaf : now) {
                    leaves.set(leaf);
                }
                reached.put(light.reference(), leaves);
            }
        }
        var count = leafZones.length + caps.size();
        var permeatingRuns = new int[count][];
        var volumetricRuns = new int[count][];
        var permeating = saved.permeating();
        var volumetric = saved.volumetric();
        for (var leaf = 0; leaf < count; leaf++) {
            var old = leaf < leafZones.length;
            permeatingRuns[leaf] =
                    BspLeafLightEdit.editRun(old ? saved.run(permeating[leaf]) : new int[0], leaf, reached, places);
            volumetricRuns[leaf] =
                    BspLeafLightEdit.editRun(old ? saved.run(volumetric[leaf]) : new int[0], leaf, reached, places);
        }
        var slots = new IntArrayList();
        var permeatingStarts = BspLeafLightEdit.formRuns(permeatingRuns, slots);
        var volumetricStarts = BspLeafLightEdit.formRuns(volumetricRuns, slots);
        return new LeafLights(permeatingStarts, volumetricStarts, slots.toArray());
    }

    private List<int[]> savedReach() {
        var reach = savedReach;
        if (reach == null) {
            var flood = Flood.of(geometry, () -> false);
            reach = new ArrayList<>(lights.size());
            for (var light : lights) {
                reach.add(flood.getLeaves(light));
            }
            savedReach = reach;
        }
        return reach;
    }

    private boolean hasSheetOnIt(BrushInfo info, List<double[][]> triangles, Set<Integer> mine) {
        var box = info.box();
        for (var node = 0; node < nodeCount; node++) {
            if (geometry.vertexCount()[node] < 3
                    || (geometry.flags()[node] & NOT_CSG) == 0
                    || mine.contains(geometry.surface()[node])) {
                continue;
            }
            var center = nodeCenter(node);
            if (!isWithin(box, center[0], center[1], center[2], 1)) {
                continue;
            }
            var p = node * 4;
            var nx = geometry.nodePlanes()[p];
            var ny = geometry.nodePlanes()[p + 1];
            var nz = geometry.nodePlanes()[p + 2];
            if (isInside(triangles, new double[] {center[0] - nx, center[1] - ny, center[2] - nz})
                    || isInside(triangles, new double[] {center[0] + nx, center[1] + ny, center[2] + nz})) {
                return true;
            }
        }
        return false;
    }

    private Geometry edited(List<Cap> caps) {
        var count = nodeCount + caps.size();
        var surfaces = surfaceActors.length;
        var nodePlanes = Arrays.copyOf(geometry.nodePlanes(), count * 4);
        var back = Arrays.copyOf(geometry.back(), count);
        var front = Arrays.copyOf(geometry.front(), count);
        var surface = Arrays.copyOf(geometry.surface(), count);
        var vertexPool = Arrays.copyOf(geometry.vertexPool(), count);
        var vertexCount = Arrays.copyOf(geometry.vertexCount(), count);
        var flags = Arrays.copyOf(geometry.flags(), count);
        var leaves = Arrays.copyOf(geometry.savedNodeLeaves(), count * 2);
        var surfacePlanes = Arrays.copyOf(geometry.surfacePlanes(), (surfaces + caps.size()) * 4);
        var surfaceNormals = Arrays.copyOf(geometry.surfaceNormals(), surfaces + caps.size());
        var vectorCount = geometry.vectors().length / 3;
        var vectors = Arrays.copyOf(geometry.vectors(), (vectorCount + caps.size()) * 3);
        var extraPoints = 0;
        for (var cap : caps) {
            extraPoints += cap.polygon().length / 3;
        }
        var pointCount = geometry.points().length / 3;
        var points = Arrays.copyOf(geometry.points(), (pointCount + extraPoints) * 3);
        var vertexPoints = Arrays.copyOf(geometry.vertexPoints(), geometry.vertexPoints().length + extraPoints);
        var leafZonesAfter = Arrays.copyOf(leafZones, leafZones.length + caps.size());
        var point = pointCount;
        var vertex = geometry.vertexPoints().length;
        for (var k = 0; k < caps.size(); k++) {
            var cap = caps.get(k);
            var node = nodeCount + k;
            if (cap.side() == 0) {
                back[cap.node()] = node;
            } else {
                front[cap.node()] = node;
            }
            System.arraycopy(cap.plane(), 0, nodePlanes, node * 4, 4);
            back[node] = -1;
            front[node] = -1;
            surface[node] = surfaces + k;
            vertexPool[node] = vertex;
            vertexCount[node] = cap.polygon().length / 3;
            flags[node] = 0;
            leaves[node * 2] = -1;
            leaves[node * 2 + 1] = leafZones.length + k;
            System.arraycopy(cap.plane(), 0, surfacePlanes, (surfaces + k) * 4, 4);
            surfaceNormals[surfaces + k] = vectorCount + k;
            System.arraycopy(cap.plane(), 0, vectors, (vectorCount + k) * 3, 3);
            for (var i = 0; i < cap.polygon().length / 3; i++) {
                System.arraycopy(cap.polygon(), i * 3, points, point * 3, 3);
                vertexPoints[vertex++] = point++;
            }
            leafZonesAfter[leafZones.length + k] = cap.zone();
        }
        return new Geometry(
                count,
                nodePlanes,
                back,
                front,
                surface,
                vertexPool,
                vertexCount,
                flags,
                leaves,
                surfacePlanes,
                surfaceNormals,
                vectors,
                points,
                vertexPoints,
                leafZonesAfter,
                geometry.rootOutside());
    }

    private byte[] write(
            byte[] input,
            int inputOffset,
            int targetOffset,
            Set<Integer> deleted,
            List<Cap> caps,
            LeafLights lists,
            IntFunction<float @Nullable []> mappingSizes)
            throws IOException {
        var exportFlags = source.exports.get(modelExport - 1).flags();
        var c = new Cursor(input, 0, input.length);
        NativeLevelEditor.skipProperties(source, c, exportFlags);
        c.skip(25 + 16);
        var vectorsStart = c.position;
        var vectorCount = count(c, 12);
        var vectorsAt = c.position;
        c.skip(vectorCount * 12);
        var pointCount = count(c, 12);
        var pointsAt = c.position;
        c.skip(pointCount * 12);
        if (count(c, 60) != nodeCount) {
            throw new IOException("The level model changed before its brushes were deleted");
        }
        var nodeStarts = new int[nodeCount + 1];
        var nodeRecords = new NodeRecord[nodeCount];
        for (var node = 0; node < nodeCount; node++) {
            nodeStarts[node] = c.position;
            nodeRecords[node] = NodeRecord.read(c);
        }
        nodeStarts[nodeCount] = c.position;
        if (count(c, 20) != surfaceActors.length) {
            throw new IOException("The level model changed before its brushes were deleted");
        }
        var surfaceStarts = new int[surfaceActors.length + 1];
        var surfaceRecords = new SurfaceRecord[surfaceActors.length];
        for (var surface = 0; surface < surfaceActors.length; surface++) {
            surfaceStarts[surface] = c.position;
            surfaceRecords[surface] = SurfaceRecord.read(c, input, source.licenseVersion);
        }
        surfaceStarts[surfaceActors.length] = c.position;
        var vertexCount = count(c, 2);
        var vertexAt = c.position;
        for (var i = 0; i < vertexCount; i++) {
            c.readIndex();
            c.readIndex();
        }
        var vertsEnd = c.position;
        c.skip(4);
        var zoneTotal = c.readI32();
        if (zoneTotal < 0 || zoneTotal > 64) {
            throw new IOException("Invalid BSP zone count " + zoneTotal);
        }
        for (var i = 0; i < zoneTotal; i++) {
            c.readIndex();
            c.skip(20);
        }
        c.readIndex();
        c.skip(count(c, 25) * 25);
        c.skip(count(c, 4) * 4);
        var leavesStart = c.position;
        var leafTotal = count(c, 11);
        if (leafTotal != leafZones.length) {
            throw new IOException("The level model changed before its brushes were deleted");
        }
        var savedLeafZones = new int[leafTotal];
        var savedVisible = new long[leafTotal];
        for (var i = 0; i < leafTotal; i++) {
            savedLeafZones[i] = c.readIndex();
            c.readIndex();
            c.readIndex();
            savedVisible[i] = (c.readI32() & 0xffffffffL) | (long) c.readI32() << 32;
        }
        for (var n = count(c, 1); n > 0; n--) {
            c.readIndex();
        }
        var lightsEnd = c.position;
        c.skip(8);
        var sectionTotal = count(c, 18);
        var sections = new ArrayList<SectionRecord>();
        for (var i = 0; i < sectionTotal; i++) {
            sections.add(SectionRecord.read(c));
        }
        var sectionsEnd = c.position;
        var permeating = lists.permeating();
        var volumetric = lists.volumetric();
        if (permeating.length != leafTotal + caps.size()) {
            throw new IOException("The leaf light lists do not have one run for each leaf");
        }

        var capVertices = new int[caps.size()];
        var capSections = new int[caps.size()];
        var capFirsts = new int[caps.size()];
        var extraPoints = 0;
        for (var k = 0; k < caps.size(); k++) {
            var cap = caps.get(k);
            capVertices[k] = vertexCount + extraPoints;
            var size = cap.polygon().length / 3;
            extraPoints += size;
            var material = surfaceRecords[cap.surface()].material;
            var target = -1;
            for (var i = 0; i < sections.size(); i++) {
                var section = sections.get(i);
                if (section.material == material
                        && section.flags == (DELETED_FLAGS & SECTION_FLAGS)
                        && section.lightmap == -1
                        && section.count + size <= SECTION_VERTICES) {
                    target = i;
                    break;
                }
            }
            if (target < 0) {
                sections.add(new SectionRecord(material, DELETED_FLAGS & SECTION_FLAGS, -1));
                target = sections.size() - 1;
            }
            var section = sections.get(target);
            capSections[k] = target;
            capFirsts[k] = section.count;
            section.add(vertices(
                    cap, surfaceRecords[cap.surface()], input, vectorsAt, pointsAt, mappingSizes.apply(cap.surface())));
            section.count += size;
            section.nodes++;
            section.revision++;
        }
        var changedNodes = new HashMap<Integer, NodeRecord>();
        for (var k = 0; k < caps.size(); k++) {
            var cap = caps.get(k);
            var parent = changedNodes.computeIfAbsent(cap.node(), node -> nodeRecords[node].copy());
            if (cap.side() == 0) {
                parent.back = nodeCount + k;
            } else {
                parent.front = nodeCount + k;
            }
            var bit = 1L << cap.zone();
            for (var node = cap.node(); node >= 0; node = parents[node]) {
                changedNodes.computeIfAbsent(node, index -> nodeRecords[index].copy()).zoneMask |= bit;
            }
        }
        var deletedSurfaces = new HashSet<Integer>();
        for (var brush : deleted) {
            for (var surface : getSurfaces(brush)) {
                deletedSurfaces.add(surface);
            }
        }

        var out = new ByteArrayOutputStream(input.length + caps.size() * 600);
        out.write(input, 0, vectorsStart);
        PackageBytes.writeIndex(out, vectorCount + caps.size());
        out.write(input, vectorsAt, vectorCount * 12);
        for (var cap : caps) {
            writeFloats(out, cap.plane(), 3);
        }
        PackageBytes.writeIndex(out, pointCount + extraPoints);
        out.write(input, pointsAt, pointCount * 12);
        for (var cap : caps) {
            writeFloats(out, cap.polygon(), cap.polygon().length);
        }
        PackageBytes.writeIndex(out, nodeCount + caps.size());
        for (var node = 0; node < nodeCount; node++) {
            var changed = changedNodes.get(node);
            if (changed == null) {
                out.write(input, nodeStarts[node], nodeStarts[node + 1] - nodeStarts[node]);
            } else {
                changed.write(out);
            }
        }
        for (var k = 0; k < caps.size(); k++) {
            var cap = caps.get(k);
            var parent = nodeRecords[cap.node()];
            var record = new NodeRecord();
            record.plane = cap.plane().clone();
            record.zoneMask = 1L << cap.zone();
            record.flags = 0;
            record.pool = capVertices[k];
            record.surface = surfaceActors.length + k;
            record.back = -1;
            record.front = -1;
            record.coplanar = -1;
            record.collisionBound = -1;
            record.renderBound = parent.renderBound;
            record.spheres = parent.spheres.clone();
            record.zoneBack = 0;
            record.zoneFront = cap.zone();
            record.count = cap.polygon().length / 3;
            record.leafBack = -1;
            record.leafFront = leafTotal + k;
            record.section = capSections[k];
            record.first = capFirsts[k];
            record.lightmap = -1;
            record.write(out);
        }
        PackageBytes.writeIndex(out, surfaceActors.length + caps.size());
        for (var surface = 0; surface < surfaceActors.length; surface++) {
            if (deletedSurfaces.contains(surface)) {
                var record = surfaceRecords[surface].copy();
                record.flags |= DELETED_FLAGS;
                record.write(out);
            } else {
                out.write(input, surfaceStarts[surface], surfaceStarts[surface + 1] - surfaceStarts[surface]);
            }
        }
        for (var k = 0; k < caps.size(); k++) {
            var cap = caps.get(k);
            var record = surfaceRecords[cap.surface()].copy();
            record.flags = DELETED_FLAGS;
            record.normal = vectorCount + k;
            record.brushPolygon = -1;
            record.plane = cap.plane().clone();
            record.write(out);
        }
        PackageBytes.writeIndex(out, vertexCount + extraPoints);
        out.write(input, vertexAt, vertsEnd - vertexAt);
        var point = pointCount;
        for (var cap : caps) {
            for (var i = 0; i < cap.polygon().length / 3; i++) {
                PackageBytes.writeIndex(out, point++);
                PackageBytes.writeIndex(out, -1);
            }
        }
        out.write(input, vertsEnd, leavesStart - vertsEnd);
        PackageBytes.writeIndex(out, leafTotal + caps.size());
        for (var leaf = 0; leaf < leafTotal + caps.size(); leaf++) {
            var old = leaf < leafTotal;
            var zone = old ? savedLeafZones[leaf] : caps.get(leaf - leafTotal).zone();
            var visible = old ? savedVisible[leaf] : caps.get(leaf - leafTotal).visibleZones();
            PackageBytes.writeIndex(out, zone);
            PackageBytes.writeIndex(out, permeating[leaf]);
            PackageBytes.writeIndex(out, volumetric[leaf]);
            PackageBytes.writeInt(out, (int) visible);
            PackageBytes.writeInt(out, (int) (visible >>> 32));
        }
        var slots = lists.slots();
        PackageBytes.writeIndex(out, slots.length);
        for (var slot : slots) {
            PackageBytes.writeIndex(out, slot);
        }
        out.write(input, lightsEnd, 8);
        PackageBytes.writeIndex(out, sections.size());
        for (var section : sections) {
            section.write(out, input);
        }
        var shift = out.size() - sectionsEnd;
        out.write(input, sectionsEnd, input.length - sectionsEnd);
        var result = out.toByteArray();
        var buffer = PackageBytes.littleEndian(result);
        var inputTail = BspLightmapTail.read(source, input, inputOffset, exportFlags);
        for (var texture : inputTail.textures) {
            for (var variant : texture.variants()) {
                buffer.putInt(
                        variant.fullLazyAt() + shift, variant.fullEndPosition() - inputOffset + targetOffset + shift);
                buffer.putInt(
                        variant.halfLazyAt() + shift, variant.halfEndPosition() - inputOffset + targetOffset + shift);
            }
        }
        return result;
    }

    private static byte[] vertices(
            Cap cap, SurfaceRecord surface, byte[] input, int vectorsAt, int pointsAt, float @Nullable [] sizes) {
        var buffer = PackageBytes.littleEndian(input);
        var u = new float[3];
        var v = new float[3];
        var base = new float[3];
        for (var axis = 0; axis < 3; axis++) {
            u[axis] = buffer.getFloat(vectorsAt + surface.u * 12 + axis * 4);
            v[axis] = buffer.getFloat(vectorsAt + surface.v * 12 + axis * 4);
            base[axis] = buffer.getFloat(pointsAt + surface.base * 12 + axis * 4);
        }
        var count = cap.polygon().length / 3;
        var uv = new float[count * 2];
        if (sizes != null && sizes[0] > 0 && sizes[1] > 0) {
            var uTranslation = u[1] * -base[1] + u[0] * -base[0] + u[2] * -base[2];
            var vTranslation = v[1] * -base[1] + v[0] * -base[0] + v[2] * -base[2];
            var totalU = 0f;
            var totalV = 0f;
            for (var k = 0; k < count; k++) {
                var x = cap.polygon()[k * 3];
                var y = cap.polygon()[k * 3 + 1];
                var z = cap.polygon()[k * 3 + 2];
                uv[k * 2] = (u[1] * y + u[0] * x + u[2] * z + uTranslation) / sizes[0];
                uv[k * 2 + 1] = (v[1] * y + v[0] * x + v[2] * z + vTranslation) / sizes[1];
                totalU += uv[k * 2];
                totalV += uv[k * 2 + 1];
            }
            var centerU = (float) (int) Math.floor((double) (totalU / count) + 0.5);
            var centerV = (float) (int) Math.floor((double) (totalV / count) + 0.5);
            for (var k = 0; k < count; k++) {
                uv[k * 2] -= centerU;
                uv[k * 2 + 1] -= centerV;
            }
        }
        var out = new ByteArrayOutputStream(count * 40);
        for (var k = 0; k < count; k++) {
            writeFloats(out, Arrays.copyOfRange(cap.polygon(), k * 3, k * 3 + 3), 3);
            writeFloats(out, new float[] {uv[k * 2], uv[k * 2 + 1], 0f, 0f}, 4);
            writeFloats(out, cap.plane(), 3);
        }
        return out.toByteArray();
    }

    private static final class NodeRecord {
        float[] plane;
        long zoneMask;
        int flags;
        int pool;
        int surface;
        int back;
        int front;
        int coplanar;
        int collisionBound;
        int renderBound;
        byte[] spheres;
        int zoneBack;
        int zoneFront;
        int count;
        int leafBack;
        int leafFront;
        int section;
        int first;
        int lightmap;

        static NodeRecord read(Cursor c) throws IOException {
            var record = new NodeRecord();
            record.plane = new float[] {f32(c), f32(c), f32(c), f32(c)};
            record.zoneMask = (c.readI32() & 0xffffffffL) | (long) c.readI32() << 32;
            record.flags = c.readU8();
            record.pool = c.readIndex();
            record.surface = c.readIndex();
            record.back = c.readIndex();
            record.front = c.readIndex();
            record.coplanar = c.readIndex();
            record.collisionBound = c.readIndex();
            record.renderBound = c.readIndex();
            record.spheres = Arrays.copyOfRange(c.bytes, c.position, c.position + 32);
            c.skip(32);
            record.zoneBack = c.readIndex();
            record.zoneFront = c.readIndex();
            record.count = c.readU8();
            record.leafBack = c.readI32();
            record.leafFront = c.readI32();
            record.section = c.readI32();
            record.first = c.readI32();
            record.lightmap = c.readI32();
            return record;
        }

        NodeRecord copy() {
            var copy = new NodeRecord();
            copy.plane = plane.clone();
            copy.zoneMask = zoneMask;
            copy.flags = flags;
            copy.pool = pool;
            copy.surface = surface;
            copy.back = back;
            copy.front = front;
            copy.coplanar = coplanar;
            copy.collisionBound = collisionBound;
            copy.renderBound = renderBound;
            copy.spheres = spheres.clone();
            copy.zoneBack = zoneBack;
            copy.zoneFront = zoneFront;
            copy.count = count;
            copy.leafBack = leafBack;
            copy.leafFront = leafFront;
            copy.section = section;
            copy.first = first;
            copy.lightmap = lightmap;
            return copy;
        }

        void write(ByteArrayOutputStream out) {
            writeFloats(out, plane, 4);
            PackageBytes.writeInt(out, (int) zoneMask);
            PackageBytes.writeInt(out, (int) (zoneMask >>> 32));
            out.write(flags);
            PackageBytes.writeIndex(out, pool);
            PackageBytes.writeIndex(out, surface);
            PackageBytes.writeIndex(out, back);
            PackageBytes.writeIndex(out, front);
            PackageBytes.writeIndex(out, coplanar);
            PackageBytes.writeIndex(out, collisionBound);
            PackageBytes.writeIndex(out, renderBound);
            out.writeBytes(spheres);
            PackageBytes.writeIndex(out, zoneBack);
            PackageBytes.writeIndex(out, zoneFront);
            out.write(count);
            PackageBytes.writeInt(out, leafBack);
            PackageBytes.writeInt(out, leafFront);
            PackageBytes.writeInt(out, section);
            PackageBytes.writeInt(out, first);
            PackageBytes.writeInt(out, lightmap);
        }
    }

    private static final class SurfaceRecord {
        int material;
        int flags;
        int base;
        int normal;
        int u;
        int v;
        int brushPolygon;
        int actor;
        float[] plane;
        byte[] rest;

        static SurfaceRecord read(Cursor c, byte[] input, int licenseVersion) throws IOException {
            var record = new SurfaceRecord();
            record.material = c.readIndex();
            record.flags = c.readI32();
            record.base = c.readIndex();
            record.normal = c.readIndex();
            record.u = c.readIndex();
            record.v = c.readIndex();
            record.brushPolygon = c.readIndex();
            record.actor = c.readIndex();
            record.plane = new float[] {f32(c), f32(c), f32(c), f32(c)};
            var rest = licenseVersion > 20 ? 8 : 4;
            record.rest = Arrays.copyOfRange(input, c.position, c.position + rest);
            c.skip(rest);
            return record;
        }

        SurfaceRecord copy() {
            var copy = new SurfaceRecord();
            copy.material = material;
            copy.flags = flags;
            copy.base = base;
            copy.normal = normal;
            copy.u = u;
            copy.v = v;
            copy.brushPolygon = brushPolygon;
            copy.actor = actor;
            copy.plane = plane.clone();
            copy.rest = rest.clone();
            return copy;
        }

        void write(ByteArrayOutputStream out) {
            PackageBytes.writeIndex(out, material);
            PackageBytes.writeInt(out, flags);
            PackageBytes.writeIndex(out, base);
            PackageBytes.writeIndex(out, normal);
            PackageBytes.writeIndex(out, u);
            PackageBytes.writeIndex(out, v);
            PackageBytes.writeIndex(out, brushPolygon);
            PackageBytes.writeIndex(out, actor);
            writeFloats(out, plane, 4);
            out.writeBytes(rest);
        }
    }

    private static final class SectionRecord {
        final int start;
        final int end;
        final int vertices;
        final int savedCount;
        final int material;
        final int flags;
        final int lightmap;
        int count;
        int revision;
        int nodes;
        final ByteArrayOutputStream added = new ByteArrayOutputStream();

        SectionRecord(int material, int flags, int lightmap) {
            this(-1, -1, -1, 0, material, flags, lightmap, 0, 0);
        }

        SectionRecord(
                int start,
                int end,
                int vertices,
                int savedCount,
                int material,
                int flags,
                int lightmap,
                int revision,
                int nodes) {
            this.start = start;
            this.end = end;
            this.vertices = vertices;
            this.savedCount = savedCount;
            this.material = material;
            this.flags = flags;
            this.lightmap = lightmap;
            this.count = savedCount;
            this.revision = revision;
            this.nodes = nodes;
        }

        static SectionRecord read(Cursor c) throws IOException {
            var start = c.position;
            var count = count(c, 40);
            var vertices = c.position;
            c.skip(count * 40);
            var revision = c.readI32();
            var material = c.readIndex();
            var nodes = c.readI32();
            var flags = c.readI32();
            var lightmap = c.readI32();
            return new SectionRecord(start, c.position, vertices, count, material, flags, lightmap, revision, nodes);
        }

        void add(byte[] bytes) {
            added.writeBytes(bytes);
        }

        void write(ByteArrayOutputStream out, byte[] input) {
            if (added.size() == 0 && start >= 0) {
                out.write(input, start, end - start);
                return;
            }
            PackageBytes.writeIndex(out, count);
            if (start >= 0) {
                out.write(input, vertices, savedCount * 40);
            }
            out.writeBytes(added.toByteArray());
            PackageBytes.writeInt(out, revision);
            PackageBytes.writeIndex(out, material);
            PackageBytes.writeInt(out, nodes);
            PackageBytes.writeInt(out, flags);
            PackageBytes.writeInt(out, lightmap);
        }
    }

    private static Analysis refused(String reason) {
        return new Analysis(reason, List.of());
    }

    private static String touching() {
        return "It touches other solid: deleting it would leave a hole in what it stands on or leans against";
    }

    private static byte[][] status(int[] counts, int[] flags, boolean rootOutside, int[] back, int[] front) {
        var n = counts.length;
        var a = new byte[2 * n];
        var b = new byte[2 * n];
        Arrays.fill(a, (byte) -1);
        Arrays.fill(b, (byte) -1);
        if (n == 0) {
            return new byte[][] {a, b};
        }
        var stack = new IntArrayList();
        stack.add(0);
        stack.add(rootOutside ? 1 : 0);
        stack.add(rootOutside ? 1 : 0);
        var guard = 0;
        while (!stack.isEmpty()) {
            if (++guard > 4 * n + 4) {
                throw new IllegalStateException("The BSP tree has a cycle");
            }
            var ob = stack.removeAtIndex(stack.size() - 1);
            var oa = stack.removeAtIndex(stack.size() - 1);
            var node = stack.removeAtIndex(stack.size() - 1);
            var notCsg = (flags[node] & NOT_CSG) != 0;
            var csgA = counts[node] > 0 && (flags[node] & NOT_SOLID_NODE) == 0;
            var csgB = !notCsg;
            var fa = oa == 1 || csgA ? 1 : 0;
            var ba = oa == 1 && !csgA ? 1 : 0;
            var fb = ob == 1 || csgB ? 1 : 0;
            var bb = ob == 1 && !csgB ? 1 : 0;
            if (front[node] >= 0) {
                stack.add(front[node]);
                stack.add(fa);
                stack.add(fb);
            } else {
                a[2 * node + 1] = (byte) fa;
                b[2 * node + 1] = (byte) fb;
            }
            if (back[node] >= 0) {
                stack.add(back[node]);
                stack.add(ba);
                stack.add(bb);
            } else {
                a[2 * node] = (byte) ba;
                b[2 * node] = (byte) bb;
            }
        }
        return new byte[][] {a, b};
    }

    private static Map<Integer, BrushInfo> readBrushes(UArchive archive, Set<Integer> inLevel) {
        var result = new TreeMap<Integer, BrushInfo>();
        var exports = archive.getExportMap();
        for (var i = 0; i < exports.size(); i++) {
            var export = exports.get(i);
            if (!"Brush".equals(export.getClassName())) {
                continue;
            }
            var index = i + 1;
            var csgOper = 0;
            var polyFlags = 0;
            for (var property : archive.getObjectLoader().exportProperties(export)) {
                switch (property.getName()) {
                    case "CsgOper" -> csgOper = property.getUInt8Value();
                    case "PolyFlags" -> polyFlags = property.getInt32Value();
                    default -> {}
                }
            }
            var polygons = new ArrayList<double[][]>();
            var box = new double[] {1e30, 1e30, 1e30, -1e30, -1e30, -1e30};
            var deleteMe = false;
            try {
                if (archive.getObjectLoader().loadObject(index) instanceof BrushActor actor) {
                    deleteMe = actor.isDeleteMe();
                    for (var world : BspBrushShapes.of(actor).polygons()) {
                        for (var point : world) {
                            for (var axis = 0; axis < 3; axis++) {
                                box[axis] = Math.min(box[axis], point[axis]);
                                box[axis + 3] = Math.max(box[axis + 3], point[axis]);
                            }
                        }
                        polygons.add(world);
                    }
                }
            } catch (RuntimeException unreadable) {
                polygons.clear();
            }
            var name = archive.getObjectLoader().peekObjectFullName(index);
            var shortName = name == null ? "Brush" + index : name.substring(name.lastIndexOf('.') + 1);
            result.put(
                    index,
                    new BrushInfo(
                            index, shortName, csgOper, polyFlags, inLevel.contains(index), deleteMe, polygons, box));
        }
        return result;
    }

    private static Set<Integer> readSkyZones(UArchive archive, UModel model) {
        var result = new HashSet<Integer>();
        var zones = model.getZones();
        var exports = archive.getExportMap();
        var named = new HashSet<Integer>();
        for (var zone : zones) {
            var actor = zone.getZoneActor().getIndex();
            if (actor <= 0 || actor > exports.size()) {
                continue;
            }
            try {
                for (var property : archive.getObjectLoader().exportProperties(exports.get(actor - 1))) {
                    if (property.getName().equals("SkyZone") && property.getIndexValue() > 0) {
                        named.add(property.getIndexValue());
                    }
                }
            } catch (RuntimeException unreadable) {
            }
        }
        for (var i = 0; i < zones.length; i++) {
            var actor = zones[i].getZoneActor().getIndex();
            if (actor <= 0 || actor > exports.size()) {
                continue;
            }
            var className = exports.get(actor - 1).getClassName().toLowerCase(java.util.Locale.ROOT);
            if (className.contains("skyzone") || named.contains(actor)) {
                result.add(i);
            }
        }
        return result;
    }

    private static List<Placed> readPlaced(UArchive archive, List<Integer> actors) {
        var result = new ArrayList<Placed>();
        var exports = archive.getExportMap();
        for (var reference : actors) {
            if (reference <= 0 || reference > exports.size()) {
                continue;
            }
            var export = exports.get(reference - 1);
            if ("Brush".equals(export.getClassName())) {
                continue;
            }
            try {
                for (var property : archive.getObjectLoader().exportProperties(export)) {
                    if (property.getName().equals("Location") && property.getVectorValue() != null) {
                        var location = property.getVectorValue();
                        var name = archive.getObjectLoader().peekObjectFullName(reference);
                        result.add(new Placed(
                                name == null ? export.getClassName() : name.substring(name.lastIndexOf('.') + 1),
                                location.getX(),
                                location.getY(),
                                location.getZ()));
                    }
                }
            } catch (RuntimeException unreadable) {
            }
        }
        return result;
    }

    private static int count(Cursor c, int minimumBytes) throws IOException {
        var value = c.readIndex();
        if (value < 0 || (long) value * minimumBytes > c.end - c.position) {
            throw new IOException("Invalid BSP array count " + value);
        }
        return value;
    }

    private static float f32(Cursor c) throws IOException {
        return Float.intBitsToFloat(c.readI32());
    }

    private static void writeFloats(ByteArrayOutputStream out, float[] values, int count) {
        for (var i = 0; i < count; i++) {
            PackageBytes.writeInt(out, Float.floatToRawIntBits(values[i]));
        }
    }

    private static void reverse(float[] polygon) {
        var count = polygon.length / 3;
        for (var i = 0; i < count / 2; i++) {
            var j = count - 1 - i;
            for (var axis = 0; axis < 3; axis++) {
                var swap = polygon[i * 3 + axis];
                polygon[i * 3 + axis] = polygon[j * 3 + axis];
                polygon[j * 3 + axis] = swap;
            }
        }
    }

    private static boolean isWithin(double[] box, double x, double y, double z, double margin) {
        return x >= box[0] - margin
                && y >= box[1] - margin
                && z >= box[2] - margin
                && x <= box[3] + margin
                && y <= box[4] + margin
                && z <= box[5] + margin;
    }

    private static List<double[][]> box() {
        var r = WORLD;
        double[][] corners = {
            {-r, -r, -r}, {r, -r, -r}, {r, r, -r}, {-r, r, -r}, {-r, -r, r}, {r, -r, r}, {r, r, r}, {-r, r, r}
        };
        int[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
        var result = new ArrayList<double[][]>();
        for (var face : faces) {
            var polygon = new double[4][];
            for (var i = 0; i < 4; i++) {
                polygon[i] = corners[face[i]].clone();
            }
            result.add(polygon);
        }
        return result;
    }

    private static List<double[][]> clip(
            List<double[][]> faces, double nx, double ny, double nz, double w, boolean front) {
        var s = front ? 1.0 : -1.0;
        var result = new ArrayList<double[][]>();
        var cut = new ArrayList<double[]>();
        for (var face : faces) {
            var kept = new ArrayList<double[]>();
            var m = face.length;
            for (var i = 0; i < m; i++) {
                var a = face[i];
                var b = face[(i + 1) % m];
                var da = s * (nx * a[0] + ny * a[1] + nz * a[2] - w);
                var db = s * (nx * b[0] + ny * b[1] + nz * b[2] - w);
                if (da >= 0) {
                    kept.add(a);
                    if (da < 1e-7) {
                        cut.add(a);
                    }
                }
                if ((da >= 0) != (db >= 0)) {
                    var t = da / (da - db);
                    var p = new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
                    kept.add(p);
                    cut.add(p);
                }
            }
            if (kept.size() >= 3) {
                result.add(kept.toArray(new double[0][]));
            }
        }
        if (result.isEmpty()) {
            return result;
        }
        var unique = new ArrayList<double[]>();
        for (var p : cut) {
            var duplicate = false;
            for (var q : unique) {
                if (Math.abs(p[0] - q[0]) < 1e-4 && Math.abs(p[1] - q[1]) < 1e-4 && Math.abs(p[2] - q[2]) < 1e-4) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(p);
            }
        }
        if (unique.size() >= 3) {
            var cx = 0.0;
            var cy = 0.0;
            var cz = 0.0;
            for (var p : unique) {
                cx += p[0];
                cy += p[1];
                cz += p[2];
            }
            cx /= unique.size();
            cy /= unique.size();
            cz /= unique.size();
            var n = new double[] {nx, ny, nz};
            var u = Math.abs(nx) < 0.9 ? cross(n, new double[] {1, 0, 0}) : cross(n, new double[] {0, 1, 0});
            var v = cross(n, u);
            final var fx = cx;
            final var fy = cy;
            final var fz = cz;
            unique.sort((p, q) -> Double.compare(
                    Math.atan2(
                            (p[0] - fx) * v[0] + (p[1] - fy) * v[1] + (p[2] - fz) * v[2],
                            (p[0] - fx) * u[0] + (p[1] - fy) * u[1] + (p[2] - fz) * u[2]),
                    Math.atan2(
                            (q[0] - fx) * v[0] + (q[1] - fy) * v[1] + (q[2] - fz) * v[2],
                            (q[0] - fx) * u[0] + (q[1] - fy) * u[1] + (q[2] - fz) * u[2])));
            result.add(unique.toArray(new double[0][]));
        }
        return result;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] centroid(List<double[][]> faces) {
        var sum = new double[3];
        var count = 0;
        for (var face : faces) {
            for (var p : face) {
                sum[0] += p[0];
                sum[1] += p[1];
                sum[2] += p[2];
                count++;
            }
        }
        return new double[] {sum[0] / count, sum[1] / count, sum[2] / count};
    }

    private static double area(double[][] polygon) {
        var sum = areaVector(polygon);
        return 0.5 * Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
    }

    private static double @Nullable [] normal(double[][] polygon) {
        var sum = areaVector(polygon);
        var length = Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
        return length < 1e-9 ? null : new double[] {sum[0] / length, sum[1] / length, sum[2] / length};
    }

    private static double[] areaVector(double[][] polygon) {
        var sum = new double[3];
        for (var i = 1; i + 1 < polygon.length; i++) {
            var a = new double[] {
                polygon[i][0] - polygon[0][0], polygon[i][1] - polygon[0][1], polygon[i][2] - polygon[0][2]
            };
            var b = new double[] {
                polygon[i + 1][0] - polygon[0][0], polygon[i + 1][1] - polygon[0][1], polygon[i + 1][2] - polygon[0][2]
            };
            var c = cross(a, b);
            sum[0] += c[0];
            sum[1] += c[1];
            sum[2] += c[2];
        }
        return sum;
    }

    private static List<double[][]> triangles(List<double[][]> polygons) {
        var result = new ArrayList<double[][]>();
        for (var p : polygons) {
            for (var i = 1; i + 1 < p.length; i++) {
                result.add(new double[][] {p[0], p[i], p[i + 1]});
            }
        }
        return result;
    }

    private static boolean isInside(List<double[][]> triangles, double[] origin) {
        var dx = 0.3012;
        var dy = 0.5213;
        var dz = 0.7983;
        var crossings = 0;
        for (var t : triangles) {
            var e1 = new double[] {t[1][0] - t[0][0], t[1][1] - t[0][1], t[1][2] - t[0][2]};
            var e2 = new double[] {t[2][0] - t[0][0], t[2][1] - t[0][1], t[2][2] - t[0][2]};
            var pv = cross(new double[] {dx, dy, dz}, e2);
            var det = e1[0] * pv[0] + e1[1] * pv[1] + e1[2] * pv[2];
            if (Math.abs(det) < 1e-12) {
                continue;
            }
            var inverse = 1 / det;
            var tv = new double[] {origin[0] - t[0][0], origin[1] - t[0][1], origin[2] - t[0][2]};
            var u = (tv[0] * pv[0] + tv[1] * pv[1] + tv[2] * pv[2]) * inverse;
            if (u < 0 || u > 1) {
                continue;
            }
            var qv = cross(tv, e1);
            var v = (dx * qv[0] + dy * qv[1] + dz * qv[2]) * inverse;
            if (v < 0 || u + v > 1) {
                continue;
            }
            var distance = (e2[0] * qv[0] + e2[1] * qv[1] + e2[2] * qv[2]) * inverse;
            if (distance > 1e-6) {
                crossings++;
            }
        }
        return (crossings & 1) == 1;
    }
}
