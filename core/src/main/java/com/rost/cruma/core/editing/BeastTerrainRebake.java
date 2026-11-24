package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingChanges.BeastTexture;
import com.rost.cruma.core.editing.ActorLightingTrace.Caster;
import com.rost.cruma.core.editing.BeastBaker.LocalLights;
import com.rost.cruma.core.editing.BeastBaker.Terms;
import com.rost.cruma.core.editing.BeastCalibration.Sample;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class BeastTerrainRebake {
    static final int CALIBRATION_TEXELS = 12000;

    static final float FLAT_SPREAD = 16f;
    static final float BLACK = 2f;

    private BeastTerrainRebake() {}

    public record Calibrated(
            ActorLightingTrace trace,
            List<NativeTerrainTrace> otherTerrain,
            NativeTerrainTrace ownerTerrain,
            LocalLights lights,
            BeastCalibration.Result fit,
            int slot0Export,
            int slot1Export,
            int width,
            int height,
            List<byte[]> mips0,
            List<byte[]> mips1) {
        public BeastBakeModel model() {
            return fit.model();
        }

        public boolean isFlat() {
            return BeastTerrainRebake.isFlat(BeastTextureCodec.decode(mips0.getFirst(), width, height))
                    && BeastTerrainRebake.isFlat(BeastTextureCodec.decode(mips1.getFirst(), width, height));
        }
    }

    public record Result(
            BeastTexture slot0, BeastTexture slot1, int sectors, int texels, int changedTexels, boolean keptFlat) {}

    static @Nullable Calibrated calibrate(
            Path clientRoot,
            UArchive archive,
            TerrainPackageSnapshot snapshot,
            Terrain terrain,
            BitSet edgeTurns,
            @Nullable BitSet visibleQuads,
            boolean inverted,
            int threads,
            BooleanSupplier cancelled)
            throws IOException {
        var beast = terrain.info().beastLightmaps();
        if (beast.isEmpty() || beast.get().references().stream().allMatch(reference -> reference == 0)) {
            return null;
        }
        if (terrain.heightmap().isEmpty()) {
            throw new IOException("The Terrain heights are needed for its Beast lightmaps");
        }
        var source = snapshot.getPackageBytes();
        int slot0 = 0, slot1 = 0;
        for (var reference : beast.get().references()) {
            if (reference <= 0 || reference > source.exports.size()) {
                throw new IOException("A Beast lightmap of this Terrain is not in the map");
            }
            var name = source.getName(source.exports.get(reference - 1).objectName());
            if (name.endsWith("_Lightmap_0")) {
                slot0 = reference;
            } else if (name.endsWith("_Lightmap_1")) {
                slot1 = reference;
            }
        }
        if (slot0 == 0 || slot1 == 0) {
            throw new IOException("This Terrain does not name both Beast lightmap slots");
        }
        var scene = ActorLightingCapture.capture(
                archive, ActorLightingCapture.readSettings(clientRoot), null, true, cancelled);
        if (scene.getBeastTrace() == null) {
            throw new IOException("The map has no Beast level, so its Terrain lightmap cannot be rebuilt");
        }
        var infos = new ArrayList<UTerrainInfoActor>();
        archive.loadObjects("TerrainInfo", infos);
        var others = new ArrayList<NativeTerrainTrace>();
        NativeTerrainTrace owner = null;
        var ownerName = terrain.path().substring(terrain.path().lastIndexOf('.') + 1);
        for (var info : infos) {
            if (info.isDeleteMe()) {
                continue;
            }
            var trace = ActorLightingCapture.terrainTraceOf(info);
            if (trace == null) {
                continue;
            }
            if (info.getName().equalsIgnoreCase(ownerName)) {
                owner = trace;
            } else {
                others.add(trace);
            }
        }
        var field = new TerrainHeightField(
                terrain.heightmap().getFirst(), terrain.info().toWorld());
        if (owner == null) {
            owner = new NativeTerrainTrace(
                    field, visibleQuads == null ? allQuads(field) : visibleQuads, edgeTurns, inverted);
        }
        return calibrate(
                source,
                slot0,
                slot1,
                scene,
                others,
                owner,
                field,
                edgeTurns,
                visibleQuads,
                inverted,
                threads,
                cancelled);
    }

    static Calibrated calibrate(
            PackageBytes source,
            int slot0,
            int slot1,
            ActorLightingScene scene,
            List<NativeTerrainTrace> others,
            NativeTerrainTrace owner,
            TerrainHeightField field,
            BitSet edgeTurns,
            @Nullable BitSet visibleQuads,
            boolean inverted,
            int threads,
            BooleanSupplier cancelled)
            throws IOException {
        var saved0 = BeastTextureCodec.read(source, source.exports.get(slot0 - 1));
        var saved1 = BeastTextureCodec.read(source, source.exports.get(slot1 - 1));
        if (saved0.width() != saved1.width() || saved0.height() != saved1.height()) {
            throw new IOException("The two Beast lightmaps of this Terrain differ in size");
        }
        var trace = scene.getBeastTrace().withTerrains(join(others, owner));
        var lights = LocalLights.of(scene);
        var width = saved0.width();
        var height = saved0.height();
        var layout =
                new BeastTerrain(field, edgeTurns, visibleQuads, inverted, width, height, BeastTerrain.SAVED_MAPPING);
        var image0 = BeastTextureCodec.decode(saved0.mips().getFirst(), width, height);
        var image1 = BeastTextureCodec.decode(saved1.mips().getFirst(), width, height);
        var samples = new ArrayList<Sample>();
        var step = Math.max(1, (int) Math.sqrt((double) width * height / CALIBRATION_TEXELS));
        var p = new float[3];
        var n = new float[3];
        for (var y = step / 2; y < height; y += step) {
            for (var x = step / 2; x < width; x += step) {
                if (!layout.computeWorldPoint(x, y, p, n)) {
                    continue;
                }
                var t = (y * width + x) * 3;
                var group = (int) (layout.toHeightmapCoordinate(y, height, field.getHeight()) / 16) * 16
                        + (int) (layout.toHeightmapCoordinate(x, width, field.getWidth()) / 16);
                samples.add(new Sample(
                        p.clone(),
                        n.clone(),
                        readRgb(image0, t),
                        readRgb(image1, t),
                        lights.findReaching(new float[] {p[0], p[1], p[2], p[0], p[1], p[2]}),
                        group));
            }
        }
        var fit = BeastCalibration.fit(
                trace, lights, samples, BeastRebuild.createUncalibratedModel(scene), threads, cancelled);
        return new Calibrated(
                trace,
                List.copyOf(others),
                owner,
                lights,
                fit,
                slot0,
                slot1,
                width,
                height,
                saved0.mips(),
                saved1.mips());
    }

    public record ActorChange(Set<String> removed, List<Caster> added) {
        public static final ActorChange NONE = new ActorChange(Set.of(), List.of());

        public ActorChange {
            removed = Set.copyOf(removed);
            added = List.copyOf(added);
        }

        public boolean isEmpty() {
            return removed.isEmpty() && added.isEmpty();
        }
    }

    public static Result rebake(
            Calibrated calibrated,
            TerrainHeightField original,
            TerrainHeightField edited,
            BitSet edgeTurns,
            @Nullable BitSet visibleQuads,
            boolean inverted,
            BooleanSupplier cancelled) {
        return rebake(calibrated, original, edited, edgeTurns, visibleQuads, inverted, ActorChange.NONE, cancelled);
    }

    public static Result rebake(
            Calibrated calibrated,
            TerrainHeightField original,
            TerrainHeightField edited,
            BitSet edgeTurns,
            @Nullable BitSet visibleQuads,
            boolean inverted,
            ActorChange actors,
            BooleanSupplier cancelled) {
        var boxes = new ArrayList<float[]>();
        var changedSamples = changedSamples(original, edited);
        if (changedSamples != null) {
            boxes.add(changedBox(original, edited, changedSamples));
        }
        var after = calibrated.trace();
        if (!actors.isEmpty()) {
            boxes.addAll(after.getCasterBounds(actors.removed()));
            for (var caster : actors.added()) {
                boxes.add(caster.bounds().clone());
            }
            after = after.withCasters(actors.removed(), actors.added());
        }
        after = after.withTerrains(
                join(calibrated.otherTerrain(), calibrated.ownerTerrain().withHeights(edited)));
        return rebake(calibrated, original, edited, edgeTurns, visibleQuads, inverted, after, boxes, cancelled);
    }

    static Result rebake(
            Calibrated calibrated,
            TerrainHeightField original,
            TerrainHeightField edited,
            BitSet edgeTurns,
            @Nullable BitSet visibleQuads,
            boolean inverted,
            ActorLightingTrace after,
            List<float[]> boxes,
            BooleanSupplier cancelled) {
        var width = calibrated.width();
        var height = calibrated.height();
        var model = calibrated.model();
        var changedSamples = changedSamples(original, edited);
        var unchanged = new Result(
                new BeastTexture(width, height, calibrated.mips0()),
                new BeastTexture(width, height, calibrated.mips1()),
                0,
                0,
                0,
                false);
        if (changedSamples == null && boxes.isEmpty()) {
            return unchanged;
        }
        var slot0 = BeastTextureCodec.decode(calibrated.mips0().getFirst(), width, height);
        var slot1 = BeastTextureCodec.decode(calibrated.mips1().getFirst(), width, height);
        var keep0 = isFlat(slot0);
        var keep1 = isFlat(slot1);
        var keptFlat = keep0 && !isBlack(slot0) || keep1 && !isBlack(slot1);
        if (keep0 && keep1) {
            return new Result(unchanged.slot0(), unchanged.slot1(), 0, 0, 0, keptFlat);
        }
        var before = new BeastTerrain(
                original, edgeTurns, visibleQuads, inverted, width, height, BeastTerrain.SAVED_MAPPING);
        var layout =
                new BeastTerrain(edited, edgeTurns, visibleQuads, inverted, width, height, BeastTerrain.SAVED_MAPPING);
        var geometry = changedSamples == null ? new boolean[width * height] : before.computeTexelsOf(changedSamples);
        var sectorsX = original.getWidth() / 16 + 1;
        var sectorsY = original.getHeight() / 16 + 1;
        var visitedSectors = new boolean[sectorsX * sectorsY];
        var brightest = sectorMaxima(slot0, slot1, before, width, height, original, sectorsX);
        var oldQuery = calibrated.trace().query();
        var newQuery = after.query();
        var lights = calibrated.lights();
        var relevant = BeastRebake.relevantLights(lights, boxes);
        var changed0 = new boolean[width * height];
        var changed1 = new boolean[width * height];
        var visited = 0;
        var moved = 0;
        var p = new float[3];
        var n = new float[3];
        var q = new float[3];
        var m = new float[3];
        var a = new float[3];
        var b = new float[3];
        var zero = new float[3];
        var oldTerms = new Terms();
        var newTerms = new Terms();
        var delta = new Terms();
        var reach = model.occlusionDistance() + 2 * BeastBaker.BIAS;
        for (var y = 0; y < height; y++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Beast Terrain rebake cancelled");
            }
            var sy = Math.clamp(
                            (int) Math.floor(before.toHeightmapCoordinate(y, height, original.getHeight()) + .5f),
                            0,
                            original.getHeight() - 1)
                    / 16;
            for (var x = 0; x < width; x++) {
                var texel = y * width + x;
                if (!before.computeWorldPoint(x, y, p, n) || !layout.computeWorldPoint(x, y, q, m)) {
                    continue;
                }
                var sx = Math.clamp(
                                (int) Math.floor(before.toHeightmapCoordinate(x, width, original.getWidth()) + .5f),
                                0,
                                original.getWidth() - 1)
                        / 16;
                var seed = BeastBaker.seed(new float[] {texel, width, height});
                if (geometry[texel]) {
                    var candidates = lights.findReaching(new float[] {
                        Math.min(p[0], q[0]),
                        Math.min(p[1], q[1]),
                        Math.min(p[2], q[2]),
                        Math.max(p[0], q[0]),
                        Math.max(p[1], q[1]),
                        Math.max(p[2], q[2])
                    });
                    BeastBaker.computeTerms(oldQuery, lights, candidates, model, p, n, seed, oldTerms, null);
                    BeastBaker.computeTerms(newQuery, lights, candidates, model, q, m, seed, newTerms, null);
                    BeastBaker.computeSlot0Linear(model, oldTerms, a);
                    BeastBaker.computeSlot0Linear(model, newTerms, b);
                    var local = localLimit(brightest, sx, sy, sectorsX, sectorsY);
                    changed0[texel] = apply(model, 0, slot0, texel, a, b, local, keep0);
                    BeastBaker.computeSlot1Linear(model, oldTerms, a);
                    BeastBaker.computeSlot1Linear(model, newTerms, b);
                    changed1[texel] = apply(model, 1, slot1, texel, a, b, local, keep1);
                } else {
                    var near = BeastRebake.isNearAnyBox(p, boxes, reach);
                    var candidates = BeastRebake.findReaching(lights, relevant, p);
                    if (!near
                            && !BeastRebake.doesSunRayMeetAnyBox(p, model.sunDirection(), boxes)
                            && !BeastRebake.isTowardsLights(p, candidates, lights, boxes)) {
                        continue;
                    }
                    var bounce = near && BeastRebake.isNearAnyBox(p, boxes, BeastRebake.BOUNCE_REACH);
                    BeastBaker.computeDeltaTerms(
                            oldQuery, newQuery, lights, candidates, model, p, n, seed, boxes, near, bounce, delta);
                    var local = localLimit(brightest, sx, sy, sectorsX, sectorsY);
                    BeastBaker.computeSlot0Linear(model, delta, b);
                    changed0[texel] = apply(model, 0, slot0, texel, zero, b, local, keep0);
                    BeastBaker.computeSlot1Linear(model, delta, b);
                    changed1[texel] = apply(model, 1, slot1, texel, zero, b, local, keep1);
                }
                visited++;
                visitedSectors[sy * sectorsX + sx] = true;
                if (changed0[texel] || changed1[texel]) {
                    moved++;
                }
            }
        }
        var sectorCount = 0;
        for (var sector : visitedSectors) {
            sectorCount += sector ? 1 : 0;
        }
        var result0 = BeastRebuild.changedTexture(calibrated.mips0(), slot0, changed0, width, height);
        var result1 = BeastRebuild.changedTexture(calibrated.mips1(), slot1, changed1, width, height);
        return new Result(result0, result1, sectorCount, visited, moved, keptFlat);
    }

    private static boolean @Nullable [] changedSamples(TerrainHeightField original, TerrainHeightField edited) {
        var result = new boolean[original.getWidth() * original.getHeight()];
        var any = false;
        for (var y = 0; y < original.getHeight(); y++) {
            for (var x = 0; x < original.getWidth(); x++) {
                if (original.getHeightAt(x, y) != edited.getHeightAt(x, y)) {
                    result[y * original.getWidth() + x] = true;
                    any = true;
                }
            }
        }
        return any ? result : null;
    }

    private static float[] changedBox(TerrainHeightField original, TerrainHeightField edited, boolean[] changed) {
        var box = new float[] {
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };
        for (var y = 0; y < original.getHeight(); y++) {
            for (var x = 0; x < original.getWidth(); x++) {
                if (!changed[y * original.getWidth() + x]) {
                    continue;
                }
                for (var field : new TerrainHeightField[] {original, edited}) {
                    for (var dy = -1; dy <= 1; dy++) {
                        for (var dx = -1; dx <= 1; dx++) {
                            var nx = Math.clamp(x + dx, 0, original.getWidth() - 1);
                            var ny = Math.clamp(y + dy, 0, original.getHeight() - 1);
                            var point = field.getWorldAt(nx, ny);
                            box[0] = Math.min(box[0], point.x());
                            box[1] = Math.min(box[1], point.y());
                            box[2] = Math.min(box[2], point.z());
                            box[3] = Math.max(box[3], point.x());
                            box[4] = Math.max(box[4], point.y());
                            box[5] = Math.max(box[5], point.z());
                        }
                    }
                }
            }
        }
        return box;
    }

    private static boolean isFlat(float[] image) {
        for (var c = 0; c < 3; c++) {
            var low = Float.MAX_VALUE;
            var high = -Float.MAX_VALUE;
            for (var i = c; i < image.length; i += 3) {
                low = Math.min(low, image[i]);
                high = Math.max(high, image[i]);
            }
            if (high - low > FLAT_SPREAD) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlack(float[] image) {
        for (var value : image) {
            if (value > BLACK) {
                return false;
            }
        }
        return true;
    }

    private static float[][] sectorMaxima(
            float[] slot0,
            float[] slot1,
            BeastTerrain layout,
            int width,
            int height,
            TerrainHeightField field,
            int sectorsX) {
        var sectorsY = field.getHeight() / 16 + 1;
        var result = new float[sectorsX * sectorsY][6];
        for (var y = 0; y < height; y++) {
            var sy = Math.clamp(
                            (int) Math.floor(layout.toHeightmapCoordinate(y, height, field.getHeight()) + .5f),
                            0,
                            field.getHeight() - 1)
                    / 16;
            for (var x = 0; x < width; x++) {
                var sx = Math.clamp(
                                (int) Math.floor(layout.toHeightmapCoordinate(x, width, field.getWidth()) + .5f),
                                0,
                                field.getWidth() - 1)
                        / 16;
                var maxima = result[sy * sectorsX + sx];
                var texel = y * width + x;
                for (var c = 0; c < 3; c++) {
                    maxima[c] = Math.max(maxima[c], slot0[texel * 3 + c]);
                    maxima[3 + c] = Math.max(maxima[3 + c], slot1[texel * 3 + c]);
                }
            }
        }
        return result;
    }

    private static float[] localLimit(float[][] maxima, int sx, int sy, int sectorsX, int sectorsY) {
        var result = new float[6];
        for (var dy = -1; dy <= 1; dy++) {
            for (var dx = -1; dx <= 1; dx++) {
                var x = sx + dx;
                var y = sy + dy;
                if (x < 0 || y < 0 || x >= sectorsX || y >= sectorsY) {
                    continue;
                }
                for (var i = 0; i < 6; i++) {
                    result[i] = Math.max(result[i], maxima[y * sectorsX + x][i]);
                }
            }
        }
        return result;
    }

    private static boolean apply(
            BeastBakeModel model,
            int slot,
            float[] image,
            int texel,
            float[] before,
            float[] after,
            float[] local,
            boolean keep) {
        if (keep) {
            return false;
        }
        var moved = false;
        for (var c = 0; c < 3; c++) {
            var stored = image[texel * 3 + c];
            var next = BeastBaker.encode(model, BeastBaker.decode(model, stored) + after[c] - before[c]);
            next = Math.min(next, Math.max(stored, Math.min(model.limit(slot, c), local[slot * 3 + c])));
            image[texel * 3 + c] = next;
            moved |= Math.abs(next - stored) >= 1f;
        }
        return moved;
    }

    private static List<NativeTerrainTrace> join(List<NativeTerrainTrace> others, NativeTerrainTrace owner) {
        var result = new ArrayList<>(others);
        result.add(owner);
        return result;
    }

    private static BitSet allQuads(TerrainHeightField field) {
        var bits = new BitSet(field.getWidth() * field.getHeight());
        bits.set(0, field.getWidth() * field.getHeight());
        return bits;
    }

    private static int[] readRgb(float[] image, int offset) {
        return new int[] {Math.round(image[offset]), Math.round(image[offset + 1]), Math.round(image[offset + 2])};
    }
}
