package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainG16Codec.Mip;
import com.rost.cruma.core.editing.TerrainHeightField;
import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainPackageSnapshot;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.editing.GroundStick.Terrain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

public final class GroundProbe implements AutoCloseable, Terrain {
    private final Path clientRoot;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("editing-ground").factory());
    private final ConcurrentHashMap<String, CompletableFuture<List<Field>>> saved = new ConcurrentHashMap<>();
    private volatile List<Field> open = List.of();
    private volatile List<Field> openSaved = List.of();
    private final Map<String, short[]> openSamples = new HashMap<>();
    private volatile Set<String> loadedMaps = Set.of();

    public GroundProbe(Path clientRoot) {
        this.clientRoot = clientRoot;
    }

    public record Shown(
            String owner,
            short[] samples,
            Info info,
            @Nullable Mip saved) {}

    record Field(String owner, TerrainHeightField heights, Coords toHeightmap) {
        @Nullable
        Float heightAt(double x, double y) {
            var point = toHeightmap.transform((float) x, (float) y, 0);
            if (!(point.x() >= 0
                    && point.y() >= 0
                    && point.x() <= heights.getWidth() - 1
                    && point.y() <= heights.getHeight() - 1)) {
                return null;
            }
            return heights.getWorldZ(point.x(), point.y());
        }
    }

    public void setLoadedMaps(Collection<String> maps) {
        loadedMaps = Set.copyOf(maps);
    }

    public String getMapAt(double x, double y) {
        return WorldTile.mapAt(x, y, loadedMaps);
    }

    public void setOpen(@Nullable String owner, short @Nullable [] samples, @Nullable Info info, @Nullable Mip saved) {
        setOpen(
                owner == null || samples == null || info == null
                        ? List.of()
                        : List.of(new Shown(owner, samples, info, saved)));
    }

    public void setOpen(List<Shown> shown) {
        var fields = open;
        var same = fields.size() == shown.size();
        for (var index = 0; same && index < shown.size(); index++) {
            var each = shown.get(index);
            same = fields.get(index).owner().equals(each.owner()) && openSamples.get(each.owner()) == each.samples();
        }
        if (same) {
            return;
        }
        var savedFields = openSaved;
        var nextOpen = new ArrayList<Field>(shown.size());
        var nextSaved = new ArrayList<Field>(shown.size());
        var nextSamples = new HashMap<String, short[]>();
        for (var each : shown) {
            var owner = each.owner();
            var info = each.info();
            var old = byOwner(fields, owner);
            nextOpen.add(
                    old != null && openSamples.get(owner) == each.samples()
                            ? old
                            : new Field(
                                    owner,
                                    new TerrainHeightField(info.width(), info.height(), each.samples(), info.toWorld()),
                                    info.toHeightmap()));
            nextSamples.put(owner, each.samples());
            if (each.saved() != null) {
                var oldSaved = byOwner(savedFields, owner);
                nextSaved.add(
                        oldSaved != null
                                ? oldSaved
                                : new Field(
                                        owner,
                                        new TerrainHeightField(each.saved(), info.toWorld()),
                                        info.toHeightmap()));
            }
        }
        openSamples.clear();
        openSamples.putAll(nextSamples);
        open = List.copyOf(nextOpen);
        openSaved = List.copyOf(nextSaved);
    }

    @Override
    public @Nullable Float getCurrent(double x, double y) {
        var height = find(open, x, y);
        return height != null ? height : find(fields(getMapAt(x, y), false), x, y);
    }

    @Override
    public @Nullable Float getSaved(double x, double y) {
        var height = find(openSaved, x, y);
        return height != null ? height : find(fields(getMapAt(x, y), false), x, y);
    }

    @Override
    public boolean isReady(double x, double y) {
        return find(openSaved, x, y) != null || fields(getMapAt(x, y), false) != null;
    }

    public Snapshot snapshot() {
        return new Snapshot(this, open, openSaved);
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    public static final class Snapshot {
        private final GroundProbe probe;
        private final List<Field> open;
        private final List<Field> openSaved;

        private Snapshot(GroundProbe probe, List<Field> open, List<Field> openSaved) {
            this.probe = probe;
            this.open = open;
            this.openSaved = openSaved;
        }

        public @Nullable Float getCurrent(double x, double y) {
            var height = find(open, x, y);
            return height != null ? height : getSaved(x, y);
        }

        public @Nullable Float getSaved(double x, double y) {
            var height = find(openSaved, x, y);
            return height != null ? height : find(probe.fields(probe.getMapAt(x, y), true), x, y);
        }
    }

    private @Nullable List<Field> fields(String map, boolean wait) {
        var future = saved.computeIfAbsent(map, name -> CompletableFuture.supplyAsync(() -> read(name), worker));
        if (wait) {
            return future.join();
        }
        return future.isDone() ? future.join() : null;
    }

    private List<Field> read(String map) {
        try (var files = Files.list(clientRoot.resolve("Maps"))) {
            var file = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(map + ".unr"))
                    .findFirst()
                    .orElse(null);
            if (file == null) {
                return List.of();
            }
            var snapshot = TerrainPackageSnapshot.open(file, clientRoot);
            var result = new ArrayList<Field>();
            for (var terrain : snapshot.getTerrains()) {
                if (terrain.heightmap().isEmpty()) {
                    continue;
                }
                try {
                    var heights = new TerrainHeightField(
                            terrain.heightmap().getFirst(), terrain.info().toWorld());
                    result.add(new Field(terrain.path(), heights, terrain.info().toHeightmap()));
                } catch (RuntimeException unreadable) {
                }
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException failure) {
            return List.of();
        }
    }

    private static @Nullable Field byOwner(List<Field> fields, String owner) {
        for (var field : fields) {
            if (field.owner().equals(owner)) {
                return field;
            }
        }
        return null;
    }

    private static @Nullable Float find(@Nullable List<Field> fields, double x, double y) {
        if (fields == null) {
            return null;
        }
        for (var field : fields) {
            var height = field.heightAt(x, y);
            if (height != null) {
                return height;
            }
        }
        return null;
    }
}
