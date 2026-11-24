package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.BspLightingScene.Light;
import com.rost.cruma.core.editing.BspLightmapBuilder.Rebuilt;
import com.rost.cruma.core.editing.BspLightmapTail.Entry;
import com.rost.cruma.core.editing.BspLightmapTail.Lightmap;
import com.rost.cruma.core.editing.BspLightmapTail.Texels;
import com.rost.cruma.core.editing.BspLightmapTail.Variant;
import com.rost.cruma.core.editing.BspLightmapTail.VariantKey;
import com.rost.cruma.core.editing.NativePackageChanges.Placer;
import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

public final class BspLightmapRebuild {
    private static final float MARGIN = 2f;

    private BspLightmapRebuild() {}

    public record Options(boolean allLightmaps, int threads) {
        public Options {
            if (threads < 1) {
                throw new IllegalArgumentException("Invalid BSP lighting threads");
            }
        }

        public static Options export(boolean allLightmaps) {
            return new Options(allLightmaps, ActorLightingRebuild.Options.defaultThreads());
        }

        public static Options preview() {
            return new Options(false, Math.max(1, Math.min(2, ActorLightingRebuild.Options.defaultThreads())));
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(int done, int total);
    }

    public record TerrainChange(String owner, TerrainHeightField edited, float[] box) {
        public TerrainChange {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(edited);
            if (box.length != 6) {
                throw new IllegalArgumentException("A Terrain change box has six values");
            }
            box = box.clone();
        }

        public boolean isEmpty() {
            return !(box[0] <= box[3] && box[1] <= box[4] && box[2] <= box[5]);
        }
    }

    public record Report(
            String map, int lightmaps, int surfaces, int lights, long texels, int blocks, List<String> notes) {
        public Report {
            notes = List.copyOf(notes);
        }

        public static Report empty(String map) {
            return new Report(map, 0, 0, 0, 0, 0, List.of());
        }
    }

    public record Result(@Nullable NativePackageChanges changes, Report report, List<Page> pages) {
        public Result {
            pages = List.copyOf(pages);
        }

        public Result(@Nullable NativePackageChanges changes, Report report) {
            this(changes, report, List.of());
        }
    }

    public record Page(int texture, int variant, int width, int height, byte[] texels, byte[] saved) {
        public int key() {
            return texture * 16 + variant;
        }
    }

    public static TerrainChange terrainChange(
            String owner, TerrainHeightField original, TerrainHeightField edited, TerrainHeightPatch patch) {
        var box = new float[] {
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        };
        for (var i = 0; i < patch.size(); i++) {
            var x = patch.getIndex(i) % patch.getWidth();
            var y = patch.getIndex(i) / patch.getWidth();
            for (var dy = -1; dy <= 1; dy++) {
                for (var dx = -1; dx <= 1; dx++) {
                    var sx = Math.clamp(x + dx, 0, edited.getWidth() - 1);
                    var sy = Math.clamp(y + dy, 0, edited.getHeight() - 1);
                    for (var field : new TerrainHeightField[] {original, edited}) {
                        var point = field.getWorldAt(sx, sy);
                        include(box, point.x(), point.y(), point.z());
                    }
                }
            }
        }
        return new TerrainChange(owner, edited, box);
    }

    public static boolean isNeeded(PackageEditDocument document, List<TerrainChange> terrain, Options options) {
        return options.allLightmaps()
                || terrain.stream().anyMatch(change -> !change.isEmpty())
                || ActorLightingRebuild.isNeeded(document)
                || !document.getLightColorEdits().isEmpty()
                || !document.getLightPlacementEdits().isEmpty();
    }

