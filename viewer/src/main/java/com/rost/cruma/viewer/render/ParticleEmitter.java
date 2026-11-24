package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.UEmitter.UBeamEmitter;
import com.rost.cruma.core.unreal.UEmitter.UBeamEmitter.BeamEndpoint;
import com.rost.cruma.core.unreal.UEmitter.UMeshEmitter;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.unreal.UEmitter.USpriteEmitter;
import com.rost.cruma.core.unreal.UEmitter.UVertMeshEmitter;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.EmitterDistanceMode;
import com.rost.cruma.core.world.EmitterLife;
import com.rost.cruma.core.world.EmitterTimeWindows;
import com.rost.cruma.core.world.EmitterVisibility;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.viewer.render.api.DebugMaterialSlot;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

public class ParticleEmitter {
    public static final float SPRITE_QUAD_SCALE = 2f;

    public static final int POOL_CAP = 256;

    private static final int ACTIVE = 1;
    private static final int INITIAL_SPAWN = 4;
    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final float SPIN_UNIT = TWO_PI / 65536f;
    private static final int PTEP_VELOCITY = 0;
    private static final int PTEP_DISTANCE = 1;
    private static final int PTEP_OFFSET = 2;
    private static final int PTEP_ACTOR = 3;
    private static final int PTEP_TRACE_OFFSET = 4;
    private static final int PTEP_OFFSET_AS_ABSOLUTE = 5;
    private static final int PTCS_RELATIVE = 1;
    private static final float NON_PARALLEL = .57f;
    private static final int PTCS_ABSOLUTE = 2;
    private static final int PTCS_SPRAY = 4;
    private static final int PTRS_NONE = 0;
    private static final int PTRS_ACTOR = 1;
    private static final int PTRS_OFFSET = 2;
    private static final int PTVD_NONE = 0;
    private static final int PTVD_START_AND_OWNER = 1;
    private static final int PTVD_OWNER_AND_START = 2;
    private static final int PTVD_ADD_RADIAL = 3;
    private static final int PTCS_P152_SIX = 6;
    private static final int PTCS_P152_SEVEN = 7;
    private static final int PTLS_POLAR = 2;
    private static final float SAFE_NORMAL_SQUARED = 1e-8f;
    private static final float BEAM_DEFAULT_SIZE = 12f;
    private static final float SPRITE_DEFAULT_SIZE = 64f;

    private DiagnosticSource diagnosticSource;

    private Vec3 actorPos;
    private float actorX;
    private float actorY;
    private float actorZ;
    private Quat actorRotation = new Quat();
    private float actorQx;
    private float actorQy;
    private float actorQz;
    private float actorQw = 1f;
    private Vec3 actorScale = new Vec3(1f, 1f, 1f);
    private @Nullable URotator ownerRotation;
    private int ownerTurns;
    private Vec3 meshPrePivot = new Vec3(0f, 0f, 0f);
    private EmitterVisibility visibility = EmitterVisibility.UNLIMITED;
    private float opacityRatio = 1f;
    private EmitterTimeWindows timeWindows = EmitterTimeWindows.ALWAYS;
    private String timeWindowsLabel = EmitterTimeWindows.ALWAYS.label();
    private int translucentRenderPriority = -1;
    private String renderActorName = "synthetic";
    private String renderActorPackage = "synthetic";
    private @Nullable String sceneMap;
    private int renderActorOrder;
    private int renderChildOrder;
    private boolean nearThisFrame;
    private ParticleActorCullState actorCullState;
    private final Kind kind;
    private Texture spriteImage;
    private Box meshBounds;
    private String debugName;
    private final int blend;
    private final int drawStyle;
    private final int refraction;
    private final float refrUScale, refrVScale;
    private final boolean useMeshBlendMode;
    private boolean renderTwoSided;
    private final boolean useParticleColor;
    private int particleColorSurfaces;
    private int unlitSurfaces;
    private int severalPassSurfaces;
    private final boolean litByActor;
    private int meshAnimationFrames = 1;
    private int meshAnimationStartFrame;
    private int meshAnimationSequenceFrames;
    private float meshAnimationFramesPerSecond;
    private final int directionUsage;
    private final Vec3 projectionNormal;
    private float projectionWorldX;
    private float projectionWorldY;
    private float projectionWorldZ = 1f;
    private boolean blackBackground;

    private int maxParticles;
    private int maxActive;
    private final boolean automaticInitialSpawning;
    private final boolean respawnDeadParticles;
    private final boolean forcedMaxParticles;
    private final float initialParticlesPerSecond;
    private final float particlesPerSecond;
    private float lifetimeMin, lifetimeMax, lifeMid;
    private boolean lifeAdjusted;
    private final float relativeWarmupTime;
    private final float warmupTicksPerSecond;
    private final float initialTimeMin, initialTimeMax;
    private final Vec3 startLocationOffset, startLocationMin, startLocationMax;
    private final int startLocationShape;
    private final float sphereRadiusMin, sphereRadiusMax;
    private final Vec3 startLocationPolarMin, startLocationPolarMax;
    private final Vec3 startVelocityMin,
            startVelocityMax,
            acceleration,
            maxAbsVelocity,
            velocityLossMin,
            velocityLossMax;
    private final int velocityDirectionFrom;
    private final float startVelocityRadialMin, startVelocityRadialMax;
    private final int coordinateSystem;
    private final int rotationSource;
    private final Quat rotationOffset;
    private final boolean absoluteCoordinates;
    private final Vec3 startSizeMin, startSizeMax;
    private float spawnScale = 1f;
    private boolean killed;
    private final boolean uniformSize, useRegularSizeScale;
    private final boolean nativeUniformSize;
    private final float initialDelayMin, initialDelayMax;
    private float initialDelayRemaining = Float.NaN;
    private boolean finished;
    private boolean keepsActorAlive = true;
    private final boolean useRevolution;
    private final Vec3 revolutionsPerSecondMin, revolutionsPerSecondMax;
    private final Vec3 revolutionCenterOffsetMin, revolutionCenterOffsetMax;
    private float[] rcx, rcy, rcz;
    private float[] rvx, rvy, rvz;
    private final int addLocationFromOtherEmitter;
    private final boolean useRevolutionScale;
    private final float[][] revolutionScale;
    private final int revolutionScaleRepeats;
    private final boolean useSubdivisionScale;
    private final float[] subdivisionScale;
    private final boolean blendBetweenSubdivisions;
    private float[] outSubBlend;
    private final float[] revolutionScaleScratch = new float[3];
    private IntFunction<ParticleEmitter> siblingResolver;
    private int spawnCounter;
    private final boolean useSizeScale, useAbsoluteTimeForSizeScale;
    private final int sizeScaleRepeats;
    private final float[][] sizeScale;
    private final boolean spinParticles;
    private final Vec3 startSpinMin, startSpinMax, spinsPerSecondMin, spinsPerSecondMax, spinCcwOrCw;
    private final boolean fadeIn, fadeOut;
    private final float fadeInEndTime;
    private float fadeOutStartTime;
    private final Vec4 fadeInFactor, fadeOutFactor;
    private final float opacity;
    private final Vec3 colorMultiplierMin, colorMultiplierMax;
    private final boolean useColorScale;
    private final int colorScaleRepeats;
    private final float[][] colorScale;
    private final float[] colorScaleScratch = new float[4];
    private final float[] worldBoundsScratch = new float[6];
    private final Box liveWorldBounds = new Box();
    private boolean liveWorldBoundsCurrent;
    private final Box nativeSortBounds = new Box();
    private boolean nativeSortBoundsCurrent;
    private float liveMaximumParticleDiameter;
    private boolean liveMaximumParticleDiameterCurrent;
    private boolean projectedParticleCullInitialized;
    private boolean projectedParticleCulled;
    private final int simulationRefreshPhase;
    private float deferredSimulationSeconds;
    private float simulatedSeconds;
    private long spritePayloadRevision = 1L;

    private final boolean hasSubUv;
    private final int subDivU, subDivV, numCells, subdivisionStart, subdivisionEnd;
    private final boolean useRandomSubdiv;
    private final float subUScale, subVScale;

    private BeamConfig beamConfig;
    private boolean beamAimed;
    private float beamTargetX;
    private float beamTargetY;
    private float beamTargetZ;
    private float[] beamEndX, beamEndY, beamEndZ;
    private long[] beamSeed;

    private float[] px, py, pz, vx, vy, vz;
    private float[] ssx, ssy, ssz;
    private float[] spx, spy, spz;
    private float[] rpx, rpy, rpz;
    private float[] cmR, cmG, cmB;
    private float[] time, maxLifetime;
    private int[] flags;
    private float[] outScaleX, outScaleY, outScaleZ;
    private float[] outPitch, outYaw, outRoll;
    private float[] outR, outG, outB, outA;
    private int[] subCell;
    private float[] outU0, outV0;

    private final long[] rngState = new long[1];
    private final long initialSeed;
    private final boolean alphaTest;
    private final int alphaRef;
    private final boolean zTest;
    private final boolean zWrite;
    private final boolean disableFogging;
    private final boolean useSoftParticle;
    private final float softParticleFadeDist;
    private int activeParticles;
    private int particleIndex;
    private float ppsFraction;
    private boolean warmedUp;
    private @Nullable Vec3 pendingBeamTarget;
    private boolean afterReset;
    private boolean steadyStart = true;

    private volatile boolean held;

