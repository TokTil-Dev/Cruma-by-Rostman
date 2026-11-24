package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.application.UiContext.Rendering;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ViewSettingsSnapshot(
        SceneElements scene,
        Visibility visibility,
        Lighting lighting,
        Atmosphere atmosphere,
        Effects effects,
        Water water) {
    public static ViewSettingsSnapshot capture(Rendering settings) {
        return new ViewSettingsSnapshot(
                SceneElements.capture(settings),
                Visibility.capture(settings),
                Lighting.capture(settings),
                Atmosphere.capture(settings),
                Effects.capture(settings),
                Water.capture(settings));
    }

    public void apply(Rendering settings) {
        scene.apply(settings);
        visibility.apply(settings);
        lighting.apply(settings);
        atmosphere.apply(settings);
        effects.apply(settings);
        water.apply(settings);
    }

    public Map<String, Object> values() {
        var result = new LinkedHashMap<String, Object>();
        result.put("textures", scene.textures());
        result.put("terrain", scene.terrain());
        result.put("staticMeshes", scene.staticMeshes());
        result.put("csg", scene.csg());
        result.put("passable", scene.passable());
        result.put("culling", visibility.culling());
        result.put("staticMeshesDistCulling", visibility.staticMeshesDistCulling());
        result.put("staticMeshesCullingDist", visibility.staticMeshesCullingDist());
        result.put("viewDistance", visibility.viewDistance());
        result.put("terrainDetailPixels", visibility.terrainDetailPixels());
        result.put("impostorDistanceMultiplier", visibility.impostorDistanceMultiplier());
        result.put("grassDistanceMultiplier", visibility.grassDistanceMultiplier());
        result.put("zNear", visibility.zNear());
        result.put("customLighting", lighting.customLighting());
        result.put("customLightingModel", lighting.customLightingModel());
        result.put("customGiStrength", lighting.customGiStrength());
        result.put("customAuthoredShadowStrength", lighting.customAuthoredShadowStrength());
        result.put("customProtectAuthoredInteriors", lighting.customProtectAuthoredInteriors());
        result.put("customSunShadows", lighting.customSunShadows());
        result.put("customLocalLightShadows", lighting.customLocalLightShadows());
        result.put("customRayTracedSunReference", lighting.customRayTracedSunReference());
        result.put("debugTint", scene.debugTint());
        result.put("timeOfDay", atmosphere.timeOfDay());
        result.put("skyPreset", atmosphere.skyPreset());
        result.put("moonVariant", atmosphere.moonVariant());
        result.put("realSkySunSize", atmosphere.realSkySunSize());
        result.put("fogStrength", atmosphere.fogStrength());
        result.put("displayGamma", effects.displayGamma());
        result.put("bloom", effects.bloom());
        result.put("particleVisibilityDistance", effects.particleVisibilityDistance());
        result.put("emitterDistanceMode", effects.emitterDistanceMode());
        result.put("l2WaterShader", water.l2WaterShader());
        result.put("waterBackTex", water.waterBackTex());
        result.put("l2WaterEffectType", water.l2WaterEffectType());
        return Collections.unmodifiableMap(result);
    }

    public record SceneElements(
            boolean textures, boolean terrain, boolean staticMeshes, boolean csg, boolean passable, boolean debugTint) {
        private void apply(Rendering settings) {
            settings.textures = textures;
            settings.terrain = terrain;
            settings.staticMeshes = staticMeshes;
            settings.csg = csg;
            settings.passable = passable;
            settings.debugTint = debugTint;
        }

        private static SceneElements capture(Rendering settings) {
            return new SceneElements(
                    settings.textures,
                    settings.terrain,
                    settings.staticMeshes,
                    settings.csg,
                    settings.passable,
                    settings.debugTint);
        }
    }

    public record Visibility(
            boolean culling,
            boolean staticMeshesDistCulling,
            int staticMeshesCullingDist,
            int viewDistance,
            float terrainDetailPixels,
            float impostorDistanceMultiplier,
            float grassDistanceMultiplier,
            int zNear) {
        private void apply(Rendering settings) {
            settings.culling = culling;
            settings.staticMeshesDistCulling = staticMeshesDistCulling;
            settings.staticMeshesCullingDist.set(staticMeshesCullingDist);
            settings.viewDistance.set(viewDistance);
            settings.terrainDetailPixels.set(terrainDetailPixels);
            settings.impostorDistanceMultiplier.set(impostorDistanceMultiplier);
            settings.grassDistanceMultiplier.set(grassDistanceMultiplier);
            settings.zNear.set(zNear);
        }

        private static Visibility capture(Rendering settings) {
            return new Visibility(
                    settings.culling,
                    settings.staticMeshesDistCulling,
                    settings.staticMeshesCullingDist.get(),
                    settings.viewDistance.get(),
                    settings.terrainDetailPixels.get(),
                    settings.impostorDistanceMultiplier.get(),
                    settings.grassDistanceMultiplier.get(),
                    settings.zNear.get());
        }
    }

    public record Lighting(
            boolean customLighting,
            int customLightingModel,
            float customGiStrength,
            float customAuthoredShadowStrength,
            boolean customProtectAuthoredInteriors,
            boolean customSunShadows,
            boolean customLocalLightShadows,
            boolean customRayTracedSunReference) {
        private void apply(Rendering settings) {
            settings.customLighting = customLighting;
            settings.customLightingModel.set(customLightingModel);
            settings.customGiStrength.set(customGiStrength);
            settings.customAuthoredShadowStrength.set(customAuthoredShadowStrength);
            settings.customProtectAuthoredInteriors = customProtectAuthoredInteriors;
            settings.customSunShadows = customSunShadows;
            settings.customLocalLightShadows = customLocalLightShadows;
            settings.customRayTracedSunReference = customRayTracedSunReference;
        }

        private static Lighting capture(Rendering settings) {
            return new Lighting(
                    settings.customLighting,
                    settings.customLightingModel.get(),
                    settings.customGiStrength.get(),
                    settings.customAuthoredShadowStrength.get(),
                    settings.customProtectAuthoredInteriors,
                    settings.customSunShadows,
                    settings.customLocalLightShadows,
                    settings.customRayTracedSunReference);
        }
    }

    public record Atmosphere(float timeOfDay, int skyPreset, int moonVariant, float realSkySunSize, float fogStrength) {
        private void apply(Rendering settings) {
            settings.timeOfDay.set(timeOfDay);
            settings.skyPreset.set(skyPreset);
            settings.moonVariant.set(moonVariant);
            settings.realSkySunSize.set(realSkySunSize);
            settings.fogStrength.set(fogStrength);
        }

        private static Atmosphere capture(Rendering settings) {
            return new Atmosphere(
                    settings.timeOfDay.get(),
                    settings.skyPreset.get(),
                    settings.moonVariant.get(),
                    settings.realSkySunSize.get(),
                    settings.fogStrength.get());
        }
    }

    public record Effects(float displayGamma, boolean bloom, int particleVisibilityDistance, int emitterDistanceMode) {
        private void apply(Rendering settings) {
            settings.displayGamma.set(displayGamma);
            settings.bloom = bloom;
            settings.particleVisibilityDistance.set(particleVisibilityDistance);
            settings.emitterDistanceMode.set(emitterDistanceMode);
        }

        private static Effects capture(Rendering settings) {
            return new Effects(
                    settings.displayGamma.get(),
                    settings.bloom,
                    settings.particleVisibilityDistance.get(),
                    settings.emitterDistanceMode.get());
        }
    }

    public record Water(boolean l2WaterShader, boolean waterBackTex, int l2WaterEffectType) {
        private void apply(Rendering settings) {
            settings.l2WaterShader = l2WaterShader;
            settings.waterBackTex = waterBackTex;
            settings.l2WaterEffectType.set(l2WaterEffectType);
        }

        private static Water capture(Rendering settings) {
            return new Water(settings.l2WaterShader, settings.waterBackTex, settings.l2WaterEffectType.get());
        }
    }
}