    public static Result rebuild(
            PackageEditDocument document,
            UArchiveLoader archives,
            Settings settings,
            List<TerrainChange> terrain,
            Options options,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(progress);
        var name = mapName(document.getSource());
        if (!isNeeded(document, terrain, options)) {
            return new Result(null, Report.empty(name));
        }
        var bytes = Files.readAllBytes(document.getSource());
        if (!PackageEditDocument.sha256(bytes).equals(document.getOriginalSha256())) {
            throw new IOException("Source map changed since editing began; reopen it before saving");
        }
        var source = new PackageBytes(document.getSource(), bytes);
        var body = document.previewBody();
        var edited = new PackageBytes(document.getSource(), body);
        var moved = new HashSet<Integer>();
        var placedLights = new HashSet<Integer>();
        for (var edit : document.getLightPlacementEdits()) {
            moved.add(edit.exportIndex());
            if (edit.current() != null && !edit.current().equals(edit.original())) {
                placedLights.add(edit.exportIndex());
            }
        }
        var archive = new UArchive(name, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), archives);
        var original =
                new UArchive(name, ByteBuffer.wrap(source.body.clone()).order(ByteOrder.LITTLE_ENDIAN), archives);
        try {
            if (hasBeastModel(archive)) {
                return new Result(null, Report.empty(name));
            }
            var heights = new HashMap<String, TerrainHeightField>();
            for (var change : terrain) {
                heights.put(change.owner(), change.edited());
            }
            BspLightingScene scene;
            try {
                scene = BspLightingScene.capture(
                        name,
                        archive,
                        original,
                        edited,
                        settings,
                        heights,
                        moved,
                        document.getOriginalSha256(),
                        cancelled);
            } catch (CancellationException stopped) {
                throw new InterruptedIOException("BSP lighting rebuild cancelled");
            } catch (IOException | RuntimeException unreadable) {
                return new Result(
                        null,
                        new Report(
                                name,
                                0,
                                0,
                                0,
                                0,
                                0,
                                List.of("The BSP lightmaps of " + name + " cannot be read (" + unreadable.getMessage()
                                        + "), so they keep their old values")));
            }
            var notes = new ArrayList<>(scene.notes);
            if (scene.beastLighting) {
                return new Result(null, Report.empty(name));
            }
            if (!scene.isWritable()) {
                return new Result(null, Report.empty(name));
            }
            if (!scene.placed.isEmpty()) {
                var recorded = new HashSet<Integer>();
                for (var lightmap : scene.tail.lightmaps) {
                    recorded.add(lightmap.surface());
                }
                var targets = new HashSet<Integer>();
                for (var index : scene.placed) {
                    if (placedLights.contains(scene.lights.get(index).reference())) {
                        targets.add(index);
                    }
                }
                var unlit = 0;
                for (var surface = 0; surface < scene.surfaces.length; surface++) {
                    if (recorded.contains(surface)
                            || (scene.surfaces[surface].flags()
                                            & (BspLightingScene.HIDDEN_SURFACES | BspLightingScene.UNLIT_SURFACES))
                                    != 0) {
                        continue;
                    }
                    for (var light : scene.candidates[surface]) {
                        if (targets.contains(light)) {
                            unlit++;
                            break;
                        }
                    }
                }
                if (unlit > 0) {
                    notes.add(unlit
                            + (unlit == 1 ? " BSP surface" : " BSP surfaces")
                            + " that a moved or new light now reaches "
                            + (unlit == 1 ? "has" : "have")
                            + " no lightmap, so "
                            + (unlit == 1 ? "it stays" : "they stay")
                            + " without its light");
                }
            }
            var meshBoxes = actorBoxes(document, archive);
            var terrainBoxes = new ArrayList<float[]>();
            for (var change : terrain) {
                if (change.isEmpty()) {
                    continue;
                }
                if (change.owner().equals(scene.shadows.counts().terrain())) {
                    terrainBoxes.add(change.box());
                } else {
                    notes.add("Terrain " + change.owner() + " is not the Terrain that " + name
                            + " renders, so it casts no shadow on the BSP");
                }
            }
            var recolored = new HashMap<Integer, NativeBspLightmapTexels.Light>();
            for (var edit : document.getLightColorEdits()) {
                var light = scene.lightsByReference.get(edit.exportIndex());
                if (light != null && light >= 0) {
                    recolored.put(light, scene.getTexelLight(scene.lights.get(light), edit.current()));
                }
            }
            var results = build(scene, meshBoxes, terrainBoxes, recolored, options, progress, cancelled);
            return write(document, source, scene, results, notes);
        } finally {
            archive.close();
            original.close();
        }
    }

