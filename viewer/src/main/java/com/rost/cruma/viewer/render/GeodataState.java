package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.config.Config.Viewer.World.Geodata;
import com.rost.cruma.viewer.geodata.GeoClientSurfaceCache;
import com.rost.cruma.viewer.geodata.GeoDataDisplay;
import com.rost.cruma.viewer.geodata.GeoDataFocusMode;
import com.rost.cruma.viewer.geodata.GeoDataMesh;
import com.rost.cruma.viewer.geodata.GeoEditingSession;
import com.rost.cruma.viewer.geodata.GeoInspection;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.geodata.GeoPinnedData;
import com.rost.cruma.viewer.geodata.GeoSurfaceFilter;
import java.nio.file.Path;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class GeodataState {
    public final GeoClientSurfaceCache clientSurfaces = new GeoClientSurfaceCache();

    public LoadedTiles tiles = new LoadedTiles();

    public Path path;
    public boolean enabled;
    public boolean toolsOpen;
    public boolean loading;
    public GeoDataFocusMode focusMode = GeoDataFocusMode.MOUSE;
    public int vertexLimit = GeoDataMesh.DEFAULT_VERTEX_LIMIT;

    public final GeoOverlayLines lines = new GeoOverlayLines();

    public boolean displayLimitReached;

    public int displayLayers;

    public Selection hovered;

    public float focusNdcX;
    public float focusNdcY;
    public GeoPoint viewFocus;
    public boolean viewFocusOnLayer;
    public int radius;
    public boolean throughGeometry;
    public GeoDataDisplay display;
    public GeoSurfaceFilter surfaceFilter;
    public Selection surfaceSeed;
    public String surfaceStatus = "";
    public String status = "";
    public GeoDataSnapshot data;
    public GeoEditingSession editing;
    public boolean sourceChangePending;
    public Path pendingSourcePath;
    public final GeoInspection inspection = new GeoInspection();

    private GeoDataSnapshot comparisonDisplay;
    private GeoCellRef comparisonAgainst;
    private GeoDataAccess preview;
    private final GeoPinnedData pinnedData = new GeoPinnedData();
    private boolean reloadPending;
    private long reloadRevision;
    private long missingRevision;

    public String getToolsUnavailableReason() {
        if (path == null) {
            return "Choose a geodata folder in the Geodata panel.";
        }
        var source = getSourceSnapshot();
        var current = source != null && path.toAbsolutePath().normalize().equals(source.getSourcePath());
        if (current && source.getTiles().values().stream().anyMatch(tile -> tile.region() != null)) {
            return "";
        }
        if (loading) {
            return "Geodata is loading. Tools will be available when a region is ready.";
        }
        if (!enabled && !toolsOpen) {
            return "Open the Geodata panel or enable Show geodata to load the selected folder.";
        }
        return "No geodata region is loaded. "
                + (current && !status.isBlank() ? status + "\n" : "")
                + "Check the folder and move the camera over a region it contains.";
    }

    public GeoDataSnapshot getSourceSnapshot() {
        return editing == null ? retainedSource() : editing.getDocument().getBase();
    }

    public GeoDataAccess queryData() {
        if (editing == null) {
            preview = null;
            return retainedSource();
        }
        if (preview == null || preview.revision() != editing.getDocument().revision()) {
            preview = editing.preview();
        }
        if (surfaceSeed != null && !surfaceSeed.ref().belongsTo(preview)) {
            surfaceSeed = null;
            surfaceFilter = null;
            surfaceStatus = "Edited data changed. Select the surface layer again.";
        }
        return preview;
    }

    public void startEditing(GeoDataSnapshot source) {
        var selected = inspection.selected;
        var pointA = inspection.pointA;
        var pointB = inspection.pointB;
        editing = new GeoEditingSession(source);
        var edited = queryData();
        inspection.selected = reselect(edited, selected);
        inspection.pointA = reselect(edited, pointA);
        inspection.pointB = reselect(edited, pointB);
    }

    public void followEdit(@Nullable Selection selected) {
        var edited = queryData();
        inspection.update(edited);
        if (selected != null) {
            var layers = edited.cellAt(selected.ref().x(), selected.ref().y()).layers();
            if (!layers.isEmpty()) {
                inspection.select(
                        edited,
                        selected.ref().x(),
                        selected.ref().y(),
                        Math.min(selected.ref().layer(), layers.size() - 1));
            }
        }
    }

    public void guardSourceChange() {
        if (editing == null) {
            return;
        }
        var source = editing.getDocument().getBase().getSourcePath();
        var requested = path == null ? null : path.toAbsolutePath().normalize();
        if (Objects.equals(source, requested)) {
            return;
        }
        if (editing.hasUnsavedChanges()) {
            pendingSourcePath = path;
            sourceChangePending = true;
            reloadPending = false;
            path = source;
        } else {
            editing = null;
            preview = null;
        }
    }

    public void discardEditingAndChangeSource() {
        editing = null;
        preview = null;
        if (sourceChangePending) {
            path = pendingSourcePath;
        }
        sourceChangePending = false;
        pendingSourcePath = null;
        if (reloadPending) {
            reloadPending = false;
            reloadSource();
        }
    }

    public void keepEditingSource() {
        sourceChangePending = false;
        pendingSourcePath = null;
        reloadPending = false;
    }

    public void requestReload() {
        if (editing != null && editing.hasUnsavedChanges()) {
            pendingSourcePath = path;
            sourceChangePending = true;
            reloadPending = true;
        } else {
            reloadSource();
        }
    }

    public long getReloadRevision() {
        return reloadRevision;
    }

    public void refreshMissing() {
        missingRevision++;
    }

    public long getMissingRevision() {
        return missingRevision;
    }

    public void showComparison(GeoDataSnapshot sourceB) {
        comparisonDisplay = sourceB;
        var sourceA = queryData();
        comparisonAgainst = sourceA == null ? null : new GeoCellRef(sourceA.getSourceId(), sourceA.revision(), 0, 0, 0);
    }

    public GeoDataSnapshot getComparisonDisplay() {
        var sourceA = queryData();
        if (sourceA == null || comparisonAgainst == null || !comparisonAgainst.belongsTo(sourceA)) {
            comparisonDisplay = null;
        }
        return comparisonDisplay;
    }

    public void setDefaults(Geodata config) {
        path = config.path();
        enabled = config.enabled();
        radius = config.radius();
        throughGeometry = config.throughGeometry();
        display = config.display();
        focusMode = config.focusMode();
        vertexLimit = config.vertexLimit();
        status = "";
    }

    private void reloadSource() {
        editing = null;
        preview = null;
        data = null;
        surfaceFilter = null;
        surfaceSeed = null;
        surfaceStatus = "";
        pinnedData.clear();
        comparisonDisplay = null;
        comparisonAgainst = null;
        sourceChangePending = false;
        pendingSourcePath = null;
        reloadPending = false;
        inspection.selected = null;
        inspection.pointA = null;
        inspection.pointB = null;
        inspection.message = "Reloading geodata. Select cells again after loading.";
        status = "Reloading geodata...";
        loading = true;
        reloadRevision++;
    }

    private GeoDataSnapshot retainedSource() {
        var publication = pinnedData.update(data, inspection, surfaceSeed);
        surfaceSeed = publication.surfaceSeed();
        if (surfaceFilter != null && !surfaceFilter.belongsTo(publication.data())) {
            surfaceFilter = null;
            surfaceStatus = surfaceSeed == null
                    ? "The pinned surface source changed. Select its layer again."
                    : "Coverage changed. The pinned seed is unchanged; recalculate its connected surface.";
        } else if (!publication.message().isBlank()) {
            surfaceStatus = publication.message();
        }
        return publication.data();
    }

    private static @Nullable Selection reselect(GeoDataAccess edited, @Nullable Selection selection) {
        if (selection == null) {
            return null;
        }
        var cell = edited.cellAt(selection.ref().x(), selection.ref().y());
        for (var layer : cell.layers()) {
            if (layer.index() == selection.ref().layer()) {
                return new Selection(
                        new GeoCellRef(edited.getSourceId(), edited.revision(), cell.x(), cell.y(), layer.index()),
                        layer.height(),
                        layer.nswe());
            }
        }
        return null;
    }
}
