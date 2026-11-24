package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.render.LoadedTiles;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class ClientFloorBackfill implements AutoCloseable {
    static final int REACH = 2;

    private final Function<String, @Nullable ClientFloorGrid> reader;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "client-floor-backfill");
        thread.setDaemon(true);
        return thread;
    });
    private final Queue<Read> finished = new ConcurrentLinkedQueue<>();
    private final Set<String> failed = new HashSet<>();
    private @Nullable String reading;
    private @Nullable Looked idle;

    ClientFloorBackfill(Function<String, @Nullable ClientFloorGrid> reader) {
        this.reader = reader;
    }

    private record Read(String map, @Nullable ClientFloorGrid grid) {}

    private record Looked(long tiles, long floors, int tileX, int tileY) {}

    private static final class MapReader implements Function<String, @Nullable ClientFloorGrid> {
        private final Path client;
        private final WorldVariant variant;
        private @Nullable UnrealLoader loader;

        MapReader(Path client, WorldVariant variant) {
            this.client = client;
            this.variant = variant;
        }

        @Override
        public @Nullable ClientFloorGrid apply(String name) {
            if (loader == null) {
                loader = new UnrealLoader(client, new Options(false, 0), true);
                loader.worldVariant(variant);
            }
            try {
                var map = loader.loadMap(name);
                return map == null ? null : ClientFloorGrid.build(map, () -> false);
            } finally {
                loader.trimCaches();
            }
        }
    }

    public static ClientFloorBackfill of(Path client, WorldVariant variant) {
        return new ClientFloorBackfill(new MapReader(client, variant));
    }

    public void tick(boolean wanted, LoadedTiles tiles, ClientFloors floors, double eyeX, double eyeY) {
        var read = finished.poll();
        while (read != null) {
            reading = null;
            if (read.grid() == null) {
                failed.add(read.map());
            } else if (tiles.has(read.map()) && !floors.holds(read.map())) {
                floors.put(read.grid());
            }
            read = finished.poll();
        }
        if (!wanted || reading != null) {
            return;
        }
        var tileX = Math.floorDiv((int) Math.floor(eyeX), LoadedTiles.SIZE) + 20;
        var tileY = Math.floorDiv((int) Math.floor(eyeY), LoadedTiles.SIZE) + 18;
        var looked = new Looked(tiles.getRevision(), floors.getRevision(), tileX, tileY);
        if (looked.equals(idle)) {
            return;
        }
        var nearest = findNearest(tiles, floors, tileX, tileY);
        if (nearest == null) {
            idle = looked;
            return;
        }
        idle = null;
        reading = nearest;
        worker.execute(() -> {
            ClientFloorGrid grid = null;
            try {
                grid = reader.apply(nearest);
            } catch (RuntimeException | LinkageError failure) {
                LogManager.getLogger(ClientFloorBackfill.class)
                        .warn("The floors of map {} could not be read: {}", nearest, failure.toString());
            } finally {
                finished.add(new Read(nearest, grid));
            }
        });
    }

    public boolean isReading() {
        return reading != null;
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    private @Nullable String findNearest(LoadedTiles tiles, ClientFloors floors, int tileX, int tileY) {
        String nearest = null;
        var best = Integer.MAX_VALUE;
        for (var dx = -REACH; dx <= REACH; dx++) {
            for (var dy = -REACH; dy <= REACH; dy++) {
                var away = dx * dx + dy * dy;
                var name = (tileX + dx) + "_" + (tileY + dy);
                if (away >= best || !tiles.has(name) || failed.contains(name)) {
                    continue;
                }
                var middleX = (tileX + dx - 20 + .5) * LoadedTiles.SIZE;
                var middleY = (tileY + dy - 18 + .5) * LoadedTiles.SIZE;
                if (!floors.covers(middleX, middleY)) {
                    nearest = name;
                    best = away;
                }
            }
        }
        return nearest;
    }
}
