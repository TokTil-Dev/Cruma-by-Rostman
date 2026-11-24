package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoDataWriter;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoPathNodeGenerator;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class GeoLivePathGraph implements AutoCloseable {
    static final long QUIET_NANOS = 1_500_000_000L;

    private final long quietNanos;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-live-pathnode");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Path> folders = new ArrayList<>();
    private Future<?> pending;
    private volatile long generation;
    private volatile Status status = Status.NONE;
    private long seenRevision = Long.MIN_VALUE;
    private long seenAt;
    private long requested = Long.MIN_VALUE;
    private long readyRevision = Long.MIN_VALUE;
    private Path ready;

    public GeoLivePathGraph() {
        this(QUIET_NANOS);
    }

    public GeoLivePathGraph(long quietNanos) {
        this.quietNanos = quietNanos;
    }

    public enum State {
        NONE,
        UPDATING,
        READY,
        FAILED
    }

    public record Status(State state, Set<GeoTile> tiles, String detail) {
        static final Status NONE = new Status(State.NONE, Set.of(), "");

        public Status {
            tiles = Set.copyOf(tiles);
        }

        public boolean covers(@Nullable GeoPoint a, @Nullable GeoPoint b) {
            return state == State.READY
                    && a != null
                    && b != null
                    && tiles.contains(GeoTile.fromWorld((int) Math.floor(a.x()), (int) Math.floor(a.y())))
                    && tiles.contains(GeoTile.fromWorld((int) Math.floor(b.x()), (int) Math.floor(b.y())));
        }

        public String names() {
            var names = new TreeSet<String>();
            for (var tile : tiles) {
                names.add(tile.x() + "_" + tile.y());
            }
            return String.join(", ", names);
        }
    }

    public synchronized void update(@Nullable GeoEditingSession session) {
        if (session == null || !session.isModified()) {
            if (status.state() != State.NONE) {
                cancel();
                status = Status.NONE;
                readyRevision = Long.MIN_VALUE;
            }
            seenRevision = Long.MIN_VALUE;
            return;
        }
        var document = session.getDocument();
        var revision = document.revision();
        var now = System.nanoTime();
        if (revision != seenRevision) {
            seenRevision = revision;
            seenAt = now;
            if (revision != readyRevision) {
                status = new Status(State.UPDATING, status.tiles(), "");
            }
            return;
        }
        if (revision == requested || revision == readyRevision || now - seenAt < quietNanos) {
            return;
        }
        cancel();
        requested = revision;
        var base = document.getBase();
        var changes = document.getChangedCells();
        var generation = ++this.generation;
        status = new Status(State.UPDATING, status.tiles(), "");
        pending = worker.submit(() -> build(generation, revision, base, changes));
    }

    public Status getStatus() {
        return status;
    }

    public synchronized @Nullable Path getGraphDirectory(long revision) {
        return revision == readyRevision && status.state() == State.READY ? ready : null;
    }

    @Override
    public synchronized void close() {
        cancel();
        worker.shutdownNow();
        for (var folder : folders) {
            delete(folder);
        }
        folders.clear();
    }

    private void cancel() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        requested = Long.MIN_VALUE;
    }

    private void build(long generation, long revision, GeoDataSnapshot base, Map<CellKey, List<Layer>> changes) {
        Path folder = null;
        try {
            var edited = new HashMap<GeoTile, Map<CellKey, List<Layer>>>();
            for (var entry : changes.entrySet()) {
                edited.computeIfAbsent(
                                GeoTile.fromWorld(
                                        entry.getKey().x(), entry.getKey().y()),
                                ignored -> new HashMap<>())
                        .put(entry.getKey(), entry.getValue());
            }
            var tiles = new TreeSet<GeoTile>(Comparator.comparingInt(GeoTile::x).thenComparingInt(GeoTile::y));
            for (var tile : edited.keySet()) {
                for (var dx = -1; dx <= 1; dx++) {
                    for (var dy = -1; dy <= 1; dy++) {
                        var near = new GeoTile(tile.x() + dx, tile.y() + dy);
                        if (near.containsWorld() && base.getRegion(near.x(), near.y()) != null) {
                            tiles.add(near);
                        }
                    }
                }
            }
            folder = Files.createTempDirectory("cruma-live-pathnode-");
            synchronized (this) {
                folders.add(folder);
            }
            var names = new ArrayList<String>();
            for (var tile : tiles) {
                requireCurrent(generation);
                var region = base.getRegion(tile.x(), tile.y());
                var edits = edited.get(tile);
                var bytes = edits == null ? GeoFormat.L2OFF.encode(region) : GeoDataWriter.encode(region, edits);
                var name = GeoFormat.L2OFF.getFileName(tile);
                Files.write(folder.resolve(name), bytes);
                names.add(name);
            }
            Files.writeString(
                    folder.resolve("geo_index.txt"),
                    names.size() + "\n" + String.join("\n", names) + "\n",
                    StandardCharsets.US_ASCII);
            GeoPathNodeGenerator.generate(
                    folder, ZoneTable.Y10_TO_26, (completed, total, detail) -> requireCurrent(generation));
            synchronized (this) {
                if (this.generation != generation) {
                    return;
                }
                var previous = ready;
                ready = folder;
                readyRevision = revision;
                status = new Status(seenRevision == revision ? State.READY : State.UPDATING, tiles, "");
                if (seenRevision != revision) {
                    readyRevision = Long.MIN_VALUE;
                }
                retire(previous);
            }
        } catch (InterruptedIOException stopped) {
            discard(folder);
        } catch (IOException | RuntimeException failure) {
            discard(folder);
            LogManager.getLogger(GeoLivePathGraph.class)
                    .warn("Could not build the edited pathnode graph: {}", failure.toString());
            synchronized (this) {
                if (this.generation == generation) {
                    status = new Status(
                            State.FAILED,
                            Set.of(),
                            failure.getMessage() == null ? failure.toString() : failure.getMessage());
                }
            }
        }
    }

    private void requireCurrent(long generation) throws IOException {
        if (this.generation != generation || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Edited pathnode build replaced");
        }
    }

    private void retire(@Nullable Path previous) {
        var keep = new HashSet<Path>();
        keep.add(ready);
        keep.add(previous);
        var iterator = folders.iterator();
        while (iterator.hasNext()) {
            var folder = iterator.next();
            if (!keep.contains(folder) && delete(folder)) {
                iterator.remove();
            }
        }
    }

    private void discard(@Nullable Path folder) {
        if (folder != null && delete(folder)) {
            synchronized (this) {
                folders.remove(folder);
            }
        }
    }

    private static boolean delete(Path folder) {
        try (var files = Files.list(folder)) {
            for (var file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(folder);
            return true;
        } catch (IOException | RuntimeException locked) {
            return false;
        }
    }
}
