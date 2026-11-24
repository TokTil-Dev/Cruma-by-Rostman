package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeLevelShadowQuery.State;
import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.WorldQuery;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Grid;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Result;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Window;
import com.rost.cruma.core.editing.P152EffectiveInputs.Source;
import com.rost.cruma.core.editing.P152FreshHashQuery.Ray;
import com.rost.cruma.core.editing.P152FreshLevelQuery.RayResult;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152TerrainSunBake {
    static final String MAP_HASH = "3ea3e022c394be36ba1d0e53e072959901d18fba0be913ef66364ecbd402952c";
    static final String HEIGHT_HASH = "346f8fe9ed001702b2a87827bd588236c3f5152b6fff103816aa9f7115462611";
    static final String OWNER = "12_24.TerrainInfo0";
    private final P152TerrainLightingInputs inputs;
    private final P152FreshLevelQuery query;
    private final Source heightSource;
    private final IsolatedHeightPackage height;
    private final TerrainHeightPatch patch;
    private final P152TerrainEditScope scope;
    private final List<Result> original, edited;
    private final long clearRays, blockedRays;

    private P152TerrainSunBake(
            P152TerrainLightingInputs inputs,
            P152FreshLevelQuery query,
            Source heightSource,
            IsolatedHeightPackage height,
            TerrainHeightPatch patch,
            P152TerrainEditScope scope,
            List<Result> original,
            List<Result> edited,
            long clearRays,
            long blockedRays) {
        this.inputs = inputs;
        this.query = query;
        this.heightSource = heightSource;
        this.height = height;
        this.patch = patch;
        this.scope = scope;
        this.original = List.copyOf(original);
        this.edited = List.copyOf(edited);
        this.clearRays = clearRays;
        this.blockedRays = blockedRays;
    }

    static P152TerrainSunBake prepare(Path root, Path map, TerrainHeightPatch patch, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(cancelled);
        var scope = requireOperation(patch);
        P152FreshHashQuery.checkCancelled(cancelled);
        var scene = captureScene(root, map, cancelled);
        var context = scene.context();
        var model = scene.model();
        var height = scene.height();
        var heightSource = scene.heightSource();
        var snapshot = scene.snapshot();
        var inputs = scene.inputs();
        var terrain = inputs.getTerrain();
        P152FreshHashQuery.checkCancelled(cancelled);
        var field = new TerrainHeightField(
                height.getCapturedHeightmap(), terrain.info().toWorld());
        var windowSectors = new ArrayList<Sector>();
        var oldBounds = new ArrayList<NativeTerrainSectorBounds.Result>();
        for (var window : scope.getWindows()) {
            var sector = terrain.sectors().stream()
                    .filter(s -> s.offsetX() == window.offsetX() && s.offsetY() == window.offsetY())
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "Terrain sunlight sector is absent at " + window.offsetX() + ", " + window.offsetY()));
            var bounds = NativeTerrainSectorBounds.compute(field, sector);
            if (!bounds.bounds().equals(sector.bounds())) {
                throw new IOException("Saved Terrain sector bounds differ from native calculation");
            }
            windowSectors.add(sector);
            oldBounds.add(bounds);
        }
        var before = new Grid(field, inputs.getEdgeTurns(), inputs.isInverted(), inputs.getVisibleQuads(), cancelled);
        try {
            field.apply(patch, false);
        } catch (IllegalStateException changed) {
            throw new IOException("The Terrain edit does not start from the original 12_24 heights", changed);
        }
        for (var i = 0; i < windowSectors.size(); i++) {
            if (!NativeTerrainSectorBounds.compute(field, windowSectors.get(i)).equals(oldBounds.get(i))) {
                throw new IOException(
                        "The edit changes a Terrain sector corner, so its native bounds or lighting sphere would"
                                + " change");
            }
        }
        var after = new Grid(field, inputs.getEdgeTurns(), inputs.isInverted(), inputs.getVisibleQuads(), cancelled);
        var rays = new ArrayList<Ray>();
        var limit = scope.getMaximumRays();
        WorldQuery collect = (start, end, flags) -> {
            if (flags != 0x197 || rays.size() >= limit) {
                throw new IllegalStateException("Terrain sunlight exceeded the bounded query profile");
            }
            rays.add(new Ray(start, end));
            return false;
        };
        for (var window : scope.getWindows()) {
            rebuild(before, inputs, window, collect, cancelled);
            rebuild(after, inputs, window, collect, cancelled);
        }
        if (rays.isEmpty()) {
            throw new IOException("Terrain sunlight has no eligible vertex in the edited sectors");
        }
        var query = P152FreshLevelQuery.capture(context, model, cancelled);
        var evaluation = query.evaluate(rays, cancelled);
        for (var ray : evaluation.rays()) {
            if (ray.result().state() == State.UNQUALIFIED) {
                throw new IOException(
                        "Terrain sunlight query is incomplete: " + ray.result().evidence());
            }
        }
        var replay = new Replay(evaluation.rays());
        var original = new ArrayList<Result>();
        var edited = new ArrayList<Result>();
        for (var window : scope.getWindows()) {
            original.add(rebuild(before, inputs, window, replay, cancelled));
            edited.add(rebuild(after, inputs, window, replay, cancelled));
        }
        replay.requireEnd();
        var time = snapshot.findAuthoredTimeMaps(OWNER).orElseThrow();
        if (time.compareSectorMaps(terrain.sectors()).mismatchingSamples() != 0) {
            throw new IOException("Native sunlight baseline differs from saved owner or sector bytes");
        }
        for (var result : original) {
            if (!Arrays.equals(time.withWindow(result).getOriginalBytes(), time.getOriginalBytes())) {
                throw new IOException("Native sunlight baseline differs from saved owner or sector bytes");
            }
        }
        var composed = time;
        for (var result : edited) {
            composed = composed.withWindow(result);
        }
        for (var result : edited) {
            if (!Arrays.equals(composed.withWindow(result).getOriginalBytes(), composed.getOriginalBytes())) {
                throw new IOException("Edited sunlight windows disagree on a shared sector seam");
            }
        }
        var clear = evaluation.rays().stream()
                .filter(r -> r.result().state() == State.CLEAR)
                .count();
        var result = new P152TerrainSunBake(
                inputs, query, heightSource, height, patch, scope, original, edited, clear, rays.size() - clear);
        result.verifySourcesUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    record Scene(
            P152FreshBakeContext context,
            P152FreshBspModel model,
            IsolatedHeightPackage height,
            Source heightSource,
            TerrainPackageSnapshot snapshot,
            P152TerrainLightingInputs inputs) {}

    static Scene captureScene(Path root, Path map, BooleanSupplier cancelled) throws IOException {
        var context = P152FreshBakeContext.capture(root, map, cancelled);
        if (!context.getMapSha256().equals(MAP_HASH)) {
            throw new IOException("Terrain sunlight operation requires the authenticated original 12_24 map");
        }
        if (!context.getGaps().isEmpty()) {
            throw new IOException(
                    "Terrain sunlight scene is incomplete: " + context.getGaps().getFirst());
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        var model = P152FreshBspModel.capture(context, cancelled);
        var heightPath = context.getClientRoot().resolve("Textures/T_12_24.utx");
        var heightReal = heightPath.toRealPath();
        if (!heightReal.startsWith(context.getClientRoot().toRealPath())) {
            throw new IOException("Terrain height source is outside the client root");
        }
        var height = IsolatedHeightPackage.open(heightPath, "T_12_24.Height.12_24");
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!height.getOriginalSha256().equals(HEIGHT_HASH)) {
            throw new IOException("Terrain sunlight height fingerprint is not qualified");
        }
        var heightSource = new Source(heightPath, heightReal, HEIGHT_HASH);
        var snapshot = TerrainPackageSnapshot.open(map, root);
        P152FreshHashQuery.checkCancelled(cancelled);
        var inputs = P152TerrainLightingInputs.capture(context, model, snapshot, OWNER, cancelled);
        var terrain = inputs.getTerrain();
        if (!terrain.heightmapObjectPath().equals("T_12_24.Height.12_24")
                || !terrain.heightmap().equals(List.of(height.getCapturedHeightmap()))) {
            throw new IOException("Terrain sunlight height snapshot differs from the authenticated asset");
        }
        inputs.verifyNoSteadyContributions();
        return new Scene(context, model, height, heightSource, snapshot, inputs);
    }

    static P152TerrainEditScope requireOperation(TerrainHeightPatch patch) throws IOException {
        return P152TerrainEditScope.of(patch);
    }

    P152TerrainLightingInputs getInputs() {
        return inputs;
    }

    P152FreshLevelQuery getQuery() {
        return query;
    }

    IsolatedHeightPackage getHeightPackage() {
        return height;
    }

    TerrainHeightPatch getPatch() {
        return patch;
    }

    P152TerrainEditScope getScope() {
        return scope;
    }

    List<Result> getOriginal() {
        return original;
    }

    List<Result> getEdited() {
        return edited;
    }

    long getClearRays() {
        return clearRays;
    }

    long getBlockedRays() {
        return blockedRays;
    }

    void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        heightSource.verifyUnchanged(cancelled);
        inputs.verifySourcesUnchanged(cancelled);
    }

    private static Result rebuild(
            Grid grid, P152TerrainLightingInputs inputs, Window window, WorldQuery query, BooleanSupplier cancelled) {
        return NativeTerrainTimeLighting.rebuild(
                grid, inputs.getOwner(), window, inputs.getLights(), inputs.getSlots(), query, cancelled);
    }

    private static final class Replay implements WorldQuery {
        private final List<RayResult> rays;
        private int index;

        Replay(List<RayResult> rays) {
            this.rays = List.copyOf(rays);
        }

        @Override
        public boolean clear(Vector start, Vector end, int flags) {
            if (index >= rays.size() || flags != 0x197) {
                throw new IllegalStateException("Terrain sunlight replay exceeds its query plan");
            }
            var expected = rays.get(index++);
            if (!expected.ray().equals(new Ray(start, end)) || expected.result().state() == State.UNQUALIFIED) {
                throw new IllegalStateException("Terrain sunlight replay differs from its frozen query plan");
            }
            return expected.result().state() == State.CLEAR;
        }

        void requireEnd() {
            if (index != rays.size()) {
                throw new IllegalStateException("Terrain sunlight replay is incomplete");
            }
        }
    }
}
