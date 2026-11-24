package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.Entity.SurfaceType;

public record KeptLayers(boolean terrain, boolean staticMeshes, boolean csg) {
    public static final KeptLayers NONE = new KeptLayers(false, false, false);

    private static final KeptLayers[] ALL = new KeptLayers[8];

    static {
        for (var bits = 0; bits < ALL.length; bits++) {
            ALL[bits] = new KeptLayers((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0);
        }
    }

    public static KeptLayers of(boolean terrain, boolean staticMeshes, boolean csg) {
        return ALL[(terrain ? 1 : 0) | (staticMeshes ? 2 : 0) | (csg ? 4 : 0)];
    }

    public boolean hasAny() {
        return terrain || staticMeshes || csg;
    }

    public boolean keeps(long surfaceType) {
        if ((surfaceType & SurfaceType.TERRAIN.getId()) != 0) {
            return terrain;
        }
        if ((surfaceType & SurfaceType.CSG.getId()) != 0) {
            return csg;
        }
        return staticMeshes;
    }
}
