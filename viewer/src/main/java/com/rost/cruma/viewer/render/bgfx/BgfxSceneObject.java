package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.ActorMotion;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.SourcePart;
import com.rost.cruma.core.world.Entity.StaticMeshLod;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.NativeLightReceiver;
import com.rost.cruma.core.world.NativeLightmapScale;
import com.rost.cruma.core.world.NativeStaticMeshDraw;
import com.rost.cruma.core.world.NativeStaticMeshLightingInputs;
import com.rost.cruma.core.world.NativeVertexLightmapState.Binding;
import com.rost.cruma.core.world.ZoneFade;
import com.rost.cruma.core.world.ZoneFades;
import com.rost.cruma.core.world.ZoneLook;
import com.rost.cruma.core.world.ZoneRender;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.render.api.DebugMaterialSlot;
import com.rost.cruma.viewer.render.api.StableRenderId;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.PreparedPrimitive;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

final class BgfxSceneObject implements AutoCloseable {
    static final int INSTANCE_FLOATS = BgfxSceneVertexLayouts.INSTANCE.getStride() / Float.BYTES;

    static final float BEAST_LIGHTMAP_LAYER = -100000f;

    private static final int VISIBILITY_LEVEL_MAX = 15;
    private static final int VISIBILITY_SIGNATURE = 10;
    private static final int HARDWARE_SUN_SIGNATURE = 11;
    private static final int FLAT_SUN_SIGNATURE = 12;

    private final long stableId;
    private final String owner;
    private List<String> sourceActors = List.of();
    private List<SourcePart> sourceParts = List.of();
    private String sourceAsset;
    private List<DebugMaterialSlot> diagnosticMaterials;

    private final String terrainProxyDetailOwner;

    private String terrainPair;

    private final BgfxSceneGeometry geometry;
    private final TextureRef lightmap;
    private final boolean vertexLightmapLayers;
    private final int lightmapVariantCount;
    private final int lightmapVariantStride;
    private final LightmapBinding lightmapBinding;
    private final boolean beastLightmap;
    private final boolean wireframe;
    private final boolean foliage;
    private final boolean mergedRegion;
    private final boolean acceptsProjectors;
    private final StaticMeshLod staticMeshLod;
    private final float[] instanceRecords;
    private final int instanceCount;
    private NativeLightReceiver[] nativeLightReceivers = new NativeLightReceiver[0];
    private NativeStaticMeshLightingInputs[] nativeLightingInputs = new NativeStaticMeshLightingInputs[0];
    private NativeStaticMeshDraw nativeStaticMeshDraw;
    private Binding nativeVertexLightmap;
    private NativeLightmapScale lightmapScale = NativeLightmapScale.ENVIRONMENT;
    private boolean terrainLighting;
    private final int terrainDecorationZone;
    private float decorationFadeMax;
    private @Nullable DecorationSlots decorationSlots;

    private record DecorationSlots(BitSet occupied, String[] actors, float[] fades) {}

    private final float[] terrainModel;
    private Bounds3f worldBounds;
    private ActorMotion movement;
    private List<Mat4> movementLocalInstances;
    private List<float[]> movementModels;
    private Mat4 movementModelScratch;
    private Mat4 movementBase;
    private ByteBuffer movementData;
    private FloatBuffer movementFloats;
    private boolean movementBillboard;
    private double clearanceScale = -1;
    private final Bounds3f[] primitiveBounds;
    private final List<BgfxActorLightingBuffer.Slice> actorLighting;
    private BgfxInstancePool.Slice instanceStorage;
    private EditorPreview editorPreview;
    private ZoneStates zoneStates;

    private ByteBuffer deferredInstanceData;

    private boolean closed;
    private boolean nativeStaticMeshAmbient;

