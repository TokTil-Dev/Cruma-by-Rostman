package com.rost.cruma.viewer.ui;

public final class SceneHourHold {
    private float before = Float.NaN;

    public float getHour(double scene, float viewer) {
        if (!Double.isNaN(scene)) {
            if (Float.isNaN(before)) {
                before = viewer;
            }
            return (float) scene;
        }
        if (Float.isNaN(before)) {
            return viewer;
        }
        var back = before;
        before = Float.NaN;
        return back;
    }
}
