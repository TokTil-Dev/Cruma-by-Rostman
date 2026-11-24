package com.rost.cruma.core.world;

import org.jetbrains.annotations.Nullable;

public final class NativeLightRefresh {
    private final float interval;
    private float lastSampleHour;
    private int pending;

    public NativeLightRefresh(float interval, float lastSampleHour, int pending) {
        this.interval = interval;
        this.lastSampleHour = lastSampleHour;
        this.pending = pending;
    }

    public interface Actor {
        boolean isDeleted();

        void markStaticLightingDirty();

        @Nullable
        Light getLight();

        void clearRenderData();
    }

    public record Light(int type, boolean sunlightColor) {}

    public boolean update(double gameHour, boolean editor, boolean running, boolean environmentChanging) {
        if (!editor && running) {
            pending = interval > 0f && Math.abs(gameHour - (double) lastSampleHour) > (double) interval ? 1 : 0;
            if (pending != 0) {
                lastSampleHour = (float) gameHour;
            }
            if (environmentChanging) {
                pending = 1;
            }
        }
        return pending != 0;
    }

    public int getPending() {
        return pending;
    }

    public void setPending(int value) {
        pending = value;
    }

    public float getLastSampleHour() {
        return lastSampleHour;
    }

    public void invalidate(Actor[] levelActors) {
        if (pending == 0) {
            return;
        }
        for (var i = 2; i < levelActors.length; i++) {
            var actor = levelActors[i];
            if (actor != null && !actor.isDeleted()) {
                actor.markStaticLightingDirty();
                if (actor.getLight() != null
                        && actor.getLight().type() != 0
                        && actor.getLight().sunlightColor()) {
                    actor.clearRenderData();
                }
            }
        }
    }
}