    public ParticleEmitter(UParticleEmitter p, Vec3 actorPos, Kind kind, int blend, long seed) {
        var child = p.getSourcePath();
        var asset = switch (p) {
            case UMeshEmitter mesh -> mesh.getStaticMesh().peekFullName();
            case UVertMeshEmitter mesh -> mesh.getVertexMesh().peekFullName();
            default -> null;
        };
        UMaterial sourceMaterial = switch (p) {
            case USpriteEmitter sprite -> sprite.getTexture();
            case UBeamEmitter beam -> beam.getTexture();
            case UMeshEmitter mesh -> mesh.getTexture();
            default -> null;
        };
        var materialName = sourceMaterial == null ? null : sourceMaterial.getSourcePath();
        var sourceType = p.getClass().getSimpleName().replaceFirst("^U", "");
        diagnosticSource = new DiagnosticSource(
                child,
                sourceType.isBlank() ? kind.name() : sourceType,
                asset == null ? materialName : asset,
                sourceMaterial == null
                        ? List.of()
                        : List.of(new DebugMaterialSlot(
                                0,
                                DebugMaterialSlot.orUnavailable(materialName),
                                "DrawStyle " + blend,
                                sourceMaterial.getClass().getSimpleName(),
                                "Unavailable")));
        this.drawStyle = p.getDrawStyle();
        setTimeWindows(p.getWeatherRangeTime());
        this.refraction = p instanceof USpriteEmitter sprite ? sprite.getRefraction() : 0;
        this.refrUScale = p instanceof USpriteEmitter sprite ? sprite.getRefrUScale() : 0f;
        this.refrVScale = p instanceof USpriteEmitter sprite ? sprite.getRefrVScale() : 0f;
        this.actorPos = actorPos;
        this.actorX = actorPos.getX();
        this.actorY = actorPos.getY();
        this.actorZ = actorPos.getZ();
        this.kind = kind;
        this.blend = blend;
        this.alphaTest = p.isAlphaTest();
        this.alphaRef = p.getAlphaRef();
        this.zTest = p.isZTest();
        this.zWrite = p.isZWrite();
        this.disableFogging = p.isDisableFogging();
        this.useSoftParticle = p.isUseSoftParticle();
        this.softParticleFadeDist = p.getSoftParticleFadeDist();
        var meshEmitter = p instanceof UMeshEmitter m ? m : null;
        var vertMeshEmitter = p instanceof UVertMeshEmitter v ? v : null;
        this.useMeshBlendMode = meshEmitter != null
                ? meshEmitter.isUseMeshBlendMode()
                : vertMeshEmitter == null || vertMeshEmitter.isUseMeshBlendMode();
        this.renderTwoSided = meshEmitter != null && meshEmitter.isRenderTwoSided();
        this.litByActor = (meshEmitter != null || vertMeshEmitter != null) && useMeshBlendMode;
        this.useParticleColor = !useMeshBlendMode
                || (meshEmitter != null
                        ? meshEmitter.isUseParticleColor()
                        : vertMeshEmitter == null || vertMeshEmitter.isUseParticleColor());
        this.directionUsage = (p instanceof USpriteEmitter s) ? s.getUseDirectionAs() : 0;
        this.projectionNormal = (p instanceof USpriteEmitter s) ? s.getProjectionNormal() : new Vec3(0f, 0f, 1f);
        this.initialSeed = seed;
        this.rngState[0] = seed;
        this.simulationRefreshPhase = simulationRefreshPhase(seed, actorPos);

        this.maxParticles = Math.max(1, p.getMaxParticles());
        this.forcedMaxParticles = p.isForcedMaxParticles();
        this.maxActive = Math.min(POOL_CAP, forcedMaxParticles ? maxParticles : maxParticles * 2);
        this.automaticInitialSpawning = p.isAutomaticInitialSpawning();
        this.respawnDeadParticles = p.isRespawnDeadParticles();
        this.initialParticlesPerSecond = p.getInitialParticlesPerSecond();
        this.particlesPerSecond = p.getParticlesPerSecond();
        this.lifetimeMin = p.getLifetimeMin();
        this.lifetimeMax = p.getLifetimeMax();
        this.lifeMid = (lifetimeMin + lifetimeMax) * 0.5f;
        this.relativeWarmupTime = p.getRelativeWarmupTime();
        this.warmupTicksPerSecond = p.getWarmupTicksPerSecond();
        this.initialTimeMin = p.getInitialTimeMin();
        this.initialTimeMax = p.getInitialTimeMax();
        this.startLocationOffset = p.getStartLocationOffset();
        this.startLocationMin = p.getStartLocationMin();
        this.startLocationMax = p.getStartLocationMax();
        this.startLocationShape = p.getStartLocationShape();
        this.sphereRadiusMin = p.getSphereRadiusMin();
        this.sphereRadiusMax = p.getSphereRadiusMax();
        this.startLocationPolarMin = p.getStartLocationPolarMin();
        this.startLocationPolarMax = p.getStartLocationPolarMax();
        this.startVelocityMin = p.getStartVelocityMin();
        this.startVelocityMax = p.getStartVelocityMax();
        this.acceleration = p.getAcceleration();
        this.maxAbsVelocity = p.getMaxAbsVelocity();
        this.velocityLossMin = p.getVelocityLossMin();
        this.velocityLossMax = p.getVelocityLossMax();
        this.velocityDirectionFrom = p.getGetVelocityDirectionFrom();
        this.startVelocityRadialMin = p.getStartVelocityRadialMin();
        this.startVelocityRadialMax = p.getStartVelocityRadialMax();
        this.coordinateSystem = p.getCoordinateSystem();
        this.rotationSource = p.getUseRotationFrom();
        this.rotationOffset = new Quat(p.getRotationOffset().vector());
        this.absoluteCoordinates = coordinateSystem == PTCS_ABSOLUTE;
        if (kind == Kind.SPRITE && !p.isStartSizeAuthored()) {
            this.startSizeMin = new Vec3(SPRITE_DEFAULT_SIZE, SPRITE_DEFAULT_SIZE, SPRITE_DEFAULT_SIZE);
            this.startSizeMax = this.startSizeMin;
        } else if (kind == Kind.BEAM && !p.isStartSizeAuthored()) {
            this.startSizeMin = new Vec3(BEAM_DEFAULT_SIZE, BEAM_DEFAULT_SIZE, BEAM_DEFAULT_SIZE);
            this.startSizeMax = this.startSizeMin;
        } else {
            this.startSizeMin = p.getStartSizeMin();
            this.startSizeMax = p.getStartSizeMax();
        }
        this.uniformSize = p.isUniformSize() || (kind == Kind.SPRITE && directionUsage == 0);
        this.nativeUniformSize = p.isUniformSize();
        this.initialDelayMin = p.getInitialDelayMin();
        this.initialDelayMax = p.getInitialDelayMax();
        this.useRevolution = p.isUseRevolution();
        this.revolutionsPerSecondMin = p.getRevolutionsPerSecondMin();
        this.revolutionsPerSecondMax = p.getRevolutionsPerSecondMax();
        this.revolutionCenterOffsetMin = p.getRevolutionCenterOffsetMin();
        this.revolutionCenterOffsetMax = p.getRevolutionCenterOffsetMax();
        this.addLocationFromOtherEmitter = p.getAddLocationFromOtherEmitter();
        this.useRevolutionScale = p.isUseRevolutionScale();
        this.revolutionScale = p.getRevolutionScale().toArray(new float[0][]);
        this.revolutionScaleRepeats = p.getRevolutionScaleRepeats();
        this.useSubdivisionScale = p.isUseSubdivisionScale();
        this.subdivisionScale = p.getSubdivisionScale();
        this.blendBetweenSubdivisions =
                kind == Kind.SPRITE && p instanceof USpriteEmitter sprite && sprite.isBlendBetweenSubdivisions();
        this.useRegularSizeScale = p.isUseRegularSizeScale();
        this.useSizeScale = p.isUseSizeScale();
        this.useAbsoluteTimeForSizeScale = p.isUseAbsoluteTimeForSizeScale();
        this.sizeScaleRepeats = p.getSizeScaleRepeats();
        this.sizeScale = p.getSizeScale().toArray(new float[0][]);
        this.spinParticles = p.isSpinParticles();
        this.startSpinMin = p.getStartSpinMin();
        this.startSpinMax = p.getStartSpinMax();
        this.spinsPerSecondMin = p.getSpinsPerSecondMin();
        this.spinsPerSecondMax = p.getSpinsPerSecondMax();
        this.spinCcwOrCw = p.getSpinCcwOrCw();
        this.fadeIn = p.isFadeIn();
        this.fadeOut = p.isFadeOut();
        this.fadeInEndTime = p.getFadeInEndTime();
        this.fadeOutStartTime = p.getFadeOutStartTime();
        this.fadeInFactor = p.getFadeInFactor();
        this.fadeOutFactor = p.getFadeOutFactor();
        this.opacity = p.getOpacity();
        this.colorMultiplierMin = p.getColorMultiplierMin();
        this.colorMultiplierMax = p.getColorMultiplierMax();
        this.useColorScale = p.isUseColorScale() && !p.getColorScale().isEmpty();
        this.colorScaleRepeats = p.getColorScaleRepeats();
        this.colorScale = p.getColorScale().toArray(new float[0][]);

        this.subDivU = Math.max(1, p.getTextureUSubdivisions());
        this.subDivV = Math.max(1, p.getTextureVSubdivisions());
        this.numCells = subDivU * subDivV;
        this.hasSubUv = kind == Kind.SPRITE && numCells > 1;
        this.useRandomSubdiv = p.isUseRandomSubdivision();
        this.subUScale = 1f / subDivU;
        this.subVScale = 1f / subDivV;
        this.subdivisionStart = p.getSubdivisionStart();
        this.subdivisionEnd = p.getSubdivisionEnd();

        if (kind == Kind.BEAM && p instanceof UBeamEmitter b) {
            this.beamConfig = new BeamConfig(
                    b.getDetermineEndPointBy(),
                    List.copyOf(b.getBeamEndPoints()),
                    b.getBeamDistanceMin(),
                    b.getBeamDistanceMax(),
                    b.getLowFrequencyPoints(),
                    b.getHighFrequencyPoints(),
                    b.getLowFrequencyNoiseMin(),
                    b.getLowFrequencyNoiseMax(),
                    b.getHighFrequencyNoiseMin(),
                    b.getHighFrequencyNoiseMax(),
                    b.getBeamTextureUScale(),
                    b.getBeamTextureVScale(),
                    b.getRotatingSheets(),
                    b.isNoiseDeterminesEndPoint());
        } else {
            this.beamConfig = null;
        }
        allocate(maxActive);
        refreshProjectionWorldNormal();
    }

    public record DiagnosticSource(String child, String type, String asset, List<DebugMaterialSlot> materials) {}

    public enum Kind {
        SPRITE,
        MESH,
        BEAM
    }

    public record BeamConfig(
            int determineEndPointBy,
            List<BeamEndpoint> endpoints,
            float distMin,
            float distMax,
            int lowFrequencyPoints,
            int highFrequencyPoints,
            Vec3 lfNoiseMin,
            Vec3 lfNoiseMax,
            Vec3 hfNoiseMin,
            Vec3 hfNoiseMax,
            float uScale,
            float vScale,
            int rotatingSheets,
            boolean noiseDeterminesEndPoint) {
        BeamConfig scaled(float factor) {
            var scaledEnds = new ArrayList<BeamEndpoint>(endpoints.size());
            for (var end : endpoints) {
                scaledEnds.add(new BeamEndpoint(
                        end.offsetMin().times(factor),
                        end.offsetMax().times(factor),
                        end.weight(),
                        end.actorTag(),
                        end.actorPlace()));
            }
            return new BeamConfig(
                    determineEndPointBy,
                    List.copyOf(scaledEnds),
                    distMin * factor,
                    distMax * factor,
                    lowFrequencyPoints,
                    highFrequencyPoints,
                    lfNoiseMin.times(factor),
                    lfNoiseMax.times(factor),
                    hfNoiseMin.times(factor),
                    hfNoiseMax.times(factor),
                    uScale,
                    vScale,
                    rotatingSheets,
                    noiseDeterminesEndPoint);
        }
    }

    public static Vec3 findNonParallel(Vec3 vector) {
        if (Math.abs(vector.getX()) > NON_PARALLEL) {
            return new Vec3(0f, 1f, 0f);
        }
        return Math.abs(vector.getY()) > NON_PARALLEL ? new Vec3(0f, 0f, 1f) : new Vec3(1f, 0f, 0f);
    }

    public static float getCardWidth(float sizeX, float sizeY) {
        return sizeY < 0f ? sizeX : -sizeX;
    }

    static int warmupTickCount(float lifetimeMin, float lifetimeMax, float relativeWarmupTime, float ticksPerSecond) {
        var middle = (float) (((double) lifetimeMax + lifetimeMin) * 0.5);
        var seconds = (float) ((double) middle * relativeWarmupTime);
        return (int) ((double) ticksPerSecond * seconds);
    }

