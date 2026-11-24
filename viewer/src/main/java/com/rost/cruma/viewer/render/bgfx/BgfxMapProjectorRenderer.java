package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_submit_indirect;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.ProjectorDescriptor;
import com.rost.cruma.viewer.render.ShaderHeightCut;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class BgfxMapProjectorRenderer implements AutoCloseable {
    private static final int PARAMETER_VECTORS = 7;
    private static final int CLAMP = (int) (BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP);
    private static final boolean RECEIVER_DIAGNOSTIC =
            Boolean.getBoolean("cruma.viewer.bgfx.projector.receiverDiagnostic");
    private static final boolean VERIFY_RECEIVER_CACHE =
            Boolean.getBoolean("cruma.viewer.bgfx.projector.verify_receiver_cache");

    private static final Logger log = LogManager.getLogger(BgfxMapProjectorRenderer.class);

    private final BgfxTextureCache textures;
    private final BgfxShader entityFragment;
    private final BgfxShader entityDepthMrtFragment;
    private final BgfxProgram entityProgram;
    private final BgfxProgram entityDepthMrtProgram;
    private final BgfxProgram entityGpuProgram;
    private final BgfxProgram entityGpuDepthMrtProgram;
    private final BgfxProgram terrainProgram;
    private final BgfxProgram terrainDepthMrtProgram;
    private final BgfxUniform sampler;
    private final BgfxUniform parameters;
    private final Map<String, List<Entry>> byOwner = new LinkedHashMap<>();
    private final BgfxProjectorReceiverCache<ProjectedDraw> receivers = new BgfxProjectorReceiverCache<>();
    private final Map<Entry, List<ProjectedDraw>[]> staticReceiverDraws = new IdentityHashMap<>();
    private long receiverSceneRevision = Long.MIN_VALUE;
    private long verifiedReceiverUpdates;
    private final float[] parameterValues = new float[PARAMETER_VECTORS * 4];
    private long sourceRevision;
    private boolean closed;

    BgfxMapProjectorRenderer(
            BgfxDevice device,
            BgfxTextureCache textures,
            BgfxShader sharedEntityVertex,
            BgfxShader sharedGpuEntityVertex,
            BgfxShader sharedTerrainVertex) {
        this.textures = textures;
        BgfxShader createdEntityFragment = null;
        BgfxShader createdEntityDepthMrtFragment = null;
        BgfxProgram createdEntity = null;
        BgfxProgram createdEntityDepthMrt = null;
        BgfxProgram createdEntityGpu = null;
        BgfxProgram createdEntityGpuDepthMrt = null;
        BgfxProgram createdTerrain = null;
        BgfxProgram createdTerrainDepthMrt = null;
        BgfxUniform createdSampler = null;
        BgfxUniform createdParameters = null;
        try {
            createdEntityFragment = BgfxShader.create(
                    device.getResources(),
                    "scene/map-projector-entity/fs",
                    BgfxShaderResources.load(device.getCapabilities(), "fs_projector_entity"));
            createdEntityDepthMrtFragment = BgfxShader.create(
                    device.getResources(),
                    "scene/map-projector-entity-depth-mrt/fs",
                    BgfxShaderResources.load(device.getCapabilities(), "fs_projector_entity_depth_mrt"));
            createdEntity = BgfxProgram.graphics(
                    device.getResources(), "scene/map-projector-entity", sharedEntityVertex, createdEntityFragment);
            createdEntityDepthMrt = BgfxProgram.graphics(
                    device.getResources(),
                    "scene/map-projector-entity-depth-mrt",
                    sharedEntityVertex,
                    createdEntityDepthMrtFragment);
            createdEntityGpu = BgfxProgram.graphics(
                    device.getResources(),
                    "scene/map-projector-entity-gpu",
                    sharedGpuEntityVertex,
                    createdEntityFragment);
            createdEntityGpuDepthMrt = BgfxProgram.graphics(
                    device.getResources(),
                    "scene/map-projector-entity-gpu-depth-mrt",
                    sharedGpuEntityVertex,
                    createdEntityDepthMrtFragment);
            createdTerrain = BgfxProgram.graphics(
                    device.getResources(),
                    "scene/map-projector-terrain",
                    sharedTerrainVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_projector_terrain"));
            createdTerrainDepthMrt = BgfxProgram.graphics(
                    device.getResources(),
                    "scene/map-projector-terrain-depth-mrt",
                    sharedTerrainVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_projector_terrain_depth_mrt"));
            createdSampler = BgfxUniform.create(device.getResources(), "s_projector", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdParameters =
                    BgfxUniform.create(device.getResources(), "u_projector", BGFX_UNIFORM_TYPE_VEC4, PARAMETER_VECTORS);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdParameters, failure);
            closeAfterFailure(createdSampler, failure);
            closeAfterFailure(createdTerrainDepthMrt, failure);
            closeAfterFailure(createdTerrain, failure);
            closeAfterFailure(createdEntityGpuDepthMrt, failure);
            closeAfterFailure(createdEntityGpu, failure);
            closeAfterFailure(createdEntityDepthMrt, failure);
            closeAfterFailure(createdEntity, failure);
            closeAfterFailure(createdEntityDepthMrtFragment, failure);
            closeAfterFailure(createdEntityFragment, failure);
            throw failure;
        }
        entityFragment = createdEntityFragment;
        entityDepthMrtFragment = createdEntityDepthMrtFragment;
        entityProgram = createdEntity;
        entityDepthMrtProgram = createdEntityDepthMrt;
        entityGpuProgram = createdEntityGpu;
        entityGpuDepthMrtProgram = createdEntityGpuDepthMrt;
        terrainProgram = createdTerrain;
        terrainDepthMrtProgram = createdTerrainDepthMrt;
        sampler = createdSampler;
        parameters = createdParameters;
    }

    static @Nullable String projectorTextureName(@Nullable String materialName) {
        return materialName == null || materialName.isBlank() ? null : "projector/" + materialName;
    }

    void publish(String owner, List<ProjectorDescriptor> descriptors) {
        requireOpen();
        if (owner == null || owner.isBlank() || descriptors == null) {
            throw new IllegalArgumentException("projector publication requires owner and descriptors");
        }
        closeEntries(byOwner.remove(owner));
        if (descriptors.isEmpty()) {
            invalidate();
            return;
        }
        var entries = new ArrayList<Entry>(descriptors.size());
        var skippedBlend = 0;
        var skippedMaterial = 0;
        try {
            for (var ordinal = 0; ordinal < descriptors.size(); ordinal++) {
                var descriptor = descriptors.get(ordinal);
                var blendPair = descriptor.fusedBlendPair();
                if (blendPair < 0) {
                    skippedBlend++;
                    continue;
                }
                var sourceTexture = descriptor.material().getTexture();
                if (sourceTexture == null) {
                    skippedMaterial++;
                    continue;
                }
                entries.add(new Entry(
                        owner,
                        descriptor,
                        blendPair,
                        textures.load(sourceTexture, projectorTextureName(descriptor.materialName())),
                        descriptor.volumeBounds()));
            }
        } catch (RuntimeException | Error failure) {
            closeEntriesAfterFailure(entries, failure);
            throw failure;
        }
        if (!entries.isEmpty()) {
            byOwner.put(owner, List.copyOf(entries));
        }
        invalidate();
        log.debug(
                "Published {} authored projector(s) for {}" + " (unsupported blend={}, unsupported material={})",
                entries.size(),
                owner,
                skippedBlend,
                skippedMaterial);
    }

    void evict(Set<String> owners) {
        requireOpen();
        if (owners == null || owners.isEmpty()) {
            return;
        }
        var removed = false;
        for (var owner : new HashSet<>(owners)) {
            var entries = byOwner.remove(owner);
            if (entries != null) {
                closeEntries(entries);
                removed = true;
            }
        }
        if (removed) {
            invalidate();
        }
    }

    List<ProjectedDraw> collectDraws(List<BgfxSceneObject> objects, long sceneRevision, Set<String> movedOwners) {
        requireOpen();
        if (receiverSceneRevision != sceneRevision) {
            staticReceiverDraws.clear();
            receiverSceneRevision = sceneRevision;
        }
        return receivers.update(
                objects, sceneRevision, sourceRevision, byOwner.keySet(), movedOwners, this::buildReceiverDraws);
    }

    void bind(ProjectedDraw draw, @Nullable ShaderHeightCut heightCut) {
        var descriptor = draw.entry.descriptor;
        var basis = descriptor.basis();
        var location = descriptor.location();
        put(0, location.getX(), location.getY(), location.getZ(), descriptor.fovDegrees() == 0 ? 0f : 1f);
        put(
                1,
                basis.direction().getX(),
                basis.direction().getY(),
                basis.direction().getZ(),
                descriptor.maxTraceDistance());
        put(2, basis.left().getX(), basis.left().getY(), basis.left().getZ(), descriptor.uSize());
        put(3, basis.up().getX(), basis.up().getY(), basis.up().getZ(), descriptor.vSize());
        var tanHalf =
                descriptor.fovDegrees() == 0 ? 0f : (float) Math.tan(Math.toRadians(descriptor.fovDegrees()) * 0.5);
        put(
                4,
                tanHalf,
                draw.entry.blendPair,
                descriptor.projectOnBackfaces() ? 1f : 0f,
                descriptor.projectOnAlpha() ? 1f : 0f);
        ShaderHeightCut.put(heightCut, surfaceType(draw), parameterValues, 5 * 4);
        bgfx_set_uniform(parameters.getHandle(), parameterValues, PARAMETER_VECTORS);
        bgfx_set_texture(
                0,
                sampler.getHandle(),
                draw.entry.texture.getTexture().getHandle(),
                draw.entry.texture.getSamplerFlags() | CLAMP);
    }

    void submit(int view, ProjectedDraw draw, boolean ortho, boolean depthMrt) {
        var material = draw.primitive().entityMaterial();
        bgfx_set_state(stateFor(draw, ortho), 0);
        var program = material == null
                ? (depthMrt ? terrainDepthMrtProgram : terrainProgram)
                : (depthMrt ? entityDepthMrtProgram : entityProgram);
        bgfx_submit(view, program.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    void submitIndirect(
            int view,
            ProjectedDraw draw,
            boolean ortho,
            boolean depthMrt,
            short indirectHandle,
            int commandIndex,
            int depthKey) {
        bgfx_set_state(stateFor(draw, ortho), 0);
        bgfx_submit_indirect(
                view,
                (depthMrt ? entityGpuDepthMrtProgram : entityGpuProgram).getHandle(),
                indirectHandle,
                commandIndex,
                1,
                depthKey,
                BGFX_DISCARD_ALL);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (VERIFY_RECEIVER_CACHE) {
            log.info("Verified {} owner updates of the receiver cache", verifiedReceiverUpdates);
        }
        RuntimeException failure = null;
        for (var entries : byOwner.values()) {
            try {
                closeEntries(entries);
            } catch (RuntimeException closeFailure) {
                failure = merge(failure, closeFailure);
            }
        }
        byOwner.clear();
        receivers.clear();
        staticReceiverDraws.clear();
        failure = closeAndMerge(parameters, failure);
        failure = closeAndMerge(sampler, failure);
        failure = closeAndMerge(terrainDepthMrtProgram, failure);
        failure = closeAndMerge(terrainProgram, failure);
        failure = closeAndMerge(entityGpuDepthMrtProgram, failure);
        failure = closeAndMerge(entityGpuProgram, failure);
        failure = closeAndMerge(entityDepthMrtProgram, failure);
        failure = closeAndMerge(entityProgram, failure);
        failure = closeAndMerge(entityDepthMrtFragment, failure);
        failure = closeAndMerge(entityFragment, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private List<ProjectedDraw> buildReceiverDraws(String owner, List<BgfxSceneObject> objects) {
        var draws = buildReceiverDraws(owner, objects, !RECEIVER_DIAGNOSTIC);
        if (VERIFY_RECEIVER_CACHE && !RECEIVER_DIAGNOSTIC) {
            if (!draws.equals(buildReceiverDraws(owner, objects, false))) {
                throw new IllegalStateException("projector receiver cache differs from complete search for " + owner);
            }
            verifiedReceiverUpdates++;
        }
        return draws;
    }

    private List<ProjectedDraw> buildReceiverDraws(String owner, List<BgfxSceneObject> objects, boolean cacheStatic) {
        var draws = new ArrayList<ProjectedDraw>();
        var objectVolumeHits = 0;
        var primitiveVolumeHits = 0;
        for (var entry : byOwner.get(owner)) {
            var cachedObjects = cacheStatic
                    ? staticReceiverDraws.computeIfAbsent(entry, _ -> emptyReceiverCache(objects.size()))
                    : null;
            for (var objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
                var object = objects.get(objectIndex);
                var reusable = cacheStatic && !object.isMoving();
                var cached = reusable ? cachedObjects[objectIndex] : null;
                if (cached != null) {
                    if (!cached.isEmpty()) {
                        draws.addAll(cached);
                    }
                    continue;
                }
                var geometry = object.getGeometry();
                if (!entry.descriptor.intersects(object.getWorldBounds())) {
                    if (reusable) {
                        cachedObjects[objectIndex] = List.of();
                    }
                    continue;
                }
                objectVolumeHits++;
                var firstDraw = draws.size();
                for (var primitiveOrdinal = 0;
                        primitiveOrdinal < geometry.getPrimitives().size();
                        primitiveOrdinal++) {
                    if (!entry.descriptor.intersects(object.getPrimitiveBounds(primitiveOrdinal))) {
                        continue;
                    }
                    primitiveVolumeHits++;
                    var primitive = geometry.getPrimitives().get(primitiveOrdinal);
                    if (receivesProjector(entry.descriptor, object, primitive)) {
                        var visible = clip(object.getPrimitiveBounds(primitiveOrdinal), entry.volumeBounds);
                        if (visible != null) {
                            draws.add(new ProjectedDraw(entry, object, primitiveOrdinal, visible));
                        }
                    }
                }
                if (reusable) {
                    cachedObjects[objectIndex] = List.copyOf(draws.subList(firstDraw, draws.size()));
                }
            }
        }
        if (RECEIVER_DIAGNOSTIC) {
            log.info(
                    "Projector receivers: owner={} projectors={} objects={}"
                            + " objectVolumeHits={} primitiveVolumeHits={} receiverDraws={}",
                    owner,
                    byOwner.get(owner).size(),
                    objects.size(),
                    objectVolumeHits,
                    primitiveVolumeHits,
                    draws.size());
        }
        return draws;
    }

    private void put(int vector, float x, float y, float z, float w) {
        var offset = vector * 4;
        parameterValues[offset] = x;
        parameterValues[offset + 1] = y;
        parameterValues[offset + 2] = z;
        parameterValues[offset + 3] = w;
    }

    private void invalidate() {
        sourceRevision = Math.incrementExact(sourceRevision);
        receivers.clear();
        staticReceiverDraws.clear();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("map projector renderer is closed");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ProjectedDraw>[] emptyReceiverCache(int objects) {
        return (List<ProjectedDraw>[]) new List<?>[objects];
    }

    private static long stateFor(ProjectedDraw draw, boolean ortho) {
        return BgfxSceneState.projector(
                draw.entry.blendPair, surfaceType(draw), ortho, draw.entry.descriptor.projectOnBackfaces());
    }

    private static long surfaceType(ProjectedDraw draw) {
        var material = draw.primitive().entityMaterial();
        return material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
    }

    private static boolean receivesProjector(
            ProjectorDescriptor projector, BgfxSceneObject object, Primitive primitive) {
        var material = primitive.entityMaterial();
        if (material == null) {
            return projector.projectTerrain();
        }
        if (material.getBlendMode() == BlendMode.INVISIBLE
                || !projector.projectOnUnlit() && material.getBlendMode() == BlendMode.UNLIT) {
            return false;
        }
        var surfaceType = material.getSurfaceType();
        if ((surfaceType & SurfaceType.CSG.getId()) != 0) {
            return projector.projectBsp();
        }
        return projector.projectStaticMesh() && object.acceptsProjectors();
    }

    private static void closeEntries(List<Entry> entries) {
        if (entries == null) {
            return;
        }
        RuntimeException failure = null;
        for (var entry : entries) {
            failure = closeAndMerge(entry.texture, failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeEntriesAfterFailure(List<Entry> entries, Throwable failure) {
        for (var entry : entries) {
            closeAfterFailure(entry.texture, failure);
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

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            return merge(
                    failure,
                    closeFailure instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException(closeFailure));
        }
        return failure;
    }

    private static RuntimeException merge(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private record Entry(
            String owner, ProjectorDescriptor descriptor, int blendPair, TextureRef texture, Bounds3f volumeBounds) {}

    record ProjectedDraw(Entry entry, BgfxSceneObject object, int primitiveOrdinal, Bounds3f visibleBounds) {
        Primitive primitive() {
            return object.getGeometry().getPrimitives().get(primitiveOrdinal);
        }

        Object projector() {
            return entry;
        }

        Bounds3f volumeBounds() {
            return entry.volumeBounds();
        }
    }

    private static @Nullable Bounds3f clip(Bounds3f receiver, Bounds3f volume) {
        var minX = Math.max(receiver.minX(), volume.minX());
        var minY = Math.max(receiver.minY(), volume.minY());
        var minZ = Math.max(receiver.minZ(), volume.minZ());
        var maxX = Math.min(receiver.maxX(), volume.maxX());
        var maxY = Math.min(receiver.maxY(), volume.maxY());
        var maxZ = Math.min(receiver.maxZ(), volume.maxZ());
        return minX <= maxX && minY <= maxY && minZ <= maxZ ? new Bounds3f(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }
}
