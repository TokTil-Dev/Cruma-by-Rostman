package com.rost.cruma.core.world;

import com.rost.cruma.core.world.Entity.MultiTextureEntry;
import java.util.ArrayList;
import java.util.List;

public record TerrainZoneLayers(List<ZoneRender> zones, int textured) {
    public TerrainZoneLayers {
        zones = List.copyOf(zones);
    }

    public static TerrainZoneLayers of(List<MultiTextureEntry> entries) {
        var zones = new ArrayList<ZoneRender>(entries.size());
        var textured = 0;
        for (var layer = 0; layer < entries.size(); layer++) {
            zones.add(entries.get(layer).zoneRender());
            if (layer < 32 && entries.get(layer).texture() != null) {
                textured |= 1 << layer;
            }
        }
        return new TerrainZoneLayers(zones, textured);
    }

    public boolean hasZoneLayers() {
        for (var zone : zones) {
            if (zone.mask() != 0) {
                return true;
            }
        }
        return false;
    }

    public int findHiddenLayers(ZoneStateChoice choice) {
        var hidden = 0;
        for (var layer = 0; layer < Math.min(32, zones.size()); layer++) {
            if (!zones.get(layer).shows(choice)) {
                hidden |= 1 << layer;
            }
        }
        return hidden;
    }

    public int choiceIn(ZoneStateChoice choice) {
        for (var zone : zones) {
            if (zone.mask() != 0) {
                return zone.choiceIn(choice);
            }
        }
        return MapEventState.AS_THE_MAP;
    }

    public int findHiddenLayers(int choice) {
        var hidden = 0;
        for (var layer = 0; layer < Math.min(32, zones.size()); layer++) {
            if (!zones.get(layer).shows(choice)) {
                hidden |= 1 << layer;
            }
        }
        return hidden;
    }

    public int findBaseLayer(int hidden) {
        if ((hidden & 1) == 0) {
            return 0;
        }
        var shown = textured & ~hidden;
        return shown == 0 ? 0 : Integer.numberOfTrailingZeros(shown);
    }
}
