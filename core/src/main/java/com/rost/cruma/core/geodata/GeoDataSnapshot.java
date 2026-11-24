package com.rost.cruma.core.geodata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GeoDataSnapshot implements GeoDataAccess {
    private static final RegionState NOT_LOADED = new RegionState(Availability.NOT_LOADED, null, "Region not loaded");
    private static final RegionState OUTSIDE =
            new RegionState(Availability.OUTSIDE_WORLD, null, "Outside the server world");
    private final Path sourcePath;
    private final long revision;
    private final long sourceEpoch;
    private final Map<GeoTile, RegionState> tiles;

    public GeoDataSnapshot(Path sourcePath, long revision, Map<GeoTile, RegionState> tiles) {
        this(sourcePath, revision, revision, tiles);
    }

    public GeoDataSnapshot(Path sourcePath, long revision, long sourceEpoch, Map<GeoTile, RegionState> tiles) {
        this.sourcePath =
                sourcePath == null ? null : sourcePath.toAbsolutePath().normalize();
        this.revision = revision;
        this.sourceEpoch = sourceEpoch;
        this.tiles = Map.copyOf(tiles);
    }

    public record RegionState(Availability availability, GeoDataRegion region, String detail) {
        public RegionState {
            Objects.requireNonNull(availability, "availability");
            detail = detail == null ? "" : detail;
            if ((availability == Availability.AVAILABLE) != (region != null)) {
                throw new IllegalArgumentException("Only an available state contains a region");
            }
        }
    }

    public long getSourceEpoch() {
        return sourceEpoch;
    }

    @Override
    public long getContentRevision() {
        return sourceEpoch;
    }

    public GeoDataSnapshot withTiles(Map<GeoTile, RegionState> regions) {
        return tiles.equals(regions)
                ? this
                : new GeoDataSnapshot(sourcePath, GeoDataRepository.nextRevision(), sourceEpoch, regions);
    }

    public Map<GeoTile, RegionState> getTiles() {
        return tiles;
    }

    public GeoDataRegion getRegion(int tileX, int tileY) {
        return getState(new GeoTile(tileX, tileY)).region();
    }

    public RegionState getState(GeoTile tile) {
        if (!tile.containsWorld()) {
            return OUTSIDE;
        }
        return tiles.getOrDefault(tile, NOT_LOADED);
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public GeoFormat getFormat() {
        for (var state : tiles.values()) {
            if (state.region() != null && state.region().getFormat() != GeoFormat.L2OFF) {
                return state.region().getFormat();
            }
        }
        return GeoFormat.L2OFF;
    }

    public long getEstimatedBytes() {
        var total = 0L;
        for (var state : tiles.values()) {
            if (state.region() != null) {
                total += state.region().getEstimatedBytes();
            }
        }
        return total;
    }

    public Map<GeoTile, String> getFingerprints() {
        var result = new HashMap<GeoTile, String>();
        for (var entry : tiles.entrySet()) {
            if (entry.getValue().region() != null) {
                result.put(entry.getKey(), entry.getValue().region().getFingerprint());
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public Cell cellAt(int worldX, int worldY) {
        var x = Math.floorDiv(worldX, GeoDataRegion.CELL_SIZE) * GeoDataRegion.CELL_SIZE;
        var y = Math.floorDiv(worldY, GeoDataRegion.CELL_SIZE) * GeoDataRegion.CELL_SIZE;
        var tile = GeoTile.fromWorld(x, y);
        var state = getState(tile);
        if (state.region() == null) {
            return new Cell(x, y, state.availability(), List.of(), state.detail());
        }
        var region = state.region();
        var localX = (x - tile.worldMinX()) / GeoDataRegion.CELL_SIZE;
        var localY = (y - tile.worldMinY()) / GeoDataRegion.CELL_SIZE;
        var count = region.getLayerCount(localX, localY);
        var layers = new ArrayList<Layer>(count);
        for (var index = 0; index < count; index++) {
            layers.add(
                    new Layer(index, region.getHeight(localX, localY, index), region.getNswe(localX, localY, index)));
        }
        return new Cell(x, y, Availability.AVAILABLE, layers, "", region.getStorage(localX, localY));
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public String getSourceId() {
        return sourcePath == null ? "" : sourcePath.toString();
    }
}
