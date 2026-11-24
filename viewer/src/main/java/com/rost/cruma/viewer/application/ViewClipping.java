package com.rost.cruma.viewer.application;

import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.config.CutCircle;
import com.rost.cruma.viewer.config.KeptLayers;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.CameraView;
import org.jetbrains.annotations.Nullable;

public final class ViewClipping {
    private @Nullable RestoredProjection restored;

    private record RestoredProjection(
            float far, float orthoFar, int near, int nearControl, int farControl, CameraView view) {}

    public void cameraRestored(Camera camera, Rendering controls) {
        var pose = camera.snapshot();
        if (!pose.view().isHorizontal()) {
            controls.zNear.set(pose.orthoNear());
        }
        controls.viewDistance.set(Math.round(pose.far()));
        controls.orthoCutTop = false;
        controls.orthoCutBottom = false;
        controls.frontSlice.off();
        controls.sideSlice.off();
        camera.cutHeights(null, null);
        camera.setOrthoFar(pose.orthoFar());
        restored = new RestoredProjection(
                pose.far(),
                pose.orthoFar(),
                pose.orthoNear(),
                controls.zNear.get(),
                controls.viewDistance.get(),
                pose.view());
    }

    public void beginFrame(Camera camera, Rendering controls) {
        if (restored != null
                && (restored.nearControl() != controls.zNear.get()
                        || restored.farControl() != controls.viewDistance.get()
                        || restored.view() != camera.getView()
                        || controls.orthoCutTop
                        || controls.orthoCutBottom
                        || controls.frontSlice.on()
                        || controls.sideSlice.on())) {
            restored = null;
        }
        camera.setZNear(restored == null ? controls.zNear.get() : restored.near());
        camera.setFar(restored == null ? Math.max(1, controls.viewDistance.get()) : restored.far());
    }

    public void cut(Camera camera, Rendering controls, @Nullable CutCircle circle) {
        var view = camera.getView();
        switch (view) {
            case TOP ->
                camera.cutHeights(
                        controls.orthoCutTop ? controls.orthoTop[0] : null,
                        controls.orthoCutBottom ? controls.orthoBottom[0] : null,
                        KeptLayers.of(controls.orthoKeepTerrain, controls.orthoKeepStaticMeshes, controls.orthoKeepCsg),
                        circle);
            case FRONT, SIDE -> {
                var slice = controls.getSlice(view);
                camera.cutDepths(slice.cutNear ? slice.near[0] : null, slice.cutFar ? slice.far[0] : null);
            }
            case PERSPECTIVE -> camera.cutHeights(null, null);
        }
        if (restored != null) {
            camera.setOrthoFar(restored.orthoFar());
        }
    }
}
