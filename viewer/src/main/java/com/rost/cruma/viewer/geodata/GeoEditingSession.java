package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoEditDocument;
import com.rost.cruma.core.geodata.GeoEditDocument.CellEdit;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoTile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class GeoEditingSession {
    public static final int STEP_HEIGHT = 32;

    public static final int REGION_EDIT_LIMIT = 262144;

    private final GeoEditDocument document;
    private final int maxOperationCells;
    private Map<CellKey, List<Layer>> savedChanges = Map.of();
    private long checkedRevision = Long.MIN_VALUE;
    private boolean cachedUnsaved;
    private @Nullable String stepLabel;

    public GeoEditingSession(GeoDataSnapshot base) {
        this(base, 65536);
    }

    public GeoEditingSession(GeoDataSnapshot base, int maxOperationCells) {
        if (maxOperationCells < 1 || maxOperationCells > 262144) {
            throw new IllegalArgumentException("Invalid operation cell limit");
        }
        document = new GeoEditDocument(base);
        this.maxOperationCells = maxOperationCells;
    }

    public record LayerEdit(Integer height, Integer nswe) {
        public LayerEdit {
            if (height == null && nswe == null) {
                throw new IllegalArgumentException("Choose height, direction flags, or both");
            }
            if (height != null && (height < -16384 || height > 16376 || Math.floorMod(height, 8) != 0)) {
                throw new IllegalArgumentException("Height must be -16384..16376 in steps of eight");
            }
            if (nswe != null && (nswe < 0 || nswe > 15)) {
                throw new IllegalArgumentException("Direction flags must be 0..15");
            }
        }
    }

    public record EditResult(int visitedCells, int changedCells, int skippedCells, long revision) {}

    public record Patch(int version, Map<GeoTile, String> sourceFingerprints, List<CellEdit> cells) {
        public static final int VERSION = 1;

        public Patch {
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported geodata patch version");
            }
            sourceFingerprints = Map.copyOf(sourceFingerprints);
            cells = List.copyOf(cells);
            if (cells.size() > 262144 || sourceFingerprints.size() > 340) {
                throw new IllegalArgumentException("Patch exceeds cell or source limits");
            }
            for (var entry : sourceFingerprints.entrySet()) {
                if (!entry.getKey().containsWorld() || !entry.getValue().matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid source content fingerprint");
                }
            }
            var seen = new LinkedHashSet<CellKey>();
            var layers = 0L;
            for (var cell : cells) {
                if (!seen.add(new CellKey(cell.x(), cell.y()))) {
                    throw new IllegalArgumentException("Duplicate cell in patch");
                }
                if (!sourceFingerprints.containsKey(GeoTile.fromWorld(cell.x(), cell.y()))) {
                    throw new IllegalArgumentException("Patch cell has no source fingerprint");
                }
                layers += cell.layers().size();
                if (layers > 2_000_000) {
                    throw new IllegalArgumentException("Patch exceeds layer limit");
                }
            }
        }
    }

    static double slopeHeight(HeightRule.Slope slope, double x, double y, boolean steps) {
        var dx = slope.to().x() - slope.from().x();
        var dy = slope.to().y() - slope.from().y();
        var length = dx * dx + dy * dy;
        var t = length == 0
                ? 0
                : Math.clamp(((x - slope.from().x()) * dx + (y - slope.from().y()) * dy) / length, 0, 1);
        var rise = slope.to().z() - slope.from().z();
        var height = slope.from().z() + t * rise;
        if (steps && slope.step() > 0) {
            height = slope.from().z() + Math.round((height - slope.from().z()) / slope.step()) * (double) slope.step();
        }
        return height;
    }

    public static Clip copy(GeoDataAccess data, Bounds bounds, int baseHeight) {
        var minX = Math.floorDiv(bounds.minX(), 16) * 16;
        var minY = Math.floorDiv(bounds.minY(), 16) * 16;
        var columns = Math.floorDiv(bounds.maxX() - 1 - minX, 16) + 1;
        var rows = Math.floorDiv(bounds.maxY() - 1 - minY, 16) + 1;
        if (bounds.maxX() <= bounds.minX() || bounds.maxY() <= bounds.minY()) {
            throw new IllegalArgumentException("Nothing to copy");
        }
        if ((long) columns * rows > 65536) {
            throw new IllegalArgumentException("Copy at most 65,536 cells at once");
        }
        var cells = new ArrayList<List<Layer>>(columns * rows);
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var cell = data.cellAt(minX + column * 16, minY + row * 16);
                requireAvailable(cell);
                var layers = new ArrayList<Layer>();
                for (var layer : cell.layers()) {
                    layers.add(new Layer(layer.index(), layer.height() - baseHeight, layer.nswe()));
                }
                cells.add(layers);
            }
        }
        return new Clip(columns, rows, cells);
    }

    public GeoEditDocument getDocument() {
        return document;
    }

    public GeoDataAccess preview() {
        return document.snapshot();
    }

    public boolean isModified() {
        return document.isModified();
    }

    public boolean hasUnsavedChanges() {
        synchronized (document) {
            var revision = document.revision();
            if (checkedRevision != revision) {
                cachedUnsaved = !document.getChangedCells().equals(savedChanges);
                checkedRevision = revision;
            }
            return cachedUnsaved;
        }
    }

    public void markSaved() {
        synchronized (document) {
            savedChanges = document.getChangedCells();
            checkedRevision = document.revision();
            cachedUnsaved = false;
        }
    }

    public boolean undo() {
        return document.undo();
    }

    public boolean redo() {
        return document.redo();
    }

    public GeoCellRef select(int worldX, int worldY, int layer) {
        var cell = document.cellAt(worldX, worldY);
        requireAvailable(cell);
        if (layer != -1 || !cell.layers().isEmpty()) {
            Objects.checkIndex(layer, cell.layers().size());
        }
        return new GeoCellRef(document.getSourceId(), document.revision(), cell.x(), cell.y(), layer);
    }

    public void editLayer(GeoCellRef selected, int height, int nswe) {
        if (stepLabel == null) {
            document.setLayer(selected, height, nswe);
        } else {
            document.setLayer(selected, height, nswe, stepLabel);
        }
    }

    public <T> T runNamed(String label, Supplier<T> edit) {
        stepLabel = label;
        try {
            return edit.get();
        } finally {
            stepLabel = null;
        }
    }

    public void editOppositeEdge(GeoCellRef selected, Direction direction, boolean allowed) {
        var seed = selectedLayer(selected);
        var neighbor = document.cellAt(selected.x() + direction.getDx(), selected.y() + direction.getDy());
        requireAvailable(neighbor);
        var next = new GeoQueryEngine(document.snapshot()).findBaseCell(neighbor, seed.height());
        if (next == null) {
            throw new IllegalArgumentException("Neighbor has no selectable ground layer");
        }
        var matches = 0;
        for (var layer : neighbor.layers()) {
            if (layer.height() == next.layer().height()) {
                matches++;
            }
        }
        if (matches != 1) {
            throw new IllegalArgumentException("Neighbor has ambiguous layers at the selected height");
        }
        var mask = direction.opposite().getMask();
        var flags = allowed ? next.layer().nswe() | mask : next.layer().nswe() & ~mask;
        var layers = new ArrayList<>(neighbor.layers());
        layers.set(
                next.layer().index(),
                new Layer(next.layer().index(), next.layer().height(), flags));
        apply("Edit opposite neighbor edge", List.of(new CellEdit(neighbor.x(), neighbor.y(), layers)));
    }

    public EditResult paintRectangle(GeoCellRef selected, Bounds bounds, int maxHeightDistance, LayerEdit edit) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        var cells = rectangleCells(bounds);
        if (cells.isEmpty()) {
            return new EditResult(0, 0, 0, document.revision());
        }
        return paint("Rectangle", cells, seed.height(), maxHeightDistance, edit);
    }

    public EditResult paintBrush(
            GeoCellRef selected, List<GeoPoint> centers, int radius, int maxHeightDistance, LayerEdit edit) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        return paint("Brush stroke", brushCells(centers, radius), seed.height(), maxHeightDistance, edit);
    }

    public void setEdge(GeoCellRef selected, Direction direction, boolean open, boolean bothSides) {
        var cell = selectedCell(selected);
        var layer = cell.layers().get(selected.layer());
        var edits = new ArrayList<CellEdit>();
        var layers = new ArrayList<>(cell.layers());
        layers.set(layer.index(), withEdge(layer, direction, open));
        edits.add(new CellEdit(cell.x(), cell.y(), layers));
        if (bothSides) {
            var neighbor = document.cellAt(cell.x() + direction.getDx(), cell.y() + direction.getDy());
            var target =
                    neighbor.availability() == Availability.AVAILABLE ? stepNeighbor(neighbor, layer.height()) : null;
            if (target != null) {
                var neighborLayers = new ArrayList<>(neighbor.layers());
                neighborLayers.set(target.index(), withEdge(target, direction.opposite(), open));
                edits.add(new CellEdit(neighbor.x(), neighbor.y(), neighborLayers));
            }
        }
        apply(open ? "Open edge" : "Block edge", edits);
    }

    public record EdgeRef(GeoCellRef cell, Direction direction) {}

    public int setEdges(List<EdgeRef> edges, boolean open, boolean bothSides) {
        var work = new LinkedHashMap<CellKey, ArrayList<Layer>>();
        for (var edge : edges) {
            var cell = selectedCell(edge.cell());
            var key = new CellKey(cell.x(), cell.y());
            var layers = workingLayers(work, key);
            var layer = layers.get(edge.cell().layer());
            layers.set(layer.index(), withEdge(layer, edge.direction(), open));
            if (!bothSides) {
                continue;
            }
            var neighbor = document.cellAt(
                    cell.x() + edge.direction().getDx(),
                    cell.y() + edge.direction().getDy());
            if (neighbor.availability() != Availability.AVAILABLE) {
                continue;
            }
            var neighborKey = new CellKey(neighbor.x(), neighbor.y());
            var neighborLayers = workingLayers(work, neighborKey);
            var target = stepNeighbor(
                    new Cell(
                            neighbor.x(),
                            neighbor.y(),
                            Availability.AVAILABLE,
                            neighborLayers,
                            neighbor.detail(),
                            neighbor.storage()),
                    layer.height());
            if (target != null) {
                neighborLayers.set(
                        target.index(), withEdge(target, edge.direction().opposite(), open));
            }
        }
        var cells = new ArrayList<CellEdit>();
        for (var entry : work.entrySet()) {
            cells.add(new CellEdit(entry.getKey().x(), entry.getKey().y(), entry.getValue()));
        }
        if (!cells.isEmpty()) {
            apply((open ? "Open " : "Block ") + edges.size() + (edges.size() == 1 ? " edge" : " edges"), cells);
        }
        return edges.size();
    }

    public EditResult paintPassability(
            GeoCellRef selected, List<GeoPoint> centers, int radius, int maxHeightDistance, boolean open) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        return applyPassability(brushCells(centers, radius), seed, maxHeightDistance, open);
    }

    public EditResult paintPassabilityRectangle(
            GeoCellRef selected, Bounds bounds, int maxHeightDistance, boolean open) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        return applyPassability(rectangleCells(bounds), seed, maxHeightDistance, open);
    }

    public EditResult paintFloor(
            GeoCellRef selected, List<GeoPoint> centers, int radius, int height, int maxHeightDistance) {
        selectedCell(selected);
        validateFloor(height, maxHeightDistance);
        return addFloor(brushCells(centers, radius), height, maxHeightDistance);
    }

    public EditResult paintFloorRectangle(GeoCellRef selected, Bounds bounds, int height, int maxHeightDistance) {
        selectedCell(selected);
        validateFloor(height, maxHeightDistance);
        return addFloor(rectangleCells(bounds), height, maxHeightDistance);
    }

    public void addLayer(GeoCellRef selected, int insertIndex, int height, int nswe) {
        var layers = new ArrayList<>(selectedCell(selected).layers());
        if (insertIndex < 0 || insertIndex > layers.size()) {
            throw new IllegalArgumentException("Invalid layer insertion index");
        }
        layers.add(insertIndex, new Layer(insertIndex, height, nswe));
        apply("Add layer", List.of(new CellEdit(selected.x(), selected.y(), layers)));
    }

    public void removeLayer(GeoCellRef selected) {
        selectedLayer(selected);
        var layers = new ArrayList<>(document.cellAt(selected.x(), selected.y()).layers());
        layers.remove(selected.layer());
        apply("Remove layer", List.of(new CellEdit(selected.x(), selected.y(), layers)));
    }

    public int applyCells(String label, Map<CellKey, List<Layer>> cells) {
        var edits = new ArrayList<CellEdit>();
        for (var entry : cells.entrySet()) {
            var current = document.cellAt(entry.getKey().x(), entry.getKey().y());
            requireAvailable(current);
            if (!entry.getValue().equals(current.layers())) {
                edits.add(new CellEdit(entry.getKey().x(), entry.getKey().y(), entry.getValue()));
            }
        }
        if (!edits.isEmpty()) {
            apply(label, edits);
        }
        return edits.size();
    }

    public int revertRegion(GeoTile tile) {
        var edits = new ArrayList<CellEdit>();
        for (var key : document.getChangedCells().keySet()) {
            if (GeoTile.fromWorld(key.x(), key.y()).equals(tile)) {
                edits.add(new CellEdit(
                        key.x(),
                        key.y(),
                        document.getBase().cellAt(key.x(), key.y()).layers()));
            }
        }
        if (!edits.isEmpty()) {
            apply("Revert " + tile.x() + "_" + tile.y(), edits);
        }
        return edits.size();
    }

    public int replaceRegion(GeoTile tile, GeoDataRegion replacement, String label) {
        var region = document.getBase().getRegion(tile.x(), tile.y());
        if (region == null) {
            throw new IllegalArgumentException(
                    "Region " + tile.x() + "_" + tile.y() + " is not loaded in this editing session. Fly there first.");
        }
        if (replacement.getTileX() != tile.x() || replacement.getTileY() != tile.y()) {
            throw new IllegalArgumentException("The new data is for another region");
        }
        var edits = new LinkedHashMap<CellKey, List<Layer>>();
        var cells = GeoDataRegion.CELLS_PER_AXIS;
        for (var cellX = 0; cellX < cells; cellX++) {
            for (var cellY = 0; cellY < cells; cellY++) {
                if (isSameCell(region, replacement, cellX, cellY)) {
                    continue;
                }
                if (edits.size() >= REGION_EDIT_LIMIT) {
                    throw new IllegalArgumentException("More than "
                            + String.format(Locale.ROOT, "%,d", REGION_EDIT_LIMIT)
                            + " cells of "
                            + tile.x()
                            + "_"
                            + tile.y()
                            + " differ, more than an editing session holds. Build or copy the whole region"
                            + " file instead.");
                }
                var layers = new ArrayList<Layer>();
                for (var layer = 0; layer < replacement.getLayerCount(cellX, cellY); layer++) {
                    layers.add(new Layer(
                            layer,
                            replacement.getHeight(cellX, cellY, layer),
                            replacement.getNswe(cellX, cellY, layer)));
                }
                edits.put(new CellKey(region.getWorldMinX() + cellX * 16, region.getWorldMinY() + cellY * 16), layers);
            }
        }
        var differ = edits.size();
        for (var key : document.getChangedCells().keySet()) {
            if (GeoTile.fromWorld(key.x(), key.y()).equals(tile) && !edits.containsKey(key)) {
                edits.put(key, document.getBase().cellAt(key.x(), key.y()).layers());
            }
        }
        var list = new ArrayList<CellEdit>();
        for (var entry : edits.entrySet()) {
            list.add(new CellEdit(entry.getKey().x(), entry.getKey().y(), entry.getValue()));
        }
        if (!list.isEmpty()) {
            apply(label, list);
        }
        return differ;
    }

    public EditResult paintHeights(
            GeoCellRef selected, List<GeoPoint> centers, int radius, int maxHeightDistance, HeightRule rule) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        return applyHeights(brushCells(centers, radius), seed.height(), maxHeightDistance, rule);
    }

    public EditResult paintHeightsRectangle(
            GeoCellRef selected, Bounds bounds, int maxHeightDistance, HeightRule rule) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        return applyHeights(rectangleCells(bounds), seed.height(), maxHeightDistance, rule);
    }

    public EditResult paintRebuild(
            GeoCellRef selected, List<GeoPoint> centers, int radius, int maxHeightDistance, GeoDataAccess built) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        return rebuild(brushCells(centers, radius), seed.height(), maxHeightDistance, built);
    }

    public EditResult paintRebuildRectangle(
            GeoCellRef selected, Bounds bounds, int maxHeightDistance, GeoDataAccess built) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        return rebuild(rectangleCells(bounds), seed.height(), maxHeightDistance, built);
    }

    public EditResult removeLayers(GeoCellRef selected, Bounds bounds, int maxHeightDistance) {
        var seed = selectedLayer(selected);
        validateHeightDistance(maxHeightDistance);
        var edits = new ArrayList<CellEdit>();
        var cells = rectangleCells(bounds);
        var skipped = 0;
        for (var key : cells) {
            var cell = document.cellAt(key.x(), key.y());
            requireAvailable(cell);
            var index = nearestLayer(cell, seed.height(), maxHeightDistance, key);
            if (index < 0) {
                skipped++;
                continue;
            }
            var layers = new ArrayList<>(cell.layers());
            layers.remove(index);
            edits.add(new CellEdit(key.x(), key.y(), layers));
        }
        apply("Delete " + edits.size() + (edits.size() == 1 ? " layer" : " layers"), edits);
        return new EditResult(cells.size(), edits.size(), skipped, document.revision());
    }

    public record Clip(int columns, int rows, List<List<Layer>> cells) {
        public Clip {
            cells = List.copyOf(cells);
            if (columns < 1 || rows < 1 || cells.size() != columns * rows) {
                throw new IllegalArgumentException("A clip needs one entry per cell");
            }
        }

        public int count() {
            return columns * rows;
        }
    }

    public Clip copy(Bounds bounds, int baseHeight) {
        return copy(document, bounds, baseHeight);
    }

    public int paste(Clip clip, int x, int y, int baseHeight) {
        var originX = Math.floorDiv(x, 16) * 16;
        var originY = Math.floorDiv(y, 16) * 16;
        if ((long) clip.count() > maxOperationCells) {
            throw new IllegalArgumentException("The copied block exceeds the operation cell limit");
        }
        var edits = new ArrayList<CellEdit>();
        for (var row = 0; row < clip.rows(); row++) {
            for (var column = 0; column < clip.columns(); column++) {
                var cellX = originX + column * 16;
                var cellY = originY + row * 16;
                var cell = document.cellAt(cellX, cellY);
                requireAvailable(cell);
                var layers = new ArrayList<Layer>();
                for (var layer : clip.cells().get(row * clip.columns() + column)) {
                    layers.add(new Layer(
                            layers.size(),
                            Math.clamp(Math.round((layer.height() + baseHeight) / 8f) * 8, -16384, 16376),
                            layer.nswe()));
                }
                if (!layers.equals(cell.layers())) {
                    edits.add(new CellEdit(cellX, cellY, layers));
                }
            }
        }
        apply("Paste " + clip.count() + (clip.count() == 1 ? " cell" : " cells"), edits);
        return edits.size();
    }

    public Patch getPatch() {
        var cells = new ArrayList<CellEdit>();
        var fingerprints = new HashMap<GeoTile, String>();
        for (var entry : document.getChangedCells().entrySet()) {
            var key = entry.getKey();
            var tile = GeoTile.fromWorld(key.x(), key.y());
            fingerprints.put(
                    tile, document.getBase().getRegion(tile.x(), tile.y()).getFingerprint());
            cells.add(new CellEdit(key.x(), key.y(), entry.getValue()));
        }
        cells.sort(Comparator.comparingInt(CellEdit::x).thenComparingInt(CellEdit::y));
        return new Patch(Patch.VERSION, fingerprints, cells);
    }

    public void restorePatch(Patch patch) {
        if (document.isModified()) {
            throw new IllegalStateException("Open the patch in an unmodified editing session");
        }
        for (var entry : patch.sourceFingerprints().entrySet()) {
            var region = document.getBase()
                    .getRegion(entry.getKey().x(), entry.getKey().y());
            if (region == null || !region.getFingerprint().equals(entry.getValue())) {
                throw new IllegalArgumentException("Patch source fingerprint does not match region " + entry.getKey());
            }
        }
        document.loadChanges(patch.cells());
        markSaved();
    }

    private void apply(String label, List<CellEdit> edits) {
        document.apply(stepLabel == null ? label : stepLabel, edits);
    }

    private EditResult applyPassability(LinkedHashSet<CellKey> cells, Layer seed, int maxHeightDistance, boolean open) {
        var painted = new LinkedHashMap<CellKey, Integer>();
        var skipped = 0;
        for (var key : cells) {
            var cell = document.cellAt(key.x(), key.y());
            requireAvailable(cell);
            var index = nearestLayer(cell, seed.height(), maxHeightDistance, key);
            if (index < 0) {
                skipped++;
            } else {
                painted.put(key, index);
            }
        }
        var work = new LinkedHashMap<CellKey, ArrayList<Layer>>();
        for (var entry : painted.entrySet()) {
            var key = entry.getKey();
            var layers = workingLayers(work, key);
            var layer = layers.get(entry.getValue());
            var flags = 0;
            for (var direction : Direction.values()) {
                if (!open) {
                    break;
                }
                var neighbor = document.cellAt(key.x() + direction.getDx(), key.y() + direction.getDy());
                var target = neighbor.availability() == Availability.AVAILABLE
                        ? stepNeighbor(neighbor, layer.height())
                        : null;
                var neighborKey = new CellKey(neighbor.x(), neighbor.y());
                if (target != null
                        && (target.nswe() != 0 || Objects.equals(painted.get(neighborKey), target.index()))) {
                    flags |= direction.getMask();
                }
            }
            layers.set(layer.index(), new Layer(layer.index(), layer.height(), flags));
        }
        for (var entry : painted.entrySet()) {
            var key = entry.getKey();
            var height = document.cellAt(key.x(), key.y())
                    .layers()
                    .get(entry.getValue())
                    .height();
            for (var direction : Direction.values()) {
                var neighbor = document.cellAt(key.x() + direction.getDx(), key.y() + direction.getDy());
                var neighborKey = new CellKey(neighbor.x(), neighbor.y());
                if (painted.containsKey(neighborKey) || neighbor.availability() != Availability.AVAILABLE) {
                    continue;
                }
                var target = stepNeighbor(neighbor, height);
                if (target == null || open && target.nswe() == 0) {
                    continue;
                }
                var layers = workingLayers(work, neighborKey);
                var current = layers.get(target.index());
                layers.set(target.index(), withEdge(current, direction.opposite(), open));
            }
        }
        var edits = new ArrayList<CellEdit>();
        for (var entry : work.entrySet()) {
            var key = entry.getKey();
            if (!entry.getValue().equals(document.cellAt(key.x(), key.y()).layers())) {
                edits.add(new CellEdit(key.x(), key.y(), entry.getValue()));
            }
        }
        apply(open ? "Open area" : "Block area", edits);
        return new EditResult(painted.size() + skipped, edits.size(), skipped, document.revision());
    }

    private EditResult addFloor(LinkedHashSet<CellKey> cells, int height, int maxHeightDistance) {
        var work = new LinkedHashMap<CellKey, ArrayList<Layer>>();
        var added = new LinkedHashSet<CellKey>();
        for (var key : cells) {
            var cell = document.cellAt(key.x(), key.y());
            requireAvailable(cell);
            var near = false;
            for (var layer : cell.layers()) {
                near |= Math.abs(layer.height() - height) <= maxHeightDistance;
            }
            if (near) {
                continue;
            }
            var layers = workingLayers(work, key);
            var place = 0;
            while (place < layers.size() && layers.get(place).height() > height) {
                place++;
            }
            layers.add(place, new Layer(place, height, 0));
            for (var index = place + 1; index < layers.size(); index++) {
                var below = layers.get(index);
                layers.set(index, new Layer(index, below.height(), below.nswe()));
            }
            added.add(key);
        }
        for (var key : added) {
            var layers = work.get(key);
            var own = stepNeighbor(layers, height);
            var flags = 0;
            for (var direction : Direction.values()) {
                var neighbor = document.cellAt(key.x() + direction.getDx(), key.y() + direction.getDy());
                if (neighbor.availability() != Availability.AVAILABLE) {
                    continue;
                }
                var neighborKey = new CellKey(neighbor.x(), neighbor.y());
                var isAdded = added.contains(neighborKey);
                var target = stepNeighbor(isAdded ? work.get(neighborKey) : neighbor.layers(), height);
                if (target == null) {
                    continue;
                }
                if (isAdded && target.height() == height) {
                    flags |= direction.getMask();
                } else if (target.nswe() != 0) {
                    flags |= direction.getMask();
                    var neighborLayers = workingLayers(work, neighborKey);
                    neighborLayers.set(
                            target.index(), withEdge(neighborLayers.get(target.index()), direction.opposite(), true));
                }
            }
            layers.set(own.index(), new Layer(own.index(), height, flags));
        }
        var edits = new ArrayList<CellEdit>();
        for (var entry : work.entrySet()) {
            var key = entry.getKey();
            if (!entry.getValue().equals(document.cellAt(key.x(), key.y()).layers())) {
                edits.add(new CellEdit(key.x(), key.y(), entry.getValue()));
            }
        }
        apply("Add floor to " + added.size() + (added.size() == 1 ? " cell" : " cells"), edits);
        return new EditResult(cells.size(), edits.size(), cells.size() - added.size(), document.revision());
    }

    private EditResult applyHeights(
            LinkedHashSet<CellKey> cells, int seedHeight, int maxHeightDistance, HeightRule rule) {
        var edits = new ArrayList<CellEdit>();
        var skipped = 0;
        for (var key : cells) {
            var cell = document.cellAt(key.x(), key.y());
            requireAvailable(cell);
            var reference = rule instanceof HeightRule.Slope slope
                    ? (int) Math.round(slopeHeight(slope, key.x() + 8, key.y() + 8, false))
                    : seedHeight;
            var limit = rule instanceof HeightRule.Slope slope
                    ? maxHeightDistance
                            + (int) Math.ceil(
                                    Math.abs(slope.to().z() - slope.from().z()))
                    : maxHeightDistance;
            var index = nearestLayer(cell, reference, Math.min(32760, limit), key);
            if (index < 0) {
                skipped++;
                continue;
            }
            var original = cell.layers().get(index);
            var height = switch (rule) {
                case HeightRule.Flat flat -> flat.height();
                case HeightRule.Slope slope -> slopeHeight(slope, key.x() + 8, key.y() + 8, true);
                case HeightRule.Smooth ignored -> smoothHeight(key, original.height(), maxHeightDistance);
                case HeightRule.Offset offset -> original.height() + offset.delta();
            };
            var target = Math.clamp(Math.round(height / 8) * 8, -16384, 16376);
            if (target == original.height()) {
                continue;
            }
            var layers = new ArrayList<>(cell.layers());
            var duplicate = false;
            for (var layer : layers) {
                duplicate |= layer.index() != index && layer.height() == target;
            }
            if (duplicate) {
                skipped++;
                continue;
            }
            layers.set(index, new Layer(index, target, original.nswe()));
            layers.sort(Comparator.comparingInt(Layer::height).reversed());
            edits.add(new CellEdit(key.x(), key.y(), layers));
        }
        var label = switch (rule) {
            case HeightRule.Flat ignored -> "Brush stroke";
            case HeightRule.Slope slope -> slope.step() > 0 ? "Stairs" : "Slope";
            case HeightRule.Smooth ignored -> "Smooth";
            case HeightRule.Offset offset -> offset.delta() >= 0 ? "Raise" : "Lower";
        };
        apply(label, edits);
        return new EditResult(cells.size(), edits.size(), skipped, document.revision());
    }

    private double smoothHeight(CellKey key, int height, int maxHeightDistance) {
        var sum = 0.0;
        var count = 0;
        for (var dx = -16; dx <= 16; dx += 16) {
            for (var dy = -16; dy <= 16; dy += 16) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                var neighbor = document.cellAt(key.x() + dx, key.y() + dy);
                if (neighbor.availability() != Availability.AVAILABLE) {
                    continue;
                }
                Layer nearest = null;
                for (var layer : neighbor.layers()) {
                    if (layer.nswe() != 0
                            && Math.abs(layer.height() - height) <= maxHeightDistance
                            && (nearest == null
                                    || Math.abs(layer.height() - height) < Math.abs(nearest.height() - height))) {
                        nearest = layer;
                    }
                }
                if (nearest != null) {
                    sum += nearest.height();
                    count++;
                }
            }
        }
        return count == 0 ? height : sum / count;
    }

    private EditResult rebuild(
            LinkedHashSet<CellKey> cells, int seedHeight, int maxHeightDistance, GeoDataAccess built) {
        var edits = new ArrayList<CellEdit>();
        var skipped = 0;
        for (var key : cells) {
            var cell = document.cellAt(key.x(), key.y());
            requireAvailable(cell);
            var source = built.cellAt(key.x(), key.y());
            if (source.availability() != Availability.AVAILABLE) {
                skipped++;
                continue;
            }
            var layers = new ArrayList<Layer>();
            for (var layer : cell.layers()) {
                if (Math.abs(layer.height() - seedHeight) > maxHeightDistance) {
                    layers.add(layer);
                }
            }
            for (var layer : source.layers()) {
                if (Math.abs(layer.height() - seedHeight) > maxHeightDistance) {
                    continue;
                }
                var taken = false;
                for (var kept : layers) {
                    taken |= kept.height() == layer.height();
                }
                if (!taken) {
                    layers.add(layer);
                }
            }
            layers.sort(Comparator.comparingInt(Layer::height).reversed());
            var normalized = new ArrayList<Layer>();
            for (var layer : layers) {
                normalized.add(new Layer(normalized.size(), layer.height(), layer.nswe()));
            }
            if (normalized.isEmpty() && !cell.layers().isEmpty()) {
                skipped++;
                continue;
            }
            if (!normalized.equals(cell.layers())) {
                edits.add(new CellEdit(key.x(), key.y(), normalized));
            }
        }
        apply("Rebuild " + edits.size() + (edits.size() == 1 ? " cell" : " cells"), edits);
        return new EditResult(cells.size(), edits.size(), skipped, document.revision());
    }

    private Layer selectedLayer(GeoCellRef selected) {
        return selectedCell(selected).layers().get(selected.layer());
    }

    private Cell selectedCell(GeoCellRef selected) {
        if (!selected.belongsTo(document)) {
            throw new IllegalArgumentException("Selection belongs to another geodata revision");
        }
        var cell = document.cellAt(selected.x(), selected.y());
        requireAvailable(cell);
        return cell;
    }

    private EditResult paint(
            String label, LinkedHashSet<CellKey> cells, int seedHeight, int maxHeightDistance, LayerEdit edit) {
        Objects.requireNonNull(edit, "edit");
        var edits = new ArrayList<CellEdit>();
        var skipped = 0;
        for (var key : cells) {
            var cell = document.cellAt(key.x(), key.y());
            requireAvailable(cell);
            var index = nearestLayer(cell, seedHeight, maxHeightDistance, key);
            if (index < 0) {
                skipped++;
                continue;
            }
            var layers = new ArrayList<>(cell.layers());
            var original = layers.get(index);
            var changed = new Layer(
                    index,
                    edit.height() == null ? original.height() : edit.height(),
                    edit.nswe() == null ? original.nswe() : edit.nswe());
            if (!original.equals(changed)) {
                layers.set(index, changed);
                edits.add(new CellEdit(key.x(), key.y(), layers));
            }
        }
        apply(label, edits);
        return new EditResult(cells.size(), edits.size(), skipped, document.revision());
    }

    private LinkedHashSet<CellKey> rectangleCells(Bounds bounds) {
        var cells = new LinkedHashSet<CellKey>();
        if (bounds.minX() == bounds.maxX() || bounds.minY() == bounds.maxY()) {
            return cells;
        }
        var minX = (long) Math.floorDiv(bounds.minX(), 16) * 16;
        var minY = (long) Math.floorDiv(bounds.minY(), 16) * 16;
        var width = Math.floorDiv((long) bounds.maxX() - 1 - minX, 16) + 1;
        var height = Math.floorDiv((long) bounds.maxY() - 1 - minY, 16) + 1;
        if (width * height > maxOperationCells) {
            throw new IllegalArgumentException("Rectangle exceeds the operation cell limit");
        }
        for (var x = minX; x < bounds.maxX(); x += 16) {
            for (var y = minY; y < bounds.maxY(); y += 16) {
                cells.add(new CellKey((int) x, (int) y));
            }
        }
        return cells;
    }

    private LinkedHashSet<CellKey> brushCells(List<GeoPoint> centers, int radius) {
        if (radius < 1 || radius > 4096 || centers.size() > 1024) {
            throw new IllegalArgumentException("Invalid brush radius or stroke point count");
        }
        var cells = new LinkedHashSet<CellKey>();
        var examined = 0L;
        for (var index = 0; index < centers.size(); index++) {
            var first = centers.get(index == 0 ? 0 : index - 1);
            var last = centers.get(index);
            if (Math.abs(first.x()) > 1_000_000
                    || Math.abs(first.y()) > 1_000_000
                    || Math.abs(last.x()) > 1_000_000
                    || Math.abs(last.y()) > 1_000_000) {
                throw new IllegalArgumentException("Brush is outside supported world coordinates");
            }
            var minX = (int) Math.floor((Math.min(first.x(), last.x()) - radius) / 16) * 16;
            var minY = (int) Math.floor((Math.min(first.y(), last.y()) - radius) / 16) * 16;
            var maxX = Math.max(first.x(), last.x()) + radius;
            var maxY = Math.max(first.y(), last.y()) + radius;
            for (var x = minX; x < maxX; x += 16) {
                for (var y = minY; y < maxY; y += 16) {
                    if (++examined > maxOperationCells * 16L) {
                        throw new IllegalArgumentException("Brush stroke exceeds the operation work limit");
                    }
                    if (distanceSquared(x + 8.0, y + 8.0, first, last) <= (double) radius * radius) {
                        cells.add(new CellKey(x, y));
                        if (cells.size() > maxOperationCells) {
                            throw new IllegalArgumentException("Brush exceeds the operation cell limit");
                        }
                    }
                }
            }
        }
        return cells;
    }

    private ArrayList<Layer> workingLayers(Map<CellKey, ArrayList<Layer>> work, CellKey key) {
        return work.computeIfAbsent(
                key,
                ignored -> new ArrayList<>(document.cellAt(key.x(), key.y()).layers()));
    }

    private static boolean isSameCell(GeoDataRegion a, GeoDataRegion b, int cellX, int cellY) {
        var count = a.getLayerCount(cellX, cellY);
        if (count != b.getLayerCount(cellX, cellY)) {
            return false;
        }
        for (var layer = 0; layer < count; layer++) {
            if (a.getHeight(cellX, cellY, layer) != b.getHeight(cellX, cellY, layer)
                    || a.getNswe(cellX, cellY, layer) != b.getNswe(cellX, cellY, layer)) {
                return false;
            }
        }
        return true;
    }

    public sealed interface HeightRule {
        record Flat(int height) implements HeightRule {}

        record Slope(GeoPoint from, GeoPoint to, int step) implements HeightRule {}

        record Smooth() implements HeightRule {}

        record Offset(int delta) implements HeightRule {}
    }

    private static int nearestLayer(Cell cell, int seedHeight, int maxHeightDistance, CellKey key) {
        var index = -1;
        var distance = Long.MAX_VALUE;
        var ties = 0;
        for (var layer : cell.layers()) {
            var candidateDistance = Math.abs((long) layer.height() - seedHeight);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                index = layer.index();
                ties = 1;
            } else if (candidateDistance == distance) {
                ties++;
            }
        }
        if (index < 0 || distance > maxHeightDistance) {
            return -1;
        }
        if (ties != 1) {
            throw new IllegalArgumentException("Ambiguous surface in stroke at " + key);
        }
        return index;
    }

    private static Layer stepNeighbor(Cell neighbor, int height) {
        return stepNeighbor(neighbor.layers(), height);
    }

    private static Layer stepNeighbor(List<Layer> neighborLayers, int height) {
        Layer best = null;
        var distance = Integer.MAX_VALUE;
        var ties = 0;
        for (var layer : neighborLayers) {
            var candidate = Math.abs(layer.height() - height);
            if (candidate > STEP_HEIGHT) {
                continue;
            }
            if (candidate < distance) {
                best = layer;
                distance = candidate;
                ties = 1;
            } else if (candidate == distance) {
                ties++;
            }
        }
        return ties == 1 ? best : null;
    }

    private static Layer withEdge(Layer layer, Direction direction, boolean open) {
        var flags = open ? layer.nswe() | direction.getMask() : layer.nswe() & ~direction.getMask();
        return new Layer(layer.index(), layer.height(), flags);
    }

    private static void requireAvailable(Cell cell) {
        if (cell.availability() != Availability.AVAILABLE) {
            throw new IllegalArgumentException(
                    "Geodata is " + cell.availability() + " at " + cell.x() + ", " + cell.y());
        }
    }

    private static void validateHeightDistance(int distance) {
        if (distance < 0 || distance > 32760) {
            throw new IllegalArgumentException("Invalid surface height tolerance");
        }
    }

    private static void validateFloor(int height, int maxHeightDistance) {
        validateHeightDistance(maxHeightDistance);
        if (height < -16384 || height > 16376 || Math.floorMod(height, 8) != 0) {
            throw new IllegalArgumentException("Height must be -16384..16376 in steps of eight");
        }
    }

    private static double distanceSquared(double x, double y, GeoPoint first, GeoPoint last) {
        var dx = last.x() - first.x();
        var dy = last.y() - first.y();
        var length = dx * dx + dy * dy;
        var t = length == 0 ? 0 : Math.clamp(((x - first.x()) * dx + (y - first.y()) * dy) / length, 0, 1);
        var offsetX = x - first.x() - t * dx;
        var offsetY = y - first.y() - t * dy;
        return offsetX * offsetX + offsetY * offsetY;
    }
}
