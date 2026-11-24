package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import java.util.List;

public final class SceneClearance {
    public static final float LIMIT = 16_384f;

    static final float SMALL_STEP = 8f;

    static final float STEP_SHARE = .05f;

    static final long MOVING_SCENE_NANOS = 100_000_000L;

    static final float GROWTH_SHARE = .25f;

    static final float GROWTH_ROOM = 64f;

    static final float MODEL_REACH = 3f;

    static final float MODEL_ROOM = 128f;

    @FunctionalInterface
    public interface Measure {
        float getDistance(float x, float y, float z, float limit);
    }

    private boolean kept;
    private float x;
    private float y;
    private float z;
    private float measured;
    private long revision;
    private long measuredNanos;

    public static float ofModels(List<ModelInstance> models, float eyeX, float eyeY, float eyeZ, float clear) {
        var best = clear;
        for (var instance : models) {
            var matrix = instance.matrix();
            var placed = matrix != null && matrix.length >= 15;
            var dx = (placed ? matrix[12] : instance.x()) - eyeX;
            var dy = (placed ? matrix[13] : instance.y()) - eyeY;
            var dz = (placed ? matrix[14] : instance.z()) - eyeZ;
            var model = instance.model();
            var reach =
                    (model.height() + Math.abs(model.lowest())) * Math.abs(instance.scale()) * MODEL_REACH + MODEL_ROOM;
            var distance = (float) Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz) - reach;
            if (distance < best) {
                best = distance;
                if (!(best > 0f)) {
                    return 0f;
                }
            }
        }
        return best;
    }

    public float at(float eyeX, float eyeY, float eyeZ, long revision, boolean moving, long nanos, Measure measure) {
        var limit = LIMIT;
        if (kept && revision == this.revision) {
            var dx = eyeX - x;
            var dy = eyeY - y;
            var dz = eyeZ - z;
            var step = (float) Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            var fresh = !moving || nanos - measuredNanos < MOVING_SCENE_NANOS;
            if (fresh && step <= Math.max(SMALL_STEP, measured * STEP_SHARE)) {
                return Math.max(0f, measured - step);
            }
            limit = Math.min(LIMIT, measured + step + Math.max(GROWTH_ROOM, measured * GROWTH_SHARE));
        }
        var distance = measure.getDistance(eyeX, eyeY, eyeZ, limit);
        measured = Float.isNaN(distance) ? 0f : Math.clamp(distance, 0f, limit);
        x = eyeX;
        y = eyeY;
        z = eyeZ;
        this.revision = revision;
        measuredNanos = nanos;
        kept = true;
        return measured;
    }
}