    static float localBoundingRadius(Box box) {
        var x = Math.max(Math.abs(box.getMinX()), Math.abs(box.getMaxX()));
        var y = Math.max(Math.abs(box.getMinY()), Math.abs(box.getMaxY()));
        var z = Math.max(Math.abs(box.getMinZ()), Math.abs(box.getMaxZ()));
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    static float localBoundingRadius(Box box, Vec3 pivot) {
        var x = Math.max(Math.abs(box.getMinX() - pivot.getX()), Math.abs(box.getMaxX() - pivot.getX()));
        var y = Math.max(Math.abs(box.getMinY() - pivot.getY()), Math.abs(box.getMaxY() - pivot.getY()));
        var z = Math.max(Math.abs(box.getMinZ() - pivot.getZ()), Math.abs(box.getMaxZ() - pivot.getZ()));
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public Kind getKind() {
        return kind;
    }

    public Vec3 getActorPos() {
        return actorPos;
    }

    public Quat getActorRotation() {
        return actorRotation;
    }

    public Vec3 getActorScale() {
        return actorScale;
    }

    public Vec3 getMeshPrePivot() {
        return meshPrePivot;
    }

    public boolean isAbsoluteCoordinates() {
        return absoluteCoordinates;
    }

    public void setVisibility(EmitterVisibility visibility) {
        this.visibility = visibility;
        if (actorCullState != null) {
            actorCullState.visibilityChanged(this);
        }
    }

    public EmitterVisibility getVisibility() {
        return visibility;
    }

    public void setActorRotation(Quat rotation) {
        setActorTransform(rotation, actorScale);
    }

    public void setSiblingResolver(IntFunction<ParticleEmitter> resolver) {
        siblingResolver = resolver;
    }

    public float getRawX(int i) {
        return px[i];
    }

    public float getRawY(int i) {
        return py[i];
    }

    public float getRawZ(int i) {
        return pz[i];
    }

    public int getActiveSlots() {
        return Math.min(maxActive, activeParticles);
    }

    public float getVisibilityRatio(Vec3 cameraPos) {
        return visibility.opacityRatioAt(actorPos, cameraPos);
    }

    float getVisibilityRatioAtDistanceSquared2d(float distanceSquared) {
        return visibility.opacityRatioAtDistanceSquared(distanceSquared);
    }

    public float getCustomVisibilityRatio(float distance, float limit) {
        return visibility.opacityRatioForLimit(distance, limit);
    }

    public EmitterTimeWindows getTimeWindows() {
        return timeWindows;
    }

    public void setTimeWindows(EmitterTimeWindows windows) {
        timeWindows = windows == null ? EmitterTimeWindows.ALWAYS : windows;
        timeWindowsLabel = timeWindows.label();
    }

    float getOpacityAtHour(float actorRatio, double hour, MapEventState events) {
        if (timeWindows.always() || actorRatio <= 0f) {
            return actorRatio;
        }
        return switch (events.clock(timeWindowsLabel)) {
            case FOLLOW -> timeWindows.ratio(hour);
            case ALWAYS -> actorRatio;
            case OFF -> 0f;
        };
    }

    public void setOpacityRatio(float ratio) {
        if (Float.floatToIntBits(opacityRatio) != Float.floatToIntBits(ratio)) {
            opacityRatio = ratio;
            spritePayloadRevision++;
        }
    }

    public void setTranslucentRenderPriority(int priority) {
        translucentRenderPriority = priority;
    }

    public int getTranslucentRenderPriority() {
        return translucentRenderPriority;
    }

    public boolean isDeferredAfterWater() {
        return translucentRenderPriority < 0;
    }

    public ParticleEmitter createTwin(String actorName, Vec3 actorPos, UParticleEmitter source) {
        var twin = new ParticleEmitter(source, new Vec3(actorPos), kind, blend, initialSeed ^ actorName.hashCode());
        twin.setActorTransform(new Quat(actorRotation), new Vec3(actorScale));
        twin.ownerRotation = ownerRotation;
        twin.meshPrePivot = meshPrePivot;
        twin.particleColorSurfaces = particleColorSurfaces;
        twin.unlitSurfaces = unlitSurfaces;
        twin.severalPassSurfaces = severalPassSurfaces;
        twin.visibility = visibility;
        twin.translucentRenderPriority = translucentRenderPriority;
        twin.setRenderActorIdentity(actorName, renderActorOrder, renderChildOrder);
        twin.sceneMap = sceneMap;
        twin.debugName = debugName;
        twin.spriteImage = spriteImage;
        twin.diagnosticSource = new DiagnosticSource(
                diagnosticSource.child(),
                diagnosticSource.type(),
                diagnosticSource.asset(),
                diagnosticSource.materials());
        twin.blackBackground = blackBackground;
        twin.meshBounds = meshBounds;
        twin.renderTwoSided = renderTwoSided;
        twin.beamConfig = withActorPlaces(twin.beamConfig, beamConfig);
        if (meshAnimationSequenceFrames > 0 && meshAnimationFrames > 1) {
            twin.configureMeshAnimation(
                    meshAnimationFrames,
                    meshAnimationStartFrame,
                    meshAnimationSequenceFrames,
                    meshAnimationFramesPerSecond);
        }
        return twin;
    }

    public void setRenderActorIdentity(String actorName, int actorOrder, int childOrder) {
        renderActorName = actorName == null || actorName.isBlank() ? "synthetic" : actorName;
        var separator = renderActorName.lastIndexOf('.');
        renderActorPackage = separator < 0 ? renderActorName : renderActorName.substring(0, separator);
        renderActorOrder = actorOrder;
        renderChildOrder = childOrder;
    }

    public String getRenderActorName() {
        return renderActorName;
    }

    public String getRenderActorPackage() {
        return renderActorPackage;
    }

    public String getSceneMap() {
        return sceneMap != null ? sceneMap : renderActorPackage;
    }

    public void setSceneMap(String map) {
        sceneMap = map;
    }

    public int getRenderActorOrder() {
        return renderActorOrder;
    }

    public int getRenderChildOrder() {
        return renderChildOrder;
    }

    void setNearThisFrame(boolean near) {
        nearThisFrame = near;
    }

    boolean isNearThisFrame() {
        return nearThisFrame;
    }

    float getOpacityRatio() {
        return opacityRatio;
    }

    void setActorCullState(ParticleActorCullState state) {
        actorCullState = state;
    }

    ParticleActorCullState getActorCullState() {
        return actorCullState;
    }

    @Nullable
    URotator getOwnerRotation() {
        return ownerRotation;
    }

    int getOwnerTurns() {
        return ownerTurns;
    }

    boolean hasSiblingTiming() {
        return addLocationFromOtherEmitter >= 0 || initialDelayMin > 0f || initialDelayMax > 0f;
    }

    public boolean doesMeshUseActorRenderRotation() {
        return coordinateSystem == PTCS_RELATIVE;
    }

    public void setActorTransform(Quat rotation, Vec3 scale) {
        if (absoluteCoordinates) {
            return;
        }
        this.actorRotation = rotation;
        this.actorScale = scale;
        this.actorQx = rotation.x.floatValue();
        this.actorQy = rotation.y.floatValue();
        this.actorQz = rotation.z.floatValue();
        this.actorQw = rotation.w.floatValue();
        refreshProjectionWorldNormal();
        this.liveWorldBoundsCurrent = false;
        this.nativeSortBoundsCurrent = false;
        spritePayloadRevision++;
    }

    public void setSizeScale(float factor) {
        spawnScale *= factor;
        if (beamConfig != null) {
            beamConfig = beamConfig.scaled(factor);
        }
    }

    public void kill() {
        killed = true;
    }

    public void setActorTransform(URotator rotation, Vec3 scale) {
        ownerRotation = rotation;
        ownerTurns++;
        setActorTransform(new Quat(rotation.vector()), scale);
    }

    public void turnActor(URotator rotation) {
        setActorTransform(rotation, actorScale);
    }

    public void flyActor(URotator direction) {
        var state = actorCullState;
        if (state == null || !visibility.selfRotation()) {
            turnActor(direction);
            return;
        }
        state.fly(direction);
    }

    public void carryActor(Vec3 position, URotator rotation) {
        moveActor(position);
        var state = actorCullState;
        if (state != null && state.isRotating()) {
            state.fly(rotation);
        } else {
            turnActor(rotation);
        }
    }

    public void setMeshPrePivot(Vec3 prePivot) {
        if (prePivot == null) {
            throw new IllegalArgumentException("mesh emitter pre-pivot is required");
        }
        this.meshPrePivot = prePivot;
        this.liveWorldBoundsCurrent = false;
        this.nativeSortBoundsCurrent = false;
    }

    public int getBlend() {
        return blend;
    }

    public boolean isLargeRefraction() {
        return refraction == 2;
    }

    public float getRefrUScale() {
        return refrUScale;
    }

    public float getRefrVScale() {
        return refrVScale;
    }

    public boolean isAlphaTest() {
        return alphaTest;
    }

    public int getAlphaRef() {
        return alphaRef;
    }

    public boolean isZTest() {
        return zTest;
    }

    public boolean isZWrite() {
        return zWrite;
    }

    public boolean isDisableFogging() {
        return disableFogging;
    }

    public boolean isUseSoftParticle() {
        return useSoftParticle;
    }

    public float getSoftParticleFadeDist() {
        return softParticleFadeDist;
    }

    public boolean isUseMeshBlendMode() {
        return useMeshBlendMode;
    }

    public boolean isRenderTwoSided() {
        return renderTwoSided || litByActor && useParticleColor;
    }

    public boolean takesSidesFromMeshMaterial() {
        return litByActor && !useParticleColor;
    }

    public void setRenderTwoSided(boolean renderTwoSided) {
        this.renderTwoSided = renderTwoSided;
    }

    public boolean isUseParticleColor() {
        return useParticleColor;
    }

    public void setMeshSurfaceRules(int particleColorSurfaces, int unlitSurfaces, int severalPassSurfaces) {
        this.particleColorSurfaces = particleColorSurfaces;
        this.unlitSurfaces = unlitSurfaces;
        this.severalPassSurfaces = severalPassSurfaces;
    }

    public boolean hasParticleColorStage(int surface) {
        return useParticleColor
                && !(surface >= 0 && surface < Integer.SIZE && (severalPassSurfaces >>> surface & 1) != 0);
    }

    public boolean drawsConstantAsParticleColor(int surface) {
        return surface >= 0 && surface < Integer.SIZE && (particleColorSurfaces >>> surface & 1) != 0;
    }

    public boolean takesParticleColor(int surface) {
        return hasParticleColorStage(surface) || drawsConstantAsParticleColor(surface);
    }

    public boolean isLitByActor() {
        return litByActor;
    }

    public boolean takesLight(int surface) {
        return litByActor && !(surface >= 0 && surface < Integer.SIZE && (unlitSurfaces >>> surface & 1) != 0);
    }

    public int getDirectionUsage() {
        return directionUsage;
    }

    public Vec3 getWorldVelDir(int i) {
        var v = new Vec3(vx[i], vy[i], vz[i]);
        if (coordinateSystem == PTCS_RELATIVE) {
            v = actorRotation.times(v);
        }
        var len = (float) Math.sqrt(v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ());
        return len < 1e-4f ? new Vec3(0f, 0f, 0f) : new Vec3(v.getX() / len, v.getY() / len, v.getZ() / len);
    }

    public Vec3 getWorldProjectionNormal() {
        var n = projectionNormal;
        if (rotationSource == PTRS_ACTOR) {
            n = actorRotation.times(n);
        }
        if (coordinateSystem == PTCS_RELATIVE) {
            n = actorRotation.times(n);
        }
        var len = (float) Math.sqrt(n.getX() * n.getX() + n.getY() * n.getY() + n.getZ() * n.getZ());
        return len < 1e-4f ? new Vec3(0f, 0f, 1f) : new Vec3(n.getX() / len, n.getY() / len, n.getZ() / len);
    }

    public record CardPlane(Vec3 right, Vec3 up, float rise) {}

    public CardPlane getCardPlane() {
        var normal = projectionNormal;
        var length = normal.length();
        normal = length < 1e-4f ? new Vec3(0f, 0f, 1f) : normal.times(1f / length);
        if (rotationSource == PTRS_ACTOR) {
            normal = actorRotation.times(normal);
        }
        var up = normal.cross(findNonParallel(normal));
        var rise = up.length();
        up = rise < 1e-6f ? new Vec3(0f, 1f, 0f) : up.times(1f / rise);
        var right = up.cross(normal).normalize();
        if (coordinateSystem == PTCS_RELATIVE) {
            right = actorRotation.times(right);
            up = actorRotation.times(up);
        }
        return new CardPlane(right, up, rise);
    }

    public void writeSpriteSpatial(int i, float[] target) {
        writeSpriteSpatial(i, target, 0, 3);
    }

    public void writeSpriteSpatial(int i, float[] target, int positionOffset, int directionOffset) {
        if (target == null
                || positionOffset < 0
                || directionOffset < 0
                || positionOffset > target.length - 3
                || directionOffset > target.length - 3) {
            throw new IllegalArgumentException("sprite spatial target fields require three floats each");
        }
        writeRenderVector(px[i], py[i], pz[i], target, positionOffset);
        target[positionOffset] += actorX;
        target[positionOffset + 1] += actorY;
        target[positionOffset + 2] += actorZ;
        switch (directionUsage) {
            case 1 -> {
                writeRenderVector(vx[i], vy[i], vz[i], target, directionOffset);
                normalizeDirection(target, directionOffset, 0f, 0f, 0f);
            }
            case 4 -> {
                target[directionOffset] = projectionWorldX;
                target[directionOffset + 1] = projectionWorldY;
                target[directionOffset + 2] = projectionWorldZ;
            }
            default -> {
                target[directionOffset] = 0f;
                target[directionOffset + 1] = 0f;
                target[directionOffset + 2] = 0f;
            }
        }
    }

    public void writeSpriteGpuSpatial(int i, float[] target, int positionOffset, int directionOffset) {
        target[positionOffset] = px[i];
        target[positionOffset + 1] = py[i];
        target[positionOffset + 2] = pz[i];
        switch (directionUsage) {
            case 1 -> {
                target[directionOffset] = vx[i];
                target[directionOffset + 1] = vy[i];
                target[directionOffset + 2] = vz[i];
            }
            case 4 -> {
                target[directionOffset] = projectionWorldX;
                target[directionOffset + 1] = projectionWorldY;
                target[directionOffset + 2] = projectionWorldZ;
            }
            default -> {
                target[directionOffset] = 0f;
                target[directionOffset + 1] = 0f;
                target[directionOffset + 2] = 0f;
            }
        }
    }

    public int appendSpriteGpuInstances(float[] target, int targetOffset, int stride, float actorTransformCode) {
        var limit = getActiveParticleLimit();
        if (target == null
                || targetOffset < 0
                || stride < 20
                || (long) targetOffset + (long) limit * stride > target.length) {
            throw new IllegalArgumentException("sprite instance target cannot hold populated particle slots");
        }
        var scaleMultiplierX = SPRITE_QUAD_SCALE;
        var scaleMultiplierY = SPRITE_QUAD_SCALE;
        var alphaMultiplier = alphaOpacity();
        if (directionUsage == 0) {
            return appendCameraFacingSpriteGpuInstances(
                    target,
                    targetOffset,
                    stride,
                    limit,
                    scaleMultiplierX,
                    scaleMultiplierY,
                    alphaMultiplier,
                    actorTransformCode);
        }
        var written = 0;
        var offset = targetOffset;
        var rgbMultiplier = rgbOpacity();
        for (var index = 0; index < limit; index++) {
            if ((flags[index] & ACTIVE) == 0) {
                continue;
            }
            var alpha = outA[index] * alphaMultiplier;
            if (!isSpriteColorVisible(index)) {
                continue;
            }
            target[offset] = px[index];
            target[offset + 1] = py[index];
            target[offset + 2] = pz[index];
            target[offset + 3] = outScaleX[index] * scaleMultiplierX;
            target[offset + 4] = outScaleY[index] * scaleMultiplierY;
            target[offset + 5] = outPitch[index];
            target[offset + 6] = directionUsage;
            target[offset + 7] = outSubBlend[index];
            target[offset + 8] = outR[index] * rgbMultiplier;
            target[offset + 9] = outG[index] * rgbMultiplier;
            target[offset + 10] = outB[index] * rgbMultiplier;
            target[offset + 11] = alpha;
            target[offset + 12] = outU0[index];
            target[offset + 13] = outV0[index];
            target[offset + 14] = subUScale;
            target[offset + 15] = subVScale;
            if (directionUsage == 1) {
                target[offset + 16] = vx[index];
                target[offset + 17] = vy[index];
                target[offset + 18] = vz[index];
            } else if (directionUsage == 4) {
                target[offset + 16] = projectionWorldX;
                target[offset + 17] = projectionWorldY;
                target[offset + 18] = projectionWorldZ;
            } else {
                target[offset + 16] = 0f;
                target[offset + 17] = 0f;
                target[offset + 18] = 0f;
            }
            target[offset + 19] = actorTransformCode;
            offset += stride;
            written++;
        }
        return written;
    }

    public Texture getSpriteImage() {
        return spriteImage;
    }

    public void setSpriteImage(Texture image) {
        this.spriteImage = image;
    }

    public void setMeshBounds(Box bounds) {
        this.meshBounds = bounds;
        this.liveWorldBoundsCurrent = false;
        this.nativeSortBoundsCurrent = false;
    }

    public void configureMeshAnimation(int frames, int startFrame, int sequenceFrames, float framesPerSecond) {
        if (frames < 1
                || startFrame < 0
                || sequenceFrames < 0
                || startFrame + sequenceFrames > frames
                || !Float.isFinite(framesPerSecond)) {
            throw new IllegalArgumentException("invalid vertex-mesh animation "
                    + frames
                    + "/"
                    + startFrame
                    + "/"
                    + sequenceFrames
                    + "/"
                    + framesPerSecond);
        }
        meshAnimationFrames = frames;
        meshAnimationStartFrame = startFrame;
        meshAnimationSequenceFrames = sequenceFrames;
        meshAnimationFramesPerSecond = framesPerSecond;
    }

    public int getMeshAnimationFrame(int particle) {
        if (!isMeshAnimationPlaying(particle)) {
            return 0;
        }
        var frame = (int) Math.floor(time[particle] * meshAnimationFramesPerSecond);
        frame = Math.floorMod(frame, meshAnimationSequenceFrames);
        return meshAnimationStartFrame + frame;
    }

    public float getMeshAnimationBlend(int particle) {
        if (!isMeshAnimationPlaying(particle)) {
            return 0f;
        }
        var position = time[particle] * meshAnimationFramesPerSecond;
        return position - (float) Math.floor(position);
    }

    public int getMeshAnimationFollowingFrame(int frame) {
        if (meshAnimationFrames <= 1 || meshAnimationSequenceFrames <= 0) {
            return frame;
        }
        return meshAnimationStartFrame
                + Math.floorMod(frame - meshAnimationStartFrame + 1, meshAnimationSequenceFrames);
    }

    public String getDebugName() {
        return debugName;
    }

    public DiagnosticSource getDiagnosticSource() {
        return diagnosticSource;
    }

    public void setDiagnosticMaterials(List<DebugMaterialSlot> materials) {
        diagnosticSource = new DiagnosticSource(
                diagnosticSource.child(), diagnosticSource.type(), diagnosticSource.asset(), List.copyOf(materials));
    }

    public int getAnimationFrames() {
        return meshAnimationSequenceFrames;
    }

    public float getAnimationFramesPerSecond() {
        return meshAnimationFramesPerSecond;
    }

    public int getAnimationStartFrame() {
        return meshAnimationStartFrame;
    }

    public void setDebugName(String debugName) {
        this.debugName = debugName;
    }

    public boolean isBlackBackground() {
        return blackBackground;
    }

    public void setBlackBackground(boolean blackBackground) {
        this.blackBackground = blackBackground;
    }

    public int getMaxActive() {
        return maxActive;
    }

    public int getActiveParticleLimit() {
        return Math.min(maxActive, activeParticles);
    }

    public long getRetainedCpuBytes() {
        long bytes = 0L;
        for (var values : new float[][] {
            px,
            py,
            pz,
            vx,
            vy,
            vz,
            ssx,
            ssy,
            ssz,
            spx,
            spy,
            spz,
            rpx,
            rpy,
            rpz,
            cmR,
            cmG,
            cmB,
            time,
            maxLifetime,
            outScaleX,
            outScaleY,
            outScaleZ,
            outPitch,
            outYaw,
            outRoll,
            outR,
            outG,
            outB,
            outA,
            outU0,
            outV0,
            beamEndX,
            beamEndY,
            beamEndZ
        }) {
            if (values != null) {
                bytes = Math.addExact(bytes, (long) values.length * Float.BYTES);
            }
        }
        bytes = Math.addExact(bytes, (long) flags.length * Integer.BYTES);
        bytes = Math.addExact(bytes, (long) subCell.length * Integer.BYTES);
        if (beamSeed != null) {
            bytes = Math.addExact(bytes, (long) beamSeed.length * Long.BYTES);
        }
        bytes = Math.addExact(bytes, (long) colorScaleScratch.length * Float.BYTES);
        bytes = Math.addExact(bytes, (long) rngState.length * Long.BYTES);
        return bytes;
    }

    public boolean isActive(int i) {
        return (flags[i] & ACTIVE) != 0;
    }

    public float getLocalX(int i) {
        return px[i];
    }

    public float getLocalY(int i) {
        return py[i];
    }

    public float getLocalZ(int i) {
        return pz[i];
    }

    public float getScaleX(int i) {
        return outScaleX[i];
    }

    public float getScaleY(int i) {
        return outScaleY[i];
    }

    public float getScaleZ(int i) {
        return outScaleZ[i];
    }

    public float getSpriteRenderScaleX(int i) {
        return outScaleX[i];
    }

    public float getSpriteRenderScaleY(int i) {
        return outScaleY[i];
    }

    public float getPitch(int i) {
        return outPitch[i];
    }

    public float getYaw(int i) {
        return outYaw[i];
    }

    public float getRoll(int i) {
        return outRoll[i];
    }

    public float getR(int i) {
        return outR[i] * rgbOpacity();
    }

    public float getG(int i) {
        return outG[i] * rgbOpacity();
    }

    public float getB(int i) {
        return outB[i] * rgbOpacity();
    }

    public float getA(int i) {
        return outA[i] * alphaOpacity();
    }

    public float getSubU0(int i) {
        return outU0[i];
    }

    public float getSubV0(int i) {
        return outV0[i];
    }

    public float getSubUScale() {
        return subUScale;
    }

    public float getSubVScale() {
        return subVScale;
    }

    public boolean hasSubUv() {
        return hasSubUv;
    }

    public BeamConfig getBeamConfig() {
        return beamConfig;
    }

    public float getBeamEndX(int i) {
        return beamEndX[i];
    }

    public float getBeamEndY(int i) {
        return beamEndY[i];
    }

    public float getBeamEndZ(int i) {
        return beamEndZ[i];
    }

    public long getBeamSeed(int i) {
        return beamSeed[i];
    }

    public boolean isDelayed() {
        return initialDelayRemaining > 0f;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    public boolean isHeld() {
        return held;
    }

    public void update(float dt) {
        if (!(dt > 0f) || !Float.isFinite(dt)) {
            return;
        }
        simulatedSeconds += dt;
        if (Float.isNaN(initialDelayRemaining)) {
            initialDelayRemaining = randRange(initialDelayMin, initialDelayMax);
        }
        if (initialDelayRemaining > 0f) {
            initialDelayRemaining -= dt;
            if (initialDelayRemaining > 0f) {
                keepsActorAlive = false;
                return;
            }
        }
        if (!warmedUp) {
            warmUp();
            warmedUp = true;
        }
        keepsActorAlive = !finished;
        updateParticles(dt, true);
        spritePayloadRevision++;
    }

    public float getSimulatedSeconds() {
        return simulatedSeconds;
    }

    public boolean isWarmedUp() {
        return warmedUp;
    }

    public void startAsSpawned() {
        steadyStart = false;
    }

    public void adjustLife(EmitterLife life) {
        if (warmedUp) {
            throw new IllegalStateException("An emitter's life changes only before it runs");
        }
        lifeAdjusted |= life.lifetimeMin() != lifetimeMin || life.lifetimeMax() != lifetimeMax;
        lifetimeMin = life.lifetimeMin();
        lifetimeMax = life.lifetimeMax();
        lifeMid = (lifetimeMin + lifetimeMax) * 0.5f;
        fadeOutStartTime = life.fadeOutStartTime();
        if (forcedMaxParticles) {
            maxParticles = Math.clamp(life.maxParticles(), 0, POOL_CAP);
            var pool = maxParticles;
            if (pool != maxActive) {
                maxActive = pool;
                allocate(pool);
            }
        }
        spritePayloadRevision++;
    }

    public EmitterLife getLife() {
        return new EmitterLife(lifetimeMin, lifetimeMax, fadeOutStartTime, maxParticles);
    }

    public void aimBeam(Vec3 target) {
        if (beamConfig == null) {
            return;
        }
        beamAimed = true;
        beamTargetX = target.getX();
        beamTargetY = target.getY();
        beamTargetZ = target.getZ();
    }

    public boolean isBeamAimed() {
        return beamAimed;
    }

    public void setMaxParticles(int count) {
        if (warmedUp) {
            throw new IllegalStateException("An emitter's count changes only before it runs");
        }
        maxParticles = Math.clamp(count, 0, POOL_CAP);
        var pool = forcedMaxParticles ? maxParticles : Math.min(POOL_CAP, Math.max(maxActive, maxParticles));
        if (pool != maxActive) {
            maxActive = pool;
            allocate(pool);
        }
        spritePayloadRevision++;
    }

    public int getMaxParticles() {
        return maxParticles;
    }

    public float getInitialParticlesPerSecond() {
        return initialParticlesPerSecond;
    }

    public void moveActor(Vec3 position) {
        actorPos = new Vec3(position.getX(), position.getY(), position.getZ());
        actorX = position.getX();
        actorY = position.getY();
        actorZ = position.getZ();
        liveWorldBoundsCurrent = false;
        nativeSortBoundsCurrent = false;
        spritePayloadRevision++;
    }

    public boolean hasLiveParticles() {
        for (int i = 0; i < maxActive; i++) {
            if ((flags[i] & ACTIVE) != 0) {
                return true;
            }
        }
        return false;
    }

    public boolean isFinished() {
        return finished;
    }

    boolean keepsActorAlive() {
        return keepsActorAlive;
    }

    public void restart() {
        finished = false;
        keepsActorAlive = true;
        warmedUp = false;
        afterReset = true;
        initialDelayRemaining = Float.NaN;
        spawnCounter = 0;
        Arrays.fill(flags, 0);
        Arrays.fill(time, 0f);
        Arrays.fill(maxLifetime, 0f);
        Arrays.fill(subCell, -1);
        activeParticles = 0;
        particleIndex = 0;
        ppsFraction = 0f;
        liveWorldBounds.clear();
        liveWorldBoundsCurrent = false;
        nativeSortBoundsCurrent = false;
        liveMaximumParticleDiameter = 0f;
        liveMaximumParticleDiameterCurrent = false;
        deferredSimulationSeconds = 0f;
        spritePayloadRevision++;
    }

    public void resetSimulation() {
        finished = false;
        keepsActorAlive = true;
        initialDelayRemaining = Float.NaN;
        spawnCounter = 0;
        Arrays.fill(flags, 0);
        Arrays.fill(time, 0f);
        Arrays.fill(maxLifetime, 0f);
        Arrays.fill(subCell, -1);
        rngState[0] = initialSeed;
        simulatedSeconds = 0f;
        activeParticles = 0;
        particleIndex = 0;
        ppsFraction = 0f;
        warmedUp = false;
        afterReset = false;
        opacityRatio = 1f;
        liveWorldBounds.clear();
        liveWorldBoundsCurrent = false;
        nativeSortBoundsCurrent = false;
        liveMaximumParticleDiameter = 0f;
        liveMaximumParticleDiameterCurrent = false;
        projectedParticleCullInitialized = false;
        projectedParticleCulled = false;
        deferredSimulationSeconds = 0f;
        spritePayloadRevision++;
    }

    public long getSpritePayloadRevision() {
        return spritePayloadRevision;
    }

    public int getActiveParticleCount() {
        int c = 0;
        for (int i = 0; i < maxActive; i++) {
            if ((flags[i] & ACTIVE) != 0) {
                c++;
            }
        }
        return c;
    }

    public Vec3 getSpriteWorldPos(int i) {
        return localParticleWorldPos(i);
    }

    public Vec3 getMeshWorldPos(int i) {
        var local = new Vec3(px[i], py[i], pz[i]);
        if (coordinateSystem == PTCS_RELATIVE) {
            local = actorRotation.times(local);
        }
        return new Vec3(actorPos.getX() + local.getX(), actorPos.getY() + local.getY(), actorPos.getZ() + local.getZ());
    }

    public void writeMeshWorldPos(int i, float[] target) {
        if (target == null || target.length < 3) {
            throw new IllegalArgumentException("mesh world-position target requires at least three floats");
        }
        if (coordinateSystem == PTCS_RELATIVE) {
            rotateActorTo(px[i], py[i], pz[i], target, 0);
        } else {
            target[0] = px[i];
            target[1] = py[i];
            target[2] = pz[i];
        }
        target[0] += actorX;
        target[1] += actorY;
        target[2] += actorZ;
    }

    public boolean addNativeSortBounds(Box out) {
        if (!nativeSortBoundsCurrent) {
            rebuildNativeSortBounds();
        }
        if (!nativeSortBounds.isValid()) {
            return false;
        }
        out.add(nativeSortBounds.getMinX(), nativeSortBounds.getMinY(), nativeSortBounds.getMinZ());
        out.add(nativeSortBounds.getMaxX(), nativeSortBounds.getMaxY(), nativeSortBounds.getMaxZ());
        return true;
    }

    public boolean computeWorldBounds(Box out) {
        if (!liveWorldBoundsCurrent) {
            rebuildLiveWorldBounds();
        }
        if (!liveWorldBounds.isValid()) {
            return false;
        }
        if (out != liveWorldBounds) {
            out.add(liveWorldBounds.getMinX(), liveWorldBounds.getMinY(), liveWorldBounds.getMinZ());
            out.add(liveWorldBounds.getMaxX(), liveWorldBounds.getMaxY(), liveWorldBounds.getMaxZ());
        }
        return true;
    }

    public boolean isProjectedParticleSizeInvisible(
            float[] viewProjection, int renderWidth, int renderHeight, float minimumProjectedPixels) {
        if (minimumProjectedPixels <= 0f) {
            return false;
        }
        return getProjectedMaximumParticlePixels(viewProjection, renderWidth, renderHeight) < minimumProjectedPixels;
    }

    public boolean isProjectedParticleSizeInvisibleStable(
            float[] viewProjection, int renderWidth, int renderHeight, float minimumProjectedPixels) {
        if (minimumProjectedPixels <= 0f) {
            projectedParticleCullInitialized = false;
            projectedParticleCulled = false;
            return false;
        }
        var projectedPixels = getProjectedMaximumParticlePixels(viewProjection, renderWidth, renderHeight);
        if (!projectedParticleCullInitialized) {
            projectedParticleCullInitialized = true;
            projectedParticleCulled = projectedPixels < minimumProjectedPixels;
        } else if (projectedParticleCulled) {
            projectedParticleCulled = projectedPixels < minimumProjectedPixels * 1.25f;
        } else {
            projectedParticleCulled = projectedPixels < minimumProjectedPixels * 0.75f;
        }
        return projectedParticleCulled;
    }

    float getProjectedMaximumParticlePixels(float[] viewProjection, int renderWidth, int renderHeight) {
        if (kind != Kind.SPRITE
                || viewProjection == null
                || viewProjection.length < 16
                || renderWidth <= 0
                || renderHeight <= 0) {
            return Float.POSITIVE_INFINITY;
        }
        if (!liveWorldBoundsCurrent || !liveMaximumParticleDiameterCurrent) {
            rebuildLiveWorldBounds();
        }
        if (!liveWorldBounds.isValid() || liveMaximumParticleDiameter <= 0f) {
            return Float.POSITIVE_INFINITY;
        }
        var closestClipW = Float.POSITIVE_INFINITY;
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? liveWorldBounds.getMinX() : liveWorldBounds.getMaxX();
            var y = (corner & 2) == 0 ? liveWorldBounds.getMinY() : liveWorldBounds.getMaxY();
            var z = (corner & 4) == 0 ? liveWorldBounds.getMinZ() : liveWorldBounds.getMaxZ();
            var clipW = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15];
            if (clipW <= 0.00001f) {
                return Float.POSITIVE_INFINITY;
            }
            closestClipW = Math.min(closestClipW, clipW);
        }
        var xProjectionScale = (float) Math.sqrt(viewProjection[0] * viewProjection[0]
                        + viewProjection[4] * viewProjection[4]
                        + viewProjection[8] * viewProjection[8])
                * renderWidth
                * 0.5f;
        var yProjectionScale = (float) Math.sqrt(viewProjection[1] * viewProjection[1]
                        + viewProjection[5] * viewProjection[5]
                        + viewProjection[9] * viewProjection[9])
                * renderHeight
                * 0.5f;
        return liveMaximumParticleDiameter * Math.max(xProjectionScale, yProjectionScale) / closestClipW;
    }

    float getSimulationDelta(
            int frame,
            float frameDelta,
            float[] viewProjection,
            int renderWidth,
            int renderHeight,
            float terminalProjectedPixels,
            float simulationLodProjectedPixels) {
        if (!(frameDelta > 0f) || !Float.isFinite(frameDelta)) {
            return 0f;
        }
        var projectedPixels = getProjectedMaximumParticlePixels(viewProjection, renderWidth, renderHeight);
        var refreshMask = projectedPixels < terminalProjectedPixels
                ? ParticleActorCullState.REFRESH_FRAME_MASK
                : projectedPixels < Math.min(2f, simulationLodProjectedPixels)
                        ? 3
                        : projectedPixels < simulationLodProjectedPixels ? 1 : 0;
        if (refreshMask != 0 && (frame & refreshMask) != (simulationRefreshPhase & refreshMask)) {
            deferredSimulationSeconds += frameDelta;
            return -1f;
        }
        var delta = frameDelta + deferredSimulationSeconds;
        deferredSimulationSeconds = 0f;
        return delta;
    }

    private int appendCameraFacingSpriteGpuInstances(
            float[] target,
            int targetOffset,
            int stride,
            int limit,
            float scaleMultiplierX,
            float scaleMultiplierY,
            float alphaMultiplier,
            float actorTransformCode) {
        var written = 0;
        var offset = targetOffset;
        var rgbMultiplier = rgbOpacity();
        for (var index = 0; index < limit; index++) {
            if ((flags[index] & ACTIVE) == 0) {
                continue;
            }
            var alpha = outA[index] * alphaMultiplier;
            if (!isSpriteColorVisible(index)) {
                continue;
            }
            target[offset] = px[index];
            target[offset + 1] = py[index];
            target[offset + 2] = pz[index];
            target[offset + 3] = outScaleX[index] * scaleMultiplierX;
            target[offset + 4] = outScaleY[index] * scaleMultiplierY;
            target[offset + 5] = outPitch[index];
            target[offset + 6] = directionUsage;
            target[offset + 7] = outSubBlend[index];
            target[offset + 8] = outR[index] * rgbMultiplier;
            target[offset + 9] = outG[index] * rgbMultiplier;
            target[offset + 10] = outB[index] * rgbMultiplier;
            target[offset + 11] = alpha;
            target[offset + 12] = outU0[index];
            target[offset + 13] = outV0[index];
            target[offset + 14] = subUScale;
            target[offset + 15] = subVScale;
            target[offset + 16] = 0f;
            target[offset + 17] = 0f;
            target[offset + 18] = 0f;
            target[offset + 19] = actorTransformCode;
            offset += stride;
            written++;
        }
        return written;
    }

    private void writeRenderVector(float x, float y, float z, float[] target, int offset) {
        if (coordinateSystem == PTCS_RELATIVE) {
            rotateActorTo(x, y, z, target, offset);
        } else {
            target[offset] = x;
            target[offset + 1] = y;
            target[offset + 2] = z;
        }
    }

    private void rotateActorTo(float vx, float vy, float vz, float[] target, int offset) {
        var tx = 2f * (actorQy * vz - actorQz * vy);
        var ty = 2f * (actorQz * vx - actorQx * vz);
        var tz = 2f * (actorQx * vy - actorQy * vx);
        target[offset] = vx + actorQw * tx + actorQy * tz - actorQz * ty;
        target[offset + 1] = vy + actorQw * ty + actorQz * tx - actorQx * tz;
        target[offset + 2] = vz + actorQw * tz + actorQx * ty - actorQy * tx;
    }

    private void refreshProjectionWorldNormal() {
        var x = projectionNormal.getX();
        var y = projectionNormal.getY();
        var z = projectionNormal.getZ();
        if (rotationSource == PTRS_ACTOR) {
            var tx = 2f * (actorQy * z - actorQz * y);
            var ty = 2f * (actorQz * x - actorQx * z);
            var tz = 2f * (actorQx * y - actorQy * x);
            x += actorQw * tx + actorQy * tz - actorQz * ty;
            y += actorQw * ty + actorQz * tx - actorQx * tz;
            z += actorQw * tz + actorQx * ty - actorQy * tx;
        }
        if (coordinateSystem == PTCS_RELATIVE) {
            var tx = 2f * (actorQy * z - actorQz * y);
            var ty = 2f * (actorQz * x - actorQx * z);
            var tz = 2f * (actorQx * y - actorQy * x);
            x += actorQw * tx + actorQy * tz - actorQz * ty;
            y += actorQw * ty + actorQz * tx - actorQx * tz;
            z += actorQw * tz + actorQx * ty - actorQy * tx;
        }
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-4f) {
            projectionWorldX = 0f;
            projectionWorldY = 0f;
            projectionWorldZ = 1f;
            return;
        }
        projectionWorldX = x / length;
        projectionWorldY = y / length;
        projectionWorldZ = z / length;
    }

