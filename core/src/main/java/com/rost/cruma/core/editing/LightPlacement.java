package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Vector3;
import java.util.Objects;

public record LightPlacement(Vector3 location, float radius) {
    public static final float MAX_RADIUS = 255;

    public LightPlacement {
        Objects.requireNonNull(location);
        if (!Float.isFinite(radius)) {
            throw new IllegalArgumentException("Light radius must be finite");
        }
    }

    public float reach() {
        return 25 * (radius + 1);
    }
}
