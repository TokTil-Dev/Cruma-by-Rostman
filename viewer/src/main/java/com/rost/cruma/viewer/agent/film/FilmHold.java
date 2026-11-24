package com.rost.cruma.viewer.agent.film;

import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import org.jetbrains.annotations.Nullable;

final class FilmHold {
    private final ViewerContext viewer;
    private final Snapshot camera;
    private final float hour;
    private final boolean geodata;
    private final boolean paused;
    private final double speed;
    private boolean given;

    FilmHold(ViewerContext viewer, @Nullable Double shown) {
        this.viewer = viewer;
        var scene = viewer.scene();
        var rendering = viewer.ui().rendering;
        camera = scene.camera.snapshot();
        hour = rendering.timeOfDay.get();
        geodata = scene.geodata.enabled;
        paused = scene.playback.isPaused();
        speed = scene.playback.getSpeed();
        if (shown != null) {
            rendering.timeOfDay.set(shown.floatValue());
        }
        scene.geodata.enabled = false;
    }

    double getHour() {
        return Math.round(viewer.ui().rendering.timeOfDay.get() * 100) / 100.0;
    }

    void giveBack() {
        if (given) {
            return;
        }
        given = true;
        var scene = viewer.scene();
        scene.gameScenes.stop();
        scene.camera.restoreSnapshot(camera);
        viewer.ui().rendering.timeOfDay.set(hour);
        scene.geodata.enabled = geodata;
        scene.playback.setSpeed(speed);
        scene.playback.setPaused(paused);
    }
}
