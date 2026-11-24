package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.core.world.EmitterDistanceMode;
import com.rost.cruma.core.world.EmitterVisibility;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.ZoneFade;
import com.rost.cruma.core.world.ZoneFades;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import glm_.vec3.Vec3;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

public class ParticleSystem {
    public static final int DEFAULT_VISIBILITY_DISTANCE = 160000;
    private static final float EMITTER_CELL_SIZE = 32768f;
    private static final long MAX_SPATIAL_QUERY_CELLS = 4096L;
    private static final int DEFAULT_SIMULATION_WORKERS = 8;
    private static final int MAX_SIMULATION_WORKERS = 8;
    private static final int ACTORS_PER_SIMULATION_CHUNK = 8;
    private static final int PARALLEL_EMITTER_THRESHOLD = 1024;
    private static final Comparator<ParticleEmitter> SIMULATION_ORDER = Comparator.comparing(
                    ParticleEmitter::getRenderActorName)
            .thenComparingInt(ParticleEmitter::getRenderChildOrder);
    private static final Comparator<ParticleActorCullState> SPAWN_PUBLICATION_ORDER =
            Comparator.comparingInt(actor -> actor.publicationOrder);
    private static final int MAX_PENDING_ACTOR_EVENTS = 4096;
    private static final float SNAPSHOT_STEP = 1f / 60f;
    private static final float SNAPSHOT_SETTLE_SECONDS = 5f;
    private final List<ParticleEmitter> emitters = new ArrayList<>();
    private final List<ParticleEmitter> meshEmitters = new ArrayList<>();
    private final List<ParticleEmitter> spriteEmitters = new ArrayList<>();
    private final List<ParticleEmitter> beamEmitters = new ArrayList<>();
    private final List<ParticleEmitter> frameMeshEmitters = new ArrayList<>();
    private final List<ParticleEmitter> frameSpriteEmitters = new ArrayList<>();
    private final List<ParticleEmitter> frameBeamEmitters = new ArrayList<>();
    private boolean frameEmitterListsDirty = true;
    private boolean frameEmitterListsFromSnapshot;
    private final boolean rebuildFrameCandidates =
            Boolean.getBoolean("cruma.viewer.particles.rebuild_render_candidates");
    private ParticleEmitter[] updateEmitters = new ParticleEmitter[64];
    private int updateEmitterCount;
    private boolean simulationOrderDirty;
    private ParticleEmitter[] nearEmitters = new ParticleEmitter[64];
    private int nearEmitterCount;
    private ParticleActorCullState[] nearActors = new ParticleActorCullState[64];
    private int nearActorCount;
    private final int simulationWorkers;
    private final int nearSelectionWorkers;
    private final boolean verifyNearSelection = Boolean.getBoolean("cruma.viewer.particles.verify_near_selection");
    private EmitterCell[] selectionCells = new EmitterCell[64];
    private int parallelNearSelectionCount;
    private int lastNearSelectionCandidates;
    private ParticleActorCullState selectionActor;
    private boolean selectionOutsideWindow;
    private EmitterVisibility selectionVisibility;
    private boolean selectionAccepted;
    private float selectionOpacity;
    private int lastActorSimulationSkips;
    private int lastParticleLodSimulationSkips;
    private int lastSimulatedEmitters;
    private final EmitterSpatialGrid emitterGrid = new EmitterSpatialGrid();
    private boolean nearSelectionDirty = true;
    private final Map<ActorCullKey, ParticleActorCullState> actorCullBounds = new HashMap<>();
    private final List<ActorSpawn> actorSpawns = new ArrayList<>();
    private final List<ParticleActorCullState> spawnedActors = new ArrayList<>();
    private final List<String> removedActors = new ArrayList<>();
    private int boundsFrame;
    private boolean actorCullBoundsDirty = true;
    private boolean frameVisibilityValid;
    private long editRegistryChanges;
    private int frameCameraX;
    private int frameCameraY;
    private int frameCameraZ;
    private float visibilityDistance = DEFAULT_VISIBILITY_DISTANCE;
    private float visibilityDistanceSq = visibilityDistance * visibilityDistance;
    private EmitterDistanceMode distanceMode = EmitterDistanceMode.CUSTOM;
    private MapEventState gameEventStates = MapEventState.ALL_SHOWN;
    private boolean captureEffects = true;
    private boolean clientUpdateSkip = true;
    private float timeOfDay = 13f;
    private @Nullable ZoneFades zoneFades;
    private long zoneFadesRevision = Long.MIN_VALUE;

    private long registryRevision;

    public ParticleSystem() {
        this(Integer.getInteger(
                "cruma.viewer.particles.workers",
                Math.min(DEFAULT_SIMULATION_WORKERS, Runtime.getRuntime().availableProcessors())));
    }

    ParticleSystem(int simulationWorkers) {
        this.simulationWorkers = Math.clamp(simulationWorkers, 1, MAX_SIMULATION_WORKERS);
        this.nearSelectionWorkers = Math.clamp(
                Integer.getInteger(
                        "cruma.viewer.particles.near_selection_workers", Math.min(4, this.simulationWorkers)),
                1,
                MAX_SIMULATION_WORKERS);
    }

