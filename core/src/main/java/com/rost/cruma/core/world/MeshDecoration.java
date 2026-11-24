package com.rost.cruma.core.world;

import org.jetbrains.annotations.Nullable;

public record MeshDecoration(int layer, @Nullable ZoneRender layerRender) {
    public static final float MARKER = 6f;

    public MeshDecoration {
        if (layer < 0) {
            throw new IllegalArgumentException("A decoration layer has an index from 0");
        }
    }

    public static @Nullable ZoneRender render(
            int meshMask, int layerMask, int zoneStart, boolean essence, boolean inZone, String map) {
        var mesh = meshMask == 0 ? ZoneRender.EVERY_STATE : new ZoneRender(meshMask, zoneStart, essence, map);
        if (!essence || layerMask == 0 || !inZone) {
            return mesh;
        }
        var mask = meshMask == 0 ? layerMask : meshMask & layerMask;
        return mask == 0 ? null : new ZoneRender(mask, zoneStart, true, map);
    }
}
