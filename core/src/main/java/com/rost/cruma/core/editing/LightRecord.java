package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Vector3;

public record LightRecord(
        String sourcePath,
        int exportIndex,
        String className,
        Vector3 location,
        float radius,
        LightColor color,
        LightColor sourceColor,
        Vector3 sourceLocation,
        float sourceRadius,
        boolean editable,
        String restriction,
        boolean created,
        String template,
        LightShape shape,
        LightShape sourceShape) {
    public float reach() {
        return 25 * (radius + 1);
    }

    public LightPlacement placement() {
        return new LightPlacement(location, radius);
    }

    public LightPlacement sourcePlacement() {
        return new LightPlacement(sourceLocation, sourceRadius);
    }

    public boolean isChanged() {
        return created || !color.equals(sourceColor) || isMoved() || isTurned();
    }

    public boolean isMoved() {
        return !location.equals(sourceLocation) || Float.compare(radius, sourceRadius) != 0;
    }

    public boolean isTurned() {
        return !shape.equals(sourceShape);
    }
}