    static boolean isProjectedSizeInvisible(
            Box bounds, float[] viewProjection, int renderWidth, int renderHeight, float minimumProjectedPixels) {
        if (!bounds.isValid()) {
            return false;
        }
        var minimumX = Float.POSITIVE_INFINITY;
        var minimumY = Float.POSITIVE_INFINITY;
        var maximumX = Float.NEGATIVE_INFINITY;
        var maximumY = Float.NEGATIVE_INFINITY;
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? bounds.getMinX() : bounds.getMaxX();
            var y = (corner & 2) == 0 ? bounds.getMinY() : bounds.getMaxY();
            var z = (corner & 4) == 0 ? bounds.getMinZ() : bounds.getMaxZ();
            var clipW = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15];
            if (clipW <= 0.00001f) {
                return false;
            }
            var clipX = viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12];
            var clipY = viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13];
            var ndcX = clipX / clipW;
            var ndcY = clipY / clipW;
            minimumX = Math.min(minimumX, ndcX);
            minimumY = Math.min(minimumY, ndcY);
            maximumX = Math.max(maximumX, ndcX);
            maximumY = Math.max(maximumY, ndcY);
        }
        var projectedWidth = Math.max(0f, maximumX - minimumX) * 0.5f * renderWidth;
        var projectedHeight = Math.max(0f, maximumY - minimumY) * 0.5f * renderHeight;
        return Math.max(projectedWidth, projectedHeight) < minimumProjectedPixels;
    }

    public void add(ParticleEmitter emitter) {
        registryRevision++;
        emitters.add(emitter);
        emitters(emitter.getKind()).add(emitter);
        if (updateEmitterCount == updateEmitters.length) {
            updateEmitters = Arrays.copyOf(updateEmitters, updateEmitters.length << 1);
        }
        updateEmitters[updateEmitterCount++] = emitter;
        simulationOrderDirty = true;
        emitterGrid.add(emitter);
        var actorKey = ActorCullKey.of(emitter.getRenderActorName(), emitter.getActorPos());
        var parent = actorCullBounds.computeIfAbsent(
                actorKey, ignored -> new ParticleActorCullState(actorKey.refreshPhase(), actorKey));
        emitter.setActorCullState(parent);
        parent.addChild(emitter);
        emitter.setSiblingResolver(parent::findChildByOrder);
        actorCullBoundsDirty = true;
        nearSelectionDirty = true;
        frameVisibilityValid = false;
    }

    public void removeAll(List<ParticleEmitter> removed) {
        if (removed.isEmpty()) {
            return;
        }
        registryRevision++;
        var removedSet = Collections.newSetFromMap(new IdentityHashMap<ParticleEmitter, Boolean>());
        removedSet.addAll(removed);
        removeIdentities(emitters, removedSet);
        var retained = 0;
        for (var index = 0; index < updateEmitterCount; index++) {
            var emitter = updateEmitters[index];
            if (!removedSet.contains(emitter)) {
                updateEmitters[retained++] = emitter;
            }
        }
        Arrays.fill(updateEmitters, retained, updateEmitterCount, null);
        updateEmitterCount = retained;
        emitterGrid.removeAll(removedSet);
        removeIdentities(meshEmitters, removedSet);
        removeIdentities(spriteEmitters, removedSet);
        removeIdentities(beamEmitters, removedSet);
        frameMeshEmitters.clear();
        frameSpriteEmitters.clear();
        frameBeamEmitters.clear();
        frameEmitterListsDirty = true;
        for (var emitter : removed) {
            var actorState = emitter.getActorCullState();
            if (actorState != null) {
                actorState.removeChild(emitter);
                if (actorState.getChildCount() == 0) {
                    actorCullBounds.remove(actorState.getKey(), actorState);
                    if (!emitter.getVisibility().sounds().isEmpty()
                            && removedActors.size() < MAX_PENDING_ACTOR_EVENTS) {
                        removedActors.add(emitter.getRenderActorName());
                    }
                }
            }
            emitter.setActorCullState(null);
        }
        actorCullBoundsDirty = true;
        nearSelectionDirty = true;
        frameVisibilityValid = false;
    }

    public void relocate(ParticleEmitter emitter, float oldX, float oldY) {
        if (emitter.getActorCullState() == null) {
            return;
        }
        emitterGrid.move(emitter, oldX, oldY);
        frameEmitterListsDirty = true;
        actorCullBoundsDirty = true;
        nearSelectionDirty = true;
        frameVisibilityValid = false;
    }

    public List<ActorSpawn> drainActorSpawns() {
        if (actorSpawns.isEmpty()) {
            return List.of();
        }
        var drained = new ArrayList<>(actorSpawns);
        actorSpawns.clear();
        return drained;
    }

    public List<String> drainRemovedActors() {
        if (removedActors.isEmpty()) {
            return List.of();
        }
        var drained = new ArrayList<>(removedActors);
        removedActors.clear();
        return drained;
    }

    public List<ParticleEmitter> getEmitters() {
        return emitters;
    }

    public long getRegistryRevision() {
        return registryRevision;
    }

    public long getSceneRegistryRevision() {
        return registryRevision - editRegistryChanges;
    }

    public void addFromEdit(ParticleEmitter emitter) {
        add(emitter);
        editRegistryChanges++;
    }

    public void removeAllFromEdit(List<ParticleEmitter> removed) {
        if (removed.isEmpty()) {
            return;
        }
        removeAll(removed);
        editRegistryChanges++;
    }

    public List<ParticleEmitter> getEmitters(Kind kind) {
        return emitters(kind);
    }

    public List<ParticleEmitter> getFrameEmitters(Kind kind) {
        if (!frameVisibilityValid) {
            return emitters(kind);
        }
        if (frameEmitterListsDirty) {
            selectFrameEmitters(meshEmitters, frameMeshEmitters);
            selectFrameEmitters(spriteEmitters, frameSpriteEmitters);
            selectFrameEmitters(beamEmitters, frameBeamEmitters);
            frameEmitterListsDirty = false;
        }
        return switch (kind) {
            case MESH -> frameMeshEmitters;
            case SPRITE -> frameSpriteEmitters;
            case BEAM -> frameBeamEmitters;
        };
    }

    public boolean isEmpty() {
        return emitters.isEmpty();
    }

    public void setVisibilityDistance(float distance) {
        var next = Math.max(0f, distance);
        if (Float.floatToIntBits(visibilityDistance) == Float.floatToIntBits(next)) {
            return;
        }
        visibilityDistance = next;
        visibilityDistanceSq = visibilityDistance * visibilityDistance;
        nearSelectionDirty = true;
        frameVisibilityValid = false;
    }

    public float getVisibilityDistance() {
        return visibilityDistance;
    }

    public void setClientUpdateSkip(boolean skip) {
        clientUpdateSkip = skip;
    }

    public void setCaptureEffects(boolean effects) {
        captureEffects = effects;
    }

    public void configureCapture(MapEventState gameEventStates, float timeOfDay) {
        setVisibilityDistance(captureEffects ? Float.POSITIVE_INFINITY : 0f);
        setDistanceMode(EmitterDistanceMode.CUSTOM);
        setGameEventStates(gameEventStates);
        setTimeOfDay(timeOfDay);
    }

    public void setGameEventStates(MapEventState states) {
        var next = states == null ? MapEventState.ALL_SHOWN : states;
        if (!next.equals(gameEventStates)) {
            gameEventStates = next;
            nearSelectionDirty = true;
            frameVisibilityValid = false;
            for (var actor : actorCullBounds.values()) {
                if (actor.getChildCount() > 0
                        && !actor.getLeadChild().getVisibility().gameEvents().isEmpty()) {
                    actor.markDrawn();
                }
            }
        }
    }

    public void setZoneFades(@Nullable ZoneFades fades) {
        zoneFades = fades;
        zoneFadesRevision = Long.MIN_VALUE;
        nearSelectionDirty = true;
        frameVisibilityValid = false;
    }

    public void setTimeOfDay(float hour) {
        if (Float.floatToIntBits(timeOfDay) != Float.floatToIntBits(hour)) {
            timeOfDay = hour;
            nearSelectionDirty = true;
            frameVisibilityValid = false;
        }
    }

    public void setDistanceMode(EmitterDistanceMode mode) {
        if (distanceMode == mode) {
            return;
        }
        distanceMode = mode;
        nearSelectionDirty = true;
        frameVisibilityValid = false;
    }

    public EmitterDistanceMode getDistanceMode() {
        return distanceMode;
    }

    public boolean isNear(ParticleEmitter emitter, Vec3 cameraPos) {
        if (visibilityDistance <= 0f) {
            return false;
        }
        return distanceSq(emitter, cameraPos) <= farLimitSquared(emitter);
    }

    public float getVisibilityRatio(ParticleEmitter emitter, Vec3 cameraPos) {
        return emitter.getOpacityAtHour(
                getZoneOpacity(emitter.getVisibility(), actorVisibilityRatio(emitter, cameraPos)),
                timeOfDay,
                gameEventStates);
    }

    public boolean isHiddenByMapEvents(EmitterVisibility visibility) {
        var fade = findZoneFade(visibility);
        if (fade == null) {
            return visibility.isHiddenByGameEvents(gameEventStates);
        }
        return gameEventStates.hides(visibility.gameEvents())
                || !fade.isDrawn(visibility.zoneRender().mask());
    }

    public float getZoneOpacity(EmitterVisibility visibility, float ratio) {
        if (!(ratio > 0f)) {
            return ratio;
        }
        var fade = findZoneFade(visibility);
        if (fade == null || fade.getBlendingState() == ZoneFade.IDLE) {
            return ratio;
        }
        var zone = fade.getEffectRatio(visibility.zoneRender().mask());
        if (Float.isNaN(zone)) {
            return ratio;
        }
        return distanceMode == EmitterDistanceMode.AUTHORED ? zone : ratio * zone;
    }

    public boolean isVisible(ParticleEmitter emitter, Vec3 cameraPos, Frustum frustum) {
        var currentFrame = doesFrameVisibilityMatch(cameraPos);
        if (currentFrame) {
            if (!emitter.isNearThisFrame()) {
                return false;
            }
        } else if (!isNear(emitter, cameraPos)) {
            return false;
        }
        if ((currentFrame ? emitter.getOpacityRatio() : getVisibilityRatio(emitter, cameraPos)) <= 0f) {
            return false;
        }
        rebuildActorCullBoundsIfNeeded(cameraPos);
        var actorState = emitter.getActorCullState();
        if (actorState != null) {
            return actorState.getBounds().isValid() && actorState.intersects(frustum);
        }
        var childBox = new Box();
        if (emitter.computeWorldBounds(childBox)) {
            return frustum.intersects(childBox);
        }
        var actor = emitter.getActorPos();
        return frustum.intersectsSphere(actor.getX(), actor.getY(), actor.getZ(), 1f);
    }

    public boolean addNativeActorSortBounds(ParticleEmitter emitter, Box out) {
        var actorState = emitter.getActorCullState();
        if (actorState == null) {
            return emitter.addNativeSortBounds(out);
        }
        var bounds = actorState.getNativeSortBounds(boundsFrame);
        if (!bounds.isValid()) {
            return false;
        }
        out.add(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
        out.add(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
        return true;
    }

    public boolean isVisibleThisFrame(ParticleEmitter emitter, Frustum frustum) {
        if (!emitter.isNearThisFrame() || emitter.getOpacityRatio() <= 0f) {
            return false;
        }
        var actorState = emitter.getActorCullState();
        if (actorState != null) {
            return actorState.getBounds().isValid() && actorState.intersects(frustum);
        }
        var childBox = new Box();
        if (emitter.computeWorldBounds(childBox)) {
            return frustum.intersects(childBox);
        }
        var actor = emitter.getActorPos();
        return frustum.intersectsSphere(actor.getX(), actor.getY(), actor.getZ(), 1f);
    }

    public boolean isProjectedSizeInvisible(
            ParticleEmitter emitter,
            Vec3 cameraPos,
            float[] viewProjection,
            int renderWidth,
            int renderHeight,
            float minimumProjectedPixels) {
        if (viewProjection == null
                || viewProjection.length < 16
                || renderWidth <= 0
                || renderHeight <= 0
                || minimumProjectedPixels <= 0f) {
            return false;
        }
        rebuildActorCullBoundsIfNeeded(cameraPos);
        var actorState = emitter.getActorCullState();
        if (actorState != null) {
            return actorState.isProjectedSizeInvisible(
                    viewProjection, renderWidth, renderHeight, minimumProjectedPixels);
        }
        var childBox = new Box();
        return emitter.computeWorldBounds(childBox)
                && isProjectedSizeInvisible(
                        childBox, viewProjection, renderWidth, renderHeight, minimumProjectedPixels);
    }

    public boolean isProjectedSizeInvisibleThisFrame(
            ParticleEmitter emitter,
            float[] viewProjection,
            int renderWidth,
            int renderHeight,
            float minimumProjectedPixels) {
        if (viewProjection == null
                || viewProjection.length < 16
                || renderWidth <= 0
                || renderHeight <= 0
                || minimumProjectedPixels <= 0f) {
            return false;
        }
        var actorState = emitter.getActorCullState();
        if (actorState != null) {
            return actorState.isProjectedSizeInvisible(
                    viewProjection, renderWidth, renderHeight, minimumProjectedPixels);
        }
        var childBox = new Box();
        return emitter.computeWorldBounds(childBox)
                && isProjectedSizeInvisible(
                        childBox, viewProjection, renderWidth, renderHeight, minimumProjectedPixels);
    }

    public float renderBoundsDistanceSquared(ParticleEmitter emitter, Vec3 cameraPos) {
        rebuildActorCullBoundsIfNeeded(cameraPos);
        var actorState = emitter.getActorCullState();
        if (actorState != null) {
            return actorState.getBounds().isValid()
                    ? distanceSquared(cameraPos, actorState.getBounds())
                    : distanceSq(emitter, cameraPos);
        }
        var childBox = new Box();
        if (emitter.computeWorldBounds(childBox)) {
            return distanceSquared(cameraPos, childBox);
        }
        return distanceSq(emitter, cameraPos);
    }

    public void update(float dt, Vec3 cameraPos) {
        update(dt, cameraPos, null, null, 0, 0, 0f);
    }

    public void update(
            float dt,
            Vec3 cameraPos,
            Frustum frustum,
            float[] viewProjection,
            int renderWidth,
            int renderHeight,
            float minimumProjectedPixels) {
        update(
                dt,
                cameraPos,
                frustum,
                viewProjection,
                renderWidth,
                renderHeight,
                minimumProjectedPixels,
                minimumProjectedPixels);
    }

    public void update(
            float dt,
            Vec3 cameraPos,
            Frustum frustum,
            float[] viewProjection,
            int renderWidth,
            int renderHeight,
            float minimumProjectedPixels,
            float simulationLodProjectedPixels) {
        var fades = zoneFades;
        if (fades != null) {
            var revision = fades.getRevision();
            if (revision != zoneFadesRevision) {
                zoneFadesRevision = revision;
                nearSelectionDirty = true;
                frameVisibilityValid = false;
            }
        }
        if (nearSelectionDirty || !doesFrameVisibilityMatch(cameraPos)) {
            rebuildNearSelection(cameraPos);
        }
        lastActorSimulationSkips = 0;
        lastParticleLodSimulationSkips = 0;
        lastSimulatedEmitters = 0;
        spawnedActors.clear();
        var frame = nextBoundsFrame();
        var inputs = new SimulationInputs(
                frame,
                dt,
                frustum,
                viewProjection,
                renderWidth,
                renderHeight,
                minimumProjectedPixels,
                Math.max(minimumProjectedPixels, simulationLodProjectedPixels),
                clientUpdateSkip);
        var parallel = simulationWorkers > 1
                && nearEmitterCount >= PARALLEL_EMITTER_THRESHOLD
                && nearActorCount >= simulationWorkers;
        if (parallel) {
            simulateActors(inputs);
        } else {
            var counts = new SimulationCounts();
            for (var i = 0; i < nearEmitterCount; i++) {
                simulateEmitter(nearEmitters[i], inputs, counts);
            }
            for (var index = 0; index < nearActorCount; index++) {
                var actor = nearActors[index];
                actor.finishSimulation(frame);
                counts.recordSpawn(actor);
            }
            addSimulationCounts(counts);
        }
        spawnedActors.sort(SPAWN_PUBLICATION_ORDER);
        for (var actor : spawnedActors) {
            if (actorSpawns.size() >= MAX_PENDING_ACTOR_EVENTS) {
                break;
            }
            var lead = actor.getLeadChild();
            actorSpawns.add(new ActorSpawn(lead.getRenderActorName(), lead.getActorPos(), lead.getVisibility()));
        }
        spawnedActors.clear();
        actorCullBoundsDirty = false;
        rememberFrameCamera(cameraPos);
    }

    public SimulationStats getSimulationStats() {
        return new SimulationStats(
                nearEmitterCount, lastSimulatedEmitters, lastActorSimulationSkips, lastParticleLodSimulationSkips);
    }

    public record SimulationStats(
            int nearbyEmitters, int simulatedEmitters, int actorCullSkips, int particleLodSkips) {}

    int getActorStateCount() {
        return actorCullBounds.size();
    }

    int getParallelNearSelectionCount() {
        return parallelNearSelectionCount;
    }

    int getLastNearSelectionCandidates() {
        return lastNearSelectionCandidates;
    }

    public SnapshotStats prepareDeterministicSnapshot(Vec3 cameraPos) {
        frameEmitterListsDirty = true;
        frameEmitterListsFromSnapshot = true;
        nearSelectionDirty = true;
        ensureSimulationOrder();
        boundsFrame = 0;
        var frame = nextBoundsFrame();
        var nearbyEmitters = 0;
        var populatedEmitters = 0;
        var activeParticles = 0;
        for (var actor : actorCullBounds.values()) {
            actor.resetForSnapshot();
        }
        for (var index = 0; index < updateEmitterCount; index++) {
            updateEmitters[index].resetSimulation();
        }
        for (var index = 0; index < updateEmitterCount; index++) {
            var emitter = updateEmitters[index];
            emitter.update(SNAPSHOT_STEP);
            for (var waited = 0f;
                    !emitter.hasSiblingTiming()
                            && emitter.getActiveParticleCount() == 0
                            && waited < SNAPSHOT_SETTLE_SECONDS;
                    waited += SNAPSHOT_STEP) {
                emitter.update(SNAPSHOT_STEP);
            }
            var hidden = isHiddenByMapEvents(emitter.getVisibility());
            emitter.setOpacityRatio(hidden ? 0f : getVisibilityRatio(emitter, cameraPos));
            var near = !hidden && isNear(emitter, cameraPos);
            emitter.setNearThisFrame(near);
            if (near) {
                nearbyEmitters++;
                var active = emitter.getActiveParticleCount();
                if (active > 0) {
                    populatedEmitters++;
                    activeParticles += active;
                }
                var actorState = emitter.getActorCullState();
                actorState.beginFrame(frame);
                emitter.computeWorldBounds(actorState.getBounds());
            }
        }
        actorCullBoundsDirty = false;
        rememberFrameCamera(cameraPos);
        return new SnapshotStats(updateEmitterCount, nearbyEmitters, populatedEmitters, activeParticles);
    }

    public record SnapshotStats(
            int registeredEmitters, int nearbyEmitters, int populatedEmitters, int activeParticles) {}

    private float farLimitSquared(ParticleEmitter emitter) {
        var ignored = emitter.getVisibility().ignoredRange();
        return ignored > 0f ? Math.max(visibilityDistanceSq, ignored * ignored) : visibilityDistanceSq;
    }

    private @Nullable ZoneFade findZoneFade(EmitterVisibility visibility) {
        var fades = zoneFades;
        var render = visibility.zoneRender();
        return fades == null || render.blend() == null || !fades.getChoice().equals(gameEventStates.zoneStates())
                ? null
                : fades.find(render);
    }

    private float actorVisibilityRatio(ParticleEmitter emitter, Vec3 cameraPos) {
        if (distanceMode == EmitterDistanceMode.AUTHORED) {
            return emitter.getVisibilityRatio(cameraPos);
        }
        if (visibilityDistance <= 0f) {
            return 0f;
        }
        var distanceSq = distanceSq(emitter, cameraPos);
        if (distanceSq > visibilityDistanceSq) {
            return 0f;
        }
        return emitter.getCustomVisibilityRatio((float) Math.sqrt(distanceSq), visibilityDistance);
    }

    private void simulateActors(SimulationInputs inputs) {
        var work = new CompletableFuture<?>[simulationWorkers];
        var counts = new SimulationCounts[simulationWorkers];
        var nextActor = new AtomicInteger();
        for (var worker = 0; worker < simulationWorkers; worker++) {
            var laneCounts = counts[worker] = new SimulationCounts();
            work[worker] = CompletableFuture.runAsync(
                    () -> {
                        for (var first = nextActor.getAndAdd(ACTORS_PER_SIMULATION_CHUNK);
                                first < nearActorCount;
                                first = nextActor.getAndAdd(ACTORS_PER_SIMULATION_CHUNK)) {
                            var end = Math.min(nearActorCount, first + ACTORS_PER_SIMULATION_CHUNK);
                            for (var index = first; index < end; index++) {
                                var actor = nearActors[index];
                                for (var child = 0; child < actor.selectedChildCount; child++) {
                                    simulateEmitter(actor.selectedChildren[child], inputs, laneCounts);
                                }
                                actor.finishSimulation(inputs.frame);
                                laneCounts.recordSpawn(actor);
                                actor.getNativeSortBounds(inputs.frame);
                            }
                        }
                    },
                    SimulationExecutor.INSTANCE);
        }
        CompletableFuture.allOf(work).join();
        for (var laneCounts : counts) {
            addSimulationCounts(laneCounts);
        }
    }

    private void addSimulationCounts(SimulationCounts counts) {
        lastActorSimulationSkips += counts.actorSkips;
        lastParticleLodSimulationSkips += counts.particleSkips;
        lastSimulatedEmitters += counts.simulated;
        if (counts.spawnedActors != null) {
            spawnedActors.addAll(counts.spawnedActors);
        }
    }

    private record SimulationInputs(
            int frame,
            float dt,
            Frustum frustum,
            float[] viewProjection,
            int renderWidth,
            int renderHeight,
            float minimumProjectedPixels,
            float simulationLodProjectedPixels,
            boolean clientUpdateSkip) {}

    private static final class SimulationCounts {
        int actorSkips;
        int particleSkips;
        int simulated;
        List<ParticleActorCullState> spawnedActors;

        void recordSpawn(ParticleActorCullState actor) {
            if (actor.spawnedThisFrame
                    && !actor.getLeadChild().getVisibility().sounds().isEmpty()) {
                if (spawnedActors == null) {
                    spawnedActors = new ArrayList<>();
                }
                spawnedActors.add(actor);
            }
        }
    }

    private static final class SimulationExecutor {
        private static final ExecutorService INSTANCE = Executors.newFixedThreadPool(
                MAX_SIMULATION_WORKERS,
                Thread.ofPlatform().daemon().name("particle-simulation-", 0).factory());
    }

    private void rebuildNearSelection(Vec3 cameraPos) {
        rebuildNearSelection(cameraPos, true);
    }

    private void rebuildNearSelection(Vec3 cameraPos, boolean allowParallel) {
        var afterSnapshot = frameEmitterListsFromSnapshot;
        frameEmitterListsDirty |= rebuildFrameCandidates || afterSnapshot;
        frameEmitterListsFromSnapshot = false;
        ensureSimulationOrder();
        selectionActor = null;
        selectionVisibility = null;
        var cameraX = cameraPos.getX();
        var cameraY = cameraPos.getY();
        var cameraZ = cameraPos.getZ();
        var previousNearEmitterCount = nearEmitterCount;
        var previousNearActorCount = nearActorCount;
        var marked = afterSnapshot ? updateEmitters : nearEmitters;
        var markedCount = afterSnapshot ? updateEmitterCount : previousNearEmitterCount;
        for (var index = 0; index < markedCount; index++) {
            marked[index].setNearThisFrame(false);
        }
        for (var index = 0; index < nearActorCount; index++) {
            nearActors[index].clearSelectedChildren();
        }
        nearActorCount = 0;
        nearEmitterCount = 0;
        lastNearSelectionCandidates = 0;
        var parallel = false;
        if (nearEmitters.length < updateEmitterCount) {
            nearEmitters = Arrays.copyOf(nearEmitters, updateEmitters.length);
        }
        if (visibilityDistance > 0f) {
            var minCellX = emitterCell(cameraX - visibilityDistance);
            var maxCellX = emitterCell(cameraX + visibilityDistance);
            var minCellY = emitterCell(cameraY - visibilityDistance);
            var maxCellY = emitterCell(cameraY + visibilityDistance);
            var spanX = (long) maxCellX - minCellX + 1L;
            var spanY = (long) maxCellY - minCellY + 1L;
            var cellCount =
                    spanX > 0L && spanY > 0L && spanX <= Long.MAX_VALUE / spanY ? spanX * spanY : Long.MAX_VALUE;
            if (Float.isFinite(visibilityDistance)
                    && cellCount <= MAX_SPATIAL_QUERY_CELLS
                    && cellCount < updateEmitterCount) {
                parallel = allowParallel
                        && prepareParallelNearSelection(
                                minCellX, maxCellX, minCellY, maxCellY, cameraX, cameraY, cameraZ);
                if (!parallel) {
                    for (var cellX = minCellX; ; cellX++) {
                        for (var cellY = minCellY; ; cellY++) {
                            var bucket = emitterGrid.get(emitterCellKey(cellX, cellY));
                            if (bucket != null) {
                                for (var index = 0; index < bucket.emitters.size(); index++) {
                                    selectNearEmitter(bucket.emitters.get(index), cameraX, cameraY, cameraZ);
                                }
                            }
                            if (cellY == maxCellY) {
                                break;
                            }
                        }
                        if (cellX == maxCellX) {
                            break;
                        }
                    }
                }
            } else {
                for (var index = 0; index < updateEmitterCount; index++) {
                    selectNearEmitter(updateEmitters[index], cameraX, cameraY, cameraZ);
                }
            }
        }
        if (nearEmitterCount != previousNearEmitterCount) {
            frameEmitterListsDirty = true;
        }
        if (nearEmitterCount < previousNearEmitterCount) {
            Arrays.fill(nearEmitters, nearEmitterCount, previousNearEmitterCount, null);
        }
        if (nearActorCount < previousNearActorCount) {
            Arrays.fill(nearActors, nearActorCount, previousNearActorCount, null);
        }
        selectionActor = null;
        selectionVisibility = null;
        nearSelectionDirty = false;
        if (parallel && verifyNearSelection) {
            verifyParallelNearSelection(cameraPos);
        }
    }

    private boolean prepareParallelNearSelection(
            int minX, int maxX, int minY, int maxY, float cameraX, float cameraY, float cameraZ) {
        if (nearSelectionWorkers < 2 || updateEmitterCount < PARALLEL_EMITTER_THRESHOLD) {
            return false;
        }
        var cellCount = 0;
        var candidates = 0;
        for (var x = minX; ; x++) {
            for (var y = minY; ; y++) {
                var cell = emitterGrid.get(emitterCellKey(x, y));
                if (cell != null && !cell.emitters.isEmpty()) {
                    if (cellCount == selectionCells.length) {
                        selectionCells = Arrays.copyOf(selectionCells, cellCount << 1);
                    }
                    selectionCells[cellCount++] = cell;
                    candidates += cell.emitters.size();
                }
                if (y == maxY) {
                    break;
                }
            }
            if (x == maxX) {
                break;
            }
        }
        if (cellCount < 2 || candidates < PARALLEL_EMITTER_THRESHOLD) {
            Arrays.fill(selectionCells, 0, cellCount, null);
            return false;
        }
        var count = cellCount;
        var nextCell = new AtomicInteger();
        var work = new CompletableFuture<?>[Math.min(nearSelectionWorkers, count)];
        for (var worker = 0; worker < work.length; worker++) {
            work[worker] = CompletableFuture.runAsync(
                    () -> {
                        for (var index = nextCell.getAndIncrement();
                                index < count;
                                index = nextCell.getAndIncrement()) {
                            selectionCells[index].select(this, cameraX, cameraY, cameraZ);
                        }
                    },
                    SimulationExecutor.INSTANCE);
        }
        CompletableFuture.allOf(work).join();
        if (nearActors.length < candidates) {
            nearActors = Arrays.copyOf(nearActors, candidates);
        }
        for (var index = 0; index < count; index++) {
            var cell = selectionCells[index];
            for (var child = 0; child < cell.selectedCount; child++) {
                var emitter = cell.selected[child];
                if (nearEmitters[nearEmitterCount] != emitter) {
                    nearEmitters[nearEmitterCount] = emitter;
                    frameEmitterListsDirty = true;
                }
                nearEmitterCount++;
            }
            for (var actor = 0; actor < cell.actorCount; actor++) {
                var parent = cell.actors[actor];
                if (nearActors[nearActorCount] != parent) {
                    nearActors[nearActorCount] = parent;
                }
                nearActorCount++;
            }
            selectionCells[index] = null;
        }
        lastNearSelectionCandidates = candidates;
        parallelNearSelectionCount++;
        return true;
    }

    private void verifyParallelNearSelection(Vec3 cameraPos) {
        var actual = Arrays.copyOf(nearEmitters, nearEmitterCount);
        var actors = Arrays.copyOf(nearActors, nearActorCount);
        var children = new ParticleEmitter[actors.length][];
        for (var index = 0; index < actors.length; index++) {
            children[index] = Arrays.copyOf(actors[index].selectedChildren, actors[index].selectedChildCount);
        }
        var opacity = new int[actual.length];
        for (var index = 0; index < actual.length; index++) {
            opacity[index] = Float.floatToIntBits(actual[index].getOpacityRatio());
        }
        var candidates = lastNearSelectionCandidates;
        rebuildNearSelection(cameraPos, false);
        if (candidates != lastNearSelectionCandidates
                || actual.length != nearEmitterCount
                || actors.length != nearActorCount) {
            throw new IllegalStateException("parallel near-selection counts differ from sequential selection");
        }
        for (var index = 0; index < actual.length; index++) {
            if (actual[index] != nearEmitters[index]
                    || opacity[index] != Float.floatToIntBits(nearEmitters[index].getOpacityRatio())) {
                throw new IllegalStateException("parallel near-selection child/opacity differs at " + index);
            }
        }
        for (var index = 0; index < actors.length; index++) {
            var parent = nearActors[index];
            if (actors[index] != parent
                    || children[index].length != parent.selectedChildCount
                    || !Arrays.equals(
                            children[index],
                            0,
                            children[index].length,
                            parent.selectedChildren,
                            0,
                            parent.selectedChildCount)) {
                throw new IllegalStateException("parallel near-selection actor order differs at " + index);
            }
        }
        if (parallelNearSelectionCount == 1 || parallelNearSelectionCount % 240 == 0) {
            System.getLogger(ParticleSystem.class.getName())
                    .log(
                            Level.INFO,
                            "Verified {0} parallel near selections against sequential child, actor and opacity order",
                            parallelNearSelectionCount);
        }
    }

    private void selectNearEmitter(ParticleEmitter emitter, float cameraX, float cameraY, float cameraZ) {
        lastNearSelectionCandidates++;
        var parent = emitter.getActorCullState();
        var visibility = emitter.getVisibility();
        if (selectionActor != parent || selectionVisibility != visibility) {
            selectionActor = parent;
            selectionVisibility = visibility;
            selectionAccepted = false;
            if (!isHiddenByMapEvents(visibility)) {
                var actor = emitter.getActorPos();
                var dx = actor.getX() - cameraX;
                var dy = actor.getY() - cameraY;
                var dz = actor.getZ() - cameraZ;
                var distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared <= farLimitSquared(emitter)) {
                    selectionAccepted = true;
                    selectionOutsideWindow = distanceMode == EmitterDistanceMode.AUTHORED
                            && visibility.isOutsideWindow(dx * dx + dy * dy);
                    selectionOpacity = getZoneOpacity(
                            visibility,
                            distanceMode == EmitterDistanceMode.AUTHORED
                                    ? emitter.getVisibilityRatioAtDistanceSquared2d(dx * dx + dy * dy)
                                    : customVisibilityRatio(emitter, distanceSquared));
                }
            }
        }
        if (!selectionAccepted) {
            return;
        }
        emitter.setNearThisFrame(true);
        parent.setOutsideVisibleLimit(selectionOutsideWindow);
        emitter.setOpacityRatio(emitter.getOpacityAtHour(selectionOpacity, timeOfDay, gameEventStates));
        if (nearEmitters[nearEmitterCount] != emitter) {
            nearEmitters[nearEmitterCount] = emitter;
            frameEmitterListsDirty = true;
        }
        nearEmitterCount++;
        if (parent.selectedChildCount == 0) {
            if (nearActorCount == nearActors.length) {
                nearActors = Arrays.copyOf(nearActors, nearActors.length << 1);
            }
            if (nearActors[nearActorCount] != parent) {
                nearActors[nearActorCount] = parent;
            }
            nearActorCount++;
        }
        parent.selectChild(emitter);
    }

    private void rebuildActorCullBoundsIfNeeded(Vec3 cameraPos) {
        if (!actorCullBoundsDirty) {
            return;
        }
        var frame = nextBoundsFrame();
        for (var index = 0; index < updateEmitterCount; index++) {
            var emitter = updateEmitters[index];
            if (!isNear(emitter, cameraPos)) {
                continue;
            }
            var actorState = emitter.getActorCullState();
            actorState.beginFrame(frame);
            emitter.computeWorldBounds(actorState.getBounds());
        }
        actorCullBoundsDirty = false;
    }

    private int nextBoundsFrame() {
        boundsFrame++;
        if (boundsFrame == 0) {
            boundsFrame = 1;
            for (var state : actorCullBounds.values()) {
                state.resetFrame();
            }
        }
        return boundsFrame;
    }

    private float customVisibilityRatio(ParticleEmitter emitter, float distanceSquared) {
        var farSquared = farLimitSquared(emitter);
        if (visibilityDistance <= 0f || distanceSquared > farSquared) {
            return 0f;
        }
        return emitter.getCustomVisibilityRatio((float) Math.sqrt(distanceSquared), (float) Math.sqrt(farSquared));
    }

    private List<ParticleEmitter> emitters(Kind kind) {
        return switch (kind) {
            case MESH -> meshEmitters;
            case SPRITE -> spriteEmitters;
            case BEAM -> beamEmitters;
        };
    }

    private void rememberFrameCamera(Vec3 cameraPos) {
        frameCameraX = Float.floatToIntBits(cameraPos.getX());
        frameCameraY = Float.floatToIntBits(cameraPos.getY());
        frameCameraZ = Float.floatToIntBits(cameraPos.getZ());
        frameVisibilityValid = true;
    }

    private boolean doesFrameVisibilityMatch(Vec3 cameraPos) {
        return frameVisibilityValid
                && frameCameraX == Float.floatToIntBits(cameraPos.getX())
                && frameCameraY == Float.floatToIntBits(cameraPos.getY())
                && frameCameraZ == Float.floatToIntBits(cameraPos.getZ());
    }

    private record ActorCullKey(String actorName, int x, int y, int z) {
        private int refreshPhase() {
            var hash = actorName == null ? 0 : actorName.hashCode();
            hash = 31 * hash + x;
            hash = 31 * hash + y;
            hash = 31 * hash + z;
            hash ^= hash >>> 16;
            return hash & ParticleActorCullState.REFRESH_FRAME_MASK;
        }

        private static ActorCullKey of(String actorName, Vec3 position) {
            return new ActorCullKey(
                    actorName,
                    Float.floatToIntBits(position.getX()),
                    Float.floatToIntBits(position.getY()),
                    Float.floatToIntBits(position.getZ()));
        }
    }

    private void ensureSimulationOrder() {
        if (!simulationOrderDirty) {
            return;
        }
        Arrays.sort(updateEmitters, 0, updateEmitterCount, SIMULATION_ORDER);
        emitterGrid.sortChildren();
        var publicationOrder = 0;
        for (var actor : actorCullBounds.values()) {
            actor.publicationOrder = publicationOrder++;
        }
        simulationOrderDirty = false;
    }

    private static void removeIdentities(List<ParticleEmitter> source, Set<ParticleEmitter> removed) {
        var retained = 0;
        var size = source.size();
        for (var index = 0; index < size; index++) {
            var emitter = source.get(index);
            if (!removed.contains(emitter)) {
                source.set(retained++, emitter);
            }
        }
        if (retained < size) {
            source.subList(retained, size).clear();
        }
    }

    public record ActorSpawn(String actorName, Vec3 position, EmitterVisibility settings) {}

    private static void selectFrameEmitters(List<ParticleEmitter> registered, List<ParticleEmitter> selected) {
        var count = 0;
        for (var emitter : registered) {
            if (!emitter.isNearThisFrame()) {
                continue;
            }
            if (count == selected.size()) {
                selected.add(emitter);
            } else if (selected.get(count) != emitter) {
                selected.set(count, emitter);
            }
            count++;
        }
        if (count < selected.size()) {
            selected.subList(count, selected.size()).clear();
        }
    }

    private static void simulateEmitter(ParticleEmitter emitter, SimulationInputs inputs, SimulationCounts counts) {
        var actor = emitter.getActorCullState();
        if (emitter.isHeld()) {
            actor.beginFrame(inputs.frame);
            emitter.computeWorldBounds(actor.getBounds());
            return;
        }
        var actorDelta = actor.getSimulationDelta(
                inputs.frame,
                inputs.dt,
                inputs.frustum,
                inputs.viewProjection,
                inputs.renderWidth,
                inputs.renderHeight,
                inputs.minimumProjectedPixels,
                inputs.clientUpdateSkip);
        if (actorDelta < 0f) {
            counts.actorSkips++;
            return;
        }
        var delta = actor.hasSynchronousChildren()
                ? actorDelta
                : emitter.getSimulationDelta(
                        inputs.frame,
                        actorDelta,
                        inputs.viewProjection,
                        inputs.renderWidth,
                        inputs.renderHeight,
                        inputs.minimumProjectedPixels,
                        inputs.simulationLodProjectedPixels);
        if (delta > 0f) {
            emitter.update(delta);
            counts.simulated++;
        } else if (delta < 0f) {
            counts.particleSkips++;
        }
        actor.beginFrame(inputs.frame);
        emitter.computeWorldBounds(actor.getBounds());
    }

    private static float distanceSq(ParticleEmitter emitter, Vec3 cameraPos) {
        var p = emitter.getActorPos();
        float dx = p.getX() - cameraPos.getX();
        float dy = p.getY() - cameraPos.getY();
        float dz = p.getZ() - cameraPos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static float distanceSquared(Vec3 point, Box box) {
        var dx = axisDistance(point.getX(), box.getMinX(), box.getMaxX());
        var dy = axisDistance(point.getY(), box.getMinY(), box.getMaxY());
        var dz = axisDistance(point.getZ(), box.getMinZ(), box.getMaxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static float axisDistance(float value, float min, float max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0f;
    }

    private static int emitterCell(float coordinate) {
        var cell = Math.floor(coordinate / EMITTER_CELL_SIZE);
        if (cell <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (cell >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) cell;
    }

    private static long emitterCellKey(int cellX, int cellY) {
        return ((long) cellX << Integer.SIZE) ^ (cellY & 0xffff_ffffL);
    }

    private static final class EmitterCell {
        private final ArrayList<ParticleEmitter> emitters = new ArrayList<>();
        private ParticleEmitter[] selected = new ParticleEmitter[0];
        private ParticleActorCullState[] actors = new ParticleActorCullState[0];
        private int selectedCount;
        private int actorCount;

        private void select(ParticleSystem system, float cameraX, float cameraY, float cameraZ) {
            if (selected.length < emitters.size()) {
                var capacity = Math.max(emitters.size(), Math.max(16, selected.length << 1));
                selected = new ParticleEmitter[capacity];
                actors = new ParticleActorCullState[capacity];
            }
            var oldSelected = selectedCount;
            var oldActors = actorCount;
            selectedCount = 0;
            actorCount = 0;
            var previousActor = (ParticleActorCullState) null;
            var previousVisibility = (EmitterVisibility) null;
            var accepted = false;
            var outsideWindow = false;
            var opacity = 0f;
            for (var index = 0; index < emitters.size(); index++) {
                var emitter = emitters.get(index);
                var parent = emitter.getActorCullState();
                var visibility = emitter.getVisibility();
                if (previousActor != parent || previousVisibility != visibility) {
                    previousActor = parent;
                    previousVisibility = visibility;
                    accepted = false;
                    if (!system.isHiddenByMapEvents(visibility)) {
                        var position = emitter.getActorPos();
                        var dx = position.getX() - cameraX;
                        var dy = position.getY() - cameraY;
                        var dz = position.getZ() - cameraZ;
                        var distanceSquared = dx * dx + dy * dy + dz * dz;
                        if (distanceSquared <= system.farLimitSquared(emitter)) {
                            accepted = true;
                            outsideWindow = system.distanceMode == EmitterDistanceMode.AUTHORED
                                    && visibility.isOutsideWindow(dx * dx + dy * dy);
                            opacity = system.getZoneOpacity(
                                    visibility,
                                    system.distanceMode == EmitterDistanceMode.AUTHORED
                                            ? emitter.getVisibilityRatioAtDistanceSquared2d(dx * dx + dy * dy)
                                            : system.customVisibilityRatio(emitter, distanceSquared));
                        }
                    }
                }
                if (!accepted) {
                    continue;
                }
                emitter.setNearThisFrame(true);
                parent.setOutsideVisibleLimit(outsideWindow);
                emitter.setOpacityRatio(emitter.getOpacityAtHour(opacity, system.timeOfDay, system.gameEventStates));
                if (selected[selectedCount] != emitter) {
                    selected[selectedCount] = emitter;
                }
                selectedCount++;
                if (parent.selectedChildCount == 0) {
                    if (actors[actorCount] != parent) {
                        actors[actorCount] = parent;
                    }
                    actorCount++;
                }
                parent.selectChild(emitter);
            }
            if (selectedCount < oldSelected) {
                Arrays.fill(selected, selectedCount, oldSelected, null);
            }
            if (actorCount < oldActors) {
                Arrays.fill(actors, actorCount, oldActors, null);
            }
        }

        private void clearSelection() {
            Arrays.fill(selected, null);
            Arrays.fill(actors, null);
            selectedCount = 0;
            actorCount = 0;
        }
    }

    private static final class EmitterSpatialGrid {
        private static final int INITIAL_CAPACITY = 64;
        private long[] keys = new long[INITIAL_CAPACITY];
        private boolean[] used = new boolean[INITIAL_CAPACITY];
        private EmitterCell[] buckets = new EmitterCell[INITIAL_CAPACITY];
        private int size;

        void sortChildren() {
            for (var bucket : buckets) {
                if (bucket != null) {
                    bucket.emitters.sort(SIMULATION_ORDER);
                }
            }
        }

        void add(ParticleEmitter emitter) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            var key = emitterCellKey(
                    emitterCell(emitter.getActorPos().getX()),
                    emitterCell(emitter.getActorPos().getY()));
            var slot = findSlot(key);
            if (!used[slot]) {
                used[slot] = true;
                keys[slot] = key;
                buckets[slot] = new EmitterCell();
                size++;
            }
            buckets[slot].emitters.add(emitter);
        }

        void move(ParticleEmitter emitter, float oldX, float oldY) {
            var before = emitterCellKey(emitterCell(oldX), emitterCell(oldY));
            var after = emitterCellKey(
                    emitterCell(emitter.getActorPos().getX()),
                    emitterCell(emitter.getActorPos().getY()));
            if (before == after) {
                var bucket = get(after);
                if (bucket != null) {
                    bucket.clearSelection();
                }
                return;
            }
            var bucket = get(before);
            if (bucket != null) {
                var removed = Collections.newSetFromMap(new IdentityHashMap<ParticleEmitter, Boolean>());
                removed.add(emitter);
                removeIdentities(bucket.emitters, removed);
                bucket.clearSelection();
            }
            add(emitter);
            var moved = get(after);
            if (moved != null) {
                moved.clearSelection();
            }
        }

        EmitterCell get(long key) {
            var slot = findSlot(key);
            return used[slot] ? buckets[slot] : null;
        }

        void removeAll(Set<ParticleEmitter> removed) {
            var touched = new HashSet<Long>();
            for (var emitter : removed) {
                touched.add(emitterCellKey(
                        emitterCell(emitter.getActorPos().getX()),
                        emitterCell(emitter.getActorPos().getY())));
            }
            for (var key : touched) {
                var bucket = get(key);
                if (bucket != null) {
                    removeIdentities(bucket.emitters, removed);
                    bucket.clearSelection();
                }
            }
        }

        private int findSlot(long key) {
            var mask = keys.length - 1;
            var slot = mix(key) & mask;
            while (used[slot] && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        private void grow() {
            var oldKeys = keys;
            var oldUsed = used;
            var oldBuckets = buckets;
            keys = new long[oldKeys.length << 1];
            used = new boolean[keys.length];
            buckets = new EmitterCell[keys.length];
            for (var index = 0; index < oldKeys.length; index++) {
                if (!oldUsed[index]) {
                    continue;
                }
                var slot = findSlot(oldKeys[index]);
                used[slot] = true;
                keys[slot] = oldKeys[index];
                buckets[slot] = oldBuckets[index];
            }
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53l;
            value ^= value >>> 33;
            return (int) value;
        }
    }
}
