package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.render.ScenePlayback;

public final class ViewComparison {
    private ViewSettingsSnapshot a;
    private ViewSettingsSnapshot b;
    private boolean active;
    private boolean wasPaused;
    private String shown = "A";

    public boolean isActive() {
        return active;
    }

    public String getShown() {
        return shown;
    }

    public boolean isModified(Rendering settings) {
        return active && !ViewSettingsSnapshot.capture(settings).equals("A".equals(shown) ? a : b);
    }

    public void begin(Rendering settings, ScenePlayback playback) {
        if (active) {
            return;
        }
        a = ViewSettingsSnapshot.capture(settings);
        b = a;
        shown = "A";
        wasPaused = playback.isPaused();
        playback.setComparisonLocked(true);
        active = true;
    }

    public void save(String side, Rendering settings) {
        requireActive(side);
        if ("A".equals(side)) {
            a = ViewSettingsSnapshot.capture(settings);
        } else {
            b = ViewSettingsSnapshot.capture(settings);
        }
        shown = side;
    }

    public void show(String side, Rendering settings) {
        requireActive(side);
        ("A".equals(side) ? a : b).apply(settings);
        shown = side;
    }

    public void finish(ScenePlayback playback) {
        if (!active) {
            return;
        }
        playback.setComparisonLocked(false);
        playback.setPaused(wasPaused);
        active = false;
    }

    private void requireActive(String side) {
        if (!active || !("A".equals(side) || "B".equals(side))) {
            throw new IllegalStateException("no comparison side " + side);
        }
    }
}
