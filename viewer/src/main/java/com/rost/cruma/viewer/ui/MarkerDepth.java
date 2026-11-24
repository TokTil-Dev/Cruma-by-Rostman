package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class MarkerDepth {
    static final float DOT_RADIUS = 24f;

    static final float MIN_DOT = 4f;

    static final float MAX_DOT = 16f;

    static final float FAR_ALPHA = .4f;

    private static final float HOVERED_DOT = 1.4f;
    private static final float HALO_POINTS = 3f;
    private static final int HALO_COLOR = 0x00140E08;
    private static final float HALO_ALPHA = .78f;
    private static final float HOLLOW_RING_POINTS = 1.5f;
    private static final float MIN_HOLE = 1.5f;
    private static final float RING_POINTS = 2f;

    private MarkerDepth() {}

    public interface Spot {
        float x();

        float y();

        float dotDiameter();

        float distance();
    }

    public static float pixelsPerUnit(float scale, float heightPoints, float w) {
        return Math.abs(scale) * heightPoints * .5f / Math.max(w, 1e-3f);
    }

    public static float dotDiameter(float pixelsPerUnit) {
        return Math.clamp(2 * DOT_RADIUS * pixelsPerUnit, MIN_DOT, MAX_DOT);
    }

    public static float alpha(float distance, float farthest) {
        var share = farthest > 0 && distance > 0 ? Math.min(distance / farthest, 1f) : 0f;
        return 1f + (FAR_ALPHA - 1f) * share;
    }

    static int primitives(boolean shows) {
        return shows ? 2 : 3;
    }

    public static void mark(
            GeoOverlayLines marks,
            float x,
            float y,
            float z,
            float pixelsPerUnit,
            int color,
            boolean shows,
            boolean hovered) {
        var diameter = dotDiameter(pixelsPerUnit) * (hovered ? HOVERED_DOT : 1f);
        var alpha = (color >>> 24) / 255f * HALO_ALPHA;
        var dark = HALO_COLOR | Math.round(alpha * 255) << 24;
        marks.handle(x, y, z, dark, diameter + HALO_POINTS);
        marks.handle(x, y, z, color, diameter);
        if (!shows) {
            marks.handle(x, y, z, dark, Math.max(MIN_HOLE, diameter - 2 * HOLLOW_RING_POINTS));
        }
    }

    public static void ring(
            GeoOverlayLines marks,
            float x,
            float y,
            float z,
            float pixelsPerUnit,
            int color,
            boolean hovered,
            int order) {
        var diameter = dotDiameter(pixelsPerUnit) * (hovered ? HOVERED_DOT : 1f);
        marks.handle(x, y, z, color, diameter + HALO_POINTS + 2 * RING_POINTS * order);
    }

    public static int pick(List<? extends Spot> spots, float pointerX, float pointerY, boolean captured) {
        if (captured) {
            return -1;
        }
        var best = -1;
        var bestDistance = Float.MAX_VALUE;
        for (var index = 0; index < spots.size(); index++) {
            var spot = spots.get(index);
            var dx = spot.x() - pointerX;
            var dy = spot.y() - pointerY;
            var reach = pickRadius(spot.dotDiameter());
            if (dx * dx + dy * dy <= reach * reach && spot.distance() < bestDistance) {
                best = index;
                bestDistance = spot.distance();
            }
        }
        return best;
    }

    public static float pickRadius(float dotDiameter) {
        return Math.max(dotDiameter * .5f + 4, 8);
    }

    public static int faded(int color, float alpha) {
        return (color & 0x00ffffff) | Math.round((color >>> 24) * Math.clamp(alpha, 0f, 1f)) << 24;
    }

    public record Placed(float x, float y, float distance, float pixelsPerUnit) implements Spot {
        @Override
        public float dotDiameter() {
            return MarkerDepth.dotDiameter(pixelsPerUnit);
        }
    }

    public static final class Projector {
        private final float[] m = new float[16];
        private final float eyeX;
        private final float eyeY;
        private final float eyeZ;
        private final float forwardX;
        private final float forwardY;
        private final float forwardZ;
        private final boolean ortho;
        private final float scale;
        private final int width;
        private final int height;

        public Projector(Camera camera, int width, int height) {
            camera.copyViewProjection(m);
            var eye = camera.getPosition();
            var forward = camera.getCursorRayDirection(0, 0);
            eyeX = eye.getX();
            eyeY = eye.getY();
            eyeZ = eye.getZ();
            forwardX = forward.getX();
            forwardY = forward.getY();
            forwardZ = forward.getZ();
            ortho = camera.isOrtho();
            scale = camera.getProjectionMatrix().get(5);
            this.width = width;
            this.height = height;
        }

        public boolean isOrtho() {
            return ortho;
        }

        public float getAlpha(float distance, float farthest) {
            return ortho ? 1f : MarkerDepth.alpha(distance, farthest);
        }

        public @Nullable Placed place(float x, float y, float z) {
            var w = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (w <= 0) {
                return null;
            }
            var screenX = ((m[0] * x + m[4] * y + m[8] * z + m[12]) / w * .5f + .5f) * width;
            var screenY = (.5f - (m[1] * x + m[5] * y + m[9] * z + m[13]) / w * .5f) * height;
            var dx = x - eyeX;
            var dy = y - eyeY;
            var dz = z - eyeZ;
            var distance = ortho
                    ? dx * forwardX + dy * forwardY + dz * forwardZ
                    : (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            return new Placed(screenX, screenY, distance, pixelsPerUnit(scale, height, w));
        }
    }
}
