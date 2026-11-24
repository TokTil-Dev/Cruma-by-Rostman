package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.UProperty.Type;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.EmitterTimeWindows;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class UEmitter {
    private static final Logger log = LogManager.getLogger(UEmitter.class);
    private static final Vec3 ZERO = new Vec3(0f, 0f, 0f);
    private static final Vec3 ONE = new Vec3(1f, 1f, 1f);

    public abstract static class UParticleEmitter extends UObject {
        private int maxParticles = 10;
        private boolean automaticInitialSpawning = true;
        private float initialParticlesPerSecond = 0f;
        private float particlesPerSecond = 0f;
        private boolean respawnDeadParticles = true;
        private boolean forcedMaxParticles = false;
        private boolean disabled = false;

        private float lifetimeMin = 4f, lifetimeMax = 4f;
        private float initialTimeMin = 0f, initialTimeMax = 0f;
        private float initialDelayMin = 0f, initialDelayMax = 0f;
        private boolean useRevolution = false;
        private Vec3 revolutionsPerSecondMin = new Vec3(), revolutionsPerSecondMax = new Vec3();
        private Vec3 revolutionCenterOffsetMin = new Vec3(), revolutionCenterOffsetMax = new Vec3();
        private int addLocationFromOtherEmitter = -1;
        private boolean useSubdivisionScale = false;
        private final float[] subdivisionScale = new float[16];
        private int subdivisionScaleCount = 0;
        private boolean useRevolutionScale = false;
        private final List<float[]> revolutionScale = new ArrayList<>();
        private int revolutionScaleRepeats = 0;
        private boolean forcedLifeTime = false;
        private boolean forcedFade = false;
        private boolean weatherSoundCheck = false;
        private boolean checkLevelOfWeather = false;
        private EmitterTimeWindows weatherRangeTime = EmitterTimeWindows.ALWAYS;
        private boolean destroyWhenHitTarget = false;
        private boolean resetAfterChange = false;

        private Vec3 startLocationOffset = ZERO;
        private Vec3 startLocationMin = ZERO, startLocationMax = ZERO;
        private int startLocationShape = 0;
        private float sphereRadiusMin = 0f, sphereRadiusMax = 0f;
        private Vec3 startLocationPolarMin = ZERO, startLocationPolarMax = ZERO;

        private int useRotationFrom = 0;
        private URotator rotationOffset = new URotator(0, 0, 0);
        private Vec3 rotationNormal = ZERO;

        private Vec3 startVelocityMin = ZERO, startVelocityMax = ZERO;
        private Vec3 acceleration = ZERO;
        private Vec3 maxAbsVelocity = ZERO;
        private Vec3 velocityLossMin = ZERO, velocityLossMax = ZERO;
        private int getVelocityDirectionFrom = 0;
        private float startVelocityRadialMin = 0f, startVelocityRadialMax = 0f;

        private Vec3 startSizeMin = ONE, startSizeMax = ONE;
        private boolean startSizeAuthored = false;
        private boolean uniformSize = false;
        private boolean useRegularSizeScale = false;
        private boolean useSizeScale = false;
        private int sizeScaleRepeats = 0;
        private boolean useAbsoluteTimeForSizeScale = false;
        private final List<float[]> sizeScale = new ArrayList<>();

        private boolean spinParticles = false;
        private Vec3 startSpinMin = ZERO, startSpinMax = ZERO;
        private Vec3 spinsPerSecondMin = ZERO, spinsPerSecondMax = ZERO;
        private Vec3 spinCcwOrCw = ZERO;

        private boolean fadeIn = false;
        private float fadeInEndTime = 0f;
        private boolean fadeOut = false;
        private float fadeOutStartTime = 0f;
        private Vec4 fadeInFactor = new Vec4(1f);
        private Vec4 fadeOutFactor = new Vec4(1f);

        private float opacity = 1f;
        private Vec3 colorMultiplierMin = ONE, colorMultiplierMax = ONE;
        private final List<float[]> colorScale = new ArrayList<>();
        private boolean useColorScale;
        private int colorScaleRepeats;
        private int drawStyle = 3;

        private boolean alphaTest = true;
        private int alphaRef;
        private boolean zTest = true;
        private boolean zWrite;
        private boolean disableFogging;
        private boolean useSoftParticle;
        private float softParticleFadeDist = 100f;

        private int textureUSubdivisions = 1;
        private int textureVSubdivisions = 1;
        private int subdivisionStart = 0;
        private int subdivisionEnd = 0;
        private boolean useRandomSubdivision = false;

        private float relativeWarmupTime = 0f;
        private float warmupTicksPerSecond = 10f;
        private int coordinateSystem = 1;
        private boolean coordinateSystemAuthored;

        private int[] customMaterialRefs;

        protected UParticleEmitter(UArchive archive) {
            super(archive);
        }

        public List<UObject> getCustomMaterials() {
            if (customMaterialRefs == null || customMaterialRefs.length == 0) {
                return List.of();
            }
            var materials = new ArrayList<UObject>(customMaterialRefs.length);
            var loader = archive.getObjectLoader();
            for (var ref : customMaterialRefs) {
                materials.add(ref != 0 ? loader.loadObject(ref) : null);
            }
            return materials;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "MaxParticles" -> {
                    maxParticles = property.getInt32Value();
                    yield true;
                }
                case "AutomaticInitialSpawning" -> {
                    automaticInitialSpawning = property.isTrue();
                    yield true;
                }
                case "InitialParticlesPerSecond" -> {
                    initialParticlesPerSecond = property.getFloatValue();
                    yield true;
                }
                case "ParticlesPerSecond" -> {
                    particlesPerSecond = property.getFloatValue();
                    yield true;
                }
                case "RespawnDeadParticles" -> {
                    respawnDeadParticles = property.isTrue();
                    yield true;
                }
                case "ForcedMaxParticles" -> {
                    forcedMaxParticles = property.isTrue();
                    yield true;
                }
                case "Disabled" -> {
                    disabled = property.isTrue();
                    yield true;
                }

                case "LifetimeRange" -> {
                    lifetimeMin = rangeMin(property, 0f);
                    lifetimeMax = rangeMax(property, 0f);
                    yield true;
                }
                case "InitialTimeRange" -> {
                    initialTimeMin = rangeMin(property, 0f);
                    initialTimeMax = rangeMax(property, 0f);
                    yield true;
                }
                case "InitialDelayRange" -> {
                    initialDelayMin = rangeMin(property, 0f);
                    initialDelayMax = rangeMax(property, 0f);
                    yield true;
                }
                case "UseRevolution" -> {
                    useRevolution = property.isTrue();
                    yield true;
                }
                case "RevolutionsPerSecondRange" -> {
                    revolutionsPerSecondMin = rvMin(property, 0f);
                    revolutionsPerSecondMax = rvMax(property, 0f);
                    yield true;
                }
                case "RevolutionCenterOffsetRange" -> {
                    revolutionCenterOffsetMin = rvMin(property, 0f);
                    revolutionCenterOffsetMax = rvMax(property, 0f);
                    yield true;
                }
                case "AddLocationFromOtherEmitter" -> {
                    addLocationFromOtherEmitter = intOrFloat(property, -1);
                    yield true;
                }
                case "UseRevolutionScale" -> {
                    useRevolutionScale = property.isTrue();
                    yield true;
                }
                case "RevolutionScale" -> {
                    var elements = property.getSubProperties();
                    for (var element : elements) {
                        var time = element.get("RelativeTime");
                        var revolution = element.get("RelativeRevolution");
                        if (time != null && revolution != null && revolution.getVectorValue() != null) {
                            var v = revolution.getVectorValue();
                            revolutionScale.add(new float[] {time.getFloatValue(), v.getX(), v.getY(), v.getZ()});
                        }
                    }
                    yield true;
                }
                case "RevolutionScaleRepeats" -> {
                    revolutionScaleRepeats = intOrFloat(property, 0);
                    yield true;
                }
                case "ForcedLifeTime" -> {
                    forcedLifeTime = property.isTrue();
                    yield true;
                }
                case "ForcedFade" -> {
                    forcedFade = property.isTrue();
                    yield true;
                }
                case "WeatherSoundCheck" -> {
                    weatherSoundCheck = property.isTrue();
                    yield true;
                }
                case "CheckLevelOfWeather" -> {
                    checkLevelOfWeather = property.isTrue();
                    yield true;
                }
                case "WeatherRangeTime" -> {
                    var pairs = new ArrayList<float[]>();
                    for (var element : property.getSubProperties()) {
                        pairs.add(new float[] {floatOf(element.get("Min")), floatOf(element.get("Max"))});
                    }
                    weatherRangeTime = EmitterTimeWindows.ofAuthored(pairs);
                    yield true;
                }
                case "DestroyWhenHitTarget" -> {
                    destroyWhenHitTarget = property.isTrue();
                    yield true;
                }
                case "ResetAfterChange" -> {
                    resetAfterChange = property.isTrue();
                    yield true;
                }

                case "UseSubdivisionScale" -> {
                    useSubdivisionScale = property.isTrue();
                    yield true;
                }
                case "SubdivisionScale" -> {
                    var i = property.getArrayIndex();
                    if (i >= 0 && i < subdivisionScale.length) {
                        subdivisionScale[i] = property.getFloatValue();
                        subdivisionScaleCount = Math.max(subdivisionScaleCount, i + 1);
                    }
                    yield true;
                }

                case "StartLocationOffset" -> {
                    startLocationOffset = readVector(property);
                    yield true;
                }
                case "StartLocationRange" -> {
                    startLocationMin = rvMin(property, 0f);
                    startLocationMax = rvMax(property, 0f);
                    yield true;
                }
                case "StartLocationShape" -> {
                    startLocationShape = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "SphereRadiusRange" -> {
                    sphereRadiusMin = rangeMin(property, 0f);
                    sphereRadiusMax = rangeMax(property, 0f);
                    yield true;
                }
                case "StartLocationPolarRange" -> {
                    startLocationPolarMin = rvMin(property, 0f);
                    startLocationPolarMax = rvMax(property, 0f);
                    yield true;
                }

                case "UseRotationFrom" -> {
                    useRotationFrom = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "RotationOffset" -> {
                    rotationOffset = property.getRotatorValue();
                    yield true;
                }
                case "RotationNormal" -> {
                    rotationNormal = readVector(property);
                    yield true;
                }

                case "StartVelocityRange" -> {
                    startVelocityMin = rvMin(property, 0f);
                    startVelocityMax = rvMax(property, 0f);
                    yield true;
                }
                case "Acceleration" -> {
                    acceleration = readVector(property);
                    yield true;
                }
                case "MaxAbsVelocity" -> {
                    maxAbsVelocity = readVector(property);
                    yield true;
                }
                case "VelocityLossRange" -> {
                    velocityLossMin = rvMin(property, 0f);
                    velocityLossMax = rvMax(property, 0f);
                    yield true;
                }
                case "GetVelocityDirectionFrom" -> {
                    getVelocityDirectionFrom = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "StartVelocityRadialRange" -> {
                    startVelocityRadialMin = rangeMin(property, 0f);
                    startVelocityRadialMax = rangeMax(property, 0f);
                    yield true;
                }

                case "StartSizeRange" -> {
                    startSizeMin = rvMin(property, 1f);
                    startSizeMax = rvMax(property, 1f);
                    startSizeAuthored = true;
                    yield true;
                }
                case "UniformSize" -> {
                    uniformSize = property.isTrue();
                    yield true;
                }
                case "UseRegularSizeScale" -> {
                    useRegularSizeScale = property.isTrue();
                    yield true;
                }
                case "UseSizeScale" -> {
                    useSizeScale = property.isTrue();
                    yield true;
                }
                case "SizeScaleRepeats" -> {
                    sizeScaleRepeats = intOrFloat(property, 0);
                    yield true;
                }
                case "UseAbsoluteTimeForSizeScale" -> {
                    useAbsoluteTimeForSizeScale = property.isTrue();
                    yield true;
                }
                case "SizeScale" -> {
                    for (var element : structElements(property, this)) {
                        sizeScale.add(
                                new float[] {floatOf(element.get("RelativeTime")), floatOf(element.get("RelativeSize"))
                                });
                    }
                    yield true;
                }

                case "SpinParticles" -> {
                    spinParticles = property.isTrue();
                    yield true;
                }
                case "StartSpinRange" -> {
                    startSpinMin = rvMin(property, 0f);
                    startSpinMax = rvMax(property, 0f);
                    yield true;
                }
                case "SpinsPerSecondRange" -> {
                    spinsPerSecondMin = rvMin(property, 0f);
                    spinsPerSecondMax = rvMax(property, 0f);
                    yield true;
                }
                case "SpinCCWorCW" -> {
                    spinCcwOrCw = readVector(property);
                    yield true;
                }

                case "FadeIn" -> {
                    fadeIn = property.isTrue();
                    yield true;
                }
                case "FadeInEndTime" -> {
                    fadeInEndTime = floatOr(property, 0f);
                    yield true;
                }
                case "FadeOut" -> {
                    fadeOut = property.isTrue();
                    yield true;
                }
                case "FadeOutStartTime" -> {
                    fadeOutStartTime = floatOr(property, 0f);
                    yield true;
                }
                case "FadeInFactor" -> {
                    fadeInFactor = fadeFactor(property);
                    yield true;
                }
                case "FadeOutFactor" -> {
                    fadeOutFactor = fadeFactor(property);
                    yield true;
                }

                case "Opacity" -> {
                    opacity = property.getFloatValue();
                    yield true;
                }
                case "ColorMultiplierRange" -> {
                    colorMultiplierMin = rvMin(property, 1f);
                    colorMultiplierMax = rvMax(property, 1f);
                    yield true;
                }
                case "UseColorScale" -> {
                    useColorScale = property.isTrue();
                    yield true;
                }
                case "ColorScaleRepeats" -> {
                    colorScaleRepeats = intOrFloat(property, 0);
                    yield true;
                }
                case "ColorScale" -> {
                    for (var element : structElements(property, this)) {
                        var color = element.get("Color");
                        var c = color == null ? null : color.getColorValue();
                        if (c != null) {
                            colorScale.add(new float[] {
                                floatOf(element.get("RelativeTime")),
                                (c.r() & 0xFF) / 255f,
                                (c.g() & 0xFF) / 255f,
                                (c.b() & 0xFF) / 255f,
                                (c.a() & 0xFF) / 255f
                            });
                        }
                    }
                    yield true;
                }
                case "DrawStyle" -> {
                    drawStyle = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "AlphaTest" -> {
                    alphaTest = property.isTrue();
                    yield true;
                }
                case "AlphaRef" -> {
                    alphaRef = intOrFloat(property, 0) & 0xFF;
                    yield true;
                }
                case "ZTest" -> {
                    zTest = property.isTrue();
                    yield true;
                }
                case "ZWrite" -> {
                    zWrite = property.isTrue();
                    yield true;
                }
                case "DisableFogging" -> {
                    disableFogging = property.isTrue();
                    yield true;
                }
                case "UseSoftParticle" -> {
                    useSoftParticle = property.isTrue();
                    yield true;
                }
                case "SoftParticleFadeDist" -> {
                    softParticleFadeDist = property.getFloatValue();
                    yield true;
                }
                case "TextureUSubdivisions" -> {
                    textureUSubdivisions = Math.max(1, property.getInt32Value());
                    yield true;
                }
                case "TextureVSubdivisions" -> {
                    textureVSubdivisions = Math.max(1, property.getInt32Value());
                    yield true;
                }
                case "SubdivisionStart" -> {
                    subdivisionStart = property.getInt32Value();
                    yield true;
                }
                case "SubdivisionEnd" -> {
                    subdivisionEnd = property.getInt32Value();
                    yield true;
                }
                case "UseRandomSubdivision" -> {
                    useRandomSubdivision = property.isTrue();
                    yield true;
                }

                case "RelativeWarmupTime" -> {
                    relativeWarmupTime = property.getFloatValue();
                    yield true;
                }
                case "WarmupTicksPerSecond" -> {
                    warmupTicksPerSecond = switch (property.getType()) {
                        case Int -> property.getInt32Value();
                        case Float -> property.getFloatValue();
                        default -> 10f;
                    };
                    yield true;
                }
                case "CoordinateSystem" -> {
                    coordinateSystem = property.getUInt8Value() & 0xFF;
                    coordinateSystemAuthored = true;
                    yield true;
                }
                case "CustomMaterials" -> {
                    customMaterialRefs = property.getObjectArray();
                    yield true;
                }
                default -> false;
            };
        }

        public int getMaxParticles() {
            return this.maxParticles;
        }

        public boolean isAutomaticInitialSpawning() {
            return this.automaticInitialSpawning;
        }

        public float getInitialParticlesPerSecond() {
            return this.initialParticlesPerSecond;
        }

        public float getParticlesPerSecond() {
            return this.particlesPerSecond;
        }

        public boolean isRespawnDeadParticles() {
            return this.respawnDeadParticles;
        }

        public boolean isForcedMaxParticles() {
            return this.forcedMaxParticles;
        }

        public boolean isDisabled() {
            return this.disabled;
        }

        public float getLifetimeMin() {
            return this.lifetimeMin;
        }

        public float getLifetimeMax() {
            return this.lifetimeMax;
        }

        public boolean isUseRevolutionScale() {
            return useRevolutionScale;
        }

        public List<float[]> getRevolutionScale() {
            return List.copyOf(revolutionScale);
        }

        public int getRevolutionScaleRepeats() {
            return revolutionScaleRepeats;
        }

        public boolean isForcedLifeTime() {
            return forcedLifeTime;
        }

        public boolean isForcedFade() {
            return forcedFade;
        }

        public boolean isWeatherSoundCheck() {
            return weatherSoundCheck;
        }

        public boolean isCheckLevelOfWeather() {
            return checkLevelOfWeather;
        }

        public EmitterTimeWindows getWeatherRangeTime() {
            return weatherRangeTime;
        }

        public boolean isDestroyWhenHitTarget() {
            return destroyWhenHitTarget;
        }

        public boolean isResetAfterChange() {
            return resetAfterChange;
        }

        public boolean isUseRevolution() {
            return useRevolution;
        }

        public Vec3 getRevolutionsPerSecondMin() {
            return revolutionsPerSecondMin;
        }

        public Vec3 getRevolutionsPerSecondMax() {
            return revolutionsPerSecondMax;
        }

        public Vec3 getRevolutionCenterOffsetMin() {
            return revolutionCenterOffsetMin;
        }

        public Vec3 getRevolutionCenterOffsetMax() {
            return revolutionCenterOffsetMax;
        }

        public int getAddLocationFromOtherEmitter() {
            return addLocationFromOtherEmitter;
        }

        public boolean isUseSubdivisionScale() {
            return useSubdivisionScale;
        }

        public float[] getSubdivisionScale() {
            return Arrays.copyOf(subdivisionScale, subdivisionScaleCount);
        }

        public float getInitialDelayMin() {

            return this.initialDelayMin;
        }

        public float getInitialDelayMax() {

            return this.initialDelayMax;
        }

        public float getInitialTimeMin() {
            return this.initialTimeMin;
        }

        public float getInitialTimeMax() {
            return this.initialTimeMax;
        }

        public Vec3 getStartLocationOffset() {
            return this.startLocationOffset;
        }

        public Vec3 getStartLocationMin() {
            return this.startLocationMin;
        }

        public Vec3 getStartLocationMax() {
            return this.startLocationMax;
        }

        public int getStartLocationShape() {
            return this.startLocationShape;
        }

        public float getSphereRadiusMin() {
            return this.sphereRadiusMin;
        }

        public float getSphereRadiusMax() {
            return this.sphereRadiusMax;
        }

        public Vec3 getStartLocationPolarMin() {
            return this.startLocationPolarMin;
        }

        public Vec3 getStartLocationPolarMax() {
            return this.startLocationPolarMax;
        }

        public int getUseRotationFrom() {
            return this.useRotationFrom;
        }

        public URotator getRotationOffset() {
            return this.rotationOffset;
        }

        public Vec3 getRotationNormal() {
            return this.rotationNormal;
        }

        public Vec3 getStartVelocityMin() {
            return this.startVelocityMin;
        }

        public Vec3 getStartVelocityMax() {
            return this.startVelocityMax;
        }

        public Vec3 getAcceleration() {
            return this.acceleration;
        }

        public Vec3 getMaxAbsVelocity() {
            return this.maxAbsVelocity;
        }

        public Vec3 getVelocityLossMin() {
            return this.velocityLossMin;
        }

        public Vec3 getVelocityLossMax() {
            return this.velocityLossMax;
        }

        public int getGetVelocityDirectionFrom() {
            return this.getVelocityDirectionFrom;
        }

        public float getStartVelocityRadialMin() {
            return startVelocityRadialMin;
        }

        public float getStartVelocityRadialMax() {
            return startVelocityRadialMax;
        }

        public Vec3 getStartSizeMin() {
            return this.startSizeMin;
        }

        public Vec3 getStartSizeMax() {
            return this.startSizeMax;
        }

        public boolean isStartSizeAuthored() {
            return this.startSizeAuthored;
        }

        public boolean isUniformSize() {
            return this.uniformSize;
        }

        public boolean isUseRegularSizeScale() {
            return this.useRegularSizeScale;
        }

        public boolean isUseSizeScale() {
            return this.useSizeScale;
        }

        public int getSizeScaleRepeats() {
            return this.sizeScaleRepeats;
        }

        public boolean isUseAbsoluteTimeForSizeScale() {
            return this.useAbsoluteTimeForSizeScale;
        }

        public List<float[]> getSizeScale() {
            return this.sizeScale;
        }

        public boolean isSpinParticles() {
            return this.spinParticles;
        }

        public Vec3 getStartSpinMin() {
            return this.startSpinMin;
        }

        public Vec3 getStartSpinMax() {
            return this.startSpinMax;
        }

        public Vec3 getSpinsPerSecondMin() {
            return this.spinsPerSecondMin;
        }

        public Vec3 getSpinsPerSecondMax() {
            return this.spinsPerSecondMax;
        }

        public Vec3 getSpinCcwOrCw() {
            return this.spinCcwOrCw;
        }

        public boolean isFadeIn() {
            return this.fadeIn;
        }

        public float getFadeInEndTime() {
            return this.fadeInEndTime;
        }

        public boolean isFadeOut() {
            return this.fadeOut;
        }

        public float getFadeOutStartTime() {
            return this.fadeOutStartTime;
        }

        public Vec4 getFadeInFactor() {
            return fadeInFactor;
        }

        public Vec4 getFadeOutFactor() {
            return fadeOutFactor;
        }

        public float getOpacity() {
            return this.opacity;
        }

        public Vec3 getColorMultiplierMin() {
            return this.colorMultiplierMin;
        }

        public Vec3 getColorMultiplierMax() {
            return this.colorMultiplierMax;
        }

        public List<float[]> getColorScale() {
            return this.colorScale;
        }

        public boolean isUseColorScale() {
            return this.useColorScale;
        }

        public int getColorScaleRepeats() {
            return this.colorScaleRepeats;
        }

        public int getDrawStyle() {
            return this.drawStyle;
        }

        public boolean isAlphaTest() {
            return this.alphaTest;
        }

        public int getAlphaRef() {
            return this.alphaRef;
        }

        public boolean isZTest() {
            return this.zTest;
        }

        public boolean isZWrite() {
            return this.zWrite;
        }

        public boolean isDisableFogging() {
            return this.disableFogging;
        }

        public boolean isUseSoftParticle() {
            return this.useSoftParticle;
        }

        public float getSoftParticleFadeDist() {
            return this.softParticleFadeDist;
        }

        public int getTextureUSubdivisions() {
            return this.textureUSubdivisions;
        }

        public int getTextureVSubdivisions() {
            return this.textureVSubdivisions;
        }

        public int getSubdivisionStart() {
            return this.subdivisionStart;
        }

        public int getSubdivisionEnd() {
            return this.subdivisionEnd;
        }

        public boolean isUseRandomSubdivision() {
            return this.useRandomSubdivision;
        }

        public float getRelativeWarmupTime() {
            return this.relativeWarmupTime;
        }

        public float getWarmupTicksPerSecond() {
            return this.warmupTicksPerSecond;
        }

        public int getCoordinateSystem() {
            return this.coordinateSystem;
        }

        public boolean isCoordinateSystemAuthored() {
            return this.coordinateSystemAuthored;
        }
    }

    public static class USparkEmitter extends UParticleEmitter {
        private float lineSegmentsMin = 5f, lineSegmentsMax = 5f;
        private float timeBeforeVisibleMin, timeBeforeVisibleMax;
        private float timeBetweenSegmentsMin, timeBetweenSegmentsMax;

        public USparkEmitter(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            switch (property.getName()) {
                case "LineSegmentsRange" -> {
                    lineSegmentsMin = rangeMin(property, lineSegmentsMin);
                    lineSegmentsMax = rangeMax(property, lineSegmentsMax);
                }
                case "TimeBeforeVisibleRange" -> {
                    timeBeforeVisibleMin = rangeMin(property, 0f);
                    timeBeforeVisibleMax = rangeMax(property, 0f);
                }
                case "TimeBetweenSegmentsRange" -> {
                    timeBetweenSegmentsMin = rangeMin(property, 0f);
                    timeBetweenSegmentsMax = rangeMax(property, 0f);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        public int getLineSegments() {
            return (int) Math.max(2f, Math.max(lineSegmentsMin, lineSegmentsMax));
        }

        public float getTimeBeforeVisibleMin() {
            return timeBeforeVisibleMin;
        }

        public float getTimeBeforeVisibleMax() {
            return timeBeforeVisibleMax;
        }

        public float getTimeBetweenSegmentsMin() {
            return timeBetweenSegmentsMin;
        }

        public float getTimeBetweenSegmentsMax() {
            return timeBetweenSegmentsMax;
        }
    }

    public static class USpriteEmitter extends UParticleEmitter {
        private final UObjectRef<?, Optional> texture = UObjectRef.optional();

        private Vec3 projectionNormal = new Vec3(0f, 0f, 1f);

        private int useDirectionAs = 0;
        private boolean blendBetweenSubdivisions = false;
        private boolean useCloudColor = false;
        private boolean representativeParticle = false;
        private float extentMultiplier = 1f;
        private int refraction;
        private float refrUScale = .06f, refrVScale = .06f;

        public USpriteEmitter(UArchive archive) {
            super(archive);
        }

        public @Nullable UMaterial getTexture() {
            return texture.get() instanceof UMaterial material ? material : null;
        }

        public float getStartSize() {
            return isStartSizeAuthored()
                    ? (getStartSizeMin().getX() + getStartSizeMax().getX()) * 0.5f
                    : -1f;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Texture" -> {
                    texture.fromProperty(archive, property);
                    yield true;
                }
                case "BlendBetweenSubdivisions" -> {
                    blendBetweenSubdivisions = property.isTrue();
                    yield true;
                }
                case "UseCloudColor" -> {
                    useCloudColor = property.isTrue();
                    yield true;
                }
                case "IsRepresentativeParticle" -> {
                    representativeParticle = property.isTrue();
                    yield true;
                }
                case "ExtentMultiplier" -> {
                    extentMultiplier = floatOr(property, 1f);
                    yield true;
                }
                case "Refraction" -> {
                    refraction = property.getUInt8Value() & 0xff;
                    yield true;
                }
                case "RefrUScale" -> {
                    refrUScale = floatOr(property, .06f);
                    yield true;
                }
                case "RefrVScale" -> {
                    refrVScale = floatOr(property, .06f);
                    yield true;
                }
                case "UseDirectionAs" -> {
                    useDirectionAs = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "ProjectionNormal" -> {
                    projectionNormal = readVector(property);
                    yield true;
                }
                default -> false;
            };
        }

        public Vec3 getProjectionNormal() {
            return this.projectionNormal;
        }

        public boolean isBlendBetweenSubdivisions() {
            return blendBetweenSubdivisions;
        }

        public boolean isUseCloudColor() {
            return useCloudColor;
        }

        public boolean isRepresentativeParticle() {
            return representativeParticle;
        }

        public float getExtentMultiplier() {
            return extentMultiplier;
        }

        public boolean isRefraction() {
            return refraction != 0;
        }

        public int getRefraction() {
            return refraction;
        }

        public float getRefrUScale() {
            return refrUScale;
        }

        public float getRefrVScale() {
            return refrVScale;
        }

        public int getUseDirectionAs() {
            return this.useDirectionAs;
        }
    }

    public static class UMeshEmitter extends UParticleEmitter {
        private final UObjectRef<UStaticMesh, Optional> staticMesh = UObjectRef.optional();
        private final UObjectRef<?, Optional> texture = UObjectRef.optional();
        private boolean useMeshBlendMode = true;
        private boolean renderTwoSided;
        private boolean useParticleColor;

        public UMeshEmitter(UArchive archive) {
            super(archive);
        }

        public @Nullable UStaticMesh getMesh() {
            return staticMesh.get();
        }

        public @Nullable UMaterial getTexture() {
            return texture.get() instanceof UMaterial material ? material : null;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "StaticMesh" -> {
                    staticMesh.fromProperty(archive, property);
                    yield true;
                }
                case "Texture" -> {
                    texture.fromProperty(archive, property);
                    yield true;
                }
                case "UseMeshBlendMode" -> {
                    useMeshBlendMode = property.isTrue();
                    yield true;
                }
                case "RenderTwoSided" -> {
                    renderTwoSided = property.isTrue();
                    yield true;
                }
                case "UseParticleColor" -> {
                    useParticleColor = property.isTrue();
                    yield true;
                }
                default -> false;
            };
        }

        public UObjectRef<UStaticMesh, Optional> getStaticMesh() {
            return this.staticMesh;
        }

        public boolean isUseMeshBlendMode() {
            return this.useMeshBlendMode;
        }

        public boolean isRenderTwoSided() {
            return this.renderTwoSided;
        }

        public boolean isUseParticleColor() {
            return this.useParticleColor;
        }
    }

    public static class UVertMeshEmitter extends UParticleEmitter {
        private final UObjectRef<UObject, Optional> vertexMesh = UObjectRef.optional();
        private float animRate = 1f;
        private boolean useMeshBlendMode = true;
        private boolean useParticleColor;
        private boolean renderTwoSided = true;

        public UVertMeshEmitter(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "VertexMesh" -> {
                    vertexMesh.fromProperty(archive, property);
                    yield true;
                }
                case "RenderTwoSided" -> {
                    renderTwoSided = property.isTrue();
                    yield true;
                }
                case "AnimRate" -> {
                    animRate = property.getFloatValue();
                    yield true;
                }
                case "UseMeshBlendMode" -> {
                    useMeshBlendMode = property.isTrue();
                    yield true;
                }
                case "UseParticleColor" -> {
                    useParticleColor = property.isTrue();
                    yield true;
                }
                default -> false;
            };
        }

        public UObjectRef<UObject, Optional> getVertexMesh() {
            return this.vertexMesh;
        }

        public boolean isRenderTwoSided() {
            return renderTwoSided;
        }

        public float getAnimRate() {
            return this.animRate;
        }

        public boolean isUseMeshBlendMode() {
            return this.useMeshBlendMode;
        }

        public boolean isUseParticleColor() {
            return this.useParticleColor;
        }
    }

    public static class UTrailEmitter extends UParticleEmitter {
        private final UObjectRef<?, Optional> texture = UObjectRef.optional();

        private int trailShadeType = 0;
        private int trailLocation = 0;
        private int maxPointsPerTrail = 30;
        private float distanceThreshold = 2f;
        private boolean useCrossedSheets = false;
        private int maxTrailTwistAngle = 16384;
        private float pointLifeTime = 0f;

        public UTrailEmitter(UArchive archive) {
            super(archive);
        }

        public @Nullable UMaterial getTexture() {
            return texture.get() instanceof UMaterial material ? material : null;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Texture" -> {
                    texture.fromProperty(archive, property);
                    yield true;
                }
                case "TrailShadeType" -> {
                    trailShadeType = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "TrailLocation" -> {
                    trailLocation = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "MaxPointsPerTrail" -> {
                    maxPointsPerTrail = intOrFloat(property, 30);
                    yield true;
                }
                case "DistanceThreshold" -> {
                    distanceThreshold = property.getFloatValue();
                    yield true;
                }
                case "UseCrossedSheets" -> {
                    useCrossedSheets = property.isTrue();
                    yield true;
                }
                case "MaxTrailTwistAngle" -> {
                    maxTrailTwistAngle = intOrFloat(property, 16384);
                    yield true;
                }
                case "PointLifeTime" -> {
                    pointLifeTime = property.getFloatValue();
                    yield true;
                }
                default -> false;
            };
        }

        public int getTrailShadeType() {
            return this.trailShadeType;
        }

        public int getTrailLocation() {
            return this.trailLocation;
        }

        public int getMaxPointsPerTrail() {
            return this.maxPointsPerTrail;
        }

        public float getDistanceThreshold() {
            return this.distanceThreshold;
        }

        public boolean isUseCrossedSheets() {
            return this.useCrossedSheets;
        }

        public int getMaxTrailTwistAngle() {
            return this.maxTrailTwistAngle;
        }

        public float getPointLifeTime() {
            return this.pointLifeTime;
        }
    }

    public static class UBeamEmitter extends UParticleEmitter {
        private final UObjectRef<?, Optional> texture = UObjectRef.optional();

        private int determineEndPointBy = 0;
        private int rotatingSheets = 0;
        private boolean useBranching = false;
        private int branchEmitter = -1;
        private boolean triggerDisabled = false;
        private boolean resetOnTrigger = false;
        private boolean useLowFrequencyScale = false;
        private boolean applyBezierCurve = false;
        private float linkupLifetime = 0f;
        private int findEndPointCnt = 0;
        private final List<BeamEndpoint> beamEndPoints = new ArrayList<>();
        private float beamDistanceMin = 0f, beamDistanceMax = 0f;

        private int lowFrequencyPoints = 3;
        private int highFrequencyPoints = 10;
        private Vec3 lowFrequencyNoiseMin = ZERO, lowFrequencyNoiseMax = ZERO;
        private Vec3 highFrequencyNoiseMin = ZERO, highFrequencyNoiseMax = ZERO;
        private boolean noiseDeterminesEndPoint;

        private float beamTextureUScale = 1f;
        private float beamTextureVScale = 1f;
        private boolean blendBetweenSubdivisions = false;

        public UBeamEmitter(UArchive archive) {
            super(archive);
        }

        public record BeamEndpoint(
                Vec3 offsetMin,
                Vec3 offsetMax,
                float weight,
                String actorTag,
                @Nullable Vec3 actorPlace) {
            public BeamEndpoint(Vec3 offsetMin, Vec3 offsetMax, float weight) {
                this(offsetMin, offsetMax, weight, "None", null);
            }
        }

        public void resolveActorEnds(Map<String, Vec3> places) {
            for (var i = 0; i < beamEndPoints.size(); i++) {
                var end = beamEndPoints.get(i);
                var place = places.get(end.actorTag().toLowerCase(Locale.ROOT));
                beamEndPoints.set(
                        i, new BeamEndpoint(end.offsetMin(), end.offsetMax(), end.weight(), end.actorTag(), place));
            }
        }

        public @Nullable UMaterial getTexture() {
            return texture.get() instanceof UMaterial material ? material : null;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Texture" -> {
                    texture.fromProperty(archive, property);
                    yield true;
                }
                case "RotatingSheets" -> {
                    rotatingSheets = intOrFloat(property, 0);
                    yield true;
                }
                case "UseBranching" -> {
                    useBranching = property.isTrue();
                    yield true;
                }
                case "BranchEmitter" -> {
                    branchEmitter = intOrFloat(property, -1);
                    yield true;
                }
                case "TriggerDisabled" -> {
                    triggerDisabled = property.isTrue();
                    yield true;
                }
                case "ResetOnTrigger" -> {
                    resetOnTrigger = property.isTrue();
                    yield true;
                }
                case "UseLowFrequencyScale" -> {
                    useLowFrequencyScale = property.isTrue();
                    yield true;
                }
                case "bApllyBezierCurve" -> {
                    applyBezierCurve = property.isTrue();
                    yield true;
                }
                case "LinkupLifetime" -> {
                    linkupLifetime = floatOr(property, 0f);
                    yield true;
                }
                case "FindEndPointCnt" -> {
                    findEndPointCnt = intOrFloat(property, 0);
                    yield true;
                }

                case "DetermineEndPointBy" -> {
                    determineEndPointBy = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "BeamEndPoints" -> {
                    parseBeamEndPoints(property);
                    yield true;
                }
                case "BeamDistanceRange" -> {
                    beamDistanceMin = rangeMin(property, 0f);
                    beamDistanceMax = rangeMax(property, 0f);
                    yield true;
                }
                case "LowFrequencyPoints" -> {
                    lowFrequencyPoints = property.getInt32Value();
                    yield true;
                }
                case "HighFrequencyPoints" -> {
                    highFrequencyPoints = property.getInt32Value();
                    yield true;
                }
                case "LowFrequencyNoiseRange" -> {
                    lowFrequencyNoiseMin = rvMin(property, 0f);
                    lowFrequencyNoiseMax = rvMax(property, 0f);
                    yield true;
                }
                case "HighFrequencyNoiseRange" -> {
                    highFrequencyNoiseMin = rvMin(property, 0f);
                    highFrequencyNoiseMax = rvMax(property, 0f);
                    yield true;
                }
                case "NoiseDeterminesEndPoint" -> {
                    noiseDeterminesEndPoint = property.isTrue();
                    yield true;
                }
                case "BeamTextureUScale" -> {
                    beamTextureUScale = property.getFloatValue();
                    yield true;
                }
                case "BeamTextureVScale" -> {
                    beamTextureVScale = property.getFloatValue();
                    yield true;
                }
                case "BlendBetweenSubdivisions" -> {
                    blendBetweenSubdivisions = property.isTrue();
                    yield true;
                }
                default -> false;
            };
        }

        public int getDetermineEndPointBy() {
            return this.determineEndPointBy;
        }

        public List<BeamEndpoint> getBeamEndPoints() {
            return this.beamEndPoints;
        }

        public float getBeamDistanceMin() {
            return this.beamDistanceMin;
        }

        public float getBeamDistanceMax() {
            return this.beamDistanceMax;
        }

        public int getLowFrequencyPoints() {
            return this.lowFrequencyPoints;
        }

        public int getHighFrequencyPoints() {
            return this.highFrequencyPoints;
        }

        public Vec3 getLowFrequencyNoiseMin() {
            return this.lowFrequencyNoiseMin;
        }

        public Vec3 getLowFrequencyNoiseMax() {
            return this.lowFrequencyNoiseMax;
        }

        public Vec3 getHighFrequencyNoiseMin() {
            return this.highFrequencyNoiseMin;
        }

        public Vec3 getHighFrequencyNoiseMax() {
            return this.highFrequencyNoiseMax;
        }

        public boolean isNoiseDeterminesEndPoint() {
            return this.noiseDeterminesEndPoint;
        }

        public float getBeamTextureUScale() {
            return this.beamTextureUScale;
        }

        public float getBeamTextureVScale() {
            return this.beamTextureVScale;
        }

        public int getRotatingSheets() {
            return rotatingSheets;
        }

        public boolean isUseBranching() {
            return useBranching;
        }

        public int getBranchEmitter() {
            return branchEmitter;
        }

        public boolean isTriggerDisabled() {
            return triggerDisabled;
        }

        public boolean isResetOnTrigger() {
            return resetOnTrigger;
        }

        public boolean isUseLowFrequencyScale() {
            return useLowFrequencyScale;
        }

        public boolean isApplyBezierCurve() {
            return applyBezierCurve;
        }

        public float getLinkupLifetime() {
            return linkupLifetime;
        }

        public int getFindEndPointCnt() {
            return findEndPointCnt;
        }

        public boolean isBlendBetweenSubdivisions() {
            return this.blendBetweenSubdivisions;
        }

        private void parseBeamEndPoints(UProperty property) {
            for (var element : property.getSubProperties()) {
                var offset = element.get("offset");
                var min = offset != null ? rvMin(offset, 0f) : ZERO;
                var max = offset != null ? rvMax(offset, 0f) : ZERO;
                var weightProp = element.get("Weight");
                var weight = weightProp != null && weightProp.getType() == Type.Float ? weightProp.getFloatValue() : 0f;
                var tagProp = element.get("ActorTag");
                var tag = tagProp != null && tagProp.getType() == Type.Name
                        ? archive.resolveName(tagProp.getIndexValue())
                        : "None";
                beamEndPoints.add(new BeamEndpoint(min, max, weight, tag, null));
            }
        }
    }

    private static Vec3 readVector(UProperty property) {
        var v = property.getVectorValue();
        return v != null ? v : ZERO;
    }

    private static List<Map<String, UProperty>> structElements(UProperty property, UObject owner) {
        var elements = property.getSubProperties();
        if (elements.isEmpty() && property.getArraySize() > 0) {
            var bytes = property.getDataValue();
            var text = new StringBuilder();
            for (var i = 0; bytes != null && i < Math.min(bytes.length, 64); i++) {
                text.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xFF));
            }
            log.warn(
                    "{} of {} has {} elements in a layout that cannot be read, so it is left out. Its {} bytes"
                            + " start with {}",
                    property.getName(),
                    owner.getFullName(),
                    property.getArraySize(),
                    bytes == null ? 0 : bytes.length,
                    text);
        }
        return elements;
    }

    private static float floatOr(UProperty property, float dflt) {
        return property.getType() == Type.Float ? property.getFloatValue() : dflt;
    }

    private static float floatOf(@Nullable UProperty property) {
        return property == null ? 0f : floatOr(property, 0f);
    }

    private static Vec4 fadeFactor(UProperty property) {
        return new Vec4(
                subFloatOr(property, "X", 1f),
                subFloatOr(property, "Y", 1f),
                subFloatOr(property, "Z", 1f),
                subFloatOr(property, "W", 1f));
    }

    private static int intOrFloat(UProperty property, int dflt) {
        return switch (property.getType()) {
            case Int -> property.getInt32Value();
            case Float -> Math.round(property.getFloatValue());
            default -> dflt;
        };
    }

    private static float rangeMin(UProperty range, float dflt) {
        return subFloatOr(range, "Min", dflt);
    }

    private static float rangeMax(UProperty range, float dflt) {
        return subFloatOr(range, "Max", dflt);
    }

    private static Vec3 rvMin(UProperty rv, float dflt) {
        return new Vec3(
                axisBound(rv, "X", "Min", dflt), axisBound(rv, "Y", "Min", dflt), axisBound(rv, "Z", "Min", dflt));
    }

    private static Vec3 rvMax(UProperty rv, float dflt) {
        return new Vec3(
                axisBound(rv, "X", "Max", dflt), axisBound(rv, "Y", "Max", dflt), axisBound(rv, "Z", "Max", dflt));
    }

    private static float axisBound(UProperty rv, String axis, String bound, float dflt) {
        try {
            return subFloatOr(rv.subProperty(axis), bound, dflt);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static float subFloatOr(UProperty parent, String name, float dflt) {
        try {
            var v = parent.subProperty(name).getFloatValue();
            return Float.isNaN(v) ? dflt : v;
        } catch (RuntimeException e) {
            return dflt;
        }
    }
}