    private boolean isMeshAnimationPlaying(int particle) {
        return meshAnimationFrames > 1
                && meshAnimationSequenceFrames > 0
                && particle >= 0
                && particle < maxActive
                && isActive(particle);
    }

    private void warmUp() {
        if (isKindDisabled()) {
            return;
        }
        if (relativeWarmupTime > 0f && warmupTicksPerSecond > 0f && lifeMid > 0f) {
            var tickCount = warmupTickCount(lifetimeMin, lifetimeMax, relativeWarmupTime, warmupTicksPerSecond);
            var tickDelta = (float) (1.0 / warmupTicksPerSecond);
            for (var i = 0; i < tickCount; i++) {
                updateParticles(tickDelta, false);
            }
            return;
        }
        if (steadyStart && !afterReset && (forcedMaxParticles || automaticInitialSpawning)) {
            for (int i = 0; i < maxParticles && i < maxActive; i++) {
                spawnParticle(i, initialSpawnAge(i), INITIAL_SPAWN);
                activeParticles = Math.max(activeParticles, i + 1);
                particleIndex = (i + 1) % maxActive;
            }
        }
    }

    private float initialSpawnAge(int i) {
        return lifeMid > 0f && maxParticles > 0 ? (float) i / maxParticles * lifeMid : 0f;
    }

