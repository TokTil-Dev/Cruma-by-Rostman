package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingTrace.Caster;
import com.rost.cruma.core.editing.BeastTerrainRebake.ActorChange;
import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.Source;
import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.WorldQuery;
import com.rost.cruma.core.editing.PackageEditDocument.LightPlacementEdit;
import com.rost.cruma.core.editing.PackageEditDocument.LightingEdit;
import com.rost.cruma.core.editing.TerrainEditExport.Prepared;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.editing.TerrainShadowQuery.MeshCaster;
import com.rost.cruma.core.editing.TerrainShadowQuery.MeshTriangles;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UStaticMesh;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class TerrainActorShadows {
    public static final TerrainActorShadows NONE =
            new TerrainActorShadows(Set.of(), List.of(), List.of(), false, Map.of(), Map.of());

    private static final float BOX_MARGIN = 2f;

    private static final String[] MESH_ACTOR_CLASSES = {
        "StaticMeshActor", "MovableStaticMeshActor", "L2MovableStaticMeshActor"
    };

    private final Set<String> removed;
    private final List<MeshCaster> added;
    private final List<Caster> beastAdded;
    private final boolean all;
    private final Map<Integer, LightColor> recolored;
    private final Map<Integer, LightPlacementEdit> moved;

    private TerrainActorShadows(
            Set<String> removed,
            List<MeshCaster> added,
            List<Caster> beastAdded,
            boolean all,
            Map<Integer, LightColor> recolored,
            Map<Integer, LightPlacementEdit> moved) {
        this.removed = Set.copyOf(removed);
        this.added = List.copyOf(added);
        this.beastAdded = List.copyOf(beastAdded);
        this.all = all;
        this.recolored = Map.copyOf(recolored);
        this.moved = Map.copyOf(moved);
    }

    public static TerrainActorShadows of(PackageEditDocument document, UArchiveLoader archives) throws IOException {
        var recolored = new HashMap<Integer, LightColor>();
        for (var edit : document.getLightColorEdits()) {
            recolored.put(edit.exportIndex(), edit.current());
        }
        var moved = new HashMap<Integer, LightPlacementEdit>();
        for (var edit : document.getLightPlacementEdits()) {
            moved.put(edit.exportIndex(), edit);
        }
        var removed = new LinkedHashSet<String>();
        var current = new HashSet<String>();
        for (var edit : document.getLightingEdits()) {
            if (!isChanged(edit)) {
                continue;
            }
            if (!edit.created()) {
                removed.add(edit.path());
            }
            if (!edit.deleted()) {
                current.add(edit.path());
            }
        }
        if (removed.isEmpty() && current.isEmpty()) {
            return recolored.isEmpty() && moved.isEmpty()
                    ? NONE
                    : new TerrainActorShadows(Set.of(), List.of(), List.of(), false, recolored, moved);
        }
        var added = new ArrayList<MeshCaster>();
        var beastAdded = new ArrayList<Caster>();
        if (!current.isEmpty()) {
            var file = document.getSource().getFileName().toString();
            var dot = file.lastIndexOf('.');
            var name = dot < 0 ? file : file.substring(0, dot);
            try (var archive = new UArchive(
                    name, ByteBuffer.wrap(document.previewBody()).order(ByteOrder.LITTLE_ENDIAN), archives)) {
                var actors = new ArrayList<StaticMeshActor>();
                for (var type : MESH_ACTOR_CLASSES) {
                    archive.loadObjects(type, actors);
                }
                if (ActorLightingCapture.hasBeastTextures(archive)) {
                    beastAdded.addAll(ActorLightingCapture.beastCasters(actors, current));
                }
                var triangles = new IdentityHashMap<UStaticMesh, MeshTriangles>();
                var seen = new HashSet<String>();
                for (var actor : actors) {
                    var path = actor.getSourcePath();
                    if (!current.contains(path) || actor.isDeleteMe() || !seen.add(path)) {
                        continue;
                    }
                    if (!actor.isShadowCast() || !actor.isCollideActors() || !actor.isBlockZeroExtentTraces()) {
                        continue;
                    }
                    UStaticMesh mesh;
                    try {
                        mesh = actor.getStaticMesh().get();
                    } catch (RuntimeException missing) {
                        mesh = null;
                    }
                    if (mesh == null || mesh.getBoundingBox() == null) {
                        continue;
                    }
                    var shape = triangles.computeIfAbsent(mesh, TerrainLightingScene::collectTriangles);
                    if (shape == null) {
                        continue;
                    }
                    try {
                        var matrices = TerrainLightingScene.toTransformPair(actor);
                        added.add(new MeshCaster(
                                0,
                                TerrainLightingScene.transform(mesh.getBoundingBox(), matrices.getLocalToWorld()),
                                shape,
                                matrices.getWorldToLocal()));
                    } catch (IllegalArgumentException degenerate) {
                    }
                }
            } catch (RuntimeException unreadable) {
                throw new IOException(
                        "The edited actors of " + name + " cannot be read: " + unreadable.getMessage(), unreadable);
            }
        }
        return new TerrainActorShadows(removed, added, beastAdded, false, recolored, moved);
    }

    public static boolean isNeeded(PackageEditDocument document, boolean all) {
        return all || ActorLightingRebuild.isNeeded(document);
    }

    public static Result rebuild(
            PackageEditDocument document,
            Path clientRoot,
            UArchiveLoader archives,
            Set<String> skipOwners,
            boolean all,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(progress);
        Objects.requireNonNull(cancelled);
        var name = mapName(document.getSource());
        if (!isNeeded(document, all)) {
            return new Result(null, Report.empty(name));
        }
        try {
            var shadows = of(document, archives).withAll(all);
            if (shadows.isEmpty()) {
                return new Result(null, Report.empty(name));
            }
            TerrainPackageSnapshot snapshot;
            try {
                snapshot = TerrainPackageSnapshot.open(document.getSource(), clientRoot);
            } catch (IOException unreadable) {
                if (!hasTerrain(document.getSource())) {
                    return new Result(null, Report.empty(name));
                }
                return new Result(
                        null,
                        new Report(
                                name,
                                all,
                                List.of(),
                                List.of("The Terrain of " + name + " cannot be read (" + unreadable.getMessage()
                                        + "), so its lighting keeps the saved actor shadows")));
            }
            if (!snapshot.getOriginalSha256().equals(document.getOriginalSha256())) {
                throw new IOException("Source map changed since editing began; reopen it before saving");
            }
            var owners = new ArrayList<Terrain>();
            for (var terrain : snapshot.getTerrains()) {
                if (!skipOwners.contains(terrain.path()) && isLit(terrain)) {
                    owners.add(terrain);
                }
            }
            progress.update(0, owners.size());
            NativePackageChanges changes = null;
            var reports = new ArrayList<Owner>();
            var notes = new ArrayList<String>();
            var empty = new TerrainHeightPatch(256, 256, new int[0], new short[0], new short[0]);
            for (var i = 0; i < owners.size(); i++) {
                checkCancelled(cancelled);
                var terrain = owners.get(i);
                var owner = terrain.path();
                var restriction = TerrainEditExport.findRestriction(terrain);
                if (!restriction.isEmpty()) {
                    notes.add(keptMessage(owner, restriction));
                    progress.update(i + 1, owners.size());
                    continue;
                }
                TerrainLightingScene scene;
                try {
                    scene = TerrainLightingScene.capture(clientRoot, snapshot, owner, cancelled);
                } catch (IOException unreadable) {
                    notes.add(keptMessage(owner, unreadable.getMessage()));
                    progress.update(i + 1, owners.size());
                    continue;
                }
                if (scene.getSummary().terrainCasters() > 0) {
                    notes.add(keptMessage(owner, "this Terrain casts shadows on itself"));
                    progress.update(i + 1, owners.size());
                    continue;
                }
                if (!all
                        && !scene.hasSunlightMeshShadows()
                        && !shadows.hasRecoloredLights()
                        && !shadows.hasMovedLights()) {
                    notes.add("The saved lighting of " + owner
                            + " has no static mesh shadows, so the edited actors change nothing there");
                    progress.update(i + 1, owners.size());
                    continue;
                }
                Prepared prepared;
                try {
                    prepared = TerrainEditExport.prepare(snapshot, scene, owner, empty, List.of(), shadows, cancelled);
                } catch (IllegalArgumentException unsupported) {
                    notes.add(keptMessage(owner, unsupported.getMessage()));
                    progress.update(i + 1, owners.size());
                    continue;
                }
                if (shadows.hasRecoloredLights() && scene.getBeast() != null) {
                    notes.add("The Beast lightmap of " + owner + " keeps the old light colours");
                }
                if (shadows.hasMovedLights() && scene.getBeast() != null) {
                    notes.add("The Beast lightmap of " + owner + " keeps the old places of the moved lights");
                }
                if (prepared.getActorShadowVertices() > 0
                        || prepared.getReport().lighting().colorsChanged() > 0
                        || prepared.getReport().lighting().steadyBitsChanged() > 0) {
                    var stats = prepared.getReport().lighting();
                    reports.add(new Owner(
                            owner,
                            prepared.getActorShadowVertices(),
                            stats.steadyBitsChanged(),
                            stats.colorsChanged(),
                            stats.timeSamplesChanged(),
                            stats.rays()));
                }
                if (!prepared.getChanges().isEmpty()) {
                    changes = changes == null ? prepared.getChanges() : changes.combine(prepared.getChanges());
                }
                progress.update(i + 1, owners.size());
            }
            return new Result(changes, new Report(name, all, reports, notes));
        } catch (CancellationException stopped) {
            throw new InterruptedIOException("Terrain lighting rebuild cancelled");
        }
    }

    public TerrainActorShadows withAll(boolean all) {
        return all == this.all ? this : new TerrainActorShadows(removed, added, beastAdded, all, recolored, moved);
    }

    public boolean isEmpty() {
        return !all && removed.isEmpty() && added.isEmpty() && recolored.isEmpty() && moved.isEmpty();
    }

    public boolean hasRecoloredLights() {
        return !recolored.isEmpty();
    }

    public boolean hasMovedLights() {
        return !moved.isEmpty();
    }

    public ActorChange getBeast() {
        return new ActorChange(removed, beastAdded);
    }

    public boolean isFullRebuild() {
        return all;
    }

    public Selection select(TerrainLightingScene scene, TerrainHeightField heights, BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(cancelled);
        if (isEmpty()) {
            return Selection.NONE;
        }
        var colors = new HashMap<Integer, Vector>();
        recolored.forEach((reference, color) -> colors.put(reference, scene.computeSteadyColor(color)));
        var sources = new LinkedHashMap<Integer, Source>();
        var deleted = new HashSet<Integer>();
        for (var source : scene.getSteadySources()) {
            var edit = moved.get(source.reference());
            if (edit != null) {
                if (edit.current() == null) {
                    deleted.add(source.reference());
                } else {
                    sources.put(
                            source.reference(),
                            placed(
                                    source,
                                    source.reference(),
                                    edit,
                                    colors.getOrDefault(source.reference(), source.color())));
                }
            }
        }
        for (var edit : moved.values().stream()
                .sorted(Comparator.comparingInt(LightPlacementEdit::exportIndex))
                .toList()) {
            if (edit.original() == null && edit.current() != null) {
                for (var source : scene.getSteadySources()) {
                    if (source.reference() == edit.template()) {
                        sources.put(
                                edit.exportIndex(),
                                placed(source, edit.exportIndex(), edit, scene.computeSteadyColor(edit.color())));
                    }
                }
            }
        }
        var width = heights.getWidth();
        var height = heights.getHeight();
        var count = width * height;
        var query = scene.getQuery();
        var boxes = new ArrayList<float[]>();
        if (scene.hasSunlightMeshShadows() && (!removed.isEmpty() || !added.isEmpty())) {
            var references = new HashSet<Integer>();
            for (var actor : scene.getMeshActors()) {
                if (removed.contains(actor.path())) {
                    references.add(actor.reference());
                }
            }
            for (var box : query.getMeshBounds(references)) {
                boxes.add(toBoxArray(box));
            }
            for (var caster : added) {
                boxes.add(toBoxArray(caster.bounds()));
            }
            query = query.replacing(references, added);
        }
        if (all) {
            var every = new BitSet(count);
            every.set(0, count);
            return new Selection(query, true, every, Map.of(), List.of(), colors, sources, deleted);
        }
        if (boxes.isEmpty()) {
            return colors.isEmpty() && sources.isEmpty() && deleted.isEmpty()
                    ? Selection.NONE
                    : new Selection(query, false, new BitSet(), Map.of(), List.of(), colors, sources, deleted);
        }
        var edgeTurns = scene.getEdgeTurns();
        var inverted = scene.isInverted();
        var visibleQuads = scene.getVisibleQuads();
        var positions = new Vector[count];
        var normals = new Vector[count];
        for (var y = 0; y < height; y++) {
            checkCancelled(cancelled);
            for (var x = 0; x < width; x++) {
                var vertex = y * width + x;
                var source = scene.getLightingSource(vertex, width) % width;
                positions[vertex] = heights.getWorldAt(source, y);
                normals[vertex] = heights.getNormalAt(source, y, edgeTurns, inverted);
            }
        }
        var hierarchy = buildHierarchy(boxes);
        var vertices = new BitSet(count);
        var steady = new HashMap<Integer, BitSet>();
        var owner = scene.getSteadyOwner();
        if (owner.staticLighting()) {
            BitSet zones = null;
            for (var light : scene.getSteadySources()) {
                if (!NativeTerrainSteadyLighting.isAdmitted(owner, light)
                        || !reaches(light.position(), light.radius(), boxes)) {
                    continue;
                }
                if (zones == null) {
                    zones = scene.computeOwnerZoneSamples(heights);
                }
                var bits = new BitSet(count);
                var radius = light.radius();
                for (var y = 0; y < height; y++) {
                    checkCancelled(cancelled);
                    for (var x = 0; x < width; x++) {
                        var vertex = y * width + x;
                        if (!TerrainLightingRebuild.isSteadyEligible(zones, visibleQuads, width, height, x, y)) {
                            continue;
                        }
                        var point = positions[vertex];
                        var normal = normals[vertex];
                        var sx = point.x() + normal.x();
                        var sy = point.y() + normal.y();
                        var sz = point.z() + normal.z();
                        var dx = light.position().x() - sx;
                        var dy = light.position().y() - sy;
                        var dz = light.position().z() - sz;
                        if (!(dy * dy + dx * dx + dz * dz < radius * radius)
                                || !(normal.y() * dy + normal.x() * dx + normal.z() * dz > 0)) {
                            continue;
                        }
                        if (crosses(sx, sy, sz, dx, dy, dz, hierarchy)) {
                            bits.set(vertex);
                        }
                    }
                }
                if (!bits.isEmpty()) {
                    steady.put(light.reference(), bits);
                    vertices.or(bits);
                }
            }
        }
        var sun = new ArrayList<BitSet>();
        if (isSunlit(scene)) {
            for (var slot : scene.getSlots()) {
                var direction = slot.safeDirection();
                var bits = new BitSet(count);
                var dx = -direction.x() * NativeTerrainTimeLighting.RAY_LENGTH;
                var dy = -direction.y() * NativeTerrainTimeLighting.RAY_LENGTH;
                var dz = -direction.z() * NativeTerrainTimeLighting.RAY_LENGTH;
                for (var y = 0; y < height; y++) {
                    checkCancelled(cancelled);
                    for (var x = 0; x < width; x++) {
                        var vertex = y * width + x;
                        if (!isSunEligible(visibleQuads, width, height, x, y)) {
                            continue;
                        }
                        var normal = normals[vertex];
                        if (NativeTerrainTimeLighting.computeIntensity(direction, normal) == 0) {
                            continue;
                        }
                        var point = positions[vertex];
                        if (crosses(
                                point.x() + normal.x(),
                                point.y() + normal.y(),
                                point.z() + normal.z(),
                                dx,
                                dy,
                                dz,
                                hierarchy)) {
                            bits.set(vertex);
                        }
                    }
                }
                sun.add(bits);
                vertices.or(bits);
            }
        }
        return vertices.isEmpty() && colors.isEmpty() && sources.isEmpty() && deleted.isEmpty()
                ? Selection.NONE
                : new Selection(query, false, vertices, steady, sun, colors, sources, deleted);
    }

    private static Source placed(Source source, int reference, LightPlacementEdit edit, Vector color) {
        var placement = edit.current();
        var rotation = edit.shape().rotation();
        return new Source(
                reference,
                source.lightType(),
                source.actorFlags(),
                edit.zone() >= 0 ? edit.zone() : source.zoneNumber(),
                source.skyZone(),
                source.movableSun(),
                source.effect(),
                new Vector(
                        placement.location().x(),
                        placement.location().y(),
                        placement.location().z()),
                edit.turned()
                        ? TerrainLightingScene.rotationVector(rotation.pitch(), rotation.yaw())
                        : source.direction(),
                placement.reach(),
                edit.turned() ? edit.shape().cone() : source.cone(),
                color);
    }

    public static final class Selection {
        public static final Selection NONE =
                new Selection(null, false, new BitSet(), Map.of(), List.of(), Map.of(), Map.of(), Set.of());

        private final @Nullable WorldQuery query;
        private final boolean all;
        private final BitSet vertices;
        private final Map<Integer, BitSet> steady;
        private final List<BitSet> sun;
        private final Map<Integer, Vector> recolored;
        private final Map<Integer, Source> moved;
        private final Set<Integer> deleted;

        private Selection(
                @Nullable WorldQuery query,
                boolean all,
                BitSet vertices,
                Map<Integer, BitSet> steady,
                List<BitSet> sun,
                Map<Integer, Vector> recolored,
                Map<Integer, Source> moved,
                Set<Integer> deleted) {
            this.query = query;
            this.all = all;
            this.vertices = (BitSet) vertices.clone();
            this.steady = Map.copyOf(steady);
            this.sun = List.copyOf(sun);
            this.recolored = Map.copyOf(recolored);
            this.moved = Map.copyOf(moved);
            this.deleted = Set.copyOf(deleted);
        }

        public boolean isEmpty() {
            return vertices.isEmpty() && recolored.isEmpty() && moved.isEmpty() && deleted.isEmpty();
        }

        public Map<Integer, Source> getMoved() {
            return moved;
        }

        public Set<Integer> getDeleted() {
            return deleted;
        }

        public List<Source> mergeSources(List<Source> saved) {
            var result = new ArrayList<Source>(saved.size() + moved.size());
            var known = new HashSet<Integer>();
            for (var source : saved) {
                known.add(source.reference());
                if (!deleted.contains(source.reference())) {
                    result.add(moved.getOrDefault(source.reference(), source));
                }
            }
            moved.keySet().stream()
                    .filter(reference -> !known.contains(reference))
                    .sorted()
                    .forEach(reference -> result.add(moved.get(reference)));
            return result;
        }

        public Map<Integer, Vector> getRecolored() {
            return recolored;
        }

        public boolean isFullRebuild() {
            return all;
        }

        public WorldQuery query(TerrainLightingScene scene) {
            return query == null ? scene.getQuery() : query;
        }

        public int getVertexCount() {
            return vertices.cardinality();
        }

        public boolean touches(int vertex) {
            return vertices.get(vertex);
        }

        public boolean isSteadySelected(int vertex, int light) {
            if (all) {
                return true;
            }
            var bits = steady.get(light);
            return bits != null && bits.get(vertex);
        }

        public boolean isSunSelected(int vertex, int slot) {
            return all || slot < sun.size() && sun.get(slot).get(vertex);
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(int done, int total);
    }

    public record Owner(
            String path, int vertices, int steadyBitsChanged, int colorsChanged, int timeSamplesChanged, long rays) {}

    public record Report(String map, boolean all, List<Owner> owners, List<String> notes) {
        public Report {
            owners = List.copyOf(owners);
            notes = List.copyOf(notes);
        }

        public static Report empty(String map) {
            return new Report(map, false, List.of(), List.of());
        }

        public int vertices() {
            var result = 0;
            for (var owner : owners) {
                result += owner.vertices();
            }
            return result;
        }
    }

    public record Result(@Nullable NativePackageChanges changes, Report report) {}

    private static boolean isChanged(LightingEdit edit) {
        return edit.created() || edit.deleted() || !edit.original().equals(edit.current());
    }

    private static String keptMessage(String owner, String reason) {
        return "The Terrain lighting of " + owner + " keeps the saved actor shadows: " + toClause(reason);
    }

    private static String toClause(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "it cannot be read";
        }
        var end = reason.endsWith(".") ? reason.length() - 1 : reason.length();
        return reason.substring(0, 1).toLowerCase(Locale.ROOT) + reason.substring(1, end);
    }

    private static boolean isLit(Terrain terrain) {
        for (var sector : terrain.sectors()) {
            if (!sector.lights().isEmpty() || sector.hasTimeMaps() != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTerrain(Path map) throws IOException {
        var source = new PackageBytes(map, Files.readAllBytes(map));
        for (var entry : source.exports) {
            if (source.getClassName(entry).equals("TerrainInfo")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSunlit(TerrainLightingScene scene) {
        if (scene.getSlots().isEmpty()) {
            return false;
        }
        for (var source : scene.getTimeSources()) {
            if (NativeTerrainTimeLighting.isAdmitted(scene.getTimeOwner(), source)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSunEligible(BitSet visibleQuads, int width, int height, int x, int y) {
        var index = y * width + x;
        return x == 0
                || y == 0
                || x == width - 1
                || y == height - 1
                || visibleQuads.get(index)
                || visibleQuads.get(index - 1)
                || visibleQuads.get(index - width)
                || visibleQuads.get(index - width - 1);
    }

    private static boolean reaches(Vector center, float radius, List<float[]> boxes) {
        for (var box : boxes) {
            var gap = 0.0;
            for (var axis = 0; axis < 3; axis++) {
                var value = axis == 0 ? center.x() : axis == 1 ? center.y() : center.z();
                var d = Math.max(0.0, Math.max(box[axis] - value, value - box[axis + 3]));
                gap += d * d;
            }
            var reach = radius + 4.0;
            if (gap <= reach * reach) {
                return true;
            }
        }
        return false;
    }

    private static SegmentBvh buildHierarchy(List<float[]> boxes) {
        var bounds = new float[boxes.size() * 6];
        for (var i = 0; i < boxes.size(); i++) {
            for (var axis = 0; axis < 3; axis++) {
                bounds[i * 6 + axis] = boxes.get(i)[axis] - BOX_MARGIN;
                bounds[i * 6 + 3 + axis] = boxes.get(i)[axis + 3] + BOX_MARGIN;
            }
        }
        return new SegmentBvh(bounds);
    }

    private static boolean crosses(float sx, float sy, float sz, float dx, float dy, float dz, SegmentBvh hierarchy) {
        return hierarchy.hasAnyHit(sx, sy, sz, sx + dx, sy + dy, sz + dz, index -> true);
    }

    private static float[] toBoxArray(Box box) {
        return new float[] {
            box.min().x(),
            box.min().y(),
            box.min().z(),
            box.max().x(),
            box.max().y(),
            box.max().z()
        };
    }

    private static String mapName(Path source) {
        var file = source.getFileName().toString();
        var dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Terrain lighting rebuild cancelled");
        }
    }
}
