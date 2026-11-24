package com.rost.cruma.viewer.render;

import org.jetbrains.annotations.Nullable;

public final class LightGlows {
    private @Nullable LightGlow pending;
    private @Nullable LightGlow shown;
    private volatile boolean drawn;

    public void begin() {
        pending = null;
    }

    public void set(LightGlow glow) {
        pending = glow;
    }

    public synchronized void publish() {
        shown = pending;
    }

    public synchronized void clear() {
        pending = null;
        shown = null;
    }

    public synchronized @Nullable LightGlow getShown() {
        return shown;
    }

    public boolean isDrawn() {
        return drawn;
    }

    public void setDrawn(boolean value) {
        drawn = value;
    }
}
