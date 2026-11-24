package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingChanges.BeastTexture;
import com.rost.cruma.core.editing.BeastBaker.LocalLights;
import com.rost.cruma.core.editing.BeastCalibration.Agreement;
import com.rost.cruma.core.editing.BeastTerrainRebake.Calibrated;
import com.rost.cruma.core.editing.BspLightmapTail.Texels;
import com.rost.cruma.core.editing.BspLightmapTail.VariantKey;
import com.rost.cruma.core.editing.NativePackageChanges.Placer;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class BeastWorldUpdate {
    private BeastWorldUpdate() {}

    record Bsp(
            @Nullable NativePackageChanges changes,
            int surfaces,
            long texels,
            int blocks,
            @Nullable Agreement slot0,
            @Nullable Agreement slot1,
            int samples,
            List<String> notes) {
        static final Bsp NONE = new Bsp(null, 0, 0, 0, null, null, 0, List.of());
    }

    record Terrain(
            Map<Integer, BeastTexture> textures,
            int owners,
            long texels,
            @Nullable Agreement slot0,
            @Nullable Agreement slot1,
            List<String> notes) {}

    static Bsp updateBsp(
            PackageEditDocument document,
            String name,
            UArchive original,
            ActorLightingScene before,
            ActorLightingTrace after,
            LocalLights lights,
            List<float[]> boxes,
            int threads,
            BooleanSupplier cancelled)
            throws IOException {
        var levels = new ArrayList<ULevel>();
        original.loadObjects("Level", levels);
        if (levels.isEmpty() || levels.getFirst().getModel().isNull() || boxes.isEmpty()) {
            return Bsp.NONE;
        }
        var level = levels.getFirst();
        var model = level.getModel().get();
        if (model == null || model.getBeastLightingFlag() == null || model.getBeastLightingFlag() == 0) {
            return Bsp.NONE;
        }
        var bytes = Files.readAllBytes(document.getSource());
        if (!PackageEditDocument.sha256(bytes).equals(document.getOriginalSha256())) {
            throw new IOException("Source map changed since editing began; reopen it before saving");
        }
        var source = new PackageBytes(document.getSource(), bytes);
        var modelExport = level.getModel().getIndex();
        BspLightmapTail tail;
        try {
            tail = BspLightmapTail.read(new PackageBytes(document.getSource(), document.previewBody()), modelExport);
        } catch (IOException unreadable) {
            return new Bsp(
                    null,
                    0,
                    0,
                    0,
                    null,
                    null,
                    0,
                    List.of("The BSP Beast lightmaps of " + name + " cannot be read, so they keep their old values"));
        }
        if (!BeastBsp.hasTexels(tail)) {
            return Bsp.NONE;
        }
        var records = BeastBsp.findRecords(tail, model, cancelled);
        var calibrated = BeastBspRebake.calibrate(
                tail,
                records,
                before.getBeastTrace(),
                lights,
                BeastRebuild.createUncalibratedModel(before),
                BeastBsp.CENTER,
                threads,
                cancelled);
        var fit = calibrated.fit();
        var holdout = fit.calibrated()
                ? BeastBspRebake.measureHoldout(
                        records,
                        calibrated.images(),
                        before.getBeastTrace(),
                        lights,
                        BeastRebuild.createUncalibratedModel(before),
                        threads,
                        cancelled)
                : new double[] {0, Double.MAX_VALUE};
        if (holdout[1] >= holdout[0]) {
            return new Bsp(
                    null,
                    0,
                    0,
                    0,
                    fit.slot0After(),
                    fit.slot1After(),
                    fit.samples(),
                    List.of("The BSP Beast lightmaps of " + name
                            + " keep their old values: on surfaces it did not use, the approximate baker"
                            + " agrees with them no better than without calibration"));
        }
        var result = BeastBspRebake.rebake(calibrated, before.getBeastTrace(), after, boxes, cancelled);
        if (result.texels().isEmpty()) {
            return new Bsp(null, 0, 0, 0, fit.slot0After(), fit.slot1After(), fit.samples(), List.of());
        }
        var texels = result.texels();
        var entry = source.exports.get(modelExport - 1);
        var payload = tail.encode(Map.of(), texels, entry.serialOffset());
        Placer placer = offset -> tail.encode(Map.of(), texels, offset);
        var relocate = payload.length == entry.serialSize() ? Set.<Integer>of() : Set.of(modelExport);
        var changes = NativePackageChanges.create(
                document.getSource(),
                document.getOriginalSha256(),
                source,
                Map.of(modelExport, payload),
                relocate,
                Map.of(modelExport, placer),
                (target, encoded) -> verify(target, encoded, modelExport, texels));
        return new Bsp(
                changes,
                result.surfaces(),
                result.texelsChanged(),
                result.blocks(),
                fit.slot0After(),
                fit.slot1After(),
                fit.samples(),
                List.of());
    }

    static Terrain updateTerrain(
            PackageEditDocument document,
            UArchive original,
            ActorLightingScene before,
            ActorLightingTrace after,
            List<float[]> boxes,
            Set<String> skipped,
            int threads,
            BooleanSupplier cancelled)
            throws IOException {
        var textures = new TreeMap<Integer, BeastTexture>();
        var notes = new ArrayList<String>();
        if (boxes.isEmpty() || before.getBeastTrace() == null) {
            return new Terrain(textures, 0, 0, null, null, notes);
        }
        var infos = new ArrayList<UTerrainInfoActor>();
        original.loadObjects("TerrainInfo", infos);
        var trace = before.getBeastTrace();
        var owners = before.getBeastTerrainOwners();
        var updated = 0;
        long texels = 0;
        Agreement slot0 = null;
        Agreement slot1 = null;
        var seen = new HashSet<String>();
        for (var info : infos) {
            if (info.isDeleteMe() || skipped.contains(info.getSourcePath()) || !seen.add(info.getSourcePath())) {
                continue;
            }
            var index = owners.indexOf(info.getSourcePath());
            if (index < 0 || index >= trace.getTerrains().size()) {
                continue;
            }
            var slots = document.getTerrainBeastTextures(info.getName());
            if (slots.isEmpty()) {
                continue;
            }
            var owner = ActorLightingCapture.terrainOwner(info);
            if (owner == null) {
                continue;
            }
            var others = new ArrayList<>(trace.getTerrains());
            var ownerTrace = others.remove(index);
            Calibrated calibrated;
            try {
                calibrated = BeastTerrainRebake.calibrate(
                        document.getOriginalPackage(),
                        slots.get(0),
                        slots.get(1),
                        before,
                        others,
                        ownerTrace,
                        owner.field(),
                        owner.turned(),
                        owner.visible(),
                        owner.inverted(),
                        threads,
                        cancelled);
            } catch (IOException unreadable) {
                notes.add("The Beast lightmap of " + info.getSourcePath()
                        + " cannot be read, so it keeps its old values");
                continue;
            }
            var result = BeastTerrainRebake.rebake(
                    calibrated,
                    owner.field(),
                    owner.field(),
                    owner.turned(),
                    owner.visible(),
                    owner.inverted(),
                    after,
                    boxes,
                    cancelled);
            if (result.changedTexels() == 0) {
                continue;
            }
            textures.put(calibrated.slot0Export(), result.slot0());
            textures.put(calibrated.slot1Export(), result.slot1());
            updated++;
            texels += result.changedTexels();
            slot0 = calibrated.fit().slot0After();
            slot1 = calibrated.fit().slot1After();
        }
        return new Terrain(textures, updated, texels, slot0, slot1, notes);
    }

    private static void verify(Path target, byte[] encoded, int modelExport, Map<VariantKey, Texels> texels)
            throws IOException {
        var written = new PackageBytes(target, encoded);
        var tail = BspLightmapTail.read(written, modelExport);
        for (var entry : texels.entrySet()) {
            var key = entry.getKey();
            var variant = tail.textures.get(key.texture()).variants().get(key.variant());
            if (!Arrays.equals(variant.full(), entry.getValue().full())
                    || !Arrays.equals(variant.half(), entry.getValue().half())) {
                throw new IOException("The written BSP Beast lightmap does not hold the rebuilt texels");
            }
        }
    }
}