    private boolean isKindDisabled() {
        return false;
    }

    private void updateParticles(float dt, boolean collectWorldBounds) {
        liveWorldBoundsCurrent = false;
        nativeSortBoundsCurrent = false;
        liveMaximumParticleDiameterCurrent = false;
        float rate;
        int spawnLimit = maxActive;
        if (activeParticles < maxParticles) {
            rate = automaticInitialSpawning
                    ? (lifeMid > 0f ? maxParticles / lifeMid : maxParticles)
                    : initialParticlesPerSecond;
            spawnLimit = maxParticles - activeParticles;
        } else {
            rate = particlesPerSecond;
        }
        if (killed) {
            rate = 0f;
        }
        if (rate > 0f) {
            ppsFraction = spawnParticles(ppsFraction, rate, dt, spawnLimit);
        }

        int count = Math.min(maxActive, activeParticles);
        for (int i = 0; i < count; i++) {
            if ((flags[i] & ACTIVE) == 0) {
                continue;
            }
            if ((flags[i] & INITIAL_SPAWN) == 0) {
                time[i] += dt;
            }
            if (maxLifetime[i] > 0f && time[i] > maxLifetime[i]) {
                if (!respawnDeadParticles || killed) {
                    flags[i] &= ~ACTIVE;
                    continue;
                }
                float newTime = time[i] - maxLifetime[i] + randRange(initialTimeMin, initialTimeMax);
                spawnParticle(i, maxLifetime[i] > 0f ? newTime % maxLifetime[i] : 0f, 0);
            }
        }

        var meshRadius = collectWorldBounds && kind == Kind.MESH && meshBounds != null ? meshLocalRadius() : 0f;
        var beamNoiseRadius = collectWorldBounds && kind == Kind.BEAM ? beamNoiseRadius() : 0f;
        if (collectWorldBounds) {
            liveWorldBounds.clear();
            liveMaximumParticleDiameter = 0f;
        }
        for (int i = 0; i < count; i++) {
            if ((flags[i] & ACTIVE) == 0) {
                continue;
            }
            boolean tick = true;
            if ((flags[i] & INITIAL_SPAWN) != 0) {
                tick = false;
                flags[i] &= ~INITIAL_SPAWN;
            }
            if (tick) {
                vx[i] += acceleration.getX() * spawnScale * dt;
                vy[i] += acceleration.getY() * spawnScale * dt;
                vz[i] += acceleration.getZ() * spawnScale * dt;
                px[i] += vx[i] * dt;
                py[i] += vy[i] * dt;
                pz[i] += vz[i] * dt;
                if (useRevolution) {
                    revolve(i, dt);
                }
            }

            float timeFactor;
            if (useRegularSizeScale) {
                timeFactor = 1f / (1f + time[i]);
            } else if (useSizeScale && sizeScale.length > 0) {
                float rt = useAbsoluteTimeForSizeScale
                        ? time[i]
                        : (maxLifetime[i] > 0f ? clamp(time[i] / maxLifetime[i], 0f, 1f) : 0f);
                timeFactor = sampleSizeScale(rt);
            } else {
                timeFactor = 1f;
            }
            outScaleX[i] = ssx[i] * timeFactor;
            outScaleY[i] = ssy[i] * timeFactor;
            outScaleZ[i] = ssz[i] * timeFactor;

            var alpha = 1f;
            if (useColorScale && maxLifetime[i] > 0f) {
                sampleColorScale(clamp(time[i] / maxLifetime[i], 0f, 1f), colorScaleScratch);
                outR[i] = colorScaleScratch[0] * cmR[i];
                outG[i] = colorScaleScratch[1] * cmG[i];
                outB[i] = colorScaleScratch[2] * cmB[i];
                if (drawStyle == 1) {
                    alpha = colorScaleScratch[3];
                }
            } else {
                outR[i] = cmR[i];
                outG[i] = cmG[i];
                outB[i] = cmB[i];
            }
            var fade = 0f;
            var factor = fadeInFactor;
            if (fadeOut && time[i] > fadeOutStartTime && maxLifetime[i] != fadeOutStartTime) {
                fade = (time[i] - fadeOutStartTime) / (maxLifetime[i] - fadeOutStartTime);
                factor = fadeOutFactor;
            } else if (fadeIn && time[i] < fadeInEndTime && fadeInEndTime != 0f) {
                fade = (fadeInEndTime - time[i]) / fadeInEndTime;
            }
            if (fade > 0f) {
                if (drawStyle == 2) {
                    outR[i] = outG[i] = outB[i] = 127f / 255f;
                } else if (drawStyle != 1) {
                    outR[i] = clamp(outR[i] - factor.getX() * fade, 0f, 1f);
                    outG[i] = clamp(outG[i] - factor.getY() * fade, 0f, 1f);
                    outB[i] = clamp(outB[i] - factor.getZ() * fade, 0f, 1f);
                }
                alpha = clamp(alpha - factor.getW() * fade, 0f, 1f);
            }
            outA[i] = alpha;

            if (hasSubUv) {
                int cell = subCell[i] >= 0
                        ? subCell[i]
                        : animatedCell(maxLifetime[i] > 0f ? clamp(time[i] / maxLifetime[i], 0f, 1f) : 0f);
                outU0[i] = (cell / subDivV) * subUScale;
                outV0[i] = (cell % subDivV) * subVScale;
                outSubBlend[i] = blendBetweenSubdivisions && subCell[i] < 0
                        ? subdivisionBlend(cell, maxLifetime[i] > 0f ? clamp(time[i] / maxLifetime[i], 0f, 1f) : 0f)
                        : 0f;
            }

            if (spinParticles) {
                outRoll[i] = (spz[i] + time[i] * rpz[i]) * SPIN_UNIT;
                outPitch[i] = (spx[i] + time[i] * rpx[i]) * SPIN_UNIT;
                outYaw[i] = (spy[i] + time[i] * rpy[i]) * SPIN_UNIT;
            }

            if (maxAbsVelocity.getX() > 0f) {
                vx[i] = clamp(vx[i], -maxAbsVelocity.getX() * spawnScale, maxAbsVelocity.getX() * spawnScale);
            }
            if (maxAbsVelocity.getY() > 0f) {
                vy[i] = clamp(vy[i], -maxAbsVelocity.getY() * spawnScale, maxAbsVelocity.getY() * spawnScale);
            }
            if (maxAbsVelocity.getZ() > 0f) {
                vz[i] = clamp(vz[i], -maxAbsVelocity.getZ() * spawnScale, maxAbsVelocity.getZ() * spawnScale);
            }
            vx[i] -= vx[i] * randRange(velocityLossMin.getX(), velocityLossMax.getX()) * dt;
            vy[i] -= vy[i] * randRange(velocityLossMin.getY(), velocityLossMax.getY()) * dt;
            vz[i] -= vz[i] * randRange(velocityLossMin.getZ(), velocityLossMax.getZ()) * dt;
            if (collectWorldBounds) {
                appendWorldBounds(i, liveWorldBounds, meshRadius, beamNoiseRadius);
                accumulateMaximumParticleDiameter(i);
            }
        }
        liveWorldBoundsCurrent = collectWorldBounds;
        liveMaximumParticleDiameterCurrent = collectWorldBounds;
        finished = (!respawnDeadParticles || killed) && (rate == 0f || maxActive == 0) && !hasLiveParticles();
    }

