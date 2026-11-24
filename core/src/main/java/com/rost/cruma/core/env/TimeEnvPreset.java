package com.rost.cruma.core.env;

import com.rost.cruma.core.world.MaterialClient;
import glm_.vec3.Vec3;
import org.jetbrains.annotations.Nullable;

public record TimeEnvPreset(
        int envType,
        TimeColorKey[] skyBox,
        TimeColorKey[] haze,
        TimeColorKey[] cloud1,
        TimeColorKey[] cloud2,
        TimeColorKey[] cloud3,
        TimeColorKey[] sunColor,
        TimeColorKey[] moonColor,
        TimeScaleKey[] sunScale,
        TimeScaleKey[] moonScale,
        TimeColorKey[] terrainAmbient,
        TimeColorKey[] actorAmbient,
        TimeColorKey[] staticMeshAmbient,
        TimeColorKey[] bspAmbient,
        TimeHsvKey[] hsvTerrainLight,
        TimeHsvKey[] hsvActorLight,
        TimeHsvKey[] hsvStaticMeshLight,
        TimeHsvKey[] hsvBspLight,
        TimeScaleKey @Nullable [] beastLightmap1,
        TimeScaleKey @Nullable [] beastLightmap2,
        TimeColorKey[][] moreClouds) {
    public boolean hasBeastWeights() {
        return beastLightmap1 != null && beastLightmap2 != null;
    }

    public ResolvedEnv resolve(float hour) {
        return resolve(hour, MaterialClient.UNKNOWN);
    }

    public ResolvedEnv resolve(float hour, MaterialClient client) {
        return new ResolvedEnv(
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, skyBox)),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, haze)),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, cloud1)),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, cloud2)),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, cloud3)),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, sunColor)),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, moonColor)),
                TimeEnvColor.interpScalar(hour, sunScale),
                TimeEnvColor.interpScalar(hour, moonScale),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, terrainAmbient)),
                TimeEnvColor.toVec3(TimeEnvColor.interpHsvColor(hour, hsvTerrainLight)),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, actorAmbient)),
                client == MaterialClient.UNKNOWN
                        ? TimeEnvColor.toVec3(TimeEnvColor.interpHsvColor(hour, hsvActorLight))
                        : TimeEnvHsv.interpColor(hour, hsvActorLight),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, staticMeshAmbient)),
                client == MaterialClient.UNKNOWN
                        ? TimeEnvColor.toVec3(TimeEnvColor.interpHsvDynamicLightColor(hour, hsvStaticMeshLight))
                        : TimeEnvHsv.interpColor(hour, hsvStaticMeshLight),
                TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, bspAmbient)),
                TimeEnvColor.toVec3(TimeEnvColor.interpHsvColor(hour, hsvBspLight)),
                beastLightmap1 == null ? 0f : TimeEnvColor.interpScalar(hour, beastLightmap1),
                beastLightmap2 == null ? 0f : TimeEnvColor.interpScalar(hour, beastLightmap2),
                resolveClouds(hour));
    }

    private Vec3[] resolveClouds(float hour) {
        var result = new Vec3[moreClouds.length];
        for (var i = 0; i < moreClouds.length; i++) {
            result[i] = TimeEnvColor.toVec3(TimeEnvColor.interpRgb(hour, moreClouds[i]));
        }
        return result;
    }
}