    static boolean reaches(
            BspLightingScene scene,
            Lightmap lightmap,
            Light light,
            List<float[]> meshBoxes,
            List<float[]> terrainBoxes) {
        var boxes = new ArrayList<float[]>(terrainBoxes);
        if (light.sun() ? scene.sunMeshes : scene.pointMeshes) {
            boxes.addAll(meshBoxes);
        }
        if (boxes.isEmpty()) {
            return false;
        }
        var surface = scene.surfaces[lightmap.surface()];
        var plane = surface.plane();
        var base = lightmap.base();
        var ax = lightmap.axisX();
        var ay = lightmap.axisY();
        if (light.sun()) {
            var directions = scene.isSunZone(lightmap) ? scene.slotToward : new float[][] {light.towardAuthored()};
            for (var toward : directions) {
                var cos = Math.abs(
                        (double) plane[0] * toward[0] + (double) plane[1] * toward[1] + (double) plane[2] * toward[2]);
                if (cos < 1e-6) {
                    continue;
                }
                var sMax = (NativeBspShadowBitmaps.SUN_CAMERA_HEIGHT - 1.0) / cos;
                for (var y = 0; y < lightmap.sizeY(); y++) {
                    for (var x = 0; x < lightmap.sizeX(); x++) {
                        double u = x + .5;
                        double v = y + .5;
                        var px = base[0] + ax[0] * u + ay[0] * v;
                        var py = base[1] + ax[1] * u + ay[1] * v;
                        var pz = base[2] + ax[2] * u + ay[2] * v;
                        for (var box : boxes) {
                            if (doesSegmentHitBox(px, py, pz, toward[0], toward[1], toward[2], 0, sMax, box)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }
        var texel = light.texel();
        var rect = NativeBspShadowBitmaps.pointRect(lightmap, plane, texel.x(), texel.y(), texel.z(), texel.radius());
        for (var y = rect.minY(); y <= rect.maxY(); y++) {
            for (var x = rect.minX(); x <= rect.maxX(); x++) {
                double u = x + .5;
                double v = y + .5;
                var px = base[0] + ax[0] * u + ay[0] * v;
                var py = base[1] + ax[1] * u + ay[1] * v;
                var pz = base[2] + ax[2] * u + ay[2] * v;
                for (var box : boxes) {
                    if (doesSegmentHitBox(
                            texel.x(),
                            texel.y(),
                            texel.z(),
                            px - texel.x(),
                            py - texel.y(),
                            pz - texel.z(),
                            0,
                            1,
                            box)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean[] computeMask(
            BspLightingScene scene,
            Lightmap lightmap,
            Light light,
            int variant,
            Entry rect,
            List<float[]> meshBoxes,
            List<float[]> terrainBoxes) {
        var flags = new boolean[rect.sizeX() * rect.sizeY()];
        var boxes = new ArrayList<float[]>(terrainBoxes);
        if (light.sun() ? scene.sunMeshes : scene.pointMeshes) {
            boxes.addAll(meshBoxes);
        }
        if (boxes.isEmpty()) {
            return flags;
        }
        var plane = scene.surfaces[lightmap.surface()].plane();
        var base = lightmap.base();
        var ax = lightmap.axisX();
        var ay = lightmap.axisY();
        float[] toward = null;
        var sMax = 0.0;
        if (light.sun()) {
            toward = scene.isSunZone(lightmap)
                    ? scene.slotToward[Math.min(variant, scene.slotToward.length - 1)]
                    : light.towardAuthored();
            var cos = Math.abs(
                    (double) plane[0] * toward[0] + (double) plane[1] * toward[1] + (double) plane[2] * toward[2]);
            if (cos < 1e-6) {
                return flags;
            }
            sMax = (NativeBspShadowBitmaps.SUN_CAMERA_HEIGHT - 1.0) / cos;
        }
        var texel = light.texel();
        for (var y = 0; y < rect.sizeY(); y++) {
            for (var x = 0; x < rect.sizeX(); x++) {
                double u = rect.minX() + x + .5;
                double v = rect.minY() + y + .5;
                var px = base[0] + ax[0] * u + ay[0] * v;
                var py = base[1] + ax[1] * u + ay[1] * v;
                var pz = base[2] + ax[2] * u + ay[2] * v;
                for (var box : boxes) {
                    var hit = toward != null
                            ? doesSegmentHitBox(px, py, pz, toward[0], toward[1], toward[2], 0, sMax, box)
                            : doesSegmentHitBox(
                                    texel.x(),
                                    texel.y(),
                                    texel.z(),
                                    px - texel.x(),
                                    py - texel.y(),
                                    pz - texel.z(),
                                    0,
                                    1,
                                    box);
                    if (hit) {
                        flags[y * rect.sizeX() + x] = true;
                        break;
                    }
                }
            }
        }
        return flags;
    }

    static boolean doesSegmentHitBox(
            double ox, double oy, double oz, double dx, double dy, double dz, double tMin, double tMax, float[] box) {
        if (!(box[0] <= box[3] && box[1] <= box[4] && box[2] <= box[5])) {
            return false;
        }
        var near = tMin;
        var far = tMax;
        for (var axis = 0; axis < 3; axis++) {
            var origin = axis == 0 ? ox : axis == 1 ? oy : oz;
            var direction = axis == 0 ? dx : axis == 1 ? dy : dz;
            var min = box[axis] - MARGIN;
            var max = box[axis + 3] + MARGIN;
            if (Math.abs(direction) < 1e-12) {
                if (origin < min || origin > max) {
                    return false;
                }
                continue;
            }
            var t0 = (min - origin) / direction;
            var t1 = (max - origin) / direction;
            if (t0 > t1) {
                var swap = t0;
                t0 = t1;
                t1 = swap;
            }
            near = Math.max(near, t0);
            far = Math.min(far, t1);
            if (near > far) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBeastModel(UArchive archive) {
        try {
            var levels = new ArrayList<ULevel>();
            archive.loadObjects("Level", levels);
            if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
                return false;
            }
            var model = levels.getFirst().getModel().get();
            return model != null && model.getBeastLightingFlag() != null && model.getBeastLightingFlag() != 0;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static List<Rebuilt> build(
            BspLightingScene scene,
            List<float[]> meshBoxes,
            List<float[]> terrainBoxes,
            Map<Integer, NativeBspLightmapTexels.Light> recolored,
            Options options,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var count = scene.tail.lightmaps.size();
        var targets = new ArrayList<Integer>();
        for (var i = 0; i < count; i++) {
            var lightmap = scene.tail.lightmaps.get(i);
            if (lightmap.texture() < scene.tail.textures.size()
                    && lightmap.surface() < scene.surfaces.length
                    && lightmap.sizeX() > 0
                    && lightmap.sizeY() > 0) {
                targets.add(i);
            }
        }
        var total = targets.size();
        progress.update(0, total);
        var results = new ArrayList<Rebuilt>();
        if (total == 0) {
            return results;
        }
        var executor = Executors.newFixedThreadPool(
                Math.min(options.threads(), total),
                Thread.ofPlatform().daemon().name("editor-bsp-lighting-", 0).factory());
        var done = new AtomicInteger();
        try {
            var futures = new ArrayList<Future<Rebuilt>>();
            for (var index : targets) {
                futures.add(executor.submit(() -> {
                    if (cancelled.getAsBoolean()) {
                        throw new CancellationException("BSP lighting rebuild cancelled");
                    }
                    var lightmap = scene.tail.lightmaps.get(index);
                    var rebuilt = BspLightmapBuilder.rebuild(
                            scene,
                            index,
                            light -> options.allLightmaps()
                                    || reaches(scene, lightmap, scene.lights.get(light), meshBoxes, terrainBoxes),
                            options.allLightmaps()
                                    ? (map, light, variant, rect) -> null
                                    : (map, light, variant, rect) ->
                                            computeMask(scene, map, light, variant, rect, meshBoxes, terrainBoxes),
                            recolored);
                    progress.update(done.incrementAndGet(), total);
                    return rebuilt;
                }));
            }
            for (var future : futures) {
                try {
                    var rebuilt = future.get();
                    if (rebuilt.changed()) {
                        results.add(rebuilt);
                    }
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof CancellationException) {
                        throw new InterruptedIOException("BSP lighting rebuild cancelled");
                    }
                    throw new IOException(
                            "BSP lighting rebuild failed: " + failure.getCause().getMessage(), failure.getCause());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("BSP lighting rebuild cancelled");
        } finally {
            executor.shutdownNow();
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("BSP lighting rebuild cancelled");
        }
        return results;
    }

    private static List<float[]> actorBoxes(PackageEditDocument document, UArchive archive) {
        var meshes = new HashMap<String, UStaticMesh>();
        var actors = new ArrayList<StaticMeshActor>();
        for (var type : BspShadowSceneCapture.MESH_ACTOR_CLASSES) {
            archive.loadObjects(type, actors);
        }
        for (var actor : actors) {
            try {
                var mesh = actor.getStaticMesh().get();
                if (mesh != null) {
                    meshes.putIfAbsent(actor.getSourcePath(), mesh);
                }
            } catch (RuntimeException missing) {
            }
        }
        var boxes = new ArrayList<float[]>();
        for (var edit : document.getLightingEdits()) {
            if (!edit.created() && !edit.deleted() && edit.original().equals(edit.current())) {
                continue;
            }
            var mesh = meshes.get(edit.path());
            if (mesh == null) {
                continue;
            }
            if (!edit.created()) {
                var box = ActorLightingRebuild.worldBox(mesh, edit.original());
                if (box != null) {
                    boxes.add(box);
                }
            }
            if (!edit.deleted()) {
                var box = ActorLightingRebuild.worldBox(mesh, edit.current());
                if (box != null) {
                    boxes.add(box);
                }
            }
        }
        return boxes;
    }

    private static Result write(
            PackageEditDocument document,
            PackageBytes source,
            BspLightingScene scene,
            List<Rebuilt> results,
            List<String> notes)
            throws IOException {
        var tail = scene.tail;
        if (results.isEmpty()) {
            var kept = new ArrayList<>(notes);
            kept.removeAll(scene.notes);
            return new Result(null, new Report(scene.map, 0, 0, 0, 0, 0, kept));
        }
        var replaced = new LinkedHashMap<Integer, Lightmap>();
        var surfaces = new HashSet<Integer>();
        var lights = 0;
        long texels = 0;
        var byTexture = new HashMap<Integer, List<Rebuilt>>();
        for (var rebuilt : results) {
            var lightmap = tail.lightmaps.get(rebuilt.index());
            if (rebuilt.replacement() != null) {
                replaced.put(rebuilt.index(), rebuilt.replacement());
            }
            surfaces.add(lightmap.surface());
            lights += rebuilt.rebuiltLights();
            texels += rebuilt.changedTexels();
            byTexture
                    .computeIfAbsent(lightmap.texture(), key -> new ArrayList<>())
                    .add(rebuilt);
        }
        var texelChanges = new LinkedHashMap<VariantKey, Texels>();
        var pages = new ArrayList<Page>();
        var blocks = 0;
        for (var entry : byTexture.entrySet()) {
            var texture = tail.textures.get(entry.getKey());
            for (var v = 0; v < texture.variants().size(); v++) {
                var variant = texture.variants().get(v);
                if (variant.isEmpty()) {
                    continue;
                }
                if (variant.format() != 3) {
                    notes.add("A BSP lightmap texture of " + scene.map
                            + " is not DXT1, so its texels keep their old values");
                    continue;
                }
                var written = writeVariant(tail, variant, v, entry.getValue());
                if (written != null) {
                    texelChanges.put(new VariantKey(entry.getKey(), v), written.texels());
                    pages.add(new Page(
                            entry.getKey(),
                            v,
                            variant.width(),
                            variant.height(),
                            written.texels().full(),
                            variant.full()));
                    blocks += written.blocks();
                }
            }
        }
        var modelExport = scene.modelExport;
        var entry = source.exports.get(modelExport - 1);
        var payload = tail.encode(replaced, texelChanges, entry.serialOffset());
        var replacements = Map.of(modelExport, payload);
        Placer placer = offset -> tail.encode(replaced, texelChanges, offset);
        var relocate = payload.length == entry.serialSize() ? Set.<Integer>of() : Set.of(modelExport);
        var changes = NativePackageChanges.create(
                document.getSource(),
                document.getOriginalSha256(),
                source,
                replacements,
                relocate,
                Map.of(modelExport, placer),
                (target, encoded) -> verify(target, encoded, modelExport, replaced.size()));
        return new Result(
                changes, new Report(scene.map, results.size(), surfaces.size(), lights, texels, blocks, notes), pages);
    }

    private record Written(Texels texels, int blocks) {}

    private static @Nullable Written writeVariant(
            BspLightmapTail tail, Variant variant, int slot, List<Rebuilt> rebuilt) {
        var width = variant.width();
        var height = variant.height();
        var decoded = DxtMipCodec.decodeToRgba(TextureFormat.TEXTURE_DXT1, width, height, variant.full());
        var updated = decoded.clone();
        var blocksX = (width + 3) / 4;
        var blocksY = (height + 3) / 4;
        var touched = new boolean[blocksX * blocksY];
        var any = false;
        for (var item : rebuilt) {
            var deltas = item.deltas();
            if (slot >= deltas.length || deltas[slot] == null) {
                continue;
            }
            var delta = deltas[slot];
            var lightmap = tail.lightmaps.get(item.index());
            for (var y = 0; y < lightmap.sizeY(); y++) {
                var ty = lightmap.offsetY() + y;
                if (ty >= height) {
                    break;
                }
                for (var x = 0; x < lightmap.sizeX(); x++) {
                    var tx = lightmap.offsetX() + x;
                    if (tx >= width) {
                        break;
                    }
                    var t = (y * lightmap.sizeX() + x) * 3;
                    if (delta[t] == 0 && delta[t + 1] == 0 && delta[t + 2] == 0) {
                        continue;
                    }
                    var o = (ty * width + tx) * 4;
                    for (var c = 0; c < 3; c++) {
                        updated[o + c] = (byte) Math.clamp((updated[o + c] & 0xff) + delta[t + c], 0, 255);
                    }
                    touched[(ty / 4) * blocksX + tx / 4] = true;
                    any = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        var full = variant.full().clone();
        var blocks = 0;
        var block = new int[64];
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                if (!touched[by * blocksX + bx]) {
                    continue;
                }
                gather(updated, width, height, bx, by, block);
                BspDxt1.encode(block, full, (by * blocksX + bx) * 8, variant.full(), (by * blocksX + bx) * 8);
                blocks++;
            }
        }
        var half = variant.half().clone();
        if (half.length > 0) {
            var halfWidth = Math.max(1, width / 2);
            var halfHeight = Math.max(1, height / 2);
            var expected = ((halfWidth + 3) / 4) * ((halfHeight + 3) / 4) * 8;
            if (half.length == expected) {
                var halfDecoded =
                        DxtMipCodec.decodeToRgba(TextureFormat.TEXTURE_DXT1, halfWidth, halfHeight, variant.half());
                var halfBlocksX = (halfWidth + 3) / 4;
                var halfTouched = new boolean[halfBlocksX * ((halfHeight + 3) / 4)];
                for (var y = 0; y < halfHeight; y++) {
                    for (var x = 0; x < halfWidth; x++) {
                        var sum = new int[3];
                        var changed = false;
                        for (var k = 0; k < 4; k++) {
                            var sx = Math.min(width - 1, x * 2 + (k & 1));
                            var sy = Math.min(height - 1, y * 2 + (k >> 1));
                            var o = (sy * width + sx) * 4;
                            for (var c = 0; c < 3; c++) {
                                var d = (updated[o + c] & 0xff) - (decoded[o + c] & 0xff);
                                sum[c] += d;
                                changed |= d != 0;
                            }
                        }
                        if (!changed) {
                            continue;
                        }
                        var o = (y * halfWidth + x) * 4;
                        for (var c = 0; c < 3; c++) {
                            halfDecoded[o + c] =
                                    (byte) Math.clamp((halfDecoded[o + c] & 0xff) + Math.round(sum[c] / 4f), 0, 255);
                        }
                        halfTouched[(y / 4) * halfBlocksX + x / 4] = true;
                    }
                }
                for (var b = 0; b < halfTouched.length; b++) {
                    if (!halfTouched[b]) {
                        continue;
                    }
                    gather(halfDecoded, halfWidth, halfHeight, b % halfBlocksX, b / halfBlocksX, block);
                    BspDxt1.encode(block, half, b * 8, variant.half(), b * 8);
                    blocks++;
                }
            }
        }
        return new Written(new Texels(full, half), blocks);
    }

    private static void gather(byte[] rgba, int width, int height, int bx, int by, int[] block) {
        for (var ty = 0; ty < 4; ty++) {
            var y = Math.min(by * 4 + ty, height - 1);
            for (var tx = 0; tx < 4; tx++) {
                var x = Math.min(bx * 4 + tx, width - 1);
                var s = (y * width + x) * 4;
                var t = (ty * 4 + tx) * 4;
                for (var c = 0; c < 4; c++) {
                    block[t + c] = rgba[s + c] & 0xff;
                }
            }
        }
    }

    private static void verify(Path target, byte[] encoded, int modelExport, int replacedCount) throws IOException {
        var written = new PackageBytes(target, encoded);
        var tail = BspLightmapTail.read(written, modelExport);
        if (replacedCount > tail.lightmaps.size()) {
            throw new IOException("The written BSP lightmaps lost records");
        }
    }

    private static void include(float[] box, float x, float y, float z) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x);
        box[4] = Math.max(box[4], y);
        box[5] = Math.max(box[5], z);
    }

    private static String mapName(Path source) {
        var file = source.getFileName().toString();
        var dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }
}
