package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.unreal.UStaticMeshInstance;
import org.jetbrains.annotations.Nullable;

public record NativeStaticMeshLightingInputs(
        int lod,
        boolean acceptedInstance,
        boolean beastInstance,
        int beastUvCount,
        boolean rootEnvironmentEntry,
        @Nullable String rootEnvironmentSource) {
    public NativeStaticMeshLightingInputs {
        if (lod < 0 || lod > 2) {
            throw new IllegalArgumentException("LOD must be in [0,2]");
        }
        if (beastUvCount < 0) {
            throw new IllegalArgumentException("negative Beast UV count");
        }
        if (beastInstance && !acceptedInstance) {
            throw new IllegalArgumentException("Beast requires an accepted instance");
        }
        if (!rootEnvironmentEntry && rootEnvironmentSource != null) {
            throw new IllegalArgumentException("root source requires an environment entry");
        }
    }

    static NativeStaticMeshLightingInputs capture(
            int lod,
            UStaticMesh mesh,
            @Nullable UStaticMeshInstance accepted,
            @Nullable UStaticMeshInstance root,
            boolean lightmapsEnabled) {
        var uv = mesh.getBeastLightmapUvStream();
        var environment = root == null ? null : root.getEnvironmentLights();
        var entry = environment != null && environment.length != 0;
        return new NativeStaticMeshLightingInputs(
                lod,
                accepted != null,
                accepted != null && accepted.usesBeastLighting(lightmapsEnabled),
                uv == null ? 0 : uv.getUvs().length,
                entry,
                entry ? environment[0].getLightName() : null);
    }

    public boolean hasBeastUv() {
        return beastInstance && beastUvCount != 0;
    }

    public NativeStaticMeshLightingFeatures selectFeatures(
            boolean modulate2x, boolean useTangent, boolean setLightCalled) {
        return NativeStaticMeshLightingFeatures.select(
                modulate2x, acceptedInstance, useTangent, hasBeastUv(), setLightCalled);
    }
}
