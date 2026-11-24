package com.rost.cruma.viewer.render;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.L2Swing;
import com.rost.cruma.core.world.MapPawn;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.core.world.ZoneFades;
import com.rost.cruma.viewer.config.Config.Viewer.Editing;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.editing.EditingAssetResolver;
import com.rost.cruma.viewer.editing.EditingLightSurfaces.Result;
import com.rost.cruma.viewer.editing.EditingLightmapUploads;
import com.rost.cruma.viewer.editing.EditingMaps;
import com.rost.cruma.viewer.editing.EditingRecovery;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.CarriedActor;
import com.rost.cruma.viewer.editing.EditingSession.LoadedSurfaces;
import com.rost.cruma.viewer.editing.EditingSession.Preview;
import com.rost.cruma.viewer.editing.EditingSession.Rebuild;
import com.rost.cruma.viewer.editing.EditingSession.SurfacePreview;
import com.rost.cruma.viewer.editing.EditingSession.SurfaceShow;
import com.rost.cruma.viewer.editing.EditingSurfaceView;
import com.rost.cruma.viewer.editing.EditorUi;
import com.rost.cruma.viewer.editing.TerrainViewportSession;
import com.rost.cruma.viewer.render.SceneAudio.Switch;
import com.rost.cruma.viewer.render.SceneDoors.Moved;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.spawn.MapPawnStage;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public class RenderingContext {
    public final Context context = new Context();
    public final Camera camera;
    public final Scene scene = new Scene();
    public final ParticleSystem particles = new ParticleSystem();
    public final CoronaSystem coronas = new CoronaSystem();
    public final SwingSystem swings = new SwingSystem();
    public final EditorEffectStage effects = new EditorEffectStage(particles, swings);
    public final ZoneFades zoneFades = new ZoneFades();
    public final SkyZones skyZones = new SkyZones();
    public final SoundCues soundCues = new SoundCues();
    public final PictureClock pictureClock = new PictureClock();
    public final ScenePlayback playback = new ScenePlayback();
    public final ScenePlayer scenes;
    public final GameScenePlayer gameScenes;
    public final Switch sceneAudio;
    public volatile @Nullable ScenePostEffects heldPostEffects;
    public final SceneInspection inspection = new SceneInspection();
    public final GeodataState geodata = new GeodataState();
    public final SpawnOverlayState spawns = new SpawnOverlayState();
    public final MapPawnStage mapPawns;
    public final LoadedTiles tiles = new LoadedTiles();
    private Path editingClientRoot;
    private ViewerRenderBackend editingBackend;
    private final SceneDoorPreview doorPreview = new SceneDoorPreview();
    private @Nullable Moved heldDoors;
    private EditingSession editingSession;
    private World editingWorld;
    private EditingMaps editingMaps = EditingMaps.MAIN;
    private EditingAssetResolver editingAssets;
    private @Nullable ExecutorService editingSurfaceReads;
    private Editing editingRecoverySettings;
    private EditingRecovery editingRecovery;
    private TerrainViewportSession terrainEditing;
    private final EditingLightmapUploads editingLightmaps = new EditingLightmapUploads(4L << 20);
    private Set<String> editingResidentMaps;
    private final Set<String> editingHidden = new HashSet<>();
    private final Map<String, List<DebugSceneObject>> editingHiddenObjects = new LinkedHashMap<>();
    private long editingHiddenRevision;
    private long coronaEditsRevision = Long.MIN_VALUE;
    private Set<String> editingUnloadedMaps;
    private boolean restoringEditedMap;
    public boolean editorInputCaptured;
    public boolean skyHidden;
    public boolean blackBackdrop;
    private final boolean capture;
    private ViewMode viewMode = ViewMode.LIT;

    private @Nullable String standaloneMap;

    public RenderingContext(boolean ortho, int nearPlane) {
        this.camera = new Camera(context, 60f, nearPlane, new Vec3());
        this.camera.setOrthoProjection(ortho);
        coronas.setCarriers(swings);
        particles.setZoneFades(zoneFades);
        mapPawns = new MapPawnStage(ortho);
        this.scenes = new ScenePlayer(camera, mapPawns::trigger);
        this.gameScenes = new GameScenePlayer(camera);
        sceneAudio = new Switch(gameScenes);
        this.geodata.tiles = tiles;
        this.capture = ortho;
    }

    public ViewMode getViewMode() {
        return viewMode == ViewMode.LIT && !camera.getView().isLit() ? ViewMode.UNLIT : viewMode;
    }

    public ViewMode getChosenViewMode() {
        return viewMode;
    }

    public void setViewMode(ViewMode mode) {
        viewMode = capture || mode == null ? ViewMode.LIT : mode;
    }

    public @Nullable String getStandaloneMap() {
        return standaloneMap;
    }

    public void setStandaloneMap(@Nullable String map) {
        standaloneMap = map;
    }

    public void moveSceneDoors(ViewerRenderBackend backend) {
        var moved = gameScenes.takeDoors();
        var held = heldDoors;
        heldDoors = null;
        doorPreview.apply(moved != null ? moved : held, hasEditingSession(), backend);
    }

    public void holdDoors(Moved doors) {
        heldDoors = doors;
    }

    public ScenePostEffects getScenePostEffects() {
        var held = heldPostEffects;
        return held != null ? held : gameScenes.getPostEffects();
    }

    public void configureEditing(World world, ViewerRenderBackend backend) {
        editingWorld = world;
        editingClientRoot = world.clientPath();
        editingMaps = EditingMaps.of(world.clientPath(), world.variant());
        editingBackend = backend;
    }

    public void refreshCoronaEdits() {
        var session = editingSession;
        if (session == null) {
            coronas.setEdits(CoronaSystem.Edits.NONE);
            swings.setCopiedLights(Map.of());
            return;
        }
        var revision = session.getRevision();
        if (revision == coronaEditsRevision && !session.isGestureActive()) {
            return;
        }
        coronaEditsRevision = revision;
        var moved = new HashMap<String, CoronaSystem.Moved>();
        for (var light : session.getOpenedChangedLights()) {
            var place = light.location();
            var color = light.color();
            moved.put(
                    light.sourcePath(),
                    new CoronaSystem.Moved(
                            new Vec3(place.x(), place.y(), place.z()),
                            color.hue(),
                            color.saturation(),
                            light.radius(),
                            new URotator(
                                    light.shape().rotation().pitch(),
                                    light.shape().rotation().yaw(),
                                    light.shape().rotation().roll())));
        }
        var gone = new HashSet<>(session.getHiddenActors());
        for (var light : session.getOpenedDeletedLights()) {
            gone.add(light.sourcePath());
        }
        var copies = new HashMap<String, CoronaSystem.Copied>();
        var carriers = new HashMap<String, String>();
        for (var light : session.getOpenedCreatedLights()) {
            var place = light.location();
            var turn = light.shape().rotation();
            copies.put(
                    light.sourcePath(),
                    new CoronaSystem.Copied(
                            light.template(),
                            new Vec3(place.x(), place.y(), place.z()),
                            new URotator(turn.pitch(), turn.yaw(), turn.roll())));
            var carrier = session.getCarrierOf(light.sourcePath());
            if (carrier != null) {
                carriers.put(light.sourcePath(), carrier);
            }
        }
        swings.setCopiedLights(carriers);
        coronas.setEdits(new CoronaSystem.Edits(moved, gone, copies));
    }

    public boolean hasEditingSession() {
        return editingSession != null;
    }

    public void configureRecovery(Editing settings) {
        editingRecoverySettings = settings;
    }

    public void prepareEditing() {
        prepareEditing(Set.of());
    }

    public void prepareEditing(Collection<String> loadedMaps) {
        editingResidentMaps = new HashSet<>(editingMaps.getSources(loadedMaps));
        publishEditingMaps();
    }

    public EditingRecovery getEditingRecovery() {
        return editingRecovery;
    }

    public void tickEditingRecovery() {
        if (editingSession != null) {
            editingSession.pollExport();
            editingSession.pollLightingPreview();
            editingSession.pollSurfacePreview();
        }
        if (editingBackend != null && !editingLightmaps.isIdle()) {
            for (var upload : editingLightmaps.drain()) {
                editingBackend.updateEditorLightmap(editingMaps.getTile(upload.map()), upload.key(), upload.texels());
            }
        }
        if (terrainEditing != null) {
            terrainEditing.poll();
        }
        if (editingRecovery != null) {
            editingRecovery.tick(System.nanoTime());
        }
    }

    public void discardEditingRecovery() throws IOException {
        if (editingSession != null) {
            editingSession.cancelExport();
        }
        if (editingRecovery != null) {
            editingRecovery.discardAndClose();
        }
        if (editingSession != null) {
            editingSession.closeExport();
        }
        if (terrainEditing != null) {
            terrainEditing.stopForRecovery();
        }
    }

    public boolean hasTerrainEditingSession() {
        return terrainEditing != null;
    }

    public TerrainViewportSession getTerrainEditing() {
        if (terrainEditing == null) {
            if (editingClientRoot == null || editingBackend == null) {
                throw new IllegalStateException("Terrain editing is unavailable for this session.");
            }
            var rendering = editingWorld.rendering();
            terrainEditing = new TerrainViewportSession(
                    editingClientRoot,
                    new Options(rendering.dxtMipCodecEnabled(), rendering.dxtMipMinSize()),
                    rendering.textureQuality(),
                    editingBackend);
            terrainEditing.setMapFiles(editingMaps);
        }
        return terrainEditing;
    }

    public boolean hasEditingWork() {
        return !editingLightmaps.isIdle()
                || editingSession != null && editingSession.isLightingPreviewBusy()
                || terrainEditing != null && terrainEditing.isBusy();
    }

    public boolean isEditingOperationActive() {
        return editingSession != null && editingSession.isGestureActive()
                || terrainEditing != null
                        && (terrainEditing.isBusy()
                                || terrainEditing.getController().isRecoveryGestureActive());
    }

    public boolean doesEditingNeedSave() {
        return isEditingOperationActive()
                || editingSession != null && editingSession.isDirty()
                || terrainEditing != null && terrainEditing.getController().isDirty();
    }

    public EditingSession getEditing() {
        if (editingSession == null) {
            if (editingClientRoot == null || editingBackend == null) {
                throw new IllegalStateException("Map editing is unavailable for this session.");
            }
            var rendering = editingWorld.rendering();
            editingAssets = new EditingAssetResolver(
                    editingClientRoot,
                    new Options(rendering.dxtMipCodecEnabled(), rendering.dxtMipMinSize()),
                    rendering.textureQuality(),
                    rendering.impostorDistanceMultiplier());
            var resolver = editingAssets;
            effects.setSources(resolver::readEffect);
            var surfaceReads = Executors.newSingleThreadExecutor(work -> {
                var thread = new Thread(work, "cruma-surface-preview");
                thread.setDaemon(true);
                return thread;
            });
            editingSurfaceReads = surfaceReads;
            editingSession = new EditingSession(editingClientRoot, new Preview() {
                @Override
                public boolean accepts(String sourcePath) {
                    return editingBackend.canPreviewActorTransform(sourcePath);
                }

                @Override
                public void apply(Map<String, float[]> deltas) {
                    editingBackend.previewActorTransformDeltas(deltas);
                }

                @Override
                public boolean isMoving(String sourcePath) {
                    return editingBackend.isMovingPlacement(sourcePath);
                }

                @Override
                public @Nullable String describeMotion(String sourcePath, List<CarriedActor> carried) {
                    var text = swings.describe(sourcePath, carried);
                    return text != null ? text : swings.describeEffect(sourcePath);
                }

                @Override
                public Map<String, L2Swing.Kind> getCarried(String carrier) {
                    return swings.getCarried(carrier);
                }

                @Override
                public boolean holdsMapOf(String actorPath) {
                    if (editingUnloadedMaps != null && isEditingMapUnloaded(actorPath)) {
                        return false;
                    }
                    var separator = actorPath.indexOf('.');
                    return editingResidentMaps == null
                            || separator > 0 && editingResidentMaps.contains(actorPath.substring(0, separator));
                }

                @Override
                public void place(Map<String, ActorTransform> placements) {
                    editingBackend.previewMovingPlacements(placements);
                }

                @Override
                public void applyChanges(
                        Map<String, float[]> updates, Set<String> restores, Map<String, float[]> allDeltas) {
                    if ((editingUnloadedMaps == null || editingUnloadedMaps.isEmpty()) && editingHidden.isEmpty()) {
                        editingBackend.previewActorTransformDeltaChanges(updates, restores);
                        return;
                    }
                    var resident = new LinkedHashMap<String, float[]>();
                    updates.forEach((path, delta) -> {
                        if (!isEditingMapUnloaded(path) && !editingHidden.contains(path)) {
                            resident.put(path, delta);
                        }
                    });
                    var restored = new HashSet<String>();
                    for (var path : restores) {
                        if (!editingHidden.contains(path)) {
                            restored.add(path);
                        }
                    }
                    editingBackend.previewActorTransformDeltaChanges(resident, restored);
                }

                @Override
                public void rebuild(Rebuild change) {
                    change = standEditedEffects(change);
                    change = standEditedPawns(change);
                    if (change.affectedActors().isEmpty()) {
                        return;
                    }
                    change = withCarriedMeshes(change);
                    var affected = new HashSet<String>();
                    for (var actor : change.affectedActors()) {
                        if (!editingHidden.contains(actor)
                                && (editingUnloadedMaps == null || !isEditingMapUnloaded(actor))) {
                            affected.add(actor);
                        }
                    }
                    if (!affected.isEmpty()) {
                        var replacements = editingAssets.rebuild(new Rebuild(
                                change.documents(),
                                change.baselines(),
                                affected,
                                change.assetPackages(),
                                change.lighting()));
                        forgetDeletedMeshes(replacements);
                        editingBackend.replaceEditorActors(replacements);
                    }
                }

                @Override
                public Map<String, List<Entity>> load(Rebuild change) {
                    return resolver.rebuild(change);
                }

                @Override
                public void replace(Map<String, List<Entity>> actors) {
                    var shown = new LinkedHashMap<String, List<Entity>>();
                    var unloaded = editingUnloadedMaps != null && !editingUnloadedMaps.isEmpty();
                    actors.forEach((actor, entities) -> {
                        if (!(unloaded && isEditingMapUnloaded(actor)) && !editingHidden.contains(actor)) {
                            shown.put(actor, entities);
                        }
                    });
                    if (!shown.isEmpty()) {
                        editingBackend.replaceEditorActors(shown);
                    }
                }

                @Override
                public void showLight(String map, Result light) {
                    editingLightmaps.offer(map, light.lightmaps(), light.saved());
                    for (var terrain : light.terrain()) {
                        if (terrain.rgb() != null || terrainEditing != null) {
                            getTerrainEditing()
                                    .previewSteadyLight(
                                            terrain.map(),
                                            terrain.owner(),
                                            terrain.info(),
                                            terrain.samples(),
                                            terrain.rgb());
                        }
                    }
                }

                @Override
                public LoadedSurfaces loadSurfaces(String map, SurfacePreview surfaces) {
                    var packages = surfaces.assetPackages();
                    var overlay = surfaces.overlay();
                    var overlayBody = surfaces.overlayBody();
                    List<Entity> drawnAlone = overlay == null
                            ? null
                            : overlay.isEmpty() || overlayBody == null
                                    ? List.of()
                                    : resolver.loadBspSurfaces(map, overlayBody, packages, overlay);
                    return new LoadedSurfaces(resolver.loadBsp(map, surfaces.layoutBody(), packages), drawnAlone);
                }

                @Override
                public SurfaceShow showSurfaces(String map, SurfacePreview surfaces, LoadedSurfaces loaded) {
                    if (!isSurfaceMapDrawn(map)) {
                        return SurfaceShow.NOTHING;
                    }
                    return EditingSurfaceView.show(editingBackend, editingMaps.getTile(map), surfaces, loaded);
                }

                @Override
                public boolean isSurfaceMapDrawn(String map) {
                    return editingUnloadedMaps == null || !editingUnloadedMaps.contains(map);
                }

                @Override
                public Executor getSurfaceExecutor() {
                    return surfaceReads;
                }

                @Override
                public int @Nullable [] getMappingSize(String material, Map<String, Path> assetPackages) {
                    return resolver.getMappingSize(material, assetPackages);
                }

                @Override
                public void hide(Set<String> actors) {
                    var objects = editingBackend.getEditorActorObjects(actors);
                    hideEditorActors(actors);
                    editingHidden.addAll(actors);
                    for (var object : objects) {
                        editingHiddenObjects
                                .computeIfAbsent(object.actorName(), ignored -> new ArrayList<>())
                                .add(object);
                    }
                    editingHiddenRevision++;
                }

                @Override
                public void show(Set<String> actors) {
                    effects.setHidden(actors, false);
                    editingHidden.removeAll(actors);
                    editingHiddenObjects.keySet().removeAll(actors);
                    editingHiddenRevision++;
                    mapPawns.setEditorHidden(pawnsOf(actors), false);
                }
            });
            var session = editingSession;
            effects.setMapPlaces(session::getSourceTransform);
            editingSession.setMapFiles(editingMaps);
            editingSession.setTextureImporter(editingAssets::importTexture);
            editingSession.enableLightingPreview();
            publishEditingMaps();
            if (editingRecoverySettings != null && editingRecoverySettings.recoveryEnabled()) {
                editingRecovery = new EditingRecovery(
                        editingSession,
                        editingRecoverySettings.recoveryDirectory(),
                        editingRecoverySettings.autosaveDebounceMillis(),
                        editingRecoverySettings.checkpointCommands(),
                        editingRecoverySettings.retainedGenerations(),
                        editingRecoverySettings.maximumMegabytes() * 1024L * 1024L,
                        this::restoreRecoveredPreview);
                editingRecovery.configureTerrain(
                        () -> terrainEditing == null ? null : terrainEditing.getController(),
                        () -> getTerrainEditing().getController());
            }
        }
        return editingSession;
    }

    public void onEditorMapEvicted(String tile) {
        var mapName = editingMaps.getSource(tile);
        if (terrainEditing != null) {
            terrainEditing.mapEvicted(mapName);
        }
        editingLightmaps.mapEvicted(mapName);
        if (editingResidentMaps != null) {
            editingResidentMaps.remove(mapName);
            publishEditingMaps();
        }
        if (editingSession == null) {
            return;
        }
        if (editingUnloadedMaps == null) {
            editingUnloadedMaps = new HashSet<>();
        }
        editingUnloadedMaps.add(mapName);
    }

    public void restoreEditedMapPreview(String tile) {
        var mapName = editingMaps.getSource(tile);
        if (terrainEditing != null && !restoringEditedMap) {
            terrainEditing.mapLoaded(mapName);
        }
        if (!restoringEditedMap) {
            editingLightmaps.mapLoaded(mapName);
        }
        if (editingResidentMaps != null) {
            editingResidentMaps.add(mapName);
            publishEditingMaps();
        }
        if (editingSession == null || restoringEditedMap) {
            return;
        }
        if (editingUnloadedMaps != null) {
            editingUnloadedMaps.remove(mapName);
        }
        restoringEditedMap = true;
        try {
            editingSession.restoreMapPreview(mapName);
            var hiddenHere = new LinkedHashSet<String>();
            for (var actor : editingHidden) {
                if (actor.startsWith(mapName + '.')) {
                    hiddenHere.add(actor);
                }
            }
            if (!hiddenHere.isEmpty()) {
                hideEditorActors(hiddenHere);
            }
        } finally {
            restoringEditedMap = false;
        }
    }

    public Set<String> getEditingResidentMaps() {
        return editingResidentMaps == null ? Set.of() : Set.copyOf(editingResidentMaps);
    }

    public List<DebugSceneObject> getEditingHiddenObjects() {
        var result = new ArrayList<DebugSceneObject>();
        editingHiddenObjects.values().forEach(result::addAll);
        return result;
    }

    public long getEditingHiddenRevision() {
        return editingHiddenRevision;
    }

    public @Nullable Editing getEditingSettings() {
        return editingRecoverySettings;
    }

    public void restoreEditedSurfaces() {
        if (editingSession != null) {
            editingSession.restoreSurfacePreviews();
        }
    }

    public void closeEditing() {
        if (editingSurfaceReads != null) {
            if (editingSession != null) {
                editingSession.awaitSurfaceReads();
            }
            editingSurfaceReads.shutdown();
            editingSurfaceReads = null;
        }
        if (editingSession != null) {
            editingSession.closeExport();
            editingSession.closeLightingPreview();
            editingSession.closeGroundProbe();
            editingLightmaps.clear();
        }
        if (terrainEditing != null) {
            terrainEditing.stopForRecovery();
        }
        if (editingRecovery != null) {
            try {
                editingRecovery.close();
            } catch (IOException failure) {
                LogManager.getLogger(RenderingContext.class)
                        .error("Editor recovery could not flush; previous valid generations were retained", failure);
            }
        }
        editingRecovery = null;
        if (terrainEditing != null) {
            terrainEditing.close();
        }
        terrainEditing = null;
        if (editingAssets != null) {
            editingAssets.close();
        }
        editingAssets = null;
        effects.setSources(EditorEffectStage.Sources.NONE);
        editingSession = null;
        effects.clear();
        effects.setMapPlaces(null);
        mapPawns.clearEditor();
        editingHidden.clear();
        editingHiddenObjects.clear();
        editingHiddenRevision++;
        editingUnloadedMaps = null;
        editingResidentMaps = null;
        restoringEditedMap = false;
    }

    private void publishEditingMaps() {
        if (editingSession != null && editingResidentMaps != null) {
            editingSession.setLoadedMaps(editingResidentMaps);
        }
    }

    private Rebuild withCarriedMeshes(Rebuild change) {
        var affected = new LinkedHashSet<>(change.affectedActors());
        affected.addAll(swings.getCarriedMeshes(change.affectedActors()));
        var baselines = new LinkedHashMap<>(change.baselines());
        for (var actor : affected) {
            if (editingBackend.isMovingPlacement(actor)) {
                baselines.remove(actor);
            }
        }
        return new Rebuild(change.documents(), baselines, affected, change.assetPackages(), change.lighting());
    }

    private void forgetDeletedMeshes(Map<String, List<Entity>> replacements) {
        replacements.forEach((actor, entities) -> {
            if (swings.isMoving(actor) && entities.stream().allMatch(Entity::isWireframe)) {
                swings.forget(actor);
            }
        });
    }

    private Rebuild standEditedEffects(Rebuild change) {
        Set<String> others = null;
        var placed = new LinkedHashMap<String, ActorTransform>();
        var gone = new ArrayList<String>();
        var back = new ArrayList<String>();
        for (var actor : change.affectedActors()) {
            var document = change.documents().get(EditingSession.mapOfPath(actor));
            if (document == null || !document.isEmitter(actor) && !effects.hasEffect(actor) && !effects.isCopy(actor)) {
                continue;
            }
            if (others == null) {
                others = new LinkedHashSet<>(change.affectedActors());
            }
            others.remove(actor);
            var record = document.findActor(actor).orElse(null);
            if (record == null && effects.isCopy(actor) && document.getDeletedActor(actor) == null) {
                effects.forgetCopy(actor);
            } else if (record == null) {
                gone.add(actor);
            } else {
                if (record.created()) {
                    effects.addCopy(
                            actor, record.templateSourcePath(), record.transform(), document.getCarrierOf(actor));
                }
                back.add(actor);
                placed.put(actor, record.transform());
            }
        }
        if (others == null) {
            return change;
        }
        effects.setRemoved(gone, true);
        effects.setRemoved(back, false);
        effects.place(placed);
        return new Rebuild(change.documents(), change.baselines(), others, change.assetPackages(), change.lighting());
    }

    private Rebuild standEditedPawns(Rebuild change) {
        Set<String> others = null;
        for (var actor : change.affectedActors()) {
            if (!EditorUi.isPawn(actor) && !mapPawns.hasPawn(actor) && !mapPawns.isPawnClassName(actor)) {
                continue;
            }
            var document = change.documents().get(EditingSession.mapOfPath(actor));
            var record = document == null ? null : document.findActor(actor).orElse(null);
            var deleted = document == null || record != null ? null : document.getDeletedActor(actor);
            var known = record != null ? record : deleted;
            var pawn = known != null ? known.pawn() : mapPawns.hasPawn(actor);
            if (!pawn) {
                continue;
            }
            if (others == null) {
                others = new LinkedHashSet<>(change.affectedActors());
            }
            others.remove(actor);
            if (document == null) {
                continue;
            }
            if (record == null) {
                mapPawns.setEditorRemoved(actor, true);
                continue;
            }
            if (record.created()) {
                var template = findSavedPawn(record.templateSourcePath());
                var baseline = change.baselines().get(actor);
                if (template != null) {
                    mapPawns.addEditorPawn(placed(template, actor, baseline != null ? baseline : record.transform()));
                }
            }
            mapPawns.setEditorRemoved(actor, false);
        }
        return others == null
                ? change
                : new Rebuild(
                        change.documents(), change.baselines(), others, change.assetPackages(), change.lighting());
    }

    private @Nullable MapPawn findSavedPawn(String path) {
        var pawn = mapPawns.findSavedPawn(path);
        if (pawn != null) {
            return pawn;
        }
        for (var saved : scene.getMapPawns().getOrDefault(EditingSession.mapOfPath(path), List.of())) {
            if (saved.path().equals(path)) {
                return saved;
            }
        }
        return null;
    }

    private Set<String> pawnsOf(Collection<String> actors) {
        var pawns = new HashSet<String>();
        for (var actor : actors) {
            if (mapPawns.hasPawn(actor)) {
                pawns.add(actor);
            }
        }
        return pawns;
    }

    private void hideEditorActors(Set<String> actors) {
        mapPawns.setEditorHidden(pawnsOf(actors), true);
        effects.setHidden(actors, true);
        var removed = new LinkedHashMap<String, List<Entity>>();
        for (var actor : actors) {
            if (editingUnloadedMaps == null || !isEditingMapUnloaded(actor)) {
                removed.put(actor, List.of());
            }
        }
        if (!removed.isEmpty()) {
            editingBackend.replaceEditorActors(removed);
        }
    }

    private boolean isEditingMapUnloaded(String actor) {
        var separator = actor.indexOf('.');
        return separator > 0 && editingUnloadedMaps.contains(actor.substring(0, separator));
    }

    private void restoreRecoveredPreview() {
        editingBackend.clearEditorPreview();
        effects.clear();
        editingUnloadedMaps = new HashSet<>();
        var resident = editingResidentMaps;
        if (resident == null) {
            resident = new HashSet<>();
            for (var object : editingBackend.getDebugSceneObjects()) {
                resident.add(editingMaps.getSource(object.owner()));
            }
        }
        for (var map : editingSession.getOpenedMaps()) {
            if (!resident.contains(map)) {
                editingUnloadedMaps.add(map);
            }
        }
        for (var map : editingSession.getOpenedMaps()) {
            if (resident.contains(map)) {
                editingSession.restoreMapPreview(map);
            }
        }
        inspection.select(null);
    }

    private static MapPawn placed(MapPawn template, String path, ActorTransform transform) {
        var location = transform.location();
        var rotation = transform.rotation();
        var scale = transform.drawScale3d();
        var size = transform.drawScale();
        var pivot = transform.prePivot();
        return new MapPawn(
                path,
                template.mesh(),
                template.skins(),
                new Vec3(location.x(), location.y(), location.z()),
                new URotator(rotation.pitch(), rotation.yaw(), rotation.roll()),
                new Vec3(size * scale.x(), size * scale.y(), size * scale.z()),
                new Vec3(pivot.x(), pivot.y(), pivot.z()),
                template.animations(),
                template.castsShadow(),
                template.tag(),
                template.script());
    }
}
