package com.rost.cruma.viewer.application;

import com.rost.cruma.core.world.EmitterDistanceMode;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.viewer.config.AudioSettings;
import com.rost.cruma.viewer.config.AudioSettings.Values;
import com.rost.cruma.viewer.config.Config.Viewer.World.Music;
import com.rost.cruma.viewer.config.Config.Viewer.World.Sound;
import com.rost.cruma.viewer.config.Config.Viewer.World.WorldRendering;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.ParticleSystem;
import com.rost.cruma.viewer.render.api.RenderSettings;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.api.RenderTelemetry;
import com.rost.cruma.viewer.render.api.WorldLoadProgress;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.Nullable;

public class UiContext {
    public boolean returnToMenuRequested;
    public @Nullable String sceneAtStart;
    public boolean copyAtStart;
    public @Nullable Path fileAtStart;
    public @Nullable Scene sceneFromFile;
    public @Nullable SceneRecording recording;
    public @Nullable SceneRequest sceneRequest;

    public boolean agentViewer;

    public boolean exportHereRequested;

    public boolean exportNpcRequested;

    public boolean effectsOnly;

    public boolean dialogOpen;

    public @Nullable Path configDirectory;

    public List<String> loadedMaps = List.of();

    public IntSupplier automaticScalePercent = () -> 100;

    public boolean settingsReloadRequested;

    public boolean directorShown;

    public boolean directorPlaying;

    public boolean sceneHoldsCamera;

    public @Nullable Path scenesFolder;

    public final Screenshots screenshots = new Screenshots();
    public boolean worldMapOpen;
    public boolean editingPrepared;
    public boolean editingReloadRequested;
    public Mode workspaceMode;

    public final Rendering rendering = new Rendering();
    public final Geodata geodata = new Geodata();

    public static class Rendering implements RenderSettings {
        public final RenderTelemetry stats = new RenderTelemetry();
        public final WorldLoadProgress loading = new WorldLoadProgress();
        public final RenderState state = new RenderState(this, stats, loading);

        public final ImFloat cameraSpeed = new ImFloat(3000f);
        public float mouseSensitivity = 1f;
        public int frameRateCap = 144;
        public boolean gpuProfiling;
        public boolean textures;
        public boolean culling;
        public boolean staticMeshesDistCulling;
        public ImInt staticMeshesCullingDist = new ImInt(100000);
        public ImInt viewDistance = new ImInt(200000);
        public final ImFloat terrainDetailPixels = new ImFloat(200f);
        public final ImFloat impostorDistanceMultiplier = new ImFloat(8f);
        public final ImFloat grassDistanceMultiplier = new ImFloat(1f);
        private float loadedImpostorDistanceMultiplier = 8f;
        private float decorationFadeMultiplier = 10f;

        public boolean passable;
        public boolean terrain;
        public boolean staticMeshes;
        public boolean csg;
        public boolean objectPicker;
        public int agentDepthFrames;
        public int agentPickFrames;
        public ImInt objectPickMaxDist = new ImInt(30000);
        public final ImInt particleVisibilityDistance = new ImInt(ParticleSystem.DEFAULT_VISIBILITY_DISTANCE);
        public final ImInt emitterDistanceMode = new ImInt(EmitterDistanceMode.CUSTOM.ordinal());
        public MapEventState gameEventStates = MapEventState.ALL_SHOWN;
        public boolean bloom;
        public final ImFloat displayGamma = new ImFloat(1.0f);
        public boolean customLighting;
        public final ImInt customLightingModel = new ImInt(0);
        public final ImFloat customGiStrength = new ImFloat(1f);

        public final ImFloat customAuthoredShadowStrength = new ImFloat(45f);
        public final ImFloat fogStrength = new ImFloat(100f);
        public boolean customProtectAuthoredInteriors = true;
        public boolean customSunShadows = true;
        public boolean customLocalLightShadows = true;
        public boolean customRayTracedSunReference;

        public boolean debugTint;
        public boolean waterBackTex;
        public boolean l2WaterShader;
        public final ImInt l2WaterEffectType = new ImInt(0);

        public final ImFloat timeOfDay = new ImFloat();
        private volatile SkyZones.View skyZone = SkyZones.View.NONE;
        public boolean musicEnabled;
        public final ImFloat musicVolume = new ImFloat();
        public final ImFloat musicWaitSeconds = new ImFloat();
        public final ImFloat musicIdleWaitSeconds = new ImFloat();
        public final ImFloat musicFadeSeconds = new ImFloat();
        public boolean soundEnabled;
        public final ImFloat soundVolume = new ImFloat();
        public final ImInt soundMaxVoices = new ImInt();
        public final ImFloat realSkySunSize = new ImFloat();
        public final ImInt moonVariant = new ImInt(0);
        public final ImInt skyPreset = new ImInt(0);
        public ImInt zNear = new ImInt(1);
        public boolean orthoCutTop;
        public final int[] orthoTop = {0};
        public boolean orthoCutBottom;
        public final int[] orthoBottom = {-1000};
        public boolean orthoKeepTerrain;
        public boolean orthoKeepStaticMeshes;
        public boolean orthoKeepCsg;
        public boolean orthoCircle;
        public final ImInt orthoCircleX = new ImInt();
        public final ImInt orthoCircleY = new ImInt();
        public final int[] orthoCircleRadius = {4000};
        public final OrthoSlice frontSlice = new OrthoSlice();
        public final OrthoSlice sideSlice = new OrthoSlice();
        public boolean orthoGrid;
        public int orthoGridDensity = 1;
        public int orthoGridStep;

