package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.world.UMap;

public interface SceneUploadSink {
    default void renderMap(UMap map) {
        renderMap(map, true);
    }

    void renderMap(UMap map, boolean setCamera);

    default void enqueueMap(UMap map, boolean setCamera) {
        renderMap(map, setCamera);
    }

    interface PreparedMap {
        default long getRetainedBytes() {
            return 0L;
        }

        default void release() {}
    }

    default PreparedMap prepareMap(UMap map) {
        return null;
    }

    default void enqueueMap(UMap map, boolean setCamera, PreparedMap prepared) {
        enqueueMap(map, setCamera);
    }

    default boolean enqueueMapPart(UMap map, PreparedMap prepared, long nanos) {
        enqueueMap(map, false, prepared);
        return true;
    }

    default void replaceMap(UMap map, PreparedMap prepared) {
        throw new UnsupportedOperationException("This renderer cannot load a map again in place");
    }

    void computeBakePlan();

    void applyBake();

    void buildDrawList(int cellSize);

    void clearCache();

    void clearNamedTextureCache();

    SceneUploadStats getUploadStats();

    default SceneLoadTelemetry getLoadTelemetry() {
        return SceneLoadTelemetry.empty();
    }

    default SceneCensus getSceneCensus() {
        return SceneCensus.empty();
    }
}
