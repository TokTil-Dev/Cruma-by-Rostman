package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoConnectedSurface.Result;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import java.util.Objects;

public final class GeoSurfaceFilter {
    private final Result surface;
    private final boolean dimOthers;
    private final GeoDataSnapshot displayData;
    private volatile GeoDataSnapshot checkedDisplay;

    public GeoSurfaceFilter(Result surface, boolean dimOthers) {
        this(surface, dimOthers, null);
    }

    private GeoSurfaceFilter(Result surface, boolean dimOthers, GeoDataSnapshot displayData) {
        this.surface = Objects.requireNonNull(surface);
        this.dimOthers = dimOthers;
        this.displayData = displayData;
    }

    public Result getSurface() {
        return surface;
    }

    public boolean shouldDimOthers() {
        return dimOthers;
    }

    public boolean belongsTo(GeoDataAccess data) {
        if (data == null) {
            return false;
        }
        if (surface.belongsTo(data) || data == displayData || data == checkedDisplay) {
            return true;
        }
        if (displayData != null
                && data instanceof GeoDataSnapshot snapshot
                && hasSameDisplayIdentity(snapshot, displayData)
                && snapshot.getTiles().equals(displayData.getTiles())) {
            checkedDisplay = snapshot;
            return true;
        }
        return false;
    }

    public GeoSurfaceFilter forDisplay(GeoDataSnapshot raw, GeoDataSnapshot query) {
        if (raw == null
                || query == null
                || !surface.belongsTo(query)
                || !raw.getSourceId().equals(query.getSourceId())
                || raw.getSourceEpoch() != query.getSourceEpoch()) {
            return null;
        }
        for (var entry : raw.getTiles().entrySet()) {
            if (!entry.getValue().equals(query.getTiles().get(entry.getKey()))) {
                return null;
            }
        }
        return raw == query ? this : new GeoSurfaceFilter(surface, dimOthers, raw);
    }

    public float getAlpha(GeoDataAccess data, Cell cell, Layer layer) {
        if (!belongsTo(data) || surface.contains(cell, layer)) {
            return 1;
        }
        return dimOthers ? .12f : 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeoSurfaceFilter filter
                && surface.equals(filter.surface)
                && dimOthers == filter.dimOthers
                && (displayData == filter.displayData
                        || displayData != null
                                && filter.displayData != null
                                && hasSameDisplayIdentity(displayData, filter.displayData)
                                && displayData.getTiles().equals(filter.displayData.getTiles()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                surface,
                dimOthers,
                displayData == null ? null : displayData.getSourceId(),
                displayData == null ? 0 : displayData.revision(),
                displayData == null ? 0 : displayData.getSourceEpoch());
    }

    private static boolean hasSameDisplayIdentity(GeoDataSnapshot a, GeoDataSnapshot b) {
        return a.revision() == b.revision()
                && a.getSourceEpoch() == b.getSourceEpoch()
                && a.getSourceId().equals(b.getSourceId());
    }
}
