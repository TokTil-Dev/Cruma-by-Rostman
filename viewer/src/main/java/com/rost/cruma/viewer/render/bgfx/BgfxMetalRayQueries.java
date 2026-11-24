package com.rost.cruma.viewer.render.bgfx;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA16F;

import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.StaticMeshLod;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderSettings;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.api.StableRenderId;
import com.rost.cruma.viewer.render.bgfx.BgfxMetalRayQueryBridge.BuildState;
import com.rost.cruma.viewer.render.bgfx.BgfxMetalRayQueryBridge.Probe;
import com.rost.cruma.viewer.render.bgfx.BgfxMetalRayQueryBridge.Session;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Kind;
import glm_.vec3.Vec3;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class BgfxMetalRayQueries implements AutoCloseable {
    static final String SAME_FRAME_BASELINE_PROPERTY = "cruma.viewer.metal_ray_query_same_frame";
    static final String SAME_FRAME_BASELINE_ENV = "CRUMA_VIEWER_METAL_RAY_QUERY_SAME_FRAME";

    private static final int VISIBILITY_SLOTS = 4;

    private static final int BGFX_RESOURCE_RETIRE_FRAMES = 4;

    private static final float PRIMARY_RAY_DISTANCE = 2_000_000f;
    private static final float SUN_RAY_DISTANCE = 160_000f;
    private static final float LOCAL_TLAS_RADIUS = 32_000f;
    private static final float GI_TLAS_RADIUS = 640_000f;

    private static final float MAX_REPROJECTION_TRANSLATION = 32f;

    private static final float MIN_REPROJECTION_FORWARD_DOT = (float) Math.cos(Math.toRadians(0.35));

    private static final Logger log = LogManager.getLogger(BgfxMetalRayQueries.class);
    private final Map<Long, BgfxSceneObject> giMoving = new HashMap<>();

    private final RenderState rendering;
    private final BgfxDevice device;
    private final BgfxResourceRegistry resources;
    private final Session session;

    private final boolean sameFrameBaseline = isSameFrameBaselineRequested();

    private final Set<Long> geometries = new HashSet<>();
    private final BgfxRayDataGate gate = new BgfxRayDataGate();
    private final Map<Long, ByteBuffer> giPending = new LinkedHashMap<>();
    private int giPrepared;
    private long giMaterialBytes, giRevision;
    private boolean giSceneWasReady;

    private final Map<Long, Map<Long, Integer>> placementsByOwner = new HashMap<>();

    private final BgfxTexture[] visibilityTextures = new BgfxTexture[VISIBILITY_SLOTS];
    private final ArrayDeque<SubmittedPose> submittedPoses = new ArrayDeque<>();
    private final ArrayDeque<RetiredTexture> retiredTextures = new ArrayDeque<>();
    private final float[] inverseViewProjection = new float[16];
    private final float[] rayBasis = new float[16];
    private BuildState buildState = BuildState.COLLECTING;
    private BuildState loggedBuildState;
    private long importedGeneration;
    private long publishedSerial;
    private Snapshot displayedSnapshot = Snapshot.disabled();
    private long preparedFrameNumber = Long.MIN_VALUE;
    private PreparedFrame preparedFrame;
    private boolean activeLogged;
    private String loggedRaySunPath = "";
    private long registeredPlacementGroups;
    private long registeredPlacementInstances;
    private Map<Long, Integer> editorRetiredGeometries;
    private float focusX = Float.NaN;
    private float focusY = Float.NaN;
    private long sameFrameWaitNanos;
    private boolean closed;

    private BgfxMetalRayQueries(RenderState rendering, BgfxDevice device, Session session) {
        this.rendering = rendering;
        this.device = device;
        this.resources = device.getResources();
        this.session = session;
    }

    static BgfxMetalRayQueries create(BgfxDevice device, RenderState rendering) {
        Probe probe;
        if (!device.getCapabilities().renderer().equalsIgnoreCase("Metal")) {
            probe = new Probe(false, "", "hardware ray queries currently require the Metal renderer");
        } else if (!device.getCapabilities().externalTextures()) {
            probe = new Probe(false, "", "Metal renderer does not expose bgfx external textures");
        } else {
            probe = BgfxMetalRayQueryBridge.probe(device.getNativeRendererContext());
        }
        rendering.stats.customRayTracingAvailable = probe.available();
        rendering.stats.customRayTracingActive = false;
        rendering.stats.customRayTracingReason = probe.reason();
        log.info("Ray query capability: available={} reason='{}'", probe.available(), probe.reason());
        if (!probe.available()) {
            return new BgfxMetalRayQueries(rendering, device, null);
        }
        Session session;
        try {
            session = BgfxMetalRayQueryBridge.create(device.getNativeRendererContext());
        } catch (RuntimeException failure) {
            var reason = "Metal native context creation failed: " + messageOf(failure);
            rendering.stats.customRayTracingAvailable = false;
            rendering.stats.customRayTracingReason = reason;
            log.warn("{}", reason, failure);
            return new BgfxMetalRayQueries(rendering, device, null);
        }
        if (session == null) {
            rendering.stats.customRayTracingAvailable = false;
            rendering.stats.customRayTracingReason =
                    "Metal device supports ray tracing, but native context returned a null handle";
        }
        if (session != null && isSameFrameBaselineRequested()) {
            log.warn("Same-frame reference mode enabled; " + "each visibility dispatch blocks the render thread");
        }
        return new BgfxMetalRayQueries(rendering, device, session);
    }

    static boolean usesRays(RenderSettings settings) {
        return settings.isCustomLightingEnabled()
                && (settings.getCustomLightingModel() == 2
                        || settings.isCustomRayTracedSunReferenceEnabled() && settings.isCustomSunShadowsEnabled());
    }

    static int uvOffset(Kind kind) {
        return switch (kind) {
            case ENTITY, STATIC_MESH_TANGENT -> 44;
            case STATIC_MESH, STATIC_MESH_COLOR -> 20;
            case TERRAIN -> 24;
        };
    }

    static boolean isPoseCompatible(
            float[] capturedCamera,
            float[] capturedForward,
            float cameraX,
            float cameraY,
            float cameraZ,
            float forwardX,
            float forwardY,
            float forwardZ) {
        if (capturedCamera == null
                || capturedCamera.length < 3
                || capturedForward == null
                || capturedForward.length < 3) {
            return false;
        }
        var dx = cameraX - capturedCamera[0];
        var dy = cameraY - capturedCamera[1];
        var dz = cameraZ - capturedCamera[2];
        var maxTranslationSquared = MAX_REPROJECTION_TRANSLATION * MAX_REPROJECTION_TRANSLATION;
        if (dx * dx + dy * dy + dz * dz > maxTranslationSquared) {
            return false;
        }
        var capturedLength = (float) Math.sqrt(capturedForward[0] * capturedForward[0]
                + capturedForward[1] * capturedForward[1]
                + capturedForward[2] * capturedForward[2]);
        var currentLength = (float) Math.sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ);
        if (capturedLength < 0.0001f || currentLength < 0.0001f) {
            return false;
        }
        var dot = (capturedForward[0] * forwardX + capturedForward[1] * forwardY + capturedForward[2] * forwardZ)
                / (capturedLength * currentLength);
        return dot >= MIN_REPROJECTION_FORWARD_DOT;
    }

    static boolean isSameFrameBaselineRequested() {
        if (Boolean.getBoolean(SAME_FRAME_BASELINE_PROPERTY)) {
            return true;
        }
        var environment = System.getenv(SAME_FRAME_BASELINE_ENV);
        if (environment == null) {
            return false;
        }
        var value = environment.trim();
        return value.equals("1")
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on");
    }

    boolean isAvailable() {
        return session != null && !closed;
    }

    boolean isCollecting() {
        return gate.isCollecting();
    }

    long getPlacementInstances() {
        return registeredPlacementInstances;
    }

    void followOptions() {
        if (isAvailable() && usesRays(rendering.settings) && gate.ask()) {
            log.info("An option needs ray data, and maps bring it from now on");
        }
    }

    void leftOut() {
        if (isAvailable()) {
            gate.leftOut();
        }
    }

    boolean takeReload() {
        return gate.takeReload();
    }

    void register(
            Entity source,
            BgfxSceneGeometry.Prepared geometry,
            BgfxSceneObject.Prepared object,
            boolean geometryCreated,
            ByteBuffer giColors) {
        register(source, geometry, object, geometryCreated, giColors, null);
    }

    void register(
            Entity source,
            BgfxSceneGeometry.Prepared geometry,
            BgfxSceneObject.Prepared object,
            boolean geometryCreated,
            ByteBuffer giColors,
            BgfxGiMaterial material) {
        if (!isAvailable() || source == null || object == null || geometryCreated && geometry == null) {
            return;
        }
        if (BgfxSceneUploadSink.isTerrainProxyOwner(object.owner())) {
            return;
        }
        if (!gate.isCollecting()) {
            gate.leftOut();
            return;
        }
        var lod = source.getStaticMeshLod();
        try {
            if (geometry != null
                    && !geometries.contains(geometry.stableId())
                    && registerGeometry(source, geometry, giColors, material)) {
                geometries.add(geometry.stableId());
            }
            if (!geometries.contains(object.geometryStableId())) {
                return;
            }
            registerPlacement(object.owner(), object.stableId(), object, lod);
        } catch (RuntimeException failure) {
            disable("Metal scene ingestion failed: " + messageOf(failure), failure);
        }
    }

    boolean registerDecorations(
            String owner, Entity source, BgfxSceneGeometry.Prepared geometry, BgfxSceneObject.Prepared object) {
        if (!isAvailable() || owner == null || owner.isBlank() || !gate.isCollecting()) {
            return false;
        }
        try {
            if (!geometries.contains(geometry.stableId()) && registerGeometry(source, geometry, null, null)) {
                geometries.add(geometry.stableId());
            }
            if (!geometries.contains(object.geometryStableId())) {
                return false;
            }
            registerPlacement(
                    owner, StableRenderId.builder("decoration-ray").put(owner).build(), object, null);
            return true;
        } catch (RuntimeException failure) {
            disable("Metal decoration ingestion failed: " + messageOf(failure), failure);
            return false;
        }
    }

    void removeOwner(String owner) {
        if (!isAvailable() || owner == null || owner.isBlank()) {
            return;
        }
        try {
            var id = ownerId(owner);
            session.removeOwner(id);
            giMoving.values().removeIf(object -> object.getOwner().equals(owner));
            var removed = placementsByOwner.remove(id);
            if (removed != null) {
                registeredPlacementGroups -= removed.size();
                for (var instanceCount : removed.values()) {
                    registeredPlacementInstances -= instanceCount;
                }
            }
        } catch (RuntimeException failure) {
            disable("Metal scene eviction failed: " + messageOf(failure), failure);
        }
    }

    BuildState pollBuild(boolean requested) {
        if (!isAvailable() || !requested) {
            rendering.stats.customRayTracingActive = false;
            return buildState;
        }
        try {
            buildState = session.pollBuild();
            if (buildState != loggedBuildState) {
                loggedBuildState = buildState;
                log.debug(
                        "Ray query build: state={} geometries={} "
                                + "registeredGroups={} registeredInstances={} selectedInstances={} focus={},{}",
                        buildState,
                        geometries.size(),
                        registeredPlacementGroups,
                        registeredPlacementInstances,
                        session.getInstanceCount(),
                        focusX,
                        focusY);
            }
            if (buildState == BuildState.FAILED) {
                var error = session.getLastError();
                disable(error.isBlank() ? "Metal BLAS/TLAS build failed" : error, null);
                return BuildState.FAILED;
            }
            rendering.stats.customRayTracingReason = switch (buildState) {
                case COLLECTING -> "Metal scene is collecting ray geometry";
                case BUILDING_BLAS -> "Building Metal bottom-level acceleration structures";
                case BUILDING_TLAS -> "Building camera-local Metal instance acceleration structure";
                case READY -> "Metal local TLAS ready: " + session.getInstanceCount() + " instances";
                case FAILED -> rendering.stats.customRayTracingReason;
            };
            rendering.stats.customRayTracingActive = false;
            return buildState;
        } catch (RuntimeException failure) {
            disable("Metal BLAS/TLAS polling failed: " + messageOf(failure), failure);
            return BuildState.FAILED;
        }
    }

    void prepare(Camera camera, Extent extent, boolean raySunRequested, float timeOfDay, Vec3 sunDirection) {
        var frameNumber = device.getStats().frameNumber();
        if (preparedFrameNumber == frameNumber) {
            return;
        }
        preparedFrameNumber = frameNumber;
        preparedFrame = null;
        if (!isAvailable() || !raySunRequested || camera == null || camera.isOrtho() || extent.isZero()) {
            rendering.stats.customRayTracingActive = false;
            return;
        }
        try {
            var position = camera.getPosition();
            if (!session.setFocus(
                    position.getX(),
                    position.getY(),
                    rendering.isGlobalIlluminationEffective() ? GI_TLAS_RADIUS : LOCAL_TLAS_RADIUS)) {
                disable("Metal local TLAS rejected the camera focus", null);
                return;
            }
            focusX = position.getX();
            focusY = position.getY();
            if (pollBuild(true) != BuildState.READY) {
                return;
            }

            session.pollVisibility();
            session.pollVisibility();
            synchronizeImports();
            publishedSnapshot();

            camera.copyInverseViewProjection(inverseViewProjection);
            camera.copyRayBasis(rayBasis);
            var forward = camera.getForwardDir();
            try (var arena = Arena.ofConfined()) {
                var inverse = arena.allocate(JAVA_FLOAT, 16);
                var basis = arena.allocate(JAVA_FLOAT, 16);
                var cameraParameters = arena.allocate(JAVA_FLOAT, 5);
                var sun = arena.allocate(JAVA_FLOAT, 4);
                for (var index = 0; index < inverseViewProjection.length; index++) {
                    inverse.setAtIndex(JAVA_FLOAT, index, inverseViewProjection[index]);
                    basis.setAtIndex(JAVA_FLOAT, index, rayBasis[index]);
                }
                cameraParameters.setAtIndex(JAVA_FLOAT, 0, position.getX());
                cameraParameters.setAtIndex(JAVA_FLOAT, 1, position.getY());
                cameraParameters.setAtIndex(JAVA_FLOAT, 2, position.getZ());
                cameraParameters.setAtIndex(JAVA_FLOAT, 3, PRIMARY_RAY_DISTANCE);
                cameraParameters.setAtIndex(JAVA_FLOAT, 4, Math.max(1f, camera.getNear()));
                sun.setAtIndex(JAVA_FLOAT, 0, sunDirection.getX());
                sun.setAtIndex(JAVA_FLOAT, 1, sunDirection.getY());
                sun.setAtIndex(JAVA_FLOAT, 2, sunDirection.getZ());
                sun.setAtIndex(JAVA_FLOAT, 3, SUN_RAY_DISTANCE);
                var dispatched =
                        session.dispatch(inverse, basis, cameraParameters, sun, extent.width(), extent.height());
                if (dispatched) {
                    var previousSerial = submittedPoses.isEmpty()
                            ? 0L
                            : submittedPoses.peekLast().serial();
                    var expectedSerial = Math.max(session.getPublishedSerial(), previousSerial) + 1L;
                    submittedPoses.addLast(new SubmittedPose(
                            expectedSerial,
                            new float[] {forward.getX(), forward.getY(), forward.getZ()},
                            inverseViewProjection,
                            rayBasis));
                }
                preparedFrame = new PreparedFrame(
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        forward.getX(),
                        forward.getY(),
                        forward.getZ(),
                        dispatched);
            }
            synchronizeImports();
        } catch (RuntimeException failure) {
            disable("Metal visibility preparation failed: " + messageOf(failure), failure);
        }
    }

    Snapshot update(Camera camera, Extent extent, boolean raySunRequested, float timeOfDay, Vec3 sunDirection) {
        var frameNumber = device.getStats().frameNumber();
        if (preparedFrameNumber != frameNumber) {
            prepare(camera, extent, raySunRequested, timeOfDay, sunDirection);
        }
        var prepared = preparedFrame;
        preparedFrame = null;
        if (prepared == null) {
            rendering.stats.customRayTracingActive = false;
            logRaySunPath(raySunRequested, "raster-fallback", rendering.stats.customRayTracingReason);
            return Snapshot.disabled();
        }
        try {
            var currentFrameCompleted = false;
            var gpuEventWait = false;
            if (prepared.dispatched()) {
                if (session.isBgfxWaitAvailable()) {
                    var gpuFenceArmed = session.armBgfxWait();
                    if (!sameFrameBaseline) {
                        gpuEventWait = gpuFenceArmed;
                        currentFrameCompleted = gpuEventWait;
                    }
                }
                if (!currentFrameCompleted) {
                    var waitStart = System.nanoTime();
                    if (!session.waitVisibility()) {
                        var error = session.getLastError();
                        disable(error.isBlank() ? "Metal same-frame visibility did not complete" : error, null);
                        return Snapshot.disabled();
                    }
                    sameFrameWaitNanos = System.nanoTime() - waitStart;
                    currentFrameCompleted = true;
                }
            }
            session.pollVisibility();
            synchronizeImports();
            var snapshot = publishedSnapshot();
            var poseMatched = snapshot.available()
                    && isPoseCompatible(
                            snapshot.camera(),
                            snapshot.forward(),
                            prepared.cameraX(),
                            prepared.cameraY(),
                            prepared.cameraZ(),
                            prepared.forwardX(),
                            prepared.forwardY(),
                            prepared.forwardZ());
            var usableSnapshot = poseMatched ? snapshot.withSunVisibility(currentFrameCompleted) : Snapshot.disabled();
            rendering.stats.customRayTracingActive = usableSnapshot.sunVisibilityAvailable();
            if (usableSnapshot.available()) {
                rendering.stats.customRayTracingReason = "Metal same-frame area-sun reference active: "
                        + session.getInstanceCount()
                        + " instances"
                        + (gpuEventWait
                                ? ", GPU shared-event wait"
                                : ", late CPU wait " + Math.round(sameFrameWaitNanos / 10_000f) / 100f + " ms");
                if (!activeLogged) {
                    activeLogged = true;
                    log.info(
                            "Ray query visibility active: serial={} instances={} " + "texture={}x{} published={}x{}",
                            snapshot.serial(),
                            session.getInstanceCount(),
                            session.getTextureWidth(),
                            session.getTextureHeight(),
                            session.getPublishedWidth(),
                            session.getPublishedHeight());
                }
            } else if (snapshot.available()) {
                rendering.stats.customRayTracingReason =
                        "Waiting for Metal visibility matching the current camera pose";
            }
            if (usableSnapshot.sunVisibilityAvailable()) {
                logRaySunPath(true, "hardware", rendering.stats.customRayTracingReason);
            } else {
                var reason = !prepared.dispatched()
                        ? "visibility texture ring busy. Using raster CSM"
                        : !currentFrameCompleted
                                ? "current-pose visibility completion unavailable. Using raster CSM"
                                : !poseMatched
                                        ? "current-pose visibility rejected. Using raster CSM"
                                        : "sun visibility payload unavailable. Using raster CSM";
                logRaySunPath(true, "raster-fallback", reason);
            }
            return usableSnapshot;
        } catch (RuntimeException failure) {
            disable("Metal visibility acquisition failed: " + messageOf(failure), failure);
            return Snapshot.disabled();
        }
    }

    Session getGiSession() {
        return isAvailable() ? session : null;
    }

    long getGiRevision() {
        return giRevision;
    }

    long getGiMaterialBytes() {
        return giMaterialBytes;
    }

    int getGiPreparedGeometries() {
        return giPrepared;
    }

    void queueGiMovement(BgfxSceneObject object) {
        if (isAvailable() && gate.isCollecting()) {
            giMoving.put(object.getStableId(), object);
        }
    }

    void releaseEditorGeometry(BgfxSceneGeometry geometry) {
        if (!isAvailable() || !session.supportsGeometryRetirement() || !geometries.contains(geometry.getStableId())) {
            return;
        }
        if (editorRetiredGeometries == null) {
            editorRetiredGeometries = new HashMap<>();
        }
        editorRetiredGeometries.put(geometry.getStableId(), geometry.getIndexCount());
        pollEditorRetirements();
    }

    void pollEditorRetirements() {
        if (editorRetiredGeometries == null || editorRetiredGeometries.isEmpty() || !isAvailable()) {
            return;
        }
        try {
            for (var iterator = editorRetiredGeometries.entrySet().iterator(); iterator.hasNext(); ) {
                var retired = iterator.next();
                if (session.removeGeometry(retired.getKey())) {
                    geometries.remove(retired.getKey());
                    if (giPending.remove(retired.getKey()) == null) {
                        giMaterialBytes =
                                Math.max(0, giMaterialBytes - (long) retired.getValue() / 3 * 8 * Float.BYTES);
                        giPrepared = Math.max(0, giPrepared - 1);
                    }
                    iterator.remove();
                    giRevision++;
                }
            }
        } catch (RuntimeException failure) {
            disable("Metal editor geometry retirement failed: " + messageOf(failure), failure);
        }
    }

    boolean hasPendingGiMovement() {
        return !giMoving.isEmpty();
    }

    void captureGiMovement() {
        if (!isAvailable() || giMoving.isEmpty()) {
            return;
        }
        try (var arena = Arena.ofConfined()) {
            for (var object : giMoving.values()) {
                if (!geometries.contains(object.getGeometry().getStableId())) {
                    continue;
                }
                var records = object.getInstanceRecords();
                var matrices = arena.allocate(JAVA_FLOAT, object.getInstanceCount() * 16L);
                var buffer =
                        matrices.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer();
                if (object.getTerrainModel() != null) {
                    buffer.put(object.getTerrainModel(), 0, 16);
                } else {
                    for (int i = 0; i < object.getInstanceCount(); i++) {
                        buffer.put(records, i * BgfxSceneObject.INSTANCE_FLOATS, 16);
                    }
                }
                var lod = object.getStaticMeshLod();
                if (!session.registerInstances(
                        ownerId(object.getOwner()),
                        object.getStableId(),
                        object.getGeometry().getStableId(),
                        matrices,
                        object.getInstanceCount(),
                        lod == null ? -1 : lod.level(),
                        lod == null ? 0 : lod.distance1(),
                        lod == null ? 0 : lod.distance2())) {
                    throw new IllegalStateException("GI actor transform update failed");
                }
                var placements =
                        placementsByOwner.computeIfAbsent(ownerId(object.getOwner()), ignored -> new HashMap<>());
                var previous = placements.put(object.getStableId(), object.getInstanceCount());
                if (previous == null) {
                    registeredPlacementGroups++;
                } else {
                    registeredPlacementInstances -= previous;
                }
                registeredPlacementInstances += object.getInstanceCount();
            }
        }
        giMoving.clear();
        giRevision++;
    }

    boolean prepareGi(float focusX, float focusY) {
        if (!isAvailable()) {
            return false;
        }
        if (!prepareGiMaterials()) {
            return false;
        }
        boolean ready = session.setFocus(focusX, focusY, GI_TLAS_RADIUS) && pollBuild(true) == BuildState.READY;
        if (!ready && giSceneWasReady) {
            giRevision++;
        }
        giSceneWasReady = ready;
        return ready;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        giPending.clear();
        giMoving.clear();
        if (editorRetiredGeometries != null) {
            editorRetiredGeometries.clear();
        }
        rendering.stats.customRayTracingActive = false;
        RuntimeException failure = null;
        try {
            closeVisibilityTextures();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (session != null) {
            try {
                device.releaseAfterShutdown(session);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    record Snapshot(
            boolean available,
            boolean sunVisibilityAvailable,
            BgfxTexture texture,
            float uvScaleX,
            float uvScaleY,
            int slot,
            long serial,
            float[] viewProjection,
            float[] inverseViewProjection,
            float[] camera,
            float[] forward,
            float[] rayBasis) {
        Snapshot {
            viewProjection = viewProjection == null ? new float[16] : viewProjection.clone();
            inverseViewProjection = inverseViewProjection == null ? new float[16] : inverseViewProjection.clone();
            camera = camera == null ? new float[4] : camera.clone();
            forward = forward == null ? new float[3] : forward.clone();
            rayBasis = rayBasis == null ? new float[16] : rayBasis.clone();
        }

        static Snapshot disabled() {
            return new Snapshot(false, false, null, 1f, 1f, -1, 0L, null, null, null, null, null);
        }

        Snapshot withSunVisibility(boolean value) {
            return new Snapshot(
                    available,
                    value,
                    texture,
                    uvScaleX,
                    uvScaleY,
                    slot,
                    serial,
                    viewProjection,
                    inverseViewProjection,
                    camera,
                    forward,
                    rayBasis);
        }

        Snapshot withTexture(BgfxTexture replacement, float replacementUvScaleX, float replacementUvScaleY) {
            if (!available || replacement == null) {
                return this;
            }
            return new Snapshot(
                    true,
                    sunVisibilityAvailable,
                    replacement,
                    replacementUvScaleX,
                    replacementUvScaleY,
                    slot,
                    serial,
                    viewProjection,
                    inverseViewProjection,
                    camera,
                    forward,
                    rayBasis);
        }
    }

    private record SubmittedPose(long serial, float[] forward, float[] inverseViewProjection, float[] rayBasis) {
        SubmittedPose {
            forward = forward == null ? new float[3] : forward.clone();
            inverseViewProjection = inverseViewProjection == null ? new float[16] : inverseViewProjection.clone();
            rayBasis = rayBasis == null ? new float[16] : rayBasis.clone();
        }
    }

    private record PreparedFrame(
            float cameraX,
            float cameraY,
            float cameraZ,
            float forwardX,
            float forwardY,
            float forwardZ,
            boolean dispatched) {}

    private record RetiredTexture(BgfxTexture texture, long retireFrame) {}

    private void synchronizeImports() {
        var generation = session.getTextureGeneration();
        if (generation == 0) {
            return;
        }
        if (generation != importedGeneration) {
            var width = session.getTextureWidth();
            var height = session.getTextureHeight();
            if (width < 1 || height < 1) {
                throw new IllegalStateException("native visibility texture has invalid dimensions");
            }
            var imported = new BgfxTexture[VISIBILITY_SLOTS];
            try {
                for (var slot = 0; slot < imported.length; slot++) {
                    imported[slot] = BgfxTexture.createExternal2D(
                            resources,
                            "metal-ray-visibility-" + generation + '-' + slot,
                            width,
                            height,
                            BGFX_TEXTURE_FORMAT_RGBA16F,
                            0,
                            session.getTexturePointer(slot));
                }
            } catch (RuntimeException failure) {
                for (var texture : imported) {
                    if (texture == null) {
                        continue;
                    }
                    try {
                        texture.close();
                    } catch (RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                throw failure;
            }
            retireVisibilityTextures(device.getStats().frameNumber());
            System.arraycopy(imported, 0, visibilityTextures, 0, imported.length);
            importedGeneration = generation;
        }
    }

    private Snapshot publishedSnapshot() {
        var currentFrame = device.getStats().frameNumber();
        collectRetiredTextures(currentFrame);
        var serial = session.getPublishedSerial();
        var slot = session.getPublishedSlot();
        if (serial == 0 || serial == publishedSerial) {
            return displayedSnapshot;
        }
        if (slot < 0 || slot >= VISIBILITY_SLOTS || visibilityTextures[slot] == null) {
            return displayedSnapshot;
        }
        var textureWidth = session.getTextureWidth();
        var textureHeight = session.getTextureHeight();
        var width = session.getPublishedWidth();
        var height = session.getPublishedHeight();
        if (textureWidth < 1
                || textureHeight < 1
                || width < 1
                || height < 1
                || width > textureWidth
                || height > textureHeight) {
            return displayedSnapshot;
        }
        var viewProjection = new float[16];
        var camera = new float[4];
        try (var arena = Arena.ofConfined()) {
            var nativeViewProjection = arena.allocate(JAVA_FLOAT, 16);
            var nativeCamera = arena.allocate(JAVA_FLOAT, 4);
            if (!session.copyPublishedReprojection(nativeViewProjection, nativeCamera)) {
                return displayedSnapshot;
            }
            for (var index = 0; index < viewProjection.length; index++) {
                viewProjection[index] = nativeViewProjection.getAtIndex(JAVA_FLOAT, index);
            }
            for (var index = 0; index < camera.length; index++) {
                camera[index] = nativeCamera.getAtIndex(JAVA_FLOAT, index);
            }
        }
        SubmittedPose pose = null;
        while (!submittedPoses.isEmpty() && submittedPoses.peekFirst().serial() <= serial) {
            var candidate = submittedPoses.removeFirst();
            if (candidate.serial() == serial) {
                pose = candidate;
            }
        }
        if (pose == null) {
            return displayedSnapshot;
        }
        publishedSerial = serial;
        displayedSnapshot = new Snapshot(
                true,
                false,
                visibilityTextures[slot],
                (float) width / textureWidth,
                (float) height / textureHeight,
                slot,
                serial,
                viewProjection,
                pose.inverseViewProjection(),
                camera,
                pose.forward(),
                pose.rayBasis());
        return displayedSnapshot;
    }

    private void collectRetiredTextures(long currentFrame) {
        RuntimeException failure = null;
        while (!retiredTextures.isEmpty() && retiredTextures.peekFirst().retireFrame() <= currentFrame) {
            try {
                retiredTextures.removeFirst().texture().close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void retireVisibilityTextures(long currentFrame) {
        displayedSnapshot = Snapshot.disabled();
        for (var slot = 0; slot < visibilityTextures.length; slot++) {
            var texture = visibilityTextures[slot];
            visibilityTextures[slot] = null;
            if (texture != null) {
                retiredTextures.addLast(new RetiredTexture(texture, currentFrame + BGFX_RESOURCE_RETIRE_FRAMES));
            }
        }
        publishedSerial = 0L;
    }

    private boolean registerGeometry(
            Entity source, BgfxSceneGeometry.Prepared geometry, ByteBuffer giColors, BgfxGiMaterial material) {
        var accepted = geometry.primitives().stream()
                .filter(primitive -> primitive.rayCoverage().eligible())
                .toList();
        if (accepted.isEmpty()) {
            return false;
        }
        try (var arena = Arena.ofConfined()) {
            var ranges = arena.allocate(JAVA_INT, accepted.size() * 8L);
            long alphaByteCount = 0L;
            for (var primitive : accepted) {
                alphaByteCount =
                        Math.addExact(alphaByteCount, primitive.rayCoverage().retainedBytes());
            }
            if (alphaByteCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("ray opacity payload exceeds uint32 capacity");
            }
            var alphaBytes = alphaByteCount == 0L ? MemorySegment.NULL : arena.allocate(alphaByteCount, 1L);
            var alphaTarget = alphaByteCount == 0L ? null : alphaBytes.asByteBuffer();
            var alphaOffset = 0;
            for (var index = 0; index < accepted.size(); index++) {
                var primitive = accepted.get(index);
                var coverage = primitive.rayCoverage();
                var base = index * 8L;
                ranges.setAtIndex(JAVA_INT, base, primitive.indexOffset());
                ranges.setAtIndex(JAVA_INT, base + 1L, primitive.indexCount());
                ranges.setAtIndex(JAVA_INT, base + 2L, coverage.masked() ? alphaOffset : -1);
                ranges.setAtIndex(JAVA_INT, base + 3L, coverage.width());
                ranges.setAtIndex(JAVA_INT, base + 4L, coverage.height());
                ranges.setAtIndex(JAVA_INT, base + 5L, coverage.flags());
                ranges.setAtIndex(JAVA_INT, base + 6L, Float.floatToRawIntBits(coverage.cutoff()));
                ranges.setAtIndex(JAVA_INT, base + 7L, 0);
                if (coverage.masked()) {
                    alphaTarget.put(coverage.alpha());
                    alphaOffset = Math.addExact(alphaOffset, coverage.alpha().length);
                }
            }
            if (!session.registerGeometry(
                    geometry.stableId(),
                    MemorySegment.ofBuffer(geometry.vertexData()),
                    geometry.vertexCount(),
                    strideOf(geometry.kind()),
                    uvOffset(geometry.kind()),
                    MemorySegment.ofBuffer(geometry.indexData()),
                    geometry.indexCount(),
                    ranges,
                    accepted.size(),
                    alphaBytes,
                    (int) alphaByteCount)) {
                geometries.remove(geometry.stableId());
                throw new IllegalStateException("native geometry registration returned false");
            }
            giPending.put(
                    geometry.stableId(),
                    giColors != null
                            ? giColors
                            : material != null
                                    ? material.buildColors(source, geometry)
                                    : BgfxGiMaterial.prepare(source, geometry));
        }
        return true;
    }

    private boolean prepareGiMaterials() {
        var pending = giPending.entrySet().iterator();
        for (int count = 0; count < 32 && pending.hasNext(); count++) {
            var entry = pending.next();
            var colors = entry.getValue();
            if (!session.uploadGiMaterials(
                    entry.getKey(), MemorySegment.ofBuffer(colors), colors.remaining() / (8 * Float.BYTES))) {
                return false;
            }
            giMaterialBytes += colors.remaining();
            giPrepared++;
            giRevision++;
            pending.remove();
        }
        return giPending.isEmpty();
    }

    private void registerPlacement(String owner, long placement, BgfxSceneObject.Prepared object, StaticMeshLod lod) {
        var records = object.instanceRecords();
        var terrain = object.terrainModel();
        var instanceCount = terrain == null ? records.length / BgfxSceneObject.INSTANCE_FLOATS : 1;
        try (var arena = Arena.ofConfined()) {
            var matrices = arena.allocate(JAVA_FLOAT, Math.multiplyExact(instanceCount, 16L));
            var buffer = matrices.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer();
            if (terrain != null) {
                buffer.put(terrain, 0, 16);
            } else {
                for (var instance = 0; instance < instanceCount; instance++) {
                    buffer.put(records, instance * BgfxSceneObject.INSTANCE_FLOATS, 16);
                }
            }
            if (!session.registerInstances(
                    ownerId(owner),
                    placement,
                    object.geometryStableId(),
                    matrices,
                    instanceCount,
                    lod == null ? -1 : lod.level(),
                    lod == null ? 0f : lod.distance1(),
                    lod == null ? 0f : lod.distance2())) {
                throw new IllegalStateException("native instance registration returned false");
            }
            var placements = placementsByOwner.computeIfAbsent(ownerId(owner), ignored -> new HashMap<>());
            var previous = placements.put(placement, instanceCount);
            if (previous == null) {
                registeredPlacementGroups++;
            } else {
                registeredPlacementInstances -= previous;
            }
            registeredPlacementInstances += instanceCount;
        }
    }

    private void disable(String reason, Throwable failure) {
        rendering.stats.customRayTracingAvailable = false;
        rendering.stats.customRayTracingActive = false;
        rendering.stats.customRayTracingReason = reason;
        if (failure == null) {
            log.warn("{}", reason);
        } else {
            log.warn("{}", reason, failure);
        }
    }

    private void logRaySunPath(boolean requested, String path, String reason) {
        if (!requested) {
            loggedRaySunPath = "";
            return;
        }
        var state = path.equals("hardware") ? path : path + ':' + reason;
        if (state.equals(loggedRaySunPath)) {
            return;
        }
        loggedRaySunPath = state;
        log.debug("Sun ray query: path={} reason='{}'", path, reason == null ? "" : reason);
    }

    private void closeVisibilityTextures() {
        RuntimeException failure = null;
        var textures = new ArrayDeque<BgfxTexture>();
        submittedPoses.clear();
        for (var slot = visibilityTextures.length - 1; slot >= 0; slot--) {
            if (visibilityTextures[slot] != null) {
                textures.addLast(visibilityTextures[slot]);
                visibilityTextures[slot] = null;
            }
        }
        while (!retiredTextures.isEmpty()) {
            textures.addLast(retiredTextures.removeFirst().texture());
        }
        displayedSnapshot = Snapshot.disabled();
        while (!textures.isEmpty()) {
            try {
                textures.removeFirst().close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        importedGeneration = 0;
        publishedSerial = 0;
        if (failure != null) {
            throw failure;
        }
    }

    private static int strideOf(Kind kind) {
        return switch (kind) {
            case ENTITY, STATIC_MESH_TANGENT -> BgfxSceneVertexLayouts.ENTITY.getStride();
            case STATIC_MESH -> BgfxSceneVertexLayouts.STATIC_MESH.getStride();
            case STATIC_MESH_COLOR -> BgfxSceneVertexLayouts.STATIC_MESH_COLOR.getStride();
            case TERRAIN -> BgfxSceneVertexLayouts.TERRAIN.getStride();
        };
    }

    private static long ownerId(String owner) {
        return StableRenderId.builder("metal-ray-owner").put(owner).build();
    }

    private static String messageOf(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
