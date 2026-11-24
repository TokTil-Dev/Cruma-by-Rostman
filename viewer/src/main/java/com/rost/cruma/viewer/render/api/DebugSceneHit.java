package com.rost.cruma.viewer.render.api;

public record DebugSceneHit(DebugSceneObject object, float distance) {
    public DebugSceneHit {
        if (object == null || !Float.isFinite(distance) || distance < 0f) {
            throw new IllegalArgumentException("invalid debug scene hit");
        }
    }
}
