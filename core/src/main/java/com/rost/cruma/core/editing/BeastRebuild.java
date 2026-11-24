package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingChanges.Actor;
import com.rost.cruma.core.editing.ActorLightingChanges.BeastBake;
import com.rost.cruma.core.editing.ActorLightingChanges.BeastTexture;
import com.rost.cruma.core.editing.ActorLightingChanges.Instance;
import com.rost.cruma.core.editing.ActorLightingRebuild.BeastSummary;
import com.rost.cruma.core.editing.ActorLightingRebuild.Progress;
import com.rost.cruma.core.editing.ActorLightingScene.Mesh;
import com.rost.cruma.core.editing.ActorLightingScene.Receiver;
import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.BeastBaker.LocalLights;
import com.rost.cruma.core.editing.BeastCalibration.Sample;
import com.rost.cruma.core.editing.BeastWorldUpdate.Bsp;
import com.rost.cruma.core.editing.BeastWorldUpdate.Terrain;
import com.rost.cruma.core.editing.BspLightmapRebuild.TerrainChange;
import com.rost.cruma.core.editing.PackageEditDocument.LightingEdit;
import com.rost.cruma.core.editing.PackageEditDocument.SavedBeast;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class BeastRebuild {
    static final int CALIBRATION_ACTORS = 240;

    static final int CALIBRATION_TEXELS = 40;

    static final int DEFAULT_SIZE = 64;

    private BeastRebuild() {}

    record Result(
            Map<String, Actor> actors,
            Map<Integer, BeastTexture> textures,
            @Nullable BeastSummary summary,
            List<String> notes,
            @Nullable NativePackageChanges bsp) {
        Result(
                Map<String, Actor> actors,
                Map<Integer, BeastTexture> textures,
                @Nullable BeastSummary summary,
                List<String> notes) {
            this(actors, textures, summary, notes, null);
        }
    }

    private record Source(
            String path, SavedBeast saved, float[] matrix, float[][] images, List<byte[]> mips0, List<byte[]> mips1) {}

    static Result run(
            PackageEditDocument document,
            String name,
            UArchiveLoader archives,
            Settings settings,
            ActorLightingScene editedScene,
            Map<String, Actor> actors,
            Set<String> editedPaths,
            List<LightingEdit> changed,
            List<TerrainChange> terrain,
            boolean neighbors,
            boolean allActors,
            float neighborRadius,
            int threads,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var notes = new ArrayList<String>();
        if (editedScene.getBeastTrace() == null) {
            return new Result(actors, Map.of(), null, notes);
        }
        if (!document.canWriteBeastLightmaps()) {
            notes.add("The map has no Beast lightmap texture to copy, so edited actors use vertex lighting");
            return new Result(actors, Map.of(), null, notes);
        }
        var originalArchive = new UArchive(
                name, ByteBuffer.wrap(document.getOriginalBody()).order(ByteOrder.LITTLE_ENDIAN), archives);
        try {
            ActorLightingScene originalScene;
            try {
                originalScene = ActorLightingCapture.capture(originalArchive, settings, cancelled);
            } catch (CancellationException stopped) {
                throw new InterruptedIOException("Lighting rebuild cancelled");
            } catch (RuntimeException failure) {
                throw new IOException(
                        "Cannot read the lighting scene of " + name + " before the edits: " + failure.getMessage(),
                        failure);
            }
            if (originalScene.getBeastTrace() == null) {
                return new Result(actors, Map.of(), null, notes);
            }
            var lights = LocalLights.of(editedScene);
            var changedPaths = new HashSet<String>();
            for (var edit : changed) {
                changedPaths.add(edit.path());
            }
            var fit = calibrate(document, originalScene, lights, changedPaths, threads, cancelled);
            var model = fit.model();
            var heights = new HashMap<String, TerrainHeightField>();
            var stroked = new HashSet<String>();
            var boxes = new ArrayList<>(changedBoxes(changed, originalScene, editedScene));
            for (var change : terrain) {
                if (!change.isEmpty()) {
                    heights.put(change.owner(), change.edited());
                    stroked.add(change.owner());
                    boxes.add(change.box());
                }
            }
            var editedTrace = editedScene.getBeastTraceWith(heights);
            var tasks = new ArrayList<Runnable>();
            var result = new TreeMap<String, Actor>(String.CASE_INSENSITIVE_ORDER);
            result.putAll(actors);
            var textures = new TreeMap<Integer, BeastTexture>();
            var counts = new int[5];
            var texels = new long[1];
            var failures = new ArrayList<String>();
            for (var path : editedPaths) {
                var actor = actors.get(path);
                var receiver = editedScene.getReceiver(path);
                if (actor == null || receiver == null) {
                    continue;
                }
                tasks.add(() -> {
                    var instances = new Instance[] {actor.lod0(), actor.lod1(), actor.lod2()};
                    for (var lod = 0; lod < 3; lod++) {
                        var mesh = receiver.lods()[lod];
                        if (instances[lod] == null || mesh == null) {
                            continue;
                        }
                        if (!mesh.hasBeastLayout()) {
                            synchronized (counts) {
                                counts[4]++;
                            }
                            continue;
                        }
                        try {
                            var source = findSource(document, originalScene, path, lod, mesh);
                            var size = source != null
                                    ? new int[] {
                                        source.saved().width(), source.saved().height()
                                    }
                                    : size(document, originalScene, mesh, lod);
                            var images = BeastRebake.bakeFull(
                                    editedTrace,
                                    lights,
                                    model,
                                    mesh,
                                    receiver.localToWorld(),
                                    size[0],
                                    size[1],
                                    cancelled);
                            if (source != null) {
                                var moved = BeastRebake.transferBake(
                                        source.images(),
                                        originalScene.getBeastTrace(),
                                        editedTrace,
                                        lights,
                                        model,
                                        mesh,
                                        source.matrix(),
                                        receiver.localToWorld(),
                                        size[0],
                                        size[1],
                                        cancelled);
                                images = BeastRebake.combine(images, moved);
                            }
                            instances[lod] = instances[lod].withBeast(new BeastBake(
                                    encodeTexture(images[0], size[0], size[1]),
                                    encodeTexture(images[1], size[0], size[1])));
                            synchronized (counts) {
                                counts[source != null ? 1 : 0]++;
                            }
                        } catch (IOException failure) {
                            synchronized (failures) {
                                failures.add(path + ": " + failure.getMessage());
                            }
                        }
                    }
                    synchronized (result) {
                        result.put(path, new Actor(instances[0], instances[1], instances[2], actor.lod2SharesLod1()));
                    }
                });
            }
            if (neighbors) {
                for (var receiver : sortReceivers(originalScene)) {
                    var path = receiver.path();
                    if (changedPaths.contains(path) || document.findActor(path).isEmpty()) {
                        continue;
                    }
                    if (!allActors && !isNear(receiver.bounds(), boxes, neighborRadius)) {
                        continue;
                    }
                    var saved = document.getSavedBeast(path);
                    if (saved.isEmpty()) {
                        continue;
                    }
                    tasks.add(() -> {
                        var updated = false;
                        for (var bake : saved) {
                            var mesh = receiver.lods()[bake.lod()];
                            if (mesh == null || !mesh.hasBeastLayout()) {
                                continue;
                            }
                            try {
                                var source = load(document, path, bake, receiver.localToWorld());
                                var update = BeastRebake.rebakeDifferential(
                                        source.images(),
                                        originalScene.getBeastTrace(),
                                        editedTrace,
                                        lights,
                                        model,
                                        mesh,
                                        receiver.localToWorld(),
                                        bake.width(),
                                        bake.height(),
                                        boxes,
                                        cancelled);
                                if (update.changedTexels() == 0) {
                                    continue;
                                }
                                updated = true;
                                var slot0 = changedTexture(
                                        source.mips0(), update.slot0(), update.changed(), bake.width(), bake.height());
                                var slot1 = changedTexture(
                                        source.mips1(), update.slot1(), update.changed(), bake.width(), bake.height());
                                synchronized (textures) {
                                    textures.put(bake.slot0(), slot0);
                                    textures.put(bake.slot1(), slot1);
                                    texels[0] += update.changedTexels();
                                }
                            } catch (IOException failure) {
                                synchronized (failures) {
                                    failures.add(path + ": " + failure.getMessage());
                                }
                            }
                        }
                        synchronized (counts) {
                            counts[updated ? 2 : 3]++;
                        }
                    });
                }
            }
            run(tasks, threads, progress, cancelled);
            if (counts[4] > 0) {
                notes.add(counts[4] + (counts[4] == 1 ? " edited instance has" : " edited instances have")
                        + " a mesh without Beast lightmap UVs and uses vertex lighting");
            }
            if (!failures.isEmpty()) {
                notes.add("Beast bakes kept for " + failures.size()
                        + " lightmaps that Cruma could not read, for example " + failures.getFirst());
            }
            var bsp = Bsp.NONE;
            var ground = new Terrain(Map.of(), 0, 0, null, null, List.of());
            if (neighbors && !boxes.isEmpty()) {
                try {
                    bsp = BeastWorldUpdate.updateBsp(
                            document,
                            name,
                            originalArchive,
                            originalScene,
                            editedTrace,
                            lights,
                            boxes,
                            threads,
                            cancelled);
                    ground = BeastWorldUpdate.updateTerrain(
                            document, originalArchive, originalScene, editedTrace, boxes, stroked, threads, cancelled);
                } catch (CancellationException stopped) {
                    throw new InterruptedIOException("Lighting rebuild cancelled");
                }
                notes.addAll(bsp.notes());
                notes.addAll(ground.notes());
                textures.putAll(ground.textures());
            }
            var summary = new BeastSummary(
                    counts[0],
                    counts[1],
                    counts[2],
                    counts[3],
                    texels[0],
                    model.describe(),
                    fit.slot0After(),
                    fit.slot1After(),
                    fit.samples(),
                    bsp.surfaces(),
                    bsp.texels(),
                    bsp.slot0(),
                    ground.owners(),
                    ground.texels(),
                    ground.slot0());
            return new Result(result, textures, summary, notes, bsp.changes());
        } finally {
            originalArchive.close();
        }
    }

    static BeastCalibration.Result calibrate(
            PackageEditDocument document,
            ActorLightingScene scene,
            LocalLights lights,
            Set<String> excluded,
            int threads,
            BooleanSupplier cancelled)
            throws IOException {
        var candidates = new ArrayList<Receiver>();
        for (var receiver : sortReceivers(scene)) {
            if (excluded.contains(receiver.path()) || !receiver.lods()[0].hasBeastLayout()) {
                continue;
            }
            var saved = document.getSavedBeast(receiver.path());
            if (!saved.isEmpty() && saved.getFirst().lod() == 0) {
                candidates.add(receiver);
            }
        }
        var samples = new ArrayList<Sample>();
        var step = Math.max(1.0, candidates.size() / (double) CALIBRATION_ACTORS);
        var group = 0;
        for (var at = 0.0; at < candidates.size(); at += step) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("Lighting rebuild cancelled");
            }
            var receiver = candidates.get((int) at);
            var bake = document.getSavedBeast(receiver.path()).getFirst();
            var source = load(document, receiver.path(), bake, receiver.localToWorld());
            var mesh = receiver.lods()[0];
            var texels = BeastTexels.of(mesh, bake.width(), bake.height());
            if (texels.getCount() == 0) {
                continue;
            }
            var near = lights.findReaching(BeastBaker.worldBox(mesh, receiver.localToWorld()));
            var stride = Math.max(1, texels.getCount() / CALIBRATION_TEXELS);
            var g = group++;
            for (var i = 0; i < texels.getCount(); i += stride) {
                var p = new float[3];
                var n = new float[3];
                if (!texels.computeWorldPoint(receiver.localToWorld(), i, p, n)) {
                    continue;
                }
                var t = texels.getTexel(i) * 3;
                samples.add(new Sample(p, n, readRgb(source.images()[0], t), readRgb(source.images()[1], t), near, g));
            }
        }
        try {
            return BeastCalibration.fit(
                    scene.getBeastTrace(), lights, samples, createUncalibratedModel(scene), threads, cancelled);
        } catch (CancellationException stopped) {
            throw new InterruptedIOException("Lighting rebuild cancelled");
        }
    }

    static BeastBakeModel createUncalibratedModel(ActorLightingScene scene) {
        for (var light : scene.getLights()) {
            if (light.effect() == ActorLightingBaker.SUNLIGHT && !light.excluded()) {
                return BeastBakeModel.uncalibrated(
                        new float[] {-light.directionX(), -light.directionY(), -light.directionZ()},
                        new float[] {light.red(), light.green(), light.blue()});
            }
        }
        return BeastBakeModel.uncalibrated(new float[] {0, 0, 1}, new float[] {1, 1, 1});
    }

    static BeastTexture changedTexture(List<byte[]> saved, float[] image, boolean[] changed, int width, int height) {
        var mip0 = BeastTextureCodec.encodeChanged(saved.get(0), image, changed, width, height);
        var halfWidth = Math.max(1, width / 2);
        var halfHeight = Math.max(1, height / 2);
        var halfChanged = new boolean[halfWidth * halfHeight];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                if (changed[y * width + x]) {
                    halfChanged[Math.min(halfHeight - 1, y / 2) * halfWidth + Math.min(halfWidth - 1, x / 2)] = true;
                }
            }
        }
        var mip1 = BeastTextureCodec.encodeChanged(
                saved.get(1), BeastTextureCodec.halve(image, width, height), halfChanged, halfWidth, halfHeight);
        return new BeastTexture(width, height, List.of(mip0, mip1));
    }

    static List<float[]> changedBoxes(List<LightingEdit> changed, ActorLightingScene before, ActorLightingScene after) {
        var boxes = new ArrayList<float[]>();
        for (var edit : changed) {
            var old = edit.created() ? null : before.getReceiver(edit.path());
            if (old != null) {
                boxes.add(old.bounds());
            }
            var current = edit.deleted() ? null : after.getReceiver(edit.path());
            if (current != null) {
                boxes.add(current.bounds());
            }
        }
        return boxes;
    }

    private static @Nullable Source findSource(
            PackageEditDocument document, ActorLightingScene originalScene, String path, int lod, Mesh mesh)
            throws IOException {
        var own = originalScene.getReceiver(path);
        if (own != null) {
            for (var bake : document.getSavedBeast(path)) {
                if (bake.lod() == lod
                        && own.lods()[lod] != null
                        && own.lods()[lod].name().equals(mesh.name())) {
                    return load(document, path, bake, own.localToWorld());
                }
            }
        }
        Receiver best = null;
        SavedBeast bestBake = null;
        var bestDistance = Double.MAX_VALUE;
        var target = own == null ? null : own.localToWorld();
        for (var receiver : sortReceivers(originalScene)) {
            if (receiver.path().equals(path)
                    || receiver.lods()[lod] == null
                    || !receiver.lods()[lod].name().equals(mesh.name())) {
                continue;
            }
            for (var bake : document.getSavedBeast(receiver.path())) {
                if (bake.lod() != lod) {
                    continue;
                }
                var m = receiver.localToWorld();
                var distance = target == null
                        ? 0
                        : Math.pow(m[12] - target[12], 2)
                                + Math.pow(m[13] - target[13], 2)
                                + Math.pow(m[14] - target[14], 2);
                if (best == null || distance < bestDistance) {
                    best = receiver;
                    bestBake = bake;
                    bestDistance = distance;
                }
            }
        }
        return best == null ? null : load(document, best.path(), bestBake, best.localToWorld());
    }

    private static int[] size(PackageEditDocument document, ActorLightingScene originalScene, Mesh mesh, int lod) {
        if (BeastTextureCodec.isValidSize(mesh.beastSize(), mesh.beastSize())) {
            return new int[] {mesh.beastSize(), mesh.beastSize()};
        }
        for (var receiver : sortReceivers(originalScene)) {
            if (receiver.lods()[lod] == null || !receiver.lods()[lod].name().equals(mesh.name())) {
                continue;
            }
            for (var bake : document.getSavedBeast(receiver.path())) {
                if (bake.lod() == lod) {
                    return new int[] {bake.width(), bake.height()};
                }
            }
        }
        return new int[] {DEFAULT_SIZE, DEFAULT_SIZE};
    }

    private static Source load(PackageEditDocument document, String path, SavedBeast bake, float[] matrix)
            throws IOException {
        var slot0 = document.getSavedBeastTexture(bake.slot0());
        var slot1 = document.getSavedBeastTexture(bake.slot1());
        if (slot0.width() != bake.width()
                || slot0.height() != bake.height()
                || slot1.width() != bake.width()
                || slot1.height() != bake.height()) {
            throw new IOException("Beast lightmap textures do not match the instance size");
        }
        var images = new float[][] {
            BeastTextureCodec.decode(slot0.mips().getFirst(), bake.width(), bake.height()),
            BeastTextureCodec.decode(slot1.mips().getFirst(), bake.width(), bake.height())
        };
        return new Source(path, bake, matrix, images, slot0.mips(), slot1.mips());
    }

    private static BeastTexture encodeTexture(float[] image, int width, int height) {
        return new BeastTexture(width, height, BeastTextureCodec.encodeMips(image, width, height));
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

    private static List<Receiver> sortReceivers(ActorLightingScene scene) {
        var receivers = new ArrayList<>(scene.getReceivers().values());
        receivers.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        return receivers;
    }

    private static int[] readRgb(float[] image, int offset) {
        return new int[] {Math.round(image[offset]), Math.round(image[offset + 1]), Math.round(image[offset + 2])};
    }

    private static void run(List<Runnable> tasks, int threads, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        var done = new AtomicInteger();
        progress.update(0, tasks.size());
        if (tasks.isEmpty()) {
            return;
        }
        var executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(threads, tasks.size())),
                Thread.ofPlatform().daemon().name("editor-beast-", 0).factory());
        try {
            var futures = new ArrayList<Future<?>>();
            for (var task : tasks) {
                futures.add(executor.submit(() -> {
                    if (cancelled.getAsBoolean()) {
                        throw new CancellationException("Lighting rebuild cancelled");
                    }
                    task.run();
                    progress.update(done.incrementAndGet(), tasks.size());
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Lighting rebuild cancelled");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof CancellationException) {
                throw new InterruptedIOException("Lighting rebuild cancelled");
            }
            throw new IOException(
                    "Beast lightmap rebuild failed: " + failure.getCause().getMessage(), failure.getCause());
        } finally {
            executor.shutdownNow();
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Lighting rebuild cancelled");
        }
    }
}