    private BgfxSceneObject(
            BgfxInstancePool instancePool,
            List<BgfxActorLightingBuffer.Slice> actorLighting,
            long stableId,
            String owner,
            BgfxSceneGeometry geometry,
            TextureRef lightmap,
            boolean vertexLightmapLayers,
            int lightmapVariantCount,
            int lightmapVariantStride,
            boolean beastLightmap,
            List<Integer> lightmapVariantSlots,
            boolean wireframe,
            boolean foliage,
            boolean mergedRegion,
            boolean acceptsProjectors,
            StaticMeshLod staticMeshLod,
            float[] instanceRecords,
            ByteBuffer instanceData,
            boolean deferInstanceUpload,
            int terrainDecorationZone,
            float[] terrainModel,
            Bounds3f worldBounds,
            Bounds3f[] primitiveBounds) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("scene object owner cannot be null or blank");
        }
        this.stableId = stableId;
        this.owner = owner;
        this.geometry = geometry;
        this.terrainProxyDetailOwner =
                geometry.getKind() == Kind.TERRAIN && BgfxSceneUploadSink.isTerrainProxyOwner(owner)
                        ? BgfxSceneUploadSink.terrainDetailOwner(owner)
                        : null;
        this.lightmap = lightmap;
        this.vertexLightmapLayers = vertexLightmapLayers;
        this.lightmapVariantCount = lightmapVariantCount;
        this.lightmapVariantStride = lightmapVariantStride;
        this.beastLightmap = beastLightmap;
        this.lightmapBinding = new LightmapBinding(
                lightmap,
                vertexLightmapLayers,
                lightmapVariantCount,
                lightmapVariantStride,
                beastLightmap,
                lightmapVariantSlots);
        this.wireframe = wireframe;
        this.foliage = foliage;
        this.mergedRegion = mergedRegion;
        this.acceptsProjectors = acceptsProjectors;
        this.staticMeshLod = staticMeshLod;
        this.instanceRecords = instanceRecords;
        this.instanceCount = geometry.getKind().isInstanced() ? instanceRecords.length / INSTANCE_FLOATS : 1;
        this.terrainLighting = usesTerrainLighting(instanceRecords);
        this.terrainDecorationZone = terrainDecorationZone;
        this.terrainModel = terrainModel;
        this.worldBounds = worldBounds;
        this.primitiveBounds = primitiveBounds;
        this.actorLighting = List.copyOf(actorLighting);
        if (geometry.getKind().isInstanced()) {
            if (instanceData == null || !instanceData.hasRemaining()) {
                throw new IllegalArgumentException("entity scene object requires packed instance data");
            }
            if (deferInstanceUpload) {
                deferredInstanceData = instanceData.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
                deferredInstanceData.position(0);
            } else {
                instanceStorage = instancePool.allocate(
                        "instances/" + geometry.getLabel() + '-' + Long.toUnsignedString(stableId, 16), instanceData);
            }
        }
    }

    static BgfxSceneObject createDecorationPool(
            BgfxInstancePool instancePool,
            BgfxActorLightingBuffer actorLightingBuffer,
            BgfxTextureCache textures,
            Entity representative,
            BgfxSceneGeometry geometry,
            long stableId,
            int capacity,
            Bounds3f bounds) {
        if (capacity < 1 || !geometry.getKind().isInstanced()) {
            throw new IllegalArgumentException("a decoration pool needs instanced geometry and slots");
        }
        var sample = prepare(representative, geometry, BgfxDecorations.OWNER, 0);
        var records = new float[Math.multiplyExact(capacity, INSTANCE_FLOATS)];
        var primitiveBounds = new Bounds3f[geometry.getPrimitives().size()];
        Arrays.fill(primitiveBounds, bounds);
        var prepared = new Prepared(
                stableId,
                geometry.getStableId(),
                BgfxDecorations.OWNER,
                0,
                sample.foliage(),
                sample.mergedRegion(),
                sample.acceptsProjectors(),
                records,
                packInstances(records),
                null,
                bounds,
                primitiveBounds);
        var pool = install(instancePool, actorLightingBuffer, textures, representative, geometry, prepared);
        try {
            pool.terrainLighting = usesTerrainLighting(sample.instanceRecords());
            var slots = new DecorationSlots(new BitSet(capacity), new String[capacity], new float[capacity]);
            pool.decorationSlots = slots;
            pool.sourceActors = new AbstractList<>() {
                @Override
                public String get(int index) {
                    var actor = slots.actors()[index];
                    return actor == null ? "Unavailable" : actor;
                }

                @Override
                public int size() {
                    return slots.actors().length;
                }
            };
            pool.publishDeferredInstances(instancePool);
            return pool;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(pool, failure);
            throw failure;
        }
    }

    static BgfxSceneObject create(
            BgfxInstancePool instancePool,
            BgfxActorLightingBuffer actorLightingBuffer,
            BgfxTextureCache textures,
            Entity source,
            BgfxSceneGeometry geometry,
            String mapName,
            int entityOrdinal) {
        var prepared = prepare(source, geometry, mapName, entityOrdinal);
        return install(instancePool, actorLightingBuffer, textures, source, geometry, prepared);
    }

    static Prepared prepare(Entity source, BgfxSceneGeometry.Prepared geometry, String mapName, int entityOrdinal) {
        if (geometry == null) {
            throw new IllegalArgumentException("scene object preparation requires geometry");
        }
        return prepare(
                source,
                geometry.stableId(),
                geometry.kind(),
                geometry.localBounds(),
                geometry.primitives().stream()
                        .map(PreparedPrimitive::localBounds)
                        .toList(),
                mapName,
                entityOrdinal);
    }

    static Prepared prepare(Entity source, BgfxSceneGeometry geometry, String mapName, int entityOrdinal) {
        return prepare(
                source,
                geometry.getStableId(),
                geometry.getKind(),
                geometry.getLocalBounds(),
                geometry.getPrimitives().stream().map(Primitive::localBounds).toList(),
                mapName,
                entityOrdinal);
    }

    static BgfxSceneObject install(
            BgfxInstancePool instancePool,
            BgfxActorLightingBuffer actorLightingBuffer,
            BgfxTextureCache textures,
            Entity source,
            BgfxSceneGeometry geometry,
            Prepared prepared) {
        return install(instancePool, actorLightingBuffer, textures, source, geometry, prepared, null);
    }

    static BgfxSceneObject install(
            BgfxInstancePool instancePool,
            BgfxActorLightingBuffer actorLightingBuffer,
            BgfxTextureCache textures,
            Entity source,
            BgfxSceneGeometry geometry,
            Prepared prepared,
            @Nullable BgfxNativeLightSources nativeLights) {
        if (prepared == null || prepared.geometryStableId() != geometry.getStableId()) {
            throw new IllegalArgumentException("prepared scene object does not match installed geometry");
        }
        var lightmap = loadLightmap(textures, source, prepared.owner(), prepared.entityOrdinal());
        BgfxActorLightingBuffer.Slice actorLighting = null;
        try {
            var instanceRecords = prepared.instanceRecords();
            var instanceData = prepared.instanceData();
            var actorPayload = source.getActorVertexLighting();
            if (actorPayload != null || source.getStaticMeshAmbient() != null) {
                if (!geometry.getKind().isInstanced()
                        || actorPayload != null && actorPayload.vertexCount() != geometry.getVertexCount()) {
                    throw new IllegalArgumentException("actor lighting is incompatible with shared geometry: owner="
                            + prepared.owner()
                            + " entity="
                            + prepared.entityOrdinal()
                            + " mesh="
                            + source.getMeshName()
                            + " kind="
                            + geometry.getKind()
                            + " geometryVertices="
                            + geometry.getVertexCount()
                            + " lightingVertices="
                            + (actorPayload == null ? "none" : actorPayload.vertexCount()));
                }
                actorLighting = actorLightingBuffer.allocate(
                        "actor-lighting/" + prepared.owner() + '-' + prepared.entityOrdinal(),
                        actorPayload,
                        source.getInstanceLight(),
                        source.getHardwareSunLight(),
                        source.isFlatSunAmbient(),
                        source.getStaticMeshAmbientIntensity(),
                        source.getStaticMeshAmbient(),
                        source.getHardwareSunDirection(),
                        source.getHardwareSunEnvironment(),
                        nativeLights == null || source.getHardwareSunSource() == null
                                ? null
                                : nativeLights.bind(source.getHardwareSunSource()));
                applyActorLightingMetadata(
                        instanceRecords, actorLighting.getOffset(), geometry.getIndirectVertexOffset());
                instanceData = patchActorLightingMetadata(
                        instanceData,
                        instanceRecords.length / INSTANCE_FLOATS,
                        actorLighting.getOffset(),
                        geometry.getIndirectVertexOffset());
            }
            var object = new BgfxSceneObject(
                    instancePool,
                    actorLighting == null ? List.of() : List.of(actorLighting),
                    prepared.stableId(),
                    prepared.owner(),
                    geometry,
                    lightmap.texture,
                    lightmap.vertexLayers,
                    lightmap.variantCount,
                    lightmap.variantStride,
                    lightmap.beast,
                    lightmap.variantSlots,
                    source.isWireframe(),
                    prepared.foliage(),
                    prepared.mergedRegion(),
                    prepared.acceptsProjectors(),
                    source.getStaticMeshLod(),
                    instanceRecords,
                    instanceData,
                    true,
                    source.getTerrainDecorationZone(),
                    prepared.terrainModel(),
                    prepared.worldBounds(),
                    prepared.primitiveBounds());
            object.nativeStaticMeshAmbient = source.getStaticMeshAmbient() != null;
            object.nativeStaticMeshDraw = source.getNativeStaticMeshDraw();
            object.decorationFadeMax = source.getMesh() instanceof EntityMesh mesh && mesh.decorationFadeMax() > 0f
                    ? mesh.decorationFadeMax()
                    : 0f;
            var receiver = source.getNativeLightReceiver();
            if (receiver != null && !receiver.getFlags().unlit()) {
                object.lightmapScale = receiver.getLightmapScale();
            }
            if (source.getNativeStaticMeshLightingInputs() != null) {
                object.nativeLightingInputs = new NativeStaticMeshLightingInputs[object.instanceCount];
                Arrays.fill(object.nativeLightingInputs, source.getNativeStaticMeshLightingInputs());
            }
            if (source.getNativeLightReceiver() != null) {
                object.nativeLightReceivers = new NativeLightReceiver[object.instanceCount];
                Arrays.fill(object.nativeLightReceivers, source.getNativeLightReceiver());
            }
            object.movementBillboard = source.getMesh() instanceof EntityMesh mesh && mesh.billboard();
            if (source.getMovement() != null) {
                object.bindMotion(source, source.getMovement().newState());
            }
            var actor = source.getSourceActor() == null ? "Unavailable" : source.getSourceActor();
            object.sourceActors = Collections.nCopies(
                    object.instanceCount, object.terrainProxyDetailOwner == null ? actor : "Unavailable");
            if (geometry.getKind() == Kind.TERRAIN) {
                object.terrainPair =
                        (object.terrainProxyDetailOwner == null ? object.owner : object.terrainProxyDetailOwner)
                                + '|'
                                + actor;
            }
            object.sourceAsset = source.getSourceAsset();
            object.sourceParts = source.getSourceParts();
            if (geometry.getKind().isInstanced() && source.getZoneRenderMask() != 0) {
                object.zoneStates =
                        ZoneStates.uniform(object.instanceCount, source.getZoneRender(), source.getZoneLook());
            }
            return object;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(actorLighting, failure);
            closeAfterFailure(lightmap.texture, failure);
            throw failure;
        }
    }

    static BgfxSceneObject merge(BgfxInstancePool instancePool, List<BgfxSceneObject> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("cannot merge an empty scene-object group");
        }
        var first = sources.getFirst();
        if (!first.geometry.getKind().isInstanced()) {
            throw new IllegalArgumentException("terrain objects are not instanced together");
        }
        var totalFloats = 0;
        var id = StableRenderId.builder("instance-group").put(first.geometry.getStableId());
        var world = first.worldBounds;
        var primitiveBounds = first.primitiveBounds.clone();
        for (var source : sources) {
            if (source.isMoving()
                    || !source.owner.equals(first.owner)
                    || source.geometry != first.geometry
                    || source.nativeStaticMeshAmbient != first.nativeStaticMeshAmbient
                    || !source.lightmapScale.equals(first.lightmapScale)
                    || !Objects.equals(source.lightmap, first.lightmap)
                    || source.vertexLightmapLayers != first.vertexLightmapLayers
                    || source.lightmapVariantCount != first.lightmapVariantCount
                    || source.lightmapVariantStride != first.lightmapVariantStride
                    || !source.getLightmapVariantSlots().equals(first.getLightmapVariantSlots())
                    || source.terrainLighting != first.terrainLighting
                    || source.terrainDecorationZone != first.terrainDecorationZone
                    || source.decorationFadeMax != first.decorationFadeMax
                    || source.wireframe != first.wireframe
                    || source.acceptsProjectors != first.acceptsProjectors
                    || !Objects.equals(source.staticMeshLod, first.staticMeshLod)) {
                throw new IllegalArgumentException("instance group contains incompatible objects");
            }
            totalFloats = Math.addExact(totalFloats, source.instanceRecords.length);
            id.put(source.stableId);
            world = union(world, source.worldBounds);
            for (var primitive = 0; primitive < primitiveBounds.length; primitive++) {
                primitiveBounds[primitive] = union(primitiveBounds[primitive], source.primitiveBounds[primitive]);
            }
        }
        var records = new float[totalFloats];
        var offset = 0;
        for (var source : sources) {
            System.arraycopy(source.instanceRecords, 0, records, offset, source.instanceRecords.length);
            offset += source.instanceRecords.length;
        }
        var lightmap = first.lightmap == null ? null : first.lightmap.retain();
        var actorLighting = new ArrayList<BgfxActorLightingBuffer.Slice>();
        try {
            for (var source : sources) {
                for (var slice : source.actorLighting) {
                    actorLighting.add(slice.retain());
                }
            }
            var merged = new BgfxSceneObject(
                    instancePool,
                    actorLighting,
                    id.build(),
                    first.owner,
                    first.geometry,
                    lightmap,
                    first.vertexLightmapLayers,
                    first.lightmapVariantCount,
                    first.lightmapVariantStride,
                    first.beastLightmap,
                    first.getLightmapVariantSlots(),
                    first.wireframe,
                    first.foliage,
                    false,
                    first.acceptsProjectors,
                    first.staticMeshLod,
                    records,
                    packInstances(records),
                    false,
                    first.terrainDecorationZone,
                    null,
                    world,
                    primitiveBounds);
            merged.nativeStaticMeshAmbient = first.nativeStaticMeshAmbient;
            merged.decorationFadeMax = first.decorationFadeMax;
            merged.sourceActors = sources.stream()
                    .flatMap(source -> source.sourceActors.stream())
                    .toList();
            if (sources.stream().anyMatch(source -> source.zoneStates != null)) {
                merged.zoneStates = ZoneStates.merge(sources, merged.instanceCount);
            }
            merged.sourceAsset = first.sourceAsset;
            merged.movementBillboard = first.movementBillboard;
            merged.lightmapScale = first.lightmapScale;
            if (sources.stream().anyMatch(source -> source.nativeLightingInputs.length != 0)) {
                merged.nativeLightingInputs = new NativeStaticMeshLightingInputs[merged.instanceCount];
                var lightingOffset = 0;
                for (var source : sources) {
                    if (source.nativeLightingInputs.length != 0) {
                        System.arraycopy(
                                source.nativeLightingInputs,
                                0,
                                merged.nativeLightingInputs,
                                lightingOffset,
                                source.nativeLightingInputs.length);
                    }
                    lightingOffset += source.instanceCount;
                }
            }
            if (sources.stream().anyMatch(source -> source.nativeLightReceivers.length != 0)) {
                merged.nativeLightReceivers = new NativeLightReceiver[merged.instanceCount];
                var receiverOffset = 0;
                for (var source : sources) {
                    if (source.nativeLightReceivers.length != 0) {
                        System.arraycopy(
                                source.nativeLightReceivers,
                                0,
                                merged.nativeLightReceivers,
                                receiverOffset,
                                source.nativeLightReceivers.length);
                    }
                    receiverOffset += source.instanceCount;
                }
            }
            return merged;
        } catch (RuntimeException | Error failure) {
            for (var slice : actorLighting) {
                closeAfterFailure(slice, failure);
            }
            closeAfterFailure(lightmap, failure);
            throw failure;
        }
    }

    static float beastLightmapLayer(int baseLayer) {
        if (baseLayer < 0) {
            throw new IllegalArgumentException("invalid Beast lightmap base layer");
        }
        return BEAST_LIGHTMAP_LAYER - baseLayer;
    }

    static int beastLightmapBaseLayer(float encodedLayer) {
        if (encodedLayer > BEAST_LIGHTMAP_LAYER + 0.5f) {
            return -1;
        }
        return Math.round(BEAST_LIGHTMAP_LAYER - encodedLayer);
    }

    static float encodedLightmapLayer(
            int baseLayer, boolean vertexLightmapLayers, int variantCount, int variantStride, int shadowVariant) {
        if (baseLayer < 0 || variantCount < 1 || variantStride < 1 || !vertexLightmapLayers && variantStride != 1) {
            throw new IllegalArgumentException("invalid lightmap layer layout");
        }
        var selected = Math.min(Math.max(0, shadowVariant), variantCount - 1);
        if (vertexLightmapLayers) {
            return -(baseLayer + selected * variantStride + 1f);
        }
        return baseLayer + selected;
    }

    static LightmapBinding loadLightmap(BgfxTextureCache textures, Entity source, String mapName, int entityOrdinal) {
        var binding = bindLightmap(textures, source, mapName, entityOrdinal);
        if (binding.texture() != null) {
            textures.registerLightmapLayers(mapName, binding.texture(), lightmapLayers(source), source);
        }
        return binding;
    }

    static float[] packStaticMeshLighting(
            Vec3 light, float marker, byte[] masks, @Nullable Vec3 hardwareSun, boolean flatSun) {
        if (masks != null && masks.length > 0 || !(marker > 0.5f && marker < 2.5f)) {
            return packStaticMeshVisibility(light, marker, masks);
        }
        var result = new float[] {light.getX(), light.getY(), light.getZ(), marker};
        if (hardwareSun != null) {
            var red = Math.clamp(Math.round(hardwareSun.getX() * 255f), 0, 255);
            var green = Math.clamp(Math.round(hardwareSun.getY() * 255f), 0, 255);
            var blue = Math.clamp(Math.round(hardwareSun.getZ() * 255f), 0, 255);
            result[0] = packLightComponent(light.getX(), red & 15, red >> 4, 0);
            result[1] = packLightComponent(light.getY(), green & 15, green >> 4, 0);
            result[2] = packLightComponent(light.getZ(), blue & 15, blue >> 4, HARDWARE_SUN_SIGNATURE);
        } else if (flatSun) {
            result[2] = packLightComponent(light.getZ(), 0, 0, FLAT_SUN_SIGNATURE);
        }
        return result;
    }

    static float[] packStaticMeshVisibility(Vec3 light, float marker, byte[] masks) {
        var result = new float[] {light.getX(), light.getY(), light.getZ(), marker};
        if (masks == null || masks.length == 0 || !(marker > 0.5f && marker < 2.5f)) {
            return result;
        }
        var levels = new int[8];
        for (var slot = 0; slot < levels.length; slot++) {
            var visible = 0;
            for (var mask : masks) {
                visible += (Byte.toUnsignedInt(mask) >>> slot) & 1;
            }
            levels[slot] = Math.round(visible * (float) VISIBILITY_LEVEL_MAX / masks.length);
        }
        result[0] = packLightComponent(light.getX(), levels[0], levels[1], levels[2]);
        result[1] = packLightComponent(light.getY(), levels[3], levels[4], levels[5]);
        result[2] = packLightComponent(light.getZ(), levels[6], levels[7], VISIBILITY_SIGNATURE);
        return result;
    }

    static Bounds3f transformBillboard(Bounds3f local, float[] matrix) {
        return transformBillboard(local, matrix, 0);
    }

    static Bounds3f transformBillboard(Bounds3f local, float[] matrix, int offset) {
        var x = Math.max(Math.abs(local.minX()), Math.abs(local.maxX())) * columnLength(matrix, offset);
        var y = Math.max(Math.abs(local.minY()), Math.abs(local.maxY())) * columnLength(matrix, offset + 4);
        var scaleZ = columnLength(matrix, offset + 8);
        if (BgfxBillboardTilt.isTilted(matrix, offset)) {
            var z = Math.max(Math.abs(local.minZ()), Math.abs(local.maxZ())) * scaleZ;
            var ball = (float) Math.sqrt(x * x + y * y + z * z);
            return new Bounds3f(
                    matrix[offset + 12] - ball,
                    matrix[offset + 13] - ball,
                    matrix[offset + 14] - ball,
                    matrix[offset + 12] + ball,
                    matrix[offset + 13] + ball,
                    matrix[offset + 14] + ball);
        }
        var radius = (float) Math.sqrt(x * x + y * y);
        var z0 = matrix[offset + 14] + local.minZ() * scaleZ;
        var z1 = matrix[offset + 14] + local.maxZ() * scaleZ;
        return new Bounds3f(
                matrix[offset + 12] - radius,
                matrix[offset + 13] - radius,
                Math.min(z0, z1),
                matrix[offset + 12] + radius,
                matrix[offset + 13] + radius,
                Math.max(z0, z1));
    }

    static Bounds3f transform(Bounds3f local, float[] matrix) {
        return transform(local, matrix, 0);
    }

    static Bounds3f transform(Bounds3f local, float[] matrix, int offset) {
        var centerX = local.centerX();
        var centerY = local.centerY();
        var centerZ = local.centerZ();
        var extentX = (local.maxX() - local.minX()) * 0.5f;
        var extentY = (local.maxY() - local.minY()) * 0.5f;
        var extentZ = (local.maxZ() - local.minZ()) * 0.5f;
        var worldX = matrix[offset] * centerX
                + matrix[offset + 4] * centerY
                + matrix[offset + 8] * centerZ
                + matrix[offset + 12];
        var worldY = matrix[offset + 1] * centerX
                + matrix[offset + 5] * centerY
                + matrix[offset + 9] * centerZ
                + matrix[offset + 13];
        var worldZ = matrix[offset + 2] * centerX
                + matrix[offset + 6] * centerY
                + matrix[offset + 10] * centerZ
                + matrix[offset + 14];
        var worldExtentX = Math.abs(matrix[offset]) * extentX
                + Math.abs(matrix[offset + 4]) * extentY
                + Math.abs(matrix[offset + 8]) * extentZ;
        var worldExtentY = Math.abs(matrix[offset + 1]) * extentX
                + Math.abs(matrix[offset + 5]) * extentY
                + Math.abs(matrix[offset + 9]) * extentZ;
        var worldExtentZ = Math.abs(matrix[offset + 2]) * extentX
                + Math.abs(matrix[offset + 6]) * extentY
                + Math.abs(matrix[offset + 10]) * extentZ;
        return new Bounds3f(
                worldX - worldExtentX,
                worldY - worldExtentY,
                worldZ - worldExtentZ,
                worldX + worldExtentX,
                worldY + worldExtentY,
                worldZ + worldExtentZ);
    }

    static Bounds3f union(Bounds3f first, Bounds3f second) {
        return new Bounds3f(
                Math.min(first.minX(), second.minX()),
                Math.min(first.minY(), second.minY()),
                Math.min(first.minZ(), second.minZ()),
                Math.max(first.maxX(), second.maxX()),
                Math.max(first.maxY(), second.maxY()),
                Math.max(first.maxZ(), second.maxZ()));
    }

    List<DebugMaterialSlot> getDiagnosticMaterials() {
        if (diagnosticMaterials == null) {
            diagnosticMaterials = geometry.getPrimitives().stream()
                    .filter(primitive -> primitive.entityMaterial() != null)
                    .map(primitive -> primitive.entityMaterial().getDiagnosticSlot(primitive.sourceSurfaceOrdinal()))
                    .toList();
        }
        return diagnosticMaterials;
    }

    boolean hasNativeStaticMeshAmbient() {
        return nativeStaticMeshAmbient;
    }

    NativeStaticMeshDraw getNativeStaticMeshDraw() {
        return nativeStaticMeshDraw;
    }

    Binding getNativeVertexLightmap() {
        return nativeVertexLightmap;
    }

    void setNativeVertexLightmap(Binding binding) {
        nativeVertexLightmap = binding;
    }

    float getNativeActorX() {
        if (editorPreview != null) {
            return instanceRecords[0] * nativeStaticMeshDraw.pivotX()
                    + instanceRecords[4] * nativeStaticMeshDraw.pivotY()
                    + instanceRecords[8] * nativeStaticMeshDraw.pivotZ()
                    + instanceRecords[12];
        }
        return movement == null ? nativeStaticMeshDraw.actorX() : movement.getLocationX();
    }

    float getNativeActorY() {
        if (editorPreview != null) {
            return instanceRecords[1] * nativeStaticMeshDraw.pivotX()
                    + instanceRecords[5] * nativeStaticMeshDraw.pivotY()
                    + instanceRecords[9] * nativeStaticMeshDraw.pivotZ()
                    + instanceRecords[13];
        }
        return movement == null ? nativeStaticMeshDraw.actorY() : movement.getLocationY();
    }

    NativeLightmapScale getLightmapScale() {
        return lightmapScale;
    }

    long getStableId() {
        return stableId;
    }

    String getSourceActor(int instance) {
        var index = Math.max(0, instance);
        return index < sourceActors.size() ? sourceActors.get(index) : "Unavailable";
    }

    List<String> getSourceActors() {
        return sourceActors;
    }

    BgfxSceneObject copyForActors(BgfxInstancePool pool, Set<String> actors) {
        if (!canPreviewTransform()) {
            throw new IllegalArgumentException("This object cannot be split for editing");
        }
        var indices = new ArrayList<Integer>();
        for (var i = 0; i < instanceCount; i++) {
            if (actors.contains(getSourceActor(i))) {
                indices.add(i);
            }
        }
        return copyInstances(pool, indices);
    }

    BgfxSceneObject copyInstances(BgfxInstancePool pool, List<Integer> indices) {
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("An editor subset cannot be empty");
        }
        var identities = new ArrayList<String>(indices.size());
        var id = StableRenderId.builder("editor-instance-subset").put(stableId);
        for (var index : indices) {
            identities.add(getSourceActor(index));
            id.put(index);
        }
        var original = editorPreview == null ? instanceRecords : editorPreview.originalRecords;
        var records = new float[indices.size() * INSTANCE_FLOATS];
        var models = new ArrayList<float[]>(indices.size());
        for (var i = 0; i < indices.size(); i++) {
            System.arraycopy(original, indices.get(i) * INSTANCE_FLOATS, records, i * INSTANCE_FLOATS, INSTANCE_FLOATS);
            models.add(Arrays.copyOfRange(records, i * INSTANCE_FLOATS, i * INSTANCE_FLOATS + 16));
        }
        var bounds = new Bounds3f[primitiveBounds.length];
        for (var i = 0; i < bounds.length; i++) {
            bounds[i] = transformUnion(geometry.getPrimitives().get(i).localBounds(), models, movementBillboard);
        }
        var retainedLightmap = lightmap == null ? null : lightmap.retain();
        var retainedLighting = new ArrayList<BgfxActorLightingBuffer.Slice>();
        try {
            for (var slice : actorLighting) {
                retainedLighting.add(slice.retain());
            }
            var copy = new BgfxSceneObject(
                    pool,
                    retainedLighting,
                    id.build(),
                    owner,
                    geometry,
                    retainedLightmap,
                    vertexLightmapLayers,
                    lightmapVariantCount,
                    lightmapVariantStride,
                    beastLightmap,
                    getLightmapVariantSlots(),
                    wireframe,
                    foliage,
                    false,
                    acceptsProjectors,
                    staticMeshLod,
                    records,
                    packInstances(records),
                    false,
                    terrainDecorationZone,
                    null,
                    transformUnion(geometry.getLocalBounds(), models, movementBillboard),
                    bounds);
            copy.sourceActors = List.copyOf(identities);
            copy.sourceAsset = sourceAsset;
            copy.nativeStaticMeshAmbient = nativeStaticMeshAmbient;
            copy.nativeStaticMeshDraw = nativeStaticMeshDraw;
            copy.nativeVertexLightmap = nativeVertexLightmap;
            copy.lightmapScale = lightmapScale;
            copy.movementBillboard = movementBillboard;
            if (zoneStates != null) {
                copy.zoneStates = zoneStates.subset(indices, editorPreview == null);
                if (editorPreview != null && zoneStates.choice != null) {
                    copy.applyZoneState(zoneStates.choice, zoneStates.fades, pool);
                }
            }
            if (nativeLightingInputs.length != 0) {
                copy.nativeLightingInputs = new NativeStaticMeshLightingInputs[indices.size()];
                for (var i = 0; i < indices.size(); i++) {
                    copy.nativeLightingInputs[i] = nativeLightingInputs[indices.get(i)];
                }
            }
            if (nativeLightReceivers.length != 0) {
                copy.nativeLightReceivers = new NativeLightReceiver[indices.size()];
                for (var i = 0; i < indices.size(); i++) {
                    copy.nativeLightReceivers[i] = nativeLightReceivers[indices.get(i)];
                }
            }
            return copy;
        } catch (RuntimeException | Error failure) {
            for (var slice : retainedLighting) {
                closeAfterFailure(slice, failure);
            }
            closeAfterFailure(retainedLightmap, failure);
            throw failure;
        }
    }

    String getSourceAsset() {
        return sourceAsset == null ? geometry.getLabel() : sourceAsset;
    }

    List<SourcePart> getSourceParts() {
        return sourceParts;
    }

    Bounds3f getInstanceBounds(int instance) {
        var model = Arrays.copyOfRange(instanceRecords, instance * INSTANCE_FLOATS, instance * INSTANCE_FLOATS + 16);
        return transformUnion(geometry.getLocalBounds(), List.of(model), movementBillboard);
    }

    boolean hasActorAnimation() {
        return movement != null;
    }

    @Nullable
    Bounds3f getSavedBounds() {
        var motion = movement;
        if (motion == null || movementLocalInstances == null) {
            return null;
        }
        var saved = motion.writeSavedMatrix(new Mat4());
        var models = new ArrayList<float[]>(instanceCount);
        for (var instance = 0; instance < instanceCount; instance++) {
            var model = saved.times(movementLocalInstances.get(instance)).to(new float[16]);
            writeLanes(model, instance, null);
            models.add(model);
        }
        return transformUnion(geometry.getLocalBounds(), models, movementBillboard);
    }

    String getAnimationDescription() {
        return movement == null ? "No actor movement" : "Authored actor movement";
    }

    String getOwner() {
        return owner;
    }

    String getTerrainProxyDetailOwner() {
        return terrainProxyDetailOwner;
    }

    @Nullable
    String getTerrainPair() {
        return terrainPair;
    }

    BgfxSceneGeometry getGeometry() {
        return geometry;
    }

    TextureRef getLightmap() {
        return lightmap;
    }

    float getLightmapLayer(int shadowVariant) {
        return lightmapBinding.layerAt(shadowVariant);
    }

    TextureRef getLightmap(int shadowVariant) {
        return lightmapBinding.textureAt(shadowVariant);
    }

    List<Integer> getLightmapVariantSlots() {
        return lightmapBinding.variantSlots();
    }

    boolean usesVertexLightmapLayers() {
        return vertexLightmapLayers;
    }

    int getLightmapVariantCount() {
        return lightmapVariantCount;
    }

    int getLightmapVariantStride() {
        return lightmapVariantStride;
    }

    boolean isWireframe() {
        return wireframe;
    }

    boolean isFoliage() {
        return foliage;
    }

    boolean isMergedRegion() {
        return mergedRegion;
    }

    boolean acceptsProjectors() {
        return acceptsProjectors;
    }

    StaticMeshLod getStaticMeshLod() {
        return staticMeshLod;
    }

    float[] getInstanceRecords() {
        return instanceRecords;
    }

    float[] getPlacementRecords(int instance) {
        var zones = zoneStates;
        return zones != null
                        && instance >= 0
                        && instance < instanceCount
                        && zones.hidden[instance]
                        && zones.looks[instance] != null
                ? zones.saved
                : instanceRecords;
    }

    int getInstanceCount() {
        return instanceCount;
    }

    @Nullable
    NativeLightReceiver getNativeLightReceiver(int instance) {
        if (instance < 0 || instance >= instanceCount) {
            throw new IndexOutOfBoundsException(instance);
        }
        return nativeLightReceivers.length == 0 ? null : nativeLightReceivers[instance];
    }

    @Nullable
    NativeStaticMeshLightingInputs getNativeLightingInputs(int instance) {
        if (instance < 0 || instance >= instanceCount) {
            throw new IndexOutOfBoundsException(instance);
        }
        return nativeLightingInputs.length == 0 ? null : nativeLightingInputs[instance];
    }

    boolean hasBeastLightmap() {
        return beastLightmap;
    }

    boolean hasActorLighting() {
        return !actorLighting.isEmpty();
    }

    boolean hasTerrainLighting() {
        return terrainLighting;
    }

    int getTerrainDecorationZone() {
        return terrainDecorationZone;
    }

    float getDecorationFadeMax() {
        return decorationFadeMax;
    }

    float getDecorationFadeMax(int instance) {
        return decorationSlots == null ? decorationFadeMax : decorationSlots.fades()[instance];
    }

    boolean isDecorationPool() {
        return decorationSlots != null;
    }

    boolean isEmptySlot(int instance) {
        return decorationSlots != null && !decorationSlots.occupied().get(instance);
    }

    void writeDecorationSlots(int first, float[] records, int count, String actor, float fadeMax) {
        var slots = requireDecorationSlots(first, count);
        System.arraycopy(records, 0, instanceRecords, first * INSTANCE_FLOATS, count * INSTANCE_FLOATS);
        slots.occupied().set(first, first + count);
        Arrays.fill(slots.actors(), first, first + count, actor);
        Arrays.fill(slots.fades(), first, first + count, fadeMax);
        uploadDecorationSlots(first, count);
    }

    void setDecorationPoolBounds(@Nullable Bounds3f bounds) {
        if (decorationSlots == null || bounds == null) {
            return;
        }
        var grown = union(worldBounds, bounds);
        worldBounds = grown;
        Arrays.fill(primitiveBounds, grown);
    }

    void clearDecorationSlots(int first, int count) {
        var slots = requireDecorationSlots(first, count);
        Arrays.fill(instanceRecords, first * INSTANCE_FLOATS, (first + count) * INSTANCE_FLOATS, 0f);
        slots.occupied().clear(first, first + count);
        Arrays.fill(slots.actors(), first, first + count, null);
        Arrays.fill(slots.fades(), first, first + count, 0f);
        uploadDecorationSlots(first, count);
    }

    boolean isMoving() {
        return movement != null || editorPreview != null;
    }

    boolean containsSourceActor(String actor) {
        return sourceActors.contains(actor)
                || sourceParts.stream().anyMatch(part -> part.actor().equals(actor));
    }

    boolean canPreviewTransform() {
        return decorationSlots == null
                && !mergedRegion
                && sourceParts.isEmpty()
                && geometry.getKind().isInstanced()
                && (wireframe
                        || geometry.getPrimitives().stream()
                                .anyMatch(primitive -> primitive.entityMaterial() != null
                                        && (primitive.entityMaterial().getSurfaceType()
                                                        & SurfaceType.STATIC_MESH.getId())
                                                != 0));
    }

    boolean hasEditorPreview() {
        return editorPreview != null;
    }

    boolean previewTransforms(Map<String, float[]> deltas, BgfxInstancePool instancePool) {
        var affected = sourceActors.stream().anyMatch(deltas::containsKey);
        if (!affected && editorPreview == null) {
            return false;
        }
        showZoneHidden();
        if (editorPreview == null) {
            editorPreview = new EditorPreview(
                    instanceRecords.clone(),
                    worldBounds,
                    primitiveBounds.clone(),
                    BufferUtils.createByteBuffer(instanceRecords.length * Float.BYTES));
        }
        var preview = editorPreview;
        BgfxEditorTransforms.apply(preview.originalRecords, instanceRecords, INSTANCE_FLOATS, sourceActors, deltas);
        if (affected) {
            var models = new ArrayList<float[]>(instanceCount);
            for (var instance = 0; instance < instanceCount; instance++) {
                models.add(Arrays.copyOfRange(
                        instanceRecords, instance * INSTANCE_FLOATS, instance * INSTANCE_FLOATS + 16));
            }
            worldBounds = transformUnion(geometry.getLocalBounds(), models, movementBillboard);
            for (var primitive = 0; primitive < primitiveBounds.length; primitive++) {
                primitiveBounds[primitive] = transformUnion(
                        geometry.getPrimitives().get(primitive).localBounds(), models, movementBillboard);
            }
        } else {
            worldBounds = preview.originalWorldBounds;
            System.arraycopy(preview.originalPrimitiveBounds, 0, primitiveBounds, 0, primitiveBounds.length);
        }
        hideZoneHidden();
        preview.data.asFloatBuffer().put(instanceRecords);
        publishDeferredInstances(instancePool);
        instanceStorage.update(preview.data);
        if (!affected) {
            editorPreview = null;
        }
        return true;
    }

    private record EditorPreview(
            float[] originalRecords,
            Bounds3f originalWorldBounds,
            Bounds3f[] originalPrimitiveBounds,
            ByteBuffer data) {}

    void bindMotion(Entity source, ActorMotion motion) {
        if (movement != null) {
            throw new IllegalStateException("scene object already moves");
        }
        movement = motion;
        movementLocalInstances = List.copyOf(source.getInstanceMatrices());
        movementBillboard = ((EntityMesh) source.getMesh()).billboard();
        movementData = BufferUtils.createByteBuffer(instanceRecords.length * Float.BYTES);
        movementFloats = movementData.asFloatBuffer();
        movementModelScratch = new Mat4();
        movementBase = new Mat4();
        movementModels = new ArrayList<>(instanceCount);
        for (var instance = 0; instance < instanceCount; instance++) {
            movementModels.add(new float[16]);
        }
    }

    void replaceMotion(ActorMotion motion) {
        if (movement == null) {
            throw new IllegalStateException("scene object does not move");
        }
        movement = Objects.requireNonNull(motion, "motion");
    }

    @Nullable
    ActorMotion getMotion() {
        return movement;
    }

    boolean refreshMovement(BgfxInstancePool instancePool) {
        return movement != null && writeMovement(0f, instancePool);
    }

    boolean advanceMovement(float seconds, BgfxInstancePool instancePool) {
        if (movement == null || !(seconds > 0f)) {
            return false;
        }
        return writeMovement(seconds, instancePool);
    }

    boolean writeMovement(float seconds, BgfxInstancePool instancePool) {
        var base = movement.advance(seconds, movementBase);
        showZoneHidden();
        for (var instance = 0; instance < instanceCount; instance++) {
            var model = movementModels.get(instance);
            base.times(movementLocalInstances.get(instance), movementModelScratch)
                    .to(model);
            var offset = instance * INSTANCE_FLOATS;
            writeLanes(model, instance, movement.getRotation());
            System.arraycopy(model, 0, instanceRecords, offset, 16);
        }
        worldBounds = transformUnion(geometry.getLocalBounds(), movementModels, movementBillboard);
        for (var primitive = 0; primitive < primitiveBounds.length; primitive++) {
            primitiveBounds[primitive] = transformUnion(
                    geometry.getPrimitives().get(primitive).localBounds(), movementModels, movementBillboard);
        }
        hideZoneHidden();
        movementFloats.put(0, instanceRecords);
        publishDeferredInstances(instancePool);
        instanceStorage.update(movementData);
        return true;
    }

    boolean isZoneLimited() {
        return zoneStates != null;
    }

    boolean applyZoneState(ZoneStateChoice choice, @Nullable ZoneFades fades, BgfxInstancePool instancePool) {
        var zones = zoneStates;
        if (zones == null) {
            return false;
        }
        var fadesRevision = fades == null || !zones.blended ? 0L : fades.getRevision();
        if (choice.equals(zones.choice) && zones.fades == fades && zones.fadesRevision == fadesRevision) {
            return false;
        }
        zones.choice = choice;
        zones.fades = fades;
        zones.fadesRevision = fadesRevision;
        var changed = false;
        var fading = 0;
        for (var instance = 0; instance < instanceCount; instance++) {
            var render = zones.renders[instance];
            var look = zones.looks[instance];
            var fade = zones.blended ? zones.findFade(instance, fades) : null;
            boolean hide;
            var opacity = 1f;
            if (fade == null) {
                zones.actorHidden[instance] = look != null && !look.actor().shows(choice);
                hide = !render.shows(choice);
            } else if (look == null) {
                zones.actorHidden[instance] = false;
                hide = !fade.isDrawn(render.mask());
                opacity = hide ? 1f : fade.getMeshOpacity(render.mask());
            } else {
                var actor = look.actor();
                var skinState = fade.getSkinState(look.looks());
                var worn = skinState != 0
                        ? (render.mask() & 1 << (skinState - 1)) != 0
                        : (render.mask() & look.looks()) == 0;
                zones.actorHidden[instance] = !fade.isDrawn(actor.mask());
                hide = zones.actorHidden[instance] || !worn;
                opacity = hide ? 1f : fade.getMeshOpacity(actor.mask());
            }
            zones.opacities[instance] = opacity;
            if (!hide && opacity < 1f) {
                fading++;
            }
            if (hide == zones.hidden[instance]) {
                continue;
            }
            var offset = instance * INSTANCE_FLOATS;
            if (hide) {
                System.arraycopy(instanceRecords, offset, zones.saved, offset, INSTANCE_FLOATS);
                Arrays.fill(instanceRecords, offset, offset + INSTANCE_FLOATS, 0f);
            } else {
                System.arraycopy(zones.saved, offset, instanceRecords, offset, INSTANCE_FLOATS);
            }
            zones.hidden[instance] = hide;
            changed = true;
        }
        zones.fading = fading;
        if (changed) {
            publishDeferredInstances(instancePool);
            instanceStorage.update(packInstances(instanceRecords));
        }
        return changed;
    }

    boolean hasZoneFades() {
        return zoneStates != null && zoneStates.blended;
    }

    boolean hasFadingInstances() {
        return zoneStates != null && zoneStates.fading > 0;
    }

    boolean isFading(int instance) {
        var zones = zoneStates;
        return zones != null && zones.fading > 0 && !zones.hidden[instance] && zones.opacities[instance] < 1f;
    }

    float getFadeOpacity(int instance) {
        var zones = zoneStates;
        return zones == null ? 1f : zones.opacities[instance];
    }

    boolean isZoneHidden(int instance) {
        return zoneStates != null && zoneStates.hidden[instance];
    }

    boolean isHiddenLook(int instance) {
        var zones = zoneStates;
        var look = zones == null ? null : zones.looks[instance];
        if (look == null) {
            return false;
        }
        if (zones.choice == null) {
            return look.copy();
        }
        return zones.hidden[instance] && (look.copy() || !zones.actorHidden[instance]);
    }

    void bindInstances() {
        bindInstances(0, instanceCount);
    }

    void bindInstances(int first, int count) {
        if (instanceStorage == null) {
            throw new IllegalStateException("scene object instance data has not been published");
        }
        instanceStorage.bind(first, count);
    }

    void publishDeferredInstances(BgfxInstancePool instancePool) {
        if (!geometry.getKind().isInstanced() || instanceStorage != null) {
            return;
        }
        if (deferredInstanceData == null) {
            throw new IllegalStateException("scene object has no deferred instance payload");
        }
        var published = instancePool.allocate(
                "instances/" + geometry.getLabel() + '-' + Long.toUnsignedString(stableId, 16), deferredInstanceData);
        instanceStorage = published;
        deferredInstanceData = null;
    }

    long getEstimatedGpuBytes() {
        return instanceStorage == null ? 0L : instanceStorage.getEstimatedBytes();
    }

    long getRetainedCpuBytes() {
        long bytes = (long) instanceRecords.length * Float.BYTES;
        if (terrainModel != null) {
            bytes = Math.addExact(bytes, (long) terrainModel.length * Float.BYTES);
        }
        bytes = Math.addExact(bytes, (long) (primitiveBounds.length + 1) * 6L * Float.BYTES);
        return bytes;
    }

    float[] getTerrainModel() {
        return terrainModel;
    }

    Bounds3f getWorldBounds() {
        return worldBounds;
    }

    Bounds3f getPrimitiveBounds(int primitive) {
        return primitiveBounds[primitive];
    }

    double getClearanceSquared(float x, float y, float z, double best) {
        if (wireframe) {
            return best;
        }
        var boxes = movementBillboard ? null : geometry.getClearanceBoxes();
        if (!geometry.getKind().isInstanced()) {
            var model = terrainModel;
            if (boxes != null && model != null && model.length >= 16) {
                return square(ClearanceBoxes.nearest(boxes, model, 0, x, y, z, Math.sqrt(best)));
            }
            boxes = null;
        }
        if (boxes == null && (instanceCount <= 1 || !geometry.getKind().isInstanced())) {
            for (var bounds : primitiveBounds) {
                best = Math.min(best, distanceSquared(bounds, x, y, z));
            }
            return best;
        }
        var records = instanceRecords;
        if (boxes != null && instanceCount <= 1) {
            if (records.length < 16 || !(largestScale() > 0)) {
                return best;
            }
            return square(ClearanceBoxes.nearest(boxes, records, 0, x, y, z, Math.sqrt(best)));
        }
        var local = geometry.getLocalBounds();
        double centerX;
        double centerY;
        double centerZ;
        double radius;
        if (movementBillboard) {
            centerX = 0;
            centerY = 0;
            centerZ = 0;
            radius = Math.sqrt(square(Math.max(Math.abs(local.minX()), Math.abs(local.maxX())))
                    + square(Math.max(Math.abs(local.minY()), Math.abs(local.maxY())))
                    + square(Math.max(Math.abs(local.minZ()), Math.abs(local.maxZ()))));
        } else {
            centerX = (local.minX() + (double) local.maxX()) * .5;
            centerY = (local.minY() + (double) local.maxY()) * .5;
            centerZ = (local.minZ() + (double) local.maxZ()) * .5;
            radius = Math.sqrt(
                    square(local.maxX() - centerX) + square(local.maxY() - centerY) + square(local.maxZ() - centerZ));
        }
        var bestRoot = Math.sqrt(best);
        var reach = (Math.sqrt(centerX * centerX + centerY * centerY + centerZ * centerZ) + radius) * largestScale();
        for (var offset = 0; offset + 16 <= records.length; offset += INSTANCE_FLOATS) {
            var placeX = records[offset + 12] - x;
            var placeY = records[offset + 13] - y;
            var placeZ = records[offset + 14] - z;
            if (placeX * placeX + placeY * placeY + placeZ * placeZ >= square(bestRoot + reach)) {
                continue;
            }
            var scale = Math.max(
                    square(records[offset]) + square(records[offset + 1]) + square(records[offset + 2]),
                    Math.max(
                            square(records[offset + 4]) + square(records[offset + 5]) + square(records[offset + 6]),
                            square(records[offset + 8]) + square(records[offset + 9]) + square(records[offset + 10])));
            if (!(scale > 0)) {
                continue;
            }
            var dx = records[offset] * centerX
                    + records[offset + 4] * centerY
                    + records[offset + 8] * centerZ
                    + records[offset + 12]
                    - x;
            var dy = records[offset + 1] * centerX
                    + records[offset + 5] * centerY
                    + records[offset + 9] * centerZ
                    + records[offset + 13]
                    - y;
            var dz = records[offset + 2] * centerX
                    + records[offset + 6] * centerY
                    + records[offset + 10] * centerZ
                    + records[offset + 14]
                    - z;
            var ball = Math.sqrt(dx * dx + dy * dy + dz * dz) - radius * Math.sqrt(scale);
            if (ball >= bestRoot) {
                continue;
            }
            bestRoot = boxes != null ? ClearanceBoxes.nearest(boxes, records, offset, x, y, z, bestRoot) : ball;
            if (!(bestRoot > 0)) {
                return 0;
            }
        }
        return bestRoot * bestRoot;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        deferredInstanceData = null;
        RuntimeException failure = null;
        try {
            if (instanceStorage != null) {
                instanceStorage.close();
                instanceStorage = null;
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            if (lightmap != null) {
                lightmap.close();
            }
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        for (var index = actorLighting.size() - 1; index >= 0; index--) {
            try {
                actorLighting.get(index).close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void writeLanes(float[] model, int instance, @Nullable URotator rotation) {
        var offset = instance * INSTANCE_FLOATS;
        for (var lane = 3; lane < 16; lane += 4) {
            model[lane] = instanceRecords[offset + lane];
        }
        if (BgfxBillboardTilt.carriesTilt(model, 0)) {
            if (rotation != null && isIdentity(movementLocalInstances.get(instance))) {
                BgfxBillboardTilt.write(model, 0, rotation);
            } else {
                BgfxBillboardTilt.writeFromMatrix(model, 0);
            }
        }
    }

    private void showZoneHidden() {
        var zones = zoneStates;
        if (zones == null) {
            return;
        }
        for (var instance = 0; instance < instanceCount; instance++) {
            if (zones.hidden[instance]) {
                var offset = instance * INSTANCE_FLOATS;
                System.arraycopy(zones.saved, offset, instanceRecords, offset, INSTANCE_FLOATS);
            }
        }
    }

    private double largestScale() {
        if (clearanceScale >= 0 && movement == null && editorPreview == null && zoneStates == null) {
            return clearanceScale;
        }
        var records = instanceRecords;
        var largest = 0.0;
        for (var offset = 0; offset + 16 <= records.length; offset += INSTANCE_FLOATS) {
            largest = Math.max(
                    largest,
                    Math.max(
                            square(records[offset]) + square(records[offset + 1]) + square(records[offset + 2]),
                            Math.max(
                                    square(records[offset + 4])
                                            + square(records[offset + 5])
                                            + square(records[offset + 6]),
                                    square(records[offset + 8])
                                            + square(records[offset + 9])
                                            + square(records[offset + 10]))));
        }
        clearanceScale = Math.sqrt(largest);
        return clearanceScale;
    }

    private void hideZoneHidden() {
        var zones = zoneStates;
        if (zones == null) {
            return;
        }
        for (var instance = 0; instance < instanceCount; instance++) {
            if (zones.hidden[instance]) {
                var offset = instance * INSTANCE_FLOATS;
                System.arraycopy(instanceRecords, offset, zones.saved, offset, INSTANCE_FLOATS);
                Arrays.fill(instanceRecords, offset, offset + INSTANCE_FLOATS, 0f);
            }
        }
    }

    private static final class ZoneStates {
        final ZoneRender[] renders;
        final ZoneLook[] looks;
        final boolean[] actorHidden;
        final boolean[] hidden;
        final float[] saved;
        final float[] opacities;
        final ZoneFade[] zoneFades;
        boolean blended;
        int fading;
        @Nullable
        ZoneStateChoice choice;

        @Nullable
        ZoneFades fades;

        long fadesRevision;

        private ZoneStates(int instances) {
            renders = new ZoneRender[instances];
            Arrays.fill(renders, ZoneRender.EVERY_STATE);
            looks = new ZoneLook[instances];
            actorHidden = new boolean[instances];
            hidden = new boolean[instances];
            saved = new float[Math.multiplyExact(instances, INSTANCE_FLOATS)];
            opacities = new float[instances];
            Arrays.fill(opacities, 1f);
            zoneFades = new ZoneFade[instances];
        }

        static ZoneStates uniform(int instances, ZoneRender render, @Nullable ZoneLook look) {
            var zones = new ZoneStates(instances);
            Arrays.fill(zones.renders, render);
            Arrays.fill(zones.looks, look);
            zones.blended = render.blend() != null;
            return zones;
        }

        static ZoneStates merge(List<BgfxSceneObject> sources, int instances) {
            var zones = new ZoneStates(instances);
            var first = 0;
            for (var source : sources) {
                var own = source.zoneStates;
                if (own != null) {
                    System.arraycopy(own.renders, 0, zones.renders, first, source.instanceCount);
                    System.arraycopy(own.looks, 0, zones.looks, first, source.instanceCount);
                    System.arraycopy(own.actorHidden, 0, zones.actorHidden, first, source.instanceCount);
                    System.arraycopy(own.hidden, 0, zones.hidden, first, source.instanceCount);
                    System.arraycopy(own.saved, 0, zones.saved, first * INSTANCE_FLOATS, own.saved.length);
                    zones.blended |= own.blended;
                }
                first += source.instanceCount;
            }
            return zones;
        }

        ZoneStates subset(List<Integer> indices, boolean hiddenToo) {
            var zones = new ZoneStates(indices.size());
            for (var i = 0; i < indices.size(); i++) {
                var index = indices.get(i);
                zones.renders[i] = renders[index];
                zones.looks[i] = looks[index];
                zones.blended |= renders[index].blend() != null;
                if (hiddenToo) {
                    zones.actorHidden[i] = actorHidden[index];
                    zones.hidden[i] = hidden[index];
                    System.arraycopy(saved, index * INSTANCE_FLOATS, zones.saved, i * INSTANCE_FLOATS, INSTANCE_FLOATS);
                }
            }
            zones.choice = hiddenToo ? choice : null;
            zones.fades = fades;
            zones.fadesRevision = Long.MIN_VALUE;
            return zones;
        }

        @Nullable
        ZoneFade findFade(int instance, @Nullable ZoneFades source) {
            if (source == null || renders[instance].blend() == null) {
                return null;
            }
            var fade = zoneFades[instance];
            if (fade == null) {
                fade = source.find(renders[instance]);
                zoneFades[instance] = fade;
            }
            return fade;
        }
    }

    private DecorationSlots requireDecorationSlots(int first, int count) {
        if (decorationSlots == null) {
            throw new IllegalStateException("not a decoration pool");
        }
        Objects.checkFromIndexSize(first, count, instanceCount);
        return decorationSlots;
    }

    private void uploadDecorationSlots(int first, int count) {
        if (instanceStorage == null || count == 0) {
            return;
        }
        var packed = ByteBuffer.allocateDirect(Math.multiplyExact(count * INSTANCE_FLOATS, Float.BYTES))
                .order(ByteOrder.nativeOrder());
        packed.asFloatBuffer().put(instanceRecords, first * INSTANCE_FLOATS, count * INSTANCE_FLOATS);
        instanceStorage.update(first, packed);
    }

    private static double square(double value) {
        return value * value;
    }

    private static double distanceSquared(Bounds3f bounds, float x, float y, float z) {
        var dx = x < bounds.minX() ? bounds.minX() - x : x > bounds.maxX() ? x - bounds.maxX() : 0f;
        var dy = y < bounds.minY() ? bounds.minY() - y : y > bounds.maxY() ? y - bounds.maxY() : 0f;
        var dz = z < bounds.minZ() ? bounds.minZ() - z : z > bounds.maxZ() ? z - bounds.maxZ() : 0f;
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    private static Prepared prepare(
            Entity source,
            long geometryStableId,
            Kind geometryKind,
            Bounds3f geometryBounds,
            List<Bounds3f> primitiveLocalBounds,
            String mapName,
            int entityOrdinal) {
        if (source == null || mapName == null || mapName.isBlank() || entityOrdinal < 0) {
            throw new IllegalArgumentException("invalid scene object preparation request");
        }
        var stableId = StableRenderId.builder("object")
                .put(mapName)
                .put(entityOrdinal)
                .put(geometryStableId)
                .build();
        var base = source.getModelMatrix();
        if (geometryKind == Kind.TERRAIN) {
            var model = toArray(base);
            var worldBounds = transform(geometryBounds, model);
            var primitiveBounds = transformPrimitives(primitiveLocalBounds, List.of(model));
            return new Prepared(
                    stableId,
                    geometryStableId,
                    mapName,
                    entityOrdinal,
                    false,
                    false,
                    source.isAcceptsProjectors(),
                    new float[0],
                    null,
                    model,
                    worldBounds,
                    primitiveBounds);
        }

        var localInstances = source.getInstanceMatrices();
        var records = new float[Math.multiplyExact(localInstances.size(), INSTANCE_FLOATS)];
        var matrices = new ArrayList<float[]>(localInstances.size());
        var mesh = (EntityMesh) source.getMesh();
        var terrainDecorationSteady = source.getTerrainDecorationSteadyLighting();
        var sourceLighting = mesh.instanceLighting().isEmpty()
                ? packStaticMeshLighting(
                        source.getInstanceLight(),
                        source.getSunLightMarker(),
                        source.getEnvironmentVisibilityMasks(),
                        source.getHardwareSunLight(),
                        source.isFlatSunAmbient())
                : null;
        for (var instance = 0; instance < localInstances.size(); instance++) {
            var model = toArray(base.times(localInstances.get(instance)));
            putStaticMeshMetadata(model, source.getStaticMeshLod(), mesh.billboard());
            if (BgfxBillboardTilt.carriesTilt(model, 0)) {
                if (source.getMovement() != null && isIdentity(localInstances.get(instance))) {
                    BgfxBillboardTilt.write(model, 0, source.getMovement().rotation());
                } else {
                    BgfxBillboardTilt.writeFromMatrix(model, 0);
                }
            }
            if (geometryKind == Kind.STATIC_MESH_TANGENT) {
                model[15] += 4f;
            }
            if (!terrainDecorationSteady.isEmpty()) {
                var steady = terrainDecorationSteady.get(instance);
                model[3] = steady.getX();
                model[7] = steady.getY();
                model[11] = steady.getZ();
                model[15] = 0f;
            }
            matrices.add(model);
            var offset = instance * INSTANCE_FLOATS;
            System.arraycopy(model, 0, records, offset, 16);
            if (!mesh.instanceLighting().isEmpty()) {
                var lighting = mesh.instanceLighting().get(instance);
                putLight(records, offset + 16, lighting);
            } else {
                System.arraycopy(sourceLighting, 0, records, offset + 16, 4);
            }
        }
        var worldBounds = transformUnion(geometryBounds, matrices, mesh.billboard());
        var primitiveBounds = transformPrimitives(primitiveLocalBounds, matrices, mesh.billboard());
        var packedInstances = packInstances(records);
        return new Prepared(
                stableId,
                geometryStableId,
                mapName,
                entityOrdinal,
                mesh.swayFrequency() > 0f || source.isFoliageLighting(),
                source.isMergedRegion(),
                source.isAcceptsProjectors(),
                records,
                packedInstances,
                null,
                worldBounds,
                primitiveBounds);
    }

    private static List<Texture> lightmapLayers(Entity source) {
        var atlases = source.getLightmaps();
        if (atlases != null && !atlases.isEmpty()) {
            var variants = source.getCsgLightmapVariants();
            return variants != null && !variants.isEmpty() ? variants : atlases;
        }
        var variants = source.getLightmapVariants();
        if (variants != null && !variants.isEmpty()) {
            return variants;
        }
        return source.getLightmap() == null ? List.of() : List.of(source.getLightmap());
    }

    private static LightmapBinding bindLightmap(
            BgfxTextureCache textures, Entity source, String mapName, int entityOrdinal) {
        var label = mapName + "/lightmap-" + entityOrdinal;
        var atlases = source.getLightmaps();
        if (atlases != null && !atlases.isEmpty()) {
            var variants = source.getCsgLightmapVariants();
            var variantCount = source.getCsgLightmapVariantCount();
            if (variants != null && !variants.isEmpty()) {
                if (variantCount < 1 || variants.size() != atlases.size() * variantCount) {
                    throw new IllegalArgumentException("invalid CSG lightmap variant layout");
                }
                return new LightmapBinding(textures.loadArray(variants, label), true, variantCount, atlases.size());
            }
            return new LightmapBinding(textures.loadArray(atlases, label), true, 1, atlases.size());
        }
        var variants = source.getLightmapVariants();
        if (source.isBeastLightmap() && variants != null && variants.size() == 2) {
            return new LightmapBinding(textures.loadArray(variants, label), false, 1, 1, true);
        }
        if (variants != null && !variants.isEmpty()) {
            var slots = source.getLightmapVariantSlots();
            return new LightmapBinding(
                    textures.loadArray(variants, label),
                    false,
                    slots.isEmpty() ? variants.size() : slots.size(),
                    1,
                    false,
                    slots);
        }
        return source.getLightmap() == null
                ? LightmapBinding.NONE
                : new LightmapBinding(textures.loadArray(List.of(source.getLightmap()), label), false, 1, 1);
    }

    record Prepared(
            long stableId,
            long geometryStableId,
            String owner,
            int entityOrdinal,
            boolean foliage,
            boolean mergedRegion,
            boolean acceptsProjectors,
            float[] instanceRecords,
            ByteBuffer instanceData,
            float[] terrainModel,
            Bounds3f worldBounds,
            Bounds3f[] primitiveBounds) {
        Prepared {
            if (stableId == 0L
                    || geometryStableId == 0L
                    || owner == null
                    || owner.isBlank()
                    || entityOrdinal < 0
                    || instanceRecords == null
                    || worldBounds == null
                    || primitiveBounds == null
                    || primitiveBounds.length == 0
                    || instanceData == null && terrainModel == null
                    || instanceData != null && terrainModel != null) {
                throw new IllegalArgumentException("invalid prepared scene object payload");
            }
            if (instanceData != null) {
                instanceData = instanceData.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
                instanceData.position(0);
            }
        }

        public ByteBuffer instanceData() {
            if (instanceData == null) {
                return null;
            }
            var result = instanceData.duplicate().order(ByteOrder.nativeOrder());
            result.position(0);
            return result;
        }

        public Bounds3f[] primitiveBounds() {
            return primitiveBounds.clone();
        }

        long retainedBytes() {
            var bytes = (long) instanceRecords.length * Float.BYTES;
            if (instanceData != null) {
                bytes = Math.addExact(bytes, instanceData.remaining());
            }
            if (terrainModel != null) {
                bytes = Math.addExact(bytes, (long) terrainModel.length * Float.BYTES);
            }
            return Math.addExact(bytes, (long) (primitiveBounds.length + 1) * 6L * Float.BYTES);
        }
    }

    record LightmapBinding(
            TextureRef texture,
            boolean vertexLayers,
            int variantCount,
            int variantStride,
            boolean beast,
            List<Integer> variantSlots) {
        private static final LightmapBinding NONE = new LightmapBinding(null, false, 1, 1);

        private LightmapBinding(TextureRef texture, boolean vertexLayers, int variantCount, int variantStride) {
            this(texture, vertexLayers, variantCount, variantStride, false, List.of());
        }

        private LightmapBinding(
                TextureRef texture, boolean vertexLayers, int variantCount, int variantStride, boolean beast) {
            this(texture, vertexLayers, variantCount, variantStride, beast, List.of());
        }

        LightmapBinding {
            if (variantCount < 1 || variantStride < 1 || !vertexLayers && variantStride != 1) {
                throw new IllegalArgumentException("invalid lightmap-array binding");
            }
            variantSlots = List.copyOf(variantSlots);
            if (!variantSlots.isEmpty() && (vertexLayers || beast || variantSlots.size() != variantCount)) {
                throw new IllegalArgumentException("invalid sparse lightmap binding");
            }
        }

        TextureRef textureAt(int shadowVariant) {
            return selectedSlot(shadowVariant) < 0 ? null : texture;
        }

        float layerAt(int shadowVariant) {
            var slot = selectedSlot(shadowVariant);
            if (texture == null || slot < 0) {
                return 0f;
            }
            if (beast) {
                return beastLightmapLayer(texture.getBaseLayer());
            }
            return encodedLightmapLayer(texture.getBaseLayer(), vertexLayers, variantCount, variantStride, slot);
        }

        private int selectedSlot(int shadowVariant) {
            var selected = Math.clamp(shadowVariant, 0, variantCount - 1);
            return variantSlots.isEmpty() ? selected : variantSlots.get(selected);
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static ByteBuffer packInstances(float[] records) {
        if (records == null || records.length == 0 || records.length % INSTANCE_FLOATS != 0) {
            throw new IllegalArgumentException("invalid scene instance record array");
        }
        var packed = ByteBuffer.allocateDirect(Math.multiplyExact(records.length, Float.BYTES))
                .order(ByteOrder.nativeOrder());
        packed.asFloatBuffer().put(records);
        return packed.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private static void applyActorLightingMetadata(float[] records, int actorLightingOffset, int geometryVertexOffset) {
        var low = actorLightingOffset & 0xffff;
        var high = actorLightingOffset >>> 16;
        if (high > 0xffff || geometryVertexOffset < 0 || geometryVertexOffset >= 1 << 24) {
            throw new IllegalArgumentException("actor lighting metadata exceeds exact float encoding");
        }
        for (var offset = 0; offset < records.length; offset += INSTANCE_FLOATS) {
            records[offset + 16] = low;
            records[offset + 17] = -(high + 1f);
            records[offset + 18] = geometryVertexOffset;
        }
    }

    private static ByteBuffer patchActorLightingMetadata(
            ByteBuffer packed, int instances, int actorLightingOffset, int geometryVertexOffset) {
        if (packed == null
                || instances < 1
                || packed.remaining() != Math.multiplyExact(instances, BgfxSceneVertexLayouts.INSTANCE.getStride())) {
            throw new IllegalArgumentException("invalid packed actor-lighting instance payload");
        }
        var low = actorLightingOffset & 0xffff;
        var high = actorLightingOffset >>> 16;
        if (high > 0xffff || geometryVertexOffset < 0 || geometryVertexOffset >= 1 << 24) {
            throw new IllegalArgumentException("actor lighting metadata exceeds exact float encoding");
        }
        var result = BufferUtils.createByteBuffer(packed.remaining()).order(ByteOrder.nativeOrder());
        result.put(packed.duplicate());
        result.flip();
        for (var instance = 0; instance < instances; instance++) {
            var metadata = instance * BgfxSceneVertexLayouts.INSTANCE.getStride() + 16 * Float.BYTES;
            result.putFloat(metadata, low);
            result.putFloat(metadata + Float.BYTES, -(high + 1f));
            result.putFloat(metadata + 2 * Float.BYTES, geometryVertexOffset);
        }
        return result.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private static boolean usesTerrainLighting(float[] records) {
        if (records == null || records.length < INSTANCE_FLOATS) {
            return false;
        }
        for (var offset = 0; offset < records.length; offset += INSTANCE_FLOATS) {
            var marker = records[offset + 19];
            if (!(marker > 2.5f && marker < 4.5f)) {
                return false;
            }
        }
        return true;
    }

    private static void putLight(float[] target, int offset, Vec4 value) {
        target[offset] = value.getX();
        target[offset + 1] = value.getY();
        target[offset + 2] = value.getZ();
        target[offset + 3] = value.getW();
    }

    private static float packLightComponent(float light, int first, int second, int third) {
        var color = Math.clamp(Math.round(light * 255f), 0, 255);
        return color | first << 8 | second << 12 | third << 16;
    }

    private static float[] toArray(Mat4 source) {
        return source.to(new float[16]);
    }

    private static boolean isIdentity(Mat4 matrix) {
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                if (matrix.get(column, row) != (column == row ? 1f : 0f)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void putStaticMeshMetadata(float[] model, StaticMeshLod lod, boolean billboard) {
        if (lod == null) {
            model[3] = 0f;
            model[7] = 0f;
            model[11] = -1f;
            model[15] = billboard ? 2f : 0f;
            return;
        }
        model[3] = lod.distance1();
        model[7] = lod.distance2();
        model[11] = lod.level();
        model[15] = (lod.authoredBlend() ? 1f : 0f) + (billboard ? 2f : 0f) + lod.impostorThresholdMask() * 4f;
    }

    private static Bounds3f[] transformPrimitives(List<Bounds3f> primitiveLocalBounds, List<float[]> matrices) {
        return transformPrimitives(primitiveLocalBounds, matrices, false);
    }

    private static Bounds3f[] transformPrimitives(
            List<Bounds3f> primitiveLocalBounds, List<float[]> matrices, boolean billboard) {
        var result = new Bounds3f[primitiveLocalBounds.size()];
        for (var primitive = 0; primitive < result.length; primitive++) {
            result[primitive] = transformUnion(primitiveLocalBounds.get(primitive), matrices, billboard);
        }
        return result;
    }

    private static Bounds3f transformUnion(Bounds3f local, List<float[]> matrices) {
        return transformUnion(local, matrices, false);
    }

    private static Bounds3f transformUnion(Bounds3f local, List<float[]> matrices, boolean billboard) {
        Bounds3f result = null;
        for (var matrix : matrices) {
            var transformed = billboard ? transformBillboard(local, matrix) : transform(local, matrix);
            result = result == null ? transformed : union(result, transformed);
        }
        if (result == null) {
            throw new IllegalArgumentException("scene object requires at least one model matrix");
        }
        return result;
    }

    private static float columnLength(float[] matrix, int offset) {
        return (float) Math.sqrt(matrix[offset] * matrix[offset]
                + matrix[offset + 1] * matrix[offset + 1]
                + matrix[offset + 2] * matrix[offset + 2]);
    }
}
