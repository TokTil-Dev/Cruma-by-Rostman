package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.UProperty.Type;
import com.rost.cruma.core.unreal.primitives.UColor;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.AmbientSoundList.Randomizer;
import com.rost.cruma.core.world.MotionLink;
import com.rost.cruma.core.world.PhysicsRotation;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public class UActor extends UObject {
    private static final Vec3 VEC_3_EMPTY = new Vec3(0f, 0f, 0f);
    private static final Vec3 VEC_3_1 = new Vec3(1.0f, 1.0f, 1.0f);

    protected Vec3 location;
    private URotator rotation;
    private float drawScale;
    private float cullDistance;
    private float scaleGlow;
    private float lifeSpan;
    private int translucentRenderPriority;
    private int ambientGlow;
    private int maxLights = 4;
    private boolean unlit;
    private int style = 1;
    private int drawType = 1;
    protected boolean shadowCast;
    private boolean useLightingFromBase;
    private boolean specialLit;
    private boolean unlitCheck;
    private boolean culledSunlight;
    private boolean acceptsProjectors;
    private final UObjectRef<UActor, Optional> base = UObjectRef.optional();
    private boolean sunAffect;
    private boolean sunAffectSerialized;
    private final UObjectRef<ZoneInfoActor, Optional> regionZone = UObjectRef.optional();
    private int regionLeaf;
    private int regionZoneNumber;
    private String forcedRegionTag = UArchive.NONE_NAME;
    private int forcedRegion;
    private String forcedVisibilityZoneTag = UArchive.NONE_NAME;
    private String tag = UArchive.NONE_NAME;
    private String l2MoveEvent = UArchive.NONE_NAME;
    protected boolean movable = true;
    protected int physics = PhysicsRotation.PHYS_NONE;
    private boolean physicsSaved;
    private URotator rotationRate = new URotator(0, 0, 0);
    private URotator desiredRotation = new URotator(0, 0, 0);
    private boolean fixedRotationDir;
    private boolean rotateToDesired;
    private @Nullable MotionLink motionLink;
    private Vec3 drawScale3d;
    private Vec3 prePivot;
    private int[] skinRefs;
    private final UObjectRef<UObject, Optional> mesh = UObjectRef.optional();
    private final UObjectRef<UStaticMesh, Optional> staticMesh = UObjectRef.optional();
    private final UObjectRef<UConvexVolume, Optional> antiPortal = UObjectRef.optional();
    private boolean deleteMe;
    private boolean staticActor;
    private boolean hidden;
    private boolean useL2ActorViewType = true;
    private boolean ignoredRange;
    private float rangeForIgnoredRange = 32768f;
    private float l2ActorViewDuration = 2.5f;
    private float l2ActorViewElapsedTime;
    private int l2ActorViewType;
    private boolean dynamicLight;
    private boolean useDynamicLights = true;
    private boolean lightChanged;
    private boolean collideActors;
    private boolean blockActors;
    private boolean blockPlayers;
    private boolean blockNonZeroExtentTraces;
    private boolean useCylinderCollision;
    private boolean worldGeometry;
    private boolean blockZeroExtentTraces = true;
    private boolean hiddenEd;
    private boolean hiddenEdGroup;
    private final List<int[]> gameEvents = new ArrayList<>();
    private int[] zoneRenderStates = new int[0];
    private List<int[]> zoneRenderSkinRefs = List.of();

    public UActor(UArchive archive) {
        this(archive, false);
    }

    protected UActor(UArchive archive, boolean staticActor) {
        super(archive);
        this.staticActor = staticActor;
        location = VEC_3_EMPTY;
        rotation = new URotator(0, 0, 0);
        drawScale = 1.0f;
        scaleGlow = 1.0f;
        translucentRenderPriority = 0;
        ambientGlow = 0;
        unlit = false;
        shadowCast = false;
        useLightingFromBase = false;
        specialLit = false;
        acceptsProjectors = true;
        sunAffect = true;
        sunAffectSerialized = false;
        regionLeaf = -1;
        regionZoneNumber = -1;
        drawScale3d = VEC_3_1;
        prePivot = VEC_3_EMPTY;
        deleteMe = false;
        hidden = false;
        collideActors = true;
        blockActors = true;
        blockPlayers = true;
    }

    public static int zoneStateMask(int[] states) {
        var mask = 0;
        for (var state : states) {
            if (state == 0) {
                return 0;
            }
            if (state >= 1 && state <= 10) {
                mask |= 1 << (state - 1);
            }
        }
        return mask;
    }

    static List<int[]> decodeGameEvents(short[] data) {
        var out = new ArrayList<int[]>();
        if (data == null) {
            return out;
        }
        var pos = 0;
        var id = Integer.MIN_VALUE;
        var type = 0;
        while (pos < data.length) {
            var b = data[pos++] & 0xFF;
            var value = b & 0x3F;
            var more = (b & 0x40) != 0;
            var shift = 6;
            while (more && pos < data.length) {
                b = data[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
                more = (b & 0x80) != 0;
            }
            if (value == 0) {
                if (id != Integer.MIN_VALUE) {
                    out.add(new int[] {id, type});
                }
                id = Integer.MIN_VALUE;
                type = 0;
                continue;
            }
            if (pos >= data.length) {
                break;
            }
            var info = data[pos++] & 0xFF;
            var propertyType = info & 0x0F;
            var size = switch ((info >> 4) & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> pos < data.length ? data[pos++] & 0xFF : 0;
                case 6 -> pos + 1 < data.length ? (data[pos++] & 0xFF) | (data[pos++] & 0xFF) << 8 : 0;
                default ->
                    pos + 3 < data.length
                            ? (data[pos++] & 0xFF)
                                    | (data[pos++] & 0xFF) << 8
                                    | (data[pos++] & 0xFF) << 16
                                    | (data[pos++] & 0xFF) << 24
                            : 0;
            };
            if (propertyType == 3) {
                continue;
            }
            if (pos + size > data.length) {
                break;
            }
            if (propertyType == 2 && size == 4 && id == Integer.MIN_VALUE) {
                id = (data[pos] & 0xFF)
                        | (data[pos + 1] & 0xFF) << 8
                        | (data[pos + 2] & 0xFF) << 16
                        | (data[pos + 3] & 0xFF) << 24;
            } else if (propertyType == 1 && size == 1) {
                type = data[pos] & 0xFF;
            }
            pos += size;
        }
        if (id != Integer.MIN_VALUE) {
            out.add(new int[] {id, type});
        }
        return out;
    }

    public Vec3 getPosition() {
        return location.minus(prePivot);
    }

    public Vec3 getScale() {
        return drawScale3d.times(drawScale);
    }

    @Override
    public boolean setProperty(UProperty property) {
        if (super.setProperty(property)) {
            return true;
        }
        return switch (property.getName()) {
            case "Location" -> {
                location = property.getVectorValue();
                yield true;
            }
            case "Rotation" -> {
                rotation = property.getRotatorValue();
                yield true;
            }
            case "DrawScale" -> {
                drawScale = property.getFloatValue();
                yield true;
            }
            case "CullDistance" -> {
                cullDistance = property.getFloatValue();
                yield true;
            }
            case "ScaleGlow" -> {
                scaleGlow = property.getFloatValue();
                yield true;
            }
            case "LifeSpan" -> {
                lifeSpan = property.getFloatValue();
                yield true;
            }
            case "TranslucentRenderPriority" -> {
                translucentRenderPriority = property.getInt32Value();
                yield true;
            }
            case "AmbientGlow" -> {
                ambientGlow = Short.toUnsignedInt(property.getUInt8Value());
                yield true;
            }
            case "MaxLights" -> {
                maxLights = Short.toUnsignedInt(property.getUInt8Value());
                yield true;
            }
            case "bUnlit" -> {
                unlit = property.isTrue();
                yield true;
            }
            case "Style" -> {
                style = Short.toUnsignedInt(property.getUInt8Value());
                yield true;
            }
            case "DrawType" -> {
                drawType = Short.toUnsignedInt(property.getUInt8Value());
                yield true;
            }
            case "bShadowCast" -> {
                shadowCast = property.isTrue();
                yield true;
            }
            case "bUseLightingFromBase" -> {
                useLightingFromBase = property.isTrue();
                yield true;
            }
            case "bSpecialLit" -> {
                specialLit = property.isTrue();
                yield true;
            }
            case "bUnlitCheck" -> {
                unlitCheck = property.isTrue();
                yield true;
            }
            case "bCulledSunlight" -> {
                culledSunlight = property.isTrue();
                yield true;
            }
            case "bAcceptsProjectors" -> {
                acceptsProjectors = property.isTrue();
                yield true;
            }
            case "Base" -> {
                base.fromProperty(archive, property);
                yield true;
            }
            case "bSunAffect" -> {
                sunAffect = property.isTrue();
                sunAffectSerialized = true;
                yield true;
            }
            case "Region" -> {
                if (!property.getSubProperties().isEmpty()) {
                    var region = property.getSubProperties().getFirst();
                    var zone = region.get("Zone");
                    var leaf = region.get("iLeaf");
                    var zoneNumber = region.get("ZoneNumber");
                    if (zone != null) {
                        regionZone.fromProperty(archive, zone);
                    }
                    if (leaf != null) {
                        regionLeaf = leaf.getInt32Value();
                    }
                    if (zoneNumber != null) {
                        regionZoneNumber = Short.toUnsignedInt(zoneNumber.getUInt8Value());
                    }
                }
                yield true;
            }
            case "ForcedRegionTag" -> {
                forcedRegionTag = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "ForcedRegion" -> {
                forcedRegion = property.getInt32Value();
                yield true;
            }
            case "ForcedVisibilityZoneTag" -> {
                forcedVisibilityZoneTag = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "Tag" -> {
                tag = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "L2MoveEvent" -> {
                l2MoveEvent = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "bMovable" -> {
                movable = property.isTrue();
                yield true;
            }
            case "Physics" -> {
                physics = Short.toUnsignedInt(property.getUInt8Value());
                physicsSaved = true;
                yield true;
            }
            case "RotationRate" -> {
                rotationRate = property.getRotatorValue();
                yield true;
            }
            case "DesiredRotation" -> {
                desiredRotation = property.getRotatorValue();
                yield true;
            }
            case "bFixedRotationDir" -> {
                fixedRotationDir = property.isTrue();
                yield true;
            }
            case "bRotateToDesired" -> {
                rotateToDesired = property.isTrue();
                yield true;
            }
            case "L2GameEvent" -> {
                gameEvents.addAll(decodeGameEvents(property.getDataValue()));
                yield true;
            }
            case "ZoneRenderState" -> {
                zoneRenderStates = zoneStatesOf(property);
                zoneRenderSkinRefs = zoneSkinRefsOf(property);
                yield true;
            }
            case "DrawScale3D" -> {
                drawScale3d = property.getVectorValue();
                yield true;
            }
            case "Skins" -> {
                skinRefs = property.getObjectArray();
                yield true;
            }
            case "Mesh" -> {
                mesh.fromProperty(archive, property);
                yield true;
            }
            case "StaticMesh" -> {
                staticMesh.fromProperty(archive, property);
                yield true;
            }
            case "AntiPortal" -> {
                antiPortal.fromProperty(archive, property);
                yield true;
            }
            case "bDeleteMe" -> {
                deleteMe = property.isTrue();
                yield true;
            }
            case "bStatic" -> {
                staticActor = property.isTrue();
                yield true;
            }
            case "bHidden" -> {
                hidden = property.isTrue();
                yield true;
            }
            case "bUseL2ActorViewType" -> {
                useL2ActorViewType = property.isTrue();
                yield true;
            }
            case "bIgnoredRange" -> {
                ignoredRange = property.isTrue();
                yield true;
            }
            case "RangeForIgnoredRange" -> {
                rangeForIgnoredRange = property.getFloatValue();
                yield true;
            }
            case "L2ActorViewDuration" -> {
                l2ActorViewDuration = property.getFloatValue();
                yield true;
            }
            case "L2ActorViewElapsedTime" -> {
                l2ActorViewElapsedTime = property.getFloatValue();
                yield true;
            }
            case "L2ActorViewtype" -> {
                l2ActorViewType = property.getUInt8Value();
                yield true;
            }
            case "bDynamicLight" -> {
                dynamicLight = property.isTrue();
                yield true;
            }
            case "bUseDynamicLights" -> {
                useDynamicLights = property.isTrue();
                yield true;
            }
            case "bLightChanged" -> {
                lightChanged = property.isTrue();
                yield true;
            }
            case "bCollideActors" -> {
                collideActors = property.isTrue();
                yield true;
            }
            case "bBlockActors" -> {
                blockActors = property.isTrue();
                yield true;
            }
            case "bBlockPlayers" -> {
                blockPlayers = property.isTrue();
                yield true;
            }
            case "PrePivot" -> {
                prePivot = property.getVectorValue();
                yield true;
            }
            case "bBlockNonZeroExtentTraces" -> {
                blockNonZeroExtentTraces = property.isTrue();
                yield true;
            }
            case "bUseCylinderCollision" -> {
                useCylinderCollision = property.isTrue();
                yield true;
            }
            case "bWorldGeometry" -> {
                worldGeometry = property.isTrue();
                yield true;
            }
            case "bBlockZeroExtentTraces" -> {
                blockZeroExtentTraces = property.isTrue();
                yield true;
            }
            case "bHiddenEd" -> {
                hiddenEd = property.isTrue();
                yield true;
            }
            case "bHiddenEdGroup" -> {
                hiddenEdGroup = property.isTrue();
                yield true;
            }
            default -> false;
        };
    }

    public ZoneInfoActor getRegionZone() {
        return regionZone.get();
    }

    public String getForcedRegionTag() {
        return forcedRegionTag;
    }

    public int getForcedRegion() {
        return forcedRegion;
    }

    public String getForcedVisibilityZoneTag() {
        return forcedVisibilityZoneTag;
    }

    public String getTag() {
        return tag;
    }

    public String getL2MoveEvent() {
        return l2MoveEvent;
    }

    public boolean isMovable() {
        return movable;
    }

    public int getPhysics() {
        return physics;
    }

    public boolean isPhysicsSaved() {
        return physicsSaved;
    }

    public URotator getRotationRate() {
        return rotationRate;
    }

    public URotator getDesiredRotation() {
        return desiredRotation;
    }

    public boolean isFixedRotationDir() {
        return fixedRotationDir;
    }

    public boolean isRotateToDesired() {
        return rotateToDesired;
    }

    public @Nullable PhysicsRotation createPhysicsRotation(@Nullable String carrier) {
        var interpolating = false;
        if (deleteMe
                || !PhysicsRotation.turns(
                        physics,
                        staticActor,
                        movable,
                        rotationRate,
                        fixedRotationDir,
                        rotateToDesired,
                        interpolating)) {
            return null;
        }
        return new PhysicsRotation(
                getFullName(),
                location,
                rotation,
                rotationRate,
                desiredRotation,
                fixedRotationDir,
                rotateToDesired,
                carrier);
    }

    public @Nullable MotionLink getMotionLink() {
        return motionLink;
    }

    public void setMotionLink(@Nullable MotionLink motionLink) {
        this.motionLink = motionLink;
    }

    public int[] getZoneRenderStates() {
        return zoneRenderStates.clone();
    }

    public int getZoneRenderMask() {
        return zoneStateMask(zoneRenderStates);
    }

    public List<int[]> getGameEvents() {
        return List.copyOf(gameEvents);
    }

    public UActor getAmbientLightingActor() {
        var actor = this;
        for (var depth = 0; depth < 64 && actor.useLightingFromBase; depth++) {
            var next = actor.base.get();
            if (next == null || next == actor || next == this) {
                break;
            }
            actor = next;
        }
        return actor;
    }

    public float getLifeSpan() {
        return lifeSpan;
    }

    public UObject getSkin(int slot) {
        if (skinRefs == null || slot < 0 || slot >= skinRefs.length || skinRefs[slot] == 0) {
            return null;
        }
        return archive.getObjectLoader().loadObject(skinRefs[slot]);
    }

    public List<UObject> getSkins() {
        if (skinRefs == null || skinRefs.length == 0) {
            return List.of();
        }
        var skins = new ArrayList<UObject>(skinRefs.length);
        var loader = archive.getObjectLoader();
        for (var ref : skinRefs) {
            skins.add(ref == 0 ? null : loader.loadObject(ref));
        }
        return skins;
    }

    public @Nullable List<UObject> getZoneStateSkins(int state) {
        if (state < 1 || state > 32 || zoneRenderSkinRefs.isEmpty()) {
            return null;
        }
        var bit = 1 << (state - 1);
        var own = skinRefs == null ? new int[0] : skinRefs;
        var slots = own.length;
        for (var element = 0; element < zoneRenderSkinRefs.size(); element++) {
            if (1 << (zoneRenderStates[element] - 1) == bit) {
                slots = Math.max(slots, zoneRenderSkinRefs.get(element).length);
            }
        }
        var refs = new int[slots];
        var changed = false;
        for (var slot = 0; slot < slots; slot++) {
            var ref = slot < own.length ? own[slot] : 0;
            for (var element = 0; element < zoneRenderSkinRefs.size(); element++) {
                var skins = zoneRenderSkinRefs.get(element);
                if (1 << (zoneRenderStates[element] - 1) == bit && skins.length > slot) {
                    if (skins[slot] != 0 && skins[slot] != ref) {
                        ref = skins[slot];
                        changed = true;
                    }
                    break;
                }
            }
            refs[slot] = ref;
        }
        if (!changed) {
            return null;
        }
        var result = new ArrayList<UObject>(slots);
        var loader = archive.getObjectLoader();
        for (var ref : refs) {
            result.add(ref == 0 ? null : loader.loadObject(ref));
        }
        return result;
    }

    public static class UInfoActor extends UActor {
        public UInfoActor(UArchive archive) {
            super(archive);
        }
    }

    public static class AntiPortalActor extends UActor {
        public AntiPortalActor(UArchive archive) {
            super(archive);
            super.drawType = 11;
        }
    }

    public static class BrushActor extends UActor {
        private final UObjectRef<UModel, Optional> brush = UObjectRef.optional();

        public BrushActor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            if ("Brush".equals(property.getName())) {
                brush.fromProperty(archive, property);
                return true;
            }
            return false;
        }

        public UObjectRef<UModel, Optional> getBrush() {
            return this.brush;
        }
    }

    public static class StaticMeshActor extends UActor {
        private final UObjectRef<UStaticMeshInstance, Optional> staticMeshInstance = UObjectRef.optional();
        private boolean staticLighting = true;
        private float ambientIntensity = 1f;
        private float lightIntensity = 1f;
        private boolean agitDefaultStaticMesh;
        private List<UTerrain.UDecorationLayer> decoLayers = List.of();
        private List<MeshDecorationData.Instances> decoLayerData = List.of();
        private List<MeshDecorationData.Instances> decoLayerDataForQuad = List.of();
        private int decoAmbientColor;

        public StaticMeshActor(UArchive archive) {
            this(archive, true);
        }

        public StaticMeshActor(UArchive archive, boolean staticActor) {
            super(archive, staticActor);
            super.drawType = 8;
            shadowCast = true;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "StaticMeshInstance" -> {
                    staticMeshInstance.fromProperty(archive, property);
                    yield true;
                }
                case "bStaticLighting" -> {
                    staticLighting = property.isTrue();
                    yield true;
                }
                case "AmbientIntensity" -> {
                    ambientIntensity = property.getFloatValue();
                    yield true;
                }
                case "LightIntensity" -> {
                    lightIntensity = property.getFloatValue();
                    yield true;
                }
                case "bAgitDefaultStaticMesh" -> {
                    agitDefaultStaticMesh = property.isTrue();
                    yield true;
                }
                case "DecoLayers" -> {
                    var layers = new ArrayList<UTerrain.UDecorationLayer>();
                    for (var properties : property.getSubProperties()) {
                        layers.add(new UTerrain.UDecorationLayer(archive, properties));
                    }
                    decoLayers = List.copyOf(layers);
                    yield true;
                }
                case "DecoLayerData" -> {
                    decoLayerData = property.getDataValue() == null
                            ? List.of()
                            : MeshDecorationData.readLayerData(
                                    MeshDecorationData.toBytes(property.getDataValue()),
                                    property.getArraySize(),
                                    archive::resolveName);
                    yield true;
                }
                case "DecoLayerDataForQuad" -> {
                    decoLayerDataForQuad = property.getDataValue() == null
                            ? List.of()
                            : MeshDecorationData.readQuadData(
                                    MeshDecorationData.toBytes(property.getDataValue()),
                                    property.getArraySize(),
                                    archive::resolveName);
                    yield true;
                }
                case "DecoAmbientColor" -> {
                    var color = property.getColorValue();
                    decoAmbientColor =
                            color == null ? 0 : (color.r() & 0xff) << 16 | (color.g() & 0xff) << 8 | color.b() & 0xff;
                    yield true;
                }
                default -> false;
            };
        }

        public boolean isAgitDefaultStaticMesh() {
            return agitDefaultStaticMesh;
        }

        public List<UTerrain.UDecorationLayer> getDecoLayers() {
            return decoLayers;
        }

        public List<MeshDecorationData.Instances> getDecoLayerData() {
            return decoLayerData;
        }

        public List<MeshDecorationData.Instances> getDecoLayerDataForQuad() {
            return decoLayerDataForQuad;
        }

        public int getDecoAmbientColor() {
            return decoAmbientColor;
        }

        public UStaticMeshInstance getStaticMeshInstance() {
            return staticMeshInstance.get();
        }

        public boolean isStaticLighting() {
            return staticLighting;
        }

        public float getAmbientIntensity() {
            return ambientIntensity;
        }

        public float getLightIntensity() {
            return lightIntensity;
        }
    }

    public static class ProjectorActor extends UActor {
        private final UObjectRef<UMaterial, Optional> projTexture = UObjectRef.optional();
        private final UObjectRef<UTexture, Optional> gradientTexture = UObjectRef.optional();
        private final Set<String> serializedProjectorProperties = new LinkedHashSet<>();
        private int materialBlendingOp;
        private int frameBufferBlendingOp = 1;
        private int fov;
        private int maxTraceDistance = 1000;
        private boolean projectBsp = true;
        private boolean projectTerrain = true;
        private boolean projectStaticMesh = true;
        private boolean projectParticles = true;
        private boolean projectActor = true;
        private boolean levelStatic;
        private boolean clipBsp;
        private boolean clipStaticMesh;
        private boolean projectOnUnlit;
        private boolean gradient;
        private boolean projectOnBackfaces;
        private boolean projectOnAlpha;
        private boolean projectOnParallelBsp;
        private boolean dynamicAttach;
        private String projectTag = UArchive.NONE_NAME;
        private float projectorRadius = -1f;

        public ProjectorActor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            var handled = switch (property.getName()) {
                case "MaterialBlendingOp" -> {
                    materialBlendingOp = Short.toUnsignedInt(property.getUInt8Value());
                    yield true;
                }
                case "FrameBufferBlendingOp" -> {
                    frameBufferBlendingOp = Short.toUnsignedInt(property.getUInt8Value());
                    yield true;
                }
                case "ProjTexture" -> {
                    projTexture.fromProperty(archive, property);
                    yield true;
                }
                case "FOV" -> {
                    fov = property.getInt32Value();
                    yield true;
                }
                case "MaxTraceDistance" -> {
                    maxTraceDistance = property.getInt32Value();
                    yield true;
                }
                case "bProjectBSP" -> {
                    projectBsp = property.isTrue();
                    yield true;
                }
                case "bProjectTerrain" -> {
                    projectTerrain = property.isTrue();
                    yield true;
                }
                case "bProjectStaticMesh" -> {
                    projectStaticMesh = property.isTrue();
                    yield true;
                }
                case "bProjectParticles" -> {
                    projectParticles = property.isTrue();
                    yield true;
                }
                case "bProjectActor" -> {
                    projectActor = property.isTrue();
                    yield true;
                }
                case "bLevelStatic" -> {
                    levelStatic = property.isTrue();
                    yield true;
                }
                case "bClipBSP" -> {
                    clipBsp = property.isTrue();
                    yield true;
                }
                case "bClipStaticMesh" -> {
                    clipStaticMesh = property.isTrue();
                    yield true;
                }
                case "bProjectOnUnlit" -> {
                    projectOnUnlit = property.isTrue();
                    yield true;
                }
                case "bGradient" -> {
                    gradient = property.isTrue();
                    yield true;
                }
                case "bProjectOnBackfaces" -> {
                    projectOnBackfaces = property.isTrue();
                    yield true;
                }
                case "bProjectOnAlpha" -> {
                    projectOnAlpha = property.isTrue();
                    yield true;
                }
                case "bProjectOnParallelBSP" -> {
                    projectOnParallelBsp = property.isTrue();
                    yield true;
                }
                case "ProjectTag" -> {
                    projectTag = archive.resolveName(property.getIndexValue());
                    yield true;
                }
                case "bDynamicAttach" -> {
                    dynamicAttach = property.isTrue();
                    yield true;
                }
                case "GradientTexture" -> {
                    gradientTexture.fromProperty(archive, property);
                    yield true;
                }
                case "ProjectorRadius" -> {
                    projectorRadius = property.getFloatValue();
                    yield true;
                }
                default -> false;
            };
            if (handled) {
                serializedProjectorProperties.add(property.getName());
            }
            return handled;
        }

        public UMaterial getProjTexture() {
            return projTexture.get();
        }

        public String getProjTextureName() {
            return projTexture.peekFullName();
        }

        public UTexture getGradientTexture() {
            return gradientTexture.get();
        }

        public String getGradientTextureName() {
            var authored = gradientTexture.peekFullName();
            return authored != null ? authored : "Engine.GRADIENT_Fade";
        }

        public Set<String> getSerializedProjectorProperties() {
            return Set.copyOf(serializedProjectorProperties);
        }

        public int getMaterialBlendingOp() {
            return this.materialBlendingOp;
        }

        public int getFrameBufferBlendingOp() {
            return this.frameBufferBlendingOp;
        }

        public int getFov() {
            return this.fov;
        }

        public int getMaxTraceDistance() {
            return this.maxTraceDistance;
        }

        public boolean isProjectBsp() {
            return this.projectBsp;
        }

        public boolean isProjectTerrain() {
            return this.projectTerrain;
        }

        public boolean isProjectStaticMesh() {
            return this.projectStaticMesh;
        }

        public boolean isProjectParticles() {
            return this.projectParticles;
        }

        public boolean isProjectActor() {
            return this.projectActor;
        }

        public boolean isLevelStatic() {
            return this.levelStatic;
        }

        public boolean isClipBsp() {
            return this.clipBsp;
        }

        public boolean isClipStaticMesh() {
            return this.clipStaticMesh;
        }

        public boolean isProjectOnUnlit() {
            return this.projectOnUnlit;
        }

        public boolean isGradient() {
            return this.gradient;
        }

        public boolean isProjectOnBackfaces() {
            return this.projectOnBackfaces;
        }

        public boolean isProjectOnAlpha() {
            return this.projectOnAlpha;
        }

        public boolean isProjectOnParallelBsp() {
            return this.projectOnParallelBsp;
        }

        public boolean isDynamicAttach() {
            return this.dynamicAttach;
        }

        public String getProjectTag() {
            return this.projectTag;
        }

        public float getProjectorRadius() {
            return this.projectorRadius;
        }
    }

    public static class EmitterActor extends UActor {
        private static final Set<String> LIFETIME = Set.of("AutoDestroy", "AutoReset", "bNoDelete");
        private static final int SHOW_EVENT = 1;

        private int[] emitterRefs;
        private float visibleLimitMin;
        private float visibleLimitMax;
        private float visibilityInterpRange = 500f;
        private Vec3 globalOffsetMin = VEC_3_EMPTY;
        private Vec3 globalOffsetMax = VEC_3_EMPTY;
        private float speedRate = 1f;

        private boolean autoReset;
        private boolean autoDestroy = true;
        private boolean noDelete = true;
        private boolean placed;
        private @Nullable String spawnSoundPackage;
        private float timeTillResetMin;
        private float timeTillResetMax;
        private URotator rotPerSecond = new URotator(0, 0, 0);
        private boolean rotEmitter;
        private boolean selfRotation;
        private boolean disableFogging;
        private boolean autoReplay;
        private boolean screenEffect;
        private boolean setSizeScale = true;
        private float projectileSpeed = 10f;
        private float projectileAcceleration = 10f;
        private int[] spawnSoundRefs = new int[0];
        private float soundRadius;
        private float soundVolume;
        private float soundDelay;
        private float soundPitchMin = 1f;
        private float soundPitchMax = 1f;
        private boolean soundLooping;
        private boolean uiSound;
        private float soundFadeInDuration;
        private float soundFadeOutStart;
        private float soundFadeOutDuration;
        private boolean useLight;
        private int lightType;
        private float lightBrightness;
        private int lightHue;
        private int lightSaturation;
        private int emitterLightingType;
        private float lightRadius;
        private float lightLifeSpan;
        private float lightInitialDelay;

        public EmitterActor(UArchive archive) {
            super(archive);
            setTranslucentRenderPriority(-1);
            setUseL2ActorViewType(false);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Emitters" -> {
                    emitterRefs = property.getObjectArray();
                    yield true;
                }
                case "AutoReset" -> {
                    autoReset = property.isTrue();
                    yield true;
                }
                case "SpawnSound" -> {
                    var refs = property.getObjectArray();
                    if (refs != null) {
                        spawnSoundRefs = refs;
                        spawnSoundPackage = null;
                    }
                    yield true;
                }
                case "bNoDelete" -> {
                    noDelete = property.isTrue();
                    yield true;
                }
                case "SoundRadius" -> {
                    soundRadius = readNumeric(property);
                    yield true;
                }
                case "SoundVolume" -> {
                    soundVolume = readNumeric(property);
                    yield true;
                }
                case "SoundDelay" -> {
                    soundDelay = readNumeric(property);
                    yield true;
                }
                case "SoundPitchMin" -> {
                    soundPitchMin = readNumeric(property);
                    yield true;
                }
                case "SoundPitchMax" -> {
                    soundPitchMax = readNumeric(property);
                    yield true;
                }
                case "SoundLooping" -> {
                    soundLooping = property.isTrue();
                    yield true;
                }
                case "IsUISound" -> {
                    uiSound = property.isTrue();
                    yield true;
                }
                case "SoundFadeInDuration" -> {
                    soundFadeInDuration = readNumeric(property);
                    yield true;
                }
                case "SoundFadeOutStart" -> {
                    soundFadeOutStart = readNumeric(property);
                    yield true;
                }
                case "SoundFadeOutDuration" -> {
                    soundFadeOutDuration = readNumeric(property);
                    yield true;
                }
                case "RotPerSecond" -> {
                    var rotator = property.getRotatorValue();
                    if (rotator != null) {
                        rotPerSecond = rotator;
                    }
                    yield true;
                }
                case "bRotEmitter" -> {
                    rotEmitter = property.isTrue();
                    yield true;
                }
                case "bSelfRotation" -> {
                    selfRotation = property.isTrue();
                    yield true;
                }
                case "DisableFogging" -> {
                    disableFogging = property.isTrue();
                    yield true;
                }
                case "AutoReplay" -> {
                    autoReplay = property.isTrue();
                    yield true;
                }
                case "bSetSizeScale" -> {
                    setSizeScale = property.isTrue();
                    yield true;
                }
                case "Speed" -> {
                    projectileSpeed = property.getFloatValue();
                    yield true;
                }
                case "AccSpeed" -> {
                    projectileAcceleration = property.getFloatValue();
                    yield true;
                }
                case "IsScreenEffect" -> {
                    screenEffect = property.isTrue();
                    yield true;
                }
                case "AutoDestroy" -> {
                    autoDestroy = property.isTrue();
                    yield true;
                }
                case "TimeTillResetRange" -> {
                    timeTillResetMin = rangeBound(property, "Min");
                    timeTillResetMax = rangeBound(property, "Max");
                    yield true;
                }
                case "VisibleLimit" -> {
                    visibleLimitMin = rangeBound(property, "Min");
                    visibleLimitMax = rangeBound(property, "Max");
                    yield true;
                }
                case "VisibilityInterpRange" -> {
                    visibilityInterpRange = property.getFloatValue();
                    yield true;
                }
                case "GlobalOffsetRange" -> {
                    globalOffsetMin = rangeVector(property, "Min");
                    globalOffsetMax = rangeVector(property, "Max");
                    yield true;
                }
                case "SpeedRate" -> {
                    speedRate = property.getFloatValue();
                    yield true;
                }
                case "bUseLight" -> {
                    useLight = property.isTrue();
                    yield true;
                }
                case "LightType" -> {
                    lightType = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightBrightness" -> {
                    lightBrightness = readNumeric(property);
                    yield true;
                }
                case "LightHue" -> {
                    lightHue = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightSaturation" -> {
                    lightSaturation = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "EmitterLightingType" -> {
                    emitterLightingType = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightRadius" -> {
                    lightRadius = readNumeric(property);
                    yield true;
                }
                case "EL_LifeSpan" -> {
                    lightLifeSpan = property.getFloatValue();
                    yield true;
                }
                case "EL_InitialDelay" -> {
                    lightInitialDelay = property.getFloatValue();
                    yield true;
                }
                default -> false;
            };
        }

        public void takeEngineLifetime(List<UProperty> defaults) {
            if (defaults.isEmpty()) {
                return;
            }
            autoDestroy = false;
            autoReset = false;
            noDelete = false;
            for (var property : defaults) {
                if (LIFETIME.contains(property.getName())) {
                    setProperty(property);
                }
            }
        }

        public void takeLifetime(List<UProperty> defaults) {
            for (var property : defaults) {
                if (LIFETIME.contains(property.getName())) {
                    setProperty(property);
                }
            }
        }

        public void takeClassDefaults(List<UProperty> defaults, String packageName) {
            for (var property : defaults) {
                switch (property.getName()) {
                    case "Emitters" -> {
                    }
                    case "SpawnSound" -> {
                        var refs = property.getObjectArray();
                        if (refs != null) {
                            spawnSoundRefs = refs;
                            spawnSoundPackage = packageName;
                        }
                    }
                    default -> {
                        if (!holdsReference(property)) {
                            setProperty(property);
                        }
                    }
                }
            }
        }

        public boolean isChildPlaying(UEmitter.UParticleEmitter child) {
            if (!child.isDisabled()) {
                return true;
            }
            for (var event : getGameEvents()) {
                if (event.length > 1 && event[1] == SHOW_EVENT) {
                    return true;
                }
            }
            return false;
        }

        public void markPlaced() {
            placed = true;
        }

        public boolean isPlaced() {
            return placed;
        }

        public boolean isNoDelete() {
            return noDelete;
        }

        public boolean isSetSizeScale() {
            return setSizeScale;
        }

        public float getProjectileSpeed() {
            return projectileSpeed;
        }

        public float getProjectileAcceleration() {
            return projectileAcceleration;
        }

        public List<UObject> getChildEmitters() {
            if (emitterRefs == null || emitterRefs.length == 0) {
                return List.of();
            }
            var children = new ArrayList<UObject>(emitterRefs.length);
            var loader = archive.getObjectLoader();
            for (var ref : emitterRefs) {
                if (ref != 0) {
                    var child = loader.loadObject(ref);
                    if (child != null) {
                        children.add(child);
                    }
                }
            }
            return children;
        }

        public float getVisibleLimitMin() {
            return this.visibleLimitMin;
        }

        public float getVisibleLimitMax() {
            return this.visibleLimitMax;
        }

        public float getVisibilityInterpRange() {
            return this.visibilityInterpRange;
        }

        public Vec3 getGlobalOffsetMin() {
            return this.globalOffsetMin;
        }

        public Vec3 getGlobalOffsetMax() {
            return this.globalOffsetMax;
        }

        public boolean isAutoReset() {
            return autoReset;
        }

        public int getSpawnSoundCount() {
            return spawnSoundRefs.length;
        }

        public float getSoundRadius() {
            return soundRadius;
        }

        public float getSoundVolume() {
            return soundVolume;
        }

        public float getSoundDelay() {
            return soundDelay;
        }

        public float getSoundPitchMin() {
            return soundPitchMin;
        }

        public float getSoundPitchMax() {
            return soundPitchMax;
        }

        public boolean isSoundLooping() {
            return soundLooping;
        }

        public boolean isUiSound() {
            return uiSound;
        }

        public float getSoundFadeInDuration() {
            return soundFadeInDuration;
        }

        public float getSoundFadeOutStart() {
            return soundFadeOutStart;
        }

        public float getSoundFadeOutDuration() {
            return soundFadeOutDuration;
        }

        public String peekSpawnSoundName(int i) {
            var ref = spawnSoundRefs[i];
            if (ref == 0) {
                return null;
            }
            var loader = archive.getObjectLoader();
            return spawnSoundPackage == null
                    ? loader.peekObjectFullName(ref)
                    : loader.peekObjectFullNameOf(spawnSoundPackage, ref);
        }

        public USound loadSpawnSound(int i) {
            var ref = spawnSoundRefs[i];
            if (ref == 0) {
                return null;
            }
            var loader = archive.getObjectLoader();
            var object =
                    spawnSoundPackage == null ? loader.loadObject(ref) : loader.loadObjectOf(spawnSoundPackage, ref);
            return object instanceof USound sound ? sound : null;
        }

        public URotator getRotPerSecond() {
            return rotPerSecond;
        }

        public boolean isRotEmitter() {
            return rotEmitter;
        }

        public boolean isSelfRotation() {
            return selfRotation;
        }

        public boolean isDisableFogging() {
            return disableFogging;
        }

        public boolean isAutoReplay() {
            return autoReplay;
        }

        public boolean isScreenEffect() {
            return screenEffect;
        }

        public boolean isAutoDestroy() {
            return autoDestroy;
        }

        public float getTimeTillResetMin() {
            return timeTillResetMin;
        }

        public float getTimeTillResetMax() {
            return timeTillResetMax;
        }

        public float getSpeedRate() {
            return this.speedRate;
        }

        public boolean isUseLight() {
            return useLight;
        }

        public int getLightType() {
            return lightType;
        }

        public float getLightBrightness() {
            return lightBrightness;
        }

        public int getLightHue() {
            return lightHue;
        }

        public int getLightSaturation() {
            return lightSaturation;
        }

        public int getEmitterLightingType() {
            return emitterLightingType;
        }

        public float getLightRadius() {
            return lightRadius;
        }

        public float getLightLifeSpan() {
            return lightLifeSpan;
        }

        public float getLightInitialDelay() {
            return lightInitialDelay;
        }

        private static float rangeBound(UProperty property, String name) {
            if (property.getSubProperties().isEmpty()) {
                return 0f;
            }
            var bound = property.getSubProperties().getFirst().get(name);
            return bound != null ? bound.getFloatValue() : 0f;
        }

        private static Vec3 rangeVector(UProperty property, String boundName) {
            return new Vec3(
                    axisBound(property, "X", boundName),
                    axisBound(property, "Y", boundName),
                    axisBound(property, "Z", boundName));
        }

        private static float axisBound(UProperty property, String axisName, String boundName) {
            if (property.getSubProperties().isEmpty()) {
                return 0f;
            }
            var root = property.getSubProperties().getFirst();
            var axis = root.get(axisName);
            if (axis == null || axis.getSubProperties().isEmpty()) {
                return 0f;
            }
            var bound = axis.getSubProperties().getFirst().get(boundName);
            return bound != null ? bound.getFloatValue() : 0f;
        }

        private static float readNumeric(UProperty property) {
            return property.getType() == Type.Byte ? property.getUInt8Value() & 0xFF : property.getFloatValue();
        }

        private static boolean holdsReference(UProperty property) {
            switch (property.getType()) {
                case Name, Object, Class, Delegate, Array, Map, FixedArray -> {
                    return true;
                }
                case Struct -> {
                    for (var fields : property.getSubProperties()) {
                        for (var field : fields.values()) {
                            if (holdsReference(field)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }
    }

    protected final void setTranslucentRenderPriority(int priority) {
        translucentRenderPriority = priority;
    }

    public static class LightActor extends UActor {
        public static final int LE_SUNLIGHT = 19;
        public static final int LE_SPOTLIGHT = 12;
        private int lightType = 1;
        private int lightEffect = 0;
        private float lightBrightness = 64;
        private int lightHue = 0;
        private int lightSaturation = 255;
        private float lightRadius = 64;
        private int lightCone = 128;
        private int lightPeriod = 32;
        private int lightPhase = 0;
        private float lightOnTime;
        private float lightOffTime;
        private boolean sunlightColor;
        private boolean movableSun;
        private boolean corona;
        private float minCoronaSize;
        private float maxCoronaSize = 1000f;
        private float coronaRotation;
        private float coronaRotationOffset;
        private boolean useOwnFinalBlend;

        public LightActor(UArchive archive) {
            this(archive, 0, false);
        }

        public LightActor(UArchive archive, int defaultLightEffect) {
            this(archive, defaultLightEffect, false);
        }

        public LightActor(UArchive archive, int defaultLightEffect, boolean movableSun) {
            super(archive, !movableSun);
            movable = movableSun;
            lightEffect = defaultLightEffect;
            this.movableSun = movableSun;
            sunlightColor = movableSun;
        }

        public boolean isMovableSun() {
            return movableSun;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "LightType" -> {
                    lightType = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightEffect" -> {
                    lightEffect = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightBrightness" -> {
                    lightBrightness = floatProperty(property);
                    yield true;
                }
                case "LightHue" -> {
                    lightHue = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightSaturation" -> {
                    lightSaturation = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightRadius" -> {
                    lightRadius = floatProperty(property);
                    yield true;
                }
                case "LightCone" -> {
                    lightCone = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightPeriod" -> {
                    lightPeriod = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightPhase" -> {
                    lightPhase = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "LightOnTime" -> {
                    lightOnTime = property.getFloatValue();
                    yield true;
                }
                case "LightOffTime" -> {
                    lightOffTime = property.getFloatValue();
                    yield true;
                }
                case "bSunlightColor" -> {
                    sunlightColor = property.isTrue();
                    yield true;
                }
                case "bCorona" -> {
                    corona = property.isTrue();
                    yield true;
                }
                case "MinCoronaSize" -> {
                    minCoronaSize = property.getFloatValue();
                    yield true;
                }
                case "MaxCoronaSize" -> {
                    maxCoronaSize = property.getFloatValue();
                    yield true;
                }
                case "CoronaRotation" -> {
                    coronaRotation = property.getFloatValue();
                    yield true;
                }
                case "CoronaRotationOffset" -> {
                    coronaRotationOffset = property.getFloatValue();
                    yield true;
                }
                case "UseOwnFinalBlend" -> {
                    useOwnFinalBlend = property.isTrue();
                    yield true;
                }
                default -> false;
            };
        }

        public boolean isCorona() {
            return corona;
        }

        public float getMinCoronaSize() {
            return minCoronaSize;
        }

        public float getMaxCoronaSize() {
            return maxCoronaSize;
        }

        public float getCoronaRotation() {
            return coronaRotation;
        }

        public float getCoronaRotationOffset() {
            return coronaRotationOffset;
        }

        public boolean isUseOwnFinalBlend() {
            return useOwnFinalBlend;
        }

        public int getLightType() {
            return this.lightType;
        }

        public int getLightEffect() {
            return this.lightEffect;
        }

        public float getLightBrightness() {
            return this.lightBrightness;
        }

        public int getLightHue() {
            return this.lightHue;
        }

        public int getLightSaturation() {
            return this.lightSaturation;
        }

        public float getLightRadius() {
            return this.lightRadius;
        }

        public int getLightCone() {
            return this.lightCone;
        }

        public int getLightPeriod() {
            return this.lightPeriod;
        }

        public int getLightPhase() {
            return this.lightPhase;
        }

        public float getLightOnTime() {
            return lightOnTime;
        }

        public float getLightOffTime() {
            return lightOffTime;
        }

        public boolean isSunlightColor() {
            return this.sunlightColor;
        }

        private static float floatProperty(UProperty property) {
            return property.getType() == Type.Float ? property.getFloatValue() : property.getUInt8Value() & 0xFF;
        }
    }

    public record StateFog(int state, UColor color, float start, float end, float blendTime) {
        static StateFog of(Map<String, UProperty> element) {
            var state = element.get("ZoneState");
            var color = element.get("DistanceFogColor");
            var start = element.get("DistanceFogStart");
            var end = element.get("DistanceFogEnd");
            var blend = element.get("DistanceFogBlendTime");
            return new StateFog(
                    state == null ? 0 : state.getInt32Value(),
                    color == null || color.getColorValue() == null
                            ? new UColor((byte) 0, (byte) 0, (byte) 0, (byte) 0)
                            : color.getColorValue(),
                    start == null ? 0f : start.getFloatValue(),
                    end == null ? 0f : end.getFloatValue(),
                    blend == null ? 0f : blend.getFloatValue());
        }
    }

    public record StateBlendInfo(
            int state, float delayTime, float blendTime, float blendRatio, float time, boolean active) {
        static StateBlendInfo of(Map<String, UProperty> element) {
            var state = element.get("ZoneState");
            var active = element.get("bActive");
            return new StateBlendInfo(
                    state == null ? 0 : state.getInt32Value(),
                    floatOf(element.get("DelayTime")),
                    floatOf(element.get("BlendTime")),
                    floatOf(element.get("BlendRatio")),
                    floatOf(element.get("Time")),
                    active != null && active.isTrue());
        }

        private static float floatOf(@Nullable UProperty property) {
            return property == null ? 0f : property.getFloatValue();
        }
    }

    public static class ZoneInfoActor extends UActor {
        private String zoneTag = UArchive.NONE_NAME;
        private boolean lonelyZone;
        private int[] manualExcludes = new int[0];
        private int ambientBrightness = 0;
        private int ambientHue = 0;
        private int ambientSaturation = 255;
        private Vec3 ambientVector;
        private float levelBrightness = 1f;
        private boolean useZoneLightMapIntensity;
        private float zoneLightMapIntensity = 1f;
        private boolean distanceFog;
        private boolean clearToFogColor;
        private boolean terrainZone;
        private UColor distanceFogColor = new UColor((byte) 128, (byte) 128, (byte) 128, (byte) 0);
        private float distanceFogStart = 3000f;
        private float distanceFogEnd = 8000f;
        private boolean useZoneState;
        private int curZoneState;
        private boolean useWaterZoneState;
        private boolean useTerrainZoneState;
        private List<StateFog> stateFogs = List.of();
        private boolean useBlendZoneState;
        private List<StateBlendInfo> stateBlendInfos = List.of();
        private int previousZoneState;
        private int blendingState;

        public ZoneInfoActor(UArchive archive) {
            super(archive, true);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "bLonelyZone" -> {
                    lonelyZone = property.isTrue();
                    yield true;
                }
                case "ManualExcludes" -> {
                    var refs = property.getObjectArray();
                    manualExcludes = refs == null ? new int[0] : refs.clone();
                    yield true;
                }
                case "ZoneTag" -> {
                    zoneTag = archive.resolveName(property.getIndexValue());
                    yield true;
                }
                case "AmbientBrightness" -> {
                    ambientBrightness = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "AmbientHue" -> {
                    ambientHue = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "AmbientSaturation" -> {
                    ambientSaturation = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                case "AmbientVector" -> {
                    ambientVector = property.getVectorValue();
                    yield true;
                }
                case "Brightness" -> {
                    levelBrightness = property.getFloatValue();
                    yield true;
                }
                case "UseZoneLightMapIntensity" -> {
                    useZoneLightMapIntensity = property.isTrue();
                    yield true;
                }
                case "ZoneLightMapIntensity" -> {
                    zoneLightMapIntensity = property.getFloatValue();
                    yield true;
                }
                case "bDistanceFog" -> {
                    distanceFog = property.isTrue();
                    yield true;
                }
                case "bTerrainZone" -> {
                    terrainZone = property.isTrue();
                    yield true;
                }
                case "bClearToFogColor" -> {
                    clearToFogColor = property.isTrue();
                    yield true;
                }
                case "DistanceFogColor" -> {
                    distanceFogColor = property.getColorValue();
                    yield true;
                }
                case "DistanceFogStart" -> {
                    distanceFogStart = property.getFloatValue();
                    yield true;
                }
                case "DistanceFogEnd" -> {
                    distanceFogEnd = property.getFloatValue();
                    yield true;
                }
                case "bUseZoneState" -> {
                    useZoneState = property.isTrue();
                    yield true;
                }
                case "CurZoneState" -> {
                    curZoneState = property.getInt32Value();
                    yield true;
                }
                case "bUseWaterZoneState" -> {
                    useWaterZoneState = property.isTrue();
                    yield true;
                }
                case "bUseTerrainZoneState" -> {
                    useTerrainZoneState = property.isTrue();
                    yield true;
                }
                case "StateFog" -> {
                    var entries = new ArrayList<StateFog>();
                    for (var element : property.getSubProperties()) {
                        entries.add(StateFog.of(element));
                    }
                    stateFogs = List.copyOf(entries);
                    yield true;
                }
                case "bUseBlendZoneState" -> {
                    useBlendZoneState = property.isTrue();
                    yield true;
                }
                case "StateBlendInfo" -> {
                    var entries = new ArrayList<StateBlendInfo>();
                    for (var element : property.getSubProperties()) {
                        entries.add(StateBlendInfo.of(element));
                    }
                    stateBlendInfos = List.copyOf(entries);
                    yield true;
                }
                case "PreviousZoneState" -> {
                    previousZoneState = property.getInt32Value();
                    yield true;
                }
                case "BlendingState" -> {
                    blendingState = property.getUInt8Value() & 0xFF;
                    yield true;
                }
                default -> false;
            };
        }

        public boolean isUseBlendZoneState() {
            return useBlendZoneState;
        }

        public List<StateBlendInfo> getStateBlendInfos() {
            return stateBlendInfos;
        }

        public int getPreviousZoneState() {
            return previousZoneState;
        }

        public int getBlendingState() {
            return blendingState;
        }

        public List<StateFog> getStateFogs() {
            return stateFogs;
        }

        public @Nullable StateFog findStateFog(int state) {
            for (var entry : stateFogs) {
                if (entry.state() == state) {
                    return entry;
                }
            }
            return null;
        }

        public boolean isUseZoneState() {
            return useZoneState;
        }

        public int getCurZoneState() {
            return curZoneState;
        }

        public boolean isUseWaterZoneState() {
            return useWaterZoneState;
        }

        public boolean isUseTerrainZoneState() {
            return useTerrainZoneState;
        }

        public int getDefaultZoneState() {
            return useZoneState ? curZoneState : 0;
        }

        public int getAmbientBrightness() {
            return this.ambientBrightness;
        }

        public int getAmbientHue() {
            return ambientHue;
        }

        public int getAmbientSaturation() {
            return ambientSaturation;
        }

        public boolean isUseZoneLightMapIntensity() {
            return useZoneLightMapIntensity;
        }

        public float getZoneLightMapIntensity() {
            return zoneLightMapIntensity;
        }

        public boolean isLonelyZone() {
            return lonelyZone;
        }

        public UObject[] getManualExcludes() {
            var values = new UObject[manualExcludes.length];
            for (var i = 0; i < values.length; i++) {
                if (manualExcludes[i] == 0) {
                    continue;
                }
                values[i] = archive.getObjectLoader().loadObject(manualExcludes[i]);
            }
            return values;
        }

        public String getZoneTag() {
            return zoneTag;
        }

        public Vec3 getAmbientVector() {
            return this.ambientVector;
        }

        public float getLevelBrightness() {
            return this.levelBrightness;
        }

        public boolean isDistanceFog() {
            return this.distanceFog;
        }

        public boolean isClearToFogColor() {
            return this.clearToFogColor;
        }

        public boolean isTerrainZone() {
            return this.terrainZone;
        }

        public UColor getDistanceFogColor() {
            return this.distanceFogColor;
        }

        public float getDistanceFogStart() {
            return this.distanceFogStart;
        }

        public float getDistanceFogEnd() {
            return this.distanceFogEnd;
        }
    }

    public static class L2FogInfoActor extends UActor {
        private final List<UProperty> fogProperties = new ArrayList<>();

        public L2FogInfoActor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            fogProperties.add(property);
            return true;
        }

        public List<UProperty> getFogProperties() {
            return this.fogProperties;
        }
    }

    public static class L2SkyLevelZoneInfoActor extends UActor {
        private static final Set<String> CLASS_DEFAULTS = Set.of(
                "AffectBoxMax",
                "AffectBoxMin",
                "TimeEnv",
                "Time",
                "bUseDefaultMap",
                "bUseTimeZone",
                "bRealTime",
                "ZoneStateChangeTime",
                "bShowSunActor");

        private Vec3 affectBoxMax = new Vec3();
        private Vec3 affectBoxMin = new Vec3();
        private int timeEnv;
        private float time;
        private boolean useDefaultMap;
        private boolean useTimeZone;
        private boolean realTime;
        private float zoneStateChangeTime;
        private boolean showSunActor = true;

        public L2SkyLevelZoneInfoActor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName().toLowerCase(Locale.ROOT)) {
                case "affectboxmax" -> {
                    affectBoxMax = vectorOf(property, affectBoxMax);
                    yield true;
                }
                case "affectboxmin" -> {
                    affectBoxMin = vectorOf(property, affectBoxMin);
                    yield true;
                }
                case "timeenv" -> {
                    timeEnv = property.getType() == Type.Byte ? property.getUInt8Value() : property.getInt32Value();
                    yield true;
                }
                case "time" -> {
                    time = property.getFloatValue();
                    yield true;
                }
                case "busedefaultmap" -> {
                    useDefaultMap = property.isTrue();
                    yield true;
                }
                case "busetimezone" -> {
                    useTimeZone = property.isTrue();
                    yield true;
                }
                case "brealtime" -> {
                    realTime = property.isTrue();
                    yield true;
                }
                case "zonestatechangetime" -> {
                    zoneStateChangeTime = property.getFloatValue();
                    yield true;
                }
                case "bshowsunactor" -> {
                    showSunActor = property.isTrue();
                    yield true;
                }
                default -> false;
            };
        }

        @Override
        public Set<String> getClassDefaultNames() {
            return CLASS_DEFAULTS;
        }

        public Vec3 getAffectBoxMax() {
            return new Vec3(affectBoxMax);
        }

        public Vec3 getAffectBoxMin() {
            return new Vec3(affectBoxMin);
        }

        public int getTimeEnv() {
            return timeEnv;
        }

        public float getTime() {
            return time;
        }

        public boolean isUseDefaultMap() {
            return useDefaultMap;
        }

        public boolean isUseTimeZone() {
            return useTimeZone;
        }

        public boolean isRealTime() {
            return realTime;
        }

        public float getZoneStateChangeTime() {
            return zoneStateChangeTime;
        }

        public boolean isShowSunActor() {
            return showSunActor;
        }

        private static Vec3 vectorOf(UProperty property, Vec3 fallback) {
            var value = property.getVectorValue();
            return value == null ? fallback : new Vec3(value);
        }
    }

    public static class NCelestialActor extends UActor {
        private float radius;
        private float celestialScale = 1f;
        private int envType;

        public NCelestialActor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Radius" -> {
                    radius = property.getFloatValue();
                    yield true;
                }
                case "CelestialScale", "SunScale" -> {
                    celestialScale = property.getFloatValue();
                    yield true;
                }
                case "EnvType" -> {
                    envType = property.getInt32Value();
                    yield true;
                }
                default -> false;
            };
        }

        public float getRadius() {
            return this.radius;
        }

        public float getCelestialScale() {
            return this.celestialScale;
        }

        public int getEnvType() {
            return this.envType;
        }
    }

    public static class SkyZoneInfoActor extends ZoneInfoActor {
        private static final int LENS_FLARE_COUNT = 9;
        private static final Set<String> CLASS_DEFAULTS = Set.of("UseDefaultMap");

        private float texUPanSpeed = 0.2f;
        private boolean useDefaultMap = true;
        private float texVPanSpeed = 0.2f;
        private final int[] lensFlareRefs = new int[LENS_FLARE_COUNT];
        private final float[] lensFlareOffset = new float[LENS_FLARE_COUNT];
        private final float[] lensFlareScale = new float[LENS_FLARE_COUNT];

        public SkyZoneInfoActor(UArchive archive) {
            super(archive);
        }

        public boolean isUseDefaultMap() {
            return useDefaultMap;
        }

        @Override
        public Set<String> getClassDefaultNames() {
            return CLASS_DEFAULTS;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "UseDefaultMap" -> {
                    useDefaultMap = property.isTrue();
                    yield true;
                }
                case "TexUPanSpeed" -> {
                    texUPanSpeed = property.getFloatValue();
                    yield true;
                }
                case "TexVPanSpeed" -> {
                    texVPanSpeed = property.getFloatValue();
                    yield true;
                }
                case "LensFlare" -> {
                    var i = property.getArrayIndex();
                    if (i >= 0 && i < LENS_FLARE_COUNT) {
                        lensFlareRefs[i] = property.getIndexValue();
                    }
                    yield true;
                }
                case "LensFlareOffset" -> {
                    var i = property.getArrayIndex();
                    if (i >= 0 && i < LENS_FLARE_COUNT) {
                        lensFlareOffset[i] = property.getFloatValue();
                    }
                    yield true;
                }
                case "LensFlareScale" -> {
                    var i = property.getArrayIndex();
                    if (i >= 0 && i < LENS_FLARE_COUNT) {
                        lensFlareScale[i] = property.getFloatValue();
                    }
                    yield true;
                }
                default -> false;
            };
        }

        public @Nullable UObject getLensFlare(int i) {
            if (i < 0 || i >= LENS_FLARE_COUNT || lensFlareRefs[i] == 0) {
                return null;
            }
            return archive.getObjectLoader().loadObject(lensFlareRefs[i]);
        }

        public int getLensFlareCount() {
            return LENS_FLARE_COUNT;
        }

        public float getTexUPanSpeed() {
            return this.texUPanSpeed;
        }

        public float getTexVPanSpeed() {
            return this.texVPanSpeed;
        }

        public int[] getLensFlareRefs() {
            return this.lensFlareRefs;
        }

        public float[] getLensFlareOffset() {
            return this.lensFlareOffset;
        }

        public float[] getLensFlareScale() {
            return this.lensFlareScale;
        }
    }

    public static class VolumeActor extends BrushActor {
        public VolumeActor(UArchive archive) {
            super(archive);
        }
    }

    public static class BlockingVolumeActor extends VolumeActor {
        public BlockingVolumeActor(UArchive archive) {
            super(archive);
        }
    }

    public record ZoneMusicState(int state, int musicId) {
        static ZoneMusicState of(Map<String, UProperty> element) {
            var state = element.get("ZoneState");
            var musicId = element.get("MusicID");
            return new ZoneMusicState(
                    state == null ? 0 : state.getInt32Value(), musicId == null ? 0 : musicId.getInt32Value());
        }
    }

    public static class MusicVolumeActor extends VolumeActor {
        private int musicId;
        private boolean forcePlayMusic;
        private boolean loopMusic;
        private boolean hasNightMusic;
        private int nightMusicId;
        private List<ZoneMusicState> zoneMusicStates = List.of();

        public MusicVolumeActor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "nMusicID" -> {
                    musicId = property.getInt32Value();
                    yield true;
                }
                case "bForcePlayMusic" -> {
                    forcePlayMusic = property.isTrue();
                    yield true;
                }
                case "bLoopMusic" -> {
                    loopMusic = property.isTrue();
                    yield true;
                }
                case "bHasNightMusic" -> {
                    hasNightMusic = property.isTrue();
                    yield true;
                }
                case "nNightMusicID" -> {
                    nightMusicId = property.getInt32Value();
                    yield true;
                }
                case "ZoneMusicState" -> {
                    var entries = new ArrayList<ZoneMusicState>();
                    for (var element : property.getSubProperties()) {
                        entries.add(ZoneMusicState.of(element));
                    }
                    zoneMusicStates = List.copyOf(entries);
                    yield true;
                }
                default -> false;
            };
        }

        public int getMusicId() {
            return this.musicId;
        }

        public List<ZoneMusicState> getZoneMusicStates() {
            return zoneMusicStates;
        }

        public boolean isForcePlayMusic() {
            return this.forcePlayMusic;
        }

        public boolean isLoopMusic() {
            return this.loopMusic;
        }

        public boolean isHasNightMusic() {
            return this.hasNightMusic;
        }

        public int getNightMusicId() {
            return this.nightMusicId;
        }
    }

    public static class AmbientVolumeActor extends VolumeActor {
        private static final Set<String> CLASS_DEFAULTS = Set.of("Priority", "FadeInTime", "FadeOutTime");

        private int priority = 1;
        private float fadeInTime = 1f;
        private float fadeOutTime = 1f;
        private int[] sounds = new int[0];
        private boolean eventPlaying;
        private int[] zonePlayStates = new int[0];

        public AmbientVolumeActor(UArchive archive) {
            super(archive);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Priority" -> {
                    priority = property.getInt32Value();
                    yield true;
                }
                case "FadeInTime" -> {
                    fadeInTime = property.getFloatValue();
                    yield true;
                }
                case "FadeOutTime" -> {
                    fadeOutTime = property.getFloatValue();
                    yield true;
                }
                case "AmbientVolumeSounds" -> {
                    var refs = property.getObjectArray();
                    sounds = refs == null ? new int[0] : refs.clone();
                    yield true;
                }
                case "EventPlaying" -> {
                    eventPlaying = property.isTrue();
                    yield true;
                }
                case "ZonePlayState" -> {
                    var states = property.getIntArray();
                    zonePlayStates = states == null ? new int[0] : states.clone();
                    yield true;
                }
                default -> false;
            };
        }

        @Override
        public Set<String> getClassDefaultNames() {
            return CLASS_DEFAULTS;
        }

        public int getPriority() {
            return priority;
        }

        public float getFadeInTime() {
            return fadeInTime;
        }

        public float getFadeOutTime() {
            return fadeOutTime;
        }

        public int getSoundCount() {
            return sounds.length;
        }

        public @Nullable UAmbientVolumeSound loadSound(int i) {
            var ref = sounds[i];
            return ref != 0 && archive.getObjectLoader().loadObject(ref) instanceof UAmbientVolumeSound sound
                    ? sound
                    : null;
        }

        public boolean isEventPlaying() {
            return eventPlaying;
        }

        public int[] getZonePlayStates() {
            return zonePlayStates.clone();
        }
    }

    public static class AmbientSoundActor extends UActor {
        private static final Set<String> CLASS_DEFAULTS =
                Set.of("SoundRadius", "SoundVolume", "SoundPitch", "AmbientSoundType", "AmbientRandom");

        private final UObjectRef<USound, Optional> ambientSound = UObjectRef.optional();
        private float soundRadius = 64f;
        private int soundVolume = 128;
        private int soundPitch = 64;
        private int ambientSoundType;
        private int ambientRandom;
        private float ambientSoundStartTime;
        private List<float[]> ambientSoundRangeTime = List.of();
        private int[] zonePlayStates = new int[0];

        public AmbientSoundActor(UArchive archive) {
            super(archive);
        }

        AmbientSoundActor(UArchive archive, float soundRadius, int soundVolume) {
            super(archive);
            this.soundRadius = soundRadius;
            this.soundVolume = soundVolume;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "AmbientSound" -> {
                    ambientSound.fromProperty(archive, property);
                    yield true;
                }
                case "SoundRadius" -> {
                    soundRadius = property.getFloatValue();
                    yield true;
                }
                case "SoundVolume" -> {
                    soundVolume = property.getUInt8Value() & 0xff;
                    yield true;
                }
                case "SoundPitch" -> {
                    soundPitch = property.getUInt8Value() & 0xff;
                    yield true;
                }
                case "AmbientSoundType" -> {
                    ambientSoundType = property.getUInt8Value() & 0xff;
                    yield true;
                }
                case "AmbientRandom" -> {
                    ambientRandom = property.getInt32Value();
                    yield true;
                }
                case "AmbientSoundStartTime" -> {
                    ambientSoundStartTime = property.getFloatValue();
                    yield true;
                }
                case "AmbientSoundRangeTime" -> {
                    var pairs = new ArrayList<float[]>();
                    for (var element : property.getSubProperties()) {
                        pairs.add(new float[] {boundOf(element.get("Min")), boundOf(element.get("Max"))});
                    }
                    ambientSoundRangeTime = List.copyOf(pairs);
                    yield true;
                }
                case "ZonePlayState" -> {
                    var states = property.getIntArray();
                    zonePlayStates = states == null ? new int[0] : states.clone();
                    yield true;
                }
                default -> false;
            };
        }

        @Override
        public Set<String> getClassDefaultNames() {
            return CLASS_DEFAULTS;
        }

        public float getAmbientSoundStartTime() {
            return ambientSoundStartTime;
        }

        public List<float[]> getAmbientSoundRangeTime() {
            var copy = new ArrayList<float[]>(ambientSoundRangeTime.size());
            for (var pair : ambientSoundRangeTime) {
                copy.add(pair.clone());
            }
            return copy;
        }

        public int[] getZonePlayStates() {
            return zonePlayStates.clone();
        }

        public int getZonePlayMask() {
            return zoneStateMask(zonePlayStates);
        }

        public UObjectRef<USound, Optional> getAmbientSound() {
            return this.ambientSound;
        }

        public float getSoundRadius() {
            return this.soundRadius;
        }

        public int getSoundVolume() {
            return this.soundVolume;
        }

        public int getSoundPitch() {
            return this.soundPitch;
        }

        public int getAmbientSoundType() {
            return this.ambientSoundType;
        }

        public int getAmbientRandom() {
            return this.ambientRandom;
        }

        private static float boundOf(@Nullable UProperty property) {
            return property == null ? 0f : property.getFloatValue();
        }
    }

    public static class AmbientSoundWObjectActor extends AmbientSoundActor {
        private static final Set<String> CLASS_DEFAULTS = Set.of(
                "SoundRadius",
                "SoundVolume",
                "SoundPitch",
                "AmbientSoundType",
                "AmbientRandom",
                "PlayMode",
                "InitialDelay",
                "Duration");

        private int[] soundList = new int[0];
        private int playMode;
        private Randomizer initialDelay = Randomizer.NONE;
        private Randomizer duration = new Randomizer(1, 0, 0);

        public AmbientSoundWObjectActor(UArchive archive) {
            super(archive, 50f, 250);
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "SoundList" -> {
                    var refs = property.getObjectArray();
                    soundList = refs == null ? new int[0] : refs.clone();
                    yield true;
                }
                case "PlayMode" -> {
                    playMode = property.getUInt8Value() & 0xff;
                    yield true;
                }
                case "InitialDelay" -> {
                    initialDelay = randomizer(property, initialDelay);
                    yield true;
                }
                case "Duration" -> {
                    duration = randomizer(property, duration);
                    yield true;
                }
                default -> false;
            };
        }

        @Override
        public Set<String> getClassDefaultNames() {
            return CLASS_DEFAULTS;
        }

        public int getSoundCount() {
            return soundList.length;
        }

        public @Nullable String peekSoundName(int i) {
            var ref = soundList[i];
            return ref == 0 ? null : archive.getObjectLoader().peekObjectFullName(ref);
        }

        public @Nullable USound loadSound(int i) {
            var ref = soundList[i];
            return ref != 0 && archive.getObjectLoader().loadObject(ref) instanceof USound sound ? sound : null;
        }

        public int getPlayMode() {
            return playMode;
        }

        public Randomizer getInitialDelay() {
            return initialDelay;
        }

        public Randomizer getDuration() {
            return duration;
        }

        private static Randomizer randomizer(UProperty property, Randomizer fallback) {
            var elements = property.getSubProperties();
            if (elements.isEmpty()) {
                return fallback;
            }
            var element = elements.getFirst();
            return new Randomizer(
                    intOf(element, "Time", fallback.time()),
                    intOf(element, "OffsetMin", fallback.offsetMin()),
                    intOf(element, "OffsetMax", fallback.offsetMax()));
        }

        private static int intOf(Map<String, UProperty> element, String name, int fallback) {
            var value = element.get(name);
            return value == null ? fallback : value.getInt32Value();
        }
    }

    public static class PhysicsVolumeActor extends VolumeActor {
        private int priority;
        protected boolean waterVolume;
        protected boolean l2WaterVolume;
        private Vec3 viewFog;
        protected boolean distanceFog;
        protected boolean useDistanceFogColor;
        protected UColor distanceFogColor;
        protected float distanceFogStart;
        protected float distanceFogEnd;
        private boolean useCellophane;
        private UColor cellophaneColor;

        public PhysicsVolumeActor(UArchive archive) {
            super(archive);
            this.waterVolume = false;
        }

        @Override
        public boolean setProperty(UProperty property) {
            if (super.setProperty(property)) {
                return true;
            }
            return switch (property.getName()) {
                case "Priority" -> {
                    priority = property.getInt32Value();
                    yield true;
                }
                case "bWaterVolume" -> {
                    waterVolume = property.isTrue();
                    yield true;
                }
                case "bL2WaterVolume" -> {
                    l2WaterVolume = property.isTrue();
                    yield true;
                }
                case "ViewFog" -> {
                    viewFog = property.getVectorValue();
                    yield true;
                }
                case "bDistanceFog" -> {
                    distanceFog = property.isTrue();
                    yield true;
                }
                case "bUseDistanceFogColor" -> {
                    useDistanceFogColor = property.isTrue();
                    yield true;
                }
                case "DistanceFogColor" -> {
                    distanceFogColor = property.getColorValue();
                    yield true;
                }
                case "DistanceFogStart" -> {
                    distanceFogStart = property.getFloatValue();
                    yield true;
                }
                case "DistanceFogEnd" -> {
                    distanceFogEnd = property.getFloatValue();
                    yield true;
                }
                case "bUseCellophane" -> {
                    useCellophane = property.isTrue();
                    yield true;
                }
                case "CellophaneColor" -> {
                    cellophaneColor = property.getColorValue();
                    yield true;
                }
                default -> false;
            };
        }

        public boolean isWaterVolume() {
            return this.waterVolume;
        }

        public int getPriority() {
            return priority;
        }

        public boolean isL2WaterVolume() {
            return this.l2WaterVolume;
        }

        public Vec3 getViewFog() {
            return this.viewFog;
        }

        public boolean isDistanceFog() {
            return this.distanceFog;
        }

        public boolean isUseDistanceFogColor() {
            return this.useDistanceFogColor;
        }

        public UColor getDistanceFogColor() {
            return this.distanceFogColor;
        }

        public float getDistanceFogStart() {
            return this.distanceFogStart;
        }

        public float getDistanceFogEnd() {
            return this.distanceFogEnd;
        }

        public boolean isUseCellophane() {
            return this.useCellophane;
        }

        public UColor getCellophaneColor() {
            return this.cellophaneColor;
        }
    }

    public static class WaterVolumeActor extends PhysicsVolumeActor {

        public WaterVolumeActor(UArchive archive) {
            super(archive);
            this.waterVolume = true;
            this.l2WaterVolume = true;
            this.distanceFog = true;
            this.distanceFogColor = new UColor((byte) 32, (byte) 64, (byte) 128, (byte) 64);
            this.distanceFogStart = 8f;
            this.distanceFogEnd = 2_000f;
        }

        @Override
        public boolean setProperty(UProperty property) {
            return super.setProperty(property);
        }
    }

    public Vec3 getLocation() {
        return this.location;
    }

    public URotator getRotation() {
        return this.rotation;
    }

    public float getDrawScale() {
        return this.drawScale;
    }

    public float getCullDistance() {
        return cullDistance;
    }

    public float getScaleGlow() {
        return this.scaleGlow;
    }

    public int getTranslucentRenderPriority() {
        return this.translucentRenderPriority;
    }

    public int getAmbientGlow() {
        return this.ambientGlow;
    }

    public int getMaxLights() {
        return this.maxLights;
    }

    public boolean isUnlit() {
        return this.unlit;
    }

    public int getStyle() {

        return style;
    }

    public int getDrawType() {

        return drawType;
    }

    public boolean isShadowCast() {
        return this.shadowCast;
    }

    public boolean isUseLightingFromBase() {
        return this.useLightingFromBase;
    }

    public boolean isSpecialLit() {
        return this.specialLit;
    }

    public boolean isUnlitCheck() {
        return this.unlitCheck;
    }

    public boolean isCulledSunlight() {
        return this.culledSunlight;
    }

    public boolean isAcceptsProjectors() {
        return this.acceptsProjectors;
    }

    public UObjectRef<UActor, Optional> getBase() {
        return this.base;
    }

    public boolean isSunAffect() {
        return this.sunAffect;
    }

    public boolean isSunAffectSerialized() {
        return this.sunAffectSerialized;
    }

    public int getRegionLeaf() {
        return this.regionLeaf;
    }

    public int getRegionZoneNumber() {
        return this.regionZoneNumber;
    }

    public Vec3 getDrawScale3d() {
        return this.drawScale3d;
    }

    public Vec3 getPrePivot() {
        return this.prePivot;
    }

    public int[] getSkinRefs() {
        return this.skinRefs;
    }

    public UObjectRef<UObject, Optional> getMesh() {
        return mesh;
    }

    public UObjectRef<UStaticMesh, Optional> getStaticMesh() {
        return this.staticMesh;
    }

    public UObjectRef<UConvexVolume, Optional> getAntiPortal() {
        return antiPortal;
    }

    public boolean isDeleteMe() {
        return this.deleteMe;
    }

    public boolean isStatic() {
        return staticActor;
    }

    public boolean isHidden() {
        return this.hidden;
    }

    public boolean isUseL2ActorViewType() {
        return useL2ActorViewType;
    }

    public boolean isIgnoredRange() {
        return ignoredRange;
    }

    public float getRangeForIgnoredRange() {
        return rangeForIgnoredRange;
    }

    public float getL2ActorViewDuration() {
        return l2ActorViewDuration;
    }

    public float getL2ActorViewElapsedTime() {
        return l2ActorViewElapsedTime;
    }

    public int getL2ActorViewType() {
        return l2ActorViewType;
    }

    protected void setUseL2ActorViewType(boolean value) {
        useL2ActorViewType = value;
    }

    public boolean isDynamicLight() {
        return this.dynamicLight;
    }

    public boolean isUseDynamicLights() {
        return this.useDynamicLights;
    }

    public boolean isLightChanged() {
        return this.lightChanged;
    }

    public boolean isCollideActors() {
        return this.collideActors;
    }

    public boolean isBlockActors() {
        return this.blockActors;
    }

    public boolean isBlockPlayers() {
        return this.blockPlayers;
    }

    public boolean isBlockNonZeroExtentTraces() {
        return this.blockNonZeroExtentTraces;
    }

    public boolean isUseCylinderCollision() {
        return this.useCylinderCollision;
    }

    public boolean isWorldGeometry() {
        return this.worldGeometry;
    }

    public boolean isBlockZeroExtentTraces() {
        return blockZeroExtentTraces;
    }

    public boolean isHiddenEd() {
        return hiddenEd;
    }

    public boolean isHiddenEdGroup() {
        return hiddenEdGroup;
    }

    private static int[] zoneStatesOf(UProperty property) {
        var states = property.getIntArray();
        if (states != null) {
            return states.clone();
        }
        var elements = property.getSubProperties();
        if (elements == null || elements.isEmpty()) {
            return new int[0];
        }
        var result = new int[elements.size()];
        for (var i = 0; i < result.length; i++) {
            var state = elements.get(i).get("ZoneState");
            result[i] = state == null ? 0 : state.getInt32Value();
        }
        return result;
    }

    private static List<int[]> zoneSkinRefsOf(UProperty property) {
        var elements = property.getSubProperties();
        if (property.getIntArray() != null || elements == null || elements.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<int[]>(elements.size());
        for (var element : elements) {
            var skins = element.get("Skins");
            var refs = skins == null ? null : skins.getObjectArray();
            result.add(refs == null ? new int[0] : refs.clone());
        }
        return List.copyOf(result);
    }
}
