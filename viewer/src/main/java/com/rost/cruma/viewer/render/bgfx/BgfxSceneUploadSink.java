package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientVolume;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.MultiTextureEntry;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainMeshVertex;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import com.rost.cruma.core.world.L2FogInfo;
import com.rost.cruma.core.world.LightCorona;
import com.rost.cruma.core.world.MusicVolume;
import com.rost.cruma.core.world.ReflectionBspModel;
import com.rost.cruma.core.world.TerrainZoneLayers;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.WaterVolume;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.core.world.ZoneStateItem;
import com.rost.cruma.core.world.ZoneStateMesh;
import com.rost.cruma.viewer.geodata.ClientFloorGrid;
import com.rost.cruma.viewer.geodata.GeoClientSurfaceCache;
import com.rost.cruma.viewer.geodata.GeoClientSurfaceIndex;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.SceneContentCensus;
import com.rost.cruma.viewer.render.SceneContentCensus.Prepared;
import com.rost.cruma.viewer.render.api.DebugMaterialSlot;
import com.rost.cruma.viewer.render.api.SceneCensus;
import com.rost.cruma.viewer.render.api.SceneLoadTelemetry;
import com.rost.cruma.viewer.render.api.SceneUploadSink;
import com.rost.cruma.viewer.render.api.SceneUploadSink.PreparedMap;
import com.rost.cruma.viewer.render.api.SceneUploadStats;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Tuning;
import glm_.quat.Quat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class BgfxSceneUploadSink implements SceneUploadSink, AutoCloseable {
    static final float CAPTURE_EYE_Z = 10000f;

    static final int TERRAIN_PROXY_TEXTURE_SIZE = 128;

    static final String TERRAIN_OWNER_PREFIX = "@terrain/";

    private static final int TERRAIN_PROXY_DIFFUSE_SAMPLE_EXTENT = 64;

    private static final int TERRAIN_PROXY_FALLBACK_EXTENT = 32;

    private static final Logger log = LogManager.getLogger(BgfxSceneUploadSink.class);

    private final RenderingContext renderingContext;
    private final BgfxSceneRenderer scene;
    private final BgfxDevice device;
    private final boolean mapPreview;
    private MapRecord captureMap;
    private final Map<String, MapRecord> records = new HashMap<>();
    private @Nullable PartUpload partUpload;
    private final int terrainProxyGridStep;
    private final SceneContentCensus contentCensus = new SceneContentCensus();
    private final BgfxMemoryCopy.Snapshot stagingCopyBaseline;
    private final BgfxGeometryPool.Snapshot geometryUploadBaseline;
    private final BgfxInstancePool.Snapshot instanceUploadBaseline;
    private int uploadedMaps;
    private long uploadNanos;
    private boolean closed;

    public BgfxSceneUploadSink(
            RenderingContext renderingContext, BgfxSceneRenderer scene, BgfxDevice device, Tuning tuning) {
        this(renderingContext, scene, device, tuning, false);
    }

    public BgfxSceneUploadSink(
            RenderingContext renderingContext,
            BgfxSceneRenderer scene,
            BgfxDevice device,
            Tuning tuning,
            boolean mapPreview) {
        this.renderingContext = renderingContext;
        this.scene = scene;
        this.device = device;
        this.mapPreview = mapPreview;
        this.terrainProxyGridStep = tuning.terrainProxyGridStep();
        stagingCopyBaseline = BgfxMemoryCopy.snapshot();
        geometryUploadBaseline = scene.getGeometryUploadSnapshot();
        instanceUploadBaseline = scene.getInstanceUploadSnapshot();
    }

    static Set<String> previewHiddenActors(UMap map) {
        var clouds = new HashSet<String>();
        for (var mesh : map.meshEmitters()) {
            if ("Bereth_cloud2".equalsIgnoreCase(mesh.name())
                    && mesh.actorName() != null
                    && !mesh.actorName().isBlank()) {
                clouds.add(mesh.actorName());
            }
        }
        var hidden = new HashSet<String>();
        for (var sprite : map.spriteEmitters()) {
            if (clouds.contains(sprite.actorName())
                    && "jm-flat".equalsIgnoreCase(sprite.name())
                    && sprite.emitter().getStartSizeMin().getX() >= 5000f
                    && sprite.emitter().getStartSizeMin().getY() >= 5000f) {
                hidden.add(sprite.actorName());
            }
        }
        return Set.copyOf(hidden);
    }

    static @Nullable String particleTextureName(@Nullable String textureName) {
        return textureName == null || textureName.isBlank() ? null : "fx/" + textureName;
    }

    static String terrainOwner(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            throw new IllegalArgumentException("map name cannot be blank");
        }
        return TERRAIN_OWNER_PREFIX + mapName;
    }

    static UMap terrainShell(UMap map, int gridStep) {
        if (map == null) {
            throw new IllegalArgumentException("map cannot be null");
        }
        var terrain = map.entities().stream()
                .filter(entity -> entity.getMesh() instanceof TerrainMesh)
                .map(entity -> terrainProxyEntity(entity, gridStep))
                .toList();
        if (terrain.isEmpty()) {
            return null;
        }
        return new UMap(
                terrainOwner(map.name()),
                terrain,
                map.position(),
                map.boundingBox(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    static boolean isTerrainProxyOwner(String owner) {
        return owner != null && owner.startsWith(TERRAIN_OWNER_PREFIX);
    }

    static String terrainDetailOwner(String proxyOwner) {
        if (!isTerrainProxyOwner(proxyOwner)) {
            throw new IllegalArgumentException("not a terrain proxy owner: " + proxyOwner);
        }
        return proxyOwner.substring(TERRAIN_OWNER_PREFIX.length());
    }

    static Texture bakeTerrainProxyTexture(MultiTexture source) {
        return bakeTerrainProxyTexture(source, List.of());
    }

    static Texture bakeTerrainProxyTexture(MultiTexture source, List<TerrainMeshVertex> vertices) {
        if (source == null || source.entries().isEmpty()) {
            return null;
        }
        var size = TERRAIN_PROXY_TEXTURE_SIZE;
        var layerCount =
                Math.min(BgfxTerrainMaterial.MAX_LAYERS, source.entries().size());
        var colors = new int[layerCount];
        var samplers = new TerrainLayerSampler[layerCount];
        var weights = new byte[layerCount][];
        var hasDiffuse = false;
        var zones = source.getZoneLayers();
        var hidden = zones.findHiddenLayers(ZoneStateChoice.EMPTY);
        var baseLayer = zones.findBaseLayer(hidden);
        baseLayer = baseLayer < layerCount ? baseLayer : 0;
        for (var layer = 0; layer < layerCount; layer++) {
            var entry = source.entries().get(layer);
            if (layer != baseLayer && (hidden >>> layer & 1) != 0) {
                continue;
            }
            if (entry.texture() != null) {
                colors[layer] = averageTerrainLayer(entry.texture());
                samplers[layer] = terrainLayerSampler(entry.texture());
                hasDiffuse = true;
            }
            if (layer > baseLayer && entry.alphaMap() != null) {
                weights[layer] = terrainProxyWeights(entry.alphaMap(), size);
            }
        }
        if (!hasDiffuse) {
            return null;
        }

        var grid = terrainGrid(vertices);
        var bgra = new byte[size * size * 4];
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                var pixel = y * size + x;
                var base = terrainLayerColor(samplers[baseLayer], colors[baseLayer], grid, baseLayer, x, y, size);
                var red = base >>> 16 & 0xff;
                var green = base >>> 8 & 0xff;
                var blue = base & 0xff;
                for (var layer = baseLayer + 1; layer < layerCount; layer++) {
                    var alpha = weights[layer] == null ? 0 : weights[layer][pixel] & 0xff;
                    if (alpha == 0) {
                        continue;
                    }
                    var layerColor = terrainLayerColor(samplers[layer], colors[layer], grid, layer, x, y, size);
                    alpha = (alpha * (layerColor >>> 24) + 127) / 255;
                    if (alpha == 0) {
                        continue;
                    }
                    var inverse = 255 - alpha;
                    red = (red * inverse + (layerColor >>> 16 & 0xff) * alpha + 127) / 255;
                    green = (green * inverse + (layerColor >>> 8 & 0xff) * alpha + 127) / 255;
                    blue = (blue * inverse + (layerColor & 0xff) * alpha + 127) / 255;
                }
                var offset = pixel * 4;
                bgra[offset] = (byte) blue;
                bgra[offset + 1] = (byte) green;
                bgra[offset + 2] = (byte) red;
                bgra[offset + 3] = (byte) 0xff;
            }
        }
        return new Texture(TextureFormat.TEXTURE_RGBA, size, size, List.of(bgra), false, true, true);
    }

    @Override
    public void renderMap(UMap map, boolean setCamera) {
        if (map == null) {
            throw new IllegalArgumentException("map cannot be null");
        }
        requireOpen();
        evictPreviousCaptureMap();
        var started = System.nanoTime();
        var terrain = terrainShell(map, terrainProxyGridStep);
        if (terrain != null) {
            scene.upload(terrain);
        }
        scene.upload(map);
        completeMap(
                map,
                setCamera,
                null,
                SceneContentCensus.prepare(map),
                prepareClientSurfaces(map),
                prepareClientFloors(map),
                started);
    }

    private record PreparedUpload(
            String mapName,
            UMap terrain,
            BgfxPreparedScenePayload terrainPayload,
            BgfxPreparedScenePayload payload,
            Prepared census,
            GeoClientSurfaceCache.Prepared clientSurfaces,
            ClientFloorGrid clientFloors)
            implements PreparedMap {
        @Override
        public long getRetainedBytes() {
            return payload.getRetainedBytes() + (terrainPayload == null ? 0L : terrainPayload.getRetainedBytes());
        }

        @Override
        public void release() {
            payload.close();
            if (terrainPayload != null) {
                terrainPayload.close();
            }
        }
    }

    private static final class PartUpload {
        final UMap map;
        int terrainEntity;
        boolean terrainDone;
        int entity;
        long spent;

        PartUpload(UMap map) {
            this.map = map;
        }
    }

    @Override
    public PreparedMap prepareMap(UMap map) {
        if (map == null) {
            throw new IllegalArgumentException("map cannot be null");
        }
        var capabilities = device.getCapabilities();
        var rayData = scene.collectsRayData();
        var resident = scene.getResidentTextureContents();
        var terrain = terrainShell(map, terrainProxyGridStep);
        return new PreparedUpload(
                map.name(),
                terrain,
                terrain == null ? null : BgfxPreparedScenePayload.prepare(terrain, capabilities, rayData, resident),
                BgfxPreparedScenePayload.prepare(map, capabilities, rayData, resident),
                SceneContentCensus.prepare(map),
                prepareClientSurfaces(map),
                prepareClientFloors(map));
    }

    @Override
    public void enqueueMap(UMap map, boolean setCamera, PreparedMap prepared) {
        if (map == null) {
            throw new IllegalArgumentException("map cannot be null");
        }
        if (!(prepared instanceof PreparedUpload upload) || !upload.mapName().equals(map.name())) {
            renderMap(map, setCamera);
            return;
        }
        requireOpen();
        evictPreviousCaptureMap();
        var started = System.nanoTime();
        if (upload.terrain() != null) {
            scene.upload(upload.terrain(), upload.terrainPayload());
        }
        scene.upload(map, upload.payload());
        completeMap(
                map,
                setCamera,
                upload.payload(),
                upload.census(),
                upload.clientSurfaces(),
                upload.clientFloors(),
                started);
        upload.release();
    }

    @Override
    public boolean enqueueMapPart(UMap map, PreparedMap prepared, long nanos) {
        if (map == null) {
            throw new IllegalArgumentException("map cannot be null");
        }
        if (mapPreview
                || !(prepared instanceof PreparedUpload upload)
                || !upload.mapName().equals(map.name())) {
            enqueueMap(map, false, prepared);
            return true;
        }
        requireOpen();
        var part = partUpload;
        if (part == null || part.map != map) {
            part = new PartUpload(map);
            partUpload = part;
        }
        var started = System.nanoTime();
        var terrain = upload.terrain();
        if (terrain != null && !part.terrainDone) {
            part.terrainEntity = scene.uploadPart(terrain, upload.terrainPayload(), part.terrainEntity, nanos);
            part.terrainDone = part.terrainEntity == terrain.entities().size();
        }
        if (terrain == null || part.terrainDone) {
            var left = nanos - (System.nanoTime() - started);
            if (left > 0 || part.entity == 0 && map.entities().isEmpty()) {
                part.entity = scene.uploadPart(map, upload.payload(), part.entity, Math.max(left, 1));
            }
        }
        part.spent += System.nanoTime() - started;
        if (part.entity < map.entities().size() || terrain != null && !part.terrainDone) {
            return false;
        }
        partUpload = null;
        completeMap(
                map,
                false,
                upload.payload(),
                upload.census(),
                upload.clientSurfaces(),
                upload.clientFloors(),
                System.nanoTime() - part.spent);
        upload.release();
        return true;
    }

    @Override
    public void replaceMap(UMap map, PreparedMap prepared) {
        if (map == null) {
            throw new IllegalArgumentException("map cannot be null");
        }
        requireOpen();
        var previous = records.remove(map.name());
        if (previous != null) {
            uninstallMetadata(previous);
        }
        scene.replaceMaps(Set.of(map.name(), terrainOwner(map.name())));
        enqueueMap(map, false, prepared);
    }

    @Override
    public void computeBakePlan() {
    }

    @Override
    public void applyBake() {
        scene.bakeInstances();
        scene.flushPendingTopology();
        scene.logActorLightingSummary("final");
    }

    @Override
    public void buildDrawList(int cellSize) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("draw-list cell size must be positive");
        }
    }

    @Override
    public void clearCache() {
        scene.clearTransientCaches();
    }

    @Override
    public void clearNamedTextureCache() {
        scene.clearNamedTextureCache();
    }

    @Override
    public SceneUploadStats getUploadStats() {
        return scene.getUploadStats();
    }

    @Override
    public SceneLoadTelemetry getLoadTelemetry() {
        var staging = BgfxMemoryCopy.snapshot().since(stagingCopyBaseline);
        var geometry = scene.getGeometryUploadSnapshot().since(geometryUploadBaseline);
        var instances = scene.getInstanceUploadSnapshot().since(instanceUploadBaseline);
        return new SceneLoadTelemetry(
                uploadedMaps,
                uploadNanos,
                staging.calls(),
                staging.nanos(),
                staging.bytes(),
                geometry.indexSubmissions(),
                geometry.stagedIndexBytes(),
                geometry.submittedIndexBytes(),
                instances.uploadSubmissions(),
                instances.uploadedBytes(),
                geometry.chunks(),
                geometry.capacityBytes(),
                geometry.liveBytes(),
                instances.chunks(),
                instances.capacityBytes(),
                instances.liveBytes(),
                scene.getTopologyBuilds(),
                scene.getTopologyBuildNanos(),
                scene.getTopologyDeltaUploadBytes());
    }

    @Override
    public SceneCensus getSceneCensus() {
        return contentCensus.snapshot();
    }

    @Override
    public void close() {
        closed = true;
        renderingContext.geodata.clientSurfaces.clear();
        renderingContext.spawns.floors.clear();
    }

    private ClientFloorGrid prepareClientFloors(UMap map) {
        if (!renderingContext.spawns.wanted) {
            return null;
        }
        var started = System.nanoTime();
        try {
            var grid = ClientFloorGrid.build(map, () -> closed);
            if (grid != null) {
                log.info(
                        "Spawn floors prepared: map={} bytes={} elapsedMs={}",
                        map.name(),
                        grid.getRetainedBytes(),
                        (System.nanoTime() - started) / 1_000_000);
            }
            return grid;
        } catch (RuntimeException failure) {
            log.warn("Spawn floors of {} are unavailable: {}", map.name(), failure.toString());
            return null;
        }
    }

    private GeoClientSurfaceCache.Prepared prepareClientSurfaces(UMap map) {
        var geodata = renderingContext.geodata;
        if (geodata.path == null && !geodata.enabled && !geodata.toolsOpen) {
            return null;
        }
        var started = System.nanoTime();
        var prepared = geodata.clientSurfaces.prepare(map);
        var index = prepared.index();
        log.info(
                "Geodata client reference prepared: map={} triangles={} bytes={} truncated={} elapsedMs={}"
                        + " algorithm={} bounds={}",
                map.name(),
                index.getTriangleCount(),
                index.getRetainedBytes(),
                index.isTruncated(),
                (System.nanoTime() - started) / 1_000_000,
                GeoClientSurfaceIndex.ALGORITHM,
                index.getBounds());
        return prepared;
    }

    private void evictPreviousCaptureMap() {
        if (mapPreview && captureMap != null) {
            uninstallMetadata(captureMap);
            scene.evictMaps(Set.of(captureMap.name, terrainOwner(captureMap.name)));
            scene.clearNamedTextureCache();
            captureMap = null;
        }
    }

    private void completeMap(
            UMap map,
            boolean setCamera,
            BgfxPreparedScenePayload prepared,
            Prepared census,
            GeoClientSurfaceCache.Prepared clientSurfaces,
            ClientFloorGrid clientFloors,
            long started) {
        var record = new MapRecord(map.name());
        if (prepared == null) {
            installMetadataNow(record, map, null);
        } else {
            scene.withPreparedTextures(prepared.getTextures(), () -> installMetadataNow(record, map, prepared));
        }
        if (setCamera) {
            setCamera(map);
            renderingContext.geodata.clientSurfaces.focus(
                    renderingContext.camera.getPosition().getX(),
                    renderingContext.camera.getPosition().getY());
        }
        renderingContext.geodata.clientSurfaces.publish(clientSurfaces);
        if (clientFloors != null) {
            renderingContext.spawns.floors.put(clientFloors);
        }
        if (clientSurfaces != null) {
            log.info(
                    "Geodata client reference installed: map={} cache={}",
                    map.name(),
                    renderingContext.geodata.clientSurfaces.getStatus());
        }
        contentCensus.include(census);
        uploadedMaps++;
        if (mapPreview) {
            captureMap = record;
        } else {
            records.put(record.name, record);
        }
        uploadNanos = Math.addExact(uploadNanos, Math.max(0L, System.nanoTime() - started));
    }

    private void installMetadataNow(MapRecord record, UMap map, BgfxPreparedScenePayload prepared) {
        if (record.metadata != null) {
            return;
        }
        var emitters = new ArrayList<EmitterBinding>();
        var hiddenActors = mapPreview ? previewHiddenActors(map) : Set.<String>of();
        var meshEmitterOrdinal = 0;
        var reflections = List.copyOf(map.reflectionBspModels());
        var water = List.copyOf(map.waterVolumes());
        var music = List.copyOf(map.musicVolumes());
        var ambient = List.copyOf(map.ambientSounds());
        var ambientVolumes = List.copyOf(map.ambientVolumes());
        var fogInfos = List.copyOf(map.l2FogInfos());
        var failedEmitters = 0;
        RuntimeException firstEmitterFailure = null;
        var coronas = new ArrayList<LightCorona>(map.coronas().size());
        var coronaTextures = new ArrayList<Texture>();
        try {
            renderingContext.scene.addReflectionBspModels(reflections);
            renderingContext.scene.addWaterVolumes(water);
            renderingContext.scene.addMusicVolumes(music);
            renderingContext.scene.addAmbientSounds(ambient);
            renderingContext.scene.addAmbientVolumes(ambientVolumes);
            renderingContext.scene.addL2FogInfos(fogInfos);
            renderingContext.scene.setZoneStateMeshes(record.name, ZoneStateMesh.of(record.name, map.entities()));
            renderingContext.scene.setZoneStateItems(record.name, ZoneStateItem.of(map));
            renderingContext.scene.setSkyZones(
                    record.name,
                    map.localSky() == null ? List.of() : map.localSky().zones());
            renderingContext.scene.setMapPawns(record.name, map.pawns());
            for (var descriptor : map.spriteEmitters()) {
                if (hiddenActors.contains(descriptor.actorName())) {
                    continue;
                }
                var emitter = new ParticleEmitter(
                        descriptor.emitter(),
                        descriptor.actorPos(),
                        Kind.SPRITE,
                        descriptor.blend(),
                        descriptor.seed());
                emitter.setActorTransform(new Quat(descriptor.rotation().vector()), descriptor.scale());
                emitter.setVisibility(descriptor.visibility());
                emitter.setTranslucentRenderPriority(descriptor.translucentRenderPriority());
                emitter.setRenderActorIdentity(
                        descriptor.actorName(), descriptor.actorOrder(), descriptor.childOrder());
                emitter.setSceneMap(record.name);
                emitter.setDebugName(descriptor.name());
                Texture sprite;
                try {
                    sprite = scene.registerParticleTexture(
                            record.name, descriptor.sprite(), particleTextureName(descriptor.textureName()));
                } catch (RuntimeException failure) {
                    failedEmitters++;
                    if (firstEmitterFailure == null) {
                        firstEmitterFailure = failure;
                    }
                    continue;
                }
                emitter.setSpriteImage(sprite);
                emitters.add(new EmitterBinding(emitter, false, sprite));
                renderingContext.particles.add(emitter);
            }
            for (var descriptor : map.meshEmitters()) {
                var emitterOrdinal = meshEmitterOrdinal++;
                if (hiddenActors.contains(descriptor.actorName())) {
                    continue;
                }
                var emitter = new ParticleEmitter(
                        descriptor.emitter(),
                        descriptor.actorPos(),
                        Kind.MESH,
                        descriptor.emitter().getDrawStyle(),
                        descriptor.seed());
                emitter.setActorTransform(new Quat(descriptor.rotation().vector()), descriptor.scale());
                emitter.setMeshPrePivot(descriptor.prePivot());
                emitter.setMeshSurfaceRules(
                        descriptor.particleColorSurfaces(),
                        descriptor.unlitSurfaces(),
                        descriptor.severalPassSurfaces());
                var sourceSurfaces = descriptor.mesh().surfaces();
                var diagnosticMaterials = new ArrayList<DebugMaterialSlot>();
                for (var slot = 0; slot < sourceSurfaces.size(); slot++) {
                    diagnosticMaterials.add(DebugMaterialSlot.fromSurface(slot, sourceSurfaces.get(slot)));
                }
                emitter.setDiagnosticMaterials(diagnosticMaterials);
                emitter.setBlackBackground(descriptor.blackBackground());
                emitter.setRenderTwoSided(descriptor.renderTwoSided());
                emitter.setVisibility(descriptor.visibility());
                emitter.setTranslucentRenderPriority(descriptor.translucentRenderPriority());
                emitter.setRenderActorIdentity(
                        descriptor.actorName(), descriptor.actorOrder(), descriptor.childOrder());
                emitter.setSceneMap(record.name);
                emitter.setDebugName(descriptor.name());
                try {
                    if (descriptor.isVertexAnimated()) {
                        emitter.configureMeshAnimation(
                                descriptor.animationFrames().size(),
                                descriptor.animationStartFrame(),
                                descriptor.animationFrameCount(),
                                descriptor.animationFramesPerSecond());
                        scene.registerAnimatedMeshEmitter(
                                record.name,
                                emitter,
                                descriptor.animationFrames(),
                                prepared == null ? List.of() : prepared.getMeshEmitterAnimation(emitterOrdinal));
                    } else if (prepared == null) {
                        scene.registerMeshEmitter(record.name, emitter, descriptor.mesh());
                    } else {
                        scene.registerMeshEmitter(
                                record.name,
                                emitter,
                                descriptor.mesh(),
                                prepared.getMeshEmitterGeometry(emitterOrdinal));
                    }
                } catch (RuntimeException failure) {
                    failedEmitters++;
                    if (firstEmitterFailure == null) {
                        firstEmitterFailure = failure;
                    }
                    continue;
                }
                emitters.add(new EmitterBinding(emitter, true, null));
                renderingContext.particles.add(emitter);
            }
            for (var descriptor : map.beamEmitters()) {
                if (hiddenActors.contains(descriptor.actorName())) {
                    continue;
                }
                var emitter = new ParticleEmitter(
                        descriptor.emitter(), descriptor.actorPos(), Kind.BEAM, descriptor.blend(), descriptor.seed());
                emitter.setActorTransform(new Quat(descriptor.rotation().vector()), descriptor.scale());
                emitter.setVisibility(descriptor.visibility());
                emitter.setTranslucentRenderPriority(descriptor.translucentRenderPriority());
                emitter.setRenderActorIdentity(
                        descriptor.actorName(), descriptor.actorOrder(), descriptor.childOrder());
                emitter.setSceneMap(record.name);
                emitter.setDebugName(descriptor.name());
                Texture texture;
                try {
                    texture = scene.registerParticleTexture(
                            record.name, descriptor.texture(), particleTextureName(descriptor.textureName()));
                } catch (RuntimeException failure) {
                    failedEmitters++;
                    if (firstEmitterFailure == null) {
                        firstEmitterFailure = failure;
                    }
                    continue;
                }
                emitter.setSpriteImage(texture);
                emitters.add(new EmitterBinding(emitter, false, texture));
                renderingContext.particles.add(emitter);
            }
            var canonical = new IdentityHashMap<Texture, Optional<Texture>>();
            for (var corona : map.coronas()) {
                var shared = canonical.get(corona.texture());
                if (shared == null) {
                    Texture registered = null;
                    try {
                        registered = scene.registerParticleTexture(
                                record.name, corona.texture(), particleTextureName(corona.textureName()));
                    } catch (RuntimeException failure) {
                        failedEmitters++;
                        if (firstEmitterFailure == null) {
                            firstEmitterFailure = failure;
                        }
                    }
                    shared = Optional.ofNullable(registered);
                    canonical.put(corona.texture(), shared);
                    if (registered != null) {
                        coronaTextures.add(registered);
                    }
                }
                shared.ifPresent(texture -> coronas.add(corona.withTexture(texture)));
            }
            renderingContext.swings.add(
                    record.name,
                    map.swings(),
                    map.turns(),
                    emitters.stream().map(EmitterBinding::emitter).toList());
            renderingContext.coronas.add(record.name, coronas);
            renderingContext.effects.mapLoaded(
                    record.name, emitters.stream().map(EmitterBinding::emitter).toList());
            record.metadata = new InstalledMetadata(
                    record.name,
                    reflections,
                    water,
                    music,
                    ambient,
                    ambientVolumes,
                    fogInfos,
                    List.copyOf(emitters),
                    List.copyOf(coronaTextures));
            renderingContext.tiles.add(record.name);
            if (failedEmitters > 0) {
                LogManager.getLogger(BgfxSceneUploadSink.class)
                        .error(
                                "Skipped {} optional FX emitters while finalizing {}; "
                                        + "the detailed map geometry and {} successful emitters remain resident",
                                failedEmitters,
                                record.name,
                                emitters.size(),
                                firstEmitterFailure);
            }
        } catch (RuntimeException | Error failure) {
            uninstallMetadata(new InstalledMetadata(
                    record.name,
                    reflections,
                    water,
                    music,
                    ambient,
                    ambientVolumes,
                    fogInfos,
                    List.copyOf(emitters),
                    List.copyOf(coronaTextures)));
            throw failure;
        }
    }

    private void uninstallMetadata(MapRecord record) {
        if (record.metadata == null) {
            return;
        }
        uninstallMetadata(record.metadata);
        record.metadata = null;
    }

    private void uninstallMetadata(InstalledMetadata metadata) {
        renderingContext.tiles.remove(metadata.owner);
        renderingContext.geodata.clientSurfaces.remove(metadata.owner);
        renderingContext.spawns.floors.remove(metadata.owner);
        for (var index = metadata.emitters.size() - 1; index >= 0; index--) {
            var binding = metadata.emitters.get(index);
            if (binding.mesh) {
                scene.unregisterMeshEmitter(metadata.owner, binding.emitter);
            } else {
                scene.unregisterParticleTexture(metadata.owner, binding.emitter, binding.texture);
            }
        }
        renderingContext.particles.removeAll(
                metadata.emitters.stream().map(EmitterBinding::emitter).toList());
        renderingContext.coronas.remove(metadata.owner);
        renderingContext.swings.remove(metadata.owner);
        renderingContext.effects.mapRemoved(metadata.owner);
        for (var texture : metadata.coronaTextures) {
            scene.unregisterParticleTexture(metadata.owner, texture);
        }
        renderingContext.scene.removeL2FogInfos(metadata.l2FogInfos);
        renderingContext.scene.removeZoneStateMeshes(metadata.owner);
        renderingContext.scene.removeZoneStateItems(metadata.owner);
        renderingContext.scene.removeSkyZones(metadata.owner);
        renderingContext.scene.removeMapPawns(metadata.owner);
        renderingContext.scene.removeAmbientSounds(metadata.ambientSounds);
        renderingContext.scene.removeAmbientVolumes(metadata.ambientVolumes);
        renderingContext.scene.removeMusicVolumes(metadata.musicVolumes);
        renderingContext.scene.removeWaterVolumes(metadata.waterVolumes);
        renderingContext.scene.removeReflectionBspModels(metadata.reflectionModels);
    }

    private void setCamera(UMap map) {
        var position = map.position();
        renderingContext.camera.setPosition(
                position.getX() + 32768f / 2f, position.getY() + 32768f / 2f, CAPTURE_EYE_Z);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("bgfx scene upload sink is closed");
        }
    }

    private static final class MapRecord {
        private final String name;
        private InstalledMetadata metadata;

        private MapRecord(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("map name cannot be blank");
            }
            this.name = name;
        }
    }

    private record EmitterBinding(ParticleEmitter emitter, boolean mesh, Texture texture) {}

    private record InstalledMetadata(
            String owner,
            List<ReflectionBspModel> reflectionModels,
            List<WaterVolume> waterVolumes,
            List<MusicVolume> musicVolumes,
            List<AmbientSound> ambientSounds,
            List<AmbientVolume> ambientVolumes,
            List<L2FogInfo> l2FogInfos,
            List<EmitterBinding> emitters,
            List<Texture> coronaTextures) {
        private long retainedCpuBytes() {
            var bytes = 0L;
            for (var binding : emitters) {
                bytes = Math.addExact(bytes, binding.emitter.getRetainedCpuBytes());
            }
            return bytes;
        }
    }

    private static Entity terrainProxyEntity(Entity source, int gridStep) {
        var terrain = (TerrainMesh) source.getMesh();
        var proxyGeometry = terrainProxyGeometry(terrain, gridStep);
        var surface = terrain.surface();
        var proxySurface = new Surface(
                surface.type(),
                0,
                proxyGeometry.indices().length,
                terrainProxyMaterial(surface.material(), terrain.vertices()),
                surface.blendMode(),
                surface.uvAnimation(),
                surface.water(),
                surface.materialName(),
                surface.alphaRef(),
                surface.usesVertexColor(),
                surface.depthWrite(),
                surface.modulateStaticLighting2x(),
                surface.reflectionPlane(),
                surface.distanceFog(),
                surface.waterSheetSource(),
                surface.bspDrawSource());
        var proxy = new Entity(new TerrainMesh(
                proxyGeometry.vertices(),
                proxyGeometry.indices(),
                proxySurface,
                terrain.boundingBox(),
                proxyGeometry.lightVariants()));
        proxy.setPosition(source.getPosition());
        proxy.setRotation(source.getRotation());
        proxy.setScale(source.getScale());
        proxy.setWireframe(source.isWireframe());
        proxy.setSourceActor(source.getSourceActor());
        proxy.setSourceAsset(source.getSourceAsset());
        return proxy;
    }

    private static MultiTextureEntityMaterial terrainProxyMaterial(
            EntityMaterial source, List<TerrainMeshVertex> vertices) {
        if (!(source instanceof MultiTextureEntityMaterial material)) {
            throw new IllegalArgumentException("terrain proxy requires a multi-texture material");
        }
        Texture proxy;
        try {
            proxy = bakeTerrainProxyTexture(material.multiTexture(), vertices);
        } catch (RuntimeException malformedTexture) {
            proxy = null;
            for (var entry : material.multiTexture().entries()) {
                if (entry.texture() != null) {
                    proxy = proxyTexture(entry.texture(), TERRAIN_PROXY_FALLBACK_EXTENT);
                    break;
                }
            }
        }
        return new MultiTextureEntityMaterial(new MultiTexture(List.of(new MultiTextureEntry(proxy, null))));
    }

    private static int terrainLayerColor(
            TerrainLayerSampler sampler, int fallback, TerrainGrid grid, int layer, int x, int y, int size) {
        if (sampler == null || grid == null || size < 2) {
            return fallback;
        }
        var sourceX = x * (grid.width - 1f) / (size - 1f);
        var sourceY = y * (grid.rows - 1f) / (size - 1f);
        var x0 = Math.min(grid.width - 1, (int) Math.floor(sourceX));
        var y0 = Math.min(grid.rows - 1, (int) Math.floor(sourceY));
        var x1 = Math.min(grid.width - 1, x0 + 1);
        var y1 = Math.min(grid.rows - 1, y0 + 1);
        var u00 = terrainVertexUv(grid, x0, y0, layer, 0);
        var v00 = terrainVertexUv(grid, x0, y0, layer, 1);
        var u10 = terrainVertexUv(grid, x1, y0, layer, 0);
        var v10 = terrainVertexUv(grid, x1, y0, layer, 1);
        var u01 = terrainVertexUv(grid, x0, y1, layer, 0);
        var v01 = terrainVertexUv(grid, x0, y1, layer, 1);
        var u11 = terrainVertexUv(grid, x1, y1, layer, 0);
        var v11 = terrainVertexUv(grid, x1, y1, layer, 1);
        if (Float.isNaN(u00 + v00 + u10 + v10 + u01 + v01 + u11 + v11)) {
            return fallback;
        }
        var tx = sourceX - x0;
        var ty = sourceY - y0;
        var topU = u00 + (u10 - u00) * tx;
        var topV = v00 + (v10 - v00) * tx;
        var bottomU = u01 + (u11 - u01) * tx;
        var bottomV = v01 + (v11 - v01) * tx;
        return sampler.sample(topU + (bottomU - topU) * ty, topV + (bottomV - topV) * ty);
    }

    private static TerrainLayerSampler terrainLayerSampler(Texture source) {
        var width = Math.max(1, source.width());
        var height = Math.max(1, source.height());
        var selected = 0;
        while (selected + 1 < source.data().size() && Math.max(width, height) > TERRAIN_PROXY_DIFFUSE_SAMPLE_EXTENT) {
            selected++;
            width = Math.max(1, width >> 1);
            height = Math.max(1, height >> 1);
        }
        RuntimeException lastFailure = null;
        for (var level = selected; level >= 0; level--) {
            width = Math.max(1, source.width() >> level);
            height = Math.max(1, source.height() >> level);
            try {
                var decoded = decodeProxyLayer(source, level, width, height);
                return new TerrainLayerSampler(
                        decoded.width(),
                        decoded.height(),
                        decoded.rgba(),
                        source.clampU(),
                        source.clampV(),
                        source.alphaTexture());
            } catch (RuntimeException malformedLevel) {
                lastFailure = malformedLevel;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return null;
    }

    private static TerrainGrid terrainGrid(List<TerrainMeshVertex> vertices) {
        if (vertices == null || vertices.size() < 4) {
            return null;
        }
        var firstY = vertexY(vertices, 0);
        var width = 1;
        while (width < vertices.size() && isSameGridCoordinate(vertexY(vertices, width), firstY)) {
            width++;
        }
        if (width < 2 || width > 4_096) {
            return null;
        }
        var rows = 0;
        while ((long) (rows + 1) * width <= vertices.size() && isMatchingTerrainRow(vertices, rows * width, width)) {
            rows++;
        }
        return rows < 2 ? null : new TerrainGrid(vertices, width, rows);
    }

    private static float terrainVertexUv(TerrainGrid grid, int x, int y, int layer, int component) {
        var index = y * grid.width + x;
        var coordinate = layer * 2 + component;
        if (layer < 0) {
            return Float.NaN;
        }
        if (grid.vertices instanceof TerrainVertexBuffer buffer) {
            return coordinate < buffer.getTextureCoordinateCount()
                    ? buffer.getTerrainTextureCoordinate(index, coordinate)
                    : Float.NaN;
        }
        var coordinates = grid.vertices.get(index).terrainTextureCoords();
        return coordinate < coordinates.length ? coordinates[coordinate] : Float.NaN;
    }

    private record DecodedLayer(int width, int height, byte[] rgba) {}

    private static DecodedLayer decodeProxyLayer(Texture source, int level, int width, int height) {
        var bytes = source.data().get(level);
        var compressed = source.format() == TextureFormat.TEXTURE_DXT1
                || source.format() == TextureFormat.TEXTURE_DXT3
                || source.format() == TextureFormat.TEXTURE_DXT5;
        var extent = Math.max(width, height);
        if (compressed && extent > TERRAIN_PROXY_DIFFUSE_SAMPLE_EXTENT * 2) {
            var step = 4;
            while (extent / step > TERRAIN_PROXY_DIFFUSE_SAMPLE_EXTENT) {
                step *= 2;
            }
            var rgba = DxtMipCodec.decodeToRgbaSubsampled(source.format(), width, height, bytes, step);
            return new DecodedLayer((width + step - 1) / step, (height + step - 1) / step, rgba);
        }
        var rgba = BgfxPreparedTexturePayloads.decodeBaseRgba(new Texture(
                source.format(),
                width,
                height,
                List.of(bytes),
                source.alphaTexture(),
                source.clampU(),
                source.clampV(),
                source.filters()));
        return new DecodedLayer(width, height, rgba);
    }

    private static int averageTerrainLayer(Texture source) {
        RuntimeException lastFailure = null;
        for (var level = source.data().size() - 1; level >= 0; level--) {
            var bytes = source.data().get(level);
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            var width = Math.max(1, source.width() >> level);
            var height = Math.max(1, source.height() >> level);
            try {
                var decoded = decodeProxyLayer(source, level, width, height);
                var rgba = decoded.rgba();
                width = decoded.width();
                height = decoded.height();
                long red = 0L;
                long green = 0L;
                long blue = 0L;
                long alpha = 0L;
                var pixels = rgba.length / 4;
                for (var offset = 0; offset + 3 < rgba.length; offset += 4) {
                    red += rgba[offset] & 0xff;
                    green += rgba[offset + 1] & 0xff;
                    blue += rgba[offset + 2] & 0xff;
                    alpha += rgba[offset + 3] & 0xff;
                }
                if (pixels > 0) {
                    var averagedAlpha = source.alphaTexture() ? (int) ((alpha + pixels / 2L) / pixels) : 255;
                    return averagedAlpha << 24
                            | (int) ((red + pixels / 2L) / pixels) << 16
                            | (int) ((green + pixels / 2L) / pixels) << 8
                            | (int) ((blue + pixels / 2L) / pixels);
                }
            } catch (RuntimeException malformedLevel) {
                lastFailure = malformedLevel;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return 0;
    }

    private static byte[] terrainProxyWeights(Texture source, int size) {
        var format = source.format();
        var compressed = format == TextureFormat.TEXTURE_DXT1
                || format == TextureFormat.TEXTURE_DXT3
                || format == TextureFormat.TEXTURE_DXT5;
        var width = source.width();
        var height = source.height();
        byte[] red;
        var stride = 1;
        if (compressed && width >= size * 4 && height >= size * 4) {
            var step = 4;
            while (width / (step * 2) >= size && height / (step * 2) >= size) {
                step *= 2;
            }
            var rgba = DxtMipCodec.decodeToRgbaSubsampled(
                    format, width, height, source.data().getFirst(), step);
            width = (width + step - 1) / step;
            height = (height + step - 1) / step;
            red = rgba;
            stride = 4;
        } else if (compressed) {
            red = DxtMipCodec.decodeRed(format, width, height, source.data().getFirst());
        } else {
            red = BgfxPreparedTexturePayloads.decodeBaseRgba(source);
            stride = 4;
        }
        var result = new byte[size * size];
        for (var y = 0; y < size; y++) {
            var sourceY = Math.min(height - 1, y * height / size);
            for (var x = 0; x < size; x++) {
                var sourceX = Math.min(width - 1, x * width / size);
                result[y * size + x] = red[(sourceY * width + sourceX) * stride];
            }
        }
        return result;
    }

    private static Texture proxyTexture(Texture source, int maxExtent) {
        if (source == null || source.data() == null || source.data().isEmpty()) {
            return null;
        }
        var width = Math.max(1, source.width());
        var height = Math.max(1, source.height());
        var level = 0;
        while (level + 1 < source.data().size() && Math.max(width, height) > maxExtent) {
            level++;
            width = Math.max(1, width >> 1);
            height = Math.max(1, height >> 1);
        }
        var copy = new Texture(
                source.format(),
                width,
                height,
                List.of(source.data().get(level)),
                source.alphaTexture(),
                source.clampU(),
                source.clampV(),
                source.filters());
        return copy;
    }

    private static TerrainProxyGeometry terrainProxyGeometry(TerrainMesh source, int gridStep) {
        var vertices = source.vertices();
        if (vertices.size() < 4) {
            return new TerrainProxyGeometry(proxyTerrainVertices(vertices), source.indices(), source.lightVariants());
        }
        var firstY = vertexY(vertices, 0);
        var width = 1;
        while (width < vertices.size() && isSameGridCoordinate(vertexY(vertices, width), firstY)) {
            width++;
        }
        if (width < 2 || width > 4_096) {
            return new TerrainProxyGeometry(proxyTerrainVertices(vertices), source.indices(), source.lightVariants());
        }
        var rows = 0;
        while ((long) (rows + 1) * width <= vertices.size() && isMatchingTerrainRow(vertices, rows * width, width)) {
            rows++;
        }
        if (rows < 2) {
            return new TerrainProxyGeometry(proxyTerrainVertices(vertices), source.indices(), source.lightVariants());
        }

        var coreRows = Math.min(rows, width);
        var eastOffset = rows * width;
        var east = hasEastStitch(vertices, eastOffset, coreRows, width);
        var hasSouth = rows > coreRows;
        var southeast = east
                && eastOffset + coreRows < vertices.size()
                && isSameGridCoordinate(vertexX(vertices, eastOffset + coreRows), vertexX(vertices, eastOffset))
                && isSameGridCoordinate(
                        vertexY(vertices, eastOffset + coreRows), vertexY(vertices, (rows - 1) * width));
        var includeEast = east && (!hasSouth || southeast);
        var gridWidth = width + (includeEast ? 1 : 0);
        var xs = sampledAxis(gridWidth, gridStep);
        var ys = sampledAxis(rows, gridStep);
        var sampled = new ArrayList<TerrainMeshVertex>(xs.length * ys.length);
        var originalOrdinals = new int[xs.length * ys.length];
        for (var sampleY = 0; sampleY < ys.length; sampleY++) {
            for (var sampleX = 0; sampleX < xs.length; sampleX++) {
                var x = xs[sampleX];
                var y = ys[sampleY];
                int original;
                if (x < width) {
                    original = y * width + x;
                } else if (y < coreRows) {
                    original = eastOffset + y;
                } else {
                    original = eastOffset + coreRows;
                }
                var destination = sampleY * xs.length + sampleX;
                originalOrdinals[destination] = original;
                sampled.add(proxyTerrainVertex(vertices.get(original)));
            }
        }
        var coverage = terrainCellCoverage(source.indices(), width, rows, coreRows, includeEast);
        var indices = new int[(xs.length - 1) * (ys.length - 1) * 6];
        var cursor = 0;
        for (var y = 0; y < ys.length - 1; y++) {
            for (var x = 0; x < xs.length - 1; x++) {
                if (!isFullyCovered(coverage, gridWidth - 1, xs[x], xs[x + 1], ys[y], ys[y + 1])) {
                    continue;
                }
                var topLeft = y * xs.length + x;
                var topRight = topLeft + 1;
                var bottomLeft = topLeft + xs.length;
                var bottomRight = bottomLeft + 1;
                indices[cursor++] = topLeft;
                indices[cursor++] = topRight;
                indices[cursor++] = bottomRight;
                indices[cursor++] = topLeft;
                indices[cursor++] = bottomRight;
                indices[cursor++] = bottomLeft;
            }
        }
        return new TerrainProxyGeometry(
                List.copyOf(sampled),
                Arrays.copyOf(indices, cursor),
                sampledLightVariants(source.lightVariants(), originalOrdinals, sampled));
    }

    private static byte[] terrainCellCoverage(int[] indices, int width, int rows, int coreRows, boolean includeEast) {
        var gridWidth = width + (includeEast ? 1 : 0);
        var cellWidth = gridWidth - 1;
        var cellHeight = rows - 1;
        var result = new byte[Math.multiplyExact(cellWidth, cellHeight)];
        for (var offset = 0; offset + 2 < indices.length; offset += 3) {
            var a = terrainGridCoordinate(indices[offset], width, rows, coreRows, includeEast);
            var b = terrainGridCoordinate(indices[offset + 1], width, rows, coreRows, includeEast);
            var c = terrainGridCoordinate(indices[offset + 2], width, rows, coreRows, includeEast);
            if (a == null || b == null || c == null) {
                continue;
            }
            var minX = Math.min(a.x, Math.min(b.x, c.x));
            var maxX = Math.max(a.x, Math.max(b.x, c.x));
            var minY = Math.min(a.y, Math.min(b.y, c.y));
            var maxY = Math.max(a.y, Math.max(b.y, c.y));
            if (maxX - minX != 1
                    || maxY - minY != 1
                    || minX < 0
                    || minX >= cellWidth
                    || minY < 0
                    || minY >= cellHeight) {
                continue;
            }
            var cell = minY * cellWidth + minX;
            if (result[cell] < 2) {
                result[cell]++;
            }
        }
        return result;
    }

    private static GridCoordinate terrainGridCoordinate(
            int ordinal, int width, int rows, int coreRows, boolean includeEast) {
        if (ordinal < 0) {
            return null;
        }
        var rowMajorVertices = rows * width;
        if (ordinal < rowMajorVertices) {
            return new GridCoordinate(ordinal % width, ordinal / width);
        }
        if (!includeEast) {
            return null;
        }
        var stitch = ordinal - rowMajorVertices;
        if (stitch < coreRows) {
            return new GridCoordinate(width, stitch);
        }
        if (rows > coreRows && stitch == coreRows) {
            return new GridCoordinate(width, coreRows);
        }
        return null;
    }

    private static boolean isFullyCovered(byte[] coverage, int cellWidth, int minX, int maxX, int minY, int maxY) {
        for (var y = minY; y < maxY; y++) {
            for (var x = minX; x < maxX; x++) {
                if (coverage[y * cellWidth + x] < 2) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float vertexX(List<TerrainMeshVertex> vertices, int index) {
        return vertices instanceof TerrainVertexBuffer buffer
                ? buffer.getPositionX(index)
                : vertices.get(index).position().getX();
    }

    private static float vertexY(List<TerrainMeshVertex> vertices, int index) {
        return vertices instanceof TerrainVertexBuffer buffer
                ? buffer.getPositionY(index)
                : vertices.get(index).position().getY();
    }

    private static float vertexLight(List<TerrainMeshVertex> vertices, int index) {
        return vertices instanceof TerrainVertexBuffer buffer
                ? buffer.getLightValue(index)
                : vertices.get(index).lightValue();
    }

    private static boolean isMatchingTerrainRow(List<TerrainMeshVertex> vertices, int offset, int width) {
        var rowY = vertexY(vertices, offset);
        for (var x = 0; x < width; x++) {
            if (!isSameGridCoordinate(vertexY(vertices, offset + x), rowY)
                    || !isSameGridCoordinate(vertexX(vertices, offset + x), vertexX(vertices, x))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEastStitch(List<TerrainMeshVertex> vertices, int offset, int rows, int width) {
        if (rows < 2 || offset + rows > vertices.size()) {
            return false;
        }
        var eastX = vertexX(vertices, offset);
        if (isSameGridCoordinate(eastX, vertexX(vertices, width - 1))) {
            return false;
        }
        for (var y = 0; y < rows; y++) {
            if (!isSameGridCoordinate(vertexX(vertices, offset + y), eastX)
                    || !isSameGridCoordinate(vertexY(vertices, offset + y), vertexY(vertices, y * width))) {
                return false;
            }
        }
        return true;
    }

    private static int[] sampledAxis(int size, int gridStep) {
        var count = (size - 1 + gridStep - 1) / gridStep + 1;
        var result = new int[count];
        var cursor = 0;
        for (var value = 0; value < size - 1; value += gridStep) {
            result[cursor++] = value;
        }
        result[cursor] = size - 1;
        return result;
    }

    private static float[][] sampledLightVariants(float[][] source, int[] ordinals, List<TerrainMeshVertex> vertices) {
        if (source == null) {
            return null;
        }
        var result = new float[source.length][];
        for (var variant = 0; variant < source.length; variant++) {
            var original = source[variant];
            var values = new float[ordinals.length];
            for (var index = 0; index < ordinals.length; index++) {
                var ordinal = ordinals[index];
                values[index] = original != null && ordinal < original.length
                        ? original[ordinal]
                        : vertexLight(vertices, index);
            }
            result[variant] = values;
        }
        return result;
    }

    private static List<TerrainMeshVertex> proxyTerrainVertices(List<TerrainMeshVertex> source) {
        return source.stream().map(BgfxSceneUploadSink::proxyTerrainVertex).toList();
    }

    private static TerrainMeshVertex proxyTerrainVertex(TerrainMeshVertex source) {
        return new TerrainMeshVertex(
                source.position(),
                source.alphaMapCoord(),
                source.lightValue(),
                new float[] {
                    source.alphaMapCoord().getX(), source.alphaMapCoord().getY()
                },
                source.steadyLightRgb(),
                source.normal());
    }

    private static boolean isSameGridCoordinate(float left, float right) {
        return Math.abs(left - right) <= 0.01f;
    }

    private record TerrainProxyGeometry(List<TerrainMeshVertex> vertices, int[] indices, float[][] lightVariants) {}

    private record TerrainGrid(List<TerrainMeshVertex> vertices, int width, int rows) {}

    private record TerrainLayerSampler(
            int width, int height, byte[] rgba, boolean clampU, boolean clampV, boolean useDiffuseAlpha) {
        private int sample(float u, float v) {
            var sourceX = (double) u * width - 0.5;
            var sourceY = (double) v * height - 0.5;
            var x0 = (int) Math.floor(sourceX);
            var y0 = (int) Math.floor(sourceY);
            var tx = sourceX - x0;
            var ty = sourceY - y0;
            var x1 = x0 + 1;
            var y1 = y0 + 1;
            x0 = wrapTexel(x0, width, clampU);
            x1 = wrapTexel(x1, width, clampU);
            y0 = wrapTexel(y0, height, clampV);
            y1 = wrapTexel(y1, height, clampV);
            var alpha = useDiffuseAlpha ? interpolate(x0, x1, y0, y1, tx, ty, 3) : 255;
            return alpha << 24
                    | interpolate(x0, x1, y0, y1, tx, ty, 0) << 16
                    | interpolate(x0, x1, y0, y1, tx, ty, 1) << 8
                    | interpolate(x0, x1, y0, y1, tx, ty, 2);
        }

        private int interpolate(int x0, int x1, int y0, int y1, double tx, double ty, int channel) {
            var top = value(x0, y0, channel) + (value(x1, y0, channel) - value(x0, y0, channel)) * tx;
            var bottom = value(x0, y1, channel) + (value(x1, y1, channel) - value(x0, y1, channel)) * tx;
            return (int) Math.clamp(Math.round(top + (bottom - top) * ty), 0L, 255L);
        }

        private int value(int x, int y, int channel) {
            return rgba[(y * width + x) * 4 + channel] & 0xff;
        }

        private static int wrapTexel(int coordinate, int extent, boolean clamp) {
            return clamp ? Math.clamp(coordinate, 0, extent - 1) : Math.floorMod(coordinate, extent);
        }
    }

    private record GridCoordinate(int x, int y) {}
}
