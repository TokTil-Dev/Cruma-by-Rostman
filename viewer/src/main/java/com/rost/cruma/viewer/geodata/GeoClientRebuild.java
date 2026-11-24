package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoDataSnapshot.RegionState;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild.Listener;
import com.rost.cruma.core.geodata.generation.ExtraStartPoints;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.StartSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class GeoClientRebuild implements AutoCloseable {
    static final int KEPT = 2;

    private final @Nullable Path client;
    private final WorldVariant variant;
    private final RegionBuilder builder;
    private final LinkedHashMap<GeoTile, GeoDataSnapshot> regions = new LinkedHashMap<>();
    private volatile Status status = Status.IDLE;
    private volatile boolean stop;
    private @Nullable Thread thread;
    private @Nullable GeoTile next;
    private @Nullable GeoTile failed;
    private StartSources sources = StartSources.NONE;

    private @Nullable StartSources readSources;
    private long readModified;
    private ExtraStartPoints readPoints = ExtraStartPoints.NONE;

    public GeoClientRebuild(@Nullable Path client) {
        this(client, WorldVariant.MAIN);
    }

    public GeoClientRebuild(@Nullable Path client, WorldVariant variant) {
        this(
                client,
                variant,
                (root, map, tile, points, listener, cancelled) -> ClientGeodataBuild.buildRegion(
                        root, map, tile, ClientGeodataBuild.defaultThreads(), points, listener, cancelled));
    }

    GeoClientRebuild(@Nullable Path client, WorldVariant variant, RegionBuilder builder) {
        this.client = client;
        this.variant = variant;
        this.builder = builder;
    }

    @FunctionalInterface
    interface RegionBuilder {
        @Nullable
        GeoDataRegion build(
                Path client,
                WorldVariant variant,
                GeoTile tile,
                ExtraStartPoints points,
                Listener listener,
                BooleanSupplier cancelled)
                throws IOException;
    }

    public record Status(boolean running, @Nullable GeoTile tile, String stage, double fraction, String problem) {
        static final Status IDLE = new Status(false, null, "", 0, "");
    }

    public boolean isAvailable() {
        return client != null;
    }

    public synchronized @Nullable GeoDataSnapshot getBuiltRegion(GeoTile tile) {
        var kept = regions.get(tile);
        if (kept != null) {
            return kept;
        }
        request(tile);
        return null;
    }

    public synchronized void request(GeoTile tile) {
        if (client == null
                || regions.containsKey(tile)
                || tile.equals(failed)
                || thread != null && tile.equals(status.tile())) {
            return;
        }
        if (thread != null) {
            next = tile;
            return;
        }
        start(tile);
    }

    public Status getStatus() {
        return status;
    }

    public synchronized void startSources(StartSources next) {
        if (next.equals(sources)) {
            return;
        }
        sources = next;
        regions.clear();
        failed = null;
    }

    public synchronized StartSources getStartSources() {
        return sources;
    }

    public synchronized void cancel() {
        stop = true;
        next = null;
    }

    @Override
    public void close() {
        cancel();
    }

    private void start(GeoTile tile) {
        stop = false;
        status = new Status(true, tile, "Reading the map", 0, "");
        var root = Objects.requireNonNull(client);
        var used = sources;
        thread = Thread.ofPlatform().daemon().name("geodata-rebuild").start(() -> build(root, tile, used));
    }

    private void build(Path root, GeoTile tile, StartSources used) {
        GeoDataRegion region = null;
        var problem = "";
        try {
            region = builder.build(
                    root,
                    variant,
                    tile,
                    startPoints(tile, used),
                    new Listener() {
                        @Override
                        public void started(GeoTile started, int index, int total) {}

                        @Override
                        public void progress(GeoTile working, String stage, double fraction) {
                            status = new Status(true, working, stage, fraction, "");
                        }

                        @Override
                        public void finished(GeoTile done, @Nullable Path file) {}
                    },
                    () -> stop);
            if (region == null) {
                problem = "The client has no map to build " + tile.x() + "_" + tile.y() + " from.";
            }
        } catch (CancellationException stopped) {
            problem = "Stopped.";
        } catch (Exception | OutOfMemoryError failure) {
            LogManager.getLogger(GeoClientRebuild.class)
                    .warn("Could not build region {}_{}: {}", tile.x(), tile.y(), failure.toString(), failure);
            problem = "Could not build "
                    + tile.x()
                    + "_"
                    + tile.y()
                    + ": "
                    + (failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
        synchronized (this) {
            var current = used.equals(sources);
            if (region != null && current) {
                if (regions.size() >= KEPT) {
                    regions.remove(regions.keySet().iterator().next());
                }
                regions.put(
                        tile,
                        new GeoDataSnapshot(
                                null,
                                System.nanoTime(),
                                Map.of(
                                        tile,
                                        new RegionState(Availability.AVAILABLE, region, "Built from the client"))));
                failed = null;
            } else if (region == null && current && !problem.equals("Stopped.")) {
                failed = tile;
            }
            status = new Status(false, tile, "", 1, problem);
            thread = null;
            var waiting = next;
            next = null;
            if (waiting != null && !regions.containsKey(waiting)) {
                start(waiting);
            }
        }
    }

    private ExtraStartPoints startPoints(GeoTile tile, StartSources used) throws IOException {
        var modified = modified(used.npcpos());
        if (!used.equals(readSources) || modified != readModified) {
            if (used.npcpos() != null) {
                status = new Status(true, tile, "Reading npcpos.txt", 0, "");
            }
            readPoints = used.read();
            readSources = used;
            readModified = modified;
        }
        return readPoints;
    }

    private static long modified(@Nullable Path file) {
        if (file == null) {
            return 0;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unreadable) {
            return -1;
        }
    }
}
