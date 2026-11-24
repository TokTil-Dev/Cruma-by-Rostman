package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.TerrainAlphaAtlas;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.UnrealLoader.DecorationDensity;
import com.rost.cruma.core.world.UnrealLoader.DecorationGround;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.system.System;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public interface ViewerRenderBackend extends System {
    int MARKERS = 2;

    int RAY_POINTS = 256;

    String EDITOR_SURFACES = ".CrumaSurfaces";

    SceneUploadSink getUploads();

    ViewerUiRenderer getUiRenderer();

    ReadbackTicket requestReadback(ReadbackRequest request);

    default boolean isReadbackOriginBottomLeft() {
        return false;
    }

    default void onCameraRestored() {}

    boolean hasPendingWork();

    default void setInterfaceOnly(boolean only) {}

    default boolean isInterfaceOnly() {
        return false;
    }

    default boolean isBenchmarkComplete() {
        return false;
    }

    RenderStats getStats();

    default long getDebugSceneRevision() {
        return 0;
    }

    default long getDebugSceneTopologyRevision() {
        return getDebugSceneRevision();
    }

    default @Nullable Set<String> getEditorTopologyActorsSince(long since) {
        return null;
    }

    default @Nullable Bounds3f getSavedActorBounds(String actor) {
        return null;
    }

    default List<DebugSceneObject> getEditorActorObjects(Set<String> actors) {
        return getDebugSceneObjects().stream()
                .filter(object -> actors.contains(object.actorName()))
                .toList();
    }

    default List<DebugSceneObject> getDebugSceneObjects() {
        return List.of();
    }

    default int getDebugSceneObjectGroups() {
        return 1;
    }

    default List<DebugSceneObject> getDebugSceneObjects(int group) {
        return getDebugSceneObjects();
    }

    default boolean doesDebugSceneGroupMeet(int group, Bounds3f box) {
        return true;
    }

    default DebugSceneObject refreshDebugSceneObject(DebugSceneObject selected) {
        return selected;
    }

    default boolean canPreviewActorTransform(String sourceActor) {
        return false;
    }

    default boolean isMovingPlacement(String sourceActor) {
        return false;
    }

    default void previewMovingPlacements(Map<String, ActorTransform> placements) {
        if (!placements.isEmpty()) {
            throw new UnsupportedOperationException("Previews of moving meshes are unavailable");
        }
    }

    default void previewActorTransformDeltas(Map<String, float[]> deltas) {
        if (!deltas.isEmpty()) {
            throw new UnsupportedOperationException("Actor transform previews are unavailable");
        }
    }

    default void previewActorTransformDeltaChanges(Map<String, float[]> updates, Set<String> restores) {
        if (!updates.isEmpty() || !restores.isEmpty()) {
            throw new UnsupportedOperationException("Incremental actor transform previews are unavailable");
        }
    }

    default void clearEditorPreview() {
        previewActorTransformDeltas(Map.of());
    }

    default void replaceEditorActors(Map<String, List<Entity>> replacements) {
        if (!replacements.isEmpty()) {
            throw new UnsupportedOperationException("Actor replacement previews are unavailable");
        }
    }

    default void replaceEditorTerrain(String owner, Map<String, List<Entity>> sectors, boolean initialize) {
        throw new UnsupportedOperationException("Terrain geometry editing is unavailable");
    }

    default void replaceEditorDecorations(Map<String, List<Entity>> replacements) {}

    default boolean streamsDecorations(String owner, int layerIndex) {
        return false;
    }

    default void showDecorationSources(
            String owner, Map<Integer, DecorationDensity> densities, @Nullable DecorationGround ground) {}

    default void restoreDecorationSources(String owner) {}

    default boolean updateEditorBspSurfaces(
            String map, List<Entity> bsp, Set<Integer> surfaces, Set<Integer> collapsed) {
        return false;
    }

    default void replaceEditorBspSurfaces(String map, List<Entity> entities) {}

    default boolean updateEditorTerrain(String owner, Map<String, List<Entity>> sectors) {
        return false;
    }

    default boolean updateEditorTerrainAlpha(TerrainAlphaAtlas atlas, int x, int y, int width, int height) {
        return false;
    }

    default long updateEditorLightmap(String map, int key, Texture texels) {
        return 0;
    }

    default void clearEditorTerrain(String owner) {}

    default float pickDebugMeshEmitter(ParticleEmitter emitter, Vec3 origin, Vec3 direction, float maxDistance) {
        return -1;
    }

    default Bounds3f getDebugMeshEmitterBounds(ParticleEmitter emitter) {
        return null;
    }

    default void highlightHovered(@Nullable DebugSceneObject object, boolean wholeActor) {}

    default @Nullable Bounds3f getDebugInstanceBounds(DebugSceneObject object) {
        return null;
    }

    default void isolateEditorActors(@Nullable Set<String> actors) {}

    default @Nullable Bounds3f getSceneBounds() {
        return null;
    }

    default DebugSceneHit pickDebugSceneObject(float ndcX, float ndcY, float maxDistance, Set<Category> categories) {
        return null;
    }

    default boolean isScenePickReady(float ndcX, float ndcY, float maxDistance, Set<Category> categories) {
        return true;
    }

    default float getSceneRayDistance(float ndcX, float ndcY, float maxDistance) {
        return Float.NaN;
    }

    default float getMarkerRayDistance(int marker, float ndcX, float ndcY, float maxDistance) {
        return Float.NaN;
    }

    default float @Nullable [] getSceneRayDistances(float[] ndc, float maxDistance) {
        return null;
    }

    default @Nullable EffectPreview getEffectPreview() {
        return null;
    }

    default @Nullable EffectPreview getSceneEffects() {
        return null;
    }

    default @Nullable EffectPreview getMapPawnEffects() {
        return null;
    }
}
