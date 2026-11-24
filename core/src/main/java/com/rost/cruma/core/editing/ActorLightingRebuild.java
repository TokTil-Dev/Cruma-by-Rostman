package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingBaker.Status;
import com.rost.cruma.core.editing.ActorLightingChanges.Actor;
import com.rost.cruma.core.editing.ActorLightingChanges.Light;
import com.rost.cruma.core.editing.ActorLightingScene.Bake;
import com.rost.cruma.core.editing.ActorLightingScene.Mesh;
import com.rost.cruma.core.editing.ActorLightingScene.Receiver;
import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.BeastCalibration.Agreement;
import com.rost.cruma.core.editing.BspLightmapRebuild.TerrainChange;
import com.rost.cruma.core.editing.PackageEditDocument.LightingEdit;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.world.NativeActorTransformPair;
import com.rost.cruma.core.world.NativeActorTransformPair.Parameters;
import com.rost.cruma.core.world.NativeActorTransformPair.Rotation;
import com.rost.cruma.core.world.NativeActorTransformPair.Vector;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class ActorLightingRebuild {
    public static final float NEIGHBOR_RADIUS = 2048f;

    private ActorLightingRebuild() {}

    public record Options(
            boolean allActors,
            boolean neighbors,
            float neighborRadius,
            boolean bakedColors,
            int threads,
            boolean beast,
            boolean keepNeighborBeast) {
        public Options(boolean allActors, boolean neighbors, float neighborRadius, boolean bakedColors, int threads) {
            this(allActors, neighbors, neighborRadius, bakedColors, threads, false, true);
        }

        public Options {
            if (!Float.isFinite(neighborRadius) || neighborRadius < 0 || threads < 1) {
                throw new IllegalArgumentException("Invalid lighting rebuild options");
            }
        }

        public static Options export(boolean allActors) {
            return export(allActors, false);
        }

        public static Options export(boolean allActors, boolean keepNeighborBeast) {
            return new Options(allActors, true, NEIGHBOR_RADIUS, false, defaultThreads(), true, keepNeighborBeast);
        }

        public static Options preview() {
            return new Options(false, false, 0f, true, Math.max(1, Math.min(2, defaultThreads())));
        }

        public static int defaultThreads() {
            return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(int done, int total);
    }

    public record BeastSummary(
            int baked,
            int transferred,
            int neighborsUpdated,
            int neighborsUnchanged,
            long texels,
            String model,
            Agreement slot0,
            Agreement slot1,
            int samples,
            int bspSurfaces,
            long bspTexels,
            @Nullable Agreement bspSlot0,
            int terrainOwners,
            long terrainTexels,
            @Nullable Agreement terrainSlot0) {
        public BeastSummary(
                int baked,
                int transferred,
                int neighborsUpdated,
                int neighborsUnchanged,
                long texels,
                String model,
                Agreement slot0,
                Agreement slot1,
                int samples) {
            this(
                    baked,
                    transferred,
                    neighborsUpdated,
                    neighborsUnchanged,
                    texels,
                    model,
                    slot0,
                    slot1,
                    samples,
                    0,
                    0,
                    null,
                    0,
                    0,
                    null);
        }
    }

    public record Report(
            String map,
            int rebuilt,
            int edited,
            int neighbors,
            int noInstance,
            int hidden,
            int skipped,
            int beastKept,
            long rays,
            List<String> notes,
            @Nullable BeastSummary beast) {
        public Report {
            notes = List.copyOf(notes);
        }

        public Report(
                String map,
                int rebuilt,
                int edited,
                int neighbors,
                int noInstance,
                int hidden,
                int skipped,
                int beastKept,
                long rays,
                List<String> notes) {
            this(map, rebuilt, edited, neighbors, noInstance, hidden, skipped, beastKept, rays, notes, null);
        }

        public Report(
                String map,
                int rebuilt,
                int edited,
                int neighbors,
                int noInstance,
                int hidden,
                int skipped,
                long rays,
                List<String> notes) {
            this(map, rebuilt, edited, neighbors, noInstance, hidden, skipped, 0, rays, notes);
        }

        public static Report empty(String map) {
            return new Report(map, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }

    public record Result(
            ActorLightingChanges changes,
            Report report,
            @Nullable NativePackageChanges bsp) {
        public Result(ActorLightingChanges changes, Report report) {
            this(changes, report, null);
        }
    }

    public static boolean isNeeded(PackageEditDocument document) {
        if (!document.getLightColorEdits().isEmpty()
                || !document.getLightPlacementEdits().isEmpty()) {
            return true;
        }
        for (var edit : document.getLightingEdits()) {
            if (isLightingChange(edit)) {
                return true;
            }
        }
        return false;
    }

    public static Result rebuild(
            PackageEditDocument document,
            UArchiveLoader archives,
            Settings settings,
            Options options,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return rebuild(document, archives, settings, options, (Set<String>) null, progress, cancelled);
    }

    public static Result rebuild(
            PackageEditDocument document,
            UArchiveLoader archives,
            Settings settings,
            Options options,
            List<TerrainChange> terrain,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return rebuild(document, archives, settings, options, null, terrain, progress, cancelled);
    }

    public static Result rebuild(
            PackageEditDocument document,
            UArchiveLoader archives,
            Settings settings,
            Options options,
            Set<String> only,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return rebuild(document, archives, settings, options, only, List.of(), progress, cancelled);
    }

    static float @Nullable [] worldBox(UStaticMesh mesh, ActorTransform transform) {
        try {
            var pair = new NativeActorTransformPair(new Parameters(
                    new Vector(
                            transform.location().x(),
                            transform.location().y(),
                            transform.location().z()),
                    new Vector(
                            transform.prePivot().x(),
                            transform.prePivot().y(),
                            transform.prePivot().z()),
                    new Rotation(
                            transform.rotation().pitch(),
                            transform.rotation().yaw(),
                            transform.rotation().roll()),
                    transform.drawScale(),
                    new Vector(
                            transform.drawScale3d().x(),
                            transform.drawScale3d().y(),
                            transform.drawScale3d().z())));
            return ActorLightingCapture.transformBox(
                    mesh.getBoundingBox().min(), mesh.getBoundingBox().max(), pair.getLocalToWorld());
        } catch (IllegalArgumentException singular) {
            return null;
        }
    }

    private static Result rebuild(
            PackageEditDocument document,
            UArchiveLoader archives,
            Settings settings,
            Options options,
            Set<String> only,
            List<TerrainChange> terrain,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var result = rebuildActors(document, archives, settings, options, only, terrain, progress, cancelled);
        return only != null || options.bakedColors() ? result : withRecoloredLights(document, result);
    }

    private static Result withRecoloredLights(PackageEditDocument document, Result result) throws IOException {
        var lights = new HashSet<Integer>();
        for (var edit : document.getLightColorEdits()) {
            lights.add(edit.exportIndex());
        }
        if (lights.isEmpty()) {
            return result;
        }
        var found = document.getUnappliedLighting(lights);
        var unapplied = new LinkedHashMap<>(found.actors());
        unapplied.keySet().removeAll(result.changes().getActors().keySet());
        if (unapplied.isEmpty() && found.beastKept() == 0) {
            return result;
        }
        var actors = new LinkedHashMap<>(result.changes().getActors());
        actors.putAll(unapplied);
        var report = result.report();
        var notes = new ArrayList<>(report.notes());
        if (!unapplied.isEmpty()) {
            notes.add(unapplied.size()
                    + (unapplied.size() == 1 ? " static mesh actor takes" : " static mesh actors take")
                    + " the new light colour when the game lights them");
        }
        if (found.beastKept() > 0) {
            notes.add(found.beastKept()
                    + (found.beastKept() == 1 ? " static mesh actor keeps" : " static mesh actors keep")
                    + " the old light colour in its Beast bake");
        }
        return new Result(
                new ActorLightingChanges(actors, result.changes().getTextures()),
                new Report(
                        report.map(),
                        report.rebuilt(),
                        report.edited(),
                        report.neighbors(),
                        report.noInstance(),
                        report.hidden(),
                        report.skipped(),
                        report.beastKept(),
                        report.rays(),
                        notes,
                        report.beast()),
                result.bsp());
    }

    private static Result rebuildActors(
            PackageEditDocument document,
            UArchiveLoader archives,
            Settings settings,
            Options options,
            Set<String> only,
            List<TerrainChange> terrain,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(progress);
        var name = mapName(document);
        var edits = document.getLightingEdits();
        var changed = new ArrayList<LightingEdit>();
        for (var edit : edits) {
            if (isLightingChange(edit)) {
                changed.add(edit);
            }
        }
        if (only != null) {
            changed.removeIf(edit -> !only.contains(edit.path()));
            for (var path : only) {
                var found = document.findActor(path);
                if (found.isPresent()
                        && !found.get().pawn()
                        && changed.stream().noneMatch(edit -> edit.path().equals(path))) {
                    var current = found.orElseThrow();
                    changed.add(new LightingEdit(
                            path,
                            current.created(),
                            false,
                            current.transform(),
                            current.transform(),
                            current.editable()));
                }
            }
        }
        var strokes = terrain.stream().anyMatch(change -> !change.isEmpty());
        var spheres = new ArrayList<float[]>();
        if (only == null) {
            for (var edit : document.getLightPlacementEdits()) {
                for (var placement : edit.placements()) {
                    var location = placement.location();
                    spheres.add(new float[] {location.x(), location.y(), location.z(), placement.reach()});
                }
            }
        }
        if (changed.isEmpty() && !options.allActors() && spheres.isEmpty()) {
            if (strokes && options.beast()) {
                return beastOnly(document, name, archives, settings, options, terrain, progress, cancelled);
            }
            return new Result(ActorLightingChanges.empty(), Report.empty(name));
        }
        var body = document.previewBody();
        var archive = new UArchive(name, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), archives);
        try {
            if (!ActorLightingCapture.hasLightingData(archive)) {
                return new Result(
                        ActorLightingChanges.empty(),
                        new Report(
                                name,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                List.of("The map has no BSP model with light lists, so its lighting was not rebuilt")));
            }
            ActorLightingScene scene;
            try {
                scene = ActorLightingCapture.capture(archive, settings, null, options.beast(), cancelled);
            } catch (CancellationException stopped) {
                throw new InterruptedIOException("Lighting rebuild cancelled");
            } catch (RuntimeException failure) {
                throw new IOException(
                        "Cannot read the lighting scene of " + name + ": " + failure.getMessage(), failure);
            }
            var selection = select(document, archive, scene, changed, spheres, options);
            var results = bake(scene, selection.paths(), options, progress, cancelled);
            var converted = convert(name, scene, selection, results, options);
            if (!options.beast() || scene.getBeastTrace() == null) {
                return converted;
            }
            var editedPaths = new LinkedHashSet<String>();
            for (var edit : changed) {
                if (!edit.deleted() && converted.changes().getActors().containsKey(edit.path())) {
                    editedPaths.add(edit.path());
                }
            }
            var beast = BeastRebuild.run(
                    document,
                    name,
                    archives,
                    settings,
                    scene,
                    converted.changes().getActors(),
                    editedPaths,
                    changed,
                    terrain,
                    !options.keepNeighborBeast(),
                    options.allActors(),
                    options.neighborRadius(),
                    options.threads(),
                    progress,
                    cancelled);
            var report = converted.report();
            var notes = new ArrayList<>(report.notes());
            notes.addAll(beast.notes());
            var kept = beast.summary() == null || options.keepNeighborBeast()
                    ? report.beastKept()
                    : beast.summary().neighborsUnchanged();
            return new Result(
                    new ActorLightingChanges(beast.actors(), beast.textures()),
                    new Report(
                            report.map(),
                            report.rebuilt(),
                            report.edited(),
                            report.neighbors(),
                            report.noInstance(),
                            report.hidden(),
                            report.skipped(),
                            kept,
                            report.rays(),
                            notes,
                            beast.summary()),
                    beast.bsp());
        } finally {
            archive.close();
        }
    }

    private static Result beastOnly(
            PackageEditDocument document,
            String name,
            UArchiveLoader archives,
            Settings settings,
            Options options,
            List<TerrainChange> terrain,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        if (options.keepNeighborBeast()) {
            return new Result(ActorLightingChanges.empty(), Report.empty(name));
        }
        var archive =
                new UArchive(name, ByteBuffer.wrap(document.previewBody()).order(ByteOrder.LITTLE_ENDIAN), archives);
        try {
            if (!ActorLightingCapture.hasBeastTextures(archive) || !ActorLightingCapture.hasLightingData(archive)) {
                return new Result(ActorLightingChanges.empty(), Report.empty(name));
            }
            ActorLightingScene scene;
            try {
                scene = ActorLightingCapture.capture(archive, settings, null, true, cancelled);
            } catch (CancellationException stopped) {
                throw new InterruptedIOException("Lighting rebuild cancelled");
            } catch (RuntimeException failure) {
                throw new IOException(
                        "Cannot read the lighting scene of " + name + ": " + failure.getMessage(), failure);
            }
            if (scene.getBeastTrace() == null) {
                return new Result(ActorLightingChanges.empty(), Report.empty(name));
            }
            var beast = BeastRebuild.run(
                    document,
                    name,
                    archives,
                    settings,
                    scene,
                    Map.of(),
                    Set.of(),
                    List.of(),
                    terrain,
                    true,
                    false,
                    options.neighborRadius(),
                    options.threads(),
                    progress,
                    cancelled);
            var kept = beast.summary() == null ? 0 : beast.summary().neighborsUnchanged();
            return new Result(
                    new ActorLightingChanges(beast.actors(), beast.textures()),
                    new Report(name, 0, 0, 0, 0, 0, 0, kept, 0, beast.notes(), beast.summary()),
                    beast.bsp());
        } finally {
            archive.close();
        }
    }

    private record Selection(LinkedHashSet<String> paths, int edited, int neighbors, int skipped, int beastKept) {}

    private static boolean isLightingChange(LightingEdit edit) {
        return edit.created() || edit.deleted() || !edit.original().equals(edit.current());
    }

    private static Selection select(
            PackageEditDocument document,
            UArchive archive,
            ActorLightingScene scene,
            List<LightingEdit> changed,
            List<float[]> spheres,
            Options options) {
        var paths = new LinkedHashSet<String>();
        var skipped = 0;
        var edited = 0;
        var boxes = new ArrayList<float[]>();
        var meshes = meshesByActor(archive);
        for (var edit : changed) {
            var mesh = meshes.get(edit.path());
            if (!edit.created() && mesh != null) {
                var box = worldBox(mesh, edit.original());
                if (box != null) {
                    boxes.add(box);
                }
            }
            if (edit.deleted()) {
                continue;
            }
            var receiver = scene.getReceiver(edit.path());
            if (receiver == null) {
                continue;
            }
            boxes.add(receiver.bounds());
            if (document.canWriteLighting(edit.path())) {
                paths.add(edit.path());
                edited++;
            } else {
                skipped++;
            }
        }
        var neighbors = 0;
        var beastKept = 0;
        var nearby = options.neighbors() && scene.getBake() != Bake.EARLY;
        for (var receiver : scene.getReceivers().values()) {
            if (!options.allActors() && !nearby && spheres.isEmpty()) {
                break;
            }
            if (paths.contains(receiver.path())
                    || document.findActor(receiver.path()).isEmpty()) {
                continue;
            }
            if (!options.allActors()
                    && !(nearby && isNear(receiver.bounds(), boxes, options.neighborRadius()))
                    && !isLitBy(receiver.bounds(), spheres)) {
                continue;
            }
            if (!document.hasStaticMeshInstance(receiver.path())
                    || !document.canWriteLighting(receiver.path())
                    || document.hasBeastLighting(receiver.path())) {
                if (document.hasBeastLighting(receiver.path())) {
                    beastKept++;
                }
                continue;
            }
            paths.add(receiver.path());
            neighbors++;
        }
        return new Selection(paths, edited, neighbors, skipped, beastKept);
    }

    private static Map<String, ActorLightingBaker.Result> bake(
            ActorLightingScene scene,
            LinkedHashSet<String> paths,
            Options options,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var total = paths.size();
        progress.update(0, total);
        var results = new LinkedHashMap<String, ActorLightingBaker.Result>();
        if (total == 0) {
            return results;
        }
        var threads = Math.min(options.threads(), total);
        var executor = Executors.newFixedThreadPool(
                threads,
                Thread.ofPlatform().daemon().name("editor-lighting-", 0).factory());
        var queries = ThreadLocal.withInitial(() -> scene.getTrace().query());
        var done = new AtomicInteger();
        try {
            var futures = new LinkedHashMap<String, Future<ActorLightingBaker.Result>>();
            for (var path : paths) {
                var receiver = scene.getReceiver(path);
                futures.put(path, executor.submit(() -> {
                    var result = ActorLightingBaker.bake(scene, receiver, queries.get(), cancelled);
                    progress.update(done.incrementAndGet(), total);
                    return result;
                }));
            }
            for (var entry : futures.entrySet()) {
                try {
                    results.put(entry.getKey(), entry.getValue().get());
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof CancellationException) {
                        throw new InterruptedIOException("Lighting rebuild cancelled");
                    }
                    throw new IOException(
                            "Lighting rebuild failed for " + entry.getKey() + ": "
                                    + failure.getCause().getMessage(),
                            failure.getCause());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Lighting rebuild cancelled");
        } finally {
            executor.shutdownNow();
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Lighting rebuild cancelled");
        }
        return results;
    }

    private static Result convert(
            String name,
            ActorLightingScene scene,
            Selection selection,
            Map<String, ActorLightingBaker.Result> results,
            Options options) {
        var actors = new LinkedHashMap<String, Actor>();
        var noInstance = 0;
        var hidden = 0;
        long rays = 0;
        for (var entry : results.entrySet()) {
            var result = entry.getValue();
            rays += result.rays();
            if (result.status() == Status.NO_INSTANCE) {
                noInstance++;
                continue;
            }
            if (result.status() == Status.HIDDEN) {
                hidden++;
                continue;
            }
            var receiver = scene.getReceiver(entry.getKey());
            var lods = receiver.lods();
            var lod0 = toChangeInstance(scene, receiver, lods[0], result.lod(0), options);
            var lod1 =
                    result.lod(1) == null ? null : toChangeInstance(scene, receiver, lods[1], result.lod(1), options);
            var shared = result.lod(2) != null && result.lod(2).shared();
            var lod2 = result.lod(2) == null || shared
                    ? null
                    : toChangeInstance(scene, receiver, lods[2], result.lod(2), options);
            actors.put(entry.getKey(), new Actor(lod0, lod1, lod2, shared));
        }
        var notes = new ArrayList<>(scene.getNotes());
        var report = new Report(
                name,
                actors.size(),
                selection.edited(),
                selection.neighbors(),
                noInstance,
                hidden,
                selection.skipped(),
                selection.beastKept(),
                rays,
                notes);
        return new Result(new ActorLightingChanges(actors), report);
    }

    private static ActorLightingChanges.Instance toChangeInstance(
            ActorLightingScene scene,
            Receiver receiver,
            Mesh mesh,
            ActorLightingBaker.Instance baked,
            Options options) {
        var lights = scene.getLights();
        var sceneLights = new ArrayList<Light>();
        for (var entry : baked.sceneLights()) {
            var light = lights.get(entry.light());
            sceneLights.add(new Light(light.reference(), entry.bits(), options.bakedColors() ? 1 : 0));
        }
        var environment = new ArrayList<Light>();
        for (var entry : baked.environmentLights()) {
            environment.add(new Light(lights.get(entry.light()).reference(), entry.bits(), 0));
        }
        var colors = options.bakedColors()
                ? ActorLightingBaker.sceneColors(scene, receiver, mesh, baked)
                : new byte[baked.vertexCount() * 4];
        return new ActorLightingChanges.Instance(
                baked.vertexCount(), colors, options.bakedColors() ? 2 : 1, sceneLights, environment);
    }

    private static boolean isLitBy(float[] box, List<float[]> spheres) {
        for (var sphere : spheres) {
            var gap = 0.0;
            for (var axis = 0; axis < 3; axis++) {
                var d = Math.max(0.0, Math.max(box[axis] - sphere[axis], sphere[axis] - box[axis + 3]));
                gap += d * d;
            }
            if (gap <= (double) sphere[3] * sphere[3]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNear(float[] box, List<float[]> others, float radius) {
        for (var other : others) {
            var gap = 0.0;
            for (var axis = 0; axis < 3; axis++) {
                var d = Math.max(0.0, Math.max(other[axis] - box[axis + 3], box[axis] - other[axis + 3]));
                gap += d * d;
            }
            if (gap <= (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, UStaticMesh> meshesByActor(UArchive archive) {
        var actors = new ArrayList<StaticMeshActor>();
        for (var type : new String[] {"StaticMeshActor", "MovableStaticMeshActor", "L2MovableStaticMeshActor"}) {
            archive.loadObjects(type, actors);
        }
        var result = new HashMap<String, UStaticMesh>();
        for (var actor : actors) {
            try {
                var mesh = actor.getStaticMesh().get();
                if (mesh != null) {
                    result.putIfAbsent(actor.getSourcePath(), mesh);
                }
            } catch (RuntimeException missing) {
            }
        }
        return result;
    }

    private static String mapName(PackageEditDocument document) {
        var file = document.getSource().getFileName().toString();
        var dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }
}
