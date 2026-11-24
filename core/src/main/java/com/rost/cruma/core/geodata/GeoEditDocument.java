package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataSnapshot.RegionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class GeoEditDocument implements GeoDataAccess {
    private final GeoDataSnapshot base;
    private final int maxHistory;
    private final int maxChangedCells;
    private final long maxRetainedLayerValues;
    private final Map<CellKey, List<Layer>> changes = new HashMap<>();
    private final ArrayDeque<Command> undo = new ArrayDeque<>();
    private final ArrayDeque<Command> redo = new ArrayDeque<>();
    private Map<Sector, Storage> editedSectors = Map.of();
    private long historyValues;
    private long revision = GeoDataRepository.nextRevision();

    public GeoEditDocument(GeoDataSnapshot base) {
        this(base, 256, 262144);
    }

    public GeoEditDocument(GeoDataSnapshot base, int maxHistory, int maxChangedCells) {
        this(base, maxHistory, maxChangedCells, Math.max(1024, maxChangedCells * 8L));
    }

    public GeoEditDocument(GeoDataSnapshot base, int maxHistory, int maxChangedCells, long maxRetainedLayerValues) {
        this.base = Objects.requireNonNull(base, "base");
        if (maxHistory < 1 || maxChangedCells < 1 || maxRetainedLayerValues < 1) {
            throw new IllegalArgumentException("Edit limits must be positive");
        }
        this.maxHistory = maxHistory;
        this.maxChangedCells = maxChangedCells;
        this.maxRetainedLayerValues = maxRetainedLayerValues;
    }

    public record CellKey(int x, int y) {
        public CellKey {
            if (Math.floorMod(x, 16) != 0 || Math.floorMod(y, 16) != 0) {
                throw new IllegalArgumentException("Edit coordinates must be cell minimum corners");
            }
        }
    }

    public record CellEdit(int x, int y, List<Layer> layers) {
        public CellEdit {
            new CellKey(x, y);
            layers = normalizeLayers(layers);
        }
    }

    static List<Layer> normalizeLayers(List<Layer> layers) {
        Objects.requireNonNull(layers, "layers");
        if (layers.size() > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Too many cell layers");
        }
        var result = new ArrayList<Layer>(layers.size());
        for (var index = 0; index < layers.size(); index++) {
            var layer = layers.get(index);
            if (layer.height() < -16384
                    || layer.height() > 16376
                    || Math.floorMod(layer.height(), 8) != 0
                    || layer.nswe() < 0
                    || layer.nswe() > 15) {
                throw new IllegalArgumentException(
                        "Height must fit signed packed geodata in 8-unit steps and NSWE must be 0..15");
            }
            result.add(new Layer(index, layer.height(), layer.nswe()));
        }
        return List.copyOf(result);
    }

    private record Sector(int x, int y) {
        static Sector at(int x, int y) {
            return new Sector(Math.floorDiv(x, 128) * 128, Math.floorDiv(y, 128) * 128);
        }
    }

    private record Command(
            String label, Map<CellKey, List<Layer>> before, Map<CellKey, List<Layer>> after, long values) {}

    private record EditedSnapshot(
            GeoDataSnapshot base, long revision, Map<CellKey, List<Layer>> changes, Map<Sector, Storage> editedSectors)
            implements GeoDataAccess {
        @Override
        public Cell cellAt(int worldX, int worldY) {
            return editedCell(base, changes, editedSectors, worldX, worldY);
        }

        @Override
        public String getSourceId() {
            return base.getSourceId();
        }

        @Override
        public GeoDataAccess editBase() {
            return base;
        }

        @Override
        public Map<CellKey, List<Layer>> getEditedCells() {
            return changes;
        }
    }

    public GeoDataSnapshot getBase() {
        return base;
    }

    public synchronized Map<CellKey, List<Layer>> getChangedCells() {
        return Map.copyOf(changes);
    }

    public synchronized int getChangedCellCount() {
        return changes.size();
    }

    public synchronized Set<GeoTile> getChangedTiles() {
        var tiles = new HashSet<GeoTile>();
        for (var key : changes.keySet()) {
            tiles.add(GeoTile.fromWorld(key.x(), key.y()));
        }
        return Set.copyOf(tiles);
    }

    public synchronized boolean isModified() {
        return !changes.isEmpty();
    }

    public synchronized boolean isPathGraphStale() {
        return isModified();
    }

    public synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redo.isEmpty();
    }

    public synchronized long getRetainedLayerValues() {
        return countValues(changes) + historyValues;
    }

    public String getContentFingerprint() throws IOException {
        var frozenChanges = getChangedCells();
        var hashes =
                new TreeMap<GeoTile, String>(Comparator.comparingInt(GeoTile::x).thenComparingInt(GeoTile::y));
        hashes.putAll(base.getFingerprints());
        var tiles = new HashMap<GeoTile, Map<CellKey, List<Layer>>>();
        for (var entry : frozenChanges.entrySet()) {
            var tile = GeoTile.fromWorld(entry.getKey().x(), entry.getKey().y());
            tiles.computeIfAbsent(tile, unused -> new HashMap<>()).put(entry.getKey(), entry.getValue());
        }
        for (var entry : tiles.entrySet()) {
            var tile = entry.getKey();
            hashes.put(
                    tile,
                    GeoDataWriter.fileFingerprint(
                            GeoDataWriter.encode(base.getRegion(tile.x(), tile.y()), entry.getValue())));
        }
        var canonical = new StringBuilder();
        for (var entry : hashes.entrySet()) {
            canonical
                    .append(entry.getKey().x())
                    .append('_')
                    .append(entry.getKey().y())
                    .append(':')
                    .append(entry.getValue())
                    .append('\n');
        }
        return GeoDataWriter.fileFingerprint(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public synchronized String getUndoLabel() {
        return undo.isEmpty() ? "" : undo.peekLast().label();
    }

    public synchronized String getRedoLabel() {
        return redo.isEmpty() ? "" : redo.peekLast().label();
    }

    public synchronized List<String> getUndoLabels() {
        return labels(undo);
    }

    public synchronized List<String> getRedoLabels() {
        return labels(redo);
    }

    public synchronized void setLayer(GeoCellRef selection, int height, int nswe) {
        setLayer(selection, height, nswe, "Edit cell layer");
    }

    public synchronized void setLayer(GeoCellRef selection, int height, int nswe, String label) {
        if (!selection.belongsTo(this)) {
            throw new IllegalArgumentException("Selection belongs to another geodata revision");
        }
        var layers = new ArrayList<>(cellAt(selection.x(), selection.y()).layers());
        Objects.checkIndex(selection.layer(), layers.size());
        layers.set(selection.layer(), new Layer(selection.layer(), height, nswe));
        apply(label, List.of(new CellEdit(selection.x(), selection.y(), layers)));
    }

    public synchronized void apply(String label, List<CellEdit> edits) {
        Objects.requireNonNull(label, "label");
        var candidate = new HashMap<>(changes);
        var before = new HashMap<CellKey, List<Layer>>();
        var after = new HashMap<CellKey, List<Layer>>();
        var sectors = new HashSet<Sector>();
        for (var edit : edits) {
            var key = new CellKey(edit.x(), edit.y());
            var original = base.cellAt(key.x(), key.y());
            if (original.availability() != Availability.AVAILABLE) {
                throw new IllegalArgumentException("Cannot edit unavailable geodata at " + key);
            }
            before.putIfAbsent(key, changes.getOrDefault(key, original.layers()));
            var layers = normalizeLayers(edit.layers());
            after.put(key, layers);
            if (layers.equals(original.layers())) {
                candidate.remove(key);
            } else {
                candidate.put(key, layers);
            }
            sectors.add(Sector.at(key.x(), key.y()));
        }
        if (candidate.size() > maxChangedCells) {
            throw new IllegalArgumentException("Edit exceeds the changed cell limit");
        }
        for (var sector : sectors) {
            validateSector(base, candidate, sector);
        }
        if (candidate.equals(changes)) {
            return;
        }
        var command =
                new Command(label, Map.copyOf(before), Map.copyOf(after), countValues(before) + countValues(after));
        if (command.values() + Math.max(countValues(candidate), countValues(changes)) > maxRetainedLayerValues) {
            throw new IllegalArgumentException("Edit exceeds the retained layer memory limit");
        }
        changes.clear();
        changes.putAll(candidate);
        editedSectors = sectorStorage(base, changes);
        for (var discarded : redo) {
            historyValues -= discarded.values();
        }
        redo.clear();
        undo.addLast(command);
        historyValues += command.values();
        trimHistory();
        revision = GeoDataRepository.nextRevision();
    }

    public synchronized void loadChanges(List<CellEdit> edits) {
        if (!changes.isEmpty()) {
            throw new IllegalStateException("Load changes into an unmodified document");
        }
        var candidate = new HashMap<CellKey, List<Layer>>();
        var seen = new HashSet<CellKey>();
        var sectors = new HashSet<Sector>();
        for (var edit : edits) {
            var key = new CellKey(edit.x(), edit.y());
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate imported cell " + key);
            }
            var original = base.cellAt(key.x(), key.y());
            if (original.availability() != Availability.AVAILABLE) {
                throw new IllegalArgumentException("Cannot edit unavailable geodata at " + key);
            }
            var layers = normalizeLayers(edit.layers());
            if (!layers.equals(original.layers())) {
                candidate.put(key, layers);
                sectors.add(Sector.at(key.x(), key.y()));
            }
        }
        if (candidate.size() > maxChangedCells) {
            throw new IllegalArgumentException("Edit exceeds the changed cell limit");
        }
        if (countValues(candidate) > maxRetainedLayerValues) {
            throw new IllegalArgumentException("Edit exceeds the retained layer memory limit");
        }
        for (var sector : sectors) {
            validateSector(base, candidate, sector);
        }
        changes.putAll(candidate);
        editedSectors = sectorStorage(base, changes);
        undo.clear();
        redo.clear();
        historyValues = 0;
        revision = GeoDataRepository.nextRevision();
    }

    public synchronized boolean undo() {
        if (undo.isEmpty()) {
            return false;
        }
        var command = undo.removeLast();
        restore(command.before());
        redo.addLast(command);
        trimHistory();
        return true;
    }

    public synchronized boolean redo() {
        if (redo.isEmpty()) {
            return false;
        }
        var command = redo.removeLast();
        restore(command.after());
        undo.addLast(command);
        trimHistory();
        return true;
    }

    public synchronized GeoDataAccess snapshot() {
        return new EditedSnapshot(base, revision, Map.copyOf(changes), editedSectors);
    }

    public GeoDataSnapshot getMaterializedSnapshot() throws IOException {
        Map<CellKey, List<Layer>> frozenChanges;
        long frozenRevision;
        synchronized (this) {
            frozenChanges = Map.copyOf(changes);
            frozenRevision = revision;
        }
        var regions = new HashMap<>(base.getTiles());
        var edits = new HashMap<GeoTile, Map<CellKey, List<Layer>>>();
        for (var entry : frozenChanges.entrySet()) {
            var tile = GeoTile.fromWorld(entry.getKey().x(), entry.getKey().y());
            edits.computeIfAbsent(tile, unused -> new HashMap<>()).put(entry.getKey(), entry.getValue());
        }
        for (var entry : edits.entrySet()) {
            var tile = entry.getKey();
            var encoded = GeoDataWriter.encode(base.getRegion(tile.x(), tile.y()), entry.getValue());
            regions.put(tile, new RegionState(Availability.AVAILABLE, GeoDataReader.read(encoded), "Edited region"));
        }
        return new GeoDataSnapshot(base.getSourcePath(), frozenRevision, base.getSourceEpoch(), regions);
    }

    @Override
    public synchronized Cell cellAt(int worldX, int worldY) {
        return editedCell(base, changes, editedSectors, worldX, worldY);
    }

    @Override
    public synchronized long revision() {
        return revision;
    }

    @Override
    public String getSourceId() {
        return base.getSourceId();
    }

    private void restore(Map<CellKey, List<Layer>> cells) {
        for (var entry : cells.entrySet()) {
            if (entry.getValue()
                    .equals(base.cellAt(entry.getKey().x(), entry.getKey().y()).layers())) {
                changes.remove(entry.getKey());
            } else {
                changes.put(entry.getKey(), entry.getValue());
            }
        }
        editedSectors = sectorStorage(base, changes);
        revision = GeoDataRepository.nextRevision();
    }

    private void trimHistory() {
        while (undo.size() + redo.size() > maxHistory || getRetainedLayerValues() > maxRetainedLayerValues) {
            if (!undo.isEmpty()) {
                historyValues -= undo.removeFirst().values();
            } else if (!redo.isEmpty()) {
                historyValues -= redo.removeFirst().values();
            } else {
                break;
            }
        }
    }

    private static List<String> labels(ArrayDeque<Command> commands) {
        var labels = new ArrayList<String>(commands.size());
        var newest = commands.descendingIterator();
        while (newest.hasNext()) {
            labels.add(newest.next().label());
        }
        return labels;
    }

    private static long countValues(Map<CellKey, List<Layer>> cells) {
        var total = (long) cells.size();
        for (var layers : cells.values()) {
            total += layers.size();
        }
        return total;
    }

    private static Cell editedCell(
            GeoDataSnapshot base, Map<CellKey, List<Layer>> changes, Map<Sector, Storage> editedSectors, int x, int y) {
        var original = base.cellAt(x, y);
        if (original.availability() != Availability.AVAILABLE) {
            return original;
        }
        var sector = Sector.at(original.x(), original.y());
        var storage = editedSectors.get(sector);
        if (storage == null) {
            return original;
        }
        var layers = changes.getOrDefault(new CellKey(original.x(), original.y()), original.layers());
        return new Cell(original.x(), original.y(), Availability.AVAILABLE, layers, "Edited sector", storage);
    }

    private static Map<Sector, Storage> sectorStorage(GeoDataSnapshot base, Map<CellKey, List<Layer>> changes) {
        var sectors = new HashMap<Sector, Storage>();
        for (var key : changes.keySet()) {
            sectors.put(Sector.at(key.x(), key.y()), Storage.SINGLE);
        }
        for (var sector : sectors.keySet()) {
            var single = true;
            for (var x = sector.x(); single && x < sector.x() + 128; x += 16) {
                for (var y = sector.y(); single && y < sector.y() + 128; y += 16) {
                    var layers = changes.get(new CellKey(x, y));
                    single = (layers == null ? base.cellAt(x, y).layers().size() : layers.size()) == 1;
                }
            }
            sectors.put(sector, single ? Storage.SINGLE : Storage.MULTI);
        }
        return Map.copyOf(sectors);
    }

    private static void validateSector(GeoDataSnapshot base, Map<CellKey, List<Layer>> changes, Sector sector) {
        var total = 0;
        var single = true;
        for (var x = sector.x(); x < sector.x() + 128; x += 16) {
            for (var y = sector.y(); y < sector.y() + 128; y += 16) {
                var key = new CellKey(x, y);
                var layers = changes.get(key);
                var count = layers == null ? base.cellAt(x, y).layers().size() : layers.size();
                total += count;
                single &= count == 1;
            }
        }
        if (total > Short.MAX_VALUE || (!single && (total == 0 || total == 64))) {
            throw new IllegalArgumentException("Sector layer total cannot be encoded without ambiguity: " + total);
        }
    }
}
