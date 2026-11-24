package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoPathNodeFolder;
import com.rost.cruma.core.geodata.GeoPathNodeFolder.State;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild.Listener;
import com.rost.cruma.core.geodata.generation.ExtraStartPoints;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class GeodataBuildJob implements AutoCloseable {
    public static final int GRAPH_ONLY = -1;

    private static final GeodataBuildJob SHARED = new GeodataBuildJob();

    private volatile Progress progress = Progress.idle();
    private volatile boolean stop;
    private volatile @Nullable Thread thread;
    private final AtomicInteger written = new AtomicInteger();
    private final Map<GeoTile, String> failures = new ConcurrentHashMap<>();

    public static GeodataBuildJob getShared() {
        return SHARED;
    }

    public record Progress(
            Path folder,
            boolean running,
            int done,
            int total,
            @Nullable GeoTile tile,
            String stage,
            double fraction,
            int written,
            long startedNanos,
            long regionNanos,
            boolean stopped,
            int failed,
            String error,
            boolean graph,
            int graphRegions) {
        static Progress idle() {
            return new Progress(Path.of(""), false, 0, 0, null, "", 0, 0, 0, 0, false, 0, "", false, -1);
        }

        Progress graph(String stage, double fraction) {
            return new Progress(
                    folder,
                    true,
                    done,
                    total,
                    null,
                    stage,
                    fraction,
                    written,
                    startedNanos,
                    regionNanos,
                    false,
                    failed,
                    "",
                    true,
                    -1);
        }

        Progress ended(boolean stopped, String error, int graphRegions) {
            return new Progress(
                    folder,
                    false,
                    done,
                    total,
                    null,
                    "",
                    0,
                    written,
                    startedNanos,
                    regionNanos,
                    stopped,
                    failed,
                    error,
                    graph && !error.isBlank(),
                    graphRegions);
        }

        public double overall() {
            return total == 0 ? 0 : Math.min(1, (done + fraction) / total);
        }

        public long secondsLeft() {
            if (written == 0 || total <= done) {
                return -1;
            }
            var perRegion = (double) (regionNanos - startedNanos) / written;
            return Math.max(1, Math.round((total - done - fraction) * perRegion / 1e9));
        }
    }

    static List<GeoTile> planRegions(
            List<GeoTile> regions, @Nullable Set<GeoTile> only, Predicate<GeoTile> hasFile, boolean replace) {
        var result = new ArrayList<GeoTile>();
        for (var tile : regions) {
            if ((only == null || only.contains(tile)) && (replace || !hasFile.test(tile))) {
                result.add(tile);
            }
        }
        return result;
    }

    public synchronized void start(Path clientRoot, Path folder, GeoFormat format, GeoTile near) {
        start(clientRoot, WorldVariant.MAIN, folder, format, near, null, false, null);
    }

    public record PathNodes(@Nullable ZoneTable table) {}

    public record StartSources(@Nullable Path npcpos) {
        public static final StartSources NONE = new StartSources(null);

        ExtraStartPoints read() throws IOException {
            if (npcpos == null) {
                return ExtraStartPoints.NONE;
            }
            try {
                return ExtraStartPoints.spawns(npcpos);
            } catch (IOException unreadable) {
                throw new IOException("Could not read " + npcpos + ": " + failureMessage(unreadable), unreadable);
            }
        }
    }

    public synchronized void start(
            Path clientRoot,
            Path folder,
            GeoFormat format,
            GeoTile near,
            @Nullable Collection<GeoTile> only,
            boolean replace,
            @Nullable PathNodes pathNodes) {
        start(clientRoot, WorldVariant.MAIN, folder, format, near, only, replace, pathNodes);
    }

    public synchronized void start(
            Path clientRoot,
            WorldVariant variant,
            Path folder,
            GeoFormat format,
            GeoTile near,
            @Nullable Collection<GeoTile> only,
            boolean replace,
            @Nullable PathNodes pathNodes) {
        start(clientRoot, variant, folder, format, near, only, replace, pathNodes, StartSources.NONE);
    }

    public synchronized void start(
            Path clientRoot,
            WorldVariant variant,
            Path folder,
            GeoFormat format,
            GeoTile near,
            @Nullable Collection<GeoTile> only,
            boolean replace,
            @Nullable PathNodes pathNodes,
            StartSources startSources) {
        if (progress.running()) {
            return;
        }
        stop = false;
        var started = System.nanoTime();
        progress = new Progress(
                folder, true, 0, 0, null, "Finding the client's maps", 0, 0, started, started, false, 0, "", false, -1);
        thread = Thread.ofPlatform()
                .daemon()
                .name("geodata-build")
                .start(() -> run(
                        clientRoot,
                        variant,
                        folder,
                        format,
                        near,
                        only == null ? null : Set.copyOf(only),
                        replace,
                        format == GeoFormat.L2OFF ? pathNodes : null,
                        startSources));
    }

    public synchronized void rebuildPathNodes(Path folder, @Nullable ZoneTable table, boolean backup) {
        if (progress.running()) {
            return;
        }
        stop = false;
        var started = System.nanoTime();
        progress = new Progress(
                folder,
                true,
                0,
                GRAPH_ONLY,
                null,
                "Checking the regions",
                0,
                0,
                started,
                started,
                false,
                0,
                "",
                true,
                -1);
        thread = Thread.ofPlatform().daemon().name("geodata-pathnode").start(() -> {
            try {
                var regions = pathNodes(folder, table, backup, true);
                progress = progress.ended(false, "", regions);
            } catch (CancellationException stopped) {
                progress = progress.ended(true, "", -1);
            } catch (Exception | OutOfMemoryError failure) {
                if (stop && failure instanceof InterruptedIOException) {
                    progress = progress.ended(true, "", -1);
                    return;
                }
                LogManager.getLogger(GeodataBuildJob.class)
                        .warn("Pathnode rebuild failed: {}", failure.toString(), failure);
                progress = progress.ended(false, failureMessage(failure), -1);
            }
        });
    }

    public void stop() {
        stop = true;
    }

    public Progress getProgress() {
        return progress;
    }

    public Map<GeoTile, String> getFailures() {
        return Map.copyOf(failures);
    }

    public int takeWritten() {
        return written.getAndSet(0);
    }

    @Override
    public void close() {
        stop();
        var running = thread;
        if (running != null) {
            try {
                running.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int pathNodes(Path folder, @Nullable ZoneTable table, boolean backup, boolean always) throws IOException {
        progress = progress.graph("Checking the pathnode graph", 0);
        if (!always) {
            var status = GeoPathNodeFolder.check(folder, () -> stop);
            if (status.state() == State.CURRENT && (table == null || table == status.table())) {
                return 0;
            }
        }
        var reported = new long[] {0};
        var result = GeoPathNodeFolder.rebuild(
                folder,
                table,
                backup,
                LocalDateTime.now(),
                (done, total, detail) -> {
                    if (stop) {
                        throw new CancellationException();
                    }
                    var now = System.nanoTime();
                    if (now - reported[0] >= 50_000_000L || done == total) {
                        reported[0] = now;
                        progress = progress.graph(detail, total > 0 ? Math.min(1, (double) done / total) : 0);
                    }
                },
                () -> stop);
        written.incrementAndGet();
        return result.regions();
    }

    private ExtraStartPoints startPoints(StartSources sources) throws IOException {
        if (sources.npcpos() != null) {
            progress = new Progress(
                    progress.folder(),
                    true,
                    0,
                    0,
                    null,
                    "Reading npcpos.txt",
                    0,
                    0,
                    progress.startedNanos(),
                    progress.regionNanos(),
                    false,
                    0,
                    "",
                    false,
                    -1);
        }
        return sources.read();
    }

    private void run(
            Path clientRoot,
            WorldVariant variant,
            Path folder,
            GeoFormat format,
            GeoTile near,
            @Nullable Set<GeoTile> only,
            boolean replace,
            @Nullable PathNodes pathNodes,
            StartSources startSources) {
        var started = progress.startedNanos();
        failures.clear();
        var count = new int[2];
        try {
            var tiles = planRegions(
                    ClientGeodataBuild.findRegions(clientRoot, variant, near),
                    only,
                    tile -> Files.isRegularFile(folder.resolve(format.getFileName(tile))),
                    replace);
            var threads = ClientGeodataBuild.defaultThreads();
            var build = new ClientGeodataBuild(clientRoot, variant, folder, format, threads, startPoints(startSources));
            var table =
                    pathNodes == null ? null : pathNodes.table() != null ? pathNodes.table() : followedTable(folder);
            if (table != null) {
                build.setLastIndexedTileY(table.lastTileY);
            }
            progress = new Progress(
                    folder, true, 0, tiles.size(), null, "", 0, 0, started, started, false, count[1], "", false, -1);
            build.run(
                    tiles,
                    replace,
                    new Listener() {
                        @Override
                        public void started(GeoTile tile, int index, int total) {
                            var last = progress;
                            progress = new Progress(
                                    folder,
                                    true,
                                    index,
                                    total,
                                    tile,
                                    "Reading the map",
                                    0,
                                    last.written(),
                                    started,
                                    last.regionNanos(),
                                    false,
                                    count[1],
                                    "",
                                    false,
                                    -1);
                        }

                        @Override
                        public void progress(GeoTile tile, String stage, double fraction) {
                            var last = progress;
                            progress = new Progress(
                                    folder,
                                    true,
                                    last.done(),
                                    last.total(),
                                    tile,
                                    stage,
                                    fraction,
                                    last.written(),
                                    started,
                                    last.regionNanos(),
                                    false,
                                    count[1],
                                    "",
                                    false,
                                    -1);
                        }

                        @Override
                        public void failed(GeoTile tile, Exception failure) {
                            count[1]++;
                            failures.put(tile, failureMessage(failure));
                            finished(tile, null);
                        }

                        @Override
                        public void finished(GeoTile tile, @Nullable Path file) {
                            var last = progress;
                            if (file != null) {
                                count[0]++;
                                written.incrementAndGet();
                            }
                            progress = new Progress(
                                    folder,
                                    true,
                                    last.done() + 1,
                                    last.total(),
                                    tile,
                                    "",
                                    0,
                                    count[0],
                                    started,
                                    System.nanoTime(),
                                    false,
                                    count[1],
                                    "",
                                    false,
                                    -1);
                        }
                    },
                    () -> stop);
            var graphRegions = -1;
            if (pathNodes != null) {
                build.writeIndex();
                graphRegions = pathNodes(folder, table, false, false);
            }
            var last = progress;
            progress = new Progress(
                    folder,
                    false,
                    last.total(),
                    last.total(),
                    null,
                    "",
                    0,
                    count[0],
                    started,
                    last.regionNanos(),
                    false,
                    count[1],
                    "",
                    false,
                    graphRegions);
        } catch (CancellationException stopped) {
            var last = progress;
            progress = new Progress(
                    folder,
                    false,
                    last.done(),
                    last.total(),
                    null,
                    "",
                    0,
                    count[0],
                    started,
                    last.regionNanos(),
                    true,
                    count[1],
                    "",
                    false,
                    -1);
        } catch (Exception | OutOfMemoryError failure) {
            if (stop && failure instanceof InterruptedIOException) {
                progress = progress.ended(true, "", -1);
                return;
            }
            LogManager.getLogger(GeodataBuildJob.class).warn("Geodata build failed: {}", failure.toString(), failure);
            var last = progress;
            progress = new Progress(
                    folder,
                    false,
                    last.done(),
                    last.total(),
                    last.tile(),
                    "",
                    0,
                    count[0],
                    started,
                    last.regionNanos(),
                    false,
                    count[1],
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                    last.graph(),
                    -1);
        }
    }

    private static @Nullable ZoneTable followedTable(Path folder) {
        try {
            return PathNodeGraph.zoneTable(folder).orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String failureMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }
}
