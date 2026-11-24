package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainBrushStamp;
import com.rost.cruma.core.editing.TerrainBrushes.Falloff;
import com.rost.cruma.core.editing.TerrainBrushes.RampPoint;
import com.rost.cruma.core.editing.TerrainBrushes.Shape;
import com.rost.cruma.core.editing.TerrainDecorationSources;
import com.rost.cruma.core.editing.TerrainDecorationSources.Source;
import com.rost.cruma.core.editing.TerrainEditHistory.Preview;
import com.rost.cruma.core.editing.TerrainEditHistory.Revision;
import com.rost.cruma.core.editing.TerrainEditorGeometry;
import com.rost.cruma.core.editing.TerrainEditorGeometry.LayerSource;
import com.rost.cruma.core.editing.TerrainEditorGeometry.SeamHeights;
import com.rost.cruma.core.editing.TerrainEditorGeometry.SeamSectors;
import com.rost.cruma.core.editing.TerrainHeightField;
import com.rost.cruma.core.editing.TerrainHeightField.Brush;
import com.rost.cruma.core.editing.TerrainHeightField.OutsideHeights;
import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import com.rost.cruma.core.editing.TerrainLayerMapping;
import com.rost.cruma.core.editing.TerrainMappingHistory;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainPackageSnapshot;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.editing.TerrainPaintHistory;
import com.rost.cruma.core.editing.TerrainPaintHistory.Change;
import com.rost.cruma.core.editing.TerrainQuadHistory;
import com.rost.cruma.core.editing.TerrainTileLink;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.TerrainAlphaAtlas;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader.DecorationDensity;
import com.rost.cruma.core.world.UnrealLoader.DecorationGround;
import com.rost.cruma.core.world.UnrealLoader.DecorationTemplate;
import com.rost.cruma.viewer.editing.GroundProbe.Shown;
import com.rost.cruma.viewer.editing.TerrainEditingController.RecoveryPreview;
import com.rost.cruma.viewer.editing.TerrainEditingController.Step;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class TerrainViewportSession implements AutoCloseable {
    private static final long DECORATION_INTERVAL = TimeUnit.MILLISECONDS.toNanos(120);

    private final TerrainEditingController controller;
    private final TerrainAssetResolver assets;
    private final ViewerRenderBackend backend;
    private final Thread ownerThread = Thread.currentThread();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("terrain-editor").factory());
    private final ExecutorService lightingWorker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("terrain-lighting").factory());
    private final ExecutorService openingWorker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("terrain-opening").factory());
    private final AtomicReference<Publication> publication = new AtomicReference<>();
    private volatile boolean closed;
    private final Map<String, View> views = new ConcurrentHashMap<>();
    private @Nullable View openingView;
    private final List<Beside> besides = new ArrayList<>();
    private @Nullable Beside installing;
    private long generation;
    private List<TerrainDocument> linkedDocuments = List.of();
    private long waitedNanos;
    private int publications;
    private volatile Timing lastTiming = new Timing(0, 0);
    private CompletableFuture<Void> pending;
    private final Map<String, SteadyLight> steadyLight = new LinkedHashMap<>();
    private final Set<String> lightRequests = new LinkedHashSet<>();
    private final Map<String, TerrainEditorGeometry> lightOwners = new HashMap<>();
    private String error = "";
    private volatile String status = "";
    private EditingMaps mapFiles = EditingMaps.MAIN;

    public TerrainViewportSession(
            Path clientRoot, Options options, TextureQuality quality, ViewerRenderBackend backend) {
        this.backend = backend;
        assets = new TerrainAssetResolver(clientRoot, options, quality);
        controller = new TerrainEditingController(clientRoot);
        controller.setRecoveryPreview(new RecoveryPreview() {
            @Override
            public Preview install(Path map, Terrain terrain, short[] samples) {
                requireOwner();
                requireIdle();
                var owner = terrain.path();
                var loaded = assets.loadWithDecorations(map, owner, terrain.info());
                var candidate = withLight(loaded.geometry(), owner);
                lightOwners.remove(owner);
                lightRequests.remove(owner);
                var sectors = candidate.build(samples, candidate.getSectorCoordinates());
                var view = new View(owner);
                view.seamIndex = candidate.getSeamSectorsBySample();
                var resident = isLoaded(owner);
                if (resident) {
                    backend.replaceEditorTerrain(owner, sectors, true);
                    view.shown = true;
                }
                view.geometry = candidate;
                view.visibleSamples = samples.clone();
                view.pendingPaintLayers = candidate.getLayers();
                view.decorations = loaded.decorations();
                view.resident = resident;
                views.put(owner, view);
                return revision -> publishPreview(view, revision);
            }

            @Override
            public void clear() {
                requireOwner();
                dropViews();
                dropBesides("The Terrains closed.");
            }
        });
    }

    private static final class View {
        final String owner;
        volatile @Nullable TerrainEditorGeometry geometry;
        volatile short @Nullable [] visibleSamples;
        final Set<SectorCoordinate> strokeSectors = new LinkedHashSet<>();

        @Nullable
        List<LayerSource> pendingPaintLayers;

        volatile List<DecorationTemplate> decorations = List.of();
        final Set<Integer> pendingDecorations = new LinkedHashSet<>();
        long decorationsShownAt;
        volatile boolean decorationsReplaced;

        boolean reloadRequested;
        volatile boolean resident;
        boolean shown;

        @Nullable
        CompletableFuture<Void> lighting;

        volatile boolean closed;

        volatile List<Neighbor> neighbors = List.of();
        volatile SeamHeights seams = SeamHeights.NONE;
        volatile @Nullable SeamSectors seamIndex;
        final Set<SectorCoordinate> pendingSeams = new LinkedHashSet<>();

        View(String owner) {
            this.owner = owner;
        }
    }

    private record Neighbor(TerrainDocument document, TerrainTileLink link) {}

    private static final class Beside {
        final String owner;
        final View view;
        final long generation;
        final CompletableFuture<String> answer = new CompletableFuture<>();

        @Nullable
        CompletableFuture<Prepared> reading;

        Beside(String owner, View view, long generation) {
            this.owner = owner;
            this.view = view;
            this.generation = generation;
        }
    }

    private record Prepared(
            TerrainDocument document, TerrainEditorGeometry geometry, Map<String, List<Entity>> sectors) {}

    record Timing(long workNanos, int publications) {}

    private record Target(TerrainDocument document, View view) {
        String owner() {
            return document.getOwner();
        }
    }

    private record Publication(
            @Nullable View view,
            String owner,
            Map<String, List<Entity>> sectors,
            boolean initialize,
            boolean inPlace,
            List<AtlasUpdate> atlases,
            short @Nullable [] samples,
            @Nullable CompletableFuture<Void> acknowledged,
            boolean light,
            Map<String, List<Entity>> decorations,
            @Nullable DecorationSources decorationSources) {
        Publication(
                @Nullable View view,
                String owner,
                Map<String, List<Entity>> sectors,
                boolean initialize,
                boolean inPlace,
                List<AtlasUpdate> atlases,
                short @Nullable [] samples,
                @Nullable CompletableFuture<Void> acknowledged,
                boolean light) {
            this(view, owner, sectors, initialize, inPlace, atlases, samples, acknowledged, light, Map.of(), null);
        }
    }

    private record DecorationSources(Map<Integer, DecorationDensity> densities, DecorationGround ground) {}

    private record SteadyLight(Path map, Info info, short[] samples, int[] rgb) {}

    private record AtlasUpdate(TerrainAlphaAtlas atlas, int channel, int x0, int y0, int x1, int y1, byte[] values) {}

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }

    public TerrainEditingController getController() {
        return controller;
    }

    public boolean isBusy() {
        if (Thread.currentThread() == ownerThread) {
            settle();
        }
        return pending != null;
    }

    public boolean isOpening() {
        return isBusy() && openingView != null;
    }

    public boolean isReadingLighting() {
        var opening = openingView;
        if (opening != null && opening.lighting != null && !opening.lighting.isDone()) {
            return true;
        }
        for (var view : views.values()) {
            if (view.lighting != null && !view.lighting.isDone()) {
                return true;
            }
        }
        return false;
    }

    public String getError() {
        return error;
    }

    public String getStatus() {
        return status;
    }

    public boolean isOpen() {
        return !isBusy() && controller.isOpen();
    }

    public boolean isResident() {
        var active = controller.getActive();
        return active != null && isResident(active.getOwner());
    }

    public boolean isResident(String owner) {
        var view = views.get(owner);
        return view != null && view.resident;
    }

    public void updateProbe(GroundProbe probe) {
        var shown = new ArrayList<Shown>();
        for (var document : controller.getDocuments()) {
            var view = views.get(document.getOwner());
            var samples = view == null ? null : view.visibleSamples;
            if (samples == null) {
                continue;
            }
            var saved = document.getTerrain().heightmap();
            shown.add(new Shown(
                    document.getOwner(), samples, document.getInfo(), saved.isEmpty() ? null : saved.getFirst()));
        }
        probe.setOpen(shown);
    }

    public short[] getVisibleSamples() {
        var active = controller.getActive();
        return active == null ? null : getVisibleSamples(active.getOwner());
    }

    public short @Nullable [] getVisibleSamples(String owner) {
        var view = views.get(owner);
        return view == null ? null : view.visibleSamples;
    }

    public void poll() {
        requireOwner();
        var ready = publication.getAndSet(null);
        if (ready != null) {
            try {
                if (closed) {
                    throw new IllegalStateException("Terrain session closed before publication");
                }
                show(ready);
                ready.acknowledged().complete(null);
            } catch (RuntimeException | Error failure) {
                ready.acknowledged().completeExceptionally(failure);
            }
        }
        settle();
        if (closed || pending != null || controller.isExportLocked()) {
            return;
        }
        relink();
        if (installBeside()) {
            return;
        }
        for (var view : views.values()) {
            if (!view.pendingSeams.isEmpty()) {
                submit(() -> {});
                return;
            }
        }
        for (var view : views.values()) {
            var document = controller.findDocument(view.owner);
            if (view.pendingPaintLayers != null && document != null && document.getPaint() == null) {
                var layers = view.pendingPaintLayers;
                view.pendingPaintLayers = null;
                submit(() -> {
                    document.beginPaint(layers, changes -> publishPaint(view, changes));
                    document.beginMapping((layer, mapping) -> publishMapping(view, layer, mapping));
                    document.beginQuads((visible, turns, sectors) -> publishQuads(view, visible, turns, sectors));
                    beginDecorations(view, document, layers);
                });
                return;
            }
        }
        for (var view : views.values()) {
            var document = controller.findDocument(view.owner);
            var template = view.geometry;
            if (view.reloadRequested && document != null && template != null) {
                view.reloadRequested = false;
                submit(() -> {
                    publish(
                            view,
                            build(view, template, document.getHistory().getSamples(), template.getSectorCoordinates()),
                            true);
                    if (document.hasSourceChanges()) {
                        scatterDecorations(view, null, true);
                    }
                });
                return;
            }
        }
        if (!lightRequests.isEmpty() && !controller.isRecoveryGestureActive()) {
            var owner = lightRequests.iterator().next();
            lightRequests.remove(owner);
            showSteadyLight(owner);
        }
    }

    public void previewSteadyLight(Path map, String owner, Info info, short[] samples, int @Nullable [] rgb) {
        requireOwner();
        var old = steadyLight.get(owner);
        if (old == null ? rgb == null : Arrays.equals(old.rgb(), rgb)) {
            return;
        }
        if (rgb == null) {
            steadyLight.remove(owner);
        } else {
            steadyLight.put(owner, new SteadyLight(map, info, samples.clone(), rgb.clone()));
        }
        lightRequests.add(owner);
    }

    Set<String> getSteadyLightOwners() {
        return Set.copyOf(steadyLight.keySet());
    }

    public void open(Path map, String owner) {
        requireOwner();
        requireIdle();
        if (controller.isRecoveryGestureActive()) {
            throw new IllegalStateException("Finish the current Terrain operation first");
        }
        relink();
        if (controller.findDocument(owner) != null && views.containsKey(owner)) {
            controller.activate(owner);
            return;
        }
        var refusal = TerrainEditingController.quickRefusal(owner, controller.getDocuments());
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        if (!isLoaded(owner)) {
            throw new IllegalArgumentException(whyNotLoaded(owner));
        }
        lightOwners.remove(owner);
        lightRequests.remove(owner);
        var light = steadyLight.get(owner);
        var view = new View(owner);
        view.resident = true;
        submit(() -> {
            TerrainDocument document = null;
            try {
                controller.inspect(map);
                document = controller.addDocument(owner, revision -> publishPreview(view, revision));
                var loaded = assets.loadWithDecorations(map, owner, document.getInfo());
                var candidate =
                        light == null ? loaded.geometry() : loaded.geometry().withSteadyLight(light.rgb());
                view.decorations = loaded.decorations();
                view.seamIndex = candidate.getSeamSectorsBySample();
                link(view, document, controller.getDocuments());
                publish(
                        view,
                        build(view, candidate, document.getHistory().getSamples(), candidate.getSectorCoordinates()),
                        true);
                view.geometry = candidate;
                status = "Reading the layers";
                document.beginPaint(candidate.getLayers(), changes -> publishPaint(view, changes));
                document.beginMapping((layer, mapping) -> publishMapping(view, layer, mapping));
                document.beginQuads((visible, turns, sectors) -> publishQuads(view, visible, turns, sectors));
                status = "Reading the decorations";
                beginDecorations(view, document, candidate.getLayers());
                status = "Terrain brush ready";
            } catch (Exception | Error failure) {
                if (document != null) {
                    controller.closeDocument(owner);
                }
                view.geometry = null;
                throw failure;
            }
        });
        openingView = view;
        view.lighting = pending.thenRunAsync(
                        () -> {
                            var document = controller.findDocument(owner);
                            if (document != null) {
                                controller.prepareLighting(
                                        document,
                                        () -> closed
                                                || view.closed
                                                || Thread.currentThread().isInterrupted());
                            }
                        },
                        lightingWorker)
                .exceptionally(ignored -> null);
    }

    public CompletableFuture<String> openBeside(Path map, String owner, @Nullable String from) {
        requireOwner();
        var view = new View(owner);
        view.resident = true;
        var beside = new Beside(owner, view, generation);
        if (closed) {
            beside.answer.complete("The Terrain editor closed.");
            return beside.answer;
        }
        if (controller.findDocument(owner) != null) {
            beside.answer.complete("");
            return beside.answer;
        }
        for (var running : besides) {
            if (running.owner.equals(owner)) {
                return running.answer;
            }
        }
        var refusal = TerrainEditingController.quickRefusal(owner, controller.getDocuments());
        if (refusal == null && !isLoaded(owner)) {
            refusal = whyNotLoaded(owner) + ".";
        }
        if (refusal != null) {
            beside.answer.complete(refusal);
            return beside.answer;
        }
        var base = from == null ? null : controller.findDocument(from);
        var light = steadyLight.get(owner);
        var root = controller.getClientRoot();
        beside.reading = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var snapshot = TerrainPackageSnapshot.open(map, root);
                        var document = TerrainEditingController.prepareDocument(
                                snapshot, owner, revision -> publishPreview(view, revision), controller.getDocuments());
                        if (base != null
                                && !TerrainTileLink.of(base.getInfo(), document.getInfo())
                                        .fits()) {
                            throw new IllegalStateException(
                                    "Its grid does not meet the grid of " + base.getMap() + ".");
                        }
                        var loaded = assets.loadWithDecorations(map, owner, document.getInfo());
                        var geometry = light == null
                                ? loaded.geometry()
                                : loaded.geometry().withSteadyLight(light.rgb());
                        view.decorations = loaded.decorations();
                        var sectors =
                                geometry.build(document.getHistory().getSamples(), geometry.getSectorCoordinates());
                        document.beginPaint(geometry.getLayers(), changes -> publishPaint(view, changes));
                        document.beginMapping((layer, mapping) -> publishMapping(view, layer, mapping));
                        document.beginQuads((visible, turns, changed) -> publishQuads(view, visible, turns, changed));
                        beginDecorations(view, document, geometry.getLayers());
                        view.seamIndex = geometry.getSeamSectorsBySample();
                        return new Prepared(document, geometry, sectors);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                },
                openingWorker);
        besides.add(beside);
        return beside.answer;
    }

    public boolean isOpeningBeside() {
        return !besides.isEmpty() || installing != null;
    }

    public void activate(String owner) {
        requireOwner();
        controller.activate(owner);
    }

    public void closeDocument(String owner) {
        requireOwner();
        requireIdle();
        var document = controller.findDocument(owner);
        if (document == null) {
            return;
        }
        if (controller.getDocuments().size() == 1) {
            discard();
            return;
        }
        if (document.isStrokeActive()) {
            throw new IllegalStateException("Finish the stroke first");
        }
        var view = views.remove(owner);
        if (view != null) {
            restoreDecorations(List.of(view));
            forget(view);
        }
        controller.closeDocument(owner);
    }

    public void stamp(Brush brush, float x, float y, float radius, float strength, int target) {
        sculpt(brush, radius, target, List.of(new TerrainBrushStamp(x, y, strength)));
    }

    public void sculpt(Brush brush, float radius, int target, List<TerrainBrushStamp> stamps) {
        sculpt(Shape.of(brush, radius, target), stamps);
    }

    public void sculpt(Shape shape, List<TerrainBrushStamp> stamps) {
        sculpt(null, shape, stamps);
    }

    public void sculpt(@Nullable String owner, Shape shape, List<TerrainBrushStamp> stamps) {
        var target = strokeTarget(owner);
        var batch = List.copyOf(stamps);
        submit(() -> controller.getSculptStroke(target.document(), true).stamps(shape, batch));
    }

    public void ramp(RampPoint a, RampPoint b, float width, Falloff falloff, String label) {
        ramp(null, a, b, width, falloff, label);
    }

    public void ramp(@Nullable String owner, RampPoint a, RampPoint b, float width, Falloff falloff, String label) {
        var target = strokeTarget(owner);
        submit(() -> {
            var stroke = controller.getSculptStroke(target.document(), true);
            try {
                stroke.ramp(a, b, width, falloff);
            } catch (RuntimeException failure) {
                stroke.cancel();
                replaceStrokeSectors();
                throw failure;
            }
            stroke.finish(label);
            replaceStrokeSectors();
        });
    }

    public void moveSelection(float[] weights, float raw, String label) {
        moveSelection(null, weights, raw, label);
    }

    public void moveSelection(@Nullable String owner, float[] weights, float raw, String label) {
        var target = strokeTarget(owner);
        var copy = weights.clone();
        submit(() -> {
            var stroke = controller.getSculptStroke(target.document(), false);
            try {
                stroke.moveSelection(copy, raw);
            } catch (RuntimeException failure) {
                stroke.cancel();
                replaceStrokeSectors();
                throw failure;
            }
            if (label.isEmpty()) {
                return;
            }
            stroke.finish(label);
            replaceStrokeSectors();
        });
    }

    public void fill(int layer, Function<TerrainHeightField, BitSet> texels, boolean coverAbove, String label) {
        fill(null, layer, texels, coverAbove, label);
    }

    public void fill(
            @Nullable String owner,
            int layer,
            Function<TerrainHeightField, BitSet> texels,
            boolean coverAbove,
            String label) {
        var target = target(owner);
        submit(() -> {
            var document = target.document();
            var paint = document.getPaint();
            if (paint == null) {
                throw new IllegalStateException("The layers of this Terrain cannot be painted");
            }
            if (controller.isStrokeActive()) {
                throw new IllegalStateException("Finish the stroke first");
            }
            var info = document.getInfo();
            var field = new TerrainHeightField(
                    info.width(), info.height(), document.getHistory().getSamples(), info.toWorld());
            if (paint.fill(layer, texels.apply(field), coverAbove)) {
                controller.recordStep(Step.WEIGHTS, Set.of(target.owner()), label);
            }
        });
    }

    public void paint(
            int layer,
            TerrainPaintHistory.Brush brush,
            float radius,
            boolean coverAbove,
            List<TerrainBrushStamp> stamps) {
        paint(null, layer, brush, radius, coverAbove, stamps);
    }

    public void paint(
            @Nullable String owner,
            int layer,
            TerrainPaintHistory.Brush brush,
            float radius,
            boolean coverAbove,
            List<TerrainBrushStamp> stamps) {
        var target = strokeTarget(owner);
        var batch = List.copyOf(stamps);
        submit(() -> {
            var document = target.document();
            var paint = document.getPaint();
            if (paint == null) {
                throw new IllegalStateException("The layers of this Terrain cannot be painted");
            }
            if (document.getHistory().isStrokeActive()) {
                throw new IllegalStateException("Finish the sculpt stroke first");
            }
            if (!paint.isStrokeActive()) {
                paint.beginStroke();
            }
            paint.stamps(layer, brush, radius, coverAbove, batch);
        });
    }

    public void revertHeights(String label) {
        revertHeights(null, label);
    }

    public void revertHeights(@Nullable String owner, String label) {
        var target = strokeTarget(owner);
        submit(() -> {
            var stroke = controller.getSculptStroke(target.document(), false);
            stroke.revertToSource();
            stroke.finish(label);
            replaceStrokeSectors();
        });
    }

    public void revertLayer(int layer, String label) {
        revertLayer(null, layer, label);
    }

    public void revertLayer(@Nullable String owner, int layer, String label) {
        var target = target(owner);
        submit(() -> {
            var paint = target.document().getPaint();
            if (paint == null) {
                throw new IllegalStateException("The layers of this Terrain cannot be painted");
            }
            if (controller.isStrokeActive()) {
                throw new IllegalStateException("Finish the stroke first");
            }
            if (paint.revertLayer(layer)) {
                controller.recordStep(Step.WEIGHTS, Set.of(target.owner()), label);
            }
        });
    }

    public void paintDecoration(
            @Nullable String owner,
            int slot,
            TerrainPaintHistory.Brush brush,
            float radius,
            List<TerrainBrushStamp> stamps) {
        var target = strokeTarget(owner);
        var batch = List.copyOf(stamps);
        submit(() -> {
            var document = target.document();
            var density = document.getDecorations();
            if (density == null) {
                throw new IllegalStateException("The decorations of this Terrain cannot be painted");
            }
            if (document.getHistory().isStrokeActive()
                    || document.getPaint() != null && document.getPaint().isStrokeActive()
                    || document.getQuads() != null && document.getQuads().isStrokeActive()) {
                throw new IllegalStateException("Finish the stroke first");
            }
            if (!density.isStrokeActive()) {
                density.beginStroke();
            }
            density.stamps(slot, brush, radius, false, batch);
        });
    }

    public void fillDecoration(
            @Nullable String owner, int slot, Function<TerrainHeightField, BitSet> texels, String label) {
        var target = target(owner);
        submit(() -> {
            var document = target.document();
            var density = document.getDecorations();
            if (density == null) {
                throw new IllegalStateException("The decorations of this Terrain cannot be painted");
            }
            if (controller.isStrokeActive()) {
                throw new IllegalStateException("Finish the stroke first");
            }
            var info = document.getInfo();
            var field = new TerrainHeightField(
                    info.width(), info.height(), document.getHistory().getSamples(), info.toWorld());
            if (density.fill(slot, texels.apply(field), false)) {
                controller.recordStep(Step.DECORATIONS, Set.of(target.owner()), label);
            }
            flushDecorations(target.view());
        });
    }

    public void revertDecoration(@Nullable String owner, int slot, String label) {
        var target = target(owner);
        submit(() -> {
            var density = target.document().getDecorations();
            if (density == null) {
                throw new IllegalStateException("The decorations of this Terrain cannot be painted");
            }
            if (controller.isStrokeActive()) {
                throw new IllegalStateException("Finish the stroke first");
            }
            if (density.revertLayer(slot)) {
                controller.recordStep(Step.DECORATIONS, Set.of(target.owner()), label);
            }
            flushDecorations(target.view());
        });
    }

    public void quads(TerrainQuadHistory.Brush brush, float radius, List<TerrainBrushStamp> stamps) {
        quads(null, brush, radius, stamps);
    }

    public void quads(
            @Nullable String owner, TerrainQuadHistory.Brush brush, float radius, List<TerrainBrushStamp> stamps) {
        var target = strokeTarget(owner);
        var batch = List.copyOf(stamps);
        submit(() -> {
            var document = target.document();
            var bits = requireQuads(document);
            if (document.getHistory().isStrokeActive()
                    || document.getPaint() != null && document.getPaint().isStrokeActive()) {
                throw new IllegalStateException("Finish the stroke first");
            }
            if (!bits.isStrokeActive()) {
                bits.beginStroke();
            }
            for (var stamp : batch) {
                bits.stamp(brush, stamp.x(), stamp.y(), radius);
            }
        });
    }

    public void revertQuads(String label) {
        revertQuads(null, label);
    }

    public void revertQuads(@Nullable String owner, String label) {
        var target = target(owner);
        submit(() -> {
            var bits = requireQuads(target.document());
            if (controller.isStrokeActive()) {
                throw new IllegalStateException("Finish the stroke first");
            }
            bits.beginStroke();
            bits.revertToSource();
            if (bits.finishStroke()) {
                controller.recordStep(Step.QUADS, Set.of(target.owner()), label);
            }
        });
    }

    public void setLayerMapping(int layer, TerrainLayerMapping mapping, String label) {
        setLayerMapping(null, layer, mapping, label);
    }

    public void setLayerMapping(@Nullable String owner, int layer, TerrainLayerMapping mapping, String label) {
        var target = target(owner);
        submit(() -> {
            var layers = requireMapping(target.document());
            if (layers.set(layer, mapping)) {
                controller.recordStep(Step.MAPPING, Set.of(target.owner()), label);
            }
        });
    }

    public void revertLayerMapping(int layer, String label) {
        revertLayerMapping(null, layer, label);
    }

    public void revertLayerMapping(@Nullable String owner, int layer, String label) {
        var target = target(owner);
        submit(() -> {
            if (requireMapping(target.document()).revert(layer)) {
                controller.recordStep(Step.MAPPING, Set.of(target.owner()), label);
            }
        });
    }

    public void finishStroke() {
        finishStroke("");
    }

    public void finishStroke(String label) {
        var targets = targets();
        submit(() -> {
            var stroke = controller.getSculptStroke();
            if (stroke != null) {
                stroke.finish(label.isEmpty() ? "Sculpt ground" : label);
            }
            for (var target : targets) {
                var document = target.document();
                var owners = Set.of(target.owner());
                if (document.getHistory().isStrokeActive()
                        && document.getHistory().finishStroke()) {
                    controller.recordStep(Step.HEIGHTS, owners, label.isEmpty() ? "Sculpt ground" : label);
                }
                var paint = document.getPaint();
                if (paint != null && paint.isStrokeActive() && paint.finishStroke()) {
                    controller.recordStep(Step.WEIGHTS, owners, label.isEmpty() ? "Paint layer" : label);
                }
                var bits = document.getQuads();
                if (bits != null && bits.isStrokeActive() && bits.finishStroke()) {
                    controller.recordStep(Step.QUADS, owners, label.isEmpty() ? "Change quads" : label);
                }
                var density = document.getDecorations();
                if (density != null && density.isStrokeActive() && density.finishStroke()) {
                    controller.recordStep(Step.DECORATIONS, owners, label.isEmpty() ? "Paint decorations" : label);
                }
            }
            replaceStrokeSectors();
            for (var view : views.values()) {
                flushDecorations(view);
            }
        });
    }

    public void cancelStroke() {
        var targets = targets();
        if (targets.isEmpty()) {
            throw new IllegalStateException("No supported Terrain owner is open");
        }
        submit(() -> {
            var stroke = controller.getSculptStroke();
            if (stroke != null) {
                stroke.cancel();
            }
            for (var target : targets) {
                var document = target.document();
                document.getHistory().cancelStroke();
                var paint = document.getPaint();
                if (paint != null) {
                    paint.cancelStroke();
                }
                var bits = document.getQuads();
                if (bits != null) {
                    bits.cancelStroke();
                }
                var density = document.getDecorations();
                if (density != null) {
                    density.cancelStroke();
                }
            }
            replaceStrokeSectors();
            for (var view : views.values()) {
                flushDecorations(view);
            }
        });
    }

    public void undo() {
        submit(controller::undo);
    }

    public void redo() {
        submit(controller::redo);
    }

    public void clearRedo() {
        submit(controller::clearRedo);
    }

    public void setMapFiles(EditingMaps files) {
        mapFiles = files;
    }

    public void mapEvicted(String name) {
        requireOwner();
        for (var view : allViews()) {
            if (view.owner.startsWith(name + ".")) {
                view.resident = false;
                view.shown = false;
            }
        }
        lightOwners.keySet().removeIf(owner -> owner.startsWith(name + "."));
    }

    public void mapLoaded(String name) {
        requireOwner();
        for (var view : allViews()) {
            if (view.owner.startsWith(name + ".")) {
                view.resident = true;
                view.reloadRequested = true;
            }
        }
        for (var owner : steadyLight.keySet()) {
            if (owner.startsWith(name + ".") && !views.containsKey(owner)) {
                lightOwners.remove(owner);
                lightRequests.add(owner);
            }
        }
    }

    public void discard() {
        requireOwner();
        requireIdle();
        restoreDecorations(views.values());
        dropViews();
        dropBesides("The Terrains closed.");
        controller.close();
    }

    Timing getLastTiming() {
        return lastTiming;
    }

    public void stopForRecovery() {
        requireOwner();
        closed = true;
        controller.freezeCommittedRecovery();
        var waiting = publication.getAndSet(null);
        if (waiting != null) {
            waiting.acknowledged().completeExceptionally(new IllegalStateException("Terrain session closed"));
        }
        worker.shutdownNow();
        lightingWorker.shutdownNow();
        openingWorker.shutdownNow();
    }

    @Override
    public void close() {
        try {
            restoreDecorations(allViews());
        } catch (RuntimeException failure) {
        }
        stopForRecovery();
        for (var view : allViews()) {
            if (view.shown) {
                backend.clearEditorTerrain(view.owner);
            }
            view.closed = true;
        }
        for (var owner : lightOwners.keySet()) {
            backend.clearEditorTerrain(owner);
        }
        lightOwners.clear();
        steadyLight.clear();
        lightRequests.clear();
        views.clear();
        openingView = null;
        dropBesides("The Terrain editor closed.");
    }

    private void showSteadyLight(String owner) {
        var light = steadyLight.get(owner);
        var rgb = light == null ? null : light.rgb();
        var view = views.get(owner);
        var document = controller.findDocument(owner);
        if (view != null && document != null && view.geometry != null) {
            submit(() -> {
                var before = view.geometry;
                var next = before.withSteadyLight(rgb);
                var sectors = before.getSteadyLightSectors(next);
                if (!sectors.isEmpty()) {
                    var samples = document.getHistory().getSamples();
                    publish(new Publication(
                            view,
                            owner,
                            build(view, next, samples, sectors),
                            false,
                            false,
                            List.of(),
                            samples,
                            null,
                            false));
                }
                view.geometry = next;
            });
            return;
        }
        var shown = lightOwners.get(owner);
        if (light == null) {
            if (shown != null) {
                lightOwners.remove(owner);
                backend.clearEditorTerrain(owner);
            }
            return;
        }
        if (!isLoaded(owner)) {
            return;
        }
        submit(() -> {
            var template = shown != null ? shown : assets.load(light.map(), owner, light.info());
            var next = template.withSteadyLight(light.rgb());
            var sectors = shown == null ? next.getSectorCoordinates() : template.getSteadyLightSectors(next);
            if (!sectors.isEmpty()) {
                publish(new Publication(
                        null,
                        owner,
                        next.build(light.samples(), sectors),
                        shown == null,
                        false,
                        List.of(),
                        null,
                        null,
                        true));
            }
            lightOwners.put(owner, next);
        });
    }

    private TerrainEditorGeometry withLight(TerrainEditorGeometry template, String owner) {
        var light = steadyLight.get(owner);
        return light == null ? template : template.withSteadyLight(light.rgb());
    }

    private void settle() {
        if (pending != null && pending.isDone()) {
            var failed = false;
            var beside = installing;
            installing = null;
            try {
                pending.join();
            } catch (CompletionException failure) {
                var message = failureMessage(failure.getCause());
                if (beside != null) {
                    beside.answer.complete(message);
                } else {
                    error = message;
                }
                failed = true;
            } finally {
                pending = null;
                controller.setOperationActive(false);
                var opened = openingView;
                openingView = null;
                if (opened != null) {
                    finishOpen(opened, failed, true);
                }
                if (beside != null) {
                    finishBeside(beside, failed);
                }
            }
        }
    }

    private void finishOpen(View view, boolean failed, boolean activate) {
        if (!failed && controller.findDocument(view.owner) != null) {
            views.put(view.owner, view);
            if (activate && controller.getStrokeAnchor() == null) {
                controller.activate(view.owner);
            }
            return;
        }
        view.closed = true;
        if (view.shown) {
            backend.clearEditorTerrain(view.owner);
            if (steadyLight.containsKey(view.owner)) {
                lightRequests.add(view.owner);
            }
        }
    }

    private boolean installBeside() {
        for (var iterator = besides.iterator(); iterator.hasNext(); ) {
            var beside = iterator.next();
            var reading = beside.reading;
            if (reading == null || !reading.isDone()) {
                continue;
            }
            iterator.remove();
            Prepared prepared;
            try {
                prepared = reading.join();
            } catch (CompletionException | CancellationException failure) {
                beside.answer.complete(failureMessage(failure.getCause() == null ? failure : failure.getCause()));
                continue;
            }
            if (beside.generation != generation) {
                beside.answer.complete("The Terrains closed.");
                continue;
            }
            if (controller.findDocument(beside.owner) != null) {
                beside.answer.complete("");
                continue;
            }
            installing = beside;
            var view = beside.view;
            var document = prepared.document();
            lightOwners.remove(beside.owner);
            lightRequests.remove(beside.owner);
            submit(() -> {
                controller.addPrepared(document);
                try {
                    var geometry = prepared.geometry();
                    view.geometry = geometry;
                    link(view, document, controller.getDocuments());
                    var seamIndex = view.seamIndex;
                    var sectors = new LinkedHashMap<>(prepared.sectors());
                    if (seamIndex != null) {
                        sectors.putAll(
                                build(view, geometry, document.getHistory().getSamples(), seamIndex.getAll()));
                    }
                    publish(view, Map.copyOf(sectors), true);
                } catch (RuntimeException | Error failure) {
                    controller.closeDocument(document.getOwner());
                    view.geometry = null;
                    throw failure;
                }
            });
            return true;
        }
        return false;
    }

    private void finishBeside(Beside beside, boolean failed) {
        var document = controller.findDocument(beside.owner);
        var view = beside.view;
        finishOpen(view, failed, false);
        if (failed || document == null) {
            return;
        }
        beside.answer.complete("");
        view.lighting = CompletableFuture.runAsync(
                        () -> controller.prepareLighting(
                                document,
                                () -> closed
                                        || view.closed
                                        || Thread.currentThread().isInterrupted()),
                        lightingWorker)
                .exceptionally(ignored -> null);
    }

    private void dropBesides(String why) {
        generation++;
        for (var beside : besides) {
            beside.answer.complete(why);
            if (beside.reading != null) {
                beside.reading.cancel(false);
            }
        }
        besides.clear();
    }

    private TerrainMappingHistory requireMapping(TerrainDocument document) {
        var layers = document.getMapping();
        if (layers == null) {
            throw new IllegalStateException("The layer mappings of this Terrain cannot be changed");
        }
        if (controller.isStrokeActive()) {
            throw new IllegalStateException("Finish the stroke first");
        }
        return layers;
    }

    private void dropViews() {
        for (var view : views.values()) {
            forget(view);
        }
        views.clear();
        status = "";
    }

    private void forget(View view) {
        view.closed = true;
        if (view.shown) {
            backend.clearEditorTerrain(view.owner);
            view.shown = false;
            if (steadyLight.containsKey(view.owner)) {
                lightRequests.add(view.owner);
            }
        }
    }

    private void publishPreview(View view, Revision revision) {
        var template = view.geometry;
        if (template == null) {
            throw new IllegalStateException("Terrain geometry is not loaded");
        }
        var samples = revision.getSamples();
        var edge = changedEdge(view.visibleSamples, samples);
        var document = controller.findDocument(view.owner);
        var inPlace = document != null && document.getHistory().isStrokeActive();
        var waiting = Set.copyOf(view.pendingSeams);
        view.pendingSeams.clear();
        var sectors = new LinkedHashSet<>(revision.getAffectedSectors());
        sectors.addAll(waiting);
        if (inPlace) {
            view.strokeSectors.addAll(sectors);
        }
        try {
            publish(new Publication(
                    view,
                    template.getOwner(),
                    build(view, template, samples, sectors),
                    false,
                    inPlace,
                    List.of(),
                    samples,
                    null,
                    false));
        } catch (RuntimeException | Error failure) {
            view.pendingSeams.addAll(waiting);
            throw failure;
        }
        if (edge.length > 0) {
            noteSeams(view, edge);
        }
        if (!inPlace) {
            scatterDecorations(view, null, true);
        }
    }

    private void replaceStrokeSectors() {
        for (var view : views.values()) {
            replaceStrokeSectors(view);
        }
    }

    private void replaceStrokeSectors(View view) {
        var template = view.geometry;
        var document = controller.findDocument(view.owner);
        if (template == null || document == null || view.strokeSectors.isEmpty()) {
            view.strokeSectors.clear();
            return;
        }
        var touched = Set.copyOf(view.strokeSectors);
        view.strokeSectors.clear();
        var samples = document.getHistory().getSamples();
        publish(new Publication(
                view,
                template.getOwner(),
                build(view, template, samples, touched),
                false,
                false,
                List.of(),
                samples,
                null,
                false));
        scatterDecorations(view, null, true);
    }

    private void relink() {
        var open = controller.getDocuments();
        if (open == linkedDocuments) {
            return;
        }
        linkedDocuments = open;
        for (var view : views.values()) {
            var document = controller.findDocument(view.owner);
            if (document == null) {
                continue;
            }
            var before = owners(view.neighbors);
            link(view, document, open);
            var index = view.seamIndex;
            if (index != null && !before.equals(owners(view.neighbors))) {
                view.pendingSeams.addAll(index.getAll());
            }
        }
    }

    private void noteSeams(View changed, int[] edge) {
        for (var view : views.values()) {
            var index = view.seamIndex;
            if (view == changed || index == null) {
                continue;
            }
            for (var neighbor : view.neighbors) {
                if (!neighbor.document().getOwner().equals(changed.owner)) {
                    continue;
                }
                var link = neighbor.link();
                for (var packed : edge) {
                    view.pendingSeams.addAll(
                            index.at(packed % 256 + link.getOffsetX(), packed / 256 + link.getOffsetY()));
                }
            }
        }
    }

    private void flushSeams() {
        for (var view : views.values()) {
            if (view.pendingSeams.isEmpty()) {
                continue;
            }
            var sectors = Set.copyOf(view.pendingSeams);
            view.pendingSeams.clear();
            var template = view.geometry;
            var document = controller.findDocument(view.owner);
            if (template == null || document == null) {
                continue;
            }
            var inPlace = document.getHistory().isStrokeActive();
            if (inPlace) {
                view.strokeSectors.addAll(sectors);
            }
            var samples = document.getHistory().getSamples();
            publish(new Publication(
                    view,
                    view.owner,
                    build(view, template, samples, sectors),
                    false,
                    inPlace,
                    List.of(),
                    samples,
                    null,
                    false));
        }
    }

    private void publishQuads(View view, BitSet visible, BitSet turns, Set<SectorCoordinate> sectors) {
        var template = view.geometry;
        var document = controller.findDocument(view.owner);
        if (template == null || document == null) {
            throw new IllegalStateException("Terrain geometry is not loaded");
        }
        var next = template.withQuads(visible, turns, sectors);
        var samples = document.getHistory().getSamples();
        var built = new LinkedHashMap<>(build(view, next, samples, sectors));
        for (var sector : sectors) {
            built.putIfAbsent(TerrainEditorGeometry.sectorActor(next.getOwner(), sector.x(), sector.y()), List.of());
        }
        publish(new Publication(
                view, next.getOwner(), Map.copyOf(built), false, false, List.of(), samples, null, false));
        view.geometry = next;
        scatterDecorations(view, null, !document.isStrokeActive());
    }

    private void publishMapping(View view, int layer, TerrainLayerMapping mapping) {
        var template = view.geometry;
        var document = controller.findDocument(view.owner);
        if (template == null || document == null) {
            throw new IllegalStateException("Terrain geometry is not loaded");
        }
        var next = template.withLayerMapping(layer, mapping);
        var samples = document.getHistory().getSamples();
        publish(new Publication(
                view,
                next.getOwner(),
                build(view, next, samples, next.getSectorCoordinates()),
                false,
                false,
                List.of(),
                samples,
                null,
                false));
        view.geometry = next;
    }

    private void beginDecorations(View view, TerrainDocument document, List<LayerSource> layers) {
        var sources = new ArrayList<Source>();
        for (var template : view.decorations) {
            sources.add(new Source(template.getLayerIndex(), template.getMeshName(), template.getDensityMap()));
        }
        document.beginDecorations(
                controller.getClientRoot(), sources, layers, changes -> publishDecorations(view, changes));
    }

    private void publishDecorations(View view, List<Change> changes) {
        var slots = new LinkedHashSet<Integer>();
        for (var change : changes) {
            slots.add(change.layer());
        }
        var document = controller.findDocument(view.owner);
        var density = document == null ? null : document.getDecorations();
        scatterDecorations(view, slots, density == null || !density.isStrokeActive());
    }

    private void scatterDecorations(View view, @Nullable Set<Integer> slots, boolean now) {
        if (view.decorations.isEmpty()) {
            return;
        }
        var document = controller.findDocument(view.owner);
        var sources = document == null ? null : document.getDecorationSources();
        for (var template : view.decorations) {
            var slot = sources == null ? -1 : slotOf(sources, template.getLayerIndex());
            if (slots == null || slots.contains(slot)) {
                view.pendingDecorations.add(template.getLayerIndex());
            }
        }
        if (now || System.nanoTime() - view.decorationsShownAt >= DECORATION_INTERVAL) {
            flushDecorations(view);
        }
    }

    private void flushDecorations(View view) {
        if (view.pendingDecorations.isEmpty()) {
            return;
        }
        var document = controller.findDocument(view.owner);
        if (document == null) {
            view.pendingDecorations.clear();
            return;
        }
        var density = document.getDecorations();
        var sources = document.getDecorationSources();
        var quads = document.getQuads();
        var info = document.getInfo();
        var replacements = new LinkedHashMap<String, List<Entity>>();
        var streamed = new HashMap<Integer, DecorationDensity>();
        DecorationGround ground = null;
        for (var template : view.decorations) {
            if (!view.pendingDecorations.contains(template.getLayerIndex())) {
                continue;
            }
            if (ground == null) {
                var saved = template.getSavedGround();
                ground = new DecorationGround(
                        info.width(),
                        info.height(),
                        document.getHistory().getSamples().clone(),
                        (BitSet) (quads == null ? saved.visible() : quads.getVisible()).clone(),
                        (BitSet) (quads == null ? saved.turned() : quads.getTurns()).clone());
            }
            var slot = sources == null ? -1 : slotOf(sources, template.getLayerIndex());
            var weights = density == null || slot < 0 ? null : density.getWeights(slot);
            var map = weights == null
                    ? template.getSavedDensity()
                    : new DecorationDensity(
                            density.getLayers().get(slot).width(),
                            density.getLayers().get(slot).height(),
                            weights.clone());
            if (backend.streamsDecorations(view.owner, template.getLayerIndex())) {
                streamed.put(template.getLayerIndex(), map);
            } else {
                var entity = template.build(map, ground);
                replacements.put(template.getActor(), entity == null ? List.of() : List.of(entity));
            }
        }
        view.pendingDecorations.clear();
        view.decorationsShownAt = System.nanoTime();
        if (replacements.isEmpty() && streamed.isEmpty()) {
            return;
        }
        view.decorationsReplaced = true;
        publish(new Publication(
                view,
                view.owner,
                Map.of(),
                false,
                true,
                List.of(),
                null,
                null,
                false,
                Map.copyOf(replacements),
                streamed.isEmpty() ? null : new DecorationSources(Map.copyOf(streamed), ground)));
    }

    private void restoreDecorations(Collection<View> closing) {
        var replacements = new LinkedHashMap<String, List<Entity>>();
        for (var view : closing) {
            if (!view.decorationsReplaced || !view.resident) {
                continue;
            }
            view.decorationsReplaced = false;
            var restored = false;
            for (var template : view.decorations) {
                if (backend.streamsDecorations(view.owner, template.getLayerIndex())) {
                    if (!restored) {
                        backend.restoreDecorationSources(view.owner);
                        restored = true;
                    }
                    continue;
                }
                var entity = template.build(template.getSavedDensity(), template.getSavedGround());
                replacements.put(template.getActor(), entity == null ? List.of() : List.of(entity));
            }
        }
        if (!replacements.isEmpty()) {
            backend.replaceEditorDecorations(Map.copyOf(replacements));
        }
    }

    private void publishPaint(View view, List<Change> changes) {
        var template = view.geometry;
        if (template == null) {
            throw new IllegalStateException("Terrain geometry is not loaded");
        }
        var atlases = template.getAlphaAtlases();
        var document = controller.findDocument(view.owner);
        var paint = document == null ? null : document.getPaint();
        if (paint == null) {
            return;
        }
        var updates = new ArrayList<AtlasUpdate>();
        for (var change : changes) {
            var group = change.layer() / 4;
            if (group >= atlases.size()) {
                continue;
            }
            var atlas = atlases.get(group);
            var layer = paint.getLayers().get(change.layer());
            var width = layer.width();
            var height = layer.height();
            if (width < 1 || height < 1) {
                continue;
            }
            var x0 = (int) ((long) change.x0() * atlas.width() / width);
            var y0 = (int) ((long) change.y0() * atlas.height() / height);
            var x1 = (int) Math.min(
                    atlas.width() - 1, ((long) (change.x1() + 1) * atlas.width() + width - 1) / width - 1);
            var y1 = (int) Math.min(
                    atlas.height() - 1, ((long) (change.y1() + 1) * atlas.height() + height - 1) / height - 1);
            var values = new byte[(x1 - x0 + 1) * (y1 - y0 + 1)];
            var channel = change.layer() % 4;
            for (var y = y0; y <= y1; y++) {
                var sourceY = Math.min(height - 1, (int) ((long) y * height / atlas.height()));
                for (var x = x0; x <= x1; x++) {
                    var sourceX = Math.min(width - 1, (int) ((long) x * width / atlas.width()));
                    var at = (y - y0) * (x1 - x0 + 1) + x - x0;
                    if (sourceX >= change.x0()
                            && sourceX <= change.x1()
                            && sourceY >= change.y0()
                            && sourceY <= change.y1()) {
                        values[at] = change.weights()[(sourceY - change.y0()) * change.width() + sourceX - change.x0()];
                    } else {
                        values[at] = atlas.rgba()[(y * atlas.width() + x) * 4 + channel];
                    }
                }
            }
            updates.add(new AtlasUpdate(atlas, channel, x0, y0, x1, y1, values));
        }
        if (!updates.isEmpty()) {
            publish(new Publication(
                    view, template.getOwner(), Map.of(), false, true, List.copyOf(updates), null, null, false));
        }
    }

    private void publish(View view, Map<String, List<Entity>> sectors, boolean initialize) {
        var document = controller.findDocument(view.owner);
        publish(new Publication(
                view,
                view.owner,
                sectors,
                initialize,
                false,
                List.of(),
                document == null ? null : document.getHistory().getSamples(),
                null,
                false));
    }

    private void show(Publication ready) {
        var view = ready.view();
        if (ready.light() || view == null) {
            backend.replaceEditorTerrain(ready.owner(), ready.sectors(), ready.initialize());
            return;
        }
        if (view.resident) {
            if (ready.initialize() || !ready.sectors().isEmpty()) {
                if (ready.initialize()
                        || !ready.inPlace()
                        || !backend.updateEditorTerrain(ready.owner(), ready.sectors())) {
                    backend.replaceEditorTerrain(ready.owner(), ready.sectors(), ready.initialize());
                }
                view.shown = true;
            }
        }
        for (var update : ready.atlases()) {
            var atlas = update.atlas();
            var width = update.x1() - update.x0() + 1;
            for (var y = update.y0(); y <= update.y1(); y++) {
                for (var x = update.x0(); x <= update.x1(); x++) {
                    atlas.rgba()[(y * atlas.width() + x) * 4 + update.channel()] =
                            update.values()[(y - update.y0()) * width + x - update.x0()];
                }
            }
            if (view.resident) {
                backend.updateEditorTerrainAlpha(atlas, update.x0(), update.y0(), width, update.y1() - update.y0() + 1);
            }
        }
        if (ready.samples() != null) {
            view.visibleSamples = ready.samples();
        }
        if (view.resident && !ready.decorations().isEmpty()) {
            backend.replaceEditorDecorations(ready.decorations());
        }
        var decorationSources = ready.decorationSources();
        if (view.resident && decorationSources != null) {
            backend.showDecorationSources(ready.owner(), decorationSources.densities(), decorationSources.ground());
        }
    }

    private void publish(Publication change) {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Terrain operation cancelled");
        }
        if (Thread.currentThread() == ownerThread) {
            show(change);
            return;
        }
        var acknowledgement = new CompletableFuture<Void>();
        var next = new Publication(
                change.view(),
                change.owner(),
                change.sectors(),
                change.initialize(),
                change.inPlace(),
                change.atlases(),
                change.samples(),
                acknowledgement,
                change.light(),
                change.decorations(),
                change.decorationSources());
        if (!publication.compareAndSet(null, next)) {
            throw new IllegalStateException("Terrain publication already pending");
        }
        if (closed && publication.compareAndSet(next, null)) {
            acknowledgement.completeExceptionally(new IllegalStateException("Terrain session closed"));
        }
        var start = System.nanoTime();
        publications++;
        try {
            acknowledgement.get();
            waitedNanos += System.nanoTime() - start;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Terrain operation cancelled", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error fatal) {
                throw fatal;
            }
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(failure.getCause());
        }
    }

    private void submit(Operation operation) {
        requireOwner();
        requireIdle();
        if (closed) {
            throw new IllegalStateException("Terrain session is closed");
        }
        relink();
        error = "";
        controller.setOperationActive(true);
        pending = CompletableFuture.runAsync(
                () -> {
                    var start = System.nanoTime();
                    waitedNanos = 0;
                    publications = 0;
                    try {
                        operation.run();
                        flushSeams();
                        controller.cacheCommittedRecovery();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    } finally {
                        lastTiming = new Timing(System.nanoTime() - start - waitedNanos, publications);
                    }
                },
                worker);
    }

    private Target target(@Nullable String owner) {
        requireOwner();
        TerrainDocument document;
        if (owner != null) {
            document = controller.findDocument(owner);
            if (document == null) {
                throw new IllegalStateException("The Terrain " + owner + " is not open");
            }
        } else {
            var stroke = controller.getStrokeAnchor();
            document = stroke != null ? stroke : controller.getActive();
            if (document == null) {
                throw new IllegalStateException("No supported Terrain owner is open");
            }
        }
        var view = views.get(document.getOwner());
        if (view == null) {
            throw new IllegalStateException("The Terrain " + document.getOwner() + " is still opening");
        }
        return new Target(document, view);
    }

    private Target strokeTarget(@Nullable String owner) {
        var target = target(owner);
        var anchor = controller.getStrokeAnchor();
        if (anchor != null && anchor != target.document()) {
            throw new IllegalStateException("Finish the stroke on " + anchor.getMap() + " first");
        }
        return target;
    }

    private List<Target> targets() {
        requireOwner();
        var result = new ArrayList<Target>();
        for (var document : controller.getDocuments()) {
            var view = views.get(document.getOwner());
            if (view != null) {
                result.add(new Target(document, view));
            }
        }
        return result;
    }

    private List<View> allViews() {
        var result = new ArrayList<>(views.values());
        if (openingView != null) {
            result.add(openingView);
        }
        return result;
    }

    private boolean isLoaded(String owner) {
        return backend.getDebugSceneObjects().stream()
                .anyMatch(object -> owner.equals(object.actorName())
                        || object.actorName() != null && object.actorName().startsWith(owner + "$terrain$"));
    }

    private String whyNotLoaded(String owner) {
        var map = EditingSession.mapOfPath(owner);
        var mapShown = backend.getDebugSceneObjects().stream()
                .anyMatch(object -> object.owner() != null && map.equals(mapFiles.getSource(object.owner())));
        return mapShown
                ? "The viewport draws nothing of this Terrain, so it has no ground to edit"
                : "Load this Terrain map in the viewport before opening its brush";
    }

    private void requireIdle() {
        controller.requireNotExporting();
        if (isBusy()) {
            throw new IllegalStateException("Wait for the pending Terrain operation");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Terrain UI operation requires the render thread");
        }
    }

    private static TerrainQuadHistory requireQuads(TerrainDocument document) {
        var bits = document.getQuads();
        if (bits == null) {
            throw new IllegalStateException("The quads of this Terrain cannot be changed: " + document.getQuadsError());
        }
        return bits;
    }

    private static Set<String> owners(List<Neighbor> neighbors) {
        var owners = new LinkedHashSet<String>();
        for (var neighbor : neighbors) {
            owners.add(neighbor.document().getOwner());
        }
        return owners;
    }

    private static void link(View view, TerrainDocument document, List<TerrainDocument> open) {
        var neighbors = new ArrayList<Neighbor>();
        var seams = new ArrayList<SeamHeights>();
        var outside = new ArrayList<OutsideHeights>();
        for (var other : open) {
            if (other == document) {
                continue;
            }
            var link = TerrainTileLink.of(document.getInfo(), other.getInfo());
            if (!link.fits()) {
                continue;
            }
            var history = other.getHistory();
            neighbors.add(new Neighbor(other, link));
            seams.add(link.createSeamHeights(history::getHeightAt));
            outside.add(link.createOutsideHeights(history::getHeightAt));
        }
        view.neighbors = List.copyOf(neighbors);
        view.seams = SeamHeights.firstKnown(seams);
        document.getHistory().setOutsideHeights(outside.isEmpty() ? null : OutsideHeights.firstKnown(outside));
    }

    private static Map<String, List<Entity>> build(
            View view, TerrainEditorGeometry template, short[] samples, Set<SectorCoordinate> sectors) {
        return template.build(samples, sectors, view.seams);
    }

    private static int[] changedEdge(short @Nullable [] before, short[] after) {
        if (before == null || before.length != after.length || after.length != 65536) {
            return new int[0];
        }
        var changed = new int[511];
        var count = 0;
        for (var x = 0; x < 256; x++) {
            if (before[x] != after[x]) {
                changed[count++] = x;
            }
        }
        for (var y = 1; y < 256; y++) {
            if (before[y * 256] != after[y * 256]) {
                changed[count++] = y * 256;
            }
        }
        return Arrays.copyOf(changed, count);
    }

    private static String failureMessage(Throwable failure) {
        return UserMessages.plain(failure, "The Terrain work stopped. The log names the cause.");
    }

    private static int slotOf(TerrainDecorationSources.Opened sources, int layerIndex) {
        for (var decoration : sources.decorations()) {
            if (decoration.index() == layerIndex) {
                return decoration.slot();
            }
        }
        return -1;
    }
}