    private void allocate(int n) {
        px = new float[n];
        py = new float[n];
        pz = new float[n];
        vx = new float[n];
        vy = new float[n];
        vz = new float[n];
        ssx = new float[n];
        ssy = new float[n];
        ssz = new float[n];
        spx = new float[n];
        spy = new float[n];
        spz = new float[n];
        rpx = new float[n];
        rpy = new float[n];
        rpz = new float[n];
        cmR = new float[n];
        cmG = new float[n];
        cmB = new float[n];
        time = new float[n];
        maxLifetime = new float[n];
        flags = new int[n];
        outScaleX = new float[n];
        outScaleY = new float[n];
        outScaleZ = new float[n];
        outPitch = new float[n];
        outYaw = new float[n];
        outRoll = new float[n];
        outR = new float[n];
        outG = new float[n];
        outB = new float[n];
        outA = new float[n];
        subCell = new int[n];
        outU0 = new float[n];
        outV0 = new float[n];
        outSubBlend = new float[n];
        rcx = new float[n];
        rcy = new float[n];
        rcz = new float[n];
        rvx = new float[n];
        rvy = new float[n];
        rvz = new float[n];
        if (beamConfig != null) {
            beamEndX = new float[n];
            beamEndY = new float[n];
            beamEndZ = new float[n];
            beamSeed = new long[n];
        }
    }

    private float spawnParticles(float fraction, float rate, float dt, int spawnLimit) {
        float leftover = fraction + dt * rate;
        int count = (int) leftover;
        float newFraction = leftover - count;
        float inc = 1f / rate;
        float startTime = dt + fraction * inc - inc;
        count = Math.min(count, Math.min(maxActive, Math.max(0, spawnLimit)));
        for (int i = 0; i < count; i++) {
            spawnParticle(particleIndex, startTime - i * inc, INITIAL_SPAWN);
            activeParticles = Math.max(activeParticles, particleIndex + 1);
            particleIndex = (particleIndex + 1) % maxActive;
        }
        return newFraction;
    }

    private void revolve(int i, float dt) {
        var x = px[i] - rcx[i];
        var y = py[i] - rcy[i];
        var z = pz[i] - rcz[i];
        var mx = 1f;
        var my = 1f;
        var mz = 1f;
        if (useRevolutionScale && revolutionScale.length > 0) {
            var relative = maxLifetime[i] > 0f ? clamp(time[i] / maxLifetime[i], 0f, 1f) : 0f;
            var t = (revolutionScaleRepeats + 1) * relative;
            t -= (float) Math.floor(t);
            sampleRevolutionScale(t, revolutionScaleScratch);
            mx = revolutionScaleScratch[0];
            my = revolutionScaleScratch[1];
            mz = revolutionScaleScratch[2];
        }
        if (rvx[i] * mx != 0f) {
            var a = -rvx[i] * mx * dt * TWO_PI;
            var c = (float) Math.cos(a);
            var sn = (float) Math.sin(a);
            var ny = y * c - z * sn;
            z = y * sn + z * c;
            y = ny;
        }
        if (rvy[i] * my != 0f) {
            var a = -rvy[i] * my * dt * TWO_PI;
            var c = (float) Math.cos(a);
            var sn = (float) Math.sin(a);
            var nz = z * c - x * sn;
            x = z * sn + x * c;
            z = nz;
        }
        if (rvz[i] * mz != 0f) {
            var a = rvz[i] * mz * dt * TWO_PI;
            var c = (float) Math.cos(a);
            var sn = (float) Math.sin(a);
            var nx = x * c - y * sn;
            y = x * sn + y * c;
            x = nx;
        }
        px[i] = rcx[i] + x;
        py[i] = rcy[i] + y;
        pz[i] = rcz[i] + z;
    }

    private void sampleRevolutionScale(float t, float[] out) {
        var prevT = 0f;
        var prevX = 1f;
        var prevY = 1f;
        var prevZ = 1f;
        for (var key : revolutionScale) {
            if (t <= key[0]) {
                var f = key[0] == prevT ? 1f : (t - prevT) / (key[0] - prevT);
                out[0] = prevX + (key[1] - prevX) * f;
                out[1] = prevY + (key[2] - prevY) * f;
                out[2] = prevZ + (key[3] - prevZ) * f;
                return;
            }
            prevT = key[0];
            prevX = key[1];
            prevY = key[2];
            prevZ = key[3];
        }
        out[0] = prevX;
        out[1] = prevY;
        out[2] = prevZ;
    }

    private void spawnParticle(int i, float spawnTime, int extraFlags) {
        seedParticle(i, spawnTime, extraFlags, true);
        while (maxLifetime[i] > 0f && time[i] > maxLifetime[i]) {
            seedParticle(i, time[i] % maxLifetime[i], extraFlags, false);
        }
    }

    private void seedParticle(int i, float spawnTime, int extraFlags, boolean applyInitialTime) {
        px[i] = startLocationOffset.getX();
        py[i] = startLocationOffset.getY();
        pz[i] = startLocationOffset.getZ();
        vx[i] = randRange(startVelocityMin.getX(), startVelocityMax.getX()) * spawnScale;
        vy[i] = randRange(startVelocityMin.getY(), startVelocityMax.getY()) * spawnScale;
        vz[i] = randRange(startVelocityMin.getZ(), startVelocityMax.getZ()) * spawnScale;

        if (beamConfig != null) {
            spawnBeam(i);
        }

        if (startLocationShape == 0 || startLocationShape == 3) {
            px[i] += randRange(startLocationMin.getX(), startLocationMax.getX()) * spawnScale;
            py[i] += randRange(startLocationMin.getY(), startLocationMax.getY()) * spawnScale;
            pz[i] += randRange(startLocationMin.getZ(), startLocationMax.getZ()) * spawnScale;
        }
        if (startLocationShape == 1 || startLocationShape == 3) {
            float u = randRange(-1f, 1f);
            float theta = randRange(0f, TWO_PI);
            float s = (float) Math.sqrt(Math.max(0f, 1f - u * u));
            float radius = randRange(sphereRadiusMin, sphereRadiusMax) * spawnScale;
            px[i] += s * (float) Math.cos(theta) * radius;
            py[i] += s * (float) Math.sin(theta) * radius;
            pz[i] += u * radius;
        }
        if (startLocationShape == 2 || startLocationShape == 3) {
            float azimuth = randRange(startLocationPolarMin.getX(), startLocationPolarMax.getX()) * DEG_TO_RAD;
            float inclination = randRange(startLocationPolarMin.getY(), startLocationPolarMax.getY()) * DEG_TO_RAD;
            float radius = randRange(startLocationPolarMin.getZ(), startLocationPolarMax.getZ()) * spawnScale;
            float radial = (float) Math.sin(inclination) * radius;
            px[i] += (float) Math.cos(azimuth) * radial;
            py[i] += (float) Math.sin(azimuth) * radial;
            pz[i] += (float) Math.cos(inclination) * radius;
        }

        spawnCounter++;
        if (addLocationFromOtherEmitter >= 0 && siblingResolver != null) {
            var sibling = siblingResolver.apply(addLocationFromOtherEmitter);
            if (sibling != null && sibling != this && sibling.getActiveSlots() > 0) {
                var slot = Math.floorMod(spawnCounter, sibling.getActiveSlots());
                px[i] += sibling.getRawX(slot);
                py[i] += sibling.getRawY(slot);
                pz[i] += sibling.getRawZ(slot);
            }
        }
        rcx[i] = randRange(revolutionCenterOffsetMin.getX(), revolutionCenterOffsetMax.getX());
        rcy[i] = randRange(revolutionCenterOffsetMin.getY(), revolutionCenterOffsetMax.getY());
        rcz[i] = randRange(revolutionCenterOffsetMin.getZ(), revolutionCenterOffsetMax.getZ());
        rvx[i] = randRange(revolutionsPerSecondMin.getX(), revolutionsPerSecondMax.getX());
        rvy[i] = randRange(revolutionsPerSecondMin.getY(), revolutionsPerSecondMax.getY());
        rvz[i] = randRange(revolutionsPerSecondMin.getZ(), revolutionsPerSecondMax.getZ());

        applySpawnRotation(i);

        applyVelocityDirection(i);

        applySprayRotation(i);

        cmR[i] = randRange(colorMultiplierMin.getX(), colorMultiplierMax.getX());
        cmG[i] = randRange(colorMultiplierMin.getY(), colorMultiplierMax.getY());
        cmB[i] = randRange(colorMultiplierMin.getZ(), colorMultiplierMax.getZ());
        maxLifetime[i] = randRange(lifetimeMin, lifetimeMax);
        if (lifeAdjusted && maxLifetime[i] <= 0f) {
            maxLifetime[i] = Float.MIN_NORMAL;
        }
        time[i] = spawnTime + (applyInitialTime ? randRange(initialTimeMin, initialTimeMax) : 0f);
        flags[i] = ACTIVE | extraFlags;

        ssx[i] = randRange(startSizeMin.getX(), startSizeMax.getX()) * spawnScale;
        ssy[i] = uniformSize ? ssx[i] : randRange(startSizeMin.getY(), startSizeMax.getY()) * spawnScale;
        ssz[i] = uniformSize ? ssx[i] : randRange(startSizeMin.getZ(), startSizeMax.getZ()) * spawnScale;
        outScaleX[i] = ssx[i];
        outScaleY[i] = ssy[i];
        outScaleZ[i] = ssz[i];

        vx[i] += acceleration.getX() * spawnScale * spawnTime;
        vy[i] += acceleration.getY() * spawnScale * spawnTime;
        vz[i] += acceleration.getZ() * spawnScale * spawnTime;
        px[i] += vx[i] * spawnTime;
        py[i] += vy[i] * spawnTime;
        pz[i] += vz[i] * spawnTime;
        if (beamConfig != null) {
            finishBeamEnd(i);
        }
        if (beamAimed) {
            aimBeamEnd(i);
        }

        spx[i] = randRange(startSpinMin.getX(), startSpinMax.getX());
        spy[i] = randRange(startSpinMin.getY(), startSpinMax.getY());
        spz[i] = randRange(startSpinMin.getZ(), startSpinMax.getZ());
        rpx[i] = randRange(spinsPerSecondMin.getX(), spinsPerSecondMax.getX());
        rpy[i] = randRange(spinsPerSecondMin.getY(), spinsPerSecondMax.getY());
        rpz[i] = randRange(spinsPerSecondMin.getZ(), spinsPerSecondMax.getZ());
        if (spinCcwOrCw.getX() > rand()) {
            rpx[i] = -rpx[i];
        }
        if (spinCcwOrCw.getY() > rand()) {
            rpy[i] = -rpy[i];
        }
        if (spinCcwOrCw.getZ() > rand()) {
            rpz[i] = -rpz[i];
        }
        spx[i] *= 65535f;
        spy[i] *= 65535f;
        spz[i] *= 65535f;
        rpx[i] *= 65535f;
        rpy[i] *= 65535f;
        rpz[i] *= 65535f;
        outR[i] = cmR[i];
        outG[i] = cmG[i];
        outB[i] = cmB[i];
        outA[i] = 1f;
        outPitch[i] = 0f;
        outYaw[i] = 0f;
        outRoll[i] = 0f;

        if (hasSubUv) {
            subCell[i] = useRandomSubdiv ? randomCell() : -1;
            int cell = subCell[i] >= 0 ? subCell[i] : animatedCell(0f);
            outU0[i] = (cell % subDivU) * subUScale;
            outV0[i] = (cell / subDivU) * subVScale;
            outSubBlend[i] = 0f;
        }
    }

