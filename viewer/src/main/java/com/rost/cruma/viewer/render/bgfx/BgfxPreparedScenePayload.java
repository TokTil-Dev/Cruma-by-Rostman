package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Mesh;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.viewer.render.bgfx.BgfxPreparedTexturePayloads.PreparationCancelledException;
import com.rost.cruma.viewer.render.bgfx.BgfxRayOpacity.AlphaCache;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BgfxPreparedScenePayload implements AutoCloseable {
    private final String mapName;
    private final List<EntityPayload> entities;
    private final List<BgfxSceneGeometry.Prepared> meshEmitterGeometry;
    private final List<List<BgfxSceneGeometry.Prepared>> meshEmitterAnimations;
    private final BgfxPreparedTexturePayloads textures;
    private final long retainedBytes;
    private final long preparationNanos;
    private final String workerThread;
    private final BgfxPayloadMemory memory;

    private BgfxPreparedScenePayload(
            String mapName,
            List<EntityPayload> entities,
            List<BgfxSceneGeometry.Prepared> meshEmitterGeometry,
            List<List<BgfxSceneGeometry.Prepared>> meshEmitterAnimations,
            BgfxPreparedTexturePayloads textures,
            long retainedBytes,
            long preparationNanos,
            String workerThread,
            BgfxPayloadMemory memory) {
        if (mapName == null
                || mapName.isBlank()
                || entities == null
                || meshEmitterGeometry == null
                || meshEmitterAnimations == null
                || meshEmitterGeometry.size() != meshEmitterAnimations.size()
                || textures == null
                || retainedBytes < 1L
                || preparationNanos < 0L
                || workerThread == null
                || workerThread.isBlank()) {
            throw new IllegalArgumentException("invalid prepared scene payload");
        }
        this.mapName = mapName;
        this.entities = List.copyOf(entities);
        this.meshEmitterGeometry = List.copyOf(meshEmitterGeometry);
        this.meshEmitterAnimations =
                meshEmitterAnimations.stream().map(List::copyOf).toList();
        this.textures = textures;
        this.retainedBytes = retainedBytes;
        this.preparationNanos = preparationNanos;
        this.workerThread = workerThread;
        this.memory = memory;
    }

    static BgfxPreparedScenePayload prepare(
            UMap map, BgfxCapabilities capabilities, boolean rayData, Set<BgfxTextureContent> resident) {
        if (map == null) {
            throw new IllegalArgumentException("scene payload preparation requires a decoded map");
        }
        var started = System.nanoTime();
        var memory = new BgfxPayloadMemory();
        var textures = BgfxPreparedTexturePayloads.prepare(map, capabilities, memory, resident);
        var entities = new ArrayList<EntityPayload>(map.entities().size());
        var geometryByIdentity = new IdentityHashMap<Mesh, BgfxSceneGeometry.Prepared>();
        Map<String, BgfxSceneGeometry.Prepared> geometryByName = new HashMap<>();
        var bytes = textures.getRetainedBytes();
        var rayOpacity = rayData ? new AlphaCache() : AlphaCache.DISABLED;
        var giByGeometry = new IdentityHashMap<BgfxSceneGeometry.Prepared, ByteBuffer>();
        var prepareGi = rayData && !BgfxSceneUploadSink.isTerrainProxyOwner(map.name());
        for (var ordinal = 0; ordinal < map.entities().size(); ordinal++) {
            checkInterrupted();
            var entity = map.entities().get(ordinal);
            var meshName = entity.getMeshName();
            var geometry = meshName == null || meshName.isBlank()
                    ? geometryByIdentity.get(entity.getMesh())
                    : geometryByName.get(BgfxSceneGeometry.sharedCacheKey(entity));
            if (geometry == null) {
                geometry = BgfxSceneGeometry.prepare(entity, map.name(), ordinal, rayOpacity, memory);
                if (meshName == null || meshName.isBlank()) {
                    geometryByIdentity.put(entity.getMesh(), geometry);
                } else {
                    geometryByName.put(BgfxSceneGeometry.sharedCacheKey(entity), geometry);
                }
                bytes = Math.addExact(bytes, geometry.retainedBytes());
            }
            var object = BgfxSceneObject.prepare(entity, geometry, map.name(), ordinal);
            ByteBuffer giColors = null;
            if (prepareGi) {
                giColors = giByGeometry.get(geometry);
                if (giColors == null) {
                    giColors = BgfxGiMaterial.prepare(entity, geometry);
                    giByGeometry.put(geometry, giColors);
                    bytes = Math.addExact(bytes, giColors.remaining());
                }
            }
            entities.add(new EntityPayload(ordinal, geometry, object, giColors, rayData));
            bytes = Math.addExact(bytes, object.retainedBytes());
        }
        var emitters =
                new ArrayList<BgfxSceneGeometry.Prepared>(map.meshEmitters().size());
        var animations = new ArrayList<List<BgfxSceneGeometry.Prepared>>(
                map.meshEmitters().size());
        var animationByFirstFrame = new IdentityHashMap<EntityMesh, List<BgfxSceneGeometry.Prepared>>();
        for (var ordinal = 0; ordinal < map.meshEmitters().size(); ordinal++) {
            checkInterrupted();
            var descriptor = map.meshEmitters().get(ordinal);
            if (!descriptor.isVertexAnimated()) {
                var geometry = BgfxSceneGeometry.prepareParticle(
                        descriptor.mesh(), map.name() + "/mesh-emitter-" + ordinal, ordinal, memory);
                emitters.add(geometry);
                animations.add(List.of());
                bytes = Math.addExact(bytes, geometry.retainedBytes());
                continue;
            }
            var preparedFrames = animationByFirstFrame.get(descriptor.mesh());
            if (preparedFrames == null) {
                var frames = new ArrayList<BgfxSceneGeometry.Prepared>(
                        descriptor.animationFrames().size());
                for (var frame = 0; frame < descriptor.animationFrames().size(); frame++) {
                    checkInterrupted();
                    var geometry = BgfxSceneGeometry.prepareParticle(
                            descriptor.animationFrames().get(frame),
                            "vert-mesh/" + descriptor.name() + "/frame-" + frame,
                            frame,
                            memory);
                    frames.add(geometry);
                    bytes = Math.addExact(bytes, geometry.retainedBytes());
                }
                preparedFrames = List.copyOf(frames);
                animationByFirstFrame.put(descriptor.mesh(), preparedFrames);
            }
            emitters.add(preparedFrames.getFirst());
            animations.add(preparedFrames);
        }
        return new BgfxPreparedScenePayload(
                map.name(),
                entities,
                emitters,
                animations,
                textures,
                Math.max(1L, bytes),
                Math.max(0L, System.nanoTime() - started),
                Thread.currentThread().getName(),
                memory);
    }

    @Override
    public void close() {
        memory.close();
    }

    String getMapName() {
        return mapName;
    }

    EntityPayload getEntity(int ordinal) {
        var payload = entities.get(ordinal);
        if (payload.ordinal() != ordinal) {
            throw new IllegalStateException("prepared entity payload order is corrupt");
        }
        return payload;
    }

    int getEntityCount() {
        return entities.size();
    }

    BgfxSceneGeometry.Prepared getMeshEmitterGeometry(int ordinal) {
        return meshEmitterGeometry.get(ordinal);
    }

    List<BgfxSceneGeometry.Prepared> getMeshEmitterAnimation(int ordinal) {
        return meshEmitterAnimations.get(ordinal);
    }

    int getMeshEmitterCount() {
        return meshEmitterGeometry.size();
    }

    BgfxPreparedTexturePayloads getTextures() {
        return textures;
    }

    long getRetainedBytes() {
        return retainedBytes;
    }

    long getPreparationNanos() {
        return preparationNanos;
    }

    String getWorkerThread() {
        return workerThread;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new PreparationCancelledException();
        }
    }

    record EntityPayload(
            int ordinal,
            BgfxSceneGeometry.Prepared geometry,
            BgfxSceneObject.Prepared object,
            ByteBuffer giColors,
            boolean rayData) {
        EntityPayload {
            if (ordinal < 0 || geometry == null || object == null) {
                throw new IllegalArgumentException("invalid prepared entity payload");
            }
        }
    }
}
