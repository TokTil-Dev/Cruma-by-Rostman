package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnTriangles;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class SpawnOverlayState {
    public final SpawnTriangles fills = new SpawnTriangles();

    public final SpawnTriangles bodies = new SpawnTriangles();

    public final GeoOverlayLines lines = new GeoOverlayLines();

    public final SpawnTriangles areaFills = new SpawnTriangles();

    public final GeoOverlayLines areaLines = new GeoOverlayLines();

    public final GeoOverlayLines marks = new GeoOverlayLines();

    public final GeoOverlayLines editorLines = new GeoOverlayLines();

    public final GeoOverlayLines editorEdges = new GeoOverlayLines();

    public final LightGlows editorLight = new LightGlows();

    public volatile boolean marksHidden;

    public volatile boolean visible;

    public volatile boolean areasVisible;

    public volatile boolean wanted;

    public final ClientFloors floors = new ClientFloors();

    public volatile boolean sunShadows;

    public volatile List<ModelInstance> models = List.of();

    public volatile List<ModelInstance> staged = List.of();

    public volatile List<ModelInstance> cast = List.of();

    public volatile List<ModelInstance> pawns = List.of();

    public volatile List<ModelInstance> pawnShadows = List.of();

    public record ModelInstance(
            SpawnModelData model,
            float x,
            float y,
            float z,
            float yaw,
            float scale,
            @Nullable LivePose pose,
            float @Nullable [] matrix) {
        public ModelInstance(SpawnModelData model, float x, float y, float z, float yaw, float scale) {
            this(model, x, y, z, yaw, scale, null, null);
        }

        public ModelInstance(
                SpawnModelData model, float x, float y, float z, float yaw, float scale, @Nullable LivePose pose) {
            this(model, x, y, z, yaw, scale, pose, null);
        }
    }

    public static final class LivePose {
        private volatile float[] vertices;

        public LivePose(float[] vertices) {
            this.vertices = vertices;
        }

        public void set(float[] vertices) {
            this.vertices = vertices;
        }

        public float[] getVertices() {
            return vertices;
        }
    }

    public List<ModelInstance> getDrawnModels() {
        var stand = staged;
        var actors = cast;
        var placed = pawns;
        if (stand.isEmpty() && actors.isEmpty() && placed.isEmpty()) {
            return visible ? models : List.of();
        }
        if ((!visible || models.isEmpty()) && actors.isEmpty() && placed.isEmpty()) {
            return stand;
        }
        var all = new ArrayList<ModelInstance>(models.size() + stand.size() + actors.size() + placed.size());
        if (visible) {
            all.addAll(models);
        }
        all.addAll(stand);
        all.addAll(actors);
        all.addAll(placed);
        return all;
    }

    public List<ModelInstance> getShadowCasters() {
        var npcs = visible ? models : List.<ModelInstance>of();
        var placed = pawnShadows;
        if (placed.isEmpty()) {
            return npcs;
        }
        if (npcs.isEmpty()) {
            return placed;
        }
        var all = new ArrayList<ModelInstance>(npcs.size() + placed.size());
        all.addAll(npcs);
        all.addAll(placed);
        return all;
    }

    public void clear() {
        fills.clear();
        bodies.clear();
        lines.clear();
        models = List.of();
    }

    public void clearAreas() {
        areaFills.clear();
        areaLines.clear();
    }
}