    private void applySprayRotation(int i) {
        if (coordinateSystem != PTCS_SPRAY) {
            return;
        }
        var location = actorRotation.times(new Vec3(px[i], py[i], pz[i]));
        var velocity = actorRotation.times(new Vec3(vx[i], vy[i], vz[i]));
        px[i] = location.getX();
        py[i] = location.getY();
        pz[i] = location.getZ();
        vx[i] = velocity.getX();
        vy[i] = velocity.getY();
        vz[i] = velocity.getZ();
    }

    private void applySpawnRotation(int i) {
        if (rotationSource == PTRS_NONE) {
            return;
        }
        var location = new Vec3(px[i], py[i], pz[i]);
        var velocity = new Vec3(vx[i], vy[i], vz[i]);
        if (rotationSource == PTRS_ACTOR) {
            location = actorRotation.times(rotationOffset.times(location));
            velocity = actorRotation.times(rotationOffset.times(velocity));
        } else if (rotationSource == PTRS_OFFSET) {
            location = rotationOffset.times(location);
            velocity = rotationOffset.times(velocity);
        } else {
            return;
        }
        px[i] = location.getX();
        py[i] = location.getY();
        pz[i] = location.getZ();
        vx[i] = velocity.getX();
        vy[i] = velocity.getY();
        vz[i] = velocity.getZ();
    }

    private void applyVelocityDirection(int i) {
        if (velocityDirectionFrom == PTVD_NONE) {
            return;
        }
        float sign = takesOwnPlace() ? 1f : -1f;
        float dx = sign * px[i], dy = sign * py[i], dz = sign * pz[i];
        float squared = dx * dx + dy * dy + dz * dz;
        float nx = 0f, ny = 0f, nz = 0f;
        if (squared >= SAFE_NORMAL_SQUARED) {
            float scale = 1f / (float) Math.sqrt(squared);
            nx = dx * scale;
            ny = dy * scale;
            nz = dz * scale;
        }
        switch (velocityDirectionFrom) {
            case PTVD_START_AND_OWNER -> {
                vx[i] = -vx[i] * nx;
                vy[i] = -vy[i] * ny;
                vz[i] = -vz[i] * nz;
            }
            case PTVD_OWNER_AND_START -> {
                vx[i] *= nx;
                vy[i] *= ny;
                vz[i] *= nz;
            }
            case PTVD_ADD_RADIAL -> {
                float radial = randRange(startVelocityRadialMin, startVelocityRadialMax);
                vx[i] += radial * nx;
                vy[i] += radial * ny;
                vz[i] += radial * nz;
            }
            default -> {
            }
        }
    }

    private boolean takesOwnPlace() {
        return coordinateSystem == PTCS_RELATIVE
                || coordinateSystem == PTCS_SPRAY
                || (startLocationShape == PTLS_POLAR
                        && (coordinateSystem == PTCS_P152_SIX || coordinateSystem == PTCS_P152_SEVEN));
    }

    private void spawnBeam(int i) {
        beamSeed[i] = randLong();
        var cfg = beamConfig;
        var mode = cfg.determineEndPointBy();
        pendingBeamTarget = null;
        if (mode == PTEP_ACTOR) {
            beamEndX[i] = 0f;
            beamEndY[i] = 0f;
            beamEndZ[i] = 0f;
            if (!cfg.endpoints().isEmpty()) {
                pendingBeamTarget = pickEndpoint(cfg.endpoints()).actorPlace();
            }
        } else if ((mode == PTEP_OFFSET || mode == PTEP_TRACE_OFFSET || mode == PTEP_OFFSET_AS_ABSOLUTE)
                && !cfg.endpoints().isEmpty()) {
            var ep = pickEndpoint(cfg.endpoints());
            beamEndX[i] = randRange(ep.offsetMin().getX(), ep.offsetMax().getX());
            beamEndY[i] = randRange(ep.offsetMin().getY(), ep.offsetMax().getY());
            beamEndZ[i] = randRange(ep.offsetMin().getZ(), ep.offsetMax().getZ());
        } else if (mode == PTEP_DISTANCE) {
            beamEndX[i] = 0f;
            beamEndY[i] = 0f;
            beamEndZ[i] = randRange(cfg.distMin(), cfg.distMax());
        } else if (mode == PTEP_OFFSET || mode == PTEP_TRACE_OFFSET || mode == PTEP_OFFSET_AS_ABSOLUTE) {
            beamEndX[i] = 0f;
            beamEndY[i] = 0f;
            beamEndZ[i] = 0f;
        } else {
            beamEndX[i] = vx[i] * lifeMid;
            beamEndY[i] = vy[i] * lifeMid;
            beamEndZ[i] = vz[i] * lifeMid;
        }
    }

