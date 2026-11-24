package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataSnapshot.RegionState;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class GeoDataRepository {
    private static final AtomicLong REVISIONS = new AtomicLong();
    private final int maxRegions;
    private final long maxBytes;
    private final Reader reader;
    private final LinkedHashMap<GeoTile, RegionState> cache = new LinkedHashMap<>(16, .75f, true);
    private Path sourcePath;
    private long generation;
    private long revision = nextRevision();
    private long sourceEpoch = revision;
    private long bytes;
    private Set<GeoTile> snapshotTiles = Set.of();

    public GeoDataRepository(Path sourcePath) {
        this(sourcePath, 16, 256L * 1024 * 1024);
    }

    public GeoDataRepository(Path sourcePath, int maxRegions, long maxBytes) {
        this(sourcePath, maxRegions, maxBytes, GeoDataReader::read);
    }

    public GeoDataRepository(Path sourcePath, Reader reader) {
        this(sourcePath, 16, 256L * 1024 * 1024, reader);
    }

    public GeoDataRepository(Path sourcePath, int maxRegions, long maxBytes, Reader reader) {
        if (maxRegions < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("Geodata cache limits must be positive");
        }
        this.maxRegions = maxRegions;
        this.maxBytes = maxBytes;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.sourcePath = normalize(sourcePath);
    }

    @FunctionalInterface
    public interface Reader {
        GeoDataRegion read(Path path) throws IOException;
    }

    static long nextRevision() {
        return REVISIONS.incrementAndGet();
    }

    public synchronized void setSource(Path path) {
        var normalized = normalize(path);
        if (!Objects.equals(sourcePath, normalized)) {
            sourcePath = normalized;
            refresh();
        }
    }

    public synchronized void refresh() {
        cache.clear();
        bytes = 0;
        generation++;
        revision = nextRevision();
        sourceEpoch = revision;
    }

    public synchronized void forgetMissing() {
        if (cache.values().removeIf(state -> state.availability() == Availability.MISSING)) {
            revision = nextRevision();
        }
    }

    public GeoDataSnapshot loadRegion(int tileX, int tileY) {
        loadTile(new GeoTile(tileX, tileY));
        return snapshot();
    }

    public GeoDataSnapshot loadBounds(int minWorldX, int minWorldY, int maxWorldX, int maxWorldY) {
        if (minWorldX > maxWorldX || minWorldY > maxWorldY) {
            throw new IllegalArgumentException("Reversed geodata bounds");
        }
        if (minWorldX == maxWorldX || minWorldY == maxWorldY) {
            return snapshot();
        }
        var min = GeoTile.fromWorld(minWorldX, minWorldY);
        var max = GeoTile.fromWorld(maxWorldX - 1, maxWorldY - 1);
        long token;
        synchronized (this) {
            token = generation;
        }
        for (var x = Math.max(10, min.x()); x <= Math.min(29, max.x()); x++) {
            for (var y = Math.max(10, min.y()); y <= Math.min(26, max.y()); y++) {
                synchronized (this) {
                    if (token != generation || Thread.currentThread().isInterrupted()) {
                        return snapshot();
                    }
                }
                loadTile(new GeoTile(x, y));
            }
        }
        synchronized (this) {
            if (token != generation) {
                return snapshot();
            }
            var requested = new LinkedHashMap<GeoTile, RegionState>();
            for (var x = Math.max(10, min.x()); x <= Math.min(29, max.x()); x++) {
                for (var y = Math.max(10, min.y()); y <= Math.min(26, max.y()); y++) {
                    var tile = new GeoTile(x, y);
                    var state = cache.get(tile);
                    requested.put(
                            tile,
                            state != null
                                    ? state
                                    : new RegionState(
                                            Availability.NOT_LOADED,
                                            null,
                                            sourcePath == null
                                                    ? "No geodata source selected"
                                                    : "Requested bounds exceed cache limits"));
                }
            }
            return snapshotOf(requested);
        }
    }

    public synchronized GeoDataSnapshot snapshot() {
        return snapshotOf(cache);
    }

    public synchronized Path getSourcePath() {
        return sourcePath;
    }

    public synchronized long getRevision() {
        return revision;
    }

    public synchronized long getCachedBytes() {
        return bytes;
    }

    private GeoDataSnapshot snapshotOf(Map<GeoTile, RegionState> tiles) {
        if (!snapshotTiles.equals(tiles.keySet())) {
            snapshotTiles = Set.copyOf(tiles.keySet());
            revision = nextRevision();
        }
        return new GeoDataSnapshot(sourcePath, revision, sourceEpoch, tiles);
    }

    private void loadTile(GeoTile tile) {
        if (!tile.containsWorld()) {
            return;
        }
        Path path;
        long token;
        synchronized (this) {
            if (sourcePath == null || cache.get(tile) != null) {
                return;
            }
            var found = GeoFormat.find(sourcePath, tile);
            path = found != null ? found : sourcePath.resolve(tile.fileName());
            token = generation;
        }
        RegionState result;
        try {
            var region = reader.read(path);
            if (region.getTileX() != tile.x() || region.getTileY() != tile.y()) {
                throw new IOException("Region header does not match " + path.getFileName());
            }
            if (region.getEstimatedBytes() > maxBytes) {
                result = new RegionState(Availability.NOT_LOADED, null, "Region exceeds the cache memory limit");
            } else {
                region.getFingerprint();
                result = new RegionState(Availability.AVAILABLE, region, "");
            }
        } catch (NoSuchFileException exception) {
            result = new RegionState(
                    Availability.MISSING,
                    null,
                    "Missing " + tile.x() + "_" + tile.y() + " (no _conv.dat or .l2j file)");
        } catch (IOException | SecurityException exception) {
            result = new RegionState(Availability.CORRUPT, null, exception.getMessage());
        }
        synchronized (this) {
            if (token == generation && !cache.containsKey(tile)) {
                cache.put(tile, result);
                bytes += size(result);
                evict();
                revision = nextRevision();
            }
        }
    }

    private void evict() {
        var loaded =
                cache.values().stream().filter(state -> state.region() != null).count();
        var entries = cache.entrySet().iterator();
        while ((loaded > maxRegions || bytes > maxBytes) && entries.hasNext()) {
            var state = entries.next().getValue();
            if (state.region() == null) {
                continue;
            }
            bytes -= size(state);
            loaded--;
            entries.remove();
        }
    }

    private static long size(RegionState state) {
        return state.region() == null ? 0 : state.region().getEstimatedBytes();
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