        public OrthoSlice getSlice(CameraView view) {
            return switch (view) {
                case FRONT -> frontSlice;
                case SIDE -> sideSlice;
                default -> throw new IllegalArgumentException(view + " has no slice of its own");
            };
        }

        public boolean isSliced(CameraView view) {
            return switch (view) {
                case TOP -> orthoCutTop || orthoCutBottom;
                case FRONT, SIDE -> getSlice(view).on();
                case PERSPECTIVE -> false;
            };
        }

        public float getImpostorDistanceRatio() {
            var value = impostorDistanceMultiplier.get();
            return (Float.isFinite(value) ? Math.clamp(value, 1f, 20f) : loadedImpostorDistanceMultiplier)
                    / loadedImpostorDistanceMultiplier;
        }

        @Override
        public float getGrassDistanceMultiplier() {
            var value = grassDistanceMultiplier.get();
            return Float.isFinite(value) ? Math.clamp(value, 1f, 20f) : 1f;
        }

        @Override
        public float getDecorationFadeMultiplier() {
            return decorationFadeMultiplier;
        }

        public float getTerrainDetailThresholdPixels() {
            var value = terrainDetailPixels.get();
            return Float.isFinite(value) ? Math.max(0f, value) : 200f;
        }

        public boolean isEffectiveGlobalIllumination() {
            return state.isGlobalIlluminationEffective();
        }

        public boolean isEffectiveCustomLighting() {
            return state.isCustomLightingEffective();
        }

        public boolean isEffectiveCustomRealisticLighting() {
            return state.isCustomRealisticLightingEffective();
        }

        @Override
        public boolean isTexturesEnabled() {
            return textures;
        }

        @Override
        public boolean isCullingEnabled() {
            return culling;
        }

        @Override
        public boolean isStaticMeshesDistCullingEnabled() {
            return staticMeshesDistCulling;
        }

        @Override
        public int getStaticMeshesCullingDist() {
            return staticMeshesCullingDist.get();
        }

        @Override
        public int getViewDistance() {
            return viewDistance.get();
        }

        @Override
        public float getTerrainDetailPixels() {
            return terrainDetailPixels.get();
        }

        @Override
        public float getImpostorDistanceMultiplier() {
            return impostorDistanceMultiplier.get();
        }

        @Override
        public boolean isPassableEnabled() {
            return passable;
        }

        @Override
        public boolean isTerrainEnabled() {
            return terrain;
        }

        @Override
        public boolean isStaticMeshesEnabled() {
            return staticMeshes;
        }

        @Override
        public boolean isCsgEnabled() {
            return csg;
        }

        @Override
        public boolean isObjectPickerEnabled() {
            return objectPicker || agentDepthFrames > 0;
        }

        @Override
        public int getObjectPickMaxDist() {
            return objectPickMaxDist.get();
        }

        @Override
        public int getParticleVisibilityDistance() {
            return particleVisibilityDistance.get();
        }

        @Override
        public int getEmitterDistanceMode() {
            return emitterDistanceMode.get();
        }

        @Override
        public MapEventState getGameEventStates() {
            return gameEventStates;
        }

        @Override
        public boolean isBloomEnabled() {
            return bloom;
        }

        @Override
        public float getDisplayGamma() {
            return displayGamma.get();
        }

        @Override
        public boolean isCustomLightingEnabled() {
            return customLighting;
        }

        @Override
        public int getCustomLightingModel() {
            return customLightingModel.get();
        }

        @Override
        public float getCustomGiStrength() {
            return customGiStrength.get();
        }

        @Override
        public float getCustomAuthoredShadowStrength() {
            return customAuthoredShadowStrength.get();
        }

        @Override
        public float getFogStrength() {
            return fogStrength.get();
        }

        @Override
        public boolean isCustomProtectAuthoredInteriorsEnabled() {
            return customProtectAuthoredInteriors;
        }

        @Override
        public boolean isCustomSunShadowsEnabled() {
            return customSunShadows;
        }

        @Override
        public boolean isCustomLocalLightShadowsEnabled() {
            return customLocalLightShadows;
        }

        @Override
        public boolean isCustomRayTracedSunReferenceEnabled() {
            return customRayTracedSunReference;
        }

        @Override
        public boolean isDebugTintEnabled() {
            return debugTint;
        }

        @Override
        public boolean isWaterBackTexEnabled() {
            return waterBackTex;
        }

