package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import org.jetbrains.annotations.Nullable;

public interface GuideHost {
    @Nullable
    String getOpenTool();

    @Nullable
    String getOpenTab();

    boolean hasTool(String tool);

    default @Nullable String getToolHidden(String tool) {
        return null;
    }

    void openTool(String tool);

    @Nullable
    Snapshot getView();

    void restoreView(Snapshot view);

    default @Nullable Geodata getGeodata() {
        return null;
    }

    default @Nullable OpenWorld getWorld() {
        return null;
    }

    interface OpenWorld {
        String getWorkspace();

        @Nullable
        String getWorkspaceUnavailable(String workspace);

        void showWorkspace(String workspace);

        @Nullable
        String getMapUnavailable();

        void openMap();

        @Nullable
        String getExportUnavailable();

        void exportHere();
    }

    interface Geodata {
        @Nullable
        String getUnavailable();

        @Nullable
        GeoDataAccess getCells();

        double[] getCamera();

        void show();

        void goTo(double x, double y, double z);
    }

    default boolean hasWorld() {
        return getView() != null;
    }

    default @Nullable Spawns getNpcSpawns() {
        return null;
    }

    interface Spawns {
        @Nullable
        String getUnavailable();

        @Nullable
        String showNearestMaker();

        @Nullable
        String showNearestFixedNpc();

        @Nullable
        String openNearestNpc();
    }

    default @Nullable MapEvents getMapEvents() {
        return null;
    }

    default @Nullable Areas getAreas() {
        return null;
    }

    interface Areas {
        @Nullable
        String getUnavailable();

        @Nullable
        String showNearestArea();
    }

    interface MapEvents {
        @Nullable
        String getUnavailable();

        @Nullable
        String showNearest();
    }

    default @Nullable Effects getEffectsTool() {
        return null;
    }

    interface Effects {
        @Nullable
        String getUnavailable();

        void open();

        boolean isListing();

        @Nullable
        String playExample();
    }

    default @Nullable Edit getEdit() {
        return null;
    }

    interface Edit {
        @Nullable
        String getUnavailable();

        boolean chooseTool(String tool);

        void openLibrary();

        boolean hasSelection();

        void frameSelection();

        void openSave();
    }

    default @Nullable String getNpcExportUnavailable() {
        return "Export NPC needs a game client. Open one from the start menu first.";
    }

    default void openNpcExport() {}

    default @Nullable Scenes getScenes() {
        return null;
    }

    interface Scenes {
        @Nullable
        String getDirectorUnavailable();

        void openDirector();

        @Nullable
        String getExampleUnavailable();

        @Nullable
        String playExample();
    }
}
