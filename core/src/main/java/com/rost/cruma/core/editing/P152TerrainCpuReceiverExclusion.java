package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainGridFootprint.Footprint;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Result;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Window;
import com.rost.cruma.core.editing.P152ActorDomain.Disposition;
import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152TerrainCpuReceiverExclusion {
    private final P152TerrainSunBake bake;
    private final Report report;

    private P152TerrainCpuReceiverExclusion(P152TerrainSunBake bake, Report report) {
        this.bake = bake;
        this.report = report;
    }

    record GeometryFacts(
            int changedPositions,
            int changedNormals,
            int minimumHeight,
            int maximumHeight,
            int unchangedSectors,
            int coveredQuads,
            int ownerZone,
            int checkedZoneVertices,
            int changedIntensityVertices,
            Footprint gridFootprint) {}

    record Report(
            String mapSha256,
            String heightSha256,
            String ownerPath,
            int excludedTerrainActors,
            GeometryFacts geometry,
            String queryPolicy) {}

    static P152TerrainCpuReceiverExclusion capture(P152TerrainSunBake bake) throws IOException {
        return capture(bake, () -> false);
    }

    static P152TerrainCpuReceiverExclusion capture(P152TerrainSunBake bake, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(bake);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        bake.verifySourcesUnchanged(cancelled);
        P152TerrainSunBake.requireOperation(bake.getPatch());
        if (!bake.getQuery().getMapSha256().equals(P152TerrainSunBake.MAP_HASH)
                || !bake.getHeightPackage().getOriginalSha256().equals(P152TerrainSunBake.HEIGHT_HASH)) {
            throw new IOException("Reverse CPU receiver proof requires the original map and height source");
        }
        if (!bake.getQuery().getGaps().isEmpty()) {
            throw new IOException("Reverse CPU receiver scene is incomplete");
        }
        var inputs = bake.getInputs();
        var context = bake.getQuery().getContext();
        var terrainCount = 0;
        var selectedActive = false;
        for (var actor : context.getActorDomain().getReport().actors()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (!actor.classPath().equals("Engine.TerrainInfo")) {
                continue;
            }
            terrainCount++;
            var path = actor.identity().objectPath();
            var facts = context.getMapInputs().getTerrain(path);
            if (facts.collideActors().value()
                    || facts.shadowCast().value()
                    || actor.disposition() != Disposition.EXCLUDED) {
                throw new IOException("Terrain can participate in native CPU shadow queries: " + path);
            }
            selectedActive |= path.equals(inputs.getTerrain().path());
        }
        if (!selectedActive || terrainCount == 0) {
            throw new IOException("Selected Terrain is absent from the frozen native registry");
        }
        requireNoDerivedGeometry(inputs.getTerrain());
        var geometry = inspectGeometry(
                inputs.getTerrain(),
                bake.getPatch(),
                inputs.getEdgeTurns(),
                inputs.isInverted(),
                inputs.getVisibleQuads(),
                bake.getScope().getWindows(),
                bake.getQuery().getModel().getRegions(),
                inputs.getOwner().zoneNumber(),
                changedIntensityVertices(bake.getOriginal(), bake.getEdited(), 256, 256, cancelled),
                cancelled);
        var result = new P152TerrainCpuReceiverExclusion(
                bake,
                new Report(
                        bake.getQuery().getMapSha256(),
                        bake.getHeightPackage().getOriginalSha256(),
                        inputs.getTerrain().path(),
                        terrainCount,
                        geometry,
                        "P152 native CPU light queries 0x197; Terrain actor collision and shadow disabled"));
        P152FreshHashQuery.checkCancelled(cancelled);
        result.verifySourcesUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    static void requireNoDerivedGeometry(Terrain terrain) throws IOException {
        if (terrain.hasDecorations()) {
            throw new IOException("Terrain decoration receivers require a separate rebuild");
        }
        if (terrain.info().beastLightmaps().isPresent()
                && !terrain.info().beastLightmaps().orElseThrow().references().isEmpty()) {
            throw new IOException("Terrain Beast lightmap receivers require a separate rebuild");
        }
    }

    static GeometryFacts inspectGeometry(
            Terrain terrain,
            TerrainHeightPatch patch,
            BitSet turns,
            boolean inverted,
            BitSet visible,
            Window window,
            NativeBspRegions regions,
            int ownerZone,
            BitSet changedIntensityVertices,
            BooleanSupplier cancelled)
            throws IOException {
        return inspectGeometry(
                terrain,
                patch,
                turns,
                inverted,
                visible,
                List.of(window),
                regions,
                ownerZone,
                changedIntensityVertices,
                cancelled);
    }

    static GeometryFacts inspectGeometry(
            Terrain terrain,
            TerrainHeightPatch patch,
            BitSet turns,
            boolean inverted,
            BitSet visible,
            List<Window> windows,
            NativeBspRegions regions,
            int ownerZone,
            BitSet changedIntensityVertices,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (terrain.heightmap().size() != 1
                || patch.getWidth() != 256
                || patch.getHeight() != 256
                || patch.size() == 0
                || patch.size() > P152TerrainEditScope.MAX_SAMPLES
                || windows.isEmpty()
                || terrain.info().width() != 256
                || terrain.info().height() != 256
                || terrain.sectors().size() != 256
                || turns.length() > 65536
                || visible.length() > 65536
                || changedIntensityVertices.length() > 65536) {
            throw new IOException("Reverse CPU proof requires the bounded 256 by 256 Terrain profile");
        }
        var before = new TerrainHeightField(
                terrain.heightmap().getFirst(), terrain.info().toWorld());
        var after = new TerrainHeightField(
                256, 256, before.copySamples(), terrain.info().toWorld());
        after.apply(patch, false);
        var changed = new BitSet(65536);
        var halo = new BitSet(65536);
        for (var i = 0; i < patch.size(); i++) {
            var cx = patch.getIndex(i) % 256;
            var cy = patch.getIndex(i) / 256;
            if (cx < 2 || cy < 2 || cx >= 254 || cy >= 254) {
                throw new IOException("Changed normal halo is outside the rebuilt native window");
            }
            changed.set(patch.getIndex(i));
            for (var y = cy - 1; y <= cy + 1; y++) {
                for (var x = cx - 1; x <= cx + 1; x++) {
                    if (!P152TerrainEditScope.isCovered(windows, x, y)) {
                        throw new IOException("Changed normal halo is outside the rebuilt native window");
                    }
                    halo.set(y * 256 + x);
                }
            }
        }
        var positions = 0;
        var normals = 0;
        var minimum = 65535;
        var maximum = 0;
        var newMinimum = 65535;
        var newMaximum = 0;
        var checkedZones = 0;
        var changedAttributes = (BitSet) changedIntensityVertices.clone();
        for (var y = 0; y < 256; y++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            for (var x = 0; x < 256; x++) {
                var oldHeight = before.getHeightAt(x, y);
                var newHeight = after.getHeightAt(x, y);
                minimum = Math.min(minimum, oldHeight);
                maximum = Math.max(maximum, oldHeight);
                newMinimum = Math.min(newMinimum, newHeight);
                newMaximum = Math.max(newMaximum, newHeight);
                var index = y * 256 + x;
                var inHalo = halo.get(index);
                var oldPosition = before.getWorldAt(x, y);
                var newPosition = after.getWorldAt(x, y);
                var moved = !oldPosition.equals(newPosition);
                var changedNormal =
                        !before.getNormalAt(x, y, turns, inverted).equals(after.getNormalAt(x, y, turns, inverted));
                if ((moved || changedNormal) && !inHalo) {
                    throw new IOException(
                            "Terrain geometry changes outside the edited samples and their one-sample halo");
                }
                if (!inHalo) {
                    continue;
                }
                if (moved && !changed.get(index)) {
                    throw new IOException("Terrain position changes outside the edited height samples");
                }
                if (moved) {
                    positions++;
                }
                if (changedNormal) {
                    normals++;
                }
                if (moved || changedNormal) {
                    changedAttributes.set(index);
                }
                if (regions.pointRegion(oldPosition).zone() != ownerZone
                        || regions.pointRegion(newPosition).zone() != ownerZone) {
                    throw new IOException(
                            "Affected Terrain vertex leaves its native owner zone at sample " + x + ", " + y);
                }
                checkedZones++;
            }
        }
        if (positions != changed.cardinality() || normals == 0 || minimum != newMinimum || maximum != newMaximum) {
            throw new IOException("Terrain operation changes global height extrema or has no bounded geometric effect");
        }
        var sectors = new HashMap<SectorCoordinate, Sector>();
        for (var sector : terrain.sectors()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (sectors.put(new SectorCoordinate(sector.offsetX(), sector.offsetY()), sector) != null
                    || !NativeTerrainSectorBounds.compute(before, sector)
                            .equals(NativeTerrainSectorBounds.compute(after, sector))) {
                throw new IOException("Terrain sector bounds or native lighting spheres change");
            }
        }
        var quadSet = new BitSet(65536);
        for (var index = changed.nextSetBit(0); index >= 0; index = changed.nextSetBit(index + 1)) {
            var cx = index % 256;
            var cy = index / 256;
            for (var y = cy - 1; y <= cy; y++) {
                for (var x = cx - 1; x <= cx; x++) {
                    quadSet.set(y * 256 + x);
                }
            }
        }
        var quads = 0;
        for (var quad = quadSet.nextSetBit(0); quad >= 0; quad = quadSet.nextSetBit(quad + 1)) {
            var x = quad % 256;
            var y = quad / 256;
            var sector = sectors.get(new SectorCoordinate(x / 16 * 16, y / 16 * 16));
            if (!visible.get(quad)
                    || sector == null
                    || sector.quadRenderPasses().size() != 256
                    || sector.quadRenderPasses().get((y % 16) * 16 + x % 16) <= 0) {
                throw new IOException(
                        "Affected Terrain quad lacks native visibility or material-pass coverage at " + x + ", " + y);
            }
            quads++;
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new GeometryFacts(
                positions,
                normals,
                minimum,
                maximum,
                sectors.size(),
                quads,
                ownerZone,
                checkedZones,
                changedIntensityVertices.cardinality(),
                NativeTerrainGridFootprint.capture(before, after, changedAttributes, turns, visible, cancelled));
    }

    static BitSet changedIntensityVertices(
            List<Result> before, List<Result> after, int width, int height, BooleanSupplier cancelled)
            throws IOException {
        if (before.isEmpty() || before.size() != after.size()) {
            throw new IOException("Terrain intensity frame domains differ or are incomplete");
        }
        var changed = new BitSet(width * height);
        for (var i = 0; i < before.size(); i++) {
            changed.or(changedIntensityVertices(before.get(i), after.get(i), width, height, cancelled));
        }
        return changed;
    }

    static BitSet changedIntensityVertices(
            Result before, Result after, int width, int height, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var window = before.window();
        if (width < 4
                || height < 4
                || width > 256
                || height > 256
                || !window.equals(after.window())
                || (long) window.offsetX() + window.width() > width
                || (long) window.offsetY() + window.height() > height
                || before.sunReference() <= 0
                || before.sunReference() != after.sunReference()
                || before.frames().isEmpty()
                || before.frames().size() > 64
                || before.frames().size() != after.frames().size()) {
            throw new IOException("Terrain intensity frame domains differ or are incomplete");
        }
        var changed = new BitSet(width * height);
        var count = window.width() * window.height();
        var previous = -1f;
        for (var slot = 0; slot < before.frames().size(); slot++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var oldFrame = before.frames().get(slot);
            var newFrame = after.frames().get(slot);
            if (!Float.isFinite(oldFrame.time())
                    || oldFrame.time() < 0
                    || oldFrame.time() >= 24
                    || oldFrame.time() <= previous
                    || Float.floatToRawIntBits(oldFrame.time()) != Float.floatToRawIntBits(newFrame.time())
                    || oldFrame.intensities().size() != count
                    || newFrame.intensities().size() != count) {
                throw new IOException("Terrain intensity slot times or sample counts differ");
            }
            previous = oldFrame.time();
            for (var index = 0; index < count; index++) {
                var oldValue = oldFrame.intensities().get(index);
                var newValue = newFrame.intensities().get(index);
                if (oldValue < 0 || oldValue > 255 || newValue < 0 || newValue > 255) {
                    throw new IOException("Terrain intensity sample is outside uint8");
                }
                if (!oldValue.equals(newValue)) {
                    changed.set((window.offsetY() + index / window.width()) * width
                            + window.offsetX()
                            + index % window.width());
                }
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return changed;
    }

    Report getReport() {
        return report;
    }

    void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        bake.verifySourcesUnchanged(cancelled);
    }
}