        @Override
        public boolean isL2WaterShaderEnabled() {
            return l2WaterShader;
        }

        @Override
        public int getL2WaterEffectType() {
            return l2WaterEffectType.get();
        }

        @Override
        public float getTimeOfDay() {
            var zone = skyZone;
            return zone.hasTime() ? zone.hour() : timeOfDay.get();
        }

        @Override
        public SkyZones.View getSkyZone() {
            return skyZone;
        }

        @Override
        public void setSkyZone(SkyZones.View view) {
            skyZone = view == null ? SkyZones.View.NONE : view;
        }

        @Override
        public float getRealSkySunSize() {
            return realSkySunSize.get();
        }

        @Override
        public int getMoonVariant() {
            return moonVariant.get();
        }

        @Override
        public int getSkyPreset() {
            return skyPreset.get();
        }

        @Override
        public int getZNear() {
            return zNear.get();
        }

        public void setSoundDefaults(Sound sound) {
            soundEnabled = sound.enabled();
            soundVolume.set((float) (sound.volume() * 100.0));
            soundMaxVoices.set(sound.maxVoices());
        }

        public void setMusicDefaults(Music music) {
            musicEnabled = music.enabled();
            musicVolume.set((float) (music.volume() * 100.0));
            musicWaitSeconds.set((float) music.waitSeconds());
            musicIdleWaitSeconds.set((float) music.idleWaitSeconds());
            musicFadeSeconds.set((float) music.fadeSeconds());
        }

        public Values getAudioValues() {
            return new Values(
                    musicEnabled,
                    musicVolume.get(),
                    musicWaitSeconds.get(),
                    musicIdleWaitSeconds.get(),
                    musicFadeSeconds.get(),
                    soundEnabled,
                    soundVolume.get(),
                    soundMaxVoices.get());
        }

        public void setDefaults(WorldRendering worldRenderingConfig) {
            culling = true;
            textures = true;
            terrain = true;
            passable = true;
            staticMeshes = true;
            csg = true;
            bloom = worldRenderingConfig.bloomEnabled();
            displayGamma.set((float) worldRenderingConfig.displayGamma());
            customLighting = worldRenderingConfig.customLightingEnabled();
            customLightingModel.set(0);
            customGiStrength.set(1f);
            stats.customGiActive = false;
            stats.customGiStatus = "Off";
            stats.customGiGpuMillis = 0f;
            stats.customGiBatches = 0;
            stats.customGiUpdateGpuMillis = stats.customGiBlend = 0f;
            stats.customGiMaterialBytes = 0;
            stats.customGiPreparedGeometries = 0;
            customAuthoredShadowStrength.set((float) worldRenderingConfig.customAuthoredShadowStrength());
            fogStrength.set(100f);
            cameraSpeed.set(3000f);
            customProtectAuthoredInteriors = true;
            stats.customLightingSuppressedByAuthoredInterior = false;
            customSunShadows = true;
            customLocalLightShadows = true;
            customRayTracedSunReference = false;
            l2WaterShader = false;
            waterBackTex = false;
            l2WaterEffectType.set(0);
            timeOfDay.set((float) worldRenderingConfig.timeOfDay());
            realSkySunSize.set((float) worldRenderingConfig.realSkySunSize());
            staticMeshesDistCulling = worldRenderingConfig.staticMeshDistanceCullingEnabled();
            staticMeshesCullingDist.set(worldRenderingConfig.staticMeshCullingDistance());
            viewDistance.set(worldRenderingConfig.viewDistance());
            terrainDetailPixels.set(
                    (float) worldRenderingConfig.graphics().tuning().visibilityTerrainDetailPixels());
            loadedImpostorDistanceMultiplier = worldRenderingConfig.impostorDistanceMultiplier();
            impostorDistanceMultiplier.set(loadedImpostorDistanceMultiplier);
            grassDistanceMultiplier.set(worldRenderingConfig.grassDistanceMultiplier());
            decorationFadeMultiplier = (float) worldRenderingConfig.terrainDecorationFadeDistanceMultiplier();
            particleVisibilityDistance.set(ParticleSystem.DEFAULT_VISIBILITY_DISTANCE);
            emitterDistanceMode.set(worldRenderingConfig.emitterDistanceMode().ordinal());
            gameEventStates = worldRenderingConfig.mapEvents();
            frameRateCap = worldRenderingConfig.graphics().frameRateCap();
        }
    }

    public static class Geodata {
        public float actorHeight;
        public float actorRadius;
        public float maxWalkableAngle;
        public float minWalkableClimb;
        public float maxWalkableClimb;
        public float cellSize;
        public float cellHeight;

        public void setDefaults() {
            actorHeight = 48.0f;
            actorRadius = 16.0f;
            maxWalkableAngle = 45.5f;
            minWalkableClimb = 2.0f;
            maxWalkableClimb = 16.0f;
            cellSize = 16.0f;
            cellHeight = 1.0f;
        }
    }
}