    private void finishBeamEnd(int i) {
        var mode = beamConfig.determineEndPointBy();
        if (mode == PTEP_VELOCITY) {
            beamEndX[i] = vx[i] * maxLifetime[i];
            beamEndY[i] = vy[i] * maxLifetime[i];
            beamEndZ[i] = vz[i] * maxLifetime[i];
        } else if (mode == PTEP_DISTANCE) {
            var length = beamEndZ[i];
            var speed = (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
            if (speed > 1e-8f) {
                vx[i] /= speed;
                vy[i] /= speed;
                vz[i] /= speed;
            }
            beamEndX[i] = vx[i] * length;
            beamEndY[i] = vy[i] * length;
            beamEndZ[i] = vz[i] * length;
        } else if (mode == PTEP_OFFSET_AS_ABSOLUTE && !beamConfig.endpoints().isEmpty()) {
            beamEndX[i] -= px[i];
            beamEndY[i] -= py[i];
            beamEndZ[i] -= pz[i];
        } else if (mode == PTEP_ACTOR && pendingBeamTarget != null) {
            var target = pendingBeamTarget;
            var atOwner = px[i] == 0f && py[i] == 0f && pz[i] == 0f;
            beamEndX[i] = target.getX() - (atOwner ? actorX : px[i]);
            beamEndY[i] = target.getY() - (atOwner ? actorY : py[i]);
            beamEndZ[i] = target.getZ() - (atOwner ? actorZ : pz[i]);
        }
        pendingBeamTarget = null;
    }

    private void aimBeamEnd(int i) {
        float dx = beamTargetX - actorX, dy = beamTargetY - actorY, dz = beamTargetZ - actorZ;
        var tx = 2f * (-actorQy * dz + actorQz * dy);
        var ty = 2f * (-actorQz * dx + actorQx * dz);
        var tz = 2f * (-actorQx * dy + actorQy * dx);
        beamEndX[i] = dx + actorQw * tx - actorQy * tz + actorQz * ty - px[i];
        beamEndY[i] = dy + actorQw * ty - actorQz * tx + actorQx * tz - py[i];
        beamEndZ[i] = dz + actorQw * tz - actorQx * ty + actorQy * tx - pz[i];
    }

    private BeamEndpoint pickEndpoint(List<BeamEndpoint> eps) {
        if (eps.size() == 1) {
            return eps.getFirst();
        }
        float total = 0f;
        for (var e : eps) {
            total += Math.max(0f, e.weight());
        }
        if (total <= 0f) {
            return eps.getFirst();
        }
        float target = rand() * total;
        float acc = 0f;
        for (var e : eps) {
            acc += Math.max(0f, e.weight());
            if (target <= acc) {
                return e;
            }
        }
        return eps.getLast();
    }

    private float rand() {
        long z = (rngState[0] += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 40) * (1.0f / (1 << 24));
    }

    private long randLong() {
        long z = (rngState[0] += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private float randRange(float a, float b) {
        return a == b ? a : a + rand() * (b - a);
    }

    private void sampleColorScale(float rt, float[] out) {
        var t = repeatedScaleTime(rt, colorScaleRepeats);
        var n = 0;
        while (n < colorScale.length && colorScale[n][0] < t) {
            n++;
        }
        if (n >= colorScale.length) {
            Arrays.fill(out, 1f);
            return;
        }
        var c2 = colorScale[n];
        var t1 = n > 0 ? colorScale[n - 1][0] : 0f;
        var r1 = n > 0 ? colorScale[n - 1][1] : 1f;
        var g1 = n > 0 ? colorScale[n - 1][2] : 1f;
        var b1 = n > 0 ? colorScale[n - 1][3] : 1f;
        var denom = c2[0] - t1;
        var a = denom > 1e-5f ? clamp((t - t1) / denom, 0f, 1f) : 1f;
        out[0] = r1 + (c2[1] - r1) * a;
        out[1] = g1 + (c2[2] - g1) * a;
        out[2] = b1 + (c2[3] - b1) * a;
        var a1 = n > 0 && colorScale[n - 1].length > 4 ? colorScale[n - 1][4] : 1f;
        var a2 = c2.length > 4 ? c2[4] : 1f;
        out[3] = a1 + (a2 - a1) * a;
    }

    private float sampleSizeScale(float rt) {
        var t = repeatedScaleTime(rt, sizeScaleRepeats);
        var n = 0;
        while (n < sizeScale.length && sizeScale[n][0] < t) {
            n++;
        }
        if (n >= sizeScale.length) {
            return 1f;
        }
        var s2 = sizeScale[n][1];
        var r2 = sizeScale[n][0];
        var s1 = n > 0 ? sizeScale[n - 1][1] : 1f;
        var r1 = n > 0 ? sizeScale[n - 1][0] : 0f;
        var a = r2 > 1e-6f ? (t - r1) / (r2 - r1) : 1f;
        return s1 + (s2 - s1) * a;
    }

    private int animatedCell(float rt) {
        if (useSubdivisionScale) {
            var cell = 0;
            for (var key : subdivisionScale) {
                if (rt <= key) {
                    break;
                }
                cell++;
            }
            return clampInt(cell, 0, numCells - 1);
        }
        var cell = (int) Math.floor(subdivisionSpan() * rt) + (subdivisionEnd != 0 ? subdivisionStart : 0);
        return clampInt(cell, 0, numCells - 1);
    }

    private float subdivisionBlend(int cell, float rt) {
        var span = useSubdivisionScale ? numCells : subdivisionSpan();
        var next = cell + 1 != span ? cell + 1 : cell;
        float weight;
        if (!useSubdivisionScale || subdivisionScale.length <= numCells) {
            var clock = span * rt;
            weight = clock - (float) Math.floor(clock);
        } else {
            var index = clampInt(cell, 0, subdivisionScale.length - 1);
            var previous = index > 0 ? subdivisionScale[index - 1] : 0f;
            var key = Math.min(subdivisionScale[index], 1f);
            weight = key == previous ? 0f : (rt - previous) / (key - previous);
        }
        return next + clamp(weight, 0f, 0.999f);
    }

    private int subdivisionSpan() {
        return subdivisionEnd != 0 ? Math.max(1, subdivisionEnd - subdivisionStart) : numCells;
    }

    private int randomCell() {
        if (subdivisionEnd == 0) {
            return clampInt((int) (rand() * numCells), 0, numCells - 1);
        }
        var end = Math.min(subdivisionEnd, numCells);
        return clampInt((int) (rand() * (end - subdivisionStart) + subdivisionStart), 0, numCells - 1);
    }

    private Vec3 localParticleWorldPos(int i) {
        var local = new Vec3(px[i], py[i], pz[i]);
        if (coordinateSystem == PTCS_RELATIVE) {
            local = actorRotation.times(local);
        }
        return new Vec3(actorPos.getX() + local.getX(), actorPos.getY() + local.getY(), actorPos.getZ() + local.getZ());
    }

    private void rebuildNativeSortBounds() {
        nativeSortBounds.clear();
        nativeSortBoundsCurrent = true;
        for (int i = 0; i < maxActive; i++) {
            if ((flags[i] & ACTIVE) == 0) {
                continue;
            }
            writeRenderVector(px[i], py[i], pz[i], worldBoundsScratch, 0);
            nativeSortBounds.add(
                    actorX + worldBoundsScratch[0], actorY + worldBoundsScratch[1], actorZ + worldBoundsScratch[2]);
            if (kind == Kind.BEAM) {
                rotateActorTo(px[i] + beamEndX[i], py[i] + beamEndY[i], pz[i] + beamEndZ[i], worldBoundsScratch, 0);
                nativeSortBounds.add(
                        actorX + worldBoundsScratch[0], actorY + worldBoundsScratch[1], actorZ + worldBoundsScratch[2]);
            }
        }
        if (!nativeSortBounds.isValid()) {
            return;
        }
        var ceiling = nativeSizeScaleCeiling();
        var sizeX = Math.max(startSizeMin.getX(), startSizeMax.getX()) * spawnScale * ceiling;
        var sizeY =
                nativeUniformSize ? sizeX : Math.max(startSizeMin.getY(), startSizeMax.getY()) * spawnScale * ceiling;
        var sizeZ =
                nativeUniformSize ? sizeX : Math.max(startSizeMin.getZ(), startSizeMax.getZ()) * spawnScale * ceiling;
        var minX = nativeSortBounds.getMinX();
        var minY = nativeSortBounds.getMinY();
        var minZ = nativeSortBounds.getMinZ();
        var maxX = nativeSortBounds.getMaxX();
        var maxY = nativeSortBounds.getMaxY();
        var maxZ = nativeSortBounds.getMaxZ();
        if (kind == Kind.SPRITE) {
            var grow = nativeUniformSize ? sizeX : Math.max(sizeX, sizeY);
            nativeSortBounds.add(minX - grow, minY - grow, minZ - grow);
            nativeSortBounds.add(maxX + grow, maxY + grow, maxZ + grow);
        } else if (kind == Kind.MESH && meshBounds != null && meshBounds.isValid()) {
            var centerX = (meshBounds.getMinX() + meshBounds.getMaxX()) * 0.5f * sizeX;
            var centerY = (meshBounds.getMinY() + meshBounds.getMaxY()) * 0.5f * sizeY;
            var centerZ = (meshBounds.getMinZ() + meshBounds.getMaxZ()) * 0.5f * sizeZ;
            var growX = (meshBounds.getMaxX() - meshBounds.getMinX()) * 0.5f * sizeX * 1.1f;
            var growY = (meshBounds.getMaxY() - meshBounds.getMinY()) * 0.5f * sizeY * 1.1f;
            var growZ = (meshBounds.getMaxZ() - meshBounds.getMinZ()) * 0.5f * sizeZ * 1.1f;
            nativeSortBounds.add(minX + centerX - growX, minY + centerY - growY, minZ + centerZ - growZ);
            nativeSortBounds.add(maxX + centerX + growX, maxY + centerY + growY, maxZ + centerZ + growZ);
        }
    }

    private float nativeSizeScaleCeiling() {
        if (!useSizeScale || useRegularSizeScale) {
            return 1f;
        }
        var ceiling = 1f;
        for (var entry : sizeScale) {
            ceiling = Math.max(ceiling, entry[1]);
        }
        return ceiling;
    }

    private void rebuildLiveWorldBounds() {
        liveWorldBounds.clear();
        liveMaximumParticleDiameter = 0f;
        var meshRadius = kind == Kind.MESH && meshBounds != null ? meshLocalRadius() : 0f;
        var beamNoiseRadius = kind == Kind.BEAM ? beamNoiseRadius() : 0f;
        for (int i = 0; i < maxActive; i++) {
            if ((flags[i] & ACTIVE) == 0) {
                continue;
            }
            appendWorldBounds(i, liveWorldBounds, meshRadius, beamNoiseRadius);
            accumulateMaximumParticleDiameter(i);
        }
        liveWorldBoundsCurrent = true;
        liveMaximumParticleDiameterCurrent = true;
    }

    private void accumulateMaximumParticleDiameter(int index) {
        if (kind != Kind.SPRITE || !isSpriteColorVisible(index)) {
            return;
        }
        var width = Math.abs(getSpriteRenderScaleX(index) * SPRITE_QUAD_SCALE);
        var height = Math.abs(getSpriteRenderScaleY(index) * SPRITE_QUAD_SCALE);
        var diameter = (float) Math.sqrt(width * width + height * height);
        liveMaximumParticleDiameter = Math.max(liveMaximumParticleDiameter, diameter);
    }

    private float rgbOpacity() {
        return drawStyle != 0 && drawStyle != 1 && drawStyle != 2 && drawStyle != 4
                ? clamp(opacity * opacityRatio, 0f, 1f)
                : 1f;
    }

    private float alphaOpacity() {
        return drawStyle == 1 || drawStyle == 2 || drawStyle == 4 ? clamp(opacity * opacityRatio, 0f, 1f) : 1f;
    }

    private boolean isSpriteColorVisible(int index) {
        if (blend == 0) {
            return getA(index) > 0f;
        }
        if (blend == 2) {
            return true;
        }
        return rgbOpacity() > 0f && (outR[index] > 0f || outG[index] > 0f || outB[index] > 0f);
    }

    private void appendWorldBounds(int i, Box out, float meshRadius, float beamNoiseRadius) {
        if (kind == Kind.BEAM) {
            var w = Math.max(outScaleX[i], 1f) + beamNoiseRadius;
            rotateActorTo(px[i], py[i], pz[i], worldBoundsScratch, 0);
            rotateActorTo(px[i] + beamEndX[i], py[i] + beamEndY[i], pz[i] + beamEndZ[i], worldBoundsScratch, 3);
            addInflated(
                    out,
                    actorX + worldBoundsScratch[0],
                    actorY + worldBoundsScratch[1],
                    actorZ + worldBoundsScratch[2],
                    w);
            addInflated(
                    out,
                    actorX + worldBoundsScratch[3],
                    actorY + worldBoundsScratch[4],
                    actorZ + worldBoundsScratch[5],
                    w);
        } else if (kind == Kind.MESH) {
            var half = Math.max(meshRadius * maxAbs(outScaleX[i], outScaleY[i], outScaleZ[i]), 1f);
            writeRenderVector(px[i], py[i], pz[i], worldBoundsScratch, 0);
            addInflated(
                    out,
                    actorX + worldBoundsScratch[0],
                    actorY + worldBoundsScratch[1],
                    actorZ + worldBoundsScratch[2],
                    half);
        } else {
            var w = getSpriteRenderScaleX(i);
            var h = getSpriteRenderScaleY(i);
            var halfWidth = w * (0.5f * SPRITE_QUAD_SCALE);
            var halfHeight = h * (0.5f * SPRITE_QUAD_SCALE);
            var half = Math.max((float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight), 1f);
            writeRenderVector(px[i], py[i], pz[i], worldBoundsScratch, 0);
            addInflated(
                    out,
                    actorX + worldBoundsScratch[0],
                    actorY + worldBoundsScratch[1],
                    actorZ + worldBoundsScratch[2],
                    half);
        }
    }

    private float beamNoiseRadius() {
        if (beamConfig == null) {
            return 0f;
        }
        var past = beamConfig.lowFrequencyPoints() - Math.max(2, beamConfig.highFrequencyPoints()) + 1;
        return Math.max(3f, 2f * past - 1f) * rangeVectorRadius(beamConfig.lfNoiseMin(), beamConfig.lfNoiseMax())
                + rangeVectorRadius(beamConfig.hfNoiseMin(), beamConfig.hfNoiseMax());
    }

    private float meshLocalRadius() {
        return localBoundingRadius(meshBounds, meshPrePivot);
    }

    private static void rotateTo(Quat rotation, float vx, float vy, float vz, float[] target, int offset) {
        var qx = rotation.x.floatValue();
        var qy = rotation.y.floatValue();
        var qz = rotation.z.floatValue();
        var qw = rotation.w.floatValue();
        var tx = 2f * (qy * vz - qz * vy);
        var ty = 2f * (qz * vx - qx * vz);
        var tz = 2f * (qx * vy - qy * vx);
        target[offset] = vx + qw * tx + qy * tz - qz * ty;
        target[offset + 1] = vy + qw * ty + qz * tx - qx * tz;
        target[offset + 2] = vz + qw * tz + qx * ty - qy * tx;
    }

    private static void normalizeDirection(
            float[] target, int offset, float fallbackX, float fallbackY, float fallbackZ) {
        var x = target[offset];
        var y = target[offset + 1];
        var z = target[offset + 2];
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-4f) {
            target[offset] = fallbackX;
            target[offset + 1] = fallbackY;
            target[offset + 2] = fallbackZ;
            return;
        }
        target[offset] = x / length;
        target[offset + 1] = y / length;
        target[offset + 2] = z / length;
    }

    private static float repeatedScaleTime(float time, int repeats) {
        var scaled = time * (repeats + 1);
        return scaled > -1f && scaled < 1f ? scaled : scaled % 1f;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int simulationRefreshPhase(long seed, Vec3 actorPos) {
        var mixed = seed
                ^ ((long) Float.floatToIntBits(actorPos.getX()) << 32)
                ^ Integer.toUnsignedLong(Float.floatToIntBits(actorPos.getY()))
                ^ Long.rotateLeft(Integer.toUnsignedLong(Float.floatToIntBits(actorPos.getZ())), 17);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        return (int) mixed & ParticleActorCullState.REFRESH_FRAME_MASK;
    }

    private static float rangeVectorRadius(Vec3 min, Vec3 max) {
        var x = Math.max(Math.abs(min.getX()), Math.abs(max.getX()));
        var y = Math.max(Math.abs(min.getY()), Math.abs(max.getY()));
        var z = Math.max(Math.abs(min.getZ()), Math.abs(max.getZ()));
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static void addInflated(Box out, float x, float y, float z, float r) {
        out.add(x - r, y - r, z - r);
        out.add(x + r, y + r, z + r);
    }

    private static float maxAbs(float a, float b, float c) {
        return Math.max(Math.abs(a), Math.max(Math.abs(b), Math.abs(c)));
    }

    private static @Nullable BeamConfig withActorPlaces(@Nullable BeamConfig twin, @Nullable BeamConfig source) {
        if (twin == null
                || source == null
                || twin.endpoints().size() != source.endpoints().size()) {
            return twin;
        }
        var ends = new ArrayList<BeamEndpoint>(twin.endpoints().size());
        for (var index = 0; index < twin.endpoints().size(); index++) {
            var end = twin.endpoints().get(index);
            ends.add(new BeamEndpoint(
                    end.offsetMin(),
                    end.offsetMax(),
                    end.weight(),
                    end.actorTag(),
                    source.endpoints().get(index).actorPlace()));
        }
        return new BeamConfig(
                twin.determineEndPointBy(),
                List.copyOf(ends),
                twin.distMin(),
                twin.distMax(),
                twin.lowFrequencyPoints(),
                twin.highFrequencyPoints(),
                twin.lfNoiseMin(),
                twin.lfNoiseMax(),
                twin.hfNoiseMin(),
                twin.hfNoiseMax(),
                twin.uScale(),
                twin.vScale(),
                twin.rotatingSheets(),
                twin.noiseDeterminesEndPoint());
    }
}
