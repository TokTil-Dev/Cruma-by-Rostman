package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainDecorationSources;
import com.rost.cruma.core.editing.TerrainDecorationSources.Opened;
import com.rost.cruma.core.editing.TerrainDecorationSources.Source;
import com.rost.cruma.core.editing.TerrainEditExport;
import com.rost.cruma.core.editing.TerrainEditHistory;
import com.rost.cruma.core.editing.TerrainEditorGeometry.LayerSource;
import com.rost.cruma.core.editing.TerrainHeightConsumers;
import com.rost.cruma.core.editing.TerrainLightingFidelity;
import com.rost.cruma.core.editing.TerrainLightingScene;
import com.rost.cruma.core.editing.TerrainMappingHistory;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainPackageSnapshot;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.HeightSource;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.editing.TerrainPaintHistory;
import com.rost.cruma.core.editing.TerrainPaintSources;
import com.rost.cruma.core.editing.TerrainQuadHistory;
import com.rost.cruma.core.editing.TerrainTextureUsers;
import com.rost.cruma.viewer.editing.TerrainEditingController.Lighting;
import com.rost.cruma.viewer.editing.TerrainEditingController.Step;
import com.rost.cruma.viewer.editing.TerrainRecoveryCodec.State;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class TerrainDocument {
    private final TerrainPackageSnapshot snapshot;
    private final Terrain terrain;
    private final TerrainEditHistory history;
    private volatile @Nullable TerrainPaintHistory paint;
    private volatile String paintError = "";
    private volatile @Nullable TerrainMappingHistory mapping;
    private volatile String mappingError = "";
    private volatile @Nullable TerrainQuadHistory quads;
    private volatile String quadsError = "";
    private volatile @Nullable TerrainPaintHistory decorations;
    private volatile @Nullable Opened decorationSources;
    private volatile String decorationError = "";
    private volatile @Nullable TerrainLightingScene scene;
    private volatile @Nullable Lighting lighting;

    TerrainDocument(TerrainPackageSnapshot snapshot, Terrain terrain, TerrainEditHistory history) {
        this.snapshot = snapshot;
        this.terrain = terrain;
        this.history = history;
    }

    public String getOwner() {
        return terrain.path();
    }

    public String getMap() {
        return EditingSession.mapOfPath(getOwner());
    }

    public Path getSourcePath() {
        return snapshot.getSourcePath();
    }

    public String getSourceFingerprint() {
        return snapshot.getOriginalSha256();
    }

    public Terrain getTerrain() {
        return terrain;
    }

    public Info getInfo() {
        return terrain.info();
    }

    public TerrainEditHistory getHistory() {
        return history;
    }

    public @Nullable TerrainPaintHistory getPaint() {
        return paint;
    }

    public String getPaintError() {
        return paintError;
    }

    public @Nullable TerrainMappingHistory getMapping() {
        return mapping;
    }

    public String getMappingError() {
        return mappingError;
    }

    public @Nullable TerrainQuadHistory getQuads() {
        return quads;
    }

    public String getQuadsError() {
        return quadsError;
    }

    public @Nullable TerrainPaintHistory getDecorations() {
        return decorations;
    }

    public @Nullable Opened getDecorationSources() {
        return decorationSources;
    }

    public String getDecorationError() {
        return decorationError;
    }

    public @Nullable TerrainLightingScene getScene() {
        return scene;
    }

    public @Nullable Lighting getLighting() {
        return lighting;
    }

    public String getHeightPackage() {
        return getHeightSource()
                .map(source -> source.path().getFileName().toString())
                .orElse("");
    }

    public boolean isDirty() {
        var weights = paint;
        var layers = mapping;
        var bits = quads;
        var density = decorations;
        return history.isDirty()
                || weights != null && weights.isDirty()
                || layers != null && layers.isDirty()
                || bits != null && bits.isDirty()
                || density != null && density.isDirty();
    }

    public boolean hasSourceChanges() {
        var weights = paint;
        var layers = mapping;
        var bits = quads;
        var density = decorations;
        return history.getSourceChanges().size() != 0
                || weights != null && weights.hasSourceChanges()
                || layers != null && layers.hasSourceChanges()
                || bits != null && bits.hasSourceChanges()
                || density != null && density.hasSourceChanges();
    }

    public boolean hasTerrainChanges() {
        var weights = paint;
        var layers = mapping;
        var bits = quads;
        return history.getSourceChanges().size() != 0
                || weights != null && weights.hasSourceChanges()
                || layers != null && layers.hasSourceChanges()
                || bits != null && bits.hasSourceChanges();
    }

    public boolean isStrokeActive() {
        var weights = paint;
        var bits = quads;
        var density = decorations;
        return history.isStrokeActive()
                || weights != null && weights.isStrokeActive()
                || bits != null && bits.isStrokeActive()
                || density != null && density.isStrokeActive();
    }

    TerrainPackageSnapshot getSnapshot() {
        return snapshot;
    }

    Optional<HeightSource> getHeightSource() {
        return snapshot.findHeightSource(getOwner());
    }

    long getRevision() {
        var weights = paint;
        var layers = mapping;
        var bits = quads;
        var density = decorations;
        return history.getRecoveryRevision()
                + (weights == null ? 0 : weights.getRevision())
                + (layers == null ? 0 : layers.getRevision())
                + (bits == null ? 0 : bits.getRevision())
                + (density == null ? 0 : density.getRevision());
    }

    void beginPaint(List<LayerSource> layers, TerrainPaintHistory.Preview preview) {
        try {
            var opened = TerrainPaintSources.open(snapshot, getOwner(), layers);
            paint = new TerrainPaintHistory(256, 256, opened.layers(), opened.weights(), preview);
            paintError = "";
        } catch (IOException | RuntimeException failure) {
            paint = null;
            paintError = message(failure);
        }
    }

    void beginDecorations(
            Path clientRoot, List<Source> sources, List<LayerSource> layers, TerrainPaintHistory.Preview preview) {
        try {
            var opened = TerrainDecorationSources.open(
                    snapshot, getOwner(), sources, layers, TerrainTextureUsers.of(clientRoot));
            decorations = new TerrainPaintHistory(256, 256, opened.slots(), opened.weights(), preview);
            decorationSources = opened;
            decorationError = "";
        } catch (IOException | RuntimeException failure) {
            decorations = null;
            decorationSources = null;
            decorationError = message(failure);
        }
    }

    void beginMapping(TerrainMappingHistory.Preview preview) {
        try {
            mapping = new TerrainMappingHistory(snapshot.getLayerFields(getOwner()), preview);
            mappingError = "";
        } catch (IOException | RuntimeException failure) {
            mapping = null;
            mappingError = message(failure);
        }
    }

    void beginQuads(TerrainQuadHistory.Preview preview) {
        try {
            var info = terrain.info();
            var bitmaps = snapshot.getQuadBitmaps(getOwner());
            quads = new TerrainQuadHistory(info.width(), info.height(), bitmaps[0], bitmaps[1], preview);
            quadsError = "";
        } catch (IOException | RuntimeException failure) {
            quads = null;
            quadsError = message(failure);
        }
    }

    void prepareLighting(Path clientRoot, BooleanSupplier cancelled) {
        try {
            var captured = TerrainLightingScene.capture(clientRoot, snapshot, getOwner(), cancelled);
            var shared = terrain.heightmapReference() > 0
                    ? List.<String>of()
                    : TerrainHeightConsumers.scan(
                            clientRoot, terrain.heightmapObjectPath(), snapshot.getSourcePath(), cancelled);
            var sample = new ArrayList<Integer>();
            for (var i = 0; i < terrain.sectors().size(); i += 17) {
                sample.add(i);
            }
            var fidelity = TerrainLightingFidelity.measure(captured, terrain, sample, cancelled);
            scene = captured;
            lighting = new Lighting(captured.getSummary(), fidelity, shared, "");
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof CancellationException cancellation) {
                throw cancellation;
            }
            scene = null;
            lighting = new Lighting(null, null, List.of(), message(failure));
        }
    }

    boolean canUndo(Step step) {
        return switch (step) {
            case HEIGHTS -> history.canUndo();
            case WEIGHTS -> paint != null && paint.canUndo();
            case MAPPING -> mapping != null && mapping.canUndo();
            case QUADS -> quads != null && quads.canUndo();
            case DECORATIONS -> decorations != null && decorations.canUndo();
        };
    }

    boolean canRedo(Step step) {
        return switch (step) {
            case HEIGHTS -> history.canRedo();
            case WEIGHTS -> paint != null && paint.canRedo();
            case MAPPING -> mapping != null && mapping.canRedo();
            case QUADS -> quads != null && quads.canRedo();
            case DECORATIONS -> decorations != null && decorations.canRedo();
        };
    }

    void undo(Step step) {
        switch (step) {
            case HEIGHTS -> history.undo();
            case WEIGHTS -> paint.undo();
            case MAPPING -> mapping.undo();
            case QUADS -> quads.undo();
            case DECORATIONS -> decorations.undo();
        }
    }

    void redo(Step step) {
        switch (step) {
            case HEIGHTS -> history.redo();
            case WEIGHTS -> paint.redo();
            case MAPPING -> mapping.redo();
            case QUADS -> quads.redo();
            case DECORATIONS -> decorations.redo();
        }
    }

    void clearRedo() {
        history.clearRedo();
        var weights = paint;
        if (weights != null) {
            weights.clearRedo();
        }
        var layers = mapping;
        if (layers != null) {
            layers.clearRedo();
        }
        var bits = quads;
        if (bits != null) {
            bits.clearRedo();
        }
        var density = decorations;
        if (density != null) {
            density.clearRedo();
        }
    }

    State createRecoveryState(Path clientRoot) {
        var height = getHeightSource();
        return new State(
                portable(clientRoot.relativize(snapshot.getSourcePath())),
                getOwner(),
                snapshot.getOriginalSha256(),
                history.getCheckpoint(),
                TerrainEditExport.PROFILE_ID,
                height.map(source -> portable(clientRoot.relativize(source.path())))
                        .orElse(""),
                height.map(HeightSource::sha256).orElse(""));
    }

    private static String portable(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
