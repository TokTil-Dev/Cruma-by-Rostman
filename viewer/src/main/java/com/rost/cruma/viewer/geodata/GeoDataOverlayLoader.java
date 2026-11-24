package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataReader;
import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.viewer.geodata.GeoDataChunk.Place;
import com.rost.cruma.viewer.geodata.GeoDataDisplay.Mode;
import com.rost.cruma.viewer.geodata.GeoDataMesh.Tile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class GeoDataOverlayLoader implements AutoCloseable {
    public static final int SNAP_SIZE = 128;

    static final int WORK_PER_CELL = 64;

    private static final GeoDataDisplay PLAIN =
            new GeoDataDisplay(Mode.EXACT_GRID, 1, 1, false, 16, false, -32768, 32767, false, null);
    private static final int[] NEIGHBOR_X = {0, -16, 16, 0, 0};
    private static final int[] NEIGHBOR_Y = {0, 0, 0, -16, 16};
    private final GeoDataRepository repository;
    private final ThreadPoolExecutor worker;
    private volatile Snapshot snapshot;
    private volatile long revision;
    private volatile boolean closed;
    private Request current;

    private boolean lookAgain;

    private Future<?> pending;
    private final Map<Place, Piece> pieces = new HashMap<>();
    private Identity piecesIdentity;
    private Map<CellKey, List<Layer>> piecesEdits = Map.of();
    private volatile boolean dropPieces;

    public GeoDataOverlayLoader() {
        this(GeoDataReader::read);
    }

    GeoDataOverlayLoader(RegionReader reader) {
        repository = new GeoDataRepository(null, 4, 128L * 1024 * 1024, reader::read);
        snapshot = new Snapshot("Geodata is off.", false, repository.snapshot());
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            var thread = new Thread(task, "geodata-overlay");
            thread.setDaemon(true);
            return thread;
        });
    }

    public record Snapshot(
            List<GeoDataChunk> chunks,
            int layerCount,
            boolean truncated,
            String status,
            boolean loading,
            GeoDataSnapshot data,
            Area area) {
        public Snapshot {
            chunks = List.copyOf(chunks);
        }

        Snapshot(String status, boolean loading, GeoDataSnapshot data) {
            this(List.of(), 0, false, status, loading, data, null);
        }

        public GeoDataMesh mesh() {
            if (chunks.size() == 1 && chunks.getFirst().mesh().truncated() == truncated) {
                return chunks.getFirst().mesh();
            }
            var floats = 0;
            var quads = 0;
            for (var chunk : chunks) {
                floats += chunk.mesh().vertices().length;
                quads += chunk.mesh().quadCount();
            }
            if (floats == 0 && layerCount == 0 && !truncated) {
                return GeoDataMesh.EMPTY;
            }
            var vertices = new float[floats];
            var offset = 0;
            for (var chunk : chunks) {
                var source = chunk.mesh().vertices();
                System.arraycopy(source, 0, vertices, offset, source.length);
                offset += source.length;
            }
            return new GeoDataMesh(vertices, layerCount, truncated, quads);
        }

        public int gpuVertexCount() {
            var count = 0;
            for (var chunk : chunks) {
                count += chunk.mesh().gpuVertexCount();
            }
            return count;
        }
    }

    public record Area(int minX, int minY, int maxX, int maxY) {}

    public static int chunkSize(int radius) {
        return Math.clamp(Integer.highestOneBit(Math.max(1, radius / 8)), SNAP_SIZE, 512);
    }

    public GeoDataRepository getRepository() {
        return repository;
    }

    public synchronized void update(Path directory, float cameraX, float cameraY, int radius) {
        update(directory, cameraX, cameraY, radius, null);
    }

    public synchronized void update(Path directory, float cameraX, float cameraY, int radius, GeoDataDisplay display) {
        update(directory, cameraX, cameraY, radius, display, null, null);
    }

    public synchronized void update(
            Path directory,
            float cameraX,
            float cameraY,
            int radius,
            GeoDataDisplay display,
            GeoDataSnapshot fixedSource,
            GeoDataAccess preview) {
        update(directory, cameraX, cameraY, radius, display, fixedSource, preview, GeoDataMesh.DEFAULT_VERTEX_LIMIT);
    }

    public synchronized void update(
            Path directory,
            float cameraX,
            float cameraY,
            int radius,
            GeoDataDisplay display,
            GeoDataSnapshot fixedSource,
            GeoDataAccess preview,
            int vertexLimit) {
        if (preview != null && (display == null || fixedSource == null)) {
            throw new IllegalArgumentException("A preview requires its source snapshot and display options");
        }
        if (closed) {
            return;
        }
        if (directory == null) {
            clear("No geodata source selected.");
            return;
        }
        if (!Float.isFinite(cameraX)
                || !Float.isFinite(cameraY)
                || Math.abs(cameraX) > 1_000_000_000
                || Math.abs(cameraY) > 1_000_000_000) {
            clear("Geodata needs a valid camera position.");
            return;
        }
        var path = directory.toAbsolutePath().normalize();
        var request = new Request(
                path,
                snap(cameraX),
                snap(cameraY),
                Math.clamp(radius, 128, 4096),
                display,
                fixedSource,
                preview,
                Math.clamp(vertexLimit, 250_000, GeoDataMesh.MAX_VERTICES));
        if (request.equals(current) && !lookAgain) {
            return;
        }
        lookAgain = false;
        var directoryChanged = current == null || !current.directory().equals(path);
        var keepMesh = !directoryChanged
                && current.radius() == request.radius()
                && Objects.equals(current.preview(), request.preview());
        cancelPending(directoryChanged);
        if (directoryChanged) {
            repository.setSource(path);
        }
        current = request;
        var generation = ++revision;
        if (directoryChanged) {
            dropPieces = true;
        }
        snapshot = keepMesh
                ? new Snapshot(
                        snapshot.chunks(),
                        snapshot.layerCount(),
                        snapshot.truncated(),
                        "Loading geodata...",
                        true,
                        snapshot.data(),
                        snapshot.area())
                : new Snapshot("Loading geodata...", true, directoryChanged ? repository.snapshot() : snapshot.data());
        pending = worker.submit(() -> load(request, generation));
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public synchronized void refreshMissing() {
        repository.forgetMissing();
        lookAgain = true;
    }

    public synchronized void reset() {
        clear("Geodata is off.");
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            clear("Geodata is off.");
            worker.shutdownNow();
        }
    }

    @FunctionalInterface
    interface RegionReader {
        GeoDataRegion read(Path path) throws IOException;
    }

    private void load(Request request, long generation) {
        try {
            checkCurrent(generation);
            if (!Files.isDirectory(request.directory())) {
                publish(
                        generation,
                        new Snapshot(
                                "Geodata folder not found or inaccessible: " + request.directory(),
                                false,
                                repository.snapshot()));
                return;
            }
            var size = chunkSize(request.radius());
            var area = new Area(
                    Math.floorDiv(request.x() - request.radius(), size) * size,
                    Math.floorDiv(request.y() - request.radius(), size) * size,
                    Math.ceilDiv(request.x() + SNAP_SIZE + request.radius(), size) * size,
                    Math.ceilDiv(request.y() + SNAP_SIZE + request.radius(), size) * size);
            var data = request.fixedSource() == null
                    ? repository.loadBounds(area.minX(), area.minY(), area.maxX(), area.maxY())
                    : request.fixedSource();
            checkCurrent(generation);
            var missing = new ArrayList<String>();
            var failures = new ArrayList<String>();
            for (var entry : data.getTiles().entrySet()) {
                var tile = new Tile(entry.getKey().x(), entry.getKey().y());
                var state = entry.getValue();
                if (state.availability() == Availability.MISSING) {
                    missing.add(tile.fileName());
                } else if (state.availability() != Availability.AVAILABLE) {
                    failures.add(tile.fileName() + ": " + state.detail());
                }
            }
            var built = build(request, request.preview() == null ? data : request.preview(), size, area, generation);
            var status = built.layers() + " geodata layers";
            if (built.truncated()) {
                status += " · partial display (capacity limit)";
            }
            if (!missing.isEmpty()) {
                status += ". Missing: " + String.join(", ", missing);
            }
            if (!failures.isEmpty()) {
                status += ". Cannot read " + String.join("; ", failures);
            }
            publish(
                    generation,
                    new Snapshot(built.chunks(), built.layers(), built.truncated(), status, false, data, area));
        } catch (CancellationException ignored) {
        } catch (RuntimeException failure) {
            publish(
                    generation,
                    new Snapshot("Cannot load geodata: " + failure.getMessage(), false, repository.snapshot()));
        }
    }

    private synchronized void publish(long generation, Snapshot result) {
        if (!isStale(generation)) {
            snapshot = result;
        }
    }

    private boolean isStale(long generation) {
        return closed || revision != generation || Thread.currentThread().isInterrupted();
    }

    private void checkCurrent(long generation) {
        if (isStale(generation)) {
            throw new CancellationException();
        }
    }

    private void clear(String status) {
        if (current == null && snapshot.status().equals(status) && !snapshot.loading()) {
            return;
        }
        revision++;
        current = null;
        dropPieces = true;
        cancelPending(true);
        repository.refresh();
        snapshot = new Snapshot(status, false, repository.snapshot());
    }

    private void cancelPending(boolean interrupt) {
        if (pending != null) {
            pending.cancel(interrupt);
            pending = null;
        }
        worker.getQueue().clear();
    }

    private record Built(List<GeoDataChunk> chunks, int layers, boolean truncated) {}

    private record Piece(GeoDataChunk chunk, int work, long regions) {}

    private record Identity(String sourceId, long contentRevision, GeoDataDisplay display, int size) {}

    private Built build(Request request, GeoDataAccess source, int size, Area area, long generation) {
        var display = request.display() == null ? PLAIN : request.display();
        if (dropPieces) {
            dropPieces = false;
            pieces.clear();
            piecesIdentity = null;
        }
        var base = source.editBase();
        var identity = new Identity(base.getSourceId(), base.getContentRevision(), display, size);
        if (!identity.equals(piecesIdentity)) {
            pieces.clear();
            piecesIdentity = identity;
            piecesEdits = Map.of();
        }
        forgetEdited(source.getEditedCells(), display, size);
        var places = new ArrayList<Place>();
        for (var x = area.minX(); x < area.maxX(); x += size) {
            for (var y = area.minY(); y < area.maxY(); y += size) {
                places.add(new Place(x, y, size));
            }
        }
        places.sort(Comparator.comparingLong(place -> distanceSquared(place, request.x(), request.y())));
        var vertices = request.vertexLimit();
        var cells = (long) (area.maxX() - area.minX()) / 16 * ((area.maxY() - area.minY()) / 16);
        var work = (int) Math.min(Integer.MAX_VALUE, Math.max(vertices, cells * WORK_PER_CELL));
        var chunks = new ArrayList<GeoDataChunk>();
        var layers = 0;
        var truncated = false;
        for (var place : places) {
            checkCurrent(generation);
            if (vertices <= 0 || work <= 0) {
                truncated = true;
                break;
            }
            var regions = regionAvailability(source, place);
            var kept = pieces.get(place);
            if (kept == null
                    || kept.regions() != regions
                    || kept.chunk().mesh().vertexCount() > vertices
                    || kept.work() > work) {
                var piece = GeoDataDisplayMesh.build(
                        source,
                        place.x(),
                        place.y(),
                        place.x() + size,
                        place.y() + size,
                        request.x(),
                        request.y(),
                        vertices,
                        work,
                        display,
                        () -> isStale(generation));
                kept = new Piece(
                        new GeoDataChunk(place.x(), place.y(), size, piece.mesh(), piece.minZ(), piece.maxZ()),
                        piece.work(),
                        regions);
                if (piece.mesh().truncated()) {
                    pieces.remove(place);
                } else {
                    pieces.put(place, kept);
                }
            }
            var mesh = kept.chunk().mesh();
            vertices -= mesh.vertexCount();
            work -= kept.work();
            layers += mesh.layerCount();
            if (mesh.layerCount() > 0 || mesh.vertexCount() > 0) {
                chunks.add(kept.chunk());
            }
            if (mesh.truncated()) {
                truncated = true;
                break;
            }
        }
        pieces.keySet().retainAll(new HashSet<>(places));
        return new Built(chunks, layers, truncated);
    }

    private void forgetEdited(Map<CellKey, List<Layer>> edits, GeoDataDisplay display, int size) {
        if (edits == piecesEdits) {
            return;
        }
        var changed = new HashSet<CellKey>();
        for (var entry : edits.entrySet()) {
            if (!entry.getValue().equals(piecesEdits.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (var key : piecesEdits.keySet()) {
            if (!edits.containsKey(key)) {
                changed.add(key);
            }
        }
        piecesEdits = edits;
        if (changed.isEmpty()) {
            return;
        }
        if (display.surface() != null) {
            pieces.clear();
            return;
        }
        for (var key : changed) {
            for (var direction = 0; direction < 5; direction++) {
                var x = key.x() + NEIGHBOR_X[direction];
                var y = key.y() + NEIGHBOR_Y[direction];
                pieces.remove(new Place(Math.floorDiv(x, size) * size, Math.floorDiv(y, size) * size, size));
            }
        }
    }

    private static long regionAvailability(GeoDataAccess source, Place place) {
        var min = -16;
        var max = place.size();
        var result = 0L;
        for (var dx : new int[] {min, 0, max}) {
            for (var dy : new int[] {min, 0, max}) {
                result = result * 8
                        + source.cellAt(place.x() + dx, place.y() + dy)
                                .availability()
                                .ordinal();
            }
        }
        return result;
    }

    private static long distanceSquared(Place place, int x, int y) {
        return (long) (place.x() - x) * (place.x() - x) + (long) (place.y() - y) * (place.y() - y);
    }

    private static int snap(float coordinate) {
        return (int) Math.floor(coordinate / SNAP_SIZE) * SNAP_SIZE;
    }

    private record Request(
            Path directory,
            int x,
            int y,
            int radius,
            GeoDataDisplay display,
            GeoDataSnapshot fixedSource,
            GeoDataAccess preview,
            int vertexLimit) {}
}
