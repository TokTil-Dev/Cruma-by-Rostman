package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.util.List;

public record EmitterVisibility(
        float limitMin,
        float limitMax,
        float interpRange,
        EmitterResetPolicy resetPolicy,
        float ignoredRange,
        boolean disableFogging,
        List<int[]> gameEvents,
        boolean rotating,
        URotator baseRotation,
        URotator rotationPerSecond,
        boolean selfRotation,
        List<EmitterSound> sounds,
        ZoneRender zoneRender) {
    public static final EmitterVisibility UNLIMITED = new EmitterVisibility(0f, 0f, 0f);

    public EmitterVisibility {
        resetPolicy = resetPolicy == null ? EmitterResetPolicy.NONE : resetPolicy;
        gameEvents = gameEvents == null ? List.of() : List.copyOf(gameEvents);
        baseRotation = baseRotation == null ? new URotator(0, 0, 0) : baseRotation;
        rotationPerSecond = rotationPerSecond == null ? new URotator(0, 0, 0) : rotationPerSecond;
        sounds = sounds == null ? List.of() : List.copyOf(sounds);
        zoneRender = zoneRender == null ? ZoneRender.EVERY_STATE : zoneRender;
    }

    public EmitterVisibility(
            float limitMin,
            float limitMax,
            float interpRange,
            EmitterResetPolicy resetPolicy,
            float ignoredRange,
            boolean disableFogging,
            List<int[]> gameEvents,
            boolean rotating,
            URotator baseRotation,
            URotator rotationPerSecond,
            boolean selfRotation,
            List<EmitterSound> sounds) {
        this(
                limitMin,
                limitMax,
                interpRange,
                resetPolicy,
                ignoredRange,
                disableFogging,
                gameEvents,
                rotating,
                baseRotation,
                rotationPerSecond,
                selfRotation,
                sounds,
                ZoneRender.EVERY_STATE);
    }

    public EmitterVisibility(float limitMin, float limitMax, float interpRange) {
        this(limitMin, limitMax, interpRange, EmitterResetPolicy.NONE);
    }

    public EmitterVisibility(float limitMin, float limitMax, float interpRange, EmitterResetPolicy resetPolicy) {
        this(limitMin, limitMax, interpRange, resetPolicy, 0f, false, List.of(), false, null, null, List.of());
    }

    public EmitterVisibility(
            float limitMin,
            float limitMax,
            float interpRange,
            EmitterResetPolicy resetPolicy,
            float ignoredRange,
            boolean disableFogging,
            List<int[]> gameEvents,
            boolean rotating,
            URotator baseRotation,
            URotator rotationPerSecond,
            List<EmitterSound> sounds) {
        this(
                limitMin,
                limitMax,
                interpRange,
                resetPolicy,
                ignoredRange,
                disableFogging,
                gameEvents,
                rotating,
                baseRotation,
                rotationPerSecond,
                false,
                sounds);
    }

    public boolean isHiddenByGameEvents(MapEventState state) {
        return state != null && (state.hides(gameEvents) || !zoneRender.shows(state));
    }

    public boolean isLimited() {
        return limitMin != 0f || limitMax != 0f;
    }

    public float nearBound() {
        return Math.min(limitMin, limitMax);
    }

    public float farBound() {
        return Math.max(limitMin, limitMax);
    }

    public float opacityRatioAt(Vec3 actorPos, Vec3 cameraPos) {
        var dx = actorPos.getX() - cameraPos.getX();
        var dy = actorPos.getY() - cameraPos.getY();
        return opacityRatioAtDistanceSquared(dx * dx + dy * dy);
    }

    public boolean isOutsideWindow(float distanceSquared) {
        if (!isLimited()) {
            return false;
        }
        var distance = (float) Math.sqrt(distanceSquared);
        return distance < nearBound() || distance > farBound();
    }

    public float opacityRatioAtDistanceSquared(float distanceSquared) {
        if (!isLimited()) {
            return 1f;
        }
        var distance = (float) Math.sqrt(distanceSquared);
        var near = nearBound();
        var far = farBound();
        if (distance < near || distance > far) {
            return 0f;
        }
        if (interpRange <= 0f) {
            return 1f;
        }
        var toNearestEdge = Math.min(distance - near, far - distance);
        return Math.min(1f, toNearestEdge / interpRange);
    }

    public float opacityRatioForLimit(float distance, float limit) {
        if (limit <= 0f || distance > limit) {
            return 0f;
        }
        if (interpRange <= 0f) {
            return 1f;
        }
        return Math.min(1f, (limit - distance) / interpRange);
    }
}
