package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.SkyZones;

public interface RenderSettings {
    boolean isTexturesEnabled();

    boolean isCullingEnabled();

    boolean isStaticMeshesDistCullingEnabled();

    int getStaticMeshesCullingDist();

    int getViewDistance();

    float getTerrainDetailPixels();

    float getImpostorDistanceMultiplier();

    float getGrassDistanceMultiplier();

    float getDecorationFadeMultiplier();

    boolean isPassableEnabled();

    boolean isTerrainEnabled();

    boolean isStaticMeshesEnabled();

    boolean isCsgEnabled();

    boolean isObjectPickerEnabled();

    int getObjectPickMaxDist();

    int getParticleVisibilityDistance();

    int getEmitterDistanceMode();

    MapEventState getGameEventStates();

    boolean isBloomEnabled();

    float getDisplayGamma();

    boolean isCustomLightingEnabled();

    int getCustomLightingModel();

    float getCustomGiStrength();

    float getCustomAuthoredShadowStrength();

    float getFogStrength();

    boolean isCustomProtectAuthoredInteriorsEnabled();

    boolean isCustomSunShadowsEnabled();

    boolean isCustomLocalLightShadowsEnabled();

    boolean isCustomRayTracedSunReferenceEnabled();

    boolean isDebugTintEnabled();

    boolean isWaterBackTexEnabled();

    boolean isL2WaterShaderEnabled();

    int getL2WaterEffectType();

    float getTimeOfDay();

    SkyZones.View getSkyZone();

    void setSkyZone(SkyZones.View view);

    float getRealSkySunSize();

    int getMoonVariant();

    int getSkyPreset();

    int getZNear();

    float getImpostorDistanceRatio();

    float getTerrainDetailThresholdPixels();
}
