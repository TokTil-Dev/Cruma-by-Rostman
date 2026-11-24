package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.jetbrains.annotations.Nullable;

final class BspSurfaceEdit {
    static final int INVISIBLE = 0x00000001;
    static final int TWO_SIDED = 0x00000100;
    static final int UNLIT = 0x00400000;
    static final int EDITABLE_FLAGS = TWO_SIDED | UNLIT;

    private static final int WATER = 0x00080000;
    private static final int MIRRORED = 0x20000000;
    private static final int SECTION_KEY = 0x02400100;
    private static final int FAKE_BACKDROP = 0x00000080;
    private static final int NOT_DRAWN = 0x00002000;
    private static final int PORTAL = 0x04000000;
    private static final int ANTI_PORTALS = 0x08000000 | 0x00000400;
    private static final int NOT_CSG = 0x01;
    private static final float SAME_PLANE = 0.25f;
    private static final int SECTION_VERTICES = 0xfffe;
    private static final int VERTEX_U = 12;
    private static final int VERTEX_V = 16;
    private static final float SAVED_UV_TOLERANCE = 1e-3f;
    private static final int MAX_MAPPING_SIZE = 65536;

    final int modelExport;

    private final PackageBytes source;
    private final int flags;
    private final int serialOffset;
    private final byte[] payload;
    private final Layout layout;
    private final float[] vectors;
    private final float[] points;
    private final Surface[] surfaces;
    private final Node[] nodes;
    private final int[] verts;
    private final int lightmapCount;
    private final int[][] surfaceNodes;
    private final int[][] vertexNodes;
    private final float[][] mappingSizes;
    private final boolean[] shared;
    private @Nullable BspSurfaceSelect select;

    record Material(int reference, int uSize, int vSize) {}

    record Edits(
            Map<Integer, BspSurfaceMapping> mappings,
            Set<Integer> hidden,
            Map<Integer, Material> materials,
            Map<Integer, Integer> flags) {
        static final Edits NONE = new Edits(Map.of(), Set.of(), Map.of(), Map.of());

        Edits {
            mappings = Collections.unmodifiableMap(new TreeMap<>(mappings));
            hidden = Collections.unmodifiableSet(new TreeSet<>(hidden));
            materials = Collections.unmodifiableMap(new TreeMap<>(materials));
            var editable = new TreeMap<Integer, Integer>();
            flags.forEach((surface, value) -> editable.put(surface, value & EDITABLE_FLAGS));
            flags = Collections.unmodifiableMap(editable);
        }

        Edits(Map<Integer, BspSurfaceMapping> mappings, Set<Integer> hidden, Map<Integer, Material> materials) {
            this(mappings, hidden, materials, Map.of());
        }

        static Edits ofMappings(Map<Integer, BspSurfaceMapping> mappings) {
            return new Edits(mappings, Set.of(), Map.of(), Map.of());
        }

        boolean isEmpty() {
            return mappings.isEmpty() && hidden.isEmpty() && materials.isEmpty() && flags.isEmpty();
        }

        Set<Integer> surfaces() {
            var result = new TreeSet<Integer>(mappings.keySet());
            result.addAll(hidden);
            result.addAll(materials.keySet());
            result.addAll(flags.keySet());
            return result;
        }

        Set<Integer> moved() {
            var result = new TreeSet<Integer>(materials.keySet());
            result.addAll(flags.keySet());
            return result;
        }
    }

    private record Surface(
            int start,
            int flagsAt,
            int indicesStart,
            int restStart,
            int end,
            int material,
            int polyFlags,
            int base,
            int normal,
            int u,
            int v,
            int brushPolygon,
            int actor) {}

    private record Node(
            float px,
            float py,
            float pz,
            float pw,
            int flags,
            int pool,
            int surface,
            int zoneBack,
            int zoneFront,
            int count,
            int sectionAt,
            int section,
            int first,
            int lightmap) {}

    private record SectionRecord(
            int start,
            int vertices,
            int count,
            int revision,
            int material,
            int nodes,
            int flags,
            int lightmap,
            int end) {}

    private record Layout(
            int vectors,
            int vectorData,
            int points,
            int pointData,
            int nodes,
            int surfaces,
            int[] surfaceStarts,
            int verts,
            int sections,
            SectionRecord[] sectionRecords,
            int sectionsEnd) {}

    private record Plan(byte[] sections, int[] nodeSections, int[] nodeFirsts) {}

    private static final class PlannedSection {
        final int original;
        final int material;
        final int flags;
        final int lightmap;
        int revision;
        int nodes;
        ByteArrayOutputStream vertices = new ByteArrayOutputStream();
        int count;
        boolean changed;

        PlannedSection(int original, int material, int flags, int lightmap, int revision, int nodes) {
            this.original = original;
            this.material = material;
            this.flags = flags;
            this.lightmap = lightmap;
            this.revision = revision;
            this.nodes = nodes;
        }
    }

