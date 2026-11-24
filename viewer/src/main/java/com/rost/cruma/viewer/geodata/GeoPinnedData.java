package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoDataSnapshot.RegionState;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import java.util.LinkedHashMap;
import java.util.Objects;

public final class GeoPinnedData {
    public static final int MAX_REGIONS = 4;
    public static final long MAX_BYTES = 512L * 1024 * 1024;

    private GeoDataSnapshot published;
    private int retainedRegions;
    private long retainedBytes;
    private GeoDataSnapshot lastVisible;
    private Selection lastSelected;
    private Selection lastA;
    private Selection lastB;
    private Selection lastSeed;

    public record Publication(GeoDataSnapshot data, Selection surfaceSeed, String message) {}

    public Publication update(GeoDataSnapshot visible, GeoInspection inspection, Selection surfaceSeed) {
        if (visible == null) {
            clear();
            inspection.update(null);
            return new Publication(null, null, "");
        }
        if (visible == lastVisible
                && Objects.equals(inspection.selected, lastSelected)
                && Objects.equals(inspection.pointA, lastA)
                && Objects.equals(inspection.pointB, lastB)
                && Objects.equals(surfaceSeed, lastSeed)) {
            return new Publication(published, surfaceSeed, "");
        }
        var sameSource = published == null
                || published.getSourceEpoch() == visible.getSourceEpoch()
                        && published.getSourceId().equals(visible.getSourceId());
        var requested = new Selection[] {inspection.selected, inspection.pointA, inspection.pointB, surfaceSeed};
        var valid = new Selection[requested.length];
        var retained = new LinkedHashMap<GeoTile, RegionState>();
        var dropped = false;
        var bytes = 0L;
        for (var index = 0; index < requested.length; index++) {
            var selection = requested[index];
            if (selection == null) {
                continue;
            }
            var origin = published != null && selection.ref().belongsTo(published) ? published : visible;
            if (!sameSource || GeoInspection.resolve(origin, selection) == null) {
                dropped = true;
                continue;
            }
            var tile = GeoTile.fromWorld(selection.ref().x(), selection.ref().y());
            var original = origin.getState(tile);
            var incoming = visible.getTiles().get(tile);
            if (incoming != null
                    && (incoming.region() == null
                            || incoming.region() != original.region()
                                    && !incoming.region()
                                            .getFingerprint()
                                            .equals(original.region().getFingerprint()))) {
                dropped = true;
                continue;
            }
            if (incoming == null && !retained.containsKey(tile)) {
                var nextBytes = bytes + original.region().getEstimatedBytes();
                if (retained.size() == MAX_REGIONS || nextBytes > MAX_BYTES) {
                    dropped = true;
                    continue;
                }
                retained.put(tile, original);
                bytes = nextBytes;
            }
            valid[index] = selection;
        }
        var tiles = new LinkedHashMap<>(visible.getTiles());
        tiles.putAll(retained);
        var next = published != null && sameSource && published.getTiles().equals(tiles)
                ? published
                : visible.withTiles(tiles);
        inspection.selected = rebase(valid[0], next);
        inspection.pointA = rebase(valid[1], next);
        inspection.pointB = rebase(valid[2], next);
        var seed = rebase(valid[3], next);
        var message = dropped
                ? "A selected source region changed, became unavailable or exceeded the pinned-region budget. Select"
                        + " the affected layer again."
                : "";
        if (dropped) {
            inspection.message = message;
        }
        published = next;
        retainedRegions = retained.size();
        retainedBytes = bytes;
        lastVisible = visible;
        lastSelected = inspection.selected;
        lastA = inspection.pointA;
        lastB = inspection.pointB;
        lastSeed = seed;
        return new Publication(next, seed, message);
    }

    public GeoDataSnapshot getSnapshot() {
        return published;
    }

    public int getRetainedRegions() {
        return retainedRegions;
    }

    public long getRetainedBytes() {
        return retainedBytes;
    }

    public void clear() {
        published = null;
        retainedRegions = 0;
        retainedBytes = 0;
        lastVisible = null;
        lastSelected = null;
        lastA = null;
        lastB = null;
        lastSeed = null;
    }

    private static Selection rebase(Selection selection, GeoDataSnapshot data) {
        if (selection == null || selection.ref().belongsTo(data)) {
            return selection;
        }
        var ref = selection.ref();
        return new Selection(
                new GeoCellRef(data.getSourceId(), data.revision(), ref.x(), ref.y(), ref.layer()),
                selection.height(),
                selection.nswe());
    }
}
