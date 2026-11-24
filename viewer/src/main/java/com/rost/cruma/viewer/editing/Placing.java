package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.jetbrains.annotations.Nullable;

final class Placing {
    private Placing() {}

    record Options(
            boolean alignToNormal,
            boolean randomYaw,
            boolean randomScale,
            float minScale,
            float maxScale,
            boolean snapToGrid,
            float grid) {}

    static ActorTransform transform(
            Vector3 point, float @Nullable [] normal, float @Nullable [] bounds, Options options, Random random) {
        var x = point.x();
        var y = point.y();
        if (options.snapToGrid() && options.grid() > 0) {
            x = Math.round(x / options.grid()) * options.grid();
            y = Math.round(y / options.grid()) * options.grid();
        }
        var yaw = random.nextInt(65536);
        var factor = options.minScale() + random.nextFloat() * (options.maxScale() - options.minScale());
        var t = new ActorTransform(
                new Vector3(x, y, point.z()),
                new Rotator(0, options.randomYaw() ? yaw : 0, 0),
                options.randomScale() ? factor : 1,
                Vector3.ONE,
                Vector3.ZERO);
        if (options.alignToNormal() && normal != null) {
            t = EditorTransforms.alignedTo(t, normal[0], normal[1], normal[2]);
        }
        return bounds == null ? t : standing(t, bounds, point.z());
    }

    static ActorTransform standing(ActorTransform t, float[] bounds, float surface) {
        var lowest = Float.POSITIVE_INFINITY;
        for (var corner : corners(t, bounds)) {
            lowest = Math.min(lowest, corner[2]);
        }
        var p = t.location();
        var z = p.z() + surface - lowest;
        return new ActorTransform(
                new Vector3(p.x(), p.y(), z), t.rotation(), t.drawScale(), t.drawScale3d(), t.prePivot());
    }

    static float[][] corners(ActorTransform t, float[] bounds) {
        var m = EditorTransforms.modelMatrix(t);
        var result = new float[8][];
        for (var i = 0; i < 8; i++) {
            var x = (i & 1) == 0 ? bounds[0] : bounds[3];
            var y = (i & 2) == 0 ? bounds[1] : bounds[4];
            var z = (i & 4) == 0 ? bounds[2] : bounds[5];
            result[i] = new float[] {
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14]
            };
        }
        return result;
    }

    static final class Stroke {
        private final float spacing;
        private final float jitter;
        private final Random random;
        private boolean started;
        private float lastX;
        private float lastY;
        private float lastZ;

        Stroke(float spacing, float jitter, long seed) {
            this.spacing = Math.max(1, spacing);
            this.jitter = Math.clamp(jitter, 0, 1);
            random = new Random(seed);
        }

        List<float[]> add(float x, float y, float z) {
            var result = new ArrayList<float[]>();
            if (!started) {
                started = true;
                lastX = x;
                lastY = y;
                lastZ = z;
                result.add(jittered(x, y, z));
                return result;
            }
            var distance = (float) Math.hypot(x - lastX, y - lastY);
            while (distance >= spacing) {
                var t = spacing / distance;
                lastX += (x - lastX) * t;
                lastY += (y - lastY) * t;
                lastZ += (z - lastZ) * t;
                result.add(jittered(lastX, lastY, lastZ));
                distance = (float) Math.hypot(x - lastX, y - lastY);
            }
            return result;
        }

        private float[] jittered(float x, float y, float z) {
            var angle = random.nextDouble() * Math.PI * 2;
            var length = random.nextFloat() * jitter * spacing;
            return new float[] {x + (float) Math.cos(angle) * length, y + (float) Math.sin(angle) * length, z};
        }
    }
}