    private BspSurfaceEdit(
            int modelExport,
            PackageBytes source,
            int flags,
            int serialOffset,
            byte[] payload,
            Layout layout,
            float[] vectors,
            float[] points,
            Surface[] surfaces,
            Node[] nodes,
            int[] verts,
            int lightmapCount) {
        this.modelExport = modelExport;
        this.source = source;
        this.flags = flags;
        this.serialOffset = serialOffset;
        this.payload = payload;
        this.layout = layout;
        this.vectors = vectors;
        this.points = points;
        this.surfaces = surfaces;
        this.nodes = nodes;
        this.verts = verts;
        this.lightmapCount = lightmapCount;
        var polygons = new ArrayList<List<Integer>>();
        var withVertices = new ArrayList<List<Integer>>();
        for (var i = 0; i < surfaces.length; i++) {
            polygons.add(new ArrayList<>());
            withVertices.add(new ArrayList<>());
        }
        for (var i = 0; i < nodes.length; i++) {
            if (nodes[i].count > 0 && nodes[i].surface >= 0 && nodes[i].surface < surfaces.length) {
                withVertices.get(nodes[i].surface).add(i);
                if (nodes[i].count >= 3) {
                    polygons.get(nodes[i].surface).add(i);
                }
            }
        }
        surfaceNodes = new int[surfaces.length][];
        vertexNodes = new int[surfaces.length][];
        mappingSizes = new float[surfaces.length][];
        for (var i = 0; i < surfaces.length; i++) {
            surfaceNodes[i] =
                    polygons.get(i).stream().mapToInt(Integer::intValue).toArray();
            vertexNodes[i] =
                    withVertices.get(i).stream().mapToInt(Integer::intValue).toArray();
            mappingSizes[i] = savedMappingSizes(i);
        }
        shared = new boolean[surfaces.length];
        var owners = new HashMap<Long, Integer>();
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (node.count == 0
                    || node.surface < 0
                    || node.surface >= surfaces.length
                    || !hasSavedVertices(node, layout)) {
                continue;
            }
            for (var k = 0; k < node.count; k++) {
                var other = owners.putIfAbsent((long) node.section << 32 | node.first + k, i);
                if (other != null) {
                    shared[node.surface] = true;
                    shared[nodes[other].surface] = true;
                }
            }
        }
    }

    static BspSurfaceEdit open(Path map, PackageBytes source) throws IOException {
        var directory = map.toAbsolutePath().getParent();
        int modelExport;
        try (var archive = new UArchive(
                source.packageName,
                ByteBuffer.wrap(source.body.clone()).order(ByteOrder.LITTLE_ENDIAN),
                new UArchiveLoader(directory, List.of()))) {
            var levels = new ArrayList<ULevel>();
            archive.loadObjects("Level", levels);
            if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
                throw new IOException("The map has no level model");
            }
            modelExport = levels.getFirst().getModel().getIndex();
        } catch (RuntimeException unreadable) {
            throw new IOException(
                    unreadable.getMessage() == null ? unreadable.toString() : unreadable.getMessage(), unreadable);
        }
        return open(source, modelExport);
    }

    static BspSurfaceEdit open(PackageBytes source, int modelExport) throws IOException {
        if (modelExport <= 0 || modelExport > source.exports.size()) {
            throw new IOException("The level model is not an export of this map");
        }
        if (source.fileVersion < 123 || source.fileVersion > 133) {
            throw new IOException("BSP surfaces of package version " + source.fileVersion + " cannot be written");
        }
        var entry = source.exports.get(modelExport - 1);
        var payload = Arrays.copyOfRange(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        var tail = BspLightmapTail.read(source, payload, entry.serialOffset(), entry.flags());
        var layout = layout(source, payload, entry.flags());
        var c = new Cursor(payload, layout.vectors, payload.length);
        var vectors = floats(c, 3 * c.readIndex());
        var points = floats(c, 3 * c.readIndex());
        var nodes = new Node[c.readIndex()];
        for (var i = 0; i < nodes.length; i++) {
            var px = f32(c);
            var py = f32(c);
            var pz = f32(c);
            var pw = f32(c);
            c.skip(8);
            var nodeFlags = c.readU8();
            var pool = c.readIndex();
            var surface = c.readIndex();
            for (var k = 0; k < 5; k++) {
                c.readIndex();
            }
            c.skip(32);
            var zoneBack = c.readIndex();
            var zoneFront = c.readIndex();
            var count = c.readU8();
            c.skip(8);
            var sectionAt = c.position;
            var section = c.readI32();
            var first = c.readI32();
            var lightmap = c.readI32();
            nodes[i] = new Node(
                    px, py, pz, pw, nodeFlags, pool, surface, zoneBack, zoneFront, count, sectionAt, section, first,
                    lightmap);
        }
        var surfaces = new Surface[c.readIndex()];
        for (var i = 0; i < surfaces.length; i++) {
            var start = c.position;
            var material = c.readIndex();
            var flagsAt = c.position;
            var polyFlags = c.readI32();
            var indicesStart = c.position;
            var base = c.readIndex();
            var normal = c.readIndex();
            var u = c.readIndex();
            var v = c.readIndex();
            var restStart = c.position;
            var brushPolygon = c.readIndex();
            var actor = c.readIndex();
            c.skip(16 + 4);
            if (source.licenseVersion > 20) {
                c.skip(4);
            }
            surfaces[i] = new Surface(
                    start,
                    flagsAt,
                    indicesStart,
                    restStart,
                    c.position,
                    material,
                    polyFlags,
                    base,
                    normal,
                    u,
                    v,
                    brushPolygon,
                    actor);
        }
        if (c.position != layout.verts) {
            throw new IOException("The BSP surfaces do not read the same twice");
        }
        var verts = new int[c.readIndex()];
        for (var i = 0; i < verts.length; i++) {
            verts[i] = c.readIndex();
            c.readIndex();
        }
        for (var surface : surfaces) {
            if (!isValidIndex(surface.base, points)
                    || !isValidIndex(surface.normal, vectors)
                    || !isValidIndex(surface.u, vectors)
                    || !isValidIndex(surface.v, vectors)) {
                throw new IOException("A BSP surface refers to a missing point or vector");
            }
        }
        for (var node : nodes) {
            if (node.count > 0 && (node.pool < 0 || node.pool + node.count > verts.length)) {
                throw new IOException("A BSP node refers to missing vertices");
            }
            for (var k = 0; k < node.count; k++) {
                if (!isValidIndex(verts[node.pool + k], points)) {
                    throw new IOException("A BSP vertex refers to a missing point");
                }
            }
        }
        return new BspSurfaceEdit(
                modelExport,
                source,
                entry.flags(),
                entry.serialOffset(),
                payload,
                layout,
                vectors,
                points,
                surfaces,
                nodes,
                verts,
                tail.lightmaps.size());
    }

    static String getMaterialRestriction(Material material) {
        if (material.reference() == 0) {
            return "A surface needs a material";
        }
        if (material.uSize() <= 0 || material.vSize() <= 0) {
            return "This material has no size, so the game could not lay it on a surface";
        }
        if (material.uSize() > MAX_MAPPING_SIZE || material.vSize() > MAX_MAPPING_SIZE) {
            return "This material is too large for a surface";
        }
        return "";
    }

    int getSurfaceCount() {
        return surfaces.length;
    }

    BspSurfaceMapping getMapping(int surface) {
        var s = surfaces[surface];
        return new BspSurfaceMapping(point(s.base), vector(s.u), vector(s.v));
    }

    Vector3 getNormal(int surface) {
        return vector(surfaces[surface].normal);
    }

    int getMaterial(int surface) {
        return surfaces[surface].material;
    }

    int getActor(int surface) {
        return surfaces[surface].actor;
    }

    int getBrushPolygon(int surface) {
        return surfaces[surface].brushPolygon;
    }

    int getPolyFlags(int surface) {
        return surfaces[surface].polyFlags;
    }

    float @Nullable [] getMappingSizes(int surface) {
        var sizes = mappingSizes[surface];
        return sizes == null || sizes[0] == 1 && sizes[1] == 1 ? null : sizes.clone();
    }

    String getRestriction(int surface) {
        if (surface < 0 || surface >= surfaces.length) {
            return "Unknown BSP surface " + surface;
        }
        if (surfaceNodes[surface].length == 0) {
            return "This surface has no polygons";
        }
        var polyFlags = surfaces[surface].polyFlags;
        if ((polyFlags & WATER) != 0) {
            return "Water is drawn by its own rules, so its texture stays as saved";
        }
        if ((polyFlags & FAKE_BACKDROP) != 0) {
            return "This surface shows the sky, not a texture";
        }
        if (mappingSizes[surface] == null) {
            return "The saved render data of this surface does not match its mapping";
        }
        if (shared[surface]) {
            return "This surface shares its saved render data with another polygon";
        }
        return "";
    }

    String getHideRestriction(int surface) {
        if (surface < 0 || surface >= surfaces.length) {
            return "Unknown BSP surface " + surface;
        }
        if (surfaceNodes[surface].length == 0) {
            return "This surface has no polygons";
        }
        var polyFlags = surfaces[surface].polyFlags;
        if ((polyFlags & WATER) != 0) {
            return "Water is drawn by its own rules, so it stays as saved";
        }
        if ((polyFlags & FAKE_BACKDROP) != 0) {
            return "This surface shows the sky, so it stays as saved";
        }
        if ((polyFlags & (INVISIBLE | NOT_DRAWN)) != 0) {
            return "The client already does not draw this surface";
        }
        if ((polyFlags & ANTI_PORTALS) != 0) {
            return "This surface is also an antiportal: the game hides what lies behind it, also when it is"
                    + " invisible, so it stays as saved";
        }
        return "";
    }

    boolean isSheet(int surface) {
        if (surface < 0 || surface >= surfaces.length || vertexNodes[surface].length == 0) {
            return false;
        }
        for (var index : vertexNodes[surface]) {
            if ((nodes[index].flags & NOT_CSG) == 0) {
                return false;
            }
        }
        return true;
    }

    String getDeleteRestriction(int surface) {
        if (surface >= 0 && surface < surfaces.length && (surfaces[surface].polyFlags & PORTAL) != 0) {
            return "This is a zone portal, which cuts the level into zones, so it stays";
        }
        var restriction = getHideRestriction(surface);
        if (!restriction.isEmpty()) {
            return restriction;
        }
        if (!isSheet(surface)) {
            return "This is the side of a solid wall or floor: the solid stays and still blocks, so it cannot be"
                    + " deleted. Hide only makes it invisible";
        }
        return "";
    }

    String getFlagRestriction(int surface, int flag, boolean on) {
        if (flag != TWO_SIDED && flag != UNLIT) {
            throw new IllegalArgumentException("Only Two-sided and Unlit can change");
        }
        if (surface < 0 || surface >= surfaces.length) {
            return "Unknown BSP surface " + surface;
        }
        var polyFlags = surfaces[surface].polyFlags;
        if (((polyFlags & flag) != 0) == on) {
            return "";
        }
        if (surfaceNodes[surface].length == 0) {
            return "This surface has no polygons";
        }
        if ((polyFlags & WATER) != 0) {
            return "Water is drawn by its own rules, so its flags stay as saved";
        }
        if ((polyFlags & FAKE_BACKDROP) != 0) {
            return "This surface shows the sky, so its flags stay as saved";
        }
        if ((polyFlags & (INVISIBLE | NOT_DRAWN)) != 0) {
            return "The client does not draw this surface, so a flag would change nothing";
        }
        if ((polyFlags & MIRRORED) != 0) {
            return "This surface is a mirror, which the game draws by rules of its own, so its flags stay as saved";
        }
        if (mappingSizes[surface] == null) {
            return "The saved render data of this surface does not match its mapping";
        }
        if (shared[surface]) {
            return "This surface shares its saved render data with another polygon";
        }
        if (flag == UNLIT && !on && !isLightmapped(surface)) {
            return "The client saves this surface as unlit, so it has no lightmap, and the game could not light it";
        }
        return "";
    }

    List<float[]> getPolygons(int surface) {
        var result = new ArrayList<float[]>();
        for (var index : surfaceNodes[surface]) {
            var node = nodes[index];
            var polygon = new float[node.count * 3];
            for (var k = 0; k < node.count; k++) {
                var p = verts[node.pool + k] * 3;
                polygon[k * 3] = points[p];
                polygon[k * 3 + 1] = points[p + 1];
                polygon[k * 3 + 2] = points[p + 2];
            }
            result.add(polygon);
        }
        return result;
    }

    int[] getOutline(int surface) {
        var counts = new LongIntHashMap();
        for (var index : surfaceNodes[surface]) {
            var node = nodes[index];
            for (var k = 0; k < node.count; k++) {
                var a = verts[node.pool + k];
                var b = verts[node.pool + (k + 1) % node.count];
                if (a != b) {
                    counts.addToValue(edgeKey(a, b), 1);
                }
            }
        }
        var result = new IntArrayList();
        for (var index : surfaceNodes[surface]) {
            var node = nodes[index];
            for (var k = 0; k < node.count; k++) {
                var a = verts[node.pool + k];
                var b = verts[node.pool + (k + 1) % node.count];
                if (a != b && counts.get(edgeKey(a, b)) == 1) {
                    result.add(a);
                    result.add(b);
                }
            }
        }
        return result.toArray();
    }

    float[] getPoints() {
        return points;
    }

    synchronized BspSurfaceSelect getSelect() {
        if (select == null) {
            var nodeSurfaces = new int[nodes.length];
            var nodePools = new int[nodes.length];
            var nodeCounts = new int[nodes.length];
            var nodeZones = new int[nodes.length * 2];
            for (var i = 0; i < nodes.length; i++) {
                nodeSurfaces[i] = nodes[i].surface;
                nodePools[i] = nodes[i].pool;
                nodeCounts[i] = nodes[i].count;
                nodeZones[i * 2] = nodes[i].zoneBack;
                nodeZones[i * 2 + 1] = nodes[i].zoneFront;
            }
            var normals = new float[surfaces.length * 3];
            var actors = new int[surfaces.length];
            var brushPolygons = new int[surfaces.length];
            for (var i = 0; i < surfaces.length; i++) {
                var n = surfaces[i].normal * 3;
                normals[i * 3] = vectors[n];
                normals[i * 3 + 1] = vectors[n + 1];
                normals[i * 3 + 2] = vectors[n + 2];
                actors[i] = surfaces[i].actor;
                brushPolygons[i] = surfaces[i].brushPolygon;
            }
            select = new BspSurfaceSelect(
                    points, verts, nodeSurfaces, nodePools, nodeCounts, nodeZones, normals, actors, brushPolygons);
        }
        return select;
    }

    int pick(Vector3 point, float tolerance, IntPredicate hidden) {
        var best = -1;
        var bestRank = Integer.MAX_VALUE;
        var bestDistance = Float.POSITIVE_INFINITY;
        for (var node : nodes) {
            if (node.count < 3 || node.surface < 0 || node.surface >= surfaces.length) {
                continue;
            }
            var distance = Math.abs(node.px * point.x() + node.py * point.y() + node.pz * point.z() - node.pw);
            if (distance > tolerance || !isInside(node, point, tolerance)) {
                continue;
            }
            var undrawn =
                    (surfaces[node.surface].polyFlags & (INVISIBLE | NOT_DRAWN)) != 0 || hidden.test(node.surface);
            var rank = undrawn ? 2 : (node.flags & NOT_CSG) != 0 ? 1 : 0;
            var samePlane = Math.abs(distance - bestDistance) <= SAME_PLANE;
            var better = undrawn != bestRank >= 2 ? !undrawn : samePlane ? rank < bestRank : distance < bestDistance;
            if (best < 0 || better) {
                best = node.surface;
                bestRank = rank;
                bestDistance = distance;
            }
        }
        return best;
    }

    String getRestriction(Edits edits) {
        for (var edit : edits.mappings().entrySet()) {
            var restriction = getRestriction(edit.getKey());
            if (restriction.isEmpty()) {
                restriction = edit.getValue().restriction();
            }
            if (!restriction.isEmpty()) {
                return restriction;
            }
        }
        for (var surface : edits.hidden()) {
            var restriction = getHideRestriction(surface);
            if (!restriction.isEmpty()) {
                return restriction;
            }
        }
        for (var edit : edits.materials().entrySet()) {
            var restriction = getRestriction(edit.getKey());
            if (restriction.isEmpty()) {
                restriction = getMaterialRestriction(edit.getValue());
            }
            if (!restriction.isEmpty()) {
                return restriction;
            }
        }
        for (var edit : edits.flags().entrySet()) {
            for (var flag : new int[] {TWO_SIDED, UNLIT}) {
                var restriction = getFlagRestriction(edit.getKey(), flag, (edit.getValue() & flag) != 0);
                if (!restriction.isEmpty()) {
                    return restriction;
                }
            }
        }
        return "";
    }

    byte[] apply(byte[] input, int inputOffset, int targetOffset, Edits edits) throws IOException {
        if (!Arrays.equals(input, 0, layout.verts, payload, 0, layout.verts)) {
            throw new IOException("The level model changed before its BSP surfaces");
        }
        var restriction = getRestriction(edits);
        if (!restriction.isEmpty()) {
            throw new IOException(restriction);
        }
        var inputLayout = layout(source, input, flags);
        if (!Arrays.equals(
                input, inputLayout.sections, inputLayout.sectionsEnd, payload, layout.sections, layout.sectionsEnd)) {
            throw new IOException("The render sections of the level model changed before its BSP surfaces");
        }
        var plan = plan(edits);
        var head = head(edits, plan);
        var out = new ByteArrayOutputStream(input.length + head.length - layout.verts + plan.sections.length);
        out.write(head);
        out.write(input, layout.verts, inputLayout.sections - layout.verts);
        out.write(plan.sections);
        out.write(input, inputLayout.sectionsEnd, input.length - inputLayout.sectionsEnd);
        var result = out.toByteArray();
        var shift = result.length - input.length;
        var buffer = PackageBytes.littleEndian(result);
        var tail = BspLightmapTail.read(source, input, inputOffset, flags);
        for (var texture : tail.textures) {
            for (var variant : texture.variants()) {
                buffer.putInt(
                        variant.fullLazyAt() + shift, variant.fullEndPosition() - inputOffset + targetOffset + shift);
                buffer.putInt(
                        variant.halfLazyAt() + shift, variant.halfEndPosition() - inputOffset + targetOffset + shift);
            }
        }
        return result;
    }

    boolean carries(byte[] written, int offset, Edits edits) throws IOException {
        if (!getRestriction(edits).isEmpty()) {
            return false;
        }
        var plan = plan(edits);
        var head = head(edits, plan);
        if (written.length < head.length || !Arrays.equals(written, 0, head.length, head, 0, head.length)) {
            return false;
        }
        var writtenLayout = layout(source, written, flags);
        if (!Arrays.equals(
                written, writtenLayout.sections, writtenLayout.sectionsEnd, plan.sections, 0, plan.sections.length)) {
            return false;
        }
        BspLightmapTail.read(source, written, offset, flags);
        return true;
    }

    String getSectionProblem(Set<Integer> checked) {
        var buffer = PackageBytes.littleEndian(payload);
        for (var surface : checked) {
            for (var index : vertexNodes[surface]) {
                var node = nodes[index];
                if (!hasSavedVertices(node, layout)) {
                    continue;
                }
                var record = layout.sectionRecords[node.section];
                if (record.count > SECTION_VERTICES) {
                    return "A render section holds " + record.count + " vertices";
                }
                if (surfaces[surface].material != 0 && record.material != surfaces[surface].material) {
                    return "The render section of a polygon of surface " + surface + " has another material";
                }
                if (record.flags != (surfaces[surface].polyFlags & SECTION_KEY)) {
                    return "The render section of a polygon of surface " + surface + " has other flags";
                }
                var start = record.vertices + node.first * vertexBytes();
                for (var k = 0; k < node.count; k++) {
                    var p = verts[node.pool + k] * 3;
                    var at = start + k * vertexBytes();
                    if (buffer.getFloat(at) != points[p]
                            || buffer.getFloat(at + 4) != points[p + 1]
                            || buffer.getFloat(at + 8) != points[p + 2]) {
                        return "The saved vertices of a polygon of surface " + surface + " are not its points";
                    }
                }
            }
        }
        return "";
    }

    private boolean isInside(Node node, Vector3 point, float tolerance) {
        var ax = Math.abs(node.px);
        var ay = Math.abs(node.py);
        var az = Math.abs(node.pz);
        var drop = ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
        var a = drop == 0 ? 1 : 0;
        var b = drop == 2 ? 1 : 2;
        var target = new float[] {point.x(), point.y(), point.z()};
        var tx = target[a];
        var ty = target[b];
        var crossings = false;
        var nearEdge = false;
        for (var k = 0; k < node.count; k++) {
            var p = verts[node.pool + k] * 3;
            var q = verts[node.pool + (k + 1) % node.count] * 3;
            var x0 = points[p + a];
            var y0 = points[p + b];
            var x1 = points[q + a];
            var y1 = points[q + b];
            if ((y0 > ty) != (y1 > ty) && tx < x0 + (ty - y0) / (y1 - y0) * (x1 - x0)) {
                crossings = !crossings;
            }
            nearEdge |= segmentDistance(tx, ty, x0, y0, x1, y1) <= tolerance;
        }
        return crossings || nearEdge;
    }

    private byte[] head(Edits edits, Plan plan) {
        var mapped = edits.mappings();
        var out = new ByteArrayOutputStream(layout.verts + mapped.size() * 40 + 16);
        out.write(payload, 0, layout.vectors);
        if (mapped.isEmpty()) {
            out.write(payload, layout.vectors, layout.nodes - layout.vectors);
        } else {
            PackageBytes.writeIndex(out, vectors.length / 3 + mapped.size() * 2);
            out.write(payload, layout.vectorData, vectors.length * 4);
            for (var mapping : mapped.values()) {
                writeVector(out, mapping.u());
                writeVector(out, mapping.v());
            }
            PackageBytes.writeIndex(out, points.length / 3 + mapped.size());
            out.write(payload, layout.pointData, points.length * 4);
            for (var mapping : mapped.values()) {
                writeVector(out, mapping.base());
            }
        }
        var nodeBytes = Arrays.copyOfRange(payload, layout.nodes, layout.surfaces);
        var nodeBuffer = PackageBytes.littleEndian(nodeBytes);
        for (var i = 0; i < nodes.length; i++) {
            if (plan.nodeSections[i] != nodes[i].section || plan.nodeFirsts[i] != nodes[i].first) {
                nodeBuffer.putInt(nodes[i].sectionAt - layout.nodes, plan.nodeSections[i]);
                nodeBuffer.putInt(nodes[i].sectionAt - layout.nodes + 4, plan.nodeFirsts[i]);
            }
        }
        out.writeBytes(nodeBytes);
        out.write(payload, layout.surfaces, layout.surfaceStarts[0] - layout.surfaces);
        var vector = vectors.length / 3;
        var point = points.length / 3;
        for (var i = 0; i < surfaces.length; i++) {
            var s = surfaces[i];
            var mapping = mapped.get(i);
            var material = edits.materials().get(i);
            var hidden = edits.hidden().contains(i);
            var flagged = edits.flags().get(i);
            if (mapping == null && material == null && !hidden && flagged == null) {
                out.write(payload, s.start, s.end - s.start);
                continue;
            }
            if (material == null) {
                out.write(payload, s.start, s.flagsAt - s.start);
            } else {
                PackageBytes.writeIndex(out, material.reference());
            }
            var polyFlags = flagged == null ? s.polyFlags : s.polyFlags & ~EDITABLE_FLAGS | flagged;
            PackageBytes.writeInt(out, hidden ? polyFlags | INVISIBLE : polyFlags);
            if (mapping == null) {
                out.write(payload, s.indicesStart, s.restStart - s.indicesStart);
            } else {
                PackageBytes.writeIndex(out, point++);
                PackageBytes.writeIndex(out, s.normal);
                PackageBytes.writeIndex(out, vector++);
                PackageBytes.writeIndex(out, vector++);
            }
            out.write(payload, s.restStart, s.end - s.restStart);
        }
        return out.toByteArray();
    }

    private Plan plan(Edits edits) {
        var nodeSections = new int[nodes.length];
        var nodeFirsts = new int[nodes.length];
        for (var i = 0; i < nodes.length; i++) {
            nodeSections[i] = nodes[i].section;
            nodeFirsts[i] = nodes[i].first;
        }
        if (edits.mappings().isEmpty() && edits.moved().isEmpty()) {
            return new Plan(Arrays.copyOfRange(payload, layout.sections, layout.sectionsEnd), nodeSections, nodeFirsts);
        }
        var stride = vertexBytes();
        var moving = new boolean[nodes.length];
        for (var surface : edits.moved()) {
            for (var index : vertexNodes[surface]) {
                if (hasSavedVertices(nodes[index], layout)) {
                    moving[index] = true;
                }
            }
        }
        var records = layout.sectionRecords;
        var removed = new boolean[records.length][];
        for (var i = 0; i < records.length; i++) {
            removed[i] = new boolean[records[i].count];
        }
        for (var i = 0; i < nodes.length; i++) {
            if (moving[i]) {
                Arrays.fill(removed[nodes[i].section], nodes[i].first, nodes[i].first + nodes[i].count, true);
            }
        }
        var sections = new ArrayList<PlannedSection>();
        var newIndex = new int[records.length][];
        for (var i = 0; i < records.length; i++) {
            var record = records[i];
            var planned = new PlannedSection(
                    i, record.material, record.flags, record.lightmap, record.revision, record.nodes);
            newIndex[i] = new int[record.count];
            for (var k = 0; k < record.count; k++) {
                if (removed[i][k]) {
                    planned.changed = true;
                    continue;
                }
                newIndex[i][k] = planned.count++;
                planned.vertices.write(payload, record.vertices + k * stride, stride);
            }
            sections.add(planned);
        }
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (moving[i] || node.count == 0 || !hasSavedVertices(node, layout)) {
                continue;
            }
            for (var k = node.first; k < node.first + node.count; k++) {
                if (removed[node.section][k]) {
                    throw new IllegalStateException("A node shares saved vertices with a moved node");
                }
            }
            nodeFirsts[i] = newIndex[node.section][node.first];
        }
        for (var i = 0; i < nodes.length; i++) {
            if (!moving[i]) {
                continue;
            }
            var node = nodes[i];
            var from = sections.get(node.section);
            var newMaterial = edits.materials().get(node.surface);
            var material = newMaterial == null ? from.material : newMaterial.reference();
            var newFlags = edits.flags().get(node.surface);
            var flags = newFlags == null ? from.flags : from.flags & ~EDITABLE_FLAGS | newFlags;
            if (from.nodes > 0) {
                from.nodes--;
            }
            if (from.revision > 0) {
                from.revision--;
            }
            PlannedSection target = null;
            for (var candidate : sections) {
                if (candidate.material == material
                        && candidate.flags == flags
                        && candidate.lightmap == from.lightmap
                        && candidate.count + node.count <= SECTION_VERTICES) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                target = new PlannedSection(-1, material, flags, from.lightmap, 0, 0);
                sections.add(target);
            }
            target.changed = true;
            target.nodes++;
            target.revision++;
            nodeSections[i] = sections.indexOf(target);
            nodeFirsts[i] = target.count;
            target.vertices.write(payload, records[node.section].vertices + node.first * stride, node.count * stride);
            target.count += node.count;
        }
        var named = new boolean[sections.size()];
        for (var section : nodeSections) {
            if (section >= 0 && section < named.length) {
                named[section] = true;
            }
        }
        var renumber = new int[sections.size()];
        var kept = new ArrayList<PlannedSection>();
        for (var i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            if (section.count == 0 && section.changed && !named[i]) {
                renumber[i] = -1;
                continue;
            }
            renumber[i] = kept.size();
            kept.add(section);
        }
        for (var i = 0; i < nodes.length; i++) {
            if (nodeSections[i] >= 0 && nodeSections[i] < renumber.length) {
                nodeSections[i] = renumber[nodeSections[i]];
            }
        }
        var buffers = new ByteBuffer[kept.size()];
        var bytes = new byte[kept.size()][];
        for (var i = 0; i < kept.size(); i++) {
            bytes[i] = kept.get(i).vertices.toByteArray();
            buffers[i] = PackageBytes.littleEndian(bytes[i]);
        }
        var retextured = new TreeSet<>(edits.mappings().keySet());
        retextured.addAll(edits.materials().keySet());
        for (var surface : retextured) {
            var mapping = edits.mappings().getOrDefault(surface, getMapping(surface));
            var material = edits.materials().get(surface);
            var sizes = material == null ? mappingSizes[surface] : new float[] {material.uSize(), material.vSize()};
            for (var index : vertexNodes[surface]) {
                var node = nodes[index];
                if (!hasSavedVertices(node, layout)) {
                    continue;
                }
                var target = nodeSections[index];
                kept.get(target).changed = true;
                var uv = coordinates(node, mapping, sizes);
                var start = nodeFirsts[index] * stride;
                for (var k = 0; k < node.count; k++) {
                    buffers[target].putFloat(start + k * stride + VERTEX_U, uv[k * 2]);
                    buffers[target].putFloat(start + k * stride + VERTEX_V, uv[k * 2 + 1]);
                }
            }
        }
        var out = new ByteArrayOutputStream(layout.sectionsEnd - layout.sections + 64);
        PackageBytes.writeIndex(out, kept.size());
        for (var i = 0; i < kept.size(); i++) {
            var section = kept.get(i);
            if (!section.changed) {
                var record = records[section.original];
                out.write(payload, record.start, record.end - record.start);
                continue;
            }
            PackageBytes.writeIndex(out, section.count);
            out.writeBytes(bytes[i]);
            PackageBytes.writeInt(out, section.revision);
            PackageBytes.writeIndex(out, section.material);
            PackageBytes.writeInt(out, section.nodes);
            PackageBytes.writeInt(out, section.flags);
            PackageBytes.writeInt(out, section.lightmap);
        }
        return new Plan(out.toByteArray(), nodeSections, nodeFirsts);
    }

    private boolean isLightmapped(int surface) {
        if (vertexNodes[surface].length == 0) {
            return false;
        }
        for (var index : vertexNodes[surface]) {
            var node = nodes[index];
            if (node.lightmap < 0
                    || node.lightmap >= lightmapCount
                    || !hasSavedVertices(node, layout)
                    || layout.sectionRecords[node.section].lightmap < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSavedVertices(Node node, Layout layout) {
        return node.section >= 0
                && node.section < layout.sectionRecords.length
                && node.first >= 0
                && node.first + node.count <= layout.sectionRecords[node.section].count;
    }

    private float[] coordinates(Node node, BspSurfaceMapping mapping, float[] sizes) {
        var u = mapping.u();
        var v = mapping.v();
        var base = mapping.base();
        var uTranslation = u.y() * -base.y() + u.x() * -base.x() + u.z() * -base.z();
        var vTranslation = v.y() * -base.y() + v.x() * -base.x() + v.z() * -base.z();
        var result = new float[node.count * 2];
        var totalU = 0f;
        var totalV = 0f;
        for (var k = 0; k < node.count; k++) {
            var p = verts[node.pool + k] * 3;
            var x = points[p];
            var y = points[p + 1];
            var z = points[p + 2];
            result[k * 2] = (u.y() * y + u.x() * x + u.z() * z + uTranslation) / sizes[0];
            result[k * 2 + 1] = (v.y() * y + v.x() * x + v.z() * z + vTranslation) / sizes[1];
            totalU += result[k * 2];
            totalV += result[k * 2 + 1];
        }
        var centerU = (float) (int) Math.floor((double) (totalU / node.count) + 0.5);
        var centerV = (float) (int) Math.floor((double) (totalV / node.count) + 0.5);
        for (var k = 0; k < node.count; k++) {
            result[k * 2] -= centerU;
            result[k * 2 + 1] -= centerV;
        }
        return result;
    }

    private float[] savedMappingSizes(int surface) {
        var mapping = getMapping(surface);
        var sizes = new float[2];
        var buffer = PackageBytes.littleEndian(payload);
        var found = false;
        for (var axis = 0; axis < 2; axis++) {
            var direction = axis == 0 ? mapping.u() : mapping.v();
            var bestSpan = 0f;
            for (var index : surfaceNodes[surface]) {
                var node = nodes[index];
                if (!hasSavedVertices(node, layout)) {
                    continue;
                }
                found = true;
                var start = layout.sectionRecords[node.section].vertices + node.first * vertexBytes();
                for (var k = 1; k < node.count; k++) {
                    var saved = buffer.getFloat(start + k * vertexBytes() + (axis == 0 ? VERTEX_U : VERTEX_V))
                            - buffer.getFloat(start + (axis == 0 ? VERTEX_U : VERTEX_V));
                    if (Math.abs(saved) <= bestSpan) {
                        continue;
                    }
                    var p = verts[node.pool + k] * 3;
                    var q = verts[node.pool] * 3;
                    var world = direction.x() * (points[p] - points[q])
                            + direction.y() * (points[p + 1] - points[q + 1])
                            + direction.z() * (points[p + 2] - points[q + 2]);
                    bestSpan = Math.abs(saved);
                    sizes[axis] = Math.round(world / saved);
                }
            }
            if (found && bestSpan > 0 && !(sizes[axis] >= 1)) {
                return null;
            }
            if (bestSpan == 0) {
                sizes[axis] = 1;
            }
        }
        if (!found) {
            return new float[] {1, 1};
        }
        for (var index : surfaceNodes[surface]) {
            var node = nodes[index];
            if (!hasSavedVertices(node, layout)) {
                continue;
            }
            var start = layout.sectionRecords[node.section].vertices + node.first * vertexBytes();
            var uv = coordinates(node, mapping, sizes);
            for (var k = 0; k < node.count; k++) {
                var p = verts[node.pool + k] * 3;
                var at = start + k * vertexBytes();
                if (buffer.getFloat(at) != points[p]
                        || buffer.getFloat(at + 4) != points[p + 1]
                        || buffer.getFloat(at + 8) != points[p + 2]) {
                    return null;
                }
                if (Math.abs(buffer.getFloat(at + VERTEX_U) - uv[k * 2]) > SAVED_UV_TOLERANCE
                        || Math.abs(buffer.getFloat(at + VERTEX_V) - uv[k * 2 + 1]) > SAVED_UV_TOLERANCE) {
                    return null;
                }
            }
        }
        return sizes;
    }

    private int vertexBytes() {
        return source.fileVersion > 0x6c ? 40 : 28;
    }

    private Vector3 point(int index) {
        return new Vector3(points[index * 3], points[index * 3 + 1], points[index * 3 + 2]);
    }

    private Vector3 vector(int index) {
        return new Vector3(vectors[index * 3], vectors[index * 3 + 1], vectors[index * 3 + 2]);
    }

    private static float segmentDistance(float px, float py, float x0, float y0, float x1, float y1) {
        var dx = x1 - x0;
        var dy = y1 - y0;
        var lengthSquared = dx * dx + dy * dy;
        var t = lengthSquared == 0 ? 0 : Math.clamp(((px - x0) * dx + (py - y0) * dy) / lengthSquared, 0f, 1f);
        var ex = x0 + t * dx - px;
        var ey = y0 + t * dy - py;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    private static Layout layout(PackageBytes source, byte[] payload, int flags) throws IOException {
        var c = new Cursor(payload, 0, payload.length);
        NativeLevelEditor.skipProperties(source, c, flags);
        c.skip(25 + 16);
        var vectors = c.position;
        var vectorCount = count(c, 12);
        var vectorData = c.position;
        c.skip(vectorCount * 12);
        var points = c.position;
        var pointCount = count(c, 12);
        var pointData = c.position;
        c.skip(pointCount * 12);
        var nodes = c.position;
        for (var n = count(c, 60); n > 0; n--) {
            c.skip(16 + 8 + 1);
            for (var i = 0; i < 7; i++) {
                c.readIndex();
            }
            c.skip(32);
            c.readIndex();
            c.readIndex();
            c.skip(1 + 8 + 12);
        }
        var surfacesStart = c.position;
        var surfaceCount = count(c, 20);
        var surfaceStarts = new int[surfaceCount + 1];
        for (var n = 0; n < surfaceCount; n++) {
            surfaceStarts[n] = c.position;
            c.readIndex();
            c.skip(4);
            for (var i = 0; i < 6; i++) {
                c.readIndex();
            }
            c.skip(16 + 4);
            if (source.licenseVersion > 20) {
                c.skip(4);
            }
        }
        var verts = c.position;
        surfaceStarts[surfaceCount] = verts;
        for (var n = count(c, 2); n > 0; n--) {
            c.readIndex();
            c.readIndex();
        }
        c.skip(4);
        var zones = c.readI32();
        if (zones < 0 || zones > 64) {
            throw new IOException("Invalid BSP zone count " + zones);
        }
        for (var i = 0; i < zones; i++) {
            c.readIndex();
            c.skip(20);
        }
        c.readIndex();
        c.skip(count(c, 25) * 25);
        c.skip(count(c, 4) * 4);
        for (var n = count(c, 11); n > 0; n--) {
            c.readIndex();
            c.readIndex();
            c.readIndex();
            c.skip(8);
        }
        for (var n = count(c, 1); n > 0; n--) {
            c.readIndex();
        }
        c.skip(8);
        var vertexBytes = source.fileVersion > 0x6c ? 40 : 28;
        var sections = c.position;
        var sectionCount = count(c, 18);
        var records = new SectionRecord[sectionCount];
        for (var n = 0; n < sectionCount; n++) {
            var start = c.position;
            var count = count(c, vertexBytes);
            var vertices = c.position;
            c.skip(count * vertexBytes);
            var revision = c.readI32();
            var material = c.readIndex();
            var nodeCount = c.readI32();
            var polyFlags = c.readI32();
            var lightmap = c.readI32();
            records[n] = new SectionRecord(
                    start, vertices, count, revision, material, nodeCount, polyFlags, lightmap, c.position);
        }
        return new Layout(
                vectors,
                vectorData,
                points,
                pointData,
                nodes,
                surfacesStart,
                surfaceStarts,
                verts,
                sections,
                records,
                c.position);
    }

    private static int count(Cursor c, int minimumBytes) throws IOException {
        var count = c.readIndex();
        if (count < 0 || (long) count * minimumBytes > c.end - c.position) {
            throw new IOException("Invalid BSP array count " + count);
        }
        return count;
    }

    private static float[] floats(Cursor c, int count) throws IOException {
        if (count < 0 || (long) count * 4 > c.end - c.position) {
            throw new IOException("Invalid BSP array count " + count / 3);
        }
        var result = new float[count];
        for (var i = 0; i < count; i++) {
            result[i] = f32(c);
        }
        return result;
    }

    private static float f32(Cursor c) throws IOException {
        return Float.intBitsToFloat(c.readI32());
    }

    private static boolean isValidIndex(int value, float[] triples) {
        return value >= 0 && value < triples.length / 3;
    }

    private static long edgeKey(int a, int b) {
        return (long) Math.min(a, b) << 32 | Math.max(a, b) & 0xffffffffL;
    }

    private static void writeVector(ByteArrayOutputStream out, Vector3 value) {
        PackageBytes.writeInt(out, Float.floatToRawIntBits(value.x()));
        PackageBytes.writeInt(out, Float.floatToRawIntBits(value.y()));
        PackageBytes.writeInt(out, Float.floatToRawIntBits(value.z()));
    }
}
